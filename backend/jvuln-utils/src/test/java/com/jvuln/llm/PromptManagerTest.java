package com.jvuln.llm;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptManagerTest {

    private final PromptManager promptManager = new PromptManager(new DefaultResourceLoader());

    @Test
    void resolvesGlobalAndStageAsSeparateValues() {
        PromptContext context = promptManager.resolve(LlmPromptStage.REASONING);

        assertEquals("global", context.getGlobalPrompt());
        assertEquals("reasoning", context.getStagePrompt());
    }

    @Test
    void diagnosticContextContainsGlobalWithoutStage() {
        PromptContext context = promptManager.resolve(null);

        assertEquals("global", context.getGlobalPrompt());
        assertNull(context.getStagePrompt());
    }

    @Test
    void reportsMissingResourcePath() {
        PromptManager missing = new PromptManager(new DefaultResourceLoader(), "missing/");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> missing.resolve(LlmPromptStage.REASONING));

        assertTrue(exception.getMessage().contains("missing/prompts/global.md"));
    }
}
