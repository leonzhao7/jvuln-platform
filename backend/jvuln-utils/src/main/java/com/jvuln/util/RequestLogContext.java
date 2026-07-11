package com.jvuln.util;

import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.function.Consumer;

/** Routes outbound request notices to the progress stream of the current pipeline task. */
public final class RequestLogContext {

    private static final InheritableThreadLocal<Consumer<String>> LISTENER =
            new InheritableThreadLocal<>();

    private RequestLogContext() {}

    public static Scope bind(Consumer<String> listener) {
        Consumer<String> previous = LISTENER.get();
        if (listener == null) {
            LISTENER.remove();
        } else {
            LISTENER.set(listener);
        }
        return new Scope(previous);
    }

    public static ExchangeFilterFunction webRequestFilter() {
        return (request, next) -> {
            Consumer<String> listener = LISTENER.get();
            String message = webMessage(request.method().name(), request.url().toString());
            return Mono.defer(() -> {
                notifyListener(listener, message);
                return next.exchange(request);
            });
        };
    }

    public static ExchangeFilterFunction llmRequestFilter(String model, String endpoint) {
        return (request, next) -> {
            Consumer<String> listener = LISTENER.get();
            String message = llmMessage(model, endpoint);
            return Mono.defer(() -> {
                notifyListener(listener, message);
                return next.exchange(request);
            });
        };
    }

    public static void logWebRequest(String method, String url) {
        notifyListener(LISTENER.get(), webMessage(method, url));
    }

    public static void logLlmRequest(String model, String endpoint) {
        notifyListener(LISTENER.get(), llmMessage(model, endpoint));
    }

    private static void notifyListener(Consumer<String> listener, String message) {
        if (listener != null) {
            try {
                listener.accept(message);
            } catch (RuntimeException ignored) {
                // Progress logging must never block the outbound request itself.
            }
        }
    }

    private static String webMessage(String method, String url) {
        return "WEB " + value(method) + " " + safeUrl(url);
    }

    private static String llmMessage(String model, String endpoint) {
        return "LLM POST model=" + value(model) + " endpoint=" + value(endpoint);
    }

    private static String safeUrl(String url) {
        try {
            URI uri = URI.create(value(url));
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null)
                    .toString();
        } catch (Exception e) {
            String value = value(url);
            int query = value.indexOf('?');
            return query < 0 ? value : value.substring(0, query);
        }
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Scope implements AutoCloseable {
        private final Consumer<String> previous;
        private boolean closed;

        private Scope(Consumer<String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                LISTENER.remove();
            } else {
                LISTENER.set(previous);
            }
        }
    }
}
