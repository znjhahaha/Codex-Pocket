package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuestionFormsTest {
    private fun blocking(vararg questions: String) = QuestionBatches.blocking("rpc-1", Json.parseToJsonElement(
        """{"questions":[${questions.joinToString(",") }]}"""
    ).jsonObject)
    private val first = """{"id":"client","header":"Client","question":"Which client?","isOther":true,"options":[{"label":"Phone (Recommended)","description":"Use the phone"},{"label":"Desktop"}]}"""
    private val second = """{"id":"secret","question":"Access code?","isSecret":true}"""
    private fun async(item: String, index: Int, title: String = "Choose") = buildJsonObject {
        val id = buildJsonArray { add("request_user_input_async"); add(item); add(index) }.toString()
        put("id", id); put("questionItemId", id); put("title", title)
        put("options", buildJsonArray { add("Phone"); add("Desktop") })
    }

    @Test fun pagesPreserveOptionsAndOtherDraftWithoutSubmitting() {
        val batch = blocking(first, second)
        val session = QuestionSession(batch)
        assertEquals("Phone", batch.questions[0].options[0].label)
        assertTrue(batch.questions[0].options[0].recommended)
        assertEquals("Use the phone", batch.questions[0].options[0].description)
        session.selectOption("client", "Phone (Recommended)")
        assertEquals(QuestionPhase.Waiting, session.phase)
        session.editOther("client", "Tablet")
        session.selectOption("client", "Desktop")
        session.move(1)
        session.editOther("secret", "fixture-secret")
        session.move(-1)
        assertEquals("Desktop", session.answer("client"))
        session.selectOther("client")
        assertEquals("Tablet", session.answer("client"))
        assertEquals("fixture-secret", session.answer("secret"))
        assertNull(session.beginSubmission(QuestionPhase.Offline))
        assertEquals(QuestionPhase.Waiting, session.phase)
        session.move(1)
        val answers = session.beginSubmission(QuestionPhase.Waiting)!!
        assertEquals(listOf("Tablet", "fixture-secret"), answers.map { it.second })
        assertEquals(QuestionPhase.Submitting, session.phase)
        assertNull(session.beginSubmission(QuestionPhase.Waiting))
    }

    @Test fun blankDuplicateAndInvalidOptionAnswersCannotSubmit() {
        for (batch in listOf(blocking(), blocking(first, first), blocking("""{"id":"","question":"Choose"}"""))) {
            assertFalse(batch.valid)
            assertNull(QuestionSession(batch).beginSubmission(QuestionPhase.Waiting))
        }
        val session = QuestionSession(blocking(first, second))
        assertFalse(session.selectOption("client", "Unadvertised"))
        session.editOther("client", " ")
        session.editOther("secret", "ready")
        session.move(1)
        assertNull(session.beginSubmission(QuestionPhase.Waiting))
        session.editOther("client", "x".repeat(64_001))
        assertNull(session.beginSubmission(QuestionPhase.Waiting))
        session.selectOption("client", "Desktop")
        assertNull(session.beginSubmission(QuestionPhase.Stale))
        assertNotNull(session.beginSubmission(QuestionPhase.Waiting))
    }

    @Test fun freeTextIsRejectedForAQuestionWithoutOther() {
        val session = QuestionSession(blocking(first.replace("\"isOther\":true", "\"isOther\":false")))
        assertFalse(session.editOther("client", "Tablet"))
        assertNull(session.answer("client"))
        assertNull(session.beginSubmission(QuestionPhase.Waiting))
    }

    @Test fun nativeResponseKeepsEveryIdAndExactAdvertisedLabel() {
        val batch = blocking(first, second)
        val response = QuestionBatches.blockingResponse(batch, listOf("client" to "Phone (Recommended)", "secret" to "code"))!!
        assertEquals("Phone (Recommended)", response["answers"]!!.jsonObject["client"]!!.jsonObject["answers"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(setOf("client", "secret"), response["answers"]!!.jsonObject.keys)
        assertNull(QuestionBatches.blockingResponse(batch, listOf("client" to "Desktop")))
        assertNull(QuestionBatches.blockingResponse(batch, listOf("client" to "Desktop", "client" to "Desktop")))
        assertNull(QuestionBatches.blockingResponse(batch, listOf("client" to " ", "secret" to "code")))
    }

    @Test fun asyncGroupsNativeItemIdsAndOrdersPagesByNativeIndex() {
        val batch = QuestionBatches.async("chat-1", listOf(async("a", 2), async("b", 0), async("a", 0), async("a", 1)))
        assertEquals(2, batch.size)
        assertEquals(listOf(0, 1, 2), batch[0].questions.map { Json.parseToJsonElement(it.id).jsonArray[2].jsonPrimitive.int })
        assertNotEquals(batch[0].id, QuestionBatches.async("chat-2", listOf(async("a", 0))).single().id)
        val replies = QuestionBatches.asyncAnswers(batch[0], batch[0].questions.map { it.id to "Phone" })!!
        assertEquals(batch[0].questions.map { it.id }, replies.map { it.first["questionItemId"]!!.jsonPrimitive.content })
        assertEquals(listOf("Phone", "Phone", "Phone"), replies.map { it.second })
        assertNull(QuestionBatches.asyncAnswers(batch[0], listOf(batch[0].questions[0].id to "Phone")))
    }

    @Test fun schemaUpdatesPreserveByRequestIdAndClearChangedQuestion() {
        val batch = blocking(first, second)
        val session = QuestionSession(batch)
        session.selectOption("client", "Desktop")
        session.editOther("secret", "fixture-secret")
        session.move(1)
        session.update(blocking(second, first))
        assertEquals("secret", session.currentQuestion!!.id)
        assertEquals("fixture-secret", session.answer("secret"))
        session.update(blocking(second.replace("Access code?", "New access code?"), first))
        assertNull(session.answer("secret"))
        assertEquals("Desktop", session.answer("client"))
        assertFalse(session.toString().contains("fixture-secret"))
    }

    @Test fun sensitiveAnswersAreClearedOnAcknowledgementAndDisposal() {
        val batch = blocking(first, second)
        val session = QuestionSession(batch)
        session.selectOption("client", "Desktop")
        session.editOther("secret", "fixture-secret")
        session.move(1)
        assertNotNull(session.beginSubmission(QuestionPhase.Waiting))
        session.observe(submitted = true, submitting = true)
        assertEquals(QuestionPhase.Submitting, session.phase)
        assertEquals(QuestionPhase.Submitting, session.presentationPhase(QuestionPhase.Submitted))
        assertEquals(QuestionPhase.Offline, session.presentationPhase(QuestionPhase.Offline))
        session.observe(submitted = true, submitting = false)
        assertEquals(QuestionPhase.Submitted, session.phase)
        assertNull(session.answer("secret"))
        assertFalse(session.editOther("secret", "new-secret"))
        session.clear()
        assertNull(session.answer("client"))
    }

    @Test fun definiteRefusalReopensWithoutAutomaticResubmission() {
        val session = QuestionSession(blocking(first))
        session.selectOption("client", "Desktop")
        assertNotNull(session.beginSubmission(QuestionPhase.Waiting))
        session.observe(submitted = false, submitting = true)
        session.observe(submitted = false, submitting = false)
        assertEquals(QuestionPhase.Waiting, session.phase)
        assertEquals("Desktop", session.answer("client"))
    }

    @Test fun disconnectDoesNotReopenAnUnconfirmedSubmission() {
        val session = QuestionSession(blocking(first))
        session.selectOption("client", "Desktop")
        assertNotNull(session.beginSubmission(QuestionPhase.Waiting))
        session.observe(submitted = false, submitting = true)
        session.observe(submitted = false, submitting = false, pendingConfirmed = false)
        assertEquals(QuestionPhase.Submitting, session.phase)
        assertNull(session.beginSubmission(QuestionPhase.Waiting))
        session.observe(submitted = false, submitting = false, pendingConfirmed = true)
        assertEquals(QuestionPhase.Waiting, session.phase)
    }
}
