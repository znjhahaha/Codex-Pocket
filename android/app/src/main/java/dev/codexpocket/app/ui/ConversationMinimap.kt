package dev.codexpocket.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import dev.codexpocket.app.data.TranscriptKind
import dev.codexpocket.app.data.TranscriptRow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private val MinimapWidth = 52.dp
private val MinimapIdleWidth = 12.dp
private val MinimapInset = 8.dp
private val MinimapMinimumThumb = 16.dp
private const val MinimapIdleMillis = 2_500L
private const val MinimapTickLimit = 160

/**
 * Loaded transcript overview, hosted as a LEFT sibling of LazyColumn in a bounded Box.
 *
 * [rows] must be the currently displayed/folded rows; [keys] must include ALL LazyColumn
 * keys in their actual order (including history/live/end chrome). Use immutable snapshots.
 * Reserve a small idle gutter; expansion temporarily overlays its adjacent margin without reflow.
 * Constrain [modifier] below the header and above the composer/IME.
 *
 * Pass the transcript-only [backdrop], or provide it via LocalPocketBackdrop. Capture the
 * LazyColumn itself, never this composable or its surrounding Box: the glass must not sample
 * itself. Row bands approximate density; this does not render Markdown or hidden child rows.
 * The thumb uses measured visible row fractions; tap/drag seeks only already loaded keys.
 */
@Composable
internal fun ConversationMinimap(
    rows: List<TranscriptRow>,
    keys: List<String>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalPocketBackdrop.current,
    onSeek: () -> Unit = {},
) {
    val map = remember(rows, keys) { ConversationMinimapMap(rows, keys) }
    if (map.entries.isEmpty()) return
    val latestMap by rememberUpdatedState(map)
    val reportSeek by rememberUpdatedState(onSeek)
    val preferences = rememberPocketGlassPreferences()
    val motion = preferences.animatorsEnabled && !preferences.highContrast && !preferences.batterySaver
    var expanded by remember(listState) { mutableStateOf(false) }
    val latestExpanded by rememberUpdatedState(expanded)
    var held by remember(listState) { mutableStateOf(false) }
    var interaction by remember(listState) { mutableLongStateOf(0L) }
    val expansion by animateFloatAsState(
        if (expanded) 1f else 0f,
        if (motion) tween(180) else snap(), label = "conversation-minimap-width",
    )
    val requests = remember(listState) { Channel<MinimapTarget>(Channel.CONFLATED) }
    DisposableEffect(requests) { onDispose { requests.close() } }
    val scrolling by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
    LaunchedEffect(expanded, held, interaction, scrolling) {
        if (expanded && !held && !scrolling) {
            val idleInteraction = interaction
            delay(MinimapIdleMillis)
            // A pointer can arrive before recomposition cancels this older idle effect (also
            // when the test clock advances past its deadline). Check live state after delay;
            // collapsing a held rail would resize it and synthesize a pointer cancellation.
            if (!held && !scrolling && interaction == idleInteraction) expanded = false
        }
    }
    LaunchedEffect(listState, requests) {
        requests.receiveAsFlow().collectLatest { target ->
            // Resolve immediately before each scroll, never retain a numeric list index across
            // suspension or pagination. A removed/folded target is a no-op, not an index fallback.
            val index = latestMap.listIndex(target.key) ?: return@collectLatest
            reportSeek()
            listState.scrollToItem(index)
            if (target.withinRow > 0f) {
                val measured = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == target.key }
                val offset = measured?.let { (it.size * target.withinRow).toInt().coerceIn(0, (it.size - 1).coerceAtLeast(0)) } ?: 0
                val resolved = latestMap.listIndex(target.key) ?: return@collectLatest
                if (offset > 0) listState.scrollToItem(resolved, offset)
            }
            // Direct, cancellable seeks avoid an index-based body animation landing on a different
            // row after insertion. Only the minimap's reveal animates; it has no idle frame loop.
        }
    }
    val viewport by remember(map, listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            map.viewport(info.visibleItemsInfo.map { MinimapVisibleRow(it.key, it.offset, it.size) },
                maxOf(0, info.viewportStartOffset), info.viewportEndOffset - info.afterContentPadding,
                info.reverseLayout)
        }
    }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val insetPx = with(density) { MinimapInset.toPx() }
    val minimumThumbPx = with(density) { MinimapMinimumThumb.toPx() }

    BoxWithConstraints(modifier.width(20.dp + (MinimapWidth - 20.dp) * expansion).fillMaxHeight().clipToBounds()
        .testTag("conversation-minimap")
        .semantics(mergeDescendants = true) {
            contentDescription = "对话缩略图"
            stateDescription = if (expanded) "已展开" else "已收起"
            progressBarRangeInfo = ProgressBarRangeInfo(viewport?.start ?: 0f, 0f..1f)
            onClick(if (expanded) "收起对话缩略图" else "展开对话缩略图") {
                expanded = !expanded
                interaction++
                true
            }
            setProgress { fraction ->
                val target = latestMap.targetAt(fraction)
                if (target == null) false else {
                    expanded = true
                    interaction++
                    requests.trySend(target).isSuccess
                }
            }
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.Enter, Key.Spacebar -> { expanded = !expanded; interaction++; true }
                Key.DirectionUp, Key.DirectionDown, Key.MoveHome, Key.MoveEnd -> {
                    val current = viewport?.start ?: 0f
                    val step = 1f / latestMap.entries.size
                    val fraction = when (event.key) {
                        Key.MoveHome -> 0f
                        Key.MoveEnd -> 1f
                        Key.DirectionUp -> current - step
                        else -> current + step
                    }
                    latestMap.targetAt(fraction)?.let { requests.trySend(it) }
                    expanded = true
                    interaction++
                    true
                }
                else -> false
            }
        }.focusable()
        .pointerInput(listState) {
            awaitEachGesture {
                val down = awaitFirstDown()
                // Freeze the gesture's row identities, but resolve their indices against latestMap.
                val gestureMap = latestMap
                val wasExpanded = latestExpanded
                val trackInset = insetPx.coerceAtMost(size.height / 4f)
                val trackHeight = (size.height - 2f * trackInset).coerceAtLeast(1f)
                fun fraction(y: Float) = ((y - trackInset) / trackHeight).coerceIn(0f, 1f)
                val info = listState.layoutInfo
                val initialViewport = gestureMap.viewport(
                    info.visibleItemsInfo.map { MinimapVisibleRow(it.key, it.offset, it.size) },
                    maxOf(0, info.viewportStartOffset), info.viewportEndOffset - info.afterContentPadding, info.reverseLayout)
                val thumb = minimapThumb(initialViewport, size.height.toFloat(), trackInset, minimumThumbPx)
                val grabbedThumb = thumb != null && down.position.y >= thumb.top && down.position.y <= thumb.top + thumb.height
                val origin = if (grabbedThumb) initialViewport!!.start else fraction(down.position.y)
                var dragged = false
                held = true
                expanded = true
                interaction++
                down.consume()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                            break
                        }
                        val delta = change.position.y - down.position.y
                        if (!dragged && abs(delta) > viewConfiguration.touchSlop) dragged = true
                        if (dragged) {
                            gestureMap.targetAt(origin + delta / trackHeight)?.let { requests.trySend(it) }
                        }
                        change.consume()
                        if (!change.pressed) {
                            val inside = change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()
                            if (!dragged && inside && wasExpanded && !grabbedThumb) {
                                gestureMap.targetAt(fraction(change.position.y))?.let { requests.trySend(it) }
                            }
                            break
                        }
                    }
                } finally {
                    held = false
                    interaction++
                }
            }
        }) {
        val heightPx = with(density) { maxHeight.toPx() }
        val thumb = minimapThumb(viewport, heightPx, insetPx, minimumThumbPx)
        val visualWidth = MinimapIdleWidth + (MinimapWidth - MinimapIdleWidth) * expansion
        Canvas(Modifier.fillMaxSize()) {
            val inset = insetPx.coerceAtMost(size.height / 4f)
            val trackHeight = (size.height - inset * 2f).coerceAtLeast(0f)
            val width = visualWidth.toPx()
            // At most 160 samples, three tiny strokes each; no transcript text composables/images.
            val paintLimit = (trackHeight / 4.dp.toPx()).toInt().coerceIn(0, MinimapTickLimit)
            val samples = minimapSampleIndices(map.ticks.size, paintLimit)
            val band = trackHeight / samples.size.coerceAtLeast(1)
            for (sample in samples) {
                val tick = map.ticks[sample]
                val tint = when (tick.kind) {
                    TranscriptKind.User -> colors.primary
                    TranscriptKind.Error -> colors.error
                    else -> colors.onSurfaceVariant
                }.copy(alpha = if (preferences.highContrast) .85f else .48f)
                val lineGap = minOf(2.dp.toPx(), band / 4f)
                tick.widths.forEachIndexed { line, length ->
                    val y = inset + trackHeight * tick.fraction + (line - (tick.widths.size - 1) / 2f) * lineGap
                    val start = 2.dp.toPx()
                    val end = start + (width - 4.dp.toPx()).coerceAtLeast(0f) * length
                    drawLine(tint, Offset(start, y.coerceIn(inset, inset + trackHeight)),
                        Offset(end, y.coerceIn(inset, inset + trackHeight)), 1.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        if (thumb != null) {
            PocketGlassSurface(
                modifier = Modifier.offset { IntOffset(0, thumb.top.roundToInt()) }
                    .width(visualWidth).height(with(density) { thumb.height.toDp() })
                    .testTag("conversation-minimap-thumb"),
                shape = RoundedCornerShape(5.dp), backdrop = backdrop,
            ) { }
        }
    }
}

internal data class MinimapTarget(val key: String, val withinRow: Float)
internal data class MinimapVisibleRow(val key: Any, val offset: Int, val size: Int)
internal data class MinimapViewport(val start: Float, val end: Float)
internal data class MinimapThumb(val top: Float, val height: Float)
internal data class MinimapEntry(val key: String, val rowIndex: Int, val kind: TranscriptKind)
internal data class MinimapTick(val fraction: Float, val kind: TranscriptKind, val widths: List<Float>)

/** Pure key/geometry mapping. The only retained rows are the parent's current folded snapshot. */
internal class ConversationMinimapMap(rows: List<TranscriptRow>, keys: List<String>) {
    private val indices = buildMap {
        val duplicate = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        keys.forEachIndexed { index, key -> if (key !in duplicate) put(key, index) }
    }
    val entries: List<MinimapEntry> = run {
        val counts = rows.groupingBy { it.key }.eachCount()
        rows.mapIndexedNotNull { index, row ->
            if (row.key in indices && counts[row.key] == 1) MinimapEntry(row.key, index, row.kind) else null
        }
    }
    private val slots = entries.mapIndexed { slot, row -> row.key to slot }.toMap()
    val ticks: List<MinimapTick> = minimapSampleIndices(entries.size, MinimapTickLimit).map { slot ->
        val entry = entries[slot]
        val row = rows[entry.rowIndex]
        // Inspect at most 192 characters of ONE item per sampled row. Never expand a process,
        // scan a complete body, render Markdown, or access an attachment/network source.
        val prefix = (if (row.kind == TranscriptKind.Process) row.processLabel.orEmpty()
            else row.items.firstOrNull()?.text.orEmpty()).take(192)
        val widths = prefix.lineSequence().take(3).map { (it.length / 64f).coerceIn(.25f, 1f) }.toList().ifEmpty { listOf(.5f) }
        MinimapTick((slot + .5f) / entries.size, entry.kind, widths)
    }

    fun listIndex(key: String): Int? = if (key in slots) indices[key] else null

    fun targetAt(fraction: Float): MinimapTarget? {
        if (entries.isEmpty() || !fraction.isFinite()) return null
        val position = fraction.coerceIn(0f, 1f).toDouble() * entries.size
        val slot = floor(position).toInt().coerceIn(0, entries.lastIndex)
        return MinimapTarget(entries[slot].key, (position - slot).toFloat().coerceIn(0f, .999999f))
    }

    fun viewport(visible: List<MinimapVisibleRow>, start: Int, end: Int, reverse: Boolean = false): MinimapViewport? {
        if (entries.isEmpty() || end <= start) return null
        var first = Float.POSITIVE_INFINITY
        var last = Float.NEGATIVE_INFINITY
        for (item in visible) {
            val slot = slots[item.key] ?: continue
            if (item.size <= 0 || item.offset.toLong() + item.size <= start || item.offset >= end) continue
            val clippedStart = ((start.toDouble() - item.offset) / item.size).toFloat().coerceIn(0f, 1f)
            val clippedEnd = ((end.toDouble() - item.offset) / item.size).toFloat().coerceIn(0f, 1f)
            val from = if (reverse) 1f - clippedEnd else clippedStart
            val to = if (reverse) 1f - clippedStart else clippedEnd
            first = minOf(first, (slot + from) / entries.size)
            last = maxOf(last, (slot + to) / entries.size)
        }
        return if (first.isFinite() && last > first) MinimapViewport(first, last) else null
    }
}

internal fun minimapThumb(viewport: MinimapViewport?, height: Float, inset: Float, minimum: Float): MinimapThumb? {
    if (viewport == null || !height.isFinite() || height <= 0f || !viewport.start.isFinite() || !viewport.end.isFinite()) return null
    val padding = inset.coerceIn(0f, height / 4f)
    val track = height - padding * 2f
    val thumbHeight = ((viewport.end - viewport.start).coerceIn(0f, 1f) * track).coerceIn(minimum.coerceIn(0f, track), track)
    val top = (padding + viewport.start.coerceIn(0f, 1f) * track).coerceIn(padding, height - padding - thumbHeight)
    return MinimapThumb(top, thumbHeight)
}

internal fun minimapSampleIndices(count: Int, limit: Int): List<Int> {
    if (count <= 0 || limit <= 0) return emptyList()
    val size = minOf(count, limit)
    if (size == 1) return listOf((count - 1) / 2)
    return List(size) { (it.toLong() * (count - 1) / (size - 1)).toInt() }
}
