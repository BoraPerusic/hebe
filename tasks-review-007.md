# tasks-review-007.md — M4 Built-in Tools review fixes

Source: `review-007.md`

---

## Critical bugs

- [ ] **C1** — Fix `GitTool.risk`: the property always returns `High` because `verbFromArgs(emptyArgs())` always returns `""`. Determine effective risk per-verb at invoke-time (e.g. expose `effectiveRisk(verb): RiskLevel` and set a safe default on the property level, or return `NeedsApproval` for clone specifically).
- [ ] **C2** — Fix `KubectlTool.risk`: hardcoded `Medium` means mutating verbs (`delete`, `apply`, `exec`, etc.) bypass approval. Implement verb classification at invoke-time; return `NeedsApproval` for mutating verbs or gate via dynamic risk.

---

## Security issues

- [ ] **S1** — Fix shell injection in `GitPushTool` (`git push $branchArg`) and `KubectlTool` (`kubectl $kubectlArgs`): replace bash-string interpolation with a `ProcessBuilder` args list, or validate that `remote`, `branch`, and `extraArgs` values only contain safe characters before string-joining.
- [ ] **S2** — Fix path traversal check: replace `absPath.toString().startsWith(workspaceRoot.toString())` with `absPath.normalize().startsWith(workspaceRoot.toRealPath())` in `ShellTool`, `GitTool.resolveRepoDir`, and `GitPushTool`.

---

## Correctness issues

- [ ] **O1** — `HttpTool`: populate the `headers` field in the response with actual response headers instead of an empty object.
- [ ] **O2** — `GitTool.commit`: respect the optional `paths` arg; only add specified paths to the index instead of always staging everything with `addFilepattern(".")`.
- [ ] **O3** — `WebSearchTool`: move provider selection from constructor-time to invoke-time using `ctx.secretLookup("brave_api_key")`.
- [ ] **O4** — `GitPushTool`: check `result.timedOut` before checking `result.exitCode` to return a clear `"timeout"` error.

---

## Architectural issues

- [ ] **A1** — Share `HttpClient`: replace the per-class `HttpClient(CIO)` instances in `HttpTool`, `BraveSearchProvider`, and `DuckDuckGoSearchProvider` with a shared factory or injected client. Ensure the client is closed on shutdown to avoid resource leaks.
- [ ] **A2** — Fix `ProcessRunner` coroutine blocking: wrap the polling loop in `withContext(Dispatchers.IO)`, or replace the busy-poll with `process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)` to avoid pinning a coroutine dispatch thread.
- [ ] **A3** — `FileSystemListTool`: remove the redundant `workspaceRoot: Path` constructor arg; derive file metadata through `WorkspaceFs` or add a `stat(WorkspacePath)` method to `WorkspaceFs`.
- [ ] **A4** — `ScheduleTool` and `JobTools`: add `// stub: pending M8.T1/M8.T2 for DB persistence` comments to `createRoutine`, `listRoutines`, `JobCreateTool.invoke`, and `JobStatusTool.invoke` so stubs are not accidentally treated as complete.

---

## Missing tests

- [ ] **T1** — `FileSystemReadToolTest`: add traversal attack test (`path = "../../etc/passwd"` → `Err`).
- [ ] **T2** — Add `FileSystemListToolTest`: list returns names and sizes.
- [ ] **T3** — Add `FileSystemGlobToolTest`: `**/*.md` returns matching paths.
- [ ] **T4** — Add `FileSystemWriteToolTest`: write + read round-trip; hygiene rejection → `Err`.
- [ ] **T5** — Add `FileSystemAppendToolTest`: append + read verifies content.
- [ ] **T6** — Add `MemorySearchToolTest`: search returns hits; empty query returns empty.
- [ ] **T7** — Add `MemoryReadToolTest`: read existing doc → `Ok`; missing → `Err`.
- [ ] **T8** — Add `MemoryWriteToolTest`: write + read round-trip; hygiene rejection → `Err`.
- [ ] **T9** — Add `MemoryTreeToolTest`: tree under prefix returns correct paths.
- [ ] **T10** — Add `WikiReadToolTest`: round-trip; wikilink extraction `[[Foo]]` → `["Foo"]`.
- [ ] **T11** — Add `WikiWriteToolTest`: write + read back; hygiene rejection → `Err`.
- [ ] **T12** — Add `GitToolTest`: init temp repo → status clean; add file → untracked; commit → log contains entry.
- [ ] **T13** — Add `GitPushToolTest`: push to local bare repo → `Ok`; push non-git dir → `Err`.
- [ ] **T14** — Add `KubectlToolTest`: verify read-only verb does not trigger approval path; mutating verb does (after C2 is fixed).
- [ ] **T15** — Add `AskUserToolTest`: `purpose=credential` without `secretName` → `Err`; with `secretName` → `NeedsApproval`.
- [ ] **T16** — Add `ScheduleToolTest`: create + list + disable + delete round-trips (in-memory is fine until M8).
- [ ] **T17** — Add `JobToolsTest`: create → status `pending`; cancel → status `cancelled`.

---

## Minor issues

- [ ] **M1** — `WikiReadTool`: change `import com.hebe.memory.workspace.WorkspacePath` to `import com.hebe.api.workspace.WorkspacePath` for consistency with other tools.
- [ ] **M2** — `FileSystemReadTool`: add binary-file detection; if file content cannot be decoded as UTF-8 and `encoding != "base64"`, return `Err("binary file: use encoding=base64")`.
- [ ] **M3** — `KubectlTool` JSON schema: add `"required": ["verb"]` to the schema object.
- [ ] **M4** — `WikiReadTool` / `WikiWriteTool`: move `WIKI_PREFIX` to a `companion object` constant (or a file-level `const val`) rather than a per-instance `val`.
