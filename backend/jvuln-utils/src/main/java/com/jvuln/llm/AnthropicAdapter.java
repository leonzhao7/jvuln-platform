package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.AnthropicCaller;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Anthropic API 适配器
 *
 * 支持 Claude 系列模型：
 * - claude-opus-4-6
 * - claude-sonnet-*
 * - claude-haiku-*
 */
public class AnthropicAdapter implements LlmAdapter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAdapter.class);

    private final AnthropicCaller caller;
    private final String model;

    public AnthropicAdapter(LlmConfigProvider.ActiveConfig config, ObjectMapper mapper) {
        this.caller = new AnthropicCaller(
            config.getBaseUrl(),
            config.getApiKey(),
            config.getModel(),
            mapper
        );
        this.model = config.getModel();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        return caller.chat(request);
    }

    @Override
    public Flux<String> chatStream(LlmRequest request) {
        return caller.chatStream(request);
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    @Override
    public String getName() {
        return "Anthropic (" + model + ")";
    }
}
