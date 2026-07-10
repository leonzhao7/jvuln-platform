package com.jvuln.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptRegistryTest {

    @Test
    void loadsCurrentPromptFromMarkdownClasspathResource() {
        PromptRegistry registry = new PromptRegistry();

        assertEquals("template", registry.getPrompt("current/template"));
    }
}
