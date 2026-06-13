package com.jvuln.llm.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.function.Function;

/**
 * HTTP请求工具类
 * 提供统一的HTTP请求重试机制，失败时最多重试3次
 */
public class HttpUtil {

    private static final Logger log = LoggerFactory.getLogger(HttpUtil.class);
    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);

    /**
     * 执行HTTP请求（返回Mono），失败时自动重试最多3次
     *
     * @param requestBuilder 请求构建函数
     * @param webClient WebClient实例
     * @param description 请求描述（用于日志）
     * @param <T> 响应类型
     * @return Mono响应
     */
    public static <T> Mono<T> executeWithRetry(
            Function<WebClient, Mono<T>> requestBuilder,
            WebClient webClient,
            String description) {

        return requestBuilder.apply(webClient)
                .retryWhen(Retry.backoff(MAX_RETRIES, INITIAL_BACKOFF)
                        .maxBackoff(MAX_BACKOFF)
                        .filter(HttpUtil::isRetryableError)
                        .doBeforeRetry(signal -> {
                            Throwable error = signal.failure();
                            log.warn("HTTP request failed (attempt {}/{}): {} - {}",
                                    signal.totalRetries() + 1,
                                    MAX_RETRIES,
                                    description,
                                    getErrorMessage(error));
                        })
                        .onRetryExhaustedThrow((spec, signal) -> {
                            log.error("HTTP request exhausted all {} retries: {}",
                                    MAX_RETRIES, description);
                            return new RuntimeException(
                                    "HTTP request failed after " + MAX_RETRIES + " retries: " + description,
                                    signal.failure());
                        }));
    }

    /**
     * 执行HTTP请求（返回Flux），失败时自动重试最多3次
     *
     * @param requestBuilder 请求构建函数
     * @param webClient WebClient实例
     * @param description 请求描述（用于日志）
     * @param <T> 响应类型
     * @return Flux响应流
     */
    public static <T> Flux<T> executeFluxWithRetry(
            Function<WebClient, Flux<T>> requestBuilder,
            WebClient webClient,
            String description) {

        return requestBuilder.apply(webClient)
                .retryWhen(Retry.backoff(MAX_RETRIES, INITIAL_BACKOFF)
                        .maxBackoff(MAX_BACKOFF)
                        .filter(HttpUtil::isRetryableError)
                        .doBeforeRetry(signal -> {
                            Throwable error = signal.failure();
                            log.warn("HTTP streaming request failed (attempt {}/{}): {} - {}",
                                    signal.totalRetries() + 1,
                                    MAX_RETRIES,
                                    description,
                                    getErrorMessage(error));
                        })
                        .onRetryExhaustedThrow((spec, signal) -> {
                            log.error("HTTP streaming request exhausted all {} retries: {}",
                                    MAX_RETRIES, description);
                            return new RuntimeException(
                                    "HTTP streaming request failed after " + MAX_RETRIES + " retries: " + description,
                                    signal.failure());
                        }));
    }

    /**
     * 阻塞式HTTP请求执行，失败时自动重试最多3次
     *
     * @param requestBuilder 请求构建函数
     * @param webClient WebClient实例
     * @param description 请求描述（用于日志）
     * @param <T> 响应类型
     * @return 响应结果
     */
    public static <T> T executeBlockingWithRetry(
            Function<WebClient, Mono<T>> requestBuilder,
            WebClient webClient,
            String description) {

        return executeWithRetry(requestBuilder, webClient, description).block();
    }

    /**
     * 判断是否为可重试的错误
     */
    private static boolean isRetryableError(Throwable throwable) {
        // 网络连接错误，可重试
        if (throwable instanceof WebClientRequestException) {
            return true;
        }

        // HTTP响应错误
        if (throwable instanceof WebClientResponseException) {
            WebClientResponseException ex = (WebClientResponseException) throwable;
            int status = ex.getStatusCode().value();
            // 5xx服务器错误或429限流错误，可重试
            // 4xx客户端错误（除429外），不重试
            return status >= 500 || status == 429 || status == 408;
        }

        // 超时错误，可重试
        if (throwable instanceof java.util.concurrent.TimeoutException) {
            return true;
        }

        // IO异常，可重试
        if (throwable instanceof java.io.IOException) {
            return true;
        }

        // 其他异常，不重试
        return false;
    }

    /**
     * 获取异常的可读错误信息
     */
    private static String getErrorMessage(Throwable throwable) {
        if (throwable instanceof WebClientResponseException) {
            WebClientResponseException ex = (WebClientResponseException) throwable;
            return String.format("HTTP %d: %s",
                    ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        }
        return throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
    }
}
