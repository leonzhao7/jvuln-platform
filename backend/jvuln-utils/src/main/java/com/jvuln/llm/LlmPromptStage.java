package com.jvuln.llm;

/**
 * The five pipeline stages that own stage-level LLM prompts.
 */
public enum LlmPromptStage {

    INTELLIGENCE("intelligence"),
    PATCH_ANALYSIS("patch-analysis"),
    REASONING("reasoning"),
    ARTIFACT_GENERATION("artifact-generation"),
    REPORT_GENERATION("report");

    private final String resourceName;

    LlmPromptStage(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getResourcePath() {
        return "prompts/stages/" + resourceName + ".md";
    }
}
