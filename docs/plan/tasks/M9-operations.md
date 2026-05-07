# M9 — Operations

`talos doctor`, `service install`, daemon mode + PID, onboarding wizard, OTel wiring, fat-JAR build via Shadow, shell completion, status command.

**Done when:** a fresh user runs `talos onboard` → `talos service install` → talos comes up under systemd and stays up across a host reboot.

References: [`../v1-architecture.md`](../v1-architecture.md) §17; [`../v1-specs.md`](../v1-specs.md) §2.11.

---

## M9.T1 — `talos doctor`

**Status**: pending  
**Size**: M  
**Depends on**: M5.T10 (channel health), M6.T11 (plugin status), M0.T9 (keychain)  
**Blocks**: M9.T4 (onboarding refers to doctor for self-test)

### Goal

A single command that prints `Pass | Warn | Fail` for every operational concern with a remediation hint per fail.

### Files to create

- `modules/cli-app/src/main/kotlin/com/talos/cli/commands/Doctor.kt` (edit — implement)
- `modules/cli-app/src/main/kotlin/com/talos/cli/doctor/Checks.kt` (new — composable checks)
- Tests

### Detailed work

1. Checks (each returns `CheckResult(name, status, message, hint?)`):
   - **Config**: file exists, parses, all required keys.
   - **LLM endpoint**: GET `${baseUrl}/models` (or a config-defined ping path) returns 200 with valid bearer token.
   - **Channels**: each enabled channel's `healthCheck()`.
   - **Keychain**: can read/write a synthetic test secret to confirm OS keychain integration.
   - **Plugins**: each loaded plugin reports `started`; flag any in `error` state.
   - **Sandbox detect**: presence of `firejail`/`bwrap`/Docker — informational only in v1 (subprocess sandbox is v2).
   - **DB**: ping `SELECT 1` against SQLite; ensure migrations are at head.
   - **Workspace**: writable; required identity files present.
   - **Receipts signing key**: present in secrets store.

2. Output: pretty table by default; `--json` for machine-readable.

   ```
   Check                  Status   Detail
   ───────────────────────────────────────────────────────────────
   config                 PASS
   llm endpoint           PASS     gpt-4o-mini reachable
   channels: cli          PASS
   channels: web          PASS     listening 127.0.0.1:8765
   channels: telegram     WARN     bot disabled (config.channels.telegram.enabled=false)
   keychain               PASS     macOS Keychain
   plugins                PASS     2 loaded
   sandbox                WARN     no firejail/bwrap/Docker; subprocess sandbox unavailable (v2 feature)
   db                     PASS     V6 head
   workspace              PASS
   receipts signing key   PASS
   ```

3. Exit code: 0 if all `Pass`/`Warn`; 1 if any `Fail`.

4. `--verbose` flag: include the last 50 events from the in-memory ring buffer (M0.T7).

### Tests / verification

- Each check unit-testable.
- Aggregate command outputs the table.

### Acceptance criteria

- ✅ Every check produces remediation hint on Fail.
- ✅ JSON output mode.
- ✅ Exit code semantics correct.

### References

- `v1-specs.md` §2.11

---

## M9.T2 — `talos service install / start / stop / uninstall`

**Status**: pending  
**Size**: M  
**Depends on**: M9.T6 (fat JAR; the service launches the jar)  
**Blocks**: M10.T7 (soak test depends on running as a service)

### Goal

Generate and install a system service unit. Three platforms in v1:

- macOS: launchd plist at `~/Library/LaunchAgents/com.talos.agent.plist`.
- Linux: systemd user unit at `~/.config/systemd/user/talos.service`.
- Windows: a small Service Control Manager registration via `sc.exe` (best-effort; document if it ships fully working).

### Files to create

- `modules/cli-app/src/main/kotlin/com/talos/cli/commands/Service.kt` (edit — implement)
- `modules/cli-app/src/main/kotlin/com/talos/cli/service/MacOsLaunchd.kt` (new)
- `modules/cli-app/src/main/kotlin/com/talos/cli/service/LinuxSystemd.kt` (new)
- `modules/cli-app/src/main/kotlin/com/talos/cli/service/WindowsService.kt` (new)
- Tests

### Detailed work

1. Detect platform via `os.name`. Each impl provides `install()`, `start()`, `stop()`, `uninstall()`, `status()`.

2. Linux unit template:

   ```
   [Unit]
   Description=talos agent
   After=network.target

   [Service]
   Type=simple
   ExecStart=/usr/local/bin/talos run
   Environment="HOME=%h"
   Restart=on-failure
   RestartSec=5

   [Install]
   WantedBy=default.target
   ```

   Install with `systemctl --user daemon-reload && systemctl --user enable talos.service`.

3. macOS plist: `<plist><dict><key>Label</key>com.talos.agent</key><key>ProgramArguments</key>...</dict></plist>`. Install with `launchctl load`.

4. Windows: `sc.exe create talos binPath= "..\talos.exe" start= auto`. Document this is best-effort — if it doesn't work cleanly, defer Windows service to v1.1.

5. `talos service status` shows: `installed | running | stopped | not-installed` per platform.

### Tests / verification

- Linux/macOS install in CI (where possible) — integration tests.

### Acceptance criteria

- ✅ Linux + macOS work end-to-end.
- ✅ Windows path documented.
- ✅ Unit + plist templates committed.

### Pitfalls

- macOS launchd: setting `RunAtLoad` and `KeepAlive` is the auto-restart story; don't conflate.
- systemd user units don't survive a logout unless `loginctl enable-linger <user>` — note in the install output.

### References

- `v1-specs.md` §2.11

---

## M9.T3 — Daemon mode + PID file + graceful shutdown

**Status**: pending  
**Size**: S  
**Depends on**: M2.T13  
**Blocks**: M9.T2 (service runs in daemon mode)

### Goal

`talos run` writes a PID file at `~/.talos/talos.pid`; SIGTERM/SIGINT triggers graceful shutdown per `v1-architecture.md` §19.

### Files to create

- `modules/cli-app/src/main/kotlin/com/talos/cli/commands/Run.kt` (edit — implement)
- `modules/cli-app/src/main/kotlin/com/talos/cli/daemon/PidFile.kt` (new)
- `modules/cli-app/src/main/kotlin/com/talos/cli/daemon/Shutdown.kt` (new)
- Tests

### Detailed work

1. `PidFile.acquire(path)`: writes the current PID; uses an exclusive lock (`FileChannel.tryLock`) so a second `talos run` can't double-launch. On already-locked: print "talos already running (pid=X)" and exit 1.

2. `Shutdown.installHook(scope, components)`:
   - Registers signal handlers for `SIGTERM`, `SIGINT`.
   - Drains in flight: stop accepting new messages → wait for in-flight turns to finish (deadline 30 s, then force) → flush observer → close DB.

3. Exit code 0 on clean shutdown.

### Tests / verification

- `talos run` writes PID; second `run` fails.
- `kill -TERM <pid>` triggers graceful drain; in-flight tool call gets cancelled cleanly.

### Acceptance criteria

- ✅ PID file with exclusive lock.
- ✅ SIGTERM/SIGINT handled.
- ✅ Drain deadline respected.

### References

- `v1-architecture.md` §19 (boot/shutdown sequence)

---

## M9.T4 — Onboarding wizard (`talos onboard`)

**Status**: pending  
**Size**: L  
**Depends on**: M0.T8, M0.T9, M5.T8, M9.T1  
**Blocks**: M10.T2 (quickstart guide demonstrates onboarding)

### Goal

Interactive wizard that walks through LLM endpoint + Telegram setup + admin password, generates `~/.talos/config.toml` + populates `secrets.db`, deletes `BOOTSTRAP.md`. After completion, `talos doctor` reports green.

### Files to create

- `modules/cli-app/src/main/kotlin/com/talos/cli/commands/Onboard.kt` (edit — implement)
- `modules/cli-app/src/main/kotlin/com/talos/cli/onboard/Steps.kt` (new — pure functions)
- `modules/cli-app/src/main/kotlin/com/talos/cli/onboard/Prompts.kt` (new — TUI helpers via clikt)
- Tests with scripted input

### Detailed work

1. Steps:
   1. **LLM endpoint**: ask `base_url`. Default suggestion: the user's gateway. Validate with a `GET /models` ping; on failure, ask again.
   2. **API key**: prompt for the key (echo off); store under `llm.api_key` in secrets.
   3. **Default model + embedding model**: query `/models`, present a picker, default to `gpt-4o-mini` and `text-embedding-3-small` if available.
   4. **Admin password (web)**: prompt + confirm; store SHA-256 in `web.password` secret.
   5. **Telegram (optional)**: ask `enable Telegram? [y/N]`. If yes:
      - Prompt for bot token; ping `getMe`; store under `telegram.bot_token`.
      - Ask the user to message the bot from their personal Telegram; auto-detect operator id from the next inbound message (5-minute window) or accept manual input.
   6. **Generate config**: write `~/.talos/config.toml` from `TalosConfig.minimal(...)` populated with the answers.
   7. **Seed workspace**: `WorkspaceSeeder.seedIfMissing(...)`.
   8. **Generate receipts signing key**: `SigningKey.bootstrap(...)`.
   9. **Finalise**: delete `BOOTSTRAP.md`; print "All set. Run `talos doctor` to verify, then `talos run` to start."

2. The wizard is **idempotent**: rerunning skips already-completed steps unless `--force`.

3. `--non-interactive` flag with answers via env vars (for scripted setups in containers).

### Tests / verification

- Scripted-input integration test runs all steps.
- Doctor reports green after onboarding.

### Acceptance criteria

- ✅ Interactive flow.
- ✅ Idempotent.
- ✅ Non-interactive variant.
- ✅ Doctor green after.

### References

- `v1-specs.md` §5 (acceptance criterion 1)

---

## M9.T5 — OTel exporter wiring + spans

**Status**: pending  
**Size**: M  
**Depends on**: M0.T7  
**Blocks**: nothing direct

### Goal

When `OTEL_EXPORTER_OTLP_ENDPOINT` is set, talos exports spans for `dispatch.<tool>`, `memory.search`, `plugin.start`, `channel.reply` (and koog's spans). Otherwise no-op.

### Files to create

- `modules/observability/src/main/kotlin/com/talos/observability/OtelBootstrap.kt` (new)
- Tests

### Detailed work

1. Use the OTel Java SDK (the `opentelemetry-sdk-extension-autoconfigure` module) for env-driven configuration.

2. Spans created via `Observer.span(name, attrs)`:
   - `dispatch.<tool>` — wraps the dispatcher's invoke step. Attrs: `tool.name`, `risk`, `ok`.
   - `memory.search` — wraps the FTS+vec query. Attrs: `query.length`, `k`.
   - `plugin.start` — wraps PF4J start. Attrs: `plugin.id`, `plugin.version`.
   - `channel.reply` — wraps each outbound. Attrs: `channel.name`.

3. Verify: against a local OTel collector (Jaeger UI or equivalent), spans are visible after a chat turn.

### Tests / verification

- Spans emitted via an in-memory exporter (OTel SDK testing utility).
- Endpoint env var unset → no-op exporter.

### Acceptance criteria

- ✅ Env-driven.
- ✅ Four span families.
- ✅ koog's spans exported alongside.

### References

- `v1-architecture.md` §21

---

## M9.T6 — Fat-JAR via Gradle Shadow + `./talos` shell wrapper

**Status**: pending  
**Size**: S  
**Depends on**: M0.T1  
**Blocks**: M9.T2 (service runs the jar)

### Goal

`./gradlew shadowJar` produces a single JAR; a shell wrapper `./talos` runs it.

### Files to create / modify

- `modules/cli-app/build.gradle.kts` (edit — finalise Shadow config)
- `talos` (edit — production wrapper, replacing M0.T11's dev wrapper)
- Tests via CI

### Detailed work

1. `cli-app/build.gradle.kts`:

   ```kotlin
   tasks.shadowJar {
       archiveBaseName.set("talos")
       archiveClassifier.set("")
       archiveVersion.set(project.version.toString())
       manifest {
           attributes["Main-Class"] = "com.talos.cli.MainKt"
       }
       mergeServiceFiles()                  // important for ServiceLoader (e.g. Detekt / SLF4J)
       isZip64 = true                       // dependency count may exceed 65k entries
       // Logback's `Configurator` SPI — make sure it's in mergeServiceFiles
   }
   ```

2. Production wrapper:

   ```bash
   #!/usr/bin/env bash
   set -euo pipefail
   JAR_DIR="${KOKLYP_HOME:-/usr/local/lib/talos}"
   JAR="$JAR_DIR/talos.jar"
   if [ ! -f "$JAR" ]; then
       # dev fallback: run from build output
       JAR="$(dirname "$0")/modules/cli-app/build/libs/talos.jar"
   fi
   exec java -jar "$JAR" "$@"
   ```

3. Document install: `mkdir -p /usr/local/lib/talos && cp build/libs/talos.jar /usr/local/lib/talos/ && cp talos /usr/local/bin/`.

### Tests / verification

- `./gradlew shadowJar` succeeds.
- `java -jar build/libs/talos.jar --help` works.

### Acceptance criteria

- ✅ Single JAR built.
- ✅ Wrapper script installed.
- ✅ Service files merged correctly (Logback works in the JAR).

### Pitfalls

- Some libs use `provider-configuration files` (`META-INF/services/...`); without `mergeServiceFiles`, only the last copy wins and you'll have weird "no implementation" errors.
- Logback's auto-config requires `META-INF/services/ch.qos.logback.classic.spi.Configurator`; verify after Shadow.

### References

- `v1-specs.md` §2.12

---

## M9.T7 — `talos completion bash/zsh/fish`

**Status**: pending  
**Size**: S  
**Depends on**: M0.T11  
**Blocks**: nothing

### Goal

Shell completion for subcommands.

### Files to create / modify

- `modules/cli-app/src/main/kotlin/com/talos/cli/commands/Completion.kt` (edit — implement)
- Tests

### Detailed work

1. Use clikt's built-in `installCompletion()` support (Clikt 5 has this baked in). Wire as:

   ```
   talos completion bash > ~/.local/share/bash-completion/completions/talos
   talos completion zsh > ~/.zfunc/_talos
   talos completion fish > ~/.config/fish/completions/talos.fish
   ```

2. The command writes the script to stdout; users redirect.

### Tests / verification

- Generated script syntactically valid (`bash -n`).

### Acceptance criteria

- ✅ Three shells supported.

### References

- `v1-specs.md` §2.11

---

## M9.T8 — `talos status [--recent]`

**Status**: pending  
**Size**: S  
**Depends on**: M3.T8 (receipts), M5.T10 (channel health)  
**Blocks**: nothing

### Goal

Print recent receipts + last LLM call + channel health in a compact table.

### Files to create / modify

- `modules/cli-app/src/main/kotlin/com/talos/cli/commands/Status.kt` (edit — implement)
- Tests

### Detailed work

1. Default output: same as `/api/status` JSON, formatted as a table.

2. `--recent`: show the last 20 receipts.

3. `--watch`: refresh every 2 s (TUI-light; `clear` + redraw).

### Acceptance criteria

- ✅ Default summary.
- ✅ `--recent` shows receipts.
- ✅ `--watch` mode.

### References

- `v1-specs.md` §5 (acceptance criterion 3)
