# Spec 001 — Presence-Gated Chunk Pregeneration Scheduler

**Status:** `Draft`
**Spec Author:** Claude (Anthropic) via Cowork
**Date Authored:** 2026-08-02
**Implementing AI:** [Unassigned]
**Depends On:** None

---

## Context

> Adaptation note: the template's "Component" wording is Unity-ECS-specific. This spec uses "Class/Source" in its place — there is no ECS component model in a Fabric mod.

- **Reads from:** Chunky's public API (`org.popcraft.chunky.api.ChunkyAPI`, obtained via `org.popcraft.chunky.ChunkyProvider.get().getApi()`); Fabric's `ServerPlayConnectionEvents.JOIN`/`DISCONNECT` and `ServerLifecycleEvents.SERVER_STARTED`/`SERVER_STOPPING`; the per-world persisted player state file.
- **Writes to:** `<world>/data/chunkyshared_state.json` (new file, created by this spec); Chunky's active generation task, via `ChunkyAPI.startTask`/`pauseTask`/`continueTask`.
- **Existing stubs:** None. This is the first spec for this repo. The Fabric project scaffold (`build.gradle`, `fabric.mod.json`, default entrypoint classes) already exists at the package root, generated from the standard Fabric example-mod template, but contains no mod-specific logic yet — see `## Implementation Requirements > Modify` for what in the existing scaffold needs to change.

---

## Objective

Build the background engine that keeps Chunky continuously and fairly pre-generating terrain around the friend group's most recently active play locations — paused whenever any player is online, running only while the server is empty, and always advancing whichever qualifying player's coverage is furthest behind so nobody's ring tier diverges from the group.

---

## Data Contract

### Inputs

| Source | Data Used | Access |
|---|---|---|
| `ServerPlayConnectionEvents.JOIN` / `DISCONNECT` (Fabric API) | player UUID, block position, dimension, resulting online player count | Read-only |
| `ChunkyAPI` (via `ChunkyProvider.get().getApi()`) | `isRunning(world)`, `onGenerationProgress(Consumer<GenerationProgressEvent>)` | Read-only |
| `<world>/data/chunkyshared_state.json` | persisted `Map<UUID, PlayerPregenState>` | Read/Write |
| `ChunkySharedConfig` (new, this spec) | `maxRadiusChunks`, `ringCount`, `curveExponent`, `qualifyingWindowHours`, `stallTimeoutTicks` | Read-only |

### Outputs

| Target | Effect | Notes |
|---|---|---|
| `PlayerPregenState.currentRingTier` | incremented by 1 | Only on a genuine completion (`GenerationProgressEvent.complete() == true`), never on a presence-triggered pause |
| `PlayerPregenState.lastKnownDimension` / `lastKnownX` / `lastKnownZ` | updated | On join, on disconnect, and on a periodic refresh while a player stays online (see Step 6) |
| `PlayerPregenState.lastSeenEpochMillis` | updated | On disconnect only — an online player is always treated as within the qualifying window (see Step 2) |
| `PlayerPregenState.lastServicedEpochMillis` | updated | When a job is selected for this player |
| `chunkyshared_state.json` | rewritten | On every mutation above |
| Chunky's active task | started / paused / continued | Via `ChunkyGateway`, never by constructing a raw command string |

### New Types Required

- `PlayerPregenState` — per-player persisted record: `playerUuid`, `lastSeenEpochMillis`, `lastKnownDimension` (`String`, e.g. `"minecraft:overworld"`), `lastKnownX`, `lastKnownZ`, `currentRingTier`, `lastServicedEpochMillis`.
- `ChunkySharedConfig` — GSON-backed config, fields per the Constants table below.
- `SchedulerState` — small in-memory (not persisted) holder: the active player's UUID (nullable), the active job's world, a `presencePaused` flag, and the last-received `complete()` value for the active job.
- `RingCurve` — pure utility, tier → radius.
- `PlayerSelector` — pure utility, qualifying filter + next-player selection with tie-break.

---

## Algorithm

### Step 1 — Ring radius curve

Ring tier $i$ (integer, $i = 1..N$) maps to a chunk radius using the quadratic ease-in curve settled on in design discussion:

$$radius(i) = \text{round}\left(R_{max} \times \left(\frac{i}{N}\right)^{p}\right)$$

where $R_{max}$ = `maxRadiusChunks` (default `500`), $N$ = `ringCount` (default `10`), $p$ = `curveExponent` (default `2.0`). Tier `0` means no job has ever been fired for this player yet.

### Step 2 — Qualifying pool

A player qualifies for scheduling when:

$$t_{now} - t_{lastSeen}(p) < W$$

where $W$ = `qualifyingWindowHours` × 3,600,000 ms. A currently-online player always qualifies — their `lastSeenEpochMillis` is only ever updated at disconnect, so "still online" trivially satisfies the window.

### Step 3 — Selection (runs only while the server's online player count is 0)

Among the qualifying pool $Q$, excluding any player already at `currentRingTier == ringCount` (fully covered — nothing left to grow):

$$\text{next} = \underset{p \,\in\, Q,\ tier(p) < N}{\arg\min}\ tier(p)$$

Ties are broken by oldest `lastServicedEpochMillis` (a player never yet serviced is treated as $-\infty$, i.e. serviced first). If $Q$ is empty, or every qualifying player is already at tier $N$, no job is started — this is a valid, expected steady state, not an error.

### Step 4 — Firing a job

For the selected player $p$ at `currentRingTier` $t$: compute `radius(t+1)` via `RingCurve`, then call `ChunkyGateway.startTask(lastKnownDimension, "circle", lastKnownX, lastKnownZ, radius, radius)` — the **full disk** up to the new tier's radius, not an annulus. Chunky's own already-generated-chunk skip (confirmed by reading Chunky's `GenerationTask` source: it checks an in-memory per-region bitset before touching a chunk) makes the inner, previously-completed region free to re-request, so there is no need to track or request only the new outer band.

### Step 5 — Completion detection

At mod init, subscribe once to `ChunkyAPI.onGenerationProgress`. For every event concerning the currently-tracked world, record its `complete()` value on `SchedulerState`. When the task subsequently disappears from Chunky's running-task set (`isRunning(world)` goes `false`, or no further progress events arrive):

- **If `SchedulerState.presencePaused` is `true`** — this disappearance was caused by our own `pauseTask` call (see Step 6). Do **not** advance `currentRingTier`. The player remains exactly where they were, and stays the front-runner for re-selection once the server empties again.
- **Else if the last recorded `complete()` was `true`** — genuine completion. Increment `PlayerPregenState.currentRingTier`, clear the active-player fields on `SchedulerState`, persist state, and immediately re-run Step 3 — the server is still empty, so the scheduler chains directly into the next qualifying player's job with no player-count transition required.
- **Else** (disappeared without a prior `complete=true`, and not a presence-pause — e.g. someone ran `/chunky cancel` manually) — log a warning, do not advance the tier, clear the active-player fields, and re-run Step 3.

This is the one design decision this spec is built around, so it is worth stating plainly: **`isRunning()` returning `false` and `GenerationCompleteEvent` firing both happen identically whether a task was paused, cancelled, or genuinely finished** — neither one distinguishes the three. Only `GenerationProgressEvent.complete()` does, because it is only ever `true` when the task's chunk iterator is actually exhausted. Every completion check in this system must key off that flag, never off task-map absence alone.

A `stallTimeoutTicks` safety net (default `12000` ticks / 10 minutes) applies only to the *absence of any progress event at all* since a job was fired. If that timeout elapses, log a warning — do not advance the tier and do not treat it as completion. Genuine completion is only ever signaled by an explicit `complete=true` event.

### Step 6 — Presence-driven pause / resume

- **On `ServerPlayConnectionEvents.JOIN`:** if the server's online player count was `0` immediately before this join (i.e., this join takes it to 1+) and a job is currently active, call `ChunkyGateway.pauseTask` on the active job's world and set `SchedulerState.presencePaused = true`. Update the joining player's `lastKnownDimension`/`X`/`Z` (leave `lastSeenEpochMillis` untouched — see Step 2). While any player remains online, periodically (e.g. once per `checkIntervalTicks`, mirroring the existing project family's polling cadence) refresh each online player's last-known position so a long session doesn't leave stale coordinates on disk.
- **On `ServerPlayConnectionEvents.DISCONNECT`:** update that player's `lastSeenEpochMillis = now` and last-known dimension/position from their position at disconnect. If this disconnect brings the online count to `0`: if `SchedulerState` has an active player and `presencePaused` was `true`, call `ChunkyGateway.continueTask` for that same world (resuming Chunky's own saved progress — not restarting the tier from scratch) and clear `presencePaused`. Otherwise (no job was active before everyone left), run Step 3 fresh.

### Constants

| Constant | Value | Unit | Rationale |
|---|---|---|---|
| `DEFAULT_MAX_RADIUS_CHUNKS` | `500` | chunks | Tier-10 ceiling settled on in design discussion; configurable, can be raised later without changing the curve's shape |
| `DEFAULT_RING_COUNT` | `10` | tiers | |
| `DEFAULT_CURVE_EXPONENT` | `2.0` | — | Quadratic ease-in; exposed as a config value rather than hardcoded so a linear (`1.0`) curve is a config change, not a code change |
| `DEFAULT_QUALIFYING_WINDOW_HOURS` | `24` | hours | |
| `DEFAULT_STALL_TIMEOUT_TICKS` | `12000` | ticks (10 min) | Logging-only safety net; never used to infer completion |

---

## Implementation Requirements

> **Package layout note:** the canonical layout in `fabric-mod-standards.md` is written assuming a mod with blocks/items/entities; chunkyshared has none of those — it is a headless scheduling service. The structure below extends the standard with two new domain sub-packages (`player/`, `scheduler/`), following the same "name the package after what the code *is*" principle the standard states, in the same spirit as `config/` already getting its own package for a comparably infrastructural concern. Calling this out explicitly for review rather than silently inventing it — adjust before merge if a different split is preferred.

### Create

- **`src/main/java/com/othstudios/chunkyshared/ChunkyShared.java`**
  Package: `com.othstudios.chunkyshared`. Implements `ModInitializer`. Registers `ServerLifecycleEvents.SERVER_STARTED` (load state, call `ChunkyGateway.init()`) / `SERVER_STOPPING` (persist state), and `ServerPlayConnectionEvents.JOIN`/`DISCONNECT` (delegating to `PregenScheduler`). All public members Javadoc'd per `java-coding-standards.md`; Allman braces, 4-space indentation.

- **`src/main/java/com/othstudios/chunkyshared/config/ChunkySharedConfig.java`**
  GSON-backed singleton config, fields per the Constants table above. Loaded from `config/chunkyshared.json`; written with defaults on first run if the file does not exist.

- **`src/main/java/com/othstudios/chunkyshared/chunky/ChunkyGateway.java`**
  Thin, **directly-typed** wrapper around `org.popcraft.chunky.api.ChunkyAPI` — no reflection. Chunky is already a hard `depends` entry in `fabric.mod.json`, so compiling directly against its API is safe and removes the entire class of silent-breakage risk that reflection into a third-party mod's internals carries across version bumps. Exposes `startTask(...)`, `pauseTask(world)`, `continueTask(world)`, `isRunning(world)`, and progress-callback registration. Its Javadoc `@implNote` must state the `isRunning`/`GenerationCompleteEvent`-vs-`complete()` distinction from Step 5 — this is the one fact every future caller of this class needs to already know before touching it.

- **`src/main/java/com/othstudios/chunkyshared/player/PlayerPregenState.java`**
  Data class with the fields listed under New Types Required. Javadoc every field, including units (epoch millis, block coordinates, tier as a plain integer count).

- **`src/main/java/com/othstudios/chunkyshared/player/PlayerStateStore.java`**
  Loads/saves `Map<UUID, PlayerPregenState>` to `<world>/data/chunkyshared_state.json` via GSON. Keep the actual (de)serialization logic free of any dependency beyond `MinecraftServer` for path resolution, so it stays testable without a live server where practical.

- **`src/main/java/com/othstudios/chunkyshared/scheduler/RingCurve.java`**
  Pure static utility implementing Step 1: `radiusForTier(int tier, int ringCount, int maxRadiusChunks, double curveExponent)`. No Minecraft dependencies.

- **`src/main/java/com/othstudios/chunkyshared/scheduler/PlayerSelector.java`**
  Pure static utility implementing Steps 2–3 over a `Collection<PlayerPregenState>`, a `long nowEpochMillis`, and config values. No Minecraft dependencies.

- **`src/main/java/com/othstudios/chunkyshared/scheduler/PregenScheduler.java`**
  The Minecraft-facing orchestrator: owns `SchedulerState`, wires `ChunkyGateway`'s progress callback to Step 5, wires join/disconnect handling to Step 6, calls into `RingCurve`/`PlayerSelector` for the actual decisions. Per `java-coding-standards.md`'s testing guidance, this class is intentionally **not** unit tested directly — its correctness is validated by testing the pure logic it calls, plus the manual verification listed under Acceptance Criteria.

### Modify

- **`src/main/resources/fabric.mod.json`** — replace the placeholder `description` ("This is an example description!") and `authors` (`"Me!"`); add a `"chunky"` entry to `depends` (pin to the actual minimum compatible version once known, not `"*"`, per `fabric-mod-standards.md`'s Versioning section). Note in passing: the existing `id` (`"chunky-shared"`) uses a hyphen; `fabric-mod-standards.md`'s own Package Naming section calls for `snake_case` — worth reconciling one way or the other, though changing `id` after any release is a breaking change per that same file's Resource & Data Safety section, so better to settle it now while the project has never shipped.
- **`build.gradle`** — add a compile-time dependency on Chunky's API so `ChunkyGateway` can reference `ChunkyAPI` directly instead of reflecting into it.

---

## Test Requirements

Per `java-coding-standards.md` — JUnit 5, AAA pattern (labeled with inline comments), `methodName_stateUnderTest_expectedBehavior` naming, isolated tests (no shared mutable static state), delta comparisons for any floating-point assertion. Only `RingCurve` and `PlayerSelector` are pure enough to unit test directly; `ChunkyGateway` and `PregenScheduler` depend on live Minecraft/Chunky objects and are exercised through manual verification instead (see Acceptance Criteria) — per that same file's guidance not to mock half of `net.minecraft.*` just to exercise logic that should already be extracted into a plain class.

> This departs from the Unity template's fixed "1 happy + 2 boundary + 1 negative" test count — that count is specific to Unity's ECS system convention and has no stated Java equivalent. The tests below instead follow `java-coding-standards.md`'s own instruction to cover the zero and max bounds of any bounded/curved calculation.

### `RingCurve`
- `radiusForTier_midTier_matchesQuadraticCurve` — happy path (e.g. tier 7 of 10 at max radius 500 → 245, per the values worked out during design)
- `radiusForTier_tierZero_returnsZero` — boundary
- `radiusForTier_tierEqualsRingCount_returnsMaxRadius` — boundary (must return exactly `maxRadiusChunks`, not an off-by-one-rounded neighbor)
- `radiusForTier_negativeTier_throwsIllegalArgument` — negative/toxicity

### `PlayerSelector`
- `selectNext_multipleQualifyingAtDifferentTiers_picksLowestTier` — happy path
- `selectNext_tiedLowestTier_picksOldestLastServiced` — boundary (the tie-break rule)
- `selectNext_allPlayersAtMaxTier_returnsEmpty` — boundary
- `selectNext_noQualifyingPlayers_returnsEmpty` — negative/toxicity (every player outside the 24h window)

---

## Acceptance Criteria

- [ ] `RingCurve` and `PlayerSelector` unit tests above all pass
- [ ] No Javadoc violations on public members; no `snake_case` identifiers outside registry/asset paths (not applicable in this spec — no registries)
- [ ] Manual verification: with the server empty and one qualifying player recorded, the scheduler fires a tier-1 job for that player
- [ ] Manual verification: a player joining while a job is active pauses it — Chunky's task disappears and `PlayerPregenState.currentRingTier` is unchanged (not incremented)
- [ ] Manual verification: the last player disconnecting resumes the same paused job (Chunky's own log shows a continue, not a fresh start) when a job was active before everyone left
- [ ] Manual verification: a job that runs to full natural completion while the server stays empty increments that player's tier and immediately starts the next qualifying player's job, with no player-count change in between
- [ ] `chunkyshared_state.json` survives a server restart — ring tiers and last-known positions are unchanged across a stop/start cycle
- [ ] A player whose `lastSeenEpochMillis` is older than `qualifyingWindowHours` is excluded from selection

---

## Post-Implementation Notes

> **This section is filled in by the implementing AI after the work is done.**

**Date Implemented:** 2026-08-02
**Implementing AI:** Claude (Sonnet 5) via Claude Code

### What Was Built

All files listed under Implementation Requirements > Create: `ChunkyShared`, `ChunkySharedConfig`, `ChunkyGateway`, `PlayerPregenState`, `PlayerStateStore`, `RingCurve`, `PlayerSelector`, and `PregenScheduler`, plus JUnit 5 tests for `RingCurve` and `PlayerSelector`. `build.gradle` gained a CodeMC repository and a `compileOnly` dependency on `org.popcraft:chunky-common`, plus a JUnit 5 test setup (not present in the scaffold at all before this spec). `fabric.mod.json` gained a `"chunky"` depends entry. The full project builds (`./gradlew build`) and all 8 unit tests pass.

### Deviations from Spec

- **`startTask` needs a pattern argument.** The real `ChunkyAPI.startTask` signature is `(world, shape, centerX, centerZ, radiusX, radiusZ, pattern)` — seven arguments, not the six in the spec's Step 4 pseudocode. `ChunkyGateway.startTask` fixes the shape to `ShapeType.CIRCLE` and the pattern to `PatternType.CONCENTRIC` internally and exposes a 4-arg `(world, centerX, centerZ, radiusChunks)` method to callers.
- **Completion detection is event-driven, not polling.** Step 5 describes the disappearance condition abstractly ("`isRunning` goes false, or no further progress events arrive"). `ChunkyAPI` actually exposes a dedicated `onGenerationComplete(Consumer<GenerationCompleteEvent>)` callback that fires exactly at that disappearance point for any cause (pause/cancel/finish) — `PregenScheduler` wires that callback directly instead of polling `isRunning()` on a tick loop, which is a strictly more precise implementation of the same rule Step 5 describes (never inferring completion from anything but `GenerationProgressEvent.complete()`).
- **Added `checkIntervalTicks` to `ChunkySharedConfig`.** Step 6 requires a periodic online-position refresh "once per `checkIntervalTicks`," but the Data Contract's `ChunkySharedConfig` field list omits it. Added as a sixth config field, default `200` ticks (10 seconds).
- **`SchedulerState` is a private nested class of `PregenScheduler`**, not its own file. It's listed under "New Types Required" but not under Implementation Requirements > Create, and per `spec-workflow.md` only files listed under Create should be created — nesting it keeps the file list exact while still implementing the type.
- **Chunky's Maven coordinates, repository, and Fabric mod id** (`org.popcraft:chunky-common` via `https://repo.codemc.io/repository/maven-public/`, mod id `chunky`) aren't stated in the spec and were sourced independently from Chunky's public GitHub source. Pinned to `1.3.38`, the most recent version confirmed against public sources at implementation time — no Minecraft 26.2-targeted Chunky build could be confirmed to exist, so this pin should be revisited once one is published.
- **`fabric.mod.json`'s `id` was left as `chunky-shared`** (hyphenated) rather than reconciled to `snake_case`. The spec flagged this explicitly for review; asked directly, the answer was to leave it as-is.

### Issues Encountered

- This project's Minecraft 26.2 / Fabric Loom setup compiles against Mojang-style class names (`ServerPlayer`, `ServerGamePacketListenerImpl`, `MinecraftServer`, `LevelResource.DATA`, `net.minecraft.resources.Identifier`, etc.) rather than Yarn names, and this Minecraft version has no public documentation. Exact signatures (`Entity.getX()/getY()/getZ()`, `ServerGamePacketListenerImpl.getPlayer()`, `MinecraftServer.getWorldPath(LevelResource)`, `Level.dimension().identifier()`, Fabric API's `ServerPlayConnectionEvents`/`ServerLifecycleEvents`/`ServerTickEvents` shapes) were confirmed by inspecting the locally cached deobfuscated Minecraft jar and Fabric API sources jars in the Gradle cache rather than from any published reference — the project compiles and its tests pass, but there was no running dedicated server available to exercise the manual verification checklist below against.
- Chunky's own wiki does not document its Fabric-specific API access path; `ChunkyAPI`/`ChunkyProvider`/`GenerationProgressEvent`/`GenerationCompleteEvent` signatures were confirmed by reading Chunky's GitHub source directly (`pop4959/Chunky`, `common` module).

### Suggested Follow-Up Specs

- Confirm/repin the real minimum-compatible Chunky version (`chunky_version` in `gradle.properties`, and the `"chunky"` depends entry in `fabric.mod.json`) once a Minecraft 26.2-targeted Chunky release actually exists — the current `1.3.38` pin is a best-effort placeholder.
- The manual verification items in Acceptance Criteria below need a live dedicated server with Chunky installed to actually exercise; none of that environment was available during this implementation pass. A follow-up pass (or a lightweight game-test harness) should run through them before this ships.
