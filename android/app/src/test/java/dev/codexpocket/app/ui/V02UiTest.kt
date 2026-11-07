@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.codexpocket.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real Compose/Android host rendering, deliberately not emulator/device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V02UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun content(block: @Composable () -> Unit) {
        // Robolectric reports an accelerated window although view.draw below uses a software
        // canvas. Exercise the explicit opaque fallback instead of replaying GPU lens layers.
        compose.activity.runOnUiThread { compose.activity.setContent { CompositionLocalProvider(LocalPocketGlass provides false) { block() } } }
        compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        // PixelCopy needs a hardware window frame that Robolectric does not provide.
        // Draw the actual Android view tree with native Skia instead.
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        val file = File("build/reports/v02-ui/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val phone = Bitmap.createScaledBitmap(bitmap, 412, bitmap.height * 412 / bitmap.width, true)
        File(file.parentFile, "$name-phone.png").outputStream().use { assertTrue(phone.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }
    @Test fun homeLightAndDarkRenderRealComponents() {
        content { ChatsLightPreview() }
        compose.onNodeWithTag("home-navigation").assertExists()
        compose.onNodeWithText("Codex Pocket").assertDoesNotExist()
        compose.onNodeWithText("把想法变成一个小工具").assertExists()
        screenshot("home-light")
        content { ChatsDarkPreview() }
        screenshot("home-dark")
    }
    @Test fun conversationLightAndDarkHaveNoHomeNavigation() {
        content { ConversationLightPreview() }
        compose.onNodeWithTag("home-navigation").assertDoesNotExist()
        compose.onNodeWithTag("composer").assertExists()
        compose.onNodeWithContentDescription("模型：gpt-6.1-sol").assertExists()
        val capsule = compose.onNodeWithTag("composer-model").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("conversation-title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Model control belongs above the bottom input", capsule.top > title.bottom + 300)
        assertTrue("Removing the header model should leave the title room", title.width > 200)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("code-block").fetchSemanticsNodes().isNotEmpty() }
        screenshot("conversation-light")
        content { ConversationDarkPreview() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("code-block").fetchSemanticsNodes().isNotEmpty() }
        screenshot("conversation-dark")
    }
    @Test fun queueAndSteerInvokeSeparateActions() {
        var queue = 0
        var steer = 0
        content { PocketTheme("light") { Surface { PocketComposer("direction", "Codex", "高", true, ComposerAvailability(true, true, true, true), {}, {}, {}, {}, { queue++ }, { steer++ }, {}, true) } } }
        compose.onNodeWithTag("composer-send").performClick()
        assertEquals(1, queue); assertEquals(0, steer)
        compose.onNodeWithTag("composer-steer").performClick()
        assertEquals(1, queue); assertEquals(1, steer)
        compose.onNodeWithContentDescription(compose.activity.getString(dev.codexpocket.app.R.string.v02_references)).performClick()
        compose.onNodeWithText("展开输入框").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("composer-expanded-input").assertTextEquals("direction")
        compose.onNodeWithContentDescription("收起输入框").assertExists()
        screenshot("composer-running-expanded")
    }
    @Test fun approvalShowsOnlyHumanFieldsAndHonorsSubmittedState() {
        val request = PendingApproval("r", JsonPrimitive(7), "item/commandExecution/requestApproval", pocketJson.parseToJsonElement("""{"threadId":"t","command":"gradle test","reason":"Run tests","rawSecret":"MUST_NOT_RENDER","availableDecisions":["accept","decline"]}""").jsonObject)
        content { PocketTheme("light") { Surface { ApprovalCard(request, true) { _, _ -> } } } }
        compose.onNodeWithText("gradle test").assertExists()
        compose.onNodeWithText("MUST_NOT_RENDER", substring = true).assertDoesNotExist()
        screenshot("approval-light")
        content { PocketTheme("dark") { Surface { ApprovalCard(request.copy(submitted = true), true) { _, _ -> } } } }
        compose.onNodeWithText("允许一次").assertIsNotEnabled()
    }
    @Test fun attentionAndDevicesPreviewsRender() {
        content { AttentionPreview() }; screenshot("attention-light")
        content { DevicesPreview() }; screenshot("devices-dark")
    }
    @Test fun externalImagesRequireGestureAndDoNotBeginNetworkLoading() {
        content { PocketTheme("light") { Surface { RemoteTranscriptImage("https://example.invalid/a.png") } } }
        compose.onNodeWithText("点击从 example.invalid 加载图片").assertExists()
        compose.onNodeWithText("正在加载图片").assertDoesNotExist()
    }
    @Test fun darkThemeProvidesReadableContentColorForTranslucentSurfaces() {
        var foreground = androidx.compose.ui.graphics.Color.Unspecified
        content { PocketTheme("dark") { foreground = LocalContentColor.current; PocketBackdrop { Text("Dark content") } } }
        compose.runOnIdle {
            assertTrue(foreground.red > .8f && foreground.green > .8f && foreground.blue > .8f)
        }
    }
    @Test fun composerSheetExplainsUnavailableReferenceAndRetainsDraft() {
        var attached = 0
        var modelOpened = 0
        content { PocketTheme("light") { Surface {
            var draft by remember { mutableStateOf("保留这段草稿") }
            PocketComposer(draft, "Codex", "高", false, ComposerAvailability(true, false, true, false), { draft = it },
                { modelOpened++ }, { attached++ }, {}, {}, {}, {}, false)
        } } }
        compose.onNodeWithContentDescription("添加引用").performClick()
        // Compact popover: no title bar; the unavailable reason is a hint on the row itself.
        compose.onNodeWithTag("pocket-modal").assertIsDisplayed()
        compose.onNodeWithText("连接支持引用的 Codex 后可用").assertExists()
        compose.onNodeWithText("添加引用").assertIsNotEnabled()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-model").performClick()
        assertEquals(1, modelOpened); assertEquals(0, attached)
        compose.onNodeWithTag("composer-input").assertTextEquals("保留这段草稿")
    }
    @Test fun conversationMenuKeepsUnavailableActionsDiscoverableWithoutFakeButtons() {
        var pinned = false
        var forked = false
        val state = PocketPreviewStates.home.copy(selected = PocketPreviewStates.chats.first())
        content { PocketTheme("dark") {
            Surface { ConversationActionsList(state, {}, null, {}, { forked = true }, {}, {}, {}, {}, {}, {}, { pinned = true }, {}) }
        } }
        compose.onNodeWithText("分支聊天").assertDoesNotExist()
        compose.onNodeWithText("暂不可用", substring = true).performClick()
        compose.onNodeWithText("分支聊天").assertIsNotEnabled()
        compose.onAllNodesWithText("当前 Codex 版本未提供此能力").fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
        compose.onNodeWithText("取消置顶").performClick()
        assertTrue(pinned); assertFalse(forked)
    }
    @Test fun readingModeRestoresControlsWhenPreviewIsTapped() {
        content { PocketTheme("light") { Surface {
            var draft by remember { mutableStateOf("") }
            PocketComposer(draft, "Codex", "高", false, ComposerAvailability(true, false, true, false), { draft = it }, {}, {}, {}, {}, {}, {}, true, readingHistory = true)
        } } }
        // Reading uses a readonly preview; tapping it restores the multiline editor and model capsule.
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.onNodeWithTag("composer-input").assertDoesNotExist()
        compose.onNodeWithText("中文草稿").assertDoesNotExist()
        compose.onNodeWithTag("composer-history-preview")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText)).performClick()
        compose.onNodeWithTag("composer-input").assertIsFocused().performTextInput("中文草稿")
        compose.onNodeWithTag("composer-model").assertExists()
        compose.onNodeWithTag("composer-history-preview").assertDoesNotExist()
        compose.onNodeWithTag("composer-input").assertTextEquals("中文草稿")
    }
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun dialogActionsStayReachableWithLargeChineseText() {
        content { PocketTheme("light") {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                PocketDialog({}, { Text("确认操作") }, { Text("这是窄屏与大字体下的确认说明。") },
                    { PocketButton("确认并清除本机缓存", {}) }, { PocketButton("返回继续检查", {}, secondary = true) })
            }
        } }
        val confirm = compose.onNodeWithText("确认并清除本机缓存").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText("返回继续检查").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertFalse("Action bounds must not overlap at larger font sizes", confirm.overlaps(cancel))
    }
    @Test fun queueShowsInlineRowsAboveComposerWithoutSeparateScreen() {
        fun entry(id: String, text: String) = buildJsonObject {
            put("id", id)
            put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
        }
        val base = PocketPreviewStates.home
        val runtime = base.runtimes.first().copy(capabilities = setOf("turn/steer", "thread/queue/delete", "thread/queue/update", "thread/queue/reorder"))
        val state = base.copy(runtimes = listOf(runtime), selected = PocketPreviewStates.chats.first(), timeline = NativeTimeline(activeTurn = "turn-1"), nativeQueue = listOf(
            entry("queue-one", "稍后执行的消息"), entry("queue-two", "第二条"), entry("queue-three", "第三条"), entry("queue-four", "第四条")))
        var deleted: String? = null
        var steered: String? = null
        content { PocketTheme("light") { Surface { Column {
            NativeQueue(state, { _, _ -> }, { deleted = it }, { _, _ -> }, onSteer = { steered = it })
            PocketComposer("草稿", "Codex", "高", true, ComposerAvailability(true, true, true, true), {}, {}, {}, {}, {}, {}, {}, true)
        } } } }
        // The text is visible inline, with no sheet or screen in between.
        compose.onNodeWithText("稍后执行的消息").assertIsDisplayed()
        compose.onNodeWithTag("pocket-modal").assertDoesNotExist()
        val queue = compose.onNodeWithTag("native-queue").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot
        assertTrue("Queue rows sit above the input", queue.bottom <= composer.top + 1f)
        // Four entries fold after two rows.
        compose.onNodeWithText("第三条").assertDoesNotExist()
        compose.onNodeWithText("还有 2 条").performClick()
        compose.onNodeWithText("第四条").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(compose.activity.getString(dev.codexpocket.app.R.string.v02_queue_delete))[0].performClick()
        assertEquals("queue-one", deleted)
        compose.onAllNodesWithText("引导")[1].performClick()
        assertEquals("queue-two", steered)
        compose.onNodeWithTag("composer-input").assertTextEquals("草稿")
        screenshot("queue-inline")
    }
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun floatingHomeDockLeavesLastConversationReachableAtLargeText() {
        val sample = PocketPreviewStates.chats.first()
        val chats = (1..24).map { index -> sample.copy(key = ThreadKey("preview-pc", "preview-store", "scroll-$index"), title = "会话 $index", updatedAt = (25 - index).toLong(), status = "completed", pinned = false) }
        val state = PocketPreviewStates.home.copy(chats = chats)
        var opened = false
        var created = false
        content { PocketTheme("dark") {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                PocketHomeFrame(state, HomeTab.Chats, 0, false, false, {}, { created = true }, {}, {}) {
                    ChatsHome(state, { opened = true }, {}, { _, _, _ -> }, {}, {})
                }
            }
        } }
        val lastTag = "home-chat-${chats.last().key.encoded}"
        compose.onNodeWithTag("chats-home").performScrollToNode(hasTestTag(lastTag))
        val last = compose.onNodeWithTag(lastTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("home-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue("End padding must keep the final row above the floating dock", last.bottom <= dock.top)
        compose.onNodeWithTag(lastTag).performClick()
        compose.onNodeWithTag("home-new-chat").performClick()
        assertTrue(opened); assertTrue(created)
        screenshot("home-narrow-large-text")
    }
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun longDraftKeepsSendAndStopOutsideScrollingEditor() {
        content { PocketTheme("light") { Surface { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            PocketComposer((1..35).joinToString("\n") { "第 $it 行中文草稿" }, "Codex", "高", true,
                ComposerAvailability(true, true, true, true), {}, {}, {}, {}, {}, {}, {}, true)
        } } } }
        val editor = compose.onNodeWithTag("composer-input").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("composer-actions").fetchSemanticsNode().boundsInRoot
        assertTrue("Editor must stay above the independent action row", editor.bottom <= action.top)
        compose.onNodeWithTag("composer-send").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(dev.codexpocket.app.R.string.stop_turn)).assertIsDisplayed()
        screenshot("composer-long-draft")
    }
}
