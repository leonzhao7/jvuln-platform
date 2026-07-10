package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmCallerFactoryTest {

    private final LlmCallerFactory factory = new LlmCallerFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void selectsChatCallerOnlyFromEndpoint() {
        LlmProtocolCaller caller = factory.createCaller(config(
                "anthropic", "claude-opus", LlmEndpoint.CHAT_COMPLETIONS.getPath()), mapper);

        assertTrue(caller instanceof ChatCaller);
    }

    @Test
    void selectsResponsesCallerOnlyFromEndpoint() {
        LlmProtocolCaller caller = factory.createCaller(config(
                "anthropic", "claude-opus", LlmEndpoint.RESPONSES.getPath()), mapper);

        assertTrue(caller instanceof ResponsesCaller);
    }

    @Test
    void selectsMessagesCallerOnlyFromEndpoint() {
        LlmProtocolCaller caller = factory.createCaller(config(
                "openai", "gpt-5.4", LlmEndpoint.MESSAGES.getPath()), mapper);

        assertTrue(caller instanceof MessagesCaller);
    }

    @Test
    void rejectsMissingAndUnknownEndpoints() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.createCaller(config("openai", "gpt", null), mapper));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createCaller(config("openai", "gpt", "/v1/unknown"), mapper));
    }

    @Test
    void validatesRequiredArguments() {
        assertThrows(IllegalArgumentException.class, () -> factory.createCaller(null, mapper));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createCaller(config("openai", "gpt",
                        LlmEndpoint.RESPONSES.getPath()), null));
    }

    private LlmConfigProvider.ActiveConfig config(String provider, String model, String endpoint) {
        return new LlmConfigProvider.ActiveConfig(provider, "http://localhost", "key", model, endpoint);
    }
}
