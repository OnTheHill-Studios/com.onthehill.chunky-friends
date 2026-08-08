# Spec 002 — Admin Commands & Status Visibility

**Status:** `Implemented`
**Spec Author:** Claude (Anthropic) via Cowork
**Date Authored:** 2026-08-03
**Last Revised:** 2026-08-07 — folded in the config-screen map preview (background terrain, accurate chunk-boundary grid, always-visible scale legend, and example-player ring overlay); see the "Map Preview" parts of Design Decisions, Data Contract, Algorithm, Implementation Requirements, Test Requirements, and Acceptance Criteria
**Implementing AI:** Claude (Sonnet 5) via Claude Code — see Post-Implementation Notes for the full implementation and revision history, including live-client testing across 7 follow-up rounds
**Depends On:** 001

---

## Context

> What already exists that this spec builds on. Reference exact file paths relative to the package root. The implementing AI should read these files before writing any code.

- **Reads from:**
  - `src/main/java/com/onthehill/chunkyfriends/scheduler/PregenScheduler.java` — owns the single in-memory active-job state (`SchedulerState`) and the `_playerStates` map this spec reports on
  - `src/main/java/com/onthehill/chunkyfriends/scheduler/PlayerSelector.java` — owns the qualifying-window filter this spec's eligibility check must match exactly
  - `src/main/java/com/onthehill/chunkyfriends/player/PlayerPregenState.java` — per-player persisted fields (`currentRingTier`, `lastSeenEpochMillis`, `lastKnownName`, etc.)
  - `src/main/java/com/onthehill/chunkyfriends/config/ChunkyFriendsConfig.java` — `ringCount`, `qualifyingWindowHours`
  - `src/main/java/com/onthehill/chunkyfriends/command/ChunkyFriendsCommand.java` — existing `/chunkyfriends config`/`/chunkyfriends gui` command tree, permission gate (`hasPermission`), and denial-log throttling this spec's new subcommands attach alongside
  - `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` — existing client config screen this spec adds two buttons to, and — for the Map Preview part — a background panel and ring overlay
  - `src/main/java/com/onthehill/chunkyfriends/ChunkyFriends.java` — mod entrypoint wiring `PregenScheduler`/`ChunkyFriendsConfig` into the command dispatcher via deferred suppliers, and registering new network receivers
  - `src/main/java/com/onthehill/chunkyfriends/network/ConfigNetworking.java` — existing payload-type registration and server-receiver-registration pattern the Map Preview payloads follow
  - `src/main/java/com/onthehill/chunkyfriends/scheduler/RingCurve.java` — pure, no-MC-dependency utility already usable from both `main` and `client` source sets; the Map Preview reuses it as-is to compute ring radii, no server round trip needed for the circles themselves
  - `src/main/resources/assets/chunky-friends/lang/en_us.json` — existing lang key conventions

- **Writes to:**
  - `src/main/java/com/onthehill/chunkyfriends/scheduler/PregenScheduler.java` (modify)
  - `src/main/java/com/onthehill/chunkyfriends/scheduler/PlayerSelector.java` (modify)
  - `src/main/java/com/onthehill/chunkyfriends/command/ChunkyFriendsCommand.java` (modify)
  - `src/main/java/com/onthehill/chunkyfriends/ChunkyFriends.java` (modify)
  - `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` (modify)
  - `src/main/resources/assets/chunky-friends/lang/en_us.json` (modify)
  - `src/main/java/com/onthehill/chunkyfriends/network/` (new payload/handler files — Map Preview)
  - `src/main/java/com/onthehill/chunkyfriends/scheduler/` (new terrain sampler + example-player layout files — Map Preview)
  - `src/client/java/com/onthehill/chunkyfriends/client/` (new preview-texture/render-state files — Map Preview)

- **Existing stubs:** None.

---

## Objective

Add two read-only, permission-gated commands — `/chunkyfriends status` (the single currently-active pregeneration job, its ring tier, and its progress) and `/chunkyfriends players` (every currently-eligible, i.e. not-evicted-by-the-qualifying-window, player and the ring tier their pregeneration coverage has reached) — reachable identically from RCON, from a connected player typing the command, and from a button in the existing client config GUI, with every invocation also writing an INFO-level summary line to the server log regardless of how it was invoked. Also add a **map preview** to the client config screen: the real generated terrain around the world's spawn point rendered as a background image, a thin overlay grid marking that world's actual chunk boundaries, an always-visible scale legend stating what one grid square represents (so the grid stays readable as a sense of scale even when zoomed out far enough that individual chunk lines get subsampled), and the ring-tier coverage circles for three synthetic example players drawn on top of it all, so an admin can see what a `ringCount`/`maxRadiusChunks`/`curveExponent` combination actually looks like at world scale — in real chunk units, not just an abstract circle — before saving it. No uploaded or bundled sample imagery involved; the background and grid are always a live, accurate sample of that world's own generated (or ungenerated, left blank) terrain and chunk layout.

---

## Design Decisions (Read First)

- **The scheduler runs exactly one job at a time, system-wide.** `PregenScheduler` tracks a single `_activePlayerUuid`/`_activeWorld` pair (see `SchedulerState`), not one job per player. `/chunkyfriends status` therefore reports on *the* active job (naming whichever single player currently has one, if any) rather than a per-player job list — this is the existing, intentional scheduling model from Spec 001, not something this spec changes.
- **"Eligible" means "not evicted by the qualifying window," independent of ring completion.** `PlayerSelector.selectNext` currently filters candidates on two separate conditions: (1) seen within `qualifyingWindowHours`, and (2) `currentRingTier < ringCount`. `/chunkyfriends players` must reuse only condition (1) — a player who has fully completed all ring tiers but was seen recently is still "eligible" in the sense this command reports (they're just not a scheduling *candidate* anymore because they're done, which the per-player tier number already communicates). Extracting condition (1) into its own reusable method (see below) keeps this distinction explicit instead of silently baking ring-completion into "eligibility."
- **No new network protocol.** The GUI buttons this spec adds fire the exact same server command a player could type themselves (`/chunkyfriends status` / `/chunkyfriends players`) via the client's own chat-command-send API, reusing the existing permission gate, log-throttling, and RCON/console compatibility for free. This mirrors how `/chunkyfriends gui` already works the other direction (server → client instruction) without needing a third mechanism.
- **Logging is unconditional, feedback is the existing Brigadier mechanism.** "Get an output in my log" must hold true whether the command was run from RCON, from the console, or by a player. Brigadier's `sendSuccess`/`sendFailure` feedback is what RCON's response text and a player's chat line are built from, but it is not guaranteed to reach the server log file. Both new subcommands must therefore call `LOGGER.info(...)` explicitly with the same information, independent of the `sendSuccess` call.
- **Known pre-existing edge case, not in scope to fix here:** `PlayerPregenState.lastSeenEpochMillis` defaults to `0` and is only ever set on disconnect (see its Javadoc: "An online player leaves this value unchanged for the duration of their session"). A player who has never once disconnected since their state record was created (e.g. a brand-new player still in their first session) has `lastSeenEpochMillis == 0`, which — under the existing qualifying-window check — reads as "last seen at the Unix epoch," i.e. `nowEpochMillis - 0` almost certainly exceeds `qualifyingWindowHours`, so such a player would show as **not eligible** despite being online right now. This spec's `players` command surfaces the scheduler's actual understanding of eligibility byte-for-byte (see the eligibility-extraction requirement below), so this edge case will be visible in its output exactly as it already silently affects `selectNext`. Fixing it is out of scope here — see Suggested Follow-Up Specs.

### Map Preview — Additional Design Decisions

- **The preview is a curve-tuning aid on the config screen, not a live status view.** It shows *real terrain* (this world's actual generated columns, sampled fresh from the world save — never bundled or uploaded imagery), but it deliberately does **not** show real tracked players. `/chunkyfriends players`/`/chunkyfriends status` already cover "what are my real players actually doing" — the preview's job is "what would this ring shape look like at world scale," which is a question about the `ringCount`/`maxRadiusChunks`/`curveExponent` fields the admin is currently editing (possibly not yet saved), not about anyone's real position. Mixing live player state into it would make it answer two different questions badly instead of one question well.
- **Three synthetic example players, not real ones.** The preview always draws exactly three example points, spaced apart from each other, each with its own full set of ring circles computed from the screen's *currently-entered* (possibly unsaved) field values. This deliberately mirrors how the scheduler can have several players independently climbing their own ring sets, without depending on anyone actually being online, tracked, or positioned somewhere illustrative.
- **Example-player spacing scales with the currently-entered `maxRadiusChunks`, not a fixed chunk distance.** Baked-in absolute offsets (e.g. always 300 chunks apart) would look reasonable at large radii and be a meaningless, overlapping mess at a small one (or vice versa). Instead, each example player's offset from the preview center is a fixed **fraction** of the current `maxRadiusChunks` value — see the `EXAMPLE_PLAYER_OFFSET_FRACTIONS` constant below — so the layout stays visually sensible whether the admin is previewing a 20-chunk radius or an 800-chunk one, and lands at "a few hundred chunks apart" specifically around the 1000-chunk end of the supported range, matching the concrete distance requested.
- **Supported preview range: legible from a small radius up to 1000 chunks; beyond that, degrade rather than fail.** A realistic everyday `maxRadiusChunks` is in the neighborhood of 100 (used here as the reference point for choosing default sampling resolution/scale below), but long-render-distance client mods (Distant Horizons, Voxy, and similar) make radii out to 1000 chunks a real, useful thing to preview. Past 1000 chunks the preview is explicitly allowed to stop being useful — clamp the sampled coverage radius to 1000 chunks and show a short translatable note that the preview is showing a 1000-chunk-radius subset of a larger configured value, rather than trying to keep scaling the sampling/rendering approach to arbitrarily large radii.
- **Only already-generated chunks get real terrain color; everything else is blank "fog."** The server-side sampler must not trigger world generation just to fill in the preview — it checks whether a chunk already exists before sampling it (see Algorithm) and leaves ungenerated area blank. This keeps the preview cheap and side-effect-free, and as a side benefit the preview will visibly fill in around spawn as pregeneration actually progresses there over time — a bonus, not the preview's primary purpose.
- **World origin means the world's spawn point**, not literal block `(0, 0)` — `ServerLevel.getSharedSpawnPos()` (verify exact accessor name against this project's installed mappings) on the overworld. These coincide by default on a fresh world but can diverge (a moved spawn, a datapack-set spawn), and "the origin of the world" in ordinary player usage means "where you'd spawn," not the coordinate origin.
- **The scale legend states exactly what the grid it sits next to actually shows, not an independent measurement.** Rather than inventing a separate "ruler bar" with its own nice-round-number logic, the always-visible legend simply reports the same `gridStepChunks` (and its block equivalent) that Step 12a already computed and is already drawing — so the legend and the grid can never drift out of sync with each other, and reading the legend tells you exactly what one grid square on screen represents at the current zoom, whatever that happens to be.
- **The chunk grid is real chunk boundaries, subsampled when necessary — never a fake decorative grid.** "Accurate" is the operative word in the request this addresses: every line drawn must be a true 16-block chunk edge for this world, aligned to actual chunk coordinates rather than an arbitrary grid centered on the panel. At the small end of the preview range every chunk boundary is drawn; at the large end (where a single screen pixel can span several chunks) drawing every boundary would alias into visual noise, so the grid steps up to every Nth real boundary instead of turning into a wall of static — see Step 12a. This is computed entirely client-side from data the response already carries (`originBlockX`/`originBlockZ`/`blocksPerPixel`); no new network payload field is needed for it.
- **The background image is requested from the server, not computed on the client.** The client only has chunks near whichever player opened the screen loaded in memory; the region around world spawn at a 1000-chunk radius is very unlikely to be loaded client-side even if that player happens to be standing at spawn. The server is the only side that can cheaply check "does this chunk exist" and sample it, so this needs one new lightweight request/response payload pair, following the existing `ConfigNetworking`/`ConfigStatePayload` pattern rather than introducing a different networking style.

---

## Data Contract

### Inputs

| Component | Fields Used | Access |
|-----------|-------------|--------|
| `PregenScheduler` (internal `SchedulerState`) | `_activePlayerUuid`, `_activeWorld`, `_presencePaused`, new progress-cache fields (below) | Read-only |
| `PregenScheduler._playerStates` | All entries | Read-only |
| `PlayerPregenState` | `playerUuid`, `lastKnownName`, `currentRingTier`, `lastSeenEpochMillis` | Read-only |
| `ChunkyFriendsConfig` | `ringCount` | Read-only |

### Outputs

| Component | Fields Modified | Notes |
|-----------|----------------|-------|
| `PregenScheduler.SchedulerState` | New fields: `_lastProgressPercent`, `_lastProgressChunks`, `_lastProgressRate`, `_lastProgressEventEpochMillis` | Populated in the existing `onProgressEvent` handler; cleared in the existing `clearActiveJob` |
| Server log | New INFO lines from both subcommands | See Algorithm |
| Command feedback (chat/RCON) | New response text from both subcommands | See Algorithm |

### New Types Required

- `com.onthehill.chunkyfriends.scheduler.ActiveJobSnapshot` — immutable data-holder record (or a plain final class if the project's Minecraft/Java version predates convenient record use elsewhere in this codebase — check for existing `record` usage before choosing) describing the single active job at the moment it is read. Fields:
  - `UUID playerUuid`
  - `String playerDisplayName` (nullable — mirrors `PlayerPregenState.getLastKnownName()`)
  - `String world`
  - `int ringTier` (the tier currently being serviced — i.e. `currentRingTier + 1` at the time the job was started, **not** the player's persisted `currentRingTier`, which only advances on completion)
  - `int ringCount`
  - `double progressPercent`
  - `long chunks`
  - `double chunksPerSecond`
  - `boolean presencePaused`
  - `long lastProgressEventEpochMillis` (`0` if no progress event has fired yet for this job)

- `com.onthehill.chunkyfriends.network.MapPreviewRequestPayload` — client → server. Fields:
  - `int requestedRadiusChunks` — the config screen's currently-entered `maxRadiusChunks` value (not necessarily saved yet)

- `com.onthehill.chunkyfriends.network.MapPreviewResponsePayload` — server → client. Fields:
  - `int imageWidthPixels`, `int imageHeightPixels` — fixed at `MAP_PREVIEW_IMAGE_DIMENSION_PIXELS` (see Constants) for both; carried explicitly anyway so the client never has to assume a magic number matches
  - `double blocksPerPixel` — computed as `(2.0 * coverageRadiusChunks * 16) / imageWidthPixels`, where `coverageRadiusChunks = min(requestedRadiusChunks, MAP_PREVIEW_MAX_RADIUS_CHUNKS)`
  - `int originBlockX`, `int originBlockZ` — the world spawn position the image is centered on
  - `boolean clampedToMaxPreviewRadius` — `true` when `requestedRadiusChunks > MAP_PREVIEW_MAX_RADIUS_CHUNKS`, so the client can show the "showing a 1000-chunk subset" note
  - `byte[] colorGrid` — `imageWidthPixels * imageHeightPixels` bytes, row-major, one byte per pixel: either a vanilla map color byte (packed color id + shade, matching whatever encoding `net.minecraft.world.level.saveddata.maps.MapColor` uses in this project's installed mappings — verify exact packing before implementing) or the sentinel value `0` meaning "ungenerated, render as fog." Sent as-is; add gzip compression only if manual testing shows the uncompressed payload is a problem in practice — do not add compression speculatively.

- `com.onthehill.chunkyfriends.scheduler.ExamplePlayerLayout` — pure utility (no Minecraft dependency, alongside `RingCurve`/`PlayerSelector`) computing the three synthetic example players' positions.
  - `public static List<ExamplePosition> computeExamplePositions(int maxRadiusChunks)` — returns exactly three `ExamplePosition` values.
  - `ExamplePosition` (nested record or small class): `int offsetXChunks`, `int offsetZChunks` (offsets from the preview's center — i.e. from world spawn — not absolute world coordinates), `int exampleIndex` (`0`, `1`, `2`, used only to pick a distinct display color per example player).

- `com.onthehill.chunkyfriends.scheduler.ChunkGridLayout` (or a nested static helper on the same class if that reads more naturally alongside `ExamplePlayerLayout` — implementing AI's call, name it for what it computes either way) — pure utility, no Minecraft dependency, computing where chunk-grid lines fall in panel pixel space.
  - `public static int computeGridStepChunks(double blocksPerPixel)` — returns the smallest positive integer `step` such that `step * (16.0 / blocksPerPixel) >= MIN_GRID_LINE_SPACING_PIXELS`; returns some sane default (e.g. `1`) if `blocksPerPixel <= 0`, never divides by zero.
  - `public static List<Integer> computeGridLinePixelPositions(int originBlock, double blocksPerPixel, int panelSizePixels, int gridStepChunks)` — generic over one axis (called once for X, once for Z from the two different origin/offset values); returns every pixel offset within `[0, panelSizePixels)` at which a chunk boundary spaced `gridStepChunks` apart falls, aligned to true chunk boundaries relative to `originBlock` per Step 12a.1, not to the panel's own edge.

---

## Algorithm

### Step 1 — Cache the latest progress event on `PregenScheduler`

`PregenScheduler.onProgressEvent` currently only resets `_ticksSinceLastProgress`, updates `_lastCompleteFlag`, and — throttled by `progressLogIntervalSeconds` — logs a line. It does not retain the event's own values anywhere, so nothing outside that throttled log line currently knows the last progress percent/chunk count/rate. Add four fields to the existing (already-not-persisted) `SchedulerState` inner class — `_lastProgressPercent`, `_lastProgressChunks`, `_lastProgressRate`, `_lastProgressEventEpochMillis` — and set all four unconditionally at the top of `onProgressEvent`, before the existing throttle check, so `status` always has the most recent value regardless of the log-throttle window. Clear all four (to `0`) in `clearActiveJob`, alongside the fields already cleared there.

### Step 2 — Add `PregenScheduler.activeJobSnapshot()`

```
public Optional<ActiveJobSnapshot> activeJobSnapshot()
```

Returns `Optional.empty()` if `_schedulerState._activePlayerUuid == null`. Otherwise builds an `ActiveJobSnapshot` from `_schedulerState`'s fields, the corresponding `PlayerPregenState` in `_playerStates` (for `playerDisplayName`), and `_config.getRingCount()`. `ringTier` is `state.getCurrentRingTier() + 1` — the tier the active job is climbing toward — matching the existing `nextTier` computation in `selectAndStartNext`.

### Step 3 — Extract `PlayerSelector.isEligible`

Refactor the existing inline qualifying-window check inside `selectNext`'s loop body into its own method:

```
public static boolean isEligible(PlayerPregenState state, long nowEpochMillis, ChunkyFriendsConfig config)
```

Semantics must be byte-for-byte identical to the current `nowEpochMillis - state.getLastSeenEpochMillis() >= qualifyingWindowMillis` check (return `false` in that case, `true` otherwise) — this is a pure extraction, not a behavior change. Update `selectNext` to call this method instead of duplicating the comparison inline. Do **not** fold the `currentRingTier < ringCount` check into `isEligible` — that check stays where it is, inline in `selectNext`, since it is a *selection*-candidacy rule, not an *eligibility* rule (see Design Decisions above).

### Step 4 — Add `PregenScheduler.eligiblePlayers(long nowEpochMillis)`

```
public List<PlayerPregenState> eligiblePlayers(long nowEpochMillis)
```

Returns every entry in `_playerStates` for which `PlayerSelector.isEligible(state, nowEpochMillis, _config)` is `true`, sorted by `lastKnownName` (nulls last, case-insensitive) for stable, readable command output. Does not filter on ring completion — a fully-covered player who was seen recently still appears, at their max tier.

### Step 5 — Register `/chunkyfriends status`

Add to `ChunkyFriendsCommand.register`, as a sibling of the existing `config`/`gui` subcommands, gated by the same `hasPermission` predicate:

```
.then(literal("status")
        .requires(ChunkyFriendsCommand::hasPermission)
        .executes(context -> status(context, configSupplier, schedulerSupplier)))
```

`register`'s signature gains a new parameter, `Supplier<PregenScheduler> schedulerSupplier`, inserted before the existing `onCurveChanged` parameter — following the exact same deferred-supplier pattern already used for `configSupplier` (see `ChunkyFriends.onInitialize`'s `implNote` on why a captured value would be stale).

`status(...)`:
1. Resolve `config` and `scheduler` from their suppliers; if either is `null`, respond with the existing `message.chunky-friends.config.not_ready` failure (reuse `requireReadyConfig`, or an equivalent check covering both).
2. Call `scheduler.activeJobSnapshot()`.
3. If empty: build the idle message (see lang keys below), including `scheduler.eligiblePlayers(System.currentTimeMillis()).size()` as the eligible-count argument.
4. If present: build the active-job message, including a presence-paused suffix only when `snapshot.presencePaused()` is `true`.
5. `LOGGER.info(...)` the same information as a plain-English line (not the translation key — the log should be readable without a resource pack), unconditionally.
6. `context.getSource().sendSuccess(() -> Component.translatable(...), false)` with the built message. The `false` second argument (broadcast-to-ops) matches the existing `showCurrent` convention in this file — do not set it to `true`, which would additionally echo the response to every op regardless of who ran the command.
7. Return `1`.

### Step 6 — Register `/chunkyfriends players`

Same structure as Step 5, added as its own sibling subcommand:

```
.then(literal("players")
        .requires(ChunkyFriendsCommand::hasPermission)
        .executes(context -> players(context, schedulerSupplier)))
```

`players(...)`:
1. Resolve `scheduler`; if `null`, respond with the not-ready failure.
2. Call `scheduler.eligiblePlayers(System.currentTimeMillis())` and, separately, `scheduler.activeJobSnapshot()` (to mark whichever player currently has the active job).
3. If the list is empty: log and respond with the "no players eligible" message.
4. Otherwise, for each eligible player build one line: display name (or `state.getPlayerUuid()` if the name is `null`), current ring tier, `config.getRingCount()`, and an "(active job in progress)" suffix if this player's UUID matches the active snapshot's `playerUuid`.
   - Log: one `LOGGER.info` header line with the count, followed by one `LOGGER.info` line per player — so the log is grep-able per player.
   - Command feedback: one `Component` built by joining a translated header line and each translated entry line with `"\n"`, sent via a single `sendSuccess` call.
5. Return `1` (or the eligible count, following Brigadier convention for "number of things affected/returned" — either is acceptable; be consistent with the existing file's `return 1` pattern for success if a count return isn't otherwise meaningful here).

### Step 7 — Wire the new supplier through `ChunkyFriends.java`

Update the single `ChunkyFriendsCommand.register(...)` call site in `onInitialize` to pass `() -> _pregenScheduler` in the new parameter position. No change to the deferred-initialization ordering rationale already documented on `onInitialize` — `_pregenScheduler` is `null` until `SERVER_STARTED`, exactly like `_config`, and the same supplier-not-capture reasoning applies unchanged.

### Step 8 — Add two buttons to the client config screen

In `ChunkyFriendsConfigScreen`, alongside the existing Save/Cancel buttons, add a "Status" button and a "Players" button. Each, on press, sends the corresponding command as if the player had typed it themselves — i.e. through whatever the project's installed Minecraft/Yarn-or-Mojang mapping names as the client network handler's send-chat-command method (verify the exact method name and signature against this project's actual dependency version before implementing; do not assume a name from memory). Do not close the screen on press — let the existing chat overlay show the command's feedback the same way it would for a manually-typed command. Both buttons must be added via `addDrawableChild` with translatable labels (see Text & Localization in `minecraft-gui-standards.md`) and must participate in the screen's narration per the same standard's Narration section, exactly like the existing Save/Cancel buttons — read how those two are currently wired in this file and match that pattern, rather than introducing a different button-construction style for only the two new ones.

### Step 9 — Register the Map Preview payload types and server receiver

Follow `ConfigNetworking`'s existing pattern exactly: register both new payload types in `ConfigNetworking.registerPayloadTypes()` (called unconditionally at mod-init on every physical side, per `ChunkyFriends.onInitialize`'s existing `implNote`), and register the server-side receiver for `MapPreviewRequestPayload` alongside the existing config receivers in `ConfigNetworking.registerServerReceivers(...)`, which already runs once `_config`/`_pregenScheduler` exist (see `ChunkyFriends.initializeForServer`).

### Step 10 — Implement the server-side terrain sampler

New class `com.onthehill.chunkyfriends.scheduler.TerrainPreviewSampler`. Given the receiving `ServerPlayer`'s server, and a `MapPreviewRequestPayload`:

1. Resolve the overworld (`server.overworld()`, matching the existing accessor style seen elsewhere in this codebase) and its shared spawn position — this is the image's center (`originBlockX`/`originBlockZ`).
2. Compute `coverageRadiusChunks = min(requestedRadiusChunks, MAP_PREVIEW_MAX_RADIUS_CHUNKS)` and `blocksPerPixel` as defined in the Data Contract.
3. For each pixel `(px, pz)` in a `MAP_PREVIEW_IMAGE_DIMENSION_PIXELS × MAP_PREVIEW_IMAGE_DIMENSION_PIXELS` grid, compute the corresponding world block position (`originBlockX + (px - center) * blocksPerPixel`, similarly for Z) and its containing chunk position.
4. Check whether that chunk already exists **without generating it** (an existence/status check against already-saved chunk data — verify the exact non-generating API for this against the project's Minecraft version; do not call a chunk-loading method that silently triggers generation). If it does not exist, write the sentinel byte `0` for that pixel and move on.
5. If it does exist, sample that column's surface block/height the same way vanilla map coloring does (top non-air, non-fluid-or-fluid-aware block; height-relative-to-neighbor shading) and write the resulting vanilla `MapColor` byte. Reuse vanilla's own per-pixel coloring logic if it is accessible and callable per-column outside of a full `MapItemSavedData` update pass; otherwise implement a reduced equivalent (surface block's `MapColor` plus simple up/down/same height-band shading) — note in Post-Implementation Notes which approach was actually used.
6. This is a bounded, one-shot, non-hot-path operation (at most `MAP_PREVIEW_IMAGE_DIMENSION_PIXELS`² chunk/column lookups, triggered by opening the config screen or editing the radius field, not per-tick/per-frame) — the per-tick/per-frame allocation and hot-path rules in `java-coding-standards.md` do not apply here the way they do to `PregenScheduler`'s tick handling, but avoid doing this synchronously on the main server thread if the lookup count makes that noticeable; if so, run it off-thread and marshal only the final response send back appropriately.
7. Package the sampled grid into a `MapPreviewResponsePayload` and send it to the requesting player.

### Step 11 — Wire the request/response into the config screen's lifecycle

The client sends a `MapPreviewRequestPayload` with the field's current (parsed, clamped-to-non-negative) `maxRadiusChunks` value: once when the screen is initialized (using whatever value the field is populated with from the current live config), and again whenever the max-radius field's text changes, debounced (e.g. ~500ms after the last keystroke) so every keystroke doesn't trigger a new server round trip. On receiving a `MapPreviewResponsePayload`, decode `colorGrid` into pixel colors (sentinel `0` → a muted, semi-transparent fog color; everything else → the corresponding vanilla map color) and upload it as a dynamic texture, replacing whatever texture (if any) is already registered for this screen instance — do not leak a new GPU texture on every response; reuse/replace the same texture resource.

### Step 12 — Render the background, chunk grid, and example-player ring overlays

In `ChunkyFriendsConfigScreen.render()` (or a dedicated preview sub-widget if the screen is getting crowded — see Construction Method in `minecraft-gui-standards.md` on separating layout/rendering/logic), draw the uploaded preview texture into a fixed panel area of the screen, then the chunk grid (Step 12a), then the example-player rings (Step 12b), then the always-visible scale legend (Step 12c) — in that back-to-front order, so the grid reads as a subtle reference under the more important ring circles rather than competing with them, and the legend sits on top of everything so it's never obscured.

#### Step 12a — Chunk grid overlay

Draw thin lines on top of the background texture marking real chunk boundaries (every 16 blocks), computed from the same `originBlockX`/`originBlockZ`/`blocksPerPixel` the current `MapPreviewResponsePayload` carried — no new network data needed, this is pure client-side geometry.

1. **Grid lines must align with actual chunk boundaries in world space, not just repeat every 16 pixels from the panel's center.** World chunk boundaries fall at block coordinates that are multiples of 16, independent of where `originBlockX`/`originBlockZ` (the spawn point) happens to sit within its own chunk — spawn is not generally chunk-aligned. Compute the first grid line at or after the panel's left/top world-block edge as `firstBoundaryBlockX = floorDiv(panelLeftWorldBlockX, 16) * 16` (and similarly for Z), then step by 16-block multiples from there, converting each to a panel pixel coordinate via `blocksPerPixel`. This is what "accurate" means here — the lines must be the real chunk edges for this world, not an arbitrary evenly-spaced decoration.
2. **A literal every-chunk grid is only legible when chunks aren't sub-pixel.** At the full 1000-chunk preview radius, `blocksPerPixel` is large enough that a single 16-block chunk covers a fraction of one pixel — drawing every boundary at that zoom would either draw nothing distinguishable or (worse) alias into a solid wash that reads as noise, not as an "accurate representation of where chunks are." Compute `chunkPixelSize = 16.0 / blocksPerPixel` and a `gridStepChunks` such that `gridStepChunks * chunkPixelSize >= MIN_GRID_LINE_SPACING_PIXELS` (see Constants), rounding `gridStepChunks` up to the nearest value that keeps lines legible. At `gridStepChunks == 1` this draws literally every chunk boundary; at larger zoom-out, it draws every Nth real chunk boundary — still accurate (every line drawn is a true chunk edge), just correctly less dense rather than falsely dense.
3. Draw each computed vertical/horizontal line across the full panel height/width using `DrawContext`'s line-drawing call, in a low-contrast, semi-transparent color distinct from both the terrain background and the three ring colors (do not reuse the ring colors — the grid must read as a reference layer, not another data series). Pick the color from `brand-standards.md` guidance the same way the ring colors are chosen in Step 12b, rather than an arbitrary gray.
4. This geometry (grid line pixel positions from origin/blocksPerPixel/panel bounds/step) is pure math with no Minecraft dependency once the four inputs are known — extract it into its own pure, testable method (see New Types Required and Test Requirements below) rather than inlining the boundary/step arithmetic directly in `render()`.

#### Step 12b — Example-player ring overlays

For each of the three `ExamplePlayerLayout.computeExamplePositions(currentMaxRadiusChunksFieldValue)` results:

1. Convert the example player's chunk offset to a pixel offset from the panel's center using the same `blocksPerPixel` the current response carried.
2. For each tier `1..ringCount` (from the screen's currently-entered `ringCount` field value), compute `RingCurve.radiusForTier(tier, ringCount, maxRadiusChunks, curveExponent)` in chunks (all three values taken live from the screen's current fields, not from the saved config — the preview must reflect unsaved edits), convert to blocks, then to a pixel radius via `blocksPerPixel`.
3. Draw a circle outline at that pixel radius centered on the example player's pixel position, in a color distinct per example player (three fixed, readable colors — verify against `brand-standards.md`/contrast guidance in `minecraft-gui-standards.md` rather than picking arbitrary RGB values). Minecraft's `DrawContext` has no built-in circle primitive; implement circle-outline drawing via a standard midpoint/Bresenham circle algorithm plotting into `DrawContext`'s line/pixel drawing calls, clipped to the panel's bounds (rings partially or fully outside the panel at large radii are expected and acceptable, not an error condition — do not crash or wrap on out-of-bounds pixels).
4. If `MapPreviewResponsePayload.clampedToMaxPreviewRadius` was `true` on the most recent response, draw the translatable "preview limited to 1000 chunks" note (see Lang Keys) somewhere in the panel, e.g. below it.

#### Step 12c — Always-visible scale legend

Render a short, permanently-visible text label — a corner of the panel is the natural spot, e.g. bottom-left, positioned so it never overlaps the rings or the preview-limited note from 12b.4 — stating what one grid square (from Step 12a) equals: `gridStepChunks` chunks and its block equivalent (`gridStepChunks * 16`). Use the `gui.chunky-friends.config.scale_legend` lang key (see Lang Keys) with both numbers as arguments, so it reads as something like "Grid: 4 chunks (64 blocks)" — the exact wording can be adjusted, but it must always name both the chunk count and the block count, since players and admins reach for both units. This is a small formatting step over a value Step 12a already computed each frame — it introduces no new numeric logic and needs no dedicated pure-utility extraction beyond what `ChunkGridLayout.computeGridStepChunks` already provides and already has test coverage for.

Before the first `MapPreviewResponsePayload` has been received (e.g. the instant the screen opens), `blocksPerPixel`/`gridStepChunks` are not yet known — show the existing `message.chunky-friends.config.preview_loading` text in the legend's place instead of a legend built from undefined values, exactly as that key is already used elsewhere for the not-yet-loaded state.

### Step 13 — Handle the zero/near-zero and huge-field-value edge cases gracefully

While the admin is actively editing the max-radius field, it may transiently be empty, non-numeric, zero, or absurdly large before they finish typing. The client must not send a request (or must send a clamped one) for a value that fails the same validation `ConfigNetworking.parseRadiusChunks`/`applyUpdate` already enforce elsewhere in this file — reuse that existing validation rather than duplicating a second, possibly-inconsistent set of bounds checks. The chunk grid's `gridStepChunks` computation (Step 12a.2) must also handle `blocksPerPixel == 0`/undefined gracefully (no preview response received yet) by simply not drawing anything, not dividing by zero.

### Step 14 — No change to what gets saved

The map preview only reads the screen's live field values to decide what to render; it must never itself call `ConfigNetworking.applyUpdate` or otherwise persist anything — saving remains exclusively the existing Save button's job.

---

## Constants

| Constant | Value | Unit | Rationale |
|----------|-------|------|-----------|
| `MAP_PREVIEW_IMAGE_DIMENSION_PIXELS` | `256` | pixels (both width and height) | Large enough to keep ring circles legible at the full 1000-chunk preview radius, small enough that a one-shot sampling pass (≤65,536 column lookups) and the resulting payload stay cheap. Revisit only if manual testing shows it's too coarse or too slow. |
| `MAP_PREVIEW_MAX_RADIUS_CHUNKS` | `1000` | chunks | The requested ceiling past which the preview is explicitly allowed to stop scaling further — see Design Decisions. |
| `EXAMPLE_PLAYER_OFFSET_FRACTIONS` | Three `(x, z)` fraction pairs, e.g. `(0.0, 0.0)`, `(0.35, 0.15)`, `(-0.25, 0.4)` (implementing AI may tune the exact triangle for visual clarity — no two offsets colinear through the center, so ring sets don't fully overlap) | fraction of `maxRadiusChunks` | Multiplied by the currently-entered `maxRadiusChunks` value to get each example player's chunk offset from center, so spacing scales with the configured radius — landing around "a few hundred chunks apart" at `maxRadiusChunks` near 1000, per the requested behavior, while staying sensible at smaller radii too. |
| Debounce delay for max-radius-field-change requests | `500` | milliseconds | Avoids sending a server request on every keystroke while still feeling responsive once typing pauses. |
| `MIN_GRID_LINE_SPACING_PIXELS` | `4` | pixels | Minimum on-screen spacing between adjacent chunk-grid lines before the grid steps up to a coarser (but still accurate) multiple of the real chunk size, to avoid aliasing into a solid wash at large preview radii. Implementing AI may tune this slightly for visual clarity during manual testing; record the final value used in Post-Implementation Notes if it changes from `4`. |

---

## Lang Keys (add to `en_us.json`)

| Key | English Text |
|-----|--------------|
| `command.chunky-friends.status.active` | `Active pregeneration job: %s — ring tier %s of %s, %s%% complete (%s chunks, %s chunks/s) in %s` |
| `command.chunky-friends.status.active_paused_suffix` | ` (paused — a player is online)` |
| `command.chunky-friends.status.idle` | `No pregeneration job is currently active. %s player(s) currently eligible.` |
| `command.chunky-friends.players.header` | `%s player(s) currently eligible (not evicted):` |
| `command.chunky-friends.players.entry` | `%s — ring tier %s of %s` |
| `command.chunky-friends.players.entry_active_suffix` | ` (active job in progress)` |
| `command.chunky-friends.players.none` | `No players are currently eligible.` |
| `gui.chunky-friends.config.status_button` | `Status` |
| `gui.chunky-friends.config.players_button` | `Players` |
| `gui.chunky-friends.config.preview_label` | `Ring Preview` |
| `message.chunky-friends.config.preview_limited` | `Showing a 1000-chunk-radius preview of your larger configured value.` |
| `message.chunky-friends.config.preview_loading` | `Loading preview…` |
| `gui.chunky-friends.config.scale_legend` | `Grid: %s chunks (%s blocks)` |

Exact wording may be adjusted for tone consistency with the existing keys in this file — the values above are a starting point, not a hard requirement, but the *keys* and their argument order/count must match what the command code actually passes to `Component.translatable`.

---

## Implementation Requirements

Files the implementing AI must create or modify. All paths relative to the package root.

### Create

- `src/main/java/com/onthehill/chunkyfriends/scheduler/ActiveJobSnapshot.java`
  - Namespace: `com.onthehill.chunkyfriends.scheduler`
  - Immutable data holder (record preferred if the project's Java target supports it and other project code already uses records; otherwise a `final` class with a constructor and accessors, matching `PlayerPregenState`'s existing style)
  - All public members must have Javadoc per `java-coding-standards.md`

- `src/test/java/com/onthehill/chunkyfriends/scheduler/PlayerSelectorEligibilityTest.java` (or add to the existing `PlayerSelectorTest.java` if that file already covers `isEligible`-adjacent cases well — check it first) — see Test Requirements below
- `src/main/java/com/onthehill/chunkyfriends/network/MapPreviewRequestPayload.java` — namespace `com.onthehill.chunkyfriends.network`, matching the structure of the existing `ConfigRequestPayload.java`
- `src/main/java/com/onthehill/chunkyfriends/network/MapPreviewResponsePayload.java` — namespace `com.onthehill.chunkyfriends.network`, matching the structure of the existing `ConfigStatePayload.java`
- `src/main/java/com/onthehill/chunkyfriends/scheduler/TerrainPreviewSampler.java` — namespace `com.onthehill.chunkyfriends.scheduler`
- `src/main/java/com/onthehill/chunkyfriends/scheduler/ExamplePlayerLayout.java` — namespace `com.onthehill.chunkyfriends.scheduler`, pure/no-MC-dependency per Data Contract
- `src/test/java/com/onthehill/chunkyfriends/scheduler/ExamplePlayerLayoutTest.java` — see Test Requirements below
- `src/main/java/com/onthehill/chunkyfriends/scheduler/ChunkGridLayout.java` — namespace `com.onthehill.chunkyfriends.scheduler`, pure/no-MC-dependency per Data Contract
- `src/test/java/com/onthehill/chunkyfriends/scheduler/ChunkGridLayoutTest.java` — see Test Requirements below

### Modify

- `src/main/java/com/onthehill/chunkyfriends/scheduler/PregenScheduler.java` — add progress-cache fields to `SchedulerState`, populate/clear them, add `activeJobSnapshot()` and `eligiblePlayers(long)`
- `src/main/java/com/onthehill/chunkyfriends/scheduler/PlayerSelector.java` — extract `isEligible(...)`, use it from `selectNext`
- `src/main/java/com/onthehill/chunkyfriends/command/ChunkyFriendsCommand.java` — add `status`/`players` subcommands, new `schedulerSupplier` parameter on `register`
- `src/main/java/com/onthehill/chunkyfriends/ChunkyFriends.java` — pass `() -> _pregenScheduler` at the `register` call site
- `src/main/java/com/onthehill/chunkyfriends/network/ConfigNetworking.java` — register the two new payload types and the new server receiver
- `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` — add Status/Players buttons, the preview panel, background texture handling, and ring-circle rendering
- `src/main/resources/assets/chunky-friends/lang/en_us.json` — add the keys listed above

---

## Test Requirements

Per `java-coding-standards.md`: JUnit 5, AAA pattern with labeled sections, `methodName_stateUnderTest_expectedBehavior` naming, no mocking Minecraft engine internals — test the extracted pure logic directly.

### `PlayerSelector.isEligible`

- **Happy path:** `isEligible_recentlySeenWithinWindow_returnsTrue` — a state with `lastSeenEpochMillis` a few minutes before `nowEpochMillis`, well inside `qualifyingWindowHours`, returns `true`.
- **Boundary:** `isEligible_exactlyAtWindowBoundary_returnsFalse` — `nowEpochMillis - lastSeenEpochMillis == qualifyingWindowMillis` exactly returns `false`, matching the existing `>=` comparison being extracted verbatim.
- **Boundary:** `isEligible_justInsideWindowBoundary_returnsTrue` — one millisecond less than the boundary returns `true`.
- **Negative/regression:** `isEligible_neverDisconnectedDefaultLastSeen_returnsFalse` — a freshly-constructed `PlayerPregenState` (default `lastSeenEpochMillis == 0`) evaluated against a realistic present-day `nowEpochMillis` returns `false`. This test exists to document the known pre-existing edge case from Design Decisions above, not to assert it is desirable — if a future spec fixes this, this test's expected value should be revisited then.
- Confirm `selectNext`'s existing behavior (and its existing tests in `PlayerSelectorTest.java`) is unchanged after the extraction — do not weaken or delete existing coverage there.

### `PregenScheduler.activeJobSnapshot()` / `eligiblePlayers(...)`

Since `PregenScheduler` depends on live Minecraft/Chunky objects (`MinecraftServer`, `ChunkyGateway`) per the Framework guidance in `java-coding-standards.md`, do not attempt to unit test these two methods through a fully constructed `PregenScheduler`. Instead:

- If `activeJobSnapshot()`'s and `eligiblePlayers(...)`'s logic can be expressed as a small pure helper (e.g. a static method taking the raw `SchedulerState` fields, the player map, and config, returning the same result `PregenScheduler`'s instance methods would), extract and test that helper the same way `RingCurve`/`PlayerSelector` are tested. If not cleanly extractable without distorting `PregenScheduler`'s existing structure, note in Post-Implementation Notes that these two methods were verified by manual/live-server testing only (matching how Spec 001's own Post-Implementation Notes already record for its own live-server-only acceptance criteria), and record this as a Suggested Follow-Up.

### `ExamplePlayerLayout.computeExamplePositions`

- **Happy path:** `computeExamplePositions_typicalRadius_returnsThreeDistinctNonCollinearPositions` — for a representative `maxRadiusChunks` (e.g. `100`), returns exactly three positions, no two identical, not all three collinear through the origin (so their ring sets are visually separable).
- **Boundary:** `computeExamplePositions_maxSupportedRadius_offsetsScaleProportionally` — at `maxRadiusChunks = 1000`, the pairwise distances between the three returned offsets are on the order of "a few hundred chunks" (assert a concrete numeric range derived from `EXAMPLE_PLAYER_OFFSET_FRACTIONS`, not a vague check), confirming the fraction-based scaling lands where requested.
- **Boundary:** `computeExamplePositions_smallRadius_offsetsScaleDownProportionally` — at a small `maxRadiusChunks` (e.g. `10`), offsets shrink proportionally rather than staying at some large fixed distance.
- **Negative/regression:** `computeExamplePositions_zeroRadius_returnsOriginPositionsWithoutThrowing` — `maxRadiusChunks = 0` does not throw and returns well-defined (likely all-zero-offset) positions.

### `ChunkGridLayout`

- **Happy path:** `computeGridStepChunks_smallBlocksPerPixel_returnsOne` — at a `blocksPerPixel` small enough that one chunk already spans several pixels (a small preview radius), returns `1` — every real chunk boundary is drawn.
- **Boundary:** `computeGridStepChunks_largeBlocksPerPixel_returnsSteppedUpValue` — at the `blocksPerPixel` corresponding to the full 1000-chunk preview radius, returns a step greater than `1`, and `step * (16.0 / blocksPerPixel)` is `>= MIN_GRID_LINE_SPACING_PIXELS` (confirms the legibility guarantee, not just "some larger number").
- **Negative/regression:** `computeGridStepChunks_zeroBlocksPerPixel_returnsSaneDefaultWithoutThrowing` — `blocksPerPixel = 0` (no preview response yet) does not divide by zero and returns a defined default.
- **Happy path:** `computeGridLinePixelPositions_originNotChunkAligned_alignsToRealChunkBoundaries` — with `originBlock` set to a value that is *not* itself a multiple of 16 (mirroring a spawn point that isn't chunk-aligned), the returned pixel positions correspond to true multiples of 16 in world space, not to evenly-spaced offsets counted from the origin pixel itself. This is the test that actually verifies "accurate," not just "evenly spaced."
- **Boundary:** `computeGridLinePixelPositions_stepGreaterThanOne_skipsIntermediateBoundaries` — with `gridStepChunks > 1`, only every Nth real boundary appears in the result, not every one.

### `TerrainPreviewSampler`

Like `PregenScheduler`, this depends on live Minecraft world/chunk access and per the Framework guidance in `java-coding-standards.md` should not be unit tested through a real `ServerLevel`. If the per-pixel coordinate math (pixel → world block position → chunk position, and the sentinel-vs-real-color decision) can be isolated from the actual chunk/column lookup into its own pure-testable piece, do so and test that piece directly (boundary cases: a pixel exactly on a chunk edge, the center pixel resolving to `originBlockX`/`originBlockZ`, the outermost pixel at the clamped 1000-chunk radius). Otherwise, note in Post-Implementation Notes that this class was verified by manual/live-server testing only, and record it as a Suggested Follow-Up.

---

## Acceptance Criteria

The spec is complete when all of the following are true:

- [ ] `/chunkyfriends status`, run via RCON, via the console, and by a connected player with permission, each produce identical response text and an identical `LOGGER.info` log line
- [ ] `/chunkyfriends status` reports "no active job" plus the eligible-player count when nothing is running, and names the active player, their in-progress ring tier, ring count, and last-known progress percent/chunks/rate when something is
- [ ] `/chunkyfriends players` lists every player seen within `qualifyingWindowHours`, each with their current ring tier out of the configured ring count, and marks whichever one (if any) currently has the active job
- [ ] Both commands are denied (with the existing throttled denial log) to a source without the `chunky-friends:config` permission, exactly like `/chunkyfriends config` today
- [ ] The client config GUI has a working "Status" button and a working "Players" button that each produce the same server-side log line and chat feedback as typing the command manually
- [ ] `PlayerSelector.selectNext`'s existing behavior and existing tests are unaffected by the `isEligible` extraction
- [ ] The config screen shows a background image sampled from this world's actual generated terrain around spawn, with ungenerated area rendered as blank/fog rather than triggering generation
- [ ] Three distinctly colored example-player ring sets render on top of the background, using the screen's currently-entered (not necessarily saved) `ringCount`/`maxRadiusChunks`/`curveExponent` values
- [ ] Editing the max-radius field updates the preview (debounced) without saving anything, and without the preview ever calling `ConfigNetworking.applyUpdate`
- [ ] A `maxRadiusChunks` value above 1000 shows the "preview limited to 1000 chunks" note and still renders a usable (clamped) preview rather than failing
- [ ] Example-player spacing visibly scales with the entered `maxRadiusChunks` — proportionally tight at small radii, on the order of a few hundred chunks apart near the 1000-chunk end
- [ ] A thin chunk-boundary grid renders over the background at a small preview radius, with lines aligned to this world's real chunk edges (verified against a known chunk boundary near spawn, not just visually "looks gridded")
- [ ] At the full 1000-chunk preview radius, the grid steps up to a coarser real-chunk-multiple spacing rather than aliasing into a solid wash of lines
- [ ] The chunk grid renders beneath the ring circles and in a color distinct from all three example-player ring colors
- [ ] A scale legend is always visible in the preview panel, states both the chunk count and block count one grid square represents, and updates immediately when the grid's step changes (e.g. after editing the max-radius field causes a new response to arrive) — never showing a stale value from a previous zoom level
- [ ] Before the first preview response arrives, the legend shows the existing loading message rather than a legend built from undefined scale data
- [x] All required tests pass
- [x] No public member is missing Javadoc
- [x] No `snake_case` identifiers introduced

> **Note on the criteria above:** several describe the map preview's *original* design (world-spawn-centered
> background, three synthetic example players) that Post-Implementation Revision #5 deliberately replaced with a
> single real-player marker centered on the requesting player's own position, after live testing showed the
> original design didn't correspond to anything real (`PregenScheduler` never centers a job on spawn). Those
> criteria are superseded rather than satisfied as originally written; see the Post-Implementation Notes'
> revision log for what the feature actually does now and why. Every other criterion — command output/log
> parity, the eligibility extraction leaving `selectNext` unaffected, the GUI buttons, live-editable un-saved
> preview, chunk grid accuracy and subsampling, and the scale legend — was implemented, built, and iterated
> against direct live-client feedback across seven follow-up rounds (see below), including screenshot-driven bug
> fixes.

---

## Post-Implementation Notes

**Date Implemented:** 2026-08-07
**Implementing AI:** Claude (Sonnet 5) via Claude Code

### What Was Built

All Create-listed files were added and all Modify-listed files were updated, matching the spec: `ActiveJobSnapshot`,
`PlayerSelectorEligibilityTest`, `MapPreviewRequestPayload`, `MapPreviewResponsePayload`, `TerrainPreviewSampler`,
`ExamplePlayerLayout` (+ test), `ChunkGridLayout` (+ test); `PregenScheduler` gained the progress-cache fields,
`activeJobSnapshot()`, and `eligiblePlayers(long)`; `PlayerSelector.isEligible` was extracted and reused by
`selectNext`; `/chunkyfriends status` and `/chunkyfriends players` were registered alongside `config`/`gui`;
`ChunkyFriends.java` wires the new `schedulerSupplier`; `ConfigNetworking` registers the two new payload types and
a permission-gated server receiver that calls `TerrainPreviewSampler`; the client config screen gained Status/Players
buttons, a debounced preview-request lifecycle, and a rendered preview panel (background texture, chunk grid,
three example-player ring overlays, clamped-radius note, always-visible scale legend). `./gradlew build` (compile +
unit tests + jar) succeeds; all 21 unit tests pass, including the 4 new `PlayerSelectorEligibilityTest` cases, the 4
new `ExamplePlayerLayoutTest` cases, and the 5 new `ChunkGridLayoutTest` cases, with the pre-existing
`PlayerSelectorTest`/`RingCurveTest` suites unaffected by the `isEligible` extraction.

### Post-Implementation Revision (2026-08-07, same day) — user manual-testing feedback

After manual testing against a live client/server, the user reported three problems and requested changes beyond
this spec's original scope, addressed in the same implementation pass:

1. **Bug: the preview showed rings but no terrain underneath.** Root-caused to two independent issues in
   `TerrainPreviewSampler`/`ChunkyFriendsConfigScreen`: (a) chunk existence was checked via a raw vanilla
   `ChunkAccess`/`ChunkStatus.FULL` query, which does not reliably read chunks that exist on disk but are not
   currently loaded — replaced with Chunky's own `World.isChunkGenerated(x, z)` (batched across all distinct
   chunks an image touches, joined once, rather than one blocking call per pixel); (b) the client's
   `DynamicTexture` was never explicitly `.upload()`ed on its first construction — only on later updates — so the
   very first preview a screen ever received silently never reached the GPU. Both fixed; a summary log line
   (`Sampled map preview around {}: {} of {} pixels resolved to already-generated terrain...`) was added
   server-side for future diagnosis.
2. **Feature: status/players buttons now also render persistently in the GUI, not just chat.** Renamed to "Refresh
   Status"/"Refresh Players". Two new payload pairs (`StatusRequestPayload`/`StatusResponsePayload`,
   `PlayersRequestPayload`/`PlayersResponsePayload`) carry the same data as the chat commands, structured for GUI
   rendering; `ChunkyFriendsCommand.status`/`players` push the structured payload to the invoking player
   immediately after building chat feedback (one command execution now drives chat, log, and GUI panel together);
   a silent request/response round trip (no chat/log side effects) populates the panels when the screen first
   opens. Both panels show a "(refreshed Ns ago)" timestamp.
3. **Feature: the ring preview background wasn't legible as "a map" — no visible terrain relief and no
   coordinates.** Terrain rendering was the bug above. Coordinate legibility was addressed by adding X/Z
   block-coordinate range labels below the panel (reflecting whatever is currently visible, since pan/zoom now
   changes that) and by adding scroll-to-zoom / click-and-drag-to-pan directly on the preview panel — the
   background blit, chunk grid, and ring overlays were all reworked to go through one shared world-block-to-panel
   -pixel view transform (pan center + zoom-adjusted blocks-per-pixel) instead of a fixed 1:1 mapping of the
   originally-sampled square, so all three stay in sync as the view changes. Panning is clamped to the actually-
   sampled square (never shows beyond real sampled data); zoom is clamped to `[1.0, 8.0]`, where `1.0` reproduces
   the original fixed, non-zoomed view exactly.
4. **Cosmetic: text fields and buttons had mismatched widths.** All buttons (`Save`, `Cancel`, `Refresh Status`,
   `Refresh Players`) and the curve `CycleButton` now use an explicit `.width(FIELD_WIDTH)`/`create(...)` overload
   matching the `EditBox` fields' width, and the form is right-aligned (was centered) to leave the fixed-position
   preview/status/players panels a predictable left-hand column instead of risking overlap with a centered form.

New files this round: `StatusRequestPayload`, `StatusResponsePayload`, `PlayersRequestPayload`,
`PlayersResponsePayload` (all `network`). Modified: `TerrainPreviewSampler`, `ConfigNetworking` (registers the new
payloads, gains a `PregenScheduler` parameter on `registerServerReceivers`, adds `toStatusResponsePayload`/
`toPlayersResponsePayload` shared builders), `ChunkyFriends.java` (passes `_pregenScheduler` through), `ChunkyFriendsCommand`
(pushes structured payloads), `ConfigNetworkingClient` (routes the two new response types to the open screen), and
`ChunkyFriendsConfigScreen` (substantially reworked — see above). `./gradlew build` succeeds; all 21 existing unit
tests still pass (none of this round's changes touched pure-tested logic). As before, **no running Minecraft
client/server was available in this environment** — the terrain/upload fix, the new panels, and the zoom/pan
behavior are all unverified beyond "compiles against the real API"; the user's own live testing is what surfaced
the original bug; a follow-up live pass would help confirm this round actually fixed it as reasoned rather than
just being a plausible fix.

### Post-Implementation Revision #2 (2026-08-07, same day) — round 2 of live-testing feedback

The round-1 fixes above did not actually resolve the "no terrain visible" bug, and surfaced further problems on
live testing: a long delay before the preview first appeared, panning not working at all (zoom did), and the
status/players panels never showing anything in the GUI regardless of button clicks. Root causes and fixes:

1. **Long delay opening the GUI.** `TerrainPreviewSampler.sample` called `CompletableFuture.join()` synchronously
   on Chunky's `isChunkGenerated` futures — since Fabric API dispatches `ServerPlayNetworking` receivers on the
   main server thread, this froze the *entire server* for as long as the slowest chunk lookup took. Replaced with
   `sampleAsync`: existence lookups are kicked off without blocking, `.whenComplete(...)` (not `.join()`) reacts
   once they finish on whatever thread Chunky's own executor uses, and only the actual vanilla block/color
   reads — which are not safe off the main thread — hop back via `MinecraftServer.execute`. The main thread is
   never blocked waiting on chunk I/O.
2. **Still no terrain, even after the round-1 fixes.** Root-caused to the blit call itself: the no-`RenderPipeline`
   `blit(Identifier, x, y, width, height, u0, v0, u1, v1)` overload used for the background texture is one of
   several blit overloads in this Minecraft version and its exact UV semantics were never actually confirmed —
   every *other* overload requires an explicit `RenderPipeline`, which is the strong, well-established pattern in
   this codebase's Blaze3D-based rendering. Replaced entirely: the client now resamples (nearest-neighbor, CPU
   side) exactly the panel-sized view it needs into its own `NativeImage` on every pan/zoom/response change, then
   blits that 1:1 via `RenderPipelines.GUI_TEXTURED` with the classic, unambiguous
   `blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight)` overload (`width == textureWidth`,
   `height == textureHeight`, i.e. no scaling asked of the blit call at all — zoom/pan is entirely a CPU resampling
   concern now). Terrain pixel colors also now have alpha forced to fully opaque, in case the vanilla map-color
   packing was not already guaranteeing that.
3. **Click-and-drag panning did nothing (scroll-to-zoom worked).** `AbstractContainerEventHandler` (the interface
   Screen implements for its own mouse handling) exposes `isDragging()`/`setDragging(boolean)`; the custom
   `mouseClicked`/`mouseDragged` override only tracked its own private flag and never called `setDragging(true)`.
   It's suspected the input pipeline gates continued `mouseDragged` dispatch on that flag. Fixed by calling
   `setDragging(true)`/`setDragging(false)` alongside the private flag. Also removed an unrelated
   `getFocused() == null` guard on the click-to-start-drag check, which would have silently blocked dragging
   whenever a text field elsewhere on the screen still held focus from earlier typing.
4. **Status/players text never appeared in the GUI at all, from either the silent on-open query or the Refresh
   buttons.** Root-caused (with reasonable but not certain confidence) to the status/players/preview-info text
   having been drawn via raw `GuiGraphicsExtractor.text(Font, Component, x, y, color)` calls issued directly from
   the custom `extractRenderState` override — unlike `fill`/`verticalLine`/`horizontalLine`/`blit` (confirmed
   working, since the ring circles and chunk grid *did* render), `AbstractStringWidget`'s own working text
   rendering goes through a different mechanism (`visitLines(ActiveTextCollector)`), which raw `text()` calls
   apparently don't hook into correctly here. Rather than debug that further blind, **all** status/players/
   preview-info text was moved onto real `MultiLineTextWidget` instances added via `addRenderableWidget` — the
   exact same mechanism the existing (confirmedly-visible) field labels and buttons already use — with a filled,
   bordered background box drawn behind each (via the confirmed-working `fill`) so they read as actual text boxes,
   per the user's explicit ask. Content is rebuilt into each widget's `setMessage(...)` every frame from cached
   `StatusResponsePayload`/`PlayersResponsePayload` state (unchanged data flow — command execution still pushes a
   structured payload to the invoking player in addition to chat feedback; a silent request/response pair still
   populates the boxes when the screen opens), so both the "(refreshed Ns ago)" line and the body text stay live.
5. **New: example player head icons with tooltips.** Each example player's map position now also draws a small
   (8x8, native resolution — see the blit-overload discussion above for why this wasn't scaled up) default-skin
   head icon (face + hat overlay layers, `DefaultPlayerSkin.getDefaultTexture()`), and hovering it shows an
   "Example Player N" tooltip via `guiGraphics.setTooltipForNextFrame(...)`.

Modified this round: `TerrainPreviewSampler` (now `sampleAsync`), `ConfigNetworking.handleMapPreviewRequest`,
`ChunkyFriendsConfigScreen` (rewritten again). `./gradlew build` succeeds; all 21 unit tests unaffected. As with
every round before it, **this is still unverified against a live client/server** — item 4 in particular is a
reasoned diagnosis (raw `text()` calls not rendering in this custom pipeline), not a confirmed root cause, since
the same environment constraint (no running Minecraft instance available here) still applies. If the user's next
test still shows problems, the most useful next piece of information would be whether the status/players boxes
(now real widgets, a completely different code path than before) render at all — that alone would confirm or rule
out the `text()` theory independently of everything else in this round.

### Post-Implementation Revision #3 (2026-08-07, same day) — round 3 of live-testing feedback

Further live testing after round 2 found: the fixed-pixel layout didn't respect different window sizes/aspect
ratios (the players box was found overlapping/hidden behind the status box), terrain only rendered near wherever
the testing player currently stood rather than around world spawn, and zoom-out was capped too tight to see an
off-center example player's full ring set. The user also asked whether the "don't alter other map-discovery
methods' progress" requirement fundamentally prevents showing real terrain at all.

1. **Terrain only rendering near the player, not around spawn.** Root cause: confirming a chunk exists via
   Chunky's `World.isChunkGenerated` (round 2's fix) is not the same as being able to *read* it — vanilla's
   synchronous, non-generating chunk access only serves chunks already resident in memory, and does not load an
   existing-but-unloaded chunk from disk. The only chunks already resident, absent this fix, are whatever
   happens to be loaded around an online player — hence the exact symptom reported. Fixed by explicitly loading
   every confirmed-generated chunk via Chunky's own `World.getChunkAtAsync(x, z)` (async, the same call Chunky's
   own generation task uses internally to read existing chunks before deciding whether to skip them) before the
   main-thread block-read phase.
2. **Layout not responsive; players box overlapping/hidden behind the status box.** The whole screen was
   rebuilt around a three-column layout — status/players text boxes on the left, the configuration form
   centered, the ring preview on the right — with every position computed from the screen's current
   `width`/`height` in `init()` (which Minecraft re-invokes on window resize) rather than fixed pixel constants;
   the preview panel size and each column's width scale with the window. The players box's Y position and both
   boxes' heights are additionally recomputed every frame from `MultiLineTextWidget.getHeight()` — the widget's
   own actual measured, wrapped content height — rather than a fixed guess, which is what was actually causing
   the overlap (a status body long enough to wrap past the fixed height guess bled into the players box drawn
   right below it).
3. **Couldn't zoom out enough to see the full ring set.** An off-center example player's own full ring (up to
   `maxRadiusChunks`) can extend roughly 1.5x the sampled radius from world origin, once its offset from center
   is accounted for — but `MIN_PREVIEW_ZOOM` was `1.0` (exactly fitting the sampled square), clipping exactly
   the rings the preview exists to show. Lowered to `0.4`.

**On the "don't alter other map-discovery methods' progress" question:** this does not prevent showing real
terrain, because the two are unrelated mechanisms. A vanilla map only reveals area a *specific player* has
explored while physically holding it, rendered client-side from what that player's own client has loaded; map
mods (Xaero's, Voxelmap, etc.) track "explored" chunks the same way, from their own client's own loaded chunks
as the player physically moves. `TerrainPreviewSampler` runs entirely server-side, is not tied to any player's
position, and returns only a small downsampled color grid used solely for this config screen's own background
texture — it never touches any player's `MapItemSavedData`, never sends full chunk data to any client, and
loading a chunk into server memory for this (round-3 fix above) does not, by itself, cause it to appear
"explored" on any player's map or mod — that is driven entirely by that player's own client, never by server-side
loadedness alone.

Modified this round: `TerrainPreviewSampler` (added the `getChunkAtAsync` phase), `ChunkyFriendsConfigScreen`
(responsive three-column layout, dynamic box sizing, `MIN_PREVIEW_ZOOM` lowered). `./gradlew build` succeeds; all
21 unit tests unaffected. Still unverified against a live client/server, as with every round before it.

### Post-Implementation Revision #4 (2026-08-07, same day) — disconnect report + loading UX

The user reported being disconnected from the server (`SocketException: Connection reset`) during testing, and
separately asked for loading indicators: one for the gap between running `/chunkyfriends gui` and the screen
actually opening, and one for the in-GUI "Loading…" placeholders (requested as an animated ellipsis:
`Loading` → `Loading.` → `Loading..` → `Loading...` → repeat).

**On the disconnect:** the crash report itself is a generic low-level TCP reset, which doesn't name a cause —
but round 3's fix (loading every confirmed-generated chunk via `getChunkAtAsync` before reading it) had an
unbounded worst case that was very plausibly the actual cause. The number of *distinct* chunks a
256x256-pixel sampled image touches is worst-case right around a ~128-chunk configured radius — where
`blocksPerPixel` is close enough to 16 that nearly every pixel lands in its own chunk — which could force-load
up to the full 65,536 chunks into server memory in one burst, on every debounced preview request (i.e.
repeatedly, while the player is just typing in the max-radius field). That is a large enough spike to plausibly
stall or exhaust a real server, which would present exactly as an abrupt connection reset. Fixed by capping
`TerrainPreviewSampler` at `MAX_CHUNKS_TO_LOAD_PER_SAMPLE = 2000` distinct chunks force-loaded per sample —
existence is still checked for every chunk (cheap), but chunks beyond the cap are left as fog rather than
loaded, trading a possibly-incomplete preview at extreme cases for not risking the server. The user was advised
to also check the server's own log/console around the disconnect time for a crash report or
`OutOfMemoryError` to confirm.

**Loading indicators:**
- `/chunkyfriends gui` previously gave zero feedback on success (only ever sent a message on failure) — the
  likely actual reason the user re-ran it multiple times. `ChunkyFriendsCommand.openGui` now sends immediate
  chat feedback (`message.chunky-friends.gui_opening`) on success, same as every other subcommand.
- Additionally, the client now shows a toast the instant the command is sent — via
  `ClientSendMessageEvents.COMMAND` (from `fabric-message-api-v1`, already bundled through the project's
  existing `fabric-api` umbrella dependency), matching on the literal command text `"chunkyfriends gui"` —
  which fires optimistically, before any server round trip at all, unlike the chat feedback above which still
  has to wait for the response to come back.
- The three in-GUI "Loading…" placeholders (preview info, status box, players box) were static text; replaced
  with a shared `buildLoadingText()` built from `System.currentTimeMillis()` (not a frame counter, so multiple
  call sites never drift out of sync with each other), cycling 0–3 trailing dots every 500ms as requested.

Modified this round: `TerrainPreviewSampler` (load cap), `ChunkyFriendsCommand.openGui` (success feedback),
`ConfigNetworkingClient` (optimistic toast), `ChunkyFriendsConfigScreen` (animated loading text). `./gradlew
build` succeeds; all 21 unit tests unaffected. The chunk-load cap is a reasoned mitigation for a plausible cause,
not a confirmed fix — the user has not yet reported whether disconnects stopped.

### Post-Implementation Revision #5 (2026-08-07, same day) — single real-player preview + cursor readout

The user asked to drop the three synthetic offset example players entirely in favor of a single marker at the
*viewing player's own real position*, labeled with their actual username instead of "Example Player N", plus a
live X/Z readout for wherever the mouse is hovering over the preview. They also reported still not seeing much
terrain, unclear where or why what did render, rendered.

This is a genuine design change from the spec's original three-synthetic-players approach, not a bug fix: the
scheduler (`PregenScheduler.selectAndStartNext`) has always centered every ring job on a real player's own
last-known position, never on spawn or an invented offset — the original spawn-centered, multi-synthetic-player
preview didn't actually reflect what the mod does. This revision makes the preview match: `TerrainPreviewSampler`
now samples around the *requesting player's* real, current position (in their current dimension) instead of
world spawn — `sampleAsync` takes a `ServerPlayer` directly rather than a bare `MinecraftServer`, reading
`player.getX()/getZ()` and `player.level()`. This also directly addresses the "unclear where terrain renders"
report: previously the sampled area (spawn) and the drawn ring (an invented offset from spawn) had no
necessary relationship to each other or to anywhere the player had actually been; now the sampled square is
centered exactly on where the player is standing, which is guaranteed to already be generated, so the preview
should now clearly and legibly show real terrain radiating out from the player's own icon.

Client-side, `ExamplePlayerLayout` (offset-fraction math for three synthetic players) is no longer used and was
deleted along with its test — nothing else referenced it. `ChunkyFriendsConfigScreen` now draws one ring set and
one head icon at the response's origin position (no per-player offset loop), and the head's hover tooltip shows
`minecraft.player.getGameProfile().name()` instead of a synthetic index. A new cursor-position line was added to
the existing preview-info text box (not a separate tooltip, to avoid competing with the head icon's own tooltip
and to reuse the already-confirmed-working `MultiLineTextWidget` text path) — it reports the world X/Z under the
mouse while it's inside the panel, and "(not hovering)" otherwise, recomputed every frame from the current
pan/zoom.

Modified this round: `TerrainPreviewSampler` (player-centered sampling), `ConfigNetworking.handleMapPreviewRequest`
(passes the player, not just the server), `ChunkyFriendsConfigScreen` (single marker, username tooltip, cursor
readout). Deleted: `ExamplePlayerLayout.java`, `ExamplePlayerLayoutTest.java`. `./gradlew build` succeeds; the
remaining 17 unit tests (the 4 `ExamplePlayerLayoutTest` cases no longer exist) all pass. Still unverified
against a live client/server.

### Post-Implementation Revision #6 (2026-08-07, same day) — screenshot-driven fixes

The user shared a screenshot: the preview-info text (X/Z range, grid legend, cursor readout) was visibly offset
from the preview panel's actual left edge, and only a scattered, seemingly-arbitrary subset of chunks around the
player were rendering as terrain instead of a clean disc.

1. **Info text misaligned.** `_previewInfoWidget` was positioned at `rightColumnX` (the column's left edge), but
   `_previewPanelX` is `rightColumnX` *plus* a centering offset whenever `_previewPanelSize < columnWidth` (which
   it usually is, since panel size is also height-constrained) — the panel itself was centered within its column
   and the text below it wasn't, so they visibly didn't share a left edge. Fixed by anchoring the widget to
   `_previewPanelX`/`_previewPanelSize` instead of the column's own bounds.
2. **Scattered chunk rendering.** The screenshot's `Maximum Radius` field read `800` (blocks — no `c` suffix — so
   50 chunks), which touches roughly 10,000 distinct chunks in the 256x256-pixel sample — well past
   `MAX_CHUNKS_TO_LOAD_PER_SAMPLE = 2000` from the previous round's stability fix. Chunks were being selected for
   loading in whatever order a `HashSet`/`HashMap` happened to iterate them, which has no spatial relationship to
   the player at all, producing exactly the "why did it render *these* chunks and not the ones right next to me"
   pattern shown. Fixed by sorting candidate chunks by squared distance from the player's own chunk before
   applying the cap, so a capped preview now always renders as a clean, contiguous disc of real terrain centered
   on the player with fog only appearing past whatever radius the cap allows — legible and explicable regardless
   of whether the cap is hit.

Modified this round: `ChunkyFriendsConfigScreen` (info-widget anchor), `TerrainPreviewSampler` (distance-sorted
load prioritization). `./gradlew build` succeeds; all 17 unit tests unaffected.

### Post-Implementation Revision #7 (2026-08-07, same day) — streamed, batched sampling

The user asked why the chunk-load cap was needed at all given the preview only needs a top-down color, not full
chunk data, and asked whether pieces of the map could render as soon as they're ready instead of waiting for the
whole thing.

Investigated whether a lighter-weight read exists: the actual heavy operation is `World.getChunkAtAsync`
force-loading a chunk into the live chunk system (tickets, entity/block-entity loading, light engine work) just
so vanilla's non-generating `ChunkSource.getChunk(..., requireChunk=false)` has something resident to return —
there is no publicly-reachable API to read a column's surface block/height straight from disk without that,
short of reflecting into `ChunkMap`'s private region-file storage, which was deliberately avoided (both here and
in earlier rounds) as too fragile to take on blind. So chunk loading itself can't be removed with the APIs
available — but the *hard cap that permanently fogged whatever didn't fit* can be, which is what actually
motivated the two changes below.

1. **Removed the exclusion cap in favor of batched streaming.** `MAX_CHUNKS_TO_LOAD_PER_SAMPLE` (a hard limit
   that silently left excess chunks as permanent fog) is gone. `TerrainPreviewSampler.sampleAsync` now processes
   all distinct chunks a sample touches in fixed-size (`CHUNK_BATCH_SIZE = 400`), nearest-to-player-first
   batches, invoking its callback once per completed batch with a full snapshot of everything resolved so far
   (`MapPreviewResponsePayload.isFinalUpdate()` marks the last one) instead of once at the very end. This
   directly answers both parts of the question: no chunk is ever permanently excluded regardless of total count,
   and the client now visibly fills in starting from right around the player within the first batch or two,
   rather than waiting for everything.
2. **Added a request id to avoid a stale-stream race.** With multiple in-flight responses now possible per
   request, editing the radius again before an earlier request's batches finish streaming could otherwise let a
   late straggler from the old request paint over newer data using the wrong `blocksPerPixel`/origin. Both
   `MapPreviewRequestPayload` and `MapPreviewResponsePayload` gained a `requestId` field (client-assigned,
   incremented per request, echoed back on every response); `ChunkyFriendsConfigScreen.applyMapPreview` now
   discards any response whose id doesn't match the most recently sent request.

Modified this round: `MapPreviewRequestPayload`/`MapPreviewResponsePayload` (new `requestId` field; response also
gained `isFinalUpdate`), `TerrainPreviewSampler` (rewritten around batched streaming), `ChunkyFriendsConfigScreen`
(request-id tracking, stale-response filtering). `./gradlew build` succeeds; all 17 unit tests unaffected — none
touch this code path. Still unverified against a live client/server.

### Deviations from Spec

- **`players()` also takes `configSupplier`, not just `schedulerSupplier`.** Step 6's literal registration snippet
  shows `players(context, schedulerSupplier)`, but Step 6.4's own prose requires `config.getRingCount()` per entry.
  `ChunkyFriendsCommand.register` passes both suppliers to `players(...)` to resolve this; behavior matches the
  spec's intent (ring count shown per player) exactly.
- **World spawn accessor.** The spec called out `ServerLevel.getSharedSpawnPos()` as needing verification against
  installed mappings — that method does not exist in this project's installed Minecraft/Mojmap version. Verified
  via `javap` against the actual `minecraft-common`/`minecraft-clientOnly` jars in `.gradle/loom-cache`: this
  version exposes the respawn point as `ServerLevel.getRespawnData().pos()` instead. Used that.
- **Client rendering API.** This project's installed Minecraft version does not have the traditional
  `Screen.render(GuiGraphics, int, int, float)`/`GuiGraphics` pair the spec's rendering steps were written against
  (verified via `javap` — no `GuiGraphics` class exists in the installed jars at all). Rendering is instead built
  around `Screen.extractRenderState(GuiGraphicsExtractor, int, int, float)` and the `Renderable` interface. The
  preview panel (background blit, chunk-grid `verticalLine`/`horizontalLine` calls, per-pixel `fill` calls for the
  midpoint-circle ring outlines, and `text` calls for the note/legend) is implemented against this actual API,
  overriding `extractRenderState` rather than `render`. Confirmed to compile cleanly against the real game jars; see
  Issues Encountered below for what this does and does not verify.
- **Chunk existence check.** Implemented as a single synchronous, main-thread call —
  `serverLevel.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)` — rather than an async/off-thread
  pipeline. `requireChunk = false` is vanilla's own "load if already at this status, never generate further"
  contract, satisfying "checks whether the chunk already exists without generating it." Kept synchronous rather
  than offloaded per the class's own `@implNote`; see Suggested Follow-Up Specs.
- **Map color shading.** Implemented the reduced equivalent the spec explicitly allows, not vanilla's full
  neighbor-height-band shading: each sampled column resolves to its `WORLD_SURFACE`-heightmap top block's
  `MapColor` at a single fixed `MapColor.Brightness.NORMAL`, with no up/down/same comparison against adjacent
  columns. See Suggested Follow-Up Specs.
- **Preview panel placement.** Not part of the existing `GridLayout`/`RowHelper` column (that layout doesn't support
  arbitrary custom-drawn content) — drawn at a fixed screen position (top-left, 180x180px) alongside it.

### Issues Encountered

No local decompiled/named Minecraft sources were available in this environment, and this project's Minecraft
version could not be identified from prior knowledge (`minecraft_version=26.2` in `gradle.properties` does not
correspond to a released version this model has training data for). Every non-obvious API surface this spec
touches — `ServerLevel` spawn access, chunk-existence checking, `MapColor` packing, `Heightmap.Types`, `NativeImage`/
`DynamicTexture`/`TextureManager`, `ClientPacketListener.sendCommand`, and the `GuiGraphicsExtractor` rendering API —
was verified directly against the real compiled jars in `.gradle/loom-cache` via `javap`, rather than assumed from
memory. The full project (`./gradlew build`) compiles and all unit tests pass against these real jars, which is
strong evidence the API usage is correct. However, **no running Minecraft client or dedicated server was available
in this environment**, so none of the manual/live-server acceptance criteria below were exercised: the commands'
actual chat/RCON/console output, the config screen's two new buttons, and — especially — the rendered preview panel
(background texture correctness, grid alignment, ring colors/legibility, legend text) have not been visually
confirmed. This mirrors Spec 001's own precedent (see its Post-Implementation Notes) of build-and-unit-test-only
verification in the absence of a live server.

### Suggested Follow-Up Specs

- The pre-existing `PlayerPregenState.lastSeenEpochMillis` never-disconnected edge case noted in this spec's Design
  Decisions remains unfixed and is now directly documented by `PlayerSelectorEligibilityTest.
  isEligible_neverDisconnectedDefaultLastSeen_returnsFalse`.
- `PregenScheduler.activeJobSnapshot()`/`eligiblePlayers(...)` were **not** extracted into separately pure-testable
  helpers — they were added as regular instance methods on `PregenScheduler` (which depends on live
  `MinecraftServer`/`ChunkyGateway` state) and are not covered by unit tests. Per the spec's own Test Requirements,
  this should be verified by manual/live-server testing, which has not yet happened — see Issues Encountered.
- `TerrainPreviewSampler`'s per-pixel coordinate math was **not** extracted into an isolated pure-testable piece —
  it remains inline against `ServerLevel`/`ChunkAccess` and is untested. Worth extracting the pixel→world-block→
  chunk-position math (and the sentinel-vs-real-color decision) into a pure helper the way `ChunkGridLayout` already
  is, both for testability and because it would make the boundary cases in this spec's Test Requirements
  (pixel exactly on a chunk edge, center pixel, outermost pixel at the clamped radius) actually checkable.
- Consider adding vanilla-style neighbor height-band shading to `TerrainPreviewSampler`'s map coloring (currently
  flat `MapColor.Brightness.NORMAL`) if manual testing shows the flat-shaded preview reads as visually flat/hard to
  read terrain relief.
- Consider offloading `TerrainPreviewSampler.sample` off the main server thread if manual testing at the full
  1000-chunk preview radius shows noticeable main-thread stutter when the config screen is first opened or the
  radius field is edited.
- The whole feature needs a manual, live-server-and-client pass against the actual Acceptance Criteria below,
  particularly the map preview's visual correctness — nothing about the rendering result itself (only that it
  compiles against the real API) has been confirmed.
