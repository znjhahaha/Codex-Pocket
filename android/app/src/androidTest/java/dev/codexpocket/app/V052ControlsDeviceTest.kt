package dev.codexpocket.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Device acceptance, deliberately NOT part of an ordinary instrumentation run.
 * -e v052Controls true -e class dev.codexpocket.app.V052ControlsDeviceTest
 *
 * Launch in a fresh instrumentation process. MainActivity normally initializes the live repository:
 * the outer rule substitutes an in-memory, unpaired repository BEFORE Activity launch and suppresses
 * the application's reconnect observer. The original lazy binding is never evaluated or persisted.
 * All controls below use local callbacks. No pairing/draft clearing, native operations or inference.
 * Both main and child diff readers run under PocketBackdrop + ProvideMarkdownRendering.
 */
@RunWith(AndroidJUnit4::class)
class V052ControlsDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    // MuMu can route its IME to display 0 while this Activity is on another display.
    // Explicit false verifies touch/focus/history only; it never counts as system IME coverage.
    private val verifySystemIme get() = InstrumentationRegistry.getArguments().getString("v052ControlsIme") != "false"
    private var connectionAttempts = 0
    private var pairingWrites = 0

    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Opt in with v052Controls=true",
                    InstrumentationRegistry.getArguments().getString("v052Controls") == "true")
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val application = instrumentation.targetContext.applicationContext as PocketApplication
                val field = PocketApplication::class.java.getDeclaredField("repository\$delegate").apply { isAccessible = true }
                val original = field.get(application) as Lazy<*>
                check(!original.isInitialized()) { "Run this fixture alone in a fresh instrumentation process" }
                val database = Room.inMemoryDatabaseBuilder(application, PocketDatabase::class.java).build()
                var isolated: PocketRepository? = null
                instrumentation.runOnMainSync {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(application)
                    field.set(application, lazy {
                        PocketRepository(application,
                            connectionFactory = { connectionAttempts++; error("Fixture attempted a connection") },
                            pairStore = object : PairingStorage {
                                override fun load(): PairedComputer? = null
                                override fun save(pair: PairedComputer) { pairingWrites++; error("Fixture attempted pairing") }
                                override fun forget() { pairingWrites++; error("Fixture attempted to clear pairing") }
                            }, db = database, networkAvailable = emptyFlow()
                        ).also { isolated = it }
                    })
                }
                try {
                    base.evaluate()
                } finally {
                    try {
                        isolated?.let { repository ->
                            val scopeField = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
                            val scope = scopeField.get(repository) as CoroutineScope
                            runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]?.cancelAndJoin() } }
                        }
                    } finally {
                        database.close()
                        // ProcessLifecycleOwner delays Stop; restoring its observer while Started would
                        // immediately initialize the original repository and reconnect after the fixture.
                        val deadline = SystemClock.elapsedRealtime() + 5_000
                        while (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
                        instrumentation.runOnMainSync {
                            field.set(application, original)
                            check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                "Fixture Activity did not stop; reconnect observer cannot yet be restored"
                            }
                            ProcessLifecycleOwner.get().lifecycle.addObserver(application)
                        }
                    }
                }
            }
        }
    }).around(compose)

    private data class Variant(val id: String, val theme: String, val fontScale: Float)
    private enum class Page { Controls, References, Trace, Child, Composer, PlanFlow, Minimap }
    private class Fixture {
        var variant by mutableStateOf(Variant("light", "light", 1f))
        var page by mutableStateOf(Page.Controls)
        var permissionOpen by mutableStateOf(false)
        var approvalRevision by mutableIntStateOf(0)
        var advertised by mutableStateOf<List<String>?>(listOf("accept", "decline"))
        var traceExpanded by mutableStateOf(false)
        var childOpen by mutableStateOf(true)
        var childFullscreen by mutableStateOf(false)
        var readingHistory by mutableStateOf(false)
        var transcriptScrolling by mutableStateOf(false)
        var focusRequest by mutableIntStateOf(0)
        var imeBottom by mutableIntStateOf(0)
        var scrollTriggers = 0
        var implementations = 0
        var modifications = 0
        var permissionRefreshes = 0
        var contextRefreshes = 0
        var editorChanges = 0
        var draftChanges = 0
        val decisions = mutableListOf<Pair<String, JsonObject>>()
        val childSizes = mutableListOf<Boolean>()
        val draft = "保留草稿：布局审阅与设计说明\n只核对控件，不发送消息。"
        val plan = ProposedPlan("controls-plan-turn", "controls-plan", "## 控件验收\n\n1. 核对权限与审批。\n2. 保留草稿与阅读位置。")
        val permissions = PermissionState(status = "ready", revision = JsonPrimitive("local-revision"),
            currentPresetId = "custom", currentLabel = "自定义权限", presets = listOf(
                PermissionPreset("request", "请求权限", "逐次确认操作", true),
                PermissionPreset("auto", "自动权限", "工作区内自动确认", true),
                PermissionPreset("full", "完全访问", "本地样本不可选择", false, "此本地样本未公布完全访问权限")))
        val usage = buildJsonObject {
            put("modelContextWindow", 200_000)
            put("last", buildJsonObject { put("inputTokens", 47_000); put("outputTokens", 3_000); put("totalTokens", 50_000) })
            put("total", buildJsonObject { put("totalTokens", 350_000) })
        }
        val parent = ThreadKey("controls-pc", "controls-store", "controls-parent")
        val child = ChatEntity(ThreadKey(parent.computerId, parent.configStoreId, "controls-child"),
            "controls-runtime", "desktop", "控件子代理", "", "D:/fixture", 0, "idle", false,
            isSubagent = true, parentThreadId = parent.nativeId)
        val state = PocketState(paired = true, online = true, selected = ChatEntity(parent,
            "controls-runtime", "desktop", "0.5.2 控件验收", "", "D:/fixture", 0, "idle", false),
            runtimes = listOf(RuntimeInfo("controls-runtime", "desktop", "fixture", "controls-store", true,
                emptySet(), setOf("thread/compact/start"))),
            advanced = AdvancedConversationState(usage = usage), permissions = permissions,
            contextSnapshot = ContextSnapshot(usage = usage, status = "ok", source = "local-fixture"), pushConfigured = false)

        private fun reference(type: String, name: String, display: String, path: String) = buildJsonObject {
            put("type", type); put("name", name); put("displayName", display); put("path", path)
            put("description", "已选择的本地${type}样本"); put("source", "local-fixture")
            put("enabled", true); put("supported", true)
        }
        val skill = reference("skill", "layout-review", "布局审阅", "D:/fixture/skills/layout-review/SKILL.md")
        val mention = reference("mention", "design-notes", "设计说明", "D:/fixture/docs/design-notes.md")
        val referenceBody = "已选择：布局审阅 + 设计说明\n仅键入：\$layout-review @design-notes"
        val spans = listOf(skill, mention).map { value ->
            val name = value.string("displayName")
            val start = referenceBody.indexOf(name)
            ReferenceSpan(start, start + name.length, name, value)
        }
        var editor by mutableStateOf(TextFieldValue(referenceBody, TextRange(spans.first().start, spans.first().end)))
        val commands = (1..12).map { number -> item("controls-trace", buildJsonObject {
            put("id", "read-$number"); put("type", "commandExecution"); put("status", "completed")
            put("command", "Get-Content src/Control$number.kt"); put("cwd", "D:/fixture")
            put("exitCode", 0); put("durationMs", 10)
            put("commandActions", buildJsonArray { add(buildJsonObject {
                put("type", "read"); put("name", "Control$number.kt"); put("path", "src/Control$number.kt")
            }) })
            put("aggregatedOutput", "local read $number")
        }) }
        val patch = "@@ -1,3 +1,3 @@\n fun controls() {\n-    val mode = 0\n+    val mode = 1\n }"
        val fileChange = item("controls-trace", buildJsonObject {
            put("id", "files"); put("type", "fileChange"); put("status", "completed")
            put("changes", buildJsonArray {
                listOf("Controls.kt", "Reading.kt").forEach { name -> add(buildJsonObject {
                    put("path", "D:/fixture/src/$name"); put("kind", buildJsonObject { put("type", "update") }); put("diff", patch)
                }) }
            })
        })
        val trace = TranscriptRow("controls-dense-trace", TranscriptKind.Activity, commands + fileChange, completedTurn = true)
        // A native in-progress turn retains Activity rows in the real child reader (no synthetic process wrapper).
        val childTimeline = NativeTimeline((commands + fileChange).associateBy { it.key }, activeTurn = "controls-trace",
            status = "inProgress", turns = mapOf("controls-trace" to NativeTurnInfo("controls-trace", status = "inProgress", durationMs = 1_000)))
        fun approval() = PendingApproval("controls-runtime", JsonPrimitive("controls-approval-$approvalRevision"),
            "item/commandExecution/requestApproval", buildJsonObject {
                put("command", "Get-Content src/Controls.kt"); put("cwd", "D:/fixture")
                put("reason", "只显示已公布的审批选项；按钮仅记录本地回调。")
                advertised?.let { put("availableDecisions", buildJsonArray { it.forEach { choice -> add(choice) } }) }
            })
        companion object {
            private fun item(turn: String, raw: JsonObject) = TimelineItem.from(turn, raw, true)
        }
    }

    private lateinit var proofDirectory: File
    private val stages = mutableListOf<JsonObject>()
    private val screenshots = mutableListOf<JsonObject>()
    private val stageAssertions = mutableListOf<JsonObject>()
    private var stage = "setup"
    private var startedAt = 0L
    private var density = 1f
    private var viewportWidthDp = 0f
    private lateinit var fixture: Fixture
    private val screenshotLimit = 24

    @Test(timeout = 240_000) fun localControlsScreenshotsAndComposerHistoryRegression() {
        startedAt = SystemClock.elapsedRealtime()
        proofDirectory = File(requireNotNull(compose.activity.getExternalFilesDir(null)),
            "v052-controls-proof/${System.currentTimeMillis()}")
        check(proofDirectory.mkdirs())
        fixture = Fixture()
        var failure: Throwable? = null
        try {
            compose.activity.runOnUiThread { compose.activity.setContent { FixtureScreen(fixture) } }
            listOf(Variant("light", "light", 1f), Variant("dark", "dark", 1f),
                Variant("dark-large", "dark", 1.4f)).forEach { variant ->
                compose.runOnIdle {
                    fixture.variant = variant; fixture.page = Page.Controls; fixture.permissionOpen = false
                    fixture.advertised = listOf("accept", "decline"); fixture.approvalRevision++
                    fixture.traceExpanded = false; fixture.childOpen = true; fixture.childFullscreen = false
                    fixture.readingHistory = false; fixture.transcriptScrolling = false; fixture.focusRequest = 0
                    fixture.editor = TextFieldValue(fixture.referenceBody, TextRange(fixture.spans.first().start, fixture.spans.first().end))
                }
                exerciseControls(variant)
                exerciseReferences(variant)
                exerciseTrace(variant)
                exerciseChild(variant)
                exerciseComposer(variant)
            }
            exerciseLateReading()
            record("fixture-summary") {
                verify("bounded-screenshot-proof") { assertEquals(22, screenshots.size); assertTrue(screenshots.size < screenshotLimit) }
                verify("no-network-or-pairing-writes") { assertEquals(0, connectionAttempts); assertEquals(0, pairingWrites) }
                verify("draft-text-preserved") { assertEquals(0, fixture.draftChanges) }
                verify("all-four-advertised-decisions-per-variant") { assertEquals(12, fixture.decisions.size) }
                verify("three-plan-local-callbacks") { assertEquals(3, fixture.implementations); assertEquals(3, fixture.modifications) }
                verify("child-fullscreen-return-per-variant") { assertEquals(listOf(true, false, true, false, true, false), fixture.childSizes) }
            }
        } catch (error: Throwable) {
            failure = error
            if (screenshots.size < screenshotLimit) runCatching { capture("failure") }
            throw error
        } finally {
            val report = buildJsonObject {
                put("schemaVersion", 1); put("fixture", "CodexPocket0.5.2-controls"); put("passed", failure == null)
                put("fixtureOnly", true); put("optIn", "v052Controls=true"); put("stage", stage)
                put("repositoryIsolation", "in-memory-unpaired-before-activity-launch")
                put("repositoryCallbacks", 0); put("modelRequests", 0); put("shellExecutions", 0)
                put("connectionAttempts", connectionAttempts); put("pairingWrites", pairingWrites)
                put("draftChanges", fixture.draftChanges); put("denseToolCount", fixture.commands.size)
                put("fileChangeCount", activityFiles(fixture.fileChange).size)
                put("elapsedMs", SystemClock.elapsedRealtime() - startedAt); put("viewportWidthDp", viewportWidthDp)
                put("screenshotLimit", screenshotLimit); put("screenshots", JsonArray(screenshots)); put("stages", JsonArray(stages))
                put("systemImeVerified", verifySystemIme && failure == null)
                put("decisions", buildJsonArray { fixture.decisions.forEach { (request, result) -> add(buildJsonObject {
                    put("request", request); put("response", result)
                }) } })
                failure?.let { put("failure", "${it.javaClass.simpleName}: ${it.message}") }
            }
            File(proofDirectory, "report.json").writeText(report.toString())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("v052ControlsProof", proofDirectory.absolutePath)
                putString("v052ControlsReport", report.toString())
            })
        }
    }

    @Composable private fun FixtureScreen(f: Fixture) {
        PocketTheme(f.variant.theme) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val physical = LocalDensity.current
                // Scale the complete overlay host, not just a child Column, to a 360dp viewport.
                val fixtureDensity = Density(maxWidth.value * physical.density / 360f, f.variant.fontScale)
                CompositionLocalProvider(LocalDensity provides fixtureDensity) {
                    val ime = WindowInsets.ime.getBottom(fixtureDensity)
                    SideEffect { density = fixtureDensity.density; f.imeBottom = ime }
                    ProvideMarkdownRendering { PocketBackdrop { key(f.variant.id) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("v052-viewport")) {
                            when (f.page) {
                                Page.Controls -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                    .testTag("v052-controls").padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("0.5.2 控件验收", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        ContextIndicator(f.state, { error("Unexpected context mutation") }, { f.contextRefreshes++ })
                                    }
                                    PlanConfirmation(f.plan, null, { f.implementations++ }, { f.modifications++ })
                                    key(f.approvalRevision) { ApprovalCard(f.approval(), true, { approval, response ->
                                        f.decisions += approval.key to response
                                    }) }
                                }
                                Page.References -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("已选择的引用与仅键入的文字", style = MaterialTheme.typography.titleSmall)
                                    ReferenceEditor(f.editor, { value ->
                                        if (value.text != f.editor.text) f.editorChanges++
                                        f.editor = value
                                    }, f.spans, Modifier.fillMaxWidth().testTag("v052-reference-editor"))
                                    Text("发送前阅读样本", style = MaterialTheme.typography.labelLarge)
                                    SelectionContainer { ReferenceText(f.referenceBody, f.spans,
                                        Modifier.fillMaxWidth().testTag("v052-reference-text")) }
                                }
                                Page.Trace -> Column(Modifier.fillMaxWidth().padding(12.dp).testTag("v052-trace")) {
                                    Text("12 个工具 · 2 个文件更改", style = MaterialTheme.typography.titleSmall)
                                    CompositionLocalProvider(LocalInlineDiffComment provides null) {
                                        NativeActivityTrace(f.trace, "v052:${f.variant.id}:main", f.traceExpanded,
                                            { f.traceExpanded = !f.traceExpanded }, { error("Unexpected raw tool detail") })
                                    }
                                }
                                Page.Child -> Text("子代理文件差异返回路径", Modifier.padding(16.dp))
                                Page.PlanFlow -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                    NativeActivityCard(TimelineItem("long-plan", "plan", "plan",
                                        "# Codex Pocket：阅读布局\n\n第一段计划正文。\n\n## 权限闭环\n\n- 保留配对\n- 保留草稿\n\n## 设备验收\n\n最后一段验收内容。", complete = true), "device-plan-flow", true, {}, {})
                                }
                                Page.Minimap -> Box(Modifier.fillMaxSize().padding(vertical = 20.dp)) {
                                    val list = rememberLazyListState()
                                    val rows = remember { (0 until 80).map { index -> TranscriptRow("mini-$index", TranscriptKind.Answer,
                                        listOf(TimelineItem("mini", "$index", "agentMessage", "已加载的对话 $index\n原生记录"))) } }
                                    val keys = remember { rows.map { it.key } }
                                    LazyColumn(Modifier.fillMaxSize().padding(start = 58.dp).testTag("device-minimap-transcript"), state = list) {
                                        items(rows.size, key = { rows[it].key }) { index -> Text(rows[index].items.first().text, Modifier.height(80.dp)) }
                                    }
                                    ConversationMinimap(rows, keys, list, Modifier.align(Alignment.CenterStart))
                                }
                                Page.Composer -> {
                                    val list = rememberLazyListState()
                                    LaunchedEffect(list) {
                                        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
                                            f.transcriptScrolling = scrolling
                                            if (scrolling) { f.readingHistory = true; f.scrollTriggers++ }
                                        }
                                    }
                                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("v052-composer-transcript"), state = list) {
                                        items(40) { index -> Text("本地历史 ${index + 1} · 手势滚动结束输入焦点",
                                            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(16.dp)) }
                                    }
                                    PocketComposer(text = f.draft, model = "本地模型", detail = "medium", running = false,
                                        availability = ComposerAvailability(false, false, false, false),
                                        onText = { f.draftChanges++; error("Unexpected draft text change") },
                                        onModels = { error("Unexpected model menu") },
                                        onAttach = { error("Unexpected attachment") },
                                        onReferences = { error("Unexpected references request") },
                                        onSend = { error("Unexpected send") },
                                        onSteer = { error("Unexpected steer") }, onStop = { error("Unexpected stop") },
                                        referencesEnabled = false,
                                        readingHistory = f.readingHistory, transcriptScrolling = f.transcriptScrolling,
                                        modelMenuExpanded = false, referenceSpans = emptyList(), focusRequest = f.focusRequest,
                                        onTextEdit = { _, _, _ -> f.draftChanges++; error("Unexpected draft range edit") })
                                }
                            }
                        }
                        if (f.permissionOpen) PermissionPanel(f.permissions, true, false,
                            { f.permissionOpen = false }, { f.permissionRefreshes++ }, { error("Unexpected permission mutation") })
                        if (f.page == Page.Child && f.childOpen) AgentReaderPanel(
                            AgentReaderState(f.parent, f.child, f.childTimeline, fullScreen = f.childFullscreen),
                            emptyList(), mapOf(f.child.key.nativeId to "控件子代理"), true,
                            { error("Unexpected child network selection") }, { f.childOpen = false }, { f.childOpen = false },
                            { full -> f.childSizes += full; f.childFullscreen = full },
                            { error("Unexpected history request") }, { error("Unexpected child retry") })
                    } } }
                }
            }
        }
    }

    private fun exerciseControls(v: Variant) {
        record("${v.id}-controls", screenshot = true) {
            waitTag("plan-confirmation")
            verify("360dp-viewport") {
                viewportWidthDp = compose.onNodeWithTag("v052-viewport").fetchSemanticsNode().boundsInRoot.width / density
                assertEquals(360f, viewportWidthDp, 2f)
            }
            verify("plan-actions-visible-enabled") {
                compose.onNodeWithTag("plan-implement").assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithTag("plan-modify").assertIsDisplayed().assertIsEnabled()
            }
            verify("real-approval-only-advertised-choices") {
                compose.onNodeWithTag("approval-card").assertIsDisplayed()
                compose.onNodeWithText("允许一次").assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithText("拒绝").assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithText("本会话允许").assertDoesNotExist()
                compose.onNodeWithText("取消任务").assertDoesNotExist()
            }
            verify("context-indicator-25-percent") { compose.onNodeWithTag("context-indicator").assertContentDescriptionEquals("上下文 25%") }
        }
        record("${v.id}-plan-local-roundtrip") {
            val before = fixture.modifications
            compose.onNodeWithTag("plan-modify").performClick()
            verify("modify-only-local-callback") { assertEquals(before + 1, fixture.modifications); compose.onNodeWithText("补充你希望修改的内容").assertExists() }
            compose.onNodeWithContentDescription("返回方案确认").performClick()
            val implemented = fixture.implementations
            compose.onNodeWithTag("plan-implement").performClick()
            verify("implement-only-local-callback") { assertEquals(implemented + 1, fixture.implementations) }
        }
        record("${v.id}-approval-decisions") {
            val choices = listOf("accept" to "允许一次", "acceptForSession" to "本会话允许", "decline" to "拒绝", "cancel" to "取消任务")
            choices.forEach { (decision, label) ->
                compose.runOnIdle { fixture.advertised = listOf(decision); fixture.approvalRevision++ }
                val before = fixture.decisions.size
                val request = fixture.approval().key
                choices.forEach { (other, otherLabel) -> if (other != decision) compose.onNodeWithText(otherLabel).assertDoesNotExist() }
                compose.onNodeWithText(label).performClick()
                verify("advertised-$decision-exact-response-once") {
                    assertEquals(before + 1, fixture.decisions.size)
                    assertEquals(request to buildJsonObject { put("decision", decision) }, fixture.decisions.last())
                    compose.onNodeWithText(label).assertIsNotEnabled().performClick()
                    assertEquals(before + 1, fixture.decisions.size)
                }
            }
            compose.runOnIdle { fixture.advertised = null; fixture.approvalRevision++ }
            verify("unadvertised-session-allow-hidden") { compose.onNodeWithText("本会话允许").assertDoesNotExist() }
            compose.onNodeWithTag("approval-details-toggle").performClick()
            verify("approval-full-details-local") { compose.onNodeWithText("仅批准本次请求公布的操作；不会更改会话权限预设。").assertExists() }
            compose.onNodeWithTag("approval-details-toggle").performClick()
        }
        record("${v.id}-custom-permission-menu", screenshot = true) {
            compose.runOnIdle { fixture.permissionOpen = true }
            waitTag("permission-panel")
            verify("custom-state-has-no-fabricated-selection") {
                compose.onNodeWithText("自定义权限").assertIsDisplayed()
                listOf("request", "auto", "full").forEach { compose.onNodeWithTag("permission-preset:$it").assertIsNotSelected() }
                compose.onNodeWithTag("permission-preset:full").assertIsNotEnabled()
            }
            compose.onNodeWithContentDescription("查看不可用原因：完全访问").performClick()
            verify("disabled-choice-reason") { compose.onNodeWithTag("permission-reason:full").assertTextEquals("此本地样本未公布完全访问权限") }
            val refreshes = fixture.permissionRefreshes
            compose.onNodeWithTag("permission-refresh").performClick()
            verify("permission-refresh-local-state-unchanged") { assertEquals(refreshes + 1, fixture.permissionRefreshes); assertEquals("custom", fixture.permissions.currentPresetId) }
        }
        back(); waitAbsent("permission-panel")
        record("${v.id}-context-compact") {
            compose.onNodeWithTag("context-indicator").performClick()
            waitTag("context-used-window")
            verify("window-usage-not-cumulative") { compose.onNodeWithTag("context-used-window").assertTextEquals("50.0k / 200.0k tokens") }
            verify("details-initially-hidden") { compose.onNodeWithTag("context-details").assertDoesNotExist() }
        }
        record("${v.id}-context-details", screenshot = true) {
            compose.onNodeWithTag("context-details-toggle").performClick()
            verify("exact-latest-and-lifetime-counters") {
                compose.onNodeWithTag("context-details").assertExists()
                compose.onNodeWithText("输入 47000 · 输出 3000").assertIsDisplayed()
                compose.onNodeWithText("最近一轮 50000 tokens").assertIsDisplayed()
                compose.onNodeWithText("窗口 200000 tokens").assertIsDisplayed()
                compose.onNodeWithText("累计使用 350000 tokens").assertIsDisplayed()
            }
            val before = fixture.contextRefreshes
            compose.onNodeWithTag("context-refresh").performClick()
            verify("context-refresh-is-local") { assertEquals(before + 1, fixture.contextRefreshes) }
            verify("compact-operation-not-invoked") { compose.onNodeWithTag("context-compact").assertIsEnabled() }
        }
        back(); waitAbsent("context-details")
    }

    private fun exerciseReferences(v: Variant) {
        compose.runOnIdle { fixture.page = Page.References }
        record("${v.id}-selected-and-typed-references", screenshot = true) {
            waitTag("v052-reference-editor")
            val range = TextRange(fixture.spans.first().start, fixture.spans.first().end)
            compose.onNodeWithTag("v052-reference-editor").performTextInputSelection(range)
            hideKeyboard()
            verify("editor-preserves-display-text-and-selection") {
                compose.onNodeWithTag("v052-reference-editor").assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.InputText, androidx.compose.ui.text.AnnotatedString(fixture.referenceBody)))
                compose.onNodeWithTag("v052-reference-editor").assertTextEquals(
                    ReferenceVisualTransformation(fixture.spans, ReferenceBlue)
                        .filter(androidx.compose.ui.text.AnnotatedString(fixture.referenceBody)).text.text)
                compose.onNodeWithTag("v052-reference-editor").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, range))
                assertEquals(0, fixture.editorChanges)
            }
            verify("read-only-text-has-two-selected-identities") {
                compose.onNodeWithTag("v052-reference-text").assertIsDisplayed()
                    .assertTextContains("\$layout-review @design-notes", substring = true)
                compose.onNodeWithContentDescription("查看布局审阅").assertExists()
                compose.onNodeWithContentDescription("查看设计说明").assertExists()
                assertEquals(listOf("skill", "mention"), prepareReferenceInputs(fixture.editor.text, fixture.spans).references.map { it.string("type") })
                compose.onNodeWithContentDescription("查看\$layout-review").assertDoesNotExist()
                compose.onNodeWithContentDescription("查看@design-notes").assertDoesNotExist()
            }
        }
        listOf(fixture.skill, fixture.mention).forEach { reference ->
            record("${v.id}-reference-${reference.string("type")}-metadata") {
                compose.onNodeWithContentDescription("查看${reference.string("displayName")}").performClick()
                waitTag("reference-metadata")
                verify("selected-reference-exact-path") { compose.onNodeWithText(reference.string("path")).assertIsDisplayed() }
            }
            back(); waitAbsent("reference-metadata")
        }
        record("${v.id}-editor-reference-metadata") {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("v052-reference-editor").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val glyph = layouts.single().getBoundingBox(fixture.spans.first().start).center
            compose.onNodeWithTag("v052-reference-editor").performTouchInput { click(glyph) }
            waitTag("reference-metadata")
            verify("editor-selected-skill-opens-exact-metadata") { compose.onNodeWithText(fixture.skill.string("path")).assertIsDisplayed() }
        }
        back(); waitAbsent("reference-metadata")
    }

    private fun exerciseTrace(v: Variant) {
        hideKeyboard()
        compose.runOnIdle { fixture.page = Page.Trace }
        record("${v.id}-dense-trace") {
            waitTag("activity-group-toggle:${fixture.trace.key}")
            verify("more-than-ten-native-tools") { assertTrue(fixture.commands.size > 10) }
            compose.onNodeWithTag("activity-group-toggle:${fixture.trace.key}").performClick()
            waitTag("activity-group-scroll:${fixture.trace.key}")
            verify("all-twelve-native-tool-rows-reachable") {
                fixture.commands.forEach { command ->
                    val toggle = compose.onNodeWithTag("activity-toggle:${command.key}")
                    waitTag("activity-toggle:${command.key}")
                    toggle.performScrollTo().assertIsDisplayed()
                }
            }
            verify("group-scroll-bounded-to-360dp") {
                val bounds = compose.onNodeWithTag("activity-group-scroll:${fixture.trace.key}").fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.height / density <= 361f)
            }
        }
        record("${v.id}-main-file-inline", screenshot = true) {
            openInline()
            verify("inline-diff-rendered-without-modal") {
                compose.onNodeWithTag("inline-diff:file:0").assertIsDisplayed()
                compose.onNodeWithTag("pocket-modal-surface").assertDoesNotExist()
                assertDiffVisible()
            }
        }
        record("${v.id}-main-file-fullscreen", screenshot = true) {
            compose.onNodeWithContentDescription("放大文件差异").performClick()
            waitTag("inline-diff-reader")
            verify("fullscreen-diff-rendered") { compose.onNodeWithTag("inline-diff-reader").assertIsDisplayed(); assertDiffVisible() }
        }
        record("${v.id}-main-file-back") {
            back(); waitAbsent("inline-diff-reader")
            verify("back-retains-selected-inline-file") { compose.onNodeWithTag("inline-diff:file:0").assertIsDisplayed(); assertDiffVisible() }
            compose.onNodeWithTag("activity-file:file:0").performScrollTo().performClick()
            verify("inline-can-close") { compose.onNodeWithTag("inline-diff:file:0").assertDoesNotExist() }
        }
    }

    private fun exerciseChild(v: Variant) {
        compose.runOnIdle { fixture.page = Page.Child }
        record("${v.id}-child-file-inline") {
            waitTag("agent-reader-transcript")
            val group = ConversationPresentation.rows(fixture.childTimeline).single { it.kind == TranscriptKind.Activity }
            compose.onNodeWithTag("agent-reader-transcript").performScrollToNode(hasTestTag("activity-group-toggle:${group.key}"))
            compose.onNodeWithTag("activity-group-toggle:${group.key}").performClick()
            openInline()
            verify("child-inline-is-read-only") { assertDiffVisible(); compose.onAllNodes(hasSetTextAction()).assertCountEquals(0) }
        }
        record("${v.id}-child-file-fullscreen", screenshot = v.id == "dark-large") {
            compose.onNodeWithContentDescription("放大文件差异").performClick()
            waitTag("inline-diff-reader")
            verify("child-fullscreen-is-read-only") {
                assertDiffVisible(); compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
                compose.onNodeWithText("点按行号添加草稿评论 · 旧侧 / 新侧").assertDoesNotExist()
            }
        }
        record("${v.id}-child-file-back-and-reader-return") {
            back(); waitAbsent("inline-diff-reader")
            verify("child-back-restores-inline") { compose.onNodeWithTag("inline-diff:file:0").assertIsDisplayed(); assertDiffVisible() }
            compose.onNodeWithContentDescription("放大子代理阅读").performClick()
            verify("child-reader-can-enlarge") { assertTrue(fixture.childFullscreen) }
            back()
            verify("child-reader-back-shrinks") { assertFalse(fixture.childFullscreen) }
            compose.onNodeWithContentDescription("关闭子代理阅读").performClick()
            waitAbsent("agent-reader-panel")
            verify("child-reader-returns-to-main") { assertFalse(fixture.childOpen) }
        }
    }

    private fun exerciseComposer(v: Variant) {
        compose.runOnIdle { fixture.page = Page.Composer }
        record("${v.id}-composer-input-first") {
            waitTag("composer-input")
            // Focus first: setting readingHistory/scrolling before a tap misses the regression.
            compose.onNodeWithTag("composer-input").performTouchInput { click() }
            compose.onNodeWithTag("composer-input").assertIsFocused()
            if (verifySystemIme) compose.waitUntil(5_000) { fixture.imeBottom > 0 }
            verify("editing-hides-history-preview") {
                compose.onNodeWithTag("composer-input").assertIsFocused()
                compose.onNodeWithTag("composer-history-preview").assertDoesNotExist()
                compose.onNodeWithTag("composer-model").assertIsDisplayed()
            }
        }
        record("${v.id}-composer-swipe-then-keyboard-dismiss") {
            val triggers = fixture.scrollTriggers
            compose.onNodeWithTag("v052-composer-transcript").performTouchInput { swipeUp(durationMillis = 500) }
            compose.waitUntil(5_000) { fixture.scrollTriggers > triggers && fixture.readingHistory }
            hideKeyboard()
            waitTag("composer-history-preview")
            verify("gesture-observed-and-scroll-settled") { assertTrue(fixture.scrollTriggers > triggers); assertFalse(fixture.transcriptScrolling) }
            assertCompactComposer()
        }
        record("${v.id}-composer-refocus-then-ime-dismiss") {
            compose.onNodeWithTag("composer-history-preview").performClick()
            waitTag("composer-input")
            if (verifySystemIme) compose.waitUntil(5_000) { fixture.imeBottom > 0 }
            verify("preview-tap-restores-editor") { compose.onNodeWithTag("composer-input").assertIsFocused(); compose.onNodeWithTag("composer-history-preview").assertDoesNotExist() }
            if (!verifySystemIme) compose.onNodeWithTag("v052-composer-transcript").performTouchInput { swipeUp(durationMillis = 500) }
            hideKeyboard()
            waitTag("composer-history-preview")
            assertCompactComposer()
        }
        record("${v.id}-composer-explicit-focus-request") {
            compose.runOnIdle { fixture.focusRequest++ }
            waitTag("composer-input")
            if (verifySystemIme) compose.waitUntil(5_000) { fixture.imeBottom > 0 }
            verify("trailing-focus-request-restores-same-draft") { compose.onNodeWithTag("composer-input").assertIsFocused().assertTextEquals(fixture.draft) }
            if (!verifySystemIme) compose.onNodeWithTag("v052-composer-transcript").performTouchInput { swipeUp(durationMillis = 500) }
            hideKeyboard(); waitTag("composer-history-preview"); assertCompactComposer()
        }
    }

    private fun assertCompactComposer() = verify("history-preview-visible-model-capsule-hidden-draft-preserved") {
        compose.onNodeWithTag("composer-history-preview").assertIsDisplayed()
            .assertTextEquals(fixture.draft.replace('\n', ' '))
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.onNodeWithTag("composer-input").assertDoesNotExist()
        compose.onNodeWithTag("composer-surface").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "紧凑"))
        assertEquals(0, fixture.draftChanges)
    }

    private fun exerciseLateReading() {
        hideKeyboard()
        compose.runOnIdle { fixture.variant = Variant("light-late", "light", 1f); fixture.page = Page.PlanFlow }
        record("plan-markdown-block-flow", screenshot = true) {
            fun views(view: View): List<TextView> = when (view) {
                is TextView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
                else -> emptyList()
            }
            compose.waitUntil(5_000) { compose.runOnIdle { views(compose.activity.window.decorView).any { "最后一段验收内容" in it.text } } }
            verify("separate-heading-bounds-no-overlap") { compose.runOnIdle {
                val blocks = views(compose.activity.window.decorView).filter { v -> listOf("Codex Pocket：阅读布局", "权限闭环", "设备验收").any { v.text.startsWith(it) } }
                assertEquals(3, blocks.size)
                blocks.zipWithNext().forEach { (before, after) ->
                    val a = IntArray(2); val b = IntArray(2)
                    before.getLocationInWindow(a); after.getLocationInWindow(b)
                    assertTrue(a[1] + before.height <= b[1])
                }
            } }
        }
        compose.runOnIdle { fixture.page = Page.Minimap }
        record("minimap-collapsed", screenshot = true) { waitTag("conversation-minimap") }
        record("minimap-expanded-navigation", screenshot = true) {
            compose.onNodeWithTag("conversation-minimap").performClick()
            compose.onNodeWithTag("conversation-minimap").performSemanticsAction(SemanticsActions.SetProgress) { it(.65f) }
            compose.waitForIdle()
            verify("seek-keeps-loaded-identity") { compose.onNodeWithText("已加载的对话 52\n原生记录").assertIsDisplayed() }
        }
    }

    private fun openInline() {
        waitTag("activity-file:file:0")
        compose.onNodeWithTag("activity-file:file:0").performScrollTo().performClick()
        waitTag("inline-diff:file:0")
        compose.onNodeWithTag("inline-diff:file:0").performScrollTo()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("    val mode = 1").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertDiffVisible() {
        compose.onNodeWithTag("diff-lines").assertIsDisplayed()
        compose.onNodeWithText("    val mode = 0").assertIsDisplayed()
        compose.onNodeWithText("    val mode = 1").assertIsDisplayed()
    }

    private fun back() {
        hideKeyboard()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun hideKeyboard() {
        compose.runOnIdle {
            val activity = compose.activity
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        if (::fixture.isInitialized) compose.waitUntil(5_000) { fixture.imeBottom == 0 }
    }

    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitAbsent(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    private fun verify(name: String, assertions: () -> Unit) {
        var failure: Throwable? = null
        try { assertions() }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            stageAssertions += buildJsonObject {
                put("name", name); put("passed", failure == null)
                failure?.let { put("failure", "${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    }

    private fun record(name: String, screenshot: Boolean = false, assertions: () -> Unit) {
        stage = name
        stageAssertions.clear()
        var failure: Throwable? = null
        val before = screenshots.size
        try {
            assertions()
            compose.waitForIdle()
            if (screenshot) capture(name)
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val entry = buildJsonObject {
                put("stage", name); put("passed", failure == null); put("theme", fixture.variant.theme)
                put("variant", fixture.variant.id); put("fontScale", fixture.variant.fontScale)
                put("assertions", JsonArray(stageAssertions))
                put("readingHistory", fixture.readingHistory); put("transcriptScrolling", fixture.transcriptScrolling)
                put("imeBottomPx", fixture.imeBottom); put("scrollTriggers", fixture.scrollTriggers)
                put("screenshots", JsonArray(screenshots.drop(before)))
                put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
                failure?.let { put("failure", "${it.javaClass.simpleName}: ${it.message}") }
            }
            stages += entry
            File(proofDirectory, "stages.jsonl").appendText("$entry\n")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("v052ControlsStage", entry.toString()) })
        }
    }

    private fun capture(name: String) {
        check(screenshots.size < screenshotLimit) { "Screenshot budget exhausted at $stage" }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latch = CountDownLatch(1)
        var result = -1
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val view = compose.activity.window.decorView
            check(view.width > 0 && view.height > 0)
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            // Like V051ReadingDeviceTest: capture the Activity window on any display and give
            // AndroidView Markdown plus the sibling overlay two window frames to reach PixelCopy.
            view.invalidate()
            view.postOnAnimation { view.postOnAnimation {
                PixelCopy.request(compose.activity.window, bitmap,
                    { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
            } }
        }
        try {
            check(latch.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "PixelCopy failed at $stage: $result" }
            val file = File(proofDirectory, "$name.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(file.length() > 0)
            screenshots += buildJsonObject {
                put("file", file.name); put("stage", stage); put("variant", fixture.variant.id)
                put("theme", fixture.variant.theme); put("fontScale", fixture.variant.fontScale)
                put("width", bitmap.width); put("height", bitmap.height); put("bytes", file.length())
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("v052ControlsScreenshot", file.absolutePath) })
        } finally {
            if (latch.count == 0L) bitmap.recycle() // A pending PixelCopy still owns the bitmap.
        }
    }
}
