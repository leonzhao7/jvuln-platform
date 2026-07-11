package com.jvuln.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RequestLogContextTest {

    @Test
    void routesSafeWebAndLlmNoticesToBoundListener() {
        List<String> messages = new ArrayList<>();

        try (RequestLogContext.Scope ignored = RequestLogContext.bind(messages::add)) {
            RequestLogContext.logWebRequest(
                    "GET", "https://example.test/path?api_key=secret#fragment");
            RequestLogContext.logLlmRequest("model-name", "/v1/responses");
        }

        assertEquals("WEB GET https://example.test/path", messages.get(0));
        assertEquals("LLM POST model=model-name endpoint=/v1/responses", messages.get(1));
        assertFalse(messages.get(0).contains("secret"));
    }

    @Test
    void webFilterLogsEverySubscription() {
        List<String> messages = new ArrayList<>();
        ClientRequest request = ClientRequest.create(HttpMethod.GET,
                java.net.URI.create("https://example.test/data?token=secret")).build();

        try (RequestLogContext.Scope ignored = RequestLogContext.bind(messages::add)) {
            Mono<ClientResponse> exchange = RequestLogContext.webRequestFilter().filter(
                    request, value -> Mono.just(
                            ClientResponse.create(HttpStatus.OK).build()));
            exchange.block();
            exchange.block();
        }

        assertEquals(2, messages.size());
        assertEquals("WEB GET https://example.test/data", messages.get(0));
        assertEquals(messages.get(0), messages.get(1));
    }
}
