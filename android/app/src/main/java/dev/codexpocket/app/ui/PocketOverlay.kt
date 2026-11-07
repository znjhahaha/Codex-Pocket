@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package dev.codexpocket.app.ui

import androidx.compose.animation.core.FastOutLinearInEasing

import androidx.compose.animation.core.snap

import androidx.compose.animation.core.spring

import androidx.compose.ui.graphics.TransformOrigin

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class OverlayEntry(val content: @Composable (Boolean) -> Unit) {
    var present by mutableStateOf(true)
}
private class OverlayRegistry {
    val entries = mutableStateListOf<OverlayEntry>()
}
private val LocalOverlayRegistry = staticCompositionLocalOf<OverlayRegistry?> { null }
private val LocalModalBackdrop = staticCompositionLocalOf<Backdrop?> { null }
private val LocalOverlayProgress = staticCompositionLocalOf { 1f }
// Read at callback time: registry changes can precede the covered editor's recomposition.
internal val LocalPocketInputActive = staticCompositionLocalOf<() -> Boolean> { { true } }

/** One window, two sibling layers. Only the page is recorded, never scrims or modal content. */
@Composable
internal fun PocketOverlayHost(modifier: Modifier = Modifier.fillMaxSize(), content: @Composable () -> Unit) {
    val registry = remember { OverlayRegistry() }
    val preferences = rememberPocketGlassPreferences()
    val page = rememberLayerBackdrop()
    val base = rememberLayerBackdrop()
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
                Box(Modifier.matchParentSize().layerBackdrop(base).background(MaterialTheme.colorScheme.background))
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
                                targetState -> spring(dampingRatio = .86f, stiffness = 520f)
                                else -> tween(140, easing = FastOutLinearInEasing)
                            }
                        },
                        label = "modal-progress",
                    ) { if (it) 1f else 0f }
                    LaunchedEffect(state.isIdle, state.currentState, entry.present) {
                        if (state.isIdle && !state.currentState && !entry.present) registry.entries.remove(entry)
                    }
                    CompositionLocalProvider(LocalOverlayProgress provides progress,
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
private fun PocketOverlay(content: @Composable (Boolean) -> Unit) {
    val registry = LocalOverlayRegistry.current
    if (registry == null) {
        // Standalone previews and isolated tests get the same window architecture.
        PocketOverlayHost { PocketOverlay(content) }
        return
    }
    val latest by rememberUpdatedState(content)
    val entry = remember { OverlayEntry { top -> latest(top) } }
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
    PocketOverlay { top ->
        val focus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        val currentDismiss by rememberUpdatedState(onDismiss)
        val progress = LocalOverlayProgress.current
        val inputActive by rememberUpdatedState(LocalPocketInputActive.current)
        val interactive = inputActive()
        LaunchedEffect(top) {
            if (top && !focus.restoreFocusedChild()) focus.requestFocus()
        }
        // Keep owning Back and pointer input until the exit animation removes this entry.
        BackHandler(top) { if (inputActive()) currentDismiss() }
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
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (screen) 0f else .18f * progress.coerceIn(0f, 1f)))
                .focusProperties { canFocus = false }
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = top) {
                    if (inputActive()) currentDismiss()
                }
                .clearAndSetSemantics {})
            // Standalone destinations own an opaque page; only small menus use sampled glass.
            BoxWithConstraints(Modifier.fillMaxSize()
                .then(if (screen) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)
                .safeDrawingPadding().imePadding().padding(if (screen) 0.dp else if (sheet) 8.dp else if (maxWidthDp > 360f) 16.dp else 12.dp),
                // Sheets rise from the bottom edge like iOS action sheets; dialogs stay centered.
                contentAlignment = if (sheet && !screen && !anchored) Alignment.BottomCenter else Alignment.Center) {
                val handle = if (sheet && !screen && !anchored && interactive) Modifier.pointerInput(Unit) {
                    var distance = 0f
                    detectVerticalDragGestures(
                        onDragStart = { distance = 0f },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            distance = (distance + amount).coerceAtLeast(0f)
                            scope.launch { drag.snapTo(distance) }
                        },
                        onDragEnd = {
                            if (distance > 72.dp.toPx() && inputActive()) currentDismiss()
                            else scope.launch { drag.animateTo(0f, tween(pocketMotionMillis(160))) }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(0f, tween(pocketMotionMillis(160))) } },
                    )
                } else Modifier
                val panelModifier = Modifier
                        .then(if (screen) Modifier.fillMaxSize() else Modifier.widthIn(max = maxWidthDp.dp).fillMaxWidth())
                        .heightIn(max = if (screen || anchored) maxHeight else maxHeight * maxHeightFraction.coerceIn(.1f, 1f)).graphicsLayer {
                            translationY = drag.value + if (screen) 0f else (1f - progress) * (if (sheet) 28 else 8).dp.toPx()
                            // Pages push in from the trailing edge (a third of a screen), like UINavigationController.
                            translationX = if (screen) (1f - progress) * size.width * .32f else 0f
                            alpha = progress.coerceIn(0f, 1f)
                            // Popovers grow out of the control that opened them, like an iOS context menu.
                            val from = if (anchored) .86f else .97f
                            scaleX = if (screen) 1f else from + progress * (1f - from)
                            scaleY = if (screen) 1f else from + progress * (1f - from)
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
                    if (screen) Surface(panelModifier, color = MaterialTheme.colorScheme.background) { body() }
                    else PocketGlassSurface(panelModifier, shape = RoundedCornerShape(if (anchored) 22.dp else if (sheet) 28.dp else 24.dp),
                        backdrop = LocalModalBackdrop.current, dense = true, content = body)
                }
                if (anchored) AnchoredPanelLayout(anchor, menuWidth, alignStart, below, panel) else panel()
            }
        }
    }
}

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
