package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** Stable product identities, independent of a replaceable native coordinator turn. */
data class AssistantTaskRecord(val id:String,val sequence:Long,val state:String,val parentId:String?,
    val continuesId:String?,val activitySequence:Long,val unread:Boolean)
data class AssistantAttention(val id:String,val kind:String,val state:String,val taskId:String?,val attemptId:String,
    val requestId:String?,val title:String)
data class AssistantMemoryRevision(val memoryId:String,val revision:Long,val text:String?,val state:String,val source:String?)
data class AssistantDomain(val conversationId:String,val tasks:List<AssistantTaskRecord>,val attention:List<AssistantAttention>,
    val memoryRevisions:List<AssistantMemoryRevision>) {
    companion object {
        fun from(o:JsonObject):AssistantDomain? {
            if(o.productLong("schema")!=2L) return null
            val conversation=o.productText("conversationId") ?: return null
            fun objects(key:String)=(o[key] as? JsonArray)?.mapNotNull {it as? JsonObject}.orEmpty()
            return AssistantDomain(conversation,objects("tasks").mapNotNull {t->
                t.productText("id")?.let {AssistantTaskRecord(it,t.productLong("sequence") ?: 0,t.productText("state") ?: "unknown",
                    t.productText("parentTaskId"),t.productText("continuesTaskId"),t.productLong("activitySequence") ?: 0,t["unread"]==JsonPrimitive(true))}
            },objects("attention").mapNotNull {a->
                val id=a.productText("id") ?: return@mapNotNull null
                val attempt=a.productText("attemptId") ?: return@mapNotNull null
                AssistantAttention(id,a.productText("kind") ?: "unknown",a.productText("state") ?: "unknown",a.productText("taskId"),attempt,a.productText("requestId"),a.productText("title").orEmpty())
            },objects("memoryRevisions").mapNotNull {m->m.productText("memoryId")?.let {
                AssistantMemoryRevision(it,m.productLong("revision") ?: 0,m.productText("text"),m.productText("state") ?: "unknown",m.productText("source"))
            }})
        }
    }
}

fun AssistantSnapshot.taskRecord(id:String)=domain?.tasks?.firstOrNull {it.id==id}
fun AssistantSnapshot.orderedTasks():List<AssistantTask> = if(domain==null) tasks.distinctBy {it.id} else
    tasks.distinctBy {it.id}.sortedBy {taskRecord(it.id)?.sequence ?: Long.MAX_VALUE}
fun AssistantSnapshot.taskStateLabel(task:AssistantTask):String = when(taskRecord(task.id)?.state) {
    "stop_requested"->"正在停止，等待确认"
    "needs_verification"->"待核对原执行"
    else->when(task.status) {
        AssistantTaskStatus.Queued->"已接收";AssistantTaskStatus.Running->"执行中";AssistantTaskStatus.WaitingUser->"待你处理"
        AssistantTaskStatus.Paused->"已停止";AssistantTaskStatus.Done->"已交付";AssistantTaskStatus.Failed->"未完成";AssistantTaskStatus.Unknown->"待核对"
    }
}
fun AssistantSnapshot.canStartIndependentMessage()=protocolVersion>=2 && acceptingMessages==true

fun assistantRetry(runtime:String,task:String)=AssistantAction("assistant/v2/task/retry",buildJsonObject {put("runtimeId",runtime);put("taskId",task)})
fun assistantSeen(runtime:String,task:String,sequence:Long)=AssistantAction("assistant/v2/task/seen",buildJsonObject {
    put("runtimeId",runtime);put("taskId",task);put("throughSequence",sequence)
})
