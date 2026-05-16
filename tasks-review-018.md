# Tasks — Review 018 (M8 re-review)

---

## Required before merge

- [ ] **Fix `parseStep` residual: `min - 1` → `-1` for `*/n` on dom/month** (`CronParser.kt:76`)
  `*/n` expressions on `dom` (min=1) or `month` (min=1) fields return 0 instead of -1, so they never match any day/month in `matchesAllFields`.
  ```kotlin
  // CronParser.kt line 76 — change:
  return if (parts[0] == "*") min - 1 else parseSingle(parts[0], min, max, name)
  // to:
  return if (parts[0] == "*") -1 else parseSingle(parts[0], min, max, name)
  ```

- [ ] **Remove unused parameters from `parseList`; drop file-level suppress** (`CronParser.kt:1`, `CronParser.kt:79`)
  `parseList` ignores `min`, `max`, `name` — they exist only to match the call site. Remove them from the signature and remove `@file:Suppress("UnusedParameter")`:
  ```kotlin
  private fun parseList(value: String): Int {
      throw IllegalArgumentException("Comma lists not supported in v1: '$value'")
  }
  // call site in parseField: if (value.contains(",")) return parseList(value)
  ```

- [ ] **Move `Services` data class to `SchedulerInternals.kt`** (`Heartbeat.kt:36`)
  `Services` is a general scheduler dependency container, not a Heartbeat-specific type. Move the class to `SchedulerInternals.kt` (already exists) and update the import in `HeartbeatTest`.

- [ ] **Encapsulate `loadEnabledRoutines` and `updateRoutineNextRun` in `JobRepo`** (`RoutinesEngine.kt:60`, `RoutinesEngine.kt:101`)
  `RoutinesEngine` calls `repo.ds.connection` directly, requiring `ds` to be `internal` on `JobRepo`. Move the two operations into `JobRepo`:
  ```kotlin
  // JobRepo:
  fun loadEnabledRoutines(): List<RoutineRow> { ... }
  fun updateRoutineNextRun(routineId: String, lastRunAt: Long, nextRunAt: Long) { ... }
  ```
  Then `RoutinesEngine` calls `repo.loadEnabledRoutines()` / `repo.updateRoutineNextRun(...)` and `ds` reverts to `private`.

---

## Optional

- [ ] **Remove `runTest` wrappers in Kotest `StringSpec` tests**
  Kotest test bodies are natively suspend; `runTest { ... }` creates a redundant `TestScope` with no benefit here. Unwrap the calls in `HeartbeatTest`, `SummariserTest`, `FactExtractorTest`, `DailyDigestTest`, `EmbeddingRefreshTest`.

- [ ] **Use `while (isActive)` in `JobLoop.loop()` and `Scheduler.runRoutinesLoop()`** (`JobLoop.kt:24`, `Scheduler.kt:28`)
  Both loops rely on `delay` for cancellation; switching to `while (isActive)` makes the intent explicit and consistent with convention.

- [ ] **Add `TODO` to `EmbeddingRefresh.embedBatch`** (`EmbeddingRefresh.kt:85`)
  The chat-based embedding is a placeholder. Add a comment:
  ```kotlin
  // TODO: replace with a real embedding endpoint (LlmProvider.embed) when available.
  // Chat completions cannot produce meaningful vector embeddings.
  ```
