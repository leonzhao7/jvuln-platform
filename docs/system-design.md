# jvuln-platform — System Design Document

**Version**: 1.0  
**Last Updated**: 2026-06-08  
**Purpose**: Comprehensively document the system architecture, component interactions, data flow, and implementation details for code review and maintenance.

---

## 1. System Overview

jvuln-platform is a Java CVE analysis platform that automates vulnerability reproduction for open-source Java libraries. Given a CVE ID (e.g. `CVE-2021-42392`), it runs a 5-stage pipeline that collects intelligence, locates the fix commit, analyzes the code change, reasons about the vulnerability, and generates a proof-of-concept exploit + educational report.

### 1.1 Technology Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 8 |
| Framework | Spring Boot 2.7 (embedded Tomcat) |
| Build | Maven multi-module |
| Database | H2 (file-based, `./data/jvuln`) |
| ORM | JPA / Hibernate 5.6 |
| Frontend | Vue 3 + TypeScript + Element Plus + Vite |
| LLM | Anthropic API (via new-api proxy) + OpenAI-compat fallback |
| HTTP Client | Spring WebFlux WebClient (reactive) |

### 1.2 Project Structure

```
jvuln-platform/
├── backend/                         # Maven multi-module root
│   ├── pom.xml                      # Parent POM — Spring Boot 2.7.18, Java 8
│   ├── jvuln-app/                   # Spring Boot application + REST controllers
│   │   └── src/main/
│   │       ├── java/com/jvuln/
│   │       │   ├── JvulnApplication.java
│   │       │   ├── config/
│   │       │   │   ├── AsyncConfig.java          # @Async thread pool (8 threads)
│   │       │   │   └── DbLlmConfigProvider.java  # DB-backed LLM config
│   │       │   └── controller/
│   │       │       ├── AnalysisController.java    # 13 endpoints (CRUD + rerun + SSE + data)
│   │       │       └── ConfigController.java      # LLM config CRUD + test
│   │       └── resources/
│   │           ├── application.yml
│   │           └── prompts/           # .txt template files loaded by PromptRegistry
│   ├── jvuln-collector/             # Stage 1: Intelligence Collection
│   ├── jvuln-patcher/               # Stage 2: Patch Locating
│   ├── jvuln-analyzer/              # Stage 3: Code Analysis
│   ├── jvuln-reasoning/             # (legacy dependency only)
│   ├── jvuln-generator/             # Stage 5: Artifact Generation (Tool-Use Agent)
│   ├── jvuln-llm/                   # LLM abstraction layer
│   ├── jvuln-pipeline/              # PipelineEngine + ReasoningStage (Stage 4)
│   └── jvuln-store/                 # DB entities, repositories, WorkspaceManager,
│                                    # pipeline model interfaces (Stage, StageResult, PipelineContext)
├── frontend/                        # Vue 3 + Vite
│   └── src/
│       ├── api/index.ts             # Axios API client (typed)
│       ├── router/index.ts          # 5 routes
│       ├── views/                   # Dashboard, NewAnalysis, AnalysisDetail, PatchDiff, Settings
│       ├── locales/en-US.ts         # English i18n
│       └── locales/zh-CN.ts         # Chinese i18n
├── docs/
│   └── stage5-agent-design.md       # Stage 5 deep-dive
└── start.sh                         # Monorepo launcher (frontend + backend)
```

### 1.3 Maven Module Dependency Graph

```
jvuln-app
  ├── jvuln-collector
  ├── jvuln-patcher
  ├── jvuln-analyzer
  ├── jvuln-generator
  ├── jvuln-pipeline     (contains Stage 4 — ReasoningStage)
  ├── jvuln-llm
  └── jvuln-store        (pipeline interfaces, entities, repositories, WorkspaceManager)
```

---

## 2. Pipeline Architecture

### 2.1 Execution Model

The pipeline runs **asynchronously** via `@Async("pipelineExecutor")` on a thread pool (8 threads max). Stages execute **sequentially** in numeric order (1→2→3→4→5). If any stage returns `StageResult.isSuccess() == false`, the pipeline **stops** (does not continue to subsequent stages).

### 2.2 PipelineEngine (Orchestrator)

File: `backend/jvuln-pipeline/src/main/java/com/jvuln/pipeline/PipelineEngine.java`

The engine discovers all `Stage` beans, sorts them by `number()`, and iterates through them. Key behaviors:

| Feature | Implementation |
|---------|---------------|
| Skip completed stages | `isStageComplete(cveId, n)` checks if `stages/{n}_*.json` exists on disk |
| SSE progress | `SseEmitter` with 10-minute timeout, pushed to frontend via `EventSource` |
| Error propagation | Stage exception → marks task as FAILED, returns immediately |
| Task enrichment | After Stage 1, extracts cvssScore/cweId/artifact from `CveIntelligence` and writes to `cve_task` table |

### 2.3 PipelineContext

File: `backend/jvuln-store/src/main/java/com/jvuln/pipeline/model/PipelineContext.java`

Immutable context object passed through each stage execution. Contains:
- `cveId: String` — the CVE being analyzed
- `workspacePath: Path` — disk workspace root
- `llmClient: LlmClient` — LLM abstraction (dispatches to Anthropic or OpenAI)
- `workspaceManager: WorkspaceManager` — filesystem I/O
- `completedStages: Map<Integer, StageResult>` — results from previously executed stages
- `progressCallback: Consumer<StageProgress>` — SSE event dispatcher

### 2.4 StageResult

File: `backend/jvuln-store/src/main/java/com/jvuln/pipeline/model/StageResult.java`

Simple value object: `stageNum`, `stageName`, `success: boolean`, `data: Object`, `errorMessage: String`. Factory methods: `StageResult.success(int, String, Object)` and `StageResult.failure(int, String, String)`.

---

## 3. Five-Stage Pipeline

### 3.1 Stage 1 — Intelligence Collection

| Property | Value |
|----------|-------|
| Class | `com.jvuln.collector.IntelligenceStage` |
| File | `backend/jvuln-collector/.../IntelligenceStage.java` |
| Sources | Multiple `IntelSource` implementations (NVD API, GHSA, OSV, Gitee issue search, and reference-derived hints) |
| Execution | Parallel — one thread per source, 60s timeout |
| Output | `CveIntelligence` (cveId, description, cvss, cweId, artifact groupId+artifactId, version range, fix commits, articles) |
| Failure | Individual source failures are logged but don't fail the stage (first-non-blank merge strategy) |

**Data extraction**: The `merge()` method takes the first non-blank value from any successful source for each field. Fix commits are merged across all sources (set union).

Additional Stage 1 enrichment behavior:
- `NvdSource` extracts Maven `groupId`, `artifactId`, and upper-bound affected versions from `configurations[].nodes[].cpeMatch` when NVD publishes Maven CPE data.
- `GiteeSource` searches public Gitee issues by CVE ID so domestic-hosted projects can still produce `sourceRepo` and reference leads even without GitHub metadata.
- `IntelligenceStage` backfills `sourceRepo`, `artifactId`, and `affectedTo` from reference URLs and advisory description text when upstream sources are incomplete.

**Key design note**: When stages are loaded from cache (deserialized as `Map` rather than typed `CveIntelligence`), downstream stages use Jackson `JsonNode` path traversal — not typed getters — to handle both cases.

### 3.2 Stage 2 — Patch Locating

| Property | Value |
|----------|-------|
| Class | `com.jvuln.patcher.PatchLocateStage` |
| File | `backend/jvuln-patcher/.../PatchLocateStage.java` |
| Strategy | 3-phase waterfall |

**Phase 1 — Commit-based strategies** (try in priority order):
1. `RefCommitStrategy` — uses known "reference" commits (from reference-commit NVD data)
2. `GhsaCommitStrategy` — queries GitHub Security Advisory API  
3. `CveCommitSearchStrategy` — searches by CVE ID in commit messages

**Phase 2 — Maven source JAR diff** (`MavenSourceDiffStrategy`):
- Downloads vulnerable + fixed version source JARs from Maven Central
- Computes unified diff between the two
- If `fixedVersion` is unknown, infers it from the `affectedTo` version range
- If only `artifactId` is known, infers `groupId` through Maven Search before resolving metadata and source JAR URLs

**Phase 3 — AI-guided search** (`AiPatchSearchStrategy`):
- Uses LLM to generate structured enrichment: `sourceRepo`, `groupId`, `artifactId`, `affectedTo`, commit keywords, `fixedVersion`, and release tags
- Feeds the enrichment back into existing deterministic strategies and retries them before failing the stage

**Output**: `PatchInfo` (commitHash, commitMessage, strategyName, rawDiff, list of `FileDiff` objects, and `patchEvidence`). Raw diff is also written to `patches/fix.diff`.

`patchEvidence` is extracted at the end of Stage 2 from file paths, added/removed lines, and raw diff fallback data. It records the primary vulnerability category, category votes, changed files/modules, and concrete signals. This gives later stages a patch-grounded fact source when advisory text is vague or wrong.

### 3.3 Stage 3 — Code Analysis

| Property | Value |
|----------|-------|
| Class | `com.jvuln.analyzer.CodeAnalysisStage` |
| File | `backend/jvuln-analyzer/.../CodeAnalysisStage.java` |
| Approach | Static analysis of the unified diff |

**Workflow**:
1. Read `patches/fix.diff` from disk
2. Parse diff into per-file `JavaFileChange` objects (removed code, added code, method names)
3. Apply `DiffRelevanceFilter` — removes unrelated files from noisy diffs (e.g., version bump in pom.xml vs. actual code change)
4. For each relevant file:
   - Match CWE patterns on removed code via `CwePatternMatcher`
   - Extract called methods from the vulnerable code
   - Parse Java code with JavaParser to enrich method signatures
   - Build call chains between changed methods

5. Resolve vulnerability facts:
   - Treat Stage 1 descriptions, titles, and CWE labels as advisory claims
   - Prefer Stage 2 `patchEvidence` and Stage 3 code evidence
   - Use an LLM reconciliation pass when available, with deterministic rule fallback

**Output**: `Map<String, Object>` containing `analyzedFiles` (list of `CodeAnalysisResult`), `totalCweMatches`, and `vulnerabilityFacts`.

Stage 3 is still primarily deterministic static analysis, but `VulnerabilityFactResolver` may call the configured LLM to reconcile conflicting advisory and patch evidence. If that call fails, Stage 3 keeps the deterministic fact result.

### 3.4 Stage 4 — Vulnerability Reasoning

| Property | Value |
|----------|-------|
| Class | `com.jvuln.pipeline.stage.ReasoningStage` |
| File | `backend/jvuln-pipeline/.../stage/ReasoningStage.java` |
| Approach | LLM-based JSON-structured reasoning with retry |

**Workflow**:
1. Load prompts from `system_reasoning.txt` and `user_reasoning.txt`
2. Assemble context: trimmed Stage 1 data + Stage 3 `vulnerabilityFacts` + Stage 2 diff (capped) + Stage 3 code analysis
3. Call LLM in **JSON mode** (`response_format: json_object`)
4. Strip markdown fences from response, parse as JSON
5. On failure: retry up to 2 more times with progressively smaller diff caps (6000 → 3000 → 1000 chars)

**Retry behavior**: Each retry halves the diff context to reduce token load. 2s/4s backoff between attempts.

**Output**: LLM-generated JSON containing `trigger_chain` (step-by-step bug trigger path), `root_cause` analysis, and `attack_vectors`. If Stage 1 claims conflict with Stage 3 facts, the reasoning output should follow `vulnerabilityFacts` and explain the conflict.

### 3.5 Stage 5 — Artifact Generation (Tool-Use Agent)

Detailed design: [docs/stage5-agent-design.md](../docs/stage5-agent-design.md)

| Property | Value |
|----------|-------|
| Class | `com.jvuln.generator.ArtifactGenStage` |
| File | `backend/jvuln-generator/.../ArtifactGenStage.java` |
| Approach | Phase-driven agent loop with backend-controlled validation, attempt memory, and reviewer/validator reconciliation |

**Summary**: The only stage using tool-use mode. The model plans first, writes a minimal candidate, and then relies on backend compile/startup/PoC validation to decide the next repair phase. Current tools are `submit_plan`, `write_file`, `write_files`, `read_file`, `validate_artifacts`, `inspect_runtime`, and `finish`. Stage 5 also persists `5_memory.json`, cleans stale demo processes before startup, and lets backend validation override a reviewer LLM that still claims `unverified`.

---

## 4. Data Flow Between Stages

```
Stage 1                     Stage 2                   Stage 3                  Stage 4                      Stage 5
┌──────────┐              ┌──────────┐              ┌──────────┐              ┌──────────┐                ┌──────────────┐
│CveIntel- │──raw data──→│extracts: │──raw diff──→│parses:  │──code data─→│assembles:│──intelligence──→│writes:      │
│ligence   │              │group+ver │              │JavaFile-│              │intel+   │  diff+code      │vuln-demo/   │
│          │              │commits   │              │Changes  │              │diff+    │  ──→ LLM JSON──→│poc/         │
│          │              │repo ──→  │              │CWE hits │              │code     │                  │report/      │
│          │              │git clone │              │calls    │              │         │                  │              │
└──────────┘              └──────────┘              └──────────┘              └──────────┘                └──────────────┘
     │                                                  │                                                    │
     └─── artifact metadata ────────────────────────────┴── enriched into CveTask (DB) ─────────────────────┘
```

**Cross-stage data access pattern**: Each stage retrieves upstream data via `ctx.getCompletedStages().get(n).getData()`. The PipelineEngine handles loading cached stage data from disk when skipping completed stages.

---

## 5. LLM Layer

### 5.1 Architecture

```
OpenAiCompatClient (implements LlmClient, @Component)
  │
  ├─ if providerType == "anthropic":
  │    └─ new AnthropicCaller(baseUrl, apiKey, model).chat(request)
  │         └─ POST /v1/messages (Anthropic API with SSE streaming)
  │
  └─ else (openai-compat):
       └─ new LlmCaller(baseUrl, apiKey, model).chat(request)
            └─ POST /chat/completions (OpenAI-compatible API)
```

### 5.2 Core Interfaces

| Interface | File | Purpose |
|-----------|------|---------|
| `LlmClient` | `jvuln-llm/.../LlmClient.java` | `chat(request)` + `chatStream(request)` |
| `LlmConfigProvider` | `jvuln-llm/.../impl/LlmConfigProvider.java` | `getActive()` → `ActiveConfig(providerType, baseUrl, apiKey, model)` |

### 5.3 LlmRequest

File: `jvuln-llm/.../LlmRequest.java`

Supports three modes via factory methods:

| Mode | Factory | jsonMode | tools |
|------|---------|----------|-------|
| Reasoning (Stages 1-4) | `reasoning(sys, user)` | true | none |
| Generation | `generation(sys, user)` | false | none |
| Agent (Stage 5) | `agent(sys, messages, tools)` | false | provided |

Every provider payload uses the same defaults: `temperature=0.0` and a
65,536-token output limit.

Key extension for tool-use: `Message.content` is `Object` (can be `String` or `List<ContentBlock>`). Classes: `ToolDef`, `ContentBlock` (text/tool_use/tool_result).

### 5.4 AnthropicCaller — SSE Parsing

File: `jvuln-llm/.../impl/AnthropicCaller.java`

Uses **WebFlux reactive streaming** (`bodyToFlux(String.class)`). Parses 6 SSE event types from the Anthropic Messages API:

| Event | Handling |
|-------|----------|
| `message_start` | Extract `input_tokens` |
| `content_block_start` | Detect block type; if `tool_use`, capture id + name; reset accumulators |
| `content_block_delta` | `text_delta` → append to text buffer; `input_json_delta` → append partial JSON |
| `content_block_stop` | Finalize block: text → `ContentBlock.text()`, tool_use → parse JSON → `ContentBlock.toolUse()` |
| `message_delta` | Extract `stop_reason` (end_turn / tool_use), `output_tokens` |
| `error` | Record error message |

Config: 300s response timeout, 600s `blockLast` timeout, 10MB max in-memory size.

### 5.5 Prompt Management

File: `jvuln-llm/.../PromptRegistry.java`

Loads `.txt` files from `classpath:prompts/` by convention: `system_{stage}.txt` and `user_{stage}.txt`. Templates use `{{variable}}` substitution via `render(template, vars)`. Files are cached in-memory after first load.

**Current prompt files** (in `backend/jvuln-app/src/main/resources/prompts/`):

| File | Used By |
|------|---------|
| `system_reasoning.txt` | Stage 4 |
| `user_reasoning.txt` | Stage 4 |
| `system_gen_agent.txt` | Stage 5 |
| `user_gen_agent.txt` | Stage 5 |

### 5.6 LLM Configuration (DB-backed)

The active LLM provider is stored in the `llm_config` DB table (single active record). Managed via `/api/config/llm` endpoints. The `DbLlmConfigProvider` (in `jvuln-app`) reads the active record and returns it as `LlmConfigProvider.ActiveConfig`.

**Fallback chain**: If no active DB config exists → uses `jvuln.llm.*` properties from `application.yml` → defaults to `http://localhost:11434/v1` (Ollama).

---

## 6. Store Layer

### 6.1 WorkspaceManager

File: `backend/jvuln-store/.../store/WorkspaceManager.java`

Filesystem-based storage for pipeline stage data. Root configured via `jvuln.workspace.root` (default: `workspace` → resolves to `<cwd>/workspace`).

**Directory layout per CVE**:
```
workspace/CVE-2023-3276/
├── stages/
│   ├── 1_intelligence.json
│   ├── 2_patch.json
│   ├── 3_analysis.json
│   ├── 4_reasoning.json
│   ├── 5_artifacts.json
│   └── 5_checkpoint.json        # Stage 5 agent pause checkpoint (transient)
├── patches/
│   ├── fix.diff
│   ├── source/before/            # (unused — Maven source diff writes here)
│   └── source/after/
├── vuln-demo/                    # Stage 5 generated Spring Boot project
├── poc/                          # Stage 5 PoC scripts
├── references/articles/
└── report/                       # Stage 5 Markdown report
```

### 6.2 Database Schema (H2 JPA)

#### cve_task
```
id           BIGINT PK AUTO
cve_id       VARCHAR(20) UNIQUE NOT NULL    -- e.g. "CVE-2021-42392"
status       VARCHAR(20) NOT NULL           -- PENDING | RUNNING | COMPLETED | FAILED
current_stage INT                           -- 0-5
artifact     VARCHAR(100)                   -- "groupId:artifactId" (populated after Stage 1)
cvss_score   DECIMAL(3,1)
cwe_id       VARCHAR(20)
workspace_path VARCHAR(500)
created_at   TIMESTAMP
updated_at   TIMESTAMP
```

#### stage_record
```
id           BIGINT PK AUTO
cve_id       VARCHAR(20) NOT NULL
stage_num    INT NOT NULL
stage_name   VARCHAR(100)
status       VARCHAR(20)           -- PENDING | RUNNING | COMPLETED | FAILED
started_at   TIMESTAMP
finished_at  TIMESTAMP
error_msg    VARCHAR(2000)
```

#### llm_config
```
id           BIGINT PK (sequence: llm_config_seq, starts at 100)
name         VARCHAR(100)
provider_type VARCHAR(30)          -- "anthropic" | "openai-compat"
base_url     VARCHAR(500)
api_key      VARCHAR(500)
model        VARCHAR(100)
active       BOOLEAN (only one row should be true)
```

**Important**: `ddl-auto: update` means JPA creates/alters tables on startup. No migrations framework.

---

## 7. Frontend Architecture

### 7.1 Technology & Build

| Item | Value |
|------|-------|
| Framework | Vue 3 (Composition API) |
| Language | TypeScript |
| UI Library | Element Plus |
| Build | Vite |
| HTTP Client | Axios |
| Router | Vue Router (HTML5 History mode) |

### 7.2 Routes

| Path | Component | Purpose |
|------|-----------|---------|
| `/` | Dashboard.vue | CVE task list, create new, delete, SSE progress |
| `/analysis/new` | NewAnalysis.vue | Input CVE ID, submit analysis |
| `/analysis/:cveId` | AnalysisDetail.vue | 5-tab detail view (stages/source/diff/reasoning/artifacts) |
| `/analysis/:cveId/diff` | PatchDiff.vue | Full diff viewer with syntax highlighting |
| `/settings` | Settings.vue | LLM config management |

### 7.3 SSE Progress

AnalysisDetail.vue opens an `EventSource` to `/api/analysis/{cveId}/stream` when the task status is `RUNNING`. Server pushes events (`stage_start`, `progress`, `stage_done`, `error`, `pipeline_done`) via `SseEmitter`. The frontend displays live stage progress while loading stage detail data from dedicated per-stage APIs.

### 7.4 API Client

File: `frontend/src/api/index.ts`

Typed Axios wrapper covering task CRUD, rerun, sync-status, per-stage data retrieval, diff/report/artifact endpoints, and LLM config management.

### 7.5 i18n

Two locale files: `en-US.ts` (English) and `zh-CN.ts` (Chinese). Keys organized by view section: `common.*`, `dashboard.*`, `analysis.*`, `settings.*`.

### 7.6 Settings View

`Settings.vue` provides a full LLM config manager: list/create/edit/delete/activate/test. The "Test" button sends a "PONG" request to the configured LLM and shows the response.

---

## 8. API Reference

### 8.1 Analysis Endpoints

Base: `/api/analysis`

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/` | Create new analysis. Body: `{cveId, fromStage?}` |
| `GET` | `/` | List all CVE tasks |
| `GET` | `/{cveId}` | Get task + stage records |
| `POST` | `/{cveId}/rerun?fromStage=N` | Rerun from stage N (default 1) |
| `DELETE` | `/{cveId}` | Delete task + stage records (not workspace files) |
| `POST` | `/{cveId}/sync-status` | Sync DB stage status from disk (file present → COMPLETED) |
| `GET` | `/{cveId}/stream` | SSE event stream for progress updates |
| `GET` | `/{cveId}/intelligence` | Stage 1 data |
| `GET` | `/{cveId}/patch` | Stage 2 data |
| `GET` | `/{cveId}/diff` | Raw diff (optionally filtered by Stage 3 analyzed files) |
| `GET` | `/{cveId}/code-analysis` | Stage 3 data |
| `GET` | `/{cveId}/reasoning` | Stage 4 data |
| `GET` | `/{cveId}/artifacts` | Stage 5 data |
| `GET` | `/{cveId}/stages/{stageNum}/json` | Raw JSON for any stage file (1-5) |
| `GET` | `/{cveId}/report` | Stage 5 report markdown (raw file) |

### 8.2 LLM Config Endpoints

Base: `/api/config`

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/llm` | List all configs (API key masked) |
| `POST` | `/llm` | Create config |
| `PUT` | `/llm/{id}` | Update config |
| `DELETE` | `/llm/{id}` | Delete config |
| `POST` | `/llm/{id}/activate` | Deactivate all others, activate this one |
| `POST` | `/llm/{id}/test` | Test config with "PONG" prompt |

---

## 9. Async Execution

The pipeline runs on a dedicated thread pool configured in `AsyncConfig` (8 threads, named `pipeline-*`). No queue capacity limit — excess tasks block until a thread frees up.

`PipelineEngine.execute()` now keeps an in-memory per-CVE running lock (`AtomicBoolean`). `AnalysisController.rerun()` and `sync-status` use `pipelineEngine.isRunning(cveId)` so stale DB status does not create duplicate runs for the same CVE inside the same backend process.

---

## 10. Configuration Reference

### 10.1 application.yml

```yaml
server.port: 8080
spring.datasource.url: jdbc:h2:file:./data/jvuln
spring.jpa.hibernate.ddl-auto: update
jvuln.workspace.root: workspace                    # resolved to absolute path
jvuln.llm.base-url: http://localhost:11434/v1      # fallback if no DB config
jvuln.llm.model: deepseek-coder                    # fallback model
```

### 10.2 Environment Variables

| Variable | Purpose |
|----------|---------|
| `LLM_BASE_URL` | Override `jvuln.llm.base-url` |
| `LLM_API_KEY` | API key for LLM provider |
| `LLM_MODEL` | Override `jvuln.llm.model` |
| `NVD_API_KEY` | NVD API key for Stage 1 |
| `GITHUB_TOKEN` | GitHub API token for Stage 2 commit strategies |

---

## 11. Security Model & Input Validation

### 11.1 CVE ID Validation

`POST /api/analysis` validates CVE ID format: `CVE-\d{4}-\d{4,}`. Rejects anything else with 400.

### 11.2 Stage 5 Tool Execution

The `write_file` tool rejects:
- Paths not starting with `vuln-demo/`, `poc/`, or `report/`
- Path traversal (`..`)
- Empty path or content

The current Stage 5 agent no longer receives a generic `run_command` tool. The backend owns build/startup/PoC execution via `validate_artifacts`, while write operations remain restricted to `vuln-demo/`, `poc/`, and `report/`.

### 11.3 API Key Masking

ConfigController masks API keys in responses (replaces with `••••••••`). Keys sent to the server are never returned to the frontend after creation.

---

## 12. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| DB + Filesystem dual storage | DB for structured metadata (task status, stage progress, config); filesystem for large outputs (diffs, code analysis, artifacts). Enables simple backup/restore. |
| H2 file-based database | Zero-config, no external DB dependency, sufficient for single-user desktop use. |
| Absolute path resolution in WorkspaceManager | `Paths.get(root).toAbsolutePath()` prevents ambiguity but means restarts from different CWDs can create fragmented workspaces. User must always start from the project root. |
| In-memory pipeline lock per CVE | Prevents duplicate rerun execution and workspace contention inside one backend process. |
| Validator-first Stage 5 control | Compile/startup/PoC truth is decided by backend validation, not by the model's self-report alone. |
| "Configure, don't simulate" (Stage 5) | The vulnerability must be triggered through the real library. If the LLM simulates vulnerable behavior in application code, the results are misleading. |
| No CSP/Security Headers | Not required — this is a local development tool, not a production web app. |

---

## 13. Known Issues & Limitations

### 13.1 Workspace Path Fragility

`WorkspaceManager(workspaceRoot)` resolves relative paths against `System.getProperty("user.dir")`. If the JAR is started from a different directory, the workspace path changes and previously stored data becomes invisible to the API. Users must always start from the project root.

### 13.2 Concurrent Pipeline Execution

Concurrent reruns for the same CVE are now blocked inside a single backend process. This fixes the original Stage 5 workspace race and most accidental repeated-click cases.

Residual limitation: the lock is process-local. If multiple backend JVMs share the same workspace/database, external coordination is still required.

### 13.3 Mixed Typed/Deserialized Data

When pipeline stages are rerun with cached upstream stages, data is loaded as `Map` (Jackson `LinkedHashMap`) rather than the original typed class (e.g., `CveIntelligence`). Downstream stages must handle both — they use `instanceof` checks and fall back to `JsonNode` path traversal. This is fragile: adding a field to a model class requires adding the extraction logic in multiple places.

### 13.4 H2 File DB Not Portable

The H2 database file (`data/jvuln.mv.db`) is tied to the H2 version and JVM. Moving between machines requires re-creating the DB via the `POST /api/analysis/sync-status` endpoint.

### 13.5 Stage 5 Still Depends on Model Efficiency

Stage 5 is now much more backend-governed, with:

- explicit planning
- phase-restricted tools
- backend compile/startup/PoC validation
- stale process cleanup
- no-progress aborts
- reviewer/backend reconciliation

Even so, the exact number of turns still depends on model quality and prompt adherence. The remaining instability is mostly efficiency-related rather than correctness-related.

---

## 14. Development & Verification Checklist

### 14.1 First-Time Setup

```bash
cd /root/jvuln-platform/backend && mvn install -DskipTests -q
cd /root/jvuln-platform/frontend && npm install
```

### 14.2 Running

```bash
cd /root/jvuln-platform && bash start.sh    # starts both frontend and backend
java -jar backend/jvuln-app/target/jvuln-app-1.0.0-SNAPSHOT.jar  # backend only
```

### 14.3 Verification After Code Changes

- [ ] `mvn install -DskipTests -q` compiles cleanly (from `backend/` directory)
- [ ] Backend starts on port 8080 without errors
- [ ] Frontend `npx vue-tsc --noEmit` passes type checking
- [ ] Frontend `npm run build` produces a production bundle
- [ ] `POST /api/analysis` with `{"cveId":"CVE-2021-42392"}` creates task
- [ ] SSE stream at `/api/analysis/CVE-2021-42392/stream` receives events
- [ ] All 5 stages complete successfully for CVE-2021-42392
- [ ] Artifacts tab shows file list + reproduction steps + status badges
- [ ] Report markdown renders in the frontend
- [ ] LLM config CRUD works at `/settings`
- [ ] Stage 5 pause/resume: kill API mid-run → paused banner appears → click Continue → resumes
