# Unified LLM Prompt Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Make every LLM request combine Markdown-backed global, pipeline-stage, and request-current system prompts in one utils-layer client path.

**Architecture:** LlmRequest declares one of four LlmPromptStage values and its current system prompt. PromptManager loads and caches Markdown resources from the runtime classpath, combines the three prompt layers, and OpenAiCompatClient resolves requests before delegating to any provider adapter. Stage code only supplies its stage enum and a current prompt/template.

**Tech Stack:** Java 8, Spring Boot 2.7, JUnit 5, Maven, Jackson, Reactor

---

## File structure

- Create backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmPromptStage.java: four pipeline stage keys and resource paths.
- Create backend/jvuln-utils/src/main/java/com/jvuln/llm/PromptManager.java: cached classpath Markdown reader and prompt combiner.
- Modify backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmRequest.java: stage/current prompt metadata and immutable resolved copy.
- Modify backend/jvuln-utils/src/main/java/com/jvuln/llm/impl/OpenAiCompatClient.java: resolve before chat and chatStream.
- Modify backend/jvuln-utils/src/main/java/com/jvuln/llm/PromptRegistry.java: keep reusable Markdown template loading/rendering for call-specific current prompts.
- Create tests in backend/jvuln-utils/src/test/java/com/jvuln/llm and runtime resources in backend/jvuln-stages/src/main/resources/prompts.
- Modify every LLM call in backend/jvuln-stages to pass LlmPromptStage and a current prompt.

### Task 1: Add stage metadata and resolved request copies

**Files:**
- Create: backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmPromptStage.java
- Modify: backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmRequest.java
- Test: backend/jvuln-utils/src/test/java/com/jvuln/llm/LlmRequestTest.java

- [ ] **Step 1: Write the failing API tests**

~~~java
@Test
void resolvedCopyPreservesRequestOptionsAndKeepsCurrentPrompt() {
    LlmRequest request = LlmRequest.agent(
            LlmPromptStage.ARTIFACT_GENERATION, "current", messages, tools);
    LlmRequest resolved = request.withResolvedSystemPrompt("global\n\nstage\n\ncurrent");

    assertEquals(LlmPromptStage.ARTIFACT_GENERATION, resolved.getStage());
    assertEquals("current", resolved.getCurrentSystemPrompt());
    assertEquals("global\n\nstage\n\ncurrent", resolved.getSystemPrompt());
    assertSame(messages, resolved.getMessages());
    assertSame(tools, resolved.getTools());
    assertEquals("auto", resolved.getToolChoice());
}

@Test
void reasoningRequestRequiresStage() {
    assertThrows(IllegalArgumentException.class,
            () -> LlmRequest.reasoning(null, "current", "user"));
}
~~~

- [ ] **Step 2: Run RED**

Run: mvn test -pl jvuln-utils -Dtest=LlmRequestTest

Expected: compilation fails because the stage enum, factory overload, and resolved-copy method are absent.

- [ ] **Step 3: Implement the minimal immutable API**

~~~java
public enum LlmPromptStage {
    INTELLIGENCE("intelligence"),
    PATCH_ANALYSIS("patch-analysis"),
    REASONING("reasoning"),
    ARTIFACT_GENERATION("artifact-generation");

    private final String resourceName;
    LlmPromptStage(String resourceName) { this.resourceName = resourceName; }
    public String getResourcePath() { return "prompts/stages/" + resourceName + ".md"; }
}

public LlmRequest withResolvedSystemPrompt(String resolvedSystemPrompt) {
    return new LlmRequest(stage, currentSystemPrompt, resolvedSystemPrompt, messages,
            temperature, maxTokens, jsonMode, tools, toolChoice);
}
~~~

Store stage, currentSystemPrompt, and the final systemPrompt separately. Require a non-null stage in all reasoning, generation, and agent factories. The copy must retain messages, temperature, max tokens, JSON mode, tools, and tool choice.

- [ ] **Step 4: Run GREEN**

Run: mvn test -pl jvuln-utils -Dtest=LlmRequestTest

Expected: PASS.

### Task 2: Compose Markdown prompt layers

**Files:**
- Create: backend/jvuln-utils/src/main/java/com/jvuln/llm/PromptManager.java
- Create: backend/jvuln-utils/src/test/java/com/jvuln/llm/PromptManagerTest.java
- Create: five test Markdown resources below backend/jvuln-utils/src/test/resources/prompts.

- [ ] **Step 1: Write failing composition tests**

~~~java
@Test
void combinesGlobalStageAndCurrentInThatOrder() {
    assertEquals("global\n\nreasoning\n\ncurrent",
            promptManager.resolve(LlmPromptStage.REASONING, "current"));
}

@Test
void omitsBlankCurrentPromptWithoutTrailingSeparator() {
    assertEquals("global\n\nreasoning",
            promptManager.resolve(LlmPromptStage.REASONING, "  "));
}

@Test
void reportsMissingResourcePath() {
    PromptManager missing = new PromptManager(new DefaultResourceLoader(), "missing/");
    IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> missing.resolve(LlmPromptStage.REASONING, "current"));
    assertTrue(ex.getMessage().contains("missing/prompts/global.md"));
}
~~~

- [ ] **Step 2: Run RED**

Run: mvn test -pl jvuln-utils -Dtest=PromptManagerTest

Expected: compilation fails because PromptManager does not exist.

- [ ] **Step 3: Implement the classpath-only manager**

~~~java
@Component
public class PromptManager {
    private static final String GLOBAL_RESOURCE = "prompts/global.md";
    private final ResourceLoader resourceLoader;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String resolve(LlmPromptStage stage, String current) {
        if (stage == null) throw new IllegalStateException("LLM prompt stage is required");
        List<String> layers = new ArrayList<>();
        layers.add(readRequired(GLOBAL_RESOURCE));
        layers.add(readRequired(stage.getResourcePath()));
        if (current != null && !current.trim().isEmpty()) layers.add(current.trim());
        return String.join("\n\n", layers);
    }
}
~~~

Use ResourceLoader with classpath: paths, UTF-8, try-with-resources, and a ConcurrentHashMap cache. Missing or unreadable files become IllegalStateException containing the resource path. Test resources contain the literal values global, intelligence, patch-analysis, reasoning, and artifact-generation.

- [ ] **Step 4: Run GREEN**

Run: mvn test -pl jvuln-utils -Dtest=PromptManagerTest

Expected: PASS.

### Task 3: Resolve in the only client entry point

**Files:**
- Modify: backend/jvuln-utils/src/main/java/com/jvuln/llm/impl/OpenAiCompatClient.java
- Create: backend/jvuln-utils/src/test/java/com/jvuln/llm/impl/OpenAiCompatClientTest.java

- [ ] **Step 1: Write the failing client behavior test**

~~~java
@Test
void requestCurrentPromptsDoNotLeakBetweenCalls() {
    CapturingAdapter adapter = new CapturingAdapter();
    OpenAiCompatClient client = clientUsing(adapter, promptManager);

    client.chat(LlmRequest.reasoning(LlmPromptStage.REASONING, "one", "user"));
    assertEquals("global\n\nreasoning\n\none", adapter.lastRequest.getSystemPrompt());

    client.chat(LlmRequest.reasoning(LlmPromptStage.REASONING, "two", "user"));
    assertEquals("global\n\nreasoning\n\ntwo", adapter.lastRequest.getSystemPrompt());
}
~~~

Supply a package-visible test constructor or a factory seam that uses a capturing LlmAdapter while the production constructor keeps config-based selection.

- [ ] **Step 2: Run RED**

Run: mvn test -pl jvuln-utils -Dtest=OpenAiCompatClientTest

Expected: compilation fails because the client cannot accept PromptManager or resolve a request.

- [ ] **Step 3: Implement both resolution paths**

~~~java
public LlmResponse chat(LlmRequest request) {
    return getAdapter().chat(resolveRequest(request));
}

public Flux<String> chatStream(LlmRequest request) {
    return getAdapter().chatStream(resolveRequest(request));
}

private LlmRequest resolveRequest(LlmRequest request) {
    return request.withResolvedSystemPrompt(
            promptManager.resolve(request.getStage(), request.getCurrentSystemPrompt()));
}
~~~

Inject PromptManager into the Spring constructor. Do not modify LlmAdapter, LlmCaller, AnthropicCaller, or provider request serialization.

- [ ] **Step 4: Run GREEN**

Run: mvn test -pl jvuln-utils -Dtest=LlmRequestTest,PromptManagerTest,OpenAiCompatClientTest

Expected: PASS.

### Task 4: Move all prompt material to stage-owned Markdown resources

**Files:**
- Create: backend/jvuln-stages/src/main/resources/prompts/global.md
- Create: four files under backend/jvuln-stages/src/main/resources/prompts/stages/
- Create: current prompt/template Markdown files under backend/jvuln-stages/src/main/resources/prompts/current/
- Modify: backend/jvuln-utils/src/main/java/com/jvuln/llm/PromptRegistry.java
- Delete: eight backend/jvuln-app/src/main/resources/prompts/*.txt files after migration.

- [ ] **Step 1: Write the failing stage-classpath test**

~~~java
@Test
void everyPipelineStageHasAResolvablePromptLayer() {
    PromptManager manager = new PromptManager(new DefaultResourceLoader());
    for (LlmPromptStage stage : LlmPromptStage.values()) {
        assertFalse(manager.resolve(stage, "current").trim().isEmpty());
    }
}
~~~

- [ ] **Step 2: Run RED**

Run: mvn test -pl jvuln-stages -am -Dtest=StagePromptResourcesTest

Expected: FAIL because global.md and the four stage Markdown files do not exist.

- [ ] **Step 3: Migrate exact prompt text**

Copy the exact content of each existing app resource to a current Markdown file:

~~~text
system_reasoning.txt              -> current/reasoning-system.md
user_reasoning.txt                -> current/reasoning-user.md
system_gen_agent.txt              -> current/artifact-agent-system.md
user_gen_agent.txt                -> current/artifact-agent-user.md
system_gen_verifier.txt           -> current/artifact-verifier-system.md
user_gen_verifier.txt             -> current/artifact-verifier-user.md
system_gen_verification_plan.txt  -> current/artifact-verification-plan-system.md
user_gen_verification_plan.txt    -> current/artifact-verification-plan-user.md
~~~

Move all current hard-coded system strings from the collector, patcher, and generator classes into same-purpose files in current/. Adapt PromptRegistry to load classpath:prompts/<name>.md in UTF-8 and expose getPrompt(String name) plus render. Add non-empty global.md and all four stage files; stage files contain only pipeline-wide guidance, not substep requirements.

- [ ] **Step 4: Run GREEN**

Run: mvn test -pl jvuln-stages -am -Dtest=StagePromptResourcesTest

Expected: PASS.

### Task 5: Migrate every LLM call to stage plus current

**Files:**
- Modify: collector/ArticleClassifier.java and collector/DescriptionCorrector.java
- Modify: patcher/analyzer/DiffRelevanceFilter.java, VulnerabilityFactResolver.java, AnalysisRelevanceFilter.java, and patcher/strategy/AiPatchSearchStrategy.java
- Modify: reasoning/ReasoningStage.java
- Modify: generator/AgentPhaseEngine.java, JavaProfileResolver.java, ReviewEngine.java, and ArtifactGenStage.java

- [ ] **Step 1: Write a failing migration guard**

Run this acceptance search before changes:

~~~bash
rg -n --glob '*.java' 'LlmRequest\.(reasoning|generation|agent)\([^\n]*SYSTEM_PROMPT|new LlmRequest\(\s*(SYSTEM_PROMPT|systemPrompt)' backend/jvuln-stages/src/main/java
~~~

Expected: it reports current legacy call sites.

- [ ] **Step 2: Make every call explicit**

Use these mappings at every call site:

~~~java
LlmRequest.reasoning(LlmPromptStage.INTELLIGENCE, currentPrompt, userPrompt);
LlmRequest.reasoning(LlmPromptStage.PATCH_ANALYSIS, currentPrompt, userPrompt);
LlmRequest.reasoning(LlmPromptStage.REASONING, currentPrompt, userPrompt);
LlmRequest.agent(LlmPromptStage.ARTIFACT_GENERATION, currentPrompt, messages, tools);
~~~

ArticleClassifier and DescriptionCorrector use INTELLIGENCE. The four patch-analysis callers use PATCH_ANALYSIS. ReasoningStage uses REASONING. ArtifactGenStage, AgentPhaseEngine, JavaProfileResolver, ReviewEngine, and verification-plan generation use ARTIFACT_GENERATION. Load the migrated Markdown content through PromptRegistry.getPrompt and pass it as current; delete old SYSTEM_PROMPT constants and buildSystemPrompt methods.

- [ ] **Step 3: Run the migration guard and stage tests**

Run: the search above, then mvn test -pl jvuln-stages -am

Expected: the search has no output and module tests PASS.

### Task 6: Verify and commit

**Files:**
- Delete: the old app .txt resources.
- Modify: only files required to correct failures found by test execution.

- [ ] **Step 1: Confirm no legacy prompt API remains**

Run:

~~~bash
rg -n 'getSystemPrompt|getUserPrompt|prompts/.*\.txt|new LlmRequest\(' backend
~~~

Expected: no production references.

- [ ] **Step 2: Run complete verification**

Run: mvn test -pl jvuln-utils,jvuln-stages -am

Expected: BUILD SUCCESS.

- [ ] **Step 3: Check scope and whitespace**

Run: git diff --check && git status --short

Expected: no whitespace error; do not touch the pre-existing backend/jvuln-stages/src/main/java/com/jvuln/collector/.IntelligenceStage.java.swp.

- [ ] **Step 4: Commit**

~~~bash
git add backend/jvuln-utils backend/jvuln-stages backend/jvuln-app/src/main/resources/prompts
git commit -m "feat: unify LLM prompt management"
~~~

Expected: the commit contains only prompt-management implementation, tests, Markdown resources, and old-resource removals.


