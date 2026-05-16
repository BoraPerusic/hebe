# M8.T2 — Job loop

## Status: in_progress

## Plan

### 1. JobRepo (`modules/scheduler/src/main/kotlin/com/hebe/scheduler/JobRepo.kt`)
- `claimPending(now, maxN=1): List<Job>` — pessimistic claim via atomic UPDATE...RETURNING
- `updateStatus(jobId, status, resultJson, endedAt)` — final state update
- `markRunning(jobId, startedAt)` — transition pending→running
- `insertPending(kind, triggerAt, payloadJson): String` — insert new job
- `Job` data class: id, kind, status, startedAt, endedAt, triggerAt, payloadJson, resultJson, attempt

### 2. JobLoop (`modules/scheduler/src/main/kotlin/com/hebe/scheduler/JobLoop.kt`)
- `run(scope: CoroutineScope, tickInterval: Duration = 5.seconds)`
- every tick: `repo.claimPending(now, maxN=1)` → for each job → `JobRunner.run(job)`
- on exception: `status='failed'`, `resultJson={error: ...}`
- on success: `status='done'`, `endedAt=now`
- cooperative cancellation check between tool calls

### 3. JobRunner (`modules/scheduler/src/main/kotlin/com/hebe/scheduler/JobRunner.kt`)
- `run(job: Job)` dispatches by `job.kind`:
  - `routine` → resolve to a `routines` row → execute via `JobDelegate`
  - `maintenance` → call registered maintenance handler (placeholder for M8.T4-T8)
  - `adhoc` → run a `JobDelegate` with `payload_json` as free-form prompt
  - `heartbeat` → placeholder (M8.T9)

### 4. Tests (`modules/scheduler/src/test/kotlin/com/hebe/scheduler/JobLoopTest.kt`)
- Insert pending job → loop picks it up → status progresses to done
- Job with future trigger_at → not picked up
- Running job cancellation works

## Dependencies
- `scheduler` already has: `api`, `memory`, `core`
- Need to add: `libs.kotlinx.coroutines.core` (from gradle/libs.versions.toml)