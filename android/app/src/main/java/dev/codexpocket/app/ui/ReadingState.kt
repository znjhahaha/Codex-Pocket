package dev.codexpocket.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.AnnotatedString

internal data class DiffReadingDocument(val content: String, val diff: Boolean, val lines: List<ReviewLine>, val columns: Int, val numberDigits: Int)

/** Owned by the file, above preview/reader branches, so resizing never creates a new reading session. */
internal class DiffReadingState(index: Int = 0, offset: Int = 0, x: Int = 0, wrap: Boolean = true, contexts: Set<Int> = emptySet()) {
    val list = LazyListState(index, offset)
    val horizontal = ScrollState(x)
    var wrap by mutableStateOf(wrap)
    var contexts by mutableStateOf(contexts)
    // A resize reuses parsing, token context and width. A temporary zero-width pane must not clamp x.
    var document: DiffReadingDocument? = null
    var highlightKey: Any? = null
    var highlighted: List<AnnotatedString>? = null

    companion object {
        val Saver = listSaver<DiffReadingState, Any>(
            save = { listOf(it.list.firstVisibleItemIndex, it.list.firstVisibleItemScrollOffset,
                it.horizontal.value, it.wrap, it.contexts.toList()) },
            restore = { DiffReadingState(it[0] as Int, it[1] as Int, it[2] as Int, it[3] as Boolean,
                (it[4] as List<*>).filterIsInstance<Int>().toSet()) },
        )
    }
}

@Composable
internal fun rememberDiffReadingState(key: String): DiffReadingState =
    rememberSaveable(key, saver = DiffReadingState.Saver) { DiffReadingState() }
