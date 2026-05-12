# Review 006 — Remaining Fix Checklist

6 items remain from review-005. Items are ordered by severity.

---

- [ ] **[Critical] Fix verifier signature parsing** (`modules/security/src/main/kotlin/com/hebe/security/receipts/Verifier.kt`)  
  Replace `receipt.sig.split(":")` with a split that correctly handles the `"ed25519:base64url:<base64>"` format. Simplest fix:
  ```kotlin
  val prefix = "ed25519:base64url:"
  if (!receipt.sig.startsWith(prefix)) {
      return VerifyResult.Failed(seq, "Invalid signature format")
  }
  val signatureBytes = try {
      java.util.Base64.getUrlDecoder().decode(receipt.sig.removePrefix(prefix))
  } catch (e: Exception) { ... }
  ```

- [ ] **[Significant] Implement `EstopCommand`** (`modules/cli-app/src/main/kotlin/com/hebe/cli/Main.kt`)  
  Replace the stub with a real implementation that calls `EstopIpc.sendStop(EstopIpc.getSocketPath(hebeDataDir))` and prints the spec-format output:
  ```
  Sending estop to local hebe instance…
  Acknowledged. In-flight tool calls cancelled. Pending approvals expired.
  ```
  If `sendStop()` returns `false`, print an error (no running instance or failed to connect).

- [ ] **[Moderate] Fix `EstopIpc.startServer()` — replace non-existent JDK class** (`modules/security/src/main/kotlin/com/hebe/security/estop/EstopIpc.kt`)  
  Delete `getUnixDomainServerSocketAddress()` entirely. Replace the `server.bind(...)` call with:
  ```kotlin
  val server = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
  server.bind(java.net.UnixDomainSocketAddress.of(socketPath))
  ```
  The client side already uses `java.net.UnixDomainSocketAddress.of()` correctly — the server should too.

- [ ] **[Moderate] Fix `CanonicalJson.valueToJsonElement()` for nested Maps** (`modules/security/src/main/kotlin/com/hebe/security/receipts/CanonicalJson.kt`)  
  Add a `is Map<*, *>` branch before the `else` to recursively build a sorted `JsonObject`:
  ```kotlin
  is Map<*, *> -> buildJsonObject {
      value.entries.sortedBy { it.key.toString() }.forEach { (k, v) ->
          put(k.toString(), valueToJsonElement(v))
      }
  }
  ```

- [ ] **[Minor] Delete duplicate `ArgsRedactor`** (`modules/security/src/main/kotlin/com/hebe/security/policy/ArgsRedactor.kt`)  
  This file is identical to `modules/api/src/main/kotlin/com/hebe/api/security/ArgsRedactor.kt` which is what `ToolDispatcher` imports. The security-module copy is now dead code — delete it.

- [ ] **[Minor] Write estop receipt in `EmergencyStop.requestStop()`** (`modules/security/src/main/kotlin/com/hebe/security/estop/EmergencyStop.kt`)  
  Inject `Receipts` (the `api.Receipts` interface) into `EmergencyStop` constructor. In `requestStop()`, call:
  ```kotlin
  receipts.append(PartialReceipt(
      sessionId = "estop",
      turnId = "estop",
      tool = "_estop",
      argsRedacted = "{}",
      risk = "High",
      durationMs = 0,
      ok = false,
  ))
  ```
