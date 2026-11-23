package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A sent message is edited in its own bubble: no dock, no dialog, and every state of the edit is said beside it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageEditBubbleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val key = ThreadKey("pc", "store", "thread")
    private val sent = TimelineItem.from("turn", pocketJson.parseToJsonElement("""{"id":"sent","type":"userMessage","content":[{"type":"text","text":"把按钮改成蓝色"}]}""").jsonObject, true)
    private val other = TimelineItem.from("turn2", pocketJson.parseToJsonElement("""{"id":"later","type":"userMessage","content":[{"type":"text","text":"再加一个测试"}]}""").jsonObject, true)
    private var edit by mutableStateOf<MessageEditState?>(null)
    private var online by mutableStateOf(true)
    private var running by mutableStateOf(false)
    private val calls = mutableListOf<String>()
    private lateinit var view: android.view.View

    private fun ready(text: String = "把按钮改成蓝色") = MessageEditState(key, "runtime", sent.turnId, sent.itemId, text, "把按钮改成蓝色", MessageEditPhase.Ready, version = "v1", token = "t1")
    private fun content() {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlass provides false, LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, true)) {
                ProvideMarkdownRendering {
                    val host = edit?.let { state -> MessageEditHost(state, online, running, { text -> calls += "text:$text"; edit = edit?.copy(text = text) },
                        { calls += "submit" }, { calls += "cancel"; edit = null }, { calls += "check" }, { calls += "prepare" }, { calls += "stop" }) }
                    CompositionLocalProvider(LocalMessageEdit provides host) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            listOf(sent, other).forEach { item ->
                                TranscriptRowView(TranscriptRow(item.key, TranscriptKind.User, listOf(item)), "edit", false, {}, {}, onEdit = { calls += "begin:${it.itemId}"; edit = ready() })
                            }
                        }
                    }
                }
            }
        } } }
        compose.waitForIdle()
    }
    private fun capture(name: String) = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File("build/reports/kit/$name.png").also { it.parentFile.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun theBubbleItselfBecomesTheFieldAndOnlyThatBubble() {
        content()
        compose.onNodeWithTag("message-edit-bubble").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("编辑此消息")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf("begin:sent"), calls)
        compose.onNodeWithTag("message-edit-bubble").assertExists()
        compose.onNodeWithTag("message-edit-text").assertTextEquals("把按钮改成蓝色").assertIsFocused()
        // The other message is still a message, with its own pencil; the edited one has given its actions up for the two buttons.
        compose.onNodeWithText("再加一个测试").assertExists()
        compose.onAllNodesWithContentDescription("编辑此消息").assertCountEquals(1)
        compose.onNodeWithTag("message-edit-dock").assertDoesNotExist()
        compose.onNodeWithText("重发会撤回此回合及后续会话，不会撤销文件修改。").assertExists()
        capture("message-edit-open")
        compose.onNodeWithTag("message-edit-text").performTextReplacement("把按钮改成绿色，并加上图标")
        compose.waitForIdle()
        assertEquals("text:把按钮改成绿色，并加上图标", calls.last())
        compose.onNodeWithTag("message-edit-text").assertTextEquals("把按钮改成绿色，并加上图标")
        compose.onNodeWithTag("message-edit-submit").assertIsEnabled().performClick()
        assertEquals("submit", calls.last())
        compose.onNodeWithTag("message-edit-cancel").performClick()
        compose.waitForIdle()
        assertEquals("cancel", calls.last())
        compose.onNodeWithTag("message-edit-bubble").assertDoesNotExist()
        compose.onNodeWithText("把按钮改成蓝色").assertExists()
    }

    @Test fun everyStateOfTheEditIsSaidBesideTheBubble() {
        edit = ready().copy(phase = MessageEditPhase.Preparing, token = "")
        content()
        compose.onNodeWithText("正在核对原消息").assertExists()
        compose.onNodeWithTag("message-edit-text").assertIsNotEnabled()
        compose.onNodeWithTag("message-edit-submit").assertDoesNotExist(); compose.onNodeWithTag("message-edit-cancel").assertDoesNotExist()
        compose.runOnIdle { edit = ready().copy(text = "  ") }
        compose.onNodeWithTag("message-edit-submit").assertIsNotEnabled()
        compose.runOnIdle { edit = ready(); online = false }
        compose.onNodeWithTag("message-edit-submit").assertIsNotEnabled()
        compose.runOnIdle { online = true; running = true }
        compose.onNodeWithTag("message-edit-submit").assertDoesNotExist()
        compose.onNodeWithText("先停止当前任务").performClick(); assertEquals("stop", calls.last())
        compose.runOnIdle { running = false; edit = ready().copy(phase = MessageEditPhase.Reverting) }
        compose.onNodeWithText("正在撤回会话").assertExists()
        compose.runOnIdle { edit = ready().copy(phase = MessageEditPhase.UnknownSend) }
        compose.onNodeWithTag("message-edit-text").assertIsNotEnabled()
        compose.onNodeWithText("核对操作结果").performClick(); assertEquals("check", calls.last())
        compose.runOnIdle { edit = ready().copy(phase = MessageEditPhase.Unavailable, reason = "原消息读取失败，请重试") }
        compose.onNodeWithText("原消息读取失败，请重试").assertExists()
        compose.onNodeWithText("重新读取消息").performClick(); assertEquals("prepare", calls.last())
        compose.runOnIdle { edit = ready().copy(phase = MessageEditPhase.Reverted, reverted = true, retainedItems = 2) }
        compose.onNodeWithText("原消息及后续会话已撤回，编辑稿已保留。 保留 2 个原附件或引用").assertExists()
        compose.onNodeWithContentDescription("发送修改后的消息").assertIsEnabled()
        capture("message-edit-reverted")
    }
}
