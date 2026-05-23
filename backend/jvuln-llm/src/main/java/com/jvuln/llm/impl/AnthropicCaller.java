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
import java.util.UUID;

/**
 * Calls Anthropic Messages API directly.
 * Endpoint: POST {baseUrl}/v1/messages
 * Auth: x-api-key header + anthropic-version header
 */
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
                .responseTimeout(Duration.ofSeconds(180));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(base)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024));
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            // Send both: official Anthropic endpoint uses x-api-key,
            // local proxies (new-api/one-api) expect Authorization: Bearer
            builder.defaultHeader("x-api-key", apiKey);
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.webClient = builder.build();
    }

    public LlmResponse chat(LlmRequest request) {
        ObjectNode body = buildBody(request);
        body.put("stream", true);
        log.info("Anthropic chat (streaming): model={} maxTokens={}", model, request.getMaxTokens());

        StringBuilder text = new StringBuilder();
        int[] inputTokens  = {0};
        int[] outputTokens = {0};
        String[] stopReason = {"end_turn"};
        String[] errorMsg   = {null};

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
                        if ("content_block_delta".equals(type)) {
                            String chunk = json.path("delta").path("text").asText(null);
                            if (chunk != null) text.append(chunk);
                        } else if ("message_delta".equals(type)) {
                            JsonNode usage = json.path("usage");
                            outputTokens[0] = usage.path("output_tokens").asInt(0);
                            stopReason[0] = json.path("delta").path("stop_reason").asText("end_turn");
                        } else if ("message_start".equals(type)) {
                            JsonNode usage = json.path("message").path("usage");
                            inputTokens[0] = usage.path("input_tokens").asInt(0);
                        } else if ("error".equals(type)) {
                            String msg = json.path("error").path("message").asText(null);
                            if (msg == null) msg = json.path("error").path("type").asText("unknown API error");
                            errorMsg[0] = msg;
                            log.warn("Anthropic SSE error event: {}", msg);
                        }
                    } catch (Exception ignored) {}
                })
                .blockLast(Duration.ofSeconds(300));

        if (text.length() == 0 && errorMsg[0] != null) {
            throw new RuntimeException("Anthropic API error: " + errorMsg[0]);
        }
        if (text.length() == 0) {
            throw new RuntimeException("Anthropic streaming returned no content");
        }

        log.info("Anthropic response: stopReason={} tokens={}/{}", stopReason[0], inputTokens[0], outputTokens[0]);
        return new LlmResponse(text.toString(), inputTokens[0], outputTokens[0], model, stopReason[0]);
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
                        // content_block_delta events carry the actual text
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

        // Anthropic uses top-level "system" field, not a system message in the array
        String sys = request.getSystemPrompt();
        if (sys != null && !sys.trim().isEmpty()) {
            body.put("system", sys);
        }

        ArrayNode messages = mapper.createArrayNode();
        for (LlmRequest.Message msg : request.getMessages()) {
            ObjectNode m = mapper.createObjectNode();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            messages.add(m);
        }
        body.set("messages", messages);

        // new-api channel affinity requires metadata.user_id to route the request;
        // actual id values are not validated — only field presence matters
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("user_id",
                "{\"device_id\":\"0000000000000000000000000000000000000000000000000000000000000001\","
                + "\"account_uuid\":\"\","
                + "\"session_id\":\"" + UUID.randomUUID() + "\"}");
        body.set("metadata", metadata);

        return body;
    }
}
