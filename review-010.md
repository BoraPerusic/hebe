# Review 010 — M5 Channels Re-Review

**Branch**: v1-development  
**Stage**: M5 (re-review after developer fixes from review-009)  
**Verdict**: **Almost there, but not done** — the six critical blockers from review-009 are fixed. What remains are 4 functional issues (one of which is critical), serious test quality gaps, and 2 persistent structural deviations.

---

## What Was Fixed Since Review-009

All six critical blockers are resolved:

| # | Issue | Status |
|---|---|---|
| R009-01 | SSE one-shot dump | ✅ Now uses `respondTextWriter` + `collectEvents` |
| R009-02 | POST /api/messages not routed | ✅ `channel.emitMessage(msg)` wired; `WebChannel` has `_incomingMessages` flow |
| R009-03 | Auth not applied to /api/* | ✅ `authenticate("admin") { route("/api") { ... } }` |
| R009-04 | `editMessageText` stub | ✅ Now calls `EditMessageText` Telegram API |
| R009-05 | SSE data not JSON | ✅ `buildJsonObject` produces proper JSON |
| R009-06 | Zero tests | ✅ Tests written for all modules |
| R009-09 | `memory.js` missing | ✅ Created |
| R009-10 | Memory API routes wrong | ✅ `/api/memory/tree` and `/api/memory/doc?path=` match spec |
| R009-11 | InjectChannel not DROP_OLDEST | ✅ `BufferOverflow.DROP_OLDEST` added |
| R009-12–16 | ChannelRegistry / structural | ✅ ChannelRegistry removed; ChannelManagerImpl unified |
| R009-13 | Leaked CoroutineScope | ✅ DraftThrottler and processUpdate take scope param |
| R009-14 | Uptime always 0 | ✅ `serverStartMs` captured at startup |
| R009-15 | Hardcoded source path | ✅ Classpath loading via `getResourceAsStream` |
| R009-17–21 | Misc structural/security | ✅ All resolved |

---

## Remaining Functional Issues

### 1. **CRITICAL** — Static files not on Gateway's classpath

`Gateway.serveFile()` calls `javaClass.classLoader.getResourceAsStream("static/$filename")`. The static files live in `modules/channels/web/src/main/resources/static/`. But `modules/gateway/build.gradle.kts` has no dependency on `modules/channels/web` — so the gateway's classloader cannot find them. Every request to `/`, `/static/app.js`, etc. will return 404 at runtime.

Fix: either add `runtimeOnly(project(":modules:channels:web"))` to gateway's build, or move the static resources into `modules/gateway/src/main/resources/static/`.

### 2. Session creation race condition — SSE always 404 on first connect

`Routes.kt` line 102: `channel.getSession(rawSessionId)` returns null if the session doesn't exist, which triggers a 404. But a session is only created when `getOrCreateSession()` is called — and that's never called from any route. A client connecting to SSE before sending its first message (which is what `app.js` does in `init()`) will always receive 404. The SSE endpoint should call `getOrCreateSession(rawSessionId)` instead of `getSession(rawSessionId)`.

### 3. `Last-Event-ID` reconnection not implemented

The plan acceptance criterion: "Reconnect mid-stream with Last-Event-ID → catch-up works." The ring buffer (`WebSession.getEventsSince()`) was built for this purpose but the SSE handler never reads `Last-Event-ID` or the `lastEventId` query parameter, and never calls `getEventsSince()` before starting `collectEvents()`. Missed events are silently lost on reconnect.

### 4. `handleDone` doesn't display the assistant reply

`app.js` line 129: `handleDone(data)` calls only `setTyping(false)`. When the agent finishes, the final reply is never rendered. Additionally, `handleTextDelta` calls `appendToLastMessage()` which looks for `.message.assistant` elements — but `addMessage()` is never called to create an assistant bubble. Both streaming deltas and the final reply are invisible to the user. The chat loop cannot complete visually.

---

## Persistent Structural Deviations

### 5. MemoryRoutes / ReceiptsRoutes in wrong module (R009-08, unchanged)

`MemoryRoutes.kt` and `ReceiptsRoutes.kt` remain in `modules/channels/web` instead of `modules/gateway` as the plan specifies. The web channel module should not depend on `memory` and `security` — that dependency chain is inverted. This will cause issues when either module is used standalone.

### 6. Architecture docs not updated (R009-07, unchanged)

`docs/plan/v1-architecture.md` and the plan docs still reference `channels/api/`. The module is now `channels/channel-manager`. Either the code or the docs must be reconciled.

---

## Test Quality

Tests exist for all modules, which is progress. However, the tests are shallow. They test properties and "does not throw" cases rather than behavior. Several plan-specified test scenarios are entirely absent.

### ChannelManagerTest — routing behavior untested

The plan required:
- "Two mock channels register; messages from each route through the agent" — **absent**
- "injectChannel.send(msg) reaches the agent" — **absent**
- "A message with `is_agent_broadcast=true` is dropped" — **absent**
- `triggeringMissionId` dedup guard — **absent**

All seven tests cover registration/unregistration/start bookkeeping only. `agent.handleMessage()` is mocked but never called through the actual flow. The entire message routing pipeline (the purpose of `ChannelManager`) has zero test coverage.

### InjectChannelTest — flow relay untested

`start()` is never called in any test. The core behavior — that a `send()` message arrives on the `flow` — is not tested. The `DROP_OLDEST` overflow behavior is not tested. Three tests (`send returns false when closed`, `shutdown closes input channel`, and the identical `send returns false when closed`) essentially test the same thing.

### CliChannelTest — scripted input entirely absent

The plan specified: "Scripted input: 'hi' + scripted mock LLM → assistant text printed", "`/help` → help text printed", "Ctrl-C cancellation cancels the in-flight tool call." None of these exist. The six tests only check properties and confirm nothing throws. `start()` is never invoked. The REPL is completely untested.

### TelegramChannelTest — operator gate not tested

The most security-critical behavior in the Telegram channel is that messages from any user ID other than `operatorTelegramId` are silently dropped. The plan explicitly required: "Wrong `from.id` → dropped, no further calls." This test does not exist. None of the six tests exercise `processUpdate()` at all. The operator gate, which directly controls who can command the agent via Telegram, has zero test coverage.

### WebSessionTest — emit() never called

`session.emit()` is never called in any test. The ring buffer overflow behavior is untested. `collectEvents()` is untested. The test "event id starts at 0 and increments" only checks that a freshly-constructed `WebSseEvent` has `id = 0` — it doesn't test the ID incrementing done by `emit()`.

### GatewayTest — both tests are meaningless

- `"webChannelConfig has correct values"` — tests a data class constructor; this belongs in the config module tests, not here.
- `"gateway can be instantiated"` — `assert(gateway != null)` is always true for any non-nullable variable. This test cannot fail.

Neither test touches the actual gateway behavior: auth gating, `/health` endpoint, `/api/status` response, route registration. The plan required: "GET /health returns 200", "GET /api/status without auth → 401", "With credentials → 200". None of these are tested.

### WebChannelTest — core methods untested

`reply()` and `updateDraft()` are not tested — these are the methods that drive SSE events to the client. `emitMessage()` is not tested (it was the fix for R009-02 but has no test).

### DraftThrottlerTest — one test is confusing

`"pending text updated correctly"` sends "initial" (which fires the sender), then "updated". It then only `verify { sender.invoke(123L, 1, "initial") }` — it doesn't verify whether "updated" was sent or not. The test doesn't clearly assert the throttling behavior for the second call.

---

## Minor Issues Remaining

- No separate `Repl.kt` (acknowledged deviation, R009-23).
- Approval endpoint in `app.js` posts to `/api/approvals` (line 156), but the architecture spec says `POST /api/approval/{id}`. Neither exists as a registered route, so approvals cannot complete regardless.
