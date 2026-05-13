# Tasks — Review 010 (M5 Re-Review)

## Functional Issues

- [ ] **R010-01** Fix static files not on gateway classpath — add `runtimeOnly(project(":modules:channels:web"))` to `modules/gateway/build.gradle.kts`, OR move the static resources into `modules/gateway/src/main/resources/static/`
- [ ] **R010-02** Fix SSE 404 on first connect — `Routes.kt` line 102: change `channel.getSession(rawSessionId)` to `channel.getOrCreateSession(rawSessionId)` so the session is created lazily when SSE connects
- [ ] **R010-03** Implement `Last-Event-ID` reconnection in SSE handler — before starting `collectEvents()`, read the `Last-Event-ID` header (or `lastEventId` query param), call `session.getEventsSince(lastId)`, and write those buffered events before beginning live collection
- [ ] **R010-04** Fix `app.js` to render the assistant reply — (a) create an assistant message bubble when the first `text_delta` arrives or when `done` fires; (b) make `handleDone` display `data.text` when present; (c) ensure `appendToLastMessage` has a target to append to

## Test Gaps

- [ ] **R010-05** Add `ChannelManagerTest` for message routing — test that a message emitted by a mock channel is passed to `agent.handleMessage()`, that a message with `isAgentBroadcast=true` is dropped before `handleMessage()` is called, and that the same `triggeringMissionId` within 30 s results in a dropped message
- [ ] **R010-06** Add `InjectChannelTest` for the flow relay — call `start(scope)`, send a message via `send()`, collect from the returned flow, assert the message arrives; also test that overflow behaves as DROP_OLDEST by filling capacity and verifying the oldest message was dropped
- [ ] **R010-07** Add `CliChannelTest` with scripted input — use an in-memory `Terminal` or mock `LineReader` to drive the REPL; verify that typing "hi" produces an `IncomingMessage` on the channel's flow; test double Ctrl-C closes the input channel
- [ ] **R010-08** Add `TelegramChannelTest` for the operator gate — construct an `Update` with a `from.id` that differs from `operatorTelegramId`; call `consume(listOf(update))`; verify no message is emitted on the flow; also test that a matching `from.id` emits an `IncomingMessage`
- [ ] **R010-09** Add `WebSessionTest.emit increments id and stores in buffer` — call `session.emit(event)` in a coroutine, verify `getEventsSince(0)` returns the event with `id = 1`; also test ring buffer overflow (emit 33 events, verify `getEventsSince(0).size == 32` and the oldest is gone)
- [ ] **R010-10** Replace `GatewayTest` with meaningful route tests — using Ktor `TestApplication`: test `/health` returns 200 without credentials; test `/api/status` without credentials returns 401; test `/api/status` with correct credentials returns 200 with JSON body
- [ ] **R010-11** Add `WebChannelTest` for `reply()` and `updateDraft()` — create a session, call `reply(ctx, msg)`, collect from `session.events`, verify a `done` SSE event was emitted with the correct text; same for `updateDraft` emitting a `text_delta` event
- [ ] **R010-12** Clarify `DraftThrottlerTest."pending text updated correctly"` — add an assertion that the second call was NOT forwarded to sender (since the interval hasn't passed), not just that the first call was sent

## Structural Deviations

- [ ] **R010-13** Move `MemoryRoutes.kt` and `ReceiptsRoutes.kt` to `modules/gateway` — the `channels/web` module should not import `memory` and `security` modules; those dependencies belong in gateway
- [ ] **R010-14** Reconcile module name in architecture docs — update `docs/plan/v1-architecture.md` directory tree to say `channels/channel-manager/` instead of `channels/api/`

## Minor

- [ ] **R010-15** Fix approval endpoint in `app.js` — line 156 POSTs to `/api/approvals` but the spec says `POST /api/approval/{id}`; neither route is registered; add the route or align the JS with whatever route will be implemented
