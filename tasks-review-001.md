# Tasks — Review 001 follow-up

Derived from `review-001.md`. Fix these before starting M2.
Items are ordered so that foundational fixes unblock later ones.

---

## 1. `api` module — type fixes

- [ ] **R01-01** Add missing discriminating fields to `HebeException`:
  - `Provider(val retriable: Boolean, message: String, cause: Throwable? = null)`
  - `Tool(val tool: String, val retriable: Boolean, message: String)`
  - `Plugin(val pluginId: String, message: String, cause: Throwable? = null)`
  - `Channel(val channel: String, message: String, cause: Throwable? = null)`

- [ ] **R01-02** Remove `typealias WorkspacePath = String` from `api/Common.kt`. Move the `value class WorkspacePath` from `memory/workspace/WorkspacePath.kt` to the `api` module so that `ToolContext.workspace` and `WorkspaceFs` share the same type. Update all import sites.

- [ ] **R01-03** Add `ObserverEvent.PluginLoaded(val pluginId: String)` to `ObserverEvent` (required by M6 plugin lifecycle).

- [ ] **R01-04** Add `kotlinx-datetime` as an explicit `api` dependency in `modules/api/build.gradle.kts` (currently relied on transitively).

---

## 2. `config` module — schema rewrite

- [ ] **R01-05** Rewrite `HebeConfig` and all sub-configs to match architecture §7 exactly. Required sections:
  - `[hebe]` → `dataDir`, `logLevel`
  - `[llm]` → `baseUrl`, `apiKeySecret`, `defaultModel`, `embeddingModel`, `embeddingDim`
  - `[autonomy]` → `level` (ReadOnly | Supervised | Full | YOLO)
  - `[security]` → `forbiddenPaths`, `allowedCommandGlobs`, `forbiddenCommandGlobs`, `httpAllowlistDomains`, `pluginSignatureMode`
  - `[scheduler]` → `heartbeatCron`, `dailyDigestCron`, `summarisationCron`, `factExtractCron`
  - `[channels.cli]`, `[channels.web]`, `[channels.telegram]` as per spec
  - `[plugins]` → `registry`, `autoPull`, `publisherKeys`
  - `[mcp.server]` and `[mcp.client.servers]`

- [ ] **R01-06** Update `ConfigLoader` to parse the new schema and keep the row/col diagnostic behaviour.

- [ ] **R01-07** Delete `ConfigDefaults`, `ConfigFormats`, and all old sub-config data classes that no longer apply.

---

## 3. `config` module — secrets store rewrite

- [ ] **R01-08** Replace `OsKeychainSecretStore` with a real implementation:
  - macOS: use `security` CLI or JNA against `Security.framework`
  - Linux: DBus secret-service API or passphrase fallback
  - Passphrase fallback: PBKDF2-HMAC-SHA256, 600k rounds, key stored in a `chmod 600` file
  - `get(name)` must return the value that was passed to `set(name, value)` — the current implementation ignores the value entirely.

- [ ] **R01-09** Make the secrets store persist across JVM restarts (the current `KeyStore` is in-memory and loses all secrets on shutdown).

- [ ] **R01-10** Align the `SecretStore` interface in `config` with `SecretLookup` in `api` (or document clearly which interface each module depends on and why they differ).

---

## 4. `plugin-api` module — PF4J alignment

- [ ] **R01-11** Add `pf4j` as a dependency in `modules/plugin-api/build.gradle.kts`.

- [ ] **R01-12** Change `HebePlugin` from an `interface` to an `abstract class` extending `org.pf4j.Plugin(wrapper)`, matching architecture §4:
  ```kotlin
  abstract class HebePlugin(wrapper: org.pf4j.PluginWrapper) : org.pf4j.Plugin(wrapper) {
      open fun tools(host: PluginHost): List<Tool> = emptyList()
      open fun channels(host: PluginHost): List<Channel> = emptyList()
      open fun memoryStores(host: PluginHost): List<MemoryStore> = emptyList()
      open fun observers(host: PluginHost): List<Observer> = emptyList()
      open fun init(host: PluginHost) {}
      open fun teardown() {}
  }
  ```

- [ ] **R01-13** Rewrite `PluginHost` to match architecture §4:
  - `val pluginId: String`
  - `val manifest: PluginManifest`
  - `fun http(): GatedHttpClient`
  - `fun env(name: String): String?`
  - `fun secret(name: String): SecretHandle?`
  - `val observer: Observer`
  - `val log: org.slf4j.Logger`

- [ ] **R01-14** Change `SecretHandle` from an interface exposing raw bytes to `@JvmInline value class SecretHandle(val name: String)` — an opaque handle; the host resolves it at call time, never inside the plugin.

- [ ] **R01-15** Align `Permission` sealed interface to spec variants: `HttpClient`, `EnvRead`, `Secret(val name: String)`.

- [ ] **R01-16** Change `Capability` to the enum from the spec: `enum class Capability { Tool, Skill }`.

---

## 5. `observability` module — implement `LogbackObserver`

- [ ] **R01-17** Make `LogbackObserver` implement the `Observer` interface from `api`:
  - `fun event(e: ObserverEvent)` → write to `LogRingBuffer` and emit structured JSON via kotlin-logging / logback.
  - `fun span(name: String, attrs: Map<String, Any>): Span` → create an OTel span (or a no-op span if no endpoint configured); also push a log entry.
  - Required JSON fields per architecture §21: `ts`, `level`, `logger`, `msg`, `session_id?`, `turn_id?`, `tool?`, `plugin?`, `channel?`, `trace_id?`, `span_id?`.

---

## 6. `detekt-rules` module — fix `MutationFunnelRule`

- [ ] **R01-18** Replace the current rule body with the actual mutation-funnel check: flag any direct call to a method that causes a side-effect (tool invocation, DB write, file write) that is **not** inside `ToolDispatcher.dispatch` and is **not** annotated with `// dispatch-exempt: <reason>`. The current rule (checking mutable collection parameters) is unrelated.

- [ ] **R01-19** Add a unit test with a synthetic positive case (direct mutation → rule fires) and a negative case (`// dispatch-exempt: reason` → rule passes).

---

## 7. `cli-app` module — add subcommand stubs

- [ ] **R01-20** Register all v1 subcommands as Clikt `CliktCommand` stubs, each printing "not yet implemented". Required: `run`, `mcp serve`, `plugin install`, `plugin list`, `plugin remove`, `doctor`, `service install`, `service start`, `service stop`, `service uninstall`, `status`, `completion bash`, `completion zsh`, `completion fish`, `onboard`, `estop`, `memory show`.

---

## 8. `memory` module — vector search plumbing

- [ ] **R01-21** Add `memory_chunks_vec` to `V2__memory.sql`:
  ```sql
  CREATE VIRTUAL TABLE memory_chunks_vec USING vec0(
    doc_path TEXT,
    chunk_idx INTEGER,
    embedding FLOAT[1536]
  );
  ```

- [ ] **R01-22** Call `SqliteVecExtension.load(connection)` in `DbFactory.open()` and `DbFactory.openInMemory()` before running migrations, so the `vec0` module is available when the virtual table is created.

- [ ] **R01-23** Add `EmbeddingProvider` as a constructor parameter to `Indexer`. After inserting `memory_chunks` rows, call `embeddings.embed(chunkTexts)` and insert each resulting vector into `memory_chunks_vec`.

- [ ] **R01-24** Fix `Searcher.vecQuery()`: pass `queryVec` as a bind parameter to the sqlite-vec distance query. The SQL must include a `WHERE vec_distance_*(embedding, ?)` or equivalent clause. Remove the `@Suppress("UNUSED_PARAMETER")` suppression.

---

## 9. `memory` module — `SqliteMemoryStore` runtime bugs

- [ ] **R01-25** Fix `appendMessage()`: the INSERT into `conversations` must supply all `NOT NULL` columns (`channel`, `user_id`, `started_at`). Either pass them through `ConversationMessage` / `ToolContext`, or use `INSERT OR IGNORE` if the conversation row already exists and is managed elsewhere.

- [ ] **R01-26** Fix `loadContext()`: the constants `PARAM_MSG_ID`, `PARAM_ROLE`, `PARAM_CONTENT`, `PARAM_TS` are INSERT parameter indices (1-based bind positions) and must **not** be reused as `ResultSet` column indices for the SELECT. Use separate named constants or inline literals for the result column positions: `id` is column 1, `role` is column 2, `content` is column 3, `ts` is column 4.

---

## 10. `memory` module — remaining test gaps

- [ ] **R01-27** Add property tests for `Chunker`: at minimum — output is deterministic, all tokens from input appear in some chunk, no chunk exceeds `targetWords + overlapWords`, last chunk is ≥ `minWords` (unless doc is below minWords), chunk indices are contiguous from 0.

- [ ] **R01-28** Fix `Chunker.findHeadingBreakPoint()`: the heading pattern is matched against single whitespace-split tokens, so `"## Introduction"` (two tokens) will never match. Either rejoin a window of tokens around the candidate position and match on that, or scan the original text by line boundary rather than by token.

- [ ] **R01-29** Add unit tests for `LruResponseCache`: hit returns cached value, miss calls through, eviction removes LRU entry when over capacity.

- [ ] **R01-30** Add unit tests for `CachedEmbeddingProvider`: cache hit skips delegate, cache miss calls delegate, eviction occurs at capacity boundary.

- [ ] **R01-31** Wire `MemoryStore.systemPrompt()` to accept a group-chat flag (or a `ConversationContext`), so M1.T13 group-chat detection propagates through the interface rather than being stranded in the assembler.

---

## 11. CI pipeline

- [ ] **R01-32** Add a second job (or step, gated on `github.ref == 'refs/heads/master'`) that runs `./gradlew shadowJar` on pushes to `master`.

- [ ] **R01-33** Change the `push.branches` filter from `[main]` to `[master]` to match the actual default branch.
