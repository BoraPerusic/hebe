# Progress — Review 009 Fixes (M5 Channels)

## Completed Fixes

### Critical Blockers (R009-01 through R009-05)
- **R009-01**: Fixed SSE endpoint to use `respondTextWriter` for live persistent streaming
- **R009-02**: Wired POST /api/messages to WebChannel flow via `emitMessage()` method
- **R009-03**: Applied `authenticate("admin")` to all `/api/*` routes in Gateway.kt
- **R009-04**: Implemented `editMessageText` using `EditMessageText` Telegram API
- **R009-05**: Fixed SSE data format to JSON using `buildJsonObject` with `JsonPrimitive`

### Structural/Quality Issues (R009-11 through R009-24)
- **R009-07**: Updated `v1-architecture.md` to reflect `channels/channel-manager` rename
- **R009-09**: Created `static/memory.js` for memory browser frontend
- **R009-10**: Fixed memory API paths to match spec (`/api/memory/tree?prefix=...`, `/api/memory/doc?path=...`)
- **R009-11**: Changed `InjectChannel` to use `BufferOverflow.DROP_OLDEST`
- **R009-12**: Made `ChannelRegistry` methods `suspend fun` (removed `runBlocking`)
- **R009-13**: Fixed leaked coroutine scopes - `TelegramChannel.start()` now stores scope, `DraftThrottler.throttle()` takes scope parameter
- **R009-14**: Fixed uptime calculation to capture `serverStartMs` when `start()` is called
- **R009-15**: Serve static files from classpath via `javaClass.classLoader.getResourceAsStream()`
- **R009-17**: Moved `TelegramUpdateConsumer` interface to `TelegramChannel.kt`
- **R009-18**: Fixed `handleTextDelta` in `app.js` to append streaming text to message bubble
- **R009-19**: Close `inputChannel` on double Ctrl-C in CliChannel
- **R009-20**: Removed auth fallback to plaintext "admin" password
- **R009-21**: Removed spurious `/api/webhooks/telegram/invalid` route
- **R009-22**: Separated `DraftThrottler` into its own file
- **R009-24**: Extended Telegram Markdown escaping to include `(`, `)`, and backticks

## Remaining Issues

### R009-06: Write tests for all channel components
**Status**: Pending - requires significant test infrastructure setup
Tests needed for:
- ChannelManager (message routing, recursion guards)
- InjectChannel
- CliChannel (scripted input)
- WebChannel/Routes (Ktor TestApplication)
- TelegramChannel (operator gate, mock API)
- Gateway (health, auth)
- ReceiptsRoutes
- MemoryRoutes

### R009-08: Move MemoryRoutes and ReceiptsRoutes to gateway module
**Status**: Not started - requires file moves and import updates
Per the plan, these should be in `gateway` module, not `channels/web`.

### R009-16: Unify ChannelRegistry and ChannelManager
**Status**: Not started - requires architectural decision
Both maintain independent `Map<String, Channel>` stores. The plan implies one unified registry.

### R009-23: Create Repl.kt or document merge decision
**Status**: Not started - minor
CLI REPL logic is merged into CliChannel.kt. Could create separate file or document decision.

## Verification
All modified modules pass ktlint check and compile successfully:
- `:modules:channels:web`
- `:modules:channels:telegram`
- `:modules:channels:channel-manager`
- `:modules:channels:cli`
- `:modules:gateway`