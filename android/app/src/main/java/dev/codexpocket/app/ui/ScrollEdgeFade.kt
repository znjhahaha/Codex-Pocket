package dev.codexpocket.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fade clipped content into its existing backdrop; never paint an opaque dark strip. */
internal fun Modifier.scrollEdgeFade(
    leading: () -> Boolean,
    trailing: () -> Boolean,
    depth: Dp = 18.dp,
    trailingInset: Dp = 0.dp,
): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val height = minOf(depth.toPx(), size.height / 3f)
    if (height > 0f && leading()) drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = 0f, endY = height),
        size = Size(size.width, height), blendMode = BlendMode.DstIn)
    val end = (size.height - trailingInset.toPx()).coerceIn(height, size.height)
    if (height > 0f && trailing()) drawRect(
        Brush.verticalGradient(listOf(Color.White, Color.Transparent), startY = end - height, endY = end),
        topLeft = Offset(0f, end - height), size = Size(size.width, size.height - end + height), blendMode = BlendMode.DstIn)
}
