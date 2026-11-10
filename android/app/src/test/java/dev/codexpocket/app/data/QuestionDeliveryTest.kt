package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuestionDeliveryTest {
    private val chat = ThreadKey("pc", "store", "thread")
    private fun params(secret: Boolean = false) = buildJsonObject {
        put("threadId", "thread"); put("turnId", "turn"); put("itemId", "item")
        put("questions", buildJsonArray {
            add(buildJsonObject { put("id", "public"); put("question", "Which client?"); put("isOther", true) })
            add(buildJsonObject { put("id", "private"); put("question", "Access code?"); put("isSecret", secret) })
        })
    }
    private fun response() = buildJsonObject { put("answers", buildJsonObject {
        put("public", buildJsonObject { put("answers", buildJsonArray { add("Phone") }) })
        put("private", buildJsonObject { put("answers", buildJsonArray { add("secret-fixture") }) })
    }) }
    private fun receipt(secret: Boolean = false) = QuestionReceipt.capture("command", chat,
        PendingApproval("runtime", JsonPrimitive("request"), "item/tool/requestUserInput", params(secret)), response())
    private fun delivery(phase: String) = buildJsonObject {
        put("commandId", "command"); put("runtimeId", "runtime"); put("threadId", "thread")
        put("turnId", "turn"); put("itemId", "item"); put("requestId", "request"); put("phase", phase)
    }
    private fun item(status: String = "completed", content: JsonArray? = null, secret: Boolean = false): TimelineItem = TimelineItem.from("turn", buildJsonObject {
        put("id", "item"); put("type", "dynamicToolCall"); put("tool", "functions.request_user_input")
        put("status", status); put("success", true); put("arguments", params(secret))
        put("contentItems", content ?: buildJsonArray { add(buildJsonObject { put("type", "inputText"); put("text", response().toString()) }) })
    })

    @Test fun phasesUseBackendSnakeCaseAndOnlyConfirmedAccepts() {
        val r = receipt()
        for (phase in listOf("dispatching", "forwarded", "awaiting_confirmation")) {
            val next = r.withDelivery(delivery(phase))!!
            assertEquals("submitted", next.commandStatus("accepted"))
            assertFalse(next.phase.terminal)
        }
        assertEquals("accepted", r.withDelivery(delivery("confirmed"))!!.commandStatus("submitted"))
        assertEquals("answered_elsewhere", r.withDelivery(delivery("answered_elsewhere"))!!.commandStatus("accepted"))
        assertEquals("cancelled", r.withDelivery(delivery("cancelled"))!!.commandStatus("accepted"))
        assertNull(r.withDelivery(delivery("Confirmed")))
        assertEquals("submitted", r.commandStatus("accepted"))
    }
    @Test fun receiptRequiresEveryNativeIdentityAndTerminalPhasesDoNotRegress() {
        for (field in listOf("commandId", "runtimeId", "threadId", "turnId", "itemId", "requestId")) {
            assertNull(receipt().withDelivery(JsonObject(delivery("confirmed") + (field to JsonPrimitive("wrong")))))
        }
        val confirmed = receipt().withDelivery(delivery("confirmed"))!!
        assertEquals(QuestionDeliveryPhase.Confirmed, confirmed.withDelivery(delivery("forwarded"))!!.phase)
    }
    @Test fun roundTripRetainsOnlyNonsecretSubmittedAnswersAndNoDiagnosticText() {
        val r = receipt(secret = true)
        val json = r.json().toString()
        assertFalse(json.contains("secret-fixture"))
        assertFalse(r.toString().contains("Phone"))
        val restored = QuestionReceipt.from(pocketJson.parseToJsonElement(json).jsonObject)!!
        assertEquals("command", restored.commandId)
        assertEquals(mapOf("public" to "Phone"), restored.restorableAnswers(QuestionBatches.blocking(r.requestKey, params(true))))
        val changed = QuestionBatches.blocking(r.requestKey, params(true)).let { batch ->
            batch.copy(questions = batch.questions.map { it.copy(prompt = "Changed prompt") })
        }
        assertTrue(restored.restorableAnswers(changed).isEmpty())
    }
    @Test fun historicalAnswersRequireSingleActualCompletedInputTextResult() {
        val history = NativeQuestionHistory.from(item())!!
        assertEquals("Phone", history.answers["public"])
        assertEquals("Which client?", history.batch.questions.first().prompt)
        assertTrue(NativeQuestionHistory.from(item(status = "inProgress"))!!.answers.isEmpty())
        for (type in listOf("outputText", "inputImage")) {
            assertTrue(NativeQuestionHistory.from(item(content = buildJsonArray {
                add(buildJsonObject { put("type", type); put("text", response().toString()) })
            }))!!.answers.isEmpty())
        }
        val duplicate = buildJsonArray { repeat(2) { add(buildJsonObject { put("type", "inputText"); put("text", response().toString()) }) } }
        assertTrue(NativeQuestionHistory.from(item(content = duplicate))!!.answers.isEmpty())
        val failed = item().let { it.copy(raw = JsonObject(it.raw + ("success" to JsonPrimitive(false)))) }
        assertTrue(NativeQuestionHistory.from(failed)!!.answers.isEmpty())
        val projected = item(content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", response().toString()) })
        })
        assertEquals("Phone", NativeQuestionHistory.from(projected)!!.answers["public"])
        val blocksOnly = projected.copy(raw = JsonObject((projected.raw - "contentItems") +
            ("toolOutputBlocks" to projected.raw.getValue("contentItems"))))
        assertEquals("Phone", NativeQuestionHistory.from(blocksOnly)!!.answers["public"])
    }
    @Test fun cancellationAndMalformedAnswerIdsDoNotInventAcceptedAnswers() {
        fun content(text: String) = buildJsonArray { add(buildJsonObject { put("type", "inputText"); put("text", text) }) }
        assertTrue(NativeQuestionHistory.from(item(content = content("{\"answers\":{}}")))!!.cancelled)
        for (text in listOf("broken", "{\"answers\":{\"wrong\":{\"answers\":[\"Phone\"]}}}",
            "{\"answers\":{\"public\":{\"answers\":[true]},\"private\":{\"answers\":[]}}}")) {
            assertTrue(NativeQuestionHistory.from(item(content = content(text)))!!.answers.isEmpty())
        }
    }
    @Test fun secretNativeAnswersAreHiddenAndRemovedBeforeRoomPersistence() {
        val native = item(secret = true)
        assertFalse(NativeQuestionHistory.from(native)!!.text.contains("secret-fixture"))
        assertEquals("敏感回答已隐藏", NativeQuestionHistory.from(native)!!.answers["private"])
        val cached = ItemEntity.from(chat, native, 0)
        assertFalse(cached.raw.toString().contains("secret-fixture"))
        assertFalse(cached.text.contains("secret-fixture"))
        assertEquals("Phone", NativeQuestionHistory.from(cached.item())!!.answers["public"])
        assertFalse(ConversationPresentation.detail(native).contains("secret-fixture"))
        assertFalse(NativeActivityPresentation.describe(native).output.text.contains("secret-fixture"))
        val projected = native.copy(raw = JsonObject((native.raw - "contentItems") + ("toolOutputBlocks" to buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", response().toString()) })
        })))
        val safeProjected = ItemEntity.from(chat, projected, 0)
        assertFalse(safeProjected.raw.toString().contains("secret-fixture"))
        assertEquals("Phone", NativeQuestionHistory.from(safeProjected.item())!!.answers["public"])
    }
    @Test fun fourMaximumSizeAnswersRemainReadableWithinNativeFrameLimit() {
        val ids = (0..3).map { "q$it" }
        val answer = "a".repeat(MAX_QUESTION_ANSWER_BYTES)
        val arguments = buildJsonObject { put("questions", buildJsonArray {
            ids.forEach { id -> add(buildJsonObject { put("id", id); put("question", id); put("isOther", true) }) }
        }) }
        val output = buildJsonObject { put("answers", buildJsonObject {
            ids.forEach { id -> put(id, buildJsonObject { put("answers", buildJsonArray { add(answer) }) }) }
        }) }.toString()
        assertTrue(output.toByteArray(Charsets.UTF_8).size > 256 * 1024)
        val native = TimelineItem.from("turn", buildJsonObject {
            put("id", "large"); put("type", "dynamicToolCall"); put("tool", "functions.request_user_input")
            put("status", "completed"); put("success", true); put("arguments", arguments)
            put("contentItems", buildJsonArray { add(buildJsonObject { put("type", "inputText"); put("text", output) }) })
        })
        val actual = NativeQuestionHistory.from(native)!!.answers
        assertEquals(ids.toSet(), actual.keys)
        assertTrue(actual.values.all { it == answer })
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class QuestionAnswerStoreTest {
    @Test fun staleWritersCannotRegressAnyTerminalReceiptAcrossStoreInstances() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val preferences = app.getSharedPreferences("question-delivery", android.content.Context.MODE_PRIVATE)
        val original = QuestionReceipt.capture("command", ThreadKey("pc", "store", "thread"),
            PendingApproval("runtime", JsonPrimitive("request"), "item/tool/requestUserInput", buildJsonObject {
                put("threadId", "thread"); put("turnId", "turn"); put("itemId", "item")
                put("questions", buildJsonArray { add(buildJsonObject { put("id", "q"); put("question", "Choose") }) })
            }), buildJsonObject { put("answers", buildJsonObject { put("q", buildJsonObject { put("answers", buildJsonArray { add("Phone") }) }) }) })
        for (phase in QuestionDeliveryPhase.entries.filter { it.terminal }) {
            preferences.edit().clear().commit()
            val terminal = QuestionReceipt.from(JsonObject(original.json() + ("phase" to JsonPrimitive(phase.wire))))!!
            QuestionAnswerStore(app).save(terminal)
            QuestionAnswerStore(app).save(original.awaitingConfirmation())
            assertEquals(phase, QuestionAnswerStore(app).command("command")!!.phase)
            QuestionAnswerStore(app).removeRejected("command")
            assertEquals("Late errors must not delete terminal evidence", phase, QuestionAnswerStore(app).command("command")!!.phase)
        }
        preferences.edit().clear().commit()
    }
}
