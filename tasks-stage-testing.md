# Tasks — R009-06: Write Tests for M5 Channel Components

## Overview

The review identified that zero tests were written for the M5 channel components. This task list covers writing unit/integration tests for all channel-related classes.

## Testing Dependencies Available
- **JUnit 5** (`junit-jupiter`)
- **Kotest** assertions + property testing
- **Mockk** for mocking
- **kotlinx-coroutines-test** for coroutine testing
- **Ktor test** (for HTTP/routing tests)

## Test Strategy

### 1. InjectChannel Tests (`modules/channels/channel-manager/src/test/kotlin/...`)

**File**: `InjectChannelTest.kt`

| Test Case | Description |
|-----------|-------------|
| `send adds message to flow` | Verify `send()` emits to `flow` |
| `send returns true on success` | Verify `send()` returns `true` when channel is open |
| `send returns false when closed` | Verify `send()` returns `false` after `shutdown()` |
| `start launches coroutine` | Verify `start()` launches a coroutine that collects from inputChannel |
| `DROP_OLDEST overflow` | Verify messages are dropped when capacity exceeded |
| `shutdown closes input channel` | Verify `shutdown()` closes the channel |

### 2. CliChannel Tests (`modules/channels/cli/src/test/kotlin/...`)

**File**: `CliChannelTest.kt`

| Test Case | Description |
|-----------|-------------|
| `start creates terminal and reader` | Verify terminal and LineReader are created |
| `reply prints to terminal` | Verify `reply()` writes to terminal |
| `supportsDraftUpdates returns true` | CLI supports draft updates |
| `updateDraft clears and redraws line` | Verify `updateDraft()` calls widgets |
| `double ctrl-c closes channel` | Verify double Ctrl-C calls `shutdown()` |
| `parseInput creates correct message` | Verify input parsing produces correct `IncomingMessage` |

**Note**: CliChannel uses JLine which requires a real terminal. Consider testing the parsing/logic separately from terminal interaction, or use a mock terminal.

### 3. WebChannel + WebSession Tests (`modules/channels/web/src/test/kotlin/...`)

**Files**:
- `WebChannelTest.kt`
- `WebSessionTest.kt`

**WebChannelTest**:
| Test Case | Description |
|-----------|-------------|
| `start returns incomingMessages flow` | Verify `start()` returns the internal flow |
| `emitMessage sends to flow` | Verify `emitMessage()` emits to `incomingMessages` |
| `reply emits done event` | Verify `reply()` emits `done` to session |
| `updateDraft emits textDelta` | Verify `updateDraft()` emits `textDelta` to session |

**WebSessionTest**:
| Test Case | Description |
|-----------|-------------|
| `emit increments event id` | Verify events get sequential IDs |
| `ring buffer evicts oldest` | Verify buffer size limit works |
| `getEventsSince filters correctly` | Verify catch-up works |
| `collectEvents collects all events` | Verify flow collection works |

### 4. TelegramChannel Tests (`modules/channels/telegram/src/test/kotlin/...`)

**File**: `TelegramChannelTest.kt`

| Test Case | Description |
|-----------|-------------|
| `start initializes client` | Verify Telegram client is initialized |
| `reply sends message` | Verify `reply()` calls sendMessage |
| `processUpdate filters unauthorized` | Verify only operator messages are processed |
| `operator gate allows correct user` | Verify operator ID check works |
| `updateDraft calls throttler` | Verify `updateDraft()` uses throttler |
| `shutdown flushes throttler` | Verify `shutdown()` calls `flushAll()` |

**Mocking Strategy**: Mock `JettyTelegramClient` for testing send/edit operations

### 5. DraftThrottler Tests (`modules/channels/telegram/src/test/kotlin/...`)

**File**: `DraftThrottlerTest.kt`

| Test Case | Description |
|-----------|-------------|
| `immediate throttle skipped if interval not passed` | Verify rate limiting |
| `immediate throttle sent if chars exceed threshold` | Verify char-delta override |
| `pending text updated correctly` | Verify pending state management |
| `flushAll clears pending` | Verify flush |

### 6. ChannelManager Tests (`modules/channels/channel-manager/src/test/kotlin/...`)

**File**: `ChannelManagerTest.kt`

| Test Case | Description |
|-----------|-------------|
| `register adds channel` | Verify channels are stored |
| `unregister removes channel` | Verify channels are removed |
| `start launches all channels` | Verify all registered channels' `start()` is called |
| `message routed to agent` | Verify messages reach `agent.handleMessage()` |
| `reply routed to correct channel` | Verify replies go to correct channel |
| `recursion guard drops duplicate missions` | Verify mission guard logic |
| `agent broadcast dropped` | Verify `isAgentBroadcast` messages are dropped |
| `shutdown closes all channels` | Verify cleanup on shutdown |

### 7. ChannelRegistry Tests (`modules/channels/channel-manager/src/test/kotlin/...`)

**File**: `ChannelRegistryTest.kt`

| Test Case | Description |
|-----------|-------------|
| `register stores channel` | Verify channel is stored |
| `unregister removes and returns channel` | Verify removal works |
| `get returns stored channel` | Verify retrieval works |
| `getAll returns all channels` | Verify collection works |
| `getAllHealth checks all channels` | Verify health check delegation |
| `clear shuts down and removes all` | Verify cleanup |

### 8. Gateway Integration Tests (`modules/gateway/src/test/kotlin/...`)

**File**: `GatewayTest.kt`

**Requires**: Ktor test engine (`io.ktor:ktor-server-test-host`)

| Test Case | Description |
|-----------|-------------|
| `GET /health returns OK` | Health endpoint works |
| `GET /api/status requires auth` | Verify 401 without credentials |
| `GET /api/status with auth returns status` | Verify status endpoint with basic auth |
| `static files served from classpath` | Verify CSS/JS files served correctly |
| `uptime increases over time` | Verify uptime calculation |
| `unauthenticated request to /api/* returns 401` | Auth gate works |

### 9. Routes/WebChannel Integration Tests (`modules/channels/web/src/test/kotlin/...`)

**File**: `RoutesTest.kt`

**Requires**: Ktor test engine

| Test Case | Description |
|-----------|-------------|
| `POST /api/messages creates message and emits to channel` | Message routing works |
| `GET /api/sessions/{id}/events streams events` | SSE streaming works |
| `GET /api/sessions/{id}/events with invalid session returns 404` | Error handling |
| `POST /api/messages with invalid JSON returns 400` | Input validation |
| `SSE data format is valid JSON` | Verify `buildSseData` produces valid JSON |

### 10. MemoryRoutes Tests (`modules/channels/web/src/test/kotlin/...`)

**File**: `MemoryRoutesTest.kt`

**Requires**: Ktor test engine + mock `MemoryStore`

| Test Case | Description |
|-----------|-------------|
| `GET /api/memory/tree?prefix=... returns docs` | Tree endpoint works |
| `GET /api/memory/doc?path=... returns content` | Doc endpoint works |
| `GET /api/memory/doc without path returns 400` | Validation |
| `GET /api/memory/doc with invalid path returns 404` | Not found handling |

## Implementation Notes

### Coroutine Testing
Use `runTest` from `kotlinx.coroutines.test`:
```kotlin
@Test
fun `test name`() = runTest {
    // test code
}
```

### Mockk Usage
```kotlin
val mockChannel = mockk<Channel>(relaxed = true)
every { mockChannel.name } returns "test"
coEvery { mockChannel.start(any()) } returns flowOf(message)
```

### Ktor Test Application
```kotlin
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

withTestApplication {
    routing {
        Routes.register(this, webChannel)
    }
    // test requests
}
```

## Execution Order

1. **Phase 1**: InjectChannel + ChannelRegistry (simple, no external deps)
2. **Phase 2**: ChannelManager (depends on InjectChannel + Registry)
3. **Phase 3**: CliChannel (logic-only tests, terminal mocked)
4. **Phase 4**: DraftThrottler (simple class, no deps)
5. **Phase 5**: TelegramChannel (mock Telegram client)
6. **Phase 6**: WebChannel + WebSession (in-memory, no I/O)
7. **Phase 7**: Routes + WebChannel integration (Ktor test engine)
8. **Phase 8**: Gateway integration (full Ktor test)

## Verification

After implementation, run:
```bash
./gradlew :modules:channels:channel-manager:test :modules:channels:cli:test :modules:channels:web:test :modules:channels:telegram:test :modules:gateway:test
```

All tests should pass with `BUILD SUCCESSFUL`.