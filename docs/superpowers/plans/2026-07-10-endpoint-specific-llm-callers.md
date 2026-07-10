# Endpoint-Specific LLM Callers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace current-level prompt concatenation and provider/model protocol guessing with explicit endpoint routing across Chat Completions, Responses, and Messages callers.

**Architecture:** `LlmRequest` contains only stage, task prompt, history, tools, and options. `OpenAiCompatClient` resolves global/stage resources into an immutable `LlmCall`, then `LlmCallerFactory` selects one of three protocol callers solely from the configured endpoint. Each caller owns its protocol request/response/SSE mapping and returns the shared `LlmResponse` model.

**Tech Stack:** Java 8, Spring Boot 2.7 WebClient/Reactor, Jackson, JUnit 5, JDK `HttpServer`, Vue 3/TypeScript, Element Plus.

---

### Task 1: Separate Prompt Context From Task Requests

**Files:**
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/PromptContext.java`
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmCall.java`
- Modify: `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmRequest.java`
- Modify: `backend/jvuln-utils/src/main/java/com/jvuln/llm/PromptManager.java`
- Test: `backend/jvuln-utils/src/test/java/com/jvuln/llm/LlmRequestTest.java`
- Test: `backend/jvuln-utils/src/test/java/com/jvuln/llm/PromptManagerTest.java`

- [ ] **Step 1: Write failing request and prompt-context tests**

Replace current-layer assertions with the desired API:

```java
@Test
void agentRequestExposesTaskPromptAndPreservesOptions() {
    LlmRequest request = LlmRequest.agent(LlmPromptStage.ARTIFACT_GENERATION,
            "task", messages, tools);
    assertEquals("task", request.getTaskPrompt());
    assertSame(messages, request.getMessages());
    assertSame(tools, request.getTools());
    assertEquals("auto", request.getToolChoice());
}

@Test
void resolvesGlobalAndStageAsSeparateValues() {
    PromptContext context = promptManager.resolve(LlmPromptStage.REASONING);
    assertEquals("global", context.getGlobalPrompt());
    assertEquals("reasoning", context.getStagePrompt());
}

@Test
void diagnosticContextContainsGlobalWithoutStage() {
    PromptContext context = promptManager.resolve(null);
    assertEquals("global", context.getGlobalPrompt());
    assertNull(context.getStagePrompt());
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `mvn -pl jvuln-utils -Dtest=LlmRequestTest,PromptManagerTest test`

Expected: compilation fails because `getTaskPrompt`, `PromptContext`, and `PromptManager.resolve(stage)` do not exist.

- [ ] **Step 3: Implement immutable task and prompt models alongside temporary compatibility APIs**

`PromptContext` has final `globalPrompt` and nullable `stagePrompt`. `LlmCall` has final `LlmRequest request` and `PromptContext promptContext`, rejects null constructor arguments, and exposes getters. Add `taskPrompt` and `getTaskPrompt`, and make all factories use task naming. Keep the old current/system accessors and old two-argument `PromptManager.resolve` only as deprecated compatibility paths until Task 6 switches the client and deletes the old callers; this keeps the focused test cycle compilable. Keep the diagnostic factory as the only stage-null request path.

`PromptManager.resolve` becomes:

```java
public PromptContext resolve(LlmPromptStage stage) {
    String global = readRequired(GLOBAL_RESOURCE);
    String stagePrompt = stage == null ? null : readRequired(stage.getResourcePath());
    return new PromptContext(global, stagePrompt);
}
```

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `mvn -pl jvuln-utils -Dtest=LlmRequestTest,PromptManagerTest test`

Expected: all request and prompt-manager tests pass.

### Task 2: Add Explicit Endpoint Configuration and URI Resolution

**Files:**
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmEndpoint.java`
- Modify: `backend/jvuln-utils/src/main/java/com/jvuln/llm/impl/LlmConfigProvider.java`
- Test: `backend/jvuln-utils/src/test/java/com/jvuln/llm/LlmEndpointTest.java`

- [ ] **Step 1: Write failing endpoint tests**

```java
@Test
void acceptsOnlyCanonicalEndpointPaths() {
    assertEquals(LlmEndpoint.RESPONSES, LlmEndpoint.fromPath("/v1/responses"));
    assertThrows(IllegalArgumentException.class, () -> LlmEndpoint.fromPath("responses"));
}

@Test
void replacesKnownSuffixWhenResolvingUri() {
    assertEquals("https://proxy.example/openai/v1/responses",
            LlmEndpoint.RESPONSES.resolveUri("https://proxy.example/openai/v1/chat/completions"));
}

@Test
void preservesProxyPrefixWhenBaseEndsInV1() {
    assertEquals("https://proxy.example/openai/v1/messages",
            LlmEndpoint.MESSAGES.resolveUri("https://proxy.example/openai/v1"));
}
```

- [ ] **Step 2: Run endpoint tests and verify RED**

Run: `mvn -pl jvuln-utils -Dtest=LlmEndpointTest test`

Expected: compilation fails because `LlmEndpoint` does not exist.

- [ ] **Step 3: Implement endpoint enum and config field**

Define `CHAT_COMPLETIONS`, `RESPONSES`, and `MESSAGES` enum constants with exact paths. `fromPath` rejects null, blank, and non-canonical values. `resolveUri` trims trailing slashes, removes any known endpoint suffix or one terminal `/v1`, preserves any preceding proxy prefix, then appends the selected path.

Add `endpoint` as the fifth constructor argument and getter on `LlmConfigProvider.ActiveConfig`. Keep a deprecated four-argument constructor defaulting to Chat Completions only until all app configuration code is migrated in Task 7:

```java
public ActiveConfig(String providerType, String baseUrl, String apiKey,
                    String model, String endpoint) { ... }
public String getEndpoint() { return endpoint; }
```

- [ ] **Step 4: Run endpoint tests and verify GREEN**

Run: `mvn -pl jvuln-utils -Dtest=LlmEndpointTest test`

Expected: endpoint tests pass.

### Task 3: Introduce Protocol Caller Contract and Chat Completions Caller

**Files:**
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmProtocolCaller.java`
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/AbstractLlmCaller.java`
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/ChatCaller.java`
- Create: `backend/jvuln-utils/src/test/java/com/jvuln/llm/CallerTestServer.java`
- Create: `backend/jvuln-utils/src/test/java/com/jvuln/llm/ChatCallerTest.java`

- [ ] **Step 1: Write failing Chat request/response tests using a real local HTTP server**

The server captures method, path, headers, and JSON body and returns queued JSON/SSE fixtures. Assert:

```java
assertEquals("/v1/chat/completions", server.getLastPath());
assertEquals("system", body.path("messages").path(0).path("role").asText());
assertEquals("global", body.path("messages").path(0).path("content").asText());
assertEquals("developer", body.path("messages").path(1).path("role").asText());
assertEquals("stage", body.path("messages").path(1).path("content").asText());
assertEquals("user", body.path("messages").path(2).path("role").asText());
assertEquals("task", body.path("messages").path(2).path("content").asText());
```

Use a response fixture with text, `tool_calls`, `prompt_tokens`, `completion_tokens`, model, and finish reason; assert every value is normalized into `LlmResponse`. Add a stream fixture containing two `chat.completion.chunk` deltas and assert `chatStream(...).collectList().block()` equals `['hel', 'lo']`.

- [ ] **Step 2: Run Chat tests and verify RED**

Run: `mvn -pl jvuln-utils -Dtest=ChatCallerTest test`

Expected: compilation fails because `ChatCaller` and the new caller contract do not exist.

- [ ] **Step 3: Implement the protocol caller contract and Chat protocol**

Keep the old concrete `LlmCaller` temporarily and add the new internal contract:

```java
public interface LlmProtocolCaller {
    LlmResponse chat(LlmCall call);
    Flux<String> chatStream(LlmCall call);
    String getName();
}
```

`AbstractLlmCaller` owns the configured model, mapper, resolved URI, WebClient creation, authorization header, timeout, retry-aware JSON POST, SSE POST, response truncation, and JSON argument parsing. `ChatCaller` implements `LlmProtocolCaller`, maps global/system, stage/developer, task/user, then history; keeps Chat tool/history shapes; uses non-stream JSON for `chat` and Chat SSE delta parsing for `chatStream`.

- [ ] **Step 4: Run Chat tests and verify GREEN**

Run: `mvn -pl jvuln-utils -Dtest=ChatCallerTest test`

Expected: Chat request, response, tool, and SSE tests pass.

### Task 4: Implement Responses Caller

**Files:**
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/ResponsesCaller.java`
- Create: `backend/jvuln-utils/src/test/java/com/jvuln/llm/ResponsesCallerTest.java`

- [ ] **Step 1: Write failing Responses protocol tests**

Assert the request has top-level `instructions=global`, followed by typed developer and user message items with `input_text`. Assert function tools are flat:

```java
assertEquals("function", body.path("tools").path(0).path("type").asText());
assertEquals("lookup", body.path("tools").path(0).path("name").asText());
assertTrue(body.path("tools").path(0).has("parameters"));
assertFalse(body.path("tools").path(0).has("function"));
```

Return `output` containing an `output_text` message and a `function_call`; assert text, call id/name/JSON arguments, input/output tokens, model, and `tool_calls` finish reason. Add typed SSE fixtures for `response.output_text.delta`, `response.completed`, and `response.failed`.

- [ ] **Step 2: Run Responses tests and verify RED**

Run: `mvn -pl jvuln-utils -Dtest=ResponsesCallerTest test`

Expected: compilation fails because `ResponsesCaller` does not exist.

- [ ] **Step 3: Implement Responses request, response, and typed SSE mapping**

Use `max_output_tokens`, `text.format` for JSON mode, flat function tools, and Responses history items (`message`, `function_call`, `function_call_output`). Parse non-stream `output` in order. Map completed text to `stop`, any function call to `tool_calls`, incomplete to its reason, and failed to `error`. Stream only `response.output_text.delta`; throw on error/failed events.

- [ ] **Step 4: Run Responses tests and verify GREEN**

Run: `mvn -pl jvuln-utils -Dtest=ResponsesCallerTest test`

Expected: all Responses tests pass.

### Task 5: Add Messages Caller

**Files:**
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/MessagesCaller.java`
- Create: `backend/jvuln-utils/src/test/java/com/jvuln/llm/MessagesCallerTest.java`

- [ ] **Step 1: Write failing Messages protocol tests**

Assert `anthropic-version`, `x-api-key`, `/v1/messages`, two top-level system text blocks, task as user, and task merging with a following user message. Assert `input_schema`, `tool_use`, and `tool_result` shapes. Return a normal Messages response with text/tool blocks and usage, then test `content_block_delta` SSE text events and an SSE error event.

- [ ] **Step 2: Run Messages tests and verify RED**

Run: `mvn -pl jvuln-utils -Dtest=MessagesCallerTest test`

Expected: compilation fails because `MessagesCaller` does not exist.

- [ ] **Step 3: Implement Messages request, response, and SSE mapping**

Extend shared HTTP support with Messages headers. Serialize global and optional stage as separate top-level system content blocks. Add task as the first user content block and merge adjacent equal roles while preserving content blocks. `chat` uses `stream=false` and parses normal Messages JSON; `chatStream` uses `stream=true` and emits only `text_delta` values while surfacing error events.

- [ ] **Step 4: Run Messages tests and verify GREEN**

Run: `mvn -pl jvuln-utils -Dtest=MessagesCallerTest test`

Expected: all Messages tests pass.

### Task 6: Route the Unified Client Solely by Endpoint

**Files:**
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmCallerFactory.java`
- Modify: `backend/jvuln-utils/src/main/java/com/jvuln/llm/impl/OpenAiCompatClient.java`
- Delete: `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmCaller.java`
- Delete: `backend/jvuln-utils/src/main/java/com/jvuln/llm/impl/AnthropicCaller.java`
- Delete: `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmAdapter.java`
- Delete: `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmAdapterFactory.java`
- Delete: `backend/jvuln-utils/src/main/java/com/jvuln/llm/OpenAICompatibleAdapter.java`
- Delete: `backend/jvuln-utils/src/main/java/com/jvuln/llm/AnthropicAdapter.java`
- Replace test: `backend/jvuln-utils/src/test/java/com/jvuln/llm/LlmAdapterFactoryTest.java` -> `LlmCallerFactoryTest.java`
- Delete test: `backend/jvuln-utils/src/test/java/com/jvuln/llm/OpenAICompatibleAdapterTest.java`
- Delete test: `backend/jvuln-utils/src/test/java/com/jvuln/llm/AnthropicAdapterTest.java`
- Modify test: `backend/jvuln-utils/src/test/java/com/jvuln/llm/impl/OpenAiCompatClientTest.java`

- [ ] **Step 1: Write failing factory and client tests**

Assert each exact endpoint creates the corresponding caller even when provider/model names disagree. Assert missing/unknown endpoint fails. Use a capturing `LlmProtocolCaller` to verify the client passes `PromptContext(global, stage)` and each request's own task. Verify `chatStream` follows the same path and `chat(activeConfig, request)` uses the explicit config supplied by the configuration test flow.

- [ ] **Step 2: Run factory/client tests and verify RED**

Run: `mvn -pl jvuln-utils -Dtest=LlmCallerFactoryTest,OpenAiCompatClientTest test`

Expected: tests fail because endpoint-only factory/client routing is absent.

- [ ] **Step 3: Implement factory/client routing and remove adapters**

`LlmCallerFactory.createCaller(config, mapper)` returns `LlmProtocolCaller` and switches only on `LlmEndpoint.fromPath(config.getEndpoint())`. `OpenAiCompatClient` resolves `new LlmCall(request, promptManager.resolve(request.getStage()))`, delegates to the selected caller, and exposes an explicit-config overload for connectivity tests. Add fallback injection for `jvuln.llm.endpoint` with `/v1/chat/completions` default. Once the client is switched, remove `getCurrentSystemPrompt`, `getSystemPrompt`, `withResolvedSystemPrompt`, the old two-argument prompt resolver, concrete old `LlmCaller`, `AnthropicCaller`, and all Adapter classes.

- [ ] **Step 4: Run all utils tests and verify GREEN**

Run: `mvn -pl jvuln-utils test`

Expected: all utils tests pass and no old Adapter tests/classes remain.

### Task 7: Persist, Validate, and Test Endpoint Configuration

**Files:**
- Modify: `backend/jvuln-utils/src/main/java/com/jvuln/store/entity/LlmConfig.java`
- Modify: `backend/jvuln-app/src/main/java/com/jvuln/config/DbLlmConfigProvider.java`
- Modify: `backend/jvuln-app/src/main/java/com/jvuln/controller/ConfigController.java`
- Modify: `backend/jvuln-app/src/main/resources/application.yml`
- Modify: `backend/jvuln-app/pom.xml`
- Create test: `backend/jvuln-app/src/test/java/com/jvuln/config/DbLlmConfigProviderTest.java`
- Create test: `backend/jvuln-app/src/test/java/com/jvuln/controller/ConfigControllerTest.java`

- [ ] **Step 1: Add app test dependency and write failing configuration tests**

Add `spring-boot-starter-test` with test scope. Test that an active entity with endpoint `/v1/responses` produces `ActiveConfig.getEndpoint()` unchanged; active records with null/unknown endpoints throw instead of returning null. Test controller create/copy and config-test paths preserve endpoint, and that activate/test reject an invalid endpoint before sending HTTP.

- [ ] **Step 2: Run app tests and verify RED**

Run: `mvn -pl jvuln-app -am -Dtest=DbLlmConfigProviderTest,ConfigControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: tests fail because the entity/provider/controller do not expose or validate endpoint.

- [ ] **Step 3: Implement endpoint persistence and shared test routing**

Add `@Column(name="endpoint", length=100)` plus getter/setter/toString field. `DbLlmConfigProvider` validates the active endpoint with `LlmEndpoint.fromPath` and passes it into `ActiveConfig`. `ConfigController` copies/validates endpoint on create, update, activate, and test; injects `OpenAiCompatClient`; and calls its explicit-config overload instead of directly constructing a caller. Remove the temporary four-argument `ActiveConfig` constructor after all call sites use the endpoint. Add:

```yaml
jvuln:
  llm:
    endpoint: ${LLM_ENDPOINT:/v1/chat/completions}
```

- [ ] **Step 4: Run app tests and verify GREEN**

Run: `mvn -pl jvuln-app -am -Dtest=DbLlmConfigProviderTest,ConfigControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: focused configuration tests pass.

### Task 8: Expose Endpoint in the Settings UI

**Files:**
- Modify: `frontend/src/api/index.ts`
- Modify: `frontend/src/views/Settings.vue`
- Modify: `frontend/src/locales/en-US.ts`
- Modify: `frontend/src/locales/zh-CN.ts`

- [ ] **Step 1: Add the endpoint type and form state**

Add the required union to `LlmConfig`:

```ts
endpoint: '/v1/chat/completions' | '/v1/responses' | '/v1/messages'
```

Add endpoint to `emptyForm` and `openEdit`. The frontend currently has no unit-test runner, so this task uses the TypeScript/Vue production build as its executable verification rather than adding a new test framework solely for one settings field.

- [ ] **Step 2: Implement endpoint selection, list display, presets, and validation**

Use an `el-select` with the three exact paths; show endpoint as its own table column. Defaults: OpenAI-compatible/Ollama/DeepSeek -> Chat Completions, OpenAI -> Responses, Anthropic -> Messages. Require endpoint alongside base URL and model and add `endpoint`/validation translations in both locale files.

- [ ] **Step 3: Install locked dependencies if absent and verify the frontend build**

Run: `test -d node_modules || npm ci`

Run: `npm run build`

Expected: `vue-tsc` and Vite complete successfully.

### Task 9: Migrate Task Naming and Verify the Whole Branch

**Files:**
- Modify: current LLM call sites under `backend/jvuln-stages/src/main/java/com/jvuln/**`
- Preserve unchanged: `backend/jvuln-stages/src/main/resources/prompts/current/*.md`

- [ ] **Step 1: Rename local current/system prompt variables to task semantics**

At every `LlmRequest` construction, rename the value passed as the second argument to `taskPrompt`. Do not move, edit, or delete any `prompts/current/*.md` resource. Search must return no Java current-level APIs:

Run: `rg -n "getCurrentSystemPrompt|getSystemPrompt|withResolvedSystemPrompt|currentSystemPrompt" backend --glob '*.java'`

Expected: no matches.

- [ ] **Step 2: Run source-size and method-size checks**

Run: `find backend frontend/src -type f \( -name '*.java' -o -name '*.ts' -o -name '*.vue' \) -size +80k -print`

Expected: no files printed. Review changed methods to ensure each remains below 256 lines.

- [ ] **Step 3: Run complete backend and frontend verification**

Run: `mvn test`

Run: `npm run build`

Expected: backend reactor reports BUILD SUCCESS with zero test failures; frontend TypeScript/Vite build succeeds.

- [ ] **Step 4: Review diff against every specification requirement**

Run: `git diff --check && git status --short`

Confirm endpoint-only routing, all three prompt mappings, ordinary and SSE responses, tool calls, configuration UI, retained current resource files, no secret logging, and no unrelated changes.

- [ ] **Step 5: Commit and push `clear-prompt`**

```bash
git add backend frontend docs/superpowers/plans/2026-07-10-endpoint-specific-llm-callers.md
git commit -m "feat: add endpoint-specific LLM callers"
git push origin clear-prompt
```
