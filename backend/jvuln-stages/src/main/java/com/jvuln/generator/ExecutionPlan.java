package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jvuln.generator.ArtifactGenUtils.*;

class ExecutionPlan {
    final String goal;
    final List<String> firstBatchFiles;
    final List<String> minimalDeliverables;
    final List<String> validationSequence;
    final List<String> deferredUntilVerified;
    final List<String> risks;
    final String reportStrategy;

    private ExecutionPlan(String goal, List<String> firstBatchFiles, List<String> minimalDeliverables,
                      List<String> validationSequence, List<String> deferredUntilVerified,
                      List<String> risks, String reportStrategy) {
        this.goal = goal == null ? "" : goal.trim();
        this.firstBatchFiles = normalizeList(firstBatchFiles);
        this.minimalDeliverables = normalizeList(minimalDeliverables);
        this.validationSequence = normalizeList(validationSequence);
        this.deferredUntilVerified = normalizeList(deferredUntilVerified);
        this.risks = normalizeList(risks);
        this.reportStrategy = reportStrategy == null ? "" : reportStrategy.trim();
        }

    static ExecutionPlan fromJson(JsonNode node) {
        return new ExecutionPlan(
                node.path("goal").asText(""),
                readList(node.path("firstBatchFiles")),
                readList(node.path("minimalDeliverables")),
                readList(node.path("validationSequence")),
                readList(node.path("deferredUntilVerified")),
                readList(node.path("risks")),
                node.path("reportStrategy").asText("")
        );
        }

        boolean isUsable() {
        return !goal.isEmpty() && !firstBatchFiles.isEmpty() && !validationSequence.isEmpty() && !reportStrategy.isEmpty();
        }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("goal", goal);
        out.put("firstBatchFiles", firstBatchFiles);
        out.put("minimalDeliverables", minimalDeliverables);
        out.put("validationSequence", validationSequence);
        out.put("deferredUntilVerified", deferredUntilVerified);
        out.put("risks", risks);
        out.put("reportStrategy", reportStrategy);
        return out;
        }
    }
