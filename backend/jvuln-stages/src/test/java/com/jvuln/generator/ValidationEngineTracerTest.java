package com.jvuln.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ValidationEngineTracerTest {

    @Test
    void gracefullySkipsWhenTraceTargetMissing(@TempDir Path tmp) throws Exception {
        AgentContext ctx = new AgentContext(tmp, null);
        ctx.traceTarget = null;
        ValidationEngine engine = new ValidationEngine(null);
        ValidationResult result = new ValidationResult("poc");
        assertDoesNotThrow(() -> {
            java.lang.reflect.Method m = ValidationEngine.class.getDeclaredMethod(
                    "buildTraceDigest", AgentContext.class, ValidationResult.class);
            m.setAccessible(true);
            m.invoke(engine, ctx, result);
        });
        assertNull(result.runtimeTrace);
    }

    @Test
    void gracefullySkipsWhenTracerJarMissing(@TempDir Path tmp) throws Exception {
        AgentContext ctx = new AgentContext(tmp, null);
        ctx.traceTarget = new TraceTarget("org.h2", "h2", "1.4.199",
                Collections.singleton("org.h2"), Arrays.asList("org.h2.Foo.bar"));
        ValidationEngine engine = new ValidationEngine(null);
        ProcessBuilder pb = new ProcessBuilder("echo", "test");
        assertDoesNotThrow(() -> {
            java.lang.reflect.Method m = ValidationEngine.class.getDeclaredMethod(
                    "attachTracerIfConfigured", AgentContext.class, ProcessBuilder.class);
            m.setAccessible(true);
            m.invoke(engine, ctx, pb);
        });
        assertNull(pb.environment().get("JAVA_TOOL_OPTIONS"));
    }
}
