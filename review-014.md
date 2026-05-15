# Review 014 — M7: MCP (re-review after review-013 fixes)

**Branch:** `v1-development`
**Based on:** uncommitted changes over `1e7c919`
**Previous review:** `review-013.md`

---

## Progress Since review-013

All 7 critical issues from review-013 have been addressed — good progress. The remaining open items are fewer and mostly in the test layer and one remaining wiring gap.

| review-013 issue | Fixed? |
|---|---|
| `runMcpStdioServer` exposes no tools | ✅ Now passes registry + dispatcher |
| `runBlocking` in `RemoteTool.invoke` | ✅ Removed |
| `runBlocking` in `connectServer` | ✅ Removed |
| `JsonObject.toMap()` adds JSON quotes | ✅ Fixed — uses `element.content` for primitives |
| `SENSITIVE_ENV_KEYS` exact-match | ✅ Fixed — substring match now |
| HTTP transport not wired in gateway | ✅ `Gateway.configureApplication` now calls `installMcpHttpTransport` |
| T5 filter not wired into `ChatDelegate` | ❌ Still missing |
| Missing-secret swallowed | ✅ Both catches now rethrow |
| Duplicate `main()` in McpServer | ✅ Removed |
| `required` fields dropped in schema | ✅ Preserved |
| Hardcoded `~/.hebe` workspace path | ✅ Uses `System.getProperty("user.home")` |
| `McpApprovalGate` silent false | ✅ Logs a warn now |
| Redundant companion on `McpToolFilter` | ✅ Removed |
| Thread safety on maps | ✅ `ConcurrentHashMap` |
| `mcpStreamableHttp` re-registers per call | ✅ Tools registered before lambda, lambda returns server |

---

## Remaining Issues

### Critical

**1. `McpServeCommand` boots an empty registry — no hebe tools are exposed**

`Main.kt:81-87`:
```kotlin
val registry = com.hebe.tools.dispatch.ToolRegistry()
val dispatcher = com.hebe.tools.dispatch.ToolDispatcher.default(registry)
```
A fresh empty `ToolRegistry` is created. No builtin tools (`file_system`, `shell`, etc.) are registered before the server starts. The "done when" criterion — *hebe's `file_system` tool is callable from Claude Desktop* — still cannot be met. The wiring of `ToolBridge` → `runMcpStdioServer` is correct; the missing piece is registering the builtin tools into the registry before calling it.

**2. T5 filter still not wired into `ChatDelegate.callLlm`**

`ChatDelegate.kt` has no reference to `McpClientManager` or `McpToolFilter`. The per-turn dynamic tool filtering that is the whole point of T5 has no effect at runtime. `McpClientManager.toolsForMessage()` is callable but is never called.

---

### Structural / Code Quality

**3. `disconnect()` is now `suspend` but still uses `runBlocking { client.close() }`**

`McpClientManager.kt:131` — `runBlocking` inside a suspend function is the same anti-pattern that was fixed in `connectServer`. Since `disconnect` is suspend, just call `client.close()` directly.

**4. `disconnectAll()` is non-suspend and wraps `disconnect()` in `runBlocking`**

`McpClientManager.kt:139-145` — this is an acceptable teardown pattern if called from a lifecycle hook, but it's inconsistent with `disconnect()` being suspend. Either make `disconnectAll()` suspend too, or keep both non-suspend and use `runBlocking` consistently (which at least communicates the blocking intent to callers).

**5. `tool` parameter in `bridgeHandler` is unused and suppressed**

`ToolBridge.kt:81-82`:
```kotlin
@Suppress("UnusedParameter")
private suspend fun bridgeHandler(request, tool: Tool, dispatcher, sessionId)
```
`tool` is never referenced inside the function body — the dispatch goes through the registry by name. Remove the parameter entirely; the `@Suppress` is a smell.

**6. Gateway `configureApplication` uses three nullable parameters that always go together**

`Gateway.kt:77-79`:
```kotlin
mcpServerConfig: McpServerConfig? = null,
registry: ToolRegistry? = null,
dispatcher: ToolDispatcher? = null,
```
These three are always either all-null or all-present. The triple nullability is fragile — it's possible to pass `mcpServerConfig = someConfig` but forget `registry`, and nothing fails at compile time. A single nullable `McpDeps` value class (or a named wrapper) would be safer.

---

### Tests

**7. `McpClientManagerTest` uses `println` in place of assertions**

`McpClientManagerTest.kt:34-44` — the "SENSITIVE_ENV_KEYS" test iterates env keys and prints `PASS:` / `INFO:` messages but never asserts that sensitive keys are absent. A test that passes regardless of what `buildEnvWithSecrets` returns is not a test. The assertion should be:
```kotlin
env.keys.filter { key -> SENSITIVE_PATTERNS.any { key.contains(it) } } shouldBe emptyList()
```

**8. `McpClientManagerTest` missing-secret test uses bare `try/catch`**

`McpClientManagerTest.kt:67-74` — catching `Exception` broadly (including `AssertionError`) and printing "PASS:" is not a proper assertion. Use Kotest's `shouldThrow<IllegalStateException> { ... }`.

**9. `RemoteToolTest` only checks name/description — missing key behaviours**

`RemoteToolTest.kt` covers one case (constructor mapping). Missing:
- `invoke` with a successful remote call returns `ToolResult.Ok` with correct text.
- `invoke` when `mcpClient.callTool` throws returns `ToolResult.Err(retriable=true)`.
- `invoke` when `isError == true` returns `ToolResult.Err`.

**10. T3 transport tests still absent**

Smoke test (`McpServerSmokeTest.kt`) still does not exercise a programmatic client calling `tools/list` or `tools/call`. This was a required acceptance criterion for T1/T3.

**11. T6 env-leakage assertion missing**

`McpClientManagerTest` doesn't assert that `OPENAI_API_KEY` (or any key containing a sensitive substring) is absent from the result of `buildEnvWithSecrets`. The test will pass even if the filtering regresses.

---

### Minor

- `@Suppress("TooGenericExceptionCaught")` on `McpClientManager.connect` — now that the catches rethrow, the broad catch is still doing the right thing (log + rethrow), but it catches `IllegalStateException` *and* `RuntimeException` separately even though `IllegalStateException` extends `RuntimeException`. The `IllegalStateException` branch is redundant; a single `catch (e: RuntimeException)` + rethrow covers both.

- T4 lifecycle (reconnect on disconnect, capped retries, `hebe doctor` status) is still unimplemented. These were explicit acceptance criteria in the plan.

---

## What Is Now Working

- `runMcpStdioServer` is correctly wired: registry → bridge → dispatcher.
- `KtorTransports.installMcpHttpTransport` is called from `Gateway.configureApplication` when MCP is enabled.
- `JsonObject.toMap()` correctly unwraps primitive values.
- Env denylist uses substring matching — `OPENAI_API_KEY` will now be excluded.
- Missing secret in `buildEnvWithSecrets` propagates as an exception.
- `toolSpecToMcpSchema` preserves required fields.
- `ToolBridgeTest` covers Low/Medium registration and High-risk gating (3 cases).
- `McpToolFilter` redundant companion removed; API is clean.
- Maps are thread-safe (`ConcurrentHashMap`).
- `McpApprovalGate` logs a warning when called.
