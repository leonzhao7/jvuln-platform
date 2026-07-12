package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.impl.LlmConfigProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

public class ChatCaller extends AbstractLlmCaller {

    public ChatCaller(LlmConfigProvider.ActiveConfig config, ObjectMapper mapper,
                      LlmAuditLogger auditLogger) {
        super(config, mapper, LlmEndpoint.CHAT_COMPLETIONS, false, auditLogger);
    }

    @Override
    public LlmResponse chat(LlmCall call) {
        String raw = postJson(buildBody(call, false));
        try {
            JsonNode json = mapper.readTree(raw);
            JsonNode choice = json.path("choices").path(0);
            if (choice.isMissingNode()) {
                throw new IllegalStateException("Missing choices[0]");
            }
            JsonNode message = choice.path("message");
            String content = extractMessageText(message);
            List<LlmRequest.ContentBlock> blocks = extractContentBlocks(message, content);
            if (blocks.isEmpty()) {
                throw new IllegalStateException("Chat response contained no text or tool calls");
            }
            JsonNode usage = json.path("usage");
            return new LlmResponse(content,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    json.path("model").asText(model),
                    choice.path("finish_reason").asText("stop"),
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
                        JsonNode json = mapper.readTree(data);
                        if (json.has("error")) {
                            sink.error(eventException(json));
                            return;
                        }
                        JsonNode content = json.path("choices").path(0).path("delta").path("content");
                        if (content.isTextual()) {
                            sink.next(content.asText());
                        }
                    } catch (Exception e) {
                        sink.error(parseException(data, e));
                    }
                });
    }

    @Override
    public String getName() {
        return "Chat Completions (" + model + ")";
    }

    private ObjectNode buildBody(LlmCall call, boolean stream) {
        LlmRequest request = call.getRequest();
        PromptContext prompts = call.getPromptContext();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        LlmRequestDefaults.apply(body, "max_tokens");
        body.put("stream", stream);
        addResponseFormat(body, request);
        addTools(body, request);

        ArrayNode messages = mapper.createArrayNode();
        addTextMessage(messages, "system", prompts.getGlobalPrompt());
        addTextMessage(messages, "system", prompts.getStagePrompt());
        addTextMessage(messages, "user", request.getTaskPrompt());
        for (LlmRequest.Message message : request.getMessages()) {
            addHistoryMessage(messages, message);
        }
        body.set("messages", messages);
        return body;
    }

    private void addResponseFormat(ObjectNode body, LlmRequest request) {
        if (request.isJsonMode()) {
            ObjectNode format = mapper.createObjectNode();
            format.put("type", "json_object");
            body.set("response_format", format);
        }
    }

    private void addTools(ObjectNode body, LlmRequest request) {
        if (!request.hasTools()) {
            return;
        }
        ArrayNode tools = mapper.createArrayNode();
        for (LlmRequest.ToolDef tool : request.getTools()) {
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "function");
            ObjectNode function = mapper.createObjectNode();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.set("parameters", mapper.valueToTree(tool.getInputSchema()));
            item.set("function", function);
            tools.add(item);
        }
        body.set("tools", tools);
        if (request.getToolChoice() != null && !request.getToolChoice().trim().isEmpty()) {
            body.put("tool_choice", request.getToolChoice());
        }
    }

    private void addHistoryMessage(ArrayNode messages, LlmRequest.Message message) {
        List<LlmRequest.ContentBlock> blocks = message.getContentBlocks();
        if (blocks == null || blocks.isEmpty()) {
            addTextMessage(messages, message.getRole(), message.getTextContent());
            return;
        }
        if (allToolResults(blocks)) {
            for (LlmRequest.ContentBlock block : blocks) {
                ObjectNode result = mapper.createObjectNode();
                result.put("role", "tool");
                result.put("tool_call_id", block.getToolUseId());
                result.put("content", valueOrEmpty(block.getToolResultContent()));
                messages.add(result);
            }
            return;
        }
        if ("assistant".equals(message.getRole())) {
            messages.add(serializeAssistant(blocks));
            return;
        }
        ObjectNode item = mapper.createObjectNode();
        item.put("role", message.getRole());
        item.put("content", textFromBlocks(blocks));
        messages.add(item);
    }

    private ObjectNode serializeAssistant(List<LlmRequest.ContentBlock> blocks) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = mapper.createArrayNode();
        for (LlmRequest.ContentBlock block : blocks) {
            if ("text".equals(block.getType())) {
                text.append(valueOrEmpty(block.getText()));
            } else if ("tool_use".equals(block.getType())) {
                ObjectNode toolCall = mapper.createObjectNode();
                toolCall.put("id", block.getToolUseId());
                toolCall.put("type", "function");
                ObjectNode function = mapper.createObjectNode();
                function.put("name", block.getToolName());
                function.put("arguments", block.getToolInput() == null
                        ? "{}" : block.getToolInput().toString());
                toolCall.set("function", function);
                toolCalls.add(toolCall);
            }
        }
        if (text.length() == 0) {
            message.putNull("content");
        } else {
            message.put("content", text.toString());
        }
        if (toolCalls.size() > 0) {
            message.set("tool_calls", toolCalls);
        }
        return message;
    }

    private List<LlmRequest.ContentBlock> extractContentBlocks(JsonNode message, String content) {
        List<LlmRequest.ContentBlock> blocks = new ArrayList<>();
        if (!content.isEmpty()) {
            blocks.add(LlmRequest.ContentBlock.text(content));
        }
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode toolCall : toolCalls) {
                JsonNode function = toolCall.path("function");
                blocks.add(LlmRequest.ContentBlock.toolUse(
                        toolCall.path("id").asText(""),
                        function.path("name").asText(""),
                        parseArguments(function.path("arguments").asText(""))));
            }
        }
        return blocks;
    }

    private String extractMessageText(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText()) && item.path("text").isTextual()) {
                text.append(item.path("text").asText());
            }
        }
        return text.toString();
    }

    private void addTextMessage(ArrayNode messages, String role, String content) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        messages.add(message);
    }

    private boolean allToolResults(List<LlmRequest.ContentBlock> blocks) {
        for (LlmRequest.ContentBlock block : blocks) {
            if (!"tool_result".equals(block.getType())) {
                return false;
            }
        }
        return true;
    }

    private String textFromBlocks(List<LlmRequest.ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (LlmRequest.ContentBlock block : blocks) {
            if ("text".equals(block.getType())) {
                text.append(valueOrEmpty(block.getText()));
            }
        }
        return text.toString();
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
