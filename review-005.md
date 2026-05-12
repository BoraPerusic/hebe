# Review 005 — M3 Security

**Branch**: v1-development  
**Date**: 2026-05-12  
**Scope**: Milestone M3 — all 11 tasks (M3.T1 – M3.T11)

---

## Summary

The developer has implemented all 11 M3 tasks at the structural level: every required class and interface exists and compiles. However, the milestone has multiple correctness bugs, one architectural anti-pattern that spans the entire module, near-zero test coverage, and two tasks that are effectively stubs. The work is **not complete as claimed**.

---

## Architectural Issue: Validator Duplication (Affects T1–T5, T10)

Every policy validator was written twice: once implementing `api.Validator` and once implementing `tools.dispatch.DispatchValidator`. These paired classes are structurally identical — same constructor, same `validate` logic, different return type only. This pattern was also applied in `AutonomyValidator`/`AutonomyDispatchValidator`, `WorkspaceBoundary*/Dispatch*`, `CommandPolicy*/Dispatch*`, `DomainAllowlist*/Dispatch*`, and `PromptInjection*/Dispatch*`.

The spec required only one validator per policy. The bridge to `DispatchValidator` already exists as `Validator.toDispatchValidator()` in `tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/Validator.kt`. `PolicyChain.standard()` should return `List<Validator>` and call `.toDispatchValidator()` at the wiring site; there is no need for the `*DispatchValidator` classes at all.

`PolicyChain` also has a `toApiValidators()` converter that does the reverse — `DispatchValidator` → `Validator` — which is only needed because of the duplication and creates a confusing bidirectional conversion that does not appear in the architecture docs.

**Impact**: ~50% of the security module's source code is dead weight. The split makes the module harder to read, harder to test, and the duplicate classes will inevitably drift.

---

## Task-by-Task Findings

### M3.T1 — AutonomyLevel + Validator

**Overall**: Passes in spirit; matrix logic is correct.

- The `ReadOnly` branch allows read-only tools unconditionally, including High-risk read-only tools. The spec matrix says ReadOnly/Low → Allow if read-only. No provision exists for High-risk read-only tools. Minor ambiguity in the spec, but worth noting.
- `Supervised` case: the `else` branch at line 33 is dead code — the `tool.requiresApproval || tool.risk == RiskLevel.High` condition already covers all non-Low cases. The `else` is unreachable.
- `YOLO` returns `Allow` with no warning logged, despite the spec saying "Allow (with loud warning)".
- **No test file exists**. The spec requires all 12 cells of the autonomy matrix to be tested.

### M3.T2 — Workspace Boundary Validator

**Overall**: Logic is mostly right, but there is a correctness gap.

- `ConfiguredRoots` path scope is silently allowed when a path is outside BOTH the workspace AND the `additionalAllowedRoots`. The loop at line 37–46 only denies for `WorkspaceOnly` scope. A `ConfiguredRoots`-scoped tool pointing to `/etc/passwd` passes through if `additionalAllowedRoots` is empty — it should `Deny` instead (path must be inside at least one configured root).
- **No test file exists**.

### M3.T3 — Command Policy Validator

**Overall**: Implemented; several correctness issues.

- `PipeToShellChecker` at line 51 includes `"york"` in the list of shell interpreters. This is clearly a typo (likely `ruby`). It makes `rb` redundant too since there's no `rb` command in common use.
- `globToRegex` anchors at start and end (`^...$`), so `git *` matches `git status` but not `git push --force` (because `[^ ]*` stops at the first space and the `$` anchor fails). Multi-argument commands will be silently denied even when allowed. This breaks real-world usage (e.g. `kubectl get pods -n default`).
- `VariableSubstitutionChecker` requires BOTH `$IFS` AND `$9` to be present. The spec says "flag `$IFS$9` or whitespace tricks". The compound token `$IFS$9` (i.e., the concatenation) should be one pattern, and standalone `$IFS` tricks should also be flagged independently.
- No property test exists for `rm -rf` detection, which the spec explicitly requires.
- **No test file exists**.

### M3.T4 — Domain Allowlist + SSRF Guard

**Overall**: SSRF guard is broken — the primary security control does not work.

- `BlockedRange.contains()` in `SsrfGuard.kt` (lines 99–105) always returns `false` for CIDR ranges. The implementation checks `addr == cidr` (exact match only) and then returns `false` for ranges containing `/`. Every blocked CIDR range (`127.0.0.0/8`, `10.0.0.0/8`, `192.168.0.0/16`, etc.) is silently bypassed. Only the exact-match entries `169.254.169.254` and `metadata.google.internal` actually block.
- The DNS cache stores IPv4 as packed `Long` but IPv6 addresses are converted via `addrToNum()` which only handles dot-notation IPv4 (`addr.contains(".")`). IPv6 addresses are stored as `0L` and thus invisible to the SSRF check.
- `allowLoopbackFor` check in `resolveHostnames()` at line 51 resolves allowed hosts to loopback addresses — but these addresses then pass the SSRF check because the outer `isBlocked()` has already returned `Allowed` at line 31. The `resolveHostnames()` branch at line 51 is therefore unreachable in normal operation.
- **No test file exists**.

### M3.T5 — Prompt-Injection Guard

**Overall**: Reasonable implementation; one spec deviation.

- The spec says the scan should also run on the model's text response in `BeforeOutbound`. Currently only tool-call args are scanned.
- The severity mapping is slightly off: `HygieneResult.Warn` with High-severity findings → `RequireApproval` (should be `Deny` per M3.T5 spec which says `High → Deny`).
- **No test file exists**.

### M3.T6 — Leak Detector

**Overall**: Patterns are correct; missing spec requirements.

- `LeakDetectorImpl` lives inside `SecretPatterns.kt`. The spec called for a dedicated `LeakDetector.kt`. It also named the class `LeakDetector` (not `LeakDetectorImpl`). Minor naming deviation but inconsistent.
- The high-entropy check runs `hasHighEntropy(contentStr)` on the **entire serialized result string**, not on individual candidate tokens. A normal JSON response with a UUID, a long base64 image URL, or any 32-char alphanumeric field will trigger a false positive. The entropy check should run on individual matched substrings, not the whole payload.
- No observer event (`LeakDetected(tool, rule, severity=High)`) is emitted when a leak is detected. The spec requires this.
- Patterns are not configurable from `config.toml` as required by the spec.

### M3.T7 — Sensitive-Param Redaction

**Overall**: `ArgsRedactor` is correct; it is not wired up where the spec requires.

- `ArgsRedactor` has the right denylist and recursive walk.
- `ToolDispatcher.writeReceiptAndMemory()` at line 138 passes `call.args.toString()` directly to `PartialReceipt.argsRedacted`. The `ArgsRedactor` is never called. Receipts are written with un-redacted arguments.
- The spec also requires redaction in `LogbackObserver` and the Web UI; those integration points are absent.

### M3.T8 — Ed25519 Receipts Log Writer

**Overall**: The happy path works; several spec deviations in correctness and details.

- `buildCanonical()` in `Receipts.kt` uses a hand-rolled `key:value,key:value` string, not canonical JSON (sorted keys, no whitespace). The spec explicitly calls for a `CanonicalJson` class with deterministic JSON serialisation. The custom format would produce different hashes from any external verifier expecting RFC 8785 / canonical JSON.
- `Receipt.argsRedacted` is declared as `String`, but the spec defines it as `JsonObject`. Since `ToolDispatcher` serializes args to String before passing them, the type mismatch compounds the un-redacted args bug from T7.
- The `fsync` code increments `fsyncCounter` and resets it to 0 but never actually calls `fsync()` on the file channel. No durability guarantee exists.
- The signing key's public half is not written to `~/.hebe/receipts/public.key` as the spec requires, making external verification impossible without digging into the secrets store.
- Cross-month rollover wires `prevHash` correctly in memory, but on a fresh boot `loadLastState()` does not propagate the previous month's `selfHash` to the new month — `lastHash` will reset to `ZERO_HASH` on restart, breaking the chain across reboots.

### M3.T9 — Receipts Verifier

**Overall**: Logic is correct; one reporting bug, CLI is a stub.

- `verifyDirectory()` returns `VerifyResult.Ok(0, lastSelfHash)` — the record count is hardcoded to `0` regardless of how many records were verified.
- `MemoryShowCommand` in `Main.kt` is a stub (`"Not yet implemented: hebe memory show"`). The spec requires `hebe memory show receipts/<file> --verify` to be implemented.
- Verifier's `buildCanonical()` mirrors the same non-canonical format as in `Receipts.kt`. This is at least consistent, but if `Receipts.kt`'s canonical format is fixed, the verifier must be updated in lockstep — a coupling risk.

### M3.T10 — Wire Policy Chain

**Overall**: `PolicyChain` compiles and integrates structurally; wiring is incomplete.

- `PolicyChain.standard()` returns `List<DispatchValidator>` (as a result of the duplication anti-pattern); the spec says it should return `List<Validator>`.
- `AppComponents.kt` is absent. The spec lists it as a required new file.
- `ToolDispatcher` does not use `ArgsRedactor` before populating receipts (T7 issue propagates here).
- No integration test exists exercising the full chain through `ToolDispatcher`.

### M3.T11 — Emergency Stop

**Overall**: Flag and client-side IPC exist; server-side and CLI are stubs.

- `EmergencyStop.scope` is accepted as a constructor argument but never used. The in-flight coroutine scope is never cancelled on stop.
- `EstopIpc` implements the **client side** only (`sendStop()`). The server side — the Unix-domain socket listener that runs inside the hebe process and handles `STOP\n` → `OK\n` — is not implemented.
- `EstopCommand` in `Main.kt` is a stub (`"Not yet implemented: hebe estop"`).
- No synthetic receipt is written on estop, as required.

---

## Test Coverage

Only one test file exists for the entire `security` module: `ApprovalGateTest.kt`, which has two trivial flag-check tests. The spec mandates unit tests for every policy validator, a 20+ golden-case suite for `CommandPatternChecker`, receipt chain tests, verifier tamper tests, and at least one policy-chain integration test. None of these exist.

---

## Summary Table

| Task | Files exist | Correctness | Tests |
|------|-------------|-------------|-------|
| M3.T1 AutonomyValidator | ✅ | ⚠️ YOLO no warning, dead else-branch | ❌ |
| M3.T2 WorkspaceBoundary | ✅ | ⚠️ ConfiguredRoots allows any path | ❌ |
| M3.T3 CommandPolicy | ✅ | ❌ glob end-anchor bug, "york" typo | ❌ |
| M3.T4 DomainAllowlist / SSRF | ✅ | ❌ SSRF guard non-functional (CIDR unimplemented) | ❌ |
| M3.T5 PromptInjection | ✅ | ⚠️ High severity should Deny, not RequireApproval | ❌ |
| M3.T6 LeakDetector | ✅ | ⚠️ entropy FP risk, no observer event, not configurable | ❌ |
| M3.T7 ArgsRedactor | ✅ | ❌ not wired in ToolDispatcher receipt path | — |
| M3.T8 Receipts | ✅ | ❌ no canonical JSON, no real fsync, no public key file, chain broken across reboots | ❌ |
| M3.T9 Verifier | ✅ | ⚠️ record count always 0; CLI stub | ❌ |
| M3.T10 PolicyChain | ✅ | ⚠️ no AppComponents, no integration test | ❌ |
| M3.T11 Estop | ⚠️ server-side missing | ❌ scope not cancelled, CLI stub, no receipt | ❌ |
