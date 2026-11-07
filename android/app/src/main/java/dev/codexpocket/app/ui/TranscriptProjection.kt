package dev.codexpocket.app.ui

import androidx.compose.runtime.*
import dev.codexpocket.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TranscriptProjectionMemory {
    var rows by mutableStateOf(emptyList<TranscriptRow>())
}
/** Snapshot grouping and process expansion never run on the scrolling/IME thread. */
@Composable
internal fun rememberTranscriptRows(scope: String, timeline: NativeTimeline, expansions: Set<String>,
    blockedTurns: Set<String> = emptySet(), retained: TranscriptProjectionMemory? = null): List<TranscriptRow> {
    val memory = retained ?: remember(scope) { TranscriptProjectionMemory() }
    LaunchedEffect(scope, timeline, expansions, blockedTurns) {
        val previous = memory.rows.associateBy { it.key }
        val next = withContext(Dispatchers.Default) {
            expandTaskRows(ConversationPresentation.rows(timeline, blockedTurns), expansions)
                .map { row -> previous[row.key]?.takeIf { it == row } ?: row }
        }
        // withContext cancellation fences obsolete mid-stream projections.
        memory.rows = next
    }
    return memory.rows
}
