package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pages side by side, one to a screen. A finger carries them one to one, the first and last resist
 * being pulled further, and letting go settles on the page a throw would reach: at most one away.
 * [position] is in pages (1.25 is a quarter of the way from the second page to the third), so the
 * content, an indicator and any parallax all read the same number and nothing here depends on pixels.
 */
@Stable
internal class PPagerState internal constructor(initial: Int) {
    var position by mutableFloatStateOf(initial.toFloat())
        private set
    /** The page shown, or being settled on. */
    var page by mutableIntStateOf(initial)
        private set
    var dragging by mutableStateOf(false)
        private set
    internal var count = 1
    internal var width = 0f
    internal var motion = true
    internal var haptics: PocketHaptics? = null
    private var finger = 0f
    private var origin = 0
    private var settling: Job? = null

    /** Goes to [index], carrying on from wherever the pages are and at whatever speed they have. */
    fun open(index: Int, scope: CoroutineScope, velocity: Float = 0f) {
        val target = index.coerceIn(0, count - 1)
        if (target != page) { page = target; haptics?.perform(PocketHaptic.Tick) }
        settling?.cancel()
        settling = scope.launch {
            if (!motion) { position = target.toFloat(); return@launch }
            AnimationState(position, velocity).animateTo(target.toFloat(), PocketMotion.page()) { position = value }
        }
    }

    internal fun grab() {
        settling?.cancel(); settling = null
        dragging = true; finger = position; origin = position.roundToInt().coerceIn(0, count - 1)
    }

    internal fun dragBy(delta: Float) {
        if (!dragging || width <= 0f) return
        finger -= delta / width
        val last = (count - 1).toFloat()
        position = when {
            finger < 0f -> -rubberBand(-finger, Give)
            finger > last -> last + rubberBand(finger - last, Give)
            else -> finger
        }
    }

    internal fun release(velocity: Float, scope: CoroutineScope) {
        if (!dragging) return
        dragging = false
        val speed = if (width > 0f) -velocity / width else 0f
        // A throw counts for a moment of travel, and no throw skips a page.
        val landing = (position + speed * Throw).roundToInt().coerceIn(origin - 1, origin + 1)
        open(landing, scope, speed)
    }

    private companion object {
        const val Throw = .16f
        /** The most the pages can be pulled past either end, in pages. */
        const val Give = .28f
    }
}

@Composable
internal fun rememberPPager(count: Int, initial: Int = 0): PPagerState {
    val state = remember { PPagerState(initial.coerceIn(0, (count - 1).coerceAtLeast(0))) }
    state.count = count; state.motion = rememberPocketMotion(); state.haptics = rememberPocketHaptics()
    return state
}

/**
 * Lays [state]'s pages out and lets a finger carry them. Only the pages on screen are composed; each
 * is given how far it is from the middle (-1 one page to the left, 0 centred), read while drawing, so
 * a page can move parts of itself at their own rate for depth.
 */
@Composable
internal fun PPager(state: PPagerState, modifier: Modifier = Modifier, content: @Composable (page: Int, offset: () -> Float) -> Unit) {
    val scope = rememberCoroutineScope()
    val drag = rememberDraggableState { delta -> state.dragBy(delta) }
    Box(modifier.clipToBounds().onSizeChanged { state.width = it.width.toFloat() }
        .draggable(drag, Orientation.Horizontal, enabled = state.count > 1, startDragImmediately = false,
            onDragStarted = { state.grab() }, onDragStopped = { velocity -> state.release(velocity, scope) })
        .semantics { stateDescription = "第 ${state.page + 1} 页，共 ${state.count} 页" }) {
        val first = floor(state.position).toInt()
        val shown = if (abs(state.position - first) < .001f) first..first else first..first + 1
        for (index in shown) if (index in 0 until state.count) key(index) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = (index - state.position) * size.width }.testTag("pager-page:$index")) {
                content(index) { index - state.position }
            }
        }
    }
}

/**
 * A dot for each page and a pill over the current one. Between two pages the pill reaches from one
 * dot to the next and then draws in again, so it is seen to travel rather than to jump.
 */
@Composable
internal fun PPageDots(state: PPagerState, modifier: Modifier = Modifier, dot: Dp = 6.dp, gap: Dp = 10.dp,
    color: Color = Pocket.colors.ink, rest: Color = Pocket.colors.fillStrongest) {
    val count = state.count
    Canvas(modifier.width(dot * count + gap * (count - 1) + dot * 2).height(dot).testTag("pager-dots")) {
        val d = dot.toPx(); val step = d + gap.toPx()
        // The pill is two dots wide at rest, so the dots after it sit a dot further along.
        val lead = d
        val at = state.position.coerceIn(0f, (count - 1).toFloat())
        val under = floor(at).toInt(); val part = at - under
        for (index in 0 until count) {
            val x = index * step + when { index > under + 1 -> lead * 2; index == under + 1 -> lead * 2 * (1f - part); else -> 0f }
            drawRoundRect(rest, Offset(x, 0f), Size(d, d), CornerRadius(d / 2))
        }
        val reach = step * sin(Math.PI * part).toFloat() * .5f
        val start = under * step + part * step - reach * .5f
        drawRoundRect(color, Offset(start, 0f), Size(d + lead * 2 + reach, d), CornerRadius(d / 2))
    }
}
