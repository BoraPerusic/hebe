# Review 007 — M4 Built-in Tools

**Branch**: v1-development
**Date**: 2026-05-12
**Scope**: M4 built-in tools (`modules/tools/builtin`) — all 22 tool files, 4 test files, `build.gradle.kts`, and AppComponents wiring.
**Plan reference**: `docs/plan/tasks/M4-builtin-tools.md`, `tasks-stage-m4.md`

---

## Summary

M4 delivers a solid skeleton: all tool groups are present, the package/naming conventions are correct, hygiene is consistently wired to writes, and the `Tool` interface contract (`risk`, `readOnly`, `NeedsApproval`) is generally respected. The deferred items (M4.T9 GitHub, AppComponents wiring → M9.T2) are consistent with the plan.

Two architectural bugs would cause silent security regressions in production, and a shell-injection class of vulnerability appears in two tools. Test coverage is the most under-delivered area — only 4 files cover 22+ tools, and several spec-required test cases are absent.

---

## Deferred items (explicitly noted, acceptable)

- **M4.T9 GitHub tool** — not implemented. The plan marks it as cuttable; the absence is documented in the task file.
- **AppComponents tool registration** — no tools are wired into `ToolRegistry` in `AppComponents`. The plan defers this to M9.T2. This means `hebe tool list` returns nothing and acceptance criteria "tools registered" can't be verified yet.

---

## Critical bugs

### C1 — `GitTool.risk` always returns `High` (wrong for all verbs)

`GitTool.kt:56–65`

```kotlin
override val risk: RiskLevel
    get() = when (verbFromArgs(emptyArgs())) {   // emptyArgs() always returns {}
        "clone" -> RiskLevel.High
        "status", "diff", "log", "branch" -> RiskLevel.Low
        "commit", "branch_create" -> RiskLevel.Medium
        else -> RiskLevel.High   // ← always lands here
    }
```

`verbFromArgs(emptyArgs())` always receives an empty `JsonObject` and always returns `""`, so `risk` is always `High`, and `requiresApproval` is always `true`. This means `git status` and `git log` will demand approval at `Supervised` level — a silent UX regression. The spec requires per-verb risk.

**Fix**: `risk` must be determined at `invoke`-time, not as a property. One approach is to expose a per-verb `effectiveRisk(args)` helper and override `requiresApproval` accordingly, or set a default tool-level risk (Medium) and let the dispatcher or `invoke` itself gate clone separately via `NeedsApproval`.

---

### C2 — `KubectlTool.risk` is hardcoded to `Medium` regardless of verb

`KubectlTool.kt:79–83`

```kotlin
override val risk: RiskLevel
    get() = RiskLevel.Medium   // always Medium

override val readOnly: Boolean
    get() = false
```

The spec requires mutating verbs (`apply`, `create`, `delete`, `exec`, etc.) to be `High + always-approve`. The current implementation means `kubectl delete pod x` does **not** require approval. This is a security regression.

Same root cause as C1 — verb-dependent risk can't be a static property. Same fix strategy applies.

---

## Security issues

### S1 — Shell injection in `GitPushTool`

`GitPushTool.kt:86–87`

```kotlin
val branchArg = if (branch != null) "$remote $branch" else remote
val result = ProcessRunner.run("git push $branchArg", cwd, 120_000)
```

`remote` and `branch` are user-supplied strings embedded directly into a `bash -c` command. A value like `origin; rm -rf ~` would execute arbitrary commands. The same pattern appears for `kubeconfig` in `KubectlTool.buildKubectlArgs` — the assembled string is passed to `bash -c` verbatim.

**Fix**: Use `ProcessBuilder` directly with an args list (no shell interpolation), or validate that `remote` and `branch` are alphanumeric + common git identifier characters before using them.

### S2 — Path traversal check is prefix-fragile

`ShellTool.kt:75`, `GitTool.kt:210`, `GitPushTool.kt:71`

```kotlin
if (!absPath.toString().startsWith(workspaceRoot.toString())) { ... }
```

If `workspaceRoot` is `/tmp/ws`, then `/tmp/workspace/evil` passes the check because `"/tmp/workspace/evil".startsWith("/tmp/ws")` is `true`. Should be:

```kotlin
absPath.normalize().startsWith(workspaceRoot.toRealPath())
```

---

## Correctness issues

### O1 — `HttpTool` always returns empty response headers

`HttpTool.kt:175`

```kotlin
put("headers", buildJsonObject { })   // empty — spec says return actual headers
```

The spec requires `{status, headers, body}` where `headers` is the actual response headers map.

### O2 — `GitTool.commit` ignores the `paths` arg

`GitTool.kt:198–200`

```kotlin
git.add().addFilepattern(".").call()   // always stages everything
```

The spec defines `paths (optional)` for selective staging. The arg is accepted in the schema but silently ignored.

### O3 — `WebSearchTool` bypasses the secrets store for provider selection

`WebSearchTool.kt:19,23,66`

The `braveApiKey` is injected at construction time. The spec says: *"Selects provider: Brave if `brave_api_key` secret present, else DDG"* — this should be a runtime check via `ctx.secretLookup("brave_api_key")` so the user can configure the key in the secrets store at any time without restarting. The current design requires the key at wiring-time.

### O4 — `GitPushTool` doesn't check `timedOut`

`GitPushTool.kt:89–98`

The code only checks `result.exitCode == 0`. If `ProcessRunner` times out, `exitCode = -1`, and the message is "push failed: ..." instead of "timeout." Minor, but the user gets a confusing error.

---

## Architectural issues

### A1 — Multiple independent `HttpClient` instances

`HttpTool`, `BraveSearchProvider`, and `DuckDuckGoSearchProvider` each create their own `HttpClient(CIO)` instance. The spec says to share the same `HttpClientFactory` as the LLM provider. These clients are also never closed, creating a resource leak if tools are constructed and discarded.

### A2 — `ProcessRunner` blocks coroutine thread

`ProcessRunner.kt:53–74`

```kotlin
while (process.isAlive) {
    ...
    Thread.sleep(POLL_INTERVAL_MS)   // blocks the coroutine dispatch thread
}
```

Using `Thread.sleep` inside a `suspend` function pins the thread. For 100ms polling of a 60s process, this means 600 blocked coroutine frames. Should wrap the blocking section in `withContext(Dispatchers.IO)` or use `process.waitFor(timeout, TimeUnit.MILLISECONDS)` to block only an IO thread.

### A3 — `FileSystemListTool` holds a redundant `workspaceRoot: Path`

`FileSystemListTool.kt:22`

The constructor takes both `WorkspaceFs` and `workspaceRoot: Path` separately, but `WorkspaceFs` already encapsulates the root. If they diverge, file sizes will be read from the wrong path. Encapsulate the `Files.size` / `Files.getLastModifiedTime` call inside `WorkspaceFs` or use the path already embedded in `WorkspaceFs`.

### A4 — `ScheduleTool` and `JobTools` are pure in-memory stubs

`ScheduleTool.kt:79–88`, `JobTools.kt:61–69`

`ScheduleTool.createRoutine` returns a fabricated object with no DB write. `JobCreateTool` generates a timestamp ID without persisting anything. `JobStatusTool.invoke` always returns `status: pending` regardless of ID. These stubs are acceptable per the plan (M8 wires the engine), but this should be documented with a `// stub: M8.T1/M8.T2 will replace` comment so it's not accidentally shipped as-is.

---

## Test coverage

The spec acceptance criteria requires unit tests for every tool. Currently only 4 files exist covering 22+ tools:

| Test file | What it covers | Missing spec cases |
|---|---|---|
| `FileSystemReadToolTest` | Read ok, not found, missing arg | **Traversal attack → Err** (spec-required) |
| `ShellToolTest` | Echo, missing cmd, non-zero exit, timeout | Forbidden command → Deny from validator |
| `HttpToolTest` | Missing method/url/invalid method | No real GET; no domain-deny; no secret injection |
| `WebSearchToolTest` | Missing query, empty result | Provider selection (Brave/DDG); recorded fixtures |

**Completely missing test files** (all spec-required):
- `FileSystemListToolTest`
- `FileSystemGlobToolTest`
- `FileSystemWriteToolTest` (including hygiene rejection)
- `FileSystemAppendToolTest`
- `MemorySearchToolTest`, `MemoryReadToolTest`, `MemoryWriteToolTest`, `MemoryTreeToolTest`
- `WikiReadToolTest`, `WikiWriteToolTest`
- `GitToolTest` (init repo → status clean; add file → untracked; commit → log)
- `GitPushToolTest`
- `KubectlToolTest`
- `AskUserToolTest`
- `ScheduleToolTest`
- `JobToolsTest`

---

## Minor issues

### M1 — `WikiReadTool` imports `WorkspacePath` from memory module

`WikiReadTool.kt:9`

```kotlin
import com.hebe.memory.workspace.WorkspacePath
```

All other file tools import `com.hebe.api.workspace.WorkspacePath`. These may be the same type aliased, but the inconsistency should be resolved to use the canonical API path.

### M2 — `FileSystemReadTool` base64 path converts String → bytes → base64

`FileSystemReadTool.kt:69`

`WorkspaceFs.read()` returns `String?`. If the file is actually binary (e.g., a PNG), the string conversion will corrupt the content before base64-encoding. The spec says non-text files with no `encoding=base64` should return `Err`; reading binary as base64 should use a `readBytes` variant of `WorkspaceFs`.

### M3 — `KubectlTool` schema has no `required` array

`KubectlTool.kt:36–77`

The JSON Schema omits the `"required": ["verb"]` field, making `verb` technically optional from the LLM's perspective, even though `invoke` errors without it.

### M4 — `WIKI_PREFIX` is a `val` inside a class, not a companion constant

`WikiReadTool.kt:22`, `WikiWriteTool.kt:26`

`private val WIKI_PREFIX = "wiki/"` is declared as an instance field. Should be `companion object { const val WIKI_PREFIX = "wiki/" }` or a file-level constant.

---

## What's done well

- Package structure and naming (`com.hebe.tools.builtin.<group>/<Tool>.kt`) is exactly per spec.
- `ToolSpec.name` is consistently `snake_case` across all tools.
- `HygieneScanner` is wired to all write paths (FileSystemWrite, FileSystemAppend, MemoryWrite, WikiWrite) consistently.
- `ToolResult.NeedsApproval` is used correctly in `AskUserTool`.
- `ProcessRunner` graceful-then-forceful destroy is implemented correctly per the pitfalls note.
- `EmergencyStop` integration in `ProcessRunner` is present.
- `RepositoryBuilder.findGitDir` validation in `GitPushTool` is correct.
- Cron regex validation in `ScheduleTool` covers the basic cases as specified.
- `BraveSearchProvider` and `DuckDuckGoSearchProvider` provider interface is clean.
- `build.gradle.kts` deps match the spec exactly (jgit, ktor-client, serialization, security, memory).
