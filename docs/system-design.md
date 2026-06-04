# jvuln-platform — System Design Document

**Version**: 1.0  
**Last Updated**: 2026-06-05  
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
| Sources | Multiple `IntelSource` implementations (NVD API, GitHub Security Advisories, etc.) |
| Execution | Parallel — one thread per source, 60s timeout |
| Output | `CveIntelligence` (cveId, description, cvss, cweId, artifact groupId+artifactId, version range, fix commits, articles) |
| Failure | Individual source failures are logged but don't fail the stage (first-non-blank merge strategy) |

**Data extraction**: The `merge()` method (lines 83–127) takes the first non-blank value from any successful source for each field. Fix commits are merged across all sources (set union).

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

**Phase 3 — AI-guided search** (`AiPatchSearchStrategy`):
- Uses LLM to generate commit search keywords, version guesses, and release tags
- Feeds these back into existing strategies to search again

**Output**: `PatchInfo` (commitHash, commitMessage, strategyName, rawDiff, list of `FileDiff` objects). Raw diff is also written to `patches/fix.diff`.

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

**Output**: `Map<String, Object>` containing `analyzedFiles` (list of `CodeAnalysisResult`) and `totalCweMatches`.

**No LLM calls** — pure static analysis with JavaParser.

### 3.4 Stage 4 — Vulnerability Reasoning

| Property | Value |
|----------|-------|
| Class | `com.jvuln.pipeline.stage.ReasoningStage` |
| File | `backend/jvuln-pipeline/.../stage/ReasoningStage.java` |
| Approach | LLM-based JSON-structured reasoning with retry |

**Workflow**:
1. Load prompts from `system_reasoning.txt` and `user_reasoning.txt`
2. Assemble context: trimmed Stage 1 data + Stage 2 diff (capped) + Stage 3 code analysis
3. Call LLM in **JSON mode** (`response_format: json_object`)
4. Strip markdown fences from response, parse as JSON
5. On failure: retry up to 2 more times with progressively smaller diff caps (6000 → 3000 → 1000 chars)

**Retry behavior**: Each retry halves the diff context to reduce token load. 2s/4s backoff between attempts.

**Output**: LLM-generated JSON containing `trigger_chain` (step-by-step bug trigger path), `root_cause` analysis, and `attack_vectors`.

### 3.5 Stage 5 — Artifact Generation (Tool-Use Agent)

Detailed design: [docs/stage5-agent-design.md](../docs/stage5-agent-design.md)

| Property | Value |
|----------|-------|
| Class | `com.jvuln.generator.ArtifactGenStage` |
| File | `backend/jvuln-generator/.../ArtifactGenStage.java` |
| Approach | Anthropic tool_use agent loop (25 turn cap, 6 tools) |

**Summary**: The only stage using tool-use mode. The LLM autonomously writes Java files, compiles with Maven, starts the Spring Boot app, runs PoC scripts via curl, and calls `finish()` when done. Includes retry (3 attempts with backoff) and pause/resume (checkpoint to disk when API fails persistently).

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

| Mode | Factory | temperature | maxTokens | jsonMode | tools |
|------|---------|-------------|-----------|----------|-------|
| Reasoning (Stages 1-4) | `reasoning(sys, user)` | 0.1 | 8192 | true | none |
| Generation | `generation(sys, user)` | 0.3 | 16384 | false | none |
| Agent (Stage 5) | `agent(sys, messages, tools)` | 0.3 | 16384 | false | provided |

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
temperature  DOUBLE (default 0.1)
max_tokens   INT (default 8192)
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

AnalysisDetail.vue (line ~90–110) opens an `EventSource` to `/api/analysis/{cveId}/stream` when the task status is `RUNNING`. Server pushes events (`stage_start`, `progress`, `stage_done`, `error`, `pipeline_done`) via `SseEmitter`. The frontend displays a live timeline of stage progress.

### 7.4 API Client

File: `frontend/src/api/index.ts`

Typed Axios wrapper with 15 methods covering: task CRUD, rerun, per-stage data retrieval, LLM config management.

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

**Concurrency caveat**: `PipelineEngine.execute()` does **not** check if a pipeline is already running for a given CVE. Two rapid "rerun" clicks will spawn two pipelines competing for the same workspace files.

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

The `run_command` tool has a **command whitelist** (25 allowed commands: curl, wget, grep, cat, ls, find, head, tail, bash, test, echo, java, mvn, wc, sort, uniq, chmod, mkdir, pwd, diff, file, xxd, base64, sha256sum, md5sum). Only the first word is checked.

### 11.3 API Key Masking

ConfigController masks API keys in responses (replaces with `••••••••`). Keys sent to the server are never returned to the frontend after creation.

---

## 12. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| DB + Filesystem dual storage | DB for structured metadata (task status, stage progress, config); filesystem for large outputs (diffs, code analysis, artifacts). Enables simple backup/restore. |
| H2 file-based database | Zero-config, no external DB dependency, sufficient for single-user desktop use. |
| Absolute path resolution in WorkspaceManager | `Paths.get(root).toAbsolutePath()` prevents ambiguity but means restarts from different CWDs can create fragmented workspaces. User must always start from the project root. |
| StageResult.success() for paused state | Stage 5 pauses are not failures — if we returned failure, the PipelineEngine would stop the pipeline. The frontend checks `status: "paused"` to show the "Continue" button. |
| "Configure, don't simulate" (Stage 5) | The vulnerability must be triggered through the real library. If the LLM simulates vulnerable behavior in application code, the results are misleading. |
| No CSP/Security Headers | Not required — this is a local development tool, not a production web app. |

---

## 13. Known Issues & Limitations

### 13.1 Workspace Path Fragility

`WorkspaceManager(workspaceRoot)` resolves relative paths against `System.getProperty("user.dir")`. If the JAR is started from a different directory, the workspace path changes and previously stored data becomes invisible to the API. Users must always start from the project root.

### 13.2 Concurrent Pipeline Execution

No guard against running two pipelines for the same CVE. This causes file races in Stage 5 (multiple agents writing pom.xml) and port conflicts (multiple vuln-demo processes on 18080).

### 13.3 Mixed Typed/Deserialized Data

When pipeline stages are rerun with cached upstream stages, data is loaded as `Map` (Jackson `LinkedHashMap`) rather than the original typed class (e.g., `CveIntelligence`). Downstream stages must handle both — they use `instanceof` checks and fall back to `JsonNode` path traversal. This is fragile: adding a field to a model class requires adding the extraction logic in multiple places.

### 13.4 H2 File DB Not Portable

The H2 database file (`data/jvuln.mv.db`) is tied to the H2 version and JVM. Moving between machines requires re-creating the DB via the `POST /api/analysis/sync-status` endpoint.

### 13.5 No Agent Concurrency Control

Stage 5 agent pauses are safe (checkpoint saved), but there is no mechanism to prevent multiple "Continue" clicks from spawning concurrent agents for the same CVE.

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
