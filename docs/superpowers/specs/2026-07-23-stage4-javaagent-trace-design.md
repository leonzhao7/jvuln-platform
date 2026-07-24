# Stage 4 — Javaagent Runtime Trace for PoC Feedback

**Date**: 2026-07-23
**Status**: Implemented
**Author**: brainstorming session

> Naming note: the demo-generation agent is labelled **Stage 5** in `docs/system-design.md`
> but is referred to colloquially as **Stage 4** by the team. This document uses "the
> generation agent" to avoid ambiguity. The relevant code lives in
> `backend/jvuln-stages/src/main/java/com/jvuln/generator/`.

## 1. Problem

The generation agent builds a local `vuln-demo` Spring Boot project plus `poc/exploit.sh`,
and the backend validates it in three gates: **compile → startup → PoC verification**
(`ValidationEngine.validateArtifacts`). The failing gate in practice is **PoC verification**:
the demo compiles and starts, but the exploit does not demonstrably trigger the vulnerability.

Within PoC failures, the two dominant patterns are:

- **A — wrong route**: the exploit request never reaches the vulnerable library code
  (wrong endpoint, wrong payload shape, or the demo wired the library up so the vulnerable
  path is bypassed). The vulnerable method is never called.
- **B — wrong payload**: execution reaches the vulnerable method, but the arguments are not
  malformed the right way, so the bug does not fire.

Today the model sees only a black-box pass/fail plus a reviewer reason string. It cannot see
**whether execution reached the vulnerable method, or with what arguments** — exactly the
signal that distinguishes A from B. The model ends up guessing at both the route and the
payload, which is the main driver of failed PoC iterations.

## 2. Goal

Add runtime observability: attach a javaagent to the demo JVM that records the internal
call path inside the vulnerable component (package-scoped) — every method call and its
argument values — during the PoC request. The backend digests this into a compact summary
keyed to the vulnerable methods from Stage 3, and feeds that digest to the model alongside
the existing PoC feedback. The model can then see, per iteration, whether it got the route
right and what arguments actually reached the vulnerable code.

Non-goals: this does not help compile or startup failures (a trace only exists once the
demo boots and the PoC runs). It does not modify the generated demo, and it must never
change demo behavior or break validation.

## 3. Architecture

Three pieces:

### 3.1 `jvuln-tracer` (new Maven module)

A standalone javaagent jar, loaded into the **demo** JVM (not the backend JVM).

- `premain(String agentArgs, Instrumentation inst)` entry point (declared as
  `Premain-Class` in the jar manifest).
- A ByteBuddy `AgentBuilder` that instruments a configurable set of packages.
- On each intercepted method it records: sequence number, call depth, class, method,
  argument values (stringified, per-arg capped), and return value or thrown exception.
- Writes events as JSONL to a trace file. The include-filter and output path come from
  agent args: `-javaagent:jvuln-tracer.jar=includes=org.h2.*,out=/abs/path/poc/trace.jsonl`.
- Zero dependency on the rest of the backend. ByteBuddy chosen over raw ASM because it
  handles Java 8 and package-scoped instrumentation with far less code.

The module honors the project code rules (file < 80 KB, method < 256 lines). It has no
dependency on `jvuln-utils` because it runs in a separate JVM and must stay self-contained
(shading its ByteBuddy dependency so it does not collide with the demo's classpath).

### 3.2 Backend attach point

`ValidationEngine.doStartApp` currently launches the demo via
`new ProcessBuilder("bash", "run.sh")` (`ValidationEngine.java:95`). The demo needs **zero
changes**: the attach is done by setting `JAVA_TOOL_OPTIONS` on the `ProcessBuilder`
environment, which the `java` process spawned by `run.sh` inherits automatically:

```
JAVA_TOOL_OPTIONS=-javaagent:<tracerJar>=includes=<pkg>,out=<cvePath>/poc/trace.jsonl
```

- `<tracerJar>` is resolved from the built `jvuln-tracer` module artifact, the same way
  other module artifacts are located. If the jar is missing, the backend logs a warning and
  launches the demo **without** `-javaagent` (validation proceeds exactly as today).
- `<pkg>` (the include filter) is derived from the **actual vulnerable-version sources**, never guessed from `groupId`: the backend downloads the artifact's Maven `-sources.jar` using the Stage 1/2 confirmed `groupId`, `artifactId`, and vulnerable version; scans every `.java` entry for its `package ...;` declaration; then de-duplicates the resulting package names. All discovered packages (including subpackages) are passed to the agent as its include filters. If the source JAR cannot be downloaded or yields no packages, the backend logs a warning and skips agent attachment rather than applying a guessed filter.

Stage 3's changed methods are still used only as the digest's **methods of interest**, not to determine the instrumentation range. This preserves the full runtime trace for the real vulnerable component even when Stage 3 did not identify every changed class.

### 3.3 `TraceDigestBuilder` (new class in `jvuln-generator`)

After `validatePoc`, reads `poc/trace.jsonl` and produces a compact digest keyed to Stage 3's
changed methods ("methods of interest"). The digest is carried on `ValidationResult` and
surfaced to the model through the live PoC-feedback path: `AgentPhaseEngine.buildPhaseDirective`
(the `POC_FIX` branch, which sets `actual` from `pocMessage`) → `renderPhaseDirective`.

> Note: `AgentPhaseEngine.buildAutoValidationFeedback` exists but is dead code (never called);
> the live directive path is `buildPhaseDirective` → `renderPhaseDirective`. Do not wire into
> the dead method.

## 4. Data Flow

```
Stage 1/2 groupId + artifactId + vulnerable version
        │  download <artifact>-<version>-sources.jar from Maven, scan package declarations
        ▼  include-package list (actual packages from source, deduped)

Stage 3 changed methods (classes / method names)
        │  → "methods of interest" list (digest keying only, NOT instrumentation scope)
        ▼
ValidationEngine.doStartApp
        │  sets JAVA_TOOL_OPTIONS=-javaagent:jvuln-tracer.jar=includes=<pkgs>,out=poc/trace.jsonl
        ▼
demo JVM boots with tracer attached  ──►  exploit.sh hits port 18080
        │  tracer intercepts every call in <pkg>
        ▼
poc/trace.jsonl  (full raw trace: class, method, args, return/throw, depth)
        │
        ▼
TraceDigestBuilder (reads jsonl after validatePoc)
        │  keys against Stage 3 methods-of-interest
        ▼
compact digest (reached[] / notReached[] / maxDepth / lastCall / exception)
        │
        ▼
carried on ValidationResult ──► buildPhaseDirective (POC_FIX) ──► renderPhaseDirective ──► LLM
```

The digest makes A-vs-B legible:

- `notReached` non-empty + shallow `maxDepth` → payload never entered the vulnerable path
  → **A (wrong route)**.
- method-of-interest in `reached` with its arg values, but PoC still failed → path entered
  but args didn't fire the bug → **B (wrong payload)**; the model sees the actual args it
  produced vs. what it intended.

The raw `trace.jsonl` stays on disk under `poc/` for the reviewer and debugging; only the
digest enters LLM context.

## 5. Trace Format & Digest Shape

### 5.1 Raw trace `poc/trace.jsonl`

One JSON object per line, written as calls happen (JSONL, not a JSON array, so a killed
process still leaves a readable file):

```
{"seq":1,"depth":0,"class":"org.h2.util.JdbcUtils","method":"getConnection","args":["jdbc:h2:mem:test;INIT=...","sa",""],"ret":"con@3f1a"}
{"seq":2,"depth":1,"class":"org.h2.command.Parser","method":"parse","args":["CREATE ALIAS ... AS ..."],"throw":"java.lang.RuntimeException: ..."}
```

- Each arg stringified with a **per-arg cap of 512 chars** and a total-line cap so one giant
  payload can't blow up the file.
- Depth from a thread-local counter incremented on method enter / decremented on exit.
- Raw file **hard cap 5 MB**: tracer stops writing past that but keeps counting `totalCalls`.

### 5.2 Digest (what the model sees)

Small, fixed-shape:

```json
{
  "traceCaptured": true,
  "totalCalls": 1287,
  "maxDepth": 9,
  "methodsOfInterest": {
    "reached": [
      {"method":"org.h2.command.Parser.parse","argsAtEntry":["CREATE ALIAS EXEC ..."],"outcome":"threw java.lang.RuntimeException"}
    ],
    "notReached": ["org.h2.engine.Engine.createSession"]
  },
  "lastCallBeforeEnd": "org.h2.command.Parser.parse",
  "note": "1 of 2 vulnerable methods reached; execution threw inside the reached method."
}
```

If the trace file is missing/empty (tracer failed to attach, or no call in the package fired),
the digest is `{"traceCaptured": false, "reason": "..."}` — itself a strong signal (usually A).

## 6. Error Handling & Failure Modes

Governing principle: **the tracer must never break demo validation.** It is an observability
add-on; if anything about it fails, PoC validation proceeds exactly as today, minus a digest.

| Failure | Handling |
|---------|----------|
| Tracer fails to attach / throws in `premain` | ByteBuddy agent install wrapped; any instrumentation error swallowed and logged to a sidecar, never propagated. Demo JVM still boots. |
| Tracer jar missing | Backend validates the jar path exists before setting `JAVA_TOOL_OPTIONS`; if missing, logs a warning and skips attaching rather than passing a broken `-javaagent`. |
| Instrumented method throws | Tracer records `throw` and re-throws the original exception unchanged. Control flow never altered. |
| Trace file missing/empty at digest time | Digest = `{"traceCaptured": false, ...}`. Not an error; a signal. |
| Malformed JSONL line (process killed mid-write) | Digest builder skips unparseable lines, keeps the rest. |
| Instrumentation slows startup past 30s | Governed by existing `STARTUP_WAIT` (30s) / `COMMAND_TIMEOUT` (60s). Mitigated by scoping `includes` to the vulnerable package only, never the whole JVM. |
| Overhead on happy path (always-on) | Accepted: when PoC succeeds first try, the trace is still written and the digest confirms success. |

## 7. Testing

- **`jvuln-tracer` unit tests** — a tiny in-module target class in a traceable package,
  driven through the ByteBuddy `AgentBuilder`, asserting JSONL captures class/method/args/
  return/throw and depth correctly, respects per-arg and file caps, and swallows
  instrumentation errors without propagating.
- **`TraceDigestBuilder` unit tests** (highest value — this is what the model reads) — fed
  hand-written `trace.jsonl` fixtures: reached case, notReached case, throw-in-reached case,
  empty file, malformed lines. Assert digest shape and `note`/`reached`/`notReached`
  classification.
- **Integration (manual, per project checklist)** — run CVE-2021-42392 (H2, already in the
  verification checklist) end-to-end; confirm `poc/trace.jsonl` is produced when the demo
  runs, the digest appears in PoC feedback, and the demo still boots/validates identically
  when the tracer jar is absent.
- **Regression guard** — verify a run with a missing tracer jar path degrades to today's
  behavior (warning logged, no `-javaagent`, validation unchanged).

## 8. Integration Points (code references)

- `ValidationEngine.doStartApp` (`ValidationEngine.java:74-136`) — attach `JAVA_TOOL_OPTIONS`
  on the demo `ProcessBuilder` env.
- `ValidationEngine.validatePoc` (`ValidationEngine.java:212-228`) — after PoC run, invoke
  `TraceDigestBuilder` and store the digest on `ValidationResult`.
- `AgentPhaseEngine.buildPhaseDirective` `POC_FIX` branch (`AgentPhaseEngine.java:86-91`) —
  append the digest to the directive `actual`/`fixHint` that `renderPhaseDirective`
  (`AgentPhaseEngine.java:105-120`) sends to the model. NOTE: `buildAutoValidationFeedback`
  (`AgentPhaseEngine.java:261-285`) is dead code (defined, never called) — the live PoC
  feedback path is `buildPhaseDirective` → `renderPhaseDirective`, not that method.
- Include-package list — derived by downloading the vulnerable-version `-sources.jar`
  (reusing `MavenSourceDiffStrategy` download/extract logic) and scanning `package`
  declarations. Stage 3 changed methods feed the digest's methods-of-interest keying only,
  not the instrumentation scope.

## 9. Decisions (from brainstorming)

- Scope: **package-scoped** trace of the vulnerable component's internal calls (not just the
  named vulnerable methods) — richest signal for both A and B.
- Feedback: **backend trims, model sees summary** — full trace on disk, compact digest to the
  model. (Two-tier `read_trace` tool is a deferred future upgrade.)
- Timing: **always on** — attach on every demo launch; digest present on the first PoC
  validation without a wasted failing round.
- Packaging: **new `jvuln-tracer` module**, ByteBuddy, external attach via `JAVA_TOOL_OPTIONS`;
  generated demo untouched.
- Caps: per-arg 512 chars, raw-file hard cap 5 MB.
