# Stage 1 Patch-Commit Inference & Multi-Major Version Selection — Design

**Date:** 2026-07-24
**Status:** Approved (design phase)

## Problem

CVE-2021-42392 (H2 Console JNDI injection RCE, fixed in 2.0.206) produced a demo
for a completely unrelated vulnerability. Trace-back of the failure chain:

1. **Stage 1** correctly identified the CVE (JNDI RCE, `com.h2database:h2`,
   `fixedVersion=2.0.206`) but collected **no fix commits** — neither GHSA nor OSV
   advisory references contained a `/commit/` URL, so `fixCommits=[]`.
2. **Stage 2** fell through to `GhsaCommitStrategy.diffFromReleaseTag`, a
   **keyword heuristic** that lists commits between the previous and fixed release
   tags and picks the *first* commit whose message contains `fix`/`security`/
   `cve`/`jndi`/`inject`/`patch`. It matched the unrelated commit
   `0ebf1422` — message *"Fix group-sorted optimization for data types with
   different equal values"* (a GROUP BY timezone bug).
3. **Stage 3/4** then reasoned and generated against the wrong patch (GIGO).
4. The runtime tracer confirmed the diagnosis: the exploit's named method
   `Select.fetchNextRow` was reached **0 times** — the category-A "wrong route"
   signal the tracer exists to surface.

Root cause: **empty `fixCommits` from Stage 1** combined with a **weak
keyword-based commit-selection fallback in Stage 2**.

A second latent issue: the fix may span **multiple major versions**
(e.g. a `1.4.x` line and a `2.0.x` line). The demo should be built against a
version we can actually locate a patch for, and Stage 4 currently picks the demo
version via a heuristic (`affectedVersions.to` if it differs from
`fixedVersion`, else `from`) rather than an authoritative recorded value.

## Goals

1. **Stage 1** — when no patch commit is found, fetch the full commit log of the
   fixed release branch and let the LLM infer the actual patch commit(s)
   (possibly multiple). Write them into `CveIntelligence.fixCommits`.
2. **Stage 2** — delete the keyword-based commit-matching fallback. Rely on
   Stage 1's `fixCommits` (consumed by the existing priority-1
   `RefCommitStrategy`). If empty, go straight to the 2-version Maven source diff.
3. **Multi-major** — when the fix spans several major versions, prefer locating
   the patch commit from the **lowest** major line, escalating to the next major
   only if the lower one yields no locatable patch. Record the chosen version and
   use it in Stage 4 to build the vuln-demo.

## Non-Goals

- No change to Stage 3 reasoning logic.
- No change to the tracer or Stage 4 agent loop beyond version selection.
- No AI-generated diffs — the LLM only *selects among real commits*.

## Design

### 1. Capture all fixed versions (Stage 1 sources)

Today each source keeps a single fixed version:
- `OsvSource.packageFacts` overwrites `fixedVersion` on each `fixed` event,
  keeping only the last.
- `GhsaSource.packageFacts` keeps one `first_patched_version`.

**Change:** collect *every* distinct fixed version each source reports into a new
`List<String> fixedVersions` on `SourceData`. The single `fixedVersion` string is
retained for backward compatibility (set to the lowest-major fixed version so
existing consumers keep working). The assembler merges all sources' lists,
deduped, preserving order.

### 2. Select target major + demo version (Stage 1)

`IntelligenceAssembler` (or a small dedicated collaborator) computes:

- **Chosen fixed version:** group the captured fixed versions by major version;
  pick the **lowest** major's fix. This is the version we attempt to locate a
  patch for first.
- **Demo version:** the highest Maven Central version strictly below the chosen
  fix on the same line — reuse `MavenSourceDiffStrategy.inferPrevVersion`
  (metadata lookup, with decrement fallback).

**Escalation:** if patch-commit inference fails for the lowest major (see §3),
retry with the next-higher major's fix. The demo version tracks whichever major
ultimately succeeds. If *all* majors fail inference, `fixCommits` stays empty and
Stage 2 falls through to the Maven source diff (per approved decision), and the
demo version defaults to the lowest major's inferred previous version.

### 3. Infer patch commits via LLM (Stage 1)

Trigger only when `fixCommits` is empty after source merge. For the chosen major:

1. Resolve the fixed release tag on `sourceRepo` (GitHub tags API, matching the
   chosen fixed version — e.g. `version-2.0.206`).
2. Fetch the **full commit log** for `prevTag...fixedTag`
   (`/repos/{o}/{r}/compare/...`), collecting `{sha, message}` for **every**
   commit (no keyword filtering — this is the "full version-branch log").
3. Call the LLM with a new prompt `current/patch-commit-inference`, passing the
   CVE description, affected component, and the commit list. The LLM returns a
   JSON list of the sha(s) that constitute the security patch (possibly multiple,
   possibly empty).
4. For each returned sha, build the canonical
   `https://github.com/{o}/{r}/commit/{sha}` URL and add it to `fixCommits`.

If the tag can't be resolved, the compare returns nothing, or the LLM returns an
empty/unparseable list → treat as inference failure for this major; escalate (§2)
or fall through.

This lives in a new Stage 1 collaborator (e.g. `PatchCommitInferer`) invoked by
`IntelligenceStage` after `assembler.merge`, before the intelligence is persisted.
It owns its own `WebClient` (GitHub) and uses the shared `LlmClient` +
`PromptRegistry`.

### 4. New `CveIntelligence` field: `demoVersion`

Add `demoVersion` (the resolved vulnerable version to build the demo against) as a
new constructor arg + getter, threaded through
`IntelligenceAssembler.Draft.toIntelligence`. It is distinct from
`affectedVersions` and `fixedVersion`, giving Stage 4 an unambiguous source of
truth. Jackson `@JsonProperty("demoVersion")`; defaults to empty string when not
resolved (existing serialized intel without the field deserializes cleanly).

### 5. Stage 2 — delete keyword matching

- `GhsaCommitStrategy`: delete `locateByReleaseTag` and `diffFromReleaseTag`
  (lines ~106-202). Keep the URL-based commit extraction (`COMMIT_PAT`) — that is
  not keyword-based and remains a valid signal. `locate()` returns
  `Optional.empty()` when no commit URL yields a diff.
- `AiPatchSearchStrategy`: delete the `diffFromReleaseTag` method and its
  keyword-relevance block; remove the release-tag retry step (step 3 in
  `locateWithAiHints`). The AI commit-search-by-term and maven-diff retries stay.

Resulting Stage 2 order (unchanged priorities): `RefCommitStrategy` (Stage 1
commits) → `GhsaCommitStrategy` (direct commit URLs) → `maven-source-diff` →
`ai-patch-search`. With no Stage 1 commits and no URL refs, it falls straight
through to the 2-version source diff.

### 6. Stage 4 — use recorded demo version

- `StageDataExtractor.resolveVulnerableVersion`: prefer `intel.demoVersion` when
  non-empty; otherwise fall back to the existing heuristic.
- `JavaProfileResolver.resolveJavaProfile`: pass `demoVersion` (when present) as
  the version the LLM should target for profile selection.

## Data Flow

```
Stage 1 sources (OSV/GHSA/NVD)
  → SourceData.fixedVersions (all fixed events)
  → IntelligenceAssembler.merge  (dedupe, choose lowest major, resolve demoVersion)
  → PatchCommitInferer           (if fixCommits empty: full branch log → LLM → shas)
      └─ on failure: escalate major, else leave fixCommits empty
  → CveIntelligence { fixCommits, demoVersion, ... }

Stage 2 PatchAnalysisStage
  → RefCommitStrategy(fixCommits)  →  GhsaCommitStrategy(URL only)
  →  maven-source-diff  →  ai-patch-search
  (no keyword release-tag fallback)

Stage 4 ArtifactGenStage
  → resolveVulnerableVersion → demoVersion (authoritative)
```

## Error Handling

- All Stage 1 network/LLM failures are non-fatal: inference simply yields no
  commits, matching the pre-existing "empty fixCommits" path.
- Malformed/absent `demoVersion` → Stage 4 heuristic fallback (no regression).
- Deleting Stage 2 keyword fallback narrows results deliberately: a wrong patch is
  worse than no patch (the maven-source-diff path then applies).

## Testing

- `SourceData`/parser: OSV & GHSA payloads with multiple fixed events populate
  `fixedVersions`.
- Assembler: lowest-major selection; demoVersion resolution.
- `PatchCommitInferer`: given a mocked compare log + mocked LLM response, produces
  the expected commit URLs; empty/failed LLM → empty result.
- `GhsaCommitStrategy`: URL commits still work; release-tag input now returns
  empty (keyword path gone).
- `StageDataExtractor.resolveVulnerableVersion`: demoVersion preferred; heuristic
  fallback preserved.

## Global Constraints

- Every source file < 80 KB; every method < 256 lines.
- Reuse helpers from `backend/jvuln-utils` before adding local ones.
- No AI-generated diffs; LLM only selects among real commits.
- Educational local vulnerability demo context.
