# Codex Pocket: notes for agents

## Android build and tests

- JDK 21, run from `android/`. `.local/run-gradle.sh <log-name> <gradle args>` sets `JAVA_HOME` and writes `.local/<log-name>.log`.
- Compile: `:app:compileDebugKotlin`. Unit tests: `:app:testDebugUnitTest` (about 1500 tests, 4 to 5 minutes). Kit only: `--tests "dev.codexpocket.app.ui.kit.*"`.
- Two tests fail on the baseline and are not regressions: `V02UiTest.conversationMenuKeepsUnavailableActionsDiscoverableWithoutFakeButtons`, `V05TimelineTest.expandedProcessIncludesEveryReasoningSegmentExactlyOnce`.
- Compose tests with a frozen clock must step in 16 ms frames with `waitForIdle()` between them; one large `advanceTimeBy` does not let a state change reach its effects.
- Shell commands longer than about 8 KB are truncated: write long scripts to a file first.

## Android UI rules

- No Material. `androidx.compose.material*` is a test fixture only; `KitNoMaterialTest` fails if app code imports it. Build screens from `dev.codexpocket.app.ui.kit` (import `dev.codexpocket.app.ui.kit.*`).
- Colours and type come from `Pocket.colors` and `Pocket.type`; the foreground of a container is `Pocket.ink` (set with `ProvidePInk`). No hard-coded colours per screen.
- Every animation takes its spec from `PocketMotion` or `PocketSprings` and ends with `.orSnap(rememberPocketMotion())`, so it stops when system animations, battery saver or high contrast say so.
- Animate layers (`graphicsLayer`), not layout. Wait with `pocketPause`, not `delay`, so pauses follow the frame clock.

## The kit (`android/app/src/main/java/dev/codexpocket/app/ui/kit`)

| Need | Use |
|---|---|
| Text, icon | `PText`, `PIcon` |
| Button, text action, icon button, pill, badge, row | `PButton`, `PTextAction`, `PIconButton`, `PPill`, `PBadge`, `PRow` |
| Input, switch, checkbox, segmented control | `PTextField`, `PSwitch` (tap or drag), `PCheck`, `PSegmented` (tap or drag) |
| Surface, card, divider | `PSurface`, `PCard`, `PDivider` |
| Page frame with glass chrome, plain page with a top bar | `PFrame` + `PGlassBar` / `PGlassIconButton`, `PScaffold` |
| Sheet, dialog, anchored menu, long-press hint | `PSheet`, `PDialog`, `PMenu` + `rememberPAnchor()` / `Modifier.pocketAnchor`, `PTooltip` |
| Progress | `PSpinner`, `PRing`, `PLinearProgress`, `PDots`, `Modifier.pocketSkeleton()` |
| Changing number or text | `PRollingNumber`, `PSwap`, `PSwapText` |

Motion building blocks:

- Press: any `clickable` already gets Pocket's press (scale, light under the finger, looser release) through the theme. For kit-style controls use `Modifier.pocketPressable` / `pocketSelectable` / `pocketToggleable`, with `pocketPressScale` ahead of the clip to move the whole control.
- Pages: `PStage(page, depth = { ... }, back = pageBackLeadsTo, onBack = ...)` pushes, pops, turns round mid-way and follows the system back gesture. `content` must be able to draw a page that has just been left. The root (`PocketRoot`) and the assistant already use it.
- A page that opens out of a row or card: put `Modifier.pocketMorphSource(key, radius)` on the element and give the stage `source = { page -> key }` with the same key. The page then grows from the element and folds back into it (wherever it has moved to, and under the back gesture); with no such element on screen it slides as usual. Keys in use: `"chat:<encoded key>"`, `"detail:<item key>"`, `"assistant"` at the root, and the page name itself (`"Task:<id>"`, `"Artifact:<id>"`, ...) inside the assistant.
- Sliding selections (segmented controls, tab bars): `val slide = rememberPSlide(count, selected) { onSelect(it) }`, `Modifier.pocketSlide(slide)` on the element as wide as all the items, and draw the thumb from `slide.start.value` to `slide.end.value` (in items, not pixels). `slide.shown` is the item to draw as chosen while the thumb is carried, `slide.lift` is 1 while it is held. `PSegmented`, `UsageSegmented`, `PocketSegmented`, the settings tabs and the home bar all use it; do not hand-roll another.
- Lists: bounce at the ends is global (`ProvidePocketOverscroll` in `PocketTheme`). First-screen stagger: `rememberPocketEntrance()` + `Modifier.pocketEntrance(entrance, index)`. New items: `rememberPocketArrivals(scope)` + `Modifier.pocketArrival(arrivals, key, eligible)`.
- Icons: an icon beside an action answers the tap. `PIconButton`, `PGlassIconButton`, `PRow`, `PButton(leading =)` and `PTextAction(leading =)` do it already; elsewhere use `val taps = rememberPTaps()`, `onClick = taps.then(onClick)` and `PTapIcon(icon, taps.count, ...)` in place of `PIcon`. `PTapIcon` plays the icon's own glyph from `IconGlyphs.byName` (keyed by the name in `PocketIcons`) and falls back to `pocketIconMotion(icon)` (nudge, turn, spin, wiggle, bounce); at rest it is the static icon.
- A new glyph in `IconGlyphs` is drawn from the static icon's own path strings so it rests in the icon's pose at progress 0 and 1; `KitIconGlyphTest` fails if it does not, or if it does not move. Two-pose and looping glyphs are in `MotionGlyphs` with `PToggleGlyph`, `PPulseGlyph`, `PLoopGlyph`, `PEntranceGlyph`.
- Effects: `Modifier.pocketBounce(trigger)`, `pocketJelly { velocity }`, `pocketResize()`, `pocketBlur { radius }`, `rubberBand(overshoot, dimension)`.
- Haptics: pass a `PocketHaptic` to the control or call `rememberPocketHaptics().perform(...)`.

Overlays (`PocketOverlay.kt`) need a `PocketOverlayHost` above them; tests wrap content in `PocketBackdrop { }`.
