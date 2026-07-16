package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class PocStep {

    // Marker emitted by poc/exploit.sh: ##JV-STEP side=client phase=request label=Send upload
    private static final Pattern MARKER = Pattern.compile("^##JV-STEP\\b(.*)$");
    private static final Pattern ATTR_SIDE = Pattern.compile("\\bside=(\\S+)");
    private static final Pattern ATTR_PHASE = Pattern.compile("\\bphase=(\\S+)");
    private static final Pattern ATTR_LABEL = Pattern.compile("\\blabel=(.*)$");

    final String side;   // client | server
    final String phase;  // startup | request | response | verify
    final String label;
    final String body;

    PocStep(String side, String phase, String label, String body) {
        this.side = side == null ? "" : side;
        this.phase = phase == null ? "" : phase;
        this.label = label == null ? "" : label;
        this.body = body == null ? "" : body;
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("side", side);
        out.put("phase", phase);
        out.put("label", label);
        out.put("body", body);
        return out;
    }

    static List<Map<String, Object>> toMaps(List<PocStep> steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PocStep step : steps) {
            out.add(step.toMap());
        }
        return out;
    }

    static List<PocStep> fromJson(JsonNode node) {
        List<PocStep> steps = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return steps;
        }
        for (JsonNode item : node) {
            steps.add(new PocStep(
                    item.path("side").asText(""),
                    item.path("phase").asText(""),
                    item.path("label").asText(""),
                    item.path("body").asText("")));
        }
        return steps;
    }

    // Splits merged PoC stdout/stderr into structured steps by ##JV-STEP markers.
    // Returns an empty list when the script emits no markers (legacy scripts).
    static List<PocStep> parse(String output) {
        List<PocStep> steps = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return steps;
        }
        String[] lines = output.split("\n", -1);
        String side = null;
        String phase = null;
        String label = null;
        StringBuilder body = null;
        for (String line : lines) {
            Matcher marker = MARKER.matcher(line);
            if (marker.matches()) {
                if (body != null) {
                    steps.add(new PocStep(side, phase, label, stripTrailingBlank(body)));
                }
                String attrs = marker.group(1);
                side = attr(ATTR_SIDE, attrs);
                phase = attr(ATTR_PHASE, attrs);
                label = attr(ATTR_LABEL, attrs);
                body = new StringBuilder();
            } else if (body != null) {
                body.append(line).append('\n');
            }
        }
        if (body != null) {
            steps.add(new PocStep(side, phase, label, stripTrailingBlank(body)));
        }
        return steps;
    }

    private static String attr(Pattern pattern, String attrs) {
        Matcher m = pattern.matcher(attrs);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String stripTrailingBlank(StringBuilder sb) {
        int end = sb.length();
        while (end > 0 && (sb.charAt(end - 1) == '\n' || sb.charAt(end - 1) == '\r')) {
            end--;
        }
        return sb.substring(0, end);
    }
}
