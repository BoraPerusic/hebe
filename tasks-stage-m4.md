# tasks-stage-m4.md — M4 Built-in Tools

## Goal
Implement all built-in tools per `docs/plan/tasks/M4-builtin-tools.md`. Each tool:
- Implements the `Tool` interface from `com.hebe.api`
- Has correct `RiskLevel` and `requiresApproval` per the spec
- Returns `ToolResult.Ok/Err/NeedsApproval` (never throws)
- Has unit tests

## Dependencies
- M3.T1–M3.T11 completed (security policy chain wired)
- `modules/tools/builtin/build.gradle.kts` needs deps added

## Conventions (per M4 spec)
- Path: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/<group>/<Tool>.kt`
- Tests: `modules/tools/builtin/src/test/kotlin/...`
- `ToolSpec.name` = `snake_case`
- JSON Schema via `kotlinx.serialization.json.buildJsonObject`
- Risk + `requiresApproval` per spec exactly
- Register via `ToolRegistry` in `AppComponents`

---

## Task breakdown

### M4.T1 — `file_system` (read/write/list/glob/append)
**Size**: M | **Depends on**: M3.T2, M3.T10

#### M4.T1.A — Setup `builtin` module deps
Edit `modules/tools/builtin/build.gradle.kts`:
```kotlin
dependencies {
    api(project(":modules:api"))
    implementation(project(":modules:memory"))        // WorkspaceFs, MemoryStore
    implementation(project(":modules:security"))     // ArgsRedactor, LeakDetector
    implementation(project(":modules:observability"))
    implementation(libs.jgit)                         // for git tools later
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)
}
```

#### M4.T1.B — `FileSystemReadTool` (`risk=Low`, `readOnly=true`)
- Args: `path` (string, workspace-relative), `encoding` (optional, "utf-8" | "base64")
- Delegates to `WorkspaceFs.read(path)`
- Markdown detection: frontmatter parse, return metadata in response
- Binary拒绝: if file is non-text and encoding=base64 not set → Err
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/file/FileSystemReadTool.kt`

#### M4.T1.C — `FileSystemListTool` (`risk=Low`, `readOnly=true`)
- Args: `prefix` (optional, default "")
- Returns array of `{name, size, modified}` objects
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/file/FileSystemListTool.kt`

#### M4.T1.D — `FileSystemGlobTool` (`risk=Low`, `readOnly=true`)
- Args: `pattern` (glob, e.g. `**/*.md`)
- Delegates to `WorkspaceFs` list + filter with glob matching
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/file/FileSystemGlobTool.kt`

#### M4.T1.E — `FileSystemWriteTool` (`risk=Medium`)
- Args: `path`, `content`, `encoding` (optional)
- Hygiene scan on content (reuse M1.T12 `HygieneScanner`)
- Atomic write via `WorkspaceFs.write`
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/file/FileSystemWriteTool.kt`

#### M4.T1.F — `FileSystemAppendTool` (`risk=Medium`)
- Args: `path`, `content`
- Appends via `WorkspaceFs.append`
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/file/FileSystemAppendTool.kt`

#### M4.T1.G — Tests
- `FileSystemReadToolTest`: read existing file → Ok; traversal attack → Err
- `FileSystemListToolTest`: returns names + sizes
- `FileSystemGlobToolTest`: `**/*.md` returns matching paths
- `FileSystemWriteToolTest`: write + read roundtrip; hygiene rejection → Err
- `FileSystemAppendToolTest`: append + read verifies content

#### Acceptance
- [ ] 5 tools registered
- [ ] Risk levels per spec
- [ ] Hygiene on writes
- [ ] Receipts on each call (handled by dispatcher)

---

### M4.T2 — `shell`
**Size**: M | **Depends on**: M3.T3, M3.T10

#### M4.T2.A — `ProcessRunner`
- Object: `ProcessRunner`
- `run(cmd: String, cwd: Path?, timeoutMs: Long, env: Map<String,String>): ProcessResult`
- Uses `ProcessBuilder` with `bash -c` on Unix
- Captures stdout/stderr (cap 1 MB with `[truncated]` marker)
- Timeout default 60s, max 600s
- Graceful destroy then forcibly destroy after 1s grace
- Registers process with `EmergencyStop`

#### M4.T2.B — `ShellTool`
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/shell/ShellTool.kt`
- `risk = High`, `requiresApproval = true`
- Args: `cmd` (required), `cwd` (optional, default workspace), `timeout_ms` (optional, default 60000)
- Returns `{stdout, stderr, exitCode}`
- `exitCode == 0` → Ok; else → Err("exit $exitCode: $stderr")

#### M4.T2.C — Tests
- `ShellToolTest`: echo hello → Ok; forbidden command → Deny from validator; timeout → Err

---

### M4.T3 — `http`
**Size**: M | **Depends on**: M3.T4, M3.T10

#### M4.T3.A — `HttpTool`
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/http/HttpTool.kt`
- `risk = Medium`
- Args: `method` (GET/POST/PUT/DELETE/PATCH), `url` (required), `headers` (optional JsonObject), `body` (optional string|JsonObject), `secret_header_name` (optional), `secret_name` (optional)
- Uses Ktor client
- Secret injection: if `secret_name` + `secret_header_name` set → resolve via `ctx.secretLookup` and inject header
- SSRF: calls `DomainAllowlistValidator` (from M3.T4) — tool itself doesn't re-check, but the validator in the chain does
- Returns `{status: Int, headers: Map, body: String|Base64}` — body text if Content-Type text/* or application/json, else base64
- Body cap 1 MB; truncate with `[truncated]`

#### M4.T3.B — Tests
- `HttpToolTest`: GET allowed domain → Ok; disallowed domain → Deny from validator; secret injection check

---

### M4.T4 — `web_search` (Brave + DuckDuckGo)
**Size**: M | **Depends on**: M4.T3

#### M4.T4.A — `WebSearchProvider` trait + impls
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/search/WebSearchProvider.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/search/BraveSearchProvider.kt`
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/search/DuckDuckGoSearchProvider.kt`
- Trait: `interface WebSearchProvider { val name: String; suspend fun search(query: String, k: Int): List<SearchHit> }`
- Brave: `GET https://api.search.brave.com/res/v1/web/search?q=...&count=K` with `X-Subscription-Token`
- DDG: `GET https://html.duckduckgo.com/html?q=...` (HTML scrape, no API key)
- `SearchHit`: `data class SearchHit(val title: String, val url: String, val snippet: String, val rank: Int, val source: String)`

#### M4.T4.B — `WebSearchTool`
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/search/WebSearchTool.kt`
- `risk = Low`
- Args: `query` (required), `k` (optional, default 10)
- Provider selection: Brave if `brave_api_key` secret exists, else DDG

#### M4.T4.C — Tests (recorded fixtures)
- `WebSearchToolTest`: Brave present → Brave; absent → DDG; empty → empty array

---

### M4.T5 — `memory_search`, `memory_read`, `memory_write`, `memory_tree`
**Size**: M | **Depends on**: M1.T10, M1.T4, M3.T10

#### M4.T5.A — Tools
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/memory/MemorySearchTool.kt` (`risk=Low`, `readOnly=true`)
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/memory/MemoryReadTool.kt` (`risk=Low`, `readOnly=true`)
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/memory/MemoryWriteTool.kt` (`risk=Medium`)
- `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/memory/MemoryTreeTool.kt` (`risk=Low`, `readOnly=true`)

#### M4.T5.B — Tests
- Search returns hits; write hygiene rejection → Err; tree returns nested structure

---

### M4.T6 — `wiki_read`, `wiki_write`
**Size**: S | **Depends on**: M4.T5

- Convention layer: paths under `wiki/` prefix
- `wiki_read`: maps `slug` → `workspace/wiki/<slug>.md`
- `wiki_write`: writes through `MemoryStore.appendDoc(path = "wiki/<slug>.md")`
- Wikilink parsing: `[[Page Name]]` → extract as outgoing links (informational only)

---

### M4.T7 — `git` (JGit)
**Size**: M | **Depends on**: M3.T10

- Single `GitTool` with `verb` arg: `clone | status | diff | log | branch | commit`
- `clone`: `risk=High`, `requiresApproval=true`
- `status | diff | log | branch list`: `risk=Low`, `readOnly=true`
- `commit | branch create`: `risk=Medium`
- JGit API: `Git.open(repo)`, `git.status().call()`, etc.
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/git/GitTool.kt`

---

### M4.T8 — `git push` (shell-out)
**Size**: S | **Depends on**: M4.T2, M4.T7

- `risk=High`, `requiresApproval=true`
- Uses `ProcessRunner` from M4.T2 to run `git push <remote> <branch>`
- Validates `dir` is a Git repo via JGit `RepositoryBuilder.findGitDir`
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/git/GitPushTool.kt`

---

### M4.T9 — `github` (may defer to v1.1 per cut lines)
**Size**: M | **Depends on**: M4.T3, M2.T15

- PAT lookup via `ctx.secretLookup("github_pat")`; null → ask_user for credential
- Thin Ktor client over `https://api.github.com`
- Verbs: `issue list/get/create/comment`, `pr list/get/create/merge`, `repo get`
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/github/GitHubTool.kt`

---

### M4.T10 — `kubectl`
**Size**: M | **Depends on**: M4.T2

- Args: `verb` (string), `args` (array), `kubeconfig` (optional), `context` (optional)
- Read-only verbs (Medium): `get`, `describe`, `logs`, `top`, `events`, `version`, `config view`, `auth can-i`
- Mutating verbs (High + always-approve): `apply`, `create`, `delete`, `patch`, `replace`, `scale`, `rollout`, `cordon`, `drain`, `uncordon`, `taint`, `label`, `annotate`, `exec`, `port-forward`, `set`
- Unknown verbs → default High
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/k8s/KubectlTool.kt`

---

### M4.T11 — `ask_user`
**Size**: S | **Depends on**: M2.T13

- `risk=Low`
- Args: `question` (string), `purpose` (optional "general" | "credential"), `secretName` (required if purpose=credential)
- Returns `ToolResult.NeedsApproval(prompt=question, payload={purpose, secretName})`
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/ask/AskUserTool.kt`

---

### M4.T12 — `schedule`
**Size**: S | **Depends on**: M1.T2

- `risk=Medium`
- Verbs: `create`, `list`, `disable`, `enable`, `delete`
- `create` args: `name`, `cron`, `body_kind` ("skill" | "tool"), `body_ref`, `body_json`
- File: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/schedule/ScheduleTool.kt`

---

### M4.T13 — `job_create`, `job_status`, `job_cancel`
**Size**: M | **Depends on**: M1.T2, M2.T12

- `job_create` (`risk=Low`): args `kind` ("adhoc"|"routine"|"maintenance"|"heartbeat"), `payload`
- `job_status` (`risk=Low`, `readOnly=true`): args `id`
- `job_cancel` (`risk=Medium`): args `id`
- Files: `modules/tools/builtin/src/main/kotlin/com/hebe/tools/builtin/jobs/JobCreateTool.kt`, etc.

---

## Open Questions

1. **M4.T9 (github)**: Per cut lines, may defer to v1.1. Should we implement it or skip?
2. **M4.T2 (shell)**: Should v1 support Windows (`cmd /C`)? The spec notes this as a potential issue.
3. **M4.T1 (file_system)**: The spec mentions `MarkdownInferrer` from M1.T4 for frontmatter parsing — should we look for this class or implement inline?
4. **Hygiene scanner**: M1.T12 implemented `HygieneScanner.scanInbound(content)` — verify this exists and what it returns before M4.T1.E and M4.T5.C.
5. **M4.T4 (web_search)**: Should recorded fixtures be created for Brave/DDG tests, or should tests use mock providers?
6. **MCP dynamic tool filtering**: M4 tools need to support the `expose_high_risk` flag per M7.T2. Currently `ToolDispatcher` filters `High` risk tools when `expose_high_risk=false`. Confirm this is wired correctly.

## Verification Plan
After each tool:
1. `./gradlew :modules:tools:builtin:compileKotlin` — must compile
2. `./gradlew :modules:tools:builtin:test` — tests green
3. `./gradlew :modules:cli-app:compileKotlin` — AppComponents wiring compiles
4. `./gradlew detekt` — no new issues in `tools/builtin`