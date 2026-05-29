# JVuln Platform

JVuln Platform is a Java CVE analysis and security education platform. Given a CVE ID, it collects vulnerability intelligence, locates fixing patches, analyzes changed Java code, asks an LLM to reason about root cause and trigger chain, and generates local educational artifacts.

中文版文档：[`README.zh-CN.md`](README.zh-CN.md)

## Architecture

```
jvuln-platform/
├── backend/          # Spring Boot 2.7 (Java 8), multi-module Maven
│   ├── jvuln-app         # Application entry point + REST API
│   ├── jvuln-pipeline    # 5-stage Pipeline engine + SSE progress
│   ├── jvuln-collector   # Stage 1: intelligence collection (NVD/GHSA/OSV)
│   ├── jvuln-patcher     # Stage 2: patch locating + diff parsing
│   ├── jvuln-analyzer    # Stage 3: JavaParser static analysis + diff filtering
│   ├── jvuln-llm         # AI abstraction layer (Anthropic + OpenAI-compatible)
│   ├── jvuln-generator   # Stage 5: artifact generation
│   └── jvuln-store       # Storage layer (H2 + filesystem workspace)
├── frontend/         # Vue 3 + TypeScript + Vite + Element Plus
└── docs/             # Design documentation
```

**Storage strategy:** H2 stores task/config indexes and status. Analysis data is stored as JSON and files under `backend/workspace/CVE-xxx/`.

## Requirements

| Component | Version |
|-----------|---------|
| Java | 8+ |
| Maven | 3.8+ |
| Node.js | 18+ |
| LLM | Anthropic API or any OpenAI-compatible endpoint |

## Quick Start

### 1. Build the backend

```bash
cd backend
mvn install -DskipTests -q
```

### 2. Start the backend

```bash
# Start from backend/ because the H2 database path depends on the working directory.
cd backend
java -jar jvuln-app/target/jvuln-app-1.0.0-SNAPSHOT.jar
```

The backend listens on `http://localhost:8080` by default.

### 3. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend listens on `http://localhost:5173` by default.

### 4. Configure an LLM

Open `http://localhost:5173/settings`. The Settings page supports multiple LLM configurations and one active configuration at a time.

| Field | Description |
|-------|-------------|
| Name | Optional display name, such as `Claude Sonnet via proxy` |
| Provider | `anthropic`, `openai-compat`, `openai`, `deepseek`, or `ollama` |
| Base URL | API base URL, such as `https://api.anthropic.com` or a local proxy endpoint |
| API Key | API key for the selected provider |
| Model | Model name, such as `claude-sonnet-4-6` |

- **Activate:** make this configuration the active Pipeline LLM.
- **Test:** send a lightweight request to verify connectivity and credentials.

## Frontend UI and Localization

The frontend uses an IBM Carbon-inspired dark theme: angular components, high-contrast dark surfaces, blue primary actions, and semantic status colors.

The UI is bilingual:

- Chinese resources: `frontend/src/locales/zh-CN.ts`
- English resources: `frontend/src/locales/en-US.ts`
- i18n helper: `frontend/src/i18n/index.ts`

The language switcher is in the header. The selected language is persisted in `localStorage` under `jvuln-locale`. The default language is Chinese.

## Usage Flow

1. Open `http://localhost:5173` and click **New Analysis**.
2. Enter a CVE ID, for example `CVE-2025-24813`.
3. Watch the 6-stage Pipeline progress in real time through SSE.
4. After completion, review:
   - **Overview / Intelligence:** CVSS, CWE, affected artifact, source repository, and references
   - **Patch Diff:** Carbon-styled `diff2html` patch view with side-by-side and line-by-line modes
   - **Code Analysis:** JavaParser method analysis and CWE pattern matches
   - **AI Reasoning:** trigger chain, root cause, exploitability, fix assessment, and detection points
   - **Stage Logs:** per-stage status and error messages
5. Optionally, run **Product Scan** to check your own Java project against the analyzed vulnerability.

## 6-stage Pipeline

| Stage | Name | Description |
|-------|------|-------------|
| 1 | Intelligence Collection | Collect CVE data from NVD, GHSA, OSV, Maven, and references |
| 2 | Patch Locating | Locate fixing commits and extract unified diff |
| 3 | Code Analysis | Filter relevant diff files, parse Java AST, and match CWE patterns |
| 4 | Vulnerability Reasoning | Use the active LLM to reason about trigger chain, root cause, fix quality, and generate machine-executable detection points |
| 5 | Vulnerability Education Lab | Generate local educational demo project (Spring Boot with vulnerable library configuration), PoC scripts, and educational report. Uses multi-step generation with compile/startup/PoC verification loops. |
| 6 | Product Vulnerability Detection | Scan project source code using Stage 4 detection points, generate project-specific trigger chains and verification PoCs |

The Pipeline supports resume and rerun. Completed stages can be loaded from workspace files, and `fromStage` can force rerun from a selected stage.

## API Endpoints

```
POST /api/analysis                      # Submit a new analysis task
GET  /api/analysis                      # List tasks
GET  /api/analysis/{cveId}              # Task detail + stage status
GET  /api/analysis/{cveId}/stream       # SSE progress stream
POST /api/analysis/{cveId}/rerun        # Rerun (?fromStage=N)
POST /api/analysis/{cveId}/sync-status  # Sync DB status from workspace files

GET  /api/analysis/{cveId}/intelligence
GET  /api/analysis/{cveId}/patch
GET  /api/analysis/{cveId}/diff         # Returns {diff, totalFiles, shownFiles}
GET  /api/analysis/{cveId}/code-analysis
GET  /api/analysis/{cveId}/reasoning

POST /api/scan                          # Submit a scan task (cveId + projectPath)
GET  /api/scan/{scanId}                 # Get scan result
GET  /api/scan?cveId={cveId}            # List scans by CVE
GET  /api/scan/{scanId}/poc             # Download verification PoC

GET    /api/config/llm                  # List LLM configurations
POST   /api/config/llm                  # Create an LLM configuration
PUT    /api/config/llm/{id}             # Update an LLM configuration
DELETE /api/config/llm/{id}             # Delete an LLM configuration
POST   /api/config/llm/{id}/activate    # Activate an LLM configuration
POST   /api/config/llm/{id}/test        # Test an LLM configuration
```

## Workspace Layout

```
backend/workspace/CVE-xxxx-xxxxx/
├── stages/
│   ├── 1_intelligence.json
│   ├── 2_patch.json
│   ├── 3_analysis.json
│   ├── 4_reasoning.json
│   └── 5_artifacts.json
├── patches/
│   ├── fix.diff
│   └── source/{before,after}/
├── vuln-demo/        # Generated Spring Boot reproduction project
├── poc/
├── report/
└── scan-{projectName}/   # Stage 6 product scan results
    ├── scan-result.json
    ├── trigger-chain.json
    ├── verify-exploit.py
    └── remediation.md
```

## Stage 5: Education Lab Generation

Stage 5 generates a complete educational environment for vulnerability reproduction:

**Design Philosophy:** The vulnerability lives in the library/container, not in application code. Generated demos configure the vulnerable component to expose its natural code path, rather than simulating vulnerability behavior in custom controllers.

**Multi-step Generation:**
1. **SubStage A: Vuln-Demo Project**
   - Local skeleton: `pom.xml` (with vulnerable dependency version), `Application.java`, build/run scripts
   - LLM generates: Configuration classes (e.g., `TomcatConfig` to enable DefaultServlet PUT + FileStore sessions) and normal business code (e.g., `NoteController` CRUD API)
   - Compile verification loop (max 2 fix rounds)
   - Startup verification loop (max 2 fix rounds)

2. **SubStage B: PoC Scripts**
   - LLM generates: Exploit scripts that target the library's vulnerable code path directly (e.g., partial PUT to Tomcat's DefaultServlet)
   - Includes verification command (exit code 0 = success)
   - PoC verification loop (max 2 fix rounds, can modify both PoC and vuln-demo)

3. **SubStage C: Educational Report**
   - LLM generates: Markdown report covering vulnerability mechanics, exploitation steps, and mitigation strategies

**Version Resolution:**
- Spring Boot managed dependencies (e.g., `tomcat-embed-core`) → override version property (e.g., `<tomcat.version>9.0.98</tomcat.version>`)
- Non-managed dependencies → add explicit `<dependency>` with version
- Derives vulnerable version from `fixedVersion` when not explicit (e.g., 9.0.99 → 9.0.98)

**Example Output (CVE-2025-24813):**
- `TomcatConfig.java` — Configures Tomcat DefaultServlet with `readonly=false` and FileStore session persistence
- `NoteController.java` — Normal CRUD REST API (not vulnerability-related)
- `exploit.sh` — Sends partial PUT with `Content-Range` to Tomcat's DefaultServlet
- `report.md` — Educational analysis of the vulnerability

## Verified CVEs

| CVE | Component | CVSS | Type |
|-----|-----------|------|------|
| CVE-2025-24813 | Apache Tomcat | 9.8 | Partial PUT RCE (CWE-44 + CWE-502) |
| CVE-2023-25330 | MyBatis-Plus | 9.8 | SQL Injection (TenantLine/DataPermission) |
| CVE-2022-22965 | Spring Framework | 9.8 | Spring4Shell RCE |
| CVE-2023-3276 | — | — | — |
| CVE-2016-1000027 | Spring Web | — | — |

## Notes

- This platform is intended for local security education and authorized research only.
- Generated demos and PoCs should only be run in local controlled environments.
- LLM reasoning requires network access to Anthropic or a compatible API endpoint.
- If using a local relay/proxy, set its request timeout high enough for reasoning tasks, preferably at least 300 seconds.
