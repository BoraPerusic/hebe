# Stage 02 Progress — M2 (LLM + Agent Loop)

**Date:** Mon May 11 2026
**Status:** Working on M2.T9-T11 (Compactor, PreemptivePruner, ChatDelegate) — type mismatches to resolve

---

## Done This Session

### M2.T6 (ToolDispatcher) — DONE

**Goal:** State machine implementing the dispatch funnel per arch §9.

**Files created:**
- `modules/tools/dispatch/build.gradle.kts` — updated with deps
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/DispatchOutcome.kt` — sealed interface
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/Validator.kt` — interface + ValidationResult
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/ToolRegistry.kt` — registry class
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/ToolDispatcher.kt` — main dispatcher

**What was implemented:**
1. State machine: loopDetector → validators → approval → invoke → leak scan → receipts → memory → observer
2. `DispatchOutcome.Result` and `.Pending` sealed types
3. Per-turn loop detection with fingerprinting
4. Pluggable `Validator` interface
5. `awaitApproval` helper added to `ApprovalGate` for blocking semantics
6. `LeakDetector` and `Receipts` as interfaces (stub implementations)

### M2.T7 (LoopDetector) — DONE

Built into `ToolDispatcher.kt` as `LoopDetector` class:
- Per-turn fingerprint map using sha256(tool.name + canonicalJson(args))
- `shouldWarn` at count == 3
- `shouldForceText` at count >= 5

### M2.T8 (CostGuard) — DONE

**Files created:**
- `modules/core/src/main/kotlin/com/hebe/core/cost/CostGuard.kt`
- Updated `HebeConfig.kt` with `CostSection` (dailyUsdCap, perTurnTokenCap, compactionThreshold)

**What was implemented:**
- `checkAllowed(turnId)`: queries llm_calls for today's spend
- `recordCall(...)`: inserts into llm_calls table
- Emits `ObserverEvent.LlmCall`

### M2.T9 (Compactor) — PARTIAL

**Files created:**
- `modules/core/src/main/kotlin/com/hebe/core/compaction/Compactor.kt`
- 3-step ladder: workspace-promote → summarise → refuse-to-truncate

**Blocker:** `Compactor.maybeCompact` uses `List<ConversationMessage>` but the LLM call flow uses `List<ChatMessage>`. Need to resolve which type to use consistently.

### M2.T10 (PreemptivePruner) — PARTIAL

**Files created:**
- `modules/core/src/main/kotlin/com/hebe/core/compaction/PreemptivePruner.kt`

Same type issue as M2.T9.

### M2.T11 (ChatDelegate + LoopDriver) — PARTIAL

**Files created:**
- `modules/api/src/main/kotlin/com/hebe/api/LoopTypes.kt` — LoopSignal, LoopOutcome, RespondOutput, TextAction, LoopConfig, ReasoningContext, Reasoning
- `modules/core/src/main/kotlin/com/hebe/core/loop/LoopDriver.kt` — `runAgenticLoop` + `LoopDelegate`
- `modules/core/src/main/kotlin/com/hebe/core/delegate/ChatDelegate.kt` — main delegate

**Blockers:**
1. `dispatcher.dispatch(call, ctx)` — `ctx` is `ReasoningContext` but dispatcher expects `ToolContext` (which has the same fields)
2. `Clock.System.now().plusSeconds(24 * 3600)` — `plusSeconds` not available on `Instant`

---

## All M2 Tasks Status

| Task | Status | Notes |
|------|--------|-------|
| M2.T1 OpenAiCompatProvider | ✅ DONE | |
| M2.T2 MockLlmProvider | ✅ DONE | |
| M2.T3 KoogLlmProvider | ✅ DONE | |
| M2.T4 SubmissionParser | ✅ DONE | |
| M2.T5 ApprovalGate | ✅ DONE | |
| M2.T6 ToolDispatcher | ✅ DONE | |
| M2.T7 LoopDetector | ✅ DONE | |
| M2.T8 CostGuard | ✅ DONE | |
| M2.T9 Compactor | 🔄 PARTIAL | Type mismatch: ConversationMessage vs ChatMessage |
| M2.T10 PreemptivePruner | 🔄 PARTIAL | Same type issue |
| M2.T11 ChatDelegate | 🔄 PARTIAL | ReasoningContext vs ToolContext; Instant.plusSeconds |
| M2.T12 JobDelegate | ⬜ PENDING | |
| M2.T13 HebeAgent | ⬜ PENDING | |
| M2.T14 Hooks | ⬜ PENDING | |
| M2.T15 AuthMode | ⬜ PENDING | |

---

## Open Questions

1. **Type conflict (M2.T9/T10/T11):** `Compactor` and `MemoryStore` work with `ConversationMessage`, but `ChatRequest` expects `ChatMessage`. The delegate must convert between them. Is the intent that compaction operates on `ChatMessage` (the API type) rather than `ConversationMessage` (the storage type)?

2. **ReasoningContext vs ToolContext (M2.T11):** `ToolDispatcher.dispatch()` takes `ToolContext` but `ChatDelegate` has `ReasoningContext`. They have identical fields. Should `dispatch` accept `ReasoningContext`, or should we have the delegate convert?

3. **Instant arithmetic:** `Clock.System.now().plusSeconds()` doesn't exist — `Instant` only has `plusMillis()`. For the approval expiry, should I use `java.time.Instant.now().plusSeconds()` instead?

---

## Next Steps

1. Resolve type conflicts (ConversationMessage ↔ ChatMessage)
2. Fix Instant.plusSeconds → use java.time.Instant or Duration
3. Continue M2.T12 (JobDelegate)
4. Continue M2.T13 (HebeAgent)
