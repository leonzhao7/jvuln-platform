package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.impl.LlmConfigProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

public class MessagesCaller extends AbstractLlmCaller {

    public MessagesCaller(LlmConfigProvider.ActiveConfig config, ObjectMapper mapper,
                          LlmAuditLogger auditLogger) {
        super(config, mapper, LlmEndpoint.MESSAGES, true, auditLogger);
    }

    @Override
    public LlmResponse chat(LlmCall call) {
        String raw = postJson(buildBody(call, false));
        try {
            JsonNode json = mapper.readTree(raw);
            List<LlmRequest.ContentBlock> blocks = parseContent(json.path("content"));
            if (blocks.isEmpty()) {
                throw new IllegalStateException("Messages API returned no content blocks");
            }
            JsonNode usage = json.path("usage");
            return new LlmResponse(collectText(blocks),
                    usage.path("input_tokens").asInt(0),
                    usage.path("output_tokens").asInt(0),
                    json.path("model").asText(model),
                    json.path("stop_reason").asText("end_turn"),
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
                        if ("error".equals(type)) {
                            sink.error(eventException(event));
                            return;
                        }
                        JsonNode delta = event.path("delta");
                        if ("content_block_delta".equals(type)
                                && "text_delta".equals(delta.path("type").asText())) {
                            sink.next(delta.path("text").asText(""));
                        }
                    } catch (Exception e) {
                        sink.error(e instanceof RuntimeException
                                ? e : parseException(data, e));
                    }
                });
    }

    @Override
    public String getName() {
        return "Messages (" + model + ")";
    }

    private ObjectNode buildBody(LlmCall call, boolean stream) {
        LlmRequest request = call.getRequest();
        PromptContext prompts = call.getPromptContext();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        LlmRequestDefaults.apply(body, "max_tokens");
        body.put("stream", stream);
        body.set("system", buildSystem(prompts));
        addTools(body, request);

        ArrayNode messages = mapper.createArrayNode();
        ArrayNode task = mapper.createArrayNode();
        addTextBlock(task, request.getTaskPrompt());
        addMessage(messages, "user", task);
        for (LlmRequest.Message message : request.getMessages()) {
            addMessage(messages, message.getRole(), serializeMessageContent(message));
        }
        body.set("messages", messages);
        return body;
    }

    private ArrayNode buildSystem(PromptContext prompts) {
        ArrayNode system = mapper.createArrayNode();
        addTextBlock(system, prompts.getGlobalPrompt());
        addTextBlock(system, prompts.getStagePrompt());
        return system;
    }

    private void addTools(ObjectNode body, LlmRequest request) {
        if (!request.hasTools()) {
            return;
        }
        ArrayNode tools = mapper.createArrayNode();
        for (LlmRequest.ToolDef tool : request.getTools()) {
            ObjectNode item = mapper.createObjectNode();
            item.put("name", tool.getName());
            item.put("description", tool.getDescription());
            item.set("input_schema", mapper.valueToTree(tool.getInputSchema()));
            tools.add(item);
        }
        body.set("tools", tools);
        if (request.getToolChoice() != null && !request.getToolChoice().trim().isEmpty()) {
            ObjectNode choice = mapper.createObjectNode();
            choice.put("type", request.getToolChoice());
            body.set("tool_choice", choice);
        }
    }

    private ArrayNode serializeMessageContent(LlmRequest.Message message) {
        ArrayNode content = mapper.createArrayNode();
        List<LlmRequest.ContentBlock> blocks = message.getContentBlocks();
        if (blocks == null) {
            addTextBlock(content, message.getTextContent());
            return content;
        }
        for (LlmRequest.ContentBlock block : blocks) {
            ObjectNode item = serializeContentBlock(block);
            if (item != null) {
                content.add(item);
            }
        }
        return content;
    }

    private ObjectNode serializeContentBlock(LlmRequest.ContentBlock block) {
        ObjectNode item = mapper.createObjectNode();
        if ("text".equals(block.getType())) {
            item.put("type", "text");
            item.put("text", valueOrEmpty(block.getText()));
            return item;
        }
        if ("tool_use".equals(block.getType())) {
            item.put("type", "tool_use");
            item.put("id", block.getToolUseId());
            item.put("name", block.getToolName());
            item.set("input", block.getToolInput() == null
                    ? mapper.createObjectNode() : block.getToolInput());
            return item;
        }
        if ("tool_result".equals(block.getType())) {
            item.put("type", "tool_result");
            item.put("tool_use_id", block.getToolUseId());
            item.put("content", valueOrEmpty(block.getToolResultContent()));
            if (block.isError()) {
                item.put("is_error", true);
            }
            return item;
        }
        return null;
    }

    private void addMessage(ArrayNode messages, String role, ArrayNode content) {
        if (content.size() == 0) {
            return;
        }
        if (messages.size() > 0) {
            JsonNode previous = messages.get(messages.size() - 1);
            if (role.equals(previous.path("role").asText())
                    && previous.path("content") instanceof ArrayNode) {
                ((ArrayNode) previous.path("content")).addAll(content);
                return;
            }
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.set("content", content);
        messages.add(message);
    }

    private void addTextBlock(ArrayNode content, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "text");
        item.put("text", text);
        content.add(item);
    }

    private List<LlmRequest.ContentBlock> parseContent(JsonNode content) {
        List<LlmRequest.ContentBlock> blocks = new ArrayList<>();
        if (!content.isArray()) {
            return blocks;
        }
        for (JsonNode item : content) {
            String type = item.path("type").asText();
            if ("text".equals(type) && item.path("text").isTextual()) {
                blocks.add(LlmRequest.ContentBlock.text(item.path("text").asText()));
            } else if ("tool_use".equals(type)) {
                blocks.add(LlmRequest.ContentBlock.toolUse(
                        item.path("id").asText(""),
                        item.path("name").asText(""),
                        item.has("input") ? item.path("input") : mapper.createObjectNode()));
            }
        }
        return blocks;
    }

    private String collectText(List<LlmRequest.ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (LlmRequest.ContentBlock block : blocks) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                text.append(block.getText());
            }
        }
        return text.toString();
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
