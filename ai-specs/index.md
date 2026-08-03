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
| 001 | Presence-Gated Chunk Pregeneration Scheduler | `Implemented` | `ai-specs/specs/001-chunk-pregen-scheduler.md` | `ChunkyFriends.java`, `config/ChunkyFriendsConfig.java`, `chunky/ChunkyGateway.java`, `player/PlayerPregenState.java`, `player/PlayerStateStore.java`, `scheduler/RingCurve.java`, `scheduler/PlayerSelector.java`, `scheduler/PregenScheduler.java` (renamed 2026-08-03 from the `ChunkyShared*`/`com.othstudios.chunkyshared` names the spec text below still refers to — see Agent Log) |

---

## Future Specs (Not Yet Written)

| # | Title | Status | Depends On | Notes |
|---|-------|--------|------------|-------|
| 002 | Admin Commands & Status Visibility | `Draft` | 001 | `/chunkyshared status`, `/chunkyshared reset`, etc. — explicitly out of scope for 001 |
| 003 | HUD / Sodium Settings UI | `Draft` | 001 | Only if wanted — 001 is a headless background service with no client-facing UI |
| 004 | Client Configuration GUI & Command | `Implemented (no spec)` | 001 | Ring count / max radius / linear-quadratic curve, editable from a client GUI or server-side (`/chunkyfriends config ...`, RCON-safe) — `/chunkyfriends gui` (also server-side; see its own `@implNote` for why) sends the connected player's client an instruction to open the screen. Gated by an op-level permission (`chunky-friends:config`), plus client-side detection of a missing/version-mismatched server mod. Built directly per user request, skipping the spec-first step this project otherwise follows — a spec should be retroactively written here (see Agent Log) so this has the same documentation trail as everything else |

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
| 2026-08-03 | Project renamed: com.othstudios.chunkyshared → com.onthehill.chunkyfriends | Claude (Sonnet 5) via Claude Code | User-requested rename, not a spec-driven change. Java package (`com.othstudios.chunkyshared` → `com.onthehill.chunkyfriends`), Maven group, mod ID (`chunky-shared` → `chunky-friends`, kept hyphenated per the user's earlier explicit choice to deviate from `fabric-mod-standards.md`'s snake_case rule), display name ("Chunky Shared" → "Chunky Friends"), every `ChunkyShared*` class (→ `ChunkyFriends*`), the asset folder, mixin config filenames, lang keys, the persisted config filename (`chunkyshared.json` → `chunkyfriends.json`), and the project's own root directory were all updated in one pass via ordered bulk text substitution + `git mv`, then verified with a full `./gradlew build`. Network payload channel IDs kept their existing `_v1` suffixes unchanged (that versions the payload *schema*, unrelated to the project's name). The GitHub repo itself (`OnTheHill-Studios/com.othstudios.chunkyshared`) was **not** renamed by the agent — that's an externally-visible action left for the user to trigger via `gh repo rename` or the GitHub web UI. **Deliberately not touched**: the historical prose inside `ai-specs/specs/001-chunk-pregen-scheduler.md` still refers to the old `ChunkyShared`/`chunky-shared` names throughout, since it's a record of what was true when that spec was implemented — only this index's Output Files pointers were updated to the current filenames |
