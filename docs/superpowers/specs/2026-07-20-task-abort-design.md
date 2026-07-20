# Task Abort Feature Design

**Date:** 2026-07-20  
**Status:** Approved

## Overview

Add task abort/cancel functionality to the CVE analysis pipeline. Users can abort a running task via a global cancel button. After aborting, users can resume/continue execution. When Stage 4 is aborted, the recovery UI (user hint retry + vuln-demo upload) is available, same as Stage 4 failure recovery.

## Requirements

- **Global abort**: Single button in header to abort the entire running pipeline
- **Resume support**: After abort, users can continue from where they left off
- **Stage 4 special handling**: When Stage 4 is aborted, save checkpoint to support resuming from the exact turn; show hint retry + vuln-demo upload recovery UI
- **Status representation**: Reuse FAILED status, distinguish user abort via errorMsg field

## Design Decisions

### Decision 1: Status Representation

**Choice:** Reuse FAILED status, not adding a new CANCELLED enum.

**Rationale:**
- Stage 4 recovery UI is already bound to `stage4Failed` condition
- Existing rerun/continue logic already works with FAILED status
- Minimal changes — no enum changes, no database schema migration, no frontend status handling updates

**Implementation:**
- Task → FAILED, errorMsg contains "用户中止"
- Stage → FAILED, errorMsg = "用户中止"
- Stage 4 → stage data writes `status: "paused"`, `pauseReason: "用户中止"`

### Decision 2: Abort Button Scope

**Choice:** Global abort button only (no per-stage abort).

**Rationale:**
- Pipeline executes sequentially — aborting the pipeline = aborting the current stage
- Single entry point simplifies UX
- No need for two-level granularity

### Decision 3: Stage 4 Checkpoint on Abort

**Choice:** Save checkpoint on abort to support resuming from the exact turn.

**Rationale:**
- Stage 4 agent loop has LLM calls per turn — potentially many turns executed
- User abort likely happens after substantial progress
- Checkpoint enables resuming from where it left off, not starting over

## Architecture

### Abort Signal Mechanism (Approach A)

Reuse the existing `runningTasks: ConcurrentHashMap<String, AtomicBoolean>` in PipelineEngine:
- Each cveId maps to an AtomicBoolean (true = running)
- `cancel(cveId)` sets the boolean to false
- Pipeline thread and Stage 4 agent loop check this flag periodically
- When flag is false, exit gracefully and clean up

**Why not a separate cancelled flag?**
- Minimal changes — reuses existing thread-safe structure
- AtomicBoolean is already thread-safe
- Setting to false is an effective abort signal

**Why not Thread.interrupt()?**
- Interrupt semantics are complex
- HTTP client interrupt behavior is unpredictable
- Can cause cleanup issues

### Components

#### PipelineEngine

**New method:**
```java
public boolean cancel(String cveId) {
    AtomicBoolean running = runningTasks.get(cveId);
    if (running == null || !running.get()) return false;
    running.set(false);  // Pipeline thread detects and exits
    return true;
}
```

**execute() modification:**
- Pass the AtomicBoolean reference into PipelineContext

#### PipelineContext

**New field:**
```java
private AtomicBoolean runSignal; // Points to the same instance in runningTasks

public boolean isCancelled() {
    return runSignal != null && !runSignal.get();
}
```

Initialized in `execute()` when creating the context.

#### runStages() Abort Check

**Check at two points:**
1. **Before each stage starts** — if cancelled, break out of loop
2. **After each stage completes** — if cancelled, break out of loop

**On abort:**
- Current RUNNING stage → mark as FAILED, errorMsg = "用户中止"
- Task → mark as FAILED
- Send SSE event `pipeline_done` with cancelled flag

#### ArtifactGenStage (Stage 4)

**Agent loop abort check:**
- At the start of each turn, check `ctx.isCancelled()`
- If cancelled:
  - Save checkpoint (current turn, writtenFiles)
  - Write stage data (status: "paused", pauseReason: "用户中止")
  - Throw a specific exception to let runStages know it's user abort

#### AnalysisController

**New endpoint:**
```java
@PostMapping("/{cveId}/cancel")
public ResponseEntity<?> cancelAnalysis(@PathVariable String cveId) {
    CveTask task = taskRepo.findByCveId(cveId).orElse(null);
    if (task == null) return ResponseEntity.notFound().build();
    if (task.getStatus() != CveTask.TaskStatus.RUNNING || !pipelineEngine.isRunning(cveId)) {
        return ApiResponseFactory.badRequest("Task is not running");
    }
    pipelineEngine.cancel(cveId);
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("cveId", cveId);
    resp.put("cancelled", true);
    return ResponseEntity.ok(resp);
}
```

**Key points:**
- `cancel()` only sets the signal, doesn't wait for pipeline thread to finish (non-blocking)
- Pipeline thread detects the signal, completes cleanup, and sends SSE event
- Frontend knows abort is complete via SSE `pipeline_done` event, no polling needed

## Frontend Changes

### Cancel Button

**Location:** Header area, next to "↺ 全部重跑" button

**Visibility:**
- Only shown when `sseActive` (pipeline is running)
- After clicking, button enters loading state
- After receiving SSE `pipeline_done`, button naturally hides (sseActive becomes false)

```html
<button v-if="sseActive" :loading="cancelling" @click="cancelTask">
  ■ 中止
</button>
```

### API Method

`api/index.ts` add:
```typescript
cancelTask: (cveId: string) =>
  http.post(`/analysis/${cveId}/cancel`).then(r => r.data),
```

### UI After Abort

Because we reuse FAILED status:
- Task status displays as "失败" (same as regular failure)
- Stage 4 abort writes stage data `status: "paused"` (same as API error pause), so **paused banner** shows ("Agent 已被用户中止 / 已完成 N 轮")
- `stage4Failed` is true, so **hint retry + vuln-demo upload** recovery UI naturally displays
- User can click "继续" (resume from checkpoint), "提交提示并重试", or "上传 vuln-demo"

### Stage 4 Paused Banner Text

Current `pausedTitle` is fixed as "Agent 因 API 错误暂停". Need to distinguish based on pause reason:
- API error: `Agent 因 API 错误暂停` (existing)
- User abort: `Agent 已被用户中止` (new)

Stage data's `pauseReason` field will contain different values; frontend displays different titles accordingly.

### Internationalization

**zh-CN:**
```javascript
cancelTask: '■ 中止'
pausedByUser: 'Agent 已被用户中止'
```

**en-US:**
```javascript
cancelTask: '■ Cancel'
pausedByUser: 'Agent cancelled by user'
```

## Stage 1-3, 5 Abort Handling

These stages don't have agent loops; execution time is short (mainly single LLM call or I/O operations). Abort signal check points:

- **`runStages()` before calling `stage.execute()`** — if cancelled, break out
- **`runStages()` after `stage.execute()` returns** — if cancelled, break out

For stages already inside `execute()` (e.g., making HTTP request), don't force interrupt — wait for natural return then detect signal. Rationale:
1. Stage 1-3, 5 single execution time is typically seconds
2. Complexity of forcing HTTP/LLM call interruption isn't worth it
3. UX-wise, waiting a few seconds after clicking abort is acceptable

**Status after abort:**
- Current RUNNING stage → FAILED, errorMsg = "用户中止"
- Subsequent unexecuted stages remain PENDING (not marked SKIPPED)
- Task → FAILED

**Recovery:**
- User can click "从阶段 N 开始重跑" to resume (existing feature, naturally compatible)
- No special recovery UI needed (Stage 4 recovery UI exists because it has special checkpoint/hint/upload needs)

## Error Handling & Edge Cases

### Duplicate Abort Requests

User rapidly clicks abort button multiple times:
- **Frontend:** Abort button enters loading state and disables, preventing duplicate clicks
- **Backend:** `cancel()` is idempotent — checks if `runningTasks.get(cveId)` exists and is true, otherwise returns false

### Other Operations During Abort

**Scenario:** User clicks abort, then tries rerun or delete before pipeline thread completes cleanup

- **rerun:** `rerun()` checks `isRunning(cveId)` — as long as cveId exists in `runningTasks`, returns conflict error. User must wait for SSE `pipeline_done` before rerunning
- **delete:** Same logic, `delete()` also checks `isRunning(cveId)`, refuses to delete running task
- **upload-vulndemo:** Same as rerun, checks `isRunning(cveId)`

**Conclusion:** No special handling needed; existing concurrency checks already cover this.

### SSE Connection Disconnect

User closes browser tab, SSE disconnects, but pipeline continues running. User later reopens page:
- Frontend re-subscribes to SSE, `subscribe()` replays history events
- If pipeline already completed abort, receives `pipeline_done` event
- If pipeline still running, `sseActive` restores to true, abort button reappears

**Conclusion:** Existing SSE mechanism already supports this; no additional handling needed.

### Stage 4 Checkpoint Save Failure

Checkpoint write fails on abort (disk full, permission issues, etc.):
- Catch exception, log it, continue marking stage as FAILED
- Consequence: User clicking "继续" can't resume from checkpoint, will start from scratch (memory preserved)
- Acceptable: This is an extreme edge case, doesn't affect core functionality

## Implementation Checklist

### Backend

- [ ] PipelineEngine: Add `cancel(cveId)` method
- [ ] PipelineContext: Add `runSignal` field and `isCancelled()` method
- [ ] PipelineEngine.execute(): Pass AtomicBoolean into PipelineContext
- [ ] PipelineEngine.runStages(): Add abort checks before/after each stage
- [ ] ArtifactGenStage: Add abort check in agent loop, save checkpoint on abort
- [ ] AnalysisController: Add `POST /{cveId}/cancel` endpoint

### Frontend

- [ ] api/index.ts: Add `cancelTask()` method
- [ ] AnalysisDetail.vue: Add cancel button in header
- [ ] AnalysisDetail.vue: Implement `cancelTask()` function with loading state
- [ ] AnalysisDetail.vue: Adjust paused banner title based on pauseReason
- [ ] zh-CN.ts: Add `cancelTask` and `pausedByUser` strings
- [ ] en-US.ts: Add `cancelTask` and `pausedByUser` strings

### Testing

- [ ] Test aborting during each stage (1-5)
- [ ] Test Stage 4 abort checkpoint save and resume
- [ ] Test Stage 4 abort shows recovery UI (hint retry + upload)
- [ ] Test duplicate abort requests
- [ ] Test rerun/delete during abort
- [ ] Test SSE reconnection after abort
- [ ] Test paused banner text for API error vs user abort

## Future Enhancements (Out of Scope)

- Force interrupt HTTP/LLM calls (currently waits for natural completion)
- Add CANCELLED as a distinct status enum (if semantic distinction becomes important)
- Per-stage abort buttons (if users request finer granularity)
