package com.jvuln.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.jvuln.llm.LlmAuditLogger;
import com.jvuln.llm.LlmClient;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageProgress;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.generator.ManualVulnDemoValidator;
import com.jvuln.pipeline.stage.Stage;
import com.jvuln.store.CveTaskRepository;
import com.jvuln.store.StageRecordRepository;
import com.jvuln.store.WorkspaceManager;
import com.jvuln.store.entity.CveTask;
import com.jvuln.store.entity.StageRecord;
import com.jvuln.store.model.CveIntelligence;
import com.jvuln.generator.ManualVulnDemoValidator;
import com.jvuln.llm.LlmConversationContext;
import com.jvuln.util.RequestLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final ManualVulnDemoValidator manualVulnDemoValidator;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> runningTasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancellationRequests = new ConcurrentHashMap<>();
    private final Map<String, List<StageProgress>> progressHistory = new ConcurrentHashMap<>();
    private static final int MAX_PROGRESS_EVENTS = 2000;

    public PipelineEngine(List<Stage> stages, WorkspaceManager workspaceManager,
                          LlmClient llmClient, CveTaskRepository taskRepo,
                          StageRecordRepository stageRepo,
                          @Qualifier("pipelineExecutor") Executor pipelineExecutor,
                          ManualVulnDemoValidator manualVulnDemoValidator) {
        this.stages = stages.stream().sorted((a, b) -> a.number() - b.number()).collect(Collectors.toList());
        this.workspaceManager = workspaceManager;
        this.llmClient = llmClient;
        this.taskRepo = taskRepo;
        this.stageRepo = stageRepo;
        this.pipelineExecutor = pipelineExecutor;
        this.manualVulnDemoValidator = manualVulnDemoValidator;
    }

    public SseEmitter subscribe(String cveId) {
        SseEmitter emitter = new SseEmitter(PipelineConstants.SSE_TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(cveId, emitter));
        emitter.onTimeout(() -> emitters.remove(cveId, emitter));
        List<StageProgress> history = progressHistory.computeIfAbsent(
                cveId, key -> new ArrayList<>());
        synchronized (history) {
            emitters.put(cveId, emitter);
            for (StageProgress progress : history) {
                if (!sendToEmitter(cveId, emitter, progress)) {
                    break;
                }
            }
        }
        if (!isRunning(cveId)) {
            emitters.remove(cveId, emitter);
            emitter.complete();
        }
        return emitter;
    }

    public void clearProgress(String cveId) {
        progressHistory.remove(cveId);
        SseEmitter emitter = emitters.remove(cveId);
        if (emitter != null) {
            emitter.complete();
        }
    }

    public boolean execute(final String cveId, final int fromStage) {
        return execute(cveId, fromStage, null);
    }

    public boolean execute(final String cveId, final int fromStage, final String userHint) {
        // 使用 putIfAbsent 替代 computeIfAbsent，避免死锁风险
        AtomicBoolean newFlag = new AtomicBoolean(false);
        AtomicBoolean running = runningTasks.putIfAbsent(cveId, newFlag);

        // 如果返回 null，说明之前不存在，使用新创建的 flag
        if (running == null) {
            running = newFlag;
        }

        if (!running.compareAndSet(false, true)) {
            String message = "Pipeline already running for " + cveId;
            log.warn(message);
            sendEvent(cveId, new StageProgress("error", 0, message));
            return false;
        }

        progressHistory.put(cveId, new ArrayList<>());
        cancellationRequests.put(cveId, new AtomicBoolean(false));

        try {
            pipelineExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    runPipeline(cveId, fromStage, userHint);
                }
            });
            return true;
        } catch (RuntimeException e) {
            running.set(false);
            runningTasks.remove(cveId, running);
            cancellationRequests.remove(cveId);
            throw e;
        }
    }

    /**
     * 处理人工上传的 vuln-demo 压缩包：解压、验证。验证通过后从 Stage 5 继续执行流程。
     */
    public boolean uploadVulnDemo(final String cveId, final byte[] zipData) {
        AtomicBoolean newFlag = new AtomicBoolean(false);
        AtomicBoolean running = runningTasks.putIfAbsent(cveId, newFlag);
        if (running == null) {
            running = newFlag;
        }
        if (!running.compareAndSet(false, true)) {
            String message = "Pipeline already running for " + cveId;
            log.warn(message);
            sendEvent(cveId, new StageProgress("error", 0, message));
            return false;
        }

        progressHistory.put(cveId, new ArrayList<>());

        final AtomicBoolean lock = running;
        try {
            pipelineExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    runManualUpload(cveId, zipData, lock);
                }
            });
            return true;
        } catch (RuntimeException e) {
            lock.set(false);
            runningTasks.remove(cveId, lock);
            throw e;
        }
    }

    public boolean isRunning(String cveId) {
        AtomicBoolean running = runningTasks.get(cveId);
        return running != null && running.get();
    }

    public boolean cancel(String cveId) {
        AtomicBoolean running = runningTasks.get(cveId);
        AtomicBoolean cancellation = cancellationRequests.get(cveId);
        if (running == null || !running.get() || cancellation == null) {
            return false;
        }
        log.info("Cancel requested for {}", cveId);
        cancellation.set(true);
        return true;
    }

    public List<StageProgress> getProgressSnapshot(String cveId) {
        List<StageProgress> history = progressHistory.get(cveId);
        if (history == null) return null;
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    private void runPipeline(String cveId, int fromStage, String userHint) {
        log.info("Pipeline started: cveId={}, fromStage={}", cveId, fromStage);

        try {
            Path workspace = workspaceManager.initCveWorkspace(cveId);

            CveTask task = taskRepo.findByCveId(cveId).orElseThrow(
                    () -> new RuntimeException("Task not found: " + cveId));
            task.setStatus(CveTask.TaskStatus.RUNNING);
            taskRepo.save(task);

            PipelineContext ctx = new PipelineContext(cveId, workspace, llmClient, workspaceManager);
            ctx.setFromStage(fromStage);
            ctx.setUserHint(userHint);
            ctx.setProgressCallback(buildSseCallback(cveId));
            ctx.setCancellationSignal(cancellationRequests.get(cveId));

            boolean succeeded;
            LlmAuditLogger.setContextDir(workspace);
            LlmConversationContext.init();
            try (RequestLogContext.Scope ignored =
                         RequestLogContext.bind(ctx::reportProgress)) {
                succeeded = runStages(cveId, fromStage, task, ctx);
            } finally {
                LlmConversationContext.clear();
                LlmAuditLogger.clearContextDir();
            }
            if (!succeeded) {
                return;
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
            List<StageProgress> history = progressHistory.remove(cveId);
            if (history != null && !history.isEmpty()) {
                try {
                    workspaceManager.writePipelineLog(cveId, history);
                } catch (Exception ex) {
                    log.warn("Failed to write pipeline log for {}: {}", cveId, ex.getMessage());
                }
            }
            AtomicBoolean current = runningTasks.get(cveId);
            if (current != null) {
                current.set(false);
                runningTasks.remove(cveId, current);
            }
            cancellationRequests.remove(cveId);
            SseEmitter emitter = emitters.remove(cveId);
            if (emitter != null) {
                emitter.complete();
            }
        }
    }

    private boolean handleCancellation(String cveId, CveTask task) {
        log.info("Pipeline cancelled by user for {}", cveId);
        task.setStatus(CveTask.TaskStatus.FAILED);
        taskRepo.save(task);
        sendEvent(cveId, new StageProgress("pipeline_done", 0, "Cancelled by user"));
        return false;
    }

    private boolean runStages(String cveId, int fromStage, CveTask task,
                              PipelineContext ctx) {
        for (Stage stage : stages) {
            if (ctx.isCancelled()) {
                return handleCancellation(cveId, task);
            }
            boolean shouldSkip = stage.number() < fromStage
                    && workspaceManager.isStageComplete(cveId, stage.number());
            if (shouldSkip) {
                log.info("Stage {} skipped (checkpoint exists), loading cached data",
                        stage.number());
                try {
                    Object data = workspaceManager.readStageData(
                            cveId, stage.number(), Object.class);
                    ctx.getCompletedStages().put(stage.number(),
                            StageResult.success(stage.number(), stage.name(), data));
                } catch (Exception e) {
                    log.warn("Could not load cached stage {} data: {}",
                            stage.number(), e.getMessage());
                }
                if (stage.number() == 2) {
                    try {
                        LlmConversationContext.setRelevantDiff(
                                workspaceManager.readRelevantDiff(cveId));
                    } catch (Exception e) {
                        log.warn("Could not load relevant diff for {}: {}",
                                cveId, e.getMessage());
                    }
                }
                continue;
            }

            ctx.setCurrentStage(stage.number());
            sendEvent(cveId, new StageProgress(
                    "stage_start", stage.number(), stage.name()));

            StageRecord record = getOrCreateRecord(cveId, stage);
            record.setStatus(StageRecord.StageStatus.RUNNING);
            record.setStartedAt(LocalDateTime.now());
            record.setErrorMsg(null);
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
                record.setErrorMsg(result.isSuccess() ? null : result.getErrorMessage());
                stageRepo.save(record);

                updateTaskMetadata(stage, result, task);

                sendEvent(cveId, new StageProgress("stage_done", stage.number(),
                        result.isSuccess() ? "completed"
                                : "failed: " + result.getErrorMessage()));

                if (!result.isSuccess()) {
                    log.warn("Stage {} failed: {}", stage.number(), result.getErrorMessage());
                    task.setStatus(CveTask.TaskStatus.FAILED);
                    taskRepo.save(task);
                    return false;
                }

                if (ctx.isCancelled()) {
                    return handleCancellation(cveId, task);
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
                return false;
            }
        }
        return true;
    }

    private void updateTaskMetadata(Stage stage, StageResult result, CveTask task) {
        if (!result.isSuccess()) {
            return;
        }
        if (stage.number() == 5) {
            applyReportDescription(result, task);
            return;
        }
        if (stage.number() != 1 || !(result.getData() instanceof CveIntelligence)) {
            return;
        }
        CveIntelligence intel = (CveIntelligence) result.getData();
        if (intel.getCvss() != null) task.setCvssScore(intel.getCvss().getScore());
        if (intel.getCweId() != null) task.setCweId(intel.getCweId());
        if (intel.getDescription() != null && !intel.getDescription().isEmpty()) {
            task.setDescription(intel.getDescription());
        }
        if (intel.getArtifact() != null) {
            task.setArtifact(intel.getArtifact().getGroupId()
                    + ":" + intel.getArtifact().getArtifactId());
        }
        taskRepo.save(task);
    }

    private void applyReportDescription(StageResult result, CveTask task) {
        if (!(result.getData() instanceof JsonNode)) {
            return;
        }
        String description = ((JsonNode) result.getData()).path("description").asText("").trim();
        if (!description.isEmpty()) {
            task.setDescription(description);
            taskRepo.save(task);
        }
    }

    private void runManualUpload(String cveId, byte[] zipData, AtomicBoolean lock) {
        log.info("Manual vuln-demo upload started: cveId={}", cveId);

        try {
            Path workspace = workspaceManager.initCveWorkspace(cveId);

            CveTask task = taskRepo.findByCveId(cveId).orElseThrow(
                    () -> new RuntimeException("Task not found: " + cveId));
            task.setStatus(CveTask.TaskStatus.RUNNING);
            task.setCurrentStage(4);
            taskRepo.save(task);

            StageRecord stage4 = getOrCreateRecord(cveId, findStage(4));
            stage4.setStatus(StageRecord.StageStatus.RUNNING);
            stage4.setStartedAt(LocalDateTime.now());
            stage4.setErrorMsg(null);
            stageRepo.save(stage4);

            PipelineContext ctx = new PipelineContext(cveId, workspace, llmClient, workspaceManager);
            ctx.setFromStage(5);
            ctx.setProgressCallback(buildSseCallback(cveId));

            sendEvent(cveId, new StageProgress("stage_start", 4, "Manual vuln-demo upload"));

            ManualVulnDemoValidator.Result result;
            LlmAuditLogger.setContextDir(workspace);
            LlmConversationContext.init();
            try (RequestLogContext.Scope ignored =
                         RequestLogContext.bind(ctx::reportProgress)) {
                Path cvePath = workspaceManager.getCvePath(cveId);
                result = manualVulnDemoValidator.validateUploadedZip(ctx, cvePath, zipData);
                if (result.output != null) {
                    workspaceManager.writeStageData(cveId, 4, result.output);
                }

                stage4.setStatus(result.success
                        ? StageRecord.StageStatus.COMPLETED
                        : StageRecord.StageStatus.FAILED);
                stage4.setFinishedAt(LocalDateTime.now());
                stage4.setErrorMsg(result.success ? null : result.failureReason);
                stageRepo.save(stage4);

                sendEvent(cveId, new StageProgress("stage_done", 4,
                        result.success ? "completed" : "failed: " + result.failureReason));

                if (!result.success) {
                    task.setStatus(CveTask.TaskStatus.FAILED);
                    taskRepo.save(task);
                    sendEvent(cveId, new StageProgress("error", 4, result.failureReason));
                    return;
                }

                ctx.getCompletedStages().put(4,
                        StageResult.success(4, findStage(4).name(), result.output));
                boolean succeeded = runStages(cveId, 5, task, ctx);
                if (!succeeded) {
                    return;
                }
            } finally {
                LlmConversationContext.clear();
                LlmAuditLogger.clearContextDir();
            }

            task.setStatus(CveTask.TaskStatus.COMPLETED);
            taskRepo.save(task);
            sendEvent(cveId, new StageProgress("pipeline_done", 0, "All stages completed"));

        } catch (Exception e) {
            log.error("Manual upload failed for {}", cveId, e);
            taskRepo.findByCveId(cveId).ifPresent(t -> {
                t.setStatus(CveTask.TaskStatus.FAILED);
                taskRepo.save(t);
            });
            sendEvent(cveId, new StageProgress("error", 4, e.getMessage()));
        } finally {
            List<StageProgress> history = progressHistory.remove(cveId);
            if (history != null && !history.isEmpty()) {
                try {
                    workspaceManager.writePipelineLog(cveId, history);
                } catch (Exception ex) {
                    log.warn("Failed to write pipeline log for {}: {}", cveId, ex.getMessage());
                }
            }
            lock.set(false);
            runningTasks.remove(cveId, lock);
            SseEmitter emitter = emitters.remove(cveId);
            if (emitter != null) {
                emitter.complete();
            }
        }
    }

    private Stage findStage(int number) {
        for (Stage stage : stages) {
            if (stage.number() == number) {
                return stage;
            }
        }
        throw new IllegalStateException("Stage " + number + " not registered");
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
        List<StageProgress> history = progressHistory.computeIfAbsent(
                cveId, key -> new ArrayList<>());
        synchronized (history) {
            if (history.size() >= MAX_PROGRESS_EVENTS) {
                history.remove(0);
            }
            history.add(progress);
            SseEmitter emitter = emitters.get(cveId);
            if (emitter != null) {
                sendToEmitter(cveId, emitter, progress);
            }
        }
    }

    private boolean sendToEmitter(String cveId, SseEmitter emitter,
                                  StageProgress progress) {
        try {
            emitter.send(SseEmitter.event()
                    .name(progress.getType())
                    .data(progress));
            return true;
        } catch (IOException | IllegalStateException e) {
            emitters.remove(cveId, emitter);
            return false;
        }
    }
}
