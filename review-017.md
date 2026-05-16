# Review 017 — M8: Scheduler + heartbeat

**Branch:** `v1-development`
**Previous review:** `review-016.md`

---

## Overview

M8 delivers the scheduler module: cron parser, job loop, routines engine, five maintenance tasks (Summariser, FactExtractor, DailyDigest, EmbeddingRefresh, StuckJobDetector), and the Heartbeat runner. The module compiles and the build is green. However, there are several correctness bugs affecting cron firing, fact storage, schema, and test assertions, plus significant architectural gaps where maintenance tasks are not yet wired to the job loop.

---

## Bugs — must fix before the scheduler is trusted

### 1. `Cron.Standard` month wildcard never matches (`CronParser.kt:57`, `CronParser.kt:149`)

`parseField` returns `min - 1` for `*`. For `minute`, `hour`, `dom`, `dow` (all have `min=0`) this is `-1` — the sentinel `matchesAllFields` tests for. But `month` has `min=1`, so `parseField("*", 1, 12, "month")` returns `0`, not `-1`. `matchesAllFields` checks `month == -1`, which is never true. **Every standard cron with `*` in the month position (i.e. all practical crons) will never fire.**

```kotlin
// CronParser.kt line 57 — wrong range:
val month = parseField(fields[3], 1, 12, "month")  // * → returns 0, not -1

// matchesAllFields line 153 — checks -1 not 0:
val monthMatches = month == -1 || month == local.monthNumber
```

Fix: change `parseField` to always return `-1` for `*` instead of `min - 1`.

### 2. `Cron.Hourly.nextFireHourly` fires at next minute, not next `:00` (`CronParser.kt:110–122`)

The plan defines `@hourly → 0 * * * *` — fires at minute 0 of each hour. The implementation returns `local.minute + 1`, making `@hourly` equivalent to `@every 1m`. From 10:23 it returns 10:24 instead of 11:00. The test even asserts this wrong behaviour under the name "fires at next minute".

Fix: return the start of the next hour (minute 0 of `local.hour + 1`).

### 3. `FactExtractor.appendFact` destroys existing facts on every write (`FactExtractor.kt:166`)

```kotlin
val updated = if (existing.contains(FACTS_SECTION)) {
    existing.replaceAfter(FACTS_SECTION, "\n$line")  // replaces EVERYTHING after the header
} else { ... }
```

`String.replaceAfter(delimiter, replacement)` replaces all content after the first occurrence of the delimiter. Every new fact silently overwrites all previously written facts.

Fix: append after the section header rather than replace it:
```kotlin
existing.replace(FACTS_SECTION, "$FACTS_SECTION\n$line")
// or: insert before the next ## heading if present
```

### 4. Missing V7 migration — `summary_id` column doesn't exist in `messages` (`Summariser.kt:118,188`)

`Summariser.loadMessagesForSummarisation` filters `WHERE summary_id IS NULL` and `markMessagesSummarised` updates `SET summary_id = ?`. The plan (M8.T4) acknowledged that a V7 migration is needed but it was never created — the last migration is `V6__memory_categories.sql`. The SQL fails at runtime; the outer catch-all in `run()` swallows the exception silently, so summarisation always produces 0 without any user-visible failure.

Fix: add `V7__messages_summary_id.sql` with `ALTER TABLE messages ADD COLUMN summary_id TEXT;`.

### 5. `StuckJobDetector.run()` return value mismatch with its own test (`StuckJobDetector.kt:31`)

`run()` returns the count of **retried** jobs (`retried++` only executes inside `if (shouldRetry(job))`). The second test inserts a non-retryable stuck job and asserts `result.getOrNull() shouldBe 1`, but the method returns 0 for a non-retryable job. The test fails.

Fix: either return the count of stuck jobs found (`stuck.size`) and rename accordingly, or increment a `processed` counter on every `markJobStuck` call.

### 6. `HeartbeatTest` second test always fails (`HeartbeatTest.kt:38`)

Both heartbeat tests set `heartbeatFilePath = "/nonexistent/HEARTBEAT.md"`. `readHeartbeatFile` returns `""`, so `heartbeatContent.isBlank()` is true, and `run()` returns `Result.success(false)` early — the LLM and notify channel are never called. The second test asserts `notified shouldBe true`, which fails unconditionally.

Fix: use a temp file populated with content for the notification test; or expose the heartbeat content as a constructor override.

---

## Architectural issues

### 7. `maintenance` job kind not dispatched — maintenance tasks not wired to the job loop

`JobRunner.run()` dispatches `routine` and `adhoc` but the `else` branch just logs "unknown kind" and returns true. The plan (M8.T2) requires a `maintenance` dispatch path that calls the registered handler. None of Summariser, FactExtractor, DailyDigest, EmbeddingRefresh, or StuckJobDetector is wired into `JobRunner`. These classes exist as standalone objects never invoked by the scheduler.

### 8. `RoutinesEngine` duplicates `JobRepo.insertPending` with raw JDBC (`RoutinesEngine.kt:101`)

`RoutinesEngine.insertJob()` is a private raw JDBC insert that's a duplicate of `JobRepo.insertPending()`. Two places now own the insert logic for jobs, including the `trigger_at` timestamp handling. `RoutinesEngine` should take a `JobRepo` instead of a raw `DataSource` and delegate to `JobRepo.insertPending`.

### 9. `DenyAllApprovalGate` duplicated in `JobRunner` and `Heartbeat`

Identical private nested objects appear in both classes. This should move to a shared location in the scheduler module (e.g. a `SchedulerInternals.kt` internal file).

### 10. `EmbeddingRefresh` uses chat API for embeddings (`EmbeddingRefresh.kt:85`)

`embedBatch` calls `llmProvider.chat()` with a prompt asking the LLM to return embedding vectors as JSON. Chat completions cannot produce meaningful embeddings — the output is free-text. The `LlmProvider` API has no embedding endpoint, which is the root problem. Either add `embed(texts): List<FloatArray>` to `LlmProvider`, or stub this out explicitly with a comment noting the missing API.

### 11. No composite scheduler — `JobLoop` and `RoutinesEngine` are not connected

The plan specifies a 30 s cadence for `RoutinesEngine` and a 5 s cadence for `JobLoop`, running separately. No `Scheduler` class or startup entry point coordinates them. Both classes are instantiable but nothing drives them. The CLI app has no reference to either.

---

## Minor issues

### 12. `parseList` can never succeed (`CronParser.kt:77`)

`parseList` is called only when `value.contains(",")`, meaning `value.split(",")` always has `>= 2` elements. The guard `require(values.size == 1)` then always throws. This should just throw immediately with a clear "not supported" message instead of the impossible success path.

### 13. `nextFireStandard` returns `now` when `now` already matches (`CronParser.kt:137`)

The loop starts from `now` inclusive. If the current instant matches all cron fields, it returns `now` — causing the routine to re-schedule itself with `next_run_at = now` and fire again immediately on the next tick.

Fix: start from `now + 1.minutes`.

### 14. `dom` min range allows 0 (`CronParser.kt:55`)

```kotlin
val dom = parseField(fields[2], 0, 31, "dom")
```

Day-of-month is 1–31; `min=0` allows an invalid `dom=0`. Should be `parseField(fields[2], 1, 31, "dom")`. (Note: this also affects the wildcard sentinel, which must be fixed together with issue #1.)

### 15. `Cron` sealed interface — dead `minute`/`hour` properties on `Hourly`/`Daily` (`Cron.kt:7–15`)

```kotlin
sealed interface Cron {
    val minute: Int get() = -1
    val hour: Int get() = -1
    data object Hourly : Cron   // overrides both to -1 (same as default)
    data object Daily : Cron
```

These default properties exist only on `Standard`. The interface-level defaults are never used by the non-Standard variants and the explicit overrides on `Hourly.Every` are redundant.

### 16. `Summariser.loadUnsummarisedMessages` — always a no-op (`Summariser.kt:137`)

`MessageRow.summaryId` defaults to `null` and is never populated from the result set. The filter `all.filter { it.summaryId == null }` always returns every element. The SQL already does `WHERE summary_id IS NULL`, so this function is dead code.

### 17. Unused `timestamp` variable in `Summariser.summariseConversation` (`Summariser.kt:97`)

```kotlin
val timestamp = java.time.Instant.ofEpochMilli(nowMs)  // unused
```

### 18. `JobLoop.loop()` uses `while (true)` instead of `while (isActive)` (`JobLoop.kt:25`)

Coroutine cancellation propagates through `delay()` but the explicit check is cleaner and matches the project's other loop patterns.

### 19. Test framework mismatch

All test files use JUnit 5 `@Test` instead of Kotest `StringSpec` as required by `CLAUDE.md` ("Testing: Kotest (StringSpec variant)"). `CronParserTest`, `JobRepoTest`, `RoutinesEngineTest`, `StuckJobDetectorTest`, `HeartbeatTest`, `SummariserTest` all use `@Test`.

### 20. `workspaceRoot` defaults to `/tmp/hebe` in Summariser, FactExtractor, DailyDigest

These classes should receive `workspaceRoot` from configuration, not rely on a `/tmp` default. The default is acceptable as a test fallback but the constructor should probably require the path or derive it from a `Config` object.

---

## Plan compliance

| Task | Plan requirement | Status |
|---|---|---|
| M8.T1 | `@hourly`, `@daily`, `@every`, 5-field cron | Parsing ✅, nextFire ❌ (month bug, @hourly wrong) |
| M8.T2 | job loop, claimPending, `routine`/`maintenance`/`adhoc`/`heartbeat` dispatch | Loop ✅, `maintenance` dispatch ❌, cancellation ❌ |
| M8.T3 | RoutinesEngine, catchup | Catchup ✅, `JobRepo` bypass ⚠️ |
| M8.T4 | Summariser, `summary_id` marking | Built ✅, V7 migration ❌, marking broken ❌ |
| M8.T5 | FactExtractor, cosine dedup | Built ✅, fact storage destructive ❌, word-overlap not cosine ⚠️ |
| M8.T6 | DailyDigest | Built ✅ (standalone, not wired) |
| M8.T7 | EmbeddingRefresh | Built ✅, no real embed API ❌ |
| M8.T8 | StuckJobDetector | Built ✅, return value wrong ❌ |
| M8.T9 | Heartbeat, silence-on-OK | Built ✅, tests broken ❌, not wired ❌ |

---

## Overall assessment

The module structure is reasonable — clear package split (`cron`, maintenance), each task in its own file, consistent `Result<T>` returns, `Config` inner data classes per task. The job repo and routines engine are solid JDBC with good transaction discipline.

However, M8 is **not ready to ship**. There are three runtime-correctness bugs (month wildcard, `@hourly`, fact destruction) that would cause silent misfires or data loss in production, a missing schema migration, failing tests, and the maintenance dispatch path — the entire point of the job loop for M8.T4–T8 — is not connected. The scheduler infrastructure exists but none of the maintenance work is reachable from the loop.
