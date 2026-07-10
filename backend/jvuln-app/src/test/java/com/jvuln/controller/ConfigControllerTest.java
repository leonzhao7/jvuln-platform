package com.jvuln.controller;

import com.jvuln.llm.LlmEndpoint;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.llm.impl.LlmConfigProvider;
import com.jvuln.llm.impl.OpenAiCompatClient;
import com.jvuln.store.JavaProfileRepository;
import com.jvuln.store.LlmConfigRepository;
import com.jvuln.store.entity.LlmConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfigControllerTest {

    private final LlmConfigRepository repository = mock(LlmConfigRepository.class);
    private final JavaProfileRepository javaProfiles = mock(JavaProfileRepository.class);
    private final PromptRegistry prompts = mock(PromptRegistry.class);
    private final OpenAiCompatClient client = mock(OpenAiCompatClient.class);
    private ConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new ConfigController(repository, javaProfiles, prompts, client);
    }

    @Test
    void createPreservesEndpoint() {
        LlmConfig incoming = config(LlmEndpoint.RESPONSES.getPath());

        ResponseEntity<LlmConfig> response = controller.create(incoming);

        assertEquals(LlmEndpoint.RESPONSES.getPath(), response.getBody().getEndpoint());
        ArgumentCaptor<LlmConfig> saved = ArgumentCaptor.forClass(LlmConfig.class);
        verify(repository).save(saved.capture());
        assertEquals(LlmEndpoint.RESPONSES.getPath(), saved.getValue().getEndpoint());
    }

    @Test
    void createRejectsUnknownEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.create(config("/v1/unknown")));
        verify(repository, never()).save(any(LlmConfig.class));
    }

    @Test
    void connectionTestUsesConfiguredEndpointThroughUnifiedClient() {
        LlmConfig config = config(LlmEndpoint.MESSAGES.getPath());
        when(repository.findById(7L)).thenReturn(Optional.of(config));
        when(prompts.getPrompt("current/config-connection-test")).thenReturn("connection task");
        when(client.chat(any(LlmConfigProvider.ActiveConfig.class), any(LlmRequest.class)))
                .thenReturn(new LlmResponse("PONG", 2, 1, "result-model", "stop"));

        ResponseEntity<Map<String, Object>> response = controller.test(7L);

        assertEquals(Boolean.TRUE, response.getBody().get("ok"));
        ArgumentCaptor<LlmConfigProvider.ActiveConfig> active =
                ArgumentCaptor.forClass(LlmConfigProvider.ActiveConfig.class);
        verify(client).chat(active.capture(), any(LlmRequest.class));
        assertEquals(LlmEndpoint.MESSAGES.getPath(), active.getValue().getEndpoint());
    }

    @Test
    void activateRejectsUnknownEndpointBeforeChangingActiveConfig() {
        when(repository.findById(7L)).thenReturn(Optional.of(config("/v1/unknown")));

        assertThrows(IllegalArgumentException.class, () -> controller.activate(7L));

        verify(repository, never()).deactivateAll();
        verify(repository, never()).save(any(LlmConfig.class));
    }

    @Test
    void connectionTestRejectsUnknownEndpointBeforeCallingClient() {
        when(repository.findById(7L)).thenReturn(Optional.of(config("/v1/unknown")));

        ResponseEntity<Map<String, Object>> response = controller.test(7L);

        assertEquals(Boolean.FALSE, response.getBody().get("ok"));
        assertTrue(response.getBody().get("error").toString().contains("endpoint"));
        verify(client, never()).chat(any(LlmConfigProvider.ActiveConfig.class), any(LlmRequest.class));
    }

    private LlmConfig config(String endpoint) {
        LlmConfig config = new LlmConfig();
        config.setName("test");
        config.setBaseUrl("https://example.test/v1");
        config.setApiKey("secret");
        config.setModel("model");
        config.setEndpoint(endpoint);
        config.setTemperature(0.1);
        config.setMaxTokens(8192);
        return config;
    }
}
