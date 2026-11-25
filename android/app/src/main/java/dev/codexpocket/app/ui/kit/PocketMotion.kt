package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.withContext
import dev.codexpocket.app.ui.rememberPocketGlassPreferences

/**
 * One motion vocabulary. Springs wherever a gesture can interrupt; short ease-out for things that
 * enter, faster for things that leave. Typing, scrolling and keyboard actions are never animated.
 */
internal object PocketMotion {
    /** Starts fast and settles: the response is visible in the first frames. */
    val EaseOut = CubicBezierEasing(.23f, 1f, .32f, 1f)
    val EaseInOut = CubicBezierEasing(.77f, 0f, .175f, 1f)

    const val EnterMillis = 220
    const val ExitMillis = 140
    const val ColorMillis = 140
    const val EnterOffsetDp = 12

    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = .82f, stiffness = 900f)
    /** Letting go is slower and looser than pressing in, so the control is seen to come back. */
    fun <T> release(): FiniteAnimationSpec<T> = PocketSprings.of(response = .34f, damping = .68f)
    /** A page arriving or leaving: no overshoot, and it carries on from wherever a gesture left it. */
    fun <T> page(): SpringSpec<T> = PocketSprings.of(response = .44f, damping = 1f)
    /** A sheet or menu growing out of its source: a trace of overshoot in position and size. */
    fun <T> morph(): SpringSpec<T> = PocketSprings.of(response = .36f, damping = .84f)
    /** A page opening out of a row or folding back into it: it reaches its place with a little speed left and stops there. */
    fun <T> unfold(): SpringSpec<T> = PocketSprings.of(response = .46f, damping = .86f)
    /** Something trailing a finger: quick enough to feel attached, damped enough not to wobble. */
    fun <T> follow(): SpringSpec<T> = PocketSprings.of(response = .24f, damping = .86f)
    /** Returning after being pulled past a limit. */
    fun <T> rebound(): SpringSpec<T> = PocketSprings.of(response = .42f, damping = .78f)
    /** Containers changing size: cards expanding, sheets settling. */
    fun <T> resize(): FiniteAnimationSpec<T> = spring(dampingRatio = .88f, stiffness = 380f)
    /** A control changing state in place: capsules, titles, sliding selections. */
    fun <T> state(): FiniteAnimationSpec<T> = spring(dampingRatio = .85f, stiffness = 420f)
    /** A small overshoot for glyphs confirming an action. */
    fun <T> glyph(): FiniteAnimationSpec<T> = spring(dampingRatio = .62f, stiffness = 520f)
    /** Constant-speed progress along a glyph's own trajectory. */
    fun glyphPulse(millis: Int): FiniteAnimationSpec<Float> = tween(millis, easing = LinearEasing)
    fun <T> enter(): FiniteAnimationSpec<T> = tween(EnterMillis, easing = EaseOut)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(ExitMillis, easing = EaseOut)
    fun <T> color(): FiniteAnimationSpec<T> = tween(ColorMillis, easing = EaseOut)
    /** Dimming changes evenly and never follows the panel's spring or overshoot. */
    fun scrim(entering: Boolean): FiniteAnimationSpec<Float> = tween(if (entering) EnterMillis else ExitMillis, easing = LinearEasing)
}

/**
 * Springs described the way SwiftUI describes them: [response] is the period of the undamped
 * oscillation in seconds, damping 1 never overshoots and lower values bounce. Unit mass, so
 * stiffness is (2π / response)².
 */
internal object PocketSprings {
    fun stiffness(response: Float): Float { val w = (2.0 * Math.PI / response.coerceAtLeast(.01f)).toFloat(); return w * w }
    fun <T> of(response: Float, damping: Float = 1f, threshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = damping, stiffness = stiffness(response), visibilityThreshold = threshold)
    /** Settles without a bounce. Sheets, pages, size changes. */
    fun <T> smooth(response: Float = .35f): SpringSpec<T> = of(response, 1f)
    /** A barely visible overshoot. Selections, toggles, menus. */
    fun <T> snappy(response: Float = .3f): SpringSpec<T> = of(response, .85f)
    /** A clear bounce. Confirmations and glyphs, never containers of text. */
    fun <T> bouncy(response: Float = .4f): SpringSpec<T> = of(response, .68f)
}

/**
 * The pull of a scroll view past its edge: nearly one to one at first, then harder the further it
 * goes, approaching [dimension] and never reaching it. The sign of [overshoot] is kept.
 */
internal fun rubberBand(overshoot: Float, dimension: Float, coefficient: Float = .55f): Float {
    if (dimension <= 0f || overshoot == 0f) return 0f
    val distance = kotlin.math.abs(overshoot)
    val pulled = (1f - 1f / (distance * coefficient / dimension + 1f)) * dimension
    return if (overshoot < 0f) -pulled else pulled
}

/** How far a finger must have travelled to produce [displayed] under [rubberBand]. */
internal fun rubberBandInverse(displayed: Float, dimension: Float, coefficient: Float = .55f): Float {
    if (dimension <= 0f || displayed == 0f) return 0f
    val pulled = kotlin.math.abs(displayed).coerceAtMost(dimension * .999f)
    val distance = (1f / (1f - pulled / dimension) - 1f) * dimension / coefficient
    return if (displayed < 0f) -distance else distance
}

/**
 * A frame clock that never jumps: however late a frame arrives, the animation reading this clock
 * moves on by at most [maxStepNanos]. A page that is slow to compose then makes its transition take
 * longer instead of making it skip, so every step of the way is still drawn.
 */
internal class SteadyFrameClock(private val parent: MonotonicFrameClock, private val maxStepNanos: Long = 34_000_000L) : MonotonicFrameClock {
    private var last = Long.MIN_VALUE
    private var time = 0L
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = parent.withFrameNanos { real ->
        time = if (last == Long.MIN_VALUE) real else time + steadyStep(real - last, maxStepNanos)
        last = real
        onFrame(time)
    }
}

/** How far a steady clock moves for a frame that took [elapsedNanos]. */
internal fun steadyStep(elapsedNanos: Long, maxStepNanos: Long = 34_000_000L): Long = elapsedNanos.coerceIn(0L, maxStepNanos)

/** Runs [block] with its animations on a [SteadyFrameClock]. Use it for a transition that brings heavy content with it. */
internal suspend fun <T> pocketSteadyFrames(block: suspend () -> T): T {
    val clock = kotlin.coroutines.coroutineContext[MonotonicFrameClock] ?: return block()
    return withContext(SteadyFrameClock(clock)) { block() }
}

/** System animations on, not saving power, not in high-contrast text. Decoration stops otherwise. */
@Composable
internal fun rememberPocketMotion(): Boolean {
    val preferences = rememberPocketGlassPreferences()
    return preferences.animatorsEnabled && !preferences.batterySaver && !preferences.highContrast
}

/** This spec while motion is allowed; an immediate change otherwise. */
internal fun <T> FiniteAnimationSpec<T>.orSnap(motion: Boolean): FiniteAnimationSpec<T> = if (motion) this else snap()
