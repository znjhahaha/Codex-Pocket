package dev.codexpocket.app.data

import kotlinx.serialization.json.*

enum class TranscriptKind { User, Answer, Commentary, Activity, Plan, Error, Process }
enum class TaskFoldReason { Unclassified, Successful, Disabled, Active, ActiveAgent, PendingRequest, MissingTurnMetadata,
    MissingInitialUser, Failed, Interrupted, Unfinished, MissingFinal, IncompleteFinal, NonContiguous }
data class TranscriptRow(val key: String, val kind: TranscriptKind, val items: List<TimelineItem>, val completedTurn: Boolean = false, val processLabel: String? = null, val processDetail: String? = null,
    val foldReason: TaskFoldReason = TaskFoldReason.Unclassified)
data class TranscriptAnchor(val key: String?, val offset: Int = 0) {
    fun resolve(keys: List<String>, fallback: Int): Int = keys.indexOf(key).takeIf { it >= 0 } ?: fallback.coerceIn(0, (keys.size - 1).coerceAtLeast(0))
}

/** The same policy is applied to live events, authoritative snapshots and legacy Room rows. */
object ConversationPresentation {
    private val tools = setOf("commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall", "functionCallOutput", "webSearch", "imageView", "imageGeneration", "collabAgentToolCall", "subAgentActivity", "reasoning", "contextCompaction", "turn/diff/updated")
    /** Kept in the timeline (they drive [liveStatus]) but never drawn as transcript rows on the phone. */
    private val statusOnly = setOf("reasoning", "contextCompaction")

    /** A close event can precede the final item. One bounded read repairs a partial lifecycle. */
    fun needsCompletionRead(timeline: NativeTimeline, turnId: String): Boolean {
        if (timeline.turns[turnId]?.status != "completed") return false
        val values = timeline.items.values.filter { it.turnId == turnId }
        return values.none { it.complete && (it.type == "plan" || (it.type == "agentMessage" &&
            it.raw.string("phase") != "commentary" && it.raw.string("channel") != "commentary" && it.raw.array("questions").isEmpty())) }
    }

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

    fun rows(timeline: NativeTimeline, blockedTurns: Set<String> = emptySet(), foldTasks: Boolean = true, includeReasoning: Boolean = false,
        resolvedRequestIds: Set<String> = emptySet(), agentLiveStatuses: Map<String, AgentLiveStatus> = emptyMap()): List<TranscriptRow> {
        if (foldTasks) return taskRows(timeline, blockedTurns, resolvedRequestIds, agentLiveStatuses)
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
        return rows.map { it.copy(items = it.items.toList(), foldReason = TaskFoldReason.Disabled) }
    }

    private fun taskRows(timeline: NativeTimeline, blockedTurns: Set<String>, resolvedRequestIds: Set<String>,
        agentLiveStatuses: Map<String, AgentLiveStatus>): List<TranscriptRow> {
        val roots = mutableMapOf<String, String>()
        val invalidLinks = mutableSetOf<String>()
        fun root(turn: String): String {
            roots[turn]?.let { return it }
            var id = turn
            val seen = mutableSetOf<String>()
            while (seen.add(id)) {
                val parent = timeline.turns[id]?.let { it.rootId ?: it.observedParentId }?.takeIf { it != id }
                if (parent == null) { seen.forEach { roots[it] = id }; return id }
                id = parent
            }
            invalidLinks += seen
            return turn
        }
        val metadata = timeline.turns.values.groupBy { root(it.id) }
        val spans = mutableListOf<MutableList<TimelineItem>>()
        timeline.items.values.forEach { item ->
            if (spans.lastOrNull()?.lastOrNull()?.let { root(it.turnId) == root(item.turnId) } == true) spans.last() += item
            else spans += mutableListOf(item)
        }
        val occurrences = spans.groupingBy { root(it.first().turnId) }.eachCount()
        val answered = timeline.items.values.filter { it.type == "userMessage" }.flatMap { AsyncQuestions.replies(it.text) }
            .map { it.string("questionItemId") }.toSet() + resolvedRequestIds
        val resumedOwners = buildMap<String, String> {
            timeline.items.values.forEach { item -> agentResumeEvidence(item).keys.forEach { put(it, item.turnId) } }
        }
        return spans.flatMap { items ->
            val ids = items.map { it.turnId }.distinct()
            val taskRoot = root(items.first().turnId)
            // Itemless ancestors/children can still be failed, stopped or waiting. Pagination must
            // supply the root metadata before a partial child can be treated as a finished task.
            val meta = metadata[taskRoot].orEmpty()
            val visibleItems = items.filter(::visible)
            val finalTurn = ids.last()
            val subset = timeline.copy(items = items.associateTo(linkedMapOf()) { it.key to it })
            // Replies may arrive in a later native turn. Completion/submission alone never answers
            // an async question, while a completed native request tool is a resolved old guard.
            fun requestResolved(item: TimelineItem): Boolean {
                val raw = item.raw
                val aliases = listOf(item.key, item.itemId, raw.string("requestId"), raw.string("id"),
                    raw.obj("params").string("requestId"), raw.obj("params").string("id")).filter(String::isNotBlank)
                if (aliases.any { it in resolvedRequestIds }) return true
                if (item.type == "agentMessage" && raw.array("questions").isNotEmpty()) {
                    if (raw.string("delivery") != "async") return false
                    return raw.array("questions").withIndex().all { (index, question) ->
                        question.asObject().string("title").isNotBlank() &&
                            buildJsonArray { add("request_user_input_async"); add(item.itemId); add(index) }.toString() in answered
                    }
                }
                return item.complete && NativeActivityPresentation.state(item) in
                    setOf(ActivityStatus.Completed, ActivityStatus.Failed, ActivityStatus.Interrupted)
            }
            fun requestGuard(item: TimelineItem): Boolean =
                item.raw.array("questions").isNotEmpty() || item.raw.obj("params").array("questions").isNotEmpty() ||
                listOf(item.type, item.raw.string("method")).any { it.contains("approval", true) || it.contains("requestUserInput", true) } ||
                listOf(item.raw.string("tool"), item.raw.string("name")).any {
                    it.substringAfterLast('.') in setOf("request_user_input", "requestUserInput", "send_user_message")
                }
            // Only the final linked native turn supplies the task's answer. Older replies stay in
            // the process. Legacy protocols did not distinguish commentary from answers: only
            // their last assistant item is a final result. Explicit final fragments stay intact.
            val replies = visibleItems.filter { it.turnId == finalTurn && it.type == "agentMessage" && it.raw.array("questions").isEmpty() }
            val explicit = replies.filter { it.raw.string("phase") in setOf("final_answer", "final") || it.raw.string("channel") == "final" }
            val answers = explicit.ifEmpty { replies.filter { it.raw.string("phase") != "commentary" && it.raw.string("channel") != "commentary" }.takeLast(1) }
            val finalKeys = answers.map { it.key }.toSet()
            val finals = visibleItems.filter { it.key in finalKeys || (it.turnId == finalTurn && it.type == "plan" && it.text.isNotBlank()) }
            // A parent success closes historical Running/Failed/Interrupted child observations.
            // Only the latest native waiting state still represents an unresolved decision.
            // A later task's confirmed native resume does not reopen this historical process.
            val taskLiveStatuses = agentLiveStatuses.filter { (id, _) -> resumedOwners[id]?.let(::root)?.let { it == taskRoot } != false }
            val taskAgents = agentRoster(subset, taskLiveStatuses)
            val agentAttention = taskAgents.any { it.workPhase == AgentWorkPhase.Waiting }
            val liveAgentRunning = taskAgents.any { it.workPhase == AgentWorkPhase.Running &&
                taskLiveStatuses[it.threadId]?.status == ActivityStatus.Running }
            val pendingRequest = blockedTurns.any { root(it) == taskRoot } || agentAttention || items.any {
                if (requestGuard(it)) !requestResolved(it)
                else it.type !in setOf("subAgentActivity", "collabAgentToolCall") && NativeActivityPresentation.state(it) == ActivityStatus.Waiting
            }
            val reason = when {
                meta.any { it.status == "failed" } -> TaskFoldReason.Failed
                meta.any { it.status == "interrupted" } -> TaskFoldReason.Interrupted
                pendingRequest -> TaskFoldReason.PendingRequest
                liveAgentRunning -> TaskFoldReason.ActiveAgent
                timeline.activeTurn?.let(::root) == taskRoot -> TaskFoldReason.Active
                occurrences[taskRoot] != 1 -> TaskFoldReason.NonContiguous
                taskRoot !in timeline.turns || ids.any { it !in timeline.turns || it in invalidLinks } -> TaskFoldReason.MissingTurnMetadata
                meta.any { it.status != "completed" } -> TaskFoldReason.Unfinished
                visibleItems.none { it.type == "userMessage" && it.turnId == taskRoot } -> TaskFoldReason.MissingInitialUser
                finals.isEmpty() -> TaskFoldReason.MissingFinal
                finals.any { !it.complete } -> TaskFoldReason.IncompleteFinal
                else -> TaskFoldReason.Successful
            }
            if (reason != TaskFoldReason.Successful) rows(subset, foldTasks = false).map { it.copy(foldReason = reason) }
            else {
                val firstUser = visibleItems.firstOrNull { it.type == "userMessage" }
                val keptKeys = finals.map { it.key }.toSet()
                val process = visibleItems.filter { it.key != firstUser?.key && it.key !in keptKeys }
                val result = mutableListOf<TranscriptRow>()
                firstUser?.let { result += TranscriptRow(it.key, TranscriptKind.User, listOf(it), true, foldReason = reason) }
                if (process.isNotEmpty()) {
                    val duration = meta.fold<NativeTurnInfo, Long?>(0L) { total, info ->
                        val elapsed = info.elapsedMs
                        if (total == null || elapsed == null || elapsed > Long.MAX_VALUE - total) null else total + elapsed
                    }
                    val failures = process.count { NativeActivityPresentation.state(it) in setOf(ActivityStatus.Failed, ActivityStatus.Interrupted) || it.type in setOf("error", "turn/error", "thread/error") }
                    val agentFailures = taskAgents.count { it.workPhase == AgentWorkPhase.Error }
                    val detail = listOfNotNull(duration?.let { (if (meta.any { it.observed }) "观察用时 " else "用时 ") + processDurationLabel(it) },
                        "$failures 条失败记录".takeIf { failures > 0 }, "$agentFailures 个子代理失败".takeIf { agentFailures > 0 })
                        .joinToString(" · ").takeIf(String::isNotBlank)
                    result += TranscriptRow("process:${root(items.first().turnId)}", TranscriptKind.Process, process, true, "上 ${process.size} 条消息", detail, reason)
                }
                finals.forEach { result += TranscriptRow(it.key, if (it.type == "plan") TranscriptKind.Plan else TranscriptKind.Answer, listOf(it), true, foldReason = reason) }
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
