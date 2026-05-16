# M8.T1 — Cron parser + next-fire calculator

## Goal
Parse standard 5-field cron expressions plus shortcuts (`@hourly`, `@daily`, `@every <duration>`). Compute the next fire time given a `now: Instant`.

## Files to create

- `modules/scheduler/build.gradle.kts` (edit — add deps)
- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/cron/Cron.kt` (new)
- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/cron/CronParser.kt` (new)
- `modules/scheduler/src/test/kotlin/com/hebe/scheduler/cron/CronParserTest.kt` (new)

## Detailed work

### 1. Deps in `modules/scheduler/build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":modules:api"))
    implementation(project(":modules:memory"))
    implementation(project(":modules:core"))  // for JobDelegate
    implementation(libs.kotlinx.datetime)
}
```

### 2. Cron expression model (`Cron.kt`)

```kotlin
package com.hebe.scheduler.cron

import kotlinx.datetime.Instant

sealed interface Cron {
    data object Hourly : Cron                         // → 0 * * * *
    data object Daily : Cron                          // → 0 0 * * *
    data class Every(val interval: kotlinx.datetime.Duration) : Cron
    data class Standard(
        val minute: Int,      // 0-59
        val hour: Int,        // 0-23
        val dom: Int,          // 1-31, 0 = any
        val month: Int,        // 1-12, 0 = any
        val dow: Int,          // 0-6,  0 = any (Sun=0)
    ) : Cron

    fun nextFire(now: Instant, tz: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.UTC): Instant
}
```

### 3. CronParser (`CronParser.kt`)

Accepts:
- 5 fields: `minute hour dom month dow`
- Field syntax: `*`, integer, `*/n`, range `a-b`, list `a,b,c`
- Shortcuts: `@hourly` → `Hourly`, `@daily` → `Daily`, `@every 30m` → `Every(30.minutes)`
- Raises `IllegalArgumentException` with descriptive message for invalid input

### 4. nextFire algorithm

- **Standard cron**: increment minute by minute until all fields match (brute force, given cron is always forward)
- **Every**: `lastFire + interval`
- **Timezone**: UTC-only in v1; document this

### 5. Tests

Property-based tests for random valid expressions + golden tests for boundary cases.

---

## M8.T2 — Job loop

## Goal
A single coroutine reads `jobs WHERE status = 'pending' AND trigger_at <= now()`, marks `running`, runs via `JobDelegate`, marks `done`/`failed`. At-least-once; idempotency required for retryable kinds.

## Files to create

- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/JobLoop.kt` (new)
- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/JobRepo.kt` (new — `jobs` CRUD)
- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/JobRunner.kt` (new — kind dispatch)
- `modules/scheduler/src/test/kotlin/com/hebe/scheduler/JobLoopTest.kt` (new)

## Detailed work

### 1. JobLoop (`JobLoop.kt`)

```kotlin
class JobLoop(
    private val jobRepo: JobRepo,
    private val jobRunner: JobRunner,
    private val tickInterval: kotlinx.datetime.Duration = kotlinx.datetime.Duration.parse("5s"),
) {
    suspend fun run(scope: CoroutineScope): Nothing
}
```

Tick every 5 seconds (configurable via `tickInterval`):
- `JobRepo.claimPending(maxN = 1)` — pessimistic claim
- Pass to `jobRunner.run(job)`
- On exception: `status='failed'`, `result_json={error: ...}`
- On success: `status='done'`, `ended_at=now`, `result_json=...`

### 2. JobRepo (`JobRepo.kt`)

```kotlin
class JobRepo(private val db: java.sql.Connection) {
    suspend fun claimPending(maxN: Int = 1): List<Job>

    suspend fun markRunning(id: String, startedAt: Long)
    suspend fun markDone(id: String, endedAt: Long, resultJson: String?)
    suspend fun markFailed(id: String, endedAt: Long, error: String)
    suspend fun markStuck(id: String, endedAt: Long)
    suspend fun markCancelled(id: String)

    suspend fun insert(job: Job)
    suspend fun get(id: String): Job?
}
```

Pessimistic claim:
```sql
UPDATE jobs SET status='running', started_at=? WHERE id=(
    SELECT id FROM jobs
    WHERE status='pending' AND trigger_at<=?
    ORDER BY trigger_at LIMIT 1
) RETURNING *
```

### 3. JobRunner (`JobRunner.kt`)

```kotlin
class JobRunner(
    private val jobRepo: JobRepo,
    private val memory: com.hebe.api.MemoryStore,
) {
    suspend fun run(job: Job): JobOutcome
}
```

Dispatches by `job.kind`:
- `routine` → resolve to a `routines` row → execute body via `JobDelegate`
- `maintenance` → call the registered maintenance handler (M8.T4–T7, stubs in T2)
- `adhoc` → run a `JobDelegate` with `payload_json` as a free-form prompt
- `heartbeat` → handled in M8.T9

Idempotency: maintenance jobs safe to run multiple times. Operations should converge.

Cancellation: check `status='cancelled'` between tool calls; on cancel, exit and append `result_json={cancelled: true}`.

### 4. Job data model

```kotlin
data class Job(
    val id: String,
    val kind: JobKind,
    val status: JobStatus,
    val startedAt: Long?,
    val endedAt: Long?,
    val triggerAt: Long?,
    val payloadJson: String?,
    val resultJson: String?,
    val attempt: Int,
)

enum class JobKind { Routine, Maintenance, Adhoc, Heartbeat }
enum class JobStatus { Pending, Running, Done, Failed, Cancelled, Stuck }
```

### 5. JobDelegate stub

Create `modules/core/src/main/kotlin/com/hebe/core/delegate/JobDelegate.kt` (if not already existing from M2.T12) as a stripped-down `LoopDelegate` variant used by the scheduler for routine bodies. Minimal: just executes the body, captures result.

## Dependencies
- M8.T1 (Cron parser)
- M2.T12 (JobDelegate — check if already exists)

## Verification
- Insert a `pending` job → loop picks it up → status progresses
- Insert a job with `trigger_at` in the future → not picked up until time passes
- Cancel a running job → status `cancelled` after the next tool boundary