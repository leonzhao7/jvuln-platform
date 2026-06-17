package com.jvuln.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.pipeline.PipelineConstants;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private static final Pattern DIFF_HEADER =
            Pattern.compile("^diff --git a/(.+) b/(.+)$", Pattern.MULTILINE);

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

        for (int i = 1; i <= 4; i++) {
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
        if (task.getStatus() == CveTask.TaskStatus.RUNNING && pipelineEngine.isRunning(cveId)) {
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
        Matcher matcher = DIFF_HEADER.matcher(section);
        if (!matcher.find()) {
            return false;
        }
        String leftPath = matcher.group(1);
        String rightPath = matcher.group(2);
        String leftName = baseName(leftPath);
        String rightName = baseName(rightPath);
        for (String name : analyzedFiles) {
            if (name.equals(leftPath) || name.equals(rightPath)) return true;
            String shortName = baseName(name);
            if (shortName.equals(leftName) || shortName.equals(rightName)) return true;
        }
        return false;
    }

    private String baseName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
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
        return readStageJson(cveId, 3);
    }

    @GetMapping("/{cveId}/artifacts")
    public ResponseEntity<?> getArtifacts(@PathVariable String cveId) {
        return readStageJson(cveId, 4);
    }

    @GetMapping("/{cveId}/stages/{stageNum}/json")
    public ResponseEntity<?> getStageJson(@PathVariable String cveId,
                                          @PathVariable int stageNum) {
        if (stageNum < 1 || stageNum > 4) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "stageNum must be between 1 and 4");
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

    /** Sync DB stage statuses to match what's actually on disk. Stage 4 inspects its JSON status. */
    @PostMapping("/{cveId}/sync-status")
    public ResponseEntity<?> syncStatus(@PathVariable String cveId) {
        CveTask task = taskRepo.findByCveId(cveId).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();
        if (task.getStatus() == CveTask.TaskStatus.RUNNING && pipelineEngine.isRunning(cveId)) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "Analysis is currently running for " + cveId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err);
        }

        int maxCompleted = 0;
        int currentStage = 0;
        boolean failed = false;
        for (int stageNum = PipelineConstants.FIRST_STAGE; stageNum <= PipelineConstants.TOTAL_STAGES; stageNum++) {
            final int currentStageNum = stageNum;
            StageFileStatus diskStatus = inspectStageFileStatus(cveId, stageNum);
            if (diskStatus.exists) {
                currentStage = stageNum;
                stageRepo.findByCveIdAndStageNum(cveId, stageNum).ifPresent(rec -> {
                    rec.setStatus(diskStatus.status);
                    rec.setErrorMsg(diskStatus.error);
                    stageRepo.save(rec);
                });
                if (diskStatus.status == StageRecord.StageStatus.COMPLETED) {
                    maxCompleted = stageNum;
                } else if (diskStatus.status == StageRecord.StageStatus.FAILED) {
                    failed = true;
                }
            }
        }
        task.setCurrentStage(currentStage > 0 ? currentStage : maxCompleted);
        if (failed) {
            task.setStatus(CveTask.TaskStatus.FAILED);
        } else if (maxCompleted == PipelineConstants.TOTAL_STAGES) {
            task.setStatus(CveTask.TaskStatus.COMPLETED);
        } else {
            task.setStatus(CveTask.TaskStatus.PENDING);
        }
        taskRepo.save(task);
        Map<String, Object> result = new HashMap<>();
        result.put("cveId", cveId);
        result.put("syncedToStage", maxCompleted);
        result.put("currentStage", task.getCurrentStage());
        result.put("status", task.getStatus());
        return ResponseEntity.ok(result);
    }

    @SuppressWarnings("unchecked")
    private StageFileStatus inspectStageFileStatus(String cveId, int stageNum) {
        if (!workspaceManager.isStageComplete(cveId, stageNum)) {
            return StageFileStatus.missing();
        }
        if (stageNum != 5) {
            return StageFileStatus.completed();
        }
        try {
            Object data = workspaceManager.readStageData(cveId, stageNum, Object.class);
            if (!(data instanceof Map)) {
                return StageFileStatus.completed();
            }
            Map<String, Object> stage = (Map<String, Object>) data;
            String status = String.valueOf(stage.getOrDefault("status", ""));
            String failureReason = String.valueOf(stage.getOrDefault("failureReason", "")).trim();
            if ("paused".equals(status)) {
                String pauseReason = String.valueOf(stage.getOrDefault("pauseReason", "")).trim();
                return StageFileStatus.failed("Agent paused"
                        + (pauseReason.isEmpty() || "null".equals(pauseReason) ? "." : ": " + pauseReason));
            }
            if (!failureReason.isEmpty() && !"null".equals(failureReason)) {
                return StageFileStatus.failed(failureReason);
            }
            Object pocObj = stage.get("poc");
            if (pocObj instanceof Map) {
                Map<String, Object> poc = (Map<String, Object>) pocObj;
                String pocStatus = String.valueOf(poc.getOrDefault("status", ""));
                if ("unverified".equals(pocStatus)) {
                    String reason = String.valueOf(poc.getOrDefault("reason", "")).trim();
                    return StageFileStatus.failed("PoC verification failed"
                            + (reason.isEmpty() || "null".equals(reason) ? "." : ": " + reason));
                }
            }
            return StageFileStatus.completed();
        } catch (Exception e) {
            return StageFileStatus.failed("Could not read Stage 4 data: " + e.getMessage());
        }
    }

    private static class StageFileStatus {
        final boolean exists;
        final StageRecord.StageStatus status;
        final String error;

        private StageFileStatus(boolean exists, StageRecord.StageStatus status, String error) {
            this.exists = exists;
            this.status = status;
            this.error = error;
        }

        static StageFileStatus missing() {
            return new StageFileStatus(false, StageRecord.StageStatus.PENDING, null);
        }

        static StageFileStatus completed() {
            return new StageFileStatus(true, StageRecord.StageStatus.COMPLETED, null);
        }

        static StageFileStatus failed(String error) {
            return new StageFileStatus(true, StageRecord.StageStatus.FAILED, error);
        }
    }

    private ResponseEntity<?> readStageJson(String cveId, int stageNum) {
        try {
            Object data = workspaceManager.readStageData(cveId, stageNum, Object.class);
            if (data != null) return ResponseEntity.ok(normalizeStageData(stageNum, data));
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            Map<String, String> err = new HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    @SuppressWarnings("unchecked")
    private Object normalizeStageData(int stageNum, Object data) {
        if (stageNum != 4 || !(data instanceof Map)) {
            return data;
        }

        Map<String, Object> stage = (Map<String, Object>) data;
        Object vulnDemoObj = stage.get("vulnDemo");
        if (!(vulnDemoObj instanceof Map)) {
            return data;
        }

        Map<String, Object> vulnDemo = (Map<String, Object>) vulnDemoObj;
        String status = stringValue(vulnDemo.get("status"));
        if (!vulnDemo.containsKey("compileStatus")) {
            vulnDemo.put("compileStatus", deriveCompileStatus(status));
        }
        if (!vulnDemo.containsKey("startupStatus")) {
            vulnDemo.put("startupStatus", deriveStartupStatus(status));
        }
        return data;
    }

    private String deriveCompileStatus(String status) {
        if ("startup_ok".equals(status) || "compile_ok".equals(status) || "startup_failed".equals(status)) {
            return "compile_ok";
        }
        if ("compile_failed".equals(status)) {
            return "compile_failed";
        }
        if ("not_started".equals(status)) {
            return "not_started";
        }
        return "unknown";
    }

    private String deriveStartupStatus(String status) {
        if ("startup_ok".equals(status)) {
            return "startup_ok";
        }
        if ("startup_failed".equals(status)) {
            return "startup_failed";
        }
        if ("compile_ok".equals(status) || "compile_failed".equals(status)) {
            return "skipped";
        }
        if ("not_started".equals(status)) {
            return "not_started";
        }
        return "unknown";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @GetMapping("/{cveId}/transcript")
    public ResponseEntity<?> getTranscript(@PathVariable String cveId) {
        try {
            Path transcriptFile = workspaceManager.getCvePath(cveId).resolve("stages/4_transcript.jsonl");
            if (!Files.exists(transcriptFile)) {
                return ResponseEntity.ok(Collections.emptyList());
            }
            List<String> lines = Files.readAllLines(transcriptFile, StandardCharsets.UTF_8);
            List<Object> events = new java.util.ArrayList<>();
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                try {
                    JsonNode node = mapper.readTree(line);
                    String type = node.path("type").asText("");
                    // Only include relevant types for frontend display
                    if ("assistant".equals(type) || "directive".equals(type)
                            || "tool_results".equals(type) || "compact".equals(type)) {
                        events.add(mapper.readValue(line, Object.class));
                    }
                } catch (Exception ignored) {}
            }
            return ResponseEntity.ok(events);
        } catch (IOException e) {
            Map<String, String> err = new HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    private String stageName(int num) {
        switch (num) {
            case 1: return "Intelligence Collection";
            case 2: return "Patch Analysis";
            case 3: return "Vulnerability Reasoning";
            case 4: return "Artifact Generation";
            default: return "Unknown";
        }
    }
}
