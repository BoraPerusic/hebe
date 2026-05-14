# Review 012 — M6 Plugin System

**Branch:** v1-development  
**Date:** 2026-05-14  
**Reviewer:** Claude Code  

---

## Overview

M6 claims to implement the full plugin system: OCI install, sideload, PF4J lifecycle, capability-gated host, ABI checking, signature verification, manifest parsing, CLI commands, and a plugin template. The architecture is partially correct, but several critical components are broken, dead, or missing entirely. The spike-era code path is still the production code path. The plan-specified ABI logic is broken. Install records are not persisted. The CLI commands do not match the plan. Test coverage is nearly zero.

---

## Critical Bugs

### 1. `AbiChecker.isCaretMatch()` is broken

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/abi/AbiChecker.kt`

`isCaretMatch()` is supposed to match versions like `"0.1.x"` (caret notation meaning `>=0.1.0 <0.2.0`). The current implementation:

```kotlin
val prefix = manifestVersion.substringBeforeLast(".")
// prefix for "0.1.x" = "0.1"
return prefix.endsWith(".") && ...
```

`"0.1"` does **not** end with `"."`, so the caret branch always returns `false`. Every plugin using `hebe_api_version = "0.1.x"` (the canonical example in the plan and the plugin template) will be rejected by ABI check with `AbiResult.Incompatible`.

### 2. `Lifecycle.stopPlugin()` is broken

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/Lifecycle.kt`

`stopPlugin()` calls `HebePluginManager.stopPlugin(id)` as if it were a static/companion method. `HebePluginManager` is a class that extends `DefaultPluginManager` — it has no companion object with `stopPlugin`. This will throw `NoSuchMethodException` or fail to compile depending on how it's written. Plugin stop is completely non-functional.

### 3. `HostClassLoader` is dead code — never wired

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/HostClassLoader.kt`

`HebePluginManager` does not override `createPluginClassLoader()`. The default PF4J implementation uses parent-first loading with the full system classpath. `HostClassLoader` exists but is never instantiated. Plugins load with unrestricted access to the host JVM — the entire isolation model is bypassed.

### 4. `PluginManager.tools()` uses `TestPluginHost` in production

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/PluginManager.kt`

The `tools()` method creates a `TestPluginHost` (inner class, no capability checks, no permission gates). This bypasses `RealPluginHost`, `GatedHttpClientImpl`, allowlist enforcement, env denylist, and secret gating entirely. Plugins run with god-mode access in production.

### 5. `InstallFlow` and `SideloadFlow` use `hebeApiVersion` as the plugin name

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/install/InstallFlow.kt`

```kotlin
val name = manifest.hebeApiVersion  // "0.1.x" — the API version, not the plugin ID
```

The plugin directory ends up as `~/.hebe/plugins/0.1.x-0.1.x`. The plugin's actual identity (`plugin.id` from `plugin.properties`) is never read. Plugin listing, removal, and deduplication are all broken.

### 6. Install records not persisted

Plan M6.T9 requires writing `settings.plugins.installed = [...]` after a successful install. Neither `InstallFlow` nor `SideloadFlow` write anything to settings. `PluginRemoveCommand` therefore has nothing to update. The plugin list command reads the filesystem directory instead of the settings store — state diverges if files are modified manually.

---

## Architectural Issues

### 7. `Lifecycle` is not wired to PF4J events

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/Lifecycle.kt`

`afterStart()` and `beforeStop()` are defined but no `PluginStateListener` is registered with `HebePluginManager`. PF4J never calls them. Plugin `init()` (on start) and teardown (on stop) never execute via the correct lifecycle path.

### 8. `NamespacedToolWrapper.invoke()` silently redacts arguments

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/Lifecycle.kt`

`invoke()` filters args through the tool's declared parameters, dropping any key not in `parameters`. This changes the contract for the caller — the tool receives fewer args than passed, silently. This is not part of the plan and breaks tools that receive dynamic or forwarded arguments.

### 9. `OciClient.loadDockerConfigAuth()` uses wrong auth scheme

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/oci/OciClient.kt`

Docker's `config.json` `auth` field is base64(`username:password`). The code decodes it and uses the decoded string `"user:pass"` as a Bearer token. The correct approach is either:
- Use `Basic` auth with the raw base64 value directly, or  
- Split on `:` to extract username/password and use Basic auth header.

Using `"user:pass"` as a Bearer token will be rejected by every real registry (401 Unauthorized).

### 10. `GatedHttpClientImpl` creates a new `HttpClient` per method call

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/host/GatedHttpClientImpl.kt`

Each `get()`, `post()`, `put()`, `delete()` call creates a new Ktor `CIO` client. `HttpClient(CIO)` allocates a thread pool and opens a connection manager. This is extremely expensive and leaks resources — clients are never closed.

### 11. SSRF guard not integrated into `GatedHttpClientImpl`

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/host/GatedHttpClientImpl.kt`

Plan M6.T4 requires SSRF guard (already implemented in M3.T4, module `modules/security`) to be wired into the HTTP gating. Current implementation only checks `allowlistDomains`. Private IP ranges, loopback, link-local, and metadata service addresses are not blocked.

### 12. `ManifestParser` capability parsing breaks on lowercase TOML values

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/manifest/ManifestParser.kt`

```kotlin
Capability.valueOf(item)  // "tool" → IllegalArgumentException; enum value is "Tool"
```

TOML convention uses lowercase. The plan and plugin template use lowercase capability names. `Capability.valueOf()` is case-sensitive. Any plugin manifest with `capabilities = ["tool"]` will fail parsing.

### 13. `PluginRegistrationStore` is not thread-safe

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/PluginRegistration.kt`

Uses `mutableMapOf()` (a `LinkedHashMap`). Concurrent plugin load/unload events from PF4J may run on different threads. Without synchronization, this will corrupt the map under concurrent access.

### 14. `oras-sdk` dependency declared but unused

**File:** `modules/plugins/build.gradle.kts`

`oras-sdk` is listed as a dependency but `OciClient` uses manual Ktor HTTP calls instead of the SDK. The dependency adds weight and creates a false expectation that ORAS SDK semantics are being followed (e.g. content negotiation, descriptor validation).

---

## Missing Features

### 15. `plugin list` output does not match plan

**File:** `modules/cli-app/src/main/kotlin/com/hebe/cli/Main.kt` — `PluginListCommand`

Plan specifies a formatted table: `ID | VERSION | STATUS | CAPABILITIES | PERMISSIONS`. Current implementation lists directory names from `~/.hebe/plugins/`. No STATUS, no CAPABILITIES, no PERMISSIONS, no formatted table.

### 16. `PluginRemoveCommand` does not update settings

`plugin remove <id>` deletes the directory but does not update `settings.plugins.installed`. The settings store still lists the plugin as installed after removal.

### 17. `PluginInstallCommand` uses hardcoded empty `HebeConfig`

```kotlin
val config = HebeConfig()  // all defaults, no real config loaded
```

This means `config.plugins.registry`, `config.security.pluginSignatureMode`, and `config.plugins.publisherKeys` are always empty/default regardless of what the user has configured.

### 18. Plugin template missing publish infrastructure

**Directory:** `plugin-template/`

Plan M6.T13 requires:
- `plugin-template/README.md` — author flow (how to write, build, publish a plugin)
- `plugin-template/buildSrc/oras-publish.gradle.kts` — Gradle task to sign and publish the plugin JAR as an OCI artifact

Neither file exists.

### 19. `plugin-api/TalosPlugin.kt` has wrong filename

**File:** `modules/plugin-api/src/main/kotlin/com/hebe/plugin/api/TalosPlugin.kt`

The file contains the class `HebePlugin`. The filename is `TalosPlugin.kt` — a stale name from an earlier design iteration. Kotlin convention requires the public class name to match the filename for top-level classes.

### 20. `HebePlugin` has undocumented extension points not in the plan

`HebePlugin` defines `channels()`, `memoryStores()`, and `observers()` methods. These do not appear in the M6 plan. They add surface area to the plugin API without a spec, without tests, and without wiring in `PluginManager`.

---

## Test Coverage

### 21. Nearly zero unit test coverage for all new components

Only 2 tests exist in `PluginSpikeTest.kt`. All of the following components have **zero tests**:
- `ManifestParser` (especially error paths, malformed TOML, missing fields)
- `AbiChecker` (exact match, caret match, range match, incompatible versions)
- `SignatureVerifier` (DISABLED mode, OPTIONAL mode, REQUIRED mode, bad signature, unknown key)
- `RealPluginHost` (each capability gate: http, env denylist, secret permission check)
- `GatedHttpClientImpl` (allowlist enforcement, blocked domain, allowed domain)
- `Lifecycle.startPlugin()` / `stopPlugin()` (tool registration, deregistration, namespacing)
- `InstallFlow` (success path, manifest not found, bad signature, ABI incompatible)
- `SideloadFlow` (--unsigned flag, signature check bypass)
- `PluginRegistrationStore` (register, unregister, lookup)

### 22. Spike test checks un-namespaced tool name

**File:** `modules/plugins/src/test/kotlin/com/hebe/plugins/PluginSpikeTest.kt`

Test asserts that the tool is registered as `"say_hello"`. Plan requires namespaced names: `"hello:say_hello"`. The spike test passes only because the wrong `PluginManager.tools()` path (which uses `TestPluginHost` and does not namespace) is in use.

---

## Minor Issues

### 23. `HostClassLoader.findHebrewApiJars()` typo

Method named `findHebrewApiJars()` — should be `findHebeApiJars()`. This is harmless since the method is never called (class is dead code), but it is confusing.

### 24. `InstallFlow` and `SideloadFlow` duplicate all private methods

`extractAndParseManifest()`, `extractVersion()`, `extractArchive()`, and `computeHash()` are copy-pasted identically between the two classes. Any bug fix must be applied in two places.

---

## Summary Table

| # | Severity | Component | Issue |
|---|----------|-----------|-------|
| 1 | Critical | AbiChecker | isCaretMatch always false for "0.1.x" |
| 2 | Critical | Lifecycle | stopPlugin() broken (bad method reference) |
| 3 | Critical | HebePluginManager | HostClassLoader not wired — isolation bypassed |
| 4 | Critical | PluginManager | TestPluginHost used in production path |
| 5 | Critical | InstallFlow | Plugin name = hebeApiVersion, not plugin.id |
| 6 | Critical | InstallFlow | Install records not persisted to settings |
| 7 | High | Lifecycle | PluginStateListener not registered in PF4J |
| 8 | High | Lifecycle | invoke() silently drops args |
| 9 | High | OciClient | Docker auth sent as Bearer instead of Basic |
| 10 | High | GatedHttpClientImpl | New HttpClient per call (resource leak) |
| 11 | High | GatedHttpClientImpl | No SSRF guard |
| 12 | High | ManifestParser | Capability.valueOf() breaks on lowercase TOML |
| 13 | High | PluginRegistrationStore | Not thread-safe (plain HashMap) |
| 14 | Medium | plugins/build.gradle.kts | oras-sdk declared but unused |
| 15 | Medium | CLI | plugin list missing table format |
| 16 | Medium | CLI | plugin remove doesn't update settings |
| 17 | Medium | CLI | PluginInstallCommand uses hardcoded config |
| 18 | Medium | plugin-template | README and publish task missing |
| 19 | Low | plugin-api | TalosPlugin.kt filename mismatch |
| 20 | Low | plugin-api | Undocumented extra extension points |
| 21 | High | All plugin modules | Near-zero test coverage |
| 22 | Medium | PluginSpikeTest | Tests un-namespaced tool name |
| 23 | Low | HostClassLoader | findHebrewApiJars typo |
| 24 | Low | InstallFlow | Duplicate private methods in two classes |
