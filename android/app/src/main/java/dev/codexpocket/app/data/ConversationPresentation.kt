package dev.codexpocket.app.data

import kotlinx.serialization.json.*

enum class TranscriptKind { User, Answer, Commentary, Activity, Plan, Error }
data class TranscriptRow(val key: String, val kind: TranscriptKind, val items: List<TimelineItem>, val completedTurn: Boolean = false)
data class TranscriptAnchor(val key: String?, val offset: Int = 0) {
    fun resolve(keys: List<String>, fallback: Int): Int = keys.indexOf(key).takeIf { it >= 0 } ?: fallback.coerceIn(0, (keys.size - 1).coerceAtLeast(0))
}

/** The same policy is applied to live events, authoritative snapshots and legacy Room rows. */
object ConversationPresentation {
    private val tools = setOf("commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall", "functionCallOutput", "webSearch", "imageView", "imageGeneration", "collabAgentToolCall", "subAgentActivity", "reasoning", "contextCompaction", "turn/diff/updated")
    /** Kept in the timeline (they drive [liveStatus]) but never drawn as transcript rows on the phone. */
    private val statusOnly = setOf("reasoning", "contextCompaction")

    enum class LiveStatus { Working, Thinking, Compacting }

    /** Only upstream summary text, never hidden reasoning or a fabricated placeholder. */
    fun liveReasoning(timeline: NativeTimeline): TimelineItem? {
        val turn = timeline.activeTurn ?: return null
        return timeline.items.values.lastOrNull { it.turnId == turn && visible(it) && it.type != "userMessage" }
            ?.takeIf { it.type == "reasoning" && !it.complete && it.text.isNotBlank() }
    }

    /** What the running turn is doing now, for the single status line under the transcript. Null when idle. */
    fun liveStatus(timeline: NativeTimeline): LiveStatus? {
        val turn = timeline.activeTurn ?: return null
        val items = timeline.items.values.filter { it.turnId == turn && visible(it) }
        if (items.any { it.type == "contextCompaction" && !it.complete }) return LiveStatus.Compacting
        val last = items.lastOrNull { it.type != "userMessage" }
        return if (last == null || (last.type == "reasoning" && !last.complete)) LiveStatus.Thinking else LiveStatus.Working
    }
    private val messages = setOf("userMessage", "agentMessage", "plan", "turn/plan/updated", "error", "turn/error", "thread/error", "turn/failed")
    fun hookType(value: String): Boolean {
        val segments = value.lowercase().split('/')
        return segments.any { it in setOf("hook", "hooks", "hookprompt", "hookstarted", "hookcompleted", "hook_started", "hook_completed", "hook_prompt") }
    }
    fun hasHookMetadata(value: JsonElement): Boolean {
        val root = value as? JsonObject ?: return false
        // Only protocol discriminators. Tool arguments/results may contain arbitrary user data.
        if (hookType(root.string("type")) || hookType(root.string("method"))) return true
        if (root.string("method") in setOf("item/started", "item/completed") && hookType(root.obj("params").obj("item").string("type"))) return true
        return root.string("type") in setOf("", "userMessage") && root.array("content").any { hookType(it.asObject().string("type")) }
    }
    fun visible(item: TimelineItem): Boolean = !hookType(item.type) && !hasHookMetadata(item.raw) && (item.type in tools || item.type in messages)
    fun eventVisible(method: String) = !hookType(method) && method in setOf("turn/plan/updated", "turn/diff/updated", "error", "turn/error", "thread/error", "turn/failed")

    fun anchorKey(rows: List<TranscriptRow>, oldKey: String?): String? {
        if (oldKey == null || rows.any { it.key == oldKey }) return oldKey
        val nativeKey = oldKey.removePrefix("activity:")
        return rows.firstOrNull { row -> row.items.any { it.key == nativeKey } }?.key ?: oldKey
    }

    fun rows(timeline: NativeTimeline): List<TranscriptRow> {
        val rows = mutableListOf<TranscriptRow>()
        var span = mutableListOf<TimelineItem>()
        var canAppend = false
        timeline.items.values.forEach { item ->
            // Even an event rendered elsewhere (approval/question) breaks a tool span.
            if (!visible(item)) {
                canAppend = false
                return@forEach
            }
            // Codex on desktop shows thinking only while it runs; the phone shows it in the live status line instead.
            if (item.type in statusOnly) return@forEach
            val kind = when (item.type) {
                "userMessage" -> TranscriptKind.User
                "agentMessage" -> if (item.raw.string("phase") == "commentary" || item.raw.string("channel") == "commentary") TranscriptKind.Commentary else TranscriptKind.Answer
                "plan", "turn/plan/updated" -> TranscriptKind.Plan
                "error", "turn/error", "thread/error", "turn/failed" -> TranscriptKind.Error
                else -> TranscriptKind.Activity
            }
            val completed = item.turnId in timeline.completedTurns
            // Completion changes expansion defaults, never message identity or prose order.
            val groupable = kind == TranscriptKind.Activity && !NativeActivityPresentation.attentionBoundary(item)
            val previous = rows.lastOrNull()
            if (groupable && canAppend && previous?.kind == kind && previous.items.first().turnId == item.turnId) {
                span += item
            } else {
                span = mutableListOf(item)
                rows += TranscriptRow(if (kind == TranscriptKind.Activity) "activity:${item.key}" else item.key, kind, span, completed)
            }
            canAppend = groupable
        }
        // Copy once per span, rather than repeatedly copying a growing tool group on every delta.
        return rows.map { it.copy(items = it.items.toList()) }
    }

    /** Allowlisted human text only. Never serialize protocol objects as fallback UI. */
    fun detail(item: TimelineItem): String {
        if (!visible(item)) return ""
        val raw = item.raw
        return when (item.type) {
            "fileChange", "turn/diff/updated" -> item.diff
            "functionCallOutput" -> NativeActivityPresentation.describe(item).output.text
            "mcpToolCall", "dynamicToolCall", "webSearch", "collabAgentToolCall", "subAgentActivity", "imageView", "imageGeneration" -> {
                val activity = NativeActivityPresentation.describe(item)
                (listOf(activity.title, activity.subtitle, activity.output.text) + activity.agents.map { agent ->
                    listOf(agent.name.ifBlank { agent.threadId }, agent.nativeStatus, agent.message.text).filter(String::isNotBlank).joinToString(" · ")
                }).filter(String::isNotBlank).joinToString("\n")
            }
            "error", "turn/error", "thread/error", "turn/failed" -> item.text.ifBlank { raw.obj("params").obj("error").string("message", raw.obj("error").string("message", raw.obj("params").string("message"))) }
            else -> item.text
        }
    }
}
