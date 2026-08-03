# AI Specs Index

All specs for this package, in dependency order.

---

## Design Foundation

| # | Title | Status | Spec File |
|---|-------|--------|-----------|
| 000 | Mod Design | `Draft` | *(not yet authored — design rationale lives in prior conversation history rather than a written spec)* |

---

## Implementation Specs

| # | Title | Status | Spec File | Output Files |
|---|-------|--------|-----------|--------------|
| 001 | Presence-Gated Chunk Pregeneration Scheduler | `Implemented` | `ai-specs/specs/001-chunk-pregen-scheduler.md` | `ChunkyShared.java`, `config/ChunkySharedConfig.java`, `chunky/ChunkyGateway.java`, `player/PlayerPregenState.java`, `player/PlayerStateStore.java`, `scheduler/RingCurve.java`, `scheduler/PlayerSelector.java`, `scheduler/PregenScheduler.java` |

---

## Future Specs (Not Yet Written)

| # | Title | Status | Depends On | Notes |
|---|-------|--------|------------|-------|
| 002 | Admin Commands & Status Visibility | `Draft` | 001 | `/chunkyshared status`, `/chunkyshared reset`, etc. — explicitly out of scope for 001 |
| 003 | HUD / Sodium Settings UI | `Draft` | 001 | Only if wanted — 001 is a headless background service with no client-facing UI |
| 004 | Client Configuration GUI & Command | `Implemented (no spec)` | 001 | Ring count / max radius / linear-quadratic curve, editable from a client GUI (`/chunkyshared config` client command) or server-side (`/chunkyshared config ...`, RCON-safe), gated by an op-level permission (`chunky-shared:config`), plus client-side detection of a missing/version-mismatched server mod. Built directly per user request, skipping the spec-first step this project otherwise follows — a spec should be retroactively written here (see Agent Log) so this has the same documentation trail as everything else |

---

## Dependency Graph

```
001 (Presence-Gated Chunk Pregeneration Scheduler) ← foundation; everything else builds on this
```

---

## Agent Log

| Date | Event | Agent | Notes |
|------|-------|-------|-------|
| 2026-08-02 | Spec 001 authored | Claude (Anthropic) via Cowork | Covers the ring-tier scheduler, min-tier-first selection with tie-break, presence-gated pause/resume, and `GenerationProgressEvent.complete()`-based completion detection worked out in design discussion |
| 2026-08-02 | Spec 001 implemented | Claude (Sonnet 5) via Claude Code | All Create-listed files added; `./gradlew build` and unit tests pass. Manual (live-server) acceptance criteria not exercised — no running dedicated server with Chunky available. See spec's Post-Implementation Notes for deviations |
| 2026-08-02 | "004" client config GUI/command built without a spec | Claude (Sonnet 5) via Claude Code | User requested a client-side config GUI plus CLI parity plus client/server version-mismatch handling directly in chat; user explicitly chose "implement directly" over "draft a spec first" when asked. Added `network/ConfigRequestPayload`, `network/ConfigStatePayload`, `network/ConfigUpdatePayload`, `network/ConfigNetworking`, `command/ChunkySharedCommand`, `client/network/ConfigNetworkingClient`, `client/screen/ChunkySharedConfigScreen`; modified `ChunkyShared`, `ChunkySharedClient`, `ChunkySharedConfig`, `en_us.json`. Builds and tests pass; not manually verified against a live client+server pair. Someone should retroactively author a real 004 spec from this row so it gets the same rigor (data contract, acceptance criteria, test requirements) as 001 |
