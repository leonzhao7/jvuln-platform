package com.jvuln.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ValidationEngineTracerTest {

    private void invokeAttach(ValidationEngine engine, AgentContext ctx, ProcessBuilder pb) throws Exception {
        java.lang.reflect.Method m = ValidationEngine.class.getDeclaredMethod(
                "attachTracerIfConfigured", AgentContext.class, ProcessBuilder.class);
        m.setAccessible(true);
        m.invoke(engine, ctx, pb);
    }

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
        assertDoesNotThrow(() -> invokeAttach(engine, ctx, pb));
        assertNull(pb.environment().get("JAVA_TOOL_OPTIONS"));
    }

    @Test
    void attachesTracerWhenJarFoundInBackendLayout(@TempDir Path root) throws Exception {
        // Mirror the real layout: <root>/backend/jvuln-tracer/target/<jar>
        // and cvePath at <root>/backend/workspace/CVE-2021-42392
        Path tracerTarget = root.resolve("backend/jvuln-tracer/target");
        Files.createDirectories(tracerTarget);
        Files.createFile(tracerTarget.resolve("jvuln-tracer-1.0.0-SNAPSHOT.jar"));
        // decoys that must NOT be picked
        Files.createFile(tracerTarget.resolve("original-jvuln-tracer-1.0.0-SNAPSHOT.jar"));
        Files.createFile(tracerTarget.resolve("jvuln-tracer-1.0.0-SNAPSHOT-sources.jar"));

        Path cvePath = root.resolve("backend/workspace/CVE-2021-42392");
        Files.createDirectories(cvePath.resolve("poc"));

        AgentContext ctx = new AgentContext(cvePath, null);
        ctx.traceTarget = new TraceTarget("org.h2", "h2", "1.4.199",
                Collections.singleton("org.h2"), Arrays.asList("org.h2.Foo.bar"));
        ValidationEngine engine = new ValidationEngine(null);
        ProcessBuilder pb = new ProcessBuilder("echo", "test");

        invokeAttach(engine, ctx, pb);

        String opts = pb.environment().get("JAVA_TOOL_OPTIONS");
        assertNotNull(opts, "javaagent should be attached when the tracer jar is reachable");
        assertTrue(opts.contains("-javaagent:"), opts);
        assertTrue(opts.contains("jvuln-tracer-1.0.0-SNAPSHOT.jar"), opts);
        assertFalse(opts.contains("original-"), "must not select the un-shaded original- jar: " + opts);
        assertFalse(opts.contains("-sources.jar"), "must not select the sources jar: " + opts);
        assertTrue(opts.contains("includes=org.h2"), opts);
    }
}
