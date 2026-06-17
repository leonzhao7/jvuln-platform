package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class AttemptMemory {
    private static final int MEMORY_RECORD_LIMIT = 8;

    int turns;
    int reviewRevisions;
    VerificationPlan verificationPlan;
    ExecutionPlan executionPlan;
    VerificationReview lastReview;
    ValidationResult lastValidation;
    String sessionSummary = "";
    final List<ToolRun> buildHistory = new ArrayList<>();
    final List<ToolRun> startupHistory = new ArrayList<>();
    final List<ToolRun> commandHistory = new ArrayList<>();
    final List<AttemptRecord> records = new ArrayList<>();

    boolean hasRecords() {
        return !records.isEmpty() || lastValidation != null || lastReview != null;
    }

    void addRecord(AttemptRecord record) {
        if (record == null) {
            return;
        }
        records.add(record);
        while (records.size() > MEMORY_RECORD_LIMIT) {
            records.remove(0);
        }
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("turns", turns);
        out.put("reviewRevisions", reviewRevisions);
        if (verificationPlan != null) out.put("verificationPlan", verificationPlan.toMap());
        if (executionPlan != null) out.put("executionPlan", executionPlan.toMap());
        if (lastReview != null) out.put("lastReview", lastReview.toMap());
        if (lastValidation != null) out.put("lastValidation", lastValidation.toMap());
        if (sessionSummary != null && !sessionSummary.trim().isEmpty()) out.put("sessionSummary", sessionSummary);
        out.put("buildHistory", ToolRun.toolRunsToMaps(buildHistory));
        out.put("startupHistory", ToolRun.toolRunsToMaps(startupHistory));
        out.put("commandHistory", ToolRun.toolRunsToMaps(commandHistory));
        List<Map<String, Object>> recordMaps = new ArrayList<>();
        for (AttemptRecord record : records) {
            recordMaps.add(record.toMap());
        }
        out.put("records", recordMaps);
        return out;
    }

    static AttemptMemory fromJson(JsonNode node) {
        AttemptMemory memory = new AttemptMemory();
        memory.turns = node.path("turns").asInt(0);
        memory.reviewRevisions = node.path("reviewRevisions").asInt(0);
        JsonNode vpNode = node.path("verificationPlan");
        if (!vpNode.isMissingNode() && !vpNode.isNull()) {
            memory.verificationPlan = VerificationPlan.fromJson(vpNode);
        }
        JsonNode epNode = node.path("executionPlan");
        if (!epNode.isMissingNode() && !epNode.isNull()) {
            memory.executionPlan = ExecutionPlan.fromJson(epNode);
        }
        JsonNode reviewNode = node.path("lastReview");
        if (!reviewNode.isMissingNode() && !reviewNode.isNull()) {
            memory.lastReview = VerificationReview.fromJson(reviewNode);
        }
        JsonNode validationNode = node.path("lastValidation");
        if (!validationNode.isMissingNode() && !validationNode.isNull()) {
            memory.lastValidation = ValidationResult.fromJson(validationNode);
        }
        memory.sessionSummary = node.path("sessionSummary").asText("");
        ToolRun.readToolRuns(node.path("buildHistory"), memory.buildHistory);
        ToolRun.readToolRuns(node.path("startupHistory"), memory.startupHistory);
        ToolRun.readToolRuns(node.path("commandHistory"), memory.commandHistory);
        JsonNode recordsNode = node.path("records");
        if (recordsNode.isArray()) {
            for (JsonNode recordNode : recordsNode) {
                memory.records.add(AttemptRecord.fromJson(recordNode));
            }
        }
        return memory;
    }
}
