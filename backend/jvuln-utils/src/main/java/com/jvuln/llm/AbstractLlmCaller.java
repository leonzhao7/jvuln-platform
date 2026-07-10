package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.impl.LlmConfigProvider;
import com.jvuln.llm.util.HttpUtil;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

abstract class AbstractLlmCaller implements LlmProtocolCaller {

    protected final ObjectMapper mapper;
    protected final String model;
    private final String endpointUri;
    private final String endpointPath;
    private final WebClient webClient;

    AbstractLlmCaller(LlmConfigProvider.ActiveConfig config, ObjectMapper mapper,
                      LlmEndpoint expectedEndpoint, boolean messagesHeaders) {
        if (config == null) {
            throw new IllegalArgumentException("LLM config is required");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("ObjectMapper is required");
        }
        LlmEndpoint configuredEndpoint = LlmEndpoint.fromPath(config.getEndpoint());
        if (configuredEndpoint != expectedEndpoint) {
            throw new IllegalArgumentException("Expected endpoint " + expectedEndpoint.getPath()
                    + " but configured " + config.getEndpoint());
        }
        this.mapper = mapper;
        this.model = config.getModel();
        this.endpointPath = expectedEndpoint.getPath();
        this.endpointUri = expectedEndpoint.resolveUri(config.getBaseUrl());
        this.webClient = buildWebClient(config.getApiKey(), messagesHeaders);
    }

    protected String postJson(ObjectNode body) {
        try {
            return HttpUtil.executeBlockingWithRetry(
                    client -> client.post()
                            .uri(endpointUri)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body.toString())
                            .retrieve()
                            .bodyToMono(String.class),
                    webClient,
                    "LLM API request to " + endpointPath
            );
        } catch (WebClientResponseException e) {
            throw apiException(e);
        }
    }

    protected Flux<String> postSse(ObjectNode body) {
        return HttpUtil.executeFluxWithRetry(
                client -> client.post()
                        .uri(endpointUri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .bodyValue(body.toString())
                        .retrieve()
                        .bodyToFlux(String.class),
                webClient,
                "LLM streaming API request to " + endpointPath
        );
    }

    protected List<String> ssePayloads(String chunk) {
        if (chunk == null || chunk.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> payloads = new ArrayList<>();
        String[] lines = chunk.split("\\r?\\n");
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("event:") || value.startsWith("id:")) {
                continue;
            }
            if (value.startsWith("data:")) {
                value = value.substring("data:".length()).trim();
            }
            if (!value.isEmpty()) {
                payloads.add(value);
            }
        }
        return payloads;
    }

    protected JsonNode parseArguments(String arguments) {
        try {
            String value = arguments == null ? "" : arguments.trim();
            return value.isEmpty() ? mapper.createObjectNode() : mapper.readTree(value);
        } catch (Exception e) {
            ObjectNode raw = mapper.createObjectNode();
            raw.put("_raw", arguments == null ? "" : arguments);
            return raw;
        }
    }

    protected RuntimeException parseException(String raw, Exception cause) {
        return new RuntimeException("Failed to parse LLM response from " + endpointPath
                + ": " + describeRaw(raw), cause);
    }

    protected RuntimeException eventException(JsonNode event) {
        String message = event.path("error").path("message").asText(null);
        if (message == null || message.isEmpty()) {
            message = event.path("message").asText("unknown streaming error");
        }
        return new RuntimeException("LLM streaming error from " + endpointPath + ": " + message);
    }

    private WebClient buildWebClient(String apiKey, boolean messagesHeaders) {
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(300));
        WebClient.Builder builder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(10 * 1024 * 1024));
        if (messagesHeaders) {
            builder.defaultHeader("anthropic-version", "2023-06-01");
        }
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
            if (messagesHeaders) {
                builder.defaultHeader("x-api-key", apiKey);
            }
        }
        return builder.build();
    }

    private RuntimeException apiException(WebClientResponseException e) {
        return new RuntimeException("LLM API error " + e.getRawStatusCode() + " from "
                + endpointPath + ": " + truncate(e.getResponseBodyAsString(), 1200), e);
    }

    private String describeRaw(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "empty response";
        }
        String value = raw.trim();
        if (value.startsWith("<")) {
            return "received HTML instead of JSON: " + truncate(value, 400);
        }
        return truncate(value, 800);
    }

    protected String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }
}
