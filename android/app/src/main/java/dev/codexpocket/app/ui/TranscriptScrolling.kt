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
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

@Stable
internal class TranscriptGestureState {
    var contact by mutableStateOf(false)
    var released by mutableStateOf(false)
    var flinging by mutableStateOf(false)
    var dragging = false
    var movedTowardTail = false
    var consumedItemCount = 0
    var epoch by mutableIntStateOf(0)
    fun cancelResume() { epoch++; released = false; flinging = false; movedTowardTail = false; consumedItemCount = 0 }
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
            snapshotFlow {
                val info=list.layoutInfo
                val last=info.visibleItemsInfo.lastOrNull()
                listOf(gesture.epoch, gesture.released, gesture.contact, gesture.flinging, list.isScrollInProgress, list.canScrollForward,
                    info.totalItemsCount, last?.key, last?.offset, last?.size, info.afterContentPadding, info.viewportEndOffset)
            }.distinctUntilChanged().collectLatest {
                    if (gesture.released && !gesture.contact && !gesture.flinging && !list.isScrollInProgress) {
                        val epoch=gesture.epoch
                        // Pointer-up can precede the fling mutator and its final layout.
                        // Wait for stable frames; a new drag/seek cancels this gesture's ticket.
                        repeat(2) { withFrameNanos { } }
                        if (gesture.epoch!=epoch || !gesture.released || gesture.contact || gesture.flinging || list.isScrollInProgress)
                            return@collectLatest
                        val info = list.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()
                        val reachedTail=gesture.movedTowardTail && gesture.consumedItemCount == info.totalItemsCount &&
                            last != null && last.index == info.totalItemsCount - 1 &&
                            last.offset + last.size + info.afterContentPadding <= info.viewportEndOffset + 1 && !list.canScrollForward
                        // Consume once before expanding the composer changes content padding.
                        gesture.cancelResume()
                        if (reachedTail) callback(true)
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
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (gesture.dragging && gesture.movedTowardTail) gesture.flinging = true
                return Velocity.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                gesture.flinging = false
                return Velocity.Zero
            }
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
                    val movedBackward = list.firstVisibleItemIndex < beforeIndex ||
                        (list.firstVisibleItemIndex == beforeIndex && list.firstVisibleItemScrollOffset < beforeOffset)
                    // Actual transcript consumption is required. A tool's own scroll or a
                    // boundary overscroll does not mean the reader asked to follow the tail.
                    if (movedForward && consumed.y < 0f) {
                        gesture.movedTowardTail = true
                        val info = list.layoutInfo
                        // Lazy layout may not expose the final item until after this callback.
                        // Bind movement to this item set, then inspect its settled geometry.
                        gesture.consumedItemCount = info.totalItemsCount
                    } else if (movedBackward || (source == NestedScrollSource.UserInput && gesture.contact && available.y > 0f)) {
                        gesture.movedTowardTail = false
                        gesture.consumedItemCount = 0
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
