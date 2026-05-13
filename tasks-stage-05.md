# M5 — Channels Implementation Plan

## Overview
Implements: `ChannelManager`, CLI REPL, Web Console (Ktor + SSE + chat UI + memory browser + receipts viewer), Telegram (long-poll + webhook), `healthCheck()`.

**Done when:** a chat works end-to-end through CLI, web, and Telegram; an approval prompt round-trips on each.

---

## Task List

### M5.T1 — `channels/api`: `ChannelManager`, `injectChannel`, recursion guards
- [ ] Create `ChannelManager.kt` - merges N channel flows into one
- [ ] Create `InjectChannel.kt` - Channel<IncomingMessage> with capacity 64, DROP_OLDEST overflow
- [ ] Create `ChannelRegistry.kt` - registry for channels
- [ ] Implement recursion guards (is_agent_broadcast filtering, triggeringMissionId dedup)
- [ ] Write unit tests for ChannelManager
- [ ] Write unit tests for InjectChannel

### M5.T2 — CLI channel + REPL
- [ ] Update `modules/channels/cli/build.gradle.kts` with dependencies (jline, api, channels:api, core)
- [ ] Create `CliChannel.kt` implementing Channel interface
- [ ] Create `Repl.kt` with blocking REPL, slash-commands support
- [ ] Implement Ctrl-C semantics (single = cancel turn, double = exit)
- [ ] Write unit tests with scripted input

### M5.T3 — Web `gateway` skeleton (Ktor + HTTP Basic)
- [ ] Update `modules/gateway/build.gradle.kts` with Ktor dependencies
- [ ] Create `Gateway.kt` with Ktor server setup
- [ ] Create `BasicAuthPlugin.kt` for HTTP Basic auth against secrets.db
- [ ] Implement `/health` endpoint (no auth)
- [ ] Wire routing for later tasks
- [ ] Write unit tests

### M5.T4 — Web SSE `/api/sessions/{id}/events`
- [ ] Update `modules/channels/web/build.gradle.kts` with dependencies
- [ ] Create `WebChannel.kt` implementing Channel interface with SSE support
- [ ] Create `Routes.kt` for POST /api/messages and GET /api/sessions/{id}/events
- [ ] Implement per-session SSE flows with ring buffer (last 32 events)
- [ ] Support `Last-Event-ID` header for reconnection
- [ ] Write tests with Ktor TestApplication

### M5.T5 — Web chat UI
- [ ] Create `static/index.html` - chat shell with 3-panel layout
- [ ] Create `static/app.js` - vanilla JS for EventSource handling, streaming text, approval modal
- [ ] Create `static/style.css` - minimal styling
- [ ] Create `StaticRoutes.kt` to serve static files at `/`
- [ ] Manual testing verification (out of scope for automated tests)

### M5.T6 — Web memory browser
- [ ] Create `MemoryRoutes.kt` with search, tree, doc endpoints
- [ ] Create `static/memory.js` for search box + tree view + doc panel
- [ ] GET /api/memory/search?q=...&k=... → MemoryStore.search
- [ ] GET /api/memory/tree?prefix=... → WorkspaceFs.list
- [ ] GET /api/memory/doc?path=... → MemoryStore.readDoc
- [ ] Write tests with Ktor TestApplication

### M5.T7 — Web receipts viewer
- [ ] Create `ReceiptsRoutes.kt` with receipts list and verify endpoints
- [ ] Create `static/receipts.js` for tabular view
- [ ] GET /api/receipts?since=...&limit=... → reads NDJSON files
- [ ] GET /api/receipts/verify?file=... → runs verifier
- [ ] Write tests for receipts listing and verification

### M5.T8 — Telegram channel (long-poll + draft updates + operator gate)
- [ ] Update `modules/channels/telegram/build.gradle.kts` with telegrambots dependency
- [ ] Create `TelegramChannel.kt` implementing Channel interface
- [ ] Create `UpdatePoller.kt` for long-poll loop
- [ ] Create `DraftThrottler.kt` - rate-limited editMessageText throttling (1/800ms or 80 chars)
- [ ] Implement operator gate (operator_telegram_id check)
- [ ] Write tests with MockTelegramApi

### M5.T9 — Telegram webhook variant
- [ ] Create `TelegramWebhookRoute.kt` for webhook handling
- [ ] Add mode toggle (polling vs webhook) in config
- [ ] Implement secret path validation
- [ ] Write tests with Ktor TestApplication

### M5.T10 — Channel `healthCheck()` exposed in `/api/status` and `hebe doctor`
- [ ] Create `StatusRoute.kt` with GET /api/status
- [ ] Wire channel health aggregation
- [ ] Create status aggregator function (used by both /api/status and doctor)
- [ ] Write tests for status aggregation

---

## Open Questions

1. **jline version**: Should use jline 3.27.0 as specified, but is this available in libs.versions.toml? Need to add it.
2. **Session management**: How are session IDs generated and tracked for web? Need to understand existing SessionManager.
3. **Config structure**: Need to check the HebeConfig to understand channel-specific config structures.

---

## Assumptions

1. M0.T5 (api module) is complete with Channel, IncomingMessage, OutboundMessage, etc.
2. M2.T13 (HebeAgent) is complete and handleMessage() works
3. M3.T8 (Receipts) and M3.T9 (ReceiptVerifier) are complete
4. M1.T10 (MemoryStore.search) is complete
5. M4.T5 (memory tools) is complete
6. The hebe.library convention plugin is available and working

---

## Build Order

1. **M5.T1** (channels/api) - base for all channels
2. **M5.T3** (gateway skeleton) - Ktor server base
3. **M5.T2** (CLI) - depends on M5.T1, M2.T13
4. **M5.T4** (Web SSE) - depends on M5.T3, M2.T13
5. **M5.T5** (Web chat UI) - depends on M5.T4
6. **M5.T6** (Web memory) - depends on M5.T3, M1.T10
7. **M5.T7** (Web receipts) - depends on M5.T3, M3.T8
8. **M5.T8** (Telegram polling) - depends on M5.T1, M0.T9
9. **M5.T9** (Telegram webhook) - depends on M5.T8, M5.T3
10. **M5.T10** (healthCheck) - depends on M5.T2, M5.T5, M5.T8
