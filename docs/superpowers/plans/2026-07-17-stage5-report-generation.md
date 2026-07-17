# Stage 5: Report Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move report generation out of the Stage 4 artifact agent into a dedicated Stage 5 (`ReportGenerationStage`) that produces `report/report.md` via a single LLM call, using stages 1–4 outputs plus the real generated demo/PoC files as input.

**Architecture:** Add a new `@Component` `Stage` bean numbered 5 in `com.jvuln.generator.report`. It reuses the existing dead `DataExtractor` to trim stages 1–4 data, reads the generated demo/PoC files with a size cap, renders `current/report-user.md` as the user prompt, injects `stages/report.md` as the system layer via a new `LlmPromptStage.REPORT_GENERATION` enum value, and does one `LlmRequest.generation(...)` call with a small retry loop. It writes `report/report.md` and `stages/5.json` for checkpoint/skip. Stage 4's REPORT phase, `reportStrategy`, and `report_status` tracking are removed so the artifact agent only owns demo + PoC.

**Tech Stack:** Java 8 (backend, Spring Boot, Maven multi-module: jvuln-app, jvuln-stages, jvuln-utils), Jackson, Vue 3 + Element Plus + TypeScript (frontend), JUnit 5.

## Global Constraints

- Any single source file must stay under 80 KB.
- Any single method must stay under 256 lines.
- Reuse shared types/helpers from `jvuln-utils` (`StageResult`, `PipelineContext`, `LlmRequest`, `LlmResponse`, `LlmClient`, `WorkspaceManager`, `LlmPromptStage`, `PromptRegistry`) rather than duplicating them.
- Java source must be Java 8 compatible (no `var`, no `List.of`, no records, no text blocks) — match the existing style in `com.jvuln.generator`.
- The platform is security-education only; keep the existing "FOR AUTHORIZED SECURITY EDUCATION ONLY" markers intact. Do not weaken `WorkspaceManager`'s path-traversal defense (CVE pattern + normalize + startsWith).
- Backend build/verify command (run from repo root): `mvn -q -pl backend/jvuln-stages,backend/jvuln-app -am compile` and for tests `mvn -q -pl backend/jvuln-stages -am test`.
- Frontend build/verify command (run from `frontend/`): `npm run build`.

---

### Task 1: Add `REPORT_GENERATION` to `LlmPromptStage`

**Files:**
- Modify: `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmPromptStage.java`
- Test: `backend/jvuln-stages/src/test/java/com/jvuln/prompts/StagePromptResourcesTest.java` (existing — no edit; it iterates all enum values and must stay green)

**Interfaces:**
- Consumes: nothing.
- Produces: `LlmPromptStage.REPORT_GENERATION` whose `getResourcePath()` returns `"prompts/stages/report.md"`.

- [ ] **Step 1: Run the existing prompt-resources test to confirm it currently passes**

Run: `mvn -q -pl backend/jvuln-stages -am test -Dtest=StagePromptResourcesTest`
Expected: PASS (4 enum values, each resolving `stages/<name>.md`).

- [ ] **Step 2: Add the enum value**

Edit `LlmPromptStage.java`. Change the javadoc "four" to "five" and add the new constant after `ARTIFACT_GENERATION`:

```java
package com.jvuln.llm;

/** The five pipeline stages that own stage-level LLM prompts. */
public enum LlmPromptStage {
    INTELLIGENCE("intelligence"),
    PATCH_ANALYSIS("patch-analysis"),
    REASONING("reasoning"),
    ARTIFACT_GENERATION("artifact-generation"),
    REPORT_GENERATION("report");

    private final String resourceName;

    LlmPromptStage(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getResourcePath() {
        return "prompts/stages/" + resourceName + ".md";
    }
}
```

(Keep whatever existing method bodies are present; only the constant list + javadoc line change. If the file already has the exact constructor/method shown, this is the full file.)

- [ ] **Step 3: Run the prompt-resources test to verify it still passes with the new enum**

Run: `mvn -q -pl backend/jvuln-stages -am test -Dtest=StagePromptResourcesTest`
Expected: PASS — the test now iterates 5 enum values and `prompts/stages/report.md` already exists and is non-empty.

- [ ] **Step 4: Commit**

```bash
git add backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmPromptStage.java
git commit -m "feat: add REPORT_GENERATION prompt stage enum"
```

---

### Task 2: Teach `WorkspaceManager` about stage 5

**Files:**
- Modify: `backend/jvuln-utils/src/main/java/com/jvuln/store/WorkspaceManager.java` (the `getStageFile` switch, around lines 99–109)
- Test: `backend/jvuln-utils/src/test/java/com/jvuln/store/WorkspaceManagerStage5Test.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `getStageFile(cveId, 5)` resolves to `<cve>/stages/5_report.json`; `writeStageData(cveId, 5, obj)` / `readStageData(cveId, 5, type)` / `isStageComplete(cveId, 5)` all work.

- [ ] **Step 1: Write the failing test**

Create `backend/jvuln-utils/src/test/java/com/jvuln/store/WorkspaceManagerStage5Test.java`:

```java
package com.jvuln.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceManagerStage5Test {

    private static final String CVE = "CVE-2099-0001";

    @Test
    void writesAndReadsStage5Data(@TempDir Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WorkspaceManager workspace = new WorkspaceManager(tempDir.toString(), mapper);
        workspace.initCveWorkspace(CVE);

        assertFalse(workspace.isStageComplete(CVE, 5));

        Map<String, Object> summary = new HashMap<>();
        summary.put("reportPath", "report/report.md");
        summary.put("charCount", 123);
        workspace.writeStageData(CVE, 5, summary);

        assertTrue(workspace.isStageComplete(CVE, 5));

        @SuppressWarnings("unchecked")
        Map<String, Object> read = workspace.readStageData(CVE, 5, Map.class);
        assertEquals("report/report.md", read.get("reportPath"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl backend/jvuln-utils -am test -Dtest=WorkspaceManagerStage5Test`
Expected: FAIL — `writeStageData(CVE, 5, ...)` throws `IllegalArgumentException: Invalid stage: 5` from `getStageFile`'s `default` branch.

- [ ] **Step 3: Add the `case 5` branch**

In `WorkspaceManager.java`, locate the `getStageFile` switch (the block mapping stage numbers 1–4 to filenames, `default` throws `IllegalArgumentException("Invalid stage: " + stageNum)`). Add a `case 5` before `default`, matching the existing filename convention (`<n>_<name>.json`):

```java
            case 4:
                filename = "4_artifacts.json";
                break;
            case 5:
                filename = "5_report.json";
                break;
            default:
                throw new IllegalArgumentException("Invalid stage: " + stageNum);
```

(Copy the exact existing filenames for cases 1–4 as they are; only the `case 5` line is added. If case 4's filename differs from `4_artifacts.json`, keep the real one and only insert `case 5: filename = "5_report.json"; break;`.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl backend/jvuln-utils -am test -Dtest=WorkspaceManagerStage5Test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/jvuln-utils/src/main/java/com/jvuln/store/WorkspaceManager.java backend/jvuln-utils/src/test/java/com/jvuln/store/WorkspaceManagerStage5Test.java
git commit -m "feat: support stage 5 data files in WorkspaceManager"
```

---

### Task 3: Audit-fix `stages/report.md`

**Files:**
- Modify: `backend/jvuln-stages/src/main/resources/prompts/stages/report.md`

**Interfaces:**
- Consumes: nothing.
- Produces: an output-only, un-templated Stage-5 system prompt. No behavior contract other than "still non-empty" (verified by `StagePromptResourcesTest`).

This is a prompt-only change (no test-first cycle). Apply the three approved audit edits.

- [ ] **Step 1: Reframe the intro (lines 1–5) to remove "you built" and the `{{cve_id}}` template**

Replace the current lines 1–5:

```markdown
You are a security education expert writing vulnerability analysis reports for authorized CVE analysis labs.

**Current CVE: {{cve_id}}** — The report must target this specific CVE.

Your single deliverable is an educational Markdown report explaining the vulnerability. It MUST follow the fixed Chinese structure defined in "Report Format" below. Base every section on the real intelligence, Stage 3 facts, trigger chain, root cause, patch diff, and the demo/PoC that were actually built for this CVE.
```

with:

```markdown
You are a security education expert writing vulnerability analysis reports for authorized CVE analysis labs.

The user message below provides everything about one specific CVE: its id, intelligence, Stage 3 facts, trigger chain, root cause, patch diff, and the demo/PoC that were generated for it. Target that exact CVE — use the CVE id given in the user message in the title and the 情报 table.

Your single deliverable is an educational Markdown report explaining the vulnerability. It MUST follow the fixed Chinese structure defined in "Report Format" below. Base every section on the real intelligence, Stage 3 facts, trigger chain, root cause, patch diff, and the demo/PoC provided in the input below.
```

- [ ] **Step 2: De-template the "Report Format" heading and the sample title/table**

Line 7 currently reads ``## Report Format (`report/report.md`)``. Change it to:

```markdown
## Report Format
```

Line 9 currently reads: `The report MUST follow this fixed structure exactly. Write it **entirely in Chinese (中文)**, valid Markdown. Use the real CVE id in the title. Do not add, remove, rename, or reorder the top-level sections.` — keep it unchanged (it already says "Use the real CVE id").

In the fenced sample structure, replace the two `{{cve_id}}` occurrences with a placeholder that is obviously not a template variable:

- Line 12 `# {{cve_id}} 漏洞分析` → `# <CVE 编号> 漏洞分析`
- Line 20 `| CVE 编号 | {{cve_id}} |` → `| CVE 编号 | <CVE 编号> |`

- [ ] **Step 3: Reframe "you actually built / generated in this run" in the Rules section**

- Line 58: `...and the demo/PoC you actually built. Do not leave placeholder angle-bracket text in the final file.` → `...and the demo/PoC provided in the input. Do not leave placeholder angle-bracket text in the final report.`
- Line 60: `In 漏洞复现, reference the actual files and code you generated in this run, not hypothetical ones.` → `In 漏洞复现, reference the actual demo/PoC files and code provided in the input, not hypothetical ones.`

- [ ] **Step 4: Replace the file-writing Constraints section with an output contract**

Replace the final Constraints block (lines ~65–68):

```markdown
## Constraints

- Write the report to `report/report.md`.
- All report file paths must start with `report/`.
```

with:

```markdown
## Output

- Output ONLY the report Markdown. No preamble, no explanation, no commentary before or after.
- Do NOT wrap the whole report in a surrounding code fence. (Code blocks *inside* the report — e.g. ```diff, ```java — are expected and correct.)
- Start your output directly with the `#` title line.
```

- [ ] **Step 5: Verify the prompt still resolves non-empty**

Run: `mvn -q -pl backend/jvuln-stages -am test -Dtest=StagePromptResourcesTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/jvuln-stages/src/main/resources/prompts/stages/report.md
git commit -m "refactor: convert report.md into output-only Stage 5 system prompt"
```

---

### Task 4: Create the `current/report-user.md` user prompt

**Files:**
- Create: `backend/jvuln-stages/src/main/resources/prompts/current/report-user.md`

**Interfaces:**
- Consumes: nothing.
- Produces: a `PromptRegistry`-renderable template with `{{...}}` variables: `cve_id`, `intelligence`, `vulnerability_facts`, `trigger_chain`, `root_cause`, `patch_diff`, `artifact`, `generated_files`.

This is a resource-only change (no test-first cycle); it is exercised by the Stage 5 unit test in Task 7.

- [ ] **Step 1: Create the file**

Create `backend/jvuln-stages/src/main/resources/prompts/current/report-user.md`:

```markdown
为下面这个 CVE 生成漏洞分析报告。目标 CVE：**{{cve_id}}**。

严格按照系统提示中的固定中文结构（六个一级章节，顺序与标题不可改）输出，只输出报告 Markdown 本身。

下面是这个 CVE 的全部输入材料。

## 情报（Stage 1）

```json
{{intelligence}}
```

## 漏洞事实（Stage 2）

```json
{{vulnerability_facts}}
```

## 触发链（Stage 3）

```json
{{trigger_chain}}
```

## 根因分析（Stage 3）

```json
{{root_cause}}
```

## 补丁 diff

```diff
{{patch_diff}}
```

## 受影响组件（artifact）

```json
{{artifact}}
```

## 本次生成的 demo / PoC 文件

{{generated_files}}
```

- [ ] **Step 2: Verify the resource is on the classpath (build the module)**

Run: `mvn -q -pl backend/jvuln-stages -am compile`
Expected: PASS (resource files are copied; this just confirms nothing broke).

- [ ] **Step 3: Commit**

```bash
git add backend/jvuln-stages/src/main/resources/prompts/current/report-user.md
git commit -m "feat: add report-user prompt template for Stage 5"
```

---

### Task 5: Add `trimIntelligence` to `DataExtractor`

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/report/DataExtractor.java`
- Test: `backend/jvuln-stages/src/test/java/com/jvuln/generator/report/DataExtractorTest.java` (create)

**Interfaces:**
- Consumes: existing `DataExtractor` methods (`extractDiff`, `extractVulnerabilityFacts`, `extractTriggerChain`, `extractRootCause`, `extractArtifact`, private `copyField`).
- Produces: `public String trimIntelligence(Object data) throws Exception` — returns a JSON string containing only `cveId`, `cweId`, `description`, `cvss`, `fixedVersion`, `artifact`, `affectedVersions`.

- [ ] **Step 1: Write the failing test**

Create `backend/jvuln-stages/src/test/java/com/jvuln/generator/report/DataExtractorTest.java`:

```java
package com.jvuln.generator.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataExtractorTest {

    @Test
    void trimIntelligenceKeepsOnlyReportFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DataExtractor extractor = new DataExtractor(mapper);

        Map<String, Object> data = new HashMap<>();
        data.put("cveId", "CVE-2099-0001");
        data.put("cweId", "CWE-502");
        data.put("description", "demo");
        data.put("cvss", 9.8);
        data.put("fixedVersion", "1.2.3");
        data.put("artifact", "g:a");
        data.put("affectedVersions", "< 1.2.3");
        data.put("rawReferences", "should be dropped");

        String json = extractor.trimIntelligence(data);

        assertTrue(json.contains("CVE-2099-0001"));
        assertTrue(json.contains("CWE-502"));
        assertFalse(json.contains("rawReferences"));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = mapper.readValue(json, Map.class);
        assertEquals("g:a", parsed.get("artifact"));
        assertFalse(parsed.containsKey("rawReferences"));
    }

    @Test
    void trimIntelligenceHandlesNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DataExtractor extractor = new DataExtractor(mapper);
        String json = extractor.trimIntelligence(null);
        assertEquals("{}", json.replaceAll("\\s", ""));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl backend/jvuln-stages -am test -Dtest=DataExtractorTest`
Expected: FAIL with "cannot find symbol: method trimIntelligence".

- [ ] **Step 3: Add the method**

In `DataExtractor.java` add (near the other `extract*` methods; use the field name the class already uses for its `ObjectMapper` — check the constructor `public DataExtractor(ObjectMapper mapper)` and reuse that field, here assumed `mapper`):

```java
    public String trimIntelligence(Object data) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
        if (data == null) {
            return mapper.writeValueAsString(out);
        }
        com.fasterxml.jackson.databind.JsonNode root = mapper.valueToTree(data);
        copyField(root, out, "cveId");
        copyField(root, out, "cweId");
        copyField(root, out, "description");
        copyField(root, out, "cvss");
        copyField(root, out, "fixedVersion");
        copyField(root, out, "artifact");
        copyField(root, out, "affectedVersions");
        return mapper.writeValueAsString(out);
    }
```

(If `copyField(JsonNode, ObjectNode, String)` has a different signature in the file, match it. If the class already imports `JsonNode` / `ObjectNode`, drop the fully-qualified names.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl backend/jvuln-stages -am test -Dtest=DataExtractorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/generator/report/DataExtractor.java backend/jvuln-stages/src/test/java/com/jvuln/generator/report/DataExtractorTest.java
git commit -m "feat: add trimIntelligence to report DataExtractor"
```

---

### Task 6: Create `GeneratedFileReader`

**Files:**
- Create: `backend/jvuln-stages/src/main/java/com/jvuln/generator/report/GeneratedFileReader.java`
- Test: `backend/jvuln-stages/src/test/java/com/jvuln/generator/report/GeneratedFileReaderTest.java` (create)

**Interfaces:**
- Consumes: nothing (takes a `Path` cvePath at call time).
- Produces: `@Component class GeneratedFileReader` with `String readGeneratedFiles(java.nio.file.Path cvePath)` — returns a labeled, size-capped concatenation of the demo/PoC files. Per-file cap 8 KB, total cap 40 KB, with truncation markers. Missing files skipped.

- [ ] **Step 1: Write the failing test**

Create `backend/jvuln-stages/src/test/java/com/jvuln/generator/report/GeneratedFileReaderTest.java`:

```java
package com.jvuln.generator.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedFileReaderTest {

    @Test
    void readsPresentFilesAndSkipsMissing(@TempDir Path cve) throws Exception {
        Files.createDirectories(cve.resolve("vuln-demo"));
        Files.createDirectories(cve.resolve("poc"));
        Files.write(cve.resolve("vuln-demo/pom.xml"),
                "<project>demo-pom</project>".getBytes(StandardCharsets.UTF_8));
        Files.write(cve.resolve("poc/exploit.sh"),
                "echo pwned".getBytes(StandardCharsets.UTF_8));

        GeneratedFileReader reader = new GeneratedFileReader();
        String blob = reader.readGeneratedFiles(cve);

        assertTrue(blob.contains("vuln-demo/pom.xml"));
        assertTrue(blob.contains("demo-pom"));
        assertTrue(blob.contains("poc/exploit.sh"));
        assertTrue(blob.contains("echo pwned"));
        // application.properties was never created -> not listed
        assertFalse(blob.contains("application.properties"));
    }

    @Test
    void perFileCapTruncatesLargeFile(@TempDir Path cve) throws Exception {
        Files.createDirectories(cve.resolve("poc"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            big.append('x');
        }
        Files.write(cve.resolve("poc/exploit.sh"), big.toString().getBytes(StandardCharsets.UTF_8));

        GeneratedFileReader reader = new GeneratedFileReader();
        String blob = reader.readGeneratedFiles(cve);

        assertTrue(blob.contains("truncated"));
        assertTrue(blob.length() < 20000);
    }

    @Test
    void emptyWorkspaceReturnsNonNull(@TempDir Path cve) throws Exception {
        GeneratedFileReader reader = new GeneratedFileReader();
        String blob = reader.readGeneratedFiles(cve);
        assertTrue(blob != null);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl backend/jvuln-stages -am test -Dtest=GeneratedFileReaderTest`
Expected: FAIL — `GeneratedFileReader` does not exist (compilation failure).

- [ ] **Step 3: Write the implementation**

Create `backend/jvuln-stages/src/main/java/com/jvuln/generator/report/GeneratedFileReader.java`:

```java
package com.jvuln.generator.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reads the actually-generated vuln-demo/PoC files into a labeled, size-capped blob for the Stage 5 prompt. */
@Component
public class GeneratedFileReader {

    private static final Logger log = LoggerFactory.getLogger(GeneratedFileReader.class);

    private static final int PER_FILE_CAP = 8 * 1024;
    private static final int TOTAL_CAP = 40 * 1024;
    private static final int MAX_JAVA_SOURCES = 12;

    // Skeleton files that carry little report value; included only after CVE-specific sources.
    private static final List<String> LOW_VALUE_SUFFIXES = java.util.Arrays.asList(
            "Application.java", "LabInfoController.java");

    public String readGeneratedFiles(Path cvePath) {
        StringBuilder blob = new StringBuilder();
        int[] budget = new int[] { TOTAL_CAP };

        appendFile(blob, cvePath, "vuln-demo/pom.xml", budget);
        appendFile(blob, cvePath, "vuln-demo/src/main/resources/application.properties", budget);

        for (Path java : collectJavaSources(cvePath)) {
            if (budget[0] <= 0) {
                break;
            }
            String rel = cvePath.relativize(java).toString().replace('\\', '/');
            appendFile(blob, cvePath, rel, budget);
        }

        appendFile(blob, cvePath, "poc/exploit.sh", budget);

        if (blob.length() == 0) {
            return "(本次运行未找到可读取的 demo/PoC 文件。)";
        }
        return blob.toString();
    }

    private List<Path> collectJavaSources(Path cvePath) {
        Path srcRoot = cvePath.resolve("vuln-demo/src/main/java");
        List<Path> sources = new ArrayList<Path>();
        if (!Files.isDirectory(srcRoot)) {
            return sources;
        }
        try {
            List<Path> all = new ArrayList<Path>();
            Files.walk(srcRoot)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(all::add);
            // CVE-specific sources first, low-value skeletons last.
            all.sort(Comparator.comparingInt(this::lowValueRank));
            for (Path p : all) {
                if (sources.size() >= MAX_JAVA_SOURCES) {
                    break;
                }
                sources.add(p);
            }
        } catch (IOException e) {
            log.warn("Failed to walk demo sources under {}: {}", srcRoot, e.getMessage());
        }
        return sources;
    }

    private int lowValueRank(Path p) {
        String name = p.getFileName().toString();
        for (String suffix : LOW_VALUE_SUFFIXES) {
            if (name.equals(suffix)) {
                return 1;
            }
        }
        return 0;
    }

    private void appendFile(StringBuilder blob, Path cvePath, String relPath, int[] budget) {
        if (budget[0] <= 0) {
            return;
        }
        Path file = cvePath.resolve(relPath);
        if (!Files.isRegularFile(file)) {
            return;
        }
        String content;
        try {
            byte[] bytes = Files.readAllBytes(file);
            content = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read generated file {}: {}", relPath, e.getMessage());
            return;
        }

        boolean truncatedFile = false;
        if (content.length() > PER_FILE_CAP) {
            content = content.substring(0, PER_FILE_CAP);
            truncatedFile = true;
        }
        if (content.length() > budget[0]) {
            content = content.substring(0, Math.max(0, budget[0]));
            truncatedFile = true;
        }

        blob.append("### ").append(relPath).append("\n\n");
        blob.append("```\n").append(content).append("\n```\n");
        if (truncatedFile) {
            blob.append("_(truncated)_\n");
        }
        blob.append("\n");
        budget[0] -= content.length();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl backend/jvuln-stages -am test -Dtest=GeneratedFileReaderTest`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/generator/report/GeneratedFileReader.java backend/jvuln-stages/src/test/java/com/jvuln/generator/report/GeneratedFileReaderTest.java
git commit -m "feat: add GeneratedFileReader for Stage 5 demo/PoC blob"
```

---

### Task 7: Create `ReportGenerationStage`

**Files:**
- Create: `backend/jvuln-stages/src/main/java/com/jvuln/generator/report/ReportGenerationStage.java`
- Test: `backend/jvuln-stages/src/test/java/com/jvuln/generator/report/ReportGenerationStageTest.java` (create)

**Interfaces:**
- Consumes: `LlmPromptStage.REPORT_GENERATION` (Task 1), `WorkspaceManager` stage-5 support (Task 2), `stages/report.md` (Task 3), `current/report-user.md` (Task 4), `DataExtractor.trimIntelligence` (Task 5), `GeneratedFileReader.readGeneratedFiles` (Task 6). Reuses jvuln-utils: `Stage`, `PipelineContext`, `StageResult`, `LlmRequest.generation(LlmPromptStage, String, String)`, `LlmClient.chat`, `LlmResponse.getContent`, `PromptRegistry.getPrompt(String)` + `render(String, Map)`.
- Produces: `@Component class ReportGenerationStage implements Stage` with `number()==5`, `name()=="Report Generation"`. Writes `report/report.md` and `writeStageData(cveId, 5, summary)` where summary is `{reportPath, charCount}`.

> **Note on `PromptRegistry` usage:** confirm the exact injection/method names by reading `ReasoningStage.java` (the template). It uses `promptRegistry.getPrompt("current/reasoning-user")` (or similar) + a `render(template, vars)` call. Mirror those exact names here; the code below assumes `PromptRegistry promptRegistry` with `String getPrompt(String name)` and `String render(String template, Map<String,String> vars)`. If `render` lives elsewhere (e.g. a `PromptRenderer`), inject and use that instead — do not invent a new API.

- [ ] **Step 1: Write the failing test**

Create `backend/jvuln-stages/src/test/java/com/jvuln/generator/report/ReportGenerationStageTest.java`. Model the LLM mock and workspace setup on `IntelligenceStageTest`:

```java
package com.jvuln.generator.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.pipeline.PipelineContext;
import com.jvuln.pipeline.stage.StageResult;
import com.jvuln.prompt.PromptRegistry;
import com.jvuln.store.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportGenerationStageTest {

    private static final String CVE = "CVE-2099-0001";

    private PromptRegistry promptRegistry() {
        // Use the real registry so the actual report-user.md template is rendered.
        return new PromptRegistry(new DefaultResourceLoader());
    }

    private ObjectMapper mapper() {
        return new ObjectMapper();
    }

    private WorkspaceManager seededWorkspace(Path tempDir) throws Exception {
        WorkspaceManager workspace = new WorkspaceManager(tempDir.toString(), mapper());
        workspace.initCveWorkspace(CVE);
        Map<String, Object> intel = new HashMap<String, Object>();
        intel.put("cveId", CVE);
        intel.put("description", "demo vuln");
        workspace.writeStageData(CVE, 1, intel);
        // stage 2/3/4 optional; DataExtractor tolerates missing data.
        Path cve = workspace.getCvePath(CVE);
        Files.createDirectories(cve.resolve("poc"));
        Files.write(cve.resolve("poc/exploit.sh"), "echo pwned".getBytes(StandardCharsets.UTF_8));
        return workspace;
    }

    private LlmClient fixedLlm(final String content) {
        return new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                LlmResponse r = new LlmResponse();
                r.setContent(content);
                return r;
            }

            @Override
            public Flux<String> chatStream(LlmRequest request) {
                return Flux.error(new UnsupportedOperationException("no stream"));
            }
        };
    }

    @Test
    void writesReportAndSucceeds(@TempDir Path tempDir) throws Exception {
        WorkspaceManager workspace = seededWorkspace(tempDir);
        LlmClient llm = fixedLlm("# " + CVE + " 漏洞分析\n\n## 1. 漏洞介绍\n内容");
        PipelineContext ctx = new PipelineContext(CVE, workspace.getCvePath(CVE), llm, workspace);

        ReportGenerationStage stage = new ReportGenerationStage(
                mapper(), promptRegistry(), new DataExtractor(mapper()), new GeneratedFileReader());

        StageResult result = stage.execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals(5, result.getStageNum());
        Path report = workspace.getCvePath(CVE).resolve("report/report.md");
        assertTrue(Files.exists(report));
        String md = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(md.contains("漏洞介绍"));
        assertTrue(workspace.isStageComplete(CVE, 5));
    }

    @Test
    void emptyLlmOutputFailsStage(@TempDir Path tempDir) throws Exception {
        WorkspaceManager workspace = seededWorkspace(tempDir);
        LlmClient llm = fixedLlm("   ");
        PipelineContext ctx = new PipelineContext(CVE, workspace.getCvePath(CVE), llm, workspace);

        ReportGenerationStage stage = new ReportGenerationStage(
                mapper(), promptRegistry(), new DataExtractor(mapper()), new GeneratedFileReader());

        StageResult result = stage.execute(ctx);

        assertFalse(result.isSuccess());
        assertEquals(5, result.getStageNum());
    }
}
```

> Before running, open `IntelligenceStageTest.java` and copy its exact `LlmResponse` construction (some codebases use a builder or constructor rather than `setContent`), its `PromptRegistry` constructor, and the `PipelineContext` constructor argument order. Adjust the test above to match reality — do not guess the setters.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl backend/jvuln-stages -am test -Dtest=ReportGenerationStageTest`
Expected: FAIL — `ReportGenerationStage` does not exist (compilation failure).

- [ ] **Step 3: Write the implementation**

Create `backend/jvuln-stages/src/main/java/com/jvuln/generator/report/ReportGenerationStage.java`. Mirror `ReasoningStage`'s retry/backoff and fence-stripping. Confirm the `Stage` interface package (`com.jvuln.pipeline.stage.Stage`) and `LlmRequest.generation` signature before writing:

```java
package com.jvuln.generator.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.pipeline.PipelineContext;
import com.jvuln.pipeline.stage.Stage;
import com.jvuln.pipeline.stage.StageResult;
import com.jvuln.prompt.PromptRegistry;
import com.jvuln.store.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class ReportGenerationStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerationStage.class);
    private static final int MAX_RETRIES = 2;
    private static final String TASK_PROMPT =
            "为下面提供的 CVE 生成漏洞分析报告，严格遵循系统提示中的固定中文结构，只输出报告 Markdown 本身。";

    private final ObjectMapper mapper;
    private final PromptRegistry promptRegistry;
    private final DataExtractor dataExtractor;
    private final GeneratedFileReader generatedFileReader;

    public ReportGenerationStage(ObjectMapper mapper,
                                 PromptRegistry promptRegistry,
                                 DataExtractor dataExtractor,
                                 GeneratedFileReader generatedFileReader) {
        this.mapper = mapper;
        this.promptRegistry = promptRegistry;
        this.dataExtractor = dataExtractor;
        this.generatedFileReader = generatedFileReader;
    }

    @Override
    public int number() {
        return 5;
    }

    @Override
    public String name() {
        return "Report Generation";
    }

    @Override
    public StageResult execute(PipelineContext ctx) {
        try {
            WorkspaceManager workspace = ctx.getWorkspaceManager();
            String cveId = ctx.getCveId();

            Object stage1 = workspace.readStageData(cveId, 1, Object.class);
            Object stage2 = safeRead(workspace, cveId, 2);
            Object stage3 = safeRead(workspace, cveId, 3);
            Object stage4 = safeRead(workspace, cveId, 4);

            Map<String, String> vars = new HashMap<String, String>();
            vars.put("cve_id", cveId);
            vars.put("intelligence", dataExtractor.trimIntelligence(stage1));
            vars.put("vulnerability_facts", dataExtractor.extractVulnerabilityFacts(stage2));
            vars.put("trigger_chain", dataExtractor.extractTriggerChain(stage3));
            vars.put("root_cause", dataExtractor.extractRootCause(stage3));
            vars.put("patch_diff", dataExtractor.extractDiff(ctx, stage4, 40 * 1024));
            vars.put("artifact", dataExtractor.extractArtifact(stage1));
            vars.put("generated_files", generatedFileReader.readGeneratedFiles(ctx.getWorkspacePath()));

            String template = promptRegistry.getPrompt("current/report-user");
            String userPrompt = promptRegistry.render(template, vars);

            String report = callWithRetry(ctx, userPrompt);
            if (report == null || report.trim().isEmpty()) {
                return StageResult.failure(5, name(), "Report generation returned empty output after retries.");
            }

            Path reportFile = ctx.getWorkspacePath().resolve("report/report.md");
            Files.createDirectories(reportFile.getParent());
            Files.write(reportFile, report.getBytes(StandardCharsets.UTF_8));

            ObjectNode summary = mapper.createObjectNode();
            summary.put("reportPath", "report/report.md");
            summary.put("charCount", report.length());
            workspace.writeStageData(cveId, 5, summary);

            ctx.reportProgress("Report generated (" + report.length() + " chars).");
            return StageResult.success(5, name(), summary);
        } catch (Exception e) {
            log.error("Stage 5 report generation failed", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return StageResult.failure(5, name(), "Report generation failed: " + msg);
        }
    }

    private Object safeRead(WorkspaceManager workspace, String cveId, int stageNum) {
        try {
            if (!workspace.isStageComplete(cveId, stageNum)) {
                return null;
            }
            return workspace.readStageData(cveId, stageNum, Object.class);
        } catch (Exception e) {
            log.warn("Stage 5 could not read stage {} data: {}", stageNum, e.getMessage());
            return null;
        }
    }

    private String callWithRetry(PipelineContext ctx, String userPrompt) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            try {
                LlmRequest request = LlmRequest.generation(
                        LlmPromptStage.REPORT_GENERATION, TASK_PROMPT, userPrompt);
                LlmResponse response = ctx.getLlmClient().chat(request);
                String content = response == null ? null : response.getContent();
                String stripped = stripMarkdownFence(content);
                if (stripped != null && !stripped.trim().isEmpty()) {
                    return stripped;
                }
                log.warn("Stage 5 attempt {} produced empty content.", attempt);
            } catch (Exception e) {
                last = e;
                log.warn("Stage 5 attempt {} failed: {}", attempt, e.getMessage());
            }
            if (attempt <= MAX_RETRIES) {
                Thread.sleep(2000L * attempt);
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    private String stripMarkdownFence(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }
}
```

> Adjust these to the real APIs discovered while reading `ReasoningStage`/`DataExtractor`:
> - `dataExtractor.extractArtifact(...)` / `extractVulnerabilityFacts(...)` / `extractTriggerChain(...)` / `extractRootCause(...)` / `extractDiff(...)` — confirm exact parameter lists (some take the raw stage object, `extractDiff` takes `(ctx, stage4Data, cap)`). If a method reads a specific stage, pass that stage's data.
> - `promptRegistry.getPrompt` / `render` names — mirror `ReasoningStage` exactly.
> - `LlmResponse.getContent()` and `StageResult.success/failure` signatures — already verified in jvuln-utils.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl backend/jvuln-stages -am test -Dtest=ReportGenerationStageTest`
Expected: PASS (both tests). If the empty-output test flakes on the retry `Thread.sleep`, that is expected latency (2s + 4s); allow it.

- [ ] **Step 5: Full module compile + test**

Run: `mvn -q -pl backend/jvuln-stages,backend/jvuln-app -am compile`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/generator/report/ReportGenerationStage.java backend/jvuln-stages/src/test/java/com/jvuln/generator/report/ReportGenerationStageTest.java
git commit -m "feat: add Stage 5 ReportGenerationStage"
```

---

### Task 8: Bump `PipelineConstants` to 5 stages

**Files:**
- Modify: `backend/jvuln-app/src/main/java/com/jvuln/pipeline/PipelineConstants.java`
- Test: `backend/jvuln-app/src/test/java/com/jvuln/pipeline/PipelineConstantsTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `TOTAL_STAGES == 5`, `STAGE_REPORT == 5`, `STAGE_NAMES[4] == "Report Generation"`, `getStageName(5)` / `isValidStage(5)` work.

- [ ] **Step 1: Write the failing test**

Create `backend/jvuln-app/src/test/java/com/jvuln/pipeline/PipelineConstantsTest.java`:

```java
package com.jvuln.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineConstantsTest {

    @Test
    void fiveStagesWithReportLast() {
        assertEquals(5, PipelineConstants.TOTAL_STAGES);
        assertEquals(5, PipelineConstants.STAGE_REPORT);
        assertTrue(PipelineConstants.isValidStage(5));
        assertEquals("Report Generation", PipelineConstants.getStageName(5));
    }
}
```

(If `getStageName`/`isValidStage`/`STAGE_REPORT` have different names in the file, read it first and match; the test asserts the intent.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl backend/jvuln-app -am test -Dtest=PipelineConstantsTest`
Expected: FAIL — `TOTAL_STAGES` is still 4 / `STAGE_REPORT` undefined.

- [ ] **Step 3: Edit the constants**

In `PipelineConstants.java`:
- `TOTAL_STAGES`: `4` → `5`.
- Extend `STAGE_NAMES` (currently `{"Intelligence Collection", "Patch Analysis", "AI Reasoning", "Artifacts Generation"}`) by appending `"Report Generation"`.
- Add `public static final int STAGE_REPORT = 5;` alongside the other `STAGE_*` constants.

(`getStageName`/`isValidStage` derive from `TOTAL_STAGES`/`STAGE_NAMES`, so no further change.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl backend/jvuln-app -am test -Dtest=PipelineConstantsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/jvuln-app/src/main/java/com/jvuln/pipeline/PipelineConstants.java backend/jvuln-app/src/test/java/com/jvuln/pipeline/PipelineConstantsTest.java
git commit -m "feat: bump pipeline to 5 stages with Report Generation"
```

---

### Task 9: Allow stage-5 JSON fetch in `AnalysisController`

**Files:**
- Modify: `backend/jvuln-app/src/main/java/com/jvuln/controller/AnalysisController.java` (around lines 200–201)

**Interfaces:**
- Consumes: nothing.
- Produces: `GET /{cveId}/stages/5` no longer rejected by validation.

This is a two-line guard change; verified by the full app build in Task 12. No standalone unit test (the validation is a trivial bound).

- [ ] **Step 1: Widen the stage bound**

In `AnalysisController.java`, the `getStageJson` handler:
- Line ~200: `if (stageNum < 1 || stageNum > 4) {` → `if (stageNum < 1 || stageNum > 5) {`
- Line ~201 error message: `"stageNum must be between 1 and 4"` → `"stageNum must be between 1 and 5"`

Leave the `GET /{cveId}/report` endpoint (reads `analysisQueryService.reportExists/loadReport`) unchanged — it now serves the Stage-5-written `report/report.md`.

- [ ] **Step 2: Compile the app module**

Run: `mvn -q -pl backend/jvuln-app -am compile`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/jvuln-app/src/main/java/com/jvuln/controller/AnalysisController.java
git commit -m "feat: allow stage 5 JSON fetch in AnalysisController"
```

---

### Task 10: Remove the REPORT phase from the Stage-4 agent

This task removes report writing from Stage 4 across the phase state machine and its callers. It is one task because the pieces do not compile independently — `AgentPhase.REPORT` removal forces the `AgentPhaseEngine` and `ArtifactGenStage` edits together. There is no isolated failing unit test; correctness is proven by the module compile + existing generator tests staying green.

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/AgentPhase.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/AgentPhaseEngine.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/ArtifactGenStage.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/ExecutionPlan.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/ToolDefinitionBuilder.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/ReviewEngine.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/WriteScope.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/GeneratorConstants.java`
- Modify: `backend/jvuln-stages/src/main/resources/prompts/current/artifact-agent-user.md`

**Interfaces:**
- Consumes: nothing new.
- Produces: `AgentPhase` without `REPORT`; `buildPhaseDirective(AgentContext, ValidationResult)` (drops the `forceReport` param); `buildAutoValidationFeedback(AgentContext, ValidationResult, String)` (drops the `wroteReport` param); `WriteScope` without `report`; `ExecutionPlan` without `reportStrategy`.

- [ ] **Step 1: `AgentPhase.java` — remove `REPORT`**

Change the enum from `{ PLAN, GENERATE_MINIMAL, COMPILE_FIX, STARTUP_FIX, POC_FIX, REPORT, FINISHED }` to:

```java
enum AgentPhase { PLAN, GENERATE_MINIMAL, COMPILE_FIX, STARTUP_FIX, POC_FIX, FINISHED }
```

- [ ] **Step 2: `GeneratorConstants.java` — remove `REPORT_FALLBACK_TURNS`**

Delete the line `public static final int REPORT_FALLBACK_TURNS = 5;` (line ~29). Keep `MAX_AGENT_TURNS`.

- [ ] **Step 3: `WriteScope.java` — remove `report`**

Change to:

```java
class WriteScope {
    boolean vulnDemo;
    boolean poc;
}
```

- [ ] **Step 4: `AgentPhaseEngine.java` — prune the REPORT state machine**

Apply all of these:

1. Delete the field `private static final int REPORT_FALLBACK_TURNS = 5;` (line 17).
2. In `inferPhase` delete the block (lines 36–38):
   ```java
        if (Files.exists(ctx.cvePath.resolve("report/report.md"))) {
            return AgentPhase.REPORT;
        }
   ```
3. `currentDirective` line 54: `return buildPhaseDirective(ctx, ctx.lastValidation, false);` → `return buildPhaseDirective(ctx, ctx.lastValidation);`
4. Change the signature (line 57) `PhaseDirective buildPhaseDirective(AgentContext ctx, ValidationResult result, boolean forceReport) {` → `PhaseDirective buildPhaseDirective(AgentContext ctx, ValidationResult result) {` and change line 58:
   ```java
        AgentPhase phase = forceReport ? AgentPhase.REPORT : (ctx.phase == null ? inferPhase(ctx) : ctx.phase);
   ```
   →
   ```java
        AgentPhase phase = ctx.phase == null ? inferPhase(ctx) : ctx.phase;
   ```
5. Delete the entire `case REPORT:` block in the `switch (phase)` (lines 96–103).
6. In `nextPhaseAfterValidation` delete the leading fallback (lines 134–136):
   ```java
        if (remainingTurns <= REPORT_FALLBACK_TURNS) {
            return AgentPhase.REPORT;
        }
   ```
   and change the final `return AgentPhase.REPORT;` (line 149) to `return AgentPhase.FINISHED;` (once compile/startup/poc all pass, the run is done). Keep the `remainingTurns` parameter (callers still pass it); it is now unused inside the method — either keep it for signature stability or remove it and update the three callers in `ArtifactGenStage` (see Step 6). **Decision: keep the parameter** to minimize caller churn.
7. In `isToolAllowed` delete the `case REPORT:` block (lines 170–173).
8. In `allowedActions` delete the `case REPORT:` block (lines 324–325).
9. Change `buildAutoValidationFeedback` signature (line 278) `String buildAutoValidationFeedback(AgentContext ctx, ValidationResult result, String focus, boolean wroteReport) {` → drop `boolean wroteReport`. Then replace the success block (lines 283–290):
   ```java
        if (result.compileOk && result.startupOk && result.pocVerified) {
            if (Files.exists(ctx.cvePath.resolve("report/report.md")) || wroteReport) {
                sb.append("Validation passed. Call finish() now with the concrete evidence above.");
            } else {
                sb.append("Validation passed. Write report/report.md now, then call finish().");
            }
            return sb.toString();
        }
   ```
   with:
   ```java
        if (result.compileOk && result.startupOk && result.pocVerified) {
            sb.append("Validation passed. Call finish() now with the concrete evidence above.");
            return sb.toString();
        }
   ```
10. In `markWriteScope` delete the `report/` branch (lines 416–417):
    ```java
        } else if (path.startsWith("report/")) {
            scope.report = true;
        }
    ```
    so it ends at the `poc/` branch.
11. If `import java.nio.file.Files;` becomes unused after removing the `inferPhase` and feedback checks, leave it only if still referenced (it is — `determineAutoValidationFocus` uses `Files.exists`). Keep the import.

- [ ] **Step 5: `ExecutionPlan.java` — remove `reportStrategy`**

Remove the `final String reportStrategy;` field, its assignment in `fromJson` (`node.path("reportStrategy").asText("")`), the constructor parameter/assignment, its entry in `toMap()`, and the `!reportStrategy.isEmpty()` clause in `isUsable()` (line ~46). Update any constructor call sites accordingly (grep `new ExecutionPlan(`).

- [ ] **Step 6: `ArtifactGenStage.java` — remove report tracking and the REPORT branch**

1. Delete the `else if (remaining == REPORT_FALLBACK_TURNS) { ... }` block (lines 239–254) entirely (the whole `else if` including the phase-switch and FINAL WARNING). The preceding `if (remaining == 20) { ... }` stays.
2. Delete `boolean wroteReport = false;` (line 322).
3. Delete `wroteReport = wroteReport || scope.report;` (line 380).
4. Delete the branch (lines 402–406):
   ```java
                } else if (!finished && wroteReport && agentCtx.phase == AgentPhase.REPORT) {
                    agentCtx.lastDirective = phaseEngine.buildPhaseDirective(agentCtx, agentCtx.lastValidation, false);
                    String directive = phaseEngine.renderPhaseDirective(agentCtx.lastDirective);
                    messages.add(LlmRequest.Message.user(directive));
                    contextBuilder.appendTranscript(agentCtx, "directive", turn + 1, directive);
   ```
   Merge cleanly: the chain becomes `if (!finished && (wroteVulnDemo || wrotePoc)) { ... } else if (!finished && ranValidation) { ... }`.
5. Line 419 `phaseEngine.updateProgressGuard(agentCtx, wroteVulnDemo || wrotePoc || wroteReport);` → `phaseEngine.updateProgressGuard(agentCtx, wroteVulnDemo || wrotePoc);`
6. Line 440 delete `forcedSummary.put("report_status", Files.exists(cvePath.resolve("report/report.md")) ? "generated" : "skipped");`.
7. Update every `buildPhaseDirective(..., false)` / `buildPhaseDirective(..., true)` call to drop the trailing boolean (call sites at lines 245 — now deleted, 299, 396, 403 — now deleted, 409, 522). Remaining live calls: 299, 396, 409, 522 → `phaseEngine.buildPhaseDirective(agentCtx, <result>)`.
8. Update the `buildAutoValidationFeedback(...)` call site (grep for it) to drop the final `wroteReport` argument.
9. If `import static com.jvuln.generator.GeneratorConstants.*;` now leaves `REPORT_FALLBACK_TURNS` unresolved, that is fine — the constant is gone and its only uses (steps above) are deleted. Confirm no other `REPORT_FALLBACK_TURNS` reference remains via grep.

- [ ] **Step 7: `ToolDefinitionBuilder.java` — remove reportStrategy / report_status**

1. Delete line 29 `planProps.put("reportStrategy", prop("string", "When you will write report/report.md"));`.
2. Line 31 `planSchema.put("required", Arrays.asList("goal", "firstBatchFiles", "validationSequence", "reportStrategy"));` → drop `"reportStrategy"`: `planSchema.put("required", Arrays.asList("goal", "firstBatchFiles", "validationSequence"));`.
3. Line 98 delete `finishProps.put("report_status", prop("string", "Status: generated, skipped"));`.
4. Line 53 `write_files` description currently: "Every path must start with vuln-demo/, poc/, or report/." → change to "Every path must start with vuln-demo/ or poc/.".

- [ ] **Step 8: `ReviewEngine.java` — drop report from review candidates**

Line ~108 delete `candidates.add("report/report.md");` in `collectKeyFileSnippets`.

- [ ] **Step 9: `ArtifactGenStage` submit_plan validation message**

Line ~518 `return "Error: submit_plan requires goal, firstBatchFiles, validationSequence, and reportStrategy";` → `return "Error: submit_plan requires goal, firstBatchFiles, and validationSequence";`.

- [ ] **Step 10: `artifact-agent-user.md` — remove the report reference**

Line ~35 currently: "Prefer creating `vuln-demo + poc` before spending time on `report/report.md`." → remove the report clause, e.g.: "Focus on getting `vuln-demo` + `poc` to actually run and verify." (Read the surrounding line first and keep it grammatical.)

- [ ] **Step 11: Compile the stages + app modules**

Run: `mvn -q -pl backend/jvuln-stages,backend/jvuln-app -am compile`
Expected: PASS. If it fails, the error names the exact remaining `REPORT` / `reportStrategy` / `wroteReport` / `scope.report` reference — fix it and re-run.

- [ ] **Step 12: Run the stages test suite to confirm no regression**

Run: `mvn -q -pl backend/jvuln-stages -am test`
Expected: PASS (all existing generator tests + the new Task 5/6/7 tests).

- [ ] **Step 13: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/generator/ backend/jvuln-stages/src/main/resources/prompts/current/artifact-agent-user.md
git commit -m "refactor: remove report writing from Stage 4 artifact agent"
```

---

### Task 11: Frontend — show Stage 5 and read its report

**Files:**
- Modify: `frontend/src/views/AnalysisDetail.vue`
- Modify: `frontend/src/locales/zh-CN.ts` (line ~74 `stageNames`)
- Modify: `frontend/src/locales/en-US.ts` (line 74 `stageNames`)

**Interfaces:**
- Consumes: `analysis.stageNames` i18n array; `api.getReport(cveId)`.
- Produces: 5-step pipeline UI; Report tab already reads `/{cveId}/report` (now Stage-5-sourced).

- [ ] **Step 1: Add the 5th stage name to both locales**

`frontend/src/locales/zh-CN.ts` line 74: `stageNames: ['情报采集', '补丁分析', 'AI 推理', '产物生成'],` → `stageNames: ['情报采集', '补丁分析', 'AI 推理', '产物生成', '报告生成'],`

`frontend/src/locales/en-US.ts` line 74: `stageNames: ['Intelligence', 'Patch Analysis', 'AI Reasoning', 'Artifacts'],` → `stageNames: ['Intelligence', 'Patch Analysis', 'AI Reasoning', 'Artifacts', 'Report'],`

- [ ] **Step 2: Add the 5th stage icon in `AnalysisDetail.vue`**

Line 61 `const stageIcons = ['01', '02', '03', '04']` → `const stageIcons = ['01', '02', '03', '04', '05']`.

- [ ] **Step 3: Widen the pipeline row grid**

In `AnalysisDetail.vue` CSS (line ~980) `.jv-pipeline-row { ... grid-template-columns: repeat(4, 1fr); ... }` → `grid-template-columns: repeat(5, 1fr);`. (If the row is already `auto`-flow rather than a fixed `repeat(4, ...)`, leave it; the `v-for` over `stageNames` already renders 5.)

- [ ] **Step 4: (Optional) fetch stage-5 JSON in `loadStageData`**

The Report tab already loads via `api.getReport(cveId)` inside the existing stage-4 block, so the report renders without extra work. Only add a stage-5 fetch if the UI reads `stageData[5]` for the step's status. If it does, mirror the existing per-stage fetch pattern (`api.getStageJson(cveId, 5)` wrapped in try/catch, tolerate 404 while the stage has not run). If the pipeline step status comes from the task's stage records (not the JSON), skip this step.

- [ ] **Step 5: Build the frontend**

Run (from `frontend/`): `npm run build`
Expected: PASS (type-check + build). Fix any TypeScript error the build reports.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/AnalysisDetail.vue frontend/src/locales/zh-CN.ts frontend/src/locales/en-US.ts
git commit -m "feat: wire Stage 5 report generation into the frontend"
```

---

### Task 12: Full-build verification

**Files:** none (verification only).

- [ ] **Step 1: Full backend compile**

Run: `mvn -q -pl backend/jvuln-stages,backend/jvuln-app -am compile`
Expected: PASS.

- [ ] **Step 2: Full backend test**

Run: `mvn -q -pl backend/jvuln-utils,backend/jvuln-stages,backend/jvuln-app -am test`
Expected: PASS — includes `StagePromptResourcesTest` (5 enums), `WorkspaceManagerStage5Test`, `DataExtractorTest`, `GeneratedFileReaderTest`, `ReportGenerationStageTest`, `PipelineConstantsTest`, and all pre-existing tests.

- [ ] **Step 3: Frontend build**

Run (from `frontend/`): `npm run build`
Expected: PASS.

- [ ] **Step 4: Manual smoke (document only, do not automate)**

Note for the operator: run one CVE end-to-end, confirm (a) a distinct "Report Generation" / "报告生成" step appears as stage 5, (b) the Report tab renders the generated Markdown, (c) Stage 4 no longer writes `report/report.md` (only Stage 5 does). This is a manual check — the pipeline needs a live LLM config and Java toolchain.

---

## Self-Review

**1. Spec coverage** (design sections 1–9 + the WorkspaceManager blocker):

| Spec section | Task |
| --- | --- |
| §1 report.md audit | Task 3 |
| §2 new enum value | Task 1 |
| §3 DataExtractor.trimIntelligence | Task 5 |
| §4 generated-file reader | Task 6 |
| §5 ReportGenerationStage | Task 7 |
| §6 report-user.md | Task 4 |
| §7 PipelineConstants | Task 8 |
| §8 Stage 4 cleanup | Task 10 |
| §9 frontend wiring | Task 11 |
| WorkspaceManager case 5 (blocker) | Task 2 |
| Testing section | Tasks 1–12 (each has its own run step) + Task 12 |

All covered.

**2. Placeholder scan:** No "TBD"/"implement later"/"handle edge cases". The three `>`-quoted "confirm the real API by reading X" notes in Tasks 5/7 are deliberate: they point the implementer at the authoritative source (`ReasoningStage`, `DataExtractor`, `IntelligenceStageTest`) for exact method/constructor names that could not be 100% pinned from the summary, and give a concrete fallback. They are guidance, not missing content — the full code is present.

**3. Type consistency:** `trimIntelligence(Object)` (Task 5) is called in Task 7. `readGeneratedFiles(Path)` (Task 6) is called in Task 7 with `ctx.getWorkspacePath()`. `LlmPromptStage.REPORT_GENERATION` (Task 1) used in Task 7. `writeStageData(cveId, 5, ...)` (Task 2) used in Task 7. `buildPhaseDirective(AgentContext, ValidationResult)` and `buildAutoValidationFeedback(AgentContext, ValidationResult, String)` new signatures (Task 10) are applied at all call sites in the same task. `WriteScope` loses `report` (Task 10) and the only reader (`ArtifactGenStage` line 380) is removed in the same task. `STAGE_REPORT`/`TOTAL_STAGES` (Task 8) used by the controller bound (Task 9) conceptually. Consistent.

One open dependency the implementer must honor: **Task 10 must not run before Task 7 is committed** only if Task 7 relied on Stage 4 code — it does not. But Task 10 removing `AgentPhase.REPORT` must compile alongside Task 7's new stage; since they touch disjoint files, order 7→8→9→10→11 is safe. Tasks 1–2 must precede Task 7 (hard dependency). Recommended order is the task numbering as written.
