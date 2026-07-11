package com.jvuln.collector;

import com.jvuln.store.model.EvidenceResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureEvidenceFetcherTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/page");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/private-redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.2/private");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/page", exchange -> respond(exchange, 200,
                "text/html", "<html><body><nav>menu</nav><main>Trusted evidence text</main>"
                        + "<script>ignore me</script></body></html>"));
        server.createContext("/large", exchange -> respond(exchange, 200,
                "text/plain", repeat('x', 300)));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(200);
                respond(exchange, 200, "text/plain", "late");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void followsBoundedRedirectAndRemovesNonContentHtml() throws Exception {
        SecureEvidenceFetcher fetcher = fetcher(1024, 100, 3, 500, 500,
                allowingLoopbackPolicy());

        EvidencePageFetcher.FetchOutcome result = fetcher.fetch(baseUrl + "/redirect");

        assertEquals(EvidenceResult.FetchStatus.SUCCESS, result.getStatus());
        assertTrue(result.getExcerpt().contains("Trusted evidence text"));
        assertFalse(result.getExcerpt().contains("menu"));
        assertFalse(result.getExcerpt().contains("ignore me"));
    }

    @Test
    void rejectsRedirectTargetAfterRevalidatingIt() throws Exception {
        PublicUrlPolicy policy = new PublicUrlPolicy(
                host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")}) {
            @Override
            public URI requirePublic(String value) {
                if (value.contains("127.0.0.2")) {
                    throw new SecurityException("redirect target is private");
                }
                return URI.create(value);
            }
        };
        SecureEvidenceFetcher fetcher = fetcher(1024, 100, 3, 500, 500, policy);

        EvidencePageFetcher.FetchOutcome result = fetcher.fetch(baseUrl + "/private-redirect");

        assertEquals(EvidenceResult.FetchStatus.REJECTED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("private"));
    }

    @Test
    void recordsOversizedAndTimedOutResponses() throws Exception {
        SecureEvidenceFetcher smallFetcher = fetcher(64, 100, 3, 500, 500,
                allowingLoopbackPolicy());
        SecureEvidenceFetcher timeoutFetcher = fetcher(1024, 100, 3, 500, 30,
                allowingLoopbackPolicy());

        EvidencePageFetcher.FetchOutcome oversized = smallFetcher.fetch(baseUrl + "/large");
        EvidencePageFetcher.FetchOutcome timedOut = timeoutFetcher.fetch(baseUrl + "/slow");

        assertEquals(EvidenceResult.FetchStatus.FAILED, oversized.getStatus());
        assertTrue(oversized.getErrorMessage().contains("size"));
        assertEquals(EvidenceResult.FetchStatus.TIMED_OUT, timedOut.getStatus());
    }

    private SecureEvidenceFetcher fetcher(int bytes, int chars, int redirects,
                                          int connectTimeout, int readTimeout,
                                          PublicUrlPolicy policy) {
        return new SecureEvidenceFetcher(policy, bytes, chars, redirects,
                connectTimeout, readTimeout);
    }

    private PublicUrlPolicy allowingLoopbackPolicy() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        return new PublicUrlPolicy(host -> new InetAddress[]{publicAddress});
    }

    private void respond(HttpExchange exchange, int status, String contentType,
                         String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
