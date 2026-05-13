# Review 009 — M5 Channels Implementation

**Branch**: v1-development  
**Stage**: M5 (ChannelManager, CLI, Web, Telegram, healthCheck)  
**Verdict**: **Not Done** — multiple critical functional gaps, zero tests, and several architectural deviations.

---

## Overview

M5 creates the channel layer: a `ChannelManager` that fans out to CLI, Web (Ktor + SSE), and Telegram channels, plus a web gateway with auth and a static chat UI. The developer declares all 10 tasks complete. The review finds this claim incorrect: the SSE endpoint is broken (one-shot dump, not a live stream), the web channel never routes messages to the agent, auth is not wired to any route, `editMessageText` is a no-op, streaming text is not rendered in the UI, and zero tests were written despite every task requiring them.

---

## Summary of Deviations from the Plan

### 1. Module renamed without architecture update
**Tasks**: M5.T1  
The plan specifies `modules/channels/api/` throughout. The implementation uses `modules/channels/channel-manager/`. The progress note acknowledges this but the architecture docs (`v1-architecture.md` §directory tree, §79) still reference `channels/api`. This is an undocumented deviation that will confuse future stages.

### 2. Missing planned files
Several files specified in the plan were not created, with logic merged elsewhere:

| Planned file | Status | Where logic went |
|---|---|---|
| `channels/cli/Repl.kt` | Missing | Merged into `CliChannel.kt` |
| `gateway/auth/BasicAuthPlugin.kt` | Missing | Inlined in `Gateway.kt` |
| `gateway/StaticRoutes.kt` | Missing | Inlined in `Gateway.kt` |
| `gateway/StatusRoute.kt` | Missing | Inlined in `Gateway.kt` |
| `channels/telegram/UpdatePoller.kt` | Missing | Library-handled; no custom class |
| `channels/telegram/DraftThrottler.kt` | Missing | Embedded in `TelegramChannel.kt` |
| `gateway/MemoryRoutes.kt` | Wrong module | Created in `channels/web` instead |
| `gateway/ReceiptsRoutes.kt` | Wrong module | Created in `channels/web` instead |
| `static/memory.js` | **Missing entirely** | Not created anywhere |

`memory.js` being entirely absent means the memory browser UI (M5.T6) has no frontend.

### 3. **CRITICAL** — SSE endpoint is broken
**Task**: M5.T4  
`Routes.kt` line 86–125: the `GET /api/sessions/{sessionId}/events` handler dumps buffered events as a plain HTTP response and returns. This is not SSE. Server-Sent Events requires a persistent connection where the server pushes events over time. The correct implementation needs Ktor's `respondSse` (or `call.respondTextWriter` with keep-alive) to hold the connection open and collect from `session.events` flow.

The current code is a one-shot "catch-up" dump — it cannot deliver streaming text or approvals in real time.

### 4. **CRITICAL** — POST /api/messages never reaches the agent
**Task**: M5.T4  
`Routes.kt` creates an `IncomingMessage` but never sends it anywhere. `WebChannel.start()` returns an empty flow (`flow {}`). There is no mechanism connecting the POST handler to the channel's flow. Messages sent by the browser disappear into the void.

### 5. **CRITICAL** — Auth not applied to any route
**Task**: M5.T3  
`Gateway.kt` calls `installAuth(secretStore, ...)` to configure the `basic("admin")` provider, but no routes are wrapped in `authenticate("admin") { }`. Every `/api/*` endpoint is publicly accessible. The plan acceptance criterion explicitly states: "Basic auth gates `/api/*`".

### 6. **CRITICAL** — `editMessageText` is a stub
**Task**: M5.T8  
`TelegramChannel.kt` line 260–268: `editMessageText()` logs a debug message and returns null. This means `updateDraft` (which calls `draftThrottler.throttle()` → `editMessageText`) never edits any message. Draft updates on Telegram are silently dropped.

### 7. **CRITICAL** — Zero tests
Every task (T1–T10) required unit or integration tests. All test directories contain only `.gitkeep`. No tests exist for `ChannelManager`, `InjectChannel`, `CliChannel`, `WebChannel`, `TelegramChannel`, `Gateway`, or any route.

---

## Code Quality Issues

### 8. SSE data format is wrong
**Task**: M5.T4  
`Routes.buildSseData()` produces output like `text: hello\napprovalId: xyz` — plain key-value pairs, not JSON. The spec (`v1-architecture.md` §14) requires `data: {"text": "hello"}`. The `app.js` client does `JSON.parse(e.data)` (line 57), which will throw on this format.

### 9. `ChannelRegistry` uses `runBlocking` throughout
`ChannelRegistry.kt` wraps every method in `runBlocking { mutex.withLock { ... } }`. In a coroutine application, `runBlocking` on the main or coroutine thread is a deadlock hazard and blocks threads. The API should be `suspend fun` or use `ConcurrentHashMap` directly (which doesn't need a mutex for reads).

### 10. `DraftThrottler` and `processUpdate` spawn untracked coroutine scopes
`TelegramChannel.kt` line 215 and `DraftThrottler.kt` line 310: `CoroutineScope(Dispatchers.IO).launch { }` creates scopes with no parent. These are leaked coroutines — they survive shutdown, cannot be cancelled, and accumulate on errors. Both must take a `CoroutineScope` parameter from the caller.

### 11. `InjectChannel` doesn't implement DROP_OLDEST
The plan specifies `Channel<IncomingMessage>(capacity = 64, overflow = DROP_OLDEST)`. The implementation uses `Channel<IncomingMessage>(capacity)` with default `SUSPEND` semantics. Under backpressure, senders block instead of dropping oldest messages, which can deadlock background producers.

### 12. `/api/status` uptime is always ~0ms
`Gateway.kt` line 53: `val startTime = System.currentTimeMillis()` is captured at request time. `System.currentTimeMillis() - startTime` will always be near zero. The server start time must be captured when `start()` is called.

### 13. Static files served via hardcoded source-tree path
`Gateway.kt` line 86: `File("modules/channels/web/src/main/resources/static/$filename")` resolves relative to the working directory at runtime. This works only if the process is launched from the repo root. In any deployed/packaged scenario the file is not there. Static resources must be loaded from the classpath: `javaClass.classLoader.getResourceAsStream("static/$filename")`.

### 14. `ChannelManager` vs `ChannelRegistry` — redundant channel stores
Both classes maintain their own `Map<String, Channel>`. The `ChannelManager` never uses `ChannelRegistry`; they are entirely disconnected. The plan implies one unified registry. Having two independent stores will cause synchronization issues.

### 15. `TelegramUpdateConsumer` interface nested inside routing class
`TelegramWebhookRoute.kt` line 17: the `TelegramUpdateConsumer` interface is nested inside `TelegramWebhookRoute`. `TelegramChannel` needs to implement this interface, creating an inversion where a channel class depends on a routing class to get its interface. The interface belongs in the `TelegramChannel` file or a shared model.

### 16. `app.js` `handleTextDelta` doesn't render text
`app.js` line 111–113: `handleTextDelta()` only calls `setTyping(false)`. It doesn't append partial text to a message bubble. Streaming text is invisible to the user. This is a complete gap in the acceptance criterion "streaming visible."

### 17. `TelegramWebhookRoute` has a spurious `/api/webhooks/telegram/invalid` route
Line 35–39: a hardcoded route that catches `...invalid` paths and returns 404. This is dead code. Any unknown path already returns 404; adding this route adds confusion and shows up in route introspection.

### 18. Memory API routes deviate from spec
**Task**: M5.T6  
- Plan/spec: `GET /api/memory/tree?prefix=…` → returns tree  
- Implemented: `GET /api/memory/docs` → calls `memoryStore.listDocs()`  
- Plan/spec: `GET /api/memory/doc?path=…` (singular)  
- Implemented: `GET /api/memory/docs/{...path}` (plural, path segment)

The endpoint paths and parameter styles don't match the architecture document or the task spec.

### 19. Ctrl-C double-tap exits REPL without closing the channel
`CliChannel.kt` line 104: on double Ctrl-C, `replJob?.cancel()` is called but `inputChannel` is never closed. The flow returned by `start()` remains open indefinitely. `shutdown()` should be called instead.

### 20. Auth fallback allows plain `"admin"` password
`Gateway.kt` line 114–116: if `secretStore.get(passwordSecret)` returns null (key not found), the auth falls back to accepting password `"admin"` in cleartext. This is a dangerous default — a misconfigured secrets store silently opens the gateway.

---

## What Works Well

- `ChannelManager` message routing and recursion guards (`isAgentBroadcast`, `triggeringMissionId`) are correctly structured and readable.
- `WebSession` ring buffer with `lastEventId` catch-up is a good design (though unused due to #3 above).
- `DraftThrottler` throttling logic (interval + char-delta) matches the spec — the issue is only the coroutine scope leak and the no-op `editMessageText`.
- `TelegramChannel` operator gate (line 187) is correctly implemented.
- `ReceiptsRoutes` file listing and verification logic is solid.
- Markdown escaping in `TelegramChannel.formatMarkdown()` is present (though incomplete — backticks and parentheses are not escaped).
- The `WebSseEvent` data class has a clean set of factory methods.
