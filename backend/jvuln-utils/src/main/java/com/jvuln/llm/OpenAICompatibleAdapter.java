package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * OpenAI 兼容 API 适配器
 *
 * 支持所有遵循 OpenAI API 规范的 LLM 提供商：
 * - OpenAI (gpt-5.4, gpt-4o, etc.)
 * - DeepSeek (deepseek-v4, deepseek-chat, etc.)
 * - 其他 OpenAI-compatible 提供商
 */
public class OpenAICompatibleAdapter implements LlmAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleAdapter.class);

    private final LlmCaller caller;
    private final String model;

    public OpenAICompatibleAdapter(LlmConfigProvider.ActiveConfig config, ObjectMapper mapper) {
        this.caller = new LlmCaller(
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
        return "OpenAI-Compatible (" + model + ")";
    }
}
