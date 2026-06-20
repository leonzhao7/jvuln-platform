package com.jvuln.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.pipeline.model.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
class LlmHelper {

    private static final Logger log = LoggerFactory.getLogger(LlmHelper.class);
    private static final int REVIEW_HISTORY_ITEMS = 6;

    private final ObjectMapper mapper;

    LlmHelper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    LlmResponse chatWithRetry(PipelineContext ctx, LlmRequest request, int maxAttempts) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return ctx.getLlmClient().chat(request);
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("LLM call attempt {} failed: {}. Retrying...", attempt, e.getMessage());
                    Thread.sleep(2000L * attempt);
                }
            }
        }
        throw new Exception("LLM call failed after " + maxAttempts + " attempts", lastException);
    }

    String renderJson(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    List<Map<String, Object>> recentHistory(List<ToolRun> history) {
        if (history.isEmpty()) {
            return Collections.emptyList();
        }
        int from = Math.max(0, history.size() - REVIEW_HISTORY_ITEMS);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = from; i < history.size(); i++) {
            items.add(history.get(i).toMap());
        }
        return items;
    }

    com.fasterxml.jackson.databind.JsonNode parseJsonObject(String raw) throws java.io.IOException {
        String s = stripMarkdownFence(raw);
        if (s.isEmpty()) {
            throw new java.io.IOException("Empty JSON response");
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(s);
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (Exception ignored) {
            // Fall through and try to extract the first balanced JSON object.
        }

        int start = s.indexOf('{');
        if (start < 0) {
            throw new java.io.IOException("No JSON object found in response: " + s.substring(0, Math.min(200, s.length())));
        }
        int end = findJsonObjectEnd(s, start);
        if (end < 0) {
            throw new java.io.IOException("Unbalanced JSON object in response: " + s.substring(0, Math.min(200, s.length())));
        }
        return mapper.readTree(s.substring(start, end + 1));
    }

    private String stripMarkdownFence(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        if (firstNewline < 0) {
            return s;
        }
        s = s.substring(firstNewline + 1);
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    private int findJsonObjectEnd(String s, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
