package com.jvuln.collector;

import com.jvuln.collector.source.IntelSource;
import com.jvuln.store.model.SourceData;
import com.jvuln.store.model.SourceResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.jvuln.util.ValueUtils.limit;

@Component
public class SourceCollector {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);
    private final Duration timeout;

    public SourceCollector() {
        this(DEFAULT_TIMEOUT);
    }

    SourceCollector(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Source collection timeout must be positive");
        }
        this.timeout = timeout;
    }

    public List<SourceResult> collect(String cveId, List<IntelSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        ExecutorService executor = Executors.newFixedThreadPool(sources.size());
        CompletionService<IndexedResult> completions = new ExecutorCompletionService<>(executor);
        List<Future<IndexedResult>> futures = new ArrayList<>();
        List<SourceResult> ordered = new ArrayList<>(
                Collections.nCopies(sources.size(), (SourceResult) null));
        long startedAt = System.nanoTime();
        long deadline = startedAt + timeout.toNanos();
        try {
            for (int i = 0; i < sources.size(); i++) {
                final int index = i;
                final IntelSource source = sources.get(i);
                futures.add(completions.submit(() -> collectOne(index, source, cveId)));
            }
            receiveUntilDeadline(completions, ordered, sources.size(), deadline);
            addTimeouts(ordered, sources, futures, startedAt);
            return Collections.unmodifiableList(ordered);
        } finally {
            executor.shutdownNow();
        }
    }

    private void receiveUntilDeadline(CompletionService<IndexedResult> completions,
                                      List<SourceResult> ordered, int count,
                                      long deadline) {
        int received = 0;
        while (received < count) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                Future<IndexedResult> completed = completions.poll(remaining, TimeUnit.NANOSECONDS);
                if (completed == null) {
                    return;
                }
                IndexedResult result = completed.get();
                ordered.set(result.index, result.result);
                received++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                throw new IllegalStateException("Source collector task failed unexpectedly", e);
            }
        }
    }

    private IndexedResult collectOne(int index, IntelSource source, String cveId) {
        long started = System.nanoTime();
        try {
            IntelSource.IntelFragment fragment = source.collect(cveId);
            long duration = elapsedMillis(started);
            if (fragment == null) {
                return new IndexedResult(index, failed(source, duration,
                        new IllegalStateException("Source returned null")));
            }
            SourceResult result = new SourceResult(sourceOf(source), fragment.getStatus(), duration,
                    "", "", fragment.getDescription(), fragment.getParsedData(),
                    fragment.getRawPayload());
            return new IndexedResult(index, result);
        } catch (Exception e) {
            SourceResult result = isTimeout(e)
                    ? timedOut(source, elapsedMillis(started), e)
                    : failed(source, elapsedMillis(started), e);
            return new IndexedResult(index, result);
        }
    }

    private void addTimeouts(List<SourceResult> ordered, List<IntelSource> sources,
                             List<Future<IndexedResult>> futures, long startedAt) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i) != null) {
                continue;
            }
            futures.get(i).cancel(true);
            ordered.set(i, new SourceResult(sourceOf(sources.get(i)),
                    SourceResult.Status.TIMED_OUT, elapsedMillis(startedAt),
                    "SourceTimeout", "Timed out after " + timeout.toMillis() + " ms",
                    "", SourceData.empty(), ""));
        }
    }

    private SourceResult failed(IntelSource source, long durationMs, Exception error) {
        IntelSource.SourceException sourceError = error instanceof IntelSource.SourceException
                ? (IntelSource.SourceException) error : null;
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        return new SourceResult(sourceOf(source), SourceResult.Status.FAILED, durationMs,
                sourceError == null ? error.getClass().getSimpleName() : sourceError.getErrorCode(),
                sanitize(message), "", SourceData.empty(),
                sourceError == null ? "" : sourceError.getRawPayload());
    }

    private SourceResult timedOut(IntelSource source, long durationMs, Exception error) {
        String message = error.getMessage() == null ? "Source request timed out" : error.getMessage();
        return new SourceResult(sourceOf(source), SourceResult.Status.TIMED_OUT, durationMs,
                "SourceTimeout", sanitize(message), "", SourceData.empty(), "");
    }

    private boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (name.contains("timeout") || name.contains("timedout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private SourceResult.Source sourceOf(IntelSource source) {
        String name = source.name() == null ? "" : source.name().trim().toUpperCase(Locale.ROOT);
        if (name.contains("GITHUB") || name.equals("GHSA")) {
            return SourceResult.Source.GHSA;
        }
        return SourceResult.Source.valueOf(name);
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAt));
    }

    private String sanitize(String message) {
        String sanitized = message.replaceAll("(?i)bearer\\s+[^\\s,;]+", "Bearer [redacted]")
                .replaceAll("(?i)(api[-_ ]?key[=: ]+)[^\\s,;]+", "$1[redacted]");
        return limit(sanitized, 500);
    }

    private static class IndexedResult {
        private final int index;
        private final SourceResult result;

        private IndexedResult(int index, SourceResult result) {
            this.index = index;
            this.result = result;
        }
    }
}
