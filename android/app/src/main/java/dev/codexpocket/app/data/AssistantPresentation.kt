package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import java.time.*

data class AssistantAction(val method: String, val params: JsonObject)
data class AssistantRuntimeChoice(val id: String, val label: String, val available: Boolean = true)
data class AssistantWorkspaceChoice(val runtimeId: String, val threadId: String, val label: String, val canWrite: Boolean = false)
enum class AssistantTaskStatus { Queued, Running, WaitingUser, Paused, Done, Failed, Unknown }
enum class AssistantOperationPhase { Unknown, Running, Waiting, Paused, Completed, Failed }
enum class AssistantQuestionKind { Input, Approval, Unknown }
data class AssistantNotifications(val completed: Boolean = true, val failed: Boolean = true, val waitingUser: Boolean = true)
data class AssistantSettings(val personality: String = "", val tone: String = "", val model: String? = null, val timezone: String = "UTC", val notifications: AssistantNotifications = AssistantNotifications())
data class AssistantTask(val id: String, val prompt: String, val status: AssistantTaskStatus, val activeRunId: String?, val reconciliationRequired: Boolean, val detail: String?)
data class AssistantQuestion(val requestId: String, val kind: AssistantQuestionKind, val text: String)
data class AssistantOperation(val id: String, val role: String, val taskId: String?, val phase: AssistantOperationPhase, val suspended: Boolean, val threadId: String?, val question: AssistantQuestion?, val result: String?)
data class AssistantEvent(val sequence: Long, val id: String, val operationId: String?, val kind: String, val detail: String, val atMs: Long?)
data class AssistantMemory(val id: String, val text: String)
data class AssistantSchedule(val id: String, val prompt: String, val kind: JsonObject, val timezone: String, val nextMs: Long?, val missed: Boolean, val error: String?)
data class AssistantChatMessage(val id: String, val role: String, val text: String, val atMs: Long? = null, val operationId: String? = null)
data class AssistantSnapshot(val enabled: Boolean? = null, val runtimeId: String? = null, val coordinatorThreadId: String? = null, val paused: Boolean? = null,
    val settings: AssistantSettings? = null, val tasks: List<AssistantTask> = emptyList(), val operations: List<AssistantOperation> = emptyList(),
    val events: List<AssistantEvent> = emptyList(), val memories: List<AssistantMemory> = emptyList(), val schedules: List<AssistantSchedule> = emptyList()) {
    val after: Long get() = events.maxOfOrNull { it.sequence } ?: 0
    val coordinatorReplies: List<AssistantChatMessage> get() {
        val summaries = events.filter { it.kind == "summary" && it.detail.isNotBlank() }.map {
            AssistantChatMessage("event:${it.id}", "Assistant", it.detail, it.atMs, it.operationId)
        }
        val results = operations.filter { it.role == "Coordinator" && it.phase == AssistantOperationPhase.Completed && !it.result.isNullOrBlank() }.mapNotNull { op ->
            if (summaries.any { it.operationId == op.id && it.text == op.result }) null
            else AssistantChatMessage("operation:${op.id}:result", "Assistant", op.result!!, events.lastOrNull { it.operationId == op.id && it.kind == "completed" }?.atMs, op.id)
        }
        return summaries + results
    }
    companion object {
        fun from(o: JsonObject): AssistantSnapshot = AssistantSnapshot(
            enabled = o.assistantBoolean("enabled"), runtimeId = o.assistantText("runtimeId"), coordinatorThreadId = o.assistantText("coordinatorThreadId"), paused = o.assistantBoolean("paused"),
            settings = (o["settings"] as? JsonObject)?.let { s ->
                val n = s["notifications"] as? JsonObject
                if (s.assistantText("personality") == null || s.assistantText("tone") == null || s.assistantText("timezone") == null || n == null || listOf("completed", "failed", "waiting_user").any { n.assistantBoolean(it) == null }) null
                else AssistantSettings(s.assistantText("personality")!!, s.assistantText("tone")!!, s.assistantText("model"), s.assistantText("timezone")!!, AssistantNotifications(n.flag("completed"), n.flag("failed"), n.flag("waiting_user")))
            },
            tasks = o.assistantObjects("tasks").mapNotNull { t -> t.assistantText("id")?.let { id ->
                AssistantTask(id, t.assistantText("prompt").orEmpty(), when (t.assistantText("status")) {
                    "queued" -> AssistantTaskStatus.Queued; "running" -> AssistantTaskStatus.Running; "waiting_user" -> AssistantTaskStatus.WaitingUser
                    "paused" -> AssistantTaskStatus.Paused; "done" -> AssistantTaskStatus.Done; "failed" -> AssistantTaskStatus.Failed; else -> AssistantTaskStatus.Unknown
                }, t.assistantText("active_run_id"), t.assistantBoolean("reconciliation_required") ?: true, t.assistantText("detail"))
            } },
            operations = o.assistantObjects("operations").mapNotNull { op -> op.assistantText("id")?.let { id ->
                AssistantOperation(id, op.assistantText("role").orEmpty(), op.assistantText("task_id"), AssistantOperationPhase.entries.firstOrNull { it.name == op.assistantText("phase") } ?: AssistantOperationPhase.Unknown,
                    op.assistantBoolean("suspended") ?: true, (op["mapping"] as? JsonObject)?.assistantText("thread_id"),
                    (op["question"] as? JsonObject)?.let { q -> q.assistantText("request_id")?.let { request ->
                        AssistantQuestion(request, AssistantQuestionKind.entries.firstOrNull { it.name == q.assistantText("kind") } ?: AssistantQuestionKind.Unknown, q.assistantText("text").orEmpty())
                    } }, op.assistantText("result"))
            } },
            events = o.assistantObjects("events").mapNotNull { e -> e.assistantText("id")?.let { id ->
                val sequence = e.assistantLong("sequence")?.takeIf { it >= 0 } ?: return@mapNotNull null
                AssistantEvent(sequence,id,e.assistantText("operation_id"),e.assistantText("kind").orEmpty(),e.assistantText("detail").orEmpty(),e.assistantLong("at_ms"))
            } },
            memories = o.assistantObjects("memories").mapNotNull { m -> m.assistantText("id")?.let { AssistantMemory(it,m.assistantText("text").orEmpty()) } },
            schedules = o.assistantObjects("schedules").mapNotNull { schedule -> schedule.assistantText("id")?.let { id ->
                val spec = schedule["spec"] as? JsonObject ?: return@mapNotNull null
                AssistantSchedule(id,spec.assistantText("prompt").orEmpty(),spec["kind"] as? JsonObject ?: buildJsonObject {},
                    (spec["zone"] as? JsonObject)?.assistantText("name").orEmpty(),schedule.assistantLong("next_ms"),schedule.assistantBoolean("missed") == true,schedule.assistantText("error"))
            } },
        )
    }
}
data class AssistantUiState(val snapshot: AssistantSnapshot? = null, val messages: List<AssistantChatMessage> = emptyList(), val loading: Boolean = false, val error: String? = null,
    val pendingActions: Set<String> = emptySet(), val sendAcknowledgement: Long = 0)
data class AssistantScheduleDraft(val prompt: String = "", val kind: String = "Once", val date: String = "", val time: String = "09:00", val weekday: Int = 0, val intervalMinutes: String = "60", val timezone: String = "UTC", val writes: Boolean = false)
object AssistantActions {
    private fun payload(method: String, runtime: String, fields: JsonObjectBuilder.() -> Unit = {}): AssistantAction {
        bounded(runtime,256,"请选择运行入口")
        return AssistantAction(method,buildJsonObject {put("runtimeId",runtime);fields()})
    }
    fun read(after: Long): AssistantAction { require(after >= 0); return AssistantAction("assistant/read",buildJsonObject {put("after",after)}) }
    fun enable(runtime: String) = payload("assistant/enable",runtime)
    fun pause(runtime: String) = payload("assistant/pause",runtime)
    fun resume(runtime: String) = payload("assistant/resume",runtime)
    fun task(runtime: String, id: String, resume: Boolean): AssistantAction {
        bounded(id,256,"缺少任务标识")
        return payload(if(resume) "assistant/task/resume" else "assistant/task/pause",runtime) {put("taskId",id)}
    }
    fun send(runtime: String, message: String, workspace: AssistantWorkspaceChoice?): AssistantAction {
        bounded(message,65536,"请输入消息，最多 64KiB")
        workspace?.let { require(it.runtimeId == runtime && it.threadId.isNotBlank()) {"项目不属于当前运行入口"} }
        return payload("assistant/send",runtime) {put("message",message);workspace?.let {put("threadId",it.threadId)}}
    }
    fun respond(runtime: String, operation: String, request: String, answer: String): AssistantAction {
        bounded(answer,65536,"请输入回答，最多 64KiB")
        return human(runtime,operation,request,buildJsonObject {put("Answer",answer)})
    }
    fun approve(runtime: String, operation: String, request: String, approved: Boolean) = human(runtime,operation,request,buildJsonObject {put("Approval",buildJsonObject {put("approved",approved)})})
    private fun human(runtime: String, operation: String, request: String, response: JsonObject): AssistantAction {
        bounded(operation,256,"缺少操作标识");bounded(request,256,"缺少问题标识")
        return payload("assistant/respond",runtime) {put("operationId",operation);put("requestId",request);put("response",response)}
    }
    fun settings(runtime: String, settings: AssistantSettings, models: List<NativeModel>): AssistantAction {
        bounded(settings.personality,8192,"人格描述不能为空，最多 8KiB");bounded(settings.tone,2048,"语气不能为空，最多 2KiB")
        bounded(settings.timezone,256,"请输入有效时区");require(runCatching {ZoneId.of(settings.timezone)}.isSuccess) {"时区名称无效"}
        require(settings.model == null || models.any {it.model == settings.model}) {"当前模型未在电脑传入目录中确认"}
        return payload("assistant/settings/update",runtime) {put("settings",buildJsonObject {
            put("personality",settings.personality);put("tone",settings.tone);put("model",settings.model?.let(::JsonPrimitive) ?: JsonNull);put("timezone",settings.timezone)
            put("notifications",buildJsonObject {put("completed",settings.notifications.completed);put("failed",settings.notifications.failed);put("waiting_user",settings.notifications.waitingUser)})
        })}
    }
    fun memoryAdd(runtime: String, text: String): AssistantAction { bounded(text,16384,"记忆不能为空，最多 16KiB");return payload("assistant/memory/add",runtime) {put("text",text)} }
    fun memoryEdit(runtime: String, id: String, text: String): AssistantAction {bounded(id,256,"缺少记忆标识");bounded(text,16384,"记忆不能为空，最多 16KiB");return payload("assistant/memory/update",runtime) {put("memoryId",id);put("text",text)} }
    fun memoryDelete(runtime: String, id: String): AssistantAction {bounded(id,256,"缺少记忆标识");return payload("assistant/memory/delete",runtime) {put("memoryId",id)} }
    fun scheduleDelete(runtime: String, id: String): AssistantAction {bounded(id,256,"缺少定时事项标识");return payload("assistant/schedule/delete",runtime) {put("scheduleId",id)} }
    fun schedule(runtime: String, workspace: AssistantWorkspaceChoice, draft: AssistantScheduleDraft, nowMs: Long): AssistantAction {
        bounded(draft.prompt,65536,"请输入事项内容，最多 64KiB")
        require(workspace.runtimeId == runtime && workspace.threadId.isNotBlank()) {"请选择当前运行入口已授权的项目"}
        require(!draft.writes || workspace.canWrite) {"所选项目没有写入授权"}
        val zone = AssistantScheduleTime.zoneRules(draft.timezone,nowMs)
        val kind = buildJsonObject {when(draft.kind) {
            "Once" -> put("Once",buildJsonObject {put("at_ms",AssistantScheduleTime.once(draft.date,draft.time,draft.timezone,nowMs))})
            "Daily", "Weekly" -> {
                val time = AssistantScheduleTime.time(draft.time)
                require(draft.weekday in 0..6) {"星期无效"}
                put(draft.kind,buildJsonObject {if(draft.kind == "Weekly") put("weekday",draft.weekday);put("hour",time.hour);put("minute",time.minute)})
            }
            "Interval" -> {
                val minutes = draft.intervalMinutes.toLongOrNull()
                require(minutes != null && minutes in 1..527040) {"间隔为 1分钟至366天"}
                val every = Math.multiplyExact(minutes,60000L)
                put("Interval",buildJsonObject {put("anchor_ms",nowMs);put("every_ms",every)})
            }
            else -> throw IllegalArgumentException("请选择有效定时类型")
        }}
        return payload("assistant/schedule/create",runtime) {put("threadId",workspace.threadId);put("spec",buildJsonObject {put("prompt",draft.prompt);put("writes",draft.writes);put("kind",kind);put("zone",zone)})}
    }
    private fun bounded(text: String, max: Int, message: String) { require(text.isNotBlank() && '\u0000' !in text && text.toByteArray(Charsets.UTF_8).size <= max) {message} }
}
object AssistantScheduleTime {
    private const val DAY_MS = 86400000L
    fun zoneRules(zone: String, nowMs: Long): JsonObject {
        require(nowMs >= 0 && zone.length <= 256) {"时区覆盖时间无效"}
        val id = runCatching {ZoneId.of(zone)}.getOrElse {throw IllegalArgumentException("时区名称无效")}
        val from = (nowMs - DAY_MS).coerceAtLeast(0)
        val until = Math.addExact(nowMs,400 * DAY_MS)
        require(until < Long.MAX_VALUE / 2) {"时区覆盖时间超界"}
        val rules = id.rules
        var cursor = Instant.ofEpochMilli(from)
        val transitions = mutableListOf<JsonElement>()
        while(true) {
            val next = rules.nextTransition(cursor) ?: break
            val at = next.instant.toEpochMilli()
            if(at >= until) break
            require(at > cursor.toEpochMilli() && transitions.size < 4096) {"时区规则超界"}
            transitions += buildJsonObject {put("at_ms",at);put("offset_seconds",next.offsetAfter.totalSeconds)}
            cursor = next.instant
        }
        return buildJsonObject {put("name",id.id);put("from_ms",from);put("until_ms",until);put("initial_offset_seconds",rules.getOffset(Instant.ofEpochMilli(from)).totalSeconds);put("transitions",JsonArray(transitions))}
    }
    fun time(text: String): LocalTime {
        require(text.matches(Regex("[0-9]{2}:[0-9]{2}"))) {"时间格式为 HH:mm"}
        return runCatching {LocalTime.of(text.substring(0,2).toInt(),text.substring(3,5).toInt())}.getOrElse {throw IllegalArgumentException("时间必须在 00:00 至 23:59")}
    }
    fun once(date: String, time: String, zone: String, nowMs: Long): Long {
        val rules = zoneRules(zone,nowMs)
        val local = runCatching {LocalDate.parse(date).atTime(time(time))}.getOrElse {throw IllegalArgumentException("请输入有效日期和时间")}
        val offsets = ZoneId.of(zone).rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) {"该本地时间不存在，请避开夏令时切换空档"}
        val at = offsets.minOf {local.toInstant(it).toEpochMilli()}
        require(at > nowMs && at >= rules.number("from_ms") && at < rules.number("until_ms")) {"一次事项需在未来400天的覆盖范围内"}
        return at
    }
}

private fun JsonObject.assistantText(key: String) = (this[key] as? JsonPrimitive)?.takeIf {it.isString}?.content?.takeIf {it.isNotEmpty()}
private fun JsonObject.assistantBoolean(key: String) = (this[key] as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull
private fun JsonObject.assistantLong(key: String) = (this[key] as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull
private fun JsonObject.assistantObjects(key: String) = (this[key] as? JsonArray)?.mapNotNull {it as? JsonObject}.orEmpty()
