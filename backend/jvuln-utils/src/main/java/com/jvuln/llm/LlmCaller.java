package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.util.HttpUtil;
import com.jvuln.llm.util.LlmUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reusable call logic, configured per-request via LlmCallConfig.
 * Not a Spring bean — instantiated fresh when config changes.
 */
public class LlmCaller {

    private static final Logger log = LoggerFactory.getLogger(LlmCaller.class);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private final WebClient webClient;
    private final String model;
    private final ObjectMapper mapper;

    public LlmCaller(String baseUrl, String apiKey, String model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
        String base = normalizeBaseUrl(baseUrl);
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(180));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(base)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024));
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.webClient = builder.build();
    }

    public LlmResponse chat(LlmRequest request) {
        return LlmUtil.executeWithRetry(
                () -> {
                    if (preferStreamingChat(request)) {
                        return chatViaStreaming(request);
                    }

                    ObjectNode body = buildBody(request, false);
                    log.info("LLM chat: model={} maxTokens={}", model, request.getMaxTokens());

                    String raw = postChatCompletions(body, false);

                    try {
                        if (isServerSentEvents(raw)) {
                            return parseSseChatCompletion(raw);
                        }
                        JsonNode json = mapper.readTree(raw);
                        JsonNode choice = json.path("choices").path(0);
                        JsonNode message = choice.path("message");
                        List<LlmRequest.ContentBlock> blocks = extractContentBlocks(message);
                        String content = extractMessageText(message);
                        JsonNode usage = json.path("usage");
                        return new LlmResponse(
                                content,
                                usage.path("prompt_tokens").asInt(0),
                                usage.path("completion_tokens").asInt(0),
                                json.path("model").asText(model),
                                choice.path("finish_reason").asText("stop"),
                                blocks
                        );
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse LLM response from " + CHAT_COMPLETIONS_PATH
                                + ": " + describeRawResponse(raw), e);
                    }
                },
                "LLM chat request for model " + model
        );
    }

    private LlmResponse chatViaStreaming(LlmRequest request) {
        ObjectNode body = buildBody(request, true);
        log.info("LLM chat (streaming fallback): model={} maxTokens={}", model, request.getMaxTokens());
        try {
            List<String> lines = HttpUtil.executeWithRetry(
                    client -> client.post()
                            .uri(CHAT_COMPLETIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body.toString())
                            .retrieve()
                            .bodyToFlux(String.class)
                            .collectList(),
                    webClient,
                    "LLM streaming fallback request to " + CHAT_COMPLETIONS_PATH
            ).block();

            String raw = lines == null ? "" : String.join("\n", lines);
            try {
                return parseSseChatCompletion(raw);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse streaming LLM response from " + CHAT_COMPLETIONS_PATH
                        + ": " + describeRawResponse(raw), e);
            }
        } catch (WebClientResponseException e) {
            throw new RuntimeException("LLM API error " + e.getRawStatusCode() + " from "
                    + CHAT_COMPLETIONS_PATH + ": " + truncate(e.getResponseBodyAsString(), 1200), e);
        }
    }

    public Flux<String> chatStream(LlmRequest request) {
        ObjectNode body = buildBody(request, true);

        return LlmUtil.executeStreamWithRetry(
                () -> HttpUtil.executeFluxWithRetry(
                        client -> client.post()
                                .uri(CHAT_COMPLETIONS_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(body.toString())
                                .retrieve()
                                .bodyToFlux(String.class),
                        webClient,
                        "LLM streaming API request to " + CHAT_COMPLETIONS_PATH
                )
                .mapNotNull(line -> {
                    try {
                        String data = line.startsWith("data: ") ? line.substring(6) : line;
                        if (data.trim().isEmpty() || data.equals("[DONE]")) return null;
                        JsonNode json = mapper.readTree(data);
                        return json.path("choices").path(0).path("delta").path("content").asText(null);
                    } catch (Exception e) {
                        return null;
                    }
                }),
                "LLM chat stream for model " + model
        );
    }

    private ObjectNode buildBody(LlmRequest request, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", request.getTemperature());
        body.put("max_tokens", request.getMaxTokens());
        body.put("stream", stream);

        if (request.isJsonMode() && supportsResponseFormat()) {
            ObjectNode fmt = mapper.createObjectNode();
            fmt.put("type", "json_object");
            body.set("response_format", fmt);
        }

        if (request.hasTools()) {
            ArrayNode tools = mapper.createArrayNode();
            for (LlmRequest.ToolDef tool : request.getTools()) {
                ObjectNode toolNode = mapper.createObjectNode();
                toolNode.put("type", "function");
                ObjectNode functionNode = mapper.createObjectNode();
                functionNode.put("name", tool.getName());
                functionNode.put("description", tool.getDescription());
                functionNode.set("parameters", mapper.valueToTree(tool.getInputSchema()));
                toolNode.set("function", functionNode);
                tools.add(toolNode);
            }
            body.set("tools", tools);

            String toolChoice = request.getToolChoice();
            if (toolChoice != null && !toolChoice.trim().isEmpty()) {
                body.put("tool_choice", toolChoice);
            }
        }

        ArrayNode messages = mapper.createArrayNode();
        String sys = request.getSystemPrompt();
        if (sys != null && !sys.trim().isEmpty()) {
            ObjectNode m = mapper.createObjectNode();
            m.put("role", "system");
            m.put("content", sys);
            messages.add(m);
        }
        for (LlmRequest.Message msg : request.getMessages()) {
            List<LlmRequest.ContentBlock> blocks = msg.getContentBlocks();
            if (blocks != null && !blocks.isEmpty()) {
                if (isToolResultMessage(blocks)) {
                    for (LlmRequest.ContentBlock block : blocks) {
                        messages.add(serializeToolResultMessage(block));
                    }
                    continue;
                }
                if ("assistant".equals(msg.getRole())) {
                    messages.add(serializeAssistantMessage(blocks));
                    continue;
                }
            }

            ObjectNode m = mapper.createObjectNode();
            m.put("role", msg.getRole());
            String text = msg.getTextContent();
            m.put("content", text != null ? text : "");
            messages.add(m);
        }
        body.set("messages", messages);
        return body;
    }

    private String postChatCompletions(ObjectNode body, boolean stream) {
        try {
            return HttpUtil.executeBlockingWithRetry(
                    client -> client.post()
                            .uri(CHAT_COMPLETIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body.toString())
                            .retrieve()
                            .bodyToMono(String.class),
                    webClient,
                    "LLM API request to " + CHAT_COMPLETIONS_PATH
            );
        } catch (WebClientResponseException e) {
            throw new RuntimeException("LLM API error " + e.getRawStatusCode() + " from "
                    + CHAT_COMPLETIONS_PATH + ": " + truncate(e.getResponseBodyAsString(), 1200), e);
        }
    }

    private LlmResponse parseSseChatCompletion(String raw) throws Exception {
        StringBuilder content = new StringBuilder();
        int promptTokens = 0;
        int completionTokens = 0;
        String responseModel = model;
        String finishReason = "stop";
        Map<Integer, PendingToolCall> toolCalls = new LinkedHashMap<>();

        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            String data = line.trim();
            if (data.isEmpty()) continue;
            if (data.startsWith("data:")) {
                data = data.substring("data:".length()).trim();
            }
            if (data.isEmpty() || "[DONE]".equals(data)) continue;

            JsonNode json = mapper.readTree(data);
            if (json.hasNonNull("model")) {
                responseModel = json.path("model").asText(model);
            }

            JsonNode usage = json.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                completionTokens = usage.path("completion_tokens").asInt(completionTokens);
            }

            JsonNode choices = json.path("choices");
            if (choices.isArray()) {
                for (JsonNode choice : choices) {
                    JsonNode messageContent = choice.path("message").path("content");
                    appendMessageContent(content, messageContent);
                    JsonNode deltaContent = choice.path("delta").path("content");
                    appendMessageContent(content, deltaContent);

                    JsonNode toolCallsNode = choice.path("message").path("tool_calls");
                    if (toolCallsNode.isArray()) {
                        collectToolCalls(toolCalls, toolCallsNode);
                    }
                    JsonNode deltaToolCalls = choice.path("delta").path("tool_calls");
                    if (deltaToolCalls.isArray()) {
                        collectToolCalls(toolCalls, deltaToolCalls);
                    }

                    String fr = choice.path("finish_reason").asText(null);
                    if (fr != null && !fr.isEmpty()) {
                        finishReason = fr;
                    }
                }
            }
        }

        List<LlmRequest.ContentBlock> blocks = new ArrayList<>();
        if (content.length() > 0) {
            blocks.add(LlmRequest.ContentBlock.text(content.toString()));
        }
        blocks.addAll(toContentBlocks(toolCalls));

        return new LlmResponse(content.toString(), promptTokens, completionTokens, responseModel, finishReason, blocks);
    }

    private boolean isServerSentEvents(String raw) {
        return raw != null && raw.trim().startsWith("data:");
    }

    private boolean supportsResponseFormat() {
        String m = model == null ? "" : model.toLowerCase();
        return !m.startsWith("gpt-5");
    }

    private boolean preferStreamingChat(LlmRequest request) {
        String m = model == null ? "" : model.toLowerCase();
        return m.startsWith("gpt-5");
    }

    private boolean isToolResultMessage(List<LlmRequest.ContentBlock> blocks) {
        for (LlmRequest.ContentBlock block : blocks) {
            if (!"tool_result".equals(block.getType())) {
                return false;
            }
        }
        return true;
    }

    private ObjectNode serializeAssistantMessage(List<LlmRequest.ContentBlock> blocks) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");

        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = mapper.createArrayNode();
        for (LlmRequest.ContentBlock block : blocks) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                text.append(block.getText());
                continue;
            }
            if ("tool_use".equals(block.getType())) {
                ObjectNode toolCall = mapper.createObjectNode();
                toolCall.put("id", block.getToolUseId());
                toolCall.put("type", "function");
                ObjectNode function = mapper.createObjectNode();
                function.put("name", block.getToolName());
                JsonNode input = block.getToolInput();
                function.put("arguments", input == null ? "{}" : input.toString());
                toolCall.set("function", function);
                toolCalls.add(toolCall);
            }
        }

        if (text.length() > 0) {
            message.put("content", text.toString());
        } else {
            message.putNull("content");
        }
        if (toolCalls.size() > 0) {
            message.set("tool_calls", toolCalls);
        }
        return message;
    }

    private ObjectNode serializeToolResultMessage(LlmRequest.ContentBlock block) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "tool");
        message.put("tool_call_id", block.getToolUseId());
        message.put("content", block.getToolResultContent() != null ? block.getToolResultContent() : "");
        return message;
    }

    private List<LlmRequest.ContentBlock> extractContentBlocks(JsonNode message) {
        List<LlmRequest.ContentBlock> blocks = new ArrayList<>();
        String text = extractMessageText(message);
        if (!text.isEmpty()) {
            blocks.add(LlmRequest.ContentBlock.text(text));
        }

        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode toolCall : toolCalls) {
                String id = toolCall.path("id").asText("");
                String name = extractToolName(toolCall);
                JsonNode input = parseToolArguments(extractToolArguments(toolCall));
                blocks.add(LlmRequest.ContentBlock.toolUse(id, name, input));
            }
        }
        return blocks;
    }

    private String extractMessageText(JsonNode message) {
        JsonNode contentNode = message.path("content");
        if (contentNode.isTextual()) {
            return contentNode.asText("");
        }
        if (contentNode.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : contentNode) {
                if ("text".equals(item.path("type").asText()) && item.path("text").isTextual()) {
                    text.append(item.path("text").asText(""));
                }
            }
            return text.toString();
        }
        return "";
    }

    private void appendMessageContent(StringBuilder content, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            content.append(node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if ("text".equals(item.path("type").asText()) && item.path("text").isTextual()) {
                    content.append(item.path("text").asText(""));
                }
            }
        }
    }

    private void collectToolCalls(Map<Integer, PendingToolCall> toolCalls, JsonNode toolCallsNode) {
        for (JsonNode node : toolCallsNode) {
            int index = node.path("index").asInt(toolCalls.size());
            PendingToolCall pending = toolCalls.computeIfAbsent(index, k -> new PendingToolCall());
            if (node.hasNonNull("id")) {
                pending.id = node.path("id").asText("");
            }
            String type = node.path("type").asText(null);
            if (type != null && !type.isEmpty()) {
                pending.type = type;
            }

            String name = extractToolName(node);
            if (name != null && !name.isEmpty()) {
                pending.name = name;
            }

            String arguments = extractToolArguments(node);
            if (arguments != null && !arguments.isEmpty()) {
                pending.arguments.append(arguments);
            }

            if ((pending.name == null || pending.name.isEmpty()) && log.isDebugEnabled()) {
                log.debug("Unrecognized streaming tool-call shape: {}", truncate(node.toString(), 400));
            }
        }
    }

    private List<LlmRequest.ContentBlock> toContentBlocks(Map<Integer, PendingToolCall> toolCalls) {
        List<LlmRequest.ContentBlock> blocks = new ArrayList<>();
        for (PendingToolCall pending : toolCalls.values()) {
            String id = pending.id != null ? pending.id : "";
            String name = pending.name != null ? pending.name : "";
            JsonNode input = parseToolArguments(pending.arguments.toString());
            blocks.add(LlmRequest.ContentBlock.toolUse(id, name, input));
        }
        return blocks;
    }

    private JsonNode parseToolArguments(String rawArguments) {
        try {
            String args = rawArguments == null ? "" : rawArguments.trim();
            if (args.isEmpty()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(args);
        } catch (Exception e) {
            ObjectNode raw = mapper.createObjectNode();
            raw.put("_raw", rawArguments == null ? "" : rawArguments);
            return raw;
        }
    }

    private String extractToolName(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }

        JsonNode function = node.path("function");
        if (function.hasNonNull("name")) {
            return function.path("name").asText("");
        }
        if (node.hasNonNull("name")) {
            return node.path("name").asText("");
        }

        JsonNode functionCall = node.path("function_call");
        if (functionCall.hasNonNull("name")) {
            return functionCall.path("name").asText("");
        }
        return "";
    }

    private String extractToolArguments(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }

        JsonNode function = node.path("function");
        if (function.hasNonNull("arguments")) {
            return function.path("arguments").asText("");
        }
        if (node.hasNonNull("arguments")) {
            return node.path("arguments").asText("");
        }

        JsonNode functionCall = node.path("function_call");
        if (functionCall.hasNonNull("arguments")) {
            return functionCall.path("arguments").asText("");
        }
        if (node.hasNonNull("input")) {
            JsonNode input = node.path("input");
            return input.isTextual() ? input.asText("") : input.toString();
        }
        return "";
    }

    private String normalizeBaseUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        base = base.replaceAll("(?i)/v1/chat/completions$", "");
        base = base.replaceAll("(?i)/chat/completions$", "");
        base = base.replaceAll("(?i)/v1$", "");
        return base;
    }

    private String describeRawResponse(String raw) {
        if (raw == null) return "empty response";
        String trimmed = raw.trim();
        if (trimmed.startsWith("<!doctype") || trimmed.startsWith("<html") || trimmed.startsWith("<")) {
            return "received HTML instead of JSON: " + truncate(trimmed, 400);
        }
        return truncate(trimmed, 800);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static final class PendingToolCall {
        private String id;
        private String type = "function";
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
