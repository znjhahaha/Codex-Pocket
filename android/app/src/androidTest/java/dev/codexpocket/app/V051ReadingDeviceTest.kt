package dev.codexpocket.app

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Run with -e v051Reading true -e class dev.codexpocket.app.V051ReadingDeviceTest.
 * Local native snapshots and callbacks only: no repository selection, shell execution or inference.
 * PixelCopy captures the Activity window, including on a secondary device/emulator display.
 */
@RunWith(AndroidJUnit4::class)
class V051ReadingDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    // Gate before the Compose rule launches an Activity, including in the default test suite.
    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Opt in with v051Reading=true",
                    InstrumentationRegistry.getArguments().getString("v051Reading") == "true")
                base.evaluate()
            }
        }
    }).around(compose)

    private val screenshots = mutableListOf<JsonObject>()
    private val verifiedThemes = mutableListOf<String>()
    private lateinit var proofDirectory: File
    private var startedAt = 0L
    private var density = 1f
    private var stage = "setup"

    private class Fixture {
        val parent = ThreadKey("reading-pc", "reading-store", "reading-parent")
        val childId = "reading-child-hooke"
        val names = mapOf(childId to "Hooke", "reading-child-dewey" to "Dewey", "reading-history" to "Gauss")
        val commandOutput = "NativeActivityRows.kt:61: NativeActivityTrace\n" +
            "NativeActivityRows.kt:386: NativeAgentWorkingStrip\nLiveTurnStatus.kt:110: TaskElapsedLabel"
        val summaryFirst = "先核对工具与子代理的阅读层级，保留原生摘要的段落边界。"
        val summaryLatest = "工具记录已归并；继续核对子代理只读面板与返回路径。"
        val createPrompt = "核对摘要、工具记录和子代理面板的阅读层级。"
        val sendPrompt = "补充检查放大、缩小与返回列表后的阅读位置。"
        private fun item(turn: String, raw: String, complete: Boolean = true) =
            TimelineItem.from(turn, pocketJson.parseToJsonElement(raw).jsonObject, complete)

        val command = item("reading-turn", """{"id":"read-ui","type":"commandExecution","status":"completed","command":"rg -n 'NativeActivityTrace|NativeAgentWorkingStrip|TaskElapsedLabel' android/app/src/main/java/dev/codexpocket/app/ui","cwd":"D:/codex2","exitCode":0,"durationMs":184,"aggregatedOutput":${JsonPrimitive(commandOutput)}}""")
        val created = item("reading-turn", """{"id":"create-agent","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","prompt":${JsonPrimitive(createPrompt)},"receiverThreadIds":["reading-child-hooke"],"agentsStates":{"reading-child-hooke":{"status":"running","agentNickname":"Hooke"}}}""")
        val sent = item("reading-turn", """{"id":"send-agent","type":"collabAgentToolCall","tool":"sendInput","status":"completed","prompt":${JsonPrimitive(sendPrompt)},"receiverThreadIds":["reading-child-dewey"],"agentsStates":{"reading-child-dewey":{"status":"running","agentNickname":"Dewey"}}}""")
        val reasoning = item("reading-turn", """{"id":"native-summary","type":"reasoning","summary":[${JsonPrimitive("**$summaryFirst**")},${JsonPrimitive("## $summaryLatest")}]}""", complete = false)
        private val mainItems = listOf(
            item("reading-turn", """{"id":"request","type":"userMessage","content":[{"type":"text","text":"检查工具记录和子代理阅读体验。"}]}"""),
            item("reading-turn", """{"id":"comment","type":"agentMessage","phase":"commentary","text":"已核对当前界面。接下来检查阅读层级和返回路径。"}"""),
            command,
            item("reading-turn", """{"id":"edit-ui","type":"fileChange","status":"completed","changes":[{"path":"android/app/src/main/java/dev/codexpocket/app/ui/NativeActivityRows.kt","kind":{"type":"update"},"diff":"@@ -1 +1 @@\n-工具记录\n+紧凑工具记录"}]}"""),
            created, sent, reasoning,
        )
        // A fixed native duration keeps the header deterministic without a ticking observation clock.
        val timeline = NativeTimeline(mainItems.associateBy { it.key }, activeTurn = "reading-turn",
            status = "inProgress", turns = mapOf("reading-turn" to
                NativeTurnInfo("reading-turn", status = "inProgress", durationMs = 65_000)))
        val rows = ConversationPresentation.rows(timeline)
        val group = rows.single { it.kind == TranscriptKind.Activity && it.items.size == 2 }
        val agents = agentRoster(timeline) + AgentRosterEntry("reading-history", "Gauss",
            ActivityStatus.Completed, "completed", resultPreview = "上一轮已核对颜色与文字对比度。")
        val parentChat = ChatEntity(parent, "reading-runtime", "desktop", "阅读体验审阅", "", "D:/codex2", 0, "inProgress", false)
        val child = ChatEntity(ThreadKey(parent.computerId, parent.configStoreId, childId),
            "reading-runtime", "desktop", "Hooke", "", "D:/codex2", 0, "idle", false)
        val childAnswer = "已核对阅读层级。\n\n" +
            "工具默认显示紧凑记录，点击后在原位置展开命令与输出。\n\n" +
            "子代理使用居中只读面板。放大与缩小保留当前内容，返回列表后仍可继续阅读。\n\n" +
            "- 原生摘要保持分段、低对比度正文\n- 创建与发送消息各自独立成行\n- 用时仅显示在任务标题区域"
        val childFinal = item("child-turn", """{"id":"child-final","type":"agentMessage","phase":"final_answer","text":${JsonPrimitive(childAnswer)}}""")
        private val childItems = listOf(
            item("child-turn", """{"id":"child-user","type":"userMessage","content":[{"type":"text","text":${JsonPrimitive(createPrompt)}}]}"""),
            item("child-turn", """{"id":"child-read","type":"commandExecution","status":"completed","command":"Get-Content AgentReaderPanel.kt","cwd":"D:/codex2","exitCode":0,"aggregatedOutput":"sheet = false\nmaxHeightFraction = .78f\ncanFork = false"}"""),
            childFinal,
        )
        val childTimeline = NativeTimeline(childItems.associateBy { it.key }, completedTurns = setOf("child-turn"),
            turns = mapOf("child-turn" to NativeTurnInfo("child-turn", status = "completed", durationMs = 12_000)))
        var theme by mutableStateOf("light")
        var expansions by mutableStateOf(emptySet<String>())
        var reader by mutableStateOf<AgentReaderState?>(null)
        var shows = 0
        var opens = 0
        var lists = 0
        var closes = 0
        val sizes = mutableListOf<Boolean>()
        fun showRoster() { shows++; reader = AgentReaderState(parent) }
        fun openChild(id: String) {
            check(id == childId) { "Unexpected fixture child: $id" }
            opens++
            reader = AgentReaderState(parent, child, childTimeline, updatedAt = 1_700_000_000_000L)
        }
        fun list() { lists++; reader = reader!!.copy(child = null, timeline = NativeTimeline(), fullScreen = false) }
        fun close() { closes++; reader = null }
        fun resize(fullscreen: Boolean) { sizes += fullscreen; reader = reader!!.copy(fullScreen = fullscreen) }
        fun toggle(key: String) { expansions = if (key in expansions) expansions - key else expansions + key }
    }

    @Test(timeout = 110_000) fun localReadingScreenshotsAndChildReaderRoundTrip() {
        startedAt = SystemClock.elapsedRealtime()
        val external = requireNotNull(compose.activity.getExternalFilesDir(null))
        proofDirectory = File(external, "v051-reading-proof/${System.currentTimeMillis()}")
        check(proofDirectory.mkdirs())
        val fixture = Fixture()
        var failure: Throwable? = null
        try {
            compose.activity.runOnUiThread { compose.activity.setContent { FixtureScreen(fixture) } }
            exerciseTheme(fixture, "light")
            compose.runOnIdle { fixture.theme = "dark"; fixture.expansions = emptySet() }
            exerciseTheme(fixture, "dark")
            compose.runOnIdle {
                assertEquals(2, fixture.shows)
                assertEquals(2, fixture.opens)
                assertEquals(2, fixture.lists)
                assertEquals(2, fixture.closes)
                assertEquals(listOf(true, false, true, false), fixture.sizes)
                assertNull(fixture.reader)
            }
            assertEquals(24, screenshots.size)
            check(SystemClock.elapsedRealtime() - startedAt < 100_000)
        } catch (error: Throwable) {
            failure = error
            if (SystemClock.elapsedRealtime() - startedAt < 97_000) runCatching { capture("failure") }
            throw error
        } finally {
            val report = buildJsonObject {
                put("passed", failure == null)
                put("fixtureOnly", true)
                put("repositoryCallbacks", 0); put("modelRequests", 0); put("shellExecutions", 0)
                put("optIn", "v051Reading=true"); put("stage", stage)
                put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
                put("fixtureElapsedLabel", "1:05"); put("fontScale", 1.0)
                put("rosterShows", fixture.shows); put("childOpens", fixture.opens)
                put("listCallbacks", fixture.lists); put("closeCallbacks", fixture.closes)
                put("fullscreenCallbacks", buildJsonArray { fixture.sizes.forEach { add(it) } })
                put("verifiedThemes", buildJsonArray { verifiedThemes.forEach { add(it) } })
                put("screenshots", JsonArray(screenshots))
                failure?.let { put("failure", "${it.javaClass.simpleName}: ${it.message}") }
            }
            File(proofDirectory, "report.json").writeText(report.toString())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("v051ReadingProof", proofDirectory.absolutePath)
                putString("v051ReadingReport", report.toString())
            })
        }
    }

    @Composable private fun FixtureScreen(fixture: Fixture) {
        PocketTheme(fixture.theme) {
            val actual = LocalDensity.current
            SideEffect { density = actual.density }
            CompositionLocalProvider(LocalDensity provides Density(actual.density, 1f),
                LocalNativeAgentNames provides fixture.names,
                LocalNativeAgentReader provides fixture::openChild) {
                ProvideMarkdownRendering {
                    // PocketBackdrop owns the real PocketOverlayHost; do not introduce a nested host.
                    PocketBackdrop {
                        key(fixture.theme) {
                            Column(Modifier.fillMaxSize()) {
                                ConversationHeader(PocketState(paired = true, online = true,
                                    selected = fixture.parentChat, computerName = "本地电脑", timeline = fixture.timeline,
                                    pushConfigured = false), {}, {}, {})
                                AgentRosterChip(fixture.agents, true, false, fixture::openChild,
                                    Modifier.align(Alignment.CenterHorizontally).padding(vertical = 2.dp),
                                    names = fixture.names, onShow = fixture::showRoster)
                                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("reading-transcript"),
                                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(fixture.rows, key = { it.key }) { row ->
                                        TranscriptRowView(row, "v051-reading:${fixture.theme}", row.key in fixture.expansions,
                                            { fixture.toggle(row.key) }, { fail("Unexpected full detail callback") },
                                            canFork = false, activityExpansions = fixture.expansions,
                                            onActivityExpansion = fixture::toggle)
                                    }
                                    item(key = "native-summary") {
                                        LiveTurnStatus("reading-turn", ConversationPresentation.LiveStatus.Thinking,
                                            reasoning = fixture.reasoning)
                                    }
                                    item(key = "working-agents") {
                                        NativeAgentWorkingStrip(fixture.agents, onOpen = { fixture.showRoster() })
                                    }
                                }
                                PocketComposer("", "桌面模型", "medium", true,
                                    ComposerAvailability(false, false, true, true), {}, {}, {}, {},
                                    { fail("Unexpected send") }, { fail("Unexpected steer") }, { fail("Unexpected stop") }, false,
                                    readingHistory = true, transcriptScrolling = true)
                            }
                            fixture.reader?.let { value ->
                                AgentReaderPanel(value, fixture.agents, fixture.names, true,
                                    fixture::openChild, fixture::list, fixture::close, fixture::resize,
                                    { fail("Unexpected older-history request") }, { fail("Unexpected retry request") })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun exerciseTheme(fixture: Fixture, theme: String) {
        stage = "$theme-default"
        waitText("已创建 1 个智能体")
        waitText("已发送消息 1 个智能体")
        scrollMain("native-agents-working")
        compose.onNodeWithText("Hooke、Dewey开始工作").assertIsDisplayed()
        compose.onNodeWithText(fixture.summaryLatest).assertExists()
        compose.onNodeWithText(fixture.summaryFirst).assertDoesNotExist()
        compose.onNodeWithText(fixture.createPrompt).assertDoesNotExist()
        compose.onNodeWithText(fixture.sendPrompt).assertDoesNotExist()
        compose.onNodeWithTag("activity-toggle:${fixture.command.key}").assertDoesNotExist()
        compose.onAllNodesWithTag("task-elapsed-label", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("task-elapsed-label", useUnmergedTree = true).assertTextEquals("1:05")
        val clock = bounds("task-elapsed-label")
        val header = bounds("conversation-header")
        assertTrue(clock.top >= header.top && clock.bottom <= header.bottom)
        assertNoGenericStatus()
        capture("$theme-01-latest-default")

        stage = "$theme-summary-expanded"
        compose.onNodeWithTag("live-reasoning-toggle").performClick()
        scrollMain("live-reasoning-toggle")
        compose.onNodeWithTag("native-summary-paragraph:0", useUnmergedTree = true).assertTextEquals(fixture.summaryFirst)
        compose.onNodeWithTag("native-summary-paragraph:1", useUnmergedTree = true).assertTextEquals(fixture.summaryLatest)
        capture("$theme-02-summary-expanded")
        compose.onNodeWithTag("live-reasoning-toggle").performClick()

        stage = "$theme-tool-group"
        scrollMain("activity-group-toggle:${fixture.group.key}")
        compose.onNodeWithTag("activity-group-toggle:${fixture.group.key}").performClick()
        waitTag("activity-toggle:${fixture.command.key}")
        waitText("NativeActivityRows.kt")
        compose.onNodeWithText(fixture.commandOutput).assertDoesNotExist()
        capture("$theme-03-tool-group-expanded")
        stage = "$theme-tool-inline"
        compose.onNodeWithTag("activity-toggle:${fixture.command.key}").performClick()
        waitText(fixture.commandOutput)
        compose.onNodeWithText(fixture.commandOutput).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pocket-modal-surface").assertDoesNotExist()
        capture("$theme-04-tool-inline-detail")
        compose.onNodeWithTag("activity-toggle:${fixture.command.key}").performScrollTo().performClick()
        scrollMain("activity-group-toggle:${fixture.group.key}")
        compose.onNodeWithTag("activity-group-toggle:${fixture.group.key}").performClick()

        stage = "$theme-created-row"
        scrollMain("activity-toggle:${fixture.created.key}")
        compose.onNodeWithTag("activity-toggle:${fixture.created.key}").performClick()
        waitText(fixture.createPrompt)
        compose.onNodeWithText(fixture.createPrompt).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("agent-summary:${fixture.childId}").assertExists()
        capture("$theme-05-created-row-expanded")
        compose.onNodeWithTag("activity-toggle:${fixture.created.key}").performScrollTo().performClick()
        stage = "$theme-sent-row"
        scrollMain("activity-toggle:${fixture.sent.key}")
        compose.onNodeWithTag("activity-toggle:${fixture.sent.key}").performClick()
        waitText(fixture.sendPrompt)
        compose.onNodeWithText(fixture.sendPrompt).performScrollTo().assertIsDisplayed()
        capture("$theme-06-sent-row-expanded")
        compose.onNodeWithTag("activity-toggle:${fixture.sent.key}").performScrollTo().performClick()

        stage = "$theme-roster"
        compose.onNodeWithTag("agent-roster-chip").performClick()
        waitTag("agent-reader-roster")
        compose.onNodeWithTag("agent-roster-panel").assertDoesNotExist()
        compose.onNodeWithText("本轮子代理").assertIsDisplayed()
        compose.onNodeWithTag("agent-roster-open:reading-history").assertDoesNotExist()
        compose.onNodeWithTag("agent-reader-history-toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
        val preview = assertCenteredReader()
        assertReadOnly()
        capture("$theme-07-centered-roster")
        stage = "$theme-child-preview"
        compose.onNodeWithTag("agent-roster-open:${fixture.childId}").performClick()
        waitTag("agent-reader-transcript")
        waitNativeText("已核对阅读层级。")
        compose.onNodeWithTag("agent-reader-name").assertTextEquals("Hooke")
        compose.onNodeWithText("子代理 · 只读").assertIsDisplayed()
        assertCenteredReader()
        assertReadOnly()
        capture("$theme-08-child-readonly")

        stage = "$theme-child-fullscreen"
        compose.onNodeWithContentDescription("放大子代理阅读").performClick()
        compose.onNodeWithTag("agent-reader-return-main").assertIsDisplayed()
        val fullscreen = bounds("pocket-modal-surface")
        assertTrue(fullscreen.height > preview.height)
        assertTrue(fullscreen.width >= preview.width)
        assertReadOnly()
        capture("$theme-09-child-fullscreen")
        stage = "$theme-child-minimized"
        compose.onNodeWithContentDescription("缩小子代理阅读").performClick()
        compose.onNodeWithTag("agent-reader-return-main").assertDoesNotExist()
        assertCenteredReader()
        compose.runOnIdle { assertEquals(fixture.child.key, fixture.reader!!.child!!.key) }
        waitNativeText("已核对阅读层级。")
        assertReadOnly()
        capture("$theme-10-child-minimized")

        stage = "$theme-roster-back"
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitTag("agent-reader-roster")
        assertReadOnly()
        capture("$theme-11-back-to-roster")
        stage = "$theme-task-back"
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitTag("reading-transcript")
        compose.onNodeWithTag("agent-reader-panel").assertDoesNotExist()
        scrollMain("native-agents-working")
        compose.onNodeWithTag("task-elapsed-label", useUnmergedTree = true).assertTextEquals("1:05")
        assertNoGenericStatus()
        capture("$theme-12-back-to-task")
        verifiedThemes += theme
    }

    private fun assertCenteredReader(): Rect {
        compose.waitForIdle()
        val panel = bounds("pocket-modal-surface")
        val host = bounds("pocket-overlay-host")
        assertTrue(panel.width <= minOf(host.width - 32f * density, 720f * density) + 2f)
        assertTrue(panel.height <= host.height * .78f + 2f)
        assertEquals(host.center.x, panel.center.x, 2f)
        assertEquals(host.center.y, panel.center.y, 40f * density)
        val toolbar = bounds("agent-reader-toolbar")
        assertTrue(toolbar.top >= panel.top - 2f && toolbar.bottom <= panel.bottom + 2f)
        return panel
    }

    private fun assertReadOnly() {
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("composer-surface").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.stop_turn)).assertDoesNotExist()
        compose.onNodeWithContentDescription("旁支讨论").assertDoesNotExist()
    }

    private fun assertNoGenericStatus() {
        compose.onNodeWithText("正在思考").assertDoesNotExist()
        compose.onNodeWithText("正在处理").assertDoesNotExist()
        compose.onNodeWithText("**", substring = true).assertDoesNotExist()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun budgetMillis(): Long {
        val remaining = 100_000 - (SystemClock.elapsedRealtime() - startedAt)
        check(remaining > 0) { "Reading proof exceeded its 100-second budget at $stage" }
        return minOf(3_000L, remaining)
    }
    private fun scrollMain(tag: String) {
        budgetMillis()
        compose.onNodeWithTag("reading-transcript").performScrollToNode(hasTestTag(tag))
    }
    private fun waitTag(tag: String) = compose.waitUntil(budgetMillis()) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitText(text: String) = compose.waitUntil(budgetMillis()) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitNativeText(text: String) = compose.waitUntil(budgetMillis()) {
        compose.runOnIdle { nativeTexts(compose.activity.window.decorView).any { text in it } }
    }
    private fun nativeTexts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { nativeTexts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val wait = budgetMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latch = CountDownLatch(1)
        var result = -1
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val view = compose.activity.window.decorView
            check(view.width > 0 && view.height > 0)
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            // Let AndroidView Markdown and the sibling overlay reach the window before PixelCopy.
            view.invalidate()
            view.postOnAnimation { view.postOnAnimation {
                PixelCopy.request(compose.activity.window, bitmap,
                    { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
            } }
        }
        try {
            check(latch.await(wait, TimeUnit.MILLISECONDS) && result == PixelCopy.SUCCESS) {
                "PixelCopy failed at $stage: $result"
            }
            val file = File(proofDirectory, "$name.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(file.length() > 0)
            screenshots += buildJsonObject {
                put("file", file.name); put("stage", stage)
                put("width", bitmap.width); put("height", bitmap.height); put("bytes", file.length())
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("v051ReadingScreenshot", file.absolutePath) })
        } finally {
            // A timed-out request still owns its destination until its callback completes.
            if (latch.count == 0L) bitmap.recycle()
        }
    }
}
