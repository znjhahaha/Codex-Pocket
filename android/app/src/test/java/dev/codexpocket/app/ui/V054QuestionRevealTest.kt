package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V054QuestionRevealTest {
    private fun batch(id: String) = QuestionBatches.blocking(id, Json.parseToJsonElement("""
        {"questions":[{"id":"one","question":"First?"},{"id":"two","question":"Second?"}]}
    """).jsonObject)

    @Test fun firstBatchAppearanceAndPageChangeHaveSeparateDurations() {
        val reveal = QuestionRevealState()
        assertEquals(320, reveal.durationFor("one"))
        reveal.markShown("one")
        assertEquals(0, reveal.durationFor("one"))
        assertEquals(180, reveal.durationFor("two"))
        reveal.markShown("two")
        assertEquals(0, reveal.durationFor("two"))
        assertEquals(180, reveal.durationFor("one"))
        assertEquals(0, reveal.durationFor(null))
    }

    @Test fun collapseBatchSwitchAndUnfreshSnapshotDoNotReplayOrChangeAnswers() {
        val memory = QuestionDockMemory()
        val a = batch("a"); val b = batch("b")
        val entries = listOf(QuestionDockEntry(a.id, a), QuestionDockEntry(b.id, b))
        memory.reconcile("chat", entries, fresh = true)
        val answer = memory.session(a)
        answer.editOther("one", "Keep this answer")
        val reveal = memory.reveal(a)
        reveal.markShown("one")
        memory.reveal(b).markShown("one")
        memory.reconcile("chat", emptyList(), fresh = false)
        assertSame(reveal, memory.reveal(a))
        assertEquals(0, memory.reveal(a).durationFor("one"))
        assertEquals("Keep this answer", answer.answer("one"))
        assertEquals(0, answer.page)
        memory.reconcile("chat", listOf(entries[1]), fresh = true)
        assertEquals(320, memory.reveal(a).durationFor("one"))
        memory.clear()
        assertEquals(320, memory.reveal(b).durationFor("one"))
    }

    @Test fun staticPresentationConsumesAppearanceWithoutADeferredReplay() {
        val reveal = QuestionRevealState()
        // Accessibility/power/background fallbacks mark the question as shown, too.
        reveal.markShown("one")
        assertEquals(0, reveal.durationFor("one"))
        reveal.markShown("two")
        assertEquals(0, reveal.durationFor("two"))
    }

    @Test fun emptyIdentityNeverConsumesAnActualQuestionAppearance() {
        val reveal = QuestionRevealState()
        for (id in listOf(null, "", " ")) {
            assertEquals(0, reveal.durationFor(id))
            reveal.markShown(id)
        }
        assertEquals(320, reveal.durationFor("one"))
        reveal.markShown("one")
        repeat(5) { assertEquals(0, reveal.durationFor("one")) }
    }

    @Test fun nativeMetadataAndSecretAnswerSurviveRevealAndPageChanges() {
        val secret = QuestionBatches.blocking("secret", Json.parseToJsonElement("""
            {"questions":[{"id":"secret","question":"Access code?","isSecret":true},{"id":"next","question":"Next?"}]}
        """).jsonObject)
        val session = QuestionSession(secret)
        val reveal = QuestionRevealState()
        session.editOther("secret", "private fixture")
        reveal.markShown("secret")
        session.move(1)
        reveal.markShown("next")
        session.move(-1)
        assertEquals("private fixture", session.answer("secret"))
        assertTrue(session.currentQuestion!!.secret)
        assertEquals(QuestionPhase.Waiting, session.phase)
        assertFalse(session.toString().contains("private fixture"))
    }

    @Test fun commandAndFileApprovalsAndOrdinaryAssistantMessagesNeverBecomeQuestionBatches() {
        val chat = ChatEntity(ThreadKey("pc", "store", "chat"), "runtime", "desktop", "Chat", "", "", 1, "idle", false)
        val message = TimelineItem("turn", "message", "agentMessage", "Do you want me to continue?")
        val state = PocketState(selected = chat, timeline = NativeTimeline(items = mapOf(message.key to message)))
        assertTrue(questionDockEntries(state).isEmpty())
        val approvals = listOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval").map { method ->
            PendingApproval("runtime", JsonPrimitive(method), method, buildJsonObject { put("threadId", "chat") })
        }
        val entries = questionDockEntries(state.copy(approvals = approvals))
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.batch == null })
    }
}
