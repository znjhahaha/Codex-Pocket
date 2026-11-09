package dev.codexpocket.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

enum class AgentWorkPhase { Starting, Running, Waiting, Completed, Error, Stopped, Unknown }
enum class AgentAvatarGlyph { Rosette, Brain, Arc }

/** Stable across filtering, pagination and name updates; never assigned by the visible slot. */
fun agentAvatarGlyph(threadId: String): AgentAvatarGlyph = AgentAvatarGlyph.entries[(threadId.hashCode() ushr 1) % 3]

private fun agentStatus(native: String): ActivityStatus = when (native.lowercase(Locale.ROOT)) {
    "active" -> ActivityStatus.Running
    "pending_init", "pendinginit" -> ActivityStatus.Waiting
    "systemerror", "system_error" -> ActivityStatus.Failed
    else -> NativeActivityPresentation.status(native)
}

val AgentRosterEntry.workPhase: AgentWorkPhase get() = when {
    nativeStatus.lowercase(Locale.ROOT) in setOf("pending_init", "pendinginit") -> AgentWorkPhase.Starting
    status == ActivityStatus.Running -> AgentWorkPhase.Running
    status == ActivityStatus.Waiting -> AgentWorkPhase.Waiting
    status == ActivityStatus.Completed -> AgentWorkPhase.Completed
    status == ActivityStatus.Failed -> AgentWorkPhase.Error
    status == ActivityStatus.Interrupted -> AgentWorkPhase.Stopped
    else -> AgentWorkPhase.Unknown
}

data class AgentWorkSummary(val running: List<AgentRosterEntry>, val starting: List<AgentRosterEntry>,
    val waiting: List<AgentRosterEntry>, val errors: List<AgentRosterEntry>)

/** First native appearance order is shared by labels/avatars; no elapsed-time guesses. */
fun currentAgentWork(agents: List<AgentRosterEntry>): AgentWorkSummary {
    val current = agents.filter { it.isCurrent }.distinctBy { it.threadId }
    return AgentWorkSummary(current.filter { it.workPhase == AgentWorkPhase.Running },
        current.filter { it.workPhase == AgentWorkPhase.Starting }, current.filter { it.workPhase == AgentWorkPhase.Waiting },
        current.filter { it.workPhase == AgentWorkPhase.Error })
}

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
    // Accepted resume or newer authoritative Active begins a generation before its turn ID
    // arrives. Old parent spawn/wait snapshots cannot erase that execution with an old terminal.
    val confirmedRestart: Boolean = false,
    val executionGeneration: Long = 0,
    val generationCursor: Cursor? = null,
    val retiredTurnIds: Set<String> = emptySet(),
    val resumeKey: String? = null,
    val seenResumeKeys: Set<String> = emptySet(),
) {
    val status: ActivityStatus get() {
        val base = agentStatus(nativeStatus)
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
                // Thread-level native systemError is authoritative even when an older active
                // pointer still names a completed turn. It is not a transport/stream exception.
                agentStatus(timeline.status) in setOf(ActivityStatus.Failed, ActivityStatus.Interrupted) -> timeline.status
                timeline.status == "active" -> activeInfo?.status?.takeIf { it == "inProgress" } ?: "active"
                terminalActive != null -> terminalActive.status
                timeline.status in setOf("idle", "notLoaded") -> latest?.status?.takeIf { NativeActivityPresentation.status(it).agentTerminal() } ?: timeline.status
                latest?.status?.isNotBlank() == true -> latest.status
                active != null && active !in timeline.completedTurns -> "inProgress"
                else -> timeline.status
            }
            if (native.isBlank()) return null
            val turnId = when {
                timeline.status == "active" && terminalActive != null -> null
                terminalActive != null -> terminalActive.id
                active != null -> active
                timeline.status == "active" -> null // No native identity for a newly active turn yet.
                else -> latest?.id
            }
            val retired = if (agentStatus(native) == ActivityStatus.Running) timeline.turns.values
                .filter { it.id != turnId && agentStatus(it.status).agentTerminal() }.map { it.id }.toSet() else emptySet()
            return AgentLiveStatus(native, turnId, cursor, timeline.activeFlags, retiredTurnIds = retired)
        }
    }
}

private fun ActivityStatus.agentTerminal() = this in setOf(ActivityStatus.Completed, ActivityStatus.Failed, ActivityStatus.Interrupted)

/** Owner validates read/event freshness; supplied cursors additionally reject duplicate/stale data. */
fun mergeAgentLiveStatus(previous: AgentLiveStatus?, next: AgentLiveStatus): AgentLiveStatus {
    if (previous == null) return if (next.status in setOf(ActivityStatus.Running, ActivityStatus.Waiting) &&
        (next.cursor != null || next.resumeKey != null)) next.copy(confirmedRestart = true, executionGeneration = 1,
        generationCursor = next.cursor, seenResumeKeys = next.seenResumeKeys + listOfNotNull(next.resumeKey)) else next
    if (next.resumeKey != null && next.resumeKey in previous.seenResumeKeys) return previous
    // A delayed terminal event for an earlier generation cannot close a resumed execution,
    // even when its delivery cursor is newer than the resume/status notification.
    if (next.turnId != null && next.turnId in previous.retiredTurnIds) {
        if (next.nativeStatus == "active") return mergeAgentLiveStatus(previous, next.copy(turnId = null))
        return previous
    }
    val oldCursor = previous.metadataWatermark ?: previous.cursor
    val newCursor = next.cursor
    // Clear the parent/runtime cache only after confirming a replacement epoch. Unversioned reads
    // must not supersede ordered events: the owner can attach their accepted snapshot watermark.
    if (oldCursor != null) {
        if (newCursor == null || oldCursor.epoch != newCursor.epoch) return previous
        if (newCursor.sequence <= oldCursor.sequence) {
            if (next.resumeKey != null && previous.generationCursor?.let {
                    newCursor.epoch == it.epoch && newCursor.sequence <= it.sequence
                } == true) return previous.copy(seenResumeKeys = previous.seenResumeKeys + next.resumeKey)
            val idleMetadata = previous.nativeStatus in setOf("idle", "notLoaded")
            // A read watermark can precede delivery of the very completion that made it idle.
            // Refine only with an identified native terminal turn in the same epoch, preserving
            // both the higher metadata bound and the actual ordering of terminal evidence.
            val refinesIdle = (idleMetadata || previous.metadataWatermark != null) &&
                next.status.agentTerminal() && !next.turnId.isNullOrBlank() &&
                (idleMetadata || previous.cursor?.let { newCursor.sequence > it.sequence } == true)
            return if (refinesIdle) next.copy(metadataWatermark = oldCursor,
                executionGeneration = previous.executionGeneration, generationCursor = previous.generationCursor,
                retiredTurnIds = previous.retiredTurnIds + next.retiredTurnIds, seenResumeKeys = previous.seenResumeKeys)
            else previous
        }
    }
    val active = next.status in setOf(ActivityStatus.Running, ActivityStatus.Waiting)
    val newerActive = next.nativeStatus == "active" && newCursor != null && oldCursor != null &&
        newCursor.epoch == oldCursor.epoch && newCursor.sequence > oldCursor.sequence
    val resume = next.resumeKey != null
    val newIdentity = next.turnId != null && next.turnId != previous.turnId
    val newRun = active && (previous.status.agentTerminal() ||
        // Idle can retain the old turn while its completion is still in flight.
        previous.nativeStatus in setOf("idle", "notLoaded") ||
        (newIdentity && previous.turnId != null) || previous.turnId?.let { it in next.retiredTurnIds } == true) &&
        (newerActive || resume || newIdentity)
    if (previous.status.agentTerminal() && !next.status.agentTerminal() && !newRun) {
        // The reverse arrival order preserves completion and the same metadata freshness bound.
        return if (next.nativeStatus in setOf("idle", "notLoaded") && newCursor != null)
            previous.copy(metadataWatermark = newCursor) else previous
    }
    if (newRun) {
        val retired = previous.retiredTurnIds + next.retiredTurnIds + listOfNotNull(previous.turnId)
        return next.copy(turnId = next.turnId?.takeUnless { it in retired }, confirmedRestart = true,
            executionGeneration = previous.executionGeneration + 1, generationCursor = next.cursor,
            retiredTurnIds = retired, seenResumeKeys = previous.seenResumeKeys + listOfNotNull(next.resumeKey))
    }
    val continued = next.turnId == null && !previous.status.agentTerminal() &&
        next.nativeStatus in setOf("active", "idle", "notLoaded", "systemError", "system_error", "error", "errored", "failed", "shutdown", "interrupted")
    val candidate = next.copy(turnId = if (continued) previous.turnId else next.turnId,
        executionGeneration = previous.executionGeneration, generationCursor = previous.generationCursor,
        retiredTurnIds = previous.retiredTurnIds + next.retiredTurnIds,
        seenResumeKeys = previous.seenResumeKeys + listOfNotNull(next.resumeKey))
    val restarting = !candidate.status.agentTerminal() && previous.confirmedRestart
    return if (restarting && !candidate.confirmedRestart) candidate.copy(confirmedRestart = true) else candidate
}

/** Feed accepted item/completed resume/sendInput results to the same live-state merger using
 * the native event cursor. Repeated snapshots retain the operation key instead of starting runs.
 */
fun agentResumeEvidence(item: TimelineItem, cursor: Cursor? = null): Map<String, AgentLiveStatus> {
    if (item.type != "collabAgentToolCall" || item.raw.agentString("tool") !in setOf("resumeAgent", "sendInput") ||
        !item.complete || NativeActivityPresentation.state(item) != ActivityStatus.Completed) return emptyMap()
    return NativeActivityPresentation.agents(item).filter { agentStatus(it.nativeStatus) == ActivityStatus.Running }
        .associate { it.threadId to AgentLiveStatus(it.nativeStatus, cursor = cursor, resumeKey = item.key) }
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
    val currentInteractions = mutableSetOf<String>()
    for (item in timeline.items.values) {
        if (item.type !in setOf("collabAgentToolCall", "subAgentActivity")) continue
        val task = if (item.type == "collabAgentToolCall") item.raw.agentString("prompt").agentPreview() else ""
        for (agent in NativeActivityPresentation.agents(item)) {
            if (currentRoot != null && agentTaskRoot(item.turnId, timeline.turns) == currentRoot) currentInteractions += agent.threadId
            val previous = roster[agent.threadId] ?: AgentRosterEntry(agent.threadId)
            val childKind = item.raw.agentString("kind").takeIf {
                item.type == "subAgentActivity" && agentStatus(it) != ActivityStatus.Unknown
            }.orEmpty()
            val nativeStatus = childKind.ifBlank { agent.nativeStatus }
            val explicitState = nativeStatus.isNotBlank()
            roster[agent.threadId] = previous.copy(
                name = agent.name.agentPreview().ifBlank { previous.name },
                status = if (explicitState) agentStatus(nativeStatus) else previous.status,
                nativeStatus = nativeStatus.ifBlank { previous.nativeStatus },
                taskPreview = task.ifBlank { previous.taskPreview },
                resultPreview = agent.message.text.agentPreview().ifBlank { previous.resultPreview },
                isCurrent = currentRoot != null && agentTaskRoot(item.turnId, timeline.turns) == currentRoot,
                item = item,
            )
        }
    }
    return roster.values.map { entry ->
        val observed = liveStatuses[entry.threadId]
        // A resumed historical child is still attached to this native parent. Its accepted live
        // execution can become current before the parent's interaction item arrives.
        val liveCurrent = observed != null && observed.status in setOf(ActivityStatus.Running, ActivityStatus.Waiting) &&
            (observed.executionGeneration > 0 || observed.resumeKey != null || observed.cursor != null)
        val current = entry.copy(isCurrent = entry.threadId in currentInteractions || liveCurrent)
        // The cache supplies states for already established native identities, never extra children.
        // The owner has accepted cursor-bearing child observations. Old parent tool snapshots
        // cannot override them; unversioned observations still require explicit resume evidence.
        if (observed == null || (entry.status.agentTerminal() && !observed.status.agentTerminal() &&
                observed.cursor == null && !observed.confirmedRestart)) current
        else current.copy(status = observed.status, nativeStatus = observed.nativeStatus)
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
