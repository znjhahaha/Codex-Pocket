package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Test

/** Pure timeline projection tests: no Compose, Android runtime, clock, or transport. */
class AssistantChatTimelineTest {
    @Test fun groupSynthesisKeepsMemberResultInDetailsButPreservesFailureAndWaitingCards() {
        val task=AssistantTask("group-task","综合核查",AssistantTaskStatus.Failed,"execution",false,"来源核查失败",AssistantAssignment("group-task","member","group"))
        val execution=AssistantOperation("execution","Execution","group-task",AssistantOperationPhase.Failed,false,"native",null,"成员失败详情")
        val question=execution.copy(id="waiting",phase=AssistantOperationPhase.Waiting,question=AssistantQuestion("q",AssistantQuestionKind.Approval,"允许读取？"),result=null)
        val state=AssistantUiState(AssistantSnapshot(tasks=listOf(task),operations=listOf(execution,question),
            groups=listOf(AssistantGroup("group","综合任务",listOf("group-task"),true,true,"incomplete"))))
        val timeline=assistantTimeline(state)
        assertTrue(timeline.none {it is AssistantTimeline.Result})
        assertEquals(AssistantTaskStatus.Failed,timeline.filterIsInstance<AssistantTimeline.Group>().single().tasks.single().status)
        assertEquals("waiting",timeline.filterIsInstance<AssistantTimeline.Question>().single().operation.id)
    }
    @Test fun relatedTaskGroupProducesOneSummaryWithoutDuplicateTaskRows() {
        val first=task("first","one");val second=task("second","two");val ungrouped=task("other",null)
        val snapshot=AssistantSnapshot(tasks=listOf(first,second,ungrouped),groups=listOf(AssistantGroup("g","调整助理界面",listOf("first","second"),true,true,"running")))
        val timeline=assistantTimeline(AssistantUiState(snapshot))
        assertEquals(listOf("group:g","task:other"),timeline.map {it.key})
        assertEquals(listOf("first","second"),timeline.filterIsInstance<AssistantTimeline.Group>().single().tasks.map {it.id})
    }

    private fun task(id: String, operationId: String?) = AssistantTask(
        id, "任务 $id", AssistantTaskStatus.Running, operationId, false, null)

    private fun operation(
        id: String,
        role: String = "Execution",
        phase: AssistantOperationPhase = AssistantOperationPhase.Running,
        question: AssistantQuestion? = null,
        result: String? = null,
    ) = AssistantOperation(id, role, null, phase, false, "thread-$id", question, result)

    private fun event(
        id: String,
        operationId: String?,
        atMs: Long?,
        kind: String = "started",
        detail: String = "",
        sequence: Long = 1,
    ) = AssistantEvent(sequence, id, operationId, kind, detail, atMs)

    @Test fun onlyUserAndAssistantMessagesEnterTheTimeline() {
        val user = AssistantChatMessage("user", "User", "真实用户消息", 100L)
        val assistant = AssistantChatMessage("assistant", "Assistant", "真实助理消息", 200L)
        val hidden = listOf("System", "Developer", "Tool", "Coordinator", "user", "assistant", "").mapIndexed { index, role ->
            AssistantChatMessage("hidden-$index", role, "内部内容", 0L)
        }

        val timeline = assistantTimeline(AssistantUiState(
            snapshot = AssistantSnapshot(), messages = hidden + listOf(user, assistant)))

        assertEquals(listOf("message:user", "message:assistant"), timeline.map { it.key })
        assertEquals(listOf(user, assistant), timeline.filterIsInstance<AssistantTimeline.Message>().map { it.message })
    }

    @Test fun coordinatorResultAndMatchingSummaryDoNotDuplicateAnExistingReply() {
        val nativeReply = AssistantChatMessage("native", "Assistant", "已整理两项待办", 1_000L, "coordinator")
        val snapshot = AssistantSnapshot(
            operations = listOf(operation("coordinator", role = "Coordinator", phase = AssistantOperationPhase.Completed, result = nativeReply.text)),
            events = listOf(event("summary", "coordinator", 1_200L, kind = "summary", detail = nativeReply.text)),
        )

        val timeline = assistantTimeline(AssistantUiState(snapshot, listOf(nativeReply, nativeReply)))

        assertEquals("message:native", timeline.single().key)
        assertEquals(nativeReply, (timeline.single() as AssistantTimeline.Message).message)
        // A real message timestamp wins over a different summary timestamp.
        assertEquals(1_000L, timeline.single().atMs)
    }

    @Test fun summaryAndCompletedCoordinatorResultProduceOneReplyWithoutNativeHistory() {
        val snapshot = AssistantSnapshot(
            operations = listOf(operation("coordinator", role = "Coordinator", phase = AssistantOperationPhase.Completed, result = "真实结果")),
            events = listOf(event("summary", "coordinator", 500L, kind = "summary", detail = "真实结果")),
        )

        val reply = assistantTimeline(AssistantUiState(snapshot)).single() as AssistantTimeline.Message

        assertEquals("message:event:summary", reply.key)
        assertEquals("coordinator", reply.message.operationId)
        assertEquals("真实结果", reply.message.text)
        assertEquals(500L, reply.atMs)
    }

    @Test fun duplicateMatchingRequiresBothAssistantRoleAndOperationIdentity() {
        val snapshot = AssistantSnapshot(
            operations = listOf("first", "second", "user-only").map {
                operation(it, role = "Coordinator", phase = AssistantOperationPhase.Completed, result = "相同正文")
            },
            events = listOf(
                event("first-summary", "first", 100L, kind = "summary", detail = "相同正文"),
                event("second-summary", "second", 200L, kind = "summary", detail = "相同正文"),
                event("user-summary", "user-only", 300L, kind = "summary", detail = "相同正文"),
            ),
        )
        val messages = listOf(
            AssistantChatMessage("native", "Assistant", "相同正文", 100L, "first"),
            AssistantChatMessage("user", "User", "相同正文", 150L, "user-only"),
        )

        val timeline = assistantTimeline(AssistantUiState(snapshot, messages))

        assertEquals(listOf("message:native", "message:user", "message:event:second-summary", "message:event:user-summary"), timeline.map { it.key })
        assertEquals(listOf(100L, 150L, 200L, 300L), timeline.map { it.atMs })
    }

    @Test fun actualEventTimesOrderTasksQuestionsAndResultsWithChatMessages() {
        val question = AssistantQuestion("request", AssistantQuestionKind.Input, "使用哪种语言？")
        val snapshot = AssistantSnapshot(
            // Deliberately reverse task, operation, event, and message chronology in the inputs.
            tasks = listOf(task("done-task", "done"), task("waiting-task", "waiting")),
            operations = listOf(
                operation("done", phase = AssistantOperationPhase.Completed, result = "执行结果"),
                operation("waiting", phase = AssistantOperationPhase.Waiting, question = question),
            ),
            events = listOf(
                event("done-completed", "done", 4_000L, kind = "completed", sequence = 40),
                event("waiting-question", "waiting", 2_500L, kind = "waiting_user", sequence = 25),
                event("done-started", "done", 2_000L, sequence = 20),
                event("waiting-started", "waiting", 1_000L, sequence = 10),
                event("unrelated", "other-operation", 99_000L, sequence = 99),
                event("missing-time", "waiting", null, sequence = 100),
            ),
        )
        val messages = listOf(
            AssistantChatMessage("later", "Assistant", "核对进度", 3_500L),
            AssistantChatMessage("earlier", "User", "请继续", 1_500L),
        )

        val timeline = assistantTimeline(AssistantUiState(snapshot, messages))

        assertEquals(listOf("task:waiting-task", "message:earlier", "task:done-task", "question:waiting", "message:later", "result:done"), timeline.map { it.key })
        assertEquals(listOf(1_000L, 1_500L, 2_000L, 2_500L, 3_500L, 4_000L), timeline.map { it.atMs })
        assertEquals(question, timeline.filterIsInstance<AssistantTimeline.Question>().single().operation.question)
        assertEquals("执行结果", timeline.filterIsInstance<AssistantTimeline.Result>().single().operation.result)
    }

    @Test fun equalActualTimestampsKeepDistinctMessagesInTheirSourceOrder() {
        val messages = listOf(
            AssistantChatMessage("z", "Assistant", "相同正文", 1_000L, "first"),
            AssistantChatMessage("a", "Assistant", "相同正文", 1_000L, "second"),
            AssistantChatMessage("m", "User", "后续消息", 1_000L),
        )

        val timeline = assistantTimeline(AssistantUiState(AssistantSnapshot(), messages))

        assertEquals(listOf("message:z", "message:a", "message:m"), timeline.map { it.key })
        assertEquals(listOf(1_000L, 1_000L, 1_000L), timeline.map { it.atMs })
    }

    @Test fun unknownTimesStayAtTheEndWithStableSourceOrderAndDistinctQuestionResultKeys() {
        val shared = operation("shared", phase = AssistantOperationPhase.Completed,
            question = AssistantQuestion("request", AssistantQuestionKind.Approval, "确认改动？"), result = "已完成")
        val tasks = listOf(task("z", null), task("a", null))
        val snapshot = AssistantSnapshot(tasks = tasks + tasks, operations = listOf(shared, shared))
        val messages = listOf(
            AssistantChatMessage("z", "User", "较早的无时间消息"),
            AssistantChatMessage("known", "Assistant", "有真实时间", 5_000L),
            AssistantChatMessage("a", "Assistant", "后来的无时间消息"),
        )
        val expected = listOf("message:known", "message:z", "message:a", "task:z", "task:a", "question:shared", "result:shared")

        val timeline = assistantTimeline(AssistantUiState(snapshot, messages))
        val refreshed = assistantTimeline(AssistantUiState(snapshot.copy(
            tasks = tasks.map { it.copy(status = AssistantTaskStatus.Paused, detail = "更新状态") },
            operations = listOf(shared.copy(result = "更新后的结果")),
        ), messages))

        assertEquals(expected, timeline.map { it.key })
        assertEquals(expected, refreshed.map { it.key })
        assertEquals(expected.size, timeline.map { it.key }.distinct().size)
        assertTrue(timeline.drop(1).all { it.atMs == null })
        assertTrue(refreshed.drop(1).all { it.atMs == null })
        assertEquals(5_000L, refreshed.first().atMs)
    }

    @Test fun missingTimestampsAreNotInventedFromSequenceOtherOperationsOrUiState() {
        val snapshot = AssistantSnapshot(
            tasks = listOf(task("unmapped", null), task("mapped", "execution")),
            operations = listOf(
                operation("execution", phase = AssistantOperationPhase.Completed,
                    question = AssistantQuestion("request", AssistantQuestionKind.Input, "继续吗？"), result = "完成"),
                operation("coordinator", role = "Coordinator", phase = AssistantOperationPhase.Completed, result = "协调结果"),
            ),
            events = listOf(
                event("execution-event", "execution", null, kind = "completed", sequence = 900),
                event("coordinator-event", "coordinator", null, kind = "completed", sequence = 901),
                event("unmapped-event", null, 8_000L, sequence = 902),
                event("unrelated-event", "unrelated", 9_000L, sequence = 903),
            ),
        )
        val state = AssistantUiState(snapshot, listOf(AssistantChatMessage("native", "User", "无时间的消息")),
            loading = true, error = "离线", pendingActions = setOf("assistant/send"), sendAcknowledgement = 123)

        val timeline = assistantTimeline(state)

        assertEquals(listOf("message:native", "message:operation:coordinator:result", "task:unmapped", "task:mapped", "question:execution", "result:execution"), timeline.map { it.key })
        assertTrue("Missing time must remain null rather than becoming an index, sequence, or wall-clock time", timeline.all { it.atMs == null })
        assertEquals(timeline, assistantTimeline(state.copy(loading = false, error = null, pendingActions = emptySet(), sendAcknowledgement = 124)))
    }

    @Test fun duplicateSummarySuppliesItsActualTimeToAnUntimedNativeReply() {
        assertDuplicateReplyPreservesEventTime("summary")
    }

    @Test fun duplicateCoordinatorResultSuppliesItsActualCompletionTimeToAnUntimedNativeReply() {
        assertDuplicateReplyPreservesEventTime("completed")
    }

    private fun assertDuplicateReplyPreservesEventTime(kind: String) {
        val nativeReply = AssistantChatMessage("native", "Assistant", "已整理两项待办", operationId = "coordinator")
        val later = AssistantChatMessage("later", "User", "接下来检查测试", 2_000L)
        val snapshot = AssistantSnapshot(
            operations = listOf(operation("coordinator", role = "Coordinator", phase = AssistantOperationPhase.Completed, result = nativeReply.text)),
            events = listOf(event("actual-event", "coordinator", 1_000L, kind = kind, detail = nativeReply.text)),
        )

        val timeline = assistantTimeline(AssistantUiState(snapshot, listOf(nativeReply, later)))

        assertEquals(2, timeline.size)
        val retained = timeline.filterIsInstance<AssistantTimeline.Message>().single { it.message.id == "native" }
        assertEquals(nativeReply.text, retained.message.text)
        // Regression: dropping a duplicate must not drop its only known real timestamp.
        assertEquals("The matching $kind event supplies a real timestamp; preserving null moves an older reply after the next user message", 1_000L, retained.atMs)
        assertEquals(listOf("message:native", "message:later"), timeline.map { it.key })
        assertEquals(listOf(1_000L, 2_000L), timeline.map { it.atMs })
    }
}
