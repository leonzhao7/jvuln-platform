package com.jvuln.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.pipeline.PipelineConstants;
import com.jvuln.store.WorkspaceManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AnalysisQueryService {

    private static final Pattern DIFF_HEADER =
            Pattern.compile("^diff --git a/(.+) b/(.+)$", Pattern.MULTILINE);
    private static final int TRANSCRIPT_STAGE = PipelineConstants.STAGE_ARTIFACTS;

    private final WorkspaceManager workspaceManager;
    private final ObjectMapper objectMapper;

    public AnalysisQueryService(WorkspaceManager workspaceManager, ObjectMapper objectMapper) {
        this.workspaceManager = workspaceManager;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> loadDiff(String cveId) throws IOException {
        Path diffFile = workspaceManager.getCvePath(cveId).resolve("patches/fix.diff");
        String rawDiff = new String(Files.readAllBytes(diffFile), StandardCharsets.UTF_8);
        Set<String> analyzedFiles = loadAnalyzedFileNames(cveId);
        String filteredDiff = analyzedFiles.isEmpty() ? rawDiff : filterDiff(rawDiff, analyzedFiles);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("diff", filteredDiff);
        response.put("totalFiles", countDiffSections(rawDiff));
        response.put("shownFiles", countDiffSections(filteredDiff));
        return response;
    }

    public Map<String, String> loadReport(String cveId) throws IOException {
        Path reportFile = workspaceManager.getCvePath(cveId).resolve("report/report.md");
        Map<String, String> response = new LinkedHashMap<>();
        response.put("markdown", new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8));
        return response;
    }

    public Object readStageJson(String cveId, int stageNum) throws IOException {
        Object data = workspaceManager.readStageData(cveId, stageNum, Object.class);
        if (data == null) {
            return null;
        }
        return normalizeStageData(stageNum, data);
    }

    public List<Object> loadTranscript(String cveId) throws IOException {
        Path transcriptFile = workspaceManager.getCvePath(cveId)
                .resolve("stages/" + TRANSCRIPT_STAGE + "_transcript.jsonl");
        if (!Files.exists(transcriptFile)) {
            return Collections.emptyList();
        }

        List<String> lines = Files.readAllLines(transcriptFile, StandardCharsets.UTF_8);
        List<Object> events = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(line);
                String type = node.path("type").asText("");
                if (isVisibleTranscriptType(type)) {
                    events.add(objectMapper.readValue(line, Object.class));
                }
            } catch (Exception ignored) {
            }
        }
        return events;
    }

    public boolean reportExists(String cveId) {
        Path reportFile = workspaceManager.getCvePath(cveId).resolve("report/report.md");
        return Files.exists(reportFile);
    }

    public boolean diffExists(String cveId) {
        Path diffFile = workspaceManager.getCvePath(cveId).resolve("patches/fix.diff");
        return Files.exists(diffFile);
    }

    private boolean isVisibleTranscriptType(String type) {
        return "assistant".equals(type)
                || "directive".equals(type)
                || "tool_results".equals(type)
                || "compact".equals(type);
    }

    private Set<String> loadAnalyzedFileNames(String cveId) {
        try {
            Path stage3 = workspaceManager.getStageFile(cveId, PipelineConstants.STAGE_AI_REASONING);
            if (!Files.exists(stage3)) {
                return Collections.emptySet();
            }
            JsonNode root = objectMapper.readTree(stage3.toFile());
            JsonNode files = root.path("analyzedFiles");
            if (!files.isArray()) {
                return Collections.emptySet();
            }
            Set<String> names = new HashSet<>();
            for (JsonNode fileNode : files) {
                String name = fileNode.path("fileName").asText("");
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            return names;
        } catch (Exception ignored) {
            return Collections.emptySet();
        }
    }

    private String filterDiff(String rawDiff, Set<String> analyzedFiles) {
        String[] sections = rawDiff.split("(?=diff --git )");
        StringBuilder builder = new StringBuilder();
        for (String section : sections) {
            if (section.trim().isEmpty()) {
                continue;
            }
            if (sectionMatchesAny(section, analyzedFiles)) {
                builder.append(section);
            }
        }
        return builder.toString();
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
            if (name.equals(leftPath) || name.equals(rightPath)) {
                return true;
            }
            String shortName = baseName(name);
            if (shortName.equals(leftName) || shortName.equals(rightName)) {
                return true;
            }
        }
        return false;
    }

    private String baseName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private int countDiffSections(String diff) {
        int count = 0;
        for (String section : diff.split("(?=diff --git )")) {
            if (!section.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private Object normalizeStageData(int stageNum, Object data) {
        if (stageNum != PipelineConstants.STAGE_ARTIFACTS || !(data instanceof Map)) {
            return data;
        }

        Map<String, Object> stage = (Map<String, Object>) data;
        Object vulnDemoObject = stage.get("vulnDemo");
        if (!(vulnDemoObject instanceof Map)) {
            return data;
        }

        Map<String, Object> vulnDemo = (Map<String, Object>) vulnDemoObject;
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
}
