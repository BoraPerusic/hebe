# Tasks — Review 017 (M8 Scheduler)

---

## Required before shipping

- [ ] **Fix `Cron.Standard` month wildcard** (`CronParser.kt:57`, `CronParser.kt:153`)
  `parseField("*", 1, 12, "month")` returns `0` (min-1), but `matchesAllFields` tests for `-1`. Every standard cron with `*` in the month field (i.e. all common crons like `0 9 * * *`) never fires.
  Fix: in `parseField`, change `if (value == "*") return min - 1` to `if (value == "*") return -1`.
  (Also fix `dom` min from 0 to 1 in `parseStandard` while here — `dom` wildcard still works with the -1 fix.)

- [ ] **Fix `Cron.Hourly.nextFireHourly`** (`CronParser.kt:110`)
  Currently returns `now + 1 minute`. Should implement `0 * * * *`: return minute=0 of the next hour.
  ```kotlin
  private fun Cron.Hourly.nextFireHourly(now: Instant, tz: TimeZone): Instant {
      val local = now.toLocalDateTime(tz)
      val nextHour = (local.hour + 1) % 24
      val dayOffset = if (local.hour == 23) 1.days else 0.days
      return LocalDateTime(local.year, local.month.number, local.dayOfMonth, nextHour, 0, 0)
          .toInstant(tz) + dayOffset  // handle month boundary via kotlin-datetime
  }
  ```
  Also update the `@hourly` test assertions (from "next minute" to "next :00").

- [ ] **Fix `FactExtractor.appendFact` data destruction** (`FactExtractor.kt:166`)
  `existing.replaceAfter(FACTS_SECTION, "\n$line")` replaces all content after the section header. Replace with insert logic that prepends the new line after the header:
  ```kotlin
  existing.replace(FACTS_SECTION, "$FACTS_SECTION\n$line")
  ```

- [ ] **Add V7 migration — `summary_id` column in `messages`** (missing file)
  Create `modules/memory/src/main/resources/db/migration/V7__messages_summary_id.sql`:
  ```sql
  ALTER TABLE messages ADD COLUMN summary_id TEXT;
  CREATE INDEX idx_messages_summary ON messages(summary_id) WHERE summary_id IS NOT NULL;
  ```
  Without this, `Summariser` silently produces 0 results on every run.

- [ ] **Fix `StuckJobDetector.run()` return value** (`StuckJobDetector.kt:31`)
  `retried++` only increments when a retry is inserted. The test `run marks stuck jobs and returns count` inserts a non-retryable job and asserts `1`. Rename counter to `processed`, increment it on every `markJobStuck` call:
  ```kotlin
  markJobStuck(job, nowMs)
  processed++
  if (shouldRetry(job)) { insertRetry(job, nowMs) }
  ```
  Return `Result.success(processed)`.

- [ ] **Fix `HeartbeatTest` second test** (`HeartbeatTest.kt:38`)
  Both tests pass `/nonexistent/HEARTBEAT.md`. The blank-file early-return means `notified` is never set to true. Use a temp file with content for the second test:
  ```kotlin
  val tmp = kotlin.io.path.createTempFile("HEARTBEAT", ".md")
  tmp.writeText("- Check disk space")
  val heartbeat = Heartbeat(..., heartbeatFilePath = tmp.toString())
  ```

- [ ] **Wire maintenance tasks into `JobRunner`** (`JobRunner.kt:70`)
  The plan (M8.T2) requires a `maintenance` dispatch. Add a `maintenance` case that delegates to the registered handler:
  ```kotlin
  "maintenance" -> runMaintenance(job)
  "heartbeat"   -> runHeartbeat(job)
  ```
  `JobRunner` should accept a `Map<String, MaintenanceHandler>` constructor param (or a single-dispatch interface), and the maintenance tasks should be registered at startup.

- [ ] **Add composite `Scheduler` class** (missing)
  No class drives `JobLoop` and `RoutinesEngine` together. Create a `Scheduler` that:
  1. Starts `RoutinesEngine.tick()` on a 30 s coroutine timer.
  2. Starts `JobLoop.run(scope)`.
  3. Exposes `start(scope)` and `shutdown()`.
  Wire it from the CLI startup (M9 `RunCommand`, or a dedicated `hebe scheduler` command).

---

## Required — architecture cleanup

- [ ] **Remove `RoutinesEngine` raw JDBC insert; use `JobRepo`** (`RoutinesEngine.kt:101`)
  `insertJob` is a copy of `JobRepo.insertPending`. Change `RoutinesEngine` constructor to accept `JobRepo` and call `repo.insertPending("routine", triggerAt, payloadJson)`.

- [ ] **Consolidate `DenyAllApprovalGate`** (`JobRunner.kt:48`, `Heartbeat.kt:177`)
  Identical private nested object in both classes. Extract to `SchedulerInternals.kt` or a package-internal `object`.

---

## Optional

- [ ] **Fix `parseList` to throw directly** (`CronParser.kt:77`)
  `parseList` is called only when the field contains a comma, so `values.size == 1` is never true and the function can never succeed. Replace the unreachable success path with a direct throw:
  ```kotlin
  private fun parseList(value: String, min: Int, max: Int, name: String): Int {
      throw IllegalArgumentException("Comma lists not supported in v1: '$value'")
  }
  ```

- [ ] **Fix `nextFireStandard` to start from `now + 1 minute`** (`CronParser.kt:138`)
  Starting from `now` inclusive can return `now` itself when the current instant matches all fields, causing immediate re-scheduling. Change to `var current = now + 1.minutes`.

- [ ] **Remove dead code in `Summariser`** (`Summariser.kt:137`, `Summariser.kt:97`)
  - `loadUnsummarisedMessages` always returns its input unchanged (SQL already filters `summary_id IS NULL`; the field is always null in `MessageRow`). Inline or remove.
  - `val timestamp = java.time.Instant.ofEpochMilli(nowMs)` is unused.

- [ ] **Convert all test files to Kotest `StringSpec`** (project convention per `CLAUDE.md`)
  `CronParserTest`, `JobRepoTest`, `RoutinesEngineTest`, `StuckJobDetectorTest`, `HeartbeatTest`, `SummariserTest` all use JUnit 5 `@Test`. Convert to `StringSpec` with `{ "description" { ... } }` blocks.

- [ ] **Address `EmbeddingRefresh` fundamental issue** (`EmbeddingRefresh.kt:85`)
  The chat API cannot produce real embeddings. Either add `embed(texts: List<String>): List<FloatArray>` to `LlmProvider`, or document the limitation with a `TODO` and return zero vectors explicitly. Currently the code pretends to embed via chat which will produce garbage silently.

- [ ] **Remove dead `minute`/`hour` properties from `Cron` sealed interface** (`Cron.kt:7`)
  These default properties are only meaningful on `Standard`. They serve no purpose on `Hourly`, `Daily`, or `Every` and the redundant overrides on `Every` add noise.

- [ ] **Derive `workspaceRoot` from config** (`Summariser.kt:22`, `FactExtractor.kt:21`, `DailyDigest.kt:19`)
  The `/tmp/hebe` default will cause silent data loss in production if not overridden. Pass the workspace path explicitly from the constructor call site; remove the default.
