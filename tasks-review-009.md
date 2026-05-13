# Tasks — Review 009 (M5 Channels)

## Critical Blockers (must fix before M5 can be called done)

- [ ] **R009-01** Fix SSE endpoint to use a live persistent connection — `Routes.kt` `GET /api/sessions/{sessionId}/events` must use Ktor's SSE/respondTextWriter pattern to hold the connection and collect from `session.events` Flow, not dump buffered events and return
- [ ] **R009-02** Wire POST /api/messages to the WebChannel flow — create a `MutableSharedFlow` or channel inside `WebChannel`, have `start()` return it, and have the POST handler emit to it so messages reach the agent
- [ ] **R009-03** Apply `authenticate("admin") { }` to all `/api/*` routes in `Gateway.kt` — currently all API endpoints are publicly accessible
- [ ] **R009-04** Implement `editMessageText` in `TelegramChannel` — the method is a no-op debug stub; wire it to the actual Telegram `EditMessageText` API call so draft updates work
- [ ] **R009-05** Fix SSE data format to match spec — `buildSseData()` must produce valid JSON (`{"text": "..."}`) not plain key-value text; `app.js` parses it with `JSON.parse()`
- [ ] **R009-06** Write tests for every task: `ChannelManager` (message routing, recursion guards), `InjectChannel`, `CliChannel` (scripted input), `WebChannel`/`Routes` (Ktor TestApplication), `TelegramChannel` (operator gate, mock API), `Gateway` (health, auth), `ReceiptsRoutes`, `MemoryRoutes`

## Architecture Deviations to Resolve

- [ ] **R009-07** Update `v1-architecture.md` directory tree to reflect `channels/channel-manager` rename (or revert to `channels/api`) — pick one canonical name and apply it everywhere
- [ ] **R009-08** Move `MemoryRoutes.kt` and `ReceiptsRoutes.kt` to `modules/gateway` — they are in `modules/channels/web` but the plan places them in `gateway`
- [ ] **R009-09** Create `static/memory.js` — the memory browser frontend (M5.T6) has no JavaScript; the feature is invisible to the user
- [ ] **R009-10** Fix memory API endpoint paths to match spec: `GET /api/memory/tree?prefix=…` and `GET /api/memory/doc?path=…` (singular, query param, not path segment)

## Structural / Quality Issues

- [ ] **R009-11** Change `InjectChannel` to use `DROP_OLDEST` overflow — current `Channel<IncomingMessage>(capacity)` suspends senders; plan requires `DROP_OLDEST`; use `Channel(capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)`
- [ ] **R009-12** Eliminate `runBlocking` from `ChannelRegistry` — all methods should be `suspend fun` (or use `ConcurrentHashMap` without a coroutine mutex for simple read/write)
- [ ] **R009-13** Fix leaked coroutine scopes — `DraftThrottler.throttle()` and `TelegramChannel.processUpdate()` create `CoroutineScope(Dispatchers.IO)` with no parent; thread a `CoroutineScope` parameter from `TelegramChannel.start(scope)`
- [ ] **R009-14** Fix uptime calculation in `/api/status` — capture `serverStartMs = System.currentTimeMillis()` when `Gateway.start()` is called, not at request time
- [ ] **R009-15** Serve static files from classpath, not filesystem path — replace `File("modules/channels/web/src/main/resources/static/$filename")` with `javaClass.classLoader.getResourceAsStream("static/$filename")` in `Gateway.serveFile()`
- [ ] **R009-16** Unify `ChannelRegistry` and `ChannelManager` channel stores — both maintain independent maps; `ChannelManager` should delegate to `ChannelRegistry` or the registry should be removed
- [ ] **R009-17** Move `TelegramUpdateConsumer` interface out of `TelegramWebhookRoute` — it belongs in `TelegramChannel.kt` to avoid a channel class depending on a routing class for its own interface
- [ ] **R009-18** Fix `handleTextDelta` in `app.js` to append streaming text to the active message bubble
- [ ] **R009-19** Close `inputChannel` on double Ctrl-C in `CliChannel` — currently `replJob?.cancel()` is called but the channel stays open; call `shutdown()` instead
- [ ] **R009-20** Remove auth fallback that accepts `"admin"` as plaintext password — if `secretStore.get(passwordSecret)` returns null, deny access (don't silently downgrade security)
- [ ] **R009-21** Remove the spurious `/api/webhooks/telegram/invalid` hardcoded 404 route in `TelegramWebhookRoute` — it's dead code that adds noise

## Minor

- [ ] **R009-22** Separate `DraftThrottler` into its own file (`DraftThrottler.kt`) as specified in the plan
- [ ] **R009-23** Create `Repl.kt` as a separate class (or document the intentional merge in the deviation log) — the CLI REPL logic being in `CliChannel` is arguably acceptable but deviates from the plan
- [ ] **R009-24** Extend Telegram Markdown escaping to include backticks and parentheses (MarkdownV2 characters) to prevent rendering errors
