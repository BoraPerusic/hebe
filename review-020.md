# Review 020 — M9 Operations Re-review (tasks-review-019 verification)

**Branch:** v1-development  
**Base commit:** d22de03 (M9 stage)  
**Scope:** Verification of tasks-review-019.md — 19 items

---

## Verdict: 10 of 19 tasks done, 9 remain open

---

## Completed ✅

| Task | Description | Notes |
|---|---|---|
| R019-1 | Scheduler started in `RunCommand` | `components.scheduler?.start(scope)` added |
| R019-2 | `JobRunner` gets real tool list | `registry.list().map { it.spec }` — correct |
| R019-3 | MCP connect failures non-fatal | `connect()` now catches and logs; continues |
| R019-9 | `MemoryStorePlaceholder` deleted | Gone |
| R019-10 | `AppComponents.kt` deleted | Gone |
| R019-11 | 30-second drain deadline | `withTimeoutOrNull(30_000)` + scope cancel — correct |
| R019-17 | `loadConfigOrDefault()` extracted | Used in `McpServeCommand`, `PluginInstallCommand`, `PluginListCommand` |
| R019-18 | `CoroutineScope` parameter in `Shutdown.installHook` | Added — used for deadline cancel |
| R019-19 | Unused `WorkspaceFs` instantiation removed | Removed from `checkWorkspace()` |
| OTel spans (T5) | `memory.search`, `channel.reply`, `dispatch.<tool>` | Three of four call sites correctly wired |

---

## Not Done / Remaining ❌

### R019-4 and R019-5 — Doctor checks implemented but not connected

`Checks.kt` was created with the correct sandbox probe (`which firejail/bwrap/docker` on Linux) and the correct database check (`SELECT 1` + Flyway query). However, **`DoctorCommand` in `Main.kt` was not updated** to call `Checks.kt`. The current `DoctorCommand` is still the original HTTP gateway checker (lines 434–512). `Checks.kt` is dead code — it compiles but is never invoked.

### R019-6 — `--verbose` flag on `DoctorCommand`

Not present. `DoctorCommand` has no `--verbose` or `--json` options and no `LogbackObserver` reference.

### R019-7 — MCP check with real `McpClientManager`

Not done. `DoctorCommand` does not call `runAllChecks()`, so the MCP check is never reached.

### R019-8 — `hebe service status` command

`ServiceStatusCommand` class is not present and is not registered in `HebeCLI.subcommands()`. `PlatformService.status()` remains dead code at the CLI level.

### R019-12 — `--non-interactive` flag on `OnboardCommand`

Not added. `OnboardCommand` only has `--force`.

### R019-13 — Admin password stored as SHA-256

Still stored as plaintext: `secretStore.set("web.password", adminPassword.toByteArray())`.

### R019-14 — MCP post-connection disconnect monitoring

The `connectServerWithReconnect()` loop handles initial connection retries. There is no background coroutine monitoring an established connection for later disconnection, removing tools on disconnect, or re-registering on reconnect.

### R019-15 — Fish completion broken for multi-word subcommands

Unchanged: `"fish" -> SUBCOMMANDS.joinToString("\n") { "complete -c hebe -f -a '$it'" }` — still generates broken completions for `"plugin install"`, `"service start"`, etc.

### R019-16 — `hebe status` formatted as table

`StatusCommand.printStatus()` still echoes raw JSON.

---

## New Issues Introduced

### `plugin.start` span is not closed

`Lifecycle.kt:112` — `observer.span("plugin.start", mapOf("plugin.id" to pluginId))` creates a span and immediately discards the reference. The span is never closed. The error-path span at line 115 correctly uses `.use {}`. Fix: wrap the success span the same way, or at minimum call `.close()`.

**The spec also requires `plugin.version` as an attribute.** The current call only sets `plugin.id`.

### Three unused imports in `Main.kt`

Lines 36–38 add `kotlinx.serialization.json.Json`, `JsonObject`, and `jsonObject`. None of these are used anywhere in the file — `StatusCommand` and `DoctorCommand` still parse JSON manually. These will cause a detekt `UnusedImports` violation.

---

## Summary of Open Tasks (for tasks-review-020.md)

Nine tasks from review-019 remain open, plus two new issues:

1. Wire `DoctorCommand` to call `runAllChecks()` / `renderCheckTable()` / `renderCheckJson()` / `hasAnyFailure()` from `Checks.kt`, replacing the HTTP gateway checker — this also closes R019-6 and R019-7.
2. Add `ServiceStatusCommand` to `HebeCLI` (R019-8).
3. Add `--non-interactive` flag to `OnboardCommand` (R019-12).
4. Hash admin password with SHA-256 before storing (R019-13).
5. Add post-connection monitoring coroutine to `McpClientManager` (R019-14).
6. Fix fish completion for multi-word subcommands (R019-15).
7. Format `hebe status` output as a table (R019-16).
8. Close `plugin.start` span and add `plugin.version` attribute (new).
9. Remove the three unused serialization imports from `Main.kt` (new).
