# Talos Workspace

Welcome to your Talos workspace. This directory contains all the files that Talos uses to maintain memory, identity, and context.

## Directory Structure

- `IDENTITY.md` — Your agent's persona and identity
- `MEMORY.md` — Long-term facts and preferences
- `HEARTBEAT.md` — Checklist read by the daily heartbeat routine
- `USER.md` — (optional) What Talos knows about you
- `daily/` — Daily logs created by the daily-digest routine
- `context/` — Project/topic notes the agent is allowed to read
- `projects/` — Project-scoped notes; one subdir per project
- `.system/settings/` — Machine-readable settings dual-written from the database

## Getting Started

1. Edit `IDENTITY.md` to set your agent's persona
2. Fill in `MEMORY.md` with long-term facts and preferences
3. Run `talos onboard` to complete setup

For help, run `talos --help`