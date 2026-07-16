package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ValidationResult {
        final String focus;
        boolean compileOk;
        boolean startupOk;
        boolean pocVerified;
        String compileMessage = "";
        String startupMessage = "";
        String pocMessage = "";
        final List<PocStep> pocSteps = new ArrayList<>();
        final Map<String, Object> artifacts = new LinkedHashMap<>();

        ValidationResult(String focus) {
            this.focus = focus == null ? "full" : focus;
        }

        void mergeFrom(ValidationResult other) {
            if (other == null) {
                return;
            }
            this.compileOk = this.compileOk || other.compileOk;
            this.startupOk = this.startupOk || other.startupOk;
            this.pocVerified = this.pocVerified || other.pocVerified;
            if (this.compileMessage.isEmpty()) this.compileMessage = other.compileMessage;
            if (this.startupMessage.isEmpty()) this.startupMessage = other.startupMessage;
            if (this.pocMessage.isEmpty()) this.pocMessage = other.pocMessage;
            if (this.pocSteps.isEmpty()) this.pocSteps.addAll(other.pocSteps);
            this.artifacts.putAll(other.artifacts);
        }

        String summary() {
            return "compileOk=" + compileOk + ", startupOk=" + startupOk + ", pocVerified=" + pocVerified
                    + (pocMessage.isEmpty() ? "" : ", pocMessage=" + pocMessage);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("focus", focus);
            out.put("compileOk", compileOk);
            out.put("startupOk", startupOk);
            out.put("pocVerified", pocVerified);
            out.put("compileMessage", compileMessage);
            out.put("startupMessage", startupMessage);
            out.put("pocMessage", pocMessage);
            out.put("pocSteps", PocStep.toMaps(pocSteps));
            out.put("artifacts", artifacts);
            return out;
        }

        static ValidationResult fromJson(JsonNode node) {
            ValidationResult result = new ValidationResult(node.path("focus").asText("full"));
            result.compileOk = node.path("compileOk").asBoolean(false);
            result.startupOk = node.path("startupOk").asBoolean(false);
            result.pocVerified = node.path("pocVerified").asBoolean(false);
            result.compileMessage = node.path("compileMessage").asText("");
            result.startupMessage = node.path("startupMessage").asText("");
            result.pocMessage = node.path("pocMessage").asText("");
            result.pocSteps.addAll(PocStep.fromJson(node.path("pocSteps")));
            JsonNode artifactsNode = node.path("artifacts");
            if (artifactsNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = artifactsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    result.artifacts.put(field.getKey(), field.getValue());
                }
            }
            return result;
        }
    }
