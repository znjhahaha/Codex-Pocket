package dev.codexpocket.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Real trace rows, including their native result disclosure. No tool is executed by these fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodexAppActivityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var serial = 0
    private lateinit var view: android.view.View
    private var density = 1f

    private fun call(tool: String = "list_threads", status: String = "completed", type: String = "mcpToolCall",
        namespace: String? = "codex_app", extra: JsonObjectBuilder.() -> Unit = {}): TimelineItem =
        TimelineItem.from("turn", buildJsonObject {
            put("id", "tool-${serial++}"); put("type", type); put("status", status)
            put(if (type == "functionCallOutput") "name" else "tool", tool)
            namespace?.let { put(if (type == "mcpToolCall") "server" else "namespace", it) }
            extra()
        }, complete = status in setOf("completed", "failed", "cancelled", "canceled", "interrupted"))

    private fun content(block: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current; density = LocalDensity.current.density
            CompositionLocalProvider(LocalPocketGlass provides false,
                LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false)) {
                PSurface(Modifier.fillMaxSize()) { ProvideMarkdownRendering { Column { block() } } }
            }
        } } }
        compose.waitForIdle()
    }

    @Composable private fun rows(items: List<TimelineItem>, completedTurn: Boolean = false, onDetail: (TimelineItem) -> Unit = {}) {
        items.forEach { item -> key(item.key) {
            NativeActivityTrace(TranscriptRow("trace:${item.key}", TranscriptKind.Activity, listOf(item), completedTurn),
                "codex-app-test", false, {}, onDetail)
        } }
    }

    private fun advance() { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }

    private fun inRow(item: TimelineItem, matcher: SemanticsMatcher) =
        matcher and hasAnyAncestor(hasTestTag("native-activity:${item.key}"))

    // Wait for async projection, accepting either the old generic text or the intended label.
    // The assertion after this wait, rather than a timeout, establishes the missing behavior.
    private fun awaitProjection(item: TimelineItem, expected: String) {
        val oldTitle = NativeActivityPresentation.describe(item).title
        compose.waitUntil(10_000) {
            advance()
            compose.onAllNodes(inRow(item, hasText(expected, substring = true) or hasText(oldTitle, substring = true)),
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertLabel(item: TimelineItem, label: String) {
        awaitProjection(item, label)
        compose.onNode(inRow(item, hasText(label)), useUnmergedTree = true).assertExists()
    }

    private fun screenshot(name: String) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File("build/reports/codex-app/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun completedListThreadsUsesChineseActionAndKeepsOriginalDetails() {
        val item = call {
            put("arguments", buildJsonObject { put("query", "PRIVATE_ARGUMENT_FIXTURE") })
            put("result", buildJsonObject { put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "找到两条聊天记录") })
            }) })
        }
        var opened: TimelineItem? = null
        content { rows(listOf(item), onDetail = { opened = it }) }
        assertLabel(item, "已列出聊天")
        compose.onNodeWithText("list_threads", substring = true).assertDoesNotExist()
        compose.onNodeWithText("PRIVATE_ARGUMENT_FIXTURE", substring = true).assertDoesNotExist()
        compose.onNodeWithText("找到两条聊天记录").assertDoesNotExist()
        screenshot("list-threads-collapsed")
        compose.onNodeWithTag("activity-toggle:${item.key}").performClick()
        // Mount the detail subtree, then finish its 220 ms expansion with the clock frozen.
        repeat(20) { advance() }
        compose.onNodeWithText("找到两条聊天记录").assertIsDisplayed()
        compose.onNodeWithText("PRIVATE_ARGUMENT_FIXTURE", substring = true).assertDoesNotExist()
        screenshot("list-threads-expanded")
        compose.onNodeWithText(compose.activity.getString(R.string.full_details)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertSame("Details retain the native item and its identity", item, opened) }
    }

    @Test fun lifecycleLabelsDoNotTurnFailureCancellationOrUnknownIntoSuccess() {
        val cases = listOf(
            "inProgress" to "正在列出聊天", "completed" to "已列出聊天", "failed" to "列出聊天失败",
            "cancelled" to "列出聊天 · 已取消", "interrupted" to "列出聊天 · 已中断",
            "waiting" to "等待列出聊天", "futureState" to "列出聊天")
        val items = cases.map { call(status = it.first) }
        content { rows(items) }
        items.zip(cases).forEach { (item, expected) -> assertLabel(item, expected.second) }
        screenshot("lifecycle-states")
    }

    @Test fun explicitToolErrorsOverrideACompletedEnvelope() {
        val mcp = call { put("result", buildJsonObject { put("isError", true) }) }
        val dynamic = call(type = "dynamicToolCall") { put("success", false) }
        content { rows(listOf(mcp, dynamic)) }
        listOf(mcp, dynamic).forEach {
            assertEquals(ActivityStatus.Failed, NativeActivityPresentation.describe(it).status)
            assertLabel(it, "列出聊天失败")
        }
        compose.onNodeWithText("已列出聊天").assertDoesNotExist()
    }

    @Test fun canonicalMcpDynamicAndQualifiedNamesResolveToTheSameAction() {
        val items = listOf(
            call(), call(type = "dynamicToolCall"),
            call("codex_app.list_threads", type = "dynamicToolCall", namespace = null),
            call("mcp__codex_app__list_threads", type = "dynamicToolCall", namespace = "functions"),
            call("codex_app.list_threads", type = "functionCallOutput", namespace = null) { put("output", "完成") },
            call { put("appContext", buildJsonObject { put("appName", "Codex"); put("actionName", "List chats") }) })
        content { rows(items) }
        items.forEach { assertLabel(it, "已列出聊天") }
    }

    @Test fun supportedChatProjectAndTerminalActionsUseTheirOwnVerbs() {
        val cases = listOf(
            "read_thread" to "已读取聊天", "list_archived_threads" to "已列出已归档聊天",
            "list_projects" to "已列出项目", "list_attachments" to "已列出附件",
            "read_thread_terminal" to "已读取终端", "set_thread_title" to "已重命名聊天",
            "check_app_update" to "已检查应用更新")
        val items = cases.map { call(it.first) }
        content { rows(items) }
        items.zip(cases).forEach { (item, expected) -> assertLabel(item, expected.second) }
        screenshot("supported-actions")
    }

    @Test fun inverseArchiveAndPinActionsAreNotDescribedAsTheirOpposites() {
        val archive = call("set_thread_archived") { put("arguments", buildJsonObject { put("archived", true) }) }
        val restore = call("set_thread_archived") { put("arguments", buildJsonObject { put("archived", false) }) }
        val pin = call("set_thread_pinned") { put("arguments", buildJsonObject { put("pinned", true) }) }
        val unpin = call("set_thread_pinned") { put("arguments", buildJsonObject { put("pinned", false) }) }
        content { rows(listOf(archive, restore, pin, unpin)) }
        assertLabel(archive, "已归档聊天"); assertLabel(restore, "已恢复聊天")
        assertLabel(pin, "已置顶聊天"); assertLabel(unpin, "已取消置顶聊天")
    }

    @Test fun finishedTurnDoesNotMakeAnUnresolvedToolLookCompleted() {
        val item = call(status = "inProgress")
        content { rows(listOf(item), completedTurn = true) }
        assertEquals(ActivityStatus.Unknown, NativeActivityPresentation.describe(item, completedTurn = true).status)
        assertLabel(item, "列出聊天")
        compose.onNodeWithText("已列出聊天").assertDoesNotExist()
    }

    @Test fun unknownAndOtherServersKeepGenericRowsWithoutMetadataSpoofing() {
        val unknown = call("future_action")
        val other = call("list_threads", namespace = "other_app")
        val spoofed = call("unrelated", namespace = "other_app") {
            put("appContext", buildJsonObject { put("appName", "Codex"); put("actionName", "list_threads") })
        }
        content { rows(listOf(unknown, other, spoofed)) }
        assertLabel(unknown, "codex_app · future_action")
        assertLabel(other, "other_app · list_threads")
        assertLabel(spoofed, "Codex · list_threads")
        compose.onNodeWithText("已列出聊天").assertDoesNotExist()
    }

    @Test fun compoundDynamicCallsStayGenericAndKeepSeparateNativeIdentities() {
        val script = call("exec", type = "dynamicToolCall", namespace = "functions") {
            put("arguments", buildJsonObject { put("code", "await tools.mcp__codex_app__list_threads({})") })
        }
        val batch = call("parallel", type = "dynamicToolCall", namespace = "multi_tool_use") {
            put("arguments", buildJsonObject { put("tool_uses", buildJsonArray {
                add(buildJsonObject { put("recipient_name", "codex_app.list_threads") })
            }) })
        }
        val native = listOf(script, batch)
        val projected = ConversationPresentation.rows(NativeTimeline(native.associateBy { it.key }), foldTasks = false)
        assertEquals(native.map { it.key }, projected.flatMap { it.items }.map { it.key })
        assertEquals(listOf("dynamicToolCall", "dynamicToolCall"), projected.flatMap { it.items }.map { it.type })
        content { rows(native) }
        assertLabel(script, "functions · exec"); assertLabel(batch, "multi_tool_use · parallel")
        compose.onNodeWithText("已列出聊天").assertDoesNotExist()
        compose.onNodeWithText("MCP", substring = true).assertDoesNotExist()
    }

    @Test fun listThreadsDrawsTheExistingChatGlyphRatherThanTheGenericWrench() {
        val item = call()
        content {
            rows(listOf(item))
            Box(Modifier.size(16.dp).testTag("expected-chat-glyph")) {
                NativeActivityIcon(PocketIcons.ChatBubbleOutline, false, Pocket.colors.inkMuted.copy(alpha = .85f))
            }
        }
        awaitProjection(item, "已列出聊天")
        val line = compose.onNodeWithTag("activity-toggle:${item.key}").fetchSemanticsNode().boundsInRoot
        val expectedBounds = compose.onNodeWithTag("expected-chat-glyph").fetchSemanticsNode().boundsInRoot
        val actualBounds = Rect(line.left + 4 * density, line.center.y - 8 * density,
            line.left + 20 * density, line.center.y + 8 * density)
        val bitmap = compose.runOnIdle {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        fun pixels(bounds: Rect): IntArray {
            val w = bounds.width.roundToInt(); val h = bounds.height.roundToInt()
            return IntArray(w * h).also { bitmap.getPixels(it, 0, w, bounds.left.roundToInt(), bounds.top.roundToInt(), w, h) }
        }
        try {
            assertNotNull("Reuse the existing chat motion", ToolGlyphs.working(PocketIcons.ChatBubbleOutline))
            assertArrayEquals("The row must select the existing chat glyph", pixels(expectedBounds), pixels(actualBounds))
        } finally { bitmap.recycle() }
    }
}
