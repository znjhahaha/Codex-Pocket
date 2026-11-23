package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.kit.GlyphMotion.easeOut
import dev.codexpocket.app.ui.kit.GlyphMotion.pulse
import dev.codexpocket.app.ui.kit.GlyphMotion.swing
import dev.codexpocket.app.ui.kit.GlyphMotion.window

/** What a still icon does when its action lands. Each plays once from rest and ends at rest. */
internal enum class PIconMotion {
    /** Squashes and springs back. The default acknowledgement. */
    Bounce,
    /** Leans the way an arrow points and returns. */
    NudgeBack, NudgeForward, NudgeDown, NudgeUp,
    /** A quarter turn, for marks that look the same after it: a cross, a plus. */
    Turn,
    /** One full eased turn: refresh, sync. */
    Spin,
    /** Rocks about its centre and settles: a bell, a gear, a pencil. */
    Wiggle,
    None,
}

/** The motion that suits what a glyph means, by the name it was drawn under. */
internal fun pocketIconMotion(icon: ImageVector): PIconMotion = when (icon.name) {
    "ArrowBack" -> PIconMotion.NudgeBack
    "ArrowForward", "ChevronRight", "SubdirectoryArrowRight" -> PIconMotion.NudgeForward
    "ArrowDownward", "ExpandMore" -> PIconMotion.NudgeDown
    "ArrowUpward", "ExpandLess" -> PIconMotion.NudgeUp
    "Close", "Add" -> PIconMotion.Turn
    "Refresh" -> PIconMotion.Spin
    "Settings", "Tune", "NotificationsNone", "Edit", "Search", "PushPin" -> PIconMotion.Wiggle
    else -> PIconMotion.Bounce
}

/**
 * Plays [motion] once each time [trigger] changes; nothing plays for the value it is first composed
 * with. Only a layer moves, so neighbours never shift and the touch target stays where it is.
 */
@Composable
internal fun Modifier.pocketIconMotion(trigger: Any?, motion: PIconMotion = PIconMotion.Bounce): Modifier {
    val allowed = rememberPocketMotion()
    val play = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (!allowed || motion == PIconMotion.None) { play.snapTo(1f); return@LaunchedEffect }
        play.snapTo(0f)
        play.animateTo(1f, tween(if (motion == PIconMotion.Spin) 560 else 440, easing = LinearEasing))
    }
    return graphicsLayer {
        val t = play.value
        if (t >= 1f) return@graphicsLayer
        // Out quickly, back past rest by a little, then home.
        val lean = pulse(window(t, 0f, .55f)) - .28f * pulse(window(t, .5f, 1f))
        when (motion) {
            PIconMotion.Bounce -> { val size = 1f - .2f * pulse(window(t, 0f, .4f)) + .09f * pulse(window(t, .35f, 1f)); scaleX = size; scaleY = size }
            PIconMotion.NudgeBack -> translationX = -3.2f.dp.toPx() * lean
            PIconMotion.NudgeForward -> translationX = 3.2f.dp.toPx() * lean
            PIconMotion.NudgeDown -> translationY = 3.2f.dp.toPx() * lean
            PIconMotion.NudgeUp -> translationY = -3.2f.dp.toPx() * lean
            PIconMotion.Turn -> rotationZ = 90f * easeOut(t)
            PIconMotion.Spin -> rotationZ = 360f * easeOut(t)
            PIconMotion.Wiggle -> rotationZ = 16f * swing(t, 2f)
            PIconMotion.None -> Unit
        }
    }
}
