package dev.codexpocket.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** A drawing-only bounce leaves the transcript and floating dock geometry unchanged. */
@Composable
internal fun JumpToLatestGlyph(running: Boolean) {
    if (!running) {
        Icon(PocketIcons.ArrowDownward, null, Modifier.size(18.dp).testTag("jump-latest-idle"))
        return
    }
    val preferences = rememberPocketGlassPreferences()
    val animate = preferences.animatorsEnabled && !preferences.batterySaver && !preferences.highContrast
    val phase = if (animate) rememberInfiniteTransition(label = "jump-latest-working").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "three-dot-bounce"
    ) else rememberUpdatedState(0f)
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp).testTag("jump-latest-running")) {
        val unit = size.width / 24f
        repeat(3) { index ->
            val local = (phase.value - index * .14f + 1f) % 1f
            val lift = if (animate && local < .42f) sin(local / .42f * PI).toFloat() else 0f
            drawCircle(color, 1.8f * unit, Offset((5f + index * 7f) * unit, center.y + (1f - 4f * lift) * unit))
        }
    }
}
