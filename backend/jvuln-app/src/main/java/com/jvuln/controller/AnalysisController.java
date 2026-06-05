package com.jvuln.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final PipelineEngine pipelineEngine;
    private final CveTaskRepository taskRepo;
    private final StageRecordRepository stageRepo;
    private final WorkspaceManager workspaceManager;
    private final ObjectMapper mapper = new ObjectMapper();

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
        if (!pipelineEngine.execute(cveId, fromStage)) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "Analysis already running for " + cveId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err);
        }

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
        if (task.getStatus() == CveTask.TaskStatus.RUNNING) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "Analysis already running for " + cveId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err);
        }

        task.setStatus(CveTask.TaskStatus.PENDING);
        taskRepo.save(task);
        if (!pipelineEngine.execute(cveId, fromStage)) {
            task.setStatus(CveTask.TaskStatus.RUNNING);
            taskRepo.save(task);
            Map<String, String> err = new HashMap<>();
            err.put("error", "Analysis already running for " + cveId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err);
        }

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
            if (!Files.exists(diffFile)) return ResponseEntity.notFound().build();

            String rawDiff = new String(Files.readAllBytes(diffFile), StandardCharsets.UTF_8);
            Set<String> analyzedFiles = loadAnalyzedFileNames(cveId);

            String diff = analyzedFiles.isEmpty() ? rawDiff : filterDiff(rawDiff, analyzedFiles);
            Map<String, Object> resp = new HashMap<>();
            resp.put("diff", diff);
            resp.put("totalFiles", countDiffSections(rawDiff));
            resp.put("shownFiles", countDiffSections(diff));
            return ResponseEntity.ok(resp);
        } catch (IOException e) {
            Map<String, String> err = new HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    /** Returns file names from Stage 3 analyzedFiles, empty set if Stage 3 not available. */
    private Set<String> loadAnalyzedFileNames(String cveId) {
        try {
            Path stage3 = workspaceManager.getStageFile(cveId, 3);
            if (!Files.exists(stage3)) return Collections.emptySet();
            JsonNode root = mapper.readTree(stage3.toFile());
            JsonNode files = root.path("analyzedFiles");
            if (!files.isArray()) return Collections.emptySet();
            Set<String> names = new HashSet<>();
            for (JsonNode f : files) {
                String name = f.path("fileName").asText("");
                if (!name.isEmpty()) names.add(name);
            }
            return names;
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    /** Keeps only diff sections whose file path matches one of the analyzed file names. */
    private String filterDiff(String rawDiff, Set<String> analyzedFiles) {
        String[] sections = rawDiff.split("(?=diff --git )");
        StringBuilder sb = new StringBuilder();
        for (String section : sections) {
            if (section.trim().isEmpty()) continue;
            if (sectionMatchesAny(section, analyzedFiles)) sb.append(section);
        }
        return sb.toString();
    }

    private boolean sectionMatchesAny(String section, Set<String> analyzedFiles) {
        // First line: "diff --git a/path/to/File.java b/path/to/File.java"
        String firstLine = section.indexOf('\n') > 0
                ? section.substring(0, section.indexOf('\n')) : section;
        for (String name : analyzedFiles) {
            if (firstLine.contains(name)) return true;
            // Also match by just the filename
            String shortName = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
            if (firstLine.contains(shortName)) return true;
        }
        return false;
    }

    private int countDiffSections(String diff) {
        int count = 0;
        for (String s : diff.split("(?=diff --git )")) {
            if (!s.trim().isEmpty()) count++;
        }
        return count;
    }

    @GetMapping("/{cveId}/reasoning")
    public ResponseEntity<?> getReasoning(@PathVariable String cveId) {
        return readStageJson(cveId, 4);
    }

    @GetMapping("/{cveId}/artifacts")
    public ResponseEntity<?> getArtifacts(@PathVariable String cveId) {
        return readStageJson(cveId, 5);
    }

    @GetMapping("/{cveId}/stages/{stageNum}/json")
    public ResponseEntity<?> getStageJson(@PathVariable String cveId,
                                          @PathVariable int stageNum) {
        if (stageNum < 1 || stageNum > 5) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "stageNum must be between 1 and 5");
            return ResponseEntity.badRequest().body(err);
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
