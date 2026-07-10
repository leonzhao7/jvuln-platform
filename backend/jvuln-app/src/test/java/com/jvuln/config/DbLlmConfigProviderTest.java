package com.jvuln.config;

import com.jvuln.llm.LlmEndpoint;
import com.jvuln.llm.impl.LlmConfigProvider;
import com.jvuln.store.LlmConfigRepository;
import com.jvuln.store.entity.LlmConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbLlmConfigProviderTest {

    private final LlmConfigRepository repository = mock(LlmConfigRepository.class);
    private final DbLlmConfigProvider provider = new DbLlmConfigProvider(repository);

    @Test
    void returnsConfiguredEndpointUnchanged() {
        LlmConfig config = activeConfig(LlmEndpoint.RESPONSES.getPath());
        when(repository.findByActiveTrue()).thenReturn(Optional.of(config));

        LlmConfigProvider.ActiveConfig active = provider.getActive();

        assertEquals(LlmEndpoint.RESPONSES.getPath(), active.getEndpoint());
    }

    @Test
    void rejectsActiveConfigWithoutEndpoint() {
        when(repository.findByActiveTrue()).thenReturn(Optional.of(activeConfig(null)));

        assertThrows(IllegalStateException.class, provider::getActive);
    }

    @Test
    void rejectsActiveConfigWithUnknownEndpoint() {
        when(repository.findByActiveTrue()).thenReturn(Optional.of(activeConfig("/v1/unknown")));

        assertThrows(IllegalStateException.class, provider::getActive);
    }

    private LlmConfig activeConfig(String endpoint) {
        LlmConfig config = new LlmConfig();
        config.setProviderType("openai");
        config.setBaseUrl("https://example.test/v1");
        config.setApiKey("secret");
        config.setModel("model");
        config.setEndpoint(endpoint);
        config.setActive(true);
        return config;
    }
}
