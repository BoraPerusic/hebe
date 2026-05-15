# Tasks — Review 016 (M7 MCP final)

---

## Required before shipping

- [ ] **Fix `Ed25519PrivateKey.publicKeyBytes()` in `security/receipts/SigningKey.kt`**
  The method generates a fresh random key pair instead of deriving the public key from `this.seed`. Every call returns an unrelated public key — the stored `public.key` will never verify receipts signed with the private key.
  Fix: derive the public key from the seed using BouncyCastle:
  ```kotlin
  fun publicKeyBytes(): ByteArray {
      val keyFactory = KeyFactory.getInstance("EdDSA", "BC")
      val privateKey = keyFactory.generatePrivate(
          java.security.spec.EdECPrivateKeySpec(NamedParameterSpec("Ed25519"), seed)
      )
      // BouncyCastle EdDSA private key exposes the public key via getPublicKey()
      val bcPrivKey = privateKey as org.bouncycastle.jcajce.provider.asymmetric.edec.BCEdDSAPrivateKey
      return bcPrivKey.publicKey.encoded
  }
  ```
  (Verify exact BouncyCastle cast — the key point is deriving from `seed`, not generating fresh.)

---

## Optional

- [ ] **Make first `McpClientManagerTest` environment-independent**
  `McpClientManagerTest.kt:31` — pass a controlled `systemEnv` to `buildEnvWithSecrets`:
  ```kotlin
  val fakeEnv = mapOf("CUSTOM_KEY" to "safe", "PATH" to "/usr/bin", "HOME" to "/home/user")
  val env = manager.buildEnvWithSecrets(serverConfig.envSecrets, systemEnv = fakeEnv)
  ```

- [ ] **Consistent file API in `loadOrCreateSigningKey`**
  Replace `java.nio.file.Files.createDirectories(receiptsDir)` and `java.nio.file.Files.writeString(keyFile, ...)` with Kotlin extensions:
  ```kotlin
  receiptsDir.createDirectories()
  keyFile.writeText(Base64.getEncoder().encodeToString(key.encode()))
  ```

---

## Deferred to `RunCommand` implementation

- [ ] Wire `McpClientManager.toolsForMessage()` into the `toolsProvider` lambda in `HebeAgent` construction (completes T5 per-turn filter in the live agent path).
- [ ] Reconnect with capped retries on MCP client transport disconnect (T4 lifecycle).
- [ ] MCP client connection status in `hebe doctor` (T4 lifecycle).
