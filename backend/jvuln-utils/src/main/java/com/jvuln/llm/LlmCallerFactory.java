package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.springframework.stereotype.Component;

@Component
public class LlmCallerFactory {

    public LlmProtocolCaller createCaller(LlmConfigProvider.ActiveConfig config,
                                          ObjectMapper mapper) {
        if (config == null) {
            throw new IllegalArgumentException("LLM config is required");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("ObjectMapper is required");
        }
        LlmEndpoint endpoint = LlmEndpoint.fromPath(config.getEndpoint());
        switch (endpoint) {
            case CHAT_COMPLETIONS:
                return new ChatCaller(config, mapper);
            case RESPONSES:
                return new ResponsesCaller(config, mapper);
            case MESSAGES:
                return new MessagesCaller(config, mapper);
            default:
                throw new IllegalArgumentException("Unsupported LLM endpoint: " + config.getEndpoint());
        }
    }
}
