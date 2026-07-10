package com.jvuln.prompts;

import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.PromptContext;
import com.jvuln.llm.PromptManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertFalse;

class StagePromptResourcesTest {

    @Test
    void everyPipelineStageHasAResolvablePromptLayer() {
        PromptManager manager = new PromptManager(new DefaultResourceLoader());

        for (LlmPromptStage stage : LlmPromptStage.values()) {
            PromptContext context = manager.resolve(stage);
            assertFalse(context.getGlobalPrompt().trim().isEmpty());
            assertFalse(context.getStagePrompt().trim().isEmpty());
        }
    }
}
