package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessagesCallerTest {

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
    void sendsSystemBlocksMergedUsersAndNormalizesResponse() throws Exception {
        server.enqueueJson("{\"id\":\"msg-1\",\"type\":\"message\","
                + "\"model\":\"claude-result\",\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"answer\"},"
                + "{\"type\":\"tool_use\",\"id\":\"call-3\",\"name\":\"lookup\","
                + "\"input\":{\"id\":12}}],\"stop_reason\":\"tool_use\","
                + "\"usage\":{\"input_tokens\":17,\"output_tokens\":6}}");

        MessagesCaller caller = new MessagesCaller(config(), mapper);
        LlmResponse response = caller.chat(callWithHistory());

        assertEquals("/v1/messages", server.getLastPath());
        assertEquals("2023-06-01", server.getLastHeader("anthropic-version"));
        assertEquals("secret", server.getLastHeader("x-api-key"));
        assertEquals("Bearer secret", server.getLastHeader("Authorization"));
        JsonNode body = readBody();
        assertFalse(body.path("stream").asBoolean());
        assertEquals("global", body.path("system").path(0).path("text").asText());
        assertEquals("stage", body.path("system").path(1).path("text").asText());
        JsonNode firstMessage = body.path("messages").path(0);
        assertEquals("user", firstMessage.path("role").asText());
        assertEquals("task", firstMessage.path("content").path(0).path("text").asText());
        assertEquals("payload", firstMessage.path("content").path(1).path("text").asText());
        assertEquals("assistant", body.path("messages").path(1).path("role").asText());
        assertEquals("tool_use", body.path("messages").path(1)
                .path("content").path(0).path("type").asText());
        assertEquals("tool_result", body.path("messages").path(2)
                .path("content").path(0).path("type").asText());
        assertEquals("lookup", body.path("tools").path(0).path("name").asText());
        assertTrue(body.path("tools").path(0).has("input_schema"));
        assertEquals("auto", body.path("tool_choice").path("type").asText());

        assertEquals("answer", response.getContent());
        assertEquals(17, response.getPromptTokens());
        assertEquals(6, response.getCompletionTokens());
        assertEquals("claude-result", response.getModel());
        assertEquals("tool_use", response.getFinishReason());
        LlmRequest.ContentBlock tool = response.getToolUses().get(0);
        assertEquals("call-3", tool.getToolUseId());
        assertEquals("lookup", tool.getToolName());
        assertEquals(12, tool.getToolInput().path("id").asInt());
    }

    @Test
    void streamsMessagesTextDeltas() {
        server.enqueueSse("data: {\"type\":\"content_block_delta\",\"delta\":{"
                + "\"type\":\"text_delta\",\"text\":\"hel\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{"
                + "\"type\":\"text_delta\",\"text\":\"lo\"}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n");

        MessagesCaller caller = new MessagesCaller(config(), mapper);
        List<String> chunks = caller.chatStream(callWithHistory()).collectList().block();

        assertEquals(Arrays.asList("hel", "lo"), chunks);
        assertTrue(readBody().path("stream").asBoolean());
    }

    @Test
    void surfacesMessagesErrorEvents() {
        server.enqueueSse("data: {\"type\":\"error\",\"error\":{"
                + "\"message\":\"overloaded\"}}\n\n");

        MessagesCaller caller = new MessagesCaller(config(), mapper);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> caller.chatStream(callWithHistory()).collectList().block());
        assertTrue(rootMessage(exception).contains("overloaded"));
    }

    private LlmConfigProvider.ActiveConfig config() {
        return new LlmConfigProvider.ActiveConfig("openai", server.getBaseUrl(),
                "secret", "gpt-named-model", LlmEndpoint.MESSAGES.getPath());
    }

    private LlmCall callWithHistory() {
        LlmRequest.ContentBlock oldTool = LlmRequest.ContentBlock.toolUse(
                "call-old", "lookup", mapper.createObjectNode().put("id", 1));
        List<LlmRequest.Message> messages = Arrays.asList(
                LlmRequest.Message.user("payload"),
                LlmRequest.Message.assistantWithBlocks(Collections.singletonList(oldTool)),
                LlmRequest.Message.toolResults(Collections.singletonList(
                        LlmRequest.ContentBlock.toolResult("call-old", "old result")))
        );
        LlmRequest.ToolDef tool = new LlmRequest.ToolDef("lookup", "Look up a record",
                Collections.<String, Object>singletonMap("type", "object"));
        LlmRequest request = LlmRequest.agent(LlmPromptStage.REASONING, "task", messages,
                Collections.singletonList(tool));
        return new LlmCall(request, new PromptContext("global", "stage"));
    }

    private JsonNode readBody() {
        try {
            return mapper.readTree(server.getLastBody());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }
}
