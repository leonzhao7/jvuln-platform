package com.jvuln.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LlmResponse {

    private final String content;
    private final int promptTokens;
    private final int completionTokens;
    private final String model;
    private final String finishReason;
    private final List<LlmRequest.ContentBlock> contentBlocks;

    public LlmResponse(String content, int promptTokens, int completionTokens,
                       String model, String finishReason) {
        this(content, promptTokens, completionTokens, model, finishReason, Collections.emptyList());
    }

    public LlmResponse(String content, int promptTokens, int completionTokens,
                       String model, String finishReason, List<LlmRequest.ContentBlock> contentBlocks) {
        this.content = content;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.model = model;
        this.finishReason = finishReason;
        this.contentBlocks = contentBlocks != null ? contentBlocks : Collections.emptyList();
    }

    public String getContent() { return content; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public String getModel() { return model; }
    public String getFinishReason() { return finishReason; }
    public List<LlmRequest.ContentBlock> getContentBlocks() { return contentBlocks; }

    public boolean hasToolUse() {
        for (LlmRequest.ContentBlock b : contentBlocks) {
            if ("tool_use".equals(b.getType())) return true;
        }
        return false;
    }

    public List<LlmRequest.ContentBlock> getToolUses() {
        List<LlmRequest.ContentBlock> result = new ArrayList<>();
        for (LlmRequest.ContentBlock b : contentBlocks) {
            if ("tool_use".equals(b.getType())) result.add(b);
        }
        return result;
    }
}
