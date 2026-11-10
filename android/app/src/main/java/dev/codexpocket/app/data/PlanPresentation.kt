package dev.codexpocket.app.data

import java.security.MessageDigest

/** A proposal is a native plan item, never an execution-step update or guessed prose. */
data class ProposedPlan(val turnId: String, val itemId: String, val content: String) {
    val revision: String = MessageDigest.getInstance("SHA-256")
        .digest("$turnId\u0000$itemId\u0000$content".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val prompt: String get() = "PLEASE IMPLEMENT THIS PLAN:\n$content"
    val markdownText: String get() = content
    val markdownFilename: String get() = "plan-${revision.take(12)}.md"
}

object PlanPresentation {
    fun isWriting(item: TimelineItem, timeline: NativeTimeline?, online: Boolean): Boolean =
        online && item.type == "plan" && !item.complete && timeline != null &&
            timeline.activeTurn == item.turnId && item.turnId !in timeline.completedTurns &&
            timeline.activeFlags.none { it in setOf("waitingOnApproval", "waitingOnUserInput") } &&
            timeline.status in setOf("", "active", "inProgress", "running") &&
            timeline.turns[item.turnId]?.status !in setOf("completed", "failed", "interrupted", "cancelled", "waiting")

    fun latest(timeline: NativeTimeline): ProposedPlan? {
        if (timeline.activeTurn != null) return null
        val items = timeline.items.values.toList()
        val index = items.indexOfLast { it.type == "plan" && it.text.isNotBlank() }
        if (index < 0) return null
        val item = items[index]
        if (!item.complete || timeline.turns[item.turnId]?.status != "completed") return null
        if (items.drop(index + 1).any { it.turnId != item.turnId || it.type == "userMessage" || it.type in setOf("error", "turn/error", "turn/failed") }) return null
        return ProposedPlan(item.turnId, item.itemId, item.text)
    }

    /** Only an exact match to a preceding native proposal may shorten an implementation message. */
    fun implementedProposal(message: TimelineItem, timeline: NativeTimeline): ProposedPlan? {
        if (message.type != "userMessage" || !message.text.startsWith("PLEASE IMPLEMENT THIS PLAN:\n")) return null
        return timeline.items.values.takeWhile { it.key != message.key }.lastOrNull {
            it.type == "plan" && it.complete && message.text == "PLEASE IMPLEMENT THIS PLAN:\n${it.text}"
        }?.let { ProposedPlan(it.turnId, it.itemId, it.text) }
    }

    fun unavailable(s: PocketState, plan: ProposedPlan): String? = when {
        s.selected == null || s.selected.isSubagent -> "此方案仅供阅读"
        s.selected.archived -> "已归档会话仅供阅读"
        latest(s.timeline)?.revision != plan.revision || plan.revision in s.resolvedPlans -> "此方案已处理或已更新"
        !s.online || s.activeRuntime?.online != true -> "连接电脑后可实施计划"
        s.conversationLoadState != ConversationLoadState.Ready -> "正在核对最新计划"
        s.selected.conflict -> "请先确认会话归属"
        s.uncertainSend -> "请先核对上一次提交结果"
        s.sendBusy -> "正在提交"
        s.approvals.any { it.threadId == s.selected.key.nativeId && it.runtimeId == s.selected.runtimeId } -> "请先处理待审批请求"
        !s.supports("turn/start") -> "当前运行时不支持实施计划"
        s.model.isNullOrBlank() -> "模型同步后可实施计划"
        else -> null
    }
}
