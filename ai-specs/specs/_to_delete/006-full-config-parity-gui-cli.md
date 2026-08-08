# Spec 006 — Full Configuration Parity for GUI and CLI

**Status:** `Superseded by 007`
**Spec Author:** Claude (Sonnet 5) via Cowork
**Date Authored:** 2026-08-03

> **Superseded 2026-08-03:** Folded into `ai-specs/specs/007-status-visibility-and-full-config-parity.md` along with spec 002, at the user's request — this spec was never implemented. Kept on disk for historical reference only; implement 007 instead.
**Implementing AI:** *(not yet assigned)*
**Depends On:** 001 (introduces all 7 config fields this spec exposes), 004 (Client Configuration GUI & Command — implemented without a formal spec; the screen and command this spec extends), 005 (Config Screen Layout Symmetry & Ring Coverage Preview — this spec's new button and new screen must follow that spec's `WIDGET_WIDTH` symmetric-layout rule). Also touches the same two files as 002 (Admin Commands & Status Visibility, `Ready` but not yet implemented) — `ChunkyFriendsConfigScreen.java` and `ChunkyFriendsCommand.java` — see the coordination note under Context.

---

## Context

> `ChunkyFriendsConfig` (see `config/ChunkyFriendsConfig.java`) has 7 tunable fields, but only 3 are reachable from the GUI, the CLI, or the network protocol that connects them: `ringCount`, `maxRadiusChunks`, `curveExponent`. The other 4 — `qualifyingWindowHours`, `stallTimeoutTicks`, `checkIntervalTicks`, `progressLogIntervalSeconds` — have getters only; `ChunkyFriendsConfig` has no setters for them at all, `ConfigStatePayload`/`ConfigUpdatePayload` don't carry them, and neither the GUI screen nor `/chunkyfriends config` has any way to view or change them. The only way to change these 4 today is hand-editing the on-disk JSON and restarting (or waiting for the next load). This spec closes that gap so every field in `ChunkyFriendsConfig` is reachable from both the GUI and the CLI, and the JSON file never needs to be opened by hand.

- **Reads from:**
  - `src/main/java/com/onthehill/chunkyfriends/config/ChunkyFriendsConfig.java` — the 4 unexposed fields and their existing getters/Javadoc (defaults: `qualifyingWindowHours = 24`, `stallTimeoutTicks = 12000`, `checkIntervalTicks = 200`, `progressLogIntervalSeconds = 30`).
  - `src/main/java/com/onthehill/chunkyfriends/scheduler/PregenScheduler.java` (lines ~191, 200, 227) and `scheduler/PlayerSelector.java` (line ~33) — both read `_config.getStallTimeoutTicks()` / `getCheckIntervalTicks()` / `getProgressLogIntervalSeconds()` / `getQualifyingWindowHours()` directly off the live, shared `ChunkyFriendsConfig` instance on every check rather than a value cached at construction — confirms a setter mutating that same instance takes effect immediately, with no scheduler restart or re-registration needed. This spec is scoped to *making the setters reachable*; it does not need to touch either scheduler class.
  - `src/main/java/com/onthehill/chunkyfriends/network/ConfigNetworking.java` — existing `MIN_RING_COUNT`/`MAX_RING_COUNT`/`MIN_RADIUS_CHUNKS`/`MAX_RADIUS_CHUNKS` constants and `applyUpdate`'s validate-then-apply-then-conditionally-reset shape, which the 4 new fields must follow without triggering that reset (see Algorithm).
  - `src/main/java/com/onthehill/chunkyfriends/network/ConfigStatePayload.java`, `ConfigUpdatePayload.java`, `ConfigRequestPayload.java` — `ConfigRequestPayload`'s own Javadoc `@implNote` requires that "a future incompatible change to this payload's fields must bump the suffix on all three config payloads' channel identifiers" — adding 4 fields to `ConfigStatePayload`/`ConfigUpdatePayload` is exactly that case.
  - `src/main/java/com/onthehill/chunkyfriends/command/ChunkyFriendsCommand.java` — existing `ringcount`/`maxradius`/`curve` subcommand pattern (Brigadier literal + argument, `.requires(hasPermission)`, `respond(context, applied)`) to mirror for the 4 new subcommands.
  - `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` — as restructured by spec 005 (`WIDGET_WIDTH`-symmetric single-column form + right-hand preview). This spec adds one more row (an "Advanced..." button) to that same column.
  - **Coordination note:** spec 002 (`Ready`, not yet implemented) also modifies `ChunkyFriendsConfigScreen.java` (adding Status/Players buttons) and `ChunkyFriendsCommand.java` (adding `status`/`players` subcommands). Whichever of 002/005/006 is implemented last should re-read the other two's resulting files before editing rather than assuming a clean starting point — none of the three are semantically in conflict (different rows/subcommands), but they touch the same files.
- **Writes to:** `ChunkyFriendsConfig.java` (4 new setters), `network/ConfigStatePayload.java` + `ConfigUpdatePayload.java` + `ConfigRequestPayload.java` (schema version bump), `network/ConfigNetworking.java` (new validation constants, `applyUpdate` signature change), `client/network/ConfigNetworkingClient.java` (no logic change expected, but re-verify after the payload shape change), `client/screen/ChunkyFriendsConfigScreen.java` (new "Advanced..." button), `command/ChunkyFriendsCommand.java` (4 new subcommands + `config advanced` display), `lang/en_us.json`.
- **Existing stubs:** None.

---

## Objective

Every field in `ChunkyFriendsConfig` becomes settable from the running game — the GUI (via a new "Advanced" sub-screen) and the CLI (via 4 new `/chunkyfriends config` subcommands) both gain full read/write access to `qualifyingWindowHours`, `stallTimeoutTicks`, `checkIntervalTicks`, and `progressLogIntervalSeconds`, joining the 3 fields already exposed — so the on-disk config JSON never has to be hand-edited for any supported setting.

---

## Data Contract

### Inputs

| Component | Fields Used | Access |
|-----------|-------------|--------|
| `ChunkyFriendsConfig` | All 7 fields, via existing/new getters | Read-only from the network/command layer |
| `ConfigNetworkingClient.getLastKnownState()` | Whichever of the 7 fields the currently-saving screen/command doesn't itself own, so a partial edit doesn't clobber the others | Read-only |

### Outputs

| Component | Fields Modified | Notes |
|-----------|------------------|-------|
| `ChunkyFriendsConfig` | `_qualifyingWindowHours`, `_stallTimeoutTicks`, `_checkIntervalTicks`, `_progressLogIntervalSeconds` | New setters, validated centrally in `ConfigNetworking.applyUpdate` before assignment — same pattern as the existing 3 fields; clamped to the new range constants below and rejected (not clamped) if out of range, matching existing `ringCount`/`maxRadiusChunks` behavior |

### New Types Required

- `ConfigValues` (record, `network` package) — `(int ringCount, int maxRadiusChunks, boolean quadratic, int qualifyingWindowHours, int stallTimeoutTicks, int checkIntervalTicks, int progressLogIntervalSeconds)`. Replaces the current 3-field `ConfigStatePayload`/`ConfigUpdatePayload` bodies structurally (see Algorithm Step 1) and is also the single value type `ConfigNetworking.applyUpdate` accepts, so neither the network handler, the GUI, nor the CLI command has to pass 7 loose primitive parameters through a growing method signature.
- `ChunkyFriendsAdvancedConfigScreen` (`client/screen`) — the new sub-screen for the 4 previously-unexposed fields, parented to the main config screen the same way that screen is parented to ModMenu's list (`Screen(Screen parent)` → `onClose()` returns to it).

---

## Algorithm

### Step 1 — Extend the network protocol, versioned

`ConfigStatePayload` and `ConfigUpdatePayload` both grow from 3 fields to the new `ConfigValues` record's 7 (either by wrapping one `ConfigValues` field, or by flattening all 7 fields directly into each payload record — either is acceptable; flattening matches the existing style of these two records more closely, since they're currently flat, and avoids adding a nested-record encode/decode step to the `StreamCodec`). Per `ConfigRequestPayload`'s own documented rule, this is an incompatible wire-format change, so **all three** config payload channel identifiers bump from `_v1` to `_v2`: `config_request_v2`, `config_state_v2`, `config_update_v2`. `OpenConfigGuiPayload` (`open_gui_v1`) is unaffected — its shape doesn't change. `ConfigNetworkingClient.detectProtocolSupport()` already compares the server's advertised channel set against `ConfigRequestPayload.TYPE.id()`/`ConfigUpdatePayload.TYPE.id()`, so the version bump automatically produces the correct `VERSION_MISMATCH` toast for a stale client or server without any additional detection logic — this is exactly the scenario that comment exists to guard against.

`ConfigNetworking.applyUpdate` changes from `(config, ringCount, maxRadiusChunks, quadratic, onCurveChanged)` to `(config, ConfigValues values, onCurveChanged)`, validates and applies all 7 fields, and — critically — computes its "did anything that affects ring geometry change" check using **only** `ringCount`/`maxRadiusChunks`/`quadratic` (the existing 3), exactly as today. A change to `qualifyingWindowHours`/`stallTimeoutTicks`/`checkIntervalTicks`/`progressLogIntervalSeconds` alone must never invoke `onCurveChanged` (which resets every tracked player's progress) — these 4 fields don't affect ring boundaries and a progress reset triggered by, say, tweaking a log interval would be a serious, confusing regression.

### Step 2 — Validation ranges for the 4 new fields

Centralized in `ConfigNetworking`, same shape as `MIN_RING_COUNT`/`MAX_RING_COUNT`:

| Field | Min | Max | Rationale |
|-------|-----|-----|-----------|
| `qualifyingWindowHours` | 1 | 8760 (1 year) | Below 1 hour, a player who briefly disconnects loses scheduling eligibility almost immediately, which defeats the point of a "window" at all; above a year is never meaningfully different from "never expires" and just invites confusion about whether it's actually bounded. |
| `stallTimeoutTicks` | 20 (1 second) | 432000 (6 hours) | Below 1 second the stall check fires essentially every tick, spamming the stall-warning log line for entirely normal generation gaps; above 6 hours a genuinely stalled job goes unreported for most of a play session. |
| `checkIntervalTicks` | 20 (1 second) | 12000 (10 minutes) | Below 1 second, the periodic position-refresh becomes a meaningful per-tick cost across many online players; above 10 minutes, a player's tracked position goes stale long enough to visibly affect ring-tier selection accuracy. |
| `progressLogIntervalSeconds` | 1 | 3600 (1 hour) | Below 1 second the throttle is meaningless (defeats its own purpose); above 1 hour, a long-running job produces effectively no progress feedback in the log for most of its run. |

Out-of-range values are **rejected**, not clamped — same behavior as the existing `ringCount`/`maxRadiusChunks` validation in `applyUpdate`, so the 4 new fields don't introduce a second, inconsistent validation philosophy alongside the first 3.

### Step 3 — Split the GUI: new "Advanced" sub-screen

`ChunkyFriendsConfigScreen` (as restructured by spec 005) gains one new row: an "Advanced..." `Button`, same `WIDGET_WIDTH` as every other widget in that column, placed after the Growth Curve toggle and before Save/Cancel. Pressing it opens `ChunkyFriendsAdvancedConfigScreen(this)`.

`ChunkyFriendsAdvancedConfigScreen` is a single-column `GridLayout` built the same way spec 005 builds the main screen's form column (title `StringWidget`, then 4 label+`EditBox` row pairs, then Save/Back `Button`s, all at the same `WIDGET_WIDTH`) — it does **not** get its own copy of the ring-coverage preview panel, since none of its 4 fields affect ring geometry. Like the main screen, its `init()` sends a fresh `ConfigRequestPayload` and seeds its fields from `ConfigNetworkingClient.getLastKnownState()` (falling back to the field defaults in `ChunkyFriendsConfig` if no snapshot has arrived yet this session) rather than trusting the main screen's possibly-unsaved, possibly-stale in-progress edits — this is a deliberate simplification: **navigating between the main and Advanced screens does not carry unsaved edits from one to the other.** Saving on either screen sends a full `ConfigValues` built from that screen's own fields plus whichever fields it doesn't own, read from `ConfigNetworkingClient.getLastKnownState()` (i.e., the most recent *server-confirmed* values, not the other screen's currently-typed-but-unsaved text) — identical in spirit to how closing either screen without pressing Save already discards that screen's own unsaved edits today; this spec doesn't introduce a new class of surprise, just extends the existing one-screen-at-a-time semantics to two screens.

`Back` behaves like `Cancel` on the main screen — returns to the parent via `onClose()`'s existing `minecraft.setScreenAndShow(_parent)` pattern, no network traffic.

### Step 4 — CLI: mirror the GUI's split

`/chunkyfriends config` (no arguments) is unchanged — still shows only the 3 core values via the existing `command.chunky-friends.config.current` message.

Four new subcommands, same shape as the existing `ringcount`/`maxradius`/`curve` ones:

- `/chunkyfriends config qualifyingwindow <hours>` — `integer(MIN_QUALIFYING_WINDOW_HOURS, MAX_QUALIFYING_WINDOW_HOURS)`
- `/chunkyfriends config stalltimeout <ticks>` — `integer(MIN_STALL_TIMEOUT_TICKS, MAX_STALL_TIMEOUT_TICKS)`
- `/chunkyfriends config checkinterval <ticks>` — `integer(MIN_CHECK_INTERVAL_TICKS, MAX_CHECK_INTERVAL_TICKS)`
- `/chunkyfriends config progressloginterval <seconds>` — `integer(MIN_PROGRESS_LOG_INTERVAL_SECONDS, MAX_PROGRESS_LOG_INTERVAL_SECONDS)`

Each follows `setRingCount`'s existing pattern exactly: fetch the live config, build a `ConfigValues` from the config's current values with just that one field overridden, call `ConfigNetworking.applyUpdate`, respond via the existing `respond(context, applied)` helper. Brigadier's `integer(min, max)` argument type already rejects out-of-range input at the parser level (with its own built-in error message) before the command body even runs, matching how `ringcount` already works — no separate range check needed in the handler itself, only in `applyUpdate` as the second line of defense for the GUI/network path, which doesn't get Brigadier's parser-level check for free.

A new `/chunkyfriends config advanced` (no arguments) subcommand shows the 4 new values, mirroring `showCurrent`'s existing shape, via a new `command.chunky-friends.config.current_advanced` lang key — kept as a separate command/message rather than folding into the existing one, mirroring the GUI's main/Advanced split so a player who only cares about ring geometry isn't shown 4 extra numbers by default.

### Constants

| Constant | Value | Unit | Rationale |
|----------|-------|------|-----------|
| `MIN_QUALIFYING_WINDOW_HOURS` / `MAX_QUALIFYING_WINDOW_HOURS` | `1` / `8760` | hours | See Step 2. |
| `MIN_STALL_TIMEOUT_TICKS` / `MAX_STALL_TIMEOUT_TICKS` | `20` / `432000` | ticks | See Step 2. |
| `MIN_CHECK_INTERVAL_TICKS` / `MAX_CHECK_INTERVAL_TICKS` | `20` / `12000` | ticks | See Step 2. |
| `MIN_PROGRESS_LOG_INTERVAL_SECONDS` / `MAX_PROGRESS_LOG_INTERVAL_SECONDS` | `1` / `3600` | seconds | See Step 2. |

---

## Implementation Requirements

Files the implementing AI must create or modify. All paths relative to the package root.

### Create

- `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsAdvancedConfigScreen.java`
  - Extends `Screen`, constructed with a parent `Screen` reference (the main config screen), same shape as `ChunkyFriendsConfigScreen`
  - Single-column `GridLayout` at spec 005's `WIDGET_WIDTH`, per Step 3
  - Allman braces, 4-space indent, `_camelCase` private fields, all public members Javadoc'd, no hardcoded literal UI strings

- `src/main/java/com/onthehill/chunkyfriends/network/ConfigValues.java`
  - Record per Data Contract's New Types Required
  - Javadoc on the record and every component per `java-coding-standards.md`

### Modify

- `src/main/java/com/onthehill/chunkyfriends/config/ChunkyFriendsConfig.java` — add `setQualifyingWindowHours`, `setStallTimeoutTicks`, `setCheckIntervalTicks`, `setProgressLogIntervalSeconds`, each Javadoc'd like the existing `setRingCount`.
- `src/main/java/com/onthehill/chunkyfriends/network/ConfigStatePayload.java`, `ConfigUpdatePayload.java`, `ConfigRequestPayload.java` — add the 4 new fields to `ConfigStatePayload`/`ConfigUpdatePayload` (or wrap `ConfigValues`); bump all three channel identifiers `_v1` → `_v2` per Step 1.
- `src/main/java/com/onthehill/chunkyfriends/network/ConfigNetworking.java` — new constants per Step 2; `applyUpdate` takes a `ConfigValues` and validates/applies all 7 fields, preserving the existing 3-field-only change-detection for `onCurveChanged`; `toStatePayload` builds the now-7-field state payload.
- `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` — add the "Advanced..." button per Step 3 (one more `WIDGET_WIDTH` row, per spec 005's layout rule).
- `src/main/java/com/onthehill/chunkyfriends/command/ChunkyFriendsCommand.java` — 4 new subcommands + `config advanced` display, per Step 4.
- `src/main/resources/assets/chunky-friends/lang/en_us.json` — new keys: `gui.chunky-friends.config.advanced_button`, `gui.chunky-friends.config.advanced_title`, `gui.chunky-friends.config.qualifying_window`, `gui.chunky-friends.config.stall_timeout`, `gui.chunky-friends.config.check_interval`, `gui.chunky-friends.config.progress_log_interval`, `gui.chunky-friends.config.back`, `command.chunky-friends.config.current_advanced`.

---

## Test Requirements

Per `java-coding-standards.md`: JUnit 5, AAA pattern, `methodName_stateUnderTest_expectedBehavior` naming.

### `ConfigNetworking.applyUpdate` (extend existing test class)

#### Happy Path
- **`applyUpdate_allSevenFieldsInRange_appliesAndSaves`**

#### Boundary / Limit Tests
- **`applyUpdate_newFieldAtMinimumBound_applies`** — one representative new field (e.g. `stallTimeoutTicks` at `MIN_STALL_TIMEOUT_TICKS`).
- **`applyUpdate_newFieldOneBelowMinimumBound_rejectsAndDoesNotSave`**

#### Negative / Toxicity Test
- **`applyUpdate_onlyAdvancedFieldsChanged_doesNotInvokeOnCurveChanged`** — arranges a config where `ringCount`/`maxRadiusChunks`/`quadratic` are unchanged but `progressLogIntervalSeconds` differs; asserts the `onCurveChanged` callback (a test spy/mock) is never invoked. This is the single most important test in this spec — it's the one regression (a spurious progress reset from an unrelated field) that would be easy to introduce and easy to miss without an explicit test for it.

---

## Acceptance Criteria

The spec is complete when all of the following are true:

- [ ] All 7 `ChunkyFriendsConfig` fields have both a getter and a setter.
- [ ] `/chunkyfriends config qualifyingwindow|stalltimeout|checkinterval|progressloginterval <value>` all exist, validate via Brigadier's ranged `integer()` argument, and apply via `ConfigNetworking.applyUpdate`.
- [ ] `/chunkyfriends config advanced` (no args) displays all 4 new values.
- [ ] The main config screen (as laid out by spec 005) has a new "Advanced..." button at the same `WIDGET_WIDTH` as every other widget in its column.
- [ ] `ChunkyFriendsAdvancedConfigScreen` exposes all 4 new fields for editing, Save applies them, Back discards unsaved edits and returns to the main screen.
- [ ] Changing only an Advanced-screen field never resets tracked players' pregeneration progress (verified by the negative test above).
- [ ] All three config network payload channel identifiers are versioned `_v2`; a client/server pair on mismatched versions gets the existing `VERSION_MISMATCH` toast rather than a silent decode failure.
- [ ] Every new value is rejected (not silently clamped) when out of range, from both the GUI and the CLI.
- [ ] No field in `ChunkyFriendsConfig` requires hand-editing the JSON file to change — every field is reachable from at least one of the GUI or the CLI (in practice, both).
- [ ] No hardcoded literal UI strings; every new label/button/message goes through a lang key.
- [ ] No `snake_case` identifiers; Allman braces; 4-space indentation; all public members Javadoc'd.
- [ ] All required tests pass.

---

## Post-Implementation Notes

> **This section is filled in by the implementing AI after the work is done.**

**Date Implemented:** *(pending)*
**Implementing AI:** *(pending)*

### What Was Built

*(pending)*

### Deviations from Spec

*(pending)*

### Issues Encountered

*(pending)*

### Suggested Follow-Up Specs

*(pending)*
