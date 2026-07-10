package com.jvuln.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmEndpointTest {

    @Test
    void acceptsOnlyCanonicalEndpointPaths() {
        assertEquals(LlmEndpoint.CHAT_COMPLETIONS,
                LlmEndpoint.fromPath("/v1/chat/completions"));
        assertEquals(LlmEndpoint.RESPONSES,
                LlmEndpoint.fromPath("/v1/responses"));
        assertEquals(LlmEndpoint.MESSAGES,
                LlmEndpoint.fromPath("/v1/messages"));

        assertThrows(IllegalArgumentException.class,
                () -> LlmEndpoint.fromPath("responses"));
        assertThrows(IllegalArgumentException.class,
                () -> LlmEndpoint.fromPath(null));
    }

    @Test
    void replacesKnownSuffixWhenResolvingUri() {
        assertEquals("https://proxy.example/openai/v1/responses",
                LlmEndpoint.RESPONSES.resolveUri(
                        "https://proxy.example/openai/v1/chat/completions"));
    }

    @Test
    void preservesProxyPrefixWhenBaseEndsInV1() {
        assertEquals("https://proxy.example/openai/v1/messages",
                LlmEndpoint.MESSAGES.resolveUri("https://proxy.example/openai/v1/"));
    }

    @Test
    void rejectsMissingBaseUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmEndpoint.RESPONSES.resolveUri("  "));
    }
}
