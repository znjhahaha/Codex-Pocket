package dev.codexpocket.app.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.delay

/** Only user dragging changes follow intent. Programmatic seeks never feed back into it. */
@Composable
internal fun ObserveTranscriptDrag(list: LazyListState, onFollow: (Boolean) -> Unit) {
    val callback by rememberUpdatedState(onFollow)
    LaunchedEffect(list) {
        list.interactionSource.interactions.collectLatest { interaction ->
            if (interaction is DragInteraction.Start) callback(false)
        }
    }
}

/** Layout changes may follow the end only if something remains below it. */
@Composable
internal fun FollowTranscriptEnd(list: LazyListState, following: Boolean, blocked: Boolean = false) {
    LaunchedEffect(list, following, blocked) {
        if (!following || blocked) return@LaunchedEffect
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            listOf(info.totalItemsCount, last?.key, last?.offset, last?.size, info.viewportEndOffset, info.afterContentPadding, list.canScrollForward)
        }.distinctUntilChanged().conflate().collect {
            if (list.canScrollForward && !list.isScrollInProgress && list.layoutInfo.totalItemsCount > 1) {
                delay(50)
                if (list.canScrollForward && !list.isScrollInProgress) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
            }
        }
    }
}
