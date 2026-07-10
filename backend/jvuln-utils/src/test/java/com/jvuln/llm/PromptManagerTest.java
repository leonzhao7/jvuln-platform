package com.jvuln.llm;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptManagerTest {

    private final PromptManager promptManager = new PromptManager(new DefaultResourceLoader());

    @Test
    void combinesGlobalStageAndCurrentInThatOrder() {
        assertEquals("global\n\nreasoning\n\ncurrent",
                promptManager.resolve(LlmPromptStage.REASONING, "current"));
    }

    @Test
    void omitsBlankCurrentPromptWithoutTrailingSeparator() {
        assertEquals("global\n\nreasoning",
                promptManager.resolve(LlmPromptStage.REASONING, "  "));
    }

    @Test
    void reportsMissingResourcePath() {
        PromptManager missing = new PromptManager(new DefaultResourceLoader(), "missing/");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> missing.resolve(LlmPromptStage.REASONING, "current"));

        assertTrue(exception.getMessage().contains("missing/prompts/global.md"));
    }
}
