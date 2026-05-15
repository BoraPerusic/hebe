# Review 016 — M7: MCP (final review)

**Branch:** `v1-development`
**Based on:** uncommitted changes over `1e7c919`
**Previous review:** `review-015.md`

---

## Progress Since review-015

All items from review-015 addressed:

| review-015 item | Fixed? |
|---|---|
| MCP server uses `ToolDispatcher.default()` — no policies, no receipts | ✅ `PolicyChain.standard()` + real `Receipts` in `McpServeCommand` |
| `runMcpStdioServer` mutates the registry it receives | ✅ `registerMcpBuiltinTools()` now called in `McpServeCommand.run()` |
| `bridgeHandler` unused `tool` parameter | ✅ Removed; no `@Suppress` needed |
| `ToolDispatcher` contained memory-domain no-ops | ✅ All no-ops moved to `McpDispatcherFactory`; `default()` removed |
| `buildEnvWithSecrets` environment-dependent | ✅ `systemEnv` parameter added |
| `RemoteToolTest` used `runBlocking` | ✅ Removed; tests run as native suspend |
| `McpClientManagerTest` third test environment-dependent | ✅ Uses injected `fakeEnv` with controlled keys |

---

## Remaining Findings

### Pre-existing bug — outside M7 scope, but exposed by the new code path

**`Ed25519PrivateKey.publicKeyBytes()` generates an unrelated key**

`SigningKey.kt`:
```kotlin
fun publicKeyBytes(): ByteArray {
    val keyGen = KeyPairGenerator.getInstance("EdDSA", "BC")
    keyGen.initialize(NamedParameterSpec("Ed25519"))
    return keyGen.generateKeyPair().public.encoded  // fresh random key — NOT derived from this.seed
}
```

Every call returns the public key of a freshly generated random pair, unrelated to `this.seed`. `Receipts.__init__` calls `signingKey.publicKeyBytes()` to write `public.key` — so the stored public key will never match the private key used for signing. All `hebe memory show` verification will fail. This bug predates M7 but is now reachable via `McpServeCommand`. Needs a fix in the security module before the receipt chain is trusted.

---

### Minor

**1. First `McpClientManagerTest` still reads real `System.getenv()`**

`McpClientManagerTest.kt:31` — the first test calls `manager.buildEnvWithSecrets(serverConfig.envSecrets)` without passing `systemEnv`. The assertion `leakingKeys shouldBe emptyList()` depends on the actual CI environment containing no variables matching the sensitive patterns. If the CI has `GITHUB_TOKEN` or `OPENAI_API_KEY` set, the assertion actually exercises the filter; if not, it's trivially true. The third test is deterministic and proves the filtering behaviour — this first test is redundant but harmless.

Fix: pass `systemEnv = mapOf("CUSTOM_KEY" to "safe", "PATH" to "/usr/bin")` to also make the first test environment-independent.

**2. Mixed Java/Kotlin file APIs in `loadOrCreateSigningKey`**

`Main.kt:113-129` — reads use `kotlin.io.path` extensions (`keyFile.exists()`, `keyFile.readText()`), but writes use `java.nio.file.Files.createDirectories()` and `java.nio.file.Files.writeString()`. Consistent use of Kotlin extensions (`receiptsDir.createDirectories()`, `keyFile.writeText(...)`) would be cleaner, but this is a style nit with no correctness impact.

---

## Overall Assessment

M7 is complete and ready to merge.

The architecture is clean: `McpDispatcherFactory` is a well-named home for the lightweight no-ops; `runMcpStdioServer` accepts a fully configured registry and dispatcher without mutating either; `PolicyChain.standard()` ensures workspace boundary and command policies are enforced for MCP-initiated tool calls; receipts use the shared persistent key at `~/.hebe/receipts/private.key` (base64, create if absent).

The `publicKeyBytes()` bug is the only item that could cause runtime failures, and it is a pre-existing defect in the security module, not introduced by M7. It should be tracked separately.

**Required before shipping:** fix `Ed25519PrivateKey.publicKeyBytes()` to derive the public key from `this.seed` rather than generating a random pair.
**Optional:** make the first `McpClientManagerTest` environment-independent.
