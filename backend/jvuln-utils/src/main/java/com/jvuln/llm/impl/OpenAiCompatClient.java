package com.jvuln.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmCall;
import com.jvuln.llm.LlmCallerFactory;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmProtocolCaller;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class OpenAiCompatClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatClient.class);

    private final LlmConfigProvider configProvider;
    private final LlmCallerFactory callerFactory;
    private final ObjectMapper mapper;
    private final PromptManager promptManager;
    private final String fallbackBaseUrl;
    private final String fallbackApiKey;
    private final String fallbackModel;
    private final String fallbackEndpoint;

    public OpenAiCompatClient(
            LlmConfigProvider configProvider,
            LlmCallerFactory callerFactory,
            ObjectMapper mapper,
            PromptManager promptManager,
            @Value("${jvuln.llm.base-url:http://localhost:11434/v1}") String fallbackBaseUrl,
            @Value("${jvuln.llm.api-key:}") String fallbackApiKey,
            @Value("${jvuln.llm.model:deepseek-coder}") String fallbackModel,
            @Value("${jvuln.llm.endpoint:/v1/chat/completions}") String fallbackEndpoint) {
        this.configProvider = configProvider;
        this.callerFactory = callerFactory;
        this.mapper = mapper;
        this.promptManager = promptManager;
        this.fallbackBaseUrl = fallbackBaseUrl;
        this.fallbackApiKey = fallbackApiKey;
        this.fallbackModel = fallbackModel;
        this.fallbackEndpoint = fallbackEndpoint;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        return chat(activeConfig(), request);
    }

    public LlmResponse chat(LlmConfigProvider.ActiveConfig config, LlmRequest request) {
        LlmProtocolCaller caller = callerFactory.createCaller(config, mapper);
        log.debug("Calling {} via {}", config.getModel(), config.getEndpoint());
        return caller.chat(resolveCall(request));
    }

    @Override
    public Flux<String> chatStream(LlmRequest request) {
        LlmConfigProvider.ActiveConfig config = activeConfig();
        LlmProtocolCaller caller = callerFactory.createCaller(config, mapper);
        log.debug("Streaming {} via {}", config.getModel(), config.getEndpoint());
        return caller.chatStream(resolveCall(request));
    }

    private LlmCall resolveCall(LlmRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("LLM request is required");
        }
        return new LlmCall(request, promptManager.resolve(request.getStage()));
    }

    private LlmConfigProvider.ActiveConfig activeConfig() {
        LlmConfigProvider.ActiveConfig config = configProvider.getActive();
        if (config != null) {
            return config;
        }
        return new LlmConfigProvider.ActiveConfig(
                fallbackBaseUrl, fallbackApiKey, fallbackModel, fallbackEndpoint);
    }
}
