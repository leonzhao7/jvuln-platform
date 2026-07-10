# Stage 1 Intelligence Adjudication Design

**Date:** 2026-07-10
**Branch:** `clear-prompt`
**Status:** Approved for implementation planning

## 1. Purpose

Stage 1 must collect public CVE intelligence from NVD, GitHub Advisory, and
OSV without losing source provenance. It must classify every reference and use
an LLM to produce a single evidence-backed description before later pipeline
stages consume the intelligence.

The final description is not a silent overwrite of a source description. The
stage retains each source response and records how the final description was
adjudicated.

## 2. Goals

- Collect NVD, GitHub Advisory, and OSV concurrently.
- Record the outcome, latency, error, original description, parsed fields, and
  raw payload for every source.
- Succeed at the collection boundary when at least one source succeeds.
- Classify references as `advisory`, `analysis`, `patch`, `poc`, or `other`.
- Apply deterministic classification rules first and send only unresolved
  references to the LLM.
- Provide source data and trusted reference evidence to one mandatory
  description-adjudication LLM request for every CVE.
- Expose the adjudicated description through the existing
  `CveIntelligence.getDescription()` contract.
- Fail Stage 1 instead of silently forwarding unadjudicated or unresolved
  information.

## 3. Non-goals

- Replacing or rewriting source payloads.
- Adding public intelligence sources beyond NVD, GitHub Advisory, and OSV.
- Enabling the currently disabled Gitee source.
- Changing how later stages read the final CVE description.
- Building a new UI for the additional audit fields in this change.
- Guaranteeing that the LLM can resolve facts unsupported by the collected
  evidence.

## 4. Current Problems

The current implementation reduces source success to a boolean, selects the
first non-empty value for most fields, and overloads `rawJson` with failure
messages. It does not preserve per-source timing or failure semantics.

The current article classifier sends nearly every reference to the LLM,
silently skips classification for a single article, truncates lists above 20
items, and falls back to unclassified articles when the LLM fails.

The current description corrector reads only `analysis` articles, returns a
plain string, directly replaces the selected source description, and has no
structured evidence or unresolved-conflict result.

## 5. High-level Flow

1. Start NVD, GitHub Advisory, and OSV collection concurrently and measure each
   source independently.
2. Convert every outcome to a `SourceResult` with one of `SUCCESS`,
   `NOT_FOUND`, `FAILED`, or `TIMED_OUT`.
3. Persist the source results and fail immediately if none succeeded.
4. Merge and canonicalize all source references without discarding provenance.
5. Classify references with deterministic rules.
6. If unresolved references remain, send them in one batch to the link
   classification LLM and validate every returned classification.
7. Collect bounded evidence from source records, official or vendor
   advisories, patch commits, and at most three trusted technical analyses.
8. Send all source descriptions, normalized source facts, and selected
   evidence to the mandatory description-adjudication LLM.
9. Validate the structured adjudication result. Only a fully resolved result
   produces a successful `CveIntelligence`.
10. Persist the full audit record. Set the existing `description` field to the
    adjudicated description for downstream compatibility.

The link classification call is conditional. The description adjudication
call is mandatory. A normal CVE therefore makes one or two logical LLM calls.
Retries performed by the configured LLM client remain attempts of the same
logical request.

## 6. Component Boundaries

### 6.1 `IntelligenceStage`

The stage coordinates the workflow and owns only stage-level decisions:
source fan-out, overall deadlines, minimum success requirements, persistence,
and the final `StageResult`.

### 6.2 `IntelSource`

Each source implementation requests and parses exactly one provider. A source
must distinguish an absent CVE from a request or parsing failure. It returns
source data; it does not merge data from other sources.

### 6.3 `ArticleClassifier`

The classifier canonicalizes and deduplicates URLs, applies deterministic
rules, and batches only unresolved entries into an LLM request. It validates
that the LLM returns exactly one allowed classification for every requested
reference. It never truncates or silently returns unclassified references.

### 6.4 `EvidenceCollector`

The collector selects trusted references, fetches bounded text, and creates
stable evidence IDs. It records fetch failures without immediately failing the
stage because other evidence may still be sufficient.

### 6.5 `DescriptionAdjudicator`

The adjudicator constructs the task request, calls `LlmClient`, parses the
structured response, verifies its evidence citations, and returns either a
resolved adjudication or an explicit insufficient-evidence result.

Shared persisted models belong in `backend/jvuln-utils`. Logic used only by
Stage 1 remains in `backend/jvuln-stages`.

## 7. Persisted Data Model

`CveIntelligence` retains all current fields and getters. It gains source,
evidence, and adjudication audit fields. Existing constructors remain
available so current consumers do not need to change in the same release.

### 7.1 Source result

Each `SourceResult` contains:

- `source`: `NVD`, `GHSA`, or `OSV`.
- `status`: `SUCCESS`, `NOT_FOUND`, `FAILED`, or `TIMED_OUT`.
- `durationMs`: monotonic elapsed time for that provider request.
- `errorCode` and `errorMessage`: empty for non-error outcomes.
- `originalDescription`: the provider's description without LLM rewriting.
- `parsedData`: normalized CWE, CVSS, artifact, affected versions, fixed
  version, repository, fix commits, and references extracted from that source.
- `rawPayload`: the original public API response, stored separately from error
  information.

Status meanings are precise:

- `SUCCESS`: a matching CVE/advisory record was returned and parsed.
- `NOT_FOUND`: the provider responded normally but had no matching record.
- `FAILED`: HTTP, authorization, transport, parsing, or other non-timeout error.
- `TIMED_OUT`: the provider deadline or the Stage 1 collection deadline expired.

### 7.2 Classified reference

`CveIntelligence.Article` keeps its current fields and gains:

- `discoveredFrom`: all providers that supplied the canonical URL.
- `category`: `advisory`, `analysis`, `patch`, `poc`, or `other`.
- `classificationMethod`: `RULE` or `LLM`.
- `classificationReason`: a stable rule identifier or the LLM's concise basis.
- `classificationConfidence`: a decimal from `0.0` through `1.0`.

Deduplication merges `discoveredFrom` instead of keeping only the first
provider. Old constructors remain and populate compatible defaults.

### 7.3 Evidence result

Each `EvidenceResult` contains:

- `evidenceId`: the stable ID exposed to the LLM.
- `kind`: source description, source facts, advisory, patch, or analysis.
- `source`, `title`, and `url`: provenance for the evidence.
- `fetchStatus`: `INLINE`, `SUCCESS`, `FAILED`, `TIMED_OUT`, or `REJECTED`.
- `excerpt`: the bounded text supplied to the LLM when available.
- `errorMessage`: the fetch or policy rejection reason when unavailable.

Source descriptions and parsed source facts use `INLINE`; linked documents
use one of the fetch outcomes. This structure preserves failed fetch attempts
that cannot appear in the LLM's citations.

### 7.4 Description adjudication

`DescriptionAdjudication` contains:

- `status`: `NOT_RUN`, `SUCCESS`, or `FAILED`.
- `verdict`: `RESOLVED` or `INSUFFICIENT_EVIDENCE` when an adjudication
  response was received and validated; otherwise absent.
- `corrected`: whether the final text materially corrects the source claims.
- `finalDescription`: the evidence-backed description.
- `reason`: a concise explanation of the resolution.
- `conflictingClaims`: source claims that disagreed and how they were resolved.
- `evidenceCitations`: validated evidence IDs supporting the result.
- `confidence`: a decimal from `0.0` through `1.0`, recorded but not used as a
  standalone success threshold.
- `errorMessage`: populated when adjudication cannot be completed.

On success, `CveIntelligence.description` equals
`DescriptionAdjudication.finalDescription`.

## 8. Reference Classification

Rules use normalized URL, host, path, source tags, and title. Examples include
recognized advisory hosts and advisory paths, commit or patch paths, known
PoC/exploit hosts and repository paths, and known analysis publishers. Rules
must return a category, a stable reason, and a confidence value.

Only references without a decisive rule result enter the LLM request. The LLM
receives a stable reference ID plus bounded URL, title, source, and summary
data. Its structured response must contain every requested ID exactly once,
use only allowed categories, include a non-empty reason, and provide a valid
confidence value.

The classifier does not discard references to meet a prompt limit. It bounds
individual fields and the complete request. If the unresolved set cannot fit
within the configured request budget, Stage 1 fails with an explicit input
budget error instead of producing partial classifications.

## 9. Evidence Collection

The adjudication request includes:

- Every successful provider's original description.
- Every successful provider's normalized structured facts.
- Official and vendor advisories selected from classified references.
- Relevant patch or fix commit evidence.
- At most three trusted technical analysis excerpts.

Official and primary-source evidence has priority over third-party analysis.
Individual pages and the aggregate context have fixed character budgets.
Fetch timeouts and errors are recorded. A failed page does not fail the stage
by itself; the adjudicator decides whether the remaining evidence is enough.

Every input item receives a stable `evidenceId`. The LLM may cite only these
IDs. Code maps validated IDs back to provider names, URLs, titles, and stored
excerpts. The LLM cannot introduce a new citation URL.

## 10. LLM Request Contracts

Both LLM tasks use the existing unified `LlmClient` and configured endpoint.
The caller selected for `/v1/chat/completions`, `/v1/responses`, or a Messages
endpoint remains responsible for endpoint-specific request and response
formats. Global and intelligence-stage prompts are added by the prompt system;
the classifier or adjudicator prompt is the task prompt.

### 10.1 Link classification output

The link classifier returns an array of objects containing:

- `referenceId`
- `category`
- `reason`
- `confidence`

### 10.2 Description adjudication output

The adjudicator returns one object containing:

- `verdict`
- `finalDescription`
- `corrected`
- `reason`
- `conflictingClaims`
- `evidenceCitations`
- `confidence`

The prompt treats fetched text as untrusted evidence. Instructions embedded in
source descriptions or pages cannot change the task, output format, or allowed
evidence set. The model must not fill gaps from its own unstated knowledge.

## 11. Success and Failure Semantics

Stage 1 succeeds only when all of the following are true:

- At least one public source has status `SUCCESS`.
- Every reference has a valid classification.
- Any required link-classification LLM request succeeds and validates.
- The mandatory adjudication LLM request succeeds and validates.
- The adjudicator returns `RESOLVED` with a non-empty description.
- All evidence IDs are part of the supplied evidence set.
- No critical component, affected-version, vulnerability-type, or fix claim is
  left unresolved.

Stage 1 fails after configured retries when a required LLM request times out,
raises an error, returns invalid structured data, or cannot resolve the
evidence. It never falls back to a preferred provider description.

Before returning failure, the stage persists all source results, classified
references already produced, evidence fetch outcomes, and the adjudication
error. If an earlier required step failed, adjudication has status `NOT_RUN`;
if adjudication itself failed, it has status `FAILED`. In both cases the
partial record has an empty final `description`, so it cannot be mistaken for
consumable intelligence.

## 12. Security and Resource Limits

- Evidence fetches accept only public `http` and `https` targets.
- Localhost, private, link-local, loopback, and other non-public destinations
  are rejected before connecting and after every redirect.
- Response size, connection time, read time, redirect count, per-page text,
  and total prompt context are bounded.
- HTML scripts, styles, navigation, forms, and other non-content elements are
  removed before extracting evidence text.
- Full page bodies and complete LLM prompts are not written to application
  logs.
- Provider errors and evidence fetch errors are recorded without credentials
  or authorization headers.

## 13. Testing Strategy

Unit and focused integration tests use fake providers, local HTTP fixtures, and
a fake `LlmClient`; they never depend on public APIs or a real model.

Required coverage includes:

- All four source statuses, duration recording, and error separation.
- One-success and all-failed source combinations.
- Stage-level cancellation and per-source timeout behavior.
- URL canonicalization, provenance-preserving deduplication, and a rule matrix
  covering all five categories.
- Sending only unresolved references to the LLM.
- Complete, missing, duplicate, invalid-category, invalid-confidence, empty,
  and malformed link-classification responses.
- Evidence selection order, three-analysis limit, context budgets, failed page
  fetches, redirects, oversized responses, and non-public address blocking.
- Resolved, insufficient-evidence, malformed, empty-description,
  out-of-set-citation, unresolved-critical-claim, timeout, and exception paths
  for description adjudication.
- Successful Stage 1 persistence where the legacy description is the
  adjudicated description.
- Failed Stage 1 persistence where source audit data remains but the final
  description is empty.
- Serialization compatibility for existing `CveIntelligence` consumers and
  the legacy constructors/getters.

## 14. Acceptance Criteria

- A Stage 1 run records an outcome, latency, and error details for NVD, GHSA,
  and OSV.
- Source descriptions and raw payloads remain available after adjudication.
- No reference is silently omitted or left without classification metadata.
- Rule-classified references do not consume a classification LLM call.
- Every successful CVE has exactly one resolved, evidence-backed final
  description adjudication.
- Any required LLM failure or insufficient evidence fails Stage 1 without an
  unadjudicated fallback.
- Later stages continue to obtain the final description through
  `CveIntelligence.getDescription()`.
