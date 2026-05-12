# Review 008 — M4 Built-in Tools (re-review)

**Branch**: v1-development
**Date**: 2026-05-12
**Scope**: Re-check of all issues from review-007. Changes are uncommitted (working tree only).
**Previous review**: `review-007.md`

---

## Summary

Several items are genuinely fixed: path traversal (S2), ProcessRunner coroutine blocking (A2), HttpTool headers (O1), GitTool commit paths (O2), WebSearchTool runtime secret lookup (O3), GitPushTool timedOut (O4), FileSystemListTool redundant arg (A3), stub comments (A4), wiki constants (M4), wiki import (M1), kubectl schema (M3). Good work on those.

However, the two most critical bugs (C1, C2) were not fixed — the attempt introduced a **new regression** in C1 that makes `git clone` approval-free when it previously always required approval. The binary detection fix (M2) is logically inert. Test coverage remains critically low, and `WebSearchToolTest` was deleted rather than improved.

---

## Critical bugs — status

### C1 — `GitTool.requiresApproval` still broken; new regression introduced

**Not fixed.** The developer changed the approach from a broken `risk` property to a broken `requiresApproval` override, but kept the same root cause:

```kotlin
// GitTool.kt:58-62
override val requiresApproval: Boolean
    get() = when (verbFromArgs(emptyArgs())) {   // emptyArgs() still returns {}
        "clone" -> true
        else -> super.requiresApproval              // ← super = risk == High = Medium == High = false
    }
```

`verbFromArgs(emptyArgs())` always returns `""`, so `else` always fires, and `super.requiresApproval = (RiskLevel.Medium == RiskLevel.High) = false`. Every git verb — including `clone` — now has `requiresApproval = false`.

**This is worse than before.** In review-007, the broken `risk = High` property at least ensured that all git operations required approval. The "fix" removed that safety net: `clone` now runs without approval at `Supervised` autonomy level.

The root problem is architectural: `requiresApproval` is a property evaluated before `invoke` is called, so per-verb gating cannot be expressed this way. The correct fix is one of:
1. Have `gitClone(args)` return `ToolResult.NeedsApproval(...)` directly (the tool itself asks for consent).
2. Split `GitTool` into per-verb tools so each can have its own static `risk`/`requiresApproval`.
3. Add a dispatcher-level `effectiveRequiresApproval(tool, args)` hook that the tool can override.

---

### C2 — `KubectlTool.requiresApproval` still broken

**Not fixed.** Same `emptyArgs()` pattern:

```kotlin
// KubectlTool.kt:75-76
override val requiresApproval: Boolean
    get() = verbFromArgs(emptyArgs()) in MUTATING_VERBS  // always "" in MUTATING_VERBS = false
```

Result: `kubectl delete pod x` has `requiresApproval = false` — same as before the "fix." The refactor to use `ProcessBuilder` directly (good for S1) was done correctly, but the approval gate is inoperative.

The solution for this tool is simpler: since `KubectlTool` already uses `ProcessBuilder` with an args list (no injection risk), it can call `invoke` and check the verb there, returning `ToolResult.NeedsApproval` for mutating verbs if not already approved via `ctx.approvalGate`.

---

## Security issues — status

### S1 — Shell injection in `GitPushTool`: **partially fixed**

`isValidGitIdentifier` blocks most injection vectors:
```kotlin
private fun isValidGitIdentifier(value: String): Boolean {
    return value.matches(Regex("^[a-zA-Z0-9._-]+$"))
}
```

The regex is effective against metacharacter injection. However, it rejects valid branch names containing `/` (e.g., `feature/my-branch`, `release/v1.0`). This is a **usability regression**: the tool claims to push branches but silently rejects the most common naming convention.

The root cause is still present — `ProcessRunner.run("git push $branchArg", ...)` uses bash interpolation. Using `ProcessBuilder` directly (as was done for `KubectlTool`) would remove the injection surface entirely and allow `/` in names.

### S2 — Path traversal: **fixed**

`ShellTool`, `GitTool.resolveRepoDir`, `GitPushTool`, and `WorkspaceFs` now all use `normalize()` + `toRealPath()`. Fixed.

---

## Correctness issues — status

| Issue | Status |
|---|---|
| O1 — HttpTool empty response headers | **Fixed** — headers now serialized from actual response |
| O2 — GitTool commit ignores `paths` arg | **Fixed** — reads `args["paths"]` for selective staging |
| O3 — WebSearchTool bypasses secrets store | **Fixed** — `ctx.secretLookup.secret("brave_api_key")` at invoke-time |
| O4 — GitPushTool missing timedOut check | **Fixed** — `timedOut` checked before exitCode |

---

## Architectural issues — status

| Issue | Status |
|---|---|
| A1 — Multiple independent HttpClient instances | **Not fixed** — `HttpTool`, `BraveSearchProvider`, `DuckDuckGoSearchProvider` each create their own client; no shared factory; clients never closed |
| A2 — ProcessRunner blocks coroutine thread | **Fixed** — now wraps in `withContext(Dispatchers.IO)` and uses `process.waitFor(timeout, MILLISECONDS)` |
| A3 — FileSystemListTool redundant `workspaceRoot` arg | **Fixed** — constructor now takes only `WorkspaceFs`; uses new `fs.stat(wp)` method |
| A4 — Stubs missing comments | **Fixed** — `ScheduleTool` and `JobTools` now have `// stub: pending M8.T1/M8.T2` comments |

**New issue introduced by A2 fix**: `EmergencyStop` is now only checked after `process.waitFor()` returns. If the process is still running when a stop is requested, it won't be killed until the timeout elapses. The old polling loop checked every 100ms. This is a minor regression in shutdown responsiveness for long-running commands.

---

## Minor issues — status

| Issue | Status |
|---|---|
| M1 — WikiReadTool wrong WorkspacePath import | **Fixed** — now `com.hebe.api.workspace.WorkspacePath` |
| M2 — Binary file detection in FileSystemReadTool | **Not fixed** — see below |
| M3 — KubectlTool schema missing `required` | **Fixed** — `"required": ["verb"]` added |
| M4 — WIKI_PREFIX as instance val | **Fixed** — moved to `companion object` in both wiki tools |

**M2 detail** — the fix is logically inert:

```kotlin
val isBinary = try {
    content.toByteArray()   // never throws — always returns a byte array
    false
} catch (_: Exception) {
    true   // unreachable
}
```

`String.toByteArray()` never throws an exception; it always succeeds regardless of content. `isBinary` is always `false`, so the `Err("binary file: …")` branch is unreachable. The detection doesn't work.

---

## Test coverage — status

**Significantly worse.** `WebSearchToolTest` was **deleted** rather than improved. There are now 3 test files, down from 4.

| Task | Status |
|---|---|
| T1 — Traversal attack test in FileSystemReadToolTest | **Fixed** — test added |
| T2–T5 — FileSystemList/Glob/Write/Append tests | **Not added** |
| T6–T9 — Memory tool tests | **Not added** |
| T10–T11 — Wiki tool tests | **Not added** |
| T12–T13 — Git/GitPush tests | **Not added** |
| T14 — KubectlTool approval test | **Not added** |
| T15 — AskUser test | **Not added** |
| T16 — ScheduleTool test | **Not added** |
| T17 — JobTools test | **Not added** |
| WebSearchToolTest | **Deleted** (regression) |

---

## Items fully resolved since review-007

To give credit where it is due — these are done and need not be tracked further:

- S2 path traversal (all three locations + WorkspaceFs)
- A2 ProcessRunner coroutine blocking
- A3 FileSystemListTool redundant arg
- A4 stub comments
- O1 HttpTool response headers
- O2 GitTool commit paths
- O3 WebSearchTool runtime secret lookup
- O4 GitPushTool timedOut check
- M1 WikiReadTool import
- M3 KubectlTool required schema field
- M4 wiki WIKI_PREFIX constant
- T1 traversal attack test
