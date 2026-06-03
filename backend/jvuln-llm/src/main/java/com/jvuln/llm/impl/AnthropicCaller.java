package com.jvuln.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;

public class AnthropicCaller {

    private static final Logger log = LoggerFactory.getLogger(AnthropicCaller.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final WebClient webClient;
    private final String model;
    private final ObjectMapper mapper;

    public AnthropicCaller(String baseUrl, String apiKey, String model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
        String base = baseUrl.replaceAll("/(v1/messages|v1)/?$", "");
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(300));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(base)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024));
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader("x-api-key", apiKey);
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.webClient = builder.build();
    }

    public LlmResponse chat(LlmRequest request) {
        ObjectNode body = buildBody(request);
        body.put("stream", true);
        log.info("Anthropic chat (streaming): model={} maxTokens={} tools={}",
                model, request.getMaxTokens(), request.hasTools() ? request.getTools().size() : 0);

        // Accumulators for streaming
        StringBuilder textBuf = new StringBuilder();
        int[] inputTokens  = {0};
        int[] outputTokens = {0};
        String[] stopReason = {"end_turn"};
        String[] errorMsg   = {null};

        // Tool-use accumulators
        List<LlmRequest.ContentBlock> blocks = new ArrayList<>();
        String[] currentBlockType = {null};
        String[] currentToolId = {null};
        String[] currentToolName = {null};
        StringBuilder[] currentJsonBuf = {new StringBuilder()};
        StringBuilder[] currentTextBuf = {new StringBuilder()};

        webClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(line -> {
                    try {
                        String data = line.startsWith("data: ") ? line.substring(6) : line;
                        if (data.trim().isEmpty()) return;
                        JsonNode json = mapper.readTree(data);
                        String type = json.path("type").asText();

                        switch (type) {
                            case "content_block_start": {
                                JsonNode cb = json.path("content_block");
                                String cbType = cb.path("type").asText();
                                currentBlockType[0] = cbType;
                                currentTextBuf[0] = new StringBuilder();
                                currentJsonBuf[0] = new StringBuilder();
                                if ("tool_use".equals(cbType)) {
                                    currentToolId[0] = cb.path("id").asText();
                                    currentToolName[0] = cb.path("name").asText();
                                }
                                break;
                            }
                            case "content_block_delta": {
                                JsonNode delta = json.path("delta");
                                String deltaType = delta.path("type").asText();
                                if ("text_delta".equals(deltaType)) {
                                    String chunk = delta.path("text").asText("");
                                    textBuf.append(chunk);
                                    currentTextBuf[0].append(chunk);
                                } else if ("input_json_delta".equals(deltaType)) {
                                    currentJsonBuf[0].append(delta.path("partial_json").asText(""));
                                }
                                break;
                            }
                            case "content_block_stop": {
                                if ("text".equals(currentBlockType[0])) {
                                    String t = currentTextBuf[0].toString();
                                    if (!t.isEmpty()) {
                                        blocks.add(LlmRequest.ContentBlock.text(t));
                                    }
                                } else if ("tool_use".equals(currentBlockType[0])) {
                                    JsonNode toolInput;
                                    try {
                                        String jsonStr = currentJsonBuf[0].toString();
                                        toolInput = jsonStr.isEmpty() ? mapper.createObjectNode() : mapper.readTree(jsonStr);
                                    } catch (Exception e) {
                                        toolInput = mapper.createObjectNode();
                                    }
                                    blocks.add(LlmRequest.ContentBlock.toolUse(
                                            currentToolId[0], currentToolName[0], toolInput));
                                    log.info("  Tool call: {}({})", currentToolName[0],
                                            truncateForLog(currentJsonBuf[0].toString(), 200));
                                }
                                currentBlockType[0] = null;
                                break;
                            }
                            case "message_delta": {
                                JsonNode usage = json.path("usage");
                                outputTokens[0] = usage.path("output_tokens").asInt(0);
                                stopReason[0] = json.path("delta").path("stop_reason").asText("end_turn");
                                break;
                            }
                            case "message_start": {
                                JsonNode usage = json.path("message").path("usage");
                                inputTokens[0] = usage.path("input_tokens").asInt(0);
                                break;
                            }
                            case "error": {
                                String msg = json.path("error").path("message").asText(null);
                                if (msg == null) msg = json.path("error").path("type").asText("unknown API error");
                                errorMsg[0] = msg;
                                log.warn("Anthropic SSE error event: {}", msg);
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                })
                .blockLast(Duration.ofSeconds(600));

        if (textBuf.length() == 0 && blocks.isEmpty() && errorMsg[0] != null) {
            throw new RuntimeException("Anthropic API error: " + errorMsg[0]);
        }
        if (textBuf.length() == 0 && blocks.isEmpty()) {
            throw new RuntimeException("Anthropic streaming returned no content");
        }

        log.info("Anthropic response: stopReason={} tokens={}/{} blocks={}",
                stopReason[0], inputTokens[0], outputTokens[0], blocks.size());
        return new LlmResponse(textBuf.toString(), inputTokens[0], outputTokens[0],
                model, stopReason[0], blocks);
    }

    public Flux<String> chatStream(LlmRequest request) {
        ObjectNode body = buildBody(request);
        body.put("stream", true);

        return webClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .mapNotNull(line -> {
                    try {
                        String data = line.startsWith("data: ") ? line.substring(6) : line;
                        if (data.trim().isEmpty()) return null;
                        JsonNode json = mapper.readTree(data);
                        if ("content_block_delta".equals(json.path("type").asText())) {
                            return json.path("delta").path("text").asText(null);
                        }
                        return null;
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

    private ObjectNode buildBody(LlmRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.getMaxTokens());
        body.put("temperature", request.getTemperature());

        String sys = request.getSystemPrompt();
        if (sys != null && !sys.trim().isEmpty()) {
            body.put("system", sys);
        }

        // Tools
        if (request.hasTools()) {
            ArrayNode toolsArray = mapper.createArrayNode();
            for (LlmRequest.ToolDef tool : request.getTools()) {
                ObjectNode t = mapper.createObjectNode();
                t.put("name", tool.getName());
                t.put("description", tool.getDescription());
                t.set("input_schema", mapper.valueToTree(tool.getInputSchema()));
                toolsArray.add(t);
            }
            body.set("tools", toolsArray);

            String tc = request.getToolChoice();
            if (tc != null) {
                ObjectNode tcNode = mapper.createObjectNode();
                tcNode.put("type", tc);
                body.set("tool_choice", tcNode);
            }
        }

        // Messages
        ArrayNode messages = mapper.createArrayNode();
        for (LlmRequest.Message msg : request.getMessages()) {
            ObjectNode m = mapper.createObjectNode();
            m.put("role", msg.getRole());

            List<LlmRequest.ContentBlock> blocks = msg.getContentBlocks();
            if (blocks != null) {
                ArrayNode contentArray = mapper.createArrayNode();
                for (LlmRequest.ContentBlock block : blocks) {
                    contentArray.add(serializeContentBlock(block));
                }
                m.set("content", contentArray);
            } else {
                String text = msg.getTextContent();
                m.put("content", text != null ? text : "");
            }
            messages.add(m);
        }
        body.set("messages", messages);

        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("user_id",
                "{\"device_id\":\"0000000000000000000000000000000000000000000000000000000000000001\","
                + "\"account_uuid\":\"\","
                + "\"session_id\":\"" + UUID.randomUUID() + "\"}");
        body.set("metadata", metadata);

        return body;
    }

    private ObjectNode serializeContentBlock(LlmRequest.ContentBlock block) {
        ObjectNode node = mapper.createObjectNode();
        String type = block.getType();
        node.put("type", type);

        switch (type) {
            case "text":
                node.put("text", block.getText());
                break;
            case "tool_use":
                node.put("id", block.getToolUseId());
                node.put("name", block.getToolName());
                node.set("input", block.getToolInput() != null
                        ? block.getToolInput() : mapper.createObjectNode());
                break;
            case "tool_result":
                node.put("tool_use_id", block.getToolUseId());
                node.put("content", block.getToolResultContent());
                if (block.isError()) {
                    node.put("is_error", true);
                }
                break;
        }
        return node;
    }

    private String truncateForLog(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
