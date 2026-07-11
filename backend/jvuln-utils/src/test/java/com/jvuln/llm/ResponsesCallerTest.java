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

class ResponsesCallerTest {

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
    void sendsOfficialPromptAndToolShapesAndParsesOutputItems() throws Exception {
        server.enqueueJson("{\"id\":\"resp-1\",\"model\":\"gpt-result\","
                + "\"status\":\"completed\",\"output\":["
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"answer\"}]},"
                + "{\"type\":\"function_call\",\"call_id\":\"call-2\","
                + "\"name\":\"lookup\",\"arguments\":\"{\\\"id\\\":9}\"}],"
                + "\"usage\":{\"input_tokens\":13,\"output_tokens\":8}}");

        ResponsesCaller caller = new ResponsesCaller(config(), mapper);
        LlmResponse response = caller.chat(callWithToolHistory());

        assertEquals("/v1/responses", server.getLastPath());
        JsonNode body = mapper.readTree(server.getLastBody());
        assertEquals("global", body.path("instructions").asText());
        assertEquals(0.0, body.path("temperature").asDouble());
        assertEquals(65536, body.path("max_output_tokens").asInt());
        assertFalse(body.has("reasoning"));
        assertInputMessage(body, 0, "developer", "input_text", "stage");
        assertInputMessage(body, 1, "user", "input_text", "task");
        assertEquals("message", body.path("input").path(2).path("type").asText());
        assertEquals("assistant", body.path("input").path(2).path("role").asText());
        assertEquals("function_call", body.path("input").path(3).path("type").asText());
        assertEquals("call-old", body.path("input").path(3).path("call_id").asText());
        assertEquals("function_call_output", body.path("input").path(4).path("type").asText());
        assertEquals("old result", body.path("input").path(4).path("output").asText());
        assertEquals("function", body.path("tools").path(0).path("type").asText());
        assertEquals("lookup", body.path("tools").path(0).path("name").asText());
        assertTrue(body.path("tools").path(0).has("parameters"));
        assertFalse(body.path("tools").path(0).has("function"));
        assertEquals("json_object", body.path("text").path("format").path("type").asText());

        assertEquals("answer", response.getContent());
        assertEquals(13, response.getPromptTokens());
        assertEquals(8, response.getCompletionTokens());
        assertEquals("gpt-result", response.getModel());
        assertEquals("tool_calls", response.getFinishReason());
        LlmRequest.ContentBlock tool = response.getToolUses().get(0);
        assertEquals("call-2", tool.getToolUseId());
        assertEquals("lookup", tool.getToolName());
        assertEquals(9, tool.getToolInput().path("id").asInt());
    }

    @Test
    void streamsOnlyResponsesOutputTextDeltas() {
        server.enqueueSse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"hel\"}\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"lo\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n");

        ResponsesCaller caller = new ResponsesCaller(config(), mapper);
        List<String> chunks = caller.chatStream(callWithToolHistory()).collectList().block();

        assertEquals(Arrays.asList("hel", "lo"), chunks);
        assertTrue(readBody().path("stream").asBoolean());
    }

    @Test
    void surfacesResponsesFailedEvents() {
        server.enqueueSse("data: {\"type\":\"response.failed\",\"response\":{"
                + "\"error\":{\"message\":\"model failed\"}}}\n\n");

        ResponsesCaller caller = new ResponsesCaller(config(), mapper);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> caller.chatStream(callWithToolHistory()).collectList().block());
        assertTrue(rootMessage(exception).contains("model failed"));
    }

    @Test
    void normalizesFailedResponseWithoutOutput() {
        server.enqueueJson("{\"id\":\"resp-failed\",\"model\":\"gpt-result\","
                + "\"status\":\"failed\",\"output\":[],"
                + "\"error\":{\"message\":\"model failed\"},"
                + "\"usage\":{\"input_tokens\":4,\"output_tokens\":0}}");

        ResponsesCaller caller = new ResponsesCaller(config(), mapper);

        LlmResponse response = caller.chat(callWithToolHistory());
        assertEquals("", response.getContent());
        assertEquals("error", response.getFinishReason());
        assertEquals(4, response.getPromptTokens());
        assertEquals(0, response.getCompletionTokens());
    }

    private LlmConfigProvider.ActiveConfig config() {
        return new LlmConfigProvider.ActiveConfig(server.getBaseUrl(), "secret",
                "claude-named-model", LlmEndpoint.RESPONSES.getPath());
    }

    private LlmCall callWithToolHistory() {
        LlmRequest.ContentBlock oldTool = LlmRequest.ContentBlock.toolUse(
                "call-old", "lookup", mapper.createObjectNode().put("id", 1));
        List<LlmRequest.Message> messages = Arrays.asList(
                LlmRequest.Message.assistantWithBlocks(Arrays.asList(
                        LlmRequest.ContentBlock.text("checking"), oldTool)),
                LlmRequest.Message.toolResults(Collections.singletonList(
                        LlmRequest.ContentBlock.toolResult("call-old", "old result")))
        );
        LlmRequest.ToolDef tool = new LlmRequest.ToolDef("lookup", "Look up a record",
                Collections.<String, Object>singletonMap("type", "object"));
        LlmRequest request = new LlmRequest(LlmPromptStage.REASONING, "task", messages,
                true, Collections.singletonList(tool), "auto");
        return new LlmCall(request, new PromptContext("global", "stage"));
    }

    private void assertInputMessage(JsonNode body, int index, String role,
                                    String contentType, String text) {
        JsonNode item = body.path("input").path(index);
        assertEquals("message", item.path("type").asText());
        assertEquals(role, item.path("role").asText());
        assertEquals(contentType, item.path("content").path(0).path("type").asText());
        assertEquals(text, item.path("content").path(0).path("text").asText());
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
