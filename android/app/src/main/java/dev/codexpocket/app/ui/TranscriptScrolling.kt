package dev.codexpocket.app.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

@Stable
internal class TranscriptGestureState {
    var contact by mutableStateOf(false)
    var released by mutableStateOf(false)
    var dragging = false
    var movedTowardTail = false
    var tailItemKey: Any? = null
    var tailItemCount = 0
    fun cancelResume() { released = false; movedTowardTail = false; tailItemKey = null; tailItemCount = 0 }
}

/** Only user dragging changes follow intent. Programmatic seeks never feed back into it. */
@Composable
internal fun ObserveTranscriptDrag(list: LazyListState, onFollow: (Boolean) -> Unit) {
    val callback by rememberUpdatedState(onFollow)
    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) callback(false) } }
}

@Composable
internal fun ObserveTranscriptDrag(list: LazyListState, gesture: TranscriptGestureState, onFollow: (Boolean) -> Unit) {
    val callback by rememberUpdatedState(onFollow)
    LaunchedEffect(list, gesture) {
        kotlinx.coroutines.coroutineScope {
            launch {
                list.interactionSource.interactions.collect { interaction ->
                    if (interaction is DragInteraction.Start && gesture.contact) { gesture.dragging = true; callback(false) }
                    if (interaction is DragInteraction.Cancel) gesture.cancelResume()
                }
            }
            snapshotFlow { listOf(gesture.released, gesture.contact, list.isScrollInProgress, list.canScrollForward) }
                .distinctUntilChanged().collect {
                    if (gesture.released && !gesture.contact && !list.isScrollInProgress) {
                        // Consume this gesture once. A later layout shrink/seek cannot adopt it.
                        gesture.released = false
                        val info = list.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()
                        if (gesture.movedTowardTail && gesture.tailItemKey != null && gesture.tailItemCount == info.totalItemsCount &&
                            last != null && last.key == gesture.tailItemKey && last.index == info.totalItemsCount - 1 &&
                            last.offset + last.size + info.afterContentPadding <= info.viewportEndOffset && !list.canScrollForward) callback(true)
                        gesture.movedTowardTail = false
                    }
                }
        }
    }
}

/** Observe without consuming: tool bodies and boundary overscroll are user gestures too. */
internal fun Modifier.transcriptUserGesture(list: LazyListState, gesture: TranscriptGestureState,
    onDrag: () -> Unit, onContact: (Boolean) -> Unit): Modifier = composed {
    val drag by rememberUpdatedState(onDrag)
    val contact by rememberUpdatedState(onContact)
    val connection = remember(list, gesture) {
        object : NestedScrollConnection {
            private var beforeIndex = 0
            private var beforeOffset = 0
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // SideEffect deltas count only during this released user's fling; explicit
                // seeks cancel that ticket and cannot supply evidence for resuming follow.
                if (source == NestedScrollSource.UserInput || gesture.released) {
                    beforeIndex = list.firstVisibleItemIndex
                    beforeOffset = list.firstVisibleItemScrollOffset
                }
                if (source == NestedScrollSource.UserInput && gesture.contact && available.y != 0f) {
                    gesture.dragging = true
                    drag()
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if ((source == NestedScrollSource.UserInput && gesture.contact) || gesture.released) {
                    val movedForward = list.firstVisibleItemIndex > beforeIndex ||
                        (list.firstVisibleItemIndex == beforeIndex && list.firstVisibleItemScrollOffset > beforeOffset)
                    // Actual transcript consumption is required. A tool's own scroll or a
                    // boundary overscroll does not mean the reader asked to follow the tail.
                    if (movedForward && consumed.y < 0f) {
                        gesture.movedTowardTail = true
                        val info = list.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()
                        if (last != null && last.index == info.totalItemsCount - 1 && !list.canScrollForward &&
                            last.offset + last.size + info.afterContentPadding <= info.viewportEndOffset) {
                            gesture.tailItemKey = last.key
                            gesture.tailItemCount = info.totalItemsCount
                        }
                    } else if (consumed.y != 0f || available.y > 0f) {
                        gesture.movedTowardTail = false
                        gesture.tailItemKey = null
                    }
                }
                return Offset.Zero
            }
        }
    }
    this.nestedScroll(connection).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gesture.contact = true
            gesture.dragging = false
            gesture.cancelResume()
            contact(true)
            try {
                var dragging = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (pointer != null && !dragging && abs(pointer.position.y - down.position.y) > viewConfiguration.touchSlop) {
                        dragging = true
                        gesture.dragging = true
                        drag()
                    }
                } while (event.changes.any { it.pressed })
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                gesture.cancelResume()
                throw cancelled
            } finally {
                gesture.released = gesture.dragging && gesture.movedTowardTail
                gesture.contact = false
                contact(false)
            }
        }
    }
}

/** Follow is explicit reader intent. Layout/boundary measurements can never enable it. */
@Composable
internal fun FollowTranscriptEnd(list: LazyListState, following: Boolean, blocked: Boolean = false) =
    FollowTranscriptEnd(list, { following }, { blocked })

@Composable
internal fun FollowTranscriptEnd(list: LazyListState, following: () -> Boolean, blocked: () -> Boolean = { false }) {
    val follow by rememberUpdatedState(following)
    val block by rememberUpdatedState(blocked)
    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            listOf(follow(), block(), info.totalItemsCount, last?.key, last?.offset, last?.size,
                info.viewportEndOffset, info.afterContentPadding, list.canScrollForward, list.isScrollInProgress)
        }.distinctUntilChanged().collectLatest {
            // Native layout can report intermediate end offsets repeatedly in
            // the same frame. Yield before seeking so follow cannot monopolize
            // the main dispatcher or race a pending pointer/layout update.
            withFrameNanos { }
            // Read intent synchronously again before entering the scroll mutator. A nested
            // tool drag can leave the outer list idle; contact blocks it before touch slop.
            if (follow() && !block() && list.canScrollForward && !list.isScrollInProgress && list.layoutInfo.totalItemsCount > 1) {
                list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
            }
        }
    }
}
