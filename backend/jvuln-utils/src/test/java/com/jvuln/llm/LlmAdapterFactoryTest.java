package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LlmAdapterFactory
 *
 * Tests adapter creation logic:
 * - Anthropic detection by providerType
 * - Anthropic detection by model name patterns
 * - OpenAI-compatible fallback for other models
 * - Null config validation
 */
public class LlmAdapterFactoryTest {

    private LlmAdapterFactory factory;
    private ObjectMapper mapper;

    @BeforeEach
    public void setUp() {
        factory = new LlmAdapterFactory();
        mapper = new ObjectMapper();
    }

    /**
     * Test: Anthropic adapter creation when providerType is "anthropic"
     */
    @Test
    public void testCreateAnthropicAdapterByProviderType() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "anthropic",
            "https://api.anthropic.com",
            "test-key",
            "claude-3-opus-20240229"
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        assertNotNull(adapter, "Adapter should not be null");
        assertTrue(adapter instanceof AnthropicAdapter, "Should create AnthropicAdapter");
        assertEquals("Anthropic (claude-3-opus-20240229)", adapter.getName());
        assertTrue(adapter.supportsToolCalling(), "Anthropic adapter should support tool calling");
    }

    /**
     * Test: Anthropic adapter creation by model name (claude-opus-4-6)
     */
    @Test
    public void testCreateAnthropicAdapterByModelName() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            null,
            "https://api.anthropic.com",
            "test-key",
            "claude-opus-4-6"
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        assertNotNull(adapter, "Adapter should not be null");
        assertTrue(adapter instanceof AnthropicAdapter, "Should create AnthropicAdapter for claude-opus model");
        assertTrue(adapter.getName().contains("claude-opus-4-6"), "Name should contain model name");
        assertTrue(adapter.supportsToolCalling());
    }

    /**
     * Test: Anthropic adapter creation for claude-sonnet model
     */
    @Test
    public void testCreateAnthropicAdapterForSonnetModel() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            null,
            "https://api.anthropic.com",
            "test-key",
            "claude-sonnet-3-5"
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        assertTrue(adapter instanceof AnthropicAdapter, "Should create AnthropicAdapter for sonnet model");
        assertTrue(adapter.getName().contains("sonnet"), "Name should contain 'sonnet'");
    }

    /**
     * Test: Anthropic adapter creation for claude-haiku model
     */
    @Test
    public void testCreateAnthropicAdapterForHaikuModel() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "openai",
            "https://api.anthropic.com",
            "test-key",
            "claude-haiku-3-5"
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        // Model name takes precedence over providerType
        assertTrue(adapter instanceof AnthropicAdapter, "Should create AnthropicAdapter for haiku model even with openai providerType");
        assertTrue(adapter.getName().contains("haiku"), "Name should contain 'haiku'");
    }

    /**
     * Test: Anthropic adapter creation for model containing "opus" substring
     */
    @Test
    public void testCreateAnthropicAdapterForOpusSubstring() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            null,
            "https://api.anthropic.com",
            "test-key",
            "some-opus-model"
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        assertTrue(adapter instanceof AnthropicAdapter, "Should create AnthropicAdapter for model containing 'opus'");
    }

    /**
     * Test: OpenAI-compatible adapter creation for OpenAI model
     */
    @Test
    public void testCreateOpenAICompatibleAdapter() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "openai",
            "https://api.openai.com/v1",
            "test-key",
            "gpt-5.4"
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        assertNotNull(adapter, "Adapter should not be null");
        assertTrue(adapter instanceof OpenAICompatibleAdapter, "Should create OpenAICompatibleAdapter");
        assertTrue(adapter.getName().contains("gpt-5.4"), "Name should contain model name");
        assertTrue(adapter.supportsToolCalling(), "OpenAI adapter should support tool calling");
    }

    /**
     * Test: OpenAI-compatible adapter creation for DeepSeek model by providerType
     */
    @Test
    public void testCreateDeepSeekAdapterByProviderType() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "deepseek",
            "https://api.deepseek.com/v1",
            "test-key",
            "deepseek-v4"
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        assertTrue(adapter instanceof OpenAICompatibleAdapter, "Should create OpenAICompatibleAdapter for DeepSeek");
        assertTrue(adapter.getName().contains("deepseek-v4"), "Name should contain model name");
    }

    /**
     * Test: OpenAI-compatible adapter creation for DeepSeek model by model name
     */
    @Test
    public void testCreateDeepSeekAdapterByModelName() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            null,
            "https://api.deepseek.com/v1",
            "test-key",
            "deepseek-v4"
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        assertTrue(adapter instanceof OpenAICompatibleAdapter, "Should create OpenAICompatibleAdapter for deepseek model");
        assertTrue(adapter.getName().contains("deepseek-v4"));
    }

    /**
     * Test: OpenAI-compatible adapter for generic model
     */
    @Test
    public void testCreateOpenAICompatibleAdapterForGenericModel() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            null,
            "https://api.example.com/v1",
            "test-key",
            "generic-model-v1"
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        assertTrue(adapter instanceof OpenAICompatibleAdapter, "Should fallback to OpenAICompatibleAdapter for unknown models");
    }

    /**
     * Test: Null config throws IllegalArgumentException
     */
    @Test
    public void testNullConfigThrowsException() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> factory.createAdapter(null, mapper),
            "Should throw IllegalArgumentException for null config"
        );

        assertEquals("LLM config is required", exception.getMessage());
    }

    /**
     * Test: Null ObjectMapper throws IllegalArgumentException
     */
    @Test
    public void testNullMapperThrowsException() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "openai",
            "https://api.openai.com/v1",
            "test-key",
            "gpt-4"
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> factory.createAdapter(config, null),
            "Should throw IllegalArgumentException for null ObjectMapper"
        );

        assertEquals("ObjectMapper is required", exception.getMessage());
    }

    /**
     * Test: Case-insensitive model name detection for Anthropic
     */
    @Test
    public void testAnthropicModelDetectionCaseInsensitive() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            null,
            "https://api.anthropic.com",
            "test-key",
            "CLAUDE-OPUS-4-6"
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        assertTrue(adapter instanceof AnthropicAdapter, "Should detect Anthropic model case-insensitively");
    }

    /**
     * Test: Null model name with anthropic providerType
     */
    @Test
    public void testAnthropicProviderTypeWithNullModel() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "anthropic",
            "https://api.anthropic.com",
            "test-key",
            null
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        assertTrue(adapter instanceof AnthropicAdapter, "Should create AnthropicAdapter based on providerType even with null model");
    }

    /**
     * Test: Empty model name falls back to OpenAI-compatible
     */
    @Test
    public void testEmptyModelNameFallsBackToOpenAI() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            null,
            "https://api.example.com/v1",
            "test-key",
            ""
        );

        LlmAdapter adapter = factory.createAdapter(config, mapper);

        assertTrue(adapter instanceof OpenAICompatibleAdapter, "Should fallback to OpenAICompatibleAdapter for empty model name");
    }
}
