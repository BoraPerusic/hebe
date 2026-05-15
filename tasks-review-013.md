# Tasks — Review 013 (M7 MCP)

Checkboxes for the developer to work through. Items are ordered: critical bugs first, then architectural fixes, then tests, then minor.

---

## Critical Bugs

- [ ] **[T2/T3] Wire `ToolRegistry` + `ToolDispatcher` into `runMcpStdioServer()`**
  Register tools from the registry before accepting stdio connections. Remove `@Suppress("UnusedParameter")` from `config`. This is the #1 blocker for the "done when" criterion.

- [ ] **[T4] Replace `runBlocking` with proper suspend calls in `RemoteTool.invoke`**
  `RemoteTool.kt:43` — change `runBlocking { mcpClient.callTool(...) }` to `mcpClient.callTool(...)` (the method is already suspend).

- [ ] **[T4] Replace `runBlocking` with proper suspend calls in `McpClientManager.connectServer`**
  `McpClientManager.kt:51` — change `val toolsResult = runBlocking { client.listTools() }` to `val toolsResult = client.listTools()`.

- [ ] **[T4] Fix `JsonObject.toMap()` to not include JSON quotes in string values**
  `RemoteTool.kt:73-76` — replace `this[key]?.toString()` with a proper unwrap: use `jsonPrimitive.content` for primitives, recurse for objects/arrays.

- [ ] **[T6] Fix `SENSITIVE_ENV_KEYS` to use substring/suffix matching**
  `McpClientManager.kt:138-146` — change the filter from exact-match to `SENSITIVE_ENV_KEYS.any { sensitiveKey -> envVarName.contains(sensitiveKey) }` so that `OPENAI_API_KEY`, `GITHUB_TOKEN`, etc. are actually excluded.

- [ ] **[T3] Mount `installMcpHttpTransport` in the gateway**
  Call `installMcpHttpTransport(server, config, registry, dispatcher, sessionId)` from `Gateway.configureApplication` (or `ChannelWiring`) when `config.mcp.server.httpBind` is non-blank.

- [ ] **[T5] Wire `McpToolFilter` into `ChatDelegate.callLlm`**
  Before building the `tools` list for the LLM call, apply `mcpClientManager.toolsForMessage(serverName, latestUserMessage)` to include only applicable remote tools.

- [ ] **[T6] Make missing-secret fail loudly — don't swallow in `connect()`**
  Move the `error("Secret not found: $secretName")` check out of `connectServer()` (or rethrow as a fatal error) so it is not caught by the broad `RuntimeException` handler. Log at ERROR and rethrow or halt startup.

---

## Architectural Fixes

- [ ] **Remove duplicate `main()` from `McpServer.kt`**
  `McpServer.kt:45-66` is dead code identical to `runMcpStdioServer()`. Delete it.

- [ ] **Preserve `required` fields in `toolSpecToMcpSchema`**
  `ToolBridge.kt:65-69` — extract the `required` array from the schema `JsonObject` and pass it through instead of hardcoding `emptyList()`.

- [ ] **Expand tilde in `syntheticToolContext` workspace path**
  `ToolBridge.kt:128` — replace `WorkspacePath("~/.hebe")` with `WorkspacePath(System.getProperty("user.home") + "/.hebe")` (or use the `dataDir` from the loaded config).

- [ ] **Remove redundant `McpToolFilter.filterToolNames` companion method**
  `McpToolFilter.kt:44-50` — the companion method is a factory wrapper around the instance method. Remove it; callers should construct `McpToolFilter()` directly.

- [ ] **Document `McpApprovalGate` silent-false behavior**
  `ToolBridge.kt:162-168` — add a log.warn when `awaitApproval` is called through MCP so operators know approval-required tools are blocked via this path.

- [ ] **Verify `mcpStreamableHttp` factory lambda lifecycle**
  Check whether the SDK calls the lambda once or per-connection. If per-connection, move `registerToolsFromRegistry()` outside the lambda (call it once during setup) and return the pre-configured `Server` instance from the lambda.

- [ ] **Add thread-safety to `McpClientManager` maps**
  `connectedClients` and `serverConfigs` are `mutableMapOf` without synchronization. Use `ConcurrentHashMap` or wrap access in a `Mutex`.

---

## Missing Tests

- [ ] **[T1] Smoke test: programmatic client calls `tools/list` and `tools/call`**
  `McpServerSmokeTest.kt` — add a test that connects a `Client` to the server via an in-process transport and verifies that `tools/list` returns the hello-world tool and `tools/call` returns `"Hello, World!"`.

- [ ] **[T2] ToolBridge: Low/Medium tools are registered, High tools are gated**
  New test class `ToolBridgeTest.kt` — register tools with different `RiskLevel` values, call `registerToolsFromRegistry`, and assert which tools appear/are absent based on `exposeHighRisk` flag.

- [ ] **[T2] ToolBridge: dispatch call goes through `ToolDispatcher` (receipts written)**
  Test that calling a tool via the bridge invokes `dispatcher.dispatch` and that a receipt is emitted.

- [ ] **[T3] Stdio transport: programmatic client calls `tools/list`**
  `McpServerSmokeTest.kt` or a new test — wire up a real `StdioServerTransport` pair and verify end-to-end tool listing.

- [ ] **[T4] `RemoteTool`: disconnect → `invoke` returns `Err(retriable=true)`**
  `RemoteToolTest.kt` — mock a `Client` that throws on `callTool`, assert `ToolResult.Err(retriable=true)`.

- [ ] **[T6] Credential injection: secrets injected, other secrets not leaked**
  `McpClientManagerTest.kt` — mock a stdio server that echoes its env; assert configured secrets are present and unrelated env vars are absent.

---

## Minor

- [ ] Remove `@Suppress("UnusedParameter")` from `runMcpStdioServer` once the config is used.
- [ ] Consider making `McpClientManager.disconnect()` a suspend function to avoid `runBlocking` in the cleanup path.
- [ ] Add `hebe doctor` output for MCP client connection status (per M7.T4 lifecycle requirement).
- [ ] Implement reconnect with capped retries for MCP client on transport disconnect (per M7.T4 acceptance criteria).
