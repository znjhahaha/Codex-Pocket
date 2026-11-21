package dev.codexpocket.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private val LightPointPositions = listOf(
    Offset(.03f, .08f), Offset(.88f, .05f), Offset(.97f, .22f), Offset(.02f, .32f),
    Offset(.94f, .47f), Offset(.06f, .60f), Offset(.98f, .72f), Offset(.02f, .83f),
    Offset(.26f, .96f), Offset(.76f, .93f),
)

/** Decoration only: no hit targets, accessible labels, images or off-screen render layers. */
@Composable
internal fun PocketLightPoints(modifier: Modifier = Modifier, edgeOnly: Boolean = false, active: Boolean = true) {
    val preferences = rememberPocketGlassPreferences()
    val motion = rememberNativeMotionAllowed() && active && LocalPocketInputActive.current()
    val phase = if (motion) rememberInfiniteTransition(label = "ambient-points").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "ambient-drift",
    ) else null
    val ink = pocketAmbientInk()
    Box(modifier.testTag("pocket-light-points").clearAndSetSemantics {}.drawWithCache {
        val radius = 7.dp.toPx()
        val drift = 4.dp.toPx()
        val edgeWidth = 12.dp.toPx()
        val centers = LightPointPositions.mapIndexed { i, p ->
            Offset(if (edgeOnly) (if (i % 2 == 0) edgeWidth * .45f else size.width - edgeWidth * .45f) else size.width * p.x,
                size.height * p.y)
        }
        val halo = Brush.radialGradient(listOf(ink.copy(alpha = ink.alpha * .22f), Color.Transparent),
            center = Offset.Zero, radius = radius)
        onDrawBehind {
            if (!preferences.highContrast) centers.forEachIndexed { i, center ->
                val t = phase?.value ?: 0f
                val point = center + Offset(sin(t + i) * drift, sin(t + i * 1.7f) * drift)
                // Home prose starts outside these gutters; particles never pass beneath it.
                clipRect(left = if (edgeOnly && i % 2 != 0) size.width - edgeWidth else 0f,
                    right = if (edgeOnly && i % 2 == 0) edgeWidth else size.width) {
                    translate(point.x, point.y) {
                        drawCircle(halo, radius, Offset.Zero)
                        drawCircle(ink, (if (i % 3 == 0) 1.4f else .9f).dp.toPx(), Offset.Zero)
                    }
                }
            }
        }
    })
}
