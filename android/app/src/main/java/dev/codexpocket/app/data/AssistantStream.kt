package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** One display-only line about what a dispatched job is doing now. The snapshot stays the only truth. */
data class AssistantLiveJobStage(val status:String,val stage:String,val stageLabel:String,val activeSubagents:Int,
    val operationId:String,val threadId:String? = null) {
    val terminal get() = status in AssistantStream.TERMINAL
}

/** A validated `assistant/stream` notification. It names what moved; anything to act on is read from the snapshot. */
data class AssistantStreamFrame(val runtimeId:String,val epoch:String,val sequence:Long,val kind:String,
    val operationId:String? = null,val taskId:String? = null,val threadId:String? = null,val requestId:String? = null,
    val delta:String = "",val reset:Boolean = false,val status:String = "",val stage:String = "",val stageLabel:String = "",
    val activeSubagents:Int = 0) {
    companion object {
        /** Null for anything this client cannot place: a missing identity, a wrong type or an oversized identifier. */
        fun from(o:JsonObject):AssistantStreamFrame? {
            val f=StreamFields(o)
            val runtime=f.id("runtimeId",true);val epoch=f.id("epoch",true);val kind=f.word("kind",true);val sequence=f.integer("sequence",true)
            f.id("generation")
            if(f.malformed || runtime==null || epoch==null || kind==null || sequence==null || sequence<1) return null
            val frame=when(kind) {
                "coordinator_delta" -> AssistantStreamFrame(runtime,epoch,sequence,kind,f.id("operationId",true),threadId=f.id("threadId"),
                    delta=f.text("delta",true).orEmpty().streamTail(),reset=f.flag("reset") ?: false)
                "coordinator_done" -> AssistantStreamFrame(runtime,epoch,sequence,kind,f.id("operationId",true),threadId=f.id("threadId"),
                    status=f.word("status",true).orEmpty())
                "job_stage" -> AssistantStreamFrame(runtime,epoch,sequence,kind,f.id("operationId",true),f.id("taskId"),f.id("threadId"),
                    status=f.word("status",true).orEmpty(),stage=f.word("stage").orEmpty(),stageLabel=f.text("stageLabel").orEmpty().streamLabel(),
                    activeSubagents=(f.integer("activeSubagents") ?: 0).also {if(it<0) f.malformed=true}.coerceIn(0,Int.MAX_VALUE.toLong()).toInt())
                "attention_opened", "attention_resolved" -> {
                    f.word("questionKind")
                    AssistantStreamFrame(runtime,epoch,sequence,kind,f.id("operationId",true),f.id("taskId"),f.id("threadId"),f.id("requestId",true))
                }
                // A kind added later still occupies its place in the sequence.
                else -> AssistantStreamFrame(runtime,epoch,sequence,kind)
            }
            return frame.takeUnless {f.malformed}
        }
    }
}

/** `reconcile` asks for one snapshot read; the frame itself never replaces what the snapshot says. */
data class AssistantStreamReduction(val state:AssistantUiState,val reconcile:Boolean)

object AssistantStream {
    const val CAPABILITY="assistant.stream.v1"
    /** UTF-16 units of live coordinator text kept; older text falls off the front until the snapshot replaces it. */
    const val MAX_TEXT=65536
    const val MAX_LABEL=80
    const val MAX_JOBS=256
    val TERMINAL=setOf("completed","failed","paused")
    /** Null when the frame is malformed or is not from the runtime this assistant state was read from. */
    fun reduce(state:AssistantUiState,raw:JsonObject):AssistantStreamReduction? {
        val frame=AssistantStreamFrame.from(raw) ?: return null
        if(state.snapshot?.runtimeId!=frame.runtimeId) return null
        return state.reduceStream(frame)
    }
}

/** Only stream fields change here: drafts, receipts and pending or unconfirmed actions are never touched. */
fun AssistantUiState.reduceStream(frame:AssistantStreamFrame):AssistantStreamReduction {
    val contiguous=if(lastStreamEpoch==null) frame.sequence==1L else frame.epoch==lastStreamEpoch && frame.sequence==lastStreamSequence+1
    // A restarted worker, a skipped frame or a repeated one: what was accumulated may no longer be true.
    val known=if(contiguous) this else withoutStream()
    val base=known.copy(lastStreamEpoch=frame.epoch,lastStreamSequence=frame.sequence,streamRevision=known.streamRevision+1)
    val operation=frame.operationId
    return when {
        frame.kind=="coordinator_delta" && operation!=null -> AssistantStreamReduction(when {
            // Frames can trail a snapshot that already holds this turn's final message.
            snapshot.streamFinished(operation) -> if(base.streamingCoordinatorOpId==operation) base.withoutCoordinatorStream() else base
            else -> base.copy(streamingCoordinatorOpId=operation,streamCoordinatorEnd=0,streamingCoordinatorText=
                ((if(base.streamingCoordinatorOpId==operation && !frame.reset) base.streamingCoordinatorText else "")+frame.delta).streamTail())
        },!contiguous)
        // The text stays until the snapshot read after this frame is stored; see streamReconciled.
        frame.kind=="coordinator_done" -> AssistantStreamReduction(
            if(operation!=null && base.streamingCoordinatorOpId==operation && base.streamCoordinatorEnd==0L) base.copy(streamCoordinatorEnd=base.streamRevision) else base,true)
        frame.kind=="job_stage" && operation!=null -> {
            val key=frame.taskId ?: operation
            val stage=AssistantLiveJobStage(frame.status,frame.stage,frame.stageLabel,frame.activeSubagents,operation,frame.threadId)
            val stages=base.liveJobStages+(key to stage)
            val ends=if(stage.terminal) base.streamJobEnds+(key to base.streamRevision) else base.streamJobEnds-key
            val dropped=stages.keys.take((stages.size-AssistantStream.MAX_JOBS).coerceAtLeast(0)).toSet()
            AssistantStreamReduction(base.copy(liveJobStages=stages-dropped,streamJobEnds=ends-dropped),!contiguous || stage.terminal)
        }
        frame.kind in setOf("attention_opened","attention_resolved") -> AssistantStreamReduction(base,true)
        else -> AssistantStreamReduction(base,!contiguous)
    }
}

/** A new link, worker or sequence starts with nothing held. The revision only grows, so an older read settles nothing newer. */
fun AssistantUiState.withoutStream():AssistantUiState =
    if(streamingCoordinatorOpId==null && liveJobStages.isEmpty() && streamJobEnds.isEmpty() && lastStreamEpoch==null && lastStreamSequence==0L) this
    else copy(streamingCoordinatorOpId=null,streamingCoordinatorText="",liveJobStages=emptyMap(),lastStreamEpoch=null,lastStreamSequence=0,
        streamCoordinatorEnd=0,streamJobEnds=emptyMap(),streamRevision=streamRevision+1)

/** Notifications were skipped, so every held entry may already be over: the next fresh snapshot replaces what no frame renews. */
fun AssistantUiState.withStreamUnverified():AssistantUiState {
    if(streamingCoordinatorOpId==null && liveJobStages.isEmpty()) return this
    val revision=streamRevision+1
    return copy(streamRevision=revision,streamCoordinatorEnd=if(streamingCoordinatorOpId!=null && streamCoordinatorEnd==0L) revision else streamCoordinatorEnd,
        streamJobEnds=liveJobStages.keys.associateWith {streamJobEnds[it] ?: revision})
}

/**
 * Applied in the same state update that stores a snapshot, so a finished reply is neither dropped before its message
 * is present nor shown beside it. `mark` is the stream revision observed before that read was requested, -1 for a
 * cached snapshot that cannot be newer than that, and null for a page that does not hold the newest messages.
 */
fun AssistantUiState.streamReconciled(previousRuntimeId:String?,mark:Long?):AssistantUiState {
    if(snapshot?.runtimeId!=previousRuntimeId) return withoutStream()
    if(mark==null) return this
    val operation=streamingCoordinatorOpId
    val coordinatorOver=operation!=null && (streamCoordinatorEnd in 1..mark || snapshot.streamFinished(operation))
    val settled=streamJobEnds.filterValues {it<=mark}.keys
    if(!coordinatorOver && settled.isEmpty()) return this
    return (if(coordinatorOver) withoutCoordinatorStream() else this).let {it.copy(liveJobStages=it.liveJobStages-settled,streamJobEnds=it.streamJobEnds-settled)}
}

private fun AssistantUiState.withoutCoordinatorStream()=copy(streamingCoordinatorOpId=null,streamingCoordinatorText="",streamCoordinatorEnd=0)
private fun AssistantSnapshot?.streamFinished(operation:String)=this?.operations?.any {
    it.id==operation && it.phase in setOf(AssistantOperationPhase.Completed,AssistantOperationPhase.Failed)}==true
private fun String.streamTail()=if(length<=AssistantStream.MAX_TEXT) this else substring(length-AssistantStream.MAX_TEXT).let {if(it[0].isLowSurrogate()) it.substring(1) else it}
private fun String.streamLabel()=if(length<=AssistantStream.MAX_LABEL) this else take(AssistantStream.MAX_LABEL).let {if(it.last().isHighSurrogate()) it.dropLast(1) else it}

/** Absent and null are the same; a present value of the wrong type or size makes the whole frame unusable. */
private class StreamFields(private val o:JsonObject) {
    var malformed=false
    private fun string(key:String,required:Boolean):String? {
        val raw=o[key]?.takeUnless {it is JsonNull}
        if(raw==null) {if(required) malformed=true;return null}
        return (raw as? JsonPrimitive)?.takeIf {it.isString}?.content ?: run {malformed=true;null}
    }
    fun id(key:String,required:Boolean=false)=string(key,required)?.let {if(it.isBlank() || it.length>256 || '\u0000' in it) {malformed=true;null} else it}
    fun word(key:String,required:Boolean=false)=string(key,required)?.let {if((required && it.isBlank()) || it.length>64 || '\u0000' in it) {malformed=true;null} else it}
    fun text(key:String,required:Boolean=false)=string(key,required)
    fun integer(key:String,required:Boolean=false):Long? {
        val raw=o[key]?.takeUnless {it is JsonNull}
        if(raw==null) {if(required) malformed=true;return null}
        return (raw as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull ?: run {malformed=true;null}
    }
    fun flag(key:String):Boolean? {
        val raw=o[key]?.takeUnless {it is JsonNull} ?: return null
        return (raw as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull ?: run {malformed=true;null}
    }
}
