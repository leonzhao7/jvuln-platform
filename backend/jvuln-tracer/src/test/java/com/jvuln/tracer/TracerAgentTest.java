package com.jvuln.tracer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TracerInterceptor (the static callback layer)
 * and TracerAgent helper methods.
 *
 * These tests exercise the interceptor directly rather than loading a
 * javaagent, verifying depth tracking, sequence numbering, and correct
 * delegation to TracerEventWriter.
 */
class TracerAgentTest {

    @TempDir
    Path tmp;

    private TracerEventWriter writer;

    @BeforeEach
    void setUp() {
        Path trace = tmp.resolve("trace.jsonl");
        writer = new TracerEventWriter(trace.toString(), 5 * 1024 * 1024L);
        TracerInterceptor.setWriter(writer);
        TracerInterceptor.resetForTest();
    }

    @AfterEach
    void tearDown() {
        TracerInterceptor.setWriter(null);
        if (writer != null) {
            writer.close();
        }
    }

    @Test
    void onEnterWritesEventWithArgsAndIncrementsDepth() throws Exception {
        TracerInterceptor.onEnter("com.example.Foo", "bar",
                new Object[]{"hello", 42});

        writer.close();
        List<String> lines = Files.readAllLines(tmp.resolve("trace.jsonl"));
        assertEquals(1, lines.size(), "Should write exactly one event");
        String line = lines.get(0);
        assertTrue(line.contains("\"method\":\"bar\""), "Should contain method name");
        assertTrue(line.contains("\"class\":\"com.example.Foo\""), "Should contain class name");
        assertTrue(line.contains("hello"), "Should contain string arg");
        assertTrue(line.contains("42"), "Should contain int arg as string");
        assertTrue(line.contains("\"depth\":0"), "First call should be depth 0");
        assertTrue(line.contains("\"seq\":1"), "First event should be seq 1");
    }

    @Test
    void onExitWritesReturnValueAndDecrementsDepth() throws Exception {
        // Simulate enter then exit
        TracerInterceptor.onEnter("com.example.Foo", "bar", new Object[]{});
        TracerInterceptor.onExit("com.example.Foo", "bar", "result-value");

        writer.close();
        List<String> lines = Files.readAllLines(tmp.resolve("trace.jsonl"));
        assertEquals(2, lines.size(), "Should have enter + exit events");

        String exitLine = lines.get(1);
        assertTrue(exitLine.contains("\"ret\":\"result-value\""), "Exit should contain return value");
        assertTrue(exitLine.contains("\"depth\":1"), "Exit depth should be 1 (after enter incremented)");
    }

    @Test
    void onThrowWritesThrowInfoAndDecrementsDepth() throws Exception {
        TracerInterceptor.onEnter("com.example.Foo", "bar", new Object[]{});
        TracerInterceptor.onThrow("com.example.Foo", "bar",
                new RuntimeException("boom"));

        writer.close();
        List<String> lines = Files.readAllLines(tmp.resolve("trace.jsonl"));
        assertEquals(2, lines.size(), "Should have enter + throw events");

        String throwLine = lines.get(1);
        assertTrue(throwLine.contains("\"throw\":"), "Should contain throw marker");
        assertTrue(throwLine.contains("RuntimeException"), "Should contain exception class");
        assertTrue(throwLine.contains("boom"), "Should contain exception message");
    }

    @Test
    void depthTracksNestedCalls() throws Exception {
        // Simulate: enter A -> enter B -> exit B -> exit A
        TracerInterceptor.onEnter("com.example.A", "outer", new Object[]{});
        TracerInterceptor.onEnter("com.example.B", "inner", new Object[]{});
        TracerInterceptor.onExit("com.example.B", "inner", null);
        TracerInterceptor.onExit("com.example.A", "outer", null);

        writer.close();
        List<String> lines = Files.readAllLines(tmp.resolve("trace.jsonl"));
        assertEquals(4, lines.size());

        // Enter A at depth 0
        assertTrue(lines.get(0).contains("\"depth\":0"), "A enter at depth 0");
        // Enter B at depth 1
        assertTrue(lines.get(1).contains("\"depth\":1"), "B enter at depth 1");
        // Exit B at depth 2, then decremented
        assertTrue(lines.get(2).contains("\"depth\":2"), "B exit at depth 2");
        // Exit A at depth 1, then decremented
        assertTrue(lines.get(3).contains("\"depth\":1"), "A exit at depth 1");
    }

    @Test
    void sequenceIsMonotonicallyIncreasing() throws Exception {
        TracerInterceptor.onEnter("X", "a", new Object[]{});
        TracerInterceptor.onExit("X", "a", null);
        TracerInterceptor.onEnter("X", "b", new Object[]{});
        TracerInterceptor.onExit("X", "b", null);

        writer.close();
        List<String> lines = Files.readAllLines(tmp.resolve("trace.jsonl"));
        assertEquals(4, lines.size());

        // Extract seq values and verify monotonic increase
        for (int i = 0; i < lines.size(); i++) {
            assertTrue(lines.get(i).contains("\"seq\":" + (i + 1)),
                    "Event " + i + " should have seq " + (i + 1));
        }
    }

    @Test
    void nullWriterDoesNotThrow() {
        TracerInterceptor.setWriter(null);
        // These should silently no-op, not throw
        assertDoesNotThrow(() ->
                TracerInterceptor.onEnter("X", "m", new Object[]{}));
        assertDoesNotThrow(() ->
                TracerInterceptor.onExit("X", "m", "ret"));
        assertDoesNotThrow(() ->
                TracerInterceptor.onThrow("X", "m", new RuntimeException("x")));
    }

    @Test
    void nullArgsHandledGracefully() throws Exception {
        TracerInterceptor.onEnter("X", "m", null);

        writer.close();
        List<String> lines = Files.readAllLines(tmp.resolve("trace.jsonl"));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"args\":[]"), "Null args should produce empty array");
    }

    @Test
    void onExitWithNullReturn() throws Exception {
        TracerInterceptor.onEnter("X", "m", new Object[]{});
        TracerInterceptor.onExit("X", "m", null);

        writer.close();
        List<String> lines = Files.readAllLines(tmp.resolve("trace.jsonl"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("\"ret\":\"null\""), "Null return should stringify to 'null'");
    }

    @Test
    void onThrowWithNullThrowable() throws Exception {
        TracerInterceptor.onEnter("X", "m", new Object[]{});
        TracerInterceptor.onThrow("X", "m", null);

        writer.close();
        List<String> lines = Files.readAllLines(tmp.resolve("trace.jsonl"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("\"throw\":\"null\""), "Null throwable should produce throw:null");
    }
}
