package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AnthropicAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testAdapterProperties() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "anthropic",
            "https://api.anthropic.com",
            "test-key",
            "claude-opus-4-6"
        );

        AnthropicAdapter adapter = new AnthropicAdapter(config, mapper);

        assertEquals("Anthropic (claude-opus-4-6)", adapter.getName());
        assertTrue(adapter.supportsToolCalling());
    }

    @Test
    public void testClaudeSonnetModel() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "anthropic",
            "https://api.anthropic.com",
            "test-key",
            "claude-3-5-sonnet-20241022"
        );

        AnthropicAdapter adapter = new AnthropicAdapter(config, mapper);

        assertTrue(adapter.getName().contains("claude-3-5-sonnet"));
    }
}
