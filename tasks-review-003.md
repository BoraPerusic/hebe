# Tasks — Review 003 Follow-up

Derived from `review-003.md`. Fix these before starting M2.
Items are ordered so that foundational fixes unblock later ones.

---

## 1. `memory` module — sqlite-vec native binaries

- [ ] **R03-01** Bundle sqlite-vec release binaries for all supported platforms under `modules/memory/src/main/resources/native/sqlite-vec/<os>-<arch>/`:
  - `darwin-aarch64/vec0.dylib`
  - `darwin-x86_64/vec0.dylib`
  - `linux-aarch64/vec0.so`
  - `linux-x86_64/vec0.so`

  Add a `just download-sqlite-vec` recipe (or a Gradle task) that fetches the correct release artifacts and places them there. Document the step in `CLAUDE.md` and the Developer Manual.

---

## 2. `detekt-rules` module — fix and test the exemption mechanism

- [ ] **R03-02** Fix `hasDispatchExempt()` in `MutationFunnelRule` to skip blank/whitespace PSI sibling nodes when walking backwards. The current implementation breaks immediately on the `PsiWhiteSpace` node that sits between the comment and the `fun` keyword, so `// dispatch-exempt:` comments are never detected. Replace the loop body with:
  ```kotlin
  var prev = function.prevSibling
  while (prev != null) {
      val text = prev.text
      if (text.isBlank()) { prev = prev.prevSibling; continue }
      if (text.trimStart().startsWith("//") && text.contains("dispatch-exempt")) return true
      break
  }
  ```

- [ ] **R03-03** Add a `// dispatch-exempt:` test case to `MutationFunnelRuleTest`:
  ```kotlin
  "function preceded by dispatch-exempt comment is not flagged" {
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

---

## 3. `memory` module — fix eviction assertion in `CachedEmbeddingProviderTest`

- [ ] **R03-04** Add a `coVerify` assertion to the eviction test in `CachedEmbeddingProviderTest`. After evicting `"text1"` and re-requesting it, assert the delegate was called a second time:
  ```kotlin
  io.mockk.coVerify(exactly = 2) { delegate.embed(listOf("text1")) }
  ```
  This proves the cache missed and the delegate was re-invoked rather than the test just happening to pass with no assertions.

---

## 4. `config` module — fix `MacKeychainImpl.set()` for updates

- [ ] **R03-05** `MacKeychainImpl.set()` must handle updates to existing secrets. `security add-generic-password` returns non-zero ("duplicate item") if the attribute already exists. Fix by either:
  - Passing the `-U` flag to `add-generic-password` to atomically overwrite, or
  - Deleting the existing entry first (`security delete-generic-password -a <key>`, ignoring non-zero exit on "not found") before adding.

  Without this fix, calling `set()` twice for the same key silently leaves the stale value in the keychain; after a JVM restart, `get()` returns the old value.

---

## 5. `observability` module — `channel` field and MDC safety

- [ ] **R03-06** Populate `LogEvent.channel` in `LogbackObserver.event()`. Identify which `ObserverEvent` variants carry channel information and extract the value. If no current event has a `channel` field, add one to `TurnStart` and `TurnEnd` (the natural scope boundaries) so the information flows through. Also write the value to `MDC.put("channel", ...)` alongside the other contextual fields.

- [ ] **R03-07** Replace `MDC.clear()` at the end of `LogbackObserver.event()` with a save-and-restore pattern so that inherited MDC context (e.g. from an HTTP request filter or outer coroutine scope) is not lost:
  ```kotlin
  val previousMdc = MDC.getCopyOfContextMap() ?: emptyMap()
  try {
      MDC.put("ts", ...); /* other puts */
      /* log */
  } finally {
      MDC.setContextMap(previousMdc)
  }
  ```

---

## 6. `config` module — narrow the file-level Detekt suppression

- [ ] **R03-08** Remove `@file:Suppress("detekt:all")` from `SecretStore.kt`. Replace it with targeted suppressions at the specific declarations that need them — typically `@Suppress("TooGenericExceptionCaught")` on the individual `catch` blocks. The blanket suppression prevents the mutation-funnel rule (and all other custom rules) from ever reporting anything in this file.
