package com.jvuln.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmAdapter;
import com.jvuln.llm.LlmAdapterFactory;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatClientTest {

    @Test
    void chatResolvesCurrentPromptIndependentlyForEachRequest() {
        CapturingAdapter adapter = new CapturingAdapter();
        OpenAiCompatClient client = newClient(adapter);

        client.chat(LlmRequest.reasoning(LlmPromptStage.REASONING, "one", "user"));
        assertEquals("global\n\nreasoning\n\none", adapter.lastRequest.getSystemPrompt());

        client.chat(LlmRequest.reasoning(LlmPromptStage.REASONING, "two", "user"));
        assertEquals("global\n\nreasoning\n\ntwo", adapter.lastRequest.getSystemPrompt());
    }

    @Test
    void chatStreamResolvesCurrentPromptBeforeDelegation() {
        CapturingAdapter adapter = new CapturingAdapter();
        OpenAiCompatClient client = newClient(adapter);

        client.chatStream(LlmRequest.reasoning(LlmPromptStage.REASONING, "stream", "user"))
                .collectList().block();

        assertEquals("global\n\nreasoning\n\nstream", adapter.lastRequest.getSystemPrompt());
    }

    private OpenAiCompatClient newClient(CapturingAdapter adapter) {
        LlmConfigProvider configProvider = () -> new LlmConfigProvider.ActiveConfig(
                "openai", "http://localhost", "", "test-model");
        return new OpenAiCompatClient(configProvider, new CapturingFactory(adapter), new ObjectMapper(),
                new PromptManager(new DefaultResourceLoader()), "http://localhost", "", "test-model");
    }

    private static class CapturingFactory extends LlmAdapterFactory {
        private final LlmAdapter adapter;

        private CapturingFactory(LlmAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public LlmAdapter createAdapter(LlmConfigProvider.ActiveConfig config, ObjectMapper mapper) {
            return adapter;
        }
    }

    private static class CapturingAdapter implements LlmAdapter {
        private LlmRequest lastRequest;

        @Override
        public LlmResponse chat(LlmRequest request) {
            lastRequest = request;
            return new LlmResponse("response", 0, 0, "test", "stop");
        }

        @Override
        public Flux<String> chatStream(LlmRequest request) {
            lastRequest = request;
            return Flux.just("response");
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }

        @Override
        public String getName() {
            return "capturing";
        }
    }
}
