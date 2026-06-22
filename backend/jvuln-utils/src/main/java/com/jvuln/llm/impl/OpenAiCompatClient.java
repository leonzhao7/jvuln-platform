package com.jvuln.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmAdapter;
import com.jvuln.llm.LlmAdapterFactory;
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
    private final LlmAdapterFactory adapterFactory;
    private final ObjectMapper mapper;

    private final String fallbackBaseUrl;
    private final String fallbackApiKey;
    private final String fallbackModel;

    public OpenAiCompatClient(
            LlmConfigProvider configProvider,
            LlmAdapterFactory adapterFactory,
            ObjectMapper mapper,
            @Value("${jvuln.llm.base-url:http://localhost:11434/v1}") String fallbackBaseUrl,
            @Value("${jvuln.llm.api-key:}") String fallbackApiKey,
            @Value("${jvuln.llm.model:deepseek-coder}") String fallbackModel) {
        this.configProvider = configProvider;
        this.adapterFactory = adapterFactory;
        this.mapper = mapper;
        this.fallbackBaseUrl = fallbackBaseUrl;
        this.fallbackApiKey = fallbackApiKey;
        this.fallbackModel = fallbackModel;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        LlmAdapter adapter = getAdapter();
        return adapter.chat(request);
    }

    @Override
    public Flux<String> chatStream(LlmRequest request) {
        LlmAdapter adapter = getAdapter();
        return adapter.chatStream(request);
    }

    private LlmAdapter getAdapter() {
        LlmConfigProvider.ActiveConfig cfg = configProvider.getActive();
        if (cfg == null) {
            cfg = new LlmConfigProvider.ActiveConfig(
                "openai",
                fallbackBaseUrl,
                fallbackApiKey,
                fallbackModel
            );
        }
        log.debug("Creating LLM adapter for model: {}", cfg.getModel());
        return adapterFactory.createAdapter(cfg, mapper);
    }
}
