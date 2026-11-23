package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * One thumb over a row of equal items: a segmented control, a tab bar. Tapping an item sends the
 * thumb there with its leading edge ahead of its trailing one, so it stretches the way it travels.
 * Dragging anywhere on the row picks the thumb up: it follows the finger, resists past either end,
 * and on release settles on the item it was carried or thrown to, which is then selected.
 *
 * Positions are in items from the start of the row, so a control draws the thumb from
 * [start] to [end] times the width of one item and nothing here depends on pixels.
 */
@Stable
internal class PSlideState internal constructor(initial: Int) {
    /** The thumb's edge nearer the start of the row, and its far edge. At rest they are one item apart. */
    val start = Animatable(initial.toFloat())
    val end = Animatable(initial + 1f)
    /** 0 at rest, 1 while a finger holds the thumb. */
    val lift = Animatable(0f)
    var dragging by mutableStateOf(false)
        private set
    private var over by mutableIntStateOf(initial)
    internal var selected by mutableIntStateOf(initial)
    /** The item to show as chosen: the one under the thumb while it is carried, the selection otherwise. */
    val shown: Int get() = if (dragging) over else selected

    internal var count = 1
    internal var enabled = true
    internal var motion = true
    internal var width = 0f
    internal var haptic: PocketHaptic? = null
    internal var haptics: PocketHaptics? = null
    internal var onSelect: (Int) -> Unit = {}
    private var finger = 0f
    private var grip = 0f
    private var carrying: Job? = null

    /** The middle of the thumb, in items. */
    val centre: Float get() = (start.value + end.value) / 2f

    internal val draggable: Boolean get() = enabled && count > 1 && selected in 0 until count

    /**
     * Sends the thumb to [index]. The edge facing the way it is going moves first. False when something
     * else took the thumb over before it got there. A slow frame, such as the one in which the page
     * behind a tab is first composed, moves it on by one step rather than making it skip.
     */
    internal suspend fun settle(index: Int): Boolean {
        if (index !in 0 until count) return false
        if (!motion) { start.snapTo(index.toFloat()); end.snapTo(index + 1f); return true }
        val forward = index + .5f >= centre
        var arrived = 0
        pocketSteadyFrames { coroutineScope {
            launch { start.animateTo(index.toFloat(), if (forward) Trailing else Leading); arrived++ }
            launch { end.animateTo(index + 1f, if (forward) Leading else Trailing); arrived++ }
        } }
        return arrived == 2
    }

    internal fun grab(x: Float, scope: CoroutineScope) {
        val item = item() ?: return
        dragging = true
        finger = x / item
        // Held by the thumb itself it keeps its place under the finger; held elsewhere it comes to the finger.
        grip = if (finger in start.value..end.value) centre - finger else 0f
        over = floor(target()).toInt().coerceIn(0, count - 1)
        scope.launch { if (motion) lift.animateTo(1f, PocketMotion.press()) else lift.snapTo(1f) }
        carry(0f, scope)
    }

    internal fun dragBy(delta: Float, scope: CoroutineScope) {
        val item = item() ?: return
        if (!dragging) return
        finger += delta / item
        val under = floor(target()).toInt().coerceIn(0, count - 1)
        if (under != over) { over = under; haptic?.let { haptics?.perform(it) } }
        carry(delta, scope)
    }

    internal suspend fun release(velocity: Float, scope: CoroutineScope) {
        val item = item()
        if (!dragging || item == null) { dragging = false; return }
        // Where a throw would carry it in the next moment decides which item it lands on.
        val landing = floor(target() + velocity / item * Throw).toInt().coerceIn(0, count - 1)
        dragging = false
        // A step toward the finger that has not run yet must not run after the thumb has been let go.
        carrying?.cancel(); carrying = null
        scope.launch { if (motion) lift.animateTo(0f, PocketMotion.release()) else lift.snapTo(0f) }
        if (landing != selected) onSelect(landing)
        // Once it has landed undisturbed the owner has had its say: if it refused, show what it holds.
        if (settle(landing) && !dragging && selected != landing) settle(selected)
    }

    private fun item(): Float? = (width / count).takeIf { it > 0f && count > 0 }

    /** Where the middle of the thumb belongs for the finger: one to one inside the row, less and less beyond its ends. */
    private fun target(): Float {
        val wanted = finger + grip
        val low = .5f; val high = count - .5f
        return when {
            wanted < low -> low - rubberBand(low - wanted, Give)
            wanted > high -> high + rubberBand(wanted - high, Give)
            else -> wanted
        }
    }

    private fun carry(delta: Float, scope: CoroutineScope) {
        val centre = target()
        carrying = scope.launch {
            if (!dragging) return@launch
            if (!motion) { start.snapTo(centre - .5f); end.snapTo(centre + .5f); return@launch }
            val forward = if (delta != 0f) delta > 0f else centre >= this@PSlideState.centre
            coroutineScope {
                launch { start.animateTo(centre - .5f, if (forward) HeldTrailing else HeldLeading) }
                launch { end.animateTo(centre + .5f, if (forward) HeldLeading else HeldTrailing) }
            }
        }
    }

    private companion object {
        val Leading = spring<Float>(dampingRatio = .85f, stiffness = 640f)
        val Trailing = spring<Float>(dampingRatio = .85f, stiffness = 300f)
        /** Under a finger both edges keep up closely; the trailing one gives just enough to read as soft. */
        val HeldLeading = spring<Float>(dampingRatio = 1f, stiffness = 3200f)
        val HeldTrailing = spring<Float>(dampingRatio = 1f, stiffness = 1100f)
        /** Seconds of travel at the release speed that count toward where the thumb lands. */
        const val Throw = .1f
        /** The most the thumb can be pulled past an end, in items. */
        const val Give = .32f
    }
}

/**
 * The sliding selection for [count] equal items with [selected] chosen; [selected] outside the row
 * leaves the thumb where it was and cannot be dragged. [haptic] is felt each time a carried thumb
 * crosses into another item.
 */
@Composable
internal fun rememberPSlide(count: Int, selected: Int, enabled: Boolean = true, haptic: PocketHaptic? = PocketHaptic.Tick,
    onSelect: (Int) -> Unit): PSlideState {
    val state = remember { PSlideState(selected.coerceAtLeast(0)) }
    state.count = count; state.selected = selected; state.enabled = enabled; state.haptic = haptic; state.onSelect = onSelect
    state.motion = rememberPocketMotion()
    state.haptics = rememberPocketHaptics()
    LaunchedEffect(selected, count, state.motion) { if (!state.dragging) state.settle(selected) }
    return state
}

/** Lets the row this is placed on carry [state]'s thumb. Put it on the element whose width is all the items. */
@Composable
internal fun Modifier.pocketSlide(state: PSlideState): Modifier {
    val scope = rememberCoroutineScope()
    val drag = rememberDraggableState { delta -> state.dragBy(delta, scope) }
    return onSizeChanged { state.width = it.width.toFloat() }
        .draggable(drag, Orientation.Horizontal, enabled = state.draggable,
            onDragStarted = { at -> state.grab(at.x, scope) }, onDragStopped = { velocity -> state.release(velocity, scope) })
}
