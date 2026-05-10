# Code Review 002 — Review-001 Follow-up

Reviewed against `tasks-review-001.md`. Items R01-01 through R01-33.

**Verdict: 27 of 33 items are fixed. 4 items remain unresolved. 4 new defects introduced.**

---

## Fixed Items (27/33)

R01-01 ✓ `HebeException` discriminating fields added  
R01-02 ✓ `WorkspacePath` conflict resolved — `api.workspace.WorkspacePath` is now the canonical type; `memory.workspace.WorkspacePath` is a forwarding typealias  
R01-03 ✓ `ObserverEvent.PluginLoaded` added  
R01-04 ✓ `kotlinx-datetime` explicit dependency in `api/build.gradle.kts`  
R01-05 ✓ `HebeConfig` schema matches architecture §7 exactly  
R01-06 ✓ `ConfigLoader` updated for new schema  
R01-07 ✓ Old config classes removed  
R01-11 ✓ `pf4j` dependency declared in `plugin-api/build.gradle.kts`  
R01-12 ✓ `HebePlugin` is now `abstract class ... : org.pf4j.Plugin(wrapper)`  
R01-13 ✓ `PluginHost` interface matches spec  
R01-14 ✓ `SecretHandle` is `@JvmInline value class SecretHandle(val name: String)`  
R01-15 ✓ `Permission` sealed interface has `HttpClient`, `EnvRead`, `Secret(name)` variants  
R01-16 ✓ `Capability` is `enum class { Tool, Skill }`  
R01-17 ✓ (partial — see N03) `LogbackObserver` implements `Observer`  
R01-20 ✓ All 16 CLI subcommand stubs registered and print "not yet implemented"  
R01-21 ✓ `memory_chunks_vec` virtual table added to `V2__memory.sql`  
R01-22 ✓ `SqliteVecExtension.load()` called in `DbFactory.open()` and `openInMemory()`  
R01-23 ✓ `Indexer` accepts `EmbeddingProvider`, calls `embed()`, inserts into `memory_chunks_vec`  
R01-24 ✓ `vecQuery()` passes `queryVec` to SQL via `vec_distance_cosine(mcv.embedding, ?)`  
R01-25 ✓ `appendMessage()` INSERT uses `OR IGNORE` and supplies all NOT NULL columns  
R01-26 ✓ `loadContext()` uses literal column indices 1–4, not the INSERT parameter constants  
R01-27 ✓ `ChunkerPropertyTest` covers determinism, coverage, size, last-chunk, and index contiguity  
R01-28 ✓ Heading break detection now maps token position back to a line and checks `line.trimStart().startsWith("#")`  
R01-29 ✓ `LruResponseCacheTest` covers hit, miss, and eviction  
R01-30 ✓ `CachedEmbeddingProviderTest` exists (eviction test is weak — see N04)  
R01-32 ✓ CI `build` job runs `./gradlew shadowJar` gated on `refs/heads/master`  
R01-33 ✓ CI push branch filter changed from `[main]` to `[master]`  

---

## Unresolved Items (4/33)

### R01-08 / R01-09 — Secrets do not persist across JVM restarts

`MacKeychainImpl.get()` returns only from an in-memory `cache` field; it never reads back from the OS keychain. After a JVM restart, `cache` is empty and `get()` returns null for every key even though the `security` CLI stored the value. `LinuxKeychainImpl` has the same bug.

`PassphraseBasedImpl` uses `KeyStore.getInstance("JCEKS").apply { load(null) }` — an in-memory KeyStore that is never written to disk. The 32-byte `masterKey` is persisted in `master.passphrase`, but the encrypted secret entries in the KeyStore are not saved anywhere. Every JVM restart starts with an empty store.

- [ ] **R01-08a** `MacKeychainImpl.get()` must read from the keychain, not from `cache`. Use `security find-generic-password -a <key> -w` to retrieve, and fall back to `null` on non-zero exit.
- [ ] **R01-08b** `PassphraseBasedImpl` must persist the KeyStore to a file (e.g. `<dataDir>/.hebe/secrets.jceks`) and load it from that file on construction. Call `keyStore.store(outputStream, passphrase)` on every `set()` and `delete()`.

### R01-10 — `SecretStoreProvider` and `SecretLookup` still diverge

`api.SecretLookup.secret(name: String): String?` and `config.SecretStoreProvider.get(key: String): ByteArray?` are different interfaces with different method names and return types. Any module that needs to bridge them must do its own conversion.

- [ ] **R01-10** Either make `OsKeychainSecretStore` implement `SecretLookup` directly (converting `ByteArray` to `String` on the way out), or document why two parallel secret-access interfaces are required and expose an adapter in `config`.

### R01-18 / R01-19 — `MutationFunnelRule` still incorrect; no tests

Two bugs remain:

1. `"ToolDispatcher"` and `"dispatch"` appear in `sideEffectMethods`. `ToolDispatcher.dispatch()` is the **intended** call path; flagging it is self-defeating.
2. `hasDispatchExempt()` checks `function.docComment` (KDoc `/** */` blocks) and annotation entries. A `// dispatch-exempt: reason` comment is a regular line comment and is not part of the PSI doc-comment node. The exemption mechanism silently never fires.
3. No test files exist in `modules/detekt-rules/src/test/`.

- [ ] **R01-18a** Remove `"ToolDispatcher"` and `"dispatch"` from `sideEffectMethods`.
- [ ] **R01-18b** Change `hasDispatchExempt()` to read the preceding single-line comments from the PSI tree (`KtPsiUtil` / `PsiElement.prevSibling`) rather than relying on `docComment`.
- [ ] **R01-19** Add unit tests: one positive case (direct side-effect call → rule fires) and one negative case (`// dispatch-exempt: reason` comment → rule passes).

### R01-31 — `MemoryStore.systemPrompt()` has no group-chat parameter

`api.MemoryStore.systemPrompt()` still takes no parameters. `SystemPromptAssembler.assemble(isGroup: Boolean = false)` has the group path implemented, but it is always called as `assemble()` (i.e. `isGroup = false`). Group-chat detection from M1.T13 can never reach the assembler through the public interface.

- [ ] **R01-31** Change `MemoryStore.systemPrompt()` to `systemPrompt(isGroup: Boolean = false)` (or accept a `ConversationContext`). Update `SqliteMemoryStore` to pass the flag through, and update call sites.

---

## New Defects

### N01 — `plugin-api/build.gradle.kts` has duplicate blocks

`plugin-api/build.gradle.kts` declares `dependencies {}` twice and applies the serialization plugin twice (once via `alias(libs.plugins.kotlin.serialization)` in the `plugins {}` block, and again via `apply(plugin = "org.jetbrains.kotlin.plugin.serialization")`). Gradle resolves this without error today, but it will cause warnings and may behave unexpectedly after upgrades.

- [ ] **N01** Remove the second `apply(plugin = ...)` line and the duplicate `dependencies {}` block.

### N02 — sqlite-vec native binaries not bundled; all DB operations will fail

`SqliteVecExtension.load()` looks up the binary at `classpath:/native/sqlite-vec/<os>-<arch>/vec0.{dylib,so}`. No such resources exist in the repository. The loader calls `error(...)` (throws `IllegalStateException`) when the resource is not found, with no fallback. Because `DbFactory.open()` and `DbFactory.openInMemory()` call `load()` unconditionally, **every test that instantiates a `DbFactory`** (`DbTest`, `IndexerTest`, `MemoryRoundTripTest`, `HygieneIntegrationTest`) will fail at runtime with:
```
IllegalStateException: sqlite-vec not found for darwin-aarch64 at /native/sqlite-vec/darwin-aarch64/vec0.dylib
```

- [ ] **N02** Download the sqlite-vec release binaries for the required platforms and bundle them under `modules/memory/src/main/resources/native/sqlite-vec/<os>-<arch>/`. Add a Gradle task (or a `./gradlew :modules:memory:downloadSqliteVec` step) to automate this, and document it in `CLAUDE.md` / the dev manual. Alternatively, add a `try/catch` in `DbFactory` that logs a warning and skips vec operations when the binary is absent (degraded mode), so unit tests can run on CI without native deps.

### N03 — `LogbackObserver` logs events as `toString()`, not structured JSON

`LogbackObserver.event()` logs `e.toString()` via `logger.info { msg }`. The architecture §21 requires structured JSON fields: `ts`, `level`, `logger`, `msg`, `session_id?`, `turn_id?`, `tool?`, `plugin?`, `channel?`, `trace_id?`, `span_id?`. Even with `logstash-logback-encoder` on the classpath, none of those contextual fields are written to MDC or a JSON encoder, so the fields will never appear in log output.

- [ ] **N03** In `event()`, extract the event-specific fields (e.g. `sessionId`, `turnId`, `tool` name) and write them into the SLF4J MDC before calling the logger, then clear the MDC after. Or switch to a `StructuredArguments` / `KeyValueArguments` approach that logstash-logback-encoder can pick up. The `LogEvent` written to the ring buffer should also carry those fields for the `doctor` command.

### N04 — `CachedEmbeddingProvider` eviction test does not verify delegate is re-called

`CachedEmbeddingProviderTest.eviction removes LRU entry` fills the cache to capacity, then calls `embed(["text1"])` again. Because `MockEmbeddingProvider` is deterministic (SHA-256 seeded RNG), the returned vector for "text1" is the same whether it came from the cache or a fresh delegate call. The test asserts `oldest[0].shouldNotBe(FloatArray(mock.dim))` (i.e. the result is non-zero), which passes regardless of whether eviction occurred. The test does not prove eviction.

- [ ] **N04** Track delegate call count in a custom `EmbeddingProvider` stub (or use `mockk`). After eviction, assert that the delegate was called once more for "text1" than for the still-cached entries.
