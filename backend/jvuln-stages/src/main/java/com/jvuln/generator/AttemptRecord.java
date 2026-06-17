package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

class AttemptRecord {
        String event = "";
        String reason = "";
        int turns;
        int reviewRevisions;
        String compileStatus = "";
        String startupStatus = "";
        String pocStatus = "";
        String validatorSummary = "";

        static AttemptRecord fromContext(String event, String reason, String compileStatus,
                                         String startupStatus, String pocStatus, int turns,
                                         int reviewRevisions, ValidationResult validation) {
            AttemptRecord record = new AttemptRecord();
            record.event = event == null ? "" : event;
            record.reason = reason == null ? "" : reason;
            record.turns = turns;
            record.reviewRevisions = reviewRevisions;
            record.compileStatus = compileStatus == null ? "" : compileStatus;
            record.startupStatus = startupStatus == null ? "" : startupStatus;
            record.pocStatus = pocStatus == null ? "" : pocStatus;
            record.validatorSummary = validation == null ? "" : validation.summary();
            return record;
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("event", event);
            out.put("reason", reason);
            out.put("turns", turns);
            out.put("reviewRevisions", reviewRevisions);
            out.put("compileStatus", compileStatus);
            out.put("startupStatus", startupStatus);
            out.put("pocStatus", pocStatus);
            out.put("validatorSummary", validatorSummary);
            return out;
        }

        static AttemptRecord fromJson(JsonNode node) {
            AttemptRecord record = new AttemptRecord();
            record.event = node.path("event").asText("");
            record.reason = node.path("reason").asText("");
            record.turns = node.path("turns").asInt(0);
            record.reviewRevisions = node.path("reviewRevisions").asInt(0);
            record.compileStatus = node.path("compileStatus").asText("");
            record.startupStatus = node.path("startupStatus").asText("");
            record.pocStatus = node.path("pocStatus").asText("");
            record.validatorSummary = node.path("validatorSummary").asText("");
            return record;
        }
    }
