package dev.codexpocket.app.data

import kotlinx.serialization.json.JsonElement

data class AgentReaderState(
    val parent: ThreadKey,
    val child: ChatEntity? = null,
    val timeline: NativeTimeline = NativeTimeline(),
    val loading: Boolean = false,
    val error: String? = null,
    val fullScreen: Boolean = false,
    val updatedAt: Long = 0,
    val nextTurns: JsonElement? = null,
    val liveStatuses: Map<String, AgentLiveStatus> = emptyMap(),
) {
    /** Owner validates identity/parent/runtime before publishing metadata or native status events. */
    fun withLiveStatus(threadId: String, status: AgentLiveStatus): AgentReaderState {
        val next = liveStatuses.withAgentLiveStatus(threadId, status)
        return if (next === liveStatuses) this else copy(liveStatuses = next)
    }

    /** Invoke with the accepted native snapshot/event cursor, never a DAO timestamp or an RPC ticket. */
    fun observeLiveStatus(cursor: Cursor): AgentReaderState {
        val key = child?.key ?: return this
        val status = AgentLiveStatus.fromTimeline(timeline, cursor) ?: return this
        return withLiveStatus(key.nativeId, status)
    }
}
