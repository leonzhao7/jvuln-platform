package com.jvuln.collector;

import com.jvuln.collector.source.IntelSource;
import com.jvuln.store.model.CveIntelligence;
import com.jvuln.store.model.SourceData;
import com.jvuln.store.model.SourceResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCollectorTest {

    @Test
    void recordsSuccessNotFoundAndFailureWithoutOverloadingRawPayload() {
        SourceCollector collector = new SourceCollector(Duration.ofSeconds(1));

        List<SourceResult> results = collector.collect("CVE-2026-1000", Arrays.asList(
                source("NVD", IntelSource.IntelFragment.success(
                        "NVD", "nvd description", sourceData(), "{\"nvd\":true}")),
                source("GHSA", IntelSource.IntelFragment.notFound(
                        "GHSA", "[]")),
                throwingSource("OSV", new IOException("provider offline"))));

        assertEquals(SourceResult.Status.SUCCESS, results.get(0).getStatus());
        assertEquals("nvd description", results.get(0).getOriginalDescription());
        assertEquals("{\"nvd\":true}", results.get(0).getRawPayload());
        assertEquals(SourceResult.Status.NOT_FOUND, results.get(1).getStatus());
        assertEquals("[]", results.get(1).getRawPayload());
        assertEquals(SourceResult.Status.FAILED, results.get(2).getStatus());
        assertEquals("IOException", results.get(2).getErrorCode());
        assertEquals("provider offline", results.get(2).getErrorMessage());
        assertEquals("", results.get(2).getRawPayload());
        assertTrue(results.stream().allMatch(result -> result.getDurationMs() >= 0));
    }

    @Test
    void startsAllSourcesBeforeWaitingForCompletion() throws Exception {
        SourceCollector collector = new SourceCollector(Duration.ofSeconds(1));
        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);

        IntelSource first = blockingSource("NVD", allStarted, release);
        IntelSource second = blockingSource("GHSA", allStarted, release);
        IntelSource third = blockingSource("OSV", allStarted, release);
        Thread releaser = new Thread(() -> {
            try {
                if (allStarted.await(500, TimeUnit.MILLISECONDS)) {
                    release.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        releaser.start();

        List<SourceResult> results = collector.collect(
                "CVE-2026-1000", Arrays.asList(first, second, third));
        releaser.join();

        assertEquals(0L, allStarted.getCount());
        assertTrue(results.stream().allMatch(SourceResult::isSuccess));
    }

    @Test
    void cancelsUnfinishedSourceAndRecordsTimeout() {
        SourceCollector collector = new SourceCollector(Duration.ofMillis(60));
        AtomicBoolean interrupted = new AtomicBoolean(false);
        IntelSource slow = new IntelSource() {
            @Override
            public String name() { return "NVD"; }

            @Override
            public IntelFragment collect(String cveId) throws Exception {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                }
                return IntelFragment.success("NVD", "late", sourceData(), "{}");
            }
        };

        List<SourceResult> results = collector.collect(
                "CVE-2026-1000", Collections.singletonList(slow));

        assertEquals(SourceResult.Status.TIMED_OUT, results.get(0).getStatus());
        assertEquals("SourceTimeout", results.get(0).getErrorCode());
        assertFalse(results.get(0).getErrorMessage().isEmpty());
        assertEquals("", results.get(0).getRawPayload());
        for (int i = 0; i < 20 && !interrupted.get(); i++) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertTrue(interrupted.get());
    }

    @Test
    void preservesFailureResponsePayloadAndMapsProviderTimeout() {
        SourceCollector collector = new SourceCollector(Duration.ofSeconds(1));
        IntelSource parsingFailure = throwingSource("NVD", new IntelSource.SourceException(
                "PARSE_ERROR", "invalid provider JSON", "{not-json}",
                new IOException("invalid JSON")));
        IntelSource providerTimeout = throwingSource(
                "OSV", new SocketTimeoutException("read timed out"));

        List<SourceResult> results = collector.collect(
                "CVE-2026-1000", Arrays.asList(parsingFailure, providerTimeout));

        assertEquals(SourceResult.Status.FAILED, results.get(0).getStatus());
        assertEquals("PARSE_ERROR", results.get(0).getErrorCode());
        assertEquals("{not-json}", results.get(0).getRawPayload());
        assertFalse(results.get(0).getErrorMessage().contains("{not-json}"));
        assertEquals(SourceResult.Status.TIMED_OUT, results.get(1).getStatus());
        assertEquals("SourceTimeout", results.get(1).getErrorCode());
    }

    private IntelSource blockingSource(String name, CountDownLatch started, CountDownLatch release) {
        return new IntelSource() {
            @Override
            public String name() { return name; }

            @Override
            public IntelFragment collect(String cveId) throws Exception {
                started.countDown();
                if (!release.await(800, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("sources were not started concurrently");
                }
                return IntelFragment.success(name, name + " description", sourceData(), "{}");
            }
        };
    }

    private IntelSource source(String name, IntelSource.IntelFragment fragment) {
        return new IntelSource() {
            @Override
            public String name() { return name; }

            @Override
            public IntelFragment collect(String cveId) { return fragment; }
        };
    }

    private IntelSource throwingSource(String name, Exception failure) {
        return new IntelSource() {
            @Override
            public String name() { return name; }

            @Override
            public IntelFragment collect(String cveId) throws Exception { throw failure; }
        };
    }

    private static SourceData sourceData() {
        return new SourceData("CWE-79", "7.5", "", "HIGH", "org.example", "demo",
                "", "< 2.0", "2.0", "https://github.com/example/demo",
                Collections.<String>emptyList(), Collections.<CveIntelligence.Article>emptyList());
    }
}
