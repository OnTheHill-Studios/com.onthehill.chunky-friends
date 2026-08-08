# Spec 003 — Full Configuration Parity (GUI & CLI)

**Status:** `Draft`
**Spec Author:** Claude (Sonnet 5) via Cowork
**Date Authored:** 2026-08-03
**Last Revised:** 2026-08-07 — split into a config-parity-only spec. The original version of this spec also contained a "Part B" (admin visibility commands: `/chunkyfriends status`, `/chunkyfriends players`, `ActiveJobSnapshot`, the `PlayerSelector.isEligible` extraction). That content is now implemented under the current Spec 002 (`ai-specs/specs/002-admin-commands-status-visibility.md`, `Ready`) and has been removed from this file to eliminate duplication — see `ai-specs/index.md`'s Agent Log for the full history of how these two spec numbers ended up overlapping.
**Implementing AI:** *(not yet assigned)*
**Depends On:** 001 (`ChunkyFriendsConfig`'s 7 fields), 004 (Client Configuration GUI & Command — implemented without a formal spec; the screen and command this spec extends).
**Supersedes:** the removed draft originally numbered 006 ("Full Configuration Parity for GUI and CLI"), folded into an earlier version of this spec at the user's request — see `ai-specs/specs/_to_delete/006-full-config-parity-gui-cli.md` and `ai-specs/index.md`'s Agent Log. This spec no longer contains admin-visibility content (see Last Revised above); that half of its former scope lives in Spec 002 instead.
**Coordination note — this spec adds a row to the same screen Spec 002 modifies:** `ChunkyFriendsConfigScreen` already gains a "Status" button and a "Players" button under Spec 002. This spec adds a third row, "Advanced...", opening a new sub-screen — it does not re-add Status/Players. Whichever of Spec 002 or this spec is implemented second must integrate the other's rows/widgets into the screen's actual current state rather than assuming a clean starting point; match whatever width convention already exists on the screen at implementation time.

---

## Context

> This spec gives every `ChunkyFriendsConfig` field a getter, a setter, and a reachable path from both the GUI and the CLI. Today only `ringCount`, `maxRadiusChunks`, and `curveExponent` (quadratic toggle) are settable from outside the mod; the other four fields (`qualifyingWindowHours`, `stallTimeoutTicks`, `checkIntervalTicks`, `progressLogIntervalSeconds`) can only be changed by hand-editing the on-disk JSON. It also fixes a real, motivating bug: rejecting an out-of-range config value today produces only a generic "values were rejected" message with no indication of which field or what the valid range is.

- **Reads from:**
  - `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` — current state (as of this spec's authoring; may have since gained Status/Players rows from Spec 002 — check the live file): single-column `GridLayout`, title, ring-count label+field, max-radius label+field, curve toggle, Save, Cancel. This spec adds one more row (Advanced...).
  - `src/main/java/com/onthehill/chunkyfriends/config/ChunkyFriendsConfig.java` — all 7 fields; only `ringCount`, `maxRadiusChunks`, `curveExponent` currently have setters. `qualifyingWindowHours`, `stallTimeoutTicks`, `checkIntervalTicks`, `progressLogIntervalSeconds` have getters only — the only way to change them today is hand-editing the on-disk JSON.
  - `src/main/java/com/onthehill/chunkyfriends/command/ChunkyFriendsCommand.java` — existing `config`/`gui` tree, `hasPermission` gate, denial-log throttling, and the `ringcount`/`maxradius`/`curve` subcommand pattern every new subcommand below follows.
  - `src/main/java/com/onthehill/chunkyfriends/network/ConfigNetworking.java`, `ConfigStatePayload.java`, `ConfigUpdatePayload.java`, `ConfigRequestPayload.java` — existing 3-field protocol; `ConfigRequestPayload`'s own Javadoc `@implNote` requires that any incompatible field change bump all three channel identifiers' `_v1` suffix together.
  - `src/main/java/com/onthehill/chunkyfriends/ChunkyFriends.java` — mod entrypoint wiring `ChunkyFriendsConfig` into the command dispatcher via a deferred supplier (see its own `implNote` on why a captured value would go stale).
  - `ai-specs/standards/rules/minecraft-gui-standards.md`, `java-coding-standards.md` — layout/rendering/logic separation, narration rules, Java naming/Javadoc/test conventions.
- **Writes to:** `client/screen/ChunkyFriendsConfigScreen.java`, `client/screen/ChunkyFriendsAdvancedConfigScreen.java` (new), `command/ChunkyFriendsCommand.java`, `config/ChunkyFriendsConfig.java`, `network/ConfigNetworking.java`, `network/ConfigStatePayload.java`, `network/ConfigUpdatePayload.java`, `network/ConfigRequestPayload.java`, `network/ConfigValues.java` (new), `network/ConfigField.java` (new), `network/RangeViolation.java` (new), `lang/en_us.json`.
- **Existing stubs:** None.

---

## Design Decisions (Read First)

- **This spec is config-parity only.** Admin visibility (`/chunkyfriends status`, `/chunkyfriends players`, the active-job snapshot, the eligibility extraction) is out of scope here — that work already exists under Spec 002. Do not reintroduce `ActiveJobSnapshot`, `PregenScheduler.activeJobSnapshot()`/`eligiblePlayers()`, or `PlayerSelector.isEligible` from this spec; if the live codebase doesn't already have them when this spec is implemented, that means Spec 002 hasn't landed yet, and this spec should still not duplicate them — flag the ordering issue instead.
- **Full config read/write parity needs a versioned network protocol change**, because it adds fields to payloads that already exist and are already versioned (`_v1`). Bump the existing three payload channel identifiers to `_v2` (see Part A1).
- **A field change that doesn't affect ring geometry must never reset player progress.** `ringCount`/`maxRadiusChunks`/`quadratic` changing triggers `onCurveChanged` (resets every tracked player's `currentRingTier`). The 4 newly-exposed fields must never trigger it — the easiest regression to introduce while wiring this up, and gets its own dedicated test.
- **Navigating between the main screen and the new Advanced sub-screen does not carry unsaved edits.** Each screen's `init()` sends its own fresh `ConfigRequestPayload` and seeds from `ConfigNetworkingClient.getLastKnownState()` (the most recent server-confirmed snapshot), not the other screen's currently-typed-but-unsaved text. Saving on either screen sends a full 7-field update built from that screen's own fields plus whatever it doesn't own, read from the last known server-confirmed state. Same "unsaved edits are discarded without Save" semantics the screen already has, just extended across two screens.

---

## Data Contract

### Inputs

| Component | Fields Used | Access |
|-----------|-------------|--------|
| `ChunkyFriendsConfig` | All 7 fields, via existing/new getters | Read-only from the network/command layer |
| `ConfigNetworkingClient.getLastKnownState()` | Whichever of the 7 fields the currently-saving screen doesn't itself own | Read-only |

### Outputs

| Component | Fields Modified | Notes |
|-----------|------------------|-------|
| `ChunkyFriendsConfig` | `_qualifyingWindowHours`, `_stallTimeoutTicks`, `_checkIntervalTicks`, `_progressLogIntervalSeconds` | New setters, validated centrally in `ConfigNetworking.applyUpdate`; rejected (not clamped) if out of range |
| Command feedback (chat/RCON) | New response text from the 4 new config subcommands, `config advanced`, and improved rejection messages on all config paths | See Algorithm |

### New Types Required

- `ConfigValues` (record, `network`) — `(int ringCount, int maxRadiusChunks, boolean quadratic, int qualifyingWindowHours, int stallTimeoutTicks, int checkIntervalTicks, int progressLogIntervalSeconds)`. The single value type `ConfigNetworking.applyUpdate` accepts and `ConfigStatePayload`/`ConfigUpdatePayload` are built from/around, so no call site passes 7 loose primitives.
- `ConfigField` (enum, `network`) — one entry per range-validated field: `RING_COUNT`, `MAX_RADIUS_CHUNKS`, `QUALIFYING_WINDOW_HOURS`, `STALL_TIMEOUT_TICKS`, `CHECK_INTERVAL_TICKS`, `PROGRESS_LOG_INTERVAL_SECONDS` (`quadratic` is excluded — it's a boolean toggle between two fixed literals, never "out of range"). See Part A5.
- `RangeViolation` (record, `network`) — `(ConfigField field, int min, int max, int actual)`, describing exactly one rejected field: which one, its valid bounds, and what was actually supplied. See Part A5.
- `ChunkyFriendsAdvancedConfigScreen` (`client/screen`) — the sub-screen for the 4 newly-exposed fields, parented to the main config screen the same way that screen is parented to ModMenu's list.

---

## Algorithm

### A1. Extend the network protocol, versioned

`ConfigStatePayload`/`ConfigUpdatePayload` grow from 3 fields to `ConfigValues`'s 7 (flattening the 7 fields directly into each record, matching their current flat style). Per `ConfigRequestPayload`'s documented rule, this is an incompatible wire-format change — bump **all three** config payload channel identifiers together: `config_request_v1 → v2`, `config_state_v1 → v2`, `config_update_v1 → v2`. `OpenConfigGuiPayload` (`open_gui_v1`) is unaffected. `ConfigNetworkingClient.detectProtocolSupport()`'s existing channel-set comparison automatically produces the correct `VERSION_MISMATCH` toast for a stale client/server with no additional detection logic needed.

`ConfigNetworking.applyUpdate` changes from `(config, ringCount, maxRadiusChunks, quadratic, onCurveChanged)` returning `boolean` to `(config, ConfigValues values, onCurveChanged)` returning `List<RangeViolation>` (empty list = accepted and applied; see A5 for what a non-empty list means and how it's turned into user-facing text). It computes its "did anything ring-geometry-relevant change" check using **only** `ringCount`/`maxRadiusChunks`/`quadratic`, exactly as today (see Design Decisions — this is the one regression this spec's own test suite is built to catch).

### A2. Validation ranges for the 4 new fields

| Field | Min | Max | Rationale |
|-------|-----|-----|-----------|
| `qualifyingWindowHours` | 1 | 8760 (1 year) | Below 1 hour, a brief disconnect loses eligibility almost immediately, defeating the point of a window; above a year is indistinguishable from "never expires." |
| `stallTimeoutTicks` | 20 (1s) | 432000 (6h) | Below 1s the stall check fires on normal generation gaps; above 6h a real stall goes unreported for most of a session. |
| `checkIntervalTicks` | 20 (1s) | 12000 (10min) | Below 1s the periodic refresh becomes a meaningful per-tick cost across many players; above 10min tracked positions go stale enough to visibly affect ring-tier selection. |
| `progressLogIntervalSeconds` | 1 | 3600 (1h) | Below 1s the throttle is meaningless; above 1h a long job gives almost no log feedback. |

Out-of-range values are rejected, not clamped, matching the existing `ringCount`/`maxRadiusChunks` behavior.

### A3. New "Advanced" GUI sub-screen

`ChunkyFriendsAdvancedConfigScreen`, opened via a new "Advanced..." button on the main screen (see Part C). Single-column form: title, then 4 label+`EditBox` pairs for the newly-exposed fields, then Save/Back — match whatever width convention the main screen uses at implementation time (see the coordination note above). No ring-coverage preview on this screen — none of its fields affect ring geometry. `init()` sends its own `ConfigRequestPayload` and seeds from `ConfigNetworkingClient.getLastKnownState()` (see Design Decisions on cross-screen edit semantics). `Back` behaves like the main screen's `Cancel` — returns via `onClose()`'s existing `minecraft.setScreenAndShow(_parent)` pattern, no network traffic.

### A4. CLI additions

Four new subcommands, same shape as the existing `ringcount`/`maxradius`/`curve`:

- `/chunkyfriends config qualifyingwindow <hours>` — `integer(MIN_QUALIFYING_WINDOW_HOURS, MAX_QUALIFYING_WINDOW_HOURS)`
- `/chunkyfriends config stalltimeout <ticks>` — `integer(MIN_STALL_TIMEOUT_TICKS, MAX_STALL_TIMEOUT_TICKS)`
- `/chunkyfriends config checkinterval <ticks>` — `integer(MIN_CHECK_INTERVAL_TICKS, MAX_CHECK_INTERVAL_TICKS)`
- `/chunkyfriends config progressloginterval <seconds>` — `integer(MIN_PROGRESS_LOG_INTERVAL_SECONDS, MAX_PROGRESS_LOG_INTERVAL_SECONDS)`

Each fetches the live config, builds a `ConfigValues` from current values with just that one field overridden, calls `ConfigNetworking.applyUpdate`, responds via the `respond(context, violations)` helper (see A5 — it now takes the returned `List<RangeViolation>` rather than a boolean). Brigadier's ranged `integer()` rejects out-of-range input at the parser level before the handler runs; `applyUpdate`'s own check remains the second line of defense for the GUI/network path, and now also gives `maxradius` (whose argument type can't be range-checked by Brigadier — see A5) exact-bounds feedback it never had before.

`/chunkyfriends config advanced` (no args) shows the 4 new values via a new `command.chunky-friends.config.current_advanced` key, mirroring `showCurrent`'s shape — kept separate from the existing `config` (no-args) command, mirroring the GUI's main/Advanced split.

### A5. Rejection messages report exactly which field(s) and their valid range

**Motivating bug:** today, entering an out-of-range value in the GUI (e.g. a Ring Tier Count of `100`, above `MAX_RING_COUNT = 64`) and pressing Save produces only "Those Chunky Friends configuration values are out of range and were rejected" — correct, but it never says what the actual range is, forcing the player to guess-and-check or go find the source. CLI subcommands that use Brigadier's own ranged `integer(min, max)` argument type (`ringcount`, `qualifyingwindow`, `stalltimeout`, `checkinterval`, `progressloginterval`) already get an exact-bounds message for free, at the parser level, before the handler even runs — that part of the CLI already works correctly and needs no change. The two places that don't are (1) the GUI's Save path, which never goes through Brigadier at all (it round-trips through `ConfigUpdatePayload`/`applyUpdate` directly), and (2) the `maxradius` CLI subcommand, whose argument is a bare `word()` (to allow the `500c`-style unit suffix — see `ConfigNetworking.parseRadiusChunks`'s existing `implNote`), so it never gets Brigadier's automatic bounds message either. This spec fixes both by making `applyUpdate` itself report the bounds, once, for every caller to reuse.

**`applyUpdate` validates every field before rejecting anything, and reports every violation, not just the first.** For each of the 6 range-validated fields (`ringCount`, `maxRadiusChunks`, `qualifyingWindowHours`, `stallTimeoutTicks`, `checkIntervalTicks`, `progressLogIntervalSeconds` — using the existing `MIN_RING_COUNT`/`MAX_RING_COUNT`/`MIN_RADIUS_CHUNKS`/`MAX_RADIUS_CHUNKS` constants already in `ConfigNetworking`, plus the 4 new ones from A2), check the supplied value against its bounds and collect a `RangeViolation(field, min, max, actual)` for every one that fails — do not short-circuit on the first failure. If the resulting list is non-empty, `applyUpdate` applies nothing (all-or-nothing, unchanged from today's behavior) and returns the full list; a caller fixing one field at a time based on a partial error list would otherwise have to make several round trips to discover each violation in turn.

**Formatting the message is centralized, not duplicated per caller.** Add `ConfigNetworking.describeViolations(List<RangeViolation> violations) → Component`, building one joined `Component` (newline-separated entries): a header line via `message.chunky-friends.config.range_violation_header` ("Those Chunky Friends configuration values were rejected:" — this repurposes the existing `message.chunky-friends.config.invalid_values` key as that header rather than a standalone sentence; see Lang Keys), then one entry line per violation. Every field except `MAX_RADIUS_CHUNKS` uses `message.chunky-friends.config.range_violation_entry` with the field's existing GUI label (`gui.chunky-friends.config.ring_count`, `.qualifying_window`, `.stall_timeout`, `.check_interval`, `.progress_log_interval` — reusing the same label the field already has in the screen, so the error message and the input it's complaining about always agree on what to call it) and its `min`/`max`/`actual` substituted in directly. `MAX_RADIUS_CHUNKS` gets its own entry key, `message.chunky-friends.config.range_violation_entry_max_radius`, showing bounds and the actual value in **both** blocks and chunks — matching how `command.chunky-friends.config.current` already presents this field — since `maxRadiusChunks` is the internal/persisted unit but blocks is what a bare-number GUI/CLI input means (see `ConfigNetworking.parseRadiusChunks`).

**Every caller switches from a boolean check to consuming the violation list:**
- `ConfigNetworking.handleUpdate` (the network handler behind the GUI's Save button): on a non-empty list, `LOGGER.warn` the same violations as plain English (one line per violation, for a grep-able log) and `player.sendSystemMessage(describeViolations(violations))` instead of today's single generic message.
- `ChunkyFriendsCommand`'s `respond` helper changes from `respond(context, boolean applied)` to `respond(context, List<RangeViolation> violations)`: empty → today's existing success path (`message.chunky-friends.config.saved`, return `1`); non-empty → `context.getSource().sendFailure(describeViolations(violations))`, return `0`. This is what gives the `maxradius` subcommand (and `ringcount`/`qualifyingwindow`/`stalltimeout`/`checkinterval`/`progressloginterval`, as a consistency backstop behind Brigadier's own parser-level check) exact-bounds feedback whenever a value does make it through to `applyUpdate` — e.g. `maxradius`'s unit-suffixed value, which Brigadier's `word()` argument type cannot range-check on its own.

### Part C — GUI button on the main screen

Add one new row to `ChunkyFriendsConfigScreen`'s existing single-column form, after whatever the last row is when this spec is implemented (per Spec 002, that's expected to be the Status/Players buttons, followed by Growth Curve/Save/Cancel — verify actual current order in the live file): **Advanced...**. On press, opens `ChunkyFriendsAdvancedConfigScreen(this)`. Added via `addDrawableChild` with a translatable label, participating in the screen's narration exactly like the existing buttons — read how those are currently wired before adding this one, rather than introducing a different construction style.

### Constants

| Constant | Value | Unit | Rationale |
|----------|-------|------|-----------|
| `MIN_QUALIFYING_WINDOW_HOURS` / `MAX_QUALIFYING_WINDOW_HOURS` | `1` / `8760` | hours | See A2. |
| `MIN_STALL_TIMEOUT_TICKS` / `MAX_STALL_TIMEOUT_TICKS` | `20` / `432000` | ticks | See A2. |
| `MIN_CHECK_INTERVAL_TICKS` / `MAX_CHECK_INTERVAL_TICKS` | `20` / `12000` | ticks | See A2. |
| `MIN_PROGRESS_LOG_INTERVAL_SECONDS` / `MAX_PROGRESS_LOG_INTERVAL_SECONDS` | `1` / `3600` | seconds | See A2. |

---

## Lang Keys (add to `en_us.json`)

| Key | English Text |
|-----|--------------|
| `command.chunky-friends.config.current_advanced` | `Qualifying window: %s hours, Stall timeout: %s ticks, Check interval: %s ticks, Progress log interval: %s seconds` |
| `gui.chunky-friends.config.advanced_button` | `Advanced...` |
| `gui.chunky-friends.config.advanced_title` | `Chunky Friends Advanced Configuration` |
| `gui.chunky-friends.config.qualifying_window` | `Qualifying Window (hours)` |
| `gui.chunky-friends.config.stall_timeout` | `Stall Timeout (ticks)` |
| `gui.chunky-friends.config.check_interval` | `Position Check Interval (ticks)` |
| `gui.chunky-friends.config.progress_log_interval` | `Progress Log Interval (seconds)` |
| `gui.chunky-friends.config.back` | `Back` |
| `message.chunky-friends.config.range_violation_entry` | `%s must be between %s and %s (you entered %s).` |
| `message.chunky-friends.config.range_violation_entry_max_radius` | `Maximum Radius must be between %s and %s blocks (%s and %s chunks) — you entered %s blocks.` |

**Repurposed key:** `message.chunky-friends.config.invalid_values` ("Those Chunky Friends configuration values are out of range and were rejected.") becomes the header line printed once above the itemized `range_violation_entry*` lines described in A5, rather than the entire message on its own — no new key needed for the header, but its usage site (`ConfigNetworking.describeViolations`) and effective meaning both change, so it's called out here rather than left implicit in the Modify list below.

Exact wording may be adjusted for tone consistency with existing keys — the *keys* and their argument order/count must match what the code passes to `Component.translatable`.

---

## Implementation Requirements

Files the implementing AI must create or modify. All paths relative to the package root.

### Create

- `src/main/java/com/onthehill/chunkyfriends/network/ConfigValues.java` — see Data Contract; Javadoc on the record and every component.
- `src/main/java/com/onthehill/chunkyfriends/network/ConfigField.java` — see Data Contract and A5.
- `src/main/java/com/onthehill/chunkyfriends/network/RangeViolation.java` — see Data Contract and A5; Javadoc on the record and every component.
- `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsAdvancedConfigScreen.java` — see A3.

### Modify

- `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` — add the Advanced row (Part C).
- `src/main/java/com/onthehill/chunkyfriends/network/ConfigNetworking.java` — new constants (A2), `applyUpdate(config, ConfigValues, onCurveChanged)` returning `List<RangeViolation>` (A1, A5), `describeViolations(List<RangeViolation>)` (A5), `toStatePayload` builds the 7-field payload.
- `src/main/java/com/onthehill/chunkyfriends/network/ConfigStatePayload.java`, `ConfigUpdatePayload.java`, `ConfigRequestPayload.java` — 4 new fields; `_v1` → `_v2` on all three channel identifiers.
- `src/main/java/com/onthehill/chunkyfriends/config/ChunkyFriendsConfig.java` — 4 new setters, Javadoc'd like `setRingCount`.
- `src/main/java/com/onthehill/chunkyfriends/command/ChunkyFriendsCommand.java` — 4 new config subcommands + `config advanced` (A4), `respond` changed to consume `List<RangeViolation>` (A5).
- `src/main/resources/assets/chunky-friends/lang/en_us.json` — all keys above.

---

## Test Requirements

Per `java-coding-standards.md`: JUnit 5, AAA pattern with labeled sections, `methodName_stateUnderTest_expectedBehavior` naming, isolated tests, `0.001` delta on float/double comparisons, no mocking Minecraft engine internals.

### `ConfigNetworking.applyUpdate` (extend existing tests)
- **Happy path:** `applyUpdate_allSevenFieldsInRange_appliesAndSaves` — asserts an empty violation list.
- **Boundary:** `applyUpdate_newFieldAtMinimumBound_applies`; `applyUpdate_newFieldOneBelowMinimumBound_rejectsAndDoesNotSave` — asserts a single `RangeViolation` with the correct `field`/`min`/`max`/`actual`.
- **Negative (highest-priority test in this spec):** `applyUpdate_onlyAdvancedFieldsChanged_doesNotInvokeOnCurveChanged` — arranges unchanged `ringCount`/`maxRadiusChunks`/`quadratic` but a changed `progressLogIntervalSeconds`; asserts the `onCurveChanged` spy/mock is never invoked.
- **Happy path:** `applyUpdate_ringCountAboveMaximum_returnsViolationWithCorrectBounds` — a `ringCount` of `100` against `MAX_RING_COUNT = 64` returns exactly one `RangeViolation(RING_COUNT, MIN_RING_COUNT, MAX_RING_COUNT, 100)` — this is the exact repro that motivated A5; regressing it silently would reintroduce the original bug.
- **Boundary:** `applyUpdate_multipleFieldsOutOfRange_returnsOneViolationPerField` — both `ringCount` and `stallTimeoutTicks` out of range in the same call returns a 2-element list, not just the first failure, and applies neither.

### `ConfigNetworking.describeViolations`
- **Happy path:** `describeViolations_singleNonRadiusViolation_includesFieldLabelAndBothBounds` — asserts the built `Component`'s content (via its plain-text form, e.g. `Component.getString()`) contains the field's label text and both the min and max values.
- **Boundary:** `describeViolations_maxRadiusViolation_includesBothBlocksAndChunks` — asserts the max-radius-specific formatting shows both units, not just the internal chunk value.
- **Negative:** `describeViolations_emptyList_returnsHeaderOnlyOrIsNeverCalled` — document/assert the expected behavior for a defensive empty-list call (this method is only meant to be called when the list is non-empty; pick whichever behavior — empty string, header-only, or an assertion the caller never does this — is simplest to implement correctly, and test that choice explicitly rather than leaving it undefined).

---

## Acceptance Criteria

- [ ] All 7 `ChunkyFriendsConfig` fields have both a getter and a setter, and are reachable from the GUI (main screen for the original 3, Advanced sub-screen for the other 4) and the CLI.
- [ ] `/chunkyfriends config qualifyingwindow|stalltimeout|checkinterval|progressloginterval <value>` and `/chunkyfriends config advanced` all work and validate correctly; out-of-range values are rejected, not clamped, from both GUI and CLI.
- [ ] Rejecting an out-of-range config value — from the GUI's Save button, from the `maxradius` CLI subcommand, or from any other path into `applyUpdate` — tells the user exactly which field(s) were out of range and the valid min/max for each, not just that "values were rejected." Setting Ring Tier Count to `100` from the GUI (the bug that motivated this addition) now reports "must be between 1 and 64" rather than a generic rejection.
- [ ] Submitting more than one out-of-range field in the same update reports every violated field, not just the first one found.
- [ ] All three config network payload channel identifiers are versioned `_v2`; a client/server pair on mismatched versions gets the existing `VERSION_MISMATCH` toast.
- [ ] Changing only an Advanced-screen/CLI-equivalent field never resets tracked players' pregeneration progress (verified by the dedicated negative test).
- [ ] The GUI's Advanced button opens the new sub-screen, showing the 4 newly-exposed fields, seeded from the last known server-confirmed state.
- [ ] No field in `ChunkyFriendsConfig` requires hand-editing the JSON file — every field is reachable from the GUI and the CLI.
- [ ] All required tests pass.
- [ ] No hardcoded literal UI strings; every label/button/message goes through a lang key. No `snake_case` identifiers; Allman braces; 4-space indentation; all public members Javadoc'd.

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
