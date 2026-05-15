# M7.T5 — Tool Filter Groups (Always + Dynamic)

## Goal
Per-server filter so we don't advertise hundreds of remote tools every turn. `Always` group exposes tools unconditionally; `Dynamic` exposes only when the user message contains a keyword.

## Tasks

- [ ] 1. Update `ConfigLoader.parseMcpClientServerFromTable()` to parse:
  - `command`: list from `command` TOML array
  - `envSecrets`: map from `secrets` TOML table
  - `alwaysTools`: list from `always_tools` TOML array
  - `dynamicTools`: list from `dynamic_tools` TOML array
  - `dynamicKeywords`: list from `dynamic_keywords` TOML array

- [ ] 2. Create `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/McpToolFilter.kt`:
  - `McpToolFilter` class with `applicableTools(serverName, allRemoteTools, userMessage): List<String>`
  - Always include tools in `alwaysTools` config
  - For `dynamicTools`, include only if `dynamicKeywords` substring-matches user message (case-insensitive)

- [ ] 3. Wire filter into `McpClientManager.connectServer()`:
  - After listing tools, only register tools that pass the filter

- [ ] 4. Tests in `modules/tools/mcp-client/src/test/kotlin/com/hebe/tools/mcp/McpToolFilterTest.kt`:
  - `always_tools = ["a"], dynamic_tools = ["b"], dynamic_keywords = ["beta"]` and user msg `"hello"` → `["a"]`
  - Same with user msg `"please search beta"` → `["a", "b"]`

## Config TOML example

```toml
[[mcp.client.servers]]
name = "fs"
command = ["npx", "@modelcontextprotocol/server-filesystem", "/Users/bora/data"]
always_tools = ["read_file", "write_file"]
dynamic_tools = ["search_files"]
dynamic_keywords = ["search", "find", "lookup"]
```

## Acceptance criteria
- [ ] Two modes implemented (Always, Dynamic)
- [ ] Per-turn filter applied during tool registration
- [ ] Config parsing complete for all new fields