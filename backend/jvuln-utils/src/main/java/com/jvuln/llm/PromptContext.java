package com.jvuln.llm;

public final class PromptContext {

    private final String globalPrompt;
    private final String stagePrompt;

    public PromptContext(String globalPrompt, String stagePrompt) {
        if (globalPrompt == null || globalPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("Global prompt is required");
        }
        this.globalPrompt = globalPrompt;
        this.stagePrompt = stagePrompt;
    }

    public String getGlobalPrompt() {
        return globalPrompt;
    }

    public String getStagePrompt() {
        return stagePrompt;
    }
}
