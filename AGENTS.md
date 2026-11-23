# Codex Pocket: notes for agents

## Android build and tests

- JDK 21, run from `android/`. `.local/run-gradle.sh <log-name> <gradle args>` sets `JAVA_HOME` and writes `.local/<log-name>.log`.
- Compile: `:app:compileDebugKotlin`. Unit tests: `:app:testDebugUnitTest` (about 1500 tests, 4 to 5 minutes). Kit only: `--tests "dev.codexpocket.app.ui.kit.*"`.
- Two tests fail on the baseline and are not regressions: `V02UiTest.conversationMenuKeepsUnavailableActionsDiscoverableWithoutFakeButtons`, `V05TimelineTest.expandedProcessIncludesEveryReasoningSegmentExactlyOnce`.
- Compose tests with a frozen clock must step in 16 ms frames with `waitForIdle()` between them; one large `advanceTimeBy` does not let a state change reach its effects.
- Shell commands longer than about 8 KB are truncated: write long scripts to a file first.
- A test that watches a loop (`rememberGlyphLoop`, `PDots`, any infinite transition) must set `compose.mainClock.autoAdvance = false` before `setContent`: a loop that starts while the test clock runs by itself is cancelled by the test framework and never moves.

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
| Input, switch, checkbox, segmented control | `PTextField`, `PSwitch` (tap or drag), `PCheck`, `PSegmented` (tap or drag; optional `icons`, `itemHeight`, `haptic`) |
| Surface, card, titled group of rows, divider | `PSurface`, `PCard`, `PSection` (the outlined group every settings-like page uses), `PDivider` |
| Page frame with glass chrome, plain page with a top bar | `PFrame` + `PGlassBar` / `PGlassIconButton`, `PScaffold` |
| Sheet, dialog, anchored menu, long-press hint | `PSheet`, `PDialog`, `PMenu` + `rememberPAnchor()` / `Modifier.pocketAnchor`, `PTooltip` |
| Progress | `PSpinner`, `PRing`, `PLinearProgress`, `PDots`, `Modifier.pocketSkeleton()`, `PBrandLoader` (Pocket's own mark at work) |
| Chat: someone is writing, a message on its way out, a conversation being read | `PTypingBubble`, `PSendingBubble`, `PChatSkeleton`; `Modifier.pocketBubblePop()` for anything that should pop out of a bubble's corner |
| Pages side by side with dots (onboarding, galleries) | `rememberPPager(count)` + `PPager(state) { page, offset -> }` + `PPageDots(state)` |
| The app's mark | `PBrandMark` (full colour, assembles with `progress`), `BrandGlyphs.Mark` / `.Working` / `.Entrance` (line glyphs) |
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
- A sent message leaves its field (iMessage-style): `val flight = rememberPSendFlight(scope)`, `Modifier.pocketSendSource(flight)` on the element holding the typed text (or read `LocalPSendFlight`), `flight.arm(keysAlreadyShown)` as the message is sent, then for each new item `val from = remember(key) { flight.take(key, eligible) }` and `Modifier.pocketSendLanding(from, flight)` on its bubble; `pocketSendTrail(from)` on what accompanies it. One arming carries one message, and gives up after 8 s. `ChatScreen` + `Composer` + `Transcript` are wired this way.
- Tools at work: `ToolGlyphs.working(icon)` is the loop an activity icon plays while its tool runs (`PLoopGlyph(glyph, running, millis = ToolGlyphs.period(icon))`); it rests on the static icon. `NativeActivityIcon` uses it for every tool line. A new loop goes in `ToolGlyphs.byName` under the icon's name; `KitIconGlyphTest` checks it starts and ends on the icon and moves in between.
- Pager parallax: `PPager` gives each page `offset()` (-1 one page left, 0 centred), read while drawing. Move parts of a page at their own rate in `graphicsLayer`, never by re-laying them out.

Overlays (`PocketOverlay.kt`) need a `PocketOverlayHost` above them; tests wrap content in `PocketBackdrop { }`.

## Screens built on the kit in this pass

- Tutorial: `PocketTutorial.kt` (chapters, controller, host) and `PocketTutorialScenes.kt` (one drawn, looping scene per chapter on a 280x200 grid via `TourCanvas`). A chapter is one `TourChapter` entry in `PocketTourChapters` plus one `TourScene`; `PocketTutorialTest` fails if a scene has no chapter or a topic goes unmentioned. It opens once per install (`PocketTutorialController.Version`; raise it to show it again), never by itself under instrumentation, and from Settings > 应用设置 > 使用教程 through `LocalPocketTutorial`. Scenes must end each loop on the finished picture (`kept(t, start, end)`), because that is the still shown with motion off.
- Message editing happens in the message's own bubble: `MessageEditBubble` + `LocalMessageEdit` (`MessageEditHost`). There is no edit dock or dialog; do not add one. If the edited turn has been withdrawn the editor is the `message-edit` item at the tail of the transcript.
- Assistant entry: `AssistantPocketButton` (60dp, morph source `"assistant"`) and `AssistantFace` (blinks while `lively`, smiles while `held`, hops on `cheer`). `AssistantGlyphs.face(idle, hop, held)` is the drawing; at rest (1, 1, 0) it is `AssistantIcons.Mark`.
- Launcher icon: `res/drawable/ic_pocket_{background,foreground,monochrome,notification}.xml` are drawn from the outlines in `kit/PocketBrand.kt`; change both together. `KitBrandTest` compares the in-app mark with the launcher layers and writes PNGs to `android/app/build/reports/kit/brand-*.png`.
