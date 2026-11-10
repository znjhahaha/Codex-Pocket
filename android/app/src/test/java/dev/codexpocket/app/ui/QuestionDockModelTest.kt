package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuestionDockModelTest {
    private val chat = ChatEntity(ThreadKey("pc", "store", "chat"), "runtime", "desktop", "Chat", "", "", 1, "idle", false)
    private val runtime = RuntimeInfo("runtime", "desktop", "", "store", true, emptySet(), setOf("turn/start"))
    private fun state() = PocketState(online = true, selected = chat, runtimes = listOf(runtime), approvalsFresh = true,
        conversationLoadState = ConversationLoadState.Ready)
    private fun approval(id: String, threadId: String = "chat", method: String = "item/tool/requestUserInput") = PendingApproval(
        "runtime", JsonPrimitive(id), method, buildJsonObject {
            put("threadId", threadId)
            put("questions", buildJsonArray { add(buildJsonObject { put("id", "q"); put("question", "Access code?"); put("isSecret", true) }) })
        })
    private fun question(item: String, index: Int, submitted: Boolean = false) = buildJsonObject {
        val id = buildJsonArray { add("request_user_input_async"); add(item); add(index) }.toString()
        put("id", id); put("questionItemId", id); put("title", "Choose $index")
        put("options", buildJsonArray { add("Yes"); add("No") }); put("submitted", submitted)
    }

    @Test fun dockScopesRequestsAndKeepsCommandApprovalsDistinct() {
        val s = state().copy(approvals = listOf(approval("a"), approval("other", "other-chat"), approval("threadless", ""),
            approval("command", method = "item/commandExecution/requestApproval")),
            advanced = AdvancedConversationState(asyncQuestions = listOf(question("native-a", 0), question("native-a", 1), question("native-b", 0))))
        val entries = questionDockEntries(s)
        assertEquals(4, entries.size)
        assertEquals(approval("a").key, entries[0].key)
        assertNull(entries[1].batch)
        assertEquals(2, entries[2].batch!!.questions.size)
        assertEquals(1, entries[3].batch!!.questions.size)
    }

    @Test fun threadlessAndCrossRuntimeApprovalsNeverAttachToAnArbitraryChat() {
        val s = state().copy(approvals = listOf(approval("missing-thread", ""), approval("other-runtime").copy(runtimeId = "other")))
        assertTrue(questionDockEntries(s).isEmpty())
        assertTrue(questionDockEntries(s.copy(selected = chat.copy(key = chat.key.copy(nativeId = "")))).isEmpty())
    }

    @Test fun asyncMetadataRetainsSensitiveAndOtherFlagsFromNativeTimeline() {
        val native = TimelineItem.from("turn", buildJsonObject {
            put("id", "native-a"); put("type", "agentMessage"); put("delivery", "async")
            put("questions", buildJsonArray { add(buildJsonObject { put("title", "Choose 0"); put("isSecret", true); put("isOther", false) }) })
        })
        val s = state().copy(timeline = NativeTimeline(items = mapOf(native.key to native)),
            advanced = AdvancedConversationState(asyncQuestions = listOf(question("native-a", 0))))
        val q = questionDockEntries(s).single().batch!!.questions.single()
        assertTrue(q.secret)
        assertFalse(q.allowsOther)
        assertEquals(question("native-a", 0).string("id"), q.id)
    }

    @Test fun partialLegacySubmissionsAreExcludedFromNextBatch() {
        val s = state().copy(advanced = AdvancedConversationState(asyncQuestions = listOf(question("a", 0, true), question("a", 1))))
        val pending = questionDockEntries(s).single()
        assertEquals(listOf(question("a", 1).string("id")), pending.batch!!.questions.map { it.id })
        assertFalse(pending.submitted)
        val done = questionDockEntries(s.copy(advanced = s.advanced.copy(asyncQuestions = listOf(question("a", 0, true), question("a", 1, true))))).single()
        assertTrue(done.submitted)
    }

    @Test fun connectionFreshnessAndInFlightProduceExplicitStatuses() {
        val s = state().copy(approvals = listOf(approval("a")))
        val entry = questionDockEntries(s).single()
        assertEquals(QuestionPhase.Waiting, questionAvailability(s, entry))
        assertEquals(QuestionPhase.Offline, questionAvailability(s.copy(online = false), entry))
        assertEquals(QuestionPhase.Stale, questionAvailability(s.copy(approvalsFresh = false), entry))
        assertEquals(QuestionPhase.Stale, questionAvailability(s.copy(approvals = emptyList()), entry))
        assertEquals(QuestionPhase.Submitting, questionAvailability(s.copy(submittingApprovalKeys = setOf(entry.key)), entry))
        assertEquals(QuestionPhase.Submitted, questionAvailability(s.copy(approvals = listOf(entry.approval!!.copy(submitted = true))), entry.copy(approval = entry.approval.copy(submitted = true))))
    }

    @Test fun uncertainReplyBlocksOnlyItsExactRequestAndLeavesOtherRequestsActionable() {
        val first = approval("a").copy(submitted = true)
        val second = approval("b")
        val command = CommandEntity("sent-a", "pc", chat.key, "approvals/respond", "unknown", 1, first.key)
        val s = state().copy(approvals = listOf(first, second), commands = listOf(command))
        assertFalse(s.uncertainSend)
        assertEquals(1, s.unresolvedCommands.size)
        assertEquals(QuestionPhase.AwaitingConfirmation, questionAvailability(s, questionDockEntries(s)[0]))
        assertEquals(QuestionPhase.Waiting, questionAvailability(s, questionDockEntries(s)[1]))
        assertTrue(s.copy(commands = listOf(command.copy(method = "turn/start"))).uncertainSend)
    }

    @Test fun submittedReceiptSurvivesRequestRemovalButCannotAttachToAnotherRuntimeOrThread() {
        val a = approval("a").copy(params = JsonObject(approval("a").params +
            mapOf("turnId" to JsonPrimitive("turn"), "itemId" to JsonPrimitive("item"))))
        val receipt = QuestionReceipt.capture("command", chat.key, a, buildJsonObject {})
        val command = CommandEntity("command", "pc", chat.key, "approvals/respond", "unknown", 1, a.key)
        val s = state().copy(commands = listOf(command))
        assertEquals(a.key, questionDockEntries(s, listOf(receipt)).single().key)
        assertTrue(questionDockEntries(s.copy(selected = chat.copy(runtimeId = "other")), listOf(receipt)).isEmpty())
        assertTrue(questionDockEntries(s.copy(selected = chat.copy(key = chat.key.copy(nativeId = "other"))), listOf(receipt)).isEmpty())
    }

    @Test fun cachedAsyncQuestionsCannotSubmitUntilConversationIsFresh() {
        val s = state().copy(advanced = AdvancedConversationState(asyncQuestions = listOf(question("a", 0))))
        val entry = questionDockEntries(s).single()
        for (load in listOf(ConversationLoadState.Idle, ConversationLoadState.Loading, ConversationLoadState.Failed)) {
            assertEquals(QuestionPhase.Stale, questionAvailability(s.copy(conversationLoadState = load), entry))
        }
        assertEquals(QuestionPhase.Waiting, questionAvailability(s, entry))
        assertEquals(QuestionPhase.Stale, questionAvailability(s.copy(selected = chat.copy(conflict = true)), entry))
    }

    @Test fun nativeSecretAsyncQuestionIsUnsupportedWithoutPrivateTransport() {
        val source = JsonObject(question("a", 0) + ("isSecret" to JsonPrimitive(true)))
        val s = state().copy(advanced = AdvancedConversationState(asyncQuestions = listOf(source)))
        val entry = questionDockEntries(s).single()
        assertEquals(QuestionPhase.Unsupported, questionAvailability(s, entry))
        assertEquals(QuestionPhase.Unsupported, questionAvailability(s.copy(online = false), entry))
        val blocking = state().copy(approvals = listOf(approval("secret-rpc")))
        assertEquals(QuestionPhase.Waiting, questionAvailability(blocking, questionDockEntries(blocking).single()))
    }

    @Test fun batchSwitchAndUnfreshSnapshotRetainAnswersButResolutionClearsThem() {
        val s = state().copy(approvals = listOf(approval("a"), approval("b")))
        val entries = questionDockEntries(s)
        val memory = QuestionDockMemory()
        memory.reconcile("chat", entries, fresh = true)
        val first = memory.session(entries[0].batch!!)
        val second = memory.session(entries[1].batch!!)
        first.editOther("q", "fixture-a")
        second.editOther("q", "fixture-b")
        assertSame(first, memory.session(entries[0].batch!!))
        assertEquals("fixture-b", memory.session(entries[1].batch!!).answer("q"))
        memory.reconcile("chat", emptyList(), fresh = false)
        assertEquals("fixture-a", first.answer("q"))
        memory.reconcile("chat", listOf(entries[1]), fresh = true)
        assertNull(first.answer("q"))
        assertEquals("fixture-b", second.answer("q"))
        memory.clear()
        assertNull(second.answer("q"))
    }
}
