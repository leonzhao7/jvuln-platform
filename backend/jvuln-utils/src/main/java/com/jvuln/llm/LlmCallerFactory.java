package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import com.jvuln.util.DbProxyConfigProvider;
import org.springframework.stereotype.Component;

@Component
public class LlmCallerFactory {

    private final LlmAuditLogger auditLogger;
    private final DbProxyConfigProvider proxyConfigProvider;

    public LlmCallerFactory(LlmAuditLogger auditLogger, DbProxyConfigProvider proxyConfigProvider) {
        this.auditLogger = auditLogger;
        this.proxyConfigProvider = proxyConfigProvider;
    }

    public LlmProtocolCaller createCaller(LlmConfigProvider.ActiveConfig config,
                                          ObjectMapper mapper) {
        if (config == null) {
            throw new IllegalArgumentException("LLM config is required");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("ObjectMapper is required");
        }
        int llmTimeoutMs = proxyConfigProvider != null
                ? proxyConfigProvider.getProxyConfig().getLlmTimeout() : 300000;
        LlmEndpoint endpoint = LlmEndpoint.fromPath(config.getEndpoint());
        switch (endpoint) {
            case CHAT_COMPLETIONS:
                return new ChatCaller(config, mapper, auditLogger, llmTimeoutMs);
            case RESPONSES:
                return new ResponsesCaller(config, mapper, auditLogger, llmTimeoutMs);
            case MESSAGES:
                return new MessagesCaller(config, mapper, auditLogger, llmTimeoutMs);
            default:
                throw new IllegalArgumentException("Unsupported LLM endpoint: " + config.getEndpoint());
        }
    }
}
