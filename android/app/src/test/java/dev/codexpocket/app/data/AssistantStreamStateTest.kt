package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Pure reduction of `assistant/stream` frames; the repository adds only connection guards and the snapshot read. */
class AssistantStreamStateTest {
    private fun operation(id:String,phase:AssistantOperationPhase)=AssistantOperation(id,"Coordinator",null,phase,false,"coordinator",null,null)
    private val receipt=AssistantSendReceipt("command","pc","managed","assistant:a","accepted")
    private val loaded=AssistantUiState(snapshot=AssistantSnapshot(enabled=true,runtimeId="managed",operations=listOf(operation("op-1",AssistantOperationPhase.Running))),
        messages=listOf(AssistantChatMessage("user:1","User","未确认的草稿")),error="原操作结果尚未确认，正在核对；不会重复提交",
        pendingActions=setOf("assistant/send"),unconfirmedActions=setOf("assistant/respond"),computerId="pc",sendReceipts=listOf(receipt))
    private fun frame(sequence:Long,kind:String,epoch:String="epoch-a",runtime:String="managed",fields:JsonObjectBuilder.()->Unit={})=buildJsonObject {
        put("runtimeId",runtime);put("generation","generation-a");put("epoch",epoch);put("sequence",sequence);put("kind",kind);fields()
    }
    private fun delta(sequence:Long,text:String,operation:String="op-1",reset:Boolean=false,epoch:String="epoch-a")=frame(sequence,"coordinator_delta",epoch) {
        put("operationId",operation);put("threadId",JsonNull);put("delta",text);put("reset",reset)
    }
    private fun done(sequence:Long,operation:String="op-1",status:String="completed")=frame(sequence,"coordinator_done") {
        put("operationId",operation);put("threadId","coordinator");put("status",status)
    }
    private fun stage(sequence:Long,task:String?,status:String,operation:String="job-op",label:String="运行测试",stage:String="command",subagents:Int=0)=frame(sequence,"job_stage") {
        put("operationId",operation);put("taskId",task?.let(::JsonPrimitive) ?: JsonNull);put("threadId","job-thread");put("status",status)
        put("stage",stage);put("stageLabel",label);put("activeSubagents",subagents)
    }
    private fun AssistantUiState.apply(vararg frames:JsonObject):AssistantUiState=frames.fold(this) {state,raw -> AssistantStream.reduce(state,raw)!!.state}
    private fun AssistantUiState.reconciles(raw:JsonObject)=AssistantStream.reduce(this,raw)!!.reconcile
    /** Everything the stream must leave alone. */
    private fun AssistantUiState.owned()=copy(streamingCoordinatorOpId=null,streamingCoordinatorText="",liveJobStages=emptyMap(),lastStreamEpoch=null,
        lastStreamSequence=0,streamRevision=0,streamCoordinatorEnd=0,streamJobEnds=emptyMap())

    @Test fun deltasAppendAndAResetReplacesTheText() {
        val first=AssistantStream.reduce(loaded,delta(1,"你"))!!
        assertFalse("The first frame of an epoch is not a gap",first.reconcile)
        val typed=first.state.apply(delta(2,"好"),delta(3,"，世界"))
        assertEquals("op-1",typed.streamingCoordinatorOpId)
        assertEquals("你好，世界",typed.streamingCoordinatorText)
        assertEquals("epoch-a" to 3L,typed.lastStreamEpoch to typed.lastStreamSequence)
        assertFalse(typed.reconciles(delta(4,"重写",reset=true)))
        assertEquals("重写",typed.apply(delta(4,"重写",reset=true)).streamingCoordinatorText)
        assertEquals("重写后续",typed.apply(delta(4,"重写",reset=true),delta(5,"后续")).streamingCoordinatorText)
    }

    @Test fun aDeltaForAnotherOperationStartsItsOwnText() {
        val next=loaded.apply(delta(1,"第一段"),delta(2,"第二轮","op-2"))
        assertEquals("op-2",next.streamingCoordinatorOpId)
        assertEquals("第二轮",next.streamingCoordinatorText)
        assertEquals("第二轮继续",next.apply(delta(3,"继续","op-2")).streamingCoordinatorText)
    }

    @Test fun liveTextKeepsOnlyItsNewestSixtyFourKibibytes() {
        val long=loaded.apply(delta(1,"头".repeat(AssistantStream.MAX_TEXT)),delta(2,"尾巴"))
        assertEquals(AssistantStream.MAX_TEXT,long.streamingCoordinatorText.length)
        assertTrue(long.streamingCoordinatorText.endsWith("头尾巴"))
        val oversized=loaded.apply(delta(1,"a".repeat(AssistantStream.MAX_TEXT*2)+"结尾",reset=true))
        assertEquals(AssistantStream.MAX_TEXT,oversized.streamingCoordinatorText.length)
        assertTrue(oversized.streamingCoordinatorText.endsWith("结尾"))
        val pair=loaded.apply(delta(1,"😀".repeat(AssistantStream.MAX_TEXT/2)),delta(2,"x"))
        assertFalse("A cut must not leave half of a surrogate pair",pair.streamingCoordinatorText.first().isLowSurrogate())
    }

    @Test fun aSequenceGapClearsTransientStateAndAsksForTheSnapshot() {
        val typed=loaded.apply(delta(1,"你好"),stage(2,"task-1","running"))
        for(sequence in listOf(4L,2L,1L,90L)) {
            val skipped=AssistantStream.reduce(typed,stage(sequence,"task-2","running"))!!
            assertTrue("sequence $sequence after 2",skipped.reconcile)
            assertNull(skipped.state.streamingCoordinatorOpId)
            assertEquals("",skipped.state.streamingCoordinatorText)
            assertEquals(setOf("task-2"),skipped.state.liveJobStages.keys)
            assertEquals("epoch-a" to sequence,skipped.state.lastStreamEpoch to skipped.state.lastStreamSequence)
        }
        val resumed=AssistantStream.reduce(typed,delta(9,"片段"))!!
        assertTrue(resumed.reconcile)
        assertEquals("Text from before the gap is never joined to text after it","片段",resumed.state.streamingCoordinatorText)
        assertFalse("The adopted sequence continues without another read",resumed.state.reconciles(delta(10,"继续")))
        assertTrue("A first frame that is not the first of its epoch missed something",loaded.reconciles(delta(2,"迟到")))
    }

    @Test fun aNewEpochClearsTransientStateAndAsksForTheSnapshot() {
        val typed=loaded.apply(delta(1,"旧进程的文字"),stage(2,"task-1","running"))
        val restarted=AssistantStream.reduce(typed,delta(1,"新进程","op-2",epoch="epoch-b"))!!
        assertTrue(restarted.reconcile)
        assertEquals("新进程",restarted.state.streamingCoordinatorText)
        assertTrue(restarted.state.liveJobStages.isEmpty())
        assertEquals("epoch-b" to 1L,restarted.state.lastStreamEpoch to restarted.state.lastStreamSequence)
        assertTrue("Sequence continuity never joins two epochs",typed.reconciles(delta(3,"接续",epoch="epoch-b")))
    }

    @Test fun framesForAnotherRuntimeOrBeforeAnySnapshotAreDropped() {
        assertNull(AssistantStream.reduce(loaded,frame(1,"coordinator_delta",runtime="other-runtime") {put("operationId","op-1");put("delta","x");put("reset",false)}))
        assertNull(AssistantStream.reduce(AssistantUiState(computerId="pc"),delta(1,"x")))
        assertNull(AssistantStream.reduce(loaded.copy(snapshot=loaded.snapshot!!.copy(runtimeId=null)),delta(1,"x")))
        val typed=loaded.apply(delta(1,"你好"))
        val replaced=typed.copy(snapshot=typed.snapshot!!.copy(runtimeId="other-runtime")).streamReconciled("managed",typed.streamRevision)
        assertNull("A snapshot from another runtime owns none of the held text",replaced.streamingCoordinatorOpId)
        assertNull(replaced.lastStreamEpoch)
        assertTrue(replaced.streamRevision>typed.streamRevision)
    }

    @Test fun coordinatorDoneKeepsTheTextUntilTheSnapshotReadAfterItIsStored() {
        val typing=loaded.apply(delta(1,"最终"),delta(2,"回复"))
        val requestedBeforeDone=typing.streamRevision
        val finished=AssistantStream.reduce(typing,done(3))!!
        assertTrue(finished.reconcile)
        assertEquals("The reply stays visible until its message can replace it","最终回复",finished.state.streamingCoordinatorText)
        val stale=finished.state.streamReconciled("managed",requestedBeforeDone)
        assertEquals("A read requested before the turn ended cannot hold its message","最终回复",stale.streamingCoordinatorText)
        assertEquals("最终回复",finished.state.streamReconciled("managed",-1).streamingCoordinatorText)
        assertEquals("最终回复",finished.state.streamReconciled("managed",null).streamingCoordinatorText)
        val stored=finished.state.streamReconciled("managed",finished.state.streamRevision)
        assertNull(stored.streamingCoordinatorOpId)
        assertEquals("",stored.streamingCoordinatorText)
        assertEquals("epoch-a" to 3L,stored.lastStreamEpoch to stored.lastStreamSequence)
        assertFalse("The frames after it are still in order",stored.reconciles(delta(4,"下一轮","op-2")))
    }

    @Test fun aSnapshotThatAlreadyShowsTheTurnFinishedReplacesTheTextAtOnce() {
        val typing=loaded.apply(delta(1,"最终回复"))
        val completed=typing.copy(snapshot=typing.snapshot!!.copy(operations=listOf(operation("op-1",AssistantOperationPhase.Completed))))
        assertNull("Shown once: by the snapshot, not also by the stream",completed.streamReconciled("managed",-1).streamingCoordinatorOpId)
        assertEquals("A page of older messages cannot show the final one, so it replaces nothing","最终回复",completed.streamReconciled("managed",null).streamingCoordinatorText)
        val late=completed.streamReconciled("managed",-1).apply(delta(2,"迟到的片段"))
        assertNull("Frames trailing that snapshot do not bring the reply back",late.streamingCoordinatorOpId)
        assertEquals(2L,late.lastStreamSequence)
        val running=typing.streamReconciled("managed",typing.streamRevision)
        assertEquals("A turn still running keeps its text across ordinary polls","最终回复",running.streamingCoordinatorText)
    }

    @Test fun doneNeverClearsTheTextOfANewerOperation() {
        val newer=loaded.apply(delta(1,"第一轮"),done(2),delta(3,"第二轮","op-2"))
        val stored=newer.streamReconciled("managed",newer.streamRevision)
        assertEquals("op-2",stored.streamingCoordinatorOpId)
        assertEquals("第二轮",stored.streamingCoordinatorText)
        val unrelated=loaded.apply(delta(1,"第二轮","op-2"),done(2,"op-1"))
        assertEquals("第二轮",unrelated.streamReconciled("managed",unrelated.streamRevision).streamingCoordinatorText)
        val resumed=loaded.apply(delta(1,"暂停前"),done(2,status="paused"),delta(3,"恢复后"))
        assertEquals("Text that kept arriving is not treated as ended","暂停前恢复后",resumed.streamReconciled("managed",resumed.streamRevision).streamingCoordinatorText)
    }

    @Test fun jobStagesFollowFramesAndLeaveWithTheSnapshotAfterATerminalStatus() {
        val running=loaded.apply(stage(1,"task-1","running",subagents=2),stage(2,null,"waiting_approval","orphan-op",stage="",label=""))
        assertEquals(AssistantLiveJobStage("running","command","运行测试",2,"job-op","job-thread"),running.liveJobStages["task-1"])
        assertEquals("A job without a task is keyed by its operation","waiting_approval",running.liveJobStages["orphan-op"]!!.status)
        assertFalse(running.reconciles(stage(3,"task-1","running",stage="file_change",label="修改文件")))
        val edited=running.apply(stage(3,"task-1","running",stage="file_change",label="修改文件"))
        assertEquals("file_change" to "修改文件",edited.liveJobStages["task-1"]!!.let {it.stage to it.stageLabel})
        assertEquals(2,edited.liveJobStages.size)
        for(status in AssistantStream.TERMINAL) assertTrue(status,edited.reconciles(stage(4,"task-1",status)))
        for(status in listOf("waiting_input","stop_requested","needs_verification")) assertFalse(status,edited.reconciles(stage(4,"task-1",status)))
        val requestedBefore=edited.streamRevision
        val finished=edited.apply(stage(4,"task-1","completed"))
        assertTrue(finished.liveJobStages["task-1"]!!.terminal)
        assertEquals(setOf("task-1","orphan-op"),finished.streamReconciled("managed",requestedBefore).liveJobStages.keys)
        val stored=finished.streamReconciled("managed",finished.streamRevision)
        assertEquals("Only the finished job leaves; the waiting one is still live",setOf("orphan-op"),stored.liveJobStages.keys)
        val continued=finished.apply(stage(5,"task-1","running",label="继续"))
        assertEquals("A job that runs again is not removed by its earlier ending",setOf("task-1","orphan-op"),
            continued.streamReconciled("managed",continued.streamRevision).liveJobStages.keys)
    }

    @Test fun stageLabelsAndTheNumberOfLiveJobsAreBounded() {
        val labelled=loaded.apply(stage(1,"task-1","running",label="长".repeat(500)))
        assertEquals(AssistantStream.MAX_LABEL,labelled.liveJobStages["task-1"]!!.stageLabel.length)
        val many=(1..AssistantStream.MAX_JOBS+5).fold(loaded) {state,index -> state.apply(stage(index.toLong(),"task-$index",if(index<=3) "failed" else "running"))}
        assertEquals(AssistantStream.MAX_JOBS,many.liveJobStages.size)
        assertFalse("task-1" in many.liveJobStages)
        assertTrue("task-${AssistantStream.MAX_JOBS+5}" in many.liveJobStages)
        assertTrue(many.streamJobEnds.keys.all {it in many.liveJobStages})
    }

    @Test fun attentionFramesOnlyAskForTheSnapshot() {
        val typed=loaded.apply(delta(1,"你好"))
        for(kind in listOf("attention_opened","attention_resolved")) {
            val raw=frame(2,kind) {put("operationId","job-op");put("requestId","request-1");put("taskId","task-1");put("threadId",JsonNull);put("questionKind","approval")}
            val reduced=AssistantStream.reduce(typed,raw)!!
            assertTrue(reduced.reconcile)
            assertEquals(typed.copy(lastStreamSequence=2,streamRevision=typed.streamRevision+1),reduced.state)
            assertFalse("The question's content is never taken from a frame",reduced.state.toString().contains("request-1"))
        }
        val unknown=AssistantStream.reduce(typed,frame(2,"added_later") {put("operationId",7)})!!
        assertFalse("A kind added later still keeps its place in the sequence",unknown.reconcile)
        assertEquals(2L,unknown.state.lastStreamSequence)
        assertEquals("你好",unknown.state.streamingCoordinatorText)
    }

    @Test fun skippedNotificationsLetTheNextSnapshotReplaceWhatNoFrameRenews() {
        val held=loaded.apply(delta(1,"也许已经结束"),stage(2,"task-1","running"),stage(3,"task-2","running"))
        val suspect=held.withStreamUnverified()
        assertEquals("Nothing disappears before a snapshot lands","也许已经结束",suspect.streamingCoordinatorText)
        assertEquals(held.streamReconciled("managed",held.streamRevision),held)
        val renewed=suspect.apply(delta(4,"，其实还在继续"),stage(5,"task-2","running",label="仍在运行"))
        val stored=renewed.streamReconciled("managed",renewed.streamRevision)
        assertEquals("也许已经结束，其实还在继续",stored.streamingCoordinatorText)
        assertEquals(setOf("task-2"),stored.liveJobStages.keys)
        val silent=suspect.streamReconciled("managed",suspect.streamRevision)
        assertNull(silent.streamingCoordinatorOpId)
        assertTrue(silent.liveJobStages.isEmpty())
        assertSame(loaded,loaded.withStreamUnverified())
    }

    @Test fun unconfirmedDraftsReceiptsAndPendingActionsAreNeverTouched() {
        val frames=listOf(delta(1,"你好"),stage(2,"task-1","running"),done(3),stage(4,"task-1","completed"),
            frame(5,"attention_opened") {put("operationId","job-op");put("requestId","request-1")},delta(9,"断档后"),delta(1,"新进程","op-2",epoch="epoch-b"))
        var state=loaded
        for(raw in frames) {
            state=state.apply(raw)
            assertEquals(loaded,state.owned())
            assertEquals(loaded,state.streamReconciled("managed",state.streamRevision).owned())
            assertEquals(loaded,state.streamReconciled("managed",-1).owned())
            assertEquals(loaded,state.streamReconciled("managed",null).owned())
            assertEquals(loaded,state.withStreamUnverified().owned())
            assertEquals(loaded,state.withoutStream().owned())
        }
        assertEquals(setOf("assistant/send"),state.pendingActions)
        assertEquals(setOf("assistant/respond"),state.unconfirmedActions)
        assertEquals(listOf(receipt),state.sendReceipts)
        assertEquals("未确认的草稿",state.messages.single().text)
        assertSame("Nothing held means nothing to clear",loaded,loaded.withoutStream())
    }

    @Test fun malformedFramesAreIgnoredWithoutThrowing() {
        val typed=loaded.apply(delta(1,"你好"))
        fun without(raw:JsonObject,key:String)=JsonObject(raw-key)
        fun with(raw:JsonObject,key:String,value:JsonElement)=JsonObject(raw+(key to value))
        val valid=delta(2,"x")
        val job=stage(2,"task-1","running")
        val attention=frame(2,"attention_opened") {put("operationId","job-op");put("requestId","request-1")}
        val long=JsonPrimitive("i".repeat(257))
        val malformed=listOf(buildJsonObject {},without(valid,"runtimeId"),without(valid,"epoch"),without(valid,"sequence"),without(valid,"kind"),
            without(valid,"operationId"),without(valid,"delta"),without(done(2),"status"),without(job,"status"),without(job,"operationId"),without(attention,"requestId"),
            with(valid,"sequence",JsonPrimitive("2")),with(valid,"sequence",JsonPrimitive(2.5)),with(valid,"sequence",JsonPrimitive(0)),with(valid,"sequence",JsonPrimitive(-1)),
            with(valid,"sequence",JsonNull),with(valid,"runtimeId",JsonPrimitive(7)),with(valid,"epoch",buildJsonObject {}),with(valid,"kind",JsonPrimitive(true)),
            with(valid,"generation",JsonPrimitive(3)),with(valid,"operationId",buildJsonArray {}),with(valid,"operationId",JsonPrimitive(" ")),with(valid,"delta",JsonPrimitive(1)),
            with(valid,"delta",buildJsonArray {add("x")}),with(valid,"reset",JsonPrimitive("true")),with(valid,"threadId",JsonPrimitive(9)),
            with(job,"activeSubagents",JsonPrimitive(-1)),with(job,"activeSubagents",JsonPrimitive("2")),with(job,"stageLabel",JsonPrimitive(4)),with(job,"stage",buildJsonObject {}),
            with(job,"taskId",JsonPrimitive(false)),with(job,"status",JsonPrimitive("")),with(attention,"requestId",JsonPrimitive(12)),
            with(valid,"runtimeId",long),with(valid,"epoch",long),with(valid,"operationId",long),with(valid,"threadId",long),with(valid,"generation",long),
            with(job,"taskId",long),with(job,"status",JsonPrimitive("s".repeat(65))),with(job,"stage",JsonPrimitive("s".repeat(65))),with(attention,"requestId",long),
            with(valid,"operationId",JsonPrimitive("a\u0000b")))
        malformed.forEachIndexed {index,raw ->
            assertNull("frame $index: $raw",AssistantStreamFrame.from(raw))
            assertNull("frame $index",AssistantStream.reduce(typed,raw))
        }
        assertEquals("Fields a display line can do without default instead","" to 0,
            AssistantStreamFrame.from(JsonObject(job-"stage"-"stageLabel"-"activeSubagents"-"threadId"-"taskId"))!!.let {it.stageLabel to it.activeSubagents})
        assertFalse(AssistantStreamFrame.from(without(valid,"reset"))!!.reset)
        assertNotNull(AssistantStreamFrame.from(without(valid,"generation")))
        val ignored=AssistantStream.reduce(typed,valid)!!
        assertFalse("Ignoring a malformed frame consumed no sequence number",ignored.reconcile)
        assertEquals("你好x",ignored.state.streamingCoordinatorText)
    }
}
