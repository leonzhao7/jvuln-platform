package com.jvuln.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmCaller;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class OpenAiCompatClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatClient.class);

    private final LlmConfigProvider configProvider;
    private final ObjectMapper mapper;

    private final String fallbackBaseUrl;
    private final String fallbackApiKey;
    private final String fallbackModel;

    public OpenAiCompatClient(
            LlmConfigProvider configProvider,
            ObjectMapper mapper,
            @Value("${jvuln.llm.base-url:http://localhost:11434/v1}") String fallbackBaseUrl,
            @Value("${jvuln.llm.api-key:}") String fallbackApiKey,
            @Value("${jvuln.llm.model:deepseek-coder}") String fallbackModel) {
        this.configProvider = configProvider;
        this.mapper = mapper;
        this.fallbackBaseUrl = fallbackBaseUrl;
        this.fallbackApiKey = fallbackApiKey;
        this.fallbackModel = fallbackModel;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        LlmConfigProvider.ActiveConfig cfg = configProvider.getActive();
        if (cfg != null && "anthropic".equals(cfg.getProviderType())) {
            return new AnthropicCaller(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(), mapper).chat(request);
        }
        return openAiCaller(cfg).chat(request);
    }

    @Override
    public Flux<String> chatStream(LlmRequest request) {
        LlmConfigProvider.ActiveConfig cfg = configProvider.getActive();
        if (cfg != null && "anthropic".equals(cfg.getProviderType())) {
            return new AnthropicCaller(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(), mapper).chatStream(request);
        }
        return openAiCaller(cfg).chatStream(request);
    }

    private LlmCaller openAiCaller(LlmConfigProvider.ActiveConfig cfg) {
        String baseUrl = (cfg != null && cfg.getBaseUrl() != null) ? cfg.getBaseUrl() : fallbackBaseUrl;
        String apiKey  = (cfg != null && cfg.getApiKey()  != null) ? cfg.getApiKey()  : fallbackApiKey;
        String model   = (cfg != null && cfg.getModel()   != null) ? cfg.getModel()   : fallbackModel;
        log.debug("LLM caller (openai-compat): baseUrl={} model={}", baseUrl, model);
        return new LlmCaller(baseUrl, apiKey, model, mapper);
    }
}
