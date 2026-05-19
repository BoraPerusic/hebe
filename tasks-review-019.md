# Tasks — Review 019 (M9 Operations)

## Critical

- [ ] **R019-1** Start the scheduler in `RunCommand`: add `components.scheduler?.start(scope)` after `components.channelManager.start(scope)`.
- [ ] **R019-2** Pass the real tool list to `JobRunner` in `AgentFactory.build()`: replace `tools = emptyList()` with `tools = registry.list().map { it.spec }`.
- [ ] **R019-3** Make MCP connect failures non-fatal: wrap each server's `connectServerWithReconnect` in a try/catch inside `connect()` so a single unreachable server logs a warning but does not abort agent startup.

## M9.T1 — `hebe doctor`

- [ ] **R019-4** Fix `checkSandbox()`: probe `which firejail`, `which bwrap`, `which docker` on Linux and return Warn with a hint if none found; return informational Pass only when at least one sandbox tool is present.
- [ ] **R019-5** Fix `checkDatabase()`: open the SQLite file, run `SELECT 1`, and query the Flyway schema history to confirm migrations are at head. Return Fail if either fails.
- [ ] **R019-6** Fix `--verbose` when OTel is active: pass the raw `LogbackObserver` instance to `DoctorCommand` (or through `OtelBootstrap.createObserver`) so the cast in the verbose branch can always succeed.
- [ ] **R019-7** Fix MCP check: pass a non-null `McpClientManager` constructed from config to `runAllChecks()`, or read a status file written by the running daemon, so the MCP check reflects actual state.

## M9.T2 — Service management

- [ ] **R019-8** Add `ServiceStatusCommand` to `HebeCLI` that calls `platformService(dataDir).status()` and prints `running / stopped / not-installed`.

## M9.T9 — `hebe run`

- [ ] **R019-9** Delete `MemoryStorePlaceholder` inner class from `AgentFactory` — it is no longer used.
- [ ] **R019-10** Delete `AppComponents.kt` — it is dead code since `AgentFactory` took over as the composition root.

## M9.T3 — Graceful shutdown

- [ ] **R019-11** Add a 30-second drain deadline in `Shutdown.installHook`: cancel the `CoroutineScope` or interrupt the thread if `onShutdown()` has not returned within 30 s.

## M9.T4 — Onboarding wizard

- [ ] **R019-12** Add `--non-interactive` flag to `OnboardCommand` with env-var fallbacks (`HEBE_LLM_BASE_URL`, `HEBE_API_KEY`, `HEBE_ADMIN_PASSWORD`, `HEBE_DEFAULT_MODEL`).
- [ ] **R019-13** Store `SHA-256(adminPassword.toByteArray())` in keychain under `web.password` rather than the raw password bytes.

## M9.T10 — MCP post-connect monitoring

- [ ] **R019-14** After a successful `connectServer()`, launch a background coroutine that periodically pings the MCP server (e.g., `listTools()`), detects transport closure, and re-runs `connectServerWithReconnect` on disconnect while removing stale tools from the registry.

## M9.T7 — Shell completion

- [ ] **R019-15** Fix fish completion: multi-word subcommands (`plugin install`, `service start`, etc.) cannot be expressed as a single `complete -a` string. Either split them into flag-based completions or use Clikt 5's `installCompletion()` which generates correct fish output.

## M9.T8 — `hebe status`

- [ ] **R019-16** Format `StatusCommand.printStatus()` output as a table rather than echoing raw JSON.

## Cleanup

- [ ] **R019-17** Extract a shared `loadConfigOrDefault(path): HebeConfig` top-level function and replace the three identical config-load blocks in `McpServeCommand`, `PluginInstallCommand`, and `PluginListCommand`.
- [ ] **R019-18** Add `CoroutineScope` parameter to `Shutdown.installHook` so in-flight coroutines can be cancelled on shutdown — aligns with spec signature and enables the drain deadline (R019-11).
- [ ] **R019-19** Remove unused `WorkspaceFs` instantiation in `checkWorkspace()` in `Checks.kt` (created but only `workspaceRoot.resolve(dir)` is used directly).
