# Review 013 — M7: MCP (Model Context Protocol)

**Branch:** `v1-development`
**Commit:** `1e7c919 stage M7 wip`
**Plan reference:** `docs/plan/tasks/M7-mcp.md`

---

## Overview

M7 implements MCP server (exposing hebe tools) and MCP client (consuming external servers).
Six tasks are in scope: T1 SDK baseline, T2 expose hebe tools, T3 transports, T4 MCP client, T5 tool filter groups, T6 credential injection.

The structure is broadly in place — modules exist, config is wired, and the filter logic (T5) has solid unit tests. However the implementation has several critical gaps that prevent the stated "done when" criterion from being met: a stdio MCP server is NOT consumable as hebe tools, and the HTTP transport is not wired into the gateway.

---

## Plan Compliance

| Task | Status per plan | Actual status | Notes |
|---|---|---|---|
| M7.T1 — SDK baseline | pending (target: done) | Partially done | Server creates, hello-world tool registers; smoke test does not verify `tools/list` or `tools/call` |
| M7.T2 — Expose hebe tools | pending (target: done) | Partially done | `ToolBridge.kt` exists; `registerToolsFromRegistry` is correct; but no tests and not wired into stdio server |
| M7.T3 — Transports stdio + SSE/WS | pending (target: done) | Partially done | `hebe mcp serve` boots but has no tools; HTTP transport is not mounted in gateway; no transport tests |
| M7.T4 — MCP client | pending (target: done) | Partially done | `McpClientManager` + `RemoteTool` exist; no reconnect; no T4 tests; runBlocking misuse |
| M7.T5 — Tool filter groups | pending (target: done) | Partially done | Filter logic and tests exist; NOT wired into `ChatDelegate.callLlm` |
| M7.T6 — Credential injection | pending (target: done) | Mostly done | Env denylist uses exact-match (bug); missing-secret error is swallowed by broad catch |

**Overall: M7 is a WIP — not releasable as stated.**

---

## Critical Issues

### 1. `runMcpStdioServer` exposes no hebe tools (M7.T2 + T3)

`McpServer.kt:24` — the `config` parameter is suppressed unused. `runMcpStdioServer()` creates a bare server via `createHebeMcpServer()` and connects to stdio but never calls `registerToolsFromRegistry`. The "done when" condition — *hebe's `file_system` tool is callable from Claude Desktop* — cannot be satisfied with the current code.

### 2. `runBlocking` inside `suspend` functions (M7.T4)

`RemoteTool.kt:43` — `runBlocking { mcpClient.callTool(...) }` inside a `suspend` function blocks the calling coroutine dispatcher thread. Same pattern in `McpClientManager.kt:51` — `val toolsResult = runBlocking { client.listTools() }`. Both should be plain `client.callTool(...)` and `client.listTools()` in suspend context.

### 3. `JsonObject.toMap()` serializes values with JSON quotes (M7.T4)

`RemoteTool.kt:73-76`:
```kotlin
private fun JsonObject.toMap(): Map<String, Any?> =
    this.keys.associateWith { key -> this[key]?.toString() }
```
`JsonPrimitive("hello").toString()` returns `"hello"` (with enclosing quotes), not `hello`. Remote tool arguments will be corrupted. Should use `.jsonPrimitive.content` for primitives.

### 4. `SENSITIVE_ENV_KEYS` uses exact-match denylist (M7.T6)

`McpClientManager.kt:138-146`:
```kotlin
private val SENSITIVE_ENV_KEYS = setOf("API_KEY", "TOKEN", ...)
```
The check `it !in SENSITIVE_ENV_KEYS` is an exact match on the full env var name. `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GITHUB_TOKEN` all pass through unfiltered. Should be a substring/suffix check (e.g. `SENSITIVE_ENV_KEYS.none { key.contains(it) }`).

### 5. HTTP/SSE transport not wired into the gateway (M7.T3)

`KtorTransports.kt` has `Application.installMcpHttpTransport(...)` but it is never called from `Gateway.kt` or `ChannelWiring.kt`. The HTTP/SSE/WS MCP endpoint does not exist at runtime.

### 6. T5 filter not wired into `ChatDelegate.callLlm` (M7.T5)

`McpToolFilter` exists and is tested but `ChatDelegate.kt` has no reference to it. The per-turn dynamic filtering of remote tools before building the LLM tool list — which is the whole point of T5 — is not implemented.

### 7. Missing-secret error swallowed in `connect()` (M7.T6)

`McpClientManager.kt:30-37` catches `RuntimeException`, which includes `IllegalStateException` thrown by `error("Secret not found: $secretName")`. A misconfigured server quietly logs a warning and skips — the plan explicitly requires "fail loudly at boot, not silently launch the server with an empty env var."

---

## Structural / Architectural Issues

### 8. Duplicate stdio bootstrap code

`McpServer.kt:24-66` — `runMcpStdioServer()` and `main()` contain identical stdio transport setup. The `main()` function is dead code; it should be removed.

### 9. `ToolFilter.kt` not created as a separate file (M7.T2)

The task plan calls for `ToolFilter.kt` in `mcp-server`. The filtering logic was absorbed into `ToolBridge.kt` instead. The merge is acceptable architecturally, but it means the per-server filter function is not independently testable.

### 10. `toolSpecToMcpSchema` drops required fields (M7.T2)

`ToolBridge.kt:65-69`:
```kotlin
private fun toolSpecToMcpSchema(schema: JsonObject): ToolSchema =
    ToolSchema(properties = schema, required = emptyList())
```
The `required` array from the original schema is always dropped. Remote MCP clients will not know which arguments are mandatory.

### 11. Hardcoded `WorkspacePath("~/.hebe")` without tilde expansion (M7.T2)

`ToolBridge.kt:128` — tilde is not resolved to the actual home directory. Any workspace-relative operations from the synthetic context will resolve against a literal path `~/.hebe`.

### 12. `McpApprovalGate.awaitApproval` always returns `false`

`ToolBridge.kt:162-168` — tools requiring approval silently fail via the MCP path. At minimum this deserves a log warning; ideally an explicit error response back to the MCP client.

### 13. Inconsistent `McpToolFilter` API (M7.T5)

`McpToolFilter.kt:44-50` — the class exposes both an instance method `applicableTools()` and a companion object `filterToolNames()` that creates a new instance and delegates. The companion method is redundant and creates confusion about the intended API surface.

### 14. `mcpStreamableHttp` registers tools per-connection (M7.T3)

`KtorTransports.kt:27-31` — the `registerToolsFromRegistry()` call is inside the factory lambda passed to `mcpStreamableHttp`. If the SDK calls this lambda per connection, tools are registered multiple times on the same `Server` instance (additive, not idempotent). The SDK contract needs verification; if per-connection, use a dedicated `Server` instance per call.

### 15. Missing lifecycle management for MCP clients (M7.T4)

- No reconnect logic on disconnect (capped retries required per spec).
- MCP client status not reflected in `hebe doctor`.

---

## Test Coverage

| Task | Required tests | Present |
|---|---|---|
| T1 | `tools/list` + `tools/call` programmatic client | No — smoke test only asserts non-null |
| T2 | Low/Medium bridge; High-risk gating; dispatcher in path | None |
| T3 | Stdio client, SSE session, WS session | None |
| T4 | Spawn stdio server, list+call; disconnect → `Err(retriable=true)` | None |
| T5 | always/dynamic filter | Yes — good coverage |
| T6 | Env secrets present; other secrets not leaked | None |

---

## Minor Issues

- `@Suppress("UnusedParameter")` on `config` in `runMcpStdioServer` signals incomplete wiring; when wiring is added, remove the suppression.
- `McpClientManager.disconnect()` is non-suspend but uses `runBlocking` for `client.close()`. Acceptable for teardown, but if called during coroutine scope shutdown, prefer a suspend overload.
- `McpClientManager` stores `McpClientServerConfig` in a mutable map but there is no thread-safety guarantee; `McpClientManager` instances are shared across coroutines.

---

## What Is Working

- Module structure and build files are correct.
- Config data classes (`McpSection`, `McpServerConfig`, `McpClientServerConfig`) are complete.
- `McpToolFilter` logic and all six unit test cases are correct and cover the spec requirements.
- High-risk gating in `ToolBridge.registerToolsFromRegistry` is correct.
- `hebe mcp serve` command exists and boots.
- `McpClientManager.toolsForMessage()` correctly delegates to the filter.
- `RemoteTool` error translation (`isError` → `ToolResult.Err`) is correct.
