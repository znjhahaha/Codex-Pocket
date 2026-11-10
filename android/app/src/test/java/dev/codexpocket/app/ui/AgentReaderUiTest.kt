package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Local state/callback fixture: never selects a repository thread or submits a native command. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentReaderUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val parent = ThreadKey("pc", "store", "main-native-id")
    private val child = ChatEntity(ThreadKey("pc", "store", "child-native-id"), "runtime", "desktop",
        "Long child title", "", "D:\\project", 0, "idle", false)
    private val current = AgentRosterEntry(child.key.nativeId, "Reviewer", ActivityStatus.Running,
        "running", taskPreview = "Read the existing changes", isCurrent = true)
    private val history = AgentRosterEntry("old-child", "History", ActivityStatus.Completed,
        "completed", resultPreview = "Older result")
    private var density = 1f

    private fun content(fontScale: Float = 1f, block: @Composable () -> Unit) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                PocketTheme("light") {
                    val actual = LocalDensity.current
                    SideEffect { density = actual.density }
                    CompositionLocalProvider(LocalDensity provides Density(actual.density, fontScale), LocalPocketGlass provides false) {
                        // Match PocketRoot: lazy transcript rows in the sibling overlay need the real renderer too.
                        ProvideMarkdownRendering {
                            PocketOverlayHost {
                                Text("Main task draft remains", Modifier.testTag("main-fixture"))
                                block()
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun answer(index: Int, text: String = "Existing answer $index") = TimelineItem(
        "turn-$index", "answer-$index", "agentMessage", text,
        raw = buildJsonObject { put("phase", "final_answer") }, complete = true)

    /** Dispatcher.Default projection readiness is separate from Compose being idle. */
    private fun waitForReaderRow(key: String, displayed: Boolean = false) {
        val tag = "agent-reader-row:$key"
        compose.waitUntil(10_000) {
            if (displayed) compose.onNodeWithTag(tag).isDisplayed()
            else compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
        }
    }

    @Test fun delegatedChipShowsReaderAndRefreshesWithoutAnchoredRoster() {
        var shows = 0
        var refreshes = 0
        var opens = 0
        content {
            AgentRosterChip(listOf(current), true, false, { opens++ },
                onRefresh = { refreshes++ }, onShow = { shows++ })
        }
        compose.onNodeWithTag("agent-roster-chip").performClick()
        compose.runOnIdle { assertEquals(1, shows); assertEquals(1, refreshes); assertEquals(0, opens) }
        compose.onNodeWithTag("agent-roster-panel").assertDoesNotExist()
    }

    @Test fun centeredRosterKeepsCurrentFirstAndHistoryCollapsedEvenOffline() {
        var opened = ""
        content(1.6f) {
            AgentReaderPanel(AgentReaderState(parent), listOf(history, current),
                mapOf(child.key.nativeId to "Reviewer"), online = false,
                onOpen = { opened = it }, onList = {}, onClose = {}, onFullscreen = {}, onOlder = {}, onRetry = {})
        }
        compose.onNodeWithText("本轮子代理").assertIsDisplayed()
        compose.onNodeWithTag("agent-roster-open:${child.key.nativeId}").assertIsEnabled()
        compose.onNodeWithTag("agent-roster-open:old-child").assertDoesNotExist()
        compose.onNodeWithTag("agent-reader-history-toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
        val panel = compose.onNodeWithTag("pocket-modal-surface").fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithTag("pocket-overlay-host").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.width <= minOf(host.width - 32f * density, 720f * density) + 1f)
        assertTrue(panel.height <= host.height * .78f + 1f)
        assertEquals(host.center.x, panel.center.x, 1f)
        assertEquals(host.center.y, panel.center.y, 40f * density)
        val toolbar = compose.onNodeWithTag("agent-reader-toolbar").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("agent-reader-history-toggle").performClick()
        compose.onNodeWithTag("agent-roster-open:old-child").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("old-child", opened) }
        val toolbarAfter = compose.onNodeWithTag("agent-reader-toolbar").fetchSemanticsNode().boundsInRoot
        assertEquals(toolbar.top, toolbarAfter.top, 1f)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun systemBackMovesFullscreenToPreviewToRosterThenCloses() {
        var state by mutableStateOf<AgentReaderState?>(AgentReaderState(parent, child = child,
            timeline = NativeTimeline(items = linkedMapOf(answer(0).key to answer(0))), fullScreen = true))
        var lists = 0
        var closes = 0
        val fullscreen = mutableListOf<Boolean>()
        content {
            state?.let { value -> AgentReaderPanel(value, listOf(current), emptyMap(), true,
                {}, { lists++; state = value.copy(child = null, timeline = NativeTimeline()) },
                { closes++; state = null }, { fullscreen += it; state = value.copy(fullScreen = it) }, {}, {}) }
        }
        compose.onNodeWithTag("agent-reader-return-main").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(listOf(false), fullscreen); assertEquals(child.key, state!!.child!!.key) }
        compose.onNodeWithTag("agent-reader-return-main").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回子代理列表").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("agent-reader-roster").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, lists); assertEquals(parent, state!!.parent) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("agent-reader-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closes); assertNull(state) }
        compose.onNodeWithTag("main-fixture").assertIsDisplayed()
    }

    @Test fun resizingAndRosterRoundTripPreserveChildReadingPosition() {
        val items = (0..40).map { answer(it, "Existing answer $it\n" + "中文阅读内容 ".repeat(18)) }
        val timeline = NativeTimeline(items = items.associateBy { it.key })
        var state by mutableStateOf(AgentReaderState(parent, child = child, timeline = timeline))
        content {
            AgentReaderPanel(state, listOf(current), emptyMap(), true,
                { state = state.copy(child = child, timeline = timeline) },
                { state = state.copy(child = null, timeline = NativeTimeline()) }, {},
                { state = state.copy(fullScreen = it) }, {}, {})
        }
        val list = compose.onNodeWithTag("agent-reader-transcript")
        val host = compose.onNodeWithTag("pocket-overlay-host").fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithTag("pocket-modal-surface").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("agent-reader-toolbar").fetchSemanticsNode().boundsInRoot
        val transcript = list.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(preview.width <= minOf(host.width - 32f * density, 720f * density) + 1f)
        assertTrue(preview.height <= host.height * .78f + 1f)
        assertEquals(host.center.x, preview.center.x, 1f)
        assertEquals(host.center.y, preview.center.y, 40f * density)
        assertTrue(transcript.height > 0f)
        assertTrue(transcript.top >= toolbar.bottom)
        assertTrue(transcript.bottom <= preview.bottom + 1f)
        list.performTouchInput { swipeDown() }
        list.performScrollToIndex(12)
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithContentDescription("放大子代理阅读").performClick()
        compose.onNodeWithTag("agent-reader-return-main").assertIsDisplayed()
        val fullscreen = compose.onNodeWithTag("pocket-modal-surface").fetchSemanticsNode().boundsInRoot
        assertEquals(host.width, fullscreen.width, 1f)
        assertTrue(fullscreen.height > preview.height)
        compose.onNodeWithContentDescription("缩小子代理阅读").performClick()
        val minimized = compose.onNodeWithTag("pocket-modal-surface").fetchSemanticsNode().boundsInRoot
        assertEquals(preview.width, minimized.width, 1f)
        assertEquals(preview.height, minimized.height, 1f)
        val after = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, after, 1f)
        compose.onNodeWithContentDescription("返回子代理列表").performClick()
        compose.onNodeWithTag("agent-roster-open:${child.key.nativeId}").performClick()
        val returned = compose.onNodeWithTag("agent-reader-transcript").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, returned, 1f)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription("旁支讨论").assertDoesNotExist()
    }

    @Test fun cachedErrorKeepsTranscriptAndRetryCannotSendWhileOffline() {
        val original = answer(0, "Preserved cached answer")
        var state by mutableStateOf(AgentReaderState(parent, child = child,
            timeline = NativeTimeline(items = linkedMapOf(original.key to original)),
            error = "历史读取失败", updatedAt = 1_700_000_000_000L))
        var online by mutableStateOf(false)
        var retries = 0
        var older = 0
        content {
            AgentReaderPanel(state, listOf(current), emptyMap(), online, {}, {}, {}, {}, { older++ }, { retries++ })
        }
        compose.onNodeWithTag("agent-reader-row:${original.key}").assertIsDisplayed()
        compose.onNodeWithText("离线 · 缓存内容").assertIsDisplayed()
        compose.onNodeWithTag("agent-reader-retry").assertIsNotEnabled()
        compose.runOnIdle { online = true; state = state.copy(nextTurns = JsonPrimitive("cursor")) }
        compose.onNodeWithTag("agent-reader-retry").performClick()
        compose.onNodeWithTag("agent-reader-transcript").performScrollToIndex(0)
        compose.onNodeWithTag("agent-reader-older").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, older); assertEquals(parent, state.parent) }
    }

    @Test fun liveSnapshotsReplaceChildRowsWithoutAddingMutationControls() {
        val first = answer(0, "First cached answer")
        val second = answer(1, "Latest native answer")
        var state by mutableStateOf(AgentReaderState(parent, child = child,
            timeline = NativeTimeline(items = linkedMapOf(first.key to first)), updatedAt = 1))
        content { AgentReaderPanel(state, listOf(current), emptyMap(), true, {}, {}, {}, {}, {}, {}) }
        waitForReaderRow(first.key, displayed = true)
        compose.onNodeWithTag("agent-reader-row:${first.key}").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(timeline = state.timeline.copy(items =
            linkedMapOf(first.key to first, second.key to second)), updatedAt = 2) }
        waitForReaderRow(second.key, displayed = true)
        compose.onNodeWithTag("agent-reader-row:${second.key}").assertIsDisplayed()
        compose.onNodeWithTag("pocket-composer").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription("停止").assertDoesNotExist()
        compose.onNodeWithContentDescription("旁支讨论").assertDoesNotExist()
    }

    @Test fun acceptedNativeTurnThinksBeforeContentAndStopsOnDisconnectOrIdle() {
        val started = NativeReducer.reduce(NativeTimeline(), buildJsonObject {
            put("method", "turn/started")
            put("params", buildJsonObject { put("turn", buildJsonObject { put("id", "native-turn") }) })
        })
        var state by mutableStateOf(AgentReaderState(parent, child = child, timeline = started))
        var online by mutableStateOf(true)
        content { AgentReaderPanel(state, listOf(current), emptyMap(), online, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("live-thinking-placeholder").assertTextEquals("正在思考")
        compose.onNodeWithTag("agent-reader-empty").assertDoesNotExist()
        compose.runOnIdle { online = false }
        compose.onNodeWithTag("live-thinking-placeholder").assertDoesNotExist()
        compose.runOnIdle { online = true; state = state.copy(timeline = started.copy(status = "idle")) }
        compose.onNodeWithTag("live-thinking-placeholder").assertDoesNotExist()
        compose.onNodeWithText("实时更新").assertDoesNotExist()
    }

    @Test fun acceptedChildActiveStatusThinksBeforeTurnIdentityButUnversionedDoesNot() {
        var state by mutableStateOf(AgentReaderState(parent, child = child))
        content { AgentReaderPanel(state, listOf(current), emptyMap(), true, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("live-thinking-placeholder").assertDoesNotExist()
        compose.runOnIdle { state = state.withLiveStatus(child.key.nativeId, AgentLiveStatus("active")) }
        compose.onNodeWithTag("live-thinking-placeholder").assertDoesNotExist()
        compose.runOnIdle { state = state.withLiveStatus(child.key.nativeId, AgentLiveStatus("active", cursor = Cursor("epoch", 10))) }
        compose.onNodeWithTag("live-thinking-placeholder").assertTextEquals("正在思考")
        compose.onNodeWithTag("agent-reader-empty").assertDoesNotExist()
        compose.runOnIdle { state = state.withLiveStatus(child.key.nativeId, AgentLiveStatus("active", cursor = Cursor("epoch", 11),
            activeFlags = setOf("waitingOnUserInput"))) }
        compose.onNodeWithTag("live-thinking-placeholder").assertDoesNotExist()
    }

    @Test fun completedTaskExpandsInPlaceAndRetainsChoiceWhenResized() {
        val question = TimelineItem("done", "user", "userMessage", "Original task", complete = true)
        val commentary = TimelineItem("done", "comment", "agentMessage", "Intermediate explanation",
            raw = buildJsonObject { put("phase", "commentary") }, complete = true)
        val final = TimelineItem("done", "final", "agentMessage", "Final answer",
            raw = buildJsonObject { put("phase", "final_answer") }, complete = true)
        val timeline = NativeTimeline(items = listOf(question, commentary, final).associateBy { it.key },
            completedTurns = setOf("done"), turns = mapOf("done" to NativeTurnInfo("done", status = "completed")))
        var state by mutableStateOf(AgentReaderState(parent, child = child, timeline = timeline))
        content { AgentReaderPanel(state, listOf(current), emptyMap(), true, {}, {}, {},
            { state = state.copy(fullScreen = it) }, {}, {}) }
        waitForReaderRow("process:done")
        compose.onNodeWithTag("agent-reader-row:${commentary.key}").assertDoesNotExist()
        compose.onNodeWithText("上 1 条消息").performScrollTo().performClick()
        waitForReaderRow(commentary.key)
        compose.onNodeWithTag("agent-reader-row:${commentary.key}").assertExists()
        compose.onNodeWithContentDescription("放大子代理阅读").performClick()
        compose.onNodeWithContentDescription("缩小子代理阅读").performClick()
        waitForReaderRow(commentary.key)
        compose.onNodeWithTag("agent-reader-row:${commentary.key}").assertExists()
    }

    @Test fun fullToolDetailInsideExpandedTaskHasOneNativeItemAndNoSourceAction() {
        val question = TimelineItem("done", "user", "userMessage", "Inspect existing output", complete = true)
        val tool = TimelineItem.from("done", buildJsonObject {
            put("id", "command")
            put("type", "commandExecution")
            put("status", "completed")
            put("command", "echo existing")
            put("cwd", "D:\\project")
            put("exitCode", 0)
            put("aggregatedOutput", "Existing output\n中文原文")
        })
        val final = TimelineItem("done", "final", "agentMessage", "Finished",
            raw = buildJsonObject { put("phase", "final_answer") }, complete = true)
        val timeline = NativeTimeline(items = listOf(question, tool, final).associateBy { it.key },
            completedTurns = setOf("done"), turns = mapOf("done" to NativeTurnInfo("done", status = "completed")))
        content { AgentReaderPanel(AgentReaderState(parent, child = child, timeline = timeline),
            listOf(current), emptyMap(), true, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("上 1 条消息").performScrollTo().performClick()
        compose.onNodeWithTag("activity-toggle:${tool.key}").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.full_details)).performScrollTo().performClick()
        compose.onNodeWithTag("agent-reader-detail:${tool.key}").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("agent-reader-detail:${tool.key}").assertCountEquals(1)
        compose.onNodeWithText("退出码 0").assertExists()
        compose.onNodeWithContentDescription("复制工具详情").assertIsEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.v02_open_source)).assertDoesNotExist()
        compose.onNodeWithContentDescription("收起工具详情").performClick()
        compose.onNodeWithTag("agent-reader-detail:${tool.key}").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun expandedLastToolDoesNotPullReaderBackAfterDragResizeAndAppend() {
        val tool = TimelineItem("tool", "last", "commandExecution", "echo\n\n" + "Recorded output\n".repeat(30),
            status = "completed", raw = buildJsonObject { put("command", "echo"); put("aggregatedOutput", "Recorded output\n".repeat(30)) }, complete = true)
        val items = (0 until 24).map { answer(it, "Recorded answer $it\n".repeat(5)) } + tool
        var state by mutableStateOf(AgentReaderState(parent, child = child,
            timeline = NativeTimeline(items.associateBy { it.key }, completedTurns = items.mapTo(hashSetOf()) { it.turnId })))
        content { AgentReaderPanel(state, listOf(current), emptyMap(), true, {}, {}, {},
            { state = state.copy(fullScreen = it) }, {}, {}) }
        val transcript = compose.onNodeWithTag("agent-reader-transcript")
        compose.waitUntil(5_000) { transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 20 }
        transcript.performScrollToNode(hasTestTag("activity-toggle:${tool.key}"))
        compose.onNodeWithTag("activity-toggle:${tool.key}").performClick()
        compose.waitForIdle()
        val before = transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        transcript.performTouchInput { down(center); moveBy(Offset(0f, 90f)) }
        compose.runOnIdle { state = state.copy(fullScreen = true) }
        transcript.performTouchInput { moveBy(Offset(0f, 160f)); up() }
        compose.waitForIdle()
        assertTrue(transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() < before)
        transcript.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "阅读历史"))
        val appended = answer(100, "Later recorded response\n".repeat(8))
        compose.runOnIdle { state = state.copy(timeline = state.timeline.copy(items = state.timeline.items + (appended.key to appended))) }
        compose.waitUntil(5_000) { runCatching {
            transcript.performScrollToNode(hasTestTag("agent-reader-row:${appended.key}")); true
        }.getOrDefault(false) }
        compose.waitForIdle()
        transcript.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "阅读历史"))
    }

    @Test fun childFileUsesSharedInlineDiffAndCannotReachParentDraftCallback() {
        val file = TimelineItem.from("file-turn", buildJsonObject {
            put("id", "file")
            put("type", "fileChange")
            put("status", "completed")
            put("changes", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("path", "src/Child.kt"); put("kind", "update"); put("diff", "@@ -42 +42 @@\n-old\n+new") })
            })
        })
        var drafts = 0
        content {
            CompositionLocalProvider(LocalInlineDiffComment provides { drafts++ }) {
                AgentReaderPanel(AgentReaderState(parent, child = child,
                    timeline = NativeTimeline(items = mapOf(file.key to file))), listOf(current), emptyMap(),
                    true, {}, {}, {}, {}, {}, {})
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("activity-file:file:0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("activity-file:file:0").performClick()
        compose.onNodeWithTag("inline-diff:file:0").assertExists()
        compose.onNodeWithTag("agent-reader-detail:${file.key}").assertDoesNotExist()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("diff-gutter:1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("diff-gutter:1", useUnmergedTree = true).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, drafts) }
    }
}
