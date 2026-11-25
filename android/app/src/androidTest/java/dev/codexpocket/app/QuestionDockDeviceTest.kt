package dev.codexpocket.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Local UI fixture: submitting changes only this in-memory request, never a real conversation. */
@RunWith(AndroidJUnit4::class)
class QuestionDockDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun unansweredQuestionCanCloseRestoreAndDeleteWithoutSending() {
        compose.mainClock.autoAdvance = false
        val chat = ChatEntity(ThreadKey("fixture", "store", "chat"), "runtime", "desktop", "Fixture", "", "", 1, "active", false)
        val question = TimelineItem.from("old", buildJsonObject {
            put("id", "old-question"); put("type", "agentMessage"); put("delivery", "async")
            put("text", "请解锁电脑后告诉我")
            putJsonArray("questions") { addJsonObject {
                put("title", "CCS 已找到，但电脑现在锁屏，请解锁电脑后告诉我。")
                putJsonArray("options") { add("继续使用 ADB"); add("稍后处理") }
            } }
        })
        val initial = NativeTimeline(items = linkedMapOf(question.key to question))
        val pending = AsyncQuestions.pending(initial)
        var state by mutableStateOf(PocketState(online = true, computerId = "fixture", selected = chat,
            runtimes = listOf(RuntimeInfo("runtime", "desktop", "1", "store", true, setOf("chat"), setOf("turn/start"))),
            approvalsFresh = true, conversationLoadState = ConversationLoadState.Ready, timeline = initial,
            advanced = AdvancedConversationState(asyncQuestions = pending), pendingQuestions = mapOf(chat.key.encoded to pending)))
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("dark") { PocketBackdrop {
            Box(Modifier.fillMaxSize().testTag("question-controls-proof-root")) {
                Column(Modifier.align(Alignment.Center).padding(20.dp)) {
                    PText("你用 adb 操作不就好了")
                    PText("已改用 ADB，继续测试。")
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    QuestionDock(state, { _, _ -> error("No approval submission") }, { error("No synthesized answer") },
                        onClose = { _, ids -> state = state.copy(closedQuestions = state.closedQuestions + ids) },
                        onRestore = { _, ids -> state = state.copy(closedQuestions = state.closedQuestions - ids) },
                        onDelete = { _, id ->
                            val remaining = state.advanced.asyncQuestions.filterNot { it.string("id") == id }
                            state = state.copy(advanced = state.advanced.copy(asyncQuestions = remaining),
                                pendingQuestions = if (remaining.isEmpty()) emptyMap() else mapOf(chat.key.encoded to remaining))
                        })
                    PocketTextField("继续检查工具调用", {}, Modifier.fillMaxWidth(), label = "消息")
                }
            }
        } } } }
        fun settle() { repeat(32) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
        settle()
        compose.onNodeWithTag("question-submit").assertIsDisplayed()
        compose.runOnIdle {
            val reply = TimelineItem("next", "ordinary", "userMessage", "你用 adb 操作不就好了", complete = true)
            val response = TimelineItem("next", "continuation", "agentMessage", "已改用 ADB，继续测试。", complete = true)
            val timeline = initial.copy(items = linkedMapOf(question.key to question, reply.key to reply, response.key to response))
            val remaining = AsyncQuestions.pending(timeline)
            state = state.copy(timeline = timeline, advanced = state.advanced.copy(asyncQuestions = remaining),
                pendingQuestions = if (remaining.isEmpty()) emptyMap() else mapOf(chat.key.encoded to remaining))
        }
        settle()
        // Ordinary cross-device conversation does not answer the pending native question.
        compose.onNodeWithTag("question-submit").assertIsDisplayed()
        val id = pending.single().string("id")
        compose.onNodeWithTag("question-option:$id:0").performClick()
        settle()
        saveControlsScreenshot("expanded")
        compose.onNodeWithTag("question-close").performClick()
        settle()
        compose.onNodeWithTag("question-dock").assertDoesNotExist()
        compose.onNodeWithTag("question-submit").assertDoesNotExist()
        compose.onNodeWithTag("question-restore").assertIsDisplayed()
        assertEquals(1, questionAttentionCount(state))
        saveControlsScreenshot("closed")
        compose.onNodeWithTag("question-restore").performClick()
        settle()
        compose.onNodeWithTag("question-option:$id:0").assertIsSelected()
        compose.onNodeWithTag("question-delete:$id").performClick()
        settle()
        compose.onNodeWithTag("question-dock").assertDoesNotExist()
        compose.onNodeWithTag("question-restore").assertDoesNotExist()
        compose.onNodeWithText("已改用 ADB，继续测试。").assertIsDisplayed()
        compose.onNodeWithText("继续检查工具调用").assertIsDisplayed()
        assertEquals(0, questionAttentionCount(state))
        assertEquals(1, AsyncQuestions.pending(state.timeline).size)
        saveControlsScreenshot("deleted")
    }

    private fun saveControlsScreenshot(name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "question-controls-$name.png")
        val bitmap = compose.onNodeWithTag("question-controls-proof-root").captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun submittedQuestionLeavesDockWhileBodyAndReceiptRemain() {
        compose.mainClock.autoAdvance = false
        val chat = ChatEntity(ThreadKey("fixture", "store", "chat"), "runtime", "desktop", "Fixture", "", "", 1, "active", false)
        val approval = PendingApproval("runtime", JsonPrimitive("question"), "item/tool/requestUserInput", buildJsonObject {
            put("threadId", "chat"); put("questions", buildJsonArray { add(buildJsonObject {
                put("id", "choice"); put("question", "选择本地测试方案")
                put("options", buildJsonArray { add(buildJsonObject { put("label", "方案一") }); add(buildJsonObject { put("label", "方案二") }) })
            }) })
        })
        var state by mutableStateOf(PocketState(online = true, computerId = "fixture", selected = chat,
            runtimes = listOf(RuntimeInfo("runtime", "desktop", "1", "store", true, setOf("chat"), emptySet())),
            approvals = listOf(approval), approvalsFresh = true, conversationLoadState = ConversationLoadState.Ready))
        var submissions = 0
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { PocketBackdrop {
            Box(Modifier.fillMaxSize().testTag("question-proof-root")) {
                PText("会话正文保持可读", Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.BottomCenter)) { QuestionDock(state, { _, _ ->
                    submissions++
                    state = state.copy(approvals = listOf(approval.copy(submitted = true)), commands = listOf(
                        CommandEntity("fixture-command", chat.key.computerId, chat.key, "approvals/respond", "unknown", 1, draftText = approval.key)))
                }, { _ -> error("No asynchronous question in fixture") }) }
            }
        } } } }
        fun settle() { repeat(32) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
        settle()
        compose.onNodeWithTag("question-option:choice:0").performClick()
        compose.onNodeWithTag("question-submit").performClick()
        settle()
        compose.onNodeWithTag("question-submit").assertDoesNotExist()
        compose.onNodeWithTag("question-status").assertDoesNotExist()
        compose.onNodeWithText("会话正文保持可读").assertIsDisplayed()
        assertEquals(1, submissions)
        assertEquals("unknown", state.commands.single().status)
        assertEquals(0, questionAttentionCount(state))
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "question-dock-submitted.png")
        val bitmap = compose.onNodeWithTag("question-proof-root").captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        // A definite refusal restores the same retained answer, without resending it.
        compose.runOnIdle { state = state.copy(approvals = listOf(approval), commands = emptyList()) }
        settle()
        compose.onNodeWithTag("question-option:choice:0").assertIsSelected()
        compose.onNodeWithTag("question-submit").assertIsDisplayed()
        assertEquals(1, submissions)
    }
}
