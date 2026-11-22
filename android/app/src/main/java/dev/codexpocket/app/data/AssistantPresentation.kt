package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import java.time.*

data class AssistantAction(val method: String, val params: JsonObject, val commandId: String? = null,
    val composerBinding: AssistantComposerBinding? = null,val attachments:List<LocalAttachment> = emptyList())
data class AssistantStartupStage(val id: String, val status: String, val recoveryAction: String? = null) {
    val label get() = when(id) { "runtime"->"电脑服务"; "account"->"登录与提供商"; "capabilities"->"执行能力"; "coordinator"->"助理协调"; else->"准备状态" }
    val statusLabel get() = when(status) {"ready"->"已就绪";"paused"->"已暂停";"reconciling"->"核对中";"blocked"->"需处理";"preparing"->"准备中";else->"等待前一步"}
    val recoveryText get() = when(recoveryAction) {
        "install_or_locate_native_codex_cli", "locate_saved_native_codex_cli" -> "在电脑端安装或定位 Codex CLI，再重试准备。"
        "login_with_codex_cli" -> "在电脑端用现有 Codex 配置登录，再重试准备。"
        "check_cli_account_status" -> "在电脑端检查当前账号或自定义提供商配置，再重试准备。"
        "update_native_codex_protocol" -> "更新电脑端 Codex CLI，确认任务、暂停和审批接口可用。"
        "reconcile_retained_native_receipts" -> "正在读取电脑端回执核对结果，请勿重复发送。"
        "resume_assistant" -> "在助理设置中恢复，已暂停的任务可逐个恢复。"
        "create_or_repair_codex_home", "repair_saved_codex_home", "repair_saved_profile", "check_saved_profile_access" -> "在电脑端修复已保存的 Codex 配置或访问权限，再重试准备。"
        "inspect_runtime_ownership_before_retry" -> "在电脑端检查原服务归属，确认后重试准备。"
        "resolve_handoff_before_profile_migration" -> "先完成电脑端更新交接，再重试准备。"
        null -> null
        else -> "在电脑端检查 Codex 服务与安装状态，再重试准备。"
    }
}
data class AssistantRuntimeChoice(val id: String, val label: String, val available: Boolean = true)
data class AssistantWorkspaceChoice(val runtimeId: String, val threadId: String, val label: String, val canWrite: Boolean = false, val parentThreadId: String? = null, val isSubagent: Boolean = false, val configStoreId: String? = null, val permissionStatus: String = "未核验，按只读处理") {
    val selectionKey get() = JsonArray(listOf(runtimeId,configStoreId.orEmpty(),threadId).map(::JsonPrimitive)).toString()
}
enum class AssistantTaskStatus { Queued, Running, WaitingUser, Paused, Done, Failed, Unknown }
enum class AssistantOperationPhase { Unknown, Running, Waiting, Paused, Completed, Failed }
enum class AssistantQuestionKind { Input, Approval, Unknown }
data class AssistantNotifications(val completed: Boolean = true, val failed: Boolean = true, val waitingUser: Boolean = true)
data class AssistantSettings(val personality: String = "", val tone: String = "", val model: String? = null, val timezone: String = "Asia/Shanghai", val notifications: AssistantNotifications = AssistantNotifications(),
    val coordinatorModel: String? = null)
data class AssistantTask(val id: String, val prompt: String, val status: AssistantTaskStatus, val activeRunId: String?, val reconciliationRequired: Boolean, val detail: String?, val assignment: AssistantAssignment? = null)
data class AssistantQuestion(val requestId: String, val kind: AssistantQuestionKind, val text: String)
data class AssistantOperation(val id: String, val role: String, val taskId: String?, val phase: AssistantOperationPhase, val suspended: Boolean, val threadId: String?, val question: AssistantQuestion?, val result: String?, val heldInputs:List<AssistantHeldInput> = emptyList(),
    val handoffRecovery: AssistantHandoffRecovery? = null)
data class AssistantEvent(val sequence: Long, val id: String, val operationId: String?, val kind: String, val detail: String, val atMs: Long?, val taskId: String? = null, val memberId: String? = null, val groupId: String? = null, val threadId: String? = null, val memberAttributionProvided: Boolean = memberId != null)
data class AssistantMemory(val id: String, val text: String, val source:String? = null, val scope:String = "personal", val automatic:Boolean = false, val updatedAtMs:Long? = null, val revisions:List<Long> = emptyList())
data class AssistantSchedule(val id: String, val prompt: String, val kind: JsonObject, val timezone: String, val nextMs: Long?, val missed: Boolean, val error: String?, val paused:Boolean = false, val missedPolicy:String = "skip", val writes:Boolean = false)
data class AssistantAssignment(val taskId: String, val memberId: String? = null, val groupId: String? = null,
    val dependencies: List<String> = emptyList(), val completionCriteria: String = "", val continueFromTask: String? = null) {
    companion object {
        fun from(o: JsonObject, taskId: String) = AssistantAssignment(taskId, o.assistantText("member_id"), o.assistantText("group_id"),
            o.assistantStrings("dependencies"), o.assistantText("completion_criteria").orEmpty(), o.assistantText("continue_from_task"))
    }
}
data class AssistantMember(val id: String, val name: String, val duty: String, val mainThreadId: String?, val directory: String,
    val threadId: String?, val active: Boolean)
data class AssistantGroup(val id: String, val title: String, val taskIds: List<String>, val requiresSummary: Boolean, val sealed: Boolean, val status: String)
data class AssistantDelivery(val id: String, val message: String, val memberId: String?, val taskId: String?, val status: String, val deliveredTaskId: String?, val atMs: Long? = null) {
    val label get() = when(status) {"queued" -> "待送达"; "sent" -> "已送达"; "sending" -> "正在送达"; "paused" -> "暂停送达"; else -> "待确认送达"}
}
data class AssistantQuestionHistory(val id: String, val operationId: String?, val requestId: String?, val atMs: Long?, val item: TimelineItem)
data class AssistantChatMessage(val id: String, val role: String, val text: String, val atMs: Long? = null, val operationId: String? = null,
    val memberId: String? = null, val taskId: String? = null, val groupId: String? = null, val sourceThreadId: String? = null,
    val authorName: String? = null, val eventId: String? = null) {
    companion object {
        fun from(o: JsonObject): AssistantChatMessage? {
            val id = o.assistantText("id") ?: return null
            val role = o.assistantText("role")?.takeIf {it in setOf("User", "Assistant")} ?: return null
            val text = o.assistantText("text") ?: return null
            return AssistantChatMessage(id, role, text, o.assistantLong("at_ms") ?: o.assistantLong("atMs"),
                o.assistantText("operationId") ?: o.assistantText("operation_id"), o.assistantText("memberId") ?: o.assistantText("member_id"), o.assistantText("taskId") ?: o.assistantText("task_id"),
                o.assistantText("groupId") ?: o.assistantText("group_id"), o.assistantText("sourceThreadId") ?: o.assistantText("source_thread_id"), o.assistantText("authorName"), o.assistantText("eventId"))
        }
    }
}
data class AssistantSnapshot(val enabled: Boolean? = null, val runtimeId: String? = null, val coordinatorThreadId: String? = null, val paused: Boolean? = null,
    val settings: AssistantSettings? = null, val tasks: List<AssistantTask> = emptyList(), val operations: List<AssistantOperation> = emptyList(),
    val events: List<AssistantEvent> = emptyList(), val memories: List<AssistantMemory> = emptyList(), val schedules: List<AssistantSchedule> = emptyList(),
    val members: List<AssistantMember> = emptyList(), val groups: List<AssistantGroup> = emptyList(), val assignments: List<AssistantAssignment> = emptyList(),
    val deliveries: List<AssistantDelivery> = emptyList(), val questionHistory: List<AssistantQuestionHistory> = emptyList(), val ready: Boolean? = null, val preparationStatus: String? = null, val errorCode: String? = null,
    val startupStages: List<AssistantStartupStage> = emptyList(), val recoveries: List<AssistantRecoveryRequest> = emptyList(),
    val fresh: Boolean? = null, val dispatchInFlight: Boolean = false, val assistantId: String? = null,
    val artifacts:List<AssistantArtifact> = emptyList(), val scheduleRuns:List<AssistantScheduleRun> = emptyList(),
    val memoryUsage:Map<String,List<String>> = emptyMap(), val memoryDisabledTasks:List<String> = emptyList(),
    val protocolVersion:Int = 1,val acceptingMessages:Boolean? = null,val domain:AssistantDomain? = null,
    val jobs:List<AssistantJob> = emptyList()) {
    val prepReady get() = preparationStatus in setOf(null,"ready") && errorCode.isNullOrBlank()
    val prepared get() = if(protocolVersion>=2) enabled==true && ready==true && acceptingMessages==true else enabled==true && ready==true && prepReady && recoveries.isEmpty() && (startupStages.isEmpty() ||
        listOf("runtime","account","capabilities","coordinator").all {id -> startupStages.firstOrNull {it.id==id}?.status in setOf("ready","paused")})
    val preparationIssue: String? get() = when {
        protocolVersion>=2 && prepared -> null
        dispatchInFlight && errorCode.isNullOrBlank() -> null
        recoveries.isNotEmpty() -> "请先结束待恢复的原操作，再继续与助理对话"
        errorCode=="assistant_missing_cli" || preparationStatus=="missing_cli" -> "电脑端尚未找到 Codex CLI，请安装或定位 CLI 后重试"
        errorCode=="assistant_missing_config" || preparationStatus in setOf("config","missing_config") -> "电脑端 Codex 配置尚未就绪，请修复配置后重试"
        errorCode=="assistant_needs_login" || preparationStatus=="needs_login" -> "请在电脑端登录 Codex 后重试"
        errorCode=="assistant_restore_failed" -> "助理恢复未完成，请重试准备"
        errorCode=="assistant_unavailable_on_device" -> "助理暂不支持在此手机上运行，请切换到电脑或 SSH 主机后使用"
        errorCode=="assistant_preparing" || preparationStatus=="preparing" -> "助理正在准备，草稿会保留"
        !errorCode.isNullOrBlank() || preparationStatus=="recoverable_error" -> "助理准备遇到问题，请检查电脑端后重试"
        !prepReady -> "助理准备状态尚未确认，请重试"
        ready==false -> "助理正在准备，可保留草稿并重试"
        else -> null
    }
    val preparationRetryLabel get() = when(preparationStatus) {
        "needs_login" -> "登录后重试"; "missing_cli" -> "安装后重试"; "config", "missing_config" -> "修复后重试"; else -> "重试准备"
    }
    fun operationMemberId(operationId: String?, taskId: String?): String? {
        val evidence=events.lastOrNull {it.operationId==operationId && it.memberAttributionProvided}
        return if(evidence!=null) evidence.memberId else assignmentFor(taskId.orEmpty())?.memberId
    }
    fun assignmentFor(taskId: String) = assignments.firstOrNull {it.taskId == taskId} ?: tasks.firstOrNull {it.id == taskId}?.assignment
    val after: Long get() = events.maxOfOrNull { it.sequence } ?: 0
    val coordinatorReplies: List<AssistantChatMessage> get() {
        val summaries = events.filter { it.kind == "summary" && it.detail.isNotBlank() }.map {
            AssistantChatMessage("event:${it.id}", "Assistant", it.detail, it.atMs, it.operationId, it.memberId, it.taskId, it.groupId, it.threadId, members.firstOrNull {m -> m.id == it.memberId}?.name, it.id)
        }
        val results = operations.filter { it.role == "Coordinator" && it.phase == AssistantOperationPhase.Completed && !it.result.isNullOrBlank() }.mapNotNull { op ->
            if (summaries.any { it.operationId == op.id && it.text == op.result }) null
            else AssistantChatMessage("operation:${op.id}:result", "Assistant", op.result!!, events.lastOrNull { it.operationId == op.id && it.kind == "completed" }?.atMs, op.id)
        }
        return summaries + results
    }
    companion object {
        fun from(o: JsonObject): AssistantSnapshot = AssistantSnapshot(
            protocolVersion=(o.assistantLong("protocolVersion") ?: 1).toInt(),
            acceptingMessages=o.assistantBoolean("acceptingMessages"),
            domain=(o["domain"] as? JsonObject)?.let(AssistantDomain::from),
            jobs = o.assistantObjects("jobs").mapNotNull(AssistantJob::from),
            memoryUsage = (o["memoryUsage"] as? JsonObject)?.mapValues {(_,value)->(value as? JsonArray)?.mapNotNull {(it as? JsonPrimitive)?.contentOrNull}.orEmpty()}.orEmpty(),
            memoryDisabledTasks = o.assistantStrings("memoryDisabledTasks"),
            artifacts = o.assistantObjects("artifacts").mapNotNull(AssistantArtifact::from),
            scheduleRuns = o.assistantObjects("scheduleRuns").mapNotNull(AssistantScheduleRun::from),
            recoveries = o.assistantObjects("pendingRequests").mapNotNull(AssistantRecoveryRequest::from).distinct(),
            startupStages = o.assistantObjects("startupStages").mapNotNull {s -> s.assistantText("id")?.takeIf {it in setOf("runtime","account","capabilities","coordinator")}?.let {AssistantStartupStage(it,s.assistantText("status") ?: "unknown",s.assistantText("recoveryAction"))}}.distinctBy {it.id},
            questionHistory = o.assistantObjects("questionHistory").mapNotNull {h -> h.assistantText("id")?.let {id ->
                val item = h["item"] as? JsonObject ?: return@mapNotNull null
                val native = NativeQuestionHistory.cacheSafe(TimelineItem.from(h.assistantText("operationId") ?: id, JsonObject(item + ("id" to JsonPrimitive(id))),true))
                if(NativeQuestionHistory.from(native) == null) null else AssistantQuestionHistory(id,h.assistantText("operationId"),h.assistantText("requestId"),h.assistantLong("atMs"),native)
            }},
            members = o.assistantObjects("members").mapNotNull {m -> m.assistantText("id")?.let {id -> AssistantMember(id,
                m.assistantText("name") ?: "成员", m.assistantText("duty").orEmpty(), m.assistantText("main_thread_id"), m.assistantText("directory").orEmpty(), m.assistantText("thread_id"), m.assistantBoolean("active") == true)}},
            groups = o.assistantObjects("groups").mapNotNull {g -> g.assistantText("id")?.let {id -> AssistantGroup(id, g.assistantText("title").orEmpty(),
                g.assistantStrings("task_ids"), g.assistantBoolean("requires_summary") == true, g.assistantBoolean("sealed") == true, g.assistantText("status").orEmpty())}},
            assignments = o.assistantObjects("assignments").mapNotNull {a -> a.assistantText("task_id")?.let {AssistantAssignment.from(a, it)}},
            deliveries = o.assistantObjects("deliveries").mapNotNull {d -> d.assistantText("id")?.let {id -> AssistantDelivery(id, d.assistantText("message").orEmpty(),
                d.assistantText("member_id"), d.assistantText("task_id"), d.assistantText("status").orEmpty(), d.assistantText("delivered_task_id"),d.assistantLong("at_ms"))}},
            errorCode = o.assistantText("error"), ready = o.assistantBoolean("ready"), preparationStatus = o.assistantText("preparationStatus") ?: (o["preparation"] as? JsonObject)?.assistantText("status"),
            fresh = o.assistantBoolean("fresh"), dispatchInFlight = o.assistantBoolean("dispatchInFlight") == true, assistantId = o.assistantText("assistantId"),
            enabled = o.assistantBoolean("enabled"), runtimeId = o.assistantText("runtimeId"), coordinatorThreadId = o.assistantText("coordinatorThreadId"), paused = o.assistantBoolean("paused"),
            settings = (o["settings"] as? JsonObject)?.let { s ->
                val n = s["notifications"] as? JsonObject
                if (s.assistantText("personality") == null || s.assistantText("tone") == null || s.assistantText("timezone") == null || n == null || listOf("completed", "failed", "waiting_user").any { n.assistantBoolean(it) == null }) null
                else AssistantSettings(s.assistantText("personality")!!, s.assistantText("tone")!!, s.assistantText("model"), s.assistantText("timezone")!!, AssistantNotifications(n.flag("completed"), n.flag("failed"), n.flag("waiting_user")), s.assistantText("coordinator_model"))
            },
            tasks = o.assistantObjects("tasks").mapNotNull { t -> t.assistantText("id")?.let { id ->
                AssistantTask(id, t.assistantText("prompt").orEmpty(), when (t.assistantText("status")) {
                    "queued" -> AssistantTaskStatus.Queued; "running" -> AssistantTaskStatus.Running; "waiting_user" -> AssistantTaskStatus.WaitingUser
                    "paused" -> AssistantTaskStatus.Paused; "done" -> AssistantTaskStatus.Done; "failed" -> AssistantTaskStatus.Failed; else -> AssistantTaskStatus.Unknown
                }, t.assistantText("active_run_id"), t.assistantBoolean("reconciliation_required") ?: true, t.assistantText("detail"), (t["assignment"] as? JsonObject)?.let {AssistantAssignment.from(it, id)})
            } },
            operations = o.assistantObjects("operations").mapNotNull { op -> op.assistantText("id")?.let { id ->
                AssistantOperation(id, op.assistantText("role").orEmpty(), op.assistantText("task_id"), AssistantOperationPhase.entries.firstOrNull { it.name == op.assistantText("phase") } ?: AssistantOperationPhase.Unknown,
                    op.assistantBoolean("suspended") ?: true, (op["mapping"] as? JsonObject)?.assistantText("thread_id"),
                    (op["question"] as? JsonObject)?.let { q -> q.assistantText("request_id")?.let { request ->
                        AssistantQuestion(request, AssistantQuestionKind.entries.firstOrNull { it.name == q.assistantText("kind") } ?: AssistantQuestionKind.Unknown, q.assistantText("text").orEmpty())
                    } }, op.assistantText("result"),op.assistantHeldInputs(),AssistantHandoffRecovery.from(op))
            } },
            events = o.assistantObjects("events").mapNotNull { e -> e.assistantText("id")?.let { id ->
                val sequence = e.assistantLong("sequence")?.takeIf { it >= 0 } ?: return@mapNotNull null
                AssistantEvent(sequence,id,e.assistantText("operation_id"),e.assistantText("kind").orEmpty(),e.assistantText("detail").orEmpty(),e.assistantLong("at_ms"), e.assistantText("task_id"), e.assistantText("member_id"), e.assistantText("group_id"), e.assistantText("thread_id"), e.containsKey("member_id"))
            } },
            memories = o.assistantObjects("memories").mapNotNull { m -> m.assistantText("id")?.let { (o["memoryMetadata"] as? JsonObject)?.get(it).let {raw -> val meta=raw as? JsonObject; AssistantMemory(it,m.assistantText("text").orEmpty(),meta?.assistantText("source"),meta?.assistantText("scope") ?: "personal",meta?.assistantBoolean("automatic")==true,meta?.assistantLong("updated_at_ms"),(meta?.get("revisions") as? JsonArray)?.mapNotNull {(it as? JsonPrimitive)?.longOrNull}.orEmpty())} } },
            schedules = o.assistantObjects("schedules").mapNotNull { schedule -> schedule.assistantText("id")?.let { id ->
                val spec = schedule["spec"] as? JsonObject ?: return@mapNotNull null
                AssistantSchedule(id,spec.assistantText("prompt").orEmpty(),spec["kind"] as? JsonObject ?: buildJsonObject {},
                    (spec["zone"] as? JsonObject)?.assistantText("name").orEmpty(),schedule.assistantLong("next_ms"),schedule.assistantBoolean("missed") == true,schedule.assistantText("error"),schedule.assistantBoolean("paused")==true,schedule.assistantText("missed_policy") ?: "skip",spec.assistantBoolean("writes")==true)
            } },
        )
    }
}
data class AssistantUiState(val snapshot: AssistantSnapshot? = null, val messages: List<AssistantChatMessage> = emptyList(), val loading: Boolean = false, val error: String? = null,
    val pendingActions: Set<String> = emptySet(), val nextBefore: Long? = null, val historyPaged: Boolean = false,
    val unconfirmedActions: Set<String> = emptySet(), val readError: String? = null, val modelError: String? = null,
    val computerId: String = "", val sendReceipts: List<AssistantSendReceipt> = emptyList(),
    val streamingCoordinatorOpId: String? = null, val streamingCoordinatorText: String = "",
    val liveJobStages: Map<String, AssistantLiveJobStage> = emptyMap(), val lastStreamEpoch: String? = null, val lastStreamSequence: Long = 0,
    /** Stream bookkeeping, not for display: a local frame counter and the revisions at which held entries ended. */
    val streamRevision: Long = 0, val streamCoordinatorEnd: Long = 0, val streamJobEnds: Map<String, Long> = emptyMap())
data class AssistantScheduleDraft(val prompt: String = "", val kind: String = "Once", val date: String = "", val time: String = "09:00", val weekday: Int = 0, val intervalMinutes: String = "60", val timezone: String = "Asia/Shanghai", val writes: Boolean = false)
object AssistantActions {
    private fun payload(method: String, runtime: String, fields: JsonObjectBuilder.() -> Unit = {}): AssistantAction {
        bounded(runtime,256,"请选择运行入口")
        return AssistantAction(method,buildJsonObject {put("runtimeId",runtime);fields()})
    }
    fun read(after: Long, before: Long? = null, limit: Int? = 50, taskId: String? = null): AssistantAction {
        require(after >= 0 && (before == null || before >= 0) && (limit == null || limit in 1..100))
        return AssistantAction("assistant/read",buildJsonObject {put("after",after);before?.let {put("before",it)};limit?.let {put("limit",it)};taskId?.let {bounded(it,256,"缺少任务标识");put("taskId",it)}})
    }
    fun prepare() = AssistantAction("assistant/prepare", buildJsonObject {})
    fun enable(runtime: String) = payload("assistant/enable",runtime)
    fun pause(runtime: String) = payload("assistant/pause",runtime)
    fun resume(runtime: String) = payload("assistant/resume",runtime)
    fun resumeHandoff(runtime: String, operation: String): AssistantAction {
        bounded(operation,256,"缺少原操作标识")
        return payload("assistant/resume",runtime) {put("operationId",operation)}
    }
    fun task(runtime: String, id: String, resume: Boolean): AssistantAction {
        bounded(id,256,"缺少任务标识")
        return payload(if(resume) "assistant/task/resume" else "assistant/task/pause",runtime) {put("taskId",id)}
    }
    /** Only the user brings a job's private branch into the project or drops it; each attempt is its own command. */
    fun mergeTask(runtime: String, id: String): AssistantAction {
        bounded(id,256,"缺少任务标识")
        return payload("assistant/v2/task/merge",runtime) {put("taskId",id)}
    }
    fun discardWorktree(runtime: String, id: String): AssistantAction {
        bounded(id,256,"缺少任务标识")
        return payload("assistant/v2/task/discard_worktree",runtime) {put("taskId",id)}
    }
    fun send(runtime: String, message: String, workspace: AssistantWorkspaceChoice?, memberId: String? = null, taskId: String? = null): AssistantAction {
        bounded(message,65536,"请输入消息，最多 64KiB")
        workspace?.let {bounded(it.runtimeId,256,"请选择项目来源");bounded(it.threadId,256,"请选择已授权项目");it.configStoreId?.let {store -> bounded(store,256,"项目账户标识无效")}}
        memberId?.let {bounded(it,256,"缺少成员标识")}; taskId?.let {bounded(it,256,"缺少任务标识")}
        return AssistantAction("assistant/send",buildJsonObject {
            if(runtime.isNotBlank()) put("runtimeId",runtime)
            put("message",message);workspace?.let {put("threadId",it.threadId);put("sourceRuntimeId",it.runtimeId);it.configStoreId?.let {store -> put("sourceConfigStoreId",store)}};memberId?.let {put("memberId",it)};taskId?.let {put("taskId",it)}
        })
    }
    fun respond(runtime: String, operation: String, request: String, answer: String): AssistantAction {
        bounded(answer,65536,"请输入回答，最多 64KiB")
        return human(runtime,operation,request,buildJsonObject {put("Answer",answer)})
    }
    fun approve(runtime: String, operation: String, request: String, approved: Boolean) = human(runtime,operation,request,buildJsonObject {put("Approval",buildJsonObject {put("approved",approved)})})
    private fun human(runtime: String, operation: String, request: String, response: JsonObject): AssistantAction {
        bounded(operation,256,"缺少操作标识");bounded(request,256,"缺少问题标识")
        return payload("assistant/respond",runtime) {put("operationId",operation);put("requestId",request);put("response",response)}.copy(
            commandId=java.util.UUID.nameUUIDFromBytes(JsonArray(listOf(runtime,operation,request,response.toString()).map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8)).toString())
    }
    /** `stored` is what the computer last reported: a coordinator model it already holds may be sent back unchanged even when the catalogue no longer lists it. */
    fun settings(runtime: String, settings: AssistantSettings, models: List<NativeModel>, stored: AssistantSettings? = null): AssistantAction {
        bounded(settings.personality,8192,"人格描述不能为空，最多 8KiB");bounded(settings.tone,2048,"语气不能为空，最多 2KiB")
        bounded(settings.timezone,256,"请输入有效时区");require(runCatching {ZoneId.of(settings.timezone)}.isSuccess) {"时区名称无效"}
        require(settings.model == null || models.any {it.model == settings.model}) {"当前模型未在电脑传入目录中确认"}
        require(settings.coordinatorModel == null || settings.coordinatorModel == stored?.coordinatorModel || models.any {it.model == settings.coordinatorModel}) {"助理自身模型未在电脑传入目录中确认"}
        return payload("assistant/settings/update",runtime) {put("settings",buildJsonObject {
            put("personality",settings.personality);put("tone",settings.tone);put("model",settings.model?.let(::JsonPrimitive) ?: JsonNull);put("timezone",settings.timezone)
            settings.coordinatorModel?.let {put("coordinator_model",it)}
            put("notifications",buildJsonObject {put("completed",settings.notifications.completed);put("failed",settings.notifications.failed);put("waiting_user",settings.notifications.waitingUser)})
        })}
    }
    fun memoryAdd(runtime: String, text: String): AssistantAction { bounded(text,16384,"记忆不能为空，最多 16KiB");return payload("assistant/memory/add",runtime) {put("text",text)} }
    fun memoryEdit(runtime: String, id: String, text: String): AssistantAction {bounded(id,256,"缺少记忆标识");bounded(text,16384,"记忆不能为空，最多 16KiB");return payload("assistant/memory/update",runtime) {put("memoryId",id);put("text",text)} }
    fun memoryDelete(runtime: String, id: String): AssistantAction {bounded(id,256,"缺少记忆标识");return payload("assistant/memory/delete",runtime) {put("memoryId",id)} }
    fun scheduleDelete(runtime: String, id: String): AssistantAction {bounded(id,256,"缺少定时事项标识");return payload("assistant/schedule/delete",runtime) {put("scheduleId",id)} }
    fun schedule(runtime: String, workspace: AssistantWorkspaceChoice?, draft: AssistantScheduleDraft, nowMs: Long, editing:AssistantSchedule? = null, missedPolicy:String = "skip"): AssistantAction {
        bounded(draft.prompt,65536,"请输入事项内容，最多 64KiB")
        workspace?.let {bounded(it.runtimeId,256,"请选择项目来源");bounded(it.threadId,256,"请选择已授权项目");it.configStoreId?.let {store -> bounded(store,256,"项目账户标识无效")}}
        require(!draft.writes || workspace?.canWrite==true || editing?.writes==true) {"所选项目没有写入授权"}
        require(missedPolicy in setOf("skip","run_once"))
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
        return payload(if(editing==null) "assistant/schedule/create" else "assistant/schedule/update",runtime) {
            if(editing!=null) {put("scheduleId",editing.id);put("missedPolicy",missedPolicy)} else workspace?.let {put("threadId",it.threadId);put("sourceRuntimeId",it.runtimeId);it.configStoreId?.let {store -> put("sourceConfigStoreId",store)}}
            put("spec",buildJsonObject {put("prompt",draft.prompt);put("writes",draft.writes);put("kind",kind);put("zone",zone)})
        }
    }
    fun taskMemory(runtime:String,id:String,enabled:Boolean)=payload("assistant/task/memory",runtime) {bounded(id,256,"缺少任务标识");put("taskId",id);put("enabled",enabled)}
    fun memoryUndo(runtime:String,id:String)=payload("assistant/memory/undo",runtime) {bounded(id,256,"缺少记忆标识");put("memoryId",id)}
    fun scheduleControl(runtime:String,id:String,action:String):AssistantAction {
        require(action in setOf("pause","resume","run"));bounded(id,256,"缺少定时事项标识")
        return payload("assistant/schedule/$action",runtime) {put("scheduleId",id)}
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

private fun JsonObject.assistantStrings(key: String) = (this[key] as? JsonArray)?.mapNotNull {(it as? JsonPrimitive)?.takeIf {v -> v.isString}?.content}.orEmpty()
