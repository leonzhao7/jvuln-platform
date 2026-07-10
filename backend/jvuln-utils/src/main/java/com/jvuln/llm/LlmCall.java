package com.jvuln.llm;

public final class LlmCall {

    private final LlmRequest request;
    private final PromptContext promptContext;

    public LlmCall(LlmRequest request, PromptContext promptContext) {
        if (request == null) {
            throw new IllegalArgumentException("LLM request is required");
        }
        if (promptContext == null) {
            throw new IllegalArgumentException("Prompt context is required");
        }
        this.request = request;
        this.promptContext = promptContext;
    }

    public LlmRequest getRequest() {
        return request;
    }

    public PromptContext getPromptContext() {
        return promptContext;
    }
}
