package com.jvuln.tracer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TracerConfigTest {

    @Test
    void parsesIncludesAndOut() {
        TracerConfig cfg = TracerConfig.parse("includes=org.h2;org.h2.command,out=/tmp/trace.jsonl");
        assertEquals("/tmp/trace.jsonl", cfg.outputPath);
        assertTrue(cfg.includes.contains("org.h2"));
        assertTrue(cfg.includes.contains("org.h2.command"));
    }

    @Test
    void emptyArgsYieldsNoIncludes() {
        TracerConfig cfg = TracerConfig.parse("");
        assertTrue(cfg.includes.isEmpty());
        assertNull(cfg.outputPath);
    }

    @Test
    void nullArgsYieldsNoIncludes() {
        TracerConfig cfg = TracerConfig.parse(null);
        assertTrue(cfg.includes.isEmpty());
        assertNull(cfg.outputPath);
    }
}
