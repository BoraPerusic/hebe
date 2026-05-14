# Stage M6 — Plugins (PF4J spike → production loader)

## Context

M6 implements the plugin system: PF4J-based plugin loading, manifest parsing, capability gates, signature verification, OCI/ACR distribution, and plugin lifecycle wiring into the tool registry.

**Depends on:** M0.T6 (plugin-api module - ✅ done), M0.T8 (config/toml), M0.T9 (secrets), M2.T6 (ToolDispatcher), M3.T4 (domain matcher)

**Blocks:** M9.T1 (doctor plugin reporting)

---

## M6.T1 — PF4J spike: hello-world plugin loaded from a local JAR

**Status:** pending | **Size:** L | **Deps:** M0.T6 ✅

> Derisking spike. Load a plugin JAR from a local directory and invoke its tool. No manifest validation, no signature, no OCI pull.

### Tasks

- [ ] 1. Update `modules/plugins/build.gradle.kts` with deps: `plugin-api`, `api`, `observability`, `pf4j`
- [ ] 2. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/PluginManager.kt` — minimal PF4J wrapper
- [ ] 3. Create `plugin-template/` directory (NOT in settings.gradle.kts)
- [ ] 4. Create `plugin-template/build.gradle.kts` with kotlin("jvm") + compileOnly deps on `api` and `plugin-api`
- [ ] 5. Create `plugin-template/src/main/kotlin/com/example/HelloPlugin.kt`
- [ ] 6. Create `plugin-template/src/main/kotlin/com/example/SayHelloTool.kt` — `Tool` implementation
- [ ] 7. Create `plugin-template/src/main/resources/plugin.properties`
- [ ] 8. Create `plugin-template/src/main/resources/plugin.toml` (stub: only `hebe_api_version`, `capabilities`, `permissions`, `allowlist_domains`)
- [ ] 9. Create spike test in `modules/plugins/src/test/kotlin/` that:
  - [ ] Builds plugin-template JAR
  - [ ] Loads it via PluginManager
  - [ ] Verifies `say_hello` tool is callable
  - [ ] Verifies classloader isolation (plugin CAN see `com.hebe.api.*`, CANNOT see `com.hebe.core.*`)

### Acceptance

- ✅ Hello plugin JAR built
- ✅ PluginManager.start() loads it
- ✅ PluginManager.tools() returns [SayHelloTool]
- ✅ Tool invocation returns `ToolResult.Ok`
- ✅ Classloader isolation negative test passes

---

## M6.T2 — PluginManagerWrapper (PF4J DefaultPluginManager subclass + classloader rules)

**Status:** pending | **Size:** M | **Deps:** M6.T1

### Tasks

- [ ] 1. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/HebePluginClassLoader.kt` — child-first for plugin's own classes, parent-first for `hebe-api` + `plugin-api`
- [ ] 2. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/HebePluginManager.kt` — extends `DefaultPluginManager`, overrides `createPluginClassLoader`
- [ ] 3. Build a "plugin-api classloader" (URLClassLoader over `hebe-api` and `plugin-api` JARs only)
- [ ] 4. Write isolation tests:
  - [ ] `Class.forName("com.hebe.core.*")` fails inside plugins
  - [ ] `Class.forName("com.hebe.api.Tool")` succeeds
  - [ ] Two plugins with conflicting deps coexist

### Acceptance

- ✅ Classloader rules documented
- ✅ Negative isolation test stays green
- ✅ Cross-plugin dep isolation verified

---

## M6.T3 — plugin.toml parser + manifest model

**Status:** pending | **Size:** M | **Deps:** M0.T8, M6.T2

### Tasks

- [ ] 1. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/manifest/ManifestError.kt` — error with row/col
- [ ] 2. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/manifest/ManifestParser.kt` — uses tomlj, produces `PluginManifest`
- [ ] 3. Parse permissions: `http_client` → `Permission.HttpClient`, `env_read` → `Permission.EnvRead`, `secrets:foo` → `Permission.Secret("foo")`
- [ ] 4. Parse capabilities to `Capability` enum
- [ ] 5. Write tests:
  - [ ] Golden valid manifest parses
  - [ ] Missing `hebe_api_version` → error with line/col
  - [ ] Unknown capability → error

### Acceptance

- ✅ All shapes parse
- ✅ Errors include row/col

---

## M6.T4 — PluginHost impl with capability gates

**Status:** pending | **Size:** L | **Deps:** M6.T3, M3.T4, M0.T9

### Tasks

- [ ] 1. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/host/GatedHttpClientImpl.kt`
- [ ] 2. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/host/HostFactory.kt`
- [ ] 3. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/host/RealPluginHost.kt`
  - [ ] `http()` → throws if no `Permission.HttpClient`; allowlist enforced per request
  - [ ] `env(name)` → returns env var if `Permission.EnvRead` present; redacts sensitive keys
  - [ ] `secret(name)` → returns `SecretHandle(name)` if `Permission.Secret(name)` in manifest
- [ ] 4. Auth handle resolution: host injects `Authorization: Bearer <secretValue>` when plugin uses `SecretHandle` in HTTP request
- [ ] 5. Write tests:
  - [ ] Plugin without `http_client` calling `host.http()` throws
  - [ ] Plugin with `http_client` but URL outside allowlist → exception
  - [ ] Secret injection: HTTP request contains resolved secret; plugin never sees raw value

### Acceptance

- ✅ All three gates enforced
- ✅ Allowlist on every HTTP request
- ✅ Secret values never visible to plugin

---

## M6.T5 — Ed25519 signature verification

**Status:** pending | **Size:** M | **Deps:** M6.T3, M0.T9

### Tasks

- [ ] 1. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/signature/SignatureVerifier.kt`
- [ ] 2. Implement modes:
  - [ ] `disabled` — never check
  - [ ] `optional` — if sig+key present, verify; if missing, warn + load
  - [ ] `required` — refuse without valid sig from trusted publisher
- [ ] 3. Signature payload: SHA-256 of archive (before extraction)
- [ ] 4. Use Bouncy Castle Ed25519 for verification
- [ ] 5. Write tests:
  - [ ] Signed plugin under `required` → loads
  - [ ] Unsigned under `required` → refused
  - [ ] Unsigned under `optional` → loads with warning
  - [ ] Tampered sig → refused

### Acceptance

- ✅ Three modes implemented
- ✅ Default optional
- ✅ All four outcomes tested

---

## M6.T6 — ABI compatibility check

**Status:** pending | **Size:** S | **Deps:** M6.T3

### Tasks

- [ ] 1. Add `AbiVersion.CURRENT = "0.1.0"` to `plugin-api` module
- [ ] 2. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/abi/AbiChecker.kt`
- [ ] 3. Support: exact (`"0.1.0"`), caret (`"0.1.x"`), range (`">=0.1.0 <0.2.0"`)
- [ ] 4. Write tests:
  - [ ] Match cases pass
  - [ ] Mismatch fails with remediation hint

### Acceptance

- ✅ Exact, caret, range supported
- ✅ Mismatch produces actionable error

---

## M6.T7 — Plugin lifecycle wiring into ToolRegistry

**Status:** pending | **Size:** M | **Deps:** M6.T2, M6.T3, M6.T4, M6.T5, M6.T6, M2.T6

### Tasks

- [ ] 1. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/PluginRegistration.kt` — bookkeeping for registered tools
- [ ] 2. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/Lifecycle.kt`
  - [ ] `afterStart(pluginWrapper)`: parse manifest → verify sig → ABI check → build host → init → collect tools → register as `<pluginId>:<toolName>`
  - [ ] `beforeStop(pluginWrapper)`: unregister tools → call `plugin.teardown()`
- [ ] 3. Errors during init/teardown: caught + logged + reported via observer; never crash host
- [ ] 4. Write tests:
  - [ ] Hello-world plugin loaded → `say_hello` dispatchable as `hello:say_hello`
  - [ ] Stop plugin → tool no longer in registry

### Acceptance

- ✅ Tools registered with namespaced ids
- ✅ Unregistration on stop
- ✅ Errors don't crash host

---

## M6.T8 — OCI client (ORAS Java SDK) wrapper

**Status:** pending | **Size:** L | **Deps:** M0.T9, M0.T2

### Tasks

- [ ] 1. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/oci/AzureAuthChain.kt` — DefaultAzureCredential → ACR token exchange
- [ ] 2. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/oci/OciClient.kt`
  - [ ] Parse `ref = registry/repo:tag`
  - [ ] Auth: ACR → AzureAuthChain; else → docker config / ORAS auth file
  - [ ] Pull manifest, verify media type
  - [ ] Pull layer 0 (archive) into cache by sha256
  - [ ] Optional: pull signature layer
  - [ ] Idempotent: short-circuit if cache already has artifact
  - [ ] Returns `PulledArtifact(archivePath, signaturePath?, digest)`
- [ ] 3. Mark integration tests with `@Tag("integration")` (local registry via Docker/Testcontainers)

### Acceptance

- ✅ ACR auth chain works (manual test)
- ✅ Local registry round-trip in CI
- ✅ Idempotent

---

## M6.T9 — `hebe plugin install <oci-ref>` (pull → verify → extract → load)

**Status:** pending | **Size:** M | **Deps:** M6.T7, M6.T8

### Tasks

- [ ] 1. Create `modules/plugins/src/main/kotlin/com/hebe/plugins/install/InstallFlow.kt`
  - [ ] Pull via OciClient
  - [ ] Verify signature via SignatureVerifier
  - [ ] Verify ABI via AbiChecker
  - [ ] Extract archive to `~/.hebe/plugins/<name>-<version>/`
  - [ ] Note: v1 requires restart (not hot-reload)
- [ ] 2. Persist install records in `settings` table
- [ ] 3. Update `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Plugin.kt` with `install` subcommand
- [ ] 4. Write tests:
  - [ ] Local OCI registry → install → restart → tool listable
  - [ ] Bad sig in `required` mode → refused

### Acceptance

- ✅ Full pull-verify-extract-record round-trip
- ✅ Restart-required message clear
- ✅ Persisted install records

---

## M6.T10 — `hebe plugin install <local-path>` (sideload)

**Status:** pending | **Size:** S | **Deps:** M6.T7

### Tasks

- [ ] 1. Detect arg shape: path (`./foo.jar`, `/abs/path/`) vs OCI ref
- [ ] 2. Sideload path: copy/extract → run sig + ABI check
- [ ] 3. Add `--unsigned` flag for local dev (bypasses signature check)
- [ ] 4. Write tests

### Acceptance

- ✅ Local path detection
- ✅ `--unsigned` flag for dev

---

## M6.T11 — `hebe plugin list` / `hebe plugin remove`

**Status:** pending | **Size:** M | **Deps:** M6.T9

### Tasks

- [ ] 1. `hebe plugin list`: table with ID, VERSION, STATUS, CAPABILITIES, PERMISSIONS
  - [ ] Reads PF4J state + `settings.plugins.installed` record
  - [ ] "error" status for plugins that failed to load
- [ ] 2. `hebe plugin remove <name>`:
  - [ ] Stop via HebePluginManager
  - [ ] Delete `~/.hebe/plugins/<name>-<version>/`
  - [ ] Update `settings.plugins.installed`
- [ ] 3. `hebe plugin show <name>` (bonus): full manifest + last error
- [ ] 4. Write tests: install + list + remove round-trip

### Acceptance

- ✅ list output matches table format
- ✅ remove cleans up filesystem

---

## M6.T12 — auto_pull on boot

**Status:** pending | **Size:** S | **Deps:** M6.T9

### Tasks

- [ ] 1. In boot sequence (after Db.open, before PluginManager.start): iterate `config.plugins.auto_pull`
- [ ] 2. If `~/.hebe/plugins/<name>-<version>/` exists, skip; else pull via InstallFlow
- [ ] 3. Failures non-fatal: log + continue; `doctor` reports
- [ ] 4. Write tests

### Acceptance

- ✅ Auto-pull on boot
- ✅ Non-fatal on failure

---

## M6.T13 — plugin-template/ (Gradle template for plugin authors)

**Status:** pending | **Size:** M | **Deps:** M6.T1, M6.T7

### Tasks

- [ ] 1. Create standalone `plugin-template/settings.gradle.kts` (copyable to new repo)
- [ ] 2. Finalize `plugin-template/build.gradle.kts` with sign + publish tasks
- [ ] 3. Create `plugin-template/buildSrc/src/main/kotlin/oras-publish.gradle.kts` — wraps `oras push`
- [ ] 4. Update templates:
  - [ ] `src/main/kotlin/com/example/MyPlugin.kt`
  - [ ] `src/main/kotlin/com/example/MyTool.kt`
  - [ ] `src/test/kotlin/com/example/MyToolTest.kt`
  - [ ] `src/main/resources/plugin.properties` (with placeholders)
  - [ ] `src/main/resources/plugin.toml` (with comments)
- [ ] 5. Create `plugin-template/README.md` — author-facing docs: adding tools, manifest, publish flow, install flow
- [ ] 6. Document signing: `gradle hebeSign` → detached signature; `gradle hebePublish` → sign + push

### Acceptance

- ✅ Template builds standalone
- ✅ Sign + push tasks work
- ✅ README walks through full author flow

---

## Open Questions

1. **Hot reload**: M6.T9 notes "v1 requires restart" — should I confirm this is the intended behavior or attempt hot-reload?
2. **ORAS SDK version**: `oras-java-sdk` v0.6.0 is listed but SDKs change rapidly — should I add a note to verify at implementation time?
3. **acr.auth token exchange**: Architecture §12 notes ACR requires OAuth2 exchange vs using AAD token directly — should I confirm the AzureAuthChain handles this specifically?
4. **M6.T2 classloader**: PF4J's `PluginClassLoader.PARENT_FIRST` flag isn't a clean fit for per-package rules. Should I document that we'll subclass `loadClass` with an allowlist approach?
5. **Plugin template location**: Should `plugin-template/` live inside the hebe repo (current approach) or as a separate standalone repo? The task says "sibling Gradle build" which suggests standalone.

---

## Implementation Order

```
M6.T1 (PF4J spike) ─────────────────────────┐
                                          ↓
M6.T2 (PluginManagerWrapper)               │
                                          ↓
M6.T3 (manifest parser) ← ─ ─ ─ ─ ─ ─ ─ ─┘ (also needs M0.T8)
                                          ↓
M6.T4 (PluginHost impl) ← ─ ─ ─ ─ ─ ─ ─ ─┘ (also needs M3.T4, M0.T9)
                                          ↓
M6.T5 (sig verification) ← M6.T3, M0.T9 ──┘
                                          ↓
M6.T6 (ABI check) ← M6.T3 ────────────────┘
                                          ↓
M6.T7 (lifecycle wiring) ← M6.T2–T6, M2.T6 ┐
                                          ↓
M6.T8 (OCI client) ← M0.T9, M0.T2          │
                                          ↓
M6.T9 (install OCI ref) ← M6.T7, M6.T8    │
                                          ↓
M6.T10 (sideload) ← M6.T7                  │
                                          ↓
M6.T11 (list/remove) ← M6.T9              │
                                          ↓
M6.T12 (auto_pull) ← M6.T9                │
                                          ↓
M6.T13 (plugin-template) ← M6.T1, M6.T7  ─┘
```

---

## Notes

- M6.T1 is the critical path item — finish this spike before proceeding
- M6.T4 (PluginHost) and M6.T7 (lifecycle) are the core integration points — do not parallelize these
- M6.T8 (OCI client) and M6.T9 (install) can be tackled after lifecycle is wired
- Remember: plugin classloaders must NOT see `com.hebe.core.*` — this is the load-bearing constraint of the whole module