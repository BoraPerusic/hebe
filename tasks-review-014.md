# Tasks — Review 014 (M7 MCP re-review)

---

## Critical

- [ ] **Register builtin tools into registry in `McpServeCommand`**
  `Main.kt:81-87` — after creating `registry`, register the builtin tools (FileSystem, Shell, etc.) before calling `runMcpStdioServer`. Without this the server starts but advertises zero tools.

- [ ] **Wire `McpToolFilter` into `ChatDelegate.callLlm`**
  Before building the `tools` parameter for the LLM call, iterate connected MCP servers and apply `mcpClientManager.toolsForMessage(serverName, latestUserMessage)` to decide which remote tools to include per turn.

---

## Structural

- [ ] **Remove `runBlocking` from the now-suspend `disconnect()`**
  `McpClientManager.kt:131` — `suspend fun disconnect()` still has `runBlocking { client.close() }`. Replace with `client.close()` directly.

- [ ] **Make `disconnectAll()` consistent with `disconnect()`**
  Either make `disconnectAll()` suspend and call `disconnect()` directly, or document clearly that it is a blocking shutdown hook.

- [ ] **Remove unused `tool` parameter from `bridgeHandler`**
  `ToolBridge.kt:82` — `tool: Tool` is never referenced. Drop it and remove `@Suppress("UnusedParameter")`. The handler already resolves the tool via the dispatcher's registry.

- [ ] **Consolidate triple nullable MCP params in `Gateway.configureApplication`**
  Replace the three nullable parameters `mcpServerConfig`, `registry`, `dispatcher` with a single nullable `McpDeps(config, registry, dispatcher)?` data class to prevent partial-null configurations from compiling.

---

## Tests

- [ ] **`McpClientManagerTest`: assert sensitive keys are absent, not just log it**
  `McpClientManagerTest.kt:34-44` — add an assertion:
  ```kotlin
  val sensitivePatterns = listOf("API_KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "AUTH")
  env.keys.filter { key -> sensitivePatterns.any { key.contains(it) } } shouldBe emptyList()
  ```

- [ ] **`McpClientManagerTest`: use `shouldThrow` instead of bare `try/catch`**
  `McpClientManagerTest.kt:67-74` — replace with `shouldThrow<IllegalStateException> { manager.buildEnvWithSecrets(...) }`.

- [ ] **`RemoteToolTest`: add invoke success, invoke error, and disconnect-error cases**
  - Mock `client.callTool` to return a success result → assert `ToolResult.Ok` with correct text.
  - Mock `client.callTool` to return `isError = true` → assert `ToolResult.Err`.
  - Mock `client.callTool` to throw → assert `ToolResult.Err(retriable = true)`.

- [ ] **`McpServerSmokeTest`: add a programmatic client test calling `tools/list` and `tools/call`**
  Connect a programmatic `Client` to the server (in-process or via piped streams), call `tools/list`, assert the hello_world tool is present, then call `tools/call hello_world {"name":"Test"}` and assert the response.

- [ ] **Add T6 env-leakage assertion in `McpClientManagerTest`**
  Arrange the system env to contain a key like `OPENAI_API_KEY=secret-value`. Call `buildEnvWithSecrets({})`. Assert the result does not contain `OPENAI_API_KEY`.

---

## Minor

- [ ] Collapse redundant `catch (IllegalStateException)` + `catch (RuntimeException)` into a single `catch (RuntimeException)` in `McpClientManager.connect` — `IllegalStateException` extends `RuntimeException` so the first branch is never reached for non-ISE runtime exceptions.
- [ ] Implement T4 lifecycle: reconnect with capped retries on transport disconnect.
- [ ] Reflect MCP client connection status in `hebe doctor` output.
