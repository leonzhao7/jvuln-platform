# Javaagent Runtime Trace for PoC Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Attach a package-scoped javaagent to every demo JVM launch so the backend can see whether the PoC reached the vulnerable code and with what arguments, then feed a compact digest to the LLM for smarter PoC iteration.

**Architecture:** A new `jvuln-tracer` Maven module produces a self-contained, shaded javaagent JAR that instruments all classes in the vulnerable artifact's actual Java packages (derived by scanning its Maven sources JAR). The backend sets `JAVA_TOOL_OPTIONS` on the demo `ProcessBuilder`, then reads the resulting `poc/trace.jsonl` after each PoC run and sends a compact digest through the live `buildPhaseDirective` → `renderPhaseDirective` path.

**Tech Stack:** Java 8, ByteBuddy 1.14.18 (shaded/relocated inside jvuln-tracer), maven-shade-plugin, Spring Boot 2.7, JUnit Jupiter, jackson-databind (already on classpath in jvuln-stages).

## Global Constraints

- Every source file must stay under 80 KB.
- Every method must stay under 256 lines.
- Prefer / reuse code from `backend/jvuln-utils` before adding local helpers.
- If behaviour is needed in two places, move it to `jvuln-utils`.
- `jvuln-tracer` runs in a separate JVM — zero dependency on `jvuln-utils` or Spring; ByteBuddy must be shaded.
- The javaagent must never change demo control flow; exceptions must always be rethrown unchanged.
- `JAVA_TOOL_OPTIONS` is the correct env-var name — never `JAVA_TOL_OPTIONS`.
- Vulnerable-version source of truth: parse the generated `vuln-demo/pom.xml` for the actual dependency version; fall back to `affectedVersions.to` only when `to != fixedVersion`, else `affectedVersions.from`.

---

## File Structure

### New files
| Path | Responsibility |
|------|---------------|
| `backend/jvuln-tracer/pom.xml` | Module POM: ByteBuddy dep, shade plugin, agent manifest |
| `backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerConfig.java` | Parse agent-args string → include-packages, output path |
| `backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerEventWriter.java` | Write JSONL events, enforce 5 MB cap |
| `backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerInterceptor.java` | ByteBuddy `@Advice` class: depth counter + event capture |
| `backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerAgent.java` | `premain` entry; wires config → writer → ByteBuddy builder |
| `backend/jvuln-tracer/src/test/java/com/jvuln/tracer/TracerConfigTest.java` | Unit tests for arg parsing |
| `backend/jvuln-tracer/src/test/java/com/jvuln/tracer/TracerEventWriterTest.java` | Unit tests for writer / cap behaviour |
| `backend/jvuln-tracer/src/test/java/com/jvuln/tracer/TracerAgentTest.java` | Integration: load agent into test JVM, assert JSONL |
| `backend/jvuln-stages/src/main/java/com/jvuln/generator/MavenSourcePackageScanner.java` | Download sources JAR, scan package declarations |
| `backend/jvuln-stages/src/main/java/com/jvuln/generator/TraceTarget.java` | Immutable value: groupId, artifactId, version, packages, methodsOfInterest |
| `backend/jvuln-stages/src/main/java/com/jvuln/generator/TraceDigestBuilder.java` | Read trace.jsonl → compact digest Map |
| `backend/jvuln-stages/src/test/java/com/jvuln/generator/MavenSourcePackageScannerTest.java` | Package scanner tests (in-memory JAR fixture) |
| `backend/jvuln-stages/src/test/java/com/jvuln/generator/TraceDigestBuilderTest.java` | Digest builder tests (fixture JSONL files) |
| `backend/jvuln-stages/src/test/java/com/jvuln/generator/ValidationEngineTracerTest.java` | Graceful-fallback regression tests |

### Modified files
| Path | Change |
|------|--------|
| `backend/pom.xml` | Add `<module>jvuln-tracer</module>` |
| `backend/jvuln-stages/src/main/java/com/jvuln/generator/GeneratorConstants.java` | Add `TRACE_ARG_CAP`, `TRACE_FILE_CAP_BYTES`, `TRACE_DIGEST_TEXT_CAP` |
| `backend/jvuln-stages/src/main/java/com/jvuln/generator/StageDataExtractor.java` | Add `extractTraceTarget(Object intelligence, Object analysis, Path vulnDemoPom)` |
| `backend/jvuln-stages/src/main/java/com/jvuln/generator/AgentContext.java` | Add `TraceTarget traceTarget` field |
| `backend/jvuln-stages/src/main/java/com/jvuln/generator/ValidationResult.java` | Add `Map<String,Object> runtimeTrace`; extend `toMap`/`mergeFrom`/`fromJson` |
| `backend/jvuln-stages/src/main/java/com/jvuln/generator/ValidationEngine.java` | `doStartApp`: set `JAVA_TOOL_OPTIONS`; `validatePoc`: run `TraceDigestBuilder` |
| `backend/jvuln-stages/src/main/java/com/jvuln/generator/AgentPhaseEngine.java` | `POC_FIX` branch: append trace digest to `actual` |
| `backend/jvuln-stages/src/main/java/com/jvuln/generator/ArtifactGenStage.java` | After stage data extraction, call `extractTraceTarget`; populate `agentCtx.traceTarget` |

---

## Task 1: `jvuln-tracer` module POM + `TracerConfig` + `TracerEventWriter`

**Files:**
- Create: `backend/jvuln-tracer/pom.xml`
- Create: `backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerConfig.java`
- Create: `backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerEventWriter.java`
- Create: `backend/jvuln-tracer/src/test/java/com/jvuln/tracer/TracerConfigTest.java`
- Create: `backend/jvuln-tracer/src/test/java/com/jvuln/tracer/TracerEventWriterTest.java`
- Modify: `backend/pom.xml`

**Interfaces:**
- Produces: `TracerConfig.parse(String agentArgs)` → `TracerConfig`; fields `includes` (Set\<String\>), `outputPath` (String)
- Produces: `TracerEventWriter(String outputPath, long fileCap)` + `void writeEvent(int seq, int depth, String cls, String method, String[] args, String retOrThrow)`; `void close()`

- [ ] **Step 1: Add `jvuln-tracer` module to root POM**

In `backend/pom.xml` change:
```xml
    <modules>
        <module>jvuln-utils</module>
        <module>jvuln-stages</module>
        <module>jvuln-app</module>
    </modules>
```
to:
```xml
    <modules>
        <module>jvuln-utils</module>
        <module>jvuln-tracer</module>
        <module>jvuln-stages</module>
        <module>jvuln-app</module>
    </modules>
```

- [ ] **Step 2: Write failing tests for `TracerConfig`**

`backend/jvuln-tracer/src/test/java/com/jvuln/tracer/TracerConfigTest.java`:
```java
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
```

- [ ] **Step 3: Create `backend/jvuln-tracer/pom.xml`** (needed to compile)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.jvuln</groupId>
        <artifactId>jvuln-backend</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>jvuln-tracer</artifactId>
    <name>JVuln Tracer Agent</name>

    <dependencies>
        <dependency>
            <groupId>net.bytebuddy</groupId>
            <artifactId>byte-buddy</artifactId>
            <version>1.14.18</version>
        </dependency>
        <dependency>
            <groupId>net.bytebuddy</groupId>
            <artifactId>byte-buddy-agent</artifactId>
            <version>1.14.18</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Premain-Class>com.jvuln.tracer.TracerAgent</Premain-Class>
                            <Can-Redefine-Classes>false</Can-Redefine-Classes>
                            <Can-Retransform-Classes>false</Can-Retransform-Classes>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.2</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <shadedArtifactAttached>false</shadedArtifactAttached>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                            <relocations>
                                <relocation>
                                    <pattern>net.bytebuddy</pattern>
                                    <shadedPattern>com.jvuln.tracer.shaded.bytebuddy</shadedPattern>
                                </relocation>
                            </relocations>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <manifestEntries>
                                        <Premain-Class>com.jvuln.tracer.TracerAgent</Premain-Class>
                                        <Can-Redefine-Classes>false</Can-Redefine-Classes>
                                        <Can-Retransform-Classes>false</Can-Retransform-Classes>
                                    </manifestEntries>
                                </transformer>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 4: Implement `TracerConfig`**

`backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerConfig.java`:
```java
package com.jvuln.tracer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class TracerConfig {
    final Set<String> includes;
    final String outputPath;

    private TracerConfig(Set<String> includes, String outputPath) {
        this.includes = Collections.unmodifiableSet(includes);
        this.outputPath = outputPath;
    }

    static TracerConfig parse(String agentArgs) {
        Set<String> includes = new LinkedHashSet<>();
        String outputPath = null;
        if (agentArgs == null || agentArgs.trim().isEmpty()) {
            return new TracerConfig(includes, null);
        }
        for (String token : agentArgs.split(",")) {
            token = token.trim();
            if (token.startsWith("includes=")) {
                for (String pkg : token.substring("includes=".length()).split(";")) {
                    String p = pkg.trim();
                    if (!p.isEmpty()) includes.add(p);
                }
            } else if (token.startsWith("out=")) {
                outputPath = token.substring("out=".length()).trim();
            }
        }
        return new TracerConfig(includes, outputPath);
    }
}
```

- [ ] **Step 5: Run `TracerConfigTest`, verify PASS**

```bash
cd backend && mvn test -pl jvuln-tracer -Dtest=TracerConfigTest -q
```
Expected: `BUILD SUCCESS`, 3 tests pass.

- [ ] **Step 6: Write failing tests for `TracerEventWriter`**

`backend/jvuln-tracer/src/test/java/com/jvuln/tracer/TracerEventWriterTest.java`:
```java
package com.jvuln.tracer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TracerEventWriterTest {

    @Test
    void writesJsonlLine(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("trace.jsonl");
        try (TracerEventWriter w = new TracerEventWriter(out.toString(), 5 * 1024 * 1024L)) {
            w.writeEvent(1, 0, "org.h2.Foo", "bar", new String[]{"argA"}, "ret:ok");
        }
        List<String> lines = Files.readAllLines(out);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"seq\":1"));
        assertTrue(lines.get(0).contains("\"class\":\"org.h2.Foo\""));
        assertTrue(lines.get(0).contains("\"method\":\"bar\""));
        assertTrue(lines.get(0).contains("argA"));
    }

    @Test
    void stopsWritingAtFileCap(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("trace.jsonl");
        try (TracerEventWriter w = new TracerEventWriter(out.toString(), 100L)) {
            for (int i = 0; i < 20; i++) {
                w.writeEvent(i, 0, "Cls", "m", new String[]{"x"}, "r");
            }
        }
        long size = Files.size(out);
        assertTrue(size <= 200, "Expected file to be capped, got " + size);
    }

    @Test
    void capsArgAt512Chars(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("trace.jsonl");
        String longArg = new String(new char[1000]).replace('\0', 'A');
        try (TracerEventWriter w = new TracerEventWriter(out.toString(), 5 * 1024 * 1024L)) {
            w.writeEvent(1, 0, "C", "m", new String[]{longArg}, "r");
        }
        String content = new String(Files.readAllBytes(out));
        assertFalse(content.contains(new String(new char[513]).replace('\0', 'A')), "arg should be capped at 512");
        assertTrue(content.contains(new String(new char[512]).replace('\0', 'A')));
    }
}
```

- [ ] **Step 7: Implement `TracerEventWriter`**

`backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerEventWriter.java`:
```java
package com.jvuln.tracer;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

final class TracerEventWriter implements Closeable {

    private static final int ARG_CAP = 512;

    private final String outputPath;
    private final long fileCap;
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private volatile BufferedWriter writer;

    TracerEventWriter(String outputPath, long fileCap) {
        this.outputPath = outputPath;
        this.fileCap = fileCap;
        try {
            this.writer = new BufferedWriter(new FileWriter(outputPath, true));
        } catch (Exception e) {
            this.writer = null;
        }
    }

    void writeEvent(int seq, int depth, String cls, String method, String[] args, String retOrThrow) {
        if (writer == null || bytesWritten.get() >= fileCap) return;
        String line = buildLine(seq, depth, cls, method, args, retOrThrow);
        try {
            synchronized (this) {
                if (bytesWritten.get() >= fileCap) return;
                writer.write(line);
                writer.newLine();
                writer.flush();
                bytesWritten.addAndGet(line.length() + 1);
            }
        } catch (Exception ignored) {}
    }

    private String buildLine(int seq, int depth, String cls, String method,
                              String[] args, String retOrThrow) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"seq\":").append(seq);
        sb.append(",\"depth\":").append(depth);
        sb.append(",\"class\":").append(jsonStr(cls));
        sb.append(",\"method\":").append(jsonStr(method));
        sb.append(",\"args\":[");
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(jsonStr(capArg(args[i])));
            }
        }
        sb.append("]");
        if (retOrThrow != null) {
            boolean isThrow = retOrThrow.startsWith("throw:");
            if (isThrow) {
                sb.append(",\"throw\":").append(jsonStr(retOrThrow.substring("throw:".length())));
            } else {
                sb.append(",\"ret\":").append(jsonStr(retOrThrow));
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String capArg(String s) {
        if (s == null) return "null";
        return s.length() > ARG_CAP ? s.substring(0, ARG_CAP) + "..." : s;
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    @Override
    public void close() {
        BufferedWriter w = writer;
        if (w != null) {
            try { w.close(); } catch (IOException ignored) {}
        }
    }
}
```

- [ ] **Step 8: Run `TracerEventWriterTest`, verify PASS**

```bash
cd backend && mvn test -pl jvuln-tracer -Dtest=TracerEventWriterTest -q
```
Expected: `BUILD SUCCESS`, 3 tests pass.

- [ ] **Step 9: Commit**

```bash
cd backend && git add jvuln-tracer pom.xml
git commit -m "feat(tracer): add jvuln-tracer module with TracerConfig and TracerEventWriter"
```

---

## Task 2: `TracerInterceptor` + `TracerAgent` (ByteBuddy instrumentation)

**Files:**
- Create: `backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerInterceptor.java`
- Create: `backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerAgent.java`
- Create: `backend/jvuln-tracer/src/test/java/com/jvuln/tracer/TracerAgentTest.java`

**Interfaces:**
- Consumes: `TracerConfig.parse(String)`, `TracerEventWriter(String, long)`
- Produces: `TracerAgent.premain(String agentArgs, Instrumentation inst)` — ByteBuddy agent entry point

- [ ] **Step 1: Write failing test for `TracerAgent`**

`backend/jvuln-tracer/src/test/java/com/jvuln/tracer/TracerAgentTest.java`:
```java
package com.jvuln.tracer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TracerAgentTest {

    @Test
    void capturesMethodCallsInTargetPackage(@TempDir Path tmp) throws Exception {
        Path trace = tmp.resolve("trace.jsonl");
        System.setProperty("tracer.test.includes", "com.jvuln.tracer.TracerAgentTest$TestTarget");
        System.setProperty("tracer.test.out", trace.toString());
        
        TestTarget t = new TestTarget();
        t.foo("argA", 42);
        
        Thread.sleep(100);
        assertTrue(Files.exists(trace), "Trace file should exist");
        List<String> lines = Files.readAllLines(trace);
        assertTrue(lines.size() > 0, "Should have captured at least one call");
        String content = String.join("\n", lines);
        assertTrue(content.contains("\"method\":\"foo\""), "Should capture method name");
        assertTrue(content.contains("argA"), "Should capture string arg");
    }
    
    public static class TestTarget {
        public String foo(String s, int n) {
            return s + ":" + n;
        }
    }
}
```

- [ ] **Step 2: Implement `TracerInterceptor`** (ByteBuddy advice class)

`backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerInterceptor.java`:
```java
package com.jvuln.tracer;

import java.util.concurrent.atomic.AtomicInteger;

final class TracerInterceptor {

    private static final AtomicInteger seqGen = new AtomicInteger(0);
    private static final ThreadLocal<Integer> depth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };
    
    private static volatile TracerEventWriter writer;

    static void setWriter(TracerEventWriter w) {
        writer = w;
    }

    public static void onEnter(String className, String methodName, Object[] args) {
        try {
            int d = depth.get();
            depth.set(d + 1);
            
            TracerEventWriter w = writer;
            if (w == null) return;
            
            String[] argStrings = new String[args == null ? 0 : args.length];
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    argStrings[i] = stringify(args[i]);
                }
            }
            
            w.writeEvent(seqGen.incrementAndGet(), d, className, methodName, argStrings, null);
        } catch (Exception ignored) {}
    }

    public static void onExit(String className, String methodName, Object ret) {
        try {
            int d = depth.get();
            if (d > 0) depth.set(d - 1);
            
            TracerEventWriter w = writer;
            if (w == null) return;
            
            w.writeEvent(seqGen.incrementAndGet(), d, className, methodName, null, stringify(ret));
        } catch (Exception ignored) {}
    }

    public static void onThrow(String className, String methodName, Throwable t) {
        try {
            int d = depth.get();
            if (d > 0) depth.set(d - 1);
            
            TracerEventWriter w = writer;
            if (w == null) return;
            
            String throwStr = "throw:" + (t == null ? "null" : t.getClass().getName() + ": " + t.getMessage());
            w.writeEvent(seqGen.incrementAndGet(), d, className, methodName, null, throwStr);
        } catch (Exception ignored) {}
    }

    private static String stringify(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return (String) o;
        try {
            return o.toString();
        } catch (Exception e) {
            return o.getClass().getName() + "@?";
        }
    }
}
```

- [ ] **Step 3: Implement `TracerAgent.premain`**

`backend/jvuln-tracer/src/main/java/com/jvuln/tracer/TracerAgent.java`:
```java
package com.jvuln.tracer;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import java.lang.instrument.Instrumentation;

public final class TracerAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            TracerConfig cfg = TracerConfig.parse(agentArgs);
            if (cfg.outputPath == null || cfg.includes.isEmpty()) {
                return;
            }

            TracerEventWriter writer = new TracerEventWriter(cfg.outputPath, 5 * 1024 * 1024L);
            TracerInterceptor.setWriter(writer);

            AgentBuilder.Transformer transformer = new AgentBuilder.Transformer() {
                @Override
                public net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
                        net.bytebuddy.dynamic.DynamicType.Builder<?> builder,
                        net.bytebuddy.description.type.TypeDescription typeDescription,
                        ClassLoader classLoader,
                        net.bytebuddy.dynamic.JavaModule module) {
                    return builder
                        .visit(Advice.to(MethodEntryAdvice.class).on(ElementMatchers.isMethod()))
                        .visit(Advice.to(MethodExitAdvice.class).on(ElementMatchers.isMethod()));
                }
            };

            AgentBuilder agentBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.DISABLED)
                .ignore(ElementMatchers.none())
                .type(buildPackageMatcher(cfg.includes))
                .transform(transformer);

            agentBuilder.installOn(inst);
        } catch (Exception e) {
            System.err.println("TracerAgent failed to initialize: " + e.getMessage());
        }
    }

    private static net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription> 
            buildPackageMatcher(java.util.Set<String> packages) {
        net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription> matcher = null;
        for (String pkg : packages) {
            net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription> pkgMatcher = 
                ElementMatchers.nameStartsWith(pkg);
            matcher = (matcher == null) ? pkgMatcher : matcher.or(pkgMatcher);
        }
        return matcher == null ? ElementMatchers.<net.bytebuddy.description.type.TypeDescription>none() : matcher;
    }

    public static class MethodEntryAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin("#t") String className,
                                  @Advice.Origin("#m") String methodName,
                                  @Advice.AllArguments Object[] args) {
            TracerInterceptor.onEnter(className, methodName, args);
        }
    }

    public static class MethodExitAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Origin("#t") String className,
                                 @Advice.Origin("#m") String methodName,
                                 @Advice.Return Object ret,
                                 @Advice.Thrown Throwable t) {
            if (t != null) {
                TracerInterceptor.onThrow(className, methodName, t);
            } else {
                TracerInterceptor.onExit(className, methodName, ret);
            }
        }
    }
}
```

- [ ] **Step 4: Run test, verify PASS** (note: agent test may need manual jar build first)

```bash
cd backend && mvn clean package -pl jvuln-tracer -DskipTests -q
cd backend && mvn test -pl jvuln-tracer -Dtest=TracerAgentTest -q
```
Expected: `BUILD SUCCESS`, test passes after jar is built.

- [ ] **Step 5: Verify shaded JAR manifest**

```bash
unzip -p backend/jvuln-tracer/target/jvuln-tracer-1.0.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Premain-Class
```
Expected: `Premain-Class: com.jvuln.tracer.TracerAgent`

- [ ] **Step 6: Commit**

```bash
cd backend && git add jvuln-tracer
git commit -m "feat(tracer): implement ByteBuddy TracerAgent and TracerInterceptor"
```

---
## Task 3: `GeneratorConstants` trace caps + `MavenSourcePackageScanner`

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/GeneratorConstants.java`
- Create: `backend/jvuln-stages/src/main/java/com/jvuln/generator/MavenSourcePackageScanner.java`
- Create: `backend/jvuln-stages/src/test/java/com/jvuln/generator/MavenSourcePackageScannerTest.java`

**Interfaces:**
- Produces: `GeneratorConstants.TRACE_ARG_CAP`, `TRACE_FILE_CAP_BYTES`, `TRACE_DIGEST_TEXT_CAP`
- Produces: `MavenSourcePackageScanner.scanPackages(String groupId, String artifactId, String version)` → `Set<String>` (package names)

- [ ] **Step 1: Add trace constants to `GeneratorConstants`**

In `backend/jvuln-stages/src/main/java/com/jvuln/generator/GeneratorConstants.java` add after line 86:
```java
    // ==================== Runtime trace ====================

    /** Per-argument string cap (chars) */
    public static final int TRACE_ARG_CAP = 512;

    /** Raw trace file hard cap (bytes) */
    public static final int TRACE_FILE_CAP_BYTES = 5 * 1024 * 1024;

    /** Trace digest text field cap (chars) */
    public static final int TRACE_DIGEST_TEXT_CAP = 2000;
```

- [ ] **Step 2: Write failing test for `MavenSourcePackageScanner`**

`backend/jvuln-stages/src/test/java/com/jvuln/generator/MavenSourcePackageScannerTest.java`:
```java
package com.jvuln.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class MavenSourcePackageScannerTest {

    @Test
    void scansPackagesFromSourceJar(@TempDir Path tmp) throws Exception {
        Path fakeJar = tmp.resolve("test-1.0.0-sources.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(fakeJar))) {
            addEntry(zip, "org/h2/util/JdbcUtils.java", "package org.h2.util;\nclass JdbcUtils {}");
            addEntry(zip, "org/h2/command/Parser.java", "package org.h2.command;\nclass Parser {}");
            addEntry(zip, "org/h2/command/dml/Select.java", "package org.h2.command.dml;\nclass Select {}");
            addEntry(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0");
        }
        
        MavenSourcePackageScanner scanner = new MavenSourcePackageScanner(null);
        Set<String> packages = scanner.scanPackagesFromJar(fakeJar);
        
        assertEquals(3, packages.size());
        assertTrue(packages.contains("org.h2.util"));
        assertTrue(packages.contains("org.h2.command"));
        assertTrue(packages.contains("org.h2.command.dml"));
    }
    
    private void addEntry(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes("UTF-8"));
        zip.closeEntry();
    }
}
```

- [ ] **Step 3: Implement `MavenSourcePackageScanner`**

`backend/jvuln-stages/src/main/java/com/jvuln/generator/MavenSourcePackageScanner.java`:
```java
package com.jvuln.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
class MavenSourcePackageScanner {

    private static final Logger log = LoggerFactory.getLogger(MavenSourcePackageScanner.class);
    private static final String CENTRAL = "https://repo1.maven.org/maven2";
    private static final Pattern PACKAGE_DECL = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;");

    private final WebClient webClient;

    MavenSourcePackageScanner(WebClient webClient) {
        this.webClient = webClient;
    }

    Set<String> scanPackages(String groupId, String artifactId, String version) {
        if (groupId == null || artifactId == null || version == null) {
            log.warn("MavenSourcePackageScanner: missing coordinates");
            return new LinkedHashSet<>();
        }
        String url = buildSourcesUrl(groupId, artifactId, version);
        log.info("Downloading Maven sources JAR: {}", url);
        
        File tmp = null;
        try {
            tmp = File.createTempFile("jvuln-src-", ".jar");
            downloadJar(url, tmp);
            return scanPackagesFromJar(tmp.toPath());
        } catch (Exception e) {
            log.warn("Failed to scan packages from {}:{}:{}: {}", groupId, artifactId, version, e.getMessage());
            return new LinkedHashSet<>();
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    Set<String> scanPackagesFromJar(Path jarPath) throws Exception {
        Set<String> packages = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".java")) continue;
                String pkg = extractPackage(zip.getInputStream(entry));
                if (pkg != null && !pkg.isEmpty()) {
                    packages.add(pkg);
                }
            }
        }
        log.info("Scanned {} packages from {}", packages.size(), jarPath.getFileName());
        return packages;
    }

    private String extractPackage(InputStream in) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = PACKAGE_DECL.matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
                if (line.trim().startsWith("public") || line.trim().startsWith("class") 
                        || line.trim().startsWith("interface") || line.trim().startsWith("enum")) {
                    break;
                }
            }
        }
        return null;
    }

    private String buildSourcesUrl(String groupId, String artifactId, String version) {
        String groupPath = groupId.replace('.', '/');
        return CENTRAL + "/" + groupPath + "/" + artifactId + "/" + version
                + "/" + artifactId + "-" + version + "-sources.jar";
    }

    private void downloadJar(String url, File dest) throws Exception {
        try (OutputStream os = new FileOutputStream(dest)) {
            byte[] bytes = webClient.get().uri(url).retrieve().bodyToMono(byte[].class).block();
            if (bytes != null) os.write(bytes);
        }
    }
}
```

- [ ] **Step 4: Run test, verify PASS**

```bash
cd backend && mvn test -pl jvuln-stages -Dtest=MavenSourcePackageScannerTest -q
```
Expected: `BUILD SUCCESS`, test passes.

- [ ] **Step 5: Commit**

```bash
cd backend && git add jvuln-stages/src/main/java/com/jvuln/generator/GeneratorConstants.java jvuln-stages/src/main/java/com/jvuln/generator/MavenSourcePackageScanner.java jvuln-stages/src/test/java/com/jvuln/generator/MavenSourcePackageScannerTest.java
git commit -m "feat(generator): add MavenSourcePackageScanner and trace constants"
```

---

## Task 4: `TraceTarget` value type + `StageDataExtractor.extractTraceTarget`

**Files:**
- Create: `backend/jvuln-stages/src/main/java/com/jvuln/generator/TraceTarget.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/StageDataExtractor.java`

**Interfaces:**
- Consumes: `MavenSourcePackageScanner.scanPackages(String, String, String)` → `Set<String>`
- Produces: `TraceTarget` immutable value: `groupId`, `artifactId`, `version`, `packages`, `methodsOfInterest`
- Produces: `StageDataExtractor.extractTraceTarget(Object intelligence, Object analysis, MavenSourcePackageScanner scanner)` → `TraceTarget` (or null)

- [ ] **Step 1: Implement `TraceTarget`**

`backend/jvuln-stages/src/main/java/com/jvuln/generator/TraceTarget.java`:
```java
package com.jvuln.generator;

import java.util.Collections;
import java.util.List;
import java.util.Set;

final class TraceTarget {
    final String groupId;
    final String artifactId;
    final String version;
    final Set<String> packages;
    final List<String> methodsOfInterest;

    TraceTarget(String groupId, String artifactId, String version,
                Set<String> packages, List<String> methodsOfInterest) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.packages = packages == null ? Collections.<String>emptySet() : packages;
        this.methodsOfInterest = methodsOfInterest == null ? Collections.<String>emptyList() : methodsOfInterest;
    }
    
    boolean isValid() {
        return groupId != null && !groupId.isEmpty()
                && artifactId != null && !artifactId.isEmpty()
                && version != null && !version.isEmpty()
                && !packages.isEmpty();
    }
}
```

- [ ] **Step 2: Add `extractTraceTarget` to `StageDataExtractor`**

In `backend/jvuln-stages/src/main/java/com/jvuln/generator/StageDataExtractor.java` after line 90, add:
```java

    TraceTarget extractTraceTarget(Object intelligence, Object analysis, MavenSourcePackageScanner scanner) {
        try {
            JsonNode intel = mapper.valueToTree(intelligence);
            JsonNode artifact = intel.path("artifact");
            
            String groupId = null;
            String artifactId = null;
            if (artifact.isObject()) {
                groupId = artifact.path("groupId").asText(null);
                artifactId = artifact.path("artifactId").asText(null);
            }
            
            if (groupId == null || artifactId == null) {
                return null;
            }
            
            String version = resolveVulnerableVersion(intel);
            if (version == null) {
                return null;
            }
            
            Set<String> packages = scanner.scanPackages(groupId, artifactId, version);
            if (packages.isEmpty()) {
                return null;
            }
            
            java.util.List<String> methodsOfInterest = extractMethodsOfInterest(analysis);
            
            return new TraceTarget(groupId, artifactId, version, packages, methodsOfInterest);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveVulnerableVersion(JsonNode intel) {
        JsonNode affectedVersions = intel.path("affectedVersions");
        String to = affectedVersions.path("to").asText(null);
        String from = affectedVersions.path("from").asText(null);
        String fixedVersion = intel.path("fixedVersion").asText(null);
        
        if (to != null && !to.equals(fixedVersion)) {
            return to;
        }
        if (from != null) {
            return from;
        }
        return to;
    }

    private java.util.List<String> extractMethodsOfInterest(Object analysis) {
        java.util.List<String> methods = new java.util.ArrayList<>();
        if (analysis == null) return methods;
        
        try {
            JsonNode root = mapper.valueToTree(analysis);
            JsonNode files = root.path("analyzedFiles");
            if (!files.isArray()) return methods;
            
            for (JsonNode fileNode : files) {
                String fileName = fileNode.path("fileName").asText("");
                if (!fileName.endsWith(".java")) continue;
                
                String className = deriveClassName(fileName);
                JsonNode methodNodes = fileNode.path("methods");
                if (methodNodes.isArray()) {
                    for (JsonNode m : methodNodes) {
                        String methodName = m.path("methodName").asText("");
                        if (!methodName.isEmpty() && className != null) {
                            methods.add(className + "." + methodName);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return methods;
    }

    private String deriveClassName(String fileName) {
        if (fileName == null || !fileName.contains("/")) return null;
        String normalized = fileName.replace('\\', '/');
        int srcIdx = normalized.indexOf("src/main/java/");
        if (srcIdx >= 0) {
            String rel = normalized.substring(srcIdx + "src/main/java/".length());
            return rel.replace('/', '.').replace(".java", "");
        }
        return null;
    }
```

- [ ] **Step 3: Add `MavenSourcePackageScanner` dependency to `StageDataExtractor`**

In `backend/jvuln-stages/src/main/java/com/jvuln/generator/StageDataExtractor.java` change constructor (line 25):
```java
    private final ObjectMapper mapper;

    StageDataExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }
```
to:
```java
    private final ObjectMapper mapper;
    private final MavenSourcePackageScanner scanner;

    StageDataExtractor(ObjectMapper mapper, MavenSourcePackageScanner scanner) {
        this.mapper = mapper;
        this.scanner = scanner;
    }
```

And update the `extractTraceTarget` signature (remove `scanner` param since it's now a field):
```java
    TraceTarget extractTraceTarget(Object intelligence, Object analysis) {
```
and use `this.scanner` inside.

- [ ] **Step 4: Run build, verify no compilation errors**

```bash
cd backend && mvn compile -pl jvuln-stages -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
cd backend && git add jvuln-stages/src/main/java/com/jvuln/generator/TraceTarget.java jvuln-stages/src/main/java/com/jvuln/generator/StageDataExtractor.java
git commit -m "feat(generator): add TraceTarget and extractTraceTarget logic"
```

---

## Task 5: `TraceDigestBuilder`

**Files:**
- Create: `backend/jvuln-stages/src/main/java/com/jvuln/generator/TraceDigestBuilder.java`
- Create: `backend/jvuln-stages/src/test/java/com/jvuln/generator/TraceDigestBuilderTest.java`

**Interfaces:**
- Consumes: `TraceTarget` (for methodsOfInterest)
- Produces: `TraceDigestBuilder.buildDigest(Path traceFile, TraceTarget target)` → `Map<String,Object>` (compact digest)

- [ ] **Step 1: Write failing test for `TraceDigestBuilder`**

`backend/jvuln-stages/src/test/java/com/jvuln/generator/TraceDigestBuilderTest.java`:
```java
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
```

- [ ] **Step 2: Implement `TraceDigestBuilder`**

`backend/jvuln-stages/src/main/java/com/jvuln/generator/TraceDigestBuilder.java`:
```java
package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TraceDigestBuilder {

    private static final Logger log = LoggerFactory.getLogger(TraceDigestBuilder.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    static Map<String, Object> buildDigest(Path traceFile, TraceTarget target) {
        Map<String, Object> digest = new LinkedHashMap<>();
        
        if (!Files.exists(traceFile) || !Files.isReadable(traceFile)) {
            digest.put("traceCaptured", false);
            digest.put("reason", "Trace file not found or not readable: " + traceFile.getFileName());
            return digest;
        }

        try {
            List<String> lines = Files.readAllLines(traceFile);
            if (lines.isEmpty()) {
                digest.put("traceCaptured", false);
                digest.put("reason", "Trace file empty — no calls captured in target packages");
                return digest;
            }

            int totalCalls = 0;
            int maxDepth = 0;
            Map<String, Map<String, Object>> reachedMethods = new LinkedHashMap<>();
            String lastCall = null;

            for (String line : lines) {
                try {
                    JsonNode event = mapper.readTree(line);
                    totalCalls++;
                    int depth = event.path("depth").asInt(0);
                    if (depth > maxDepth) maxDepth = depth;

                    String cls = event.path("class").asText("");
                    String method = event.path("method").asText("");
                    String fullMethod = cls + "." + method;
                    lastCall = fullMethod;

                    if (target.methodsOfInterest.contains(fullMethod)) {
                        if (!reachedMethods.containsKey(fullMethod)) {
                            Map<String, Object> methodData = new LinkedHashMap<>();
                            methodData.put("method", fullMethod);
                            
                            JsonNode args = event.path("args");
                            if (args.isArray()) {
                                List<String> argList = new ArrayList<>();
                                for (JsonNode a : args) {
                                    String argStr = a.asText("");
                                    argList.add(ArtifactGenUtils.truncate(argStr, GeneratorConstants.TRACE_ARG_CAP));
                                }
                                methodData.put("argsAtEntry", argList);
                            }
                            
                            String retVal = event.path("ret").asText(null);
                            String throwVal = event.path("throw").asText(null);
                            if (throwVal != null) {
                                methodData.put("outcome", "threw " + ArtifactGenUtils.truncate(throwVal, 200));
                            } else if (retVal != null) {
                                methodData.put("outcome", "returned " + ArtifactGenUtils.truncate(retVal, 200));
                            }
                            
                            reachedMethods.put(fullMethod, methodData);
                        }
                    }
                } catch (Exception ignored) {}
            }

            digest.put("traceCaptured", true);
            digest.put("totalCalls", totalCalls);
            digest.put("maxDepth", maxDepth);

            Map<String, Object> methodsOfInterestDigest = new LinkedHashMap<>();
            List<Map<String, Object>> reached = new ArrayList<>(reachedMethods.values());
            methodsOfInterestDigest.put("reached", reached);
            
            List<String> notReached = new ArrayList<>();
            for (String m : target.methodsOfInterest) {
                if (!reachedMethods.containsKey(m)) {
                    notReached.add(m);
                }
            }
            methodsOfInterestDigest.put("notReached", notReached);
            digest.put("methodsOfInterest", methodsOfInterestDigest);

            if (lastCall != null) {
                digest.put("lastCallBeforeEnd", lastCall);
            }

            String note = buildNote(reached.size(), target.methodsOfInterest.size(), maxDepth);
            digest.put("note", note);

        } catch (Exception e) {
            log.warn("Failed to build trace digest: {}", e.getMessage());
            digest.put("traceCaptured", false);
            digest.put("reason", "Trace digest build failed: " + e.getMessage());
        }

        return digest;
    }

    private static String buildNote(int reachedCount, int totalOfInterest, int maxDepth) {
        if (reachedCount == 0) {
            return "None of the " + totalOfInterest + " methods of interest were reached. Max call depth=" + maxDepth + ".";
        }
        if (reachedCount == totalOfInterest) {
            return "All " + totalOfInterest + " methods of interest were reached.";
        }
        return reachedCount + " of " + totalOfInterest + " methods of interest were reached.";
    }
}
```

- [ ] **Step 3: Run test, verify PASS**

```bash
cd backend && mvn test -pl jvuln-stages -Dtest=TraceDigestBuilderTest -q
```
Expected: `BUILD SUCCESS`, 3 tests pass.

- [ ] **Step 4: Commit**

```bash
cd backend && git add jvuln-stages/src/main/java/com/jvuln/generator/TraceDigestBuilder.java jvuln-stages/src/test/java/com/jvuln/generator/TraceDigestBuilderTest.java
git commit -m "feat(generator): implement TraceDigestBuilder for runtime trace feedback"
```

---

## Task 6: `ValidationResult` + `ValidationEngine` + `AgentContext` wiring

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/AgentContext.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/ValidationResult.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/ValidationEngine.java`
- Create: `backend/jvuln-stages/src/test/java/com/jvuln/generator/ValidationEngineTracerTest.java`

**Interfaces:**
- Consumes: `TraceTarget`, `TraceDigestBuilder.buildDigest(Path, TraceTarget)`
- Produces: `ValidationResult.runtimeTrace` field; extended serialization
- Produces: `ValidationEngine` agent attachment in `doStartApp`, digest building in `validatePoc`

- [ ] **Step 1: Add `traceTarget` to `AgentContext`**

In `backend/jvuln-stages/src/main/java/com/jvuln/generator/AgentContext.java` after line 31 (after `JavaProfile javaProfile;`):
```java
    TraceTarget traceTarget;
```

- [ ] **Step 2: Add `runtimeTrace` to `ValidationResult`**

In `backend/jvuln-stages/src/main/java/com/jvuln/generator/ValidationResult.java`:

After line 20 (after `final Map<String, Object> artifacts`):
```java
    Map<String, Object> runtimeTrace;
```

In `mergeFrom` method after line 37 (after `this.artifacts.putAll(other.artifacts);`):
```java
            if (this.runtimeTrace == null && other.runtimeTrace != null) {
                this.runtimeTrace = other.runtimeTrace;
            }
```

In `toMap()` method after line 55 (after `out.put("artifacts", artifacts);`):
```java
        if (runtimeTrace != null) {
            out.put("runtimeTrace", runtimeTrace);
        }
```

In `fromJson` method after line 75 (before `return result;`):
```java
            JsonNode traceNode = node.path("runtimeTrace");
            if (traceNode.isObject()) {
                result.runtimeTrace = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> traceFields = traceNode.fields();
                while (traceFields.hasNext()) {
                    Map.Entry<String, JsonNode> field = traceFields.next();
                    result.runtimeTrace.put(field.getKey(), field.getValue());
                }
            }
```

- [ ] **Step 3: Wire agent attachment in `ValidationEngine.doStartApp`**

In `backend/jvuln-stages/src/main/java/com/jvuln/generator/ValidationEngine.java`:

Add field after line 34:
```java
    private final MavenSourcePackageScanner packageScanner;

    ValidationEngine(AgentToolExecutor toolExecutor, MavenSourcePackageScanner packageScanner) {
        this.toolExecutor = toolExecutor;
        this.packageScanner = packageScanner;
    }
```

In `doStartApp` method, before line 95 (`ProcessBuilder pb = new ProcessBuilder("bash", "run.sh");`), add:
```java
        attachTracerIfConfigured(ctx, pb);
```

Add new method at end of class (before closing brace):
```java
    private void attachTracerIfConfigured(AgentContext ctx, ProcessBuilder pb) {
        if (ctx.traceTarget == null || !ctx.traceTarget.isValid()) {
            return;
        }
        
        Path tracerJar = resolveTracerJar();
        if (tracerJar == null || !Files.exists(tracerJar)) {
            log.warn("Tracer JAR not found, skipping agent attachment");
            return;
        }
        
        String includes = String.join(";", ctx.traceTarget.packages);
        String traceOut = ctx.cvePath.resolve("poc/trace.jsonl").toAbsolutePath().toString();
        String agentArgs = "includes=" + includes + ",out=" + traceOut;
        String javaToolOptions = "-javaagent:" + tracerJar.toAbsolutePath() + "=" + agentArgs;
        
        pb.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions);
        log.info("Attached javaagent for packages: {}", includes);
    }

    private Path resolveTracerJar() {
        Path projectRoot = ctx.cvePath.getParent().getParent();
        Path tracerTarget = projectRoot.resolve("backend/jvuln-tracer/target");
        if (!Files.exists(tracerTarget)) return null;
        
        try {
            return Files.list(tracerTarget)
                    .filter(p -> p.getFileName().toString().startsWith("jvuln-tracer-")
                            && p.getFileName().toString().endsWith(".jar")
                            && !p.getFileName().toString().contains("sources")
                            && !p.getFileName().toString().contains("javadoc"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
```

Fix context reference: change `ctx.cvePath` to `AgentContext` parameter in `resolveTracerJar`:
```java
    private Path resolveTracerJar(AgentContext ctx) {
        Path backendRoot = ctx.cvePath.getParent().getParent().resolve("backend");
        Path tracerTarget = backendRoot.resolve("jvuln-tracer/target");
        if (!Files.exists(tracerTarget)) return null;
        
        try {
            return Files.list(tracerTarget)
                    .filter(p -> p.getFileName().toString().startsWith("jvuln-tracer-")
                            && p.getFileName().toString().endsWith(".jar")
                            && !p.getFileName().toString().contains("sources")
                            && !p.getFileName().toString().contains("javadoc"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
```

Update `attachTracerIfConfigured` to pass ctx:
```java
        Path tracerJar = resolveTracerJar(ctx);
```

- [ ] **Step 4: Wire digest building in `ValidationEngine.validatePoc`**

In `validatePoc` method, after line 226 (after `result.pocSteps.addAll(PocStep.parse(pr.output));`), add:
```java
        buildTraceDigest(ctx, result);
```

Add new method at end of class:
```java
    private void buildTraceDigest(AgentContext ctx, ValidationResult result) {
        if (ctx.traceTarget == null || !ctx.traceTarget.isValid()) {
            return;
        }
        
        Path traceFile = ctx.cvePath.resolve("poc/trace.jsonl");
        Map<String, Object> digest = TraceDigestBuilder.buildDigest(traceFile, ctx.traceTarget);
        result.runtimeTrace = digest;
    }
```

- [ ] **Step 5: Write graceful-fallback regression test**

`backend/jvuln-stages/src/test/java/com/jvuln/generator/ValidationEngineTracerTest.java`:
```java
package com.jvuln.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ValidationEngineTracerTest {

    @Test
    void gracefullySkipsWhenTraceTargetMissing(@TempDir Path tmp) throws Exception {
        AgentContext ctx = new AgentContext(tmp, null);
        ctx.traceTarget = null;
        
        ValidationEngine engine = new ValidationEngine(null, null);
        ValidationResult result = new ValidationResult("poc");
        
        // Should not throw, should not populate runtimeTrace
        assertDoesNotThrow(() -> {
            java.lang.reflect.Method m = ValidationEngine.class.getDeclaredMethod("buildTraceDigest", AgentContext.class, ValidationResult.class);
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
        
        ValidationEngine engine = new ValidationEngine(null, null);
        ProcessBuilder pb = new ProcessBuilder("echo", "test");
        
        assertDoesNotThrow(() -> {
            java.lang.reflect.Method m = ValidationEngine.class.getDeclaredMethod("attachTracerIfConfigured", AgentContext.class, ProcessBuilder.class);
            m.setAccessible(true);
            m.invoke(engine, ctx, pb);
        });
        
        assertNull(pb.environment().get("JAVA_TOOL_OPTIONS"));
    }
}
```

- [ ] **Step 6: Run test, verify PASS**

```bash
cd backend && mvn test -pl jvuln-stages -Dtest=ValidationEngineTracerTest -q
```
Expected: `BUILD SUCCESS`, 2 tests pass.

- [ ] **Step 7: Commit**

```bash
cd backend && git add jvuln-stages/src/main/java/com/jvuln/generator/
git commit -m "feat(generator): wire runtime trace into ValidationEngine and ValidationResult"
```

---

## Task 7: `AgentPhaseEngine` POC_FIX directive + `ArtifactGenStage` wiring

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/AgentPhaseEngine.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/ArtifactGenStage.java`

**Interfaces:**
- Consumes: `ValidationResult.runtimeTrace`, `StageDataExtractor.extractTraceTarget`
- Produces: POC_FIX directive includes trace digest in `actual`

- [ ] **Step 1: Append trace digest to POC_FIX directive in `AgentPhaseEngine`**

In `backend/jvuln-stages/src/main/java/com/jvuln/generator/AgentPhaseEngine.java`, in `buildPhaseDirective` method, change the `POC_FIX` case (lines 86-91):

From:
```java
            case POC_FIX:
                gap = result == null ? "poc_unverified" : derivePocGap(result);
                expected = derivePocExpected(ctx);
                actual = result == null ? "No PoC validation evidence yet." : result.pocMessage;
                fixHint = derivePocFixHint(ctx, result);
                break;
```

To:
```java
            case POC_FIX:
                gap = result == null ? "poc_unverified" : derivePocGap(result);
                expected = derivePocExpected(ctx);
                actual = buildPocActual(result);
                fixHint = derivePocFixHint(ctx, result);
                break;
```

Add new method at end of class (before closing brace):
```java
    private String buildPocActual(ValidationResult result) {
        if (result == null) {
            return "No PoC validation evidence yet.";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(result.pocMessage);
        
        if (result.runtimeTrace != null && !result.runtimeTrace.isEmpty()) {
            sb.append("\n\nRUNTIME TRACE:\n");
            sb.append(llmHelper.renderJson(result.runtimeTrace));
        }
        
        return sb.toString();
    }
```

- [ ] **Step 2: Extract and populate `traceTarget` in `ArtifactGenStage`**

In `backend/jvuln-stages/src/main/java/com/jvuln/generator/ArtifactGenStage.java`, after line 131 (after the `verificationPlan` is built), add:
```java
        Object analysisData = ctx.getCompletedStages().get(3).getData();
        TraceTarget traceTarget = dataExtractor.extractTraceTarget(rawIntelligence, analysisData);
        if (traceTarget != null && traceTarget.isValid()) {
            log.info("Trace target: {}:{}:{} ({} packages, {} methods of interest)",
                    traceTarget.groupId, traceTarget.artifactId, traceTarget.version,
                    traceTarget.packages.size(), traceTarget.methodsOfInterest.size());
        } else {
            log.warn("Could not resolve trace target from intelligence/analysis data");
        }
```

After line 137 (after `agentCtx.javaProfile = javaProfile;`), add:
```java
        agentCtx.traceTarget = traceTarget;
```

- [ ] **Step 3: Run full backend compile, verify no errors**

```bash
cd backend && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
cd backend && git add jvuln-stages/src/main/java/com/jvuln/generator/AgentPhaseEngine.java jvuln-stages/src/main/java/com/jvuln/generator/ArtifactGenStage.java
git commit -m "feat(generator): integrate runtime trace digest into POC_FIX directive"
```

- [ ] **Step 5: Build jvuln-tracer JAR**

```bash
cd backend && mvn clean package -pl jvuln-tracer -DskipTests -q
```
Expected: `BUILD SUCCESS`, `backend/jvuln-tracer/target/jvuln-tracer-1.0.0-SNAPSHOT.jar` exists.

- [ ] **Step 6: Run full backend test suite**

```bash
cd backend && mvn test -q
```
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 7: Update design spec with "Implemented" status**

In `docs/superpowers/specs/2026-07-23-stage4-javaagent-trace-design.md` change line 4:
```markdown
**Status**: Design — pending implementation plan
```
to:
```markdown
**Status**: Implemented
```

- [ ] **Step 8: Final commit**

```bash
cd backend && git add docs/superpowers/specs/2026-07-23-stage4-javaagent-trace-design.md
git commit -m "docs: mark javaagent runtime trace design as implemented"
```

---

## Self-Review Checklist

After completing all tasks, verify:

- [ ] **Spec coverage**: Every section of `docs/superpowers/specs/2026-07-23-stage4-javaagent-trace-design.md` maps to implemented code
- [ ] **No placeholders**: No "TBD", "TODO", "implement later", or unfinished method stubs in any file
- [ ] **Type consistency**: `TraceTarget`, `TracerConfig`, `TracerEventWriter`, `TraceDigestBuilder` signatures match interfaces declared in tasks
- [ ] **File size constraints**: No file exceeds 80 KB; no method exceeds 256 lines
- [ ] **Tests pass**: `mvn test` succeeds for both `jvuln-tracer` and `jvuln-stages`
- [ ] **JAR manifest**: `jvuln-tracer` JAR has `Premain-Class: com.jvuln.tracer.TracerAgent`
- [ ] **Safe degradation**: Missing tracer JAR, missing trace file, empty packages all handled without breaking validation
- [ ] **Env-var name**: `JAVA_TOOL_OPTIONS` (not `JAVA_TOL_OPTIONS`) in `ValidationEngine`
- [ ] **Manual integration test**: Run CVE-2021-42392 (H2) end-to-end; confirm `poc/trace.jsonl` exists and digest appears in PoC feedback

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-24-javaagent-runtime-trace.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
