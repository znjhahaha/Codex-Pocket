package dev.codexpocket.app.ui

import androidx.compose.runtime.*
import dev.codexpocket.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TranscriptProjectionMemory {
    var rows by mutableStateOf(emptyList<TranscriptRow>())
    internal var scope: String? by mutableStateOf(null)
}
/** Snapshot grouping and process expansion never run on the scrolling/IME thread. */
@Composable
internal fun rememberTranscriptRows(scope: String, timeline: NativeTimeline, expansions: Set<String>,
    blockedTurns: Set<String> = emptySet(), retained: TranscriptProjectionMemory? = null,
    resolvedRequestIds: Set<String> = emptySet(), agentLiveStatuses: Map<String, AgentLiveStatus> = emptyMap()): List<TranscriptRow> {
    val memory = retained ?: remember(scope) { TranscriptProjectionMemory() }
    LaunchedEffect(scope, timeline, expansions, blockedTurns, resolvedRequestIds, agentLiveStatuses) {
        val previous = if (memory.scope == scope) memory.rows.associateBy { it.key } else emptyMap()
        val next = withContext(Dispatchers.Default) {
            expandTaskRows(ConversationPresentation.rows(timeline, blockedTurns, resolvedRequestIds = resolvedRequestIds,
                agentLiveStatuses = agentLiveStatuses), expansions)
                .map { row -> previous[row.key]?.takeIf { it == row } ?: row }
        }
        // withContext cancellation fences obsolete mid-stream projections.
        memory.rows = next
        memory.scope = scope
    }
    return memory.rows.takeIf { memory.scope == scope }.orEmpty()
}
