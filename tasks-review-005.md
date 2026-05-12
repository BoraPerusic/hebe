# Review 005 — M3 Fix Checklist

Generated from `review-005.md`. Work through these in order; items within a section are independent.

---

## 0. Architecture — Remove Validator Duplication

- [ ] Delete all `*DispatchValidator` classes: `AutonomyDispatchValidator`, `WorkspaceBoundaryDispatchValidator`, `CommandPolicyDispatchValidator`, `DomainAllowlistDispatchValidator`, `PromptInjectionDispatchValidator`
- [ ] Update `PolicyChain.standard()` to return `List<Validator>` using the api types; call `.toDispatchValidator()` at the `ToolDispatcher` construction site
- [ ] Remove `PolicyChain.toApiValidators()` (bidirectional conversion no longer needed)

---

## 1. M3.T1 — AutonomyValidator

- [ ] Add logging `logger.warn("YOLO autonomy level active — all tools permitted")` in the `YOLO` branch
- [ ] Remove the unreachable `else` branch in the `Supervised` case (it duplicates the `tool.requiresApproval || tool.risk == RiskLevel.High` case)
- [ ] Add `AutonomyValidatorTest.kt` testing all 12 matrix cells (ReadOnly × {Low/Medium/High read-only}, ReadOnly × {side-effect}, Supervised × {Low/Medium/High}, Full × {Low/Medium/High with requiresApproval=true/false}, YOLO × any)

---

## 2. M3.T2 — WorkspaceBoundaryValidator

- [ ] Fix `ConfiguredRoots` scope: if `isWithinWorkspace` is false **and** `isWithinAllowedRoot` is false, return `Deny` regardless of scope (not just for `WorkspaceOnly`)
- [ ] Add `WorkspaceBoundaryValidatorTest.kt` covering: workspace-bound rejection of `/etc/passwd`, allowed in-workspace path, ConfiguredRoots allowed path, ConfiguredRoots rejected path, Anywhere scope bypass

---

## 3. M3.T3 — CommandPolicyValidator / CommandPatternChecker

- [ ] Fix `globToRegex`: `*` should match any sequence including spaces within the remaining command string; change `[^ ]*` to `.*` (the spec's examples show `git *` matching `git push`, `kubectl get *` matching `kubectl get pods -n default`)
- [ ] Fix typo in `PipeToShellChecker`: remove `"york"` from the interpreters list
- [ ] Fix `VariableSubstitutionChecker`: flag `$IFS` alone (not only when `$9` is also present)
- [ ] Add a `rm -rf` / `rm -rf /` pattern to `CommandPatternChecker` (spec requires the property test to flag it)
- [ ] Add `CommandPolicyValidatorTest.kt` with ≥20 golden cases (10 should-pass, 10 should-block)
- [ ] Add a property test for `rm -rf` variants in `CommandPatternCheckerTest.kt`

---

## 4. M3.T4 — DomainAllowlistValidator / SsrfGuard

- [ ] Implement CIDR range matching in `BlockedRange.contains()` — parse the network address and prefix length, convert `addr` to a numeric value, apply the mask, and compare to the network address. Must work for both IPv4 CIDR (e.g. `127.0.0.0/8`) and the exact-match entries already present
- [ ] Fix IPv6 handling in the DNS cache: `addrToNum` / `numToAddr` only handle IPv4; store IPv6 addresses as strings separately (or use a proper `InetAddress`-based structure)
- [ ] Remove or fix the unreachable `allowLoopbackFor` branch in `resolveHostnames()` (the check at the top of `isBlocked()` returns early before resolution)
- [ ] Add `SsrfGuardTest.kt` covering: loopback blocked, link-local blocked, private range blocked, metadata IP blocked, public IP allowed, opt-in loopback allowed, `DomainAllowlistValidatorTest.kt` covering allowlist match, allowlist miss, SSRF-blocked URL

---

## 5. M3.T5 — PromptInjectionValidator

- [ ] Fix severity mapping: `HygieneResult.Warn` with any `High`-severity finding should return `Deny`, not `RequireApproval`
- [ ] Add `PromptInjectionValidatorTest.kt`: `"ignore the previous instructions"` → Deny, `<system>` tag → Deny, benign args → Allow, per-turn cache reuse (same turnId returns cached result)

---

## 6. M3.T6 — LeakDetector

- [ ] Move `LeakDetectorImpl` to its own file `LeakDetector.kt` and rename to `LeakDetector`
- [ ] Fix high-entropy scan: extract candidate tokens (32+ char alphanumeric substrings) from the result string and call `hasHighEntropy()` per token, rather than on the entire serialized result
- [ ] Emit an observer event (e.g. `ObserverEvent.LeakDetected`) when a pattern matches; wire the observer into `LeakDetector` constructor
- [ ] Accept a `List<SecretPatterns.Pattern>` from config so users can add patterns at runtime; `ToolDispatcher` must pass the configured list
- [ ] Add `LeakDetectorTest.kt` covering each of the 8 default patterns and a benign pass-through

---

## 7. M3.T7 — ArgsRedactor Wiring

- [ ] In `ToolDispatcher.writeReceiptAndMemory()`: wrap `call.args` through `ArgsRedactor.INSTANCE.redact(call.args)` before calling `.toString()` for `PartialReceipt.argsRedacted`
- [ ] Add `ArgsRedactorTest.kt` covering: top-level key redaction, nested key redaction, configurable extra key, non-sensitive keys pass through

---

## 8. M3.T8 — Receipts

- [ ] Create `CanonicalJson.kt`: serialize a `JsonObject` with keys sorted lexicographically and minimal separators (no whitespace). Replace the hand-rolled `buildCanonical()` string in `Receipts.kt` with `CanonicalJson.serialize()`.
- [ ] Update `Verifier.buildCanonical()` to use `CanonicalJson` (must be consistent with the writer)
- [ ] Change `Receipt.argsRedacted` type from `String` to `JsonObject` (requires updating serialization and the `PartialReceipt` → `Receipt` mapping)
- [ ] Implement actual `fsync`: open the monthly log file with `FileChannel`, call `fileChannel.force(true)` every `fsyncBatchSize` appends, and on shutdown/close
- [ ] On bootstrap, write the public key bytes to `~/.hebe/receipts/public.key` (base64-encoded); `SigningKey.bootstrap()` should accept a `receiptsDir: Path` and write it
- [ ] Fix cross-reboot chain continuity: `Receipts.init()` already loads `lastHash` from the last line on disk — verify this is exercised by `loadLastState()` correctly including across month boundaries (the first record of a new month file should set `prevHash` to the last hash from the previous month, not `ZERO_HASH`)
- [ ] Add `ReceiptsTest.kt`: 100 appends → 100 NDJSON lines, each `selfHash` matches recomputed hash, `prevHash` chain valid, signatures verify against public key

---

## 9. M3.T9 — Verifier / CLI

- [ ] Fix `verifyDirectory()`: return `VerifyResult.Ok(totalRecords, lastSelfHash)` where `totalRecords` is accumulated across all files
- [ ] Implement `MemoryShowCommand` in the CLI: parse the path argument, load the public key from `~/.hebe/receipts/public.key`, call `ReceiptVerifier.verify()` or `verifyDirectory()`, print the spec-format output (`"Verified N receipts..."` or `"FAILED at seq N: reason"`)
- [ ] Add `VerifierTest.kt`: clean log → Ok(N), tampered record → Failed at correct seq, truncated file → Failed

---

## 10. M3.T10 — PolicyChain Wiring

- [ ] Create `modules/cli-app/src/main/kotlin/com/hebe/cli/AppComponents.kt` with the `PolicyChain.standard(config, workspaceRoot)` call wired into `ToolDispatcher` construction (deferred full wiring to M9.T2 is acceptable, but the file must exist with the security wiring)
- [ ] Add policy-chain integration test: a synthetic `ToolDispatcher` with all five validators; verify each validator produces the expected `Deny`/`RequireApproval` for a mismatched config cell

---

## 11. M3.T11 — Emergency Stop

- [ ] Use `scope` to cancel the agent's per-session coroutine in `EmergencyStop.requestStop()` (call `scope.cancel()` or cancel a child scope)
- [ ] Implement the server-side Unix-domain socket listener in `EstopIpc`: expose a `startServer(socketPath, onStop)` suspend function that binds to the socket, accepts connections, reads `STOP\n`, calls `onStop()`, and responds `OK\n`
- [ ] Implement `EstopCommand` in the CLI: call `EstopIpc.sendStop()`, print the spec-format acknowledgement message
- [ ] Write a synthetic receipt `{tool:"_estop", ok:false}` in `EmergencyStop.requestStop()`
- [ ] Add `EmergencyStopTest.kt`: verify `stopFlag` is set, pending approvals are denied on estop, receipt is written
