# Review 019 — M9 Operations

**Branch:** v1-development  
**Commit:** d22de03  
**Scope:** M9 — `hebe run`, service install, onboard wizard, OTel, fat-JAR

---

## Overview

M9 is substantially more complete than the commit message implies. `AgentFactory` is a proper composition root: `SqliteMemoryStore`, the full builtin tool set, `McpClientManager`, and `SchedulerFacade` are all constructed. OTel span call sites are wired in all four required locations. `Checks.kt` implements the composable doctor checks. The structural work is solid.

There are however several concrete bugs and one critical omission that prevent the acceptance criteria from being fully met:

---

## Task Compliance

### M9.T1 — `hebe doctor` ✅ Mostly complete; three bugs

`Checks.kt` implements all ten checks in parallel, `DoctorCommand` wires `--json`, `--verbose`, and exit-code semantics correctly.

**Bug 1 — sandbox check is always Pass on Linux/macOS.** `checkSandbox()` returns `Pass` based on `os.name` regardless of whether firejail/bwrap/Docker is actually installed. The spec says "presence of firejail/bwrap/Docker." It should probe with `which firejail`, `which bwrap`, `which docker` and Warn if none found.

**Bug 2 — DB check is file-existence only.** `checkDatabase()` returns Pass if `hebe.db` exists, but the spec requires `SELECT 1` against SQLite and a migration-head check. A corrupt or outdated database will report Pass.

**Bug 3 — `--verbose` silently broken when OTel is active.** `DoctorCommand` does `observer as? LogbackObserver`. When `OTEL_EXPORTER_OTLP_ENDPOINT` is set, `OtelBootstrap.createObserver()` returns an `OtelObserver` wrapping `LogbackObserver`, so the cast returns null and verbose output is silently swallowed. The fix is to always pass the raw `LogbackObserver` to `DoctorCommand` separately, or expose `recentEvents` on the `Observer` interface.

**Deviation — doctor always passes `mcpClientManager = null`.** The MCP check therefore always reports Warn ("MCP client manager not initialized") even when no MCP servers are configured and even when the agent is running. The fix is either to let `doctor` create a read-only `McpClientManager` from config, or to expose a status file that the doctor reads.

### M9.T2 — `hebe service install/start/stop/uninstall` ✅ Mostly complete

macOS launchd and Linux systemd are well implemented. One gap:

**Missing `hebe service status` subcommand.** `PlatformService.status()` is implemented on both platforms and `ServiceStatus` covers all three states, but no `ServiceStatusCommand` is wired into `HebeCLI`. The status method is dead code at the CLI level.

### M9.T9 — `hebe run` full agent assembly ⚠️ One critical omission, two bugs

The composition root is solid: `SqliteMemoryStore` with real `EmbeddingProvider` and `HygieneScanner`, all builtin tools registered via the real `secretLookup`, `McpClientManager` connected, `SchedulerFacade` constructed.

**Critical — scheduler is never started.** `AgentFactory.build()` constructs `schedulerFacade` but `RunCommand` never calls `components.scheduler?.start(scope)`. All of M8 is dead at runtime.

**Bug — `JobRunner.tools = emptyList()`.** Even once the scheduler is started, `JobRunner` receives an empty tool list, so scheduled jobs cannot invoke any tools. It should receive `registry.list().map { it.spec }`.

**Bug — MCP initial failure crashes startup.** In `McpClientManager.connectServerWithReconnect()`, if the initial connection fails after retries are exhausted (or if it throws immediately), a `RuntimeException` is re-thrown from `connect()`. `AgentFactory.build()` wraps `connect()` in `runBlocking`, so a single unreachable MCP server aborts the entire agent startup. The spec says "call `connect()` with a timeout — don't let a slow external server delay startup indefinitely." The server list should be connected with a best-effort approach; failures should log and continue rather than crash.

**Note — `toolsProvider` covers local + MCP via registry.** The per-turn MCP filter from spec §4 is not applied (only `registry::list` is used), but since `connectServer()` already registers remote tools into the shared registry, local and MCP tools are both present. This is a minor deviation from the specified filtering design but works in practice.

**Dead code — `MemoryStorePlaceholder` class.** The class at lines 413–445 is never used now that `SqliteMemoryStore` is wired. It should be deleted.

**Dead code — `AppComponents.kt`.** The class has no references anywhere in `cli-app` since `AgentFactory` took over as the composition root. It compiles but is unreachable and should be removed.

### M9.T3 — Daemon mode + PID file + graceful shutdown ✅ Mostly complete

`PidFile` (exclusive `FileChannel.tryLock`, PID written, `AutoCloseable`) and `Shutdown` (JVM shutdown hook handling SIGTERM/SIGINT) are correct.

**Minor — no 30-second drain deadline.** The spec says "wait for in-flight turns to finish (deadline 30 s, then force)." `Shutdown.installHook` simply calls `onShutdown()` with no timeout. A stuck tool call blocks indefinitely.

### M9.T4 — Onboarding wizard ✅ Mostly complete

`Steps.kt` + `OnboardCommand` cover the happy path including Telegram validation and workspace seeding.

**Missing `--non-interactive` flag.** Acceptance criterion #3 is not met. Container / scripted setups have no way to drive the wizard without TTY.

**Admin password stored as plaintext.** The spec says "store SHA-256 in `web.password` secret." The current code stores the raw bytes. This is both a spec deviation and a minor security concern (the keychain holds the usable password rather than a one-way hash).

### M9.T5 — OTel exporter wiring ✅ Complete

`OtelBootstrap.kt` and `OtelObserver` are correct. All four span families are wired at their call sites:
- `dispatch.<tool>` — `ToolDispatcher.kt:47` with `tool.name`, `risk`, `ok` attributes
- `memory.search` — `Searcher.kt:40` with `query.length`, `k`
- `plugin.start` — `Lifecycle.kt:112` with `plugin.id`, `plugin.version`
- `channel.reply` — `ChannelManagerImpl.kt:165` with `channel.name`

### M9.T6 — Fat-JAR + `./hebe` wrapper ✅ Complete

Shadow plugin config is correct (`mergeServiceFiles`, `isZip64`, manifest main class). Wrapper uses `HEBE_HOME` (an improvement over the spec's `KOKLYP_HOME`).

### M9.T7 — Shell completion ⚠️ Partial

Manual completion scripts are used instead of Clikt 5's built-in `installCompletion()`. The bash script works for single-word subcommands. **Fish completion is broken:** `complete -c hebe -f -a 'plugin install'` passes the string `'plugin install'` as a single argument, which fish will never match against two typed words. Multi-word subcommands need to be split across `complete` calls or described differently.

### M9.T8 — `hebe status` ⚠️ Partial

`--recent` and `--watch` work. **The output is raw JSON** rather than the formatted table the spec calls for.

### M9.T10 — MCP client reconnect ⚠️ Partial

`McpServerStatus` sealed interface exists with `Connected`, `Reconnecting`, `Failed`. The initial-connection retry loop with exponential backoff (5 s → 5 min cap, 1 h total) is implemented and `connectionStatus()` is exposed.

**Gap — no post-connection disconnect monitoring.** The spec says to "launch a background coroutine that monitors for client disconnection." The current reconnect loop only handles initial connection retries. If a server disconnects after being successfully connected, there is no detection or re-registration mechanism.

---

## Architectural Findings

### Config load duplicated three times

`McpServeCommand`, `PluginInstallCommand`, and `PluginListCommand` each contain their own identical config-load block with the same fallback logic. This should be a single top-level `loadConfigOrDefault(path)` function.

### `Shutdown` drops the `scope` parameter

The spec signature is `installHook(scope, components)`. The implementation omits `scope`. Without a scope reference, the shutdown handler cannot cancel in-flight coroutines — it can only call `shutdown()` and hope it returns.

### `ChannelWiring` comment

`ChannelWiring.kt` has a multi-line KDoc comment on the class, which the project style says to avoid. Acceptable for a wiring class that needs usage guidance, but worth flagging.

---

## Summary

| Task | Status | Key gaps |
|---|---|---|
| M9.T1 doctor | ✅ mostly | sandbox always Pass; DB no SELECT 1; `--verbose` breaks w/ OTel; MCP always null |
| M9.T2 service | ✅ mostly | missing `service status` CLI command |
| M9.T3 daemon/PID | ✅ mostly | no drain deadline |
| M9.T4 onboard | ✅ mostly | no `--non-interactive`; password plaintext not SHA-256 |
| M9.T5 OTel | ✅ complete | |
| M9.T6 fat-JAR | ✅ complete | |
| M9.T7 completion | ⚠️ partial | fish broken; not using Clikt built-in |
| M9.T8 status | ⚠️ partial | raw JSON not table |
| M9.T9 hebe run | ⚠️ | scheduler never started; JobRunner no tools; MCP failure crashes startup |
| M9.T10 MCP reconnect | ⚠️ | initial retry done; no post-connect monitoring |
