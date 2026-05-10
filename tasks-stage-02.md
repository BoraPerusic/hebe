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

## Phase 1: Providers (M2.T1, M2.T2, M2.T3)

### M2.T1 — OpenAiCompatProvider (Ktor + streaming + tool use)
**Status:** pending
**Size:** L

Files to create:
- `modules/providers/openai-compat/src/main/kotlin/com/hebe/providers/openai/Wire.kt` — request/response DTOs
- `modules/providers/openai-compat/src/main/kotlin/com/hebe/providers/openai/SseParser.kt` — SSE stream parser
- `modules/providers/openai-compat/src/main/kotlin/com/hebe/providers/openai/HttpClientFactory.kt` — Ktor client factory
- `modules/providers/openai-compat/src/main/kotlin/com/hebe/providers/openai/OpenAiCompatProvider.kt` — main LlmProvider impl
- Tests with recorded fixtures

Key points:
- Deps: `:modules:api`, `:modules:observability`, `libs.bundles.ktor.client`, `libs.kotlinx.serialization.json`
- Wire.kt: ChatCompletionRequest/Response, streaming chunks (ChatCompletionChunk), all the nested DTOs
- Streaming SSE: parse `data:` lines, accumulate tool_call.arguments per index, emit `StreamEvent.ToolCall` when finish_reason=="tool_calls"
- HttpClientFactory: CIO engine, Json negotiation, retry plugin (429/5xx), HttpTimeout (30s connect, 5min read)
- Auth: Bearer token from SecretStore; never log it
- System prompt → WireMessage(role="system", ...)

### M2.T2 — MockLlmProvider (replay-based)
**Status:** pending
**Size:** M

Files to create:
- `modules/providers/openai-compat/src/main/kotlin/com/hebe/providers/openai/MockLlmProvider.kt`

Key points:
- Placed in openai-compat module so other modules can use it (not test sources)
- Builder DSL: `.turn { textDelta("..."); toolCall(...); done() }.build()`
- AtomicInteger for turn sequencing
- Multi-turn scripts work; out-of-script throws

### M2.T3 — KoogLlmProvider adapter
**Status:** pending
**Size:** L

Files to create:
- `modules/core/build.gradle.kts` (edit)
- `modules/core/src/main/kotlin/com/hebe/core/llm/KoogLlmProvider.kt`

Key points:
- Deps: `:modules:api`, `:modules:providers:openai-compat`, `libs.koog.core`
- Only file importing `ai.koog.*` from a public type
- Translate ChatRequest → koog request, koog stream → Flow<StreamEvent>
- Keep all ai.koog.* refs inside this file

## Phase 2: Core Loop (M2.T4 → M2.T6, M2.T7, M2.T8, M2.T9, M2.T10)

### M2.T4 — SubmissionParser
**Status:** pending
**Size:** M

Files to create:
- `modules/core/src/main/kotlin/com/hebe/core/submission/SubmissionParser.kt`
- `modules/core/src/main/kotlin/com/hebe/core/submission/SlashCommandParser.kt`
- Tests

Key points:
- Submission types: UserInput, SystemCommand(msg, command), Approval(msg, approvalId, approved), AuthMode(msg, purpose, secret), QuitCommand
- Parse order: authMode check → slash command → Approval → SystemCommand → UserInput
- SlashCommands: /compact, /status, /help, /skills (with optional filter), /quit, /exit
- /approve <id> or /deny <id> → Approval
- Unknown slash → UserInput (fallback, don't throw)

### M2.T5 — ApprovalGate
**Status:** pending
**Size:** M

Files to create:
- `modules/security/build.gradle.kts` (edit)
- `modules/security/src/main/kotlin/com/hebe/security/approval/ApprovalGate.kt`
- `modules/security/src/main/kotlin/com/hebe/security/approval/PendingApprovalsRepo.kt`
- Tests

Key points:
- Deps: `:modules:api`, `:modules:memory`, `:modules:observability`
- Uses existing `pending_approvals` table from M1 migrations
- Channel<ApprovalDecision> per pending id in ConcurrentHashMap for suspension
- requestIfNeeded(toolName, args, ctx): ApprovalDecision
- TTL expiry (default 24h), deny on expiry
- hebe estop → Deny
- Restart-time scan for unresolved approvals

### M2.T6 — ToolDispatcher skeleton + state machine
**Status:** pending
**Size:** L

Files to create:
- `modules/tools/dispatch/build.gradle.kts` (edit)
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/ToolDispatcher.kt`
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/ToolRegistry.kt`
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/DispatchOutcome.kt`
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/Validator.kt`
- Tests

Key points:
- Deps: `:modules:api`, `:modules:security`, `:modules:memory`, `:modules:observability`
- State machine order: loopDetector → validators → approval → invoke → leak scan → receipts → memory → observer → hooks
- DispatchOutcome: Result(ToolResult) | Pending(approvalId)
- Receipts written for every dispatch (allow/deny/error)
- Memory appended for every successful or errored result
- Annotate with `// dispatch-exempt: …` since M0.T10 rule fires here

### M2.T7 — LoopDetector
**Status:** pending
**Size:** M

Files to create:
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/LoopDetector.kt`
- Tests

Key points:
- Per-turn Map<String (fingerprint), Int (count)>
- Fingerprint: sha256(tool.name + canonicalJson(args))
- shouldWarn: count == 3
- shouldForceText: count >= 5 → inject synthetic text + abort loop
- State cleared at end of turn

### M2.T8 — CostGuard
**Status:** pending
**Size:** M

Files to create:
- `modules/core/src/main/kotlin/com/hebe/core/cost/CostGuard.kt`
- Tests

Key points:
- Config: dailyUsdCap, perTurnTokenCap (defaults: $5/day, 100k tokens/turn)
- checkAllowed(turnId): Allow | DenyDaily(spentUsd) | DenyPerTurn(tokens)
- recordCall(turnId, tokensIn, tokensOut, costMicrosUsd?) → llm_calls row
- Wire by ChatDelegate: before LLM call checkAllowed, after Done with TokenUsage recordCall
- cost_micros_usd nullable → treat as 0 for billing, warn cap is unenforceable

### M2.T9 — Compactor (3-step ladder)
**Status:** pending
**Size:** L

Files to create:
- `modules/core/src/main/kotlin/com/hebe/core/compaction/Compactor.kt`
- `modules/core/src/main/kotlin/com/hebe/core/compaction/CompactionStrategy.kt`
- Tests

Key points:
- Step 1 (workspace-promote): large blobs (>4KB) → workspace/context/<turn>-<seq>.md, replace with reference
- Step 2 (summarise): call LLM with summarisation prompt over oldest messages past keep window
- Step 3 (refuse-to-truncate): throw HebeException.Memory("compaction failed; refusing to truncate") if summarisation fails
- Token heuristic: word count × 1.3
- threshold = config.compactionThreshold ?: 0.6

### M2.T10 — PreemptivePruner
**Status:** pending
**Size:** S

Files to create:
- `modules/core/src/main/kotlin/com/hebe/core/compaction/PreemptivePruner.kt`
- Tests

Key points:
- Called by ChatDelegate.beforeLlmCall every iteration
- Delegates to Compactor.maybeCompact — same mechanism, "preemptive" is documentation
- Ensures compaction runs even on first iteration when history loaded from disk

## Phase 3: Delegates (M2.T11 → M2.T12)

### M2.T11 — ChatDelegate (implements LoopDelegate)
**Status:** pending
**Size:** L

Files to create:
- `modules/core/src/main/kotlin/com/hebe/core/delegate/ChatDelegate.kt`
- `modules/core/src/main/kotlin/com/hebe/core/loop/LoopDriver.kt`
- Tests against MockLlmProvider

Key points:
- LoopDriver.runAgenticLoop(delegate, reasoning, ctx, config): LoopOutcome
- Loop: checkSignals → beforeLlmCall → callLlm → handle response → executeToolCalls → afterIteration
- checkSignals(): estop + cancel
- beforeLlmCall: cost-guard check + preemptive prune (calls compactor)
- callLlm: builds ChatRequest from systemPrompt + history + tools + skills, streams, calls channel.updateDraft every 80 chars
- handleTextResponse: append assistant message to memory, return FinishWith(text)
- executeToolCalls: for each call, dispatcher.dispatch; on Pending return NeedApproval; on Result append and continue
- afterIteration: cost-guard recordCall
- Per-session Mutex keyed by sessionId

### M2.T12 — JobDelegate (scheduler-side)
**Status:** pending
**Size:** M

Files to create:
- `modules/core/src/main/kotlin/com/hebe/core/delegate/JobDelegate.kt`
- Tests

Key points:
- Same loop as ChatDelegate, no draft updates, sequential tools, no session lock
- On completion, write result to jobs.result_json
- Output to file or MEMORY.md per routine spec (M8)

## Phase 4: Agent + Hooks (M2.T13 → M2.T14 → M2.T15)

### M2.T13 — HebeAgent facade
**Status:** pending
**Size:** M

Files to create:
- `modules/core/src/main/kotlin/com/hebe/core/agent/HebeAgent.kt`
- `modules/core/src/main/kotlin/com/hebe/core/agent/SessionManager.kt`
- Tests

Key points:
- SessionManager: in-memory Map<sessionId, Mutex>, resolves IncomingMessage to Session, creates if missing
- handleMessage(msg): BeforeInbound hooks → SubmissionParser → branch on Submission type
- SystemCommand: handle without locking (/help, /status, /skills); /compact runs Compactor synchronously
- QuitCommand: signal shutdown
- Approval: approvalGate.resolve(id, approved), reply confirmation
- AuthMode: secretStore.put(secretName, secret), resume, redacted placeholder in messages
- UserInput: lock session, run ChatDelegate.run(), return HandleOutcome
- BeforeOutbound hooks → return HandleOutcome

### M2.T14 — Hooks
**Status:** pending
**Size:** M

Files to create:
- `modules/core/src/main/kotlin/com/hebe/core/hooks/Hook.kt`
- `modules/core/src/main/kotlin/com/hebe/core/hooks/HookRunner.kt`
- Tests

Key points:
- Hook interfaces: BeforeInbound, BeforeOutbound, BeforeToolCall, OnSessionStart, OnSessionEnd
- All are fun interfaces (single-method suspend)
- HookRunner holds lists of each, run() invokes in registration order
- null return = short-circuit (suppression)
- Exceptions caught + logged = no-op (fail-open)
- Wire into HebeAgent and ToolDispatcher

### M2.T15 — AuthMode interception
**Status:** pending
**Size:** M

Files to create:
- `modules/core/src/main/kotlin/com/hebe/core/auth/AuthMode.kt`
- Tests

Key points:
- State: Map<conversationId, AuthRequest> in memory + row in small table
- When tool calls ask_user(purpose="credential", secretName="github_pat"), dispatcher returns Pending, agent enters auth-mode
- Next inbound parsed as AuthMode, HebeAgent: secretStore.put → resume → redacted "[credential entered]" in messages
- /cancel exits auth-mode without storing
- Verify messages table never contains raw credential

---

## Current Status

### Completed (from previous sessions):
- M0: all 12 tasks done
- M1: all 16 tasks done
- Review items R01-01 through R01-33, R02-01 through R02-14 done

### Pending (M2 - 15 tasks):
- M2.T1: OpenAiCompatProvider
- M2.T2: MockLlmProvider
- M2.T3: KoogLlmProvider
- M2.T4: SubmissionParser
- M2.T5: ApprovalGate
- M2.T6: ToolDispatcher
- M2.T7: LoopDetector
- M2.T8: CostGuard
- M2.T9: Compactor
- M2.T10: PreemptivePruner
- M2.T11: ChatDelegate
- M2.T12: JobDelegate
- M2.T13: HebeAgent
- M2.T14: Hooks
- M2.T15: AuthMode

## Critical Notes
- koog version to confirm: check if 0.8.0 is still current before M2.T3
- sqlite-vec binaries: need to be in place for integration tests
- M0.T10 MutationFunnelRule needs to stay clean as M2.T6 lands