# Review 018 — M8: Scheduler + heartbeat (re-review)

**Branch:** `v1-development`
**Based on:** fixes over review-017 findings
**Previous review:** `review-017.md`

---

## Progress since review-017

All required items addressed:

| review-017 item | Fixed? |
|---|---|
| Month wildcard (`parseField("*")` returned `min-1`) | ✅ now returns -1 unconditionally |
| `@hourly` fired at next minute instead of next `:00` | ✅ fires at minute=0 of next hour; tests updated |
| `FactExtractor.appendFact` destroyed facts on each write | ✅ uses `replace(header, header + newLine)` |
| Missing V7 migration for `summary_id` | ✅ `V7__messages_summary_id.sql` added |
| `StuckJobDetector.run()` returned retried count, not stuck count | ✅ renamed to `processed`, incremented on `markJobStuck` |
| `HeartbeatTest` second test failed (non-existent file) | ✅ uses `createTempFile` |
| `maintenance` job kind not dispatched in `JobRunner` | ✅ `runMaintenance` + `maintenanceHandlers` map |
| No composite `Scheduler` class | ✅ `Scheduler.kt` drives both loops |
| `RoutinesEngine` duplicated `JobRepo.insertPending` | ✅ calls `repo.insertPending` |
| `DenyAllApprovalGate` duplicated across classes | ✅ extracted to `SchedulerInternals.kt` |
| `parseList` could never succeed | ✅ throws directly |
| `nextFireStandard` started from `now` (inclusive) | ✅ starts from `now + 1.minutes` |
| `dom` min=0 allowed invalid day 0 | ✅ min=1 |
| Dead `minute`/`hour` defaults on `Hourly`/`Daily` | ✅ removed from sealed interface |
| `Summariser` dead-code filter and unused variable | ✅ both removed |
| All tests using JUnit 5 `@Test` | ✅ all converted to Kotest `StringSpec` |

---

## Remaining findings

### Minor — fix before merge

**1. `parseStep` still uses `min - 1` for `*/n` on `dom` and `month` fields (`CronParser.kt:76`)**

`parseField("*")` was correctly fixed to return -1. But `parseStep` for the `*/n` form still returns `min - 1`:

```kotlin
return if (parts[0] == "*") min - 1 else parseSingle(parts[0], min, max, name)
```

For `minute` and `hour` (min=0), this gives -1 — correct. For `dom` (min=1) and `month` (min=1), it gives 0, which is not the -1 wildcard that `matchesAllFields` tests for. A cron like `*/5 * */2 * *` would never fire because the dom check `0 == local.dayOfMonth` is always false.

Fix: change `min - 1` to `-1` on this line.

**2. `parseList` unused parameters create a file-level suppress (`CronParser.kt:1`, `CronParser.kt:79`)**

Now that `parseList` just throws, the parameters `min`, `max`, `name` are unused, requiring `@file:Suppress("UnusedParameter")`. Remove the three parameters — the signature becomes `private fun parseList(value: String): Int`.

**3. `Services` data class lives in `Heartbeat.kt` (`Heartbeat.kt:36`)**

`Services` bundles `MemoryStore`, `ToolDispatcher`, `LlmProvider`, `CostGuard`, `PreemptivePruner`, `Observer` — a general scheduler dependency container. Defining it inside a maintenance-subpackage file means any other component that needs the same grouping (e.g. future `JobRunner` refactor) must either duplicate it or import from a maintenance class. Move it to `SchedulerInternals.kt` (already exists) or its own `Services.kt` in the `scheduler` package.

**4. `RoutinesEngine` still accesses `repo.ds` directly (`RoutinesEngine.kt:60`, `RoutinesEngine.kt:101`)**

`repo.insertPending` is now used ✅ but `loadEnabledRoutines` and `updateRoutineNextRun` both call `repo.ds.connection.use { ... }` directly, going through the internal `ds` property. `ds` had to be promoted to `internal` visibility just for this. These two operations should become proper `JobRepo` methods (`loadEnabledRoutines()` and `updateRoutineNextRun()`), and `ds` should revert to `private`.

---

### Optional

**5. `runTest` inside Kotest `StringSpec` bodies is redundant**

`StringSpec` test lambdas are natively `suspend`, so `runTest` creates a superfluous nested `TestScope`. It's harmless unless virtual-time control is needed (it isn't here). Replace `runTest { ... }` with a direct call in all maintenance test files.

**6. `JobLoop.loop()` still uses `while (true)` (`JobLoop.kt:24`)**

`while (isActive)` is clearer about cancellation intent. `delay` makes the current implementation correctly cancellation-cooperative, so this is purely a readability issue.

**7. `EmbeddingRefresh` uses chat API for embeddings (`EmbeddingRefresh.kt:85`)**

Not changed from review-017 — still calls `llmProvider.chat()` to request embedding vectors, which a chat model cannot produce meaningfully. Flagged as optional because fixing it requires adding an `embed()` method to `LlmProvider`, which is a larger API surface change. At minimum, add a `TODO` comment explaining the limitation so a future implementer knows what to do.

---

## Overall assessment

M8 is **ready to merge** with the three minor fixes above applied first. The critical correctness bugs (month wildcard, `@hourly`, fact destruction, missing migration) are all resolved. The module now has a clean architecture: `Scheduler` drives `JobLoop` and `RoutinesEngine` at their respective cadences; `JobRunner` dispatches `routine`, `adhoc`, and `maintenance`; `DenyAllApprovalGate` lives in a shared place; and all tests use Kotest `StringSpec`.

The `parseStep` residual (item 1) is the only fix that could cause a silent runtime misfires, though it's triggered only by rare `*/n` expressions on the `dom` or `month` fields. Items 2–4 are clean-up: no-parameter `parseList`, relocating `Services`, and encapsulating `repo.ds`.
