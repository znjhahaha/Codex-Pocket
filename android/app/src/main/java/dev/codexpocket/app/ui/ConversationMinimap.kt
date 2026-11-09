package dev.codexpocket.app.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.codexpocket.app.data.TranscriptKind
import dev.codexpocket.app.data.TranscriptRow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val MinimapInset = 8.dp
private val MinimapMinimumThumb = 44.dp
private data class MinimapSeekRequest(val target: MinimapTarget?, val flush: Boolean, val serial: Long, val intent: Long,
    val commitHidden: Boolean = false)

/**
 * Loaded folded overview. Share [state] with the host and reserve [ConversationMinimapState.reservedWidth]
 * in the transcript's measured constraints (24dp collapsed, 48dp expanded), not in content padding.
 * [keys] is the complete ordered LazyColumn key snapshot including chrome. Keep this rail below the
 * header and above the composer/IME. Its width and the host's reservation use the same 240ms motion.
 *
 * Capture only the transcript LazyColumn into [backdrop]. The rail and its transient press preview
 * must be siblings outside that capture. Samples include loaded hidden process children; the
 * overview itself never expands the transcript. [scope] identifies the native conversation and
 * [navigationEpoch] changes on pagination/reset. [onExpandProcess] must add, never toggle, an owner.
 * [onSeek] fires before an expansion/scroll and never restores the host's following intent.
 * [ConversationMinimapState.isInteracting] includes the queued final release, for host end detection.
 */
@Composable
internal fun ConversationMinimap(
    rows: List<TranscriptRow>,
    keys: List<String>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalPocketBackdrop.current,
    onSeek: () -> Unit = {},
    state: ConversationMinimapState = rememberConversationMinimapState(listState),
    scope: String = "",
    navigationEpoch: Any? = null,
    onExpandProcess: (String) -> Unit = {},
) {
    val samples = remember(listState, scope) { MinimapSampleCache() }
    val map = remember(rows, keys, samples) { ConversationMinimapMap(rows, keys, samples) }
    val latestMap by rememberUpdatedState(map)
    val latestKeys by rememberUpdatedState(keys)
    val reportSeek by rememberUpdatedState(onSeek)
    val expandProcess by rememberUpdatedState(onExpandProcess)
    val preferences = rememberPocketGlassPreferences()
    val requests = remember(listState, state, scope, navigationEpoch) { Channel<MinimapSeekRequest>(Channel.CONFLATED) }
    var requestSerial by remember(requests) { mutableLongStateOf(0L) }
    var intentSerial by remember(requests) { mutableLongStateOf(0L) }
    fun request(target: MinimapTarget?, flush: Boolean = false, commitHidden: Boolean = flush) {
        if (state.navigationToken !== requests) return
        requestSerial++
        state.seeking = target != null
        state.seekKey = target?.key
        requests.trySend(MinimapSeekRequest(target, flush, requestSerial, intentSerial, commitHidden))
    }
    DisposableEffect(requests) {
        state.navigationToken = requests
        onDispose {
            requests.close()
            if (state.navigationToken === requests) {
                state.navigationToken = null
                state.seeking = false; state.seekKey = null; state.held = false; state.previewKey = null; state.focused = false
            }
        }
    }
    LaunchedEffect(map, state, requests) {
        if (map.entries.isEmpty()) { request(null); state.collapse() }
        else {
            if (state.previewKey != null && map.slot(state.previewKey!!) == null) state.previewKey = null
            if (state.seekKey != null && map.slot(state.seekKey!!) == null) request(null)
        }
    }
    LaunchedEffect(listState, requests) {
        val coalescer = MinimapSeekCoalescer()
        var activeIntent = -1L
        requests.receiveAsFlow().collectLatest { request ->
            var dispatched: MinimapTarget? = null
            var finished = false
            try {
                if (request.intent != activeIntent) {
                    coalescer.cancel()
                    activeIntent = request.intent
                }
                val requested = request.target
                if (requested == null) { coalescer.cancel(); return@collectLatest }
                // While dragging, a hidden item targets its folded process header.
                // Release must still commit the expansion even for the same native key.
                if (request.commitHidden && latestMap.seekPlan(requested, true)?.expandProcessKey != null)
                    coalescer.abandon(requested)
                var target = coalescer.offer(requested, SystemClock.uptimeMillis(), request.flush)
                if (target == null) {
                    delay(coalescer.waitMillis(SystemClock.uptimeMillis()))
                    target = coalescer.take(SystemClock.uptimeMillis())
                }
                val resolvedTarget = target ?: return@collectLatest
                dispatched = resolvedTarget
                // Resolve after the cadence delay and again after measurement. Never retain an
                // index through pagination/reflow or substitute another row for a folded target.
                val plan = latestMap.seekPlan(resolvedTarget, request.commitHidden) ?: return@collectLatest
                reportSeek()
                if (plan.expandProcessKey != null) {
                    expandProcess(plan.expandProcessKey)
                    // Projection is asynchronous. Wait until its exact key snapshot has reached
                    // LazyColumn measurement before scrolling; never guess an old numeric index.
                    val ready = withTimeoutOrNull(2_000L) {
                        snapshotFlow { latestMap.slot(resolvedTarget.key) == null ||
                            (latestMap.listIndex(resolvedTarget.key) != null &&
                                listState.layoutInfo.totalItemsCount == latestKeys.size) }.first { it }
                    }
                    if (ready != true) return@collectLatest
                }
                val index = latestMap.listIndex(plan.scrollKey) ?: return@collectLatest
                listState.scrollToItem(index)
                val measured = withTimeoutOrNull(2_000L) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == plan.scrollKey } }.first { it != null }
                } ?: return@collectLatest
                if (resolvedTarget.withinRow > 0f && plan.scrollKey == resolvedTarget.key) {
                    val offset = (measured.size * resolvedTarget.withinRow).toInt()
                        .coerceIn(0, (measured.size - 1).coerceAtLeast(0))
                    val currentIndex = latestMap.listIndex(plan.scrollKey) ?: return@collectLatest
                    if (offset > 0) listState.scrollToItem(currentIndex, offset)
                }
                finished = true
            } finally {
                // Release can cancel an identical request before its offset is applied.
                if (!finished) dispatched?.let(coalescer::abandon)
                if (state.navigationToken === requests && request.serial == requestSerial) { state.seeking = false; state.seekKey = null }
            }
        }
    }
    if (map.entries.isEmpty()) return
    val viewport by remember(map, listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            map.viewport(info.visibleItemsInfo.map { MinimapVisibleRow(it.key, it.offset, it.size) },
                maxOf(0, info.viewportStartOffset), info.viewportEndOffset - info.afterContentPadding, info.reverseLayout)
        }
    }
    val latestViewport by rememberUpdatedState(viewport)
    val density = LocalDensity.current
    val insetPx = with(density) { MinimapInset.toPx() }
    val minimumThumbPx = with(density) { MinimapMinimumThumb.toPx() }
    val colors = MaterialTheme.colorScheme
    val railBackground = colors.background
    val inkBackdrop = rememberLayerBackdrop { drawRect(railBackground); drawContent() }
    var railHeight by remember { mutableIntStateOf(0) }
    val expandedMarks = remember(map, railHeight, density) {
        minimapMarks(map, null, with(density) { 48.dp.toPx() }, railHeight.toFloat(), density.density, expanded = true)
    }
    val latestExpandedMarks by rememberUpdatedState(expandedMarks)

    BoxWithConstraints(modifier.width(state.reservedWidth).fillMaxHeight().clipToBounds()
        .onSizeChanged { railHeight = it.height }
        .testTag("conversation-minimap")
        .semantics(mergeDescendants = true) {
            contentDescription = "对话缩略图"
            stateDescription = if (state.expanded) "已展开" else "已收起"
            progressBarRangeInfo = ProgressBarRangeInfo(viewport?.start ?: 0f, 0f..1f)
            onClick(if (state.expanded) "收起对话缩略图" else "展开对话缩略图") {
                if (state.expanded) state.collapse() else state.expand()
                true
            }
            setProgress { fraction ->
                val target = latestMap.targetAt(fraction)
                if (target == null) false else {
                    intentSerial++
                    state.expand()
                    request(target, flush = true)
                    true
                }
            }
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.Enter, Key.Spacebar -> { if (state.expanded) state.collapse() else state.expand(); true }
                Key.Escape -> { state.collapse(); true }
                Key.DirectionUp, Key.DirectionDown, Key.MoveHome, Key.MoveEnd -> {
                    intentSerial++
                    val current = latestViewport?.start ?: 0f
                    val step = 1f / latestMap.entries.size
                    val fraction = when (event.key) {
                        Key.MoveHome -> 0f
                        Key.MoveEnd -> 1f
                        Key.DirectionUp -> current - step
                        else -> current + step
                    }
                    state.expand()
                    request(latestMap.targetAt(fraction), flush = true)
                    true
                }
                else -> false
            }
        }
        .onFocusChanged { state.focused = it.hasFocus; state.touch() }
        .focusable()
        .pointerInput(listState, state, density, railHeight, scope, navigationEpoch) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val gestureMap = latestMap
                val wasExpanded = state.expanded
                val initialViewport = latestViewport
                val trackInset = insetPx.coerceAtMost(size.height / 4f)
                val trackHeight = (size.height - 2f * trackInset).coerceAtLeast(1f)
                fun fraction(y: Float) = ((y - trackInset) / trackHeight).coerceIn(0f, 1f)
                val thumb = minimapThumb(initialViewport, size.height.toFloat(), trackInset, minimumThumbPx)
                val grabbedThumb = wasExpanded && thumb != null && down.position.y in thumb.top..(thumb.top + thumb.height)
                val grabOffset = if (grabbedThumb) down.position.y - thumb!!.top else 0f
                val tick = if (wasExpanded) minimapTickAt(latestExpandedMarks, density.density, down.position.y)
                    ?.let { gestureMap.targetForKey(it.key) } else null
                var dragged = false
                var previewShown = false
                var completed = false
                var lastTarget: MinimapTarget? = null
                val pressedAt = SystemClock.uptimeMillis()
                state.held = true
                intentSerial++
                request(null)
                state.touch()
                // Touch is the Codex-style inspection gesture. Width only changes after a tap
                // has completed, never under a held finger or during a collapsed scrub.
                fun previewAt(y: Float) {
                    val target = gestureMap.userTargetAt(fraction(y), 10f * density.density / trackHeight)
                    state.previewKey = target?.key?.takeIf { latestMap.slot(it) != null }
                    state.previewFraction = fraction(y)
                }
                if (!wasExpanded) previewAt(down.position.y)
                down.consume()
                try {
                    while (true) {
                        val elapsed = (SystemClock.uptimeMillis() - pressedAt).coerceAtLeast(0)
                        val event = if (wasExpanded && tick != null && !dragged && !previewShown) {
                            withTimeoutOrNull((viewConfiguration.longPressTimeoutMillis - elapsed).coerceAtLeast(1)) { awaitPointerEvent() }
                        } else awaitPointerEvent()
                        if (event == null) {
                            if (latestMap.slot(tick!!.key) != null) {
                                state.previewKey = tick.key
                                state.previewFraction = fraction(down.position.y)
                            }
                            previewShown = true
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) break
                        val delta = change.position - down.position
                        if (!dragged && delta.getDistance() > viewConfiguration.touchSlop) {
                            dragged = true
                            if (wasExpanded) state.previewKey = null
                        }
                        if (!wasExpanded && change.pressed) previewAt(change.position.y)
                        if (wasExpanded && dragged) {
                            val targetFraction = if (grabbedThumb) minimapDragFraction(initialViewport!!, thumb!!,
                                size.height.toFloat(), trackInset, grabOffset, change.position.y) else fraction(change.position.y)
                            lastTarget = gestureMap.targetAt(targetFraction)
                            state.previewKey = lastTarget?.key?.takeIf { latestMap.listIndex(it) == null && latestMap.slot(it) != null }
                            state.previewFraction = targetFraction
                            request(lastTarget, flush = !change.pressed)
                        }
                        change.consume()
                        if (!change.pressed) {
                            val inside = change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()
                            val hiddenTick = tick?.key?.let { gestureMap.listIndex(it) == null } == true
                            if (wasExpanded && !dragged && inside && (!grabbedThumb || hiddenTick) && (!previewShown || hiddenTick)) {
                                // A held landmark remains the same native key even if streaming
                                // changes its rail fraction before release.
                                request(if (hiddenTick) tick else gestureMap.targetAt(fraction(change.position.y)), flush = true)
                            }
                            if (!wasExpanded && inside) {
                                // Event timestamps describe the finger, whereas dispatch time can
                                // include a slow layout/frame and turn an ordinary tap into a hold.
                                val longPress = change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                                if (dragged || longPress) {
                                    lastTarget = state.previewKey?.let { gestureMap.targetForKey(it) }
                                    request(lastTarget, flush = true)
                                } else state.expand()
                            }
                            completed = true
                            break
                        }
                    }
                } finally {
                    // Busy stays set through a completed drag's queued release flush.
                    if (!completed && lastTarget != null) request(null)
                    if (state.navigationToken === requests) {
                        state.previewKey = null
                        state.held = false
                        state.touch()
                    }
                }
            }
        }) {
        val heightPx = with(density) { maxHeight.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val expansion = state.expansion.value.coerceIn(0f, 1f)
        val dark = colors.background.luminance() < .3f
        val accent = if (dark) Color(0xFFD5AF76) else Color(0xFFA57E44)
        val activeThumb = state.held && state.expanded
        val thumb = minimapThumb(viewport, heightPx, insetPx, minimumThumbPx)
        val collapsedWidth = minOf(widthPx, with(density) { 24.dp.toPx() })
        val collapsedMarks = remember(map, heightPx, collapsedWidth, density) {
            minimapMarks(map, null, collapsedWidth, heightPx, density.density, expanded = false)
        }
        // Cache normalized expanded geometry: the width animation scales it without recreating
        // every mark on each frame, and scrolling only redraws the current tint/thumb.
        Canvas(Modifier.fillMaxSize().layerBackdrop(inkBackdrop)) {
            fun paint(marks: List<MinimapMark>, opacity: Float, expanded: Boolean) {
                if (opacity <= 0f) return
                val currentRow = viewport?.let { map.targetAt(it.start)?.key }
                val currentKey = if (expanded) currentRow else map.currentQuestion(currentRow)
                val widthScale = if (expanded) size.width / with(density) { 48.dp.toPx() } else 1f
                for (mark in marks) {
                    val inspected = mark.key == state.previewKey
                    val current = mark.key == currentKey
                    val inWindow = thumb != null && mark.top in thumb.top..(thumb.top + thumb.height)
                    val alpha = if (preferences.highContrast) {
                        if (current) 1f else .72f
                    } else when {
                        inspected || current -> .90f
                        !expanded -> if (mark.kind == TranscriptKind.User) .38f else .16f
                        mark.ink == MinimapInk.Question -> .82f
                        mark.ink == MinimapInk.Added || mark.ink == MinimapInk.Removed -> .72f
                        mark.ink == MinimapInk.Strong -> .56f
                        mark.ink == MinimapInk.Faint -> .24f
                        else -> .36f
                    }
                    val ink = if (!expanded || preferences.highContrast) colors.onSurface else when (mark.ink) {
                        MinimapInk.Question -> accent
                        MinimapInk.Added -> if (dark) Color(0xFF75C791) else Color(0xFF34834E)
                        MinimapInk.Removed -> if (dark) Color(0xFFE58E8E) else Color(0xFFBB5757)
                        else -> colors.onSurface
                    }
                    val emphasis = if (expanded && inWindow && mark.ink != MinimapInk.Question) 1.04f else 1f
                    val left = mark.left * widthScale
                    val length = if (!expanded && (current || inspected)) with(density) { 15.dp.toPx() } else mark.width * widthScale * emphasis
                    // Question marks travel from equal Codex spacing to the length-weighted map;
                    // replies fade in in place. No parsing/allocation is needed for the transition.
                    val inset = insetPx.coerceAtMost(size.height / 4f)
                    val track = size.height - 2f * inset
                    val questionFraction = map.questionFraction(mark.key, 10f * density.density / track)
                    val y = if (questionFraction != null) {
                        val collapsedY = inset + questionFraction * track - mark.height / 2f
                        val expandedY = if (expanded) mark.top else map.slot(mark.key)?.let { inset + map.tick(it).fraction * track - mark.height / 2f } ?: mark.top
                        collapsedY + (expandedY - collapsedY) * expansion
                    } else mark.top
                    drawRoundRect(ink.copy(alpha = (alpha * opacity).coerceAtMost(1f)), Offset(left, y),
                        Size(length.coerceAtMost(size.width - left), mark.height), CornerRadius(mark.height / 2f))
                }
            }
            paint(collapsedMarks, 1f - expansion, false)
            paint(expandedMarks, expansion, true)
        }
        if (thumb != null && expansion > 0f) {
            val thumbInset = minOf(5.dp, maxWidth / 4)
            MinimapGlassThumb(inkBackdrop, activeThumb, expansion,
                Modifier.offset { IntOffset(with(density) { thumbInset.roundToPx() }, thumb.top.roundToInt()) }
                    .width(minOf(30.dp, (maxWidth - thumbInset * 2).coerceAtLeast(0.dp)))
                    .height(with(density) { thumb.height.toDp() }).testTag("conversation-minimap-thumb"))
        }
        val preview = state.previewKey?.let(map::preview)
        if (preview != null) MinimapPressPreview(preview, state.previewFraction, backdrop)
    }
}

/** The transient preview is outside transcript capture and does not reserve permanent text width. */
@Composable
private fun MinimapPressPreview(preview: MinimapPreview, fraction: Float, backdrop: Backdrop?) {
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val position = remember(fraction, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right + gap else anchorBounds.left - gap - popupContentSize.width
                val y = anchorBounds.top + (fraction * anchorBounds.height).roundToInt() - popupContentSize.height / 2
                val lower = maxOf(gap, anchorBounds.top)
                val upper = minOf(windowSize.height - gap, anchorBounds.bottom) - popupContentSize.height
                return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(lower.coerceAtMost(upper.coerceAtLeast(0)), upper.coerceAtLeast(0)))
            }
        }
    }
    Popup(popupPositionProvider = position,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        PocketGlassSurface(Modifier.width(240.dp).testTag("conversation-minimap-preview"),
            shape = RoundedCornerShape(12.dp), backdrop = backdrop, dense = true) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("提问：${preview.question}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("邻近：${preview.neighbor}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(preview.position, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
