# Tasks — Review 002 Follow-up

Derived from `review-002.md`. Fix these before starting M2.
Items are ordered so that foundational fixes unblock later ones.

---

## 1. `plugin-api` module — build file cleanup

- [ ] **R02-01** Remove the duplicate `dependencies {}` block and the redundant `apply(plugin = "org.jetbrains.kotlin.plugin.serialization")` line from `modules/plugin-api/build.gradle.kts`. The serialization plugin is already applied via `alias(libs.plugins.kotlin.serialization)` in the `plugins {}` block.

---

## 2. `memory` module — sqlite-vec native binaries (critical)

- [ ] **R02-02** Bundle the sqlite-vec native binaries for all supported platforms under `modules/memory/src/main/resources/native/sqlite-vec/<os>-<arch>/`:
  - `darwin-aarch64/vec0.dylib`
  - `darwin-x86_64/vec0.dylib`
  - `linux-aarch64/vec0.so`
  - `linux-x86_64/vec0.so`

  Add a Gradle task (e.g. `:modules:memory:downloadSqliteVec`) or a `just` recipe to automate downloading the correct release artifacts, and document the step in `CLAUDE.md`.

- [ ] **R02-03** As a fallback for CI environments without native deps, wrap the `SqliteVecExtension.load(conn)` call in `DbFactory` with a try/catch. On failure, log a warning and continue — the vec leg of RRF will degrade to FTS-only rather than crashing the entire database open.

---

## 3. `config` module — secrets store persistence

- [ ] **R02-04** Fix `MacKeychainImpl.get()` to read from the OS keychain rather than the in-memory cache. Use `security find-generic-password -a <key> -w` to retrieve the stored value; return `null` if the process exits non-zero or the key is not found. The `cache` field may be kept as a read-through cache but must not be the sole source of truth.

- [ ] **R02-05** Fix `PassphraseBasedImpl` to persist the KeyStore to disk. On construction, load from `<dataDir>/.hebe/secrets.jceks` if it exists (using the master key as the KeyStore password). After every `set()` and `delete()`, write the KeyStore back to that file via `keyStore.store(outputStream, passphrase)`. Set file permissions to `600`.

- [ ] **R02-06** Align `SecretStoreProvider` with `api.SecretLookup`. Either:
  - make `OsKeychainSecretStore` implement `SecretLookup` (converting `ByteArray` to `String(Charsets.UTF_8)` in the `secret()` method), or
  - add an explicit adapter class in `config` that wraps `SecretStoreProvider` and exposes `SecretLookup`, and document the reason for the two-interface design.

---

## 4. `observability` module — structured JSON logging

- [ ] **R02-07** In `LogbackObserver.event()`, populate SLF4J MDC with the event-specific fields before logging, and clear them after. Required fields per architecture §21:
  - All events: `ts` (epoch ms), `level`, `logger`, `msg`
  - `TurnStart` / `TurnEnd`: `session_id`, `turn_id`
  - `ToolDispatched`: `turn_id`, `tool`
  - `LlmCall`: `turn_id`
  - `ApprovalRequested` / `ApprovalResolved`: `turn_id`, `tool`
  - `PluginLoaded`: `plugin`

  With `logstash-logback-encoder` configured, MDC entries appear automatically as top-level JSON fields. Use `MDC.put` / `MDC.remove` (or `MDC.putCloseable` with a try-with-resources block).

- [ ] **R02-08** Update the `LogEvent` data class (in the ring buffer) to carry the same contextual fields so the `doctor` command can display them. Add `sessionId`, `turnId`, `tool`, `pluginId`, and `channel` as nullable fields.

---

## 5. `detekt-rules` module — fix `MutationFunnelRule`

- [ ] **R02-09** Remove `"ToolDispatcher"` and `"dispatch"` from the `sideEffectMethods` set in `MutationFunnelRule`. These are the sanctioned call path and must never be flagged.

- [ ] **R02-10** Fix `hasDispatchExempt()` to detect regular line comments (`// dispatch-exempt: <reason>`), not KDoc blocks. Walk backwards through `function.prevSibling` (skipping whitespace) looking for a `PsiComment` whose text starts with `// dispatch-exempt:`. KDoc `docComment` nodes will not contain these comments.

- [ ] **R02-11** Add a unit test file `modules/detekt-rules/src/test/kotlin/com/hebe/detektrules/MutationFunnelRuleTest.kt` with:
  - A positive case: a function that calls a side-effect method directly (e.g. `Files.delete(...)`) outside `ToolDispatcher.dispatch` → rule fires.
  - A negative case: the same function annotated with `// dispatch-exempt: reason` → rule passes.

---

## 6. `memory` module — group-chat system prompt

- [ ] **R02-12** Change `MemoryStore.systemPrompt()` in `modules/api/src/main/kotlin/com/hebe/api/MemoryStore.kt` to:
  ```kotlin
  suspend fun systemPrompt(isGroup: Boolean = false): String
  ```

- [ ] **R02-13** Update `SqliteMemoryStore.systemPrompt()` to pass the flag through:
  ```kotlin
  override suspend fun systemPrompt(isGroup: Boolean): String =
      systemPromptAssembler.assemble(isGroup)
  ```
  Update call sites and existing tests to use the new signature (default `false` keeps them source-compatible).

---

## 7. `memory` module — strengthen `CachedEmbeddingProvider` eviction test

- [ ] **R02-14** Rewrite the eviction test in `CachedEmbeddingProviderTest` to use a call-counting stub (or `mockk`) instead of `MockEmbeddingProvider`. After filling the cache to capacity and evicting the oldest entry, re-request that entry and assert the delegate was called one additional time. The current test cannot distinguish a cache hit from a cache miss because `MockEmbeddingProvider` is deterministic.
