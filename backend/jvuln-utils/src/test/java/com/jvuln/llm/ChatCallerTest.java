package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatCallerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private CallerTestServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new CallerTestServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void sendsPromptRolesAndNormalizesTextToolsAndUsage() throws Exception {
        server.enqueueJson("{\"model\":\"gpt-result\",\"choices\":[{"
                + "\"message\":{\"content\":\"answer\",\"tool_calls\":[{"
                + "\"id\":\"call-1\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"lookup\",\"arguments\":\"{\\\"id\\\":7}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5}}");

        ChatCaller caller = new ChatCaller(config(), mapper);
        LlmResponse response = caller.chat(agentCall());

        assertEquals("/v1/chat/completions", server.getLastPath());
        assertEquals("Bearer secret", server.getLastHeader("Authorization"));
        JsonNode body = mapper.readTree(server.getLastBody());
        assertFalse(body.path("stream").asBoolean());
        assertRequestDefaults(body, "max_tokens");
        assertMessage(body, 0, "system", "global");
        assertMessage(body, 1, "system", "stage");
        assertMessage(body, 2, "user", "task");
        assertMessage(body, 3, "user", "payload");
        for (JsonNode message : body.path("messages")) {
            assertNotEquals("developer", message.path("role").asText());
        }
        assertEquals("function", body.path("tools").path(0).path("type").asText());
        assertEquals("lookup", body.path("tools").path(0)
                .path("function").path("name").asText());

        assertEquals("answer", response.getContent());
        assertEquals(11, response.getPromptTokens());
        assertEquals(5, response.getCompletionTokens());
        assertEquals("gpt-result", response.getModel());
        assertEquals("tool_calls", response.getFinishReason());
        assertTrue(response.hasToolUse());
        LlmRequest.ContentBlock tool = response.getToolUses().get(0);
        assertEquals("call-1", tool.getToolUseId());
        assertEquals("lookup", tool.getToolName());
        assertEquals(7, tool.getToolInput().path("id").asInt());
    }

    @Test
    void streamsChatCompletionTextDeltas() {
        server.enqueueSse("data: {\"choices\":[{\"delta\":{\"content\":\"hel\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                + "data: [DONE]\n\n");

        ChatCaller caller = new ChatCaller(config(), mapper);
        List<String> chunks = caller.chatStream(agentCall()).collectList().block();

        assertEquals(java.util.Arrays.asList("hel", "lo"), chunks);
        assertTrue(readStreamFlag());
    }

    private LlmConfigProvider.ActiveConfig config() {
        return new LlmConfigProvider.ActiveConfig(server.getBaseUrl(), "secret",
                "claude-named-model", LlmEndpoint.CHAT_COMPLETIONS.getPath());
    }

    private LlmCall agentCall() {
        LlmRequest.ToolDef tool = new LlmRequest.ToolDef("lookup", "Look up a record",
                Collections.<String, Object>singletonMap("type", "object"));
        LlmRequest request = LlmRequest.agent(LlmPromptStage.REASONING, "task",
                Collections.singletonList(LlmRequest.Message.user("payload")),
                Collections.singletonList(tool));
        return new LlmCall(request, new PromptContext("global", "stage"));
    }

    private void assertMessage(JsonNode body, int index, String role, String content) {
        JsonNode message = body.path("messages").path(index);
        assertEquals(role, message.path("role").asText());
        assertEquals(content, message.path("content").asText());
    }

    private void assertRequestDefaults(JsonNode body, String maxTokensField) {
        assertEquals(0.0, body.path("temperature").asDouble());
        assertEquals(65536, body.path(maxTokensField).asInt());
        assertFalse(body.has("reasoning"));
    }

    private boolean readStreamFlag() {
        try {
            return mapper.readTree(server.getLastBody()).path("stream").asBoolean();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
