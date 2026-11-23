@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package dev.codexpocket.app.ui

import androidx.compose.animation.core.FastOutLinearInEasing

import androidx.compose.animation.core.snap

import androidx.compose.animation.core.spring

import androidx.compose.ui.graphics.TransformOrigin

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.Backdrop
import dev.codexpocket.app.ui.kit.PBackHandler
import dev.codexpocket.app.ui.kit.PSurface
import dev.codexpocket.app.ui.kit.Pocket
import dev.codexpocket.app.ui.kit.PocketHaptic
import dev.codexpocket.app.ui.kit.PocketMotion
import dev.codexpocket.app.ui.kit.orSnap
import dev.codexpocket.app.ui.kit.rubberBand
import androidx.compose.ui.input.pointer.util.VelocityTracker
import dev.codexpocket.app.ui.kit.rememberPocketHaptics
import dev.codexpocket.app.ui.kit.rememberPocketMotion
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class OverlayEntry(val content: @Composable (Boolean) -> Unit) {
    var present by mutableStateOf(true)
    /** Sheets and pages cross the screen to leave; menus and dialogs fade where they are. */
    var travels by mutableStateOf(false)
}
private class OverlayRegistry {
    val entries = mutableStateListOf<OverlayEntry>()
}
private val LocalOverlayRegistry = staticCompositionLocalOf<OverlayRegistry?> { null }
private val LocalModalBackdrop = staticCompositionLocalOf<Backdrop?> { null }
private val LocalOverlayProgress = staticCompositionLocalOf { 1f }
// Read at callback time: registry changes can precede the covered editor's recomposition.
internal val LocalPocketInputActive = staticCompositionLocalOf<() -> Boolean> { { true } }
// False once the owner has dropped the layer and only its exit is still playing.
private val LocalOverlayPresent = staticCompositionLocalOf<() -> Boolean> { { true } }

/** One window, two sibling layers. Only the page is recorded, never scrims or modal content. */
@Composable
internal fun PocketOverlayHost(modifier: Modifier = Modifier.fillMaxSize(), content: @Composable () -> Unit) {
    val registry = remember { OverlayRegistry() }
    val preferences = rememberPocketGlassPreferences()
    val page = rememberLayerBackdrop()
    val background = Pocket.colors.bg
    // The default backdrop is a constant color, not page content. Recording a render layer
    // here lets nested glass consumers re-enter that layer when Android rebuilds display lists.
    // Drawing the color directly keeps this leaf acyclic; page/transcript glass still samples
    // its real content layers.
    val base = rememberCanvasBackdrop { drawRect(background) }
    val blocked = registry.entries.isNotEmpty()
    val pageFocus = remember { FocusRequester() }
    var wasBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(blocked) {
        if (wasBlocked && !blocked && !pageFocus.restoreFocusedChild()) pageFocus.requestFocus()
        wasBlocked = blocked
    }
    CompositionLocalProvider(LocalOverlayRegistry provides registry, LocalModalBackdrop provides page, LocalPocketGlassPreferences provides preferences) {
        Box(modifier.testTag("pocket-overlay-host")) {
            Box(Modifier.matchParentSize().layerBackdrop(page)
                .focusRequester(pageFocus)
                .focusProperties { onEnter = { if (registry.entries.isNotEmpty()) cancelFocusChange() } }
                .focusRestorer().focusGroup()
                .then(if (blocked) Modifier.clearAndSetSemantics {} else Modifier)) {
                // Default controls sample this base; Home/Chat keep their local content overrides.
                Box(Modifier.matchParentSize().background(background))
                CompositionLocalProvider(LocalPocketBackdrop provides base,
                    LocalPocketInputActive provides { registry.entries.isEmpty() }) { content() }
            }
            registry.entries.toList().forEachIndexed { index, entry ->
                key(entry) {
                    val state = remember { MutableTransitionState(false) }
                    state.targetState = entry.present
                    val transition = updateTransition(state, label = "modal-presence")
                    val progress by transition.animateFloat(
                        // Enter on a softly damped spring (iOS sheet feel, ~0.35s to settle); exit fast and linear-out.
                        transitionSpec = {
                            when {
                                pocketMotionMillis(1) == 0 -> snap()
                                targetState -> if (entry.travels) PocketMotion.page() else PocketMotion.morph()
                                // Leaving is quicker than arriving, and accelerates away rather than easing to a stop.
                                else -> tween(if (entry.travels) 230 else 140, easing = FastOutLinearInEasing)
                            }
                        },
                        label = "modal-progress",
                    ) { if (it) 1f else 0f }
                    LaunchedEffect(state.isIdle, state.currentState, entry.present) {
                        if (state.isIdle && !state.currentState && !entry.present) registry.entries.remove(entry)
                    }
                    CompositionLocalProvider(LocalOverlayProgress provides progress, LocalOverlayPresent provides { entry.present },
                        LocalPocketInputActive provides { entry.present && registry.entries.lastOrNull() === entry }) {
                        entry.content(index == registry.entries.lastIndex)
                    }
                }
            }
        }
    }
}

/** Register in composition order and retain identity until disposed, including nested sheets. */
@Composable
private fun PocketOverlay(travels: Boolean = false, content: @Composable (Boolean) -> Unit) {
    val registry = LocalOverlayRegistry.current
    if (registry == null) {
        // Standalone previews and isolated tests get the same window architecture.
        PocketOverlayHost { PocketOverlay(travels, content) }
        return
    }
    val latest by rememberUpdatedState(content)
    val entry = remember { OverlayEntry { top -> latest(top) }.also { it.travels = travels } }
    SideEffect { entry.travels = travels }
    DisposableEffect(registry, entry) {
        registry.entries.add(entry)
        onDispose { entry.present = false }
    }
}

@Composable
internal fun PocketModal(
    title: String,
    onDismiss: () -> Unit,
    sheet: Boolean,
    screen: Boolean = false,
    anchor: Rect? = null,
    anchored: Boolean = false,
    menuWidth: Float = 300f,
    alignStart: Boolean = false,
    below: Boolean = false,
    maxWidthDp: Float = 360f,
    maxHeightFraction: Float = .55f,
    content: @Composable ColumnScope.(Modifier) -> Unit,
) {
    PocketOverlay(travels = screen || (sheet && !anchored)) { top ->
        val focus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        val currentDismiss by rememberUpdatedState(onDismiss)
        val progress = LocalOverlayProgress.current
        val inputActive by rememberUpdatedState(LocalPocketInputActive.current)
        val interactive = inputActive()
        val haptics by rememberUpdatedState(rememberPocketHaptics())
        // A sheet let go short of dismissal springs back to rest; with motion off it is simply there.
        val settle by rememberUpdatedState(PocketMotion.resize<Float>().orSnap(rememberPocketMotion()))
        LaunchedEffect(top) {
            if (top && !focus.restoreFocusedChild()) focus.requestFocus()
        }
        val present by rememberUpdatedState(LocalOverlayPresent.current)
        val motion by rememberUpdatedState(rememberPocketMotion())
        // How far a back gesture has pulled this layer toward leaving: 0 at rest, 1 at the gesture's end.
        val pull = remember { Animatable(0f) }
        // Keep owning Back and pointer input until the exit animation removes this entry.
        PBackHandler(top,
            onProgress = { event -> if (motion && inputActive()) scope.launch { pull.snapTo(event.progress.coerceIn(0f, 1f)) } },
            onCancel = { scope.launch { pull.animateTo(0f, settle) } }) {
            if (inputActive()) currentDismiss()
            // A layer that leaves carries on from where the gesture held it; one that stays returns to rest.
            if (pull.value != 0f) scope.launch { repeat(2) { withFrameNanos { } }; if (present()) pull.animateTo(0f, settle) }
        }
        BoxWithConstraints(Modifier.fillMaxSize()
            .then(if (!interactive) Modifier.clearAndSetSemantics {} else Modifier)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!inputActive()) event.changes.forEach { it.consume() }
                    }
                }
            }
            .focusProperties { onEnter = { if (!inputActive()) cancelFocusChange() } }
            .onPreviewKeyEvent {
                if (!inputActive()) true
                else if (!top || it.type != KeyEventType.KeyDown) false
                else when (it.key) {
                    Key.Escape -> { currentDismiss(); true }
                    Key.Tab -> {
                        val direction = if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
                        if (!focusManager.moveFocus(direction)) {
                            // Search from the appropriate end, rather than repeatedly requesting
                            // a focusable container. Covered groups reject entry; scrims cannot focus.
                            focusManager.clearFocus(force = true)
                            focusManager.moveFocus(direction)
                        }
                        true
                    }
                    else -> false
                }
            }) {
            // Scrim and hit interception remain entirely outside the capture layer.
            Box(Modifier.matchParentSize().drawBehind {
                    val shown = progress.coerceIn(0f, 1f)
                    drawRect(Color.Black, alpha = if (screen) .14f * shown * (1f - pull.value.coerceIn(0f, 1f)) else .18f * shown)
                }
                .focusProperties { canFocus = false }
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = top) {
                    if (inputActive()) currentDismiss()
                }
                .clearAndSetSemantics {})
            // Standalone destinations own an opaque page; only small menus use sampled glass.
            // A back gesture thins that page so the one underneath shows where this one has moved aside.
            val pageColor = Pocket.colors.bg
            BoxWithConstraints(Modifier.fillMaxSize()
                // The page colour travels with the page, so what it covers stays visible until it arrives.
                .then(if (screen) Modifier.drawBehind {
                    val aside = (1f - progress.coerceIn(0f, 1f)) * size.width + pull.value.coerceIn(0f, 1f) * size.width * PageHeldTravel
                    drawRect(pageColor, topLeft = Offset(aside, 0f))
                } else Modifier)
                .safeDrawingPadding().imePadding().padding(if (screen) 0.dp else if (sheet) 8.dp else if (maxWidthDp > 360f) 16.dp else 12.dp),
                // Sheets rise from the bottom edge like iOS action sheets; dialogs stay centered.
                contentAlignment = if (sheet && !screen && !anchored) Alignment.BottomCenter else Alignment.Center) {
                val handle = if (sheet && !screen && !anchored && interactive) Modifier.pointerInput(Unit) {
                    var distance = 0f
                    val velocity = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { distance = 0f; velocity.resetTracking() },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            velocity.addPosition(change.uptimeMillis, change.position)
                            val armed = distance > 72.dp.toPx()
                            distance += amount
                            // The finger feels the point past which letting go dismisses, and the way back.
                            if (distance > 72.dp.toPx() != armed) haptics.perform(if (armed) PocketHaptic.Tick else PocketHaptic.Threshold)
                            // Downward it follows the finger; upward it has nowhere to go and only gives a little.
                            val shown = if (distance >= 0f) distance else rubberBand(distance, 120.dp.toPx())
                            scope.launch { drag.snapTo(shown) }
                        },
                        onDragEnd = {
                            // A flick down dismisses however short it was; a slow drag has to pass the threshold.
                            val flung = velocity.calculateVelocity().y > 900.dp.toPx() && distance > 8.dp.toPx()
                            if ((distance > 72.dp.toPx() || flung) && inputActive()) currentDismiss()
                            else scope.launch { drag.animateTo(0f, PocketMotion.rebound<Float>().orSnap(motion)) }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(0f, settle) } },
                    )
                } else Modifier
                val panelModifier = Modifier
                        .then(if (screen) Modifier.fillMaxSize() else Modifier.widthIn(max = maxWidthDp.dp).fillMaxWidth())
                        .heightIn(max = if (screen || anchored) maxHeight else maxHeight * maxHeightFraction.coerceIn(.1f, 1f)).graphicsLayer {
                            // A back gesture previews the way out: sheets sink, pages start toward the trailing edge, the rest recede.
                            val leaving = pull.value.coerceIn(0f, 1f)
                            val bottomSheet = sheet && !screen && !anchored
                            // A sheet rises from below the screen by its whole height and leaves the same way; it never fades in place.
                            translationY = drag.value + when {
                                screen -> 0f
                                bottomSheet -> (1f - progress) * (size.height + 24.dp.toPx()) + leaving * 28.dp.toPx()
                                else -> (1f - progress) * 10.dp.toPx()
                            }
                            // Pages push in across the whole width from the trailing edge, like UINavigationController.
                            translationX = if (screen) (1f - progress) * size.width + leaving * size.width * PageHeldTravel else 0f
                            alpha = if (screen || bottomSheet) 1f else (progress * 1.6f).coerceIn(0f, 1f)
                            // Popovers grow out of the control that opened them, like an iOS context menu.
                            val from = if (anchored) .62f else .92f
                            val recede = if (sheet) 1f else 1f - .05f * leaving
                            scaleX = if (screen) 1f else (from + progress * (1f - from)) * recede
                            scaleY = if (screen) 1f else (from + progress * (1f - from)) * recede
                            if (anchored) transformOrigin = TransformOrigin(if (alignStart) 0f else 1f, if (below) 0f else 1f)
                        }
                        // Own empty panel space without adding a click action or consuming child gestures.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                        .testTag("pocket-modal-surface")
                val body: @Composable () -> Unit = {
                    Column((if (screen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).testTag("pocket-modal")
                        .semantics {
                            paneTitle = title; isTraversalGroup = true
                            if (!screen) dialog()
                            dismiss { if (inputActive()) { currentDismiss(); true } else false }
                        }
                        .focusRequester(focus)
                        .focusRestorer()
                        .focusGroup()) { content(handle) }
                }
                val panel: @Composable () -> Unit = {
                    if (screen) PSurface(panelModifier, color = Pocket.colors.bg) { body() }
                    else PocketGlassSurface(panelModifier, shape = RoundedCornerShape(if (anchored) 22.dp else if (sheet) 28.dp else 24.dp),
                        backdrop = LocalModalBackdrop.current, dense = true, content = body)
                }
                if (anchored) AnchoredPanelLayout(anchor, menuWidth, alignStart, below, panel) else panel()
            }
        }
    }
}

/** How far across the screen a back gesture can drag a page before it is let go. */
private const val PageHeldTravel = .86f

/** Measure the menu before placing it so the first visible frame is already above its anchor. */
@Composable
private fun AnchoredPanelLayout(anchor: Rect?, widthDp: Float, alignStart: Boolean, below: Boolean, content: @Composable () -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(content = content, modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) { measurables, constraints ->
        val viewport = Rect(origin.x, origin.y, origin.x + constraints.maxWidth, origin.y + constraints.maxHeight)
        val bounds = modelMenuBounds(viewport, anchor, density, widthDp, alignStart, below)
        val panel = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0,
            maxWidth = bounds.width.roundToInt(), maxHeight = bounds.height.roundToInt()))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = if (alignStart) bounds.left - origin.x else bounds.right - origin.x - panel.width
            val y = if (below) bounds.top - origin.y else bounds.bottom - origin.y - panel.height
            panel.place(x.roundToInt(), y.roundToInt())
        }
    }
}
