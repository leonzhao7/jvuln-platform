package com.jvuln.llm.util;

import com.jvuln.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * LLM请求工具类
 * 提供统一的LLM请求重试机制，失败时最多重试3次
 */
public class LlmUtil {

    private static final Logger log = LoggerFactory.getLogger(LlmUtil.class);
    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);

    /**
     * 执行LLM请求，失败时自动重试最多3次
     *
     * @param requestExecutor LLM请求执行器
     * @param description 请求描述（用于日志）
     * @return LLM响应
     */
    public static LlmResponse executeWithRetry(
            Supplier<LlmResponse> requestExecutor,
            String description) {

        int attempt = 0;
        Exception lastException = null;

        while (attempt <= MAX_RETRIES) {
            try {
                if (attempt > 0) {
                    long backoffMs = Math.min(
                            INITIAL_BACKOFF.toMillis() * (1L << (attempt - 1)),
                            MAX_BACKOFF.toMillis()
                    );
                    log.info("Retrying LLM request after {}ms (attempt {}/{}): {}",
                            backoffMs, attempt, MAX_RETRIES, description);
                    Thread.sleep(backoffMs);
                }

                return requestExecutor.get();

            } catch (Exception e) {
                lastException = e;
                log.warn("LLM request failed (attempt {}/{}): {} - {}",
                        attempt + 1, MAX_RETRIES + 1, description, e.getMessage());
                attempt++;
            }
        }

        log.error("LLM request exhausted all {} retries: {}", MAX_RETRIES, description);
        throw new RuntimeException(
                "LLM request failed after " + MAX_RETRIES + " retries: " + description,
                lastException);
    }

    /**
     * 执行LLM流式请求，失败时自动重试最多3次
     *
     * @param requestExecutor 流式请求执行器
     * @param description 请求描述（用于日志）
     * @return Flux流
     */
    public static Flux<String> executeStreamWithRetry(
            Supplier<Flux<String>> requestExecutor,
            String description) {

        return Flux.defer(requestExecutor)
                .retryWhen(Retry.backoff(MAX_RETRIES, INITIAL_BACKOFF)
                        .maxBackoff(MAX_BACKOFF)
                        .doBeforeRetry(signal -> {
                            Throwable error = signal.failure();
                            log.warn("LLM streaming request failed (attempt {}/{}): {} - {}",
                                    signal.totalRetries() + 1,
                                    MAX_RETRIES,
                                    description,
                                    error.getMessage());
                        })
                        .onRetryExhaustedThrow((spec, signal) -> {
                            log.error("LLM streaming request exhausted all {} retries: {}",
                                    MAX_RETRIES, description);
                            return new RuntimeException(
                                    "LLM streaming request failed after " + MAX_RETRIES + " retries: " + description,
                                    signal.failure());
                        }));
    }
}
