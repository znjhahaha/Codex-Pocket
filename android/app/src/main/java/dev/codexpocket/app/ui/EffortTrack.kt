package dev.codexpocket.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A native catalog is discrete: dragging never invents an intermediate effort. */
internal fun effortTrackIndex(x: Float, width: Float, inset: Float, count: Int): Int =
    if (count <= 1 || width <= inset * 2) 0 else
        (((x - inset) / (width - inset * 2)).coerceIn(0f, 1f) * (count - 1)).roundToInt()

@Composable
internal fun EffortTrack(choices: List<String?>, selected: String?, known: Boolean, enabled: Boolean,
    label: String, onSelect: (String?) -> Unit) {
    if (choices.isEmpty()) return
    val index = choices.indexOf(selected).takeIf { known && it >= 0 }
    val fraction by animateFloatAsState(if (index == null || choices.size < 2) 0f else index.toFloat() / choices.lastIndex,
        pocketPressSpring(), label = "effort-thumb")
    val accent = effortAccent()
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val dot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f)
    val density = LocalDensity.current
    val inset = with(density) { 17.dp.toPx() }
    val callback by rememberUpdatedState(onSelect)
    val latestChoices by rememberUpdatedState(choices)
    var gestureIndex by remember { mutableIntStateOf(-1) }
    fun select(x: Float, width: Float) {
        val next = effortTrackIndex(x, width, inset, latestChoices.size)
        if (next != gestureIndex) { gestureIndex = next; callback(latestChoices[next]) }
    }
    Canvas(Modifier.fillMaxWidth().height(48.dp).testTag("effort-track")
        .semantics {
            contentDescription = "思考强度"
            stateDescription = label
            if (!enabled) disabled()
            progressBarRangeInfo = if (index == null) ProgressBarRangeInfo.Indeterminate
                else ProgressBarRangeInfo(index.toFloat(), 0f..choices.lastIndex.toFloat(), (choices.size - 2).coerceAtLeast(0))
            if (enabled) setProgress { value ->
                if (!value.isFinite()) false else { callback(choices[value.roundToInt().coerceIn(0, choices.lastIndex)]); true }
            }
        }
        .pointerInput(enabled, choices) {
            if (enabled) detectTapGestures(onTap = { gestureIndex = -1; select(it.x, size.width.toFloat()) })
        }
        .pointerInput(enabled, choices) {
            if (enabled) detectDragGestures(onDragStart = { gestureIndex = -1; select(it.x, size.width.toFloat()) }) { change, _ ->
                change.consume(); select(change.position.x, size.width.toFloat())
            }
        }) {
        val h = 32.dp.toPx()
        val top = (size.height - h) / 2
        val x = inset + (size.width - 2 * inset).coerceAtLeast(0f) * fraction
        drawRoundRect(base, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
        if (index != null) drawRoundRect(accent.copy(alpha = if (enabled) 1f else .4f), Offset(0f, top),
            Size((x + inset).coerceAtMost(size.width), h), CornerRadius(h / 2))
        choices.indices.forEach { i ->
            val cx = if (choices.size == 1) size.width / 2 else inset + (size.width - 2 * inset) * i / choices.lastIndex
            drawCircle(if (index != null && i <= index) Color.White.copy(alpha = .4f) else dot, 2.5.dp.toPx(), Offset(cx, size.height / 2))
        }
        if (index != null) {
            drawCircle(Color.Black.copy(alpha = .09f), 18.dp.toPx(), Offset(x, size.height / 2 + 1.dp.toPx()))
            drawCircle(Color.White, 16.5.dp.toPx(), Offset(x, size.height / 2))
        }
    }
}
