# hebe

A personal autonomous agent that lives on your machine, talks to you via CLI, a self-hosted web console, or Telegram, and reasons via your own LLM gateway.

## Install

```bash
# Build from source (requires JDK 21+)
./gradlew shadowJar
sudo cp build/libs/hebe-all.jar /usr/local/lib/hebe/hebe.jar
sudo cp hebe.sh /usr/local/bin/hebe && chmod +x /usr/local/bin/hebe
```

## First run

```bash
hebe onboard          # interactive setup: LLM endpoint, Telegram (optional), admin password
hebe doctor           # verify config, LLM reachability, channel health, keychain
hebe run              # start the agent in CLI mode
```

Open `http://localhost:8765` in your browser for the web console.

## Documentation

- [Quickstart guide](docs/quickstart.md) — 10-minute happy path from clone to first chat
- [Security model](docs/security.md) — autonomy levels, receipts, plugin trust posture
- [Plugin protocol spec](docs/plugin-protocol.md) — authoring and distributing plugins
- [MCP integration guide](docs/mcp.md) — using hebe as an MCP server or client
- [Telegram setup](docs/channels/telegram.md) — BotFather to first message
- [Usage examples](docs/examples/Hebe%20Examples.md) — real-world scenarios
- [Architecture](docs/plan/v1-architecture.md) — wiring diagram, contracts, schemas
- [Specs](docs/plan/v1-specs.md) — scope contract and acceptance criteria
