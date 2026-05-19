# Tasks — Review 021 (M9 re-review)

## Bug introduced in this round

- [ ] **R021-1** Fix MCP doctor false positive: `buildMcpClientManagerForDoctor()` returns a fresh manager with no connections, causing `checkMcp()` to report Pass "No MCP servers configured" even when servers are in config. Fix by either (a) returning `null` from `buildMcpClientManagerForDoctor` (honest: doctor can't know daemon state) and updating `checkMcp()` to distinguish "no servers in config" from "unknown — is daemon running?", or (b) calling `connect()` with a short timeout inside `buildMcpClientManagerForDoctor` to get real status.

## Carried forward from review-020

- [ ] **R021-2** Add `plugin.version` to `plugin.start` span attrs in `Lifecycle.kt`. The version is available via `pluginWrapper`'s PF4J plugin descriptor.
- [ ] **R021-3** Add `--non-interactive` flag to `OnboardCommand`; read answers from env vars (`HEBE_LLM_BASE_URL`, `HEBE_API_KEY`, `HEBE_ADMIN_PASSWORD`, `HEBE_DEFAULT_MODEL`, `HEBE_TELEGRAM_TOKEN`, `HEBE_OPERATOR_ID`).
- [ ] **R021-4** Hash admin password before storing: `secretStore.set("web.password", MessageDigest.getInstance("SHA-256").digest(adminPassword.toByteArray()))`.
- [ ] **R021-5** Add post-connection monitoring to `McpClientManager`: after `connectServer()` succeeds, launch a `CoroutineScope` background job that pings via `listTools()`, detects transport closure, removes stale tools from registry on disconnect, and calls `connectServerWithReconnect()` to restore the connection.
- [ ] **R021-6** Fix fish completion for multi-word subcommands. Fish's `-a` does not accept phrases. Either use Clikt 5's `installCompletion()`, or generate completions that chain `__fish_seen_subcommand_from` conditions (e.g., `complete -c hebe -n "not __fish_seen_subcommand_from plugin" -f -a plugin` and `complete -c hebe -n "__fish_seen_subcommand_from plugin" -f -a "install list remove"`).
