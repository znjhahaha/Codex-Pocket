package dev.codexpocket.app.data

import kotlinx.serialization.json.*

enum class TranscriptKind { User, Answer, Commentary, Activity, Plan, Error, Process }
data class TranscriptRow(val key: String, val kind: TranscriptKind, val items: List<TimelineItem>, val completedTurn: Boolean = false, val processLabel: String? = null)
data class TranscriptAnchor(val key: String?, val offset: Int = 0) {
    fun resolve(keys: List<String>, fallback: Int): Int = keys.indexOf(key).takeIf { it >= 0 } ?: fallback.coerceIn(0, (keys.size - 1).coerceAtLeast(0))
}

/** The same policy is applied to live events, authoritative snapshots and legacy Room rows. */
object ConversationPresentation {
    private val tools = setOf("commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall", "functionCallOutput", "webSearch", "imageView", "imageGeneration", "collabAgentToolCall", "subAgentActivity", "reasoning", "contextCompaction", "turn/diff/updated")
    /** Kept in the timeline (they drive [liveStatus]) but never drawn as transcript rows on the phone. */
    private val statusOnly = setOf("reasoning", "contextCompaction")

    enum class LiveStatus { Working, Thinking, Compacting }

    /** The active native turn's latest summary remains readable while its tools run. */
    fun liveReasoning(timeline: NativeTimeline): TimelineItem? {
        val turn = timeline.activeTurn ?: return null
        if (turn in timeline.completedTurns || timeline.turns[turn]?.status in setOf("completed", "failed", "interrupted")) return null
        // Item completion describes summary generation, not completion of the containing turn.
        // Snapshot/cache summaries need no replayed item/started event to be displayed.
        return timeline.items.values.lastOrNull {
            it.turnId == turn && it.type == "reasoning" && visible(it) && it.summaryParts.any(String::isNotBlank)
        }
    }

    /** What the running turn is doing now, for the single status line under the transcript. Null when idle. */
    fun liveStatus(timeline: NativeTimeline): LiveStatus? {
        val turn = timeline.activeTurn ?: return null
        // Snapshot presence and a missing completion are not evidence of current compaction.
        // Later actions invalidate its liveStarted evidence without inventing a native completion.
        val last = timeline.items.values.lastOrNull { it.turnId == turn && visible(it) && it.type != "userMessage" &&
            (it.type != "contextCompaction" || (it.liveStarted && !it.complete)) }
        if (last?.type == "contextCompaction") return LiveStatus.Compacting
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

    fun rows(timeline: NativeTimeline, blockedTurns: Set<String> = emptySet(), foldTasks: Boolean = true, includeReasoning: Boolean = false): List<TranscriptRow> {
        if (foldTasks) return taskRows(timeline, blockedTurns)
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
            if (item.type in statusOnly && !includeReasoning) return@forEach
            val kind = when (item.type) {
                "userMessage" -> TranscriptKind.User
                "agentMessage" -> if (item.raw.string("phase") == "commentary" || item.raw.string("channel") == "commentary") TranscriptKind.Commentary else TranscriptKind.Answer
                "plan", "turn/plan/updated" -> TranscriptKind.Plan
                "error", "turn/error", "thread/error", "turn/failed" -> TranscriptKind.Error
                else -> TranscriptKind.Activity
            }
            val completed = item.turnId in timeline.completedTurns
            // Completion changes expansion defaults, never message identity or prose order.
            // Each native agent call is a clickable created/sent-count line of its own.
            // Commands and edits on either side still form their own contiguous work groups.
            val groupable = kind == TranscriptKind.Activity && item.type !in setOf("collabAgentToolCall", "subAgentActivity") &&
                !NativeActivityPresentation.attentionBoundary(item)
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

    private fun taskRows(timeline: NativeTimeline, blockedTurns: Set<String>): List<TranscriptRow> {
        fun root(turn: String): String {
            var id = turn
            val seen = mutableSetOf<String>()
            while (seen.add(id)) { id = timeline.turns[id]?.let { it.rootId ?: it.observedParentId }?.takeIf { it != id } ?: return id }
            return turn
        }
        val spans = mutableListOf<MutableList<TimelineItem>>()
        timeline.items.values.forEach { item ->
            if (spans.lastOrNull()?.lastOrNull()?.let { root(it.turnId) == root(item.turnId) } == true) spans.last() += item
            else spans += mutableListOf(item)
        }
        val occurrences = spans.groupingBy { root(it.first().turnId) }.eachCount()
        return spans.flatMap { items ->
            val ids = items.map { it.turnId }.distinct()
            val taskRoot = root(items.first().turnId)
            // Itemless ancestors/children can still be failed, stopped or waiting. Pagination must
            // supply the root metadata before a partial child can be treated as a finished task.
            val meta = timeline.turns.values.filter { root(it.id) == taskRoot }
            val visibleItems = items.filter(::visible)
            val finalTurn = ids.last()
            val subset = timeline.copy(items = items.associateTo(linkedMapOf()) { it.key to it })
            val questionsResolved = AsyncQuestions.pending(subset).isEmpty()
            fun resolvedAsyncQuestion(item: TimelineItem) = questionsResolved && item.complete && item.type == "agentMessage" &&
                item.raw.string("delivery") == "async" && item.raw.array("questions").isNotEmpty() &&
                item.raw.array("questions").all { it.asObject().string("title").isNotBlank() }
            val attention = setOf(ActivityStatus.Failed, ActivityStatus.Waiting, ActivityStatus.Interrupted, ActivityStatus.Running)
            // Only the final linked native turn supplies the task's answer. Older replies stay in
            // the process; legacy protocols without final markers expose only the last reply.
            val replies = visibleItems.filter { it.turnId == finalTurn && it.type == "agentMessage" && it.raw.array("questions").isEmpty() }
            val explicit = replies.filter { it.raw.string("phase") in setOf("final_answer", "final") || it.raw.string("channel") == "final" }
            val finals = explicit.ifEmpty { listOfNotNull(replies.lastOrNull { it.raw.string("phase") != "commentary" && it.raw.string("channel") != "commentary" }) }
            val agentAttention = agentRoster(subset).any { agent ->
                if (agent.status !in attention) false else {
                    val lastState = items.lastOrNull { item -> NativeActivityPresentation.agents(item).any { it.threadId == agent.threadId && it.nativeStatus.isNotBlank() } }
                    // A successful native close of a previously completed child is cleanup.
                    val closedAfterCompletion = agent.nativeStatus == "shutdown" && lastState?.type == "collabAgentToolCall" &&
                        lastState.raw.string("tool") == "closeAgent" && NativeActivityPresentation.state(lastState) == ActivityStatus.Completed &&
                        agentRoster(subset.copy(items = items.takeWhile { it.key != lastState.key }.associateTo(linkedMapOf()) { it.key to it }))
                            .any { it.threadId == agent.threadId && it.status == ActivityStatus.Completed }
                    !closedAfterCompletion
                }
            }
            val safe = occurrences[taskRoot] == 1 && timeline.activeTurn?.let(::root) != taskRoot &&
                blockedTurns.none { root(it) == taskRoot } && taskRoot in timeline.turns && ids.all { it in timeline.turns } &&
                meta.all { it.status == "completed" } && finals.any { it.turnId == finalTurn } && finals.all { it.complete } &&
                visibleItems.all { it.type == "subAgentActivity" || it.complete || NativeActivityPresentation.state(it) == ActivityStatus.Completed } &&
                items.none { (it.type != "subAgentActivity" && (NativeActivityPresentation.state(it) in attention ||
                    (NativeActivityPresentation.attentionBoundary(it) && !resolvedAsyncQuestion(it)))) ||
                    it.type in setOf("error", "turn/error", "thread/error", "turn/failed") } &&
                !agentAttention && questionsResolved
            if (!safe) rows(subset, foldTasks = false)
            else {
                val firstUser = visibleItems.firstOrNull { it.type == "userMessage" }
                val finalKeys = finals.map { it.key }.toSet()
                val process = visibleItems.filter { it.key != firstUser?.key && it.key !in finalKeys }
                val result = mutableListOf<TranscriptRow>()
                firstUser?.let { result += TranscriptRow(it.key, TranscriptKind.User, listOf(it), true) }
                if (process.isNotEmpty()) {
                    val duration = meta.fold<NativeTurnInfo, Long?>(0L) { total, info ->
                        val elapsed = info.elapsedMs
                        if (total == null || elapsed == null || elapsed > Long.MAX_VALUE - total) null else total + elapsed
                    }
                    val label = duration?.let { (if (meta.any { it.observed }) "观察用时 " else "用时 ") + processDurationLabel(it) + " · 查看过程" }
                        ?: "查看过程 · ${process.size} 项"
                    result += TranscriptRow("process:${root(items.first().turnId)}", TranscriptKind.Process, process, true, label)
                }
                finals.forEach { result += TranscriptRow(it.key, TranscriptKind.Answer, listOf(it), true) }
                result
            }
        }
    }

    /** Allowlisted human text only. Never serialize protocol objects as fallback UI. */
    fun detail(item: TimelineItem): String {
        if (!visible(item)) return ""
        val raw = item.raw
        return when (item.type) {
            "reasoning" -> item.summaryParts.filter(String::isNotBlank).joinToString("\n\n")
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

/** Expanded history remains separate LazyColumn rows, never one enormous composed subtree. */
fun expandTaskRows(rows: List<TranscriptRow>, expanded: Set<String>): List<TranscriptRow> = rows.flatMap { row ->
    if (row.kind != TranscriptKind.Process || row.key !in expanded) listOf(row)
    else listOf(row) + row.items.flatMap { item ->
        ConversationPresentation.rows(NativeTimeline(items = linkedMapOf(item.key to item), completedTurns = setOf(item.turnId)), foldTasks = false, includeReasoning = true)
    }
}
