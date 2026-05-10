# Code Review 003 — Review-002 Follow-up

Reviewed against `tasks-review-002.md`. Items R02-01 through R02-14.

**Verdict: 10 of 14 items fixed. 4 items remain unresolved or only partially fixed. 4 new defects introduced.**

---

## Fixed Items (10/14)

R02-01 ✓ Duplicate `dependencies {}` block and redundant `apply(plugin = ...)` removed from `plugin-api/build.gradle.kts`  
R02-03 ✓ `DbFactory` wraps `SqliteVecExtension.load()` in `tryLoadVecExtension()` with try/catch; prints a warning and continues instead of crashing  
R02-04 ✓ `MacKeychainImpl.get()` now reads from the OS keychain via `security find-generic-password -a <key> -w`, with in-memory cache as a read-through layer  
R02-05 ✓ `PassphraseBasedImpl` persists the KeyStore to `<dataDir>/.hebe/secrets.jceks`; loads from it on startup; calls `saveKeyStore()` after every `set()` and `delete()`  
R02-06 ✓ `OsKeychainSecretStore` now implements both `SecretStoreProvider` and `SecretLookup`; `secret(name)` calls `get()` and converts `ByteArray` to `String(UTF-8)` via `runBlocking`  
R02-07 ✓ `LogbackObserver.event()` populates SLF4J MDC with `ts`, `level`, `logger`, `sessionId`, `turnId`, `tool`, `pluginId` before logging  
R02-08 ✓ `LogEvent` has `sessionId`, `turnId`, `tool`, `pluginId`, `channel` nullable fields  
R02-09 ✓ `"ToolDispatcher"` and `"dispatch"` removed from `sideEffectMethods`  
R02-12 ✓ `MemoryStore.systemPrompt(isGroup: Boolean = false)` added to the interface  
R02-13 ✓ `SqliteMemoryStore.systemPrompt(isGroup)` passes the flag to `systemPromptAssembler.assemble(isGroup)`  

---

## Unresolved / Partially Fixed Items (4/14)

### R02-02 — sqlite-vec native binaries still absent

No native binaries exist under `modules/memory/src/main/resources/native/`. The degraded-mode fallback (R02-03) prevents crashes, but vector search silently produces no results in every environment including production. This has been deferred twice; it needs to be done before M2 ships any feature that depends on semantic retrieval.

- [ ] **R02-02** Bundle sqlite-vec release binaries for `darwin-aarch64`, `darwin-x86_64`, `linux-aarch64`, `linux-x86_64` under `modules/memory/src/main/resources/native/sqlite-vec/<os>-<arch>/`. Add a `just` recipe or Gradle task to automate the download and document it.

### R02-10 — `hasDispatchExempt()` still does not detect `// dispatch-exempt:` comments

The fix walks `function.prevSibling` but does not skip whitespace PSI nodes. In practice, `function.prevSibling` is always a `PsiWhiteSpace` (the newline between the comment and the `fun` keyword). Its text is `"\n"`, which fails the `startsWith("//")` check, so the loop breaks immediately and the actual comment node is never reached. The exemption mechanism silently never fires for any function.

- [ ] **R02-10** When walking `prevSibling`, skip nodes whose `text.isBlank()` (whitespace and newlines). Only break when a non-blank, non-comment sibling is encountered:
  ```kotlin
  var prev = function.prevSibling
  while (prev != null) {
      val text = prev.text
      if (text.isBlank()) { prev = prev.prevSibling; continue }
      if (text.trimStart().startsWith("//") && text.contains("dispatch-exempt")) return true
      break
  }
  ```

### R02-11 — Missing `// dispatch-exempt:` test case

`MutationFunnelRuleTest` has three tests (positive case, `ToolDispatcher.dispatch` passes, pure function passes) but no test for the `// dispatch-exempt:` exemption path. Because R02-10 is also broken, adding this test first will expose the bug.

- [ ] **R02-11** Add a test:
  ```kotlin
  "function annotated with dispatch-exempt comment is not flagged" {
      val code = """
          package com.example
          import java.nio.file.Files
          import java.nio.file.Paths
          // dispatch-exempt: seeding initial workspace
          fun seedWorkspace(path: String) {
              Files.delete(Paths.get(path))
          }
      """.trimIndent()
      val findings = MutationFunnelRule().lint(code)
      findings.any { it.issue.id == "MutationFunnel" } shouldBe false
  }
  ```

### R02-14 — `CachedEmbeddingProvider` eviction test has no assertion

The test uses `mockk` but never calls `verify`. After filling the cache to capacity and evicting `"text1"` by adding `"text4"`, it calls `cached.embed(listOf("text1"))` but makes no assertion about how many times the delegate was called. The test passes trivially because it contains no assertions at all after the mock setup.

- [ ] **R02-14** Add a `coVerify` block after the final `embed` call:
  ```kotlin
  coVerify(exactly = 2) { delegate.embed(listOf("text1")) }
  // once on first access, once after eviction forces a re-fetch
  ```

---

## New Defects

### N01 — `MacKeychainImpl.set()` silently fails on update

`security add-generic-password` exits non-zero with "duplicate item" if a secret with the same `-a` attribute already exists. The current code does not pass `-U` (update) and does not check the exit code. A second `set("key", newValue)` call writes the new value to the in-memory `cache` but leaves the stale value in the keychain. After a JVM restart, `get()` reads the stale value from the keychain.

- [ ] **N01** Change the command in `MacKeychainImpl.set()` to always delete the existing item first (with `security delete-generic-password -a <key>`, ignoring non-zero exit) and then add, or pass `-U` to `add-generic-password` to overwrite.

### N02 — `LogEvent.channel` field is never populated

`LogEvent` gained a `channel` field (R02-08 ✓) but `LogbackObserver.event()` never sets it — no `ObserverEvent` subtype extracts a channel value, and the MDC does not receive a `"channel"` key. The field exists but is always `null`, making it invisible to the `doctor` command and log consumers.

- [ ] **N02** If any `ObserverEvent` variant carries channel information (e.g. `Channel.channel`), extract it and write it to both `LogEvent.channel` and `MDC.put("channel", ...)`. If no current event carries channel context, add a `channel: String?` field to the relevant events (e.g. `TurnStart`) so the information is available.

### N03 — `MDC.clear()` in `LogbackObserver.event()` erases inherited context

`MDC.clear()` removes every key from the current thread's MDC map, including any context established by an outer scope (e.g. an HTTP request filter or a coroutine's `MDCContext`). If `event()` is called from within a request-handling coroutine, the request-level MDC keys (request-id, user-id, etc.) are silently lost for all subsequent log statements in that thread slot.

- [ ] **N03** Replace `MDC.clear()` with individual `MDC.remove(key)` calls for only the keys this method put, or save and restore the previous MDC map:
  ```kotlin
  val previous = MDC.getCopyOfContextMap() ?: emptyMap()
  try {
      MDC.put(...); ...
      logger.info { msg }
  } finally {
      MDC.setContextMap(previous)
  }
  ```

### N04 — `@file:Suppress("detekt:all")` in `SecretStore.kt` is too broad

`SecretStore.kt` carries `@file:Suppress("detekt:all")` which disables every detekt rule for the entire file. This prevents the mutation-funnel rule (and all other custom rules) from ever reporting anything in this file. If the suppression is needed for specific Detekt warnings, it should be narrowed to the exact rule IDs at the declaration level.

- [ ] **N04** Replace `@file:Suppress("detekt:all")` with targeted suppressions at the specific declaration that needs them (e.g. `@Suppress("TooGenericExceptionCaught")` at the `catch` blocks) and remove the file-level blanket suppression.
