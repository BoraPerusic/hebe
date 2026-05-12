# Review 006 — M3 Security (re-review)

**Branch**: v1-development  
**Date**: 2026-05-12  
**Scope**: Re-check of all issues from review-005; tests excluded per agreement.

---

## Summary

The developer addressed the vast majority of review-005 findings. The architectural duplication (the `*DispatchValidator` classes) has been eliminated; the SSRF guard, glob matching, ArgsRedactor wiring, fsync, public key persistence, record count in verifier, and CLI stubs for `hebe memory show` are all fixed. Several issues remain; two of them are outright bugs that will cause runtime failures.

---

## Fixed (not repeated)

- Validator duplication removed; `PolicyChain` returns `List<Validator>`; `ToolDispatcher` converts via `.toDispatchValidator()`
- YOLO branch logs a warning
- Dead `else` branch in `Supervised` collapsed
- `WorkspaceBoundaryValidator` now denies for `ConfiguredRoots` when path is outside both workspace and allowed roots
- Glob `*` now maps to `.*` (multi-word commands work)
- `PipeToShellChecker` typo fixed (`ruby`, not `york`)
- `VariableSubstitutionChecker` now flags `$IFS` alone
- `RmRfChecker` added
- `SsrfGuard.BlockedRange.contains()` now implements real IPv4 and IPv6 CIDR matching
- DNS cache uses a typed `DnsEntry` class with `List<String>` addresses
- `PromptInjectionValidator` severity fixed: `Warn + High` → `Deny`
- `LeakDetector` moved to own file, now checks entropy per token
- Observer event emitted on leak detection
- `ArgsRedactor.redact()` called in `ToolDispatcher.writeReceiptAndMemory()` (redactor also promoted to `api` module)
- `CanonicalJson` class exists; both `Receipts` and `Verifier` use it
- `fsync` (`channel.force(true)`) actually called every `fsyncBatchSize` appends
- Public key written to `~/.hebe/receipts/public.key` on init
- `verifyDirectory()` accumulates real record count
- `MemoryShowCommand` implemented (loads public key, calls verifier, prints result)
- `AppComponents.kt` created, wires `PolicyChain.standard()` into `ToolDispatcher`
- `EmergencyStop.requestStop()` calls `scope.cancel()`
- `EstopIpc.startServer()` implemented

---

## Remaining Issues

### 1. Verifier signature parsing is always broken (Critical)

`Receipts` writes `sig` in the format `"ed25519:base64url:<base64>"` (three colon-separated segments). `ReceiptVerifier` splits on `:` without a limit:

```kotlin
val sigParts = receipt.sig.split(":")
if (sigParts.size != 2 || sigParts[0] != "ed25519:base64url") {
    return VerifyResult.Failed(seq, "Invalid signature format")
}
```

`split(":")` always produces 3 elements for any valid sig; `sigParts.size != 2` is always `true`; the verifier always returns `"Invalid signature format"` at the first record. The verifier is completely non-functional as written.

Fix: use `receipt.sig.split(":", limit = 3)` and check that the first two elements join to the expected algorithm prefix, or use `receipt.sig.startsWith("$SIG_ALGORITHM:")` and extract the tail with `removePrefix("$SIG_ALGORITHM:")`.

### 2. `EstopCommand` is still a stub (Significant)

`Main.kt` line 138–141: `EstopCommand.run()` still prints `"Not yet implemented: hebe estop"`. `EstopIpc.sendStop()` and `getSocketPath()` exist and are ready to be called. This is a trivial wiring gap but means the entire estop CLI path is non-functional.

### 3. `CanonicalJson` does not handle nested objects (Moderate)

`CanonicalJson.valueToJsonElement()`:

```kotlin
else -> JsonPrimitive(value.toString())
```

The `approval` field is passed as `mapOf("required" to false)` from both `Receipts` and `Verifier`. A `Map<String, Boolean>` hits the `else` branch and becomes the JSON string `"{required=false}"` rather than a JSON object. The hash chain is internally consistent (both sides produce the same broken canonical form), but the resulting canonical JSON is not valid JSON representation of the approval object. If a future change passes the approval as an actual structured value this will silently diverge. The spec calls for canonical JSON — the canonical form should be proper JSON.

Fix: add a `is Map<*, *>` branch that recursively builds a sorted `JsonObject` from the map entries.

### 4. `EstopIpc.startServer()` uses a non-existent JDK class (Moderate)

```kotlin
private fun getUnixDomainServerSocketAddress(socketPath: Path): java.net.SocketAddress {
    val clazz = Class.forName("jdk.net.UnixDomainServerSocketAddress")
    ...
}
```

`jdk.net.UnixDomainServerSocketAddress` does not exist in any JDK release. The correct way to open a Unix-domain server socket on JDK 16+ is:

```kotlin
val server = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
server.bind(java.net.UnixDomainSocketAddress.of(socketPath))
```

This will throw `ClassNotFoundException` at runtime the first time `hebe estop` reaches a running instance.

### 5. Duplicate `ArgsRedactor` (Minor)

`com.hebe.security.policy.ArgsRedactor` (the original) was not deleted when `com.hebe.api.security.ArgsRedactor` was added. Two identical implementations of the same class exist. The dispatch module correctly imports from `api.security`, but the security module's copy is dead code that can cause confusion if someone imports it by mistake. The old file should be deleted.

### 6. No synthetic receipt written on estop (Minor)

`EmergencyStop.requestStop()` cancels the scope but does not write the `{tool:"_estop", ok:false}` receipt that the spec (M3.T11) requires. The `Receipts` dependency is not injected into `EmergencyStop`.

---

## Summary Table (re-review)

| Issue | Severity | Fixed? |
|---|---|---|
| Validator duplication | Architecture | ✅ Fixed |
| SSRF guard non-functional | Critical | ✅ Fixed |
| ArgsRedactor not wired | Critical | ✅ Fixed |
| No canonical JSON | Critical | ⚠️ Partially (exists but Map serialization broken) |
| No fsync | Critical | ✅ Fixed |
| Verifier signature parsing always fails | Critical | ❌ New bug confirmed |
| EstopCommand stub | Significant | ❌ Still stub |
| EstopIpc server uses non-existent class | Moderate | ❌ Runtime failure |
| ConfiguredRoots path gap | Moderate | ✅ Fixed |
| Glob end-anchor | Moderate | ✅ Fixed |
| PromptInjection severity wrong | Moderate | ✅ Fixed |
| entropy FP on full result string | Moderate | ✅ Fixed |
| No observer event on leak | Moderate | ✅ Fixed |
| Verifier record count hardcoded 0 | Minor | ✅ Fixed |
| MemoryShowCommand stub | Significant | ✅ Fixed |
| YOLO no warning | Minor | ✅ Fixed |
| No public key file | Minor | ✅ Fixed |
| Duplicate ArgsRedactor | Minor | ❌ Old copy still exists |
| No estop receipt | Minor | ❌ Not implemented |
