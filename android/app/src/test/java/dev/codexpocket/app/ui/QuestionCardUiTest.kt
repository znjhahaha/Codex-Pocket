package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class QuestionCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun batch() = QuestionBatches.blocking("rpc", Json.parseToJsonElement("""{"questions":[
        {"id":"one","header":"Client","question":"Which client?","options":[{"label":"Phone (Recommended)","description":"Phone details"},{"label":"Desktop"}],"isOther":true},
        {"id":"two","question":"Which region?","options":[{"label":"Local"},{"label":"Remote"}]},
        {"id":"three","question":"Access code?","isSecret":true}
    ]}""").jsonObject)
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlass provides false) { block() }
        } } }
        compose.waitForIdle()
    }

    @Test fun optionAndNavigationNeverSubmitAndFinalSubmitSendsAllAnswersOnce() {
        val batch = batch()
        val session = QuestionSession(batch)
        val submissions = mutableListOf<List<Pair<String, String>>>()
        content { QuestionCard(batch, session, QuestionPhase.Waiting, { submissions += it }) }
        compose.onNodeWithTag("question-page").assertTextEquals("1/3")
        compose.onNodeWithText("推荐").assertExists()
        compose.onNodeWithText("Phone details").assertExists()
        compose.onNodeWithText("Which region?").assertDoesNotExist()
        compose.onNodeWithTag("question-option:one:0").performClick()
        compose.onNodeWithTag("question-next").performClick()
        compose.onNodeWithTag("question-option:two:1").performClick()
        compose.onNodeWithTag("question-previous").performClick()
        compose.onNodeWithTag("question-option:one:0").assertIsSelected()
        compose.onNodeWithTag("question-next").performClick()
        compose.onNodeWithTag("question-next").performClick()
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        compose.onNodeWithTag("question-answer:three").performTextInput("fixture-secret")
        assertTrue(submissions.isEmpty())
        compose.onNodeWithTag("question-submit").performClick()
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        assertEquals(listOf("Phone (Recommended)", "Remote", "fixture-secret"), submissions.single().map { it.second })
    }

    @Test fun completedNativeQuestionsRenderHumanAnswersWithoutRawResultOrSecret() {
        val item = TimelineItem.from("turn", pocketJson.parseToJsonElement("""{
            "id":"history","type":"dynamicToolCall","tool":"functions.request_user_input","status":"completed","success":true,
            "arguments":{"questions":[{"id":"public","question":"Which client?"},{"id":"secret","question":"Access code?","isSecret":true}]},
            "contentItems":[{"type":"inputText","text":"{\"answers\":{\"public\":{\"answers\":[\"Phone\"]},\"secret\":{\"answers\":[\"private-history-fixture\"]}}}"}]
        }""").jsonObject)
        content { HistoricalQuestionRow(NativeQuestionHistory.from(item)!!, item.key) }
        compose.onNodeWithText("已询问 2 个问题").assertExists()
        compose.onNodeWithText("Which client?").assertDoesNotExist()
        compose.onNodeWithTag("question-history-toggle:${item.key}").performClick()
        compose.onNodeWithText("Which client?").assertExists()
        compose.onNodeWithTag("question-history-answer:public").assertTextEquals("回答：Phone")
        compose.onNodeWithTag("question-history-answer:secret").assertTextEquals("回答：敏感回答已隐藏")
        compose.onNodeWithText("private-history-fixture", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("question-submit").assertDoesNotExist()
    }

    @Test fun offlineAndStaleStatesDisableAnswerAndSubmit() {
        val batch = QuestionBatches.blocking("single", Json.parseToJsonElement("""{"questions":[{"id":"q","question":"Choose","options":[{"label":"Yes"}]}]}""").jsonObject)
        val session = QuestionSession(batch)
        session.selectOption("q", "Yes")
        var phase by mutableStateOf(QuestionPhase.Offline)
        content { QuestionCard(batch, session, phase, { fail("Submission must be blocked") }) }
        compose.onNodeWithTag("question-status").assertTextContains("离线", substring = true)
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        compose.onNodeWithTag("question-option:q:0").assertIsNotEnabled()
        compose.runOnIdle { phase = QuestionPhase.Stale }
        compose.onNodeWithTag("question-status").assertTextContains("同步", substring = true)
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
    }

    @Test fun compatibilityAsyncFormUsesExplicitSubmitAndOldCallbackShape() {
        val question = buildJsonObject { put("id", "async-one"); put("questionItemId", "async-one"); put("title", "Choose"); put("options", buildJsonArray { add("Phone"); add("Desktop") }) }
        val chat = ChatEntity(ThreadKey("pc", "store", "chat"), "runtime", "desktop", "Chat", "", "", 1, "idle", false)
        val runtime = RuntimeInfo("runtime", "desktop", "", "store", true, emptySet(), setOf("turn/start"))
        val s = PocketState(online = true, selected = chat, runtimes = listOf(runtime), conversationLoadState = ConversationLoadState.Ready)
        var answer: Pair<String, JsonObject>? = null
        content { AsyncQuestionForm(question, s) { id, json -> answer = id to json } }
        compose.onNodeWithTag("question-option:async-one:0").performClick()
        assertNull(answer)
        compose.onNodeWithTag("question-submit").performClick()
        assertEquals("async-one", answer!!.first)
        assertEquals("Phone", answer!!.second["answer"]!!.jsonPrimitive.content)
    }

    @Test fun dockCollapseAndBatchSwitchKeepAnswersByRequestIdentity() {
        val chat = ChatEntity(ThreadKey("pc", "store", "chat"), "runtime", "desktop", "Chat", "", "", 1, "idle", false)
        val runtime = RuntimeInfo("runtime", "desktop", "", "store", true, emptySet(), emptySet())
        fun request(id: String) = PendingApproval("runtime", JsonPrimitive(id), "item/tool/requestUserInput", buildJsonObject {
            put("threadId", "chat"); put("questions", buildJsonArray {
                add(buildJsonObject { put("id", "answer"); put("question", "Choose $id"); put("options", buildJsonArray { add(buildJsonObject { put("label", "Yes") }) }) })
            })
        })
        val requests = listOf(request("a"), request("b"))
        var state by mutableStateOf(PocketState(computerId = "pc", online = true, selected = chat, runtimes = listOf(runtime),
            approvals = requests, approvalsFresh = true, conversationLoadState = ConversationLoadState.Ready))
        var submissions = 0
        content { QuestionDock(state, { _, _ -> submissions++ }, { fail("Unexpected async callback") }) }
        compose.onNodeWithTag("question-batch-count").assertTextEquals("待处理 · 2")
        compose.onNodeWithTag("question-option:answer:0").performClick()
        compose.onNodeWithContentDescription("下一批").performClick()
        compose.onNodeWithText("Choose b").assertExists()
        compose.onNodeWithTag("question-option:answer:0").performClick()
        compose.onNodeWithContentDescription("收起问题").performClick()
        compose.onNodeWithTag("question-card").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开问题").performClick()
        compose.onNodeWithTag("question-option:answer:0").assertIsSelected()
        compose.runOnIdle { state = state.copy(approvals = requests.reversed()) }
        compose.onNodeWithText("Choose b").assertExists()
        compose.onNodeWithContentDescription("下一批").performClick()
        compose.onNodeWithText("Choose a").assertExists()
        compose.onNodeWithTag("question-option:answer:0").assertIsSelected()
        assertEquals(0, submissions)
    }

    @Test fun sensitiveTextIsMaskedAndNeverRestoredAcrossRequestIdentityChanges() {
        var request by mutableStateOf(PendingApproval("runtime", JsonPrimitive("first"), "item/tool/requestUserInput",
            Json.parseToJsonElement("""{"questions":[{"id":"secret","question":"Access code?","isSecret":true}]}""").jsonObject))
        content { ApprovalCard(request, true) { _, _ -> fail("Typing must not submit") } }
        compose.onNodeWithTag("question-answer:secret").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("question-answer:secret").performTextInput("fixture-secret")
        compose.onNodeWithTag("question-submit").assertIsEnabled()
        compose.runOnIdle { request = request.copy(requestId = JsonPrimitive("second")) }
        compose.onNodeWithTag("question-answer:secret").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        compose.runOnIdle { request = request.copy(requestId = JsonPrimitive("first")) }
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
    }

    @Test fun dockEmitsOneOrderedAsyncBatchOnlyAfterAllPagesAreAnswered() {
        val native = TimelineItem.from("turn", buildJsonObject {
            put("id", "native-batch"); put("type", "agentMessage"); put("delivery", "async")
            put("questions", buildJsonArray { repeat(3) { index -> add(buildJsonObject {
                put("title", "Question $index"); put("options", buildJsonArray { add("Yes"); add("No") })
            }) } })
        })
        val timeline = NativeTimeline(items = mapOf(native.key to native))
        val questions = AsyncQuestions.pending(timeline)
        val chat = ChatEntity(ThreadKey("pc", "store", "chat"), "runtime", "desktop", "Chat", "", "", 1, "idle", false)
        val runtime = RuntimeInfo("runtime", "desktop", "", "store", true, emptySet(), setOf("turn/start"))
        val state = PocketState(computerId = "pc", online = true, selected = chat, runtimes = listOf(runtime), timeline = timeline,
            conversationLoadState = ConversationLoadState.Ready, advanced = AdvancedConversationState(asyncQuestions = questions))
        val emissions = mutableListOf<List<Pair<JsonObject, String>>>()
        content { QuestionDock(state, { _, _ -> fail("Async questions must not produce approval responses") }, { emissions += it }) }
        compose.onNodeWithTag("question-batch-count").assertTextEquals("待处理 · 1")
        repeat(3) { index ->
            compose.onNodeWithTag("question-page").assertTextEquals("${index + 1}/3")
            if (index == 2) compose.onNodeWithTag("question-submit").assertIsNotEnabled()
            compose.onNodeWithTag("question-option:${questions[index].string("id")}:0").performClick()
            assertTrue(emissions.isEmpty())
            if (index < 2) compose.onNodeWithTag("question-next").performClick()
        }
        compose.onNodeWithTag("question-submit").performClick()
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        assertEquals(questions.map { it.string("id") }, emissions.single().map { it.first.string("id") })
        assertEquals(listOf("Yes", "Yes", "Yes"), emissions.single().map { it.second })
    }

    @Test fun asyncSecretBatchShowsDesktopNoticeWithoutInputsOrSubmit() {
        val batch = QuestionBatches.async("chat", listOf(Json.parseToJsonElement("""{"id":"q","questionItemId":"q","title":"Access code?","isSecret":true,"options":["Yes","No"]}""").jsonObject)).single()
        val session = QuestionSession(batch)
        content { QuestionCard(batch, session, QuestionPhase.Waiting, { fail("Secret async batch must never dispatch") }) }
        compose.onNodeWithTag("question-status").assertTextEquals("此问题包含敏感字段，当前异步协议会保存为聊天消息，请在电脑端处理")
        compose.onNodeWithTag("question-option:q:0").assertDoesNotExist()
        compose.onNodeWithTag("question-answer:q").assertDoesNotExist()
        compose.onNodeWithTag("question-other:q").assertDoesNotExist()
        compose.onNodeWithTag("question-submit").assertDoesNotExist()
    }
}
