# Tasks — Review 015 (M7 MCP re-review)

---

## Critical

- [ ] **Use `PolicyChain.standard()` and real `Receipts` in `McpServeCommand`**
  `ToolDispatcher.default()` runs with no validators, no leak detection, no receipts. Replace:
  ```kotlin
  val validators = PolicyChain.standard(config, workspacePath)
  val receipts = /* real Receipts pointing to ~/.hebe/receipts/ */
  val dispatcher = ToolDispatcher(registry, validators, DenyAllApprovalGate, NoopMemory, NoopObserver, NoopLeakDetector, receipts)
  ```
  At minimum use `PolicyChain.standard(config, workspacePath)` so workspace boundary and command policy are enforced for all MCP-initiated tool calls.

---

## Structural

- [ ] **Move `registerMcpBuiltinTools()` out of `runMcpStdioServer()` into `McpServeCommand.run()`**
  `runMcpStdioServer` should only accept a pre-populated registry. In `McpServeCommand.run()`:
  ```kotlin
  val registry = ToolRegistry()
  val workspacePath = Path.of(hebeConfig.hebe.dataDir.replace("~", System.getProperty("user.home")))
  registerMcpBuiltinTools(registry, workspacePath)   // <-- move here
  val dispatcher = ToolDispatcher.default(registry)
  runMcpStdioServer(hebeConfig, registry, dispatcher) // <-- no longer registers tools
  ```

- [ ] **Remove unused `tool: Tool` from `bridgeHandler`**
  `ToolBridge.kt:82` — drop the parameter and the `@Suppress("UnusedParameter")`. Update the lambda in `registerToolsFromRegistry` to not pass `tool` to the handler.

- [ ] **Move `NoopMemoryStore` (and sibling no-ops) out of `ToolDispatcher`**
  The no-op implementations in `ToolDispatcher.kt:182-265` import memory-module types. Move them to `mcp-server` as a private `McpDispatcherFactory` object, or to a `test-support` module. Keep `ToolDispatcher` free of memory-domain imports.

---

## Tests

- [ ] **Remove `runBlocking` from `RemoteToolTest`**
  `RemoteToolTest.kt:41, 71, 99` — Kotest `StringSpec` lambdas are already `suspend`. Replace `runBlocking { ... }` with direct calls:
  ```kotlin
  "invoke returns Ok" {
      val result = tool.invoke(buildJsonObject { }, ctx)
      // ...
  }
  ```

- [ ] **Make env-leak test deterministic by injecting the environment**
  Refactor `buildEnvWithSecrets` signature:
  ```kotlin
  internal fun buildEnvWithSecrets(
      envSecrets: Map<String, String>,
      systemEnv: Map<String, String> = System.getenv(),
  ): Map<String, String>
  ```
  Then in `McpClientManagerTest`, pass a controlled map that contains known sensitive keys:
  ```kotlin
  val fakeEnv = mapOf("OPENAI_API_KEY" to "sk-secret", "PATH" to "/usr/bin", "HOME" to "/home/user")
  val result = manager.buildEnvWithSecrets(emptyMap(), systemEnv = fakeEnv)
  result.keys shouldNotContain "OPENAI_API_KEY"
  result.keys shouldContain "PATH"
  ```

---

## Deferred (when `RunCommand` is implemented)

- [ ] Wire `McpClientManager.toolsForMessage()` into the `toolsProvider` lambda passed to `HebeAgent` to complete T5 ("per-turn filter applied").
- [ ] Implement reconnect with capped retries on MCP client transport disconnect (T4 lifecycle).
- [ ] Reflect MCP client connection status in `hebe doctor` (T4 lifecycle).
