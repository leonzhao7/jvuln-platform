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
}
