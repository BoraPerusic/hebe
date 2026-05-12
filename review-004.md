# Review 004 — M2 Stage (LLM + Agent Loop)

**Branch:** v1-development  
**Reviewer:** Claude Code  
**Date:** 2026-05-11  
**Claim:** M2 Stage complete.

---

## Summary

The M2 stage has substantial implementation but is **not complete**. Fourteen of fifteen tasks
have code on disk, but three of those have blocking bugs, one (M2.T15 AuthMode) is entirely
missing, and the "done when" acceptance criterion (mock-LLM end-to-end test with receipts and
memory appended) is not met. The task tracking files are also significantly out of sync with
reality.

Additionally, the sqlite-vec extension was broken (silent `NoSuchMethodException` in the
original reflection call + wrong `DataSource` wiring for Flyway). This was fixed as part of
this review — all 36 memory tests now pass.

---

## Deviations from Plan

| Task | Planned | Actual | Deviation |
|------|---------|--------|-----------|
| M2.T7 LoopDetector | "in ToolDispatcher.kt" | Separate `LoopDetector.kt` | Minor — better |
| M2.T3 KoogLlmProvider | koog wraps OpenAiCompatProvider | `KoogLlmProvider.chat()` just delegates to transport; koog `AIAgent` created but never used | Significant |
| M2.T6 dispatch outcome | Returns `DispatchOutcome.Pending` when approval needed | Always blocks inline via `awaitApproval`; `Pending` variant never returned | Deviation |
| M2.T15 AuthMode | `core/auth/AuthMode.kt` created | File does not exist; `HebeAgent.handleAuthMode` is a one-line stub | Missing |
| M2.T11/T12 afterIteration | Calls `costGuard.recordCall` | No-op in both ChatDelegate and JobDelegate | Not implemented |

---

## Critical Bugs

- [ ] **R04-01 `ApprovalGate.awaitApproval` always returns `false`**  
  `flow.first()` collects only the first emission (`ApprovalStatus.Pending`), never the actual
  decision. Fix: replace with `flow.last()` (or `flow.drop(1).first()`). Additionally, after
  `first()` cancels the upstream, the waiter entry stays in `waiters` indefinitely — a minor
  memory leak.  
  File: `modules/security/src/main/kotlin/com/hebe/security/approval/ApprovalGate.kt:75`

- [ ] **R04-02 `SessionManager` not thread-safe**  
  Uses `mutableMapOf()` without synchronization. Concurrent `handleMessage` calls on different
  sessions can corrupt the map.  
  Fix: use `ConcurrentHashMap`.  
  File: `modules/core/src/main/kotlin/com/hebe/core/agent/SessionManager.kt:6`

- [ ] **R04-03 Compaction result discarded — compaction has no effect**  
  `ChatDelegate.beforeLlmCall` calls `compactor.prune(history, ctx.turnId)` but uses only
  `.compacted` flag; the compacted message list is thrown away. `callLlm` then calls
  `memory.loadContext(sessionId)` again, getting the original uncompressed history. Workspace
  files are written but the replaced messages are never persisted to the memory store. The 3-step
  ladder runs but has no observable effect on the context sent to the LLM.  
  File: `modules/core/src/main/kotlin/com/hebe/core/delegate/ChatDelegate.kt:71-76`

- [ ] **R04-04 Cost recording never happens**  
  `ChatDelegate.afterIteration` and `JobDelegate.afterIteration` are both no-ops. Per spec,
  `afterIteration` must call `costGuard.recordCall(turnId, model, tokensIn, tokensOut, ...)`.
  Without this, `llm_calls` is never populated, so the daily budget check always sees $0 spent.  
  Files: `ChatDelegate.kt:205`, `JobDelegate.kt:197`

---

## Missing Implementation

- [ ] **R04-05 M2.T15 AuthMode not created**  
  `modules/core/src/main/kotlin/com/hebe/core/auth/AuthMode.kt` does not exist.
  `HebeAgent.handleAuthMode` returns a hardcoded `"[credential entered]"` without touching
  `SecretStore` or resuming the suspended turn. This task is entirely unimplemented.

- [ ] **R04-06 M2.T11 `/compact` handler is a stub**  
  `HebeAgent.handleSystemCommand(SlashCommand.Compact)` returns `"Compaction not yet implemented"`.
  Per spec: should call `Compactor.maybeCompact` synchronously.

- [ ] **R04-07 No end-to-end test**  
  The M2 "done when" criterion requires: *"a mock-LLM end-to-end test runs a multi-tool turn
  through the dispatcher with receipts written and memory appended."*  
  No such test exists in the `dispatch` or `core` modules. This is the gate criterion for the stage.

- [ ] **R04-08 Missing `OpenAiCompatProvider` fixture tests**  
  M2.T1 acceptance requires "recorded-fixture test: feed a recorded SSE stream through `chat(...)`;
  assert Flow<StreamEvent> emits [TextDelta×N, ToolCall, Done]." Only `MockLlmProviderTest` exists.

---

## Architecture Issues

- [ ] **R04-09 `KoogLlmProvider` bypasses koog entirely**  
  `KoogLlmProvider.chat()` ignores `koogAgent` and delegates straight to `transport.chat()`.
  The koog `AIAgent` is constructed in `KoogLlmProviderFactory.create()` but is never called.
  Koog's history compression, tool dispatch, and agent persistence are all bypassed. This means
  M2.T3's acceptance criterion ("Shape parity with OpenAiCompatProvider; koog is the only file
  importing `ai.koog.*`") passes structurally but the adapter adds no value. The file needs
  to either truly route through koog or be simplified to a thin pass-through with a note explaining
  why koog is not used for direct chat.

- [ ] **R04-10 `ApprovalGate` API split — two types for the same concept**  
  `com.hebe.api.ApprovalGate` (interface in `Tool.kt`) and `com.hebe.security.approval.ApprovalGate`
  (concrete class) are separate types. `ToolContext.approvalGate` is the API interface, but
  `ToolDispatcher` takes the concrete `security` class. Callers must keep both in sync. The concrete
  class should implement the API interface.

- [ ] **R04-11 Model name resolved from `activeSkills`**  
  Both `ChatDelegate.callLlm` and `JobDelegate.callLlm` use `reasoning.activeSkills.firstOrNull() ?: "default"` as the
  `ChatRequest.model` field. `activeSkills` is a list of skill names, not model identifiers.
  The model name should come from config or `LlmProvider.capabilities()`.  
  Files: `ChatDelegate.kt:102`, `JobDelegate.kt:97`

- [ ] **R04-12 `DispatchOutcome.Pending` is dead code**  
  `ToolDispatcher.dispatch()` never emits `DispatchOutcome.Pending`. Approval is handled inline
  via `awaitApproval` (blocking the coroutine). Both `ChatDelegate` and `JobDelegate` have
  handling code for `Pending` that can never be reached. Decision needed: either always block
  inline (remove `Pending` variant) or return `Pending` early and implement resume semantics.

- [ ] **R04-13 `LoopDetector.shouldWarn/shouldForceText` unsynchronized reads**  
  `fingerprint()` is `suspend` and holds `turnMutex` during write. `shouldWarn/shouldForceText`
  are non-suspend and read the same map without the mutex. Concurrent calls could see stale data.  
  Fix: make read methods also acquire the mutex (or declare the detector single-coroutine-only).

- [ ] **R04-14 `KoogPromptExecutor.moderate()` throws `TODO`**  
  Will crash at runtime if koog calls into moderation. Should return a no-op allow result.

- [ ] **R04-15 `CostGuard.recordCall` always writes `ms = 0`**  
  The duration field `ms` is hardcoded to `0L`; the actual LLM call duration is never measured.

---

## Tests Missing for New Code

- [ ] **R04-16** No tests for `ChatDelegate` (spec calls for 4 scenarios including max-iterations and approval)
- [ ] **R04-17** No tests for `Compactor` / `PreemptivePruner`
- [ ] **R04-18** No tests for `CostGuard`
- [ ] **R04-19** No tests for `ToolDispatcher` (spec calls for mock-tool dispatch, deny, approval flow)
- [ ] **R04-20** No tests for `HebeAgent` (spec calls for each Submission variant)
- [ ] **R04-21** No tests for `LoopDriver` / `LoopDelegate`

---

## Task Tracking Inconsistencies

- [ ] **R04-22 `tasks-stage-02.md` is entirely stale**  
  The personal todo file marks all 15 tasks as `pending`. In reality T1–T14 have code on disk.
  This file should be updated to reflect current status or removed.

- [ ] **R04-23 `tasks-stage-02-progress.md` "Pending" section is wrong**  
  The progress tracker lists M2.T12 (JobDelegate), M2.T13 (HebeAgent), and M2.T14 (Hooks) as
  `⬜ PENDING`. All three have implementations on disk. Only M2.T15 (AuthMode) is truly missing.

- [ ] **R04-24 `tasks-stage-02-progress.md` "Next Steps" section is stale**  
  Still says "Continue with M2.T6" and "Continue with M2.T7" — both are done.

---

## Fixed in This Review

The following issues were diagnosed and fixed before this review was written:

- **sqlite-vec extension silent failure**: `SqliteVecExtension.load()` used reflection with
  `Boolean::class.java` (boxed) to find `enableLoadExtension(boolean)` (primitive) — this caused
  a `NoSuchMethodException` that was swallowed silently. Also, extension loading was only called
  on the initial manually-opened connection, not on the Flyway connections. Fixed by using
  `SQLiteDataSource.setLoadExtension(true)` (proper API) and introducing a `VecLoadingDataSource`
  wrapper that calls `SELECT load_extension(...)` on every connection obtained from the pool.
  Files changed: `SqliteVecExtension.kt`, `Db.kt`.  
  **All 36 memory module tests now pass.**
