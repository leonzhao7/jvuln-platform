package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ToolRun {
    final String kind;
    final String command;
    final int exitCode;
    final boolean success;
    final String output;

    private ToolRun(String kind, String command, int exitCode, boolean success, String output) {
        this.kind = kind;
        this.command = command;
        this.exitCode = exitCode;
        this.success = success;
        this.output = output;
    }

    static ToolRun build(int exitCode, String output) {
        return new ToolRun("build", "bash build.sh", exitCode, exitCode == 0, output);
    }

    static ToolRun startup(boolean success, int exitCode, String output) {
        return new ToolRun("startup", "bash run.sh", exitCode, success, output);
    }

    static ToolRun command(String command, int exitCode, String output) {
        return new ToolRun("command", command, exitCode, exitCode == 0, output);
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("command", command);
        out.put("exitCode", exitCode);
        out.put("success", success);
        out.put("output", output);
        return out;
    }

    static List<Map<String, Object>> toolRunsToMaps(List<ToolRun> runs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolRun run : runs) {
            out.add(run.toMap());
        }
        return out;
    }

    static void readToolRuns(JsonNode node, List<ToolRun> target) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            target.add(new ToolRun(
                    item.path("kind").asText(""),
                    item.path("command").asText(""),
                    item.path("exitCode").asInt(-1),
                    item.path("success").asBoolean(false),
                    item.path("output").asText("")));
        }
    }
}
