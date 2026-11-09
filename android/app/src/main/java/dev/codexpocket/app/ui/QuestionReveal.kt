package dev.codexpocket.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp

/** Measures the final content immediately. Only its ink is revealed, never its layout height. */
@Composable
internal fun QuestionReveal(
    questionId: String?,
    state: QuestionRevealState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = rememberNativeMotionAllowed()
    val progress = remember(state, questionId) {
        Animatable(if (state.durationFor(questionId) == 0) 1f else 0f)
    }
    LaunchedEffect(state, questionId, motion) {
        // This finite entrance starts once. Initial placement/clip changes must not cancel it
        // and consume its appearance before the first visible frame reaches the screen.
        val duration = state.durationFor(questionId)
        state.markShown(questionId)
        if (motion && duration > 0) {
            progress.animateTo(1f, tween(duration, easing = LinearEasing))
        } else progress.snapTo(1f)
    }
    Box(modifier.drawWithCache {
        val layer = Paint()
        val bounds = Rect(Offset.Zero, size)
        val feather = minOf(24.dp.toPx(), size.height).coerceAtLeast(1f)
        onDrawWithContent {
            val contentScope = this
            val fraction = if (motion) progress.value else 1f
            // saveLayer's bounds are allocation hints; explicitly constrain descendant drawing.
            clipRect {
                if (fraction >= 1f) {
                    contentScope.drawContent()
                } else if (fraction > 0f) {
                    val edge = (size.height + feather) * fraction
                    // DstIn preserves full coverage above a small feather and clears everything below.
                    // The isolated layer prevents the mask from touching the dock or transcript.
                    drawContext.canvas.saveLayer(bounds, layer)
                    try {
                        contentScope.drawContent()
                        drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent),
                            startY = edge - feather, endY = edge), blendMode = BlendMode.DstIn)
                    } finally { drawContext.canvas.restore() }
                }
            }
        }
    }, propagateMinConstraints = true) { content() }
}
