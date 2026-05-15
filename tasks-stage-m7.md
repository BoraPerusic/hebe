# Stage M7 — MCP (Model Context Protocol)

## Context

M7 implements MCP server (exposing hebe tools) and MCP client (consuming external servers). The MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk` v0.11.0) is already pinned in `libs.versions.toml`.

**Depends on:** M0.T2 (libs.versions.toml), M2.T6 (ToolDispatcher), M5.T3 (gateway), M0.T9 (secrets)
**Blocks:** M10.T4 (MCP integration guide)

**Done when:** a sample stdio MCP server is consumable from a chat turn AND hebe's `file_system` tool is callable from Claude Desktop.

---

## M7.T1 — MCP Kotlin SDK integration baseline

**Status:** pending | **Size:** M | **Deps:** M0.T2 ✅

> Hello-world stdio MCP server using the MCP Kotlin SDK; verify the SDK works.

### Tasks

- [ ] 1. Update `modules/mcp-server/build.gradle.kts` with deps:
  ```kotlin
  implementation(libs.mcp.kotlin.sdk)
  implementation(project(":modules:api"))
  ```
- [ ] 2. Create `modules/mcp-server/src/main/kotlin/com/hebe/mcp/McpServer.kt`:
  - Minimal `McpServer` class wrapping the SDK's `Server`
  - Constructor takes `name`, `version`
  - `start(transport: Transport)` connects server to transport
  - `registerTool(...)` delegates to server's `addTool`
- [ ] 3. Create `modules/mcp-server/src/main/kotlin/com/hebe/mcp/McpToolSpec.kt`:
  - Data class `McpToolSpec(name, description, inputSchema)` matching MCP protocol
- [ ] 4. Create `modules/mcp-server/src/main/kotlin/com/hebe/mcp/HebeMcpServer.kt`:
  - Implements the bridge between hebe's `Tool` interface and MCP's `Server`
  - Uses `ServerOptions` with `ServerCapabilities(tools = ToolsCapability())`
- [ ] 5. Create smoke test in `modules/mcp-server/src/test/kotlin/`:
  - Boot a minimal MCP server with one test tool
  - Connect a programmatic client via stdio transport
  - Call `tools/list` and verify response
  - Call `tools/call` and assert result

### Acceptance

- ✅ SDK works with v0.11.0
- ✅ Hello-world tool callable via stdio
- ✅ Smoke test passes

### Pitfalls

- The MCP Kotlin SDK API surface may differ from the docs — pin exact version and verify at implementation time
- Need `kotlinx-io` for the stdio transport's `asSource()` / `asSink()` extensions

---

## M7.T2 — MCP server: expose hebe tools

**Status:** pending | **Size:** L | **Deps:** M2.T6, M7.T1

> Bridge `ToolRegistry` → MCP server. Every tool whose `risk` is `Low` or `Medium` is advertised. `High` tools are gated by `mcp.server.expose_high_risk` (default `false`).

### Tasks

- [ ] 1. Create `modules/mcp-server/src/main/kotlin/com/hebe/mcp/ToolBridge.kt`:
  - `bridge(registry: ToolRegistry, server: Server, config: McpServerConfig)` iterates tools
  - For each tool: skip if `risk == High` and `!config.exposeHighRisk`
  - Otherwise `server.addTool(name, description, inputSchema, handler)`
- [ ] 2. Create `modules/mcp-server/src/main/kotlin/com/hebe/mcp/ToolFilter.kt`:
  - `ToolFilter` checks `risk` level against `exposeHighRisk` flag
  - `isExposable(tool: Tool, exposeHighRisk: Boolean): Boolean`
- [ ] 3. Create `modules/mcp-server/src/main/kotlin/com/hebe/mcp/McpServerConfig.kt`:
  - `exposeHighRisk: Boolean = false`
  - Config loaded from `config.toml` section `[mcp.server]`
- [ ] 4. Create `bridgeHandler(tool)`:
  - Constructs `ToolContext` for `"mcp:remote-caller"` session id (synthetic)
  - Runs through `ToolDispatcher` (same security policies, receipts, leak detection)
  - Translates `ToolResult.Ok` → `CallToolResult.success`
  - Translates `ToolResult.Err` → `CallToolResult.error`
- [ ] 5. Write tests:
  - [ ] Tool bridge lists all `Low|Medium` tools
  - [ ] `High` tools excluded when `exposeHighRisk = false`
  - [ ] `High` tools included when `exposeHighRisk = true`
  - [ ] Calling via MCP → dispatcher in path → receipts written

### Acceptance

- ✅ Tool bridge works
- ✅ High-risk gating respected
- ✅ Dispatcher still in the path

---

## M7.T3 — MCP transports: stdio + SSE/WS via Ktor

**Status:** pending | **Size:** M | **Deps:** M5.T3 (gateway), M7.T2

> Two transports: stdio (`hebe mcp serve`) and HTTP/SSE/WS via Ktor at `/mcp/sse` and `/mcp/ws`.

### Tasks

- [ ] 1. Create `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/McpCommand.kt`:
  - `hebe mcp serve` subcommand using Clikt
  - Boots minimal `AppComponents` (no channels, no scheduler)
  - Connects MCP server to stdin/stdout via `StdioServerTransport`
- [ ] 2. Create `modules/mcp-server/src/main/kotlin/com/hebe/mcp/StdioTransport.kt`:
  - Wraps SDK's `StdioServerTransport`
  - Provides factory `StdioTransport.forStdinStdout(): Transport`
- [ ] 3. Create `modules/mcp-server/src/main/kotlin/com/hebe/mcp/KtorTransports.kt`:
  - `mcpRoutes(server: Server)` extension on `Route`
  - `sse("/mcp/sse") { server.connect(SseServerTransport(call)) }`
  - `webSocket("/mcp/ws") { server.connect(WebSocketServerTransport(call)) }`
- [ ] 4. Wire HTTP routes into `modules/gateway/` when `config.mcp.server.http_bind != ""`
- [ ] 5. Auth: same Basic auth as the rest of the gateway (HTTP Basic against `web.password`)
- [ ] 6. Write tests:
  - [ ] Stdio: programmatic client over stdio calls `tools/list`
  - [ ] SSE: TestApplication call to `/mcp/sse` with Basic auth establishes session and lists tools
  - [ ] WS: similar WebSocket test

### Acceptance

- ✅ Stdio works for desktop clients
- ✅ SSE + WS routes mounted on gateway
- ✅ Auth applied

---

## M7.T4 — MCP client: consume external MCP servers

**Status:** pending | **Size:** L | **Deps:** M7.T1, M2.T6

> Read `[[mcp.client.servers]]` from config; spawn transport, subscribe to tools, expose in `ToolRegistry` as `mcp_<server>_<tool>`.

### Tasks

- [ ] 1. Update `modules/tools/mcp-client/build.gradle.kts`:
  ```kotlin
  implementation(libs.mcp.kotlin.sdk)
  implementation(project(":modules:api"))
  implementation(project(":modules:tools:dispatch"))
  ```
- [ ] 2. Create `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/McpClientManager.kt`:
  - `connect(serverConfigs: List<ServerConfig>)`
  - For each server: spawn stdio process OR open SSE/WS
  - Run `tools/list` to fetch catalogue
  - For each remote tool → `RemoteTool` adapter → register in `ToolRegistry` as `mcp_<serverName>_<toolName>`
- [ ] 3. Create `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/RemoteTool.kt`:
  - Wraps a remote MCP tool as a hebe `Tool`
  - `invoke(args, ctx)` calls `client.callTool(name, args)`
  - Translates `CallToolResult` → `ToolResult`
  - Risk inference: external tools default to `Medium`
- [ ] 4. Create `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/Transports.kt`:
  - `StdioClientTransport` factory (spawns `ProcessBuilder`)
  - `SseClientTransport` / `WsClientTransport` factories
- [ ] 5. Lifecycle:
  - Graceful shutdown of subprocesses on hebe shutdown
  - Restart on disconnect (capped retries)
  - Status reflected in `hebe doctor`
- [ ] 6. Write tests:
  - [ ] Spawn stdio echo server, list tools, call one
  - [ ] Disconnect underlying transport → `RemoteTool.invoke` returns `Err(retriable=true)`

### Acceptance

- ✅ Stdio transport works
- ✅ Tools exposed under `mcp_<server>_<tool>`
- ✅ Disconnect/reconnect handled

---

## M7.T5 — Tool filter groups (Always + Dynamic + keywords)

**Status:** pending | **Size:** M | **Deps:** M7.T4

> Per-server filter so we don't advertise hundreds of remote tools every turn. `Always` group exposes unconditionally; `Dynamic` exposes only when user message contains keyword.

### Tasks

- [ ] 1. Create `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/McpToolFilter.kt`:
  ```kotlin
  class McpToolFilter(
      alwaysTools: List<String>,
      dynamicTools: List<String>,
      dynamicKeywords: List<String>
  ) {
      fun applicableTools(allRemoteTools: List<String>, userMessage: String): List<String>
  }
  ```
  - Always include `always_tools` matches
  - For `dynamic_tools`, include only if `dynamic_keywords` substring-matches user message (case-insensitive)
- [ ] 2. Config (per server in `config.toml`):
  ```toml
  [[mcp.client.servers]]
  name = "fs"
  always_tools = ["read_file", "write_file"]
  dynamic_tools = ["search_files"]
  dynamic_keywords = ["search", "find", "lookup"]
  ```
- [ ] 3. Wire into `ChatDelegate.callLlm` (M2.T11): before constructing `tools` list, apply filter using latest user message
- [ ] 4. Write tests:
  - [ ] `always_tools = ["a"], dynamic_tools = ["b"], dynamic_keywords = ["beta"]` + user msg `"hello"` → `["a"]`
  - [ ] Same + user msg `"please search beta"` → `["a", "b"]`

### Acceptance

- ✅ Two modes implemented
- ✅ Per-turn filter applied

---

## M7.T6 — Per-server credential injection

**Status:** pending | **Size:** M | **Deps:** M7.T4, M0.T9

> When spawning stdio MCP server, populate its env from declared secrets so server can authenticate.

### Tasks

- [ ] 1. Config (per server):
  ```toml
  [[mcp.client.servers]]
  name = "linear"
  transport = "stdio"
  command = ["npx", "@example/linear-mcp"]
  secrets = { LINEAR_API_KEY = "linear.api_key" }
  ```
- [ ] 2. Update `Transports.kt` spawning logic:
  - Inherit current process env (filtered through denylist like `PluginHost.env` — so other hebe secrets don't leak)
  - Add entries from `secrets` mapping: `key = name in remote env`, `value = SecretStore.get(value)`
- [ ] 3. Logging: redact all secret values (`[REDACTED]`) in startup logs
- [ ] 4. Error on missing secret: misconfigured `secrets` mapping (non-existent secret name) should fail loudly at boot
- [ ] 5. Write tests:
  - [ ] Spawn stdio echo server that prints its env on startup
  - [ ] Assert configured secrets present and other secrets NOT leaked

### Acceptance

- ✅ Secrets injected at the boundary
- ✅ Other env vars not leaked

---

## Implementation Order

```
M7.T1 (SDK baseline) ─────────────────────┐
                                          ↓
M7.T2 (expose hebe tools) ← ─ ─ ─ ─ ─ ─ ─┘
                                          ↓
M7.T3 (transports) ← ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┘ (also needs M5.T3 gateway)
                                          ↓
M7.T4 (MCP client) ← ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┘
                                          ↓
M7.T5 (tool filter groups) ← ─ ─ ─ ─ ─ ─ ─┘
                                          ↓
M7.T6 (credential injection) ← ─ ─ ─ ─ ─ ─┘
```

---

## Open Questions — ANSWERED

1. **SDK version**: v0.12.0 — SDK cloned at `~/Dev/view-only/kotlim-mcp-sdk`. Use graphify to understand API.
2. **Retry limits for MCP client**: Exponential backoff capped at 5 min, retry for 1 hour, then give up.
3. **Streamable HTTP**: Yes, works. Examples in `~/Dev/ai-platform/tools/`. Use `mcpStreamableHttp` pattern.
4. **Tool naming conflict**: hebe wins (remote MCP tool hidden if name collides).
5. **Session ID**: `"mcp:remote-caller"` synthetic session ID — APPROVED.

---

## Dependencies

- `modules/api/` — `Tool`, `ToolContext`, `ToolResult`, `ToolRegistry`, `RiskLevel`
- `modules/tools/dispatch/` — `ToolDispatcher`
- `modules/config/` — `HebeConfig` (secrets store)
- `modules/gateway/` — Ktor routes registration
- `modules/cli-app/` — `hebe mcp serve` subcommand
- `modules/memory/` — for workspace bounds

---

## Notes

- MCP Kotlin SDK v0.12.0 uses `kotlinx-io` for `asSource()` / `asSink()` — add `kotlinx-io` to `libs.versions.toml`
- Stdio transport: use `StdioServerTransport(System.in.asSource().buffered(), System.out.asSink().buffered())`
- Streamable HTTP: use `mcpStreamableHttp` factory lambda pattern (see ai-platform examples)
- Key imports:
  - `io.modelcontextprotocol.kotlin.sdk.server.Server`
  - `io.modelcontextprotocol.kotlin.sdk.server.ServerOptions`
  - `io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport` (stdio)
  - `io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp` (HTTP/SSE)
  - `io.modelcontextprotocol.kotlin.sdk.types.*`
- All MCP server tool calls must route through `ToolDispatcher` — never bypass security
- Credential injection must be fail-fast: missing secret at boot = explicit error, not silent launch