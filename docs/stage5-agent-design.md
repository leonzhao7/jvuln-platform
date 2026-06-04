# Stage 5 — Tool-Use Agent Design Document

**Version**: 2.0  
**Last Updated**: 2026-06-05  
**Purpose**: Architectural reference for code review, onboarding, and future modification.

---

## 1. Overview

Stage 5 ("Artifact Generation") is the final stage of the jvuln-platform pipeline. It takes intelligence from stages 1–4 (CVE metadata, patch diff, trigger chain, root cause analysis) and produces three tangible deliverables:

| Deliverable | Description | Output Path |
|-------------|-------------|-------------|
| `vuln-demo` | A Spring Boot 2.7 Java 8 project containing the vulnerable library, configured so its vulnerable code path is reachable | `vuln-demo/` |
| `poc` | A bash script that exploits the running application to prove the vulnerability exists | `poc/exploit.sh` |
| `report` | A Markdown report explaining the vulnerability, root cause, and remediation | `report/report.md` |

Unlike stages 1–4 which use a fixed "send prompt, parse JSON response" pattern, Stage 5 uses a **Tool-Use Agent loop** where the LLM autonomously decides what to do — write files, compile, start the app, run curl commands, debug errors, and call `finish()` when done.

---

## 2. Architecture — Agent Loop

### 2.1 High-Level Flow

```
ArtifactGenStage.execute(ctx)
  │
  ├── 1. Extract upstream data (intelligence, patchDiff, triggerChain, rootCause, artifact)
  ├── 2. Write skeleton files: Application.java, build.sh, run.sh (NOT pom.xml)
  ├── 3. discoverExistingFiles() — scan for files from a previous paused run
  ├── 4. Check for 5_checkpoint.json → if present, build RESUME CONTEXT in user prompt
  ├── 5. Build 6 tool definitions + system prompt + user prompt
  ├── 6. Agent loop (max 25 turns):
  │     ┌─────────────────────────────────────────────────────┐
  │     │  a. Inject urgency messages at turn 20 and 23       │
  │     │  b. Call LLM (Anthropic API) with tool_use mode    │
  │     │     → on failure: retry up to 3x with backoff      │
  │     │     → if all retries fail: save checkpoint,         │
  │     │       write paused status, return success           │
  │     │  c. Append assistant response to message history    │
  │     │  d. If no tool_use blocks → break loop              │
  │     │  e. Execute each tool_use block:                    │
  │     │     - finish() → capture summary, break loop       │
  │     │     - other → execute, capture tool_result          │
  │     │  f. Append tool_results to message history          │
  │     └─────────────────────────────────────────────────────┘
  ├── 7. buildOutput() → 5_artifacts.json
  └── 8. finally: stop vuln-demo process
```

### 2.2 Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Agent writes its own pom.xml | LLM knows which vulnerable library + version to include based on Stage 1 intelligence. Skeleton provides Application.java but pom.xml is entirely LLM-authored. |
| "Configure, don't simulate" | The vulnerability must live in the real library, not in hand-written simulation code. The LLM configures the real vulnerable component — e.g., enabling H2 Console via `application.properties`, not writing Java code that mimics H2 Console behavior. |
| Skeleton files never overwritten | `writeMinimalSkeleton()` only runs once. On resumes, existing files are detected by `discoverExistingFiles()`. |
| Agent returns `StageResult.success()` even when paused | If we return failure, PipelineEngine (line 125 of PipelineEngine.java) would `break` the pipeline and mark the task as FAILED. By returning success with `status: "paused"`, the frontend can show a "Continue" button. |
| 25-turn cap | Empirical: CVE-2021-42392 (H2) finishes in ~9 turns; CVE-2025-24813 (Tomcat) takes ~20. 25 gives headroom without infinite loops. |

---

## 3. Tool Definitions

The agent is given 6 Claude Anthropic tools. Each has a JSON Schema input definition.

### 3.1 Tool Catalog

| Tool | Parameters | Returns | Security / Constraints |
|------|-----------|---------|----------------------|
| `write_file` | `path: string`, `content: string` | `"ok"` or error string | Path must start with `vuln-demo/`, `poc/`, or `report/`. Path traversal (`..`) rejected. `.sh` files auto-chmod +x. |
| `read_file` | `path: string` | File content (truncated to 4000 chars) or error | Path traversal rejected. |
| `run_build` | (none) | `BUILD SUCCESS` or `BUILD FAILED (exit code N)` + stdout/stderr (last 4000 chars) | Executes `build.sh` in vuln-demo dir. 120s timeout. |
| `start_app` | (none) | Startup status + HTTP response code | Kills previous process if running. Polls `http://localhost:18080/` every 1s for 30s. Timeout = "STARTUP TIMEOUT". |
| `run_command` | `command: string` | Exit code + stdout/stderr (last 4000 chars) | Only first word is checked against COMMAND_WHITELIST. Executes in CVE workspace dir. 60s timeout. |
| `finish` | `vuln_demo_status`, `poc_status`, `report_status`, `notes` | Terminates the loop | LLM's self-reported status values. Used directly in output if provided. |

### 3.2 Command Whitelist

```
curl, wget, grep, cat, ls, find, head, tail, bash, test, echo,
java, mvn, wc, sort, uniq, chmod, mkdir, pwd, diff, file, xxd,
base64, sha256sum, md5sum
```

The whitelist is checked against the **first word** of the command string only. Sub-commands (e.g., arguments to `bash -c`) bypass the check — this is intentional to allow the LLM to run PoC scripts.

### 3.3 Tool Definition Code Location

File: `backend/jvuln-generator/src/main/java/com/jvuln/generator/ArtifactGenStage.java`  
Method: `buildToolDefinitions()` (lines 242–303)

---

## 4. LLM Layer

### 4.1 Request Path

```
ArtifactGenStage
  → PipelineContext.getLlmClient()              // Returns LlmClient (OpenAiCompatClient)
    → OpenAiCompatClient.chat(request)
      → if providerType == "anthropic":
          → new AnthropicCaller(...).chat(request)
      → else:
          → new LlmCaller(...).chat(request)    // OpenAI-compatible API
```

### 4.2 LlmRequest Extensions for Tool Use

File: `backend/jvuln-llm/src/main/java/com/jvuln/llm/LlmRequest.java`

| Class | Purpose |
|-------|---------|
| `LlmRequest.ToolDef` | Holds `name`, `description`, `inputSchema` (JSON Schema as `Map<String, Object>`) |
| `LlmRequest.ContentBlock` | Union type: `text`, `tool_use`, or `tool_result`. Used in both request (tool_result blocks in user messages) and response (text + tool_use blocks from assistant). |
| `LlmRequest.Message` | `content` field is `Object` — can be `String` (plain text) or `List<ContentBlock>` (mixed content). Factory methods: `assistantWithBlocks()`, `toolResults()`. |
| `LlmRequest.agent()` | Factory method setting temperature=0.3, maxTokens=16384, toolChoice="auto". |

### 4.3 AnthropicCaller SSE Parsing

File: `backend/jvuln-llm/src/main/java/com/jvuln/llm/impl/AnthropicCaller.java`

The Anthropic API returns Server-Sent Events (SSE) when `stream: true`. The `chat()` method parses 6 event types:

| SSE Event | Action |
|-----------|--------|
| `content_block_start` | Detects block type. If `tool_use`, captures `id` and `name`. Resets accumulators. |
| `content_block_delta` | If `text_delta`: appends to text accumulators. If `input_json_delta`: appends `partial_json` to JSON accumulator. |
| `content_block_stop` | Finalizes the block. For `text` blocks: creates ContentBlock.text(). For `tool_use` blocks: parses accumulated JSON into ContentBlock.toolUse(). |
| `message_delta` | Captures `stop_reason` and `output_tokens`. |
| `message_start` | Captures `input_tokens`. |
| `error` | Records error message for later throwing. |

Error handling (lines 151–156): If no blocks were parsed and an SSE error event was received, throws `"Anthropic API error: {msg}"`. If no blocks and no error, throws `"Anthropic streaming returned no content"`.

### 4.4 Message Serialization

Method: `buildBody()` (lines 189–249) and `serializeContentBlock()` (lines 251–275)

The Anthropic API requires `tool_use` blocks in assistant messages and `tool_result` blocks in user messages. The serializer maps ContentBlock types to their wire format:

```json
// tool_use block (in assistant message content array)
{"type": "tool_use", "id": "toolu_xxx", "name": "write_file", "input": {...}}

// tool_result block (in user message content array)
{"type": "tool_result", "tool_use_id": "toolu_xxx", "content": "ok"}
```

---

## 5. Error Recovery & Pause/Resume

### 5.1 LLM Call Retry

Method: `chatWithRetry()` (lines 217–238)

- Max 3 attempts
- Retryable errors: 500, 502, 503, 429, 403, "overloaded", "Internal Server Error", "returned no content"
- Backoff: 10s → 20s → throw
- Non-retryable errors (e.g., 401, 404) throw immediately

### 5.2 Pause on Persistent Failure

When all retries are exhausted (lines 141–153):
1. Save `stages/5_checkpoint.json` with: `completedTurns`, `error`, `writtenFiles`, `timestamp`
2. Write `stages/5_artifacts.json` with `status: "paused"`, `pauseReason`, `pausedAtTurn`
3. Return `StageResult.success()` (NOT failure) — this allows the "Continue" button in the frontend

### 5.3 Resume on Rerun

When `rerun(fromStage=5)` is called (lines 87–99):
1. Check for `stages/5_checkpoint.json`
2. If found: read `completedTurns`, build RESUME CONTEXT in user prompt listing all existing files
3. Delete checkpoint file (single-use)
4. `discoverExistingFiles()` populates `writtenFiles` with all files already on disk
5. New agent starts with full context of what was already built

### 5.4 Checkpoint Format

```json
{
  "completedTurns": 9,
  "error": "503 Service Unavailable from POST http://127.0.0.1:3000/v1/messages",
  "writtenFiles": ["vuln-demo/pom.xml", "vuln-demo/src/main/java/...", ...],
  "timestamp": 1717508204997
}
```

---

## 6. Output — 5_artifacts.json

### 6.1 Structure

```json
{
  "status": "generated" | "paused",
  "agentTurns": 9,
  "vulnDemo": {
    "status": "startup_ok" | "compile_ok" | "compile_failed" | "not_started" | "unknown",
    "files": ["pom.xml", "src/main/java/...", ...]
  },
  "poc": {
    "status": "verified" | "unverified" | "skipped" | "unknown",
    "files": ["exploit.sh"]
  },
  "report": {
    "status": "generated" | "skipped" | "unknown",
    "file": "report/report.md"
  },
  "fileCount": 8,
  "files": [
    {"path": "vuln-demo/pom.xml", "type": "vuln-demo"},
    {"path": "poc/exploit.sh", "type": "poc"},
    {"path": "report/report.md", "type": "report"}
  ],
  "reproductionSteps": [
    {"step": "1", "title": "Build the vulnerable demo project",
     "command": "cd vuln-demo && mvn package -DskipTests -q", "description": "..."},
    {"step": "2", "title": "Start the application",
     "command": "cd vuln-demo && java -jar target/*.jar --server.port=18080", "description": "..."},
    {"step": "3", "title": "Execute the PoC exploit",
     "command": "bash poc/exploit.sh", "description": "..."},
    {"step": "4", "title": "Read the vulnerability report",
     "command": "cat report/report.md", "description": "..."}
  ]
}
```

### 6.2 Status Inference Logic

When the agent exhausts all 25 turns without calling `finish()` (summary is null):

| Field | Inference Rule |
|-------|----------------|
| `vulnDemo.status` | JAR exists in `target/` → `"compile_ok"`. POM exists but no JAR → `"compile_failed"`. Neither → `"not_started"`. |
| `poc.status` | Files under `poc/` exist → `"unverified"`. Otherwise → `"skipped"`. |
| `report.status` | File `report/report.md` exists → `"generated"`. Otherwise → `"skipped"`. |

### 6.3 Reproduction Steps

Generated only when `vulnDemo.status` is `"startup_ok"` or `"compile_ok"`. Steps are deterministic: build → start → PoC (if exists) → report (if exists). Unverified PoCs get a warning suffix in the description.

### 6.4 File Paths

All paths are **relative to the CVE workspace root**:

| Workspace | Disk Location |
|-----------|--------------|
| `workspace/CVE-2023-3276/` | `/root/jvuln-platform/workspace/CVE-2023-3276/` (configured via `jvuln.workspace.root`) |

Files are written to `workspace/{cveId}/stages/5_artifacts.json`.

---

## 7. Prompt Engineering

### 7.1 System Prompt

File: `backend/jvuln-app/src/main/resources/prompts/system_gen_agent.txt`

Design elements:
- **Role assignment**: "security education expert building vulnerability reproduction environments"
- **Deliverable specification**: 3 outputs with explicit paths
- **Workflow**: 8-step numbered workflow with budget constraints
- **Budget guidance**: "Do NOT spend more than 3 turns debugging the PoC", "Always call finish before running out of turns"
- **Core principle**: "Configure, Don't Simulate" with 3 concrete examples
- **Constraints**: Java 8 syntax, Spring Boot 2.7.18, port 18080, file path rules, pre-existing skeleton files

### 7.2 User Prompt (Template)

File: `backend/jvuln-app/src/main/resources/prompts/user_gen_agent.txt`

Template variables substituted at runtime:
- `{{intelligence}}` — trimmed CVE metadata (cveId, cweId, description, cvss, fixedVersion, artifact, affectedVersions)
- `{{trigger_chain}}` — JSON from Stage 4 reasoning
- `{{root_cause}}` — vuln_root_cause + fix_description from Stage 4
- `{{patch_diff}}` — truncated to 4000 chars from Stage 2
- `{{artifact}}` — groupId:artifactId from Stage 1

### 7.3 Resume Context (Dynamic)

When resuming from a checkpoint, the user prompt is appended with:

```
## RESUME CONTEXT
A previous run completed 9 turns before an API error interrupted it.
The following files already exist — inspect them with read_file before rewriting:
- vuln-demo/pom.xml
- vuln-demo/src/main/java/com/jvuln/demo/controller/OrderImportController.java
- ...

Continue from where it left off. Check compilation status and proceed.
```

### 7.4 Urgency Injection

Two messages are injected into the conversation at fixed turn counts:

| Remaining Turns | Message |
|----------------|---------|
| 5 | "NOTICE: Only 5 turns remaining. Wrap up now: if the PoC is not yet verified, set poc_status to 'unverified'. Write the report and call finish()." |
| 2 | "FINAL WARNING: 2 turns left. You MUST call finish() on your next response. Skip any remaining debugging." |

These are injected as `Message.user()` and appear in the conversation before the LLM's next response.

---

## 8. File Inventory

### 8.1 Modified Files

| File | Purpose |
|------|---------|
| `backend/jvuln-generator/src/main/java/com/jvuln/generator/ArtifactGenStage.java` | Main Stage 5 implementation — agent loop, tool execution, output building (755 lines) |
| `backend/jvuln-llm/src/main/java/com/jvuln/llm/LlmRequest.java` | Extended with ToolDef, ContentBlock, multi-format Message.content, agent() factory (167 lines) |
| `backend/jvuln-llm/src/main/java/com/jvuln/llm/LlmResponse.java` | Extended with contentBlocks field, hasToolUse(), getToolUses() (52 lines) |
| `backend/jvuln-llm/src/main/java/com/jvuln/llm/impl/AnthropicCaller.java` | SSE parsing for tool_use events, buildBody() with tools/tool_choice, serializeContentBlock() (281 lines) |
| `backend/jvuln-llm/src/main/java/com/jvuln/llm/impl/OpenAiCompatClient.java` | Routes agent requests to AnthropicCaller when provider=anthropic (64 lines) |
| `backend/jvuln-llm/src/main/java/com/jvuln/llm/LlmCaller.java` | Fixed getContent() → getTextContent() for Object return type (124 lines) |
| `frontend/src/views/AnalysisDetail.vue` | Paused banner, "Continue" button, reproduction steps display |
| `frontend/src/locales/en-US.ts` | i18n: pausedTitle, pausedAt, pausedReason, continueAgent, reproductionSteps |
| `frontend/src/locales/zh-CN.ts` | i18n: 中文翻译同上 |

### 8.2 New Files

| File | Purpose |
|------|---------|
| `backend/jvuln-app/src/main/resources/prompts/system_gen_agent.txt` | Agent system prompt (55 lines) |
| `backend/jvuln-app/src/main/resources/prompts/user_gen_agent.txt` | Agent user prompt template (25 lines) |

### 8.3 Deleted Files (replaced by agent prompts)

| File | Replaced By |
|------|-------------|
| `prompts/system_gen_vulndemo.txt` | `system_gen_agent.txt` |
| `prompts/user_gen_vulndemo.txt` | `user_gen_agent.txt` |
| `prompts/system_gen_poc.txt` | `system_gen_agent.txt` |
| `prompts/user_gen_poc.txt` | `user_gen_agent.txt` |
| `prompts/system_gen_report.txt` | `system_gen_agent.txt` |
| `prompts/user_gen_report.txt` | `user_gen_agent.txt` |

---

## 9. Configuration & Constants

| Constant | Value | Location | Reason |
|----------|-------|----------|--------|
| `MAX_AGENT_TURNS` | 25 | ArtifactGenStage:31 | Empirically determined — most CVEs complete in 9–20 turns |
| `VULN_DEMO_PORT` | 18080 | ArtifactGenStage:32 | Non-standard port to avoid conflicts |
| `COMPILE_TIMEOUT` | 120s | ArtifactGenStage:33 | Maven download + compile time |
| `STARTUP_WAIT` | 30s | ArtifactGenStage:34 | Spring Boot cold start time |
| `COMMAND_TIMEOUT` | 60s | ArtifactGenStage:35 | Curl/script execution time |
| `OUTPUT_TRUNCATE` | 4000 chars | ArtifactGenStage:36 | Prevents context overflow from build/command output |
| `LLM_RETRY_MAX` | 3 | ArtifactGenStage:217 | Balance between resilience and latency |
| `LLM_RETRY_DELAY` | 10s, 20s | ArtifactGenStage:231 | Exponential backoff |

---

## 10. Known Issues & Limitations

### 10.1 Concurrency Safety

If the user clicks "Continue" multiple times while the API is down, multiple `PipelineEngine.execute()` calls will run concurrently for the same CVE. This causes:
- Multiple agents writing to the same files (race conditions on pom.xml)
- Multiple vuln-demo processes fighting for port 18080
- Conflicting checkpoint files

**Mitigation**: None currently. A future improvement would be to check if a pipeline is already RUNNING for a given CVE before starting a new one.

### 10.2 Status Self-Report Trust

The LLM reports its own `poc_status` in the `finish()` call. There is no programmatic verification that the PoC actually exploited the vulnerability. The LLM could report "verified" based on a false positive (e.g., the curl returned HTTP 200 but the exploit didn't actually trigger).

**Mitigation**: None currently. The reproduction steps allow manual verification.

### 10.3 Turn Exhaustion

Some complex CVEs (like CVE-2025-24813 — Tomcat DefaultServlet session deserialization) require multiple compile-debug cycles and may exhaust all 25 turns before calling `finish()`. The inference logic in `buildOutput()` provides fallback status values, but the PoC remains "unverified" and the report may be incomplete.

**Mitigation**: The urgency injection at 5 and 2 remaining turns helps, but does not fully solve the problem for inherently complex CVEs.

### 10.4 LLM API Dependency

Stage 5 requires an Anthropic-compatible API (the `new-api` proxy). If this is unavailable, the agent will pause after 3 retries. It cannot fall back to an OpenAI-compatible model because the tool_use wire format differs between Anthropic and OpenAI.

---

## 11. Verification Checklist

After modifying any Stage 5 code, verify:

- [ ] `mvn install -DskipTests -q` compiles cleanly (run from `backend/` directory)
- [ ] Backend starts without errors (`java -jar backend/jvuln-app/target/jvuln-app-1.0.0-SNAPSHOT.jar`)
- [ ] LLM config is active (`GET /api/config/llm` — one record with `active: true`)
- [ ] CVE tasks exist in DB (`GET /api/analysis` — non-empty)
- [ ] `POST /api/analysis/CVE-2021-42392/rerun?fromStage=5` completes in ~20 turns
- [ ] `GET /api/analysis/CVE-2021-42392/artifacts` returns files with skeleton entries
- [ ] Frontend Artifacts tab shows: file list, reproduction steps, status badges
- [ ] `GET /api/analysis/{cveId}/progress` SSE stream sends `stage_start` / `stage_done` events
- [ ] Kill API mid-run → agent pauses with checkpoint → click Continue → resumes correctly
