# Review 022 — M9 Final Re-review (tasks-review-021 verification)

**Branch:** v1-development  
**Base commit:** d22de03 (all M9 changes still uncommitted)  
**Scope:** Verification of tasks-review-021.md — 6 items

---

## Verdict: 1 of 6 done, 5 remain open

---

## Completed ✅

### R021-1 — MCP doctor false positive fixed

`runAllChecks()` no longer takes a `McpClientManager` parameter. `checkMcp(config)` now takes `HebeConfig?` and correctly distinguishes three states:

- config null → Warn "Unknown"
- no servers in config → Pass "No MCP servers configured" (accurate)
- servers in config → Pass "MCP servers in config (daemon state unknown)"

The false positive from review-021 is gone. Minor note: returning Pass for "daemon state unknown" is debatable — Warn would be the more honest status since the check could not be completed — but this is not a blocking issue.

---

## Not Done ❌

### R021-2 — `plugin.version` missing from `plugin.start` span

`Lifecycle.kt:114` still reads:
```kotlin
observer.span("plugin.start", mapOf("plugin.id" to pluginId)).use { span -> }
```
`plugin.version` is not included. The manifest is already loaded at this point (`val manifest = (manifestResult as ManifestResult.Ok).value`); the version is available as `manifest.version`. Add it to the attrs map.

### R021-3 — `--non-interactive` flag on `OnboardCommand`

`OnboardCommand` still has only `--force`. No `--non-interactive` flag, no env-var fallbacks. This is the third review cycle this item has been raised.

### R021-4 — Admin password stored as plaintext

`secretStore.set("web.password", adminPassword.toByteArray())` is unchanged. The spec requires SHA-256. This is the third review cycle this item has been raised.

### R021-5 — MCP post-connection disconnect monitoring

`McpClientManager` is unchanged from review-020. No background coroutine monitors established connections for disconnection. This is the third review cycle this item has been raised.

### R021-6 — Fish completion broken for multi-word subcommands

`buildCompletionScript("fish")` is unchanged:
```kotlin
"fish" -> SUBCOMMANDS.joinToString("\n") { "complete -c hebe -f -a '$it'" }
```
`complete -c hebe -f -a 'plugin install'` passes a phrase as a single completion word and is silently ignored by fish for multi-word inputs. This is the third review cycle this item has been raised.

---

## Summary

| Task | Status |
|---|---|
| R021-1 MCP doctor false positive | ✅ |
| R021-2 plugin.version in span | ❌ — 2nd cycle |
| R021-3 --non-interactive onboard | ❌ — 3rd cycle |
| R021-4 SHA-256 password | ❌ — 3rd cycle |
| R021-5 MCP post-connect monitoring | ❌ — 3rd cycle |
| R021-6 Fish completion | ❌ — 3rd cycle |
