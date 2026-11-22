package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Longest a figure may take to reach its new value. */
internal const val PRollingNumberMaxMillis = 400

private class PRoll(start: Double) {
    var from = start
    var to = start
    val progress = Animatable(1f)
    fun current() = from + (to - from) * progress.value
}

/**
 * A figure that counts from what it showed to its new value when [value] changes; [format] renders
 * the steps in between and [text] is the exact final reading. The first value appears as it is, so a
 * figure scrolled into view never animates. With motion off, or without a numeric [value], [text] is
 * drawn directly and no animation state exists. One line, never wrapped: size it to fit.
 */
@Composable
internal fun PRollingNumber(value: Double?, text: String, modifier: Modifier = Modifier, style: TextStyle? = null,
    color: Color = Color.Unspecified, millis: Int = 360, format: (Double) -> String) {
    if (!rememberPocketMotion() || value == null || !value.isFinite()) {
        PText(text, modifier, style, color, maxLines = 1, softWrap = false)
        return
    }
    val roll = remember { PRoll(value) }
    LaunchedEffect(value) {
        if (value == roll.to) return@LaunchedEffect
        roll.from = roll.current(); roll.to = value
        roll.progress.snapTo(0f)
        roll.progress.animateTo(1f, tween(millis.coerceIn(0, PRollingNumberMaxMillis), easing = PocketMotion.EaseOut))
    }
    val progress = roll.progress.value
    val settled = value == roll.to && progress >= 1f
    PText(if (settled) text else format(roll.from + (roll.to - roll.from) * progress), modifier, style, color, maxLines = 1, softWrap = false)
}
