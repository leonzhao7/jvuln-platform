package com.jvuln.llm;

public class LlmResponse {

    private final String content;
    private final int promptTokens;
    private final int completionTokens;
    private final String model;
    private final String finishReason;

    public LlmResponse(String content, int promptTokens, int completionTokens,
                       String model, String finishReason) {
        this.content = content;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.model = model;
        this.finishReason = finishReason;
    }

    public String getContent() { return content; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public String getModel() { return model; }
    public String getFinishReason() { return finishReason; }
}
