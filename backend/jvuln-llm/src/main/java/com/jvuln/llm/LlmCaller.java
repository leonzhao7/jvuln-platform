package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

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
        ObjectNode body = buildBody(request, false);
        log.info("LLM chat: model={} maxTokens={}", model, request.getMaxTokens());

        String raw = postChatCompletions(body, false);

        try {
            JsonNode json = mapper.readTree(raw);
            JsonNode choice = json.path("choices").path(0);
            String content = choice.path("message").path("content").asText();
            JsonNode usage = json.path("usage");
            return new LlmResponse(
                    content,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    json.path("model").asText(model),
                    choice.path("finish_reason").asText("stop")
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LLM response from " + CHAT_COMPLETIONS_PATH
                    + ": " + describeRawResponse(raw), e);
        }
    }

    public Flux<String> chatStream(LlmRequest request) {
        ObjectNode body = buildBody(request, true);

        return webClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .mapNotNull(line -> {
                    try {
                        String data = line.startsWith("data: ") ? line.substring(6) : line;
                        if (data.trim().isEmpty() || data.equals("[DONE]")) return null;
                        JsonNode json = mapper.readTree(data);
                        return json.path("choices").path(0).path("delta").path("content").asText(null);
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

    private ObjectNode buildBody(LlmRequest request, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", request.getTemperature());
        body.put("max_tokens", request.getMaxTokens());
        body.put("stream", stream);

        if (request.isJsonMode()) {
            ObjectNode fmt = mapper.createObjectNode();
            fmt.put("type", "json_object");
            body.set("response_format", fmt);
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
            return webClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw new RuntimeException("LLM API error " + e.getRawStatusCode() + " from "
                    + CHAT_COMPLETIONS_PATH + ": " + truncate(e.getResponseBodyAsString(), 1200), e);
        }
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
}
