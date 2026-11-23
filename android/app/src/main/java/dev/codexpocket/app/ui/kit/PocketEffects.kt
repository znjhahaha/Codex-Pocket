package dev.codexpocket.app.ui.kit

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Squashes and springs back each time [trigger] changes: the acknowledgement a glyph gives when its
 * action lands. Nothing plays for the value it is first composed with.
 */
@Composable
internal fun Modifier.pocketBounce(trigger: Any?, depth: Float = .16f): Modifier {
    val motion = rememberPocketMotion()
    val scale = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (!motion) { scale.snapTo(1f); return@LaunchedEffect }
        scale.animateTo(1f - depth, tween(90, easing = PocketMotion.EaseOut))
        scale.animateTo(1f, PocketSprings.bouncy(.36f))
    }
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/** How much a body stretches along its travel at [velocity], in its own sizes per second. */
internal fun jellyStretch(velocity: Float, gel: Float = .5f): Float = (abs(velocity) * .035f * gel).coerceAtMost(.22f)

/**
 * Stretches along the direction of travel and narrows across it in proportion to speed, so a moving
 * selection reads as one soft body. When the velocity reverses on a bounce it squashes on its own.
 */
internal fun Modifier.pocketJelly(vertical: Boolean = false, gel: Float = .5f, velocity: () -> Float): Modifier = graphicsLayer {
    val stretch = jellyStretch(velocity(), gel)
    if (vertical) { scaleY = 1f + stretch; scaleX = 1f - stretch * .45f } else { scaleX = 1f + stretch; scaleY = 1f - stretch * .45f }
}

/**
 * A container that changes size settles on a spring and carries on from its current size when the
 * target changes again. Put the background before it and the padding after.
 */
@Composable
internal fun Modifier.pocketResize(alignment: Alignment = Alignment.TopStart): Modifier =
    animateContentSize(PocketMotion.resize<IntSize>().orSnap(rememberPocketMotion()), alignment)

/**
 * Decides which items of a page arrive in sequence. Only what is composed in the first moments
 * takes part; rows that scroll into view later are simply there.
 */
@Stable
internal class PEntrance(open: Boolean) {
    var open by mutableStateOf(open)
        internal set
}

/** Restarted when [key] changes, so a page that swaps its whole content can stagger it in again. */
@Composable
internal fun rememberPocketEntrance(key: Any? = Unit, windowMillis: Long = 420): PEntrance {
    val motion = rememberPocketMotion()
    val entrance = remember(key) { PEntrance(motion) }
    LaunchedEffect(entrance) { if (entrance.open) { delay(windowMillis); entrance.open = false } }
    return entrance
}

/**
 * Rises a short way into place as it fades in, [index] steps after the first item. The stagger is
 * capped so a long list never keeps its tail waiting.
 */
@Composable
internal fun Modifier.pocketEntrance(entrance: PEntrance, index: Int, rise: Dp = 14.dp, stepMillis: Int = 34): Modifier {
    val progress = remember { Animatable(if (entrance.open) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value >= 1f) return@LaunchedEffect
        delay(index.coerceIn(0, 9) * stepMillis.toLong())
        progress.animateTo(1f, PocketSprings.smooth(.46f))
    }
    return graphicsLayer {
        val shown = progress.value
        if (shown >= 1f) return@graphicsLayer
        alpha = shown.coerceIn(0f, 1f)
        translationY = (1f - shown) * rise.toPx()
        val size = .98f + .02f * shown
        scaleX = size; scaleY = size
        transformOrigin = TransformOrigin(.5f, 0f)
    }
}

/**
 * Blurs by a radius read while drawing. Costs a layer only while the radius is above zero; on
 * releases without render effects the content is left sharp and callers rely on fade and scale.
 */
internal fun Modifier.pocketBlur(radius: Density.() -> Float): Modifier =
    if (Build.VERSION.SDK_INT < 31) this else graphicsLayer {
        val amount = radius()
        renderEffect = if (amount > .5f) BlurEffect(amount, amount, TileMode.Decal) else null
    }
