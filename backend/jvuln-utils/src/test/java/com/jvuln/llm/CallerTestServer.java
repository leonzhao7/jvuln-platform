package com.jvuln.llm;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

final class CallerTestServer implements AutoCloseable {

    private final HttpServer server;
    private final Deque<Response> responses = new ArrayDeque<>();
    private volatile String lastPath;
    private volatile String lastBody;
    private volatile Headers lastHeaders;

    CallerTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    void enqueueJson(String body) {
        responses.addLast(new Response(200, "application/json", body));
    }

    void enqueueSse(String body) {
        responses.addLast(new Response(200, "text/event-stream", body));
    }

    String getBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String getLastPath() {
        return lastPath;
    }

    String getLastBody() {
        return lastBody;
    }

    String getLastHeader(String name) {
        Headers headers = lastHeaders;
        return headers == null ? null : headers.getFirst(name);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastPath = exchange.getRequestURI().getPath();
        lastHeaders = exchange.getRequestHeaders();
        lastBody = readBody(exchange.getRequestBody());
        Response response = responses.pollFirst();
        if (response == null) {
            response = new Response(500, "application/json", "{\"error\":\"no response queued\"}");
        }
        byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", response.contentType);
        exchange.sendResponseHeaders(response.status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String readBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class Response {
        private final int status;
        private final String contentType;
        private final String body;

        private Response(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
