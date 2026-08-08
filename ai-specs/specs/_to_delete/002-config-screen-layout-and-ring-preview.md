# Spec 002 — Config Screen Layout Symmetry & Ring Coverage Preview

**Status:** `Draft`
**Spec Author:** Claude (Sonnet 5) via Cowork
**Date Authored:** 2026-08-03
**Implementing AI:** *(not yet assigned)*
**Depends On:** 001 (`RingCurve`'s curve formula, reused here for visual parity), 005 (Client Configuration GUI & Command — implemented without a formal spec; see `ai-specs/index.md` Future Specs row 005). Independent of 003 (Admin Visibility Commands & Full Configuration Parity — folds former specs originally numbered 002 and 006, still `Draft`) — if 003 lands first and adds its three new buttons (Status, Players, Advanced) to this screen, apply the same `WIDGET_WIDTH` treatment described here to them as a small follow-up, not blocking either spec on the other.

> **Renumbered 2026-08-03:** this spec was originally authored and delivered as "Spec 005." Before any of the specs in this repository's second wave (originally numbered 002, 005, 006, 007) were implemented, the whole batch was renumbered into a clean, gap-free sequence — see `ai-specs/index.md`'s Agent Log for the full rationale. No content changed beyond spec-number cross-references.

---

## Context

> `ChunkyFriendsConfigScreen` currently lays out its title, two labeled `EditBox` fields, a `CycleButton`, and Save/Cancel buttons as a single-column `GridLayout`. Because each widget is constructed with its own ad-hoc width (`EditBox` fields hardcoded to 200px via `FIELD_WIDTH`, `Button`s left at their unspecified/default width, `StringWidget` labels sized to their own text), the column renders lopsided — see the reference screenshot in this spec's originating conversation, where the input fields and the "Maximum Radius" label visibly overhang the narrower Save/Cancel buttons below them. Separately, the mod has no way to visualize what a given `Ring Tier Count` / `Maximum Radius` / `Growth Curve` combination actually looks like in-world until a player saves it and watches pregeneration happen — a live schematic preview closes that gap.

- **Reads from:**
  - `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` — the screen this spec restructures. Current layout: single-column `GridLayout` via `RowHelper(1)`, `columnSpacing(8)`/`rowSpacing(8)`; `FIELD_WIDTH = 200`, `FIELD_HEIGHT = 20`; children added in order: title `StringWidget`, ring-count label + `_ringCountBox`, max-radius label + `_maxRadiusBox`, `_curveButton` (`CycleButton<Boolean>`, quadratic/linear), Save `Button`, Cancel `Button`.
  - `src/main/java/com/onthehill/chunkyfriends/scheduler/RingCurve.java` — `radiusForTier(tier, ringCount, maxRadiusChunks, curveExponent)`, `@implNote radius(i) = round(maxRadiusChunks * (i / ringCount) ^ curveExponent)`. This is the *actual gameplay* ring-boundary formula (spec 001) and must be the single source of truth the preview's ring boundaries are derived from — the preview must not carry a second, independently-drifting copy of this math.
  - `src/main/java/com/onthehill/chunkyfriends/network/ConfigNetworking.java` — `MIN_RING_COUNT`/`MAX_RING_COUNT` (1–64), `MIN_RADIUS_CHUNKS`/`MAX_RADIUS_CHUNKS` (1–100,000), `BLOCKS_PER_CHUNK` (16), `parseRadiusChunks(String)`, and the private `LINEAR_CURVE_EXPONENT`/`QUADRATIC_CURVE_EXPONENT` (1.0/2.0) that `applyUpdate` maps a boolean onto server-side.
  - `ai-specs/standards/rules/minecraft-gui-standards.md` — layout/rendering/logic separation, custom-widget, texture-identifier-caching, tooltip/narration, and performance rules all apply to the new widget.
  - `ai-specs/standards/rules/brand-standards.md` — no arbitrary colors; any UI chrome color (e.g. the player-marker tint) must come from the OTH palette.
- **Writes to:** `ChunkyFriendsConfigScreen.java` (layout + wiring), a new `RingPreviewWidget` class, `RingCurve.java` (adds a reusable pure ratio method, no behavior change to `radiusForTier`), `ConfigNetworking.java` (exposes the curve-exponent mapping so it isn't redefined client-side), `assets/chunky-friends/lang/en_us.json` (one new narration key).
- **Existing stubs:** None. This is new widget work layered onto an already-implemented (if undocumented) screen.

---

## Objective

Make `ChunkyFriendsConfigScreen`'s form column visually symmetric — title, labels, fields, and buttons all rendering at one shared width instead of the current mismatched widths — and add a live, non-interactive preview panel on the right that shows, as a small schematic "Minecraft map," what three hypothetical players' ring coverage looks like for the screen's *current, unsaved* field values, updating immediately as those values change.

---

## Data Contract

### Inputs

| Component | Fields Used | Access |
|-----------|-------------|--------|
| `ChunkyFriendsConfigScreen._ringCountBox` | Text value, parsed to `int`, clamped to `[ConfigNetworking.MIN_RING_COUNT, MAX_RING_COUNT]` for preview purposes only | Read-only |
| `ChunkyFriendsConfigScreen._maxRadiusBox` | Text value, parsed via `ConfigNetworking.parseRadiusChunks`, converted to blocks (`× BLOCKS_PER_CHUNK`), clamped to `[MIN_RADIUS_CHUNKS, MAX_RADIUS_CHUNKS] × BLOCKS_PER_CHUNK` for preview purposes only | Read-only |
| `ChunkyFriendsConfigScreen._quadratic` | Boolean, mapped to a curve exponent via the new `ConfigNetworking.curveExponentFor(boolean)` | Read-only |
| `RingCurve.tierRatio` (new) | Pure ratio function, see Algorithm | Read-only |

The preview never mutates screen state and never sends network packets — it is a pure client-side visualization of whatever is currently typed, valid or not yet saved.

### Outputs

| Component | Fields Modified | Notes |
|-----------|-----------------|-------|
| `RingPreviewWidget` internal `NativeImage` / `DynamicTexture` | Re-written and re-uploaded whenever `updateParameters(...)` is called with a changed value | Visual only; no gameplay state is touched |

### New Types Required

- `RingPreviewWidget` (`client/screen`) — `AbstractWidget` subclass that owns the composited preview texture and draws it plus the three player markers.
- `PreviewParameters` (record, nested in or alongside `RingPreviewWidget`) — `(int ringCount, int maxRadiusBlocks, double curveExponent)`, passed to `updateParameters` instead of three raw args, mirroring the existing `ConfigStatePayload`/`ConfigUpdatePayload` record style already used in this mod.
- `RingPreviewMath` (`client/screen`) — plain, Minecraft-object-free static utility holding `tierForRatio` and `opacityForTier` (see Algorithm), kept separate from `RingPreviewWidget` specifically so it is unit-testable per `java-coding-standards.md`'s "extract pure logic out of anything that needs a live game instance" rule.

---

## Algorithm

### Step 1 — Unify widget widths (layout symmetry)

Replace the existing `FIELD_WIDTH` constant with `WIDGET_WIDTH = 200` and apply it to **every** left-column child, not just the `EditBox`es:

- Title and both field-label `StringWidget`s: construct with the explicit `(width, height, message, font)` constructor at `WIDGET_WIDTH`, and call `.alignCenter()` so text that's shorter than `WIDGET_WIDTH` doesn't look left-stuck inside a wider box.
- `_ringCountBox` / `_maxRadiusBox`: already constructed at a fixed width — just repoint `FIELD_WIDTH` → `WIDGET_WIDTH`.
- `_curveButton`: build via whichever `CycleButton.Builder` overload accepts an explicit pixel width in this project's mapped Minecraft version (confirm the exact method name against the local MC/mapping sources at implementation time — the intent is "same `WIDGET_WIDTH` as everything else," not a specific overload signature this spec can guarantee sight-unseen).
- Save / Cancel `Button`s: add `.width(WIDGET_WIDTH)` to both builders.

Wrap the existing single-column `GridLayout` (unchanged internally, just narrower relative to the new total screen width) and the new `RingPreviewWidget` in an outer `LinearLayout.horizontal().spacing(16)`, with `LayoutSettings.alignVertically(0.5f)` on both children so the shorter of the two (almost certainly the form column) is vertically centered against the taller preview panel rather than top-pinned. Center the combined outer layout on screen exactly as `init()` already centers the form today (`setX`/`setY` from `(width - layout.getWidth()) / 2`, etc.), just computed against the outer layout's combined size instead of the form's alone.

### Step 2 — Expose the curve math as a single source of truth

`RingCurve.radiusForTier` currently computes `round(maxRadiusChunks * (tier/ringCount)^curveExponent)` inline. Extract the continuous, unrounded fraction into:

```
tierRatio(tier, ringCount, curveExponent) = (tier / ringCount) ^ curveExponent
```

with the same `tier < 0` guard `radiusForTier` already has, and the same `tier == 0 → 0` short-circuit. `radiusForTier` becomes `(int) Math.round(maxRadiusChunks * tierRatio(tier, ringCount, curveExponent))` — behaviorally identical, verified by the existing `RingCurve` tests continuing to pass unmodified. This is what lets the preview derive ring boundaries from *exactly* the formula that determines real pregeneration coverage, rather than a second copy that could silently drift from it.

Promote `ConfigNetworking`'s curve-exponent mapping to a public, reusable form — either widen `LINEAR_CURVE_EXPONENT`/`QUADRATIC_CURVE_EXPONENT` to `public static final`, or add:

```
curveExponentFor(boolean quadratic) → QUADRATIC_CURVE_EXPONENT if quadratic else LINEAR_CURVE_EXPONENT
```

so the preview's `_quadratic` boolean converts to the exact exponent the server will actually apply on Save, instead of the screen re-hardcoding `1.0`/`2.0` a second time.

### Step 3 — Fixed preview "map" scale

The preview panel represents a **fixed** square region of the world, `PREVIEW_MAP_HALF_WIDTH_BLOCKS` blocks from center to edge in each direction (constant, not derived from the current `Maximum Radius` value) — this is what makes cranking `Maximum Radius` up or down visibly grow or shrink the rings on screen, rather than the preview silently rescaling itself to always look the same size. Three **fixed, hypothetical** player positions are placed within that region (not derived from any real players — the preview never reads actual world/player state):

| Player | World offset from map center (blocks) |
|--------|----------------------------------------|
| 1 | `(-12000, -9000)` |
| 2 | `(15000, -13500)` |
| 3 | `(0, 18000)` |

chosen so all three sit comfortably inside the canvas with room for rings to grow — and for players 1 and 2's rings to begin overlapping — before a typical `Maximum Radius` clips against the canvas edge. `pixelsPerBlock = PREVIEW_SIZE / (2 × PREVIEW_MAP_HALF_WIDTH_BLOCKS)`, `PREVIEW_SIZE = 128` (matches vanilla in-game map resolution, so the panel reads as "a Minecraft map" the way the request asked for, and lets the widget reuse vanilla's own player-marker texture — see Step 5).

### Step 4 — Per-pixel ring classification and opacity

For each of the `PREVIEW_SIZE × PREVIEW_SIZE` canvas pixels, converted to a world-block offset from map center via `1 / pixelsPerBlock`:

1. For each of the 3 fixed players, compute the pixel's block distance to that player, divide by `maxRadiusBlocks` (the screen's current, clamped `Maximum Radius` in blocks) to get a per-player ratio.
2. Take `ratio = min(ratio_1, ratio_2, ratio_3)` — a pixel is as revealed as its *nearest* hypothetical player's coverage, mirroring how real pregeneration coverage is the union of all tracked players' rings.
3. `tier = RingPreviewMath.tierForRatio(ratio, ringCount, curveExponent)`: the smallest `i` in `[1, ringCount]` such that `ratio <= RingCurve.tierRatio(i, ringCount, curveExponent)`; if no such `i` exists (`ratio >= 1`, i.e. beyond the outermost ring), return a `BEYOND_ALL_RINGS` sentinel (`ringCount + 1`).
4. `opacity = RingPreviewMath.opacityForTier(tier, ringCount)`:
   - `tier <= ringCount`: `opacity = 1.0 - (tier - 1.0) / ringCount` — ring 1 → `1.0` (fully revealed-map blend), ring `ringCount` → `1.0 / ringCount` (least, but still nonzero, revealed-map blend).
   - `tier == BEYOND_ALL_RINGS`: `opacity = 0.0` (fully the "fog"/unrevealed source image — no coverage at all, consistent with `RingCurve`'s own tier-0 meaning "no coverage yet").
5. Final pixel color = per-channel `lerp(fogImage[pixel], revealedImage[pixel], opacity)`, written into a single reused `NativeImage` and uploaded once via `DynamicTexture#upload` after the full loop (not per-pixel).

This produces a *stepped* (banded) look — a genuine ring per tier, matching "Ring 1... Ring 2... down to Ring `ringCount`" as distinct bands rather than one smooth gradient — and the quadratic curve visibly produces thin inner bands widening toward the edge, while linear produces evenly spaced bands, directly visualizing what "Growth Curve" controls.

### Step 5 — Player markers

After the composited background is drawn, draw a small marker at each of the 3 fixed pixel positions using vanilla's own map player-icon texture (`textures/map/decorations/player.png`), tinted three distinct colors from the OTH palette (e.g. Gold `#BAA565`, Sage `#7C8E7A`, Slate Blue `#465572` — pick three with enough contrast against both source images; do not introduce new hex values outside the palette). Markers are drawn every frame directly in `renderWidget()` (cheap, 3 tinted blits) — they are not baked into the composited texture, since they don't change unless the panel itself is rebuilt.

### Step 6 — Regeneration trigger and lifecycle

`RingPreviewWidget.updateParameters(PreviewParameters)` is called from the screen's existing `_ringCountBox`/`_maxRadiusBox` changed-listeners and the `_curveButton` value-changed callback (all three already exist for other purposes — this adds one more call at the end of each), plus once from `applyServerState` so a freshly arrived server snapshot updates the preview too. Regeneration re-walks all `PREVIEW_SIZE²` pixels (~16K, no allocation inside the loop — reuse one `NativeImage` field, one `int[]`-free per-pixel color computation) and is cheap enough to run synchronously on every keystroke without debouncing. Clamp `ringCount`/`maxRadiusBlocks` to their `ConfigNetworking` min/max bounds *before* the loop so a mid-typing invalid value (empty field, out-of-range number) can't produce a divide-by-zero or a pathological iteration count — an invalid/unparseable field simply leaves the preview showing its last valid state rather than erroring.

The widget allocates its `NativeImage`/`DynamicTexture` once in the screen's `init()` (or the widget's own constructor) and must release both (`NativeImage#close`, texture manager unregister) in the screen's `onClose()` — this screen can be opened and closed repeatedly in one client session (`ModMenu`, `/chunkyfriends gui`), and native/GPU-side image memory does not get reclaimed by the JVM garbage collector.

### Graceful degradation for missing source art

If either `ring_preview_revealed.png` or `ring_preview_fog.png` is missing or fails to load (e.g. mid-development, before final art is supplied), fall back to two solid-color `PREVIEW_SIZE × PREVIEW_SIZE` procedurally-filled placeholders (a light, "revealed" tone and a darker, "fog" tone, both from the OTH palette) rather than crashing the screen — log a single warning the first time this happens.

### Constants

| Constant | Value | Unit | Rationale |
|----------|-------|------|-----------|
| `WIDGET_WIDTH` | `200` | px | Matches the current `EditBox` width, which was already the widest existing element — everything else grows/shrinks to match it rather than shrinking the fields. |
| `PREVIEW_SIZE` | `128` | px | Matches vanilla Minecraft's in-game map resolution, both for visual authenticity and so the panel can reuse vanilla's own map/player-marker texture conventions. |
| `PREVIEW_DISPLAY_SCALE` | `2` | × | On-screen blit is `PREVIEW_SIZE × PREVIEW_DISPLAY_SCALE` (256×256) for readability; source stays at native 128×128 with nearest-neighbor scaling, consistent with Minecraft's own blocky map rendering. |
| `PREVIEW_MAP_HALF_WIDTH_BLOCKS` | `40000` | blocks | Fixed viewport half-width the 128px canvas represents edge-to-edge. Chosen so a mid-range `Maximum Radius` (~8,000–32,000 blocks, the mod's built-in default and the value shown in this spec's reference screenshot) reads as modest coverage with visible unrevealed space, while values approaching `MAX_RADIUS_CHUNKS × BLOCKS_PER_CHUNK` (1,600,000) simply fill and clip the canvas — itself an informative signal ("this radius covers far more than the preview can show"). |
| Player anchors | see Step 3 table | blocks from map center | Triangular spread giving two markers (1 and 2) room to show ring overlap at larger radii, while marker 3 stays isolated to show the "no overlap yet" case at small radii. |
| Opacity falloff | `1 - (tier-1)/ringCount` | ratio | Linear falloff from `1.0` (ring 1) to `1/ringCount` (ring `ringCount`), `0` beyond — see Step 4, rationale 4. |

---

## Implementation Requirements

Files the implementing AI must create or modify. All paths relative to the package root.

### Create

- `src/client/java/com/onthehill/chunkyfriends/client/screen/RingPreviewWidget.java`
  - Extends `AbstractWidget`
  - Public members fully Javadoc'd per `java-coding-standards.md`
  - Constructor allocates the `NativeImage`/`DynamicTexture` once; `updateParameters(PreviewParameters)` regenerates in place; a `close()`/`removed()`-style method releases both
  - `renderWidget(GuiGraphics, int, int, float)` draws the cached composited texture, then the 3 tinted player markers — no allocation, no pixel math, in this method
  - Overrides narration (`updateWidgetNarration` or the project's equivalent accessibility hook per `minecraft-gui-standards.md`) with a static, translatable description of what the panel shows — it has no interactive behavior to narrate per-widget, but must still announce its presence and purpose
  - Allman braces, 4-space indent, `_camelCase` private fields, no `snake_case`, no acronyms

- `src/client/java/com/onthehill/chunkyfriends/client/screen/RingPreviewMath.java`
  - `static int tierForRatio(double ratio, int ringCount, double curveExponent)`
  - `static double opacityForTier(int tier, int ringCount)`
  - No Minecraft object dependencies — must be constructible and callable from a plain JUnit test with no running game instance

### Modify

- `src/client/java/com/onthehill/chunkyfriends/client/screen/ChunkyFriendsConfigScreen.java` — restructure per Steps 1 and 6: rename `FIELD_WIDTH` → `WIDGET_WIDTH` and apply it to every left-column child; wrap the form and a new `RingPreviewWidget` field in an outer horizontal `LinearLayout`; wire `updateParameters` calls into the existing field/button change callbacks and into `applyServerState`; release the preview widget's native resources from `onClose()`.
- `src/main/java/com/onthehill/chunkyfriends/scheduler/RingCurve.java` — extract `tierRatio` per Step 2; `radiusForTier` calls it internally; no change to `radiusForTier`'s public behavior or Javadoc contract.
- `src/main/java/com/onthehill/chunkyfriends/network/ConfigNetworking.java` — expose the curve-exponent mapping per Step 2 (widen the two constants or add `curveExponentFor`).
- `src/main/resources/assets/chunky-friends/lang/en_us.json` — add `gui.chunky-friends.config.preview`: a short translatable description of the panel, used by the narration hook above.
- `src/main/resources/assets/chunky-friends/textures/gui/ring_preview_revealed.png` (new binary asset, 128×128, user-supplied)
- `src/main/resources/assets/chunky-friends/textures/gui/ring_preview_fog.png` (new binary asset, same dimensions as the above, user-supplied — the two images the user offered during spec authoring: one fully-revealed static map screenshot, one fully-fogged/undiscovered version of the same view)

---

## Test Requirements

Per `java-coding-standards.md`: JUnit 5, AAA pattern with inline comments, `methodName_stateUnderTest_expectedBehavior` naming, isolated tests, float/double comparisons with a `0.001` delta.

### `RingCurve.tierRatio` (new coverage alongside existing `RingCurve` tests)

#### Happy Path
- **`tierRatio_midTierLinearCurve_returnsExpectedRatio`** — e.g. tier 5 of 10, exponent 1.0 → 0.5.

#### Boundary / Limit Tests
- **`tierRatio_tierZero_returnsZero`**
- **`tierRatio_tierEqualsRingCount_returnsOne`** — for both exponent 1.0 and 2.0.

#### Negative / Toxicity Test
- **`tierRatio_negativeTier_throwsIllegalArgumentException`** — mirrors `radiusForTier`'s existing guard.

### `RingPreviewMath`

#### Happy Path
- **`tierForRatio_midRangeRatioQuadraticCurve_returnsExpectedTier`**

#### Boundary / Limit Tests
- **`tierForRatio_ratioAtZero_returnsTierOne`**
- **`tierForRatio_ratioAtOrBeyondOne_returnsBeyondAllRingsSentinel`**

#### Negative / Toxicity Test
- **`opacityForTier_ringCountOfOne_doesNotThrowAndReturnsBoundedValue`** — guards the `1/ringCount` term against a degenerate `ringCount`; confirms the result stays within `[0, 1]` rather than dividing by zero or returning a negative/NaN value.

---

## Acceptance Criteria

The spec is complete when all of the following are true:

- [ ] The screen's title, both field labels, both `EditBox` fields, the `CycleButton`, and the Save/Cancel buttons all render at the same width (`WIDGET_WIDTH`), at GUI Scale Auto and at the smallest supported scale, with no element visibly overhanging another.
- [ ] A ring-coverage preview panel renders to the right of the form, sized `PREVIEW_SIZE × PREVIEW_DISPLAY_SCALE` on screen, both vertically centered against the form column.
- [ ] The preview shows exactly 3 fixed player markers at the positions in Step 3, each tinted a distinct OTH-palette color.
- [ ] Each marker's coverage shows `Ring Tier Count` concentric bands, reaching a radius on the fixed map scale that visibly grows or shrinks as `Maximum Radius` changes.
- [ ] Switching `Growth Curve` between Linear and Quadratic visibly changes the ring band spacing without needing to press Save.
- [ ] Opacity strictly decreases from ring 1 (fully the revealed-map blend) to ring `ringCount` (least, but nonzero), and is fully the fog-map blend beyond ring `ringCount`.
- [ ] The preview updates live as any of the three governing fields change, with no perceptible input lag while typing.
- [ ] Missing source textures degrade to solid-color placeholders instead of crashing the screen.
- [ ] `RingPreviewWidget`'s native image/texture is released in `onClose()` — repeatedly opening and closing the screen does not leak native memory.
- [ ] `RingCurve`'s existing tests still pass unmodified after the `tierRatio` extraction.
- [ ] All new tests listed above pass.
- [ ] No XML/Javadoc violations (all public members documented); no `snake_case` identifiers outside the two new asset paths (which correctly use it, per the texture-naming convention); Allman braces; 4-space indentation.

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

*(pending — anticipated candidate: a retroactive spec 005 for the base client config GUI itself, per the existing note in `ai-specs/index.md`'s Future Specs table, since this spec builds on that undocumented work without itself documenting it)*
