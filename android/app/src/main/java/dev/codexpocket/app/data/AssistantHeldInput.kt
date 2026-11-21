package dev.codexpocket.app.data

import kotlinx.serialization.json.*

enum class AssistantInputTerminal { Completed, Failed, Interrupted }
enum class AssistantInputResolution { Unresolved, Resolved, Unknown }
enum class AssistantInputAnswer { NotSubmitted, Unknown, Submitted }

/** History only. A terminal record never creates an answer action or a new handoff. */
data class AssistantHeldInput(val operationId:String,val callId:String,val requestId:String,val text:String,
    val terminal:AssistantInputTerminal,val resolution:AssistantInputResolution,val answer:AssistantInputAnswer) {
    val title get()=when(terminal) {
        AssistantInputTerminal.Completed->"这轮对话已结束"
        AssistantInputTerminal.Failed->"这轮对话遇到问题"
        AssistantInputTerminal.Interrupted->"这轮对话已中断"
    }
    val statusText get()=when(resolution) {
        AssistantInputResolution.Unresolved->when(answer) {
            AssistantInputAnswer.NotSubmitted->"这轮结束时未提交回答，原请求尚未确认结束。"
            AssistantInputAnswer.Unknown->"回答结果尚未确认，请先核对原请求。"
            AssistantInputAnswer.Submitted->"回答已提交，原请求尚未确认结束。"
        }
        AssistantInputResolution.Resolved->when(answer) {
            AssistantInputAnswer.NotSubmitted->"原请求已结束，未提交回答。"
            AssistantInputAnswer.Unknown->"原请求已结束，回答是否送达仍待核对。"
            AssistantInputAnswer.Submitted->"回答已提交，原请求已结束。"
        }
        AssistantInputResolution.Unknown->"原请求状态尚未确认，请先核对结果。"
    }
}

internal fun JsonObject.assistantHeldInputs():List<AssistantHeldInput> {
    val operationId=heldText("id",256) ?: return emptyList()
    val mapping=this["mapping"] as? JsonObject ?: return emptyList()
    val generation=heldText("expected_generation",256) ?: return emptyList()
    if(mapping.heldText("host_generation",256)!=generation || mapping.heldText("thread_id",256)==null || mapping.heldText("turn_id",256)==null) return emptyList()
    val records=this["held_inputs"] as? JsonArray ?: return emptyList()
    if(records.size>32) return emptyList()
    return records.mapNotNull {raw ->
        val record=raw as? JsonObject ?: return@mapNotNull null
        if(record.heldText("operation_id",256)!=operationId) return@mapNotNull null
        val scope=record["mapping"] as? JsonObject ?: return@mapNotNull null
        if(listOf("host_generation","thread_id","turn_id").any {scope.heldText(it,256)!=mapping.heldText(it,256)}) return@mapNotNull null
        val terminal=when(record.heldText("native_terminal",32)) {
            "completed"->AssistantInputTerminal.Completed
            "failed"->AssistantInputTerminal.Failed
            "interrupted"->AssistantInputTerminal.Interrupted
            else->return@mapNotNull null // Live and legacy lost questions are not reconstructed.
        }
        if(heldText("phase",32)!=when(terminal) {AssistantInputTerminal.Completed->"Completed";AssistantInputTerminal.Failed->"Failed";AssistantInputTerminal.Interrupted->"Paused"}) return@mapNotNull null
        val call=record.heldText("call_id",256) ?: return@mapNotNull null
        val question=record["question"] as? JsonObject ?: return@mapNotNull null
        if(question.heldText("kind",32)!="Input") return@mapNotNull null
        val request=question.heldText("request_id",256) ?: return@mapNotNull null
        val text=question.heldText("text",65536) ?: return@mapNotNull null
        val resolution=when(record.heldText("resolution",32)) {
            "unresolved"->AssistantInputResolution.Unresolved;"resolved"->AssistantInputResolution.Resolved;else->AssistantInputResolution.Unknown
        }
        val answer=when(record.heldText("answer_state",32)) {
            "not_submitted"->AssistantInputAnswer.NotSubmitted;"submitted"->AssistantInputAnswer.Submitted;else->AssistantInputAnswer.Unknown
        }
        AssistantHeldInput(operationId,call,request,text,terminal,resolution,answer)
    }.groupBy {it.callId}.values.mapNotNull {sameCall -> sameCall.distinct().singleOrNull()}
}

private fun JsonObject.heldText(key:String,maxBytes:Int):String? = (this[key] as? JsonPrimitive)
    ?.takeIf {it.isString}?.content?.takeIf {it.isNotBlank() && '\u0000' !in it && it.toByteArray(Charsets.UTF_8).size<=maxBytes}
