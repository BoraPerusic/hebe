# tasks-stage-m9.md

# M9 Operations — Implementation Task List

## Goal
Implement all M9 operations tasks: `hebe doctor`, `hebe service install/start/stop/uninstall`, `hebe run` full agent assembly, daemon mode + PID, graceful shutdown, onboarding wizard, OTel wiring, fat-JAR, shell completion, `hebe status`.

**Done when:** `hebe onboard` → `hebe service install` → hebe comes up under systemd and stays up across a host reboot.

---

## Phase 1 — AgentFactory + RunCommand (T9, T3 related)

### 1.1 [ ] Create `AgentFactory.kt`
- Location: `modules/cli-app/src/main/kotlin/com/hebe/cli/AgentFactory.kt`
- Pure assembly function: `fun build(config: HebeConfig, secretStore: SecretStoreProvider, workspaceRoot: Path, observer: Observer, log: Logger): AgentComponents`
- Returns data class with: `HebeAgent`, `ToolDispatcher`, `ChannelManagerImpl`, `Scheduler?`, `McpClientManager?`, `shutdown: suspend () -> Unit`
- All deps constructed here, wiring as per M9.T9 spec

### 1.2 [ ] Implement `RunCommand.run()` in `Main.kt`
- Replace stub with: PID file acquire → config load → `AgentFactory.build()` → start components → SIGTERM/SIGINT handlers → graceful shutdown
- Uses `PidFile.kt`, `Shutdown.kt` (created in Phase 2)
- `ChannelManagerImpl.start()` launches coroutine, `Gateway.start()` launches server

### 1.3 [ ] Create `daemon/PidFile.kt`
- `object PidFile { fun acquire(path: Path): Result<Unit>; fun release(path: Path) }`
- Exclusive lock via `FileChannel.tryLock`; second launch fails with exit 1

### 1.4 [ ] Create `daemon/Shutdown.kt`
- `fun installHook(scope: CoroutineScope, components: ShutdownComponents)`
- SIGTERM/SIGINT handler; 30s drain deadline; flush observer; close DB

### 1.5 [ ] Update `Main.kt` @file:Suppress
- Add `UnusedProperty`, `LongParameterList`, `NestedBlockDepth` as needed

---

## Phase 2 — hebe doctor (T1)

### 2.1 [ ] Create `doctor/Checks.kt`
- Sealed interface `CheckResult` with `name`, `status` (Pass/Warn/Fail), `message`, `hint?`
- Each check is a `suspend () -> CheckResult`:
  - `ConfigCheck`: file exists, parses
  - `LlmEndpointCheck`: GET /models with bearer token
  - `ChannelsCheck`: each enabled channel healthCheck()
  - `KeychainCheck`: read/write synthetic secret
  - `PluginsCheck`: each loaded plugin started/error state
  - `SandboxCheck`: firejail/bwrap/Docker presence (WARN informational)
  - `DbCheck`: `SELECT 1` + migration head
  - `WorkspaceCheck`: writable + identity files present
  - `ReceiptsSigningKeyCheck`: present in secrets store
  - `McpCheck`: `McpClientManager.connectionStatus()` per server (connected/reconnecting/failed)

### 2.2 [ ] Implement `DoctorCommand.run()` in `Main.kt`
- Run all checks in parallel via `coroutineScope { async { check() } }`
- Pretty table output (20-char name, 6-char status, message)
- `--json` flag → JSON output
- `--verbose` flag → last 50 events from `observer.recentEvents(50)`
- Exit 0 if all Pass/Warn; exit 1 if any Fail

### 2.3 [ ] Add `@file:Suppress` to doctor files
- Add appropriate suppressions to `Checks.kt` and `DoctorCommand`

---

## Phase 3 — Service install (T2)

### 3.1 [ ] Create `service/PlatformService.kt`
- Sealed interface for platform abstraction
- `install()`, `start()`, `stop()`, `uninstall()`, `status()` per platform

### 3.2 [ ] Create `service/MacOsLaunchd.kt`
- Generates `~/Library/LaunchAgents/com.hebe.agent.plist`
- RunAtLoad + KeepAlive for auto-restart
- `launchctl load/unload`

### 3.3 [ ] Create `service/LinuxSystemd.kt`
- Generates `~/.config/systemd/user/hebe.service`
- `systemctl --user daemon-reload && systemctl --user enable hebe.service`
- Note: `loginctl enable-linger <user>` required for survival across logout

### 3.4 [ ] Create `service/WindowsService.kt`
- Best-effort `sc.exe` registration
- Document limitations in code comment

### 3.5 [ ] Update `ServiceInstall/Start/Stop/UninstallCommand` stubs in `Main.kt`
- Wire to `PlatformService` chosen by `os.name`

---

## Phase 4 — Onboarding wizard (T4)

### 4.1 [ ] Create `onboard/Steps.kt`
- Pure functions per step: `stepLlmEndpoint()`, `stepApiKey()`, `stepModelPick()`, `stepAdminPassword()`, `stepTelegram()`, `generateConfig()`, `seedWorkspace()`, `bootstrapSigningKey()`, `finalise()`
- Each returns `StepResult` indicating success/ask-again/retry

### 4.2 [ ] Create `onboard/Prompts.kt`
- Clikt-based TUI helpers: `ask()`, `askSecret()`, `confirm()`, `picker()`

### 4.3 [ ] Implement `OnboardCommand` in `Main.kt`
- `--force` flag to re-run completed steps
- `--non-interactive` flag with env var answers
- Idempotent: skip completed steps unless `--force`
- Delete `BOOTSTRAP.md` on completion

---

## Phase 5 — OTel wiring (T5)

### 5.1 [ ] Create `observability/OtelBootstrap.kt`
- When `OTEL_EXPORTER_OTLP_ENDPOINT` set → initialise OTel SDK
- Otherwise no-op (singleton `noOpOtel`)
- Expose `Otel instance` for span creation

### 5.2 [ ] Wire Observer spans
- `dispatch.<tool>` in `ToolDispatcher`
- `memory.search` in memory retrieval
- `plugin.start` in PF4J plugin host
- `channel.reply` in channel manager
- All via `Observer.span(name, attrs)` existing API

---

## Phase 6 — Fat-JAR + wrapper (T6)

### 6.1 [ ] Update `cli-app/build.gradle.kts`
- Add Shadow plugin
- Configure `shadowJar` task: archiveBaseName="hebe", manifest Main-Class, mergeServiceFiles, isZip64=true

### 6.2 [ ] Create production `hebe` shell wrapper
- Replace M0.T11 dev wrapper with production version
- `JAR_DIR=${KOKLYP_HOME:-/usr/local/lib/hebe}` + dev fallback
- `exec java -jar "$JAR" "$@"`

---

## Phase 7 — Shell completion (T7)

### 7.1 [ ] Implement `CompletionBashCommand`, `CompletionZshCommand`, `CompletionFishCommand` in `Main.kt`
- Use clikt's built-in `installCompletion()` support
- Write to stdout; users redirect to file

### 7.2 [ ] Add `@file:Suppress` as needed

---

## Phase 8 — hebe status (T8)

### 8.1 [ ] Implement `StatusCommand` in `Main.kt`
- Default: table summary (uptime, channel health, LLM status, recent activity)
- `--recent`: last 20 receipts
- `--watch`: refresh every 2s

### 8.2 [ ] Add `@file:Suppress` as needed

---

## Phase 9 — MCP client reconnect + doctor health (T10)

### 9.1 [ ] Create `McpServerStatus.kt` sealed interface
- `Connected`, `Reconnecting(attempt, nextRetryMs)`, `Failed(reason)`

### 9.2 [ ] Update `McpClientManager` with reconnect loop
- After `connectServer()` succeeds, launch background coroutine monitoring disconnection
- Exponential backoff: 5s start, double up to 5min cap, give up after 1h total
- Remove tools on disconnect; re-register on reconnect
- Store status in `ConcurrentHashMap<String, McpServerStatus>`

### 9.3 [ ] Add `connectionStatus(): Map<String, McpServerStatus>` to `McpClientManager`

### 9.4 [ ] Add MCP check to `hebe doctor`

---

## Phase 10 — Final wiring + detekt

### 10.1 [ ] Run detekt on cli-app module
### 10.2 [ ] Fix all suppressions/file-level annotations
### 10.3 [ ] Run all tests (cli-app + integration)
### 10.4 [ ] Verify `hebe run` starts and accepts messages (manual test)