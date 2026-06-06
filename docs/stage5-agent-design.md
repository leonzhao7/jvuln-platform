# Stage 5 — Agent Design Document

**Version**: 3.0  
**Last Updated**: 2026-06-06  
**Purpose**: Keep Stage 5 design, runtime behavior, and current implementation aligned for debugging and follow-on development.

---

## 1. Overview

Stage 5 (`Artifact Generation`) is the final analysis stage in jvuln-platform. It consumes:

- Stage 1 intelligence
- Stage 2 patch/diff data
- Stage 4 trigger-chain/root-cause reasoning

and produces three workspace deliverables:

| Deliverable | Description | Path |
|-------------|-------------|------|
| `vuln-demo` | Runnable Spring Boot lab wired to the real vulnerable component path | `vuln-demo/` |
| `poc` | PoC or verification script | `poc/exploit.sh` |
| `report` | Educational Markdown report | `report/report.md` |

Unlike Stages 1–4, Stage 5 is not a single prompt/JSON exchange. It is a backend-governed agent loop with:

- explicit phases
- tool restrictions per phase
- backend-controlled compile/startup/PoC validation
- persisted attempt memory
- reviewer and validator reconciliation

The implementation lives in [ArtifactGenStage.java](/root/workspace/jvuln-platform/backend/jvuln-generator/src/main/java/com/jvuln/generator/ArtifactGenStage.java).

---

## 2. Current Execution Model

### 2.1 High-Level Flow

```text
ArtifactGenStage.execute(ctx)
  1. Extract upstream context
  2. Build CVE-specific verification plan
  3. Write minimal skeleton (Application.java, build.sh, run.sh)
  4. Discover existing files in vuln-demo/poc/report
  5. Load attempt memory + transient checkpoint
  6. Start fresh turn budget, but append prior-attempt memory to prompt
  7. Run phase-driven agent loop (max 80 turns)
  8. Auto-validate after writes
  9. Review finish() summary against backend evidence
 10. Persist 5_artifacts.json + 5_memory.json
 11. Always stop the demo process in finally
```

### 2.2 Runtime Intent

The backend now pushes the model toward a "small Codex" workflow:

1. plan once
2. write a broad minimal candidate
3. let backend validation decide compile/startup/PoC state
4. patch only the current gap
5. finish as soon as validation is green

The backend is intentionally opinionated. It no longer lets the model drift through open-ended trial and error without phase, evidence, or convergence pressure.

---

## 3. Phase Model

Stage 5 uses explicit phases:

- `PLAN`
- `GENERATE_MINIMAL`
- `COMPILE_FIX`
- `STARTUP_FIX`
- `POC_FIX`
- `REPORT`
- `FINISHED`

Phase transitions are backend-controlled:

- `submit_plan` moves `PLAN -> GENERATE_MINIMAL`
- auto/manual validation maps failures to `COMPILE_FIX`, `STARTUP_FIX`, or `POC_FIX`
- fully green validation maps to `REPORT`
- low remaining turn budget can force `REPORT`

### 3.1 Why the phase model exists

This addresses the original non-convergence problems:

- repeated planning without writing files
- debugging the wrong layer
- continuing to edit after backend validation already passed
- wasting turns on exploratory reads and ad-hoc curl loops

---

## 4. Tool Surface

### 4.1 Current Tool Catalog

Stage 5 currently exposes 7 tools:

| Tool | Purpose |
|------|---------|
| `submit_plan` | Register execution plan before normal write/validate flow |
| `write_file` | Write one workspace file |
| `write_files` | Write multiple workspace files in one call |
| `read_file` | Read an existing workspace file |
| `validate_artifacts` | Run backend validation with focus `compile`, `startup`, `poc`, or `full` |
| `inspect_runtime` | Return backend-known runtime metadata/history/evidence |
| `finish` | Submit final summary for reviewer/backend reconciliation |

There is no longer a free-form `run_build`, `start_app`, or `run_command` tool exposed to the model. Build/startup/PoC execution is owned by the backend validator.

### 4.2 Tool availability by phase

| Phase | Allowed tools |
|-------|---------------|
| `PLAN` | `submit_plan`, `write_file`, `write_files`, `read_file` |
| `GENERATE_MINIMAL` | `write_file`, `write_files`, `read_file` |
| `COMPILE_FIX` / `STARTUP_FIX` / `POC_FIX` | `write_file`, `write_files`, `read_file`, `inspect_runtime`, `validate_artifacts`, `finish` |
| `REPORT` | `write_file`, `write_files`, `read_file`, `inspect_runtime`, `finish` |

The backend rejects tools that do not match the current phase and returns a structured directive telling the model what the actual gap is.

---

## 5. Execution Plan Requirement

Before normal execution, the model must submit `submit_plan`.

The stored plan includes:

- `goal`
- `firstBatchFiles`
- `minimalDeliverables`
- `validationSequence`
- `deferredUntilVerified`
- `risks`
- `reportStrategy`

The plan is not a ceremonial artifact. It is used for:

- resume context
- frontend display
- attempt memory
- convergence pressure

The backend also now allows same-turn `submit_plan` plus writes, so planning does not cost a dedicated round if the model can move immediately.

---

## 6. Validation-First Control Loop

### 6.1 Core rule

After writing demo or PoC files, the backend automatically validates instead of waiting for the model to decide whether to compile or test.

### 6.2 Validation focus

Backend chooses validation focus by context:

- initial broad write -> `full`
- compile/startup repair paths -> `full`
- some minimal cases may use `startup`

Manual `validate_artifacts` is still available when the model wants an explicit re-check.

### 6.3 Validation output

The validator returns a structured result:

- `compileOk`
- `startupOk`
- `pocVerified`
- `compileMessage`
- `startupMessage`
- `pocMessage`
- CVE-specific evidence artifacts

This validation result is the main control signal for subsequent phases.

---

## 7. Attempt Memory and Resume

Stage 5 persists two kinds of state:

### 7.1 `5_checkpoint.json`

Transient pause marker containing:

- completed turn count
- last error
- written files
- timestamp

It is single-use and deleted when a new rerun starts.

### 7.2 `5_memory.json`

Longer-lived attempt memory containing:

- prior turn count
- prior review revisions
- verification plan
- execution plan
- latest validation/review
- build/startup/command history
- compacted attempt records

Important behavioral change:

- rerun starts with a fresh turn budget
- prior memory is used as context, not as restored control state

This avoids inheriting a bad loop state while still preserving useful evidence.

---

## 8. Convergence Controls

### 8.1 Turn budget

- `MAX_AGENT_TURNS = 80`

This is intentionally high as a hard cap, but the implementation is optimized for far fewer real turns.

### 8.2 Urgency injection

When nearing the limit, backend injects messages to push the model toward report/finish rather than further exploration.

### 8.3 No-progress guard

A backend progress guard now tracks repeated turns with no file changes under the same validation signature. If too many consecutive no-progress turns occur, Stage 5 aborts with an explicit reason rather than wandering indefinitely.

### 8.4 Smallest-gap directives

After validation, the backend emits a directive with:

- current phase
- expected state
- actual observed gap
- smallest allowed next actions
- focused fix hint

This is meant to prevent broad rewrites when only one layer is broken.

---

## 9. Process and Port Hygiene

One of the concrete Stage 5 failures was not code-related: stale demo processes occupied port `18080`, so startup kept failing and the model kept repairing the wrong thing.

Current behavior before startup:

1. stop tracked demo process if still alive
2. scan the same CVE workspace for stale demo Java processes
3. kill stale processes
4. check whether port `18080` is already in use
5. if still blocked, record a clear startup failure with port diagnostics

This changed Stage 5 from "LLM keeps trying random startup fixes" to "backend diagnoses environment failure explicitly."

---

## 10. Reviewer / Validator Reconciliation

Stage 5 uses both:

- backend validator
- LLM reviewer (`gen_verifier`)

### 10.1 Old failure mode

Previously, backend validation could already be green, but reviewer still returned `unverified`, which caused extra rounds and even destructive rewrites after success.

### 10.2 Current rule

If backend validation proves:

- `compileOk == true`
- `startupOk == true`
- `pocVerified == true`

then reviewer is not allowed to block completion. If it still says `unverified`, backend logs the discrepancy and falls back to a verified review result.

This is the key fix that prevented Stage 5 from continuing to grow rounds after actual success.

---

## 11. Pause and Failure Semantics

### 11.1 LLM transient errors

`chatWithRetry()` retries on:

- 500 / 502 / 503
- 429
- some 403 / overloaded conditions
- "returned no content"
- `LLM API error 200` style empty-body proxy failures

### 11.2 Persistent LLM failure

If retries are exhausted:

- checkpoint is saved
- attempt memory is updated
- `5_artifacts.json` is written with `status: "paused"`
- stage returns `StageResult.failure(...)`

This is another implementation change from earlier versions that treated paused state too optimistically.

### 11.3 Sync-status handling

`sync-status` now inspects Stage 5 JSON and treats paused/unverified/failure states carefully instead of blindly marking the stage completed because the file exists.

---

## 12. Output Format

The main Stage 5 output file is:

- `backend/workspace/{cveId}/stages/5_artifacts.json`

Current output includes more than the original artifact summary. It can contain:

- `status`
- `agentTurns`
- `reviewRevisions`
- `abortReason`
- `vulnDemo`
- `poc`
- `report`
- `verificationPlan`
- `executionPlan`
- `verification`
- `validation`
- `reproductionSteps`

The frontend no longer relies on a raw JSON block in the stage detail page, but the backend now exposes per-stage JSON retrieval APIs for debugging and automation.

---

## 13. API Surface Relevant to Stage 5

The controller now exposes:

- `GET /api/analysis/{cveId}/artifacts`
- `GET /api/analysis/{cveId}/report`
- `GET /api/analysis/{cveId}/stages/{stageNum}/json`
- `POST /api/analysis/{cveId}/rerun?fromStage=5`
- `POST /api/analysis/{cveId}/sync-status`

The generic `stages/{stageNum}/json` endpoint exists specifically because the frontend no longer renders raw JSON dumps at the bottom of each stage detail page.

---

## 14. CVE-Specific Validation

Stage 5 has a generic validation shell, but some CVEs require backend-owned success criteria.

`CVE-2025-24813` is the main example:

- validator checks lab info endpoint
- accepts `/api/lab/info` and `/api/lab-info`
- accepts `tempdir`, `tempDir`, and `tempDirPath`
- checks predictable temp file evidence under `ServletContext.TEMPDIR`
- verifies expected byte patterns after partial PUT

This is the current direction for Stage 5 quality:

- use LLM to generate/repair artifacts
- use backend-owned CVE-specific validators to decide whether the exploit proof is real

---

## 15. Frontend Impact

Recent Stage 5-related frontend changes:

- stage headers were simplified
- stage tabs on the main analysis page were removed in favor of click-to-view results
- Stage 2 no longer shows inline file diff in the stage detail body
- Stage 3 detail order is now: full diff view, CWE analysis, then call chain
- per-stage raw JSON blocks were removed from the detail pages

The frontend still displays Stage 5 status cards, file list, plan summary, validation evidence, and reproduction steps from `5_artifacts.json`.

---

## 16. Verified Outcome for CVE-2025-24813

After the current fixes:

- stale port/process conflict was resolved by backend cleanup
- plan loop no longer gets stuck in `PLAN`
- backend validation can drive `POC_FIX -> REPORT`
- reviewer cannot block completion after validator success

Observed successful run result:

- `agentTurns = 4`
- `compileOk = true`
- `startupOk = true`
- `pocVerified = true`

This is the current reference example that Stage 5 is converging correctly instead of growing turns indefinitely.

---

## 17. Files to Read When Modifying Stage 5

Primary code:

- [ArtifactGenStage.java](/root/workspace/jvuln-platform/backend/jvuln-generator/src/main/java/com/jvuln/generator/ArtifactGenStage.java)
- [AnalysisController.java](/root/workspace/jvuln-platform/backend/jvuln-app/src/main/java/com/jvuln/controller/AnalysisController.java)
- [PipelineEngine.java](/root/workspace/jvuln-platform/backend/jvuln-pipeline/src/main/java/com/jvuln/pipeline/PipelineEngine.java)

Prompts:

- [system_gen_agent.txt](/root/workspace/jvuln-platform/backend/jvuln-app/src/main/resources/prompts/system_gen_agent.txt)
- [user_gen_agent.txt](/root/workspace/jvuln-platform/backend/jvuln-app/src/main/resources/prompts/user_gen_agent.txt)
- [system_gen_verification_plan.txt](/root/workspace/jvuln-platform/backend/jvuln-app/src/main/resources/prompts/system_gen_verification_plan.txt)
- [user_gen_verification_plan.txt](/root/workspace/jvuln-platform/backend/jvuln-app/src/main/resources/prompts/user_gen_verification_plan.txt)
- [system_gen_verifier.txt](/root/workspace/jvuln-platform/backend/jvuln-app/src/main/resources/prompts/system_gen_verifier.txt)
- [user_gen_verifier.txt](/root/workspace/jvuln-platform/backend/jvuln-app/src/main/resources/prompts/user_gen_verifier.txt)

Frontend:

- [AnalysisDetail.vue](/root/workspace/jvuln-platform/frontend/src/views/AnalysisDetail.vue)
- [zh-CN.ts](/root/workspace/jvuln-platform/frontend/src/locales/zh-CN.ts)
- [en-US.ts](/root/workspace/jvuln-platform/frontend/src/locales/en-US.ts)

---

## 18. Verification Checklist

After modifying Stage 5, verify at minimum:

- backend build passes: `mvn -pl jvuln-generator,jvuln-pipeline,jvuln-app -am test -DskipTests`
- `./start.sh --build` starts frontend and backend
- `POST /api/analysis/{cveId}/rerun?fromStage=5` works
- `GET /api/analysis/{cveId}` reports the correct final stage/task state
- `GET /api/analysis/{cveId}/artifacts` returns consistent `validation` and `verification`
- `GET /api/analysis/{cveId}/stages/5/json` returns the same Stage 5 JSON used by the UI
- paused and failed runs preserve useful `failureReason` / `pauseReason`
- successful runs do not continue editing after backend validation is fully green
