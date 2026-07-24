package com.jvuln.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TraceDigestBuilderTest {

    @Test
    void buildsMissingDigestWhenFileDoesNotExist(@TempDir Path tmp) {
        TraceTarget target = new TraceTarget("org.h2", "h2", "1.4.199",
                Collections.singleton("org.h2"), Arrays.asList("org.h2.command.Parser.parse"));

        Map<String, Object> digest = TraceDigestBuilder.buildDigest(tmp.resolve("missing.jsonl"), target);

        assertEquals(false, digest.get("traceCaptured"));
        assertNotNull(digest.get("reason"));
    }

    @Test
    void buildsDigestWithReachedMethod(@TempDir Path tmp) throws Exception {
        Path trace = tmp.resolve("trace.jsonl");
        Files.write(trace, Arrays.asList(
            "{\"seq\":1,\"depth\":0,\"class\":\"org.h2.command.Parser\",\"method\":\"parse\",\"args\":[\"SELECT * FROM test\"],\"ret\":\"ok\"}",
            "{\"seq\":2,\"depth\":1,\"class\":\"org.h2.util.StringUtils\",\"method\":\"trim\",\"args\":[\"SELECT * FROM test\"],\"ret\":\"SELECT * FROM test\"}"
        ));

        TraceTarget target = new TraceTarget("org.h2", "h2", "1.4.199",
                Collections.singleton("org.h2"), Arrays.asList("org.h2.command.Parser.parse"));

        Map<String, Object> digest = TraceDigestBuilder.buildDigest(trace, target);

        assertEquals(true, digest.get("traceCaptured"));
        assertEquals(2, digest.get("totalCalls"));
        assertEquals(1, digest.get("maxDepth"));

        @SuppressWarnings("unchecked")
        Map<String, Object> methodsOfInterest = (Map<String, Object>) digest.get("methodsOfInterest");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> reached = (java.util.List<Map<String, Object>>) methodsOfInterest.get("reached");
        assertEquals(1, reached.size());
        assertEquals("org.h2.command.Parser.parse", reached.get(0).get("method"));
    }

    @Test
    void buildsDigestWithNotReachedMethod(@TempDir Path tmp) throws Exception {
        Path trace = tmp.resolve("trace.jsonl");
        Files.write(trace, Arrays.asList(
            "{\"seq\":1,\"depth\":0,\"class\":\"org.h2.util.StringUtils\",\"method\":\"trim\",\"args\":[\"test\"],\"ret\":\"test\"}"
        ));

        TraceTarget target = new TraceTarget("org.h2", "h2", "1.4.199",
                Collections.singleton("org.h2"), Arrays.asList("org.h2.command.Parser.parse"));

        Map<String, Object> digest = TraceDigestBuilder.buildDigest(trace, target);

        @SuppressWarnings("unchecked")
        Map<String, Object> methodsOfInterest = (Map<String, Object>) digest.get("methodsOfInterest");
        @SuppressWarnings("unchecked")
        java.util.List<String> notReached = (java.util.List<String>) methodsOfInterest.get("notReached");
        assertEquals(1, notReached.size());
        assertTrue(notReached.contains("org.h2.command.Parser.parse"));
    }
}
