# Stage 5: Report Generation — Design

**Date:** 2026-07-17
**Status:** Approved design, pending implementation plan

## Problem

Report generation currently lives inside Stage 4 (Artifact Generation). The Stage 4 agent
writes `report/report.md` as one of its deliverables via file tools during its agent loop.
This couples report writing to the artifact-building agent's turn budget and phase logic, and
means the report competes for the agent's attention with getting the demo+PoC to actually run
and verify.

We want report generation to be its own pipeline stage (Stage 5) that runs after Stage 4
completes, takes the outputs of stages 1–4 plus the actually-generated demo/PoC files as input,
and produces `report/report.md` via a single LLM call.

## Goals

- Add a fifth pipeline stage, `ReportGenerationStage` (`number() = 5`, `name() = "Report Generation"`).
- Generate the report with a **single LLM call** (not an agent loop).
- Feed the call: stage 1–4 outputs **and** the real generated `vuln-demo` source/config + `poc/exploit.sh`.
- If report generation fails, **fail the whole pipeline** (consistent with other stages).
- Full frontend wiring: Stage 5 appears as a distinct step; the existing Report tab reads its output.
- Remove report writing from Stage 4.

## Non-goals

- No change to the fixed 6-section Chinese report structure or its content rules.
- No change to how the report is served (`GET /{cveId}/report` and the frontend Report tab already exist).
- No agent loop / file tools for Stage 5.

## Key findings from codebase exploration

- **Stage interface** (`com.jvuln.pipeline.stage.Stage`): `int number()`, `String name()`,
  `StageResult execute(PipelineContext)`. Stages are Spring beans, auto-discovered and sorted by
  `number()` in `PipelineEngine` (constructor). Adding a `@Component` stage requires **no** engine change.
- **Checkpoint/skip**: `PipelineEngine.runStages` skips a stage when `stage.number() < fromStage`
  and `workspaceManager.isStageComplete(cveId, number)` (i.e. `stages/<n>.json` exists). So Stage 5
  must `writeStageData(cveId, 5, ...)` to participate in skip/resume.
- **`stages/report.md`** already exists and contains the full report prompt (identical to the
  standalone prompt authored earlier). It is currently **not wired to anything**.
- **Prompt layering** (`ChatCaller.buildBody`): system = `global.md`, then relevant-diff, then the
  stage prompt (from `LlmPromptStage`), then a `user` message = `request.getTaskPrompt()`, then the
  request messages. The stage prompt loaded via `LlmPromptStage` is injected **raw** — `{{cve_id}}`
  is NOT rendered on this path.
- **`LlmRequest.reasoning(...)` sets `jsonMode=true`** → wrong for a Markdown report. Stage 5 must
  use `LlmRequest.generation(...)` (`jsonMode=false`).
- **Dead code to repurpose**: `com.jvuln.generator.report.DataExtractor` is a public `@Component`
  that duplicates `StageDataExtractor`'s extraction methods (`extractDiff`,
  `extractVulnerabilityFacts`, `extractTriggerChain`, `extractRootCause`, `extractArtifact`) but is
  **injected nowhere**. `com.jvuln.generator.workspace.WorkspaceSkeletonGenerator` is likewise dead
  (only `WorkspaceFileRenderer` from that package is used). Stage 5 will live in the existing
  `com.jvuln.generator.report` package and use `DataExtractor`.

## Design

### 1. `stages/report.md` audit fixes (approved)

The file was written as a Stage-4 agent system prompt (file-writing agent that "built" the demo).
As a Stage-5 single-call, output-only system prompt it needs three fixes:

1. **Reframe "you built / generated in this run"** (lines ~5, 58, 60) → "the demo and PoC provided
   in the input below". A fresh model receives these as input; it did not build them.
2. **Replace the file-writing Constraints section** (lines ~65–68, "Write the report to
   `report/report.md`", "All report file paths must start with `report/`") with an **output
   contract**: output ONLY the report Markdown — no preamble, no explanation, no surrounding
   code fence.
3. **De-template `{{cve_id}}`**: because Stage 5 loads this file via the `LlmPromptStage` enum path
   (raw, unrendered), remove `{{cve_id}}` from `stages/report.md`. Instruct the model to use the
   CVE id supplied in the user prompt. The real CVE id is carried in `current/report-user.md`
   (which IS rendered).

Unchanged: the fixed 6-section Chinese structure, the 情报 table, the code-heavy 漏洞分析 / 触发链
rules, and the Stage-3-facts references.

### 2. New `LlmPromptStage` enum value

Add `REPORT_GENERATION("report")` to `com.jvuln.llm.LlmPromptStage`. Its `getResourcePath()`
resolves to `prompts/stages/report.md`, which already exists — so `StagePromptResourcesTest`
(iterates all enum values, asserts each stage prompt resolves & is non-empty) stays green.

This gives the Stage 5 call the `global.md` system layer, consistent with stages 1–4.

### 3. `DataExtractor` (repurpose existing dead class)

In `com.jvuln.generator.report.DataExtractor` (already public `@Component`), add one method it
lacks vs. `StageDataExtractor`:

- `String trimIntelligence(Object data)` — copies `cveId`, `cweId`, `description`, `cvss`,
  `fixedVersion`, `artifact`, `affectedVersions` (same as `StageDataExtractor.trimIntelligence`).

All other extraction methods already exist on it.

### 4. Generated-file reader

Stage 5 needs the actual demo/PoC contents. Add a small helper (private method or tiny collaborator)
that reads, from the CVE workspace:

- `vuln-demo/pom.xml`
- `vuln-demo/src/main/resources/application.properties`
- `vuln-demo` Java sources under `src/main/java/**` (the CVE-specific ones)
- `poc/exploit.sh`

Concatenate into a labeled, **size-capped** blob (per-file cap + total cap, truncation marker) so
the prompt does not overflow. Missing files are skipped gracefully (Stage 4 may not have produced
all of them). Skeleton files (`Application.java`, `LabInfoController.java`, `build.sh`, `run.sh`)
are of low report value; include the CVE-specific sources preferentially. Exact selection/caps to
be finalized in the plan; default caps ~8KB/file, ~40KB total.

### 5. `ReportGenerationStage`

New `@Component` in `com.jvuln.generator.report`:

- `number()` → `5`, `name()` → `"Report Generation"`.
- `execute(ctx)`:
  1. Extract stage 1–4 inputs via `DataExtractor`: trimmed intelligence, vulnerability facts
     (stage 2), trigger chain + root cause (stage 3), patch diff, artifact.
  2. Read generated demo/PoC files (section 4).
  3. Build the request as `LlmRequest.generation(LlmPromptStage.REPORT_GENERATION, taskPrompt, userPrompt)`:
     - **System layer** = `global.md` + `stages/report.md` (injected by `ChatCaller` via the enum
       path). This is the sole source of the report structure/rules.
     - **`taskPrompt`** = a short, one-line task instruction (e.g. "为下面提供的 CVE 生成漏洞分析报告，严格遵循系统提示中的结构。"),
       NOT a second copy of the structure — avoids duplicated/conflicting instructions.
     - **`userPrompt`** = `current/report-user.md` rendered with all inputs (incl. real `cve_id` and
       the `generated_files` blob) — the data carrier.
  4. Single `ctx.getLlmClient().chat(request)` call; retry a couple times on empty output
     (mirror `ReasoningStage`'s retry loop with backoff).
  5. Strip any markdown fence from the response; if the result is empty after stripping, treat as failure.
  6. Write `report/report.md` (via `WorkspaceManager` / direct file write to the CVE report dir).
  7. `writeStageData(cveId, 5, summary)` where summary is a small JSON object
     (`{ reportPath, charCount }`) for checkpoint/skip support.
  8. Return `StageResult.success(5, name(), summary)`; on retry exhaustion return
     `StageResult.failure(5, name(), msg)` → fails the pipeline.

### 6. New user prompt `current/report-user.md`

A rendered user prompt (like `reasoning-user.md`) carrying, with `{{...}}` variables:

- `{{cve_id}}`, `{{intelligence}}`, `{{vulnerability_facts}}`, `{{trigger_chain}}`,
  `{{root_cause}}`, `{{patch_diff}}`, `{{artifact}}`, and `{{generated_files}}` (the demo/PoC blob).

### 7. `PipelineConstants`

- `TOTAL_STAGES`: `4` → `5`.
- Add `STAGE_REPORT = 5`.
- Extend `STAGE_NAMES` with `"Report Generation"` (index 4).

### 8. Stage 4 cleanup (remove report writing)

Report generation moves out of Stage 4, so the Stage-4 agent should no longer be told to write
`report/report.md`. Affected code:

- `artifact-agent-system.md` / `artifact-agent-user.md`: already cleaned in prior task (deliverables
  = demo + poc only). Verify no report directive remains.
- `AgentPhase.REPORT`, `AgentPhaseEngine` (lines ~36, ~100, ~278–287): remove report phase / report
  directives from the phase state machine.
- `ReviewEngine` (~line 108): drop `report/report.md` from review candidates.
- `ToolDefinitionBuilder` (~lines 29, 31, 98): remove `reportStrategy` from `submit_plan` schema &
  required list; remove `report_status` from `finish`.
- `ExecutionPlan`: remove the `reportStrategy` field and its `isComplete()` requirement.
- `ArtifactGenStage` (~lines 322, 380, 402, 419, 440, 518): remove `wroteReport` tracking, the
  REPORT-phase branch, `report_status` in forced summary, and the `reportStrategy` validation error.
- `WorkspaceManager` / `SkeletonWriter` still create the `report/` directory — **keep** it, Stage 5
  writes there.

The `report/report.md` served by `AnalysisQueryService` / `AnalysisController` is unchanged; it now
comes from Stage 5.

### 9. Frontend wiring

- `AnalysisController.getStageJson`: validation `stageNum > 4` → `> 5` (allow stage 5 JSON fetch).
- `AnalysisDetail.vue`: `stageIcons` add `'05'`; `loadStageData` fetch stage 5 (optional — stage 5's
  JSON summary is small; the Report tab reads `/{cveId}/report` regardless).
- Locales `zh-CN.ts` / `en-US.ts`: add the Stage 5 name to `analysis.stageNames` (and any place that
  enumerates 4 stage names).

## Testing

- **`StagePromptResourcesTest`** must stay green with the new enum value (guaranteed since
  `stages/report.md` exists and is non-empty).
- **Backend build**: `mvn -q -pl backend/jvuln-stages,backend/jvuln-app -am compile` (and existing tests).
- **Stage 5 unit test**: mock `LlmClient`, feed fixture stage data + workspace files, assert
  `report/report.md` is written and `StageResult.success(5, ...)`; assert failure path returns
  `StageResult.failure` on empty LLM output.
- **Frontend**: `npm run build` (type-check) for the Vue/locale changes.
- **Manual**: run a pipeline end-to-end for one CVE; confirm Stage 5 appears, the Report tab renders,
  and Stage 4 no longer writes the report.

## Risks / open items

- **Prompt size**: generated-file blob + patch diff + stage data could be large. Mitigated by
  per-file and total caps; finalize caps in the plan.
- **`taskPrompt` vs enum-layered stage prompt**: both the enum path (`stages/report.md` raw) and the
  `taskPrompt` become system/user content. The plan must keep `stages/report.md` as the source of
  the structure and `report-user.md` as the data carrier, with a minimal `taskPrompt` to avoid
  duplicated/conflicting instructions.
- **Resume semantics**: re-running from stage ≤4 will re-run Stage 5 unless `stages/5.json` exists;
  acceptable and consistent with other stages.
