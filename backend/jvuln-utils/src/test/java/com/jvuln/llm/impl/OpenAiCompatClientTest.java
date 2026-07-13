package com.jvuln.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmAuditLogger;
import com.jvuln.llm.LlmCall;
import com.jvuln.llm.LlmCallerFactory;
import com.jvuln.llm.LlmEndpoint;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.LlmProtocolCaller;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class OpenAiCompatClientTest {

    @Test
    void chatResolvesPromptsAndKeepsTasksIndependent() {
        CapturingCaller caller = new CapturingCaller();
        CapturingFactory factory = new CapturingFactory(caller);
        OpenAiCompatClient client = newClient(factory);

        client.chat(LlmRequest.reasoning(LlmPromptStage.REASONING, "one", "payload"));
        assertEquals("global", caller.lastCall.getPromptContext().getGlobalPrompt());
        assertEquals("reasoning", caller.lastCall.getPromptContext().getStagePrompt());
        assertEquals("one", caller.lastCall.getRequest().getTaskPrompt());

        client.chat(LlmRequest.reasoning(LlmPromptStage.REASONING, "two", "payload"));
        assertEquals("two", caller.lastCall.getRequest().getTaskPrompt());
    }

    @Test
    void chatStreamUsesTheSameResolvedCallPath() {
        CapturingCaller caller = new CapturingCaller();
        OpenAiCompatClient client = newClient(new CapturingFactory(caller));

        client.chatStream(LlmRequest.reasoning(LlmPromptStage.REASONING, "stream", "payload"))
                .collectList().block();

        assertEquals("stream", caller.lastCall.getRequest().getTaskPrompt());
        assertEquals("reasoning", caller.lastCall.getPromptContext().getStagePrompt());
    }

    @Test
    void explicitConfigOverloadUsesTheProvidedEndpointConfig() {
        CapturingCaller caller = new CapturingCaller();
        CapturingFactory factory = new CapturingFactory(caller);
        OpenAiCompatClient client = newClient(factory);
        LlmConfigProvider.ActiveConfig explicit = new LlmConfigProvider.ActiveConfig(
                "http://explicit", "key", "model", LlmEndpoint.MESSAGES.getPath());

        client.chat(explicit, LlmRequest.diagnostic("diagnostic", "PONG"));

        assertSame(explicit, factory.lastConfig);
        assertEquals("global", caller.lastCall.getPromptContext().getGlobalPrompt());
        assertEquals(null, caller.lastCall.getPromptContext().getStagePrompt());
    }

    private OpenAiCompatClient newClient(CapturingFactory factory) {
        LlmConfigProvider configProvider = () -> new LlmConfigProvider.ActiveConfig(
                "http://active", "", "test-model", LlmEndpoint.RESPONSES.getPath());
        return new OpenAiCompatClient(configProvider, factory, new ObjectMapper(),
                new PromptManager(new DefaultResourceLoader()),
                "http://fallback", "", "fallback-model",
                LlmEndpoint.CHAT_COMPLETIONS.getPath());
    }

    private static class CapturingFactory extends LlmCallerFactory {
        private final LlmProtocolCaller caller;
        private LlmConfigProvider.ActiveConfig lastConfig;

        private CapturingFactory(LlmProtocolCaller caller) {
            super(new LlmAuditLogger());
            this.caller = caller;
        }

        @Override
        public LlmProtocolCaller createCaller(LlmConfigProvider.ActiveConfig config,
                                              ObjectMapper mapper) {
            lastConfig = config;
            return caller;
        }
    }

    private static class CapturingCaller implements LlmProtocolCaller {
        private LlmCall lastCall;

        @Override
        public LlmResponse chat(LlmCall call) {
            lastCall = call;
            return new LlmResponse("response", 0, 0, "test", "stop");
        }

        @Override
        public Flux<String> chatStream(LlmCall call) {
            lastCall = call;
            return Flux.just("response");
        }

        @Override
        public String getName() {
            return "capturing";
        }
    }
}
