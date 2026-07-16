package com.jvuln.controller;

import com.jvuln.controller.support.ApiResponseFactory;
import com.jvuln.pipeline.PipelineConstants;
import com.jvuln.pipeline.PipelineEngine;
import com.jvuln.service.AnalysisQueryService;
import com.jvuln.service.AnalysisStatusSyncService;
import com.jvuln.pipeline.model.StageProgress;
import com.jvuln.store.CveTaskRepository;
import com.jvuln.store.StageRecordRepository;
import com.jvuln.store.WorkspaceManager;
import com.jvuln.store.entity.CveTask;
import com.jvuln.store.entity.StageRecord;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final PipelineEngine pipelineEngine;
    private final CveTaskRepository taskRepo;
    private final StageRecordRepository stageRepo;
    private final AnalysisQueryService analysisQueryService;
    private final AnalysisStatusSyncService analysisStatusSyncService;
    private final WorkspaceManager workspaceManager;

    public AnalysisController(PipelineEngine pipelineEngine, CveTaskRepository taskRepo,
                              StageRecordRepository stageRepo,
                              AnalysisQueryService analysisQueryService,
                              AnalysisStatusSyncService analysisStatusSyncService,
                              WorkspaceManager workspaceManager) {
        this.pipelineEngine = pipelineEngine;
        this.taskRepo = taskRepo;
        this.stageRepo = stageRepo;
        this.analysisQueryService = analysisQueryService;
        this.analysisStatusSyncService = analysisStatusSyncService;
        this.workspaceManager = workspaceManager;
    }

    @PostMapping
    public ResponseEntity<?> createAnalysis(@RequestBody Map<String, String> body) {
        String cveId = body.get("cveId");
        if (cveId == null || !cveId.matches("CVE-\\d{4}-\\d{4,}")) {
            return ApiResponseFactory.badRequest("Invalid CVE ID format");
        }

        if (taskRepo.existsByCveId(cveId)) {
            return ApiResponseFactory.conflict("Analysis already exists for " + cveId);
        }

        CveTask task = new CveTask();
        task.setCveId(cveId);
        task.setStatus(CveTask.TaskStatus.PENDING);
        task.setWorkspacePath("workspace/" + cveId);
        taskRepo.save(task);

        for (int i = PipelineConstants.FIRST_STAGE; i <= PipelineConstants.TOTAL_STAGES; i++) {
            StageRecord record = new StageRecord();
            record.setCveId(cveId);
            record.setStageNum(i);
            record.setStageName(PipelineConstants.getStageName(i));
            stageRepo.save(record);
        }

        String fromStageStr = body.getOrDefault("fromStage", "1");
        int fromStage;
        try {
            fromStage = Integer.parseInt(fromStageStr);
        } catch (NumberFormatException e) {
            return ApiResponseFactory.badRequest("fromStage must be an integer");
        }
        if (!pipelineEngine.execute(cveId, fromStage)) {
            return ApiResponseFactory.conflict("Analysis already running for " + cveId);
        }

        Map<String, String> result = new java.util.LinkedHashMap<>();
        result.put("cveId", cveId);
        result.put("status", "PENDING");
        return ResponseEntity.accepted().body(result);
    }

    @GetMapping
    public List<CveTask> listAnalyses() {
        return taskRepo.findAll();
    }

    @GetMapping("/{cveId}")
    public ResponseEntity<?> getAnalysis(@PathVariable String cveId) {
        CveTask task = taskRepo.findByCveId(cveId).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();

        List<StageRecord> stages = stageRepo.findByCveIdOrderByStageNum(cveId);
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("task", task);
        resp.put("stages", stages);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{cveId}/stream")
    public SseEmitter streamProgress(@PathVariable String cveId) {
        return pipelineEngine.subscribe(cveId);
    }

    @PostMapping("/{cveId}/rerun")
    public ResponseEntity<?> rerun(@PathVariable String cveId,
                                   @RequestParam(defaultValue = "1") int fromStage) {
        CveTask task = taskRepo.findByCveId(cveId).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();
        if (task.getStatus() == CveTask.TaskStatus.RUNNING && pipelineEngine.isRunning(cveId)) {
            return ApiResponseFactory.conflict("Analysis already running for " + cveId);
        }

        task.setStatus(CveTask.TaskStatus.PENDING);
        taskRepo.save(task);
        if (!pipelineEngine.execute(cveId, fromStage)) {
            task.setStatus(CveTask.TaskStatus.RUNNING);
            taskRepo.save(task);
            return ApiResponseFactory.conflict("Analysis already running for " + cveId);
        }

        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("cveId", cveId);
        resp.put("fromStage", fromStage);
        return ResponseEntity.accepted().body(resp);
    }

    @GetMapping("/{cveId}/intelligence")
    public ResponseEntity<?> getIntelligence(@PathVariable String cveId) {
        return readStageJson(cveId, 1);
    }

    @GetMapping("/{cveId}/patch")
    public ResponseEntity<?> getPatch(@PathVariable String cveId) {
        return readStageJson(cveId, 2);
    }

    @GetMapping("/{cveId}/diff")
    public ResponseEntity<?> getDiff(@PathVariable String cveId) {
        try {
            if (!analysisQueryService.diffExists(cveId)) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(analysisQueryService.loadDiff(cveId));
        } catch (IOException e) {
            return ApiResponseFactory.internalServerError(e.getMessage());
        }
    }

    @GetMapping("/{cveId}/reasoning")
    public ResponseEntity<?> getReasoning(@PathVariable String cveId) {
        return readStageJson(cveId, 3);
    }

    @GetMapping("/{cveId}/artifacts")
    public ResponseEntity<?> getArtifacts(@PathVariable String cveId) {
        return readStageJson(cveId, 4);
    }

    @GetMapping("/{cveId}/artifacts/file")
    public ResponseEntity<?> getArtifactFile(@PathVariable String cveId,
                                             @RequestParam String path) {
        try {
            String content = analysisQueryService.loadArtifactFile(cveId, path);
            if (content == null) return ResponseEntity.notFound().build();
            Map<String, String> result = new LinkedHashMap<>();
            result.put("path", path);
            result.put("content", content);
            return ResponseEntity.ok(result);
        } catch (SecurityException | IllegalArgumentException e) {
            return ApiResponseFactory.badRequest(e.getMessage());
        } catch (IOException e) {
            return ApiResponseFactory.internalServerError(e.getMessage());
        }
    }

    @GetMapping("/{cveId}/artifacts/download")
    public ResponseEntity<byte[]> downloadArtifacts(@PathVariable String cveId) {
        try {
            byte[] zip = analysisQueryService.zipArtifacts(cveId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + cveId + "-artifacts.zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zip);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{cveId}/stages/{stageNum}/json")
    public ResponseEntity<?> getStageJson(@PathVariable String cveId,
                                          @PathVariable int stageNum) {
        if (stageNum < 1 || stageNum > 4) {
            return ApiResponseFactory.badRequest("stageNum must be between 1 and 4");
        }
        return readStageJson(cveId, stageNum);
    }

    @GetMapping("/{cveId}/code-analysis")
    public ResponseEntity<?> getCodeAnalysis(@PathVariable String cveId) {
        return readStageJson(cveId, 3);
    }

    @GetMapping("/{cveId}/report")
    public ResponseEntity<?> getReport(@PathVariable String cveId) {
        try {
            if (analysisQueryService.reportExists(cveId)) {
                return ResponseEntity.ok(analysisQueryService.loadReport(cveId));
            }
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ApiResponseFactory.internalServerError(e.getMessage());
        }
    }

    @DeleteMapping("/{cveId}")
    public ResponseEntity<?> deleteAnalysis(@PathVariable String cveId) {
        CveTask task = taskRepo.findByCveId(cveId).orElse(null);
        if (task == null) {
            return ResponseEntity.noContent().build();
        }
        if (pipelineEngine.isRunning(cveId)) {
            return ApiResponseFactory.conflict("Analysis is currently running for " + cveId);
        }
        try {
            workspaceManager.deleteCveWorkspace(cveId);
        } catch (IOException e) {
            return ApiResponseFactory.internalServerError(
                    "Failed to delete workspace: " + e.getMessage());
        }
        stageRepo.findByCveIdOrderByStageNum(cveId).forEach(stageRepo::delete);
        taskRepo.delete(task);
        pipelineEngine.clearProgress(cveId);
        return ResponseEntity.noContent().build();
    }

    /** Sync DB stage statuses to match what's actually on disk. Stage 4 inspects its JSON status. */
    @PostMapping("/{cveId}/sync-status")
    public ResponseEntity<?> syncStatus(@PathVariable String cveId) {
        CveTask task = taskRepo.findByCveId(cveId).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();
        if (task.getStatus() == CveTask.TaskStatus.RUNNING && pipelineEngine.isRunning(cveId)) {
            return ApiResponseFactory.conflict("Analysis is currently running for " + cveId);
        }

        Map<String, Object> result = analysisStatusSyncService.sync(cveId, task);
        taskRepo.save(task);
        result.put("cveId", cveId);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<?> readStageJson(String cveId, int stageNum) {
        try {
            Object data = analysisQueryService.readStageJson(cveId, stageNum);
            if (data != null) return ResponseEntity.ok(data);
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ApiResponseFactory.internalServerError(e.getMessage());
        }
    }

    @GetMapping("/{cveId}/pipeline-log")
    public ResponseEntity<?> getPipelineLog(@PathVariable String cveId) {
        if (pipelineEngine.isRunning(cveId)) {
            List<StageProgress> snapshot = pipelineEngine.getProgressSnapshot(cveId);
            if (snapshot != null) return ResponseEntity.ok(snapshot);
        }
        try {
            List<StageProgress> log = workspaceManager.readPipelineLog(cveId);
            if (log != null) return ResponseEntity.ok(log);
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ApiResponseFactory.internalServerError(e.getMessage());
        }
    }

    @GetMapping("/{cveId}/transcript")
    public ResponseEntity<?> getTranscript(@PathVariable String cveId) {
        try {
            return ResponseEntity.ok(analysisQueryService.loadTranscript(cveId));
        } catch (IOException e) {
            return ApiResponseFactory.internalServerError(e.getMessage());
        }
    }
}
