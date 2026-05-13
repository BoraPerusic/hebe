# Review 011 — M5 Channels Implementation

Reviewed on: 2026-05-13  
Branch: v1-development  
Scope: M5.T1 through M5.T10 — all channel modules, gateway wiring, web UI, and tests.

---

## Overview

The M5 channels layer is substantially complete. All ten plan tasks have been implemented at the code level. The module structure, build graph, and API surface match the plan's intent. The test suite passes (claimed 70 tests). Several correctness gaps, missing plan items, and test weaknesses exist and are documented below.

---

## Plan Coverage

### M5.T1 — ChannelManagerImpl + InjectChannel: MET (with caveats)

`ChannelManagerImpl` registers/unregisters channels under a mutex, merges N flows plus the inject flow, processes messages, enforces the agent-broadcast drop guard, and enforces the mission deduplication guard. `InjectChannel` wraps a coroutines `Channel<IncomingMessage>` with `DROP_OLDEST` overflow and bridges to a `MutableSharedFlow`.

Caveat 1: The plan says `start` should "launch a child coroutine for each message." The implementation instead uses a `mergedFlow.collect` in a single coroutine — messages are processed sequentially, not concurrently. If one `agent.handleMessage` is slow it blocks all subsequent incoming messages across all channels. This is a meaningful architectural departure from the plan's stated intent and affects multi-channel concurrent throughput.

Caveat 2: `InjectChannel.send` catches `ClosedSendChannelException` but the plan states DROP_OLDEST should be applied with a warn log. DROP_OLDEST is set on the underlying channel (correct), but there is no warn log when a message is actually dropped. The `send` function will succeed for all calls until the channel is closed — the overflow strategy silently drops.

### M5.T2 — CLI Channel: MET (with caveats)

`CliChannel` uses jline, implements all `Channel` methods, exposes a cancel flag, and handles double-Ctrl-C. `Repl.kt` was not created as a separate file (the plan called for `CliChannel.kt` + `Repl.kt`); instead the REPL loop is inlined into `CliChannel`. This is acceptable simplification.

Caveat: The plan specifies slash-commands (`/quit`, `/compact`, `/approve`, `/help`). No slash-command handling exists in `CliChannel`. The plan claims these round-trip through the agent, but the `parseInput` method in `CliChannel.kt` at line 119 does not do any pre-processing or special handling — it treats every line uniformly. The acceptance criterion "Slash-commands work via the agent" is met only if the agent itself handles them; there is no CLI-side verification of this.

### M5.T3 — Web Gateway Skeleton: MET

`Gateway.kt` starts a Netty server, installs HTTP Basic auth, gates `/api/*`, serves static files, and registers `/health` without auth. Tests verify the 4 required scenarios. The plan mentioned `auth/BasicAuthPlugin.kt` as a separate file — it is inlined into `Gateway.kt`, which is acceptable.

Caveat: The plan specifies "origin checks for browsers" as an acceptance criterion. No `Origin` header check or CORS configuration is present in `Gateway.kt`. This is a missing security control for browser-based clients.

### M5.T4 — Web SSE Channel: MET (with caveats)

`WebChannel` and `Routes.kt` implement `POST /api/messages`, `GET /api/sessions/{id}/events`, session management, a 32-event ring buffer, and `Last-Event-ID` / `lastEventId` query-param reconnection.

Caveat 1: `POST /api/messages` creates the `IncomingMessage` using the `sessionId` from the request body as a separate field but then emits the message with `channel = WebChannel.CHANNEL_NAME` ("web"), not the session id. The `ChannelManagerImpl.handleDoneOutcome` looks up the reply channel using `msg.channel` (line 160), not `msg.sessionId`. Since all web messages share the same `channel = "web"` key, replies are routed to the `WebChannel` correctly — but the session-id for reply routing is taken from `ReplyContext.sessionId`, which is populated from `msg.channel` at line 125 in `ChannelManagerImpl`. This means `observer.event(ObserverEvent.TurnStart(sessionId = msg.channel, ...))` always logs `"web"` rather than the actual browser session id, losing per-session observability.

Caveat 2: The reconnection implementation (`getEventsSince(lastEventId)` replayed before `collectEvents`) has a race window: events emitted between the replay and the `collectEvents` subscription can be missed. Events emitted to `_events` (the shared flow) that arrive after `getEventsSince` returns but before `collectEvents` subscribes will be lost. The ring buffer serves catch-up but the live flow subscription is not atomic with the replay.

Caveat 3: `Routes.kt` is registered on the `:modules:channels:web` module but the web build depends on `:modules:channels:channel-manager`. The gateway module does NOT depend on `:modules:channels:web`; the gateway `build.gradle.kts` only depends on `:modules:api`, `:modules:config`, `:modules:memory`, `:modules:security`. There is no production wiring code in the repository connecting `WebChannel` / `Routes.register(...)` / `TelegramWebhookRoute.register(...)` to the `Gateway`. The gateway is a standalone HTTP server and must be wired up by the application entry-point (presumably `cli-app`), but no such wiring exists in the modules reviewed.

### M5.T5 — Web Chat UI: MET

`index.html`, `app.js`, and `style.css` are present and correct in structure. The JS handles `text_delta`, `done`, `approval_requested`, `token_usage`, `error` SSE events, reconnect with `lastEventId`, approval modal, and auto-resize textarea.

Caveat 1: The plan calls for a 3-panel layout (session list, chat, right panel with memory/receipts). The UI is single-panel with no session list and no right panel. Memory and receipts are in separate JS files but not integrated into the main `index.html`. This is a scope reduction, not necessarily wrong for v1.

Caveat 2: `app.js` line 226: `escapeHtml` is defined but never called. Message content is added via `contentDiv.textContent = ...` (correct) so XSS isn't a risk — the method is dead code.

### M5.T6 — Web Memory Browser: MET

`MemoryRoutes.kt` implements all three endpoints: `/api/memory/search`, `/api/memory/tree`, `/api/memory/doc`. The plan specifies the search endpoint as `/api/memory/search` and tree as `/api/memory/tree` — both match. The doc endpoint matches `/api/memory/doc` (plan says `/api/memory/doc?path=…`).

Caveat: `MemoryRoutes.kt` lives in `modules/gateway` but `modules/channels/web/src/main/kotlin/com/hebe/channels/web/MemoryRoutes.kt` is a stub that says "Moved to modules/gateway." The plan (M5.T6) specifies the file should be in `modules/gateway/src/main/kotlin/com/hebe/gateway/MemoryRoutes.kt`. This matches, but the stub file in the web module is confusing dead code that should be removed.

No tests exist for `MemoryRoutes`. The plan calls for "Tests with Ktor's TestApplication."

### M5.T7 — Web Receipts Viewer: MET (with caveats)

`ReceiptsRoutes.kt` implements `/api/receipts` (with `since` and `limit`) and `/api/receipts/verify`. The verify endpoint supports both per-file and directory verification. `receipts.js` is present as a static file.

Caveat: No tests exist for `ReceiptsRoutes`. The plan calls for tests verifying listing and tamper detection. The `loadReceipts` function has non-trivial logic (nested loops, reversal, `since` filtering by `Instant.parse`) that is entirely untested.

Caveat 2: `loadReceipts` at line 133 — when `since != null`, it calls `break` out of the inner loop but the outer loop continues to the next file. If a single file spans the `since` threshold, earlier files will still be iterated (though they will all break on the first line). This is mildly wasteful but not incorrect.

### M5.T8 — Telegram Channel: PARTIAL

`TelegramChannel` supports both `LONG_POLLING` and `WEBHOOK` modes, implements operator gate, draft updates via `DraftThrottler`, and proper shutdown. `DraftThrottler` coalesces edits by time and character-change thresholds.

Gap 1: The plan specifies `healthCheck()` should "ping `getMe`" to verify liveness. The implementation returns `ChannelHealth.Up` if `isRunning == true` (set on registration) — it does not actually call the Telegram API. After the bot token is revoked or the network fails, `healthCheck()` will still return `Up`.

Gap 2: Plan calls for `UpdatePoller.kt` as a separate file. The long-polling setup is done inline via `TelegramBotsLongPollingApplication` in `startLongPolling()` — acceptable simplification, but note this class is never called in webhook mode (correct).

Gap 3: `formatMarkdown` at lines 290-297 is a V1 Markdown escaping function. It escapes `_`, `*`, `[`, `]`, `(`, `)`, `` ` `` — but this strips all Markdown formatting from outbound messages (e.g., bold, code blocks). The intent was to escape Markdown so Telegram displays it raw, but the effect is to also escape any intentional formatting the agent produces. This likely produces poor output for code-heavy responses.

### M5.T9 — Telegram Webhook Route: MET (with caveats)

`TelegramWebhookRoute` registers `POST /api/webhooks/telegram/$secretPath` and delegates to `TelegramUpdateConsumer`. The `TelegramChannel` implements `LongPollingUpdateConsumer` (from the telegrambots library) which also covers `consume(List<Update>)`, and `TelegramUpdateConsumer` is a local interface for the webhook route.

Caveat: No tests exist for `TelegramWebhookRoute`. The plan requires "POST a fake update → IncomingMessage emitted" and "POST to wrong secret path → 404." Neither is tested. A POST to a non-registered path in Ktor returns 404 by default, so the second scenario would pass — but it's not explicitly verified.

Caveat 2: The webhook route does not appear to be registered anywhere in the gateway or in any wiring code in scope. The `TelegramWebhookRoute.register(routing)` must be called from the application entry-point.

### M5.T10 — HealthCheck in /api/status: PARTIAL

`Gateway.kt` exposes `/api/status` with `uptimeMs`, `channels` (from the injected `channelHealthProvider`), and a stub `llm` field. The `setChannelHealthProvider` wiring point exists.

Gap: The plan's JSON format includes `"llm": { "endpoint": "...", "reachable": true }`. The implementation always returns `"reachable": false` with no `"endpoint"` field — effectively a hard-coded broken value regardless of actual LLM state.

Gap: The `hebe doctor` CLI integration is explicitly noted as "not yet implemented" in the progress file. The M5.T10 acceptance criterion "Used by `/api/status` and `doctor`" is only half-met.

Gap: The `GatewayTest` tests only verify authentication behavior on `/api/status`; no test verifies the channel health data is actually serialized in the response body.

---

## Test Quality

### ChannelManagerTest (10 tests)

Good coverage of register/unregister lifecycle, agent-broadcast drop guard, mission deduplication, and inject channel routing. Tests use `UnconfinedTestDispatcher` and `backgroundScope` correctly per the project memory.

Issue: `same triggeringMissionId within window is deduplicated` test (line 161) emits two messages synchronously on a shared flow and then does `coVerify(exactly = 1)`. This works with `UnconfinedTestDispatcher` because coroutines run eagerly, but the test does not call `advanceUntilIdle()` before the verify — it relies on the UnconfinedTestDispatcher having already run both emissions synchronously. This is correct in practice but fragile if the dispatcher changes.

Issue: There is no test for `HandleOutcome.Failed` producing an error reply to the channel, nor for `HandleOutcome.Pending` or `HandleOutcome.NoResponse` paths. Three of four outcome branches are untested in the manager-level test.

### InjectChannelTest (7 tests)

Solid. Covers send-success, send-after-close returns false, flow delivery, DROP_OLDEST non-suspension, name, supportsDraftUpdates, and healthCheck. The `message sent via send arrives on the flow` test correctly uses `withTimeout` and `flow.first()`.

### CliChannelTest (9 tests)

Good use of scripted mock `LineReader` to avoid blocking I/O. Tests cover flow delivery, whitespace trimming, null-safety guards, cancel flag, and shutdown.

Issue: `isCancelRequested` only tests initial state (false). There is no test that simulates a `UserInterruptException` and verifies the cancel flag becomes true. The `handleCtrlC` logic (including the double-Ctrl-C window) is completely untested.

Issue: `updateDraft does not throw when reader is null` does not verify that the call is a no-op (i.e., nothing is written). It only verifies no exception — the test is too permissive.

### DraftThrottlerTest (5 tests)

Good coverage of: first-call immediate send, second-within-interval suppressed, chars-changed threshold, flushAll reset, and independent message ids. All five cases are behaviorally distinct.

Issue: Tests pass `backgroundScope` but the throttler launches coroutines via `scope.launch`. With `UnconfinedTestDispatcher`, coroutines run eagerly so the `verify` calls are sound. This is correct per project memory.

Issue: No test for the case where `pending[messageId]` exists but the timer has elapsed — i.e., that a late-arriving call IS sent after `minInterval` expires. The three suppression tests verify the "not yet" path; the "elapsed" path relies on the real clock and is not tested.

### TelegramChannelTest (7 tests)

Uses webhook mode to avoid requiring a real Telegram API. Tests cover name, supportsDraftUpdates, healthCheck before/after start, operator gate allow/deny, reply-no-throw, and broadcast-no-throw.

Issue: `reply does not throw when client is null` at line 105 — this only verifies no exception is thrown when `telegramClient` is null. There is no test for the actual reply behavior when a client is wired up (which would require mocking `JettyTelegramClient`). The critical `sendMessage` and `editMessageText` paths have zero test coverage.

Issue: No test for `formatMarkdown`. The escaping logic is non-trivial and untested.

Issue: No test for `TelegramWebhookRoute` at all.

### WebChannelTest (14 tests)

Thorough for `WebChannel` internals. Tests flow emission, reply/updateDraft SSE event routing, session lifecycle (create/get/remove), and broadcast no-throw. Well structured.

Issue: No test for `Routes.register` (the Ktor route handlers). The POST `/api/messages` and GET `/api/sessions/{id}/events` endpoints are untested despite the build having `ktor.server.test.host` available. The plan explicitly calls for "Tests with Ktor's TestApplication."

Issue: The reconnection race window in `Routes.kt` (events between replay and live subscription being lost) is not covered by any test.

### WebSessionTest (10 tests)

Excellent. Covers all `WebSseEvent` factory methods, ring buffer capacity/eviction, `getEventsSince` filtering, event id increment, and all five event type factories.

### GatewayTest (5 tests)

Covers the 4 auth scenarios required by the plan. Well written.

Issue: No test verifies the `/api/status` response body contains channel health data. The `channelHealthProvider` injection point is tested only implicitly.

Issue: No integration test connecting the Web SSE route, the memory routes, or the receipts routes to the gateway's auth layer.

---

## Issues Found

1. [HIGH] Sequential message processing in `ChannelManagerImpl.start` — `mergedFlow.collect` runs all `agent.handleMessage` calls in sequence. A slow agent turn blocks all channels. The plan specifies launching a child coroutine per message (`scope.launch { handleIncomingMessage(msg) }`).
   File: `modules/channels/channel-manager/src/main/kotlin/com/hebe/channels/ChannelManagerImpl.kt`, line 72.

2. [HIGH] No production wiring code exists connecting `WebChannel`, `Routes`, `TelegramWebhookRoute`, `MemoryRoutes`, `ReceiptsRoutes` to the `Gateway`. The gateway module does not depend on the channel modules. The application will not function without this wiring in `cli-app` or similar entry point. This is outside the reviewed modules but is a hard blocker for end-to-end functionality.

3. [HIGH] `POST /api/messages` (`Routes.kt` line 43–54) does not store `sessionId` on the `IncomingMessage`. The `IncomingMessage.channel` is always `"web"`, so `ChannelManagerImpl` uses `"web"` as the session id for `TurnStart` events. The actual per-browser session id is discarded. Replies from the agent arrive in `handleDoneOutcome` with `ctx.sessionId = msg.channel = "web"`, so `WebChannel.reply` looks up `sessions["web"]` — not the actual browser session. If two different browser sessions are open simultaneously, both receive the same session's events.
   File: `modules/channels/web/src/main/kotlin/com/hebe/channels/web/Routes.kt`, lines 43–54; `modules/channels/channel-manager/src/main/kotlin/com/hebe/channels/ChannelManagerImpl.kt`, line 125.

4. [MED] SSE reconnection race window in `Routes.kt` lines 110–120: events emitted between `getEventsSince(lastEventId)` replay and `session.collectEvents(...)` subscription may be lost. The replay and live subscription are not atomic.
   File: `modules/channels/web/src/main/kotlin/com/hebe/channels/web/Routes.kt`, lines 110–120.

5. [MED] `TelegramChannel.healthCheck()` always returns `Up` once `isRunning = true` without actually pinging `getMe`. Bot token revocation or network outage will not be detected.
   File: `modules/channels/telegram/src/main/kotlin/com/hebe/channels/telegram/TelegramChannel.kt`, line 164.

6. [MED] `formatMarkdown` in `TelegramChannel` (lines 290–297) escapes all Markdown characters. This will visually corrupt any response containing intentional formatting (bold, code, links). It should only escape characters that Telegram would mis-parse, not all Markdown.
   File: `modules/channels/telegram/src/main/kotlin/com/hebe/channels/telegram/TelegramChannel.kt`, lines 290–297.

7. [MED] Missing tests for `MemoryRoutes` and `ReceiptsRoutes`. Both have non-trivial logic (error handling, pagination, `since` filtering) with zero test coverage. The plan explicitly requires "Tests with Ktor's TestApplication" for both.

8. [MED] Missing tests for `Routes.register` (web POST/GET routes). The plan requires "Tests with Ktor's TestApplication." The build already has `ktor.server.test.host` as a test dependency.

9. [MED] `Gateway.llm` field in `/api/status` is hard-coded to `{ "reachable": false }` with no endpoint. The plan specifies a proper LLM reachability check.
   File: `modules/gateway/src/main/kotlin/com/hebe/gateway/Gateway.kt`, line 83.

10. [MED] CORS / origin check missing from Gateway. The plan acceptance criterion for M5.T3 explicitly lists "Origin checks for browsers."
    File: `modules/gateway/src/main/kotlin/com/hebe/gateway/Gateway.kt`.

11. [MED] `hebe doctor` CLI integration not implemented. The M5.T10 acceptance criterion "Used by `/api/status` and `doctor`" is half-met.

12. [LOW] Stub files `modules/channels/web/src/main/kotlin/com/hebe/channels/web/MemoryRoutes.kt` and `ReceiptsRoutes.kt` contain only a comment. They should be deleted.
    Files: `modules/channels/web/src/main/kotlin/com/hebe/channels/web/MemoryRoutes.kt` and `ReceiptsRoutes.kt`.

13. [LOW] `app.js` defines `escapeHtml` (line 226) but never calls it. Dead code.
    File: `modules/gateway/src/main/resources/static/app.js`, line 226.

14. [LOW] `HandleOutcome.Failed`, `HandleOutcome.Pending`, and `HandleOutcome.NoResponse` branches in `ChannelManagerImpl` have no test coverage. Only `HandleOutcome.Done` and the broadcast-drop path are exercised in `ChannelManagerTest`.

15. [LOW] `CliChannel.handleCtrlC` logic (single vs. double Ctrl-C, cancel flag transition) is not tested. The `CliChannelTest` only verifies the initial state of `isCancelRequested()`.

16. [LOW] `TelegramChannel.sendMessage` and `editMessageText` paths (the actual Telegram API calls) are not tested. `TelegramChannelTest` only tests the no-op paths where `telegramClient` is null.

17. [LOW] `DraftThrottler` does not test the "elapsed interval → send" path. The "interval not yet elapsed → suppress" path is tested, but not the inverse.

18. [LOW] `InjectChannel` does not log a warning when DROP_OLDEST drops a message. The plan specifies "with a warn log." The current overflow is silent.
    File: `modules/channels/channel-manager/src/main/kotlin/com/hebe/channels/InjectChannel.kt`, line 20.

19. [LOW] The web UI does not have a session list panel or a right panel for memory/receipts as specified in M5.T5. This is a visual/UX gap rather than a functional one for v1.

---

## Summary

The M5 implementation covers all ten plan tasks in code, and the test suite is claimed to pass. The most critical issue is **Issue #3**: the session-id routing bug means the web channel cannot correctly handle simultaneous sessions — all web replies are routed to a single `"web"` bucket rather than per-browser-session buckets. This will manifest as cross-session message delivery in any multi-tab scenario. Issue #1 (sequential message processing) is an architectural deviation that will cause head-of-line blocking under concurrent load. Issue #2 (missing application wiring) means the system cannot be tested end-to-end in the current state.

The test suite quality is uneven: `WebSessionTest`, `DraftThrottlerTest`, and `InjectChannelTest` are solid. `MemoryRoutes`, `ReceiptsRoutes`, `Routes` (web), and `TelegramWebhookRoute` have zero test coverage despite the plan requiring Ktor integration tests for all of them. The Telegram `sendMessage`/`editMessageText` paths are also untested.
