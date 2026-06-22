package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OpenAICompatibleAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testAdapterProperties() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "openai",
            "https://api.openai.com/v1",
            "test-key",
            "gpt-5.4"
        );

        OpenAICompatibleAdapter adapter = new OpenAICompatibleAdapter(config, mapper);

        assertEquals("OpenAI-Compatible (gpt-5.4)", adapter.getName());
        assertTrue(adapter.supportsToolCalling());
    }

    @Test
    public void testDeepSeekModel() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "openai",
            "https://api.deepseek.com/v1",
            "test-key",
            "deepseek-v4"
        );

        OpenAICompatibleAdapter adapter = new OpenAICompatibleAdapter(config, mapper);

        assertTrue(adapter.getName().contains("deepseek-v4"));
        assertTrue(adapter.supportsToolCalling());
    }
}
