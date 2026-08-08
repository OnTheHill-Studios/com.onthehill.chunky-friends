# Spec 002 — Admin Commands & Status Visibility

**Status:** `Superseded by 007`
**Spec Author:** Claude (Anthropic) via Cowork
**Date Authored:** 2026-08-03

> **Superseded 2026-08-03:** Folded into `ai-specs/specs/007-status-visibility-and-full-config-parity.md` along with spec 006, at the user's request — this spec was never implemented. Kept on disk for historical reference only; implement 007 instead.
**Implementing AI:** [AI name, e.g. Claude Code]
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
  - `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` — existing client config screen this spec adds two buttons to
  - `src/main/java/com/onthehill/chunkyfriends/ChunkyFriends.java` — mod entrypoint wiring `PregenScheduler`/`ChunkyFriendsConfig` into the command dispatcher via deferred suppliers
  - `src/main/resources/assets/chunky-friends/lang/en_us.json` — existing lang key conventions

- **Writes to:**
  - `src/main/java/com/onthehill/chunkyfriends/scheduler/PregenScheduler.java` (modify)
  - `src/main/java/com/onthehill/chunkyfriends/scheduler/PlayerSelector.java` (modify)
  - `src/main/java/com/onthehill/chunkyfriends/command/ChunkyFriendsCommand.java` (modify)
  - `src/main/java/com/onthehill/chunkyfriends/ChunkyFriends.java` (modify)
  - `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` (modify)
  - `src/main/resources/assets/chunky-friends/lang/en_us.json` (modify)

- **Existing stubs:** None.

---

## Objective

Add two read-only, permission-gated commands — `/chunkyfriends status` (the single currently-active pregeneration job, its ring tier, and its progress) and `/chunkyfriends players` (every currently-eligible, i.e. not-evicted-by-the-qualifying-window, player and the ring tier their pregeneration coverage has reached) — reachable identically from RCON, from a connected player typing the command, and from a button in the existing client config GUI, with every invocation also writing an INFO-level summary line to the server log regardless of how it was invoked.

---

## Design Decisions (Read First)

- **The scheduler runs exactly one job at a time, system-wide.** `PregenScheduler` tracks a single `_activePlayerUuid`/`_activeWorld` pair (see `SchedulerState`), not one job per player. `/chunkyfriends status` therefore reports on *the* active job (naming whichever single player currently has one, if any) rather than a per-player job list — this is the existing, intentional scheduling model from Spec 001, not something this spec changes.
- **"Eligible" means "not evicted by the qualifying window," independent of ring completion.** `PlayerSelector.selectNext` currently filters candidates on two separate conditions: (1) seen within `qualifyingWindowHours`, and (2) `currentRingTier < ringCount`. `/chunkyfriends players` must reuse only condition (1) — a player who has fully completed all ring tiers but was seen recently is still "eligible" in the sense this command reports (they're just not a scheduling *candidate* anymore because they're done, which the per-player tier number already communicates). Extracting condition (1) into its own reusable method (see below) keeps this distinction explicit instead of silently baking ring-completion into "eligibility."
- **No new network protocol.** The GUI buttons this spec adds fire the exact same server command a player could type themselves (`/chunkyfriends status` / `/chunkyfriends players`) via the client's own chat-command-send API, reusing the existing permission gate, log-throttling, and RCON/console compatibility for free. This mirrors how `/chunkyfriends gui` already works the other direction (server → client instruction) without needing a third mechanism.
- **Logging is unconditional, feedback is the existing Brigadier mechanism.** "Get an output in my log" must hold true whether the command was run from RCON, from the console, or by a player. Brigadier's `sendSuccess`/`sendFailure` feedback is what RCON's response text and a player's chat line are built from, but it is not guaranteed to reach the server log file. Both new subcommands must therefore call `LOGGER.info(...)` explicitly with the same information, independent of the `sendSuccess` call.
- **Known pre-existing edge case, not in scope to fix here:** `PlayerPregenState.lastSeenEpochMillis` defaults to `0` and is only ever set on disconnect (see its Javadoc: "An online player leaves this value unchanged for the duration of their session"). A player who has never once disconnected since their state record was created (e.g. a brand-new player still in their first session) has `lastSeenEpochMillis == 0`, which — under the existing qualifying-window check — reads as "last seen at the Unix epoch," i.e. `nowEpochMillis - 0` almost certainly exceeds `qualifyingWindowHours`, so such a player would show as **not eligible** despite being online right now. This spec's `players` command surfaces the scheduler's actual understanding of eligibility byte-for-byte (see the eligibility-extraction requirement below), so this edge case will be visible in its output exactly as it already silently affects `selectNext`. Fixing it is out of scope here — see Suggested Follow-Up Specs.

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

---

## Constants

No new numeric constants — this spec reuses `qualifyingWindowHours`, `ringCount`, and the existing `DENIAL_LOG_THROTTLE_MILLIS` permission-denial throttle unchanged.

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

### Modify

- `src/main/java/com/onthehill/chunkyfriends/scheduler/PregenScheduler.java` — add progress-cache fields to `SchedulerState`, populate/clear them, add `activeJobSnapshot()` and `eligiblePlayers(long)`
- `src/main/java/com/onthehill/chunkyfriends/scheduler/PlayerSelector.java` — extract `isEligible(...)`, use it from `selectNext`
- `src/main/java/com/onthehill/chunkyfriends/command/ChunkyFriendsCommand.java` — add `status`/`players` subcommands, new `schedulerSupplier` parameter on `register`
- `src/main/java/com/onthehill/chunkyfriends/ChunkyFriends.java` — pass `() -> _pregenScheduler` at the `register` call site
- `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` — add Status/Players buttons
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

---

## Acceptance Criteria

The spec is complete when all of the following are true:

- [ ] `/chunkyfriends status`, run via RCON, via the console, and by a connected player with permission, each produce identical response text and an identical `LOGGER.info` log line
- [ ] `/chunkyfriends status` reports "no active job" plus the eligible-player count when nothing is running, and names the active player, their in-progress ring tier, ring count, and last-known progress percent/chunks/rate when something is
- [ ] `/chunkyfriends players` lists every player seen within `qualifyingWindowHours`, each with their current ring tier out of the configured ring count, and marks whichever one (if any) currently has the active job
- [ ] Both commands are denied (with the existing throttled denial log) to a source without the `chunky-friends:config` permission, exactly like `/chunkyfriends config` today
- [ ] The client config GUI has a working "Status" button and a working "Players" button that each produce the same server-side log line and chat feedback as typing the command manually
- [ ] `PlayerSelector.selectNext`'s existing behavior and existing tests are unaffected by the `isEligible` extraction
- [ ] All required tests pass
- [ ] No public member is missing Javadoc
- [ ] No `snake_case` identifiers introduced

---

## Post-Implementation Notes

> **This section is filled in by the implementing AI after the work is done.**

**Date Implemented:** [YYYY-MM-DD]
**Implementing AI:** [AI name + model]

### What Was Built

[Brief description of what was created]

### Deviations from Spec

[List any places where the implementation differed from the spec and why. "None" if exact.]

### Issues Encountered

[Anything that blocked or surprised the implementing AI]

### Suggested Follow-Up Specs

[Any new work discovered during implementation that should become a future spec — including, at minimum, whatever was noted above regarding the `lastSeenEpochMillis` never-disconnected edge case and the `activeJobSnapshot()`/`eligiblePlayers(...)` test-extractability outcome]
