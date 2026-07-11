package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.impl.LlmConfigProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

public class ResponsesCaller extends AbstractLlmCaller {

    public ResponsesCaller(LlmConfigProvider.ActiveConfig config, ObjectMapper mapper) {
        super(config, mapper, LlmEndpoint.RESPONSES, false);
    }

    @Override
    public LlmResponse chat(LlmCall call) {
        String raw = postJson(buildBody(call, false));
        try {
            JsonNode json = mapper.readTree(raw);
            List<LlmRequest.ContentBlock> blocks = parseOutput(json.path("output"));
            String content = collectText(blocks);
            if (blocks.isEmpty() && !"failed".equals(json.path("status").asText())) {
                throw new IllegalStateException("Responses API returned no output items");
            }
            JsonNode usage = json.path("usage");
            return new LlmResponse(content,
                    usage.path("input_tokens").asInt(0),
                    usage.path("output_tokens").asInt(0),
                    json.path("model").asText(model),
                    finishReason(json, blocks),
                    blocks);
        } catch (Exception e) {
            throw parseException(raw, e);
        }
    }

    @Override
    public Flux<String> chatStream(LlmCall call) {
        return postSse(buildBody(call, true))
                .flatMapIterable(this::ssePayloads)
                .<String>handle((data, sink) -> {
                    if ("[DONE]".equals(data)) {
                        return;
                    }
                    try {
                        JsonNode event = mapper.readTree(data);
                        String type = event.path("type").asText();
                        if ("response.output_text.delta".equals(type)) {
                            sink.next(event.path("delta").asText(""));
                        } else if ("response.failed".equals(type) || "error".equals(type)) {
                            sink.error(responsesEventException(event));
                        }
                    } catch (Exception e) {
                        sink.error(e instanceof RuntimeException
                                ? e : parseException(data, e));
                    }
                });
    }

    @Override
    public String getName() {
        return "Responses (" + model + ")";
    }

    private ObjectNode buildBody(LlmCall call, boolean stream) {
        LlmRequest request = call.getRequest();
        PromptContext prompts = call.getPromptContext();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("instructions", prompts.getGlobalPrompt());
        LlmRequestDefaults.apply(body, "max_output_tokens");
        body.put("stream", stream);
        addTextFormat(body, request);
        addTools(body, request);

        ArrayNode input = mapper.createArrayNode();
        addMessageItem(input, "developer", "input_text", prompts.getStagePrompt());
        addMessageItem(input, "user", "input_text", request.getTaskPrompt());
        for (LlmRequest.Message message : request.getMessages()) {
            addHistoryItems(input, message);
        }
        body.set("input", input);
        return body;
    }

    private void addTextFormat(ObjectNode body, LlmRequest request) {
        if (!request.isJsonMode()) {
            return;
        }
        ObjectNode text = mapper.createObjectNode();
        ObjectNode format = mapper.createObjectNode();
        format.put("type", "json_object");
        text.set("format", format);
        body.set("text", text);
    }

    private void addTools(ObjectNode body, LlmRequest request) {
        if (!request.hasTools()) {
            return;
        }
        ArrayNode tools = mapper.createArrayNode();
        for (LlmRequest.ToolDef tool : request.getTools()) {
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "function");
            item.put("name", tool.getName());
            item.put("description", tool.getDescription());
            item.set("parameters", mapper.valueToTree(tool.getInputSchema()));
            tools.add(item);
        }
        body.set("tools", tools);
        if (request.getToolChoice() != null && !request.getToolChoice().trim().isEmpty()) {
            body.put("tool_choice", request.getToolChoice());
        }
    }

    private void addHistoryItems(ArrayNode input, LlmRequest.Message message) {
        List<LlmRequest.ContentBlock> blocks = message.getContentBlocks();
        if (blocks == null || blocks.isEmpty()) {
            String contentType = "assistant".equals(message.getRole())
                    ? "output_text" : "input_text";
            addMessageItem(input, message.getRole(), contentType, message.getTextContent());
            return;
        }

        StringBuilder text = new StringBuilder();
        for (LlmRequest.ContentBlock block : blocks) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                text.append(block.getText());
            }
        }
        if (text.length() > 0) {
            String contentType = "assistant".equals(message.getRole())
                    ? "output_text" : "input_text";
            addMessageItem(input, message.getRole(), contentType, text.toString());
        }

        for (LlmRequest.ContentBlock block : blocks) {
            if ("tool_use".equals(block.getType())) {
                ObjectNode functionCall = mapper.createObjectNode();
                functionCall.put("type", "function_call");
                functionCall.put("call_id", block.getToolUseId());
                functionCall.put("name", block.getToolName());
                functionCall.put("arguments", block.getToolInput() == null
                        ? "{}" : block.getToolInput().toString());
                input.add(functionCall);
            } else if ("tool_result".equals(block.getType())) {
                ObjectNode result = mapper.createObjectNode();
                result.put("type", "function_call_output");
                result.put("call_id", block.getToolUseId());
                result.put("output", block.getToolResultContent() == null
                        ? "" : block.getToolResultContent());
                input.add(result);
            }
        }
    }

    private void addMessageItem(ArrayNode input, String role, String contentType, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "message");
        item.put("role", role);
        ArrayNode content = mapper.createArrayNode();
        ObjectNode textItem = mapper.createObjectNode();
        textItem.put("type", contentType);
        textItem.put("text", text);
        content.add(textItem);
        item.set("content", content);
        input.add(item);
    }

    private List<LlmRequest.ContentBlock> parseOutput(JsonNode output) {
        List<LlmRequest.ContentBlock> blocks = new ArrayList<>();
        if (!output.isArray()) {
            return blocks;
        }
        for (JsonNode item : output) {
            String type = item.path("type").asText();
            if ("message".equals(type)) {
                addMessageOutput(blocks, item.path("content"));
            } else if ("function_call".equals(type)) {
                blocks.add(LlmRequest.ContentBlock.toolUse(
                        item.path("call_id").asText(item.path("id").asText("")),
                        item.path("name").asText(""),
                        parseArguments(item.path("arguments").asText(""))));
            }
        }
        return blocks;
    }

    private void addMessageOutput(List<LlmRequest.ContentBlock> blocks, JsonNode content) {
        if (!content.isArray()) {
            return;
        }
        for (JsonNode part : content) {
            String type = part.path("type").asText();
            if (("output_text".equals(type) || "text".equals(type))
                    && part.path("text").isTextual()) {
                blocks.add(LlmRequest.ContentBlock.text(part.path("text").asText()));
            } else if ("refusal".equals(type) && part.path("refusal").isTextual()) {
                blocks.add(LlmRequest.ContentBlock.text(part.path("refusal").asText()));
            }
        }
    }

    private String collectText(List<LlmRequest.ContentBlock> blocks) {
        StringBuilder content = new StringBuilder();
        for (LlmRequest.ContentBlock block : blocks) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                content.append(block.getText());
            }
        }
        return content.toString();
    }

    private String finishReason(JsonNode response, List<LlmRequest.ContentBlock> blocks) {
        for (LlmRequest.ContentBlock block : blocks) {
            if ("tool_use".equals(block.getType())) {
                return "tool_calls";
            }
        }
        String status = response.path("status").asText("completed");
        if ("incomplete".equals(status)) {
            return response.path("incomplete_details").path("reason").asText("incomplete");
        }
        if ("failed".equals(status)) {
            return "error";
        }
        return "stop";
    }

    private RuntimeException responsesEventException(JsonNode event) {
        String message = event.path("response").path("error").path("message").asText(null);
        if (message == null || message.isEmpty()) {
            message = event.path("error").path("message").asText(null);
        }
        if (message == null || message.isEmpty()) {
            message = "unknown Responses API streaming error";
        }
        return new RuntimeException("LLM streaming error from /v1/responses: " + message);
    }
}
