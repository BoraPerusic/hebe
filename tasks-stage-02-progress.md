# Stage 02 Progress — M2 (LLM + Agent Loop)

**Date:** Sun May 10 2026
**Status:** M2.T3 (KoogLlmProvider) still blocked — compilation errors persist

---

## Done This Session

### M2.T3 (KoogLlmProvider) — IN PROGRESS, BLOCKED

**Goal:** Create adapter that wraps koog 0.8.0's `AIAgent<String, String>` using our `OpenAiCompatProvider` as transport. Only file in repo that imports `ai.koog.*`.

**What was done:**
1. Studied koog 0.8.0 source at `/Users/bora/Dev/view-only/koog` — key findings:
   - `AIAgent` is expect/abstract class with factory function `AIAgent(...)` in `AIAgentFactory.kt`
   - `PromptExecutor` is expect/abstract class implementing `PromptExecutorAPI`
   - `Message` is sealed interface with `User`, `Assistant`, `Tool.Call`, `Tool.Result`, `System`, `Reasoning`
   - `StreamFrame` has `TextDelta`, `TextComplete`, `ToolCallDelta`, `ToolCallComplete`, `End` (no `Done`)
   - `ContentPart.Text` is nested class in `ContentPart` sealed interface
   - `ResponseMetaInfo.create(clock)` takes `KoogClock` (functional interface from `ai.koog.utils.time`)
   - `singleRunStrategy()` returns `AIAgentGraphStrategy<String, String>`

2. Created `api-doc/koog-api.md` with full API findings

3. Built `KoogLlmProvider.kt` with:
   - `KoogLlmProvider` wrapper class (delegates to transport)
   - `KoogLlmProviderFactory.create()` factory using `AIAgent(...)` factory function
   - `KoogPromptExecutor` extending `PromptExecutor()` with `execute` and `executeStreaming`
   - `Prompt.toChatRequest()` translator (koog -> Hebe types)

**Current error:**
```
e: Unresolved reference 'KoogClock'
e: Class 'KoogPromptExecutor' is not abstract and does not implement abstract member
e: Suspend function 'suspend fun chat(...)' can only be called from a coroutine
```

**Root cause:** `ai.koog.utils.time.KoogClock` not resolving despite `api(libs.koog.utils)` in build.gradle.kts. The `utils-jvm` artifact (size 15KB) doesn't include compiled classes for `KoogClock` — only `TimeUtils.class`. KoogClock is defined in `utils/src/commonMain` but the published `utils-jvm` jar is empty/broken.

**Workaround applied:** Replaced `KoogClock.System` with custom `hebeClock = Clock { Instant.fromEpochMilliseconds(System.currentTimeMillis()) }` using stdlib `kotlin.time.Clock`.

**Still broken:** The `KoogPromptExecutor` class not implementing abstract `PromptExecutor` members — likely because `PromptExecutor` expect/actual class has JVM-specific members not visible in commonMain compilation.

---

## All M2 Tasks Status

| Task | Status | Notes |
|------|--------|-------|
| M2.T1 OpenAiCompatProvider | ✅ DONE | Wire.kt, SseParser.kt, HttpClientFactory.kt, OpenAiCompatProvider.kt |
| M2.T2 MockLlmProvider | ✅ DONE | Builder DSL in openai-compat main sources |
| M2.T3 KoogLlmProvider | 🔴 IN PROGRESS | Blocked on PromptExecutor actual class resolution |
| M2.T4 SubmissionParser | ✅ DONE | Parses authMode → Approval → SystemCommand → UserInput |
| M2.T5 ApprovalGate | ✅ DONE | PendingApprovalsRepo + Channel + ConcurrentHashMap |
| M2.T6 ToolDispatcher | ⬜ PENDING | |
| M2.T7 LoopDetector | ⬜ PENDING | |
| M2.T8 CostGuard | ⬜ PENDING | |
| M2.T9 Compactor | ⬜ PENDING | |
| M2.T10 PreemptivePruner | ⬜ PENDING | |
| M2.T11 ChatDelegate | ⬜ PENDING | |
| M2.T12 JobDelegate | ⬜ PENDING | |
| M2.T13 HebeAgent | ⬜ PENDING | |
| M2.T14 Hooks | ⬜ PENDING | |
| M2.T15 AuthMode | ⬜ PENDING | |

---

## Key Files Modified

- `modules/core/src/main/kotlin/com/hebe/core/llm/KoogLlmProvider.kt` — rewritten twice, currently using stdlib Clock
- `api-doc/koog-api.md` — new documentation file with koog 0.8.0 API findings
- `gradle/libs.versions.toml` — added `koog-utils` and `koog-utils-common` entries
- `modules/core/build.gradle.kts` — added `api(libs.koog.utils)` and `api(libs.koog.utils.common)`

---

## Next Steps

1. **Fix KoogLlmProvider compilation:**
   - The `PromptExecutor` is an expect class — need to find the actual JVM implementation
   - `KoogPromptExecutor` must implement all abstract members from `PromptExecutorAPI`
   - Need to check if `executeStreaming` signature is correct (non-suspend returning `Flow`)

2. **Continue with M2.T6 (ToolDispatcher):** state machine — loopDetector → validators → approval → invoke → leak scan → receipts → memory
3. **Continue with M2.T7 (LoopDetector):** per-turn fingerprint map, sha256(tool.name + args), warn at 3, force at 5