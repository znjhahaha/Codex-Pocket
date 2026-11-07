package dev.codexpocket.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Soft highlight sweeping across a placeholder, as skeleton screens on the web do. Flat when motion is off. */
@Composable
internal fun PocketShimmer(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val light = MaterialTheme.colorScheme.surfaceContainerHighest
    if (!loaderMotion()) { Box(modifier.background(base)); return }
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(-1f, 2f,
        infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "shimmer-x")
    Box(modifier.drawWithContent {
        val w = size.width
        drawRect(Brush.linearGradient(listOf(base, light, base), start = Offset(w * sweep - w * .6f, 0f), end = Offset(w * sweep, size.height)))
    })
}

@Composable
private fun loaderMotion(): Boolean {
    val preferences = rememberPocketGlassPreferences()
    return preferences.animatorsEnabled && !preferences.batterySaver && !preferences.highContrast
}

/**
 * Three dots breathing in sequence (iMessage typing / Vercel loading-dots). Each dot rises,
 * brightens and grows slightly on a 1.1s cycle offset by 140ms. Still dots when motion is off.
 */
@Composable
internal fun PocketLoader(modifier: Modifier = Modifier, dot: Dp = 5.dp, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val motion = loaderMotion()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(dot * .8f), verticalAlignment = Alignment.CenterVertically) {
        if (!motion) {
            repeat(3) { Box(Modifier.size(dot).background(color.copy(alpha = .55f), CircleShape)) }
            return@Row
        }
        val transition = rememberInfiniteTransition(label = "pocket-loader")
        repeat(3) { index ->
            val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(keyframes {
                durationMillis = 1100
                0f at 0 using FastOutSlowInEasing
                1f at 280 using FastOutSlowInEasing
                0f at 620
                0f at 1100
            }, initialStartOffset = StartOffset(index * 140)), label = "dot-$index")
            Box(Modifier.size(dot).graphicsLayer {
                translationY = -phase * dot.toPx() * .7f
                scaleX = 1f + phase * .18f; scaleY = scaleX
                alpha = .35f + phase * .65f
            }.background(color, CircleShape))
        }
    }
}

/** Determinate ring with rounded caps; the arc eases toward its value instead of jumping. */
@Composable
internal fun PocketRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 2.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val arc = MaterialTheme.colorScheme.onSurface
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(pocketMotionMillis(260), easing = FastOutSlowInEasing), label = "ring")
    Canvas(modifier) {
        val width = stroke.toPx()
        val inset = width / 2
        val size = androidx.compose.ui.geometry.Size(size.width - width, size.height - width)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), size, style = Stroke(width))
        drawArc(arc, -90f, 360f * shown, false, Offset(inset, inset), size, style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** Soft light sweep across a placeholder (1.3s), the skeleton pattern used by iOS and shadcn. Clip first. */
internal fun Modifier.pocketShimmer(): Modifier = composed {
    if (!loaderMotion()) return@composed this
    val highlight = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = .6f)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(-.5f, 1.5f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "shimmer-x")
    drawWithContent {
        drawContent()
        val band = size.width * .45f
        val center = size.width * x
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, highlight, Color.Transparent), startX = center - band, endX = center + band))
    }
}

/** Small feedback owned by the control doing work, with no invented percentage. */
@Composable
internal fun PocketActivityIndicator(label: String) {
    PocketLoader(Modifier.semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }, dot = 4.dp)
}

/** Null means an unknown proportion. Never manufacture a percentage from connection phases. */
@Composable
internal fun PocketProgressStatus(label: String, modifier: Modifier = Modifier, progress: Float? = null) {
    val measured = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    Row(modifier.widthIn(max = 360.dp).semantics(mergeDescendants = true) {
        liveRegion = LiveRegionMode.Polite
        progressBarRangeInfo = measured?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (measured != null) PocketRing(measured, Modifier.size(16.dp)) else PocketLoader()
        Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (measured != null) Text("${(measured * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
    }
}
