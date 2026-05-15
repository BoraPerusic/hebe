# Review 015 — M7: MCP (re-review after review-014 fixes)

**Branch:** `v1-development`
**Based on:** uncommitted changes over `1e7c919`
**Previous review:** `review-014.md`

---

## Progress Since review-014

Both critical items from review-014 have been addressed in spirit:

| review-014 issue | Fixed? |
|---|---|
| `McpServeCommand` boots empty registry (no tools) | ✅ `registerMcpBuiltinTools()` called inside `runMcpStdioServer()` |
| T5 filter not wired into `ChatDelegate` | ✅ `toolsProvider` abstraction implemented; wiring scaffolding in place |
| `disconnect()` still used `runBlocking` | ✅ Removed — calls `client.close()` directly |
| `disconnectAll()` inconsistency | ✅ Now `suspend` |
| Redundant `IllegalStateException` catch branch | ✅ Merged into single `catch (RuntimeException)` |
| `RemoteToolTest` incomplete | ✅ 4 test cases covering success, isError, throw, name mapping |
| `McpClientManagerTest` assertion quality | ✅ Uses `shouldThrow<IllegalStateException>`, proper `shouldBe` assertions |
| `bridgeHandler` unused `tool` parameter | ❌ Still present with `@Suppress` |

Additionally: `ToolDispatcher.default()` factory with no-op implementations is a clean solution.

---

## Remaining Issues

### Critical

**1. MCP stdio server runs with no security policies, no receipts, no leak detection**

`McpServeCommand` uses `ToolDispatcher.default(registry)`, which sets:
- `validators = emptyList()` — no workspace boundary, no command policy, no SSRF guard
- `leakDetector = NoopLeakDetector`
- `receipts = NoopReceipts`
- `memory = NoopMemoryStore`

M7.T2 acceptance criteria state explicitly: *"Dispatcher still in the path — same security policies, receipts, leak detection. Don't bypass."* The dispatcher IS in the path, but its safety net is empty. An MCP client calling `file_system_write` can write anywhere on disk with no policy enforcement and no audit trail.

The fix is to use `PolicyChain.standard(config, workspaceRoot)` as the validators argument — the same chain the full agent uses — and use the real `Receipts` implementation. Full `MemoryStore` is not required for the stdio server.

### Structural

**2. `runMcpStdioServer` mutates the registry it receives**

`McpServer.kt:52` — `runMcpStdioServer(config, registry, dispatcher)` calls `registerMcpBuiltinTools(registry, workspacePath)` internally. The caller (`McpServeCommand`) passes an empty registry, trusting the server function to populate it — this is an inside-out responsibility.

The function signature implies the registry is already configured; callers shouldn't have to know the server will populate it. Move `registerMcpBuiltinTools(registry, workspacePath)` into `McpServeCommand.run()` before the `runMcpStdioServer(...)` call, and have `runMcpStdioServer` only accept a pre-populated registry.

**3. `bridgeHandler` still carries an unused `tool` parameter**

`ToolBridge.kt:81-87` — `@Suppress("UnusedParameter")` + `tool: Tool` is still present. The tool is resolved by name through the dispatcher; the captured `tool` object is never used in the function body. Remove the parameter and the suppress.

**4. `ToolDispatcher` contains `NoopMemoryStore` with full memory API knowledge**

`ToolDispatcher.kt:182-214` — the `NoopMemoryStore` nested inside `ToolDispatcher` imports and implements `MemoryCategory`, `MemoryHit`, `MemoryScope`, `MemorySnapshot`. This pulls memory-module concepts into the dispatch module. A lightweight no-op like this belongs in a test helper or a dedicated bootstrap utility (e.g., `McpBootstrap` in `mcp-server`), not inside the production dispatcher class.

### T5 Wiring Gap

**5. `toolsProvider` abstraction is in place but not connected to `McpClientManager` anywhere**

`ChatDelegate` correctly calls `toolsProvider(reasoning.latestUserMessage)` at line 115. `HebeAgent` passes `toolsProvider` through. But no production call site provides an MCP-filtered implementation — the test usages all pass `{ emptyList() }` or `{ listOf(echoTool.spec) }`.

This is acceptable while `RunCommand` remains unimplemented (there is no running agent to wire). However the T5 acceptance criterion ("per-turn filter applied") cannot be verified until `RunCommand` wires `McpClientManager.toolsForMessage()` into the `toolsProvider` lambda. This should be noted as a deferred T5 completion item for when `RunCommand` is implemented.

### Tests

**6. `RemoteToolTest` uses `runBlocking` inside Kotest `StringSpec`**

`RemoteToolTest.kt:41, 71, 99` — Kotest `StringSpec` test lambdas are `suspend` by default; `runBlocking { ... }` is unnecessary and can interfere with coroutine test dispatchers. Replace with direct `suspend` calls.

**7. `McpClientManagerTest` env-leak assertions are environment-dependent**

`McpClientManagerTest.kt:36-37, 83` — both leak tests call `buildEnvWithSecrets` which reads `System.getenv()` directly. In a CI environment with no `OPENAI_API_KEY` or `GITHUB_TOKEN` set, the assertions pass trivially without proving the filter works. The test cannot distinguish between "filter is correct" and "environment happened to not have those keys."

Fix: refactor `buildEnvWithSecrets` to accept the environment as a parameter (`env: Map<String, String> = System.getenv()`) so tests can inject a controlled env with known sensitive keys.

---

## Plan Compliance Summary

| Task | Acceptance criteria met? |
|---|---|
| T1 — SDK baseline | ✅ SDK works, server creates, hello-world tool callable |
| T2 — Expose hebe tools | ✅ Bridge + gating work; ⚠️ security policies not active via `default()` |
| T3 — Transports | ✅ Stdio wired, HTTP mounted in gateway; no transport integration tests |
| T4 — MCP client | ✅ Connect, list, call, error handling; ⚠️ no reconnect/retry |
| T5 — Tool filter groups | ✅ Filter logic tested; ⚠️ not wired to any live agent path |
| T6 — Credential injection | ✅ Secrets injected, fail-loud on missing |

**"Done when"**: `hebe mcp serve` now starts a server that exposes filesystem tools to Claude Desktop — the primary deliverable is met. The `file_system_read`, `file_system_write`, `file_system_list`, `file_system_glob`, `file_system_append` tools are registered and accessible. The security concern (item 1) should be fixed before treating M7 as production-ready.
