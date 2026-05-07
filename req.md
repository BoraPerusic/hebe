# Requirements

This is an empty repository for now. I want to create here a Kotlin version of the *Claw agent, an autonomous agent able to receive instructions via different channels and perform actions.

## Sources

Please, analyze the Rust source code of the IronClaw and ZeroClaw at
- `Users/bora/Dev/view-only/ironclaw`
- `Users/bora/Dev/view-only/zeroclaw`

and its documentation, and prepare in this repo, in the `docs/claws/zero` and `docs/claws/iron` directories, the documentation for each of them - architecture, components, logic flow.

We will use this documentation to prepare our specification and features list.

## Tech Stack

I have included the `gradle` directory for the JVM world, but I would actually prefer Kotlin native, if possible and the libraries exist. If not, we will stick to JVM.
- Kotlin  2.3.x
- Ktor
- kotlinx serialization
- koog for agents

## Initial List of Features

This is an initial list of things I care about; we will make a better specification + feature list while brainstorming about the current features of zero+iron claws.

- WASM isolation (ironclaw style)
- extandable with new skills AND new agents (developed), modular
- channels:
- Slack
- WhatsApp
- Telegram
- email
- initial skills / tools
- file system (with ability to read + write things like markdown, json, yaml, html etc)
- wiki - read and write
- ability to call RESTful APIs
- web search
- cron-ish scheduling
- kubectl + K3s / K8s
- git + github
- memory management: IMPORTANT, multi-tier, scheduled internal management
- persistence: db: SQLite or PostgreSQL
- web console

## Tasks
1. read through the ironclaw and zeroclaw documentation and codebase and prepare our internal documentation
2. check other features on the web - openclaw (https://github.com/openclaw/openclaw) and hermes (https://github.com/nousresearch/hermes-agent) - anyting inspiring?
3. Answer the architectural question: should we go Native or stay JVM?
4. Based on the iron + zero claws, prepare a long list of features that we can / should implement, and how ("koklyp-features.md")
5. Prepare an architectural document what the solution would look like ("koklyp-architecture.md").
6. prepare a brainstorming document to start with - all your assumptions, questions, hints, pushbacks, ideas for improvement etc. We will brainstorm together to arrive to a final specification + features and final architecture.