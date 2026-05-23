package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
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
    private final WebClient webClient;
    private final String model;
    private final ObjectMapper mapper;

    public LlmCaller(String baseUrl, String apiKey, String model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(180));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
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

        String raw = webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToMono(String.class)
                .block();

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
            throw new RuntimeException("Failed to parse LLM response: " + e.getMessage(), e);
        }
    }

    public Flux<String> chatStream(LlmRequest request) {
        ObjectNode body = buildBody(request, true);

        return webClient.post()
                .uri("/chat/completions")
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
            m.put("content", msg.getContent());
            messages.add(m);
        }
        body.set("messages", messages);
        return body;
    }
}
