# Code Review 001 — M0 + M1

Reviewed against:
- `docs/plan/v1-tasks.md` (task list and acceptance criteria)
- `docs/plan/v1-architecture.md` (contracts, schemas, types)
- `tasks-stage-00.md` / `tasks-stage-01.md` (stage tracking)

Verdict: **M0 is not done; M1 is partially done.** Several modules exist in name but not in substance, and two integration bugs in `SqliteMemoryStore` will cause runtime failures.

---

## M0 — Foundations

### M0.T1 — Gradle multi-module skeleton
- [x] All 22 modules declared in `settings.gradle.kts`, matching architecture §1 exactly.
- [x] `build-logic` with `hebe.base`, `hebe.library`, `hebe.application` conventions.
- [ ] **Kotlin 2.3.20 in `libs.versions.toml` vs 2.2.x in spec** — minor, but an undocumented bump.

### M0.T2 — `libs.versions.toml`
- [x] All library entries present.
- [x] `logstash-logback-encoder` included (good addition, not in spec table but implied by JSON logging).
- [ ] **`kotlinx-datetime` missing** from `api/build.gradle.kts` — the `api` module references `kotlin.time.Instant` but doesn't declare the dependency directly (relies on transitive).

### M0.T3 — Detekt + ktlint baseline
- [x] Both configured in `build-logic/hebe.base.gradle.kts`.
- [x] Wired to the `check` task.
- [x] Detekt config at `config/detekt/detekt.yml`.

### M0.T4 — CI pipeline
- [ ] **`shadowJar` missing from main-branch push** — acceptance says "main CI: same + `./gradlew shadowJar`". The current `ci.yml` only runs `check` for all triggers.
- [ ] **Branch filter is `main`, not `master`** — repo's main branch is `master` (see gitStatus).

### M0.T5 — `api` module — kernel ABI types
- [x] All core interfaces compile (`LlmProvider`, `Tool`, `Channel`, `MemoryStore`, `Observer`, `HandleOutcome`, `Submission`, `HebeException`).
- [x] `@Serializable` + `@SerialName` annotations throughout.
- [ ] **`HebeException` is missing discriminating fields** — architecture §20 specifies `Provider(val retriable: Boolean, …)`, `Tool(val tool: String, val retriable: Boolean, …)`, `Plugin(val pluginId: String, …)`, `Channel(val channel: String, …)`. The implementation omits all of these, making the error-handling rules in §20 (retry on `retriable=true`, channel-specific degradation) unimplementable without an API change.
- [ ] **`StreamEvent.Error.cause` is `String`, not `Throwable`** — spec says `Throwable`. This is understandable (Throwable can't be serialized), but it's an undocumented deviation and callers will have to re-construct errors from strings.
- [ ] **`WorkspacePath` type conflict** — `api/Common.kt` defines `typealias WorkspacePath = String`. The `memory` module defines a separate `value class WorkspacePath(val value: String)`. `ToolContext.workspace: WorkspacePath` therefore uses the String alias, while `WorkspaceFs` uses the value class. These are different types with the same name; future consumers of `ToolContext` will hit a type mismatch when they try to pass the workspace to `WorkspaceFs`.
- [ ] **`ObserverEvent.PluginLoaded` missing, `MemoryDbReady` added** — the architecture §11 lifecycle mentions emitting `ObserverEvent.PluginLoaded`; it's absent. A `MemoryDbReady` event was added instead; this is not in the spec.

### M0.T6 — `plugin-api` module
- [ ] **`HebePlugin` is an `interface`, not an abstract class extending `org.pf4j.Plugin`** — acceptance criterion: "`HebePlugin` extends `org.pf4j.Plugin`". The whole PF4J integration is absent from this module.
- [ ] **PF4J not declared as a dependency** — `plugin-api/build.gradle.kts` has no `pf4j` dep; the acceptance says "Compiles with only `api` + `pf4j` as deps".
- [ ] **`PluginHost` interface does not match spec** — architecture §4 specifies `fun http(): GatedHttpClient`, `fun env(name: String): String?`, `fun secret(name: String): SecretHandle?`, `val observer: Observer`, `val log: Logger`, `val pluginId`, `val manifest`. The implementation has `capabilities`, `secrets`, `httpClient`, `registerCapability()`, `requestPermission()`, `createScopedScope()` — a completely different API contract. Downstream consumers (M6) will not be able to use this as specified.
- [ ] **`SecretHandle` is an interface with `value: ByteArray`** — spec defines it as `@JvmInline value class SecretHandle(val name: String)` (an opaque handle, never the raw value). The implementation exposes the raw value, violating the security model.
- [ ] **`GatedHttpClient` adds `put` and `delete`** — not in spec for v1. Not harmful, but undocumented.
- [ ] **`PluginManifest`, `Capability`, `Permission` types don't match spec** — `Capability` should be an enum; `Permission` should be a sealed interface with `HttpClient`, `EnvRead`, and `Secret(name)` variants.

### M0.T7 — `observability` module
- [x] `RingBuffer<T>` and `LogRingBuffer` are implemented correctly.
- [x] `LogEvent` / `LogLevel` / `LogSlot` infrastructure present.
- [ ] **`LogbackObserver` is an empty class** — the class body is completely empty. It does not implement the `Observer` interface from `api`. The acceptance criterion ("LogbackObserver produces structured JSON with required fields; ring buffer for `doctor`") is not met. This module is a stub.

### M0.T8 — `config` module — TOML schema + loader
- [x] `ConfigLoader` / `ConfigResult` / `ConfigDiagnostic` pattern is clean; row/col errors work.
- [ ] **`HebeConfig` schema does not match architecture §7** — the spec defines `[hebe]`, `[llm]` (base_url, api_key_secret, default_model, embedding_model, embedding_dim), `[autonomy]` (level), `[security]` (forbidden_paths, allowed_command_globs, http_allowlist_domains, plugin_signature_mode), `[channels.cli/web/telegram]`, `[plugins]` (registry, auto_pull, publisher_keys), `[mcp.server/client]`, `[scheduler]` cron expressions. The implementation has `ObservabilityConfig`, `SecurityConfig` with `mfaRequired`, a generic `ChannelConfig`, and no LLM config at all. Every downstream module that needs the LLM endpoint, autonomy level, or channel config will either build its own config or diverge further.
- [ ] **LLM provider config entirely absent** — `ProviderConfig` only has `defaultModel`, `fallbackModel`, `maxConcurrentRequests`. No `baseUrl`, no `apiKeySecret`, no `embeddingModel`. M2 (`OpenAiCompatProvider`) cannot be wired to this config.

### M0.T9 — `config` — secrets store
- [x] `AeadEncryptor` (AES-256-GCM) is implemented correctly.
- [ ] **`OsKeychainSecretStore` uses `KeyStore.getDefaultType()` (JKS), not the OS keychain** — this creates an in-memory keystore, not macOS Keychain / Linux secret-service / Windows Credential Manager. Secrets will not survive JVM restart.
- [ ] **`OsKeychainSecretStore.set()` ignores the provided `value: ByteArray`** — it generates a random AES key and stores that instead of the provided value. The function is functionally broken: `put("my_secret", secretBytes)` stores a random key, not `secretBytes`. `get("my_secret")` returns that random key, not the original value.
- [ ] **`SecretStoreProvider` interface has different signature from `api.SecretLookup`** — further divergence from the API surface.

### M0.T10 — `detekt-rules` — mutation-funnel rule
- [ ] **Rule implements wrong check** — the plan says: "Rule fires on a synthetic positive test; passes on `// dispatch-exempt: <reason>`-annotated calls." The purpose is to enforce that all side-effects go through `ToolDispatcher.dispatch`. The implementation instead detects mutable collection types in function signatures and `var` properties — a general immutability rule with no relation to the dispatcher funnel. The `// dispatch-exempt:` annotation is not implemented at all.

### M0.T11 — `cli-app` skeleton
- [x] `clikt` wired; `main()` entry point exists.
- [ ] **Acceptance criterion not met: "each [v1 subcommand] prints 'not yet implemented'"** — the implementation has only `HebeCLI` (prints a version string) and an unregistered `VersionCommand`. There are no stubs for `run`, `mcp serve`, `plugin install/list/remove`, `doctor`, `service install/start/stop/uninstall`, `status`, `completion`, `onboard`, `estop`, `memory show`.

---

## M1 — Memory

### M1.T1 — SQLite + Flyway runner
- [x] `DbFactory.open(path)` runs Flyway migrations; `DbFactory.openInMemory()` for tests.
- [x] Idempotent on re-open (tested).
- [ ] **WAL mode set twice** — the JDBC URL already includes `journal_mode=WAL`; the post-open PRAGMAs set it again. Harmless but noisy.
- [ ] **`MemoryDbReady` observer event emitted twice** — once in `DbFactory.open()` with real migration info, and once in `SqliteMemoryStore.init {}` with `(null, 0)`. The second emission is redundant and misleading.

### M1.T2 — Migration files V1–V5
- [x] V1–V5 match the architecture §5 DDL exactly.
- [x] V2 adds FTS triggers (not in spec DDL, but necessary and correct).
- [x] V6 adds `category` column — anticipated by M1.T14; correct.
- [ ] **`memory_chunks_vec` virtual table is missing from V2** — the spec V2 DDL includes `CREATE VIRTUAL TABLE memory_chunks_vec USING vec0(...)`. The actual V2 migration omits it. Vector search cannot function without this table; every `Searcher.vecQuery()` call will fail at runtime.

### M1.T3 — sqlite-vec extension loader
- [x] `SqliteVecExtension.load(connection)` is implemented with correct platform detection and temp-file extraction.
- [ ] **Extension is never loaded** — `DbFactory.open()` and `DbFactory.openInMemory()` never call `SqliteVecExtension.load()`. The extension must be loaded on every connection before any `vec0` operations.
- [ ] **`memory_chunks_vec` table missing** — see M1.T2.

### M1.T4 — `WorkspaceFs` API
- [x] `read/write/list/append/delete/exists` all implemented.
- [x] Symlink escape protection via `toRealPath()` in `resolveForRead`.
- [x] Atomic write via tmp file + `ATOMIC_MOVE`.
- [x] `HebeException.Security` thrown on path escapes.
- [ ] **`WorkspacePath` type conflict with `api` module** — see M0.T5. `WorkspaceFs` accepts `memory.workspace.WorkspacePath` (value class); `ToolContext.workspace` is typed to `api.WorkspacePath` (String alias). These types are incompatible.
- [ ] **`list()` only walks one level deep (`Files.walk(base, 1)`)** — may be intentional but not documented. The spec doesn't specify depth, so this could be a surprise.

### M1.T5 — Workspace seeding
- [x] All five seed files seeded from classpath resources if missing.
- [x] All four directories created.
- [x] Idempotent.
- [x] `completeOnboarding()` deletes `BOOTSTRAP.md`.

### M1.T6 — Chunker
- [x] Pure function; `ChunkerConfig` defaults (800 words, 15%, 50 min) match spec.
- [x] Small-doc fast path (< minWords → single chunk).
- [x] Last-chunk merge when tail is below minWords.
- [ ] **Heading break detection doesn't fire in practice** — `HEADING_PATTERN = Regex("^#{1,6}\\s+")` is tested against a single whitespace-split token (e.g. `"##"`). A markdown heading `## Introduction` splits into two tokens `["##", "Introduction"]`; the pattern requires the `#` followed by a space, but a single token is never `"## Introduction"`. The break logic will silently never trigger.
- [ ] **No property tests** — acceptance says "Pure function with property tests; deterministic output". Only integration-level table-existence tests exist.

### M1.T7 — `EmbeddingProvider` trait + impls
- [x] `EmbeddingProvider` interface (`model`, `dim`, `embed()`).
- [x] `MockEmbeddingProvider` uses seeded RNG for deterministic output.
- [x] `OpenAiCompatEmbeddingProvider` batches requests; reads model from constructor.

### M1.T8 — LRU `CachedEmbeddingProvider`
- [x] LRU eviction via `LinkedHashMap` access-order.
- [x] SHA-256 cache key.
- [x] Coroutine-safe via `Mutex`.
- [ ] **No unit tests** — acceptance says "Cache hit / miss / eviction tests pass".

### M1.T9 — Indexer: doc → chunks → FTS + vec rows
- [x] Upserts `memory_docs`, inserts `memory_chunks` in a transaction.
- [x] Idempotent via SHA-256 hash comparison.
- [x] FTS index maintained via triggers (from V2).
- [ ] **`memory_chunks_vec` is never populated** — the indexer inserts into `memory_chunks` and relies on FTS triggers, but there is no code to insert embeddings into `memory_chunks_vec`. Acceptance: "Writing a doc populates `memory_chunks`, `memory_chunks_fts`, `memory_chunks_vec` consistently."
- [ ] **Embedding computation absent from indexer** — the indexer takes no `EmbeddingProvider`. Embeddings are supposed to be stored in `memory_chunks.embedding` (BLOB) and synced to `memory_chunks_vec`, but neither column is populated.

### M1.T10 — RRF retrieval (`MemoryStore.search`)
- [x] `Rrf.fuse()` correctly implements RRF with `k₀=60`; `HitSource.Both` set correctly.
- [x] `Searcher.search()` fuses FTS and vec results.
- [ ] **`vecQuery()` ignores the query vector** — `@Suppress("UNUSED_PARAMETER")` on `queryVec: FloatArray` is a tell. The SQL queries `memory_chunks_vec` with no vector constraint: `ORDER BY mcv.distance` without a `WHERE vec_distance_*(embedding, ?) < …` clause. The query will either error at runtime (no distance reference point) or return arbitrary rows. This makes the entire vector leg of RRF non-functional.
- [ ] **`memory_chunks_vec` table missing** — see M1.T2; even if the query were fixed, the table doesn't exist.

### M1.T11 — Identity-files loader for `systemPrompt()`
- [x] Reads IDENTITY.md, MEMORY.md, HEARTBEAT.md.
- [x] Group-detection stub (`isGroup` param omits MEMORY.md).
- [x] Simple TTL cache.
- [ ] **Group-chat path not wired through `MemoryStore.systemPrompt()`** — `MemoryStore.systemPrompt()` (no params) always calls `assemble(isGroup=false)`. There is no way to pass group context through the spec-defined interface.

### M1.T12 — Hygiene scanner
- [x] Six default rules across Low/Medium/High severity.
- [x] High severity → `Reject`; lower → `Warn`.
- [x] Integration test verifies rejection of known injections.

### M1.T13 — Group-chat detection stub
- [ ] **Not wired** — see M1.T11. The `Conversation.metadata.group` check from the spec never reaches `SystemPromptAssembler`.

### M1.T14 — Memory category enum + storage column
- [x] `MemoryCategory` enum in `api` module.
- [x] V6 migration adds `category` column and index.
- [x] `appendDoc()` and `search()` accept categories.

### M1.T15 — LRU response cache
- [x] `LruResponseCache` and `responseCacheKey()` implemented.
- [ ] **No unit tests** — acceptance says "hit/miss/eviction tests pass".

### M1.T16 — `MemoryStore` integration tests
- [x] `MemoryRoundTripTest` and `HygieneIntegrationTest` exist and test meaningful paths.
- [ ] **Tests do not use real sqlite-vec** — acceptance: "Uses real sqlite-vec". `MockEmbeddingProvider` is used and sqlite-vec is not loaded, so the vec leg is silently skipped in every test.

---

## Cross-cutting bugs (critical)

- [ ] **`SqliteMemoryStore.appendMessage()` will throw at runtime** — the INSERT:
  ```sql
  INSERT INTO conversations(id) VALUES (?)
  ```
  violates `NOT NULL` constraints on `channel`, `user_id`, and `started_at`. Any call to `appendMessage()` will fail.

- [ ] **`SqliteMemoryStore.loadContext()` reads wrong columns** — constants `PARAM_MSG_ID=2`, `PARAM_ROLE=3`, `PARAM_CONTENT=4`, `PARAM_TS=6` are defined for INSERT parameter positions but reused as `ResultSet` column indices in the SELECT (`SELECT id, role, content, ts`). Column 2 is `role`, not `id`; column 6 doesn't exist. This will produce wrong data or throw an `IndexOutOfBoundsException`.

---

## Architecture observations

- [ ] **`WorkspacePath` dual definition** — having an opaque alias in `api` and a proper value class in `memory` for the same concept creates an impedance mismatch that every module bridging the two will have to paper over. Either the value class should replace the typealias throughout, or the value class should live in `api`.

- [ ] **Config schema drift is a structural risk** — `HebeConfig` is consumed by every module from M2 onward. Every module (LLM provider, channels, security, scheduler) will either build its own parallel config or diverge from the architecture. This needs to be fixed before M2 work starts.

- [ ] **plugin-api PF4J omission blocks M6** — M6.T1 (the PF4J spike) depends on M0.T6. Since M0.T6's acceptance criterion (`HebePlugin extends org.pf4j.Plugin`) is not met, M6 cannot start without revisiting this module first.

- [ ] **`LogbackObserver` empty class blocks M2 observability** — `HebeAgent`, `ToolDispatcher`, and `SqliteMemoryStore` all accept an `Observer`. Currently the only concrete implementation is `LogbackObserver`, which is an empty class. No spans, no events, no ring buffer for `doctor`.
