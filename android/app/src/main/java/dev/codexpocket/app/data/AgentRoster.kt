package dev.codexpocket.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A native child, independent of transcript folding and of the parent's running state. */
data class AgentRosterEntry(
    val threadId: String,
    val name: String = "",
    val status: ActivityStatus = ActivityStatus.Unknown,
    val nativeStatus: String = "",
    val taskPreview: String = "",
    val resultPreview: String = "",
    val isCurrent: Boolean = false,
    val item: TimelineItem? = null,
)

/** State only. The owner keys its cache by confirmed native child IDs within one parent/runtime. */
data class AgentLiveStatus(
    val nativeStatus: String,
    val turnId: String? = null,
    val cursor: Cursor? = null,
    val activeFlags: Set<String> = emptySet(),
    // Idle/notLoaded bounds observation freshness but supplies no turn lifecycle evidence.
    // Keep this bound separately when an earlier explicit terminal turn refines that metadata.
    val metadataWatermark: Cursor? = null,
) {
    val status: ActivityStatus get() {
        val base = when (nativeStatus) {
            "active" -> ActivityStatus.Running
            "systemError" -> ActivityStatus.Failed
            else -> NativeActivityPresentation.status(nativeStatus)
        }
        return if (base == ActivityStatus.Running && activeFlags.any { it in setOf("waitingOnApproval", "waitingOnUserInput") }) ActivityStatus.Waiting else base
    }

    companion object {
        /** Accepts thread/read.thread.status or thread/status/changed.params.status directly. */
        fun fromNativeStatus(status: JsonObject, turnId: String? = null, cursor: Cursor? = null): AgentLiveStatus? {
            val native = status.agentString("type").takeIf(String::isNotBlank) ?: return null
            val flags = status.array("activeFlags").mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }.toSet()
            return AgentLiveStatus(native, turnId, cursor, flags)
        }

        /** A timeline supplies an explicit terminal turn; idle metadata alone proves no completion. */
        fun fromTimeline(timeline: NativeTimeline, cursor: Cursor? = null): AgentLiveStatus? {
            val active = timeline.activeTurn?.takeIf(String::isNotBlank)
            val activeInfo = active?.let { timeline.turns[it] }
            val latest = timeline.turns.values.lastOrNull()
            val terminalActive = activeInfo?.takeIf { NativeActivityPresentation.status(it.status).agentTerminal() }
            val native = when {
                terminalActive != null -> terminalActive.status
                timeline.status == "systemError" -> timeline.status
                timeline.status == "active" -> activeInfo?.status?.takeIf { it == "inProgress" } ?: "active"
                timeline.status in setOf("idle", "notLoaded") -> latest?.status?.takeIf { NativeActivityPresentation.status(it).agentTerminal() } ?: timeline.status
                latest?.status?.isNotBlank() == true -> latest.status
                active != null && active !in timeline.completedTurns -> "inProgress"
                else -> timeline.status
            }
            if (native.isBlank()) return null
            val turnId = when {
                terminalActive != null -> terminalActive.id
                active != null -> active
                timeline.status == "active" -> null // No native identity for a newly active turn yet.
                else -> latest?.id
            }
            return AgentLiveStatus(native, turnId, cursor, timeline.activeFlags)
        }
    }
}

private fun ActivityStatus.agentTerminal() = this in setOf(ActivityStatus.Completed, ActivityStatus.Failed, ActivityStatus.Interrupted)

/** Owner validates read/event freshness; supplied cursors additionally reject duplicate/stale data. */
fun mergeAgentLiveStatus(previous: AgentLiveStatus?, next: AgentLiveStatus): AgentLiveStatus {
    if (previous == null) return next
    val oldCursor = previous.metadataWatermark ?: previous.cursor
    val newCursor = next.cursor
    // Clear the parent/runtime cache only after confirming a replacement epoch. Unversioned reads
    // must not supersede ordered events: the owner can attach their accepted snapshot watermark.
    if (oldCursor != null) {
        if (newCursor == null || oldCursor.epoch != newCursor.epoch) return previous
        if (newCursor.sequence <= oldCursor.sequence) {
            val idleMetadata = previous.nativeStatus in setOf("idle", "notLoaded")
            // A read watermark can precede delivery of the very completion that made it idle.
            // Refine only with an identified native terminal turn in the same epoch, preserving
            // both the higher metadata bound and the actual ordering of terminal evidence.
            val refinesIdle = (idleMetadata || previous.metadataWatermark != null) &&
                next.status.agentTerminal() && !next.turnId.isNullOrBlank() &&
                (idleMetadata || previous.cursor?.let { newCursor.sequence > it.sequence } == true)
            return if (refinesIdle) next.copy(metadataWatermark = oldCursor) else previous
        }
    }
    // A completed native turn cannot run again. A distinct native turn can establish a restart.
    if (previous.status.agentTerminal() && !next.status.agentTerminal() &&
        (next.turnId == null || next.turnId == previous.turnId)) {
        // The reverse arrival order preserves completion and the same metadata freshness bound.
        return if (next.nativeStatus in setOf("idle", "notLoaded") && newCursor != null)
            previous.copy(metadataWatermark = newCursor) else previous
    }
    return next
}

fun Map<String, AgentLiveStatus>.withAgentLiveStatus(threadId: String, next: AgentLiveStatus): Map<String, AgentLiveStatus> {
    if (threadId.isBlank() || next.nativeStatus.isBlank()) return this
    val previous = get(threadId)
    val merged = mergeAgentLiveStatus(previous, next)
    return if (merged == previous) this else this + (threadId to merged)
}

/**
 * NativeTimeline preserves native turn/item order. Only the two documented child record types
 * contribute identities; a missing field is no update, while an explicit future status is Unknown.
 * Native task roots and confirmed Steer parents supply grouping, never a child execution state.
 */
fun agentRoster(timeline: NativeTimeline, liveStatuses: Map<String, AgentLiveStatus> = emptyMap()): List<AgentRosterEntry> {
    val currentTurn = timeline.activeTurn?.takeIf(String::isNotBlank)
        ?: timeline.turns.keys.lastOrNull()
        ?: timeline.items.values.lastOrNull()?.turnId
    val currentRoot = currentTurn?.let { agentTaskRoot(it, timeline.turns) }
    val roster = linkedMapOf<String, AgentRosterEntry>()
    for (item in timeline.items.values) {
        if (item.type !in setOf("collabAgentToolCall", "subAgentActivity")) continue
        val task = if (item.type == "collabAgentToolCall") item.raw.agentString("prompt").agentPreview() else ""
        for (agent in NativeActivityPresentation.agents(item)) {
            val previous = roster[agent.threadId] ?: AgentRosterEntry(agent.threadId)
            val explicitState = agent.nativeStatus.isNotBlank()
            roster[agent.threadId] = previous.copy(
                name = agent.name.agentPreview().ifBlank { previous.name },
                status = if (explicitState) agent.status else previous.status,
                nativeStatus = agent.nativeStatus.ifBlank { previous.nativeStatus },
                taskPreview = task.ifBlank { previous.taskPreview },
                resultPreview = agent.message.text.agentPreview().ifBlank { previous.resultPreview },
                isCurrent = currentRoot != null && agentTaskRoot(item.turnId, timeline.turns) == currentRoot,
                item = item,
            )
        }
    }
    return roster.values.map { entry ->
        val observed = liveStatuses[entry.threadId]
        // The cache supplies states for already established native identities, never extra children.
        // Without per-call watermarks, a terminal parent record conservatively beats a running read.
        if (observed == null || (entry.status.agentTerminal() && !observed.status.agentTerminal())) entry
        else entry.copy(status = observed.status, nativeStatus = observed.nativeStatus)
    }
}

/** A malformed cycle proves no shared task; fall back to the original native turn identity. */
private fun agentTaskRoot(turnId: String, turns: Map<String, NativeTurnInfo>): String {
    val seen = mutableSetOf<String>()
    var id = turnId
    while (seen.add(id)) {
        val info = turns[id]
        val parent = info?.rootId?.takeIf(String::isNotBlank)
            ?: info?.observedParentId?.takeIf(String::isNotBlank)
            ?: return id
        if (parent == id) return id
        id = parent
    }
    return turnId
}

/** Protocol string fields must be strings: numbers, booleans and arbitrary objects are not prose. */
internal fun JsonObject.agentString(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun String.agentPreview(): String = trim().take(600)
