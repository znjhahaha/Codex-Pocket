package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V054TaskFoldingTest {
    private fun raw(value: String) = pocketJson.parseToJsonElement(value).jsonObject
    private fun item(turn: String, id: String, type: String = "agentMessage", text: String = id,
        metadata: String = "{}", complete: Boolean = true) = TimelineItem(turn, id, type, text,
        raw = raw(metadata), complete = complete)
    private fun final(turn: String, id: String = "final", complete: Boolean = true) =
        item(turn, id, metadata = """{"phase":"final_answer"}""", complete = complete)
    private fun timeline(items: List<TimelineItem>, turns: List<NativeTurnInfo> = listOf(NativeTurnInfo("t", status = "completed"))) =
        NativeTimeline(items = items.associateTo(linkedMapOf()) { it.key to it },
            turns = turns.associateBy { it.id }, completedTurns = turns.filter { it.status == "completed" }.map { it.id }.toSet())
    private val user = item("t", "initial", "userMessage")
    private val process = item("t", "comment", metadata = """{"phase":"commentary"}""")

    @Test fun successfulTaskKeepsInitialUserAndEveryFinalPartAndPlanInNativeOrder() {
        val followup = item("t", "followup", "userMessage")
        val parts = listOf(final("t", "part1"), item("t", "plan", "plan"), final("t", "part2"))
        val rows = ConversationPresentation.rows(timeline(listOf(user, process, followup) + parts))
        assertEquals(listOf(user.key, "process:t") + parts.map { it.key }, rows.map { it.key })
        assertEquals(listOf(process.key, followup.key), rows[1].items.map { it.key })
        assertTrue(rows.all { it.foldReason == TaskFoldReason.Successful })
        val expanded = expandTaskRows(rows, setOf("process:t"))
        assertEquals(listOf(user.key, process.key, followup.key) + parts.map { it.key },
            expanded.filter { it.kind != TranscriptKind.Process }.flatMap { it.items }.map { it.key })
    }

    @Test fun nativeRootAndConfirmedSteerAreTheOnlyTaskLinkage() {
        val a = listOf(item("a", "user", "userMessage"), final("a"))
        val b = listOf(item("b", "steer", "userMessage"), final("b", "one"), final("b", "two"))
        val separate = timeline(a + b, listOf(NativeTurnInfo("a", status = "completed"), NativeTurnInfo("b", status = "completed")))
        assertEquals(2, ConversationPresentation.rows(separate).count { it.kind == TranscriptKind.User })
        for (linked in listOf(separate.copy(turns = separate.turns + ("b" to NativeTurnInfo("b", rootId = "a", status = "completed"))),
            separate.withConfirmedSteer("a", "b"))) {
            val rows = ConversationPresentation.rows(linked)
            assertEquals(1, rows.count { it.kind == TranscriptKind.User })
            assertEquals("process:a", rows.single { it.kind == TranscriptKind.Process }.key)
            assertEquals(b.drop(1).map { it.key }, rows.filter { it.kind == TranscriptKind.Answer }.map { it.key })
        }
    }

    @Test fun proseAndAnArbitraryParentFieldNeverMergeUnrelatedTasks() {
        val next = item("other", "u", "userMessage", metadata = """{"parentTurnId":"t","text":"continue previous task"}""")
        val state = timeline(listOf(user, process, final("t"), next, final("other")),
            listOf(NativeTurnInfo("t", status = "completed"), NativeTurnInfo("other", status = "completed")))
        assertEquals(2, ConversationPresentation.rows(state).count { it.kind == TranscriptKind.User })
    }

    @Test fun missingRootAndItemlessFailedLinkedTurnsDoNotBecomeSuccess() {
        val values = listOf(user, process, final("t"))
        val missing = timeline(values, listOf(NativeTurnInfo("t", rootId = "missing", status = "completed")))
        assertEquals(TaskFoldReason.MissingTurnMetadata, ConversationPresentation.rows(missing).first().foldReason)
        val failed = timeline(values, listOf(NativeTurnInfo("t", status = "completed"), NativeTurnInfo("child", rootId = "t", status = "failed")))
        assertEquals(TaskFoldReason.Failed, ConversationPresentation.rows(failed).first().foldReason)
    }

    @Test fun partialRootWithoutItsInitialUserAndCyclicNativeLinksNeverFold() {
        val partial = timeline(listOf(process, final("t")))
        assertEquals(TaskFoldReason.MissingInitialUser, ConversationPresentation.rows(partial).first().foldReason)
        val cyclic = timeline(listOf(user, process, final("t")), listOf(NativeTurnInfo("t", rootId = "cycle", status = "completed"),
            NativeTurnInfo("cycle", rootId = "t", status = "completed")))
        assertEquals(TaskFoldReason.MissingTurnMetadata, ConversationPresentation.rows(cyclic).first().foldReason)
    }

    @Test fun completedNativeRequestToolsResolveOldQuestionGuards() {
        val request = item("t", "request", "dynamicToolCall", metadata =
            """{"tool":"request_user_input","status":"completed","questions":[{"question":"choose"}]}""")
        assertTrue(ConversationPresentation.rows(timeline(listOf(user, request, final("t")))).any { it.kind == TranscriptKind.Process })
        val waiting = request.copy(raw = raw("""{"tool":"request_user_input","status":"waitingOnUserInput","questions":[{"question":"choose"}]}"""))
        assertEquals(TaskFoldReason.PendingRequest, ConversationPresentation.rows(timeline(listOf(user, waiting, final("t")))).first().foldReason)
    }

    @Test fun closedRejectedRequestIsHistoricalFailureWhilePendingDecisionStillBlocks() {
        val rejected = item("t", "request", "dynamicToolCall", metadata =
            """{"tool":"request_user_input","status":"rejected","questions":[{"question":"choose"}]}""")
        val state = timeline(listOf(user, rejected, final("t")))
        val process = ConversationPresentation.rows(state).single { it.kind == TranscriptKind.Process }
        assertTrue(process.processDetail!!.contains("1 条失败记录"))
        assertFalse(ConversationPresentation.rows(state, blockedTurns = setOf("t")).any { it.kind == TranscriptKind.Process })
    }

    @Test fun legacyUnphasedCommentaryFoldsAndLastAnswerRemainsIntact() {
        val one = item("t", "one")
        val two = item("t", "two")
        val rows = ConversationPresentation.rows(timeline(listOf(user, process, one, two)))
        assertEquals(listOf(two.key), rows.filter { it.kind == TranscriptKind.Answer }.map { it.key })
        assertEquals(listOf(process.key, one.key), rows.single { it.kind == TranscriptKind.Process }.items.map { it.key })
    }

    @Test fun resolvedRequestIdentityDoesNotOverrideARealPendingApproval() {
        val request = item("t", "guard", "item/commandExecution/requestApproval", metadata = """{"requestId":"rpc-9"}""", complete = false)
        val state = timeline(listOf(user, request, process, final("t")))
        assertEquals(TaskFoldReason.PendingRequest, ConversationPresentation.rows(state).first().foldReason)
        assertTrue(ConversationPresentation.rows(state, resolvedRequestIds = setOf("rpc-9")).any { it.kind == TranscriptKind.Process })
        assertFalse(ConversationPresentation.rows(state, blockedTurns = setOf("t"), resolvedRequestIds = setOf("rpc-9"))
            .any { it.kind == TranscriptKind.Process })
    }

    @Test fun asyncQuestionNeedsEveryAnswerAndSubmittedIsNotAnswerEvidence() {
        val question = item("t", "questions", metadata = """{"delivery":"async","questions":[{"title":"One"},{"title":"Two"}]}""")
        val state = timeline(listOf(user, question, final("t")))
        val questions = AsyncQuestions.pending(state)
        assertEquals(2, AsyncQuestions.pending(state, questions.map { it.string("questionItemId") }.toSet()).size)
        val reply1 = item("t", "reply1", "userMessage", AsyncQuestions.reply(questions[0], "yes"))
        val reply2 = item("t", "reply2", "userMessage", AsyncQuestions.reply(questions[1], "yes"))
        assertFalse(ConversationPresentation.rows(state).any { it.kind == TranscriptKind.Process })
        assertFalse(ConversationPresentation.rows(timeline(listOf(user, question, reply1, final("t")))).any { it.kind == TranscriptKind.Process })
        assertTrue(ConversationPresentation.rows(timeline(listOf(user, question, reply1, reply2, final("t")))).any { it.kind == TranscriptKind.Process })
    }

    @Test fun terminalSuccessCanFoldOldRunningAndFailedAgentObservations() {
        for (status in listOf("running", "failed", "shutdown")) {
            val agent = item("t", "agent", "subAgentActivity", metadata = """{"agentThreadId":"child","kind":"$status"}""")
            assertTrue(ConversationPresentation.rows(timeline(listOf(user, agent, final("t")))).any { it.kind == TranscriptKind.Process })
        }
    }

    @Test fun latestNativeAgentWaitingStateStaysVisibleUntilLaterCompletion() {
        val waiting = item("t", "waiting", "subAgentActivity", metadata = """{"agentThreadId":"child","kind":"waitingOnUserInput"}""")
        val completed = item("t", "done", "subAgentActivity", metadata = """{"agentThreadId":"child","kind":"completed"}""")
        assertFalse(ConversationPresentation.rows(timeline(listOf(user, waiting, final("t")))).any { it.kind == TranscriptKind.Process })
        assertTrue(ConversationPresentation.rows(timeline(listOf(user, waiting, completed, final("t")))).any { it.kind == TranscriptKind.Process })
    }

    @Test fun incompleteFinalPartsAndPlanNeverFoldAsSuccess() {
        for (last in listOf(final("t", "part2", complete = false), item("t", "plan", "plan", complete = false))) {
            val rows = ConversationPresentation.rows(timeline(listOf(user, process, final("t", "part1"), last)))
            assertFalse(rows.any { it.kind == TranscriptKind.Process })
            assertEquals(TaskFoldReason.IncompleteFinal, rows.first().foldReason)
        }
    }

    @Test fun failedInterruptedActiveAndMissingFinalHaveExplicitReasons() {
        for ((status, reason) in listOf("failed" to TaskFoldReason.Failed, "interrupted" to TaskFoldReason.Interrupted,
            "inProgress" to TaskFoldReason.Unfinished)) {
            assertEquals(reason, ConversationPresentation.rows(timeline(listOf(user, process, final("t")),
                listOf(NativeTurnInfo("t", status = status)))).first().foldReason)
        }
        assertEquals(TaskFoldReason.Active, ConversationPresentation.rows(timeline(listOf(user, process, final("t"))).copy(activeTurn = "t")).first().foldReason)
        assertEquals(TaskFoldReason.MissingFinal, ConversationPresentation.rows(timeline(listOf(user, process))).first().foldReason)
        assertEquals(TaskFoldReason.Unclassified, TranscriptRow("key", TranscriptKind.User, listOf(user)).foldReason)
    }
}
