package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*

internal data class PlanStep(val text: String, val status: String)
internal data class PresentedPlan(val explanation: String, val steps: List<PlanStep>) {
    val completed: Int get() = steps.count { it.status == "completed" }
    val activeStep: PlanStep? get() = steps.firstOrNull { it.status == "inProgress" }
}

/** Do not turn arbitrary tool text into a plan or guess missing native status. */
internal fun nativePlan(item: TimelineItem): PresentedPlan? {
    if (item.type != "turn/plan/updated") return null
    val params = item.raw.obj("params")
    val steps = params["plan"] as? JsonArray ?: return null
    val parsed = steps.map {
        val step = it as? JsonObject ?: return null
        val text = (step["step"] as? JsonPrimitive)?.contentOrNull ?: return null
        val status = (step["status"] as? JsonPrimitive)?.contentOrNull ?: return null
        PlanStep(text, if (status == "in_progress") "inProgress" else status)
    }
    return PresentedPlan(params.string("explanation"), parsed)
}

internal typealias DiffFile = ActivityFile

internal fun diffFiles(item: TimelineItem): List<DiffFile> = activityFiles(item)

internal fun compactFileName(path: String): String = path.replace('\\', '/').substringAfterLast('/')

internal data class ConversationReadPresentation(val skeleton: Boolean, val empty: Boolean, val failed: Boolean, val refreshing: Boolean)

internal fun conversationReadPresentation(state: ConversationLoadState, hasRows: Boolean, hasCachedWindow: Boolean = false) = ConversationReadPresentation(
    skeleton = !hasRows && !hasCachedWindow && state == ConversationLoadState.Loading,
    empty = !hasRows && state in setOf(ConversationLoadState.Idle, ConversationLoadState.Ready),
    failed = state == ConversationLoadState.Failed,
    refreshing = (hasRows || hasCachedWindow) && state == ConversationLoadState.Loading,
)

/** Approvals and native execution are distinct from the phone's pending-command journal. */
internal fun chatStatus(s: PocketState, chat: ChatEntity): String {
    if (!s.online || s.runtimes.none { it.id == chat.runtimeId && it.online }) return "offline"
    if (s.approvals.any { it.runtimeId == chat.runtimeId && it.threadId == chat.key.nativeId && !ConversationPresentation.hookType(it.method) }) return "pending"
    if (s.commands.any { it.chatKey == chat.key && CommandPolicy.blocksNewSend(it.status) }) return "unknown"
    if (chat.key == s.selected?.key) {
        if (s.timeline.activeTurn != null) return "inProgress"
        if (s.timeline.status.isNotBlank()) return s.timeline.status
    }
    return chat.status.ifBlank { "idle" }
}
