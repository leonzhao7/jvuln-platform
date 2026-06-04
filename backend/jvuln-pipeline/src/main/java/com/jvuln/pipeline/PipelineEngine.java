package com.jvuln.pipeline;

import com.jvuln.llm.LlmClient;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageProgress;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.pipeline.stage.Stage;
import com.jvuln.store.CveTaskRepository;
import com.jvuln.store.StageRecordRepository;
import com.jvuln.store.WorkspaceManager;
import com.jvuln.store.entity.CveTask;
import com.jvuln.store.entity.StageRecord;
import com.jvuln.store.model.CveIntelligence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class PipelineEngine {

    private static final Logger log = LoggerFactory.getLogger(PipelineEngine.class);

    private final List<Stage> stages;
    private final WorkspaceManager workspaceManager;
    private final LlmClient llmClient;
    private final CveTaskRepository taskRepo;
    private final StageRecordRepository stageRepo;
    private final Executor pipelineExecutor;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> runningTasks = new ConcurrentHashMap<>();

    public PipelineEngine(List<Stage> stages, WorkspaceManager workspaceManager,
                          LlmClient llmClient, CveTaskRepository taskRepo,
                          StageRecordRepository stageRepo,
                          @Qualifier("pipelineExecutor") Executor pipelineExecutor) {
        this.stages = stages.stream().sorted((a, b) -> a.number() - b.number()).collect(Collectors.toList());
        this.workspaceManager = workspaceManager;
        this.llmClient = llmClient;
        this.taskRepo = taskRepo;
        this.stageRepo = stageRepo;
        this.pipelineExecutor = pipelineExecutor;
    }

    public SseEmitter subscribe(String cveId) {
        SseEmitter emitter = new SseEmitter(600_000L);
        emitters.put(cveId, emitter);
        emitter.onCompletion(() -> emitters.remove(cveId));
        emitter.onTimeout(() -> emitters.remove(cveId));
        return emitter;
    }

    public boolean execute(final String cveId, final int fromStage) {
        AtomicBoolean running = runningTasks.computeIfAbsent(cveId, key -> new AtomicBoolean(false));
        if (!running.compareAndSet(false, true)) {
            String message = "Pipeline already running for " + cveId;
            log.warn(message);
            sendEvent(cveId, new StageProgress("error", 0, message));
            return false;
        }

        try {
            pipelineExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    runPipeline(cveId, fromStage);
                }
            });
            return true;
        } catch (RuntimeException e) {
            running.set(false);
            runningTasks.remove(cveId, running);
            throw e;
        }
    }

    private void runPipeline(String cveId, int fromStage) {
        log.info("Pipeline started: cveId={}, fromStage={}", cveId, fromStage);

        try {
            Path workspace = workspaceManager.initCveWorkspace(cveId);

            CveTask task = taskRepo.findByCveId(cveId).orElseThrow(() -> new RuntimeException("Task not found: " + cveId));
            task.setStatus(CveTask.TaskStatus.RUNNING);
            taskRepo.save(task);

            PipelineContext ctx = new PipelineContext(cveId, workspace, llmClient, workspaceManager);
            ctx.setFromStage(fromStage);
            ctx.setProgressCallback(buildSseCallback(cveId));

            for (Stage stage : stages) {
                boolean shouldSkip = stage.number() < fromStage && workspaceManager.isStageComplete(cveId, stage.number());
                if (shouldSkip) {
                    log.info("Stage {} skipped (checkpoint exists), loading cached data", stage.number());
                    try {
                        Object data = workspaceManager.readStageData(cveId, stage.number(), Object.class);
                        ctx.getCompletedStages().put(stage.number(),
                                StageResult.success(stage.number(), stage.name(), data));
                    } catch (Exception e) {
                        log.warn("Could not load cached stage {} data: {}", stage.number(), e.getMessage());
                    }
                    continue;
                }

                sendEvent(cveId, new StageProgress("stage_start", stage.number(), stage.name()));

                StageRecord record = getOrCreateRecord(cveId, stage);
                record.setStatus(StageRecord.StageStatus.RUNNING);
                record.setStartedAt(LocalDateTime.now());
                stageRepo.save(record);

                task.setCurrentStage(stage.number());
                taskRepo.save(task);

                try {
                    StageResult result = stage.execute(ctx);
                    ctx.getCompletedStages().put(stage.number(), result);

                    record.setStatus(result.isSuccess()
                            ? StageRecord.StageStatus.COMPLETED
                            : StageRecord.StageStatus.FAILED);
                    record.setFinishedAt(LocalDateTime.now());
                    if (!result.isSuccess()) {
                        record.setErrorMsg(result.getErrorMessage());
                    }
                    stageRepo.save(record);

                    // Enrich task metadata after Stage 1 (intelligence)
                    if (stage.number() == 1 && result.isSuccess() && result.getData() instanceof CveIntelligence) {
                        CveIntelligence intel = (CveIntelligence) result.getData();
                        if (intel.getCvss() != null) task.setCvssScore(intel.getCvss().getScore());
                        if (intel.getCweId() != null) task.setCweId(intel.getCweId());
                        if (intel.getArtifact() != null) {
                            task.setArtifact(intel.getArtifact().getGroupId() + ":" + intel.getArtifact().getArtifactId());
                        }
                        taskRepo.save(task);
                    }

                    sendEvent(cveId, new StageProgress("stage_done", stage.number(),
                            result.isSuccess() ? "completed" : "failed: " + result.getErrorMessage()));

                    if (!result.isSuccess()) {
                        log.warn("Stage {} failed: {}", stage.number(), result.getErrorMessage());
                        task.setStatus(CveTask.TaskStatus.FAILED);
                        taskRepo.save(task);
                        return;
                    }
                } catch (Exception e) {
                    log.error("Stage {} exception", stage.number(), e);
                    record.setStatus(StageRecord.StageStatus.FAILED);
                    record.setFinishedAt(LocalDateTime.now());
                    record.setErrorMsg(e.getMessage());
                    stageRepo.save(record);

                    sendEvent(cveId, new StageProgress("error", stage.number(), e.getMessage()));
                    task.setStatus(CveTask.TaskStatus.FAILED);
                    taskRepo.save(task);
                    return;
                }
            }

            task.setStatus(CveTask.TaskStatus.COMPLETED);
            taskRepo.save(task);
            sendEvent(cveId, new StageProgress("pipeline_done", 0, "All stages completed"));

        } catch (Exception e) {
            log.error("Pipeline failed for {}", cveId, e);
            taskRepo.findByCveId(cveId).ifPresent(t -> {
                t.setStatus(CveTask.TaskStatus.FAILED);
                taskRepo.save(t);
            });
            sendEvent(cveId, new StageProgress("error", 0, e.getMessage()));
        } finally {
            AtomicBoolean current = runningTasks.get(cveId);
            if (current != null) {
                current.set(false);
                runningTasks.remove(cveId, current);
            }
            SseEmitter emitter = emitters.remove(cveId);
            if (emitter != null) {
                emitter.complete();
            }
        }
    }

    private StageRecord getOrCreateRecord(String cveId, Stage stage) {
        return stageRepo.findByCveIdAndStageNum(cveId, stage.number())
                .orElseGet(() -> {
                    StageRecord r = new StageRecord();
                    r.setCveId(cveId);
                    r.setStageNum(stage.number());
                    r.setStageName(stage.name());
                    return r;
                });
    }

    private Consumer<StageProgress> buildSseCallback(String cveId) {
        return progress -> sendEvent(cveId, progress);
    }

    private void sendEvent(String cveId, StageProgress progress) {
        SseEmitter emitter = emitters.get(cveId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name(progress.getType())
                    .data(progress));
        } catch (IOException e) {
            emitters.remove(cveId);
        }
    }
}
