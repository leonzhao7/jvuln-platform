package com.jvuln.controller;

import com.jvuln.pipeline.PipelineEngine;
import com.jvuln.store.CveTaskRepository;
import com.jvuln.store.StageRecordRepository;
import com.jvuln.store.WorkspaceManager;
import com.jvuln.store.entity.CveTask;
import com.jvuln.store.entity.StageRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final PipelineEngine pipelineEngine;
    private final CveTaskRepository taskRepo;
    private final StageRecordRepository stageRepo;
    private final WorkspaceManager workspaceManager;

    public AnalysisController(PipelineEngine pipelineEngine, CveTaskRepository taskRepo,
                              StageRecordRepository stageRepo, WorkspaceManager workspaceManager) {
        this.pipelineEngine = pipelineEngine;
        this.taskRepo = taskRepo;
        this.stageRepo = stageRepo;
        this.workspaceManager = workspaceManager;
    }

    @PostMapping
    public ResponseEntity<?> createAnalysis(@RequestBody Map<String, String> body) {
        String cveId = body.get("cveId");
        if (cveId == null || !cveId.matches("CVE-\\d{4}-\\d{4,}")) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "Invalid CVE ID format");
            return ResponseEntity.badRequest().body(err);
        }

        if (taskRepo.existsByCveId(cveId)) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "Analysis already exists for " + cveId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err);
        }

        CveTask task = new CveTask();
        task.setCveId(cveId);
        task.setStatus(CveTask.TaskStatus.PENDING);
        task.setWorkspacePath("workspace/" + cveId);
        taskRepo.save(task);

        for (int i = 1; i <= 5; i++) {
            StageRecord record = new StageRecord();
            record.setCveId(cveId);
            record.setStageNum(i);
            record.setStageName(stageName(i));
            stageRepo.save(record);
        }

        String fromStageStr = body.getOrDefault("fromStage", "1");
        int fromStage = Integer.parseInt(fromStageStr);
        pipelineEngine.execute(cveId, fromStage);

        Map<String, String> result = new HashMap<>();
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
        Map<String, Object> resp = new HashMap<>();
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

        task.setStatus(CveTask.TaskStatus.PENDING);
        taskRepo.save(task);
        pipelineEngine.execute(cveId, fromStage);

        Map<String, Object> resp = new HashMap<>();
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
            Path diffFile = workspaceManager.getCvePath(cveId).resolve("patches/fix.diff");
            if (Files.exists(diffFile)) {
                Map<String, String> resp = new HashMap<>();
                resp.put("diff", new String(Files.readAllBytes(diffFile), java.nio.charset.StandardCharsets.UTF_8));
                return ResponseEntity.ok(resp);
            }
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            Map<String, String> err = new HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    @GetMapping("/{cveId}/reasoning")
    public ResponseEntity<?> getReasoning(@PathVariable String cveId) {
        return readStageJson(cveId, 4);
    }

    @GetMapping("/{cveId}/code-analysis")
    public ResponseEntity<?> getCodeAnalysis(@PathVariable String cveId) {
        return readStageJson(cveId, 3);
    }

    @GetMapping("/{cveId}/report")
    public ResponseEntity<?> getReport(@PathVariable String cveId) {
        try {
            Path reportFile = workspaceManager.getCvePath(cveId).resolve("report/report.md");
            if (Files.exists(reportFile)) {
                Map<String, String> resp = new HashMap<>();
                resp.put("markdown", new String(Files.readAllBytes(reportFile), java.nio.charset.StandardCharsets.UTF_8));
                return ResponseEntity.ok(resp);
            }
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            Map<String, String> err = new HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    @DeleteMapping("/{cveId}")
    public ResponseEntity<?> deleteAnalysis(@PathVariable String cveId) {
        taskRepo.findByCveId(cveId).ifPresent(task -> {
            stageRepo.findByCveIdOrderByStageNum(cveId).forEach(stageRepo::delete);
            taskRepo.delete(task);
        });
        return ResponseEntity.noContent().build();
    }

    /** Sync DB stage statuses to match what's actually on disk (files present = COMPLETED). */
    @PostMapping("/{cveId}/sync-status")
    public ResponseEntity<?> syncStatus(@PathVariable String cveId) {
        CveTask task = taskRepo.findByCveId(cveId).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();
        int maxCompleted = 0;
        for (int n = 1; n <= 5; n++) {
            final int stageNum = n;
            boolean hasFile = workspaceManager.isStageComplete(cveId, n);
            if (hasFile) {
                stageRepo.findByCveIdAndStageNum(cveId, n).ifPresent(rec -> {
                    if (rec.getStatus() != StageRecord.StageStatus.COMPLETED) {
                        rec.setStatus(StageRecord.StageStatus.COMPLETED);
                        rec.setErrorMsg(null);
                        stageRepo.save(rec);
                    }
                });
                maxCompleted = n;
            }
        }
        task.setCurrentStage(maxCompleted);
        if (maxCompleted == 5) task.setStatus(CveTask.TaskStatus.COMPLETED);
        taskRepo.save(task);
        Map<String, Object> result = new HashMap<>();
        result.put("cveId", cveId);
        result.put("syncedToStage", maxCompleted);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<?> readStageJson(String cveId, int stageNum) {
        try {
            Object data = workspaceManager.readStageData(cveId, stageNum, Object.class);
            if (data != null) return ResponseEntity.ok(data);
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            Map<String, String> err = new HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    private String stageName(int num) {
        switch (num) {
            case 1: return "Intelligence Collection";
            case 2: return "Patch Locating";
            case 3: return "Code Analysis";
            case 4: return "Vulnerability Reasoning";
            case 5: return "Artifact Generation";
            default: return "Unknown";
        }
    }
}
