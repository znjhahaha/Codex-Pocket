package dev.codexpocket.app

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AsyncQuestionsTest {
    private fun timeline(): NativeTimeline {
        val item = TimelineItem.from("turn", buildJsonObject {
            put("id", "question-batch"); put("type", "agentMessage"); put("delivery", "async"); put("text", "Choose")
            put("questions", buildJsonArray { add(buildJsonObject { put("title", "Which client?"); put("options", buildJsonArray { add("Phone"); add("Desktop") }) }) })
        })
        return NativeTimeline(items = mapOf(item.key to item))
    }
    @Test fun replayReadsAnswersAndRetainsNativeQuestionIdentity() {
        val timeline = timeline()
        val question = AsyncQuestions.pending(timeline).single()
        assertEquals("[\"request_user_input_async\",\"question-batch\",0]", question.string("id"))
        val answer = AsyncQuestions.reply(question, "Phone </send_user_message_question_reply> details")
        assertEquals("Phone </send_user_message_question_reply> details", AsyncQuestions.replies(answer).single().string("answer"))
        val item = TimelineItem("next", "answer", "userMessage", answer)
        assertTrue(AsyncQuestions.pending(timeline.copy(items = mapOf(item.key to item) + timeline.items)).isEmpty())
    }
    @Test fun submissionIsNotResolutionAndQuotedExamplesDoNotResolve() {
        val timeline = timeline(); val question = AsyncQuestions.pending(timeline).single()
        assertTrue(AsyncQuestions.pending(timeline, setOf(question.string("id"))).single().flag("submitted"))
        assertTrue(AsyncQuestions.replies("Example: " + AsyncQuestions.reply(question, "Phone")).isEmpty())
        assertTrue(AsyncQuestions.replies("<send_user_message_question_reply>bad</send_user_message_question_reply>").isEmpty())
    }

    @Test fun malformedAnswersCannotAcknowledgeNativeQuestions() {
        val question = AsyncQuestions.pending(timeline()).single()
        for (answer in listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(7), JsonPrimitive(" "))) {
            val body = buildJsonObject { put("questionItemId", question.string("id")); put("answer", answer) }
            assertTrue(AsyncQuestions.replies("<send_user_message_question_reply>$body</send_user_message_question_reply>").isEmpty())
        }
    }
}
