# Tasks — Review 020 (M9 re-review)

Carries forward 9 open items from tasks-review-019, plus 2 new issues.

## Carried over from review-019

- [ ] **R020-1** Wire `DoctorCommand` to use `Checks.kt`: replace the HTTP gateway body with a call to `runAllChecks()`, add `--json` and `--verbose` options, exit 1 via `Abort()` on any Fail. This closes R019-4, R019-5, R019-6, and R019-7 in one shot — all the check logic already exists in `Checks.kt`.
- [ ] **R020-2** Pass a read-only `McpClientManager` (constructed from config) to `runAllChecks()` in `DoctorCommand` so the MCP check reflects real connection state.
- [ ] **R020-3** Add `ServiceStatusCommand` to `HebeCLI.subcommands()` that calls `platformService(dataDir).status()` and prints the result.
- [ ] **R020-4** Add `--non-interactive` flag to `OnboardCommand`; read answers from env vars (`HEBE_LLM_BASE_URL`, `HEBE_API_KEY`, `HEBE_ADMIN_PASSWORD`, `HEBE_DEFAULT_MODEL`, `HEBE_TELEGRAM_TOKEN`, `HEBE_OPERATOR_ID`).
- [ ] **R020-5** Hash admin password with SHA-256 before storing: `secretStore.set("web.password", MessageDigest.getInstance("SHA-256").digest(adminPassword.toByteArray()))`.
- [ ] **R020-6** Add post-connection monitoring to `McpClientManager`: after `connectServer()` succeeds, launch a background coroutine that pings with `listTools()`, detects transport closure, removes stale tools from registry on disconnect, and calls `connectServerWithReconnect()` again.
- [ ] **R020-7** Fix fish completion: multi-word subcommands need two `complete` directives (one for the first word, one for the second). Use `installCompletion()` from Clikt 5, or generate correct fish completions manually.
- [ ] **R020-8** Format `StatusCommand.printStatus()` output as a table rather than echoing raw JSON.

## New issues from review-020

- [ ] **R020-9** Fix `plugin.start` span in `Lifecycle.kt`: wrap with `.use {}` (or call `.close()`) so the span is always ended. Add `plugin.version` attribute as required by the spec.
- [ ] **R020-10** Remove the three unused imports from `Main.kt` (lines 36–38): `kotlinx.serialization.json.Json`, `JsonObject`, `jsonObject`.
