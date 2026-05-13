# Tasks — Review 011 (M5 Channels)

Ordered by priority. Each item references the originating issue from review-011.md.

---

## HIGH Priority

- [ ] [HIGH] Fix session-id routing bug in web channel: store `sessionId` from the POST body on the `IncomingMessage` (e.g., as `channel = sessionId`) or pass it through `ReplyContext` so `ChannelManagerImpl` routes replies to the correct per-browser session instead of always using `"web"`. Affects `modules/channels/web/src/main/kotlin/com/hebe/channels/web/Routes.kt` (lines 43–54) and the `TurnStart` event in `ChannelManagerImpl.kt` (line 125). (Issue #3)

- [ ] [HIGH] Fix sequential message processing in `ChannelManagerImpl.start`: wrap each message handler in a `scope.launch { handleIncomingMessage(msg) }` so agent turns across different channels execute concurrently instead of head-of-line blocking. Update the corresponding test to verify concurrent dispatch. Affects `modules/channels/channel-manager/src/main/kotlin/com/hebe/channels/ChannelManagerImpl.kt` line 72. (Issue #1)

- [ ] [HIGH] Create application wiring (in `modules/cli-app` or a dedicated wiring module) that connects `WebChannel`, `Routes.register`, `TelegramWebhookRoute.register`, `MemoryRoutes.register`, and `ReceiptsRoutes.register` to the `Gateway`. Add the missing module dependencies to the gateway's `build.gradle.kts` if needed. Without this, no end-to-end path works. (Issue #2)

---

## MED Priority

- [ ] [MED] Add Ktor integration tests for `Routes.register` (`POST /api/messages` and `GET /api/sessions/{id}/events`) using `testApplication`. Verify: message submitted via POST arrives on the channel flow; SSE stream delivers `text_delta` and `done` events; `Last-Event-ID` reconnect replays buffered events. Affects `modules/channels/web/build.gradle.kts` already has `ktor.server.test.host`. New test file: `modules/channels/web/src/test/kotlin/com/hebe/channels/web/RoutesTest.kt`. (Issue #8)

- [ ] [MED] Add Ktor integration tests for `MemoryRoutes` using `testApplication` + a mock `MemoryStore`. Verify: search returns results; tree lists docs; doc endpoint returns content and 404 for missing path; `q` missing returns 400. New test file: `modules/gateway/src/test/kotlin/com/hebe/gateway/MemoryRoutesTest.kt`. (Issue #7)

- [ ] [MED] Add Ktor integration tests for `ReceiptsRoutes` using `testApplication` + synthetic receipt files in a temp dir. Verify: listing returns receipts; `since` filter works; `limit` is respected; verify endpoint returns `ok: true` for valid data; verify returns `ok: false` for a tampered file. New test file: `modules/gateway/src/test/kotlin/com/hebe/gateway/ReceiptsRoutesTest.kt`. (Issue #7)

- [ ] [MED] Add a test for `TelegramWebhookRoute` using Ktor's `testApplication`. Verify: a valid POST with a JSON-serialized `Update` calls `updateConsumer.consume(...)` and returns 200; a POST with malformed JSON returns 500; a POST to a wrong secret path returns 404. New test file: `modules/channels/telegram/src/test/kotlin/com/hebe/channels/telegram/TelegramWebhookRouteTest.kt`. (Issue #9 in plan — M5.T9 acceptance criteria)

- [ ] [MED] Fix SSE reconnection race window in `Routes.kt`: subscribe to `session.collectEvents` before replaying buffered events (or use a snapshotted approach that is atomic with the live subscription) to eliminate the gap where events emitted between replay and subscription are lost. Affects `modules/channels/web/src/main/kotlin/com/hebe/channels/web/Routes.kt` lines 110–120. (Issue #4)

- [ ] [MED] Fix `TelegramChannel.healthCheck()` to actually call `getMe` via the Telegram client and return `ChannelHealth.Down` on failure. Fall back to `isRunning` flag if the client is not yet initialized. Affects `modules/channels/telegram/src/main/kotlin/com/hebe/channels/telegram/TelegramChannel.kt` line 164. (Issue #5)

- [ ] [MED] Fix `TelegramChannel.formatMarkdown`: replace the blanket escape of all Markdown characters with a targeted approach that allows intentional bold/italic/code formatting through while escaping only characters that would cause parse errors in Telegram's MarkdownV2 (or switch to `ParseMode.MARKDOWNV2` and use its proper escape rules). Affects `modules/channels/telegram/src/main/kotlin/com/hebe/channels/telegram/TelegramChannel.kt` lines 290–297. Add unit tests for `formatMarkdown`. (Issue #6)

- [ ] [MED] Add CORS plugin / origin validation to `Gateway.configureApplication`. The M5.T3 acceptance criterion explicitly lists "Origin checks for browsers." At minimum, install Ktor's `CORS` plugin restricted to the configured host. Affects `modules/gateway/src/main/kotlin/com/hebe/gateway/Gateway.kt`. (Issue #10)

- [ ] [MED] Implement LLM reachability check in `Gateway.start` or expose a setter similar to `setChannelHealthProvider`. Update the `/api/status` response to include the actual LLM endpoint URL and reachability result instead of the hard-coded `{ "reachable": false }`. Affects `modules/gateway/src/main/kotlin/com/hebe/gateway/Gateway.kt` line 83. (Issue #9)

- [ ] [MED] Implement the `hebe doctor` CLI subcommand to consume `ChannelManagerImpl.getAllHealth()` (and the LLM reachability status) and print a table. This completes the M5.T10 acceptance criterion "Used by `/api/status` and `doctor`". Target module: `modules/cli-app`. (Issue #11)

---

## LOW Priority

- [ ] [LOW] Delete stub files `modules/channels/web/src/main/kotlin/com/hebe/channels/web/MemoryRoutes.kt` and `modules/channels/web/src/main/kotlin/com/hebe/channels/web/ReceiptsRoutes.kt`. They contain only a comment and are confusing dead code. (Issue #12)

- [ ] [LOW] Remove the dead `escapeHtml` method from `app.js` (line 226). (Issue #13)

- [ ] [LOW] Add tests in `ChannelManagerTest` for the `HandleOutcome.Failed` path (verify an error reply is sent to the channel), `HandleOutcome.Pending` path (verify `TurnEnd` with `outcome = "pending"` is emitted), and `HandleOutcome.NoResponse` path. (Issue #14)

- [ ] [LOW] Add a test to `CliChannelTest` that simulates a `UserInterruptException` via the mock `LineReader` and verifies `isCancelRequested()` returns `true` after one occurrence, and that a second `UserInterruptException` within 2 seconds causes the REPL to exit (double-Ctrl-C). (Issue #15)

- [ ] [LOW] Add tests to `TelegramChannelTest` that mock `JettyTelegramClient` to verify `sendMessage` is called with the correct chatId and text on `reply(...)`, and that `editMessageText` is called via `DraftThrottler` on `updateDraft(...)`. (Issue #16)

- [ ] [LOW] Add a `DraftThrottlerTest` case for the "interval elapsed → call IS sent" path: after the first call, advance time past `minInterval` and verify the second call is transmitted. (Issue #17)

- [ ] [LOW] Add a warn log in `InjectChannel` when `DROP_OLDEST` overflow drops a message. Consider wrapping the underlying `Channel` with a `onBufferOverflow` callback or tracking buffer fullness. Affects `modules/channels/channel-manager/src/main/kotlin/com/hebe/channels/InjectChannel.kt`. (Issue #18)

- [ ] [LOW] Add `GatewayTest` test to verify the `/api/status` response body contains the `channels` array populated from the `channelHealthProvider`. Currently no test exercises the body content of the status response. (Issue from M5.T10 test gap)
