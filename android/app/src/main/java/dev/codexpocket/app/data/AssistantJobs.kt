package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** `job_view` in service_jobs.rs. A write job may run on a private branch that only the user merges or discards. */
data class AssistantJobDiffStats(val files:Int,val insertions:Int,val deletions:Int)
/** The last merge attempt: `merged` carries `commit`, `conflict` its `files`, `unsupported_git` the `version`. */
data class AssistantJobMerge(val outcome:String,val atMs:Long?,val commit:String? = null,val files:List<String> = emptyList(),val version:String? = null)
data class AssistantJob(val taskId:String,val title:String,val status:String,val writes:Boolean = false,val directory:String? = null,
    val activeTaskId:String? = null,val threadId:String? = null,val model:String? = null,val workspaceMode:String? = null,val modeReason:String? = null,
    val branch:String? = null,val diffStats:AssistantJobDiffStats? = null,val readyToMerge:Boolean = false,val lastMerge:AssistantJobMerge? = null,
    val mergedAtMs:Long? = null,val discardedAtMs:Long? = null,val createdAtMs:Long? = null,val detail:String? = null) {
    companion object {
        fun from(o:JsonObject):AssistantJob? {
            val id=o.productText("taskId") ?: return null
            fun count(stats:JsonObject,key:String)=stats.productLong(key)?.takeIf {it>=0}?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            return AssistantJob(id,o.productText("title").orEmpty(),o.productText("status") ?: "unknown",o["writes"]==JsonPrimitive(true),
                o.productText("directory"),o.productText("activeTaskId"),o.productText("threadId"),o.productText("model"),
                o.productText("workspaceMode"),o.productText("modeReason"),o.productText("branch"),
                (o["diffStats"] as? JsonObject)?.let {stats -> AssistantJobDiffStats(count(stats,"files") ?: return@let null,
                    count(stats,"insertions") ?: return@let null,count(stats,"deletions") ?: return@let null)},
                o["readyToMerge"]==JsonPrimitive(true),
                (o["lastMerge"] as? JsonObject)?.let {merge -> merge.productText("outcome")?.let {outcome -> AssistantJobMerge(outcome,merge.productLong("atMs"),
                    merge.productText("commit"),(merge["files"] as? JsonArray)?.mapNotNull {(it as? JsonPrimitive)?.takeIf {v -> v.isString}?.content}.orEmpty(),
                    merge.productText("version"))}},
                o.productLong("mergedAtMs"),o.productLong("discardedAtMs"),o.productLong("createdAtMs"),o.productText("detail"))
        }
    }
}

fun AssistantSnapshot.job(taskId:String)=jobs.firstOrNull {it.taskId==taskId} ?: jobs.firstOrNull {it.activeTaskId==taskId}
