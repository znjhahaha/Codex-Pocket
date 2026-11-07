package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AsyncSecretCapabilityTest {
    private fun nativeQuestions(secret: Boolean) = TimelineItem.from("turn", buildJsonObject {
        put("id", "batch"); put("type", "agentMessage"); put("delivery", "async")
        put("questions", buildJsonArray {
            add(buildJsonObject { put("title", "Choose client"); put("options", buildJsonArray { add("Phone"); add("Desktop") }) })
            add(buildJsonObject { put("title", "Access code"); put("isSecret", secret) })
        })
    }).let { NativeTimeline(items = mapOf(it.key to it)) }

    @Test fun pendingPreservesNativeSecretFlagAndQuestionIdentity() {
        val pending = AsyncQuestions.pending(nativeQuestions(true))
        assertFalse(pending[0].flag("isSecret"))
        assertTrue(pending[1].flag("isSecret"))
        assertEquals("[\"request_user_input_async\",\"batch\",1]", pending[1].string("id"))
        assertTrue(AsyncQuestions.pending(nativeQuestions(true), setOf(pending[1].string("id")))[1].flag("isSecret"))
        assertFalse(AsyncQuestions.pending(nativeQuestions(false))[1].flag("isSecret"))
    }

    @Test fun entireAsyncBatchRejectsSecretsEvenWithEveryAnswerProvided() {
        val pending = AsyncQuestions.pending(nativeQuestions(true))
        val batch = QuestionBatches.async("chat", pending).single()
        assertNull(QuestionBatches.asyncAnswers(batch, pending.map { it.string("id") to "fixture-value" }))
        val session = QuestionSession(batch)
        assertEquals(QuestionPhase.Unsupported, session.presentationPhase(QuestionPhase.Waiting))
        assertFalse(session.editOther(pending[1].string("id"), "fixture-secret"))
        assertFalse(session.selectOption(pending[0].string("id"), "Phone"))
        session.move(1)
        assertNull(session.beginSubmission(QuestionPhase.Waiting))
    }

    @Test fun newlySensitiveAsyncMetadataClearsDraftAndBlocksEmission() {
        val normal = QuestionBatches.async("chat", AsyncQuestions.pending(nativeQuestions(false))).single()
        val session = QuestionSession(normal)
        session.editOther(normal.questions[1].id, "fixture-secret")
        session.update(QuestionBatches.async("chat", AsyncQuestions.pending(nativeQuestions(true))).single())
        assertNull(session.answer(normal.questions[1].id))
        assertEquals(QuestionPhase.Unsupported, session.presentationPhase(QuestionPhase.Offline))
        assertNull(session.beginSubmission(QuestionPhase.Waiting))
    }

    @Test fun normalAsyncAnswersRetainExactContentAndBlockingSecretsRemainAvailable() {
        val normal = QuestionBatches.async("chat", AsyncQuestions.pending(nativeQuestions(false))).single()
        val values = listOf(normal.questions[0].id to "Phone", normal.questions[1].id to "line one\nline two")
        assertEquals(values.map { it.second }, QuestionBatches.asyncAnswers(normal, values)!!.map { it.second })
        assertEquals("line one\nline two", AsyncQuestions.replies(AsyncQuestions.reply(normal.questions[1].source, values[1].second)).single().string("answer"))
        val blocking = QuestionBatches.blocking("rpc", Json.parseToJsonElement("""{"questions":[{"id":"secret","question":"Access code?","isSecret":true}]}""").jsonObject)
        val session = QuestionSession(blocking)
        assertTrue(session.editOther("secret", "fixture-secret"))
        assertEquals(QuestionPhase.Waiting, session.presentationPhase(QuestionPhase.Waiting))
        assertNotNull(QuestionBatches.blockingResponse(blocking, session.beginSubmission(QuestionPhase.Waiting)!!))
    }
}
