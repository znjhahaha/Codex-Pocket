package dev.codexpocket.app.data

import kotlinx.serialization.json.*

data class AssistantArtifact(val id:String,val taskId:String?,val operationId:String?,val name:String,val kind:String,
    val content:String?,val uri:String?,val version:String,val createdAtMs:Long?,val availability:String,val threadId:String? = null, val mimeType:String = "application/octet-stream", val sizeBytes:Long? = null, val sha256:String? = null) {
    val category get() = when {
        kind=="text" -> "text"
        kind=="link" -> "link"
        mimeType.startsWith("image/") || name.substringAfterLast('.').lowercase() in setOf("png","jpg","jpeg","webp","gif") -> "image"
        name.substringAfterLast('.').lowercase() in setOf("xlsx","xls","csv","tsv") -> "spreadsheet"
        name.substringAfterLast('.').lowercase() in setOf("pdf","docx","doc","pptx","md","txt") -> "document"
        name.substringAfterLast('.').lowercase() in setOf("patch","diff") -> "code"
        else -> "file"
    }
    val canExportText get() = kind=="text" && availability=="available" && content!=null
    val safeWebUri get() = uri?.takeIf {runCatching {java.net.URI(it).let {u -> u.scheme in setOf("https","http") && !u.host.isNullOrBlank()}}.getOrDefault(false)}
    companion object {
        fun from(o:JsonObject):AssistantArtifact? {
            val id=o.productText("id") ?: return null
            return AssistantArtifact(id,o.productText("taskId"),o.productText("operationId"),o.productText("name") ?: "成果",
                o.productText("kind") ?: "unknown",o.productText("content"),o.productText("uri"),o.productText("version").orEmpty(),
                o.productLong("createdAtMs"),o.productText("availability") ?: "unknown",o.productText("threadId"),o.productText("mimeType") ?: "application/octet-stream",o.productLong("sizeBytes"),o.productText("sha256"))
        }
    }
}
data class AssistantScheduleRun(val id:String,val scheduleId:String,val taskId:String?,val dueMs:Long?,val status:String,val manual:Boolean) {
    val statusLabel get()=when(status) {"queued"->"已排队";"running"->"执行中";"waiting_user"->"待你处理";"done"->"已完成";"failed"->"执行失败";"paused"->"已暂停";"skipped"->"已跳过";else->"待核对"}
    companion object {
        fun from(o:JsonObject):AssistantScheduleRun? {
            val id=o.productText("id") ?: return null
            val schedule=o.productText("schedule_id") ?: return null
            return AssistantScheduleRun(id,schedule,o.productText("task_id"),o.productLong("due_ms"),o.productText("status") ?: "unknown",o["manual"]==JsonPrimitive(true))
        }
    }
}

enum class AssistantTaskView { All, Pending, Results }

fun AssistantSnapshot.taskOperations(task:AssistantTask)=operations.filter {it.taskId==task.id || it.id==task.activeRunId}
fun AssistantSnapshot.taskArtifacts(taskId:String)=artifacts.filter {it.taskId==taskId}
fun AssistantSnapshot.pendingOperations()=operations.filter {it.phase==AssistantOperationPhase.Waiting && !it.suspended && it.question!=null}
fun AssistantSnapshot.taskNeedsAttention(task:AssistantTask)=taskOperations(task).any {it in pendingOperations()} || task.status==AssistantTaskStatus.WaitingUser
fun AssistantSnapshot.taskStage(task:AssistantTask):String = when {
    task.status==AssistantTaskStatus.Unknown -> "状态尚未确认，等待电脑核对"
    task.reconciliationRequired -> "正在核对原执行回执"
    taskNeedsAttention(task) -> taskOperations(task).firstOrNull {it.question!=null && it.phase==AssistantOperationPhase.Waiting}?.question?.let {if(it.kind==AssistantQuestionKind.Approval) "等待批准本次操作" else "需要你补充信息"} ?: "等待你确认下一步"
    task.status==AssistantTaskStatus.Failed && taskArtifacts(task.id).isNotEmpty() -> "已保留部分成果，查看问题后继续"
    task.status==AssistantTaskStatus.Done -> "已交付，查看成果或继续推进"
    task.status==AssistantTaskStatus.Paused -> "已暂停，确认范围后可恢复"
    task.status==AssistantTaskStatus.Queued -> "已接收，等待执行资源"
    else -> events.lastOrNull {it.taskId==task.id && it.kind=="progress"}?.detail?.take(180) ?: "正在处理交办目标"
}

/** Group by stable task sequence, never by changing event or stream timestamps. */
fun AssistantSnapshot.visibleTasks(query:String,view:AssistantTaskView):List<AssistantTask> = tasks.distinctBy {it.id}.filter {task ->
    (query.isBlank() || task.prompt.contains(query,true) || task.detail.orEmpty().contains(query,true)) && when(view) {
        AssistantTaskView.All -> true
        AssistantTaskView.Pending -> taskNeedsAttention(task)
        AssistantTaskView.Results -> taskArtifacts(task.id).isNotEmpty()
    }
}

internal fun JsonObject.productText(key:String)=(this[key] as? JsonPrimitive)?.takeIf {it.isString}?.content?.takeIf(String::isNotBlank)
internal fun JsonObject.productLong(key:String)=(this[key] as? JsonPrimitive)?.longOrNull
