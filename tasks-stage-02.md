# M2 Implementation Task List

**Goal:** Implement all M2 tasks (LLM + agent loop). Done when a mock-LLM end-to-end test runs a multi-tool turn through the dispatcher with receipts written and memory appended.

**Dependency order:**
```
M2.T2 (Mock) ──────────────────────────┐
M2.T1 (OpenAiCompat) ─────────────────┤
M2.T4 (SubmissionParser) ──────────────┼──┐
M2.T5 (ApprovalGate) ──────────────────┤  │
                                        M2.T6 (ToolDispatcher)
M2.T7 (LoopDetector) ─────────────────┤  │
M2.T8 (CostGuard) ────────────────────┤  ├─ M2.T11 (ChatDelegate) ─ M2.T13 (HebeAgent) ─ M2.T14 (Hooks)
M2.T9 (Compactor) ────────────────────┤  │         │                     │
M2.T10 (PreemptivePruner) ───────────┘  │         ▼
                                          │   M2.T12 (JobDelegate)
M2.T3 (KoogLlmProvider) ────────────────┘
                                          M2.T15 (AuthMode)
```

## All M2 Tasks Status

| Task | Status | Notes |
|------|--------|-------|
| M2.T1 OpenAiCompatProvider | ✅ DONE | Wire.kt, SseParser.kt, HttpClientFactory.kt, OpenAiCompatProvider.kt |
| M2.T2 MockLlmProvider | ✅ DONE | Builder DSL in openai-compat main sources |
| M2.T3 KoogLlmProvider | ✅ DONE | Simplified to thin pass-through; KoogPromptExecutor retained for future koog integration |
| M2.T4 SubmissionParser | ✅ DONE | Parses authMode → Approval → SystemCommand → UserInput |
| M2.T5 ApprovalGate | ✅ DONE | PendingApprovalsRepo + Channel + ConcurrentHashMap + awaitApproval (R04-01 fix: flow.last()) |
| M2.T6 ToolDispatcher | ✅ DONE | State machine: loopDetector → validators → approval → invoke → receipts → memory |
| M2.T7 LoopDetector | ✅ DONE | sha256 fingerprint, warn at 3, force-text at 5; reads synchronized (R04-13 fix) |
| M2.T8 CostGuard | ✅ DONE | dailyUsdCap + perTurnTokenCap; duration now recorded (R04-15 fix) |
| M2.T9 Compactor | ✅ DONE | 3-step ladder; compacted history now threaded through to callLlm (R04-03 fix) |
| M2.T10 PreemptivePruner | ✅ DONE | Simple delegator to Compactor |
| M2.T11 ChatDelegate | ✅ DONE | afterIteration calls costGuard.recordCall (R04-04 fix); model from capabilities (R04-11 fix) |
| M2.T12 JobDelegate | ✅ DONE | Same fixes as ChatDelegate |
| M2.T13 HebeAgent | ✅ DONE | /compact implemented (R04-06); AuthMode stores secret via SecretStore (R04-05) |
| M2.T14 Hooks | ✅ DONE | HookRunner with BeforeInbound, BeforeOutbound, BeforeToolCall, OnSessionStart, OnSessionEnd |
| M2.T15 AuthMode | ✅ DONE | AuthMode.kt created; handleAuthMode stores via SecretStoreProvider (R04-05 fix) |

## Gate Criterion

✅ **E2E test passes:** `AgentLoopE2ETest` — mock-LLM multi-tool turn through dispatcher with receipts written and memory appended.
