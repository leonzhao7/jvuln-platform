package com.jvuln.llm;

/**
 * The four pipeline stages that own stage-level LLM prompts.
 */
public enum LlmPromptStage {

    INTELLIGENCE("intelligence"),
    PATCH_ANALYSIS("patch-analysis"),
    REASONING("reasoning"),
    ARTIFACT_GENERATION("artifact-generation");

    private final String resourceName;

    LlmPromptStage(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getResourcePath() {
        return "prompts/stages/" + resourceName + ".md";
    }
}
