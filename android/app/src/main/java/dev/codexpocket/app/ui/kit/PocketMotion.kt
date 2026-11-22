package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
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
    /** Containers changing size: cards expanding, sheets settling. */
    fun <T> resize(): FiniteAnimationSpec<T> = spring(dampingRatio = .88f, stiffness = 380f)
    /** A control changing state in place: capsules, titles, sliding selections. */
    fun <T> state(): FiniteAnimationSpec<T> = spring(dampingRatio = .85f, stiffness = 420f)
    /** A small overshoot for glyphs confirming an action. */
    fun <T> glyph(): FiniteAnimationSpec<T> = spring(dampingRatio = .62f, stiffness = 520f)
    fun <T> enter(): FiniteAnimationSpec<T> = tween(EnterMillis, easing = EaseOut)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(ExitMillis, easing = EaseOut)
    fun <T> color(): FiniteAnimationSpec<T> = tween(ColorMillis, easing = EaseOut)
}

/** System animations on, not saving power, not in high-contrast text. Decoration stops otherwise. */
@Composable
internal fun rememberPocketMotion(): Boolean {
    val preferences = rememberPocketGlassPreferences()
    return preferences.animatorsEnabled && !preferences.batterySaver && !preferences.highContrast
}

/** This spec while motion is allowed; an immediate change otherwise. */
internal fun <T> FiniteAnimationSpec<T>.orSnap(motion: Boolean): FiniteAnimationSpec<T> = if (motion) this else snap()
