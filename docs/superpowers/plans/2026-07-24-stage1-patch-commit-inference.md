# Stage 1 Patch-Commit Inference & Multi-Major Version Selection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the wrong-patch bug (CVE-2021-42392) by adding LLM-based patch-commit inference in Stage 1, removing keyword-based patch selection in Stage 2, and supporting multi-major version selection.

**Architecture:** New `PatchCommitInferer` class in Stage 1 fetches full commit logs from GitHub and asks the LLM to identify patch commits. A new `fixedVersions` list captures all fixed versions from OSV/GHSA sources. Stage 2's keyword-based release-tag fallback is deleted. A repurposed `fixedVersion` (singular) anchors the Stage 4 vuln-demo version.

**Tech Stack:** Java 17, Spring Boot 3.x, GitHub REST API, LLM (via LlmClient), Jackson, JUnit 5

## Global Constraints

- Every source file < 80 KB; every method < 256 lines.
- Reuse helpers from `backend/jvuln-utils` before adding local ones.
- No AI-generated diffs; LLM only selects among real commits.
- `LlmRequest.reasoning(stage, taskPrompt, userContent)` uses `jsonMode=true`.
- Prompts loaded via `PromptRegistry.getPrompt("current/<name>")` from `classpath:prompts/current/<name>.md`.
- Educational local vulnerability demo context.

---

### Task 1: Add `fixedVersions` list to SourceData + IntelFragment

**Files:**
- Modify: `backend/jvuln-utils/src/main/java/com/jvuln/store/model/SourceData.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/collector/source/IntelSource.java`
- Test: `backend/jvuln-stages/src/test/java/com/jvuln/collector/source/IntelSourceParsingTest.java`

**Interfaces:**
- Consumes: existing `SourceData` constructor with 12 fields
- Produces: `SourceData(List<String> fixedVersions)` — new 13th param; `getFixedVersions()` getter returns `fixedVersions`; backwards-compatible deserialization (absent field → empty list via `@JsonProperty(defaultValue = "")` or `Optional` wrapping)

**Background:** `SourceData` currently carries `fixedVersion` (singular String). We need a `fixedVersions` (plural list) to hold ALL fixed versions across majors. The list is populated by the source collectors (OSV, GHSA) and merged by the assembler.

- [ ] **Step 1: Add `fixedVersions` to `SourceData`**

```java
// New field alongside existing `private final String fixedVersion;`
private final List<String> fixedVersions;

// Update @JsonCreator constructor — add new param after `fixCommits`
// Use @JsonProperty("fixedVersions") List<String> fixedVersions
// Jackson: would match the new field by name. Use @JsonSetter(nulls = Nulls.AS_EMPTY) or
// just `fixedVersions == null ? Collections.emptyList() : immutableList(fixedVersions)`

// Update empty():
public static SourceData empty() {
    return new SourceData("", "", "", "", "", "", "", "", "", "",
            Collections.<String>emptyList(), Collections.<CveIntelligence.Article>emptyList(),
            Collections.<String>emptyList());
}

// Add getter:
public List<String> getFixedVersions() { return fixedVersions; }
```

⚠ **IMPORTANT:** The `@JsonCreator` constructor must handle the case where old JSON (without `fixedVersions`) is deserialized. Use `@JsonProperty(defaultValue = "")` on the new param or add `null` handling in the constructor body. Jackson's `@JsonSetter(nulls = Nulls.AS_EMPTY)` on the constructor parameter also works. Safest: read as `@JsonProperty("fixedVersions") List<String> fixedVersions` and in constructor: `this.fixedVersions = fixedVersions == null ? Collections.emptyList() : immutableList(fixedVersions);`.

- [ ] **Step 2: Update `IntelFragment` convenience constructor and `success()`**

The `IntelFragment(String, boolean, String, String, String, String, String, String, String, String, String, List, List)` constructor delegates to `new SourceData(...)`. Add the new `fixedVersions` param (empty list) to the `SourceData(...)` call:
```java
public IntelFragment(...) {
    this(..., new SourceData(cweId, cvssScore, ..., fixCommits, articles,
            Collections.<String>emptyList()), rawJson);
    //                                            ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ new
}
```

- [ ] **Step 3: Write test for backward-compatible deserialization**

```java
@Test void deserializesOldJsonWithoutFixedVersions() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    // Old JSON (no fixedVersions field)
    String old = "{\"cweId\":\"\",\"cvssScore\":\"\",\"cvssVector\":\"\",\"cvssSeverity\":\"\","
            + "\"artifactGroupId\":\"\",\"artifactId\":\"\",\"affectedFrom\":\"\",\"affectedTo\":\"\","
            + "\"fixedVersion\":\"2.0.206\",\"sourceRepo\":\"\",\"fixCommits\":[],\"references\":[]}";
    SourceData data = mapper.readValue(old, SourceData.class);
    assertEquals("2.0.206", data.getFixedVersion());
    assertTrue(data.getFixedVersions().isEmpty(), "missing field defaults to empty list");
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn test -pl jvuln-stages -Dtest=IntelSourceParsingTest -DfailIfNoTests=false -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/jvuln-utils/src/main/java/com/jvuln/store/model/SourceData.java
git add backend/jvuln-stages/src/main/java/com/jvuln/collector/source/IntelSource.java
git add backend/jvuln-stages/src/test/java/com/jvuln/collector/source/IntelSourceParsingTest.java
git commit -m "feat(model): add fixedVersions list to SourceData + IntelFragment"
```

---

### Task 2: Source collectors populate `fixedVersions`

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/collector/source/OsvSource.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/collector/source/GhsaSource.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/collector/source/NvdSource.java`
- Test: `backend/jvuln-stages/src/test/java/com/jvuln/collector/source/IntelSourceParsingTest.java`

**Background:** Each source currently sets a single `fixedVersion`. OSV iterates `ranges[].events` and overwrites `facts.fixedVersion` on each `fixed` event, keeping only the last. GHSA sets `facts.fixedVersion` from `first_patched_version`. NVD has no fixed version and sets empty. Each needs to also populate `fixedVersions`.

- [ ] **Step 1: Update `OsvSource.packageFacts` to collect all fixed events**

Before: `OsvSource` has a `PackageFacts` inner class with `String fixedVersion`. The field gets overwritten per event.

After: collect ALL distinct `fixed` event values into a `List<String>`:
```java
private PackageFacts packageFacts(JsonNode affected) {
    PackageFacts facts = new PackageFacts();
    for (JsonNode item : affected) {
        // ... existing ecosystem check and packageName parsing ...
        for (JsonNode range : item.path("ranges")) {
            for (JsonNode event : range.path("events")) {
                if (event.has("introduced")) facts.affectedFrom = event.path("introduced").asText("");
                if (event.has("fixed")) {
                    String fixed = event.path("fixed").asText("");
                    if (!fixed.isEmpty() && !facts.fixedVersions.contains(fixed)) {
                        facts.fixedVersions.add(fixed);
                    }
                }
            }
        }
        break; // first matching Maven ecosystem only
    }
    // Set singular fixedVersion = first (lowest) entry if available
    if (!facts.fixedVersions.isEmpty()) {
        facts.fixedVersion = facts.fixedVersions.get(0);
    }
    return facts;
}
```

Update `PackageFacts` inner class:
```java
private static class PackageFacts {
    private String groupId = "";
    private String artifactId = "";
    private String affectedFrom = "";
    private String fixedVersion = "";
    private final List<String> fixedVersions = new ArrayList<>(); // NEW
}
```

Update the `SourceData` construction in `parsePayload`:
```java
SourceData data = new SourceData("", "", "", "", facts.groupId,
        facts.artifactId, facts.affectedFrom, "", facts.fixedVersion, "",
        fixCommits, articles, facts.fixedVersions);  // ← new last param
```

- [ ] **Step 2: Update `GhsaSource` — `fixedVersions` is a singleton list**

`GhsaSource.packageFacts` already gets one `first_patched_version`. For `fixedVersions`, wrap it as a singleton list:
```java
List<String> fixedVersionsList = facts.fixedVersion.isEmpty()
    ? Collections.emptyList()
    : Collections.singletonList(facts.fixedVersion);
SourceData data = new SourceData("", "", "", "", facts.groupId,
        facts.artifactId, "", facts.affectedTo, facts.fixedVersion, sourceRepo,
        fixCommits, articles, fixedVersionsList);
```

- [ ] **Step 3: Update `NvdSource` — empty `fixedVersions`**

NvdSource already sets `fixedVersion=""`. Pass `Collections.emptyList()` as the new last param to the `SourceData` constructor:
```java
SourceData data = new SourceData(cweId, cvssScore, cvssVector, cvssSeverity,
        facts.groupId, facts.artifactId, "", facts.affectedTo, "", "",
        fixCommits, articles, Collections.<String>emptyList());
```

- [ ] **Step 4: Write test for OSV fixedVersions parsing**

```java
@Test void osvCollectsMultipleFixedVersions() throws Exception {
    OsvSource source = new OsvSource();
    String payload = "{\"id\":\"CVE-2021-42392\",\"summary\":\"test\","
            + "\"affected\":[{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"com.h2database:h2\"},"
            + "\"ranges\":[{\"type\":\"ECOSYSTEM\",\"events\":["
            + "{\"introduced\":\"0\"},{\"fixed\":\"1.4.200\"},{\"fixed\":\"2.0.206\"}]}]}],"
            + "\"references\":[]}";
    IntelSource.IntelFragment result = source.parsePayload(payload);
    List<String> fvs = result.getParsedData().getFixedVersions();
    assertEquals(2, fvs.size());
    assertTrue(fvs.contains("1.4.200"));
    assertTrue(fvs.contains("2.0.206"));
    // Singular fixedVersion = earliest (lowest major wins as per spec)
    assertEquals("1.4.200", result.getParsedData().getFixedVersion());
}
```

- [ ] **Step 5: Run tests**

Run: `cd backend && mvn test -pl jvuln-stages -Dtest=IntelSourceParsingTest -DfailIfNoTests=false -q`
Expected: BUILD SUCCESS (existing tests still pass + new test passes)

- [ ] **Step 6: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/collector/source/OsvSource.java
git add backend/jvuln-stages/src/main/java/com/jvuln/collector/source/GhsaSource.java
git add backend/jvuln-stages/src/main/java/com/jvuln/collector/source/NvdSource.java
git add backend/jvuln-stages/src/main/java/com/jvuln/collector/source/GiteeSource.java
git add backend/jvuln-stages/src/test/java/...
git commit -m "feat(collector): populate fixedVersions from OSV/GHSA/NVD sources"
```

---

### Task 3: IntelligenceAssembler + Draft + CveIntelligence — thread `fixedVersions` through

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/collector/IntelligenceAssembler.java`
- Modify: `backend/jvuln-utils/src/main/java/com/jvuln/store/model/CveIntelligence.java`
- Test: `backend/jvuln-stages/src/test/java/com/jvuln/collector/IntelligenceStageTest.java`

**Background:** The assembler merges `SourceResult` data into a `Draft` and then creates `CveIntelligence`. We need the new `fixedVersions` list to flow through: `SourceData` → `Draft` → `CveIntelligence`.

- [ ] **Step 1: Add `fixedVersions` to `Draft`**

```java
public static class Draft {
    // ... existing fields ...
    private final Set<String> fixCommits = new LinkedHashSet<>();
    private final List<String> fixedVersions = new ArrayList<>(); // NEW
    private final List<CveIntelligence.Article> articles = new ArrayList<>();
```

- [ ] **Step 2: In `merge()`, populate both `fixedVersion` and `fixedVersions`**

```java
draft.fixedVersion = first(draft.fixedVersion, data.getFixedVersion());
draft.fixedVersions.addAll(data.getFixedVersions());
// deduplicate and preserve order
```

Add dedup step after the loop:
```java
// Dedupe fixedVersions across sources preserving first-encountered order
Set<String> seen = new LinkedHashSet<>(draft.fixedVersions);
draft.fixedVersions.clear();
draft.fixedVersions.addAll(seen);
```

- [ ] **Step 3: Add `fixedVersions` to `CveIntelligence` (field, getter, both constructors)**

There are two constructors:
1. A 12-param convenience constructor (line 32-41) that delegates to the `@JsonCreator` with empty lists for `sourceResults`/`evidenceResults`/`adjudication`.
2. A 15-param `@JsonCreator` constructor (line 43-76).

**Decision (definitive):** add `fixedVersions` as a new `@JsonProperty("fixedVersions")` param positioned **immediately after `fixCommits`** in the `@JsonCreator` constructor, and thread the default `Collections.emptyList()` through the 12-param convenience constructor. Do NOT add it to the 12-param signature — keep that signature stable and pass the empty-list default in its delegation.

Add field + getter:
```java
private final List<String> fixedVersions;

public List<String> getFixedVersions() { return fixedVersions; }
```

Update the 12-param convenience constructor's delegation to insert `Collections.<String>emptyList()` right after the `fixCommits` argument:
```java
public CveIntelligence(String cveId, String description, CvssScore cvss, String cweId,
                       MavenCoordinate artifact, VersionRange affectedVersions,
                       String fixedVersion, String sourceRepo,
                       List<String> fixCommits, List<Article> articles,
                       List<ReferenceFinding> referenceFindings, Instant collectedAt) {
    this(cveId, description, cvss, cweId, artifact, affectedVersions,
            fixedVersion, sourceRepo, fixCommits,
            Collections.<String>emptyList(), // fixedVersions default (after fixCommits)
            articles, referenceFindings,
            collectedAt, Collections.<SourceResult>emptyList(),
            Collections.<EvidenceResult>emptyList(), DescriptionAdjudication.notRun(""));
}
```

Update the `@JsonCreator` constructor — insert the new param right after `fixCommits`:
```java
@JsonCreator
public CveIntelligence(
        @JsonProperty("cveId") String cveId,
        @JsonProperty("description") String description,
        @JsonProperty("cvss") CvssScore cvss,
        @JsonProperty("cweId") String cweId,
        @JsonProperty("artifact") MavenCoordinate artifact,
        @JsonProperty("affectedVersions") VersionRange affectedVersions,
        @JsonProperty("fixedVersion") String fixedVersion,
        @JsonProperty("sourceRepo") String sourceRepo,
        @JsonProperty("fixCommits") List<String> fixCommits,
        @JsonProperty("fixedVersions") List<String> fixedVersions,  // NEW
        @JsonProperty("articles") List<Article> articles,
        @JsonProperty("referenceFindings") List<ReferenceFinding> referenceFindings,
        @JsonProperty("collectedAt") Instant collectedAt,
        @JsonProperty("sourceResults") List<SourceResult> sourceResults,
        @JsonProperty("evidenceResults") List<EvidenceResult> evidenceResults,
        @JsonProperty("descriptionAdjudication") DescriptionAdjudication adjudication) {
    // ... existing assignments ...
    this.fixCommits = immutableList(fixCommits);
    this.fixedVersions = fixedVersions == null ? Collections.emptyList() : immutableList(fixedVersions);
    this.articles = immutableList(articles);
    // ... rest unchanged ...
}
```

- [ ] **Step 4: Wire `Draft.fixedVersions` into the `toIntelligence()` call**

```java
public CveIntelligence toIntelligence(String description,
                                       List<CveIntelligence.Article> classifiedArticles,
                                       List<EvidenceResult> evidence,
                                       DescriptionAdjudication adjudication) {
    return new CveIntelligence(cveId, description,
            new CveIntelligence.CvssScore(cvssScore, cvssVector, cvssSeverity),
            cweId, new CveIntelligence.MavenCoordinate(groupId, artifactId),
            new CveIntelligence.VersionRange(affectedFrom, affectedTo),
            fixedVersion, sourceRepo, new ArrayList<>(fixCommits),
            new ArrayList<>(fixedVersions), // ← new argument, right after fixCommits
            classifiedArticles, Collections.<CveIntelligence.ReferenceFinding>emptyList(),
            collectedAt, sourceResults, evidence, adjudication);
}
```

- [ ] **Step 5: Write test for fixedVersions flow**

```java
@Test void assembledIntelligenceHasFixedVersions() {
    // Create mock SourceResults with fixedVersions populated
    // Run assembler.merge()
    // Verify draft.fixedVersions contains the expected list
    // Verify toIntelligence().getFixedVersions() matches
}
```

- [ ] **Step 6: Run tests**

Run: `cd backend && mvn test -pl jvuln-stages -Dtest=IntelligenceStageTest -DfailIfNoTests=false -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/collector/IntelligenceAssembler.java
git add backend/jvuln-utils/src/main/java/com/jvuln/store/model/CveIntelligence.java
git add backend/jvuln-stages/src/test/...
git commit -m "feat(model): thread fixedVersions through Draft → CveIntelligence"
```

---

### Task 4: Create `PatchCommitInferer`

**Files:**
- Create: `backend/jvuln-stages/src/main/java/com/jvuln/collector/PatchCommitInferer.java`
- Create: `backend/jvuln-stages/src/main/resources/prompts/current/patch-commit-inference.md`
- Test: `backend/jvuln-stages/src/test/java/com/jvuln/collector/PatchCommitInfererTest.java`

**Interfaces:**
- Consumes: `LlmClient`, `PromptRegistry`, `WebClient` (GitHub API), CVE metadata (cveId, description, sourceRepo, fixedVersions list)
- Produces: `InferenceResult` — `{ List<String> commitUrls, String chosenVersion }` — the LLM-chosen commit URLs and the major's fixed version that was used. Both may be empty on failure.

**Background:** When Stage 1 has no `fixCommits`, this new class fetches the full commit log for the release range and asks the LLM to identify the actual patch commit(s). It groups `fixedVersions` by major, tries lowest first, escalates upward.

- [ ] **Step 1: Write the prompt file `patch-commit-inference.md`**

```markdown
You are analyzing a CVE to identify its security patch commits.

## CVE Description
{{cve_description}}

## Affected Component
{{artifact_coordinate}}

## Fixed Version
{{fixed_version}}

## Commits Between prevTag and fixedTag
Each commit is listed as: SHA | commit_message

{{commit_log}}

## Instructions
Review each commit message and identify which commit(s) fixed the CVE vulnerability.
A security patch commit may:
- Contain the CVE ID in its message
- Reference the vulnerability type (e.g. JNDI, injection, RCE)
- Change security-critical code (input validation, access control, deserialization, JNDI lookup)

Return ONLY a JSON object:
{"commits": ["full_sha_of_patch_commit", ...], "reasoning": "brief explanation"}
```

- [ ] **Step 2: Write the `PatchCommitInferer` class**

```java
package com.jvuln.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.util.RequestLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PatchCommitInferer {

    private static final Logger log = LoggerFactory.getLogger(PatchCommitInferer.class);
    private static final Pattern TAG_PAT = Pattern.compile(
            "github\\.com/([^/]+)/([^/]+?)(?:\\.git)?$");
    private static final Pattern VERSION_PAT = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+).*");

    private final LlmClient llmClient;
    private final PromptRegistry promptRegistry;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public PatchCommitInferer(LlmClient llmClient, PromptRegistry promptRegistry,
                               @Value("${jvuln.github.token:}") String token) {
        this.llmClient = llmClient;
        this.promptRegistry = promptRegistry;
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(30));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://api.github.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .filter(RequestLogContext.webRequestFilter())
                .defaultHeader("User-Agent", "JVuln-Platform/1.0");
        if (token != null && !token.trim().isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.webClient = builder.build();
    }

    /**
     * Attempt to infer patch commit(s) from the full commit log between release tags.
     *
     * @return InferenceResult with commit URLs (possibly empty) and the chosen major's fixed version
     */
    public InferenceResult infer(String cveId, String description, String sourceRepo,
                                  List<String> fixedVersions) {
        if (sourceRepo == null || !sourceRepo.contains("github.com") || fixedVersions == null || fixedVersions.isEmpty()) {
            return InferenceResult.empty();
        }
        Matcher repoM = TAG_PAT.matcher(sourceRepo);
        if (!repoM.find()) return InferenceResult.empty();
        String owner = repoM.group(1);
        String repo = repoM.group(2);

        // Group fixedVersions by major, order low→high
        List<String> majorsByMajor = groupByMajorAscending(fixedVersions);

        for (String majorFix : majorsByMajor) {
            log.info("PatchCommitInferer: trying major fix version {} for {}", majorFix, cveId);
            try {
                InferenceResult result = tryMajor(owner, repo, majorFix, cveId, description);
                if (result.hasResult()) {
                    return result;
                }
                log.info("PatchCommitInferer: no patch found for major {}", majorFix);
            } catch (Exception e) {
                log.warn("PatchCommitInferer: major {} failed: {}", majorFix, e.getMessage());
            }
        }
        return InferenceResult.empty();
    }

    private InferenceResult tryMajor(String owner, String repo, String fixedVersion,
                                      String cveId, String description) throws Exception {
        // Resolve release tags for this version
        String tagsJson = webClient.get()
                .uri("/repos/" + owner + "/" + repo + "/tags?per_page=100")
                .header("Accept", "application/vnd.github+json")
                .retrieve().bodyToMono(String.class).block();
        if (tagsJson == null) return InferenceResult.empty();

        JsonNode tags = mapper.readTree(tagsJson);
        if (!tags.isArray()) return InferenceResult.empty();

        // Find the tag matching this version (try exact match, then prefix/suffix variations)
        String fixedTag = findMatchingTag(tags, fixedVersion);
        if (fixedTag == null) {
            log.warn("PatchCommitInferer: no tag found matching version {}", fixedVersion);
            return InferenceResult.empty();
        }

        // Find previous tag
        String prevTag = null;
        for (int i = 0; i < tags.size(); i++) {
            if (fixedTag.equals(tags.path(i).path("name").asText(""))) {
                if (i + 1 < tags.size()) prevTag = tags.path(i + 1).path("name").asText(null);
                break;
            }
        }
        if (prevTag == null) {
            log.warn("PatchCommitInferer: no previous tag before {}", fixedTag);
            return InferenceResult.empty();
        }

        // Fetch compare log
        String compareJson = webClient.get()
                .uri("/repos/" + owner + "/" + repo + "/compare/" + prevTag + "..." + fixedTag)
                .header("Accept", "application/vnd.github+json")
                .retrieve().bodyToMono(String.class).block();
        if (compareJson == null) return InferenceResult.empty();

        JsonNode compare = mapper.readTree(compareJson);
        JsonNode commits = compare.path("commits");
        if (!commits.isArray() || commits.isEmpty()) return InferenceResult.empty();

        // Build commit log text: "SHA | message"
        StringBuilder commitLog = new StringBuilder();
        for (JsonNode c : commits) {
            String sha = c.path("sha").asText("");
            String msg = c.path("commit").path("message").asText("");
            String firstLine = msg.split("\n")[0];
            commitLog.append(sha).append(" | ").append(firstLine).append("\n");
        }

        // Call LLM
        String taskPrompt = promptRegistry.getPrompt("current/patch-commit-inference");
        String userContent = "CVE: " + cveId + "\n"
                + "Description: " + description + "\n"
                + "Fixed version: " + fixedVersion + "\n"
                + "Commits between " + prevTag + " and " + fixedTag + ":\n"
                + commitLog.toString();

        LlmResponse response = llmClient.chat(LlmRequest.reasoning(
                LlmPromptStage.INTELLIGENCE, taskPrompt, userContent));
        if (response == null || response.getContent() == null) return InferenceResult.empty();

        // Parse response
        JsonNode result = mapper.readTree(extractJsonObject(response.getContent()));
        JsonNode commitsNode = result.path("commits");
        List<String> commitUrls = new ArrayList<>();
        if (commitsNode.isArray()) {
            for (JsonNode shaNode : commitsNode) {
                String sha = shaNode.asText("");
                if (sha.length() >= 7) {
                    String url = "https://github.com/" + owner + "/" + repo + "/commit/" + sha;
                    commitUrls.add(url);
                }
            }
        }

        return commitUrls.isEmpty() ? InferenceResult.empty()
                : new InferenceResult(commitUrls, fixedVersion);
    }

    private String findMatchingTag(JsonNode tags, String version) {
        // Try exact match first
        for (JsonNode tag : tags) {
            String name = tag.path("name").asText("");
            if (name.equals(version)) return name;
        }
        // Try common patterns: v{version}, version-{version}, release-{version}
        for (String prefix : new String[]{"v", "version-", "release-", "V_"}) {
            String candidate = prefix + version;
            for (JsonNode tag : tags) {
                if (tag.path("name").asText("").equals(candidate)) return candidate;
            }
        }
        return null;
    }

    private List<String> groupByMajorAscending(List<String> fixedVersions) {
        Map<Integer, String> majorMap = new TreeMap<>();
        for (String v : fixedVersions) {
            Matcher m = VERSION_PAT.matcher(v);
            if (m.find()) {
                int major = Integer.parseInt(m.group(1));
                // For same major, prefer the entry already in the map (first encountered)
                majorMap.putIfAbsent(major, v);
            }
        }
        return new ArrayList<>(majorMap.values());
    }

    private String extractJsonObject(String raw) {
        // Same logic as AiPatchSearchStrategy.extractJsonObject
        String cleaned = raw == null ? "" : raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z0-9_-]*\\n?", "")
                    .replaceAll("```\\s*$", "").trim();
        }
        int start = cleaned.indexOf('{');
        if (start < 0) return cleaned;
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (ch == '\\') { i++; continue; }
            if (ch == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (ch == '{') depth++;
            else if (ch == '}') { depth--; if (depth == 0) return cleaned.substring(start, i + 1); }
        }
        return cleaned;
    }

    public static class InferenceResult {
        private final List<String> commitUrls;
        private final String chosenVersion;

        public InferenceResult(List<String> commitUrls, String chosenVersion) {
            this.commitUrls = commitUrls != null ? Collections.unmodifiableList(commitUrls) : Collections.emptyList();
            this.chosenVersion = chosenVersion;
        }

        public static InferenceResult empty() {
            return new InferenceResult(Collections.emptyList(), null);
        }

        public boolean hasResult() { return !commitUrls.isEmpty(); }
        public List<String> getCommitUrls() { return commitUrls; }
        public String getChosenVersion() { return chosenVersion; }
    }
}
```

- [ ] **Step 3: Write unit tests**

```java
@ExtendWith(MockitoExtension.class)
class PatchCommitInfererTest {

    @Mock LlmClient llmClient;
    @Mock PromptRegistry promptRegistry;
    PatchCommitInferer inferer;

    @BeforeEach void setUp() {
        inferer = new PatchCommitInferer(llmClient, promptRegistry, "");
    }

    @Test void emptyResultWhenNoGithubRepo() {
        PatchCommitInferer.InferenceResult r = inferer.infer(
                "CVE-2021-42392", "desc", "", Arrays.asList("2.0.206"));
        assertFalse(r.hasResult());
    }

    @Test void emptyResultWhenNoFixedVersions() {
        PatchCommitInferer.InferenceResult r = inferer.infer(
                "CVE-2021-42392", "desc", "https://github.com/h2database/h2database",
                Collections.emptyList());
        assertFalse(r.hasResult());
    }

    @Test void groupsByMajorAscending() {
        // Use reflection to test the private method
        // ... or make it package-private for testing ...
        List<String> result = inferer.groupByMajorAscending(
                Arrays.asList("2.0.206", "1.4.200", "3.5.0"));
        assertEquals(3, result.size());
        assertEquals("1.4.200", result.get(0)); // lowest major first
        assertEquals("2.0.206", result.get(1));
        assertEquals("3.5.0", result.get(2));
    }
}
```

⚠ Note: The `groupByMajorAscending` method must be made package-private or tested via reflection.

- [ ] **Step 4: Run tests**

Run: `cd backend && mvn test -pl jvuln-stages -Dtest=PatchCommitInfererTest -DfailIfNoTests=false -q`
Expected: BUILD SUCCESS (tests that don't hit GitHub API pass; the GitHub-dependent ones are integration-level)

- [ ] **Step 5: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/collector/PatchCommitInferer.java
git add backend/jvuln-stages/src/main/resources/prompts/current/patch-commit-inference.md
git add backend/jvuln-stages/src/test/...
git commit -m "feat(collector): add PatchCommitInferer for LLM-based patch commit selection"
```

---

### Task 5: Hook `PatchCommitInferer` into `IntelligenceStage`

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/collector/IntelligenceStage.java`
- Test: `backend/jvuln-stages/src/test/java/com/jvuln/collector/IntelligenceStageTest.java`

**Background:** After `assembler.merge()` produces the draft, if `fixCommits` is still empty, call `PatchCommitInferer`. On success, populate `fixCommits` with the returned URLs and set `fixedVersion` (singular) to the chosen major's version. On failure, leave both as-is (the existing empty fixCommits path, which Stage 2's maven-diff will handle).

- [ ] **Step 1: Inject `PatchCommitInferer` into `IntelligenceStage`**

```java
private final PatchCommitInferer patchCommitInferer;

public IntelligenceStage(List<IntelSource> sources, SourceCollector sourceCollector,
                          ArticleClassifier articleClassifier,
                          EvidenceCollector evidenceCollector,
                          IntelligenceAssembler assembler,
                          PatchCommitInferer patchCommitInferer) {
    // ... existing assignments ...
    this.patchCommitInferer = patchCommitInferer;
}
```

- [ ] **Step 2: Add inference call after merge, before article classification**

In `execute()`, right after `assembler.merge(cveId, sourceResults)` and before the article classifier:

```java
IntelligenceAssembler.Draft draft = assembler.merge(cveId, sourceResults);

// NEW: LLM patch-commit inference when no fix commits found
if (draft.getFixCommits().isEmpty() && draft.hasSourceRepo()) {
    PatchCommitInferer.InferenceResult inference = patchCommitInferer.infer(
            cveId, draft.getDescription(), draft.getSourceRepo(), draft.getFixedVersions());
    if (inference.hasResult()) {
        for (String url : inference.getCommitUrls()) {
            draft.addFixCommit(url);
        }
        // Set singular fixedVersion to the chosen major's version
        if (inference.getChosenVersion() != null) {
            draft.setFixedVersion(inference.getChosenVersion());
        }
        context.reportProgress("Inferred " + inference.getCommitUrls().size()
                + " patch commit(s) via LLM analysis");
    } else {
        context.reportProgress("Could not infer patch commits; will fall back to maven-source-diff");
    }
}
```

Add helper methods to `Draft`:
```java
public Set<String> getFixCommits() { return Collections.unmodifiableSet(fixCommits); }
public boolean hasSourceRepo() { return sourceRepo != null && !sourceRepo.isEmpty(); }
public void addFixCommit(String url) { fixCommits.add(url); }
public void setFixedVersion(String v) { this.fixedVersion = v; }
```

- [ ] **Step 3: Run tests**

Run: `cd backend && mvn test -pl jvuln-stages -Dtest=IntelligenceStageTest -DfailIfNoTests=false -q`
Expected: BUILD SUCCESS (existing tests pass)

- [ ] **Step 4: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/collector/IntelligenceStage.java
git add backend/jvuln-stages/src/main/java/com/jvuln/collector/IntelligenceAssembler.java
git commit -m "feat(collector): hook PatchCommitInferer into IntelligenceStage"
```

---

### Task 6: Delete keyword-based fallback from `GhsaCommitStrategy`

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/patcher/strategy/GhsaCommitStrategy.java`
- Test: (no existing test for the release-tag path; existing GhsaCommitStrategy tests should still pass)

**Background:** The `locateByReleaseTag()` and `diffFromReleaseTag()` methods (lines 106-202) use keyword heuristics (`fix`/`security`/`cve`/`jndi`/`inject`/`patch`) to pick commits from the tag comparison. This is the direct cause of the CVE-2021-42392 wrong-patch bug. Delete them entirely.

- [ ] **Step 1: Remove the two methods**

Delete from `GhsaCommitStrategy`:
- `private Optional<PatchResult> locateByReleaseTag(String cveId, JsonNode references)` — entire method
- `private Optional<PatchResult> diffFromReleaseTag(String owner, String repo, String fixedTag, String cveId)` — entire method
- The call to `locateByReleaseTag` at the end of `locate()` (line 107: `return locateByReleaseTag(cveId, advisories.path(0).path("references"));`)

After deletion, `locate()` returns `Optional.empty()` naturally when no commit URL yields a diff.

Fix the end of `locate()`:
```java
// Delete this entire block (was the locateByReleaseTag call):
// // Fallback: handle release tag references — compare adjacent tags to find security commits
// return locateByReleaseTag(cveId, advisories.path(0).path("references"));

// Instead, simply:
return Optional.empty();
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend && mvn compile -pl jvuln-stages -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/patcher/strategy/GhsaCommitStrategy.java
git commit -m "fix(patcher): remove keyword-based release-tag fallback from GhsaCommitStrategy"
```

---

### Task 7: Delete keyword-based fallback from `AiPatchSearchStrategy`

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/patcher/strategy/AiPatchSearchStrategy.java`

**Background:** `AiPatchSearchStrategy` has a `diffFromReleaseTag` method (lines 304-362) with the same keyword heuristic, called as step 3 in `locateWithAiHints` (lines 143-161). Delete both the method and the calling code.

- [ ] **Step 1: Remove the `diffFromReleaseTag` method and its call**

Delete the entire `diffFromReleaseTag` method (lines ~304-362):
```java
// DELETE this entire method:
private Optional<PatchResult> diffFromReleaseTag(String owner, String repo,
                                                   String fixedTag, String cveId) throws Exception {
    ...
}
```

Delete step 3 in `locateWithAiHints` (the "Retry release tag comparison" block, lines ~142-161):
```java
// DELETE this entire block from locateWithAiHints:
// 3. Retry release tag comparison with AI-suggested tag
if (enrichment.sourceRepo != null && enrichment.sourceRepo.contains("github.com")
        && enrichment.releaseTag != null) {
    ...
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend && mvn compile -pl jvuln-stages -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/patcher/strategy/AiPatchSearchStrategy.java
git commit -m "fix(patcher): remove keyword-based release-tag fallback from AiPatchSearchStrategy"
```

---

### Task 8: Migrate `fixedVersion` consumers to `fixedVersions`

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/reasoning/ReasoningStage.java` (line 102)
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/patcher/analyzer/VulnerabilityFactResolver.java` (line 125)
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/report/DataExtractor.java` (line 127)

**Background:** Stages 1/2/3 should use `fixedVersions` (the complete list) rather than the singular `fixedVersion`, since the singular is now the Stage-4-only anchor. These three consumers currently read `fixedVersion`.

- [ ] **Step 1: Update `ReasoningStage.trimIntelligence`**

Add `fixedVersions` to the fields copied into the trimmed JSON:
```java
private String trimIntelligence(Object data) throws Exception {
    JsonNode root = mapper.valueToTree(data);
    ObjectNode out = mapper.createObjectNode();
    copyField(root, out, "cveId");
    copyField(root, out, "cweId");
    copyField(root, out, "description");
    copyField(root, out, "cvss");
    copyField(root, out, "fixedVersion");   // keep for backward compat
    copyField(root, out, "fixedVersions");  // NEW
    copyField(root, out, "artifact");
    copyField(root, out, "fixCommits");
    copyField(root, out, "affectedVersions");
    return mapper.writeValueAsString(out);
}
```

- [ ] **Step 2: Update `VulnerabilityFactResolver.buildStage1Claim`**

```java
private ObjectNode buildStage1Claim(JsonNode s1) {
    ObjectNode claim = mapper.createObjectNode();
    copy(s1, claim, "description");
    copy(s1, claim, "cweId");
    copy(s1, claim, "artifact");
    copy(s1, claim, "affectedVersions");
    copy(s1, claim, "fixedVersion");    // keep for backward compat
    copy(s1, claim, "fixedVersions");   // NEW
    claim.put("claimedType", inferClaimedType(s1));
    return claim;
}
```

- [ ] **Step 3: Update `report/DataExtractor`**

Read the file to find the exact copyField call. Similar change: add `copyField(root, out, "fixedVersions");` alongside the existing `copyField(root, out, "fixedVersion");`.

- [ ] **Step 4: Compile and run tests**

```bash
cd backend && mvn test -pl jvuln-stages -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/reasoning/ReasoningStage.java
git add backend/jvuln-stages/src/main/java/com/jvuln/patcher/analyzer/VulnerabilityFactResolver.java
git add backend/jvuln-stages/src/main/java/com/jvuln/generator/report/DataExtractor.java
git commit -m "feat: add fixedVersions to reasoning, fact resolver, and report outputs"
```

---

### Task 9: Stage 4 — use singular `fixedVersion` as demo anchor

**Files:**
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/StageDataExtractor.java`
- Modify: `backend/jvuln-stages/src/main/java/com/jvuln/generator/JavaProfileResolver.java`

**Background:** Stage 4 currently picks the demo version via a heuristic (`affectedVersions.to` if it differs from `fixedVersion`, else `from`). Now that `fixedVersion` (singular) holds the chosen major's fix version, Stage 4 should prefer using `fixedVersion` to determine which major line to build against.

- [ ] **Step 1: Update `resolveVulnerableVersion`**

```java
/**
 * Resolve the version to use when building the vulnerable demo project.
 * - If the singular fixedVersion is non-empty and its major matches the
 *   affectedVersions.to major, return affectedVersions.to (the last affected
 *   version on the same line).
 * - If fixedVersion is set but to is from a different major, infer the
 *   highest vulnerable version on fixedVersion's line via Maven metadata.
 * - Fall back to the existing to/from heuristic.
 */
String resolveVulnerableVersion(JsonNode intel) {
    JsonNode affectedVersions = intel.path("affectedVersions");
    String to = affectedVersions.path("to").asText(null);
    String from = affectedVersions.path("from").asText(null);
    String fixedVersion = intel.path("fixedVersion").asText(null);

    if (fixedVersion != null && !fixedVersion.isEmpty()) {
        // fixedVersion is the Stage 4 anchor — use it to decide the major line
        String fixedMajor = extractMajor(fixedVersion);
        if (to != null && extractMajor(to) != null && extractMajor(to).equals(fixedMajor)) {
            return to; // same major line → use 'to'
        }
        // Different major lines; fall through to heuristic below
    }
    // Original heuristic: prefer 'to' if it differs from fixedVersion
    if (to != null && !to.equals(fixedVersion)) {
        return to;
    }
    return from;
}

/** Extract "X.Y" major.minor prefix for line-matching. */
private String extractMajor(String version) {
    if (version == null) return null;
    Matcher m = Pattern.compile("(\\d+\\.\\d+)").matcher(version);
    return m.find() ? m.group(1) : null;
}
```

- [ ] **Step 2: Update `JavaProfileResolver.resolveJavaProfile` to log `fixedVersion`**

No logic change needed — it already reads `fixedVersion` from the intel. Just add logging context:
```java
String fixedVersion = intel.at("/fixedVersion").asText("");
log.info("Using fixedVersion as demo anchor: {}", fixedVersion);
```

- [ ] **Step 3: Run tests**

```bash
cd backend && mvn test -pl jvuln-stages -Dtest=StageDataExtractorTraceTargetTest -DfailIfNoTests=false -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/jvuln-stages/src/main/java/com/jvuln/generator/StageDataExtractor.java
git add backend/jvuln-stages/src/main/java/com/jvuln/generator/JavaProfileResolver.java
git commit -m "feat(generator): prefer fixedVersion as Stage 4 demo version anchor"
```
