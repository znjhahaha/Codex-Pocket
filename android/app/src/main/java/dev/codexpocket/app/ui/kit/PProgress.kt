package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Three dots breathing in sequence on a 1.1s cycle. Still dots when motion is off. */
@Composable
internal fun PDots(modifier: Modifier = Modifier, dot: Dp = 5.dp, color: Color = Pocket.colors.inkMuted) {
    val motion = rememberPocketMotion()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(dot * .8f), verticalAlignment = Alignment.CenterVertically) {
        if (!motion) {
            repeat(3) { Box(Modifier.size(dot).background(color.copy(alpha = color.alpha * .55f), CircleShape)) }
            return@Row
        }
        val transition = rememberInfiniteTransition(label = "pocket-dots")
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

/** A determinate ring with rounded caps; the arc eases toward its value. */
@Composable
internal fun PRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 2.dp, track: Color = Pocket.colors.fillStrongest,
    color: Color = Pocket.colors.ink) {
    val motion = rememberPocketMotion()
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween<Float>(260, easing = PocketMotion.EaseOut).orSnap(motion), label = "ring")
    Canvas(modifier) {
        val width = stroke.toPx()
        val inset = width / 2
        val area = Size(size.width - width, size.height - width)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), area, style = Stroke(width))
        drawArc(color, -90f, 360f * shown, false, Offset(inset, inset), area, style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** Work of unknown length: a three-quarter arc turning once a second. A static arc when motion is off. */
@Composable
internal fun PSpinner(modifier: Modifier = Modifier.size(18.dp), stroke: Dp = 2.dp, color: Color = Pocket.colors.inkMuted) {
    val motion = rememberPocketMotion()
    val turn = if (motion) rememberInfiniteTransition(label = "spinner").animateFloat(0f, 360f,
        infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinner-turn") else null
    Canvas(modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val width = stroke.toPx()
        drawArc(color, (turn?.value ?: 0f) - 90f, 270f, false, Offset(width / 2, width / 2),
            Size(size.width - width, size.height - width), style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** A thin bar. Null progress is unknown length: a band crossing the track; never an invented percentage. */
@Composable
internal fun PLinearProgress(progress: Float?, modifier: Modifier = Modifier, track: Color = Pocket.colors.fillStrong,
    color: Color = Pocket.colors.accent) {
    val motion = rememberPocketMotion()
    val measured = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val shown by animateFloatAsState(measured ?: 0f, tween<Float>(260, easing = PocketMotion.EaseOut).orSnap(motion), label = "linear")
    val sweep = if (measured == null && motion) rememberInfiniteTransition(label = "linear-sweep").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing)), label = "linear-sweep-x") else null
    Canvas(modifier.fillMaxWidth().height(3.dp).semantics {
        progressBarRangeInfo = measured?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
    }) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (measured != null) {
            if (shown > 0f) drawRoundRect(color, size = Size(size.width * shown, size.height), cornerRadius = radius)
        } else {
            val band = size.width * .32f
            val x = if (sweep != null) (size.width + band) * sweep.value - band else (size.width - band) / 2
            val left = x.coerceAtLeast(0f)
            val right = (x + band).coerceAtMost(size.width)
            if (right > left) drawRoundRect(color, Offset(left, 0f), Size(right - left, size.height), radius)
        }
    }
}

/** A soft light crossing a placeholder every 1.3s. Clip first. Flat when motion is off. */
internal fun Modifier.pocketSkeleton(): Modifier = composed {
    val base = Pocket.colors.fillStrong
    val light = Pocket.colors.fillStrongest
    if (!rememberPocketMotion()) return@composed background(base)
    val x by rememberInfiniteTransition(label = "skeleton").animateFloat(-.5f, 1.5f,
        infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "skeleton-x")
    drawWithContent {
        val band = size.width * .45f
        val center = size.width * x
        drawRect(base)
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, light, Color.Transparent), startX = center - band, endX = center + band))
        drawContent()
    }
}
