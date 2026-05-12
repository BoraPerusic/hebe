# tasks-review-007.md — M4 Built-in Tools review fixes

Source: `review-007.md`, re-reviewed in `review-008.md`

---

## Critical bugs

- [ ] **C1** — Fix `GitTool`: clone must require approval. The `emptyArgs()` trick does not work — `requiresApproval` is evaluated before `invoke` is called so per-verb gating via a property is impossible. **Correct approaches**: (a) `gitClone` returns `ToolResult.NeedsApproval(...)` itself; (b) split into per-verb tools; (c) add `effectiveRequiresApproval(args)` to the dispatcher API. Do NOT try to fix this via `override val requiresApproval` — it cannot read the actual invocation args at property access time. **Note**: the current "fix" made clone approval-free (regression vs. review-007).
- [ ] **C2** — Fix `KubectlTool`: mutating verbs must require approval. Same root cause as C1. Simplest fix: inside `invoke`, after resolving the verb, return `ToolResult.NeedsApproval(...)` for mutating verbs (or use `ctx.approvalGate.awaitApproval(...)`) before calling `runKubectl`. The `requiresApproval` property override approach does not work.

---

## Security issues

- [ ] **S1** — `GitPushTool`: replace bash string interpolation `ProcessRunner.run("git push $branchArg", ...)` with `ProcessBuilder` args list (as was done correctly for `KubectlTool`). The current regex validator blocks injection but also incorrectly rejects `/` in branch names (e.g., `feature/my-branch`).

---

## Correctness issues

- ~~O1~~ Fixed
- ~~O2~~ Fixed
- ~~O3~~ Fixed
- ~~O4~~ Fixed

---

## Architectural issues

- [ ] **A1** — Share `HttpClient`: replace per-class `HttpClient(CIO)` instances in `HttpTool`, `BraveSearchProvider`, and `DuckDuckGoSearchProvider` with a shared factory or injected client. Clients are never closed — resource leak.
- ~~A2~~ Fixed
- ~~A3~~ Fixed
- ~~A4~~ Fixed
- [ ] **A2-regression** — `EmergencyStop` check is now inoperative during `process.waitFor()`: if stop is requested mid-run, the process is not killed until timeout. Consider using a separate coroutine to monitor `emergencyStop.isStopRequested` and cancel the `withContext` scope, or keep a reference to the `Process` and call `destroyForcibly()` from the monitoring side.

---

## Missing tests

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
- [ ] **T14** — Add `KubectlToolTest`: verify mutating verb triggers approval path (after C2 is fixed).
- [ ] **T15** — Add `AskUserToolTest`: `purpose=credential` without `secretName` → `Err`; with `secretName` → `NeedsApproval`.
- [ ] **T16** — Add `ScheduleToolTest`: create + list + disable + delete round-trips.
- [ ] **T17** — Add `JobToolsTest`: create → status `pending`; cancel → status `cancelled`.
- [ ] **T-ws** — Restore `WebSearchToolTest` (was deleted). Add provider selection test: when `brave_api_key` secret present → Brave; absent → DDG. Use a mock `SecretLookup`.

---

## Minor issues

- [ ] **M2** — `FileSystemReadTool` binary detection: `String.toByteArray()` never throws, so the `isBinary` flag is always `false` and the guard is dead code. Real detection should happen in `WorkspaceFs` at the bytes level, or use a null-byte / non-UTF-8 heuristic on the raw file bytes before decoding to String.
- ~~M1~~ Fixed
- ~~M3~~ Fixed
- ~~M4~~ Fixed
