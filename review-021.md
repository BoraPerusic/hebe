# Review 021 — M9 Re-review (tasks-review-020 verification)

**Branch:** v1-development  
**Base commit:** d22de03 (M9 stage, all changes still uncommitted)  
**Scope:** Verification of tasks-review-020.md — 10 items

---

## Verdict: 5 of 10 done, 3 not done, 2 partial (one introduces a new bug)

---

## Completed ✅

| Task | Description | Notes |
|---|---|---|
| R020-1 | Wire `DoctorCommand` to `Checks.kt` | `runAllChecks` called; `--json`, `--verbose`, exit code all correct. `--verbose` reads `logbackObserver` directly, not through `OtelObserver` cast — correct fix. |
| R020-3 | `ServiceStatusCommand` added | Wired in `HebeCLI.subcommands()` at line 81; implementation is clean. |
| R020-8 | `hebe status` formatted as table | `printStatusTable()` uses `Json.parseToJsonElement()` and formats as aligned columns. |
| R020-9 (partial) | `plugin.start` span closed | `.use { span -> }` ensures the span ends. |
| R020-10 | Unused serialization imports removed | `Json`, `JsonObject`, `jsonObject` are now used by `printStatusTable()`. |

---

## Partial ⚠️

### R020-2 — MCP check introduces a false positive

`buildMcpClientManagerForDoctor()` creates a fresh `McpClientManager` and returns it, but **never calls `connect()`**. A fresh manager has an empty `serverStatuses` map, so `connectionStatus()` returns `{}`. `checkMcp()` then hits this branch:

```kotlin
if (statuses.isEmpty()) {
    return CheckResult("MCP", CheckStatus.Pass, "No MCP servers configured")
}
```

Result: when MCP servers **are** present in config, `hebe doctor` reports **Pass "No MCP servers configured"** — a false positive that actively misleads the operator.

Fix: either call `connect()` inside `buildMcpClientManagerForDoctor` (with a short timeout so doctor stays fast), or return `null` from it and let `checkMcp` report Warn "daemon not running, status unknown" — which is the honest answer since doctor runs in a separate process.

### R020-9 (continued) — `plugin.version` attribute still missing

The `plugin.start` span is now closed correctly, but the spec requires `plugin.id` **and** `plugin.version` attributes. The current call is:

```kotlin
observer.span("plugin.start", mapOf("plugin.id" to pluginId)).use { span -> }
```

`plugin.version` is not included. The version is available from `pluginWrapper` (via `pf4j` plugin descriptor). Add it to the attrs map.

---

## Not Done ❌

### R020-4 — `--non-interactive` flag on `OnboardCommand`

Not added. `OnboardCommand` still has only `--force`. No env-var fallbacks.

### R020-5 — Admin password stored as SHA-256

Still plaintext: `secretStore.set("web.password", adminPassword.toByteArray())`. The spec says to store `SHA-256(adminPassword)`.

### R020-6 — MCP post-connection disconnect monitoring

`McpClientManager` is unchanged from the last review. `connectServerWithReconnect` handles initial connection retries only. There is no background coroutine that monitors a live connection for disconnection, removes stale tools from the registry on disconnect, or calls `connectServerWithReconnect` again after a drop.

### R020-7 — Fish completion still broken

`buildCompletionScript("fish")` still generates:

```fish
complete -c hebe -f -a 'plugin install'
complete -c hebe -f -a 'service start'
```

Fish's `-a` option takes a completion **word**, not a phrase. `'plugin install'` will never match because fish sees each token independently. These completions are silently no-ops for all multi-word subcommands.

---

## Summary

| Task | Status |
|---|---|
| R020-1 DoctorCommand wired | ✅ |
| R020-2 MCP doctor check | ⚠️ false positive introduced |
| R020-3 ServiceStatusCommand | ✅ |
| R020-4 --non-interactive | ❌ |
| R020-5 SHA-256 password | ❌ |
| R020-6 MCP post-connect monitoring | ❌ |
| R020-7 Fish completion | ❌ |
| R020-8 Status table | ✅ |
| R020-9 plugin.start span | ⚠️ closed but version missing |
| R020-10 Unused imports | ✅ |
