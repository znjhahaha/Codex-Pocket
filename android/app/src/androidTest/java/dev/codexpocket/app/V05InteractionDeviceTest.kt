package dev.codexpocket.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in, local callback fixtures. No repository calls, network fixtures or model inference. */
@RunWith(AndroidJUnit4::class)
class V05InteractionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val screenshots = mutableListOf<String>()
    private lateinit var proofDirectory: File
    private enum class Page { Task, Reasoning, Composer, Questions }
    private class Fixture {
        var variant by mutableStateOf("light")
        var page by mutableStateOf(Page.Task)
        var compact by mutableStateOf(true)
        var menu by mutableStateOf(false)
        var selectedModel by mutableIntStateOf(0)
        var effort by mutableStateOf<String?>("medium")
        var acknowledged by mutableStateOf(false)
        var draft by mutableStateOf("已有草稿，继续核对布局")
        var stopped = 0
        var selected = 0
        var submitted = 0
        var lastAnswers: List<Pair<String, String>> = emptyList()
    }

    @Test fun localV05Interactions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("v05Interactions") == "true")
        proofDirectory = File(compose.activity.getExternalFilesDir(null), "v05-interaction-proof/${System.currentTimeMillis()}")
        check(proofDirectory.mkdirs())
        val fixture = Fixture()
        val models = listOf("桌面模型", "移动模型", "推理模型").mapIndexed { index, name ->
            NativeModel("v05-local-$index", "v05-local-$index", name, "本地目录样本",
                listOf("low", "medium", "high", "xhigh"), "medium", true, index == 0)
        }
        val timeline = NativeReducer.snapshot(pocketJson.parseToJsonElement("""{"turns":[{"id":"completed-fixture","status":"completed","durationMs":4000,"items":[
            {"id":"user","type":"userMessage","content":[{"type":"text","text":"完成界面审阅"}]},
            {"id":"comment","type":"agentMessage","phase":"commentary","text":"正在核对布局细节。"},
            {"id":"command","type":"commandExecution","status":"completed","command":"echo local-fixture","exitCode":0},
            {"id":"final","type":"agentMessage","phase":"final_answer","text":"界面审阅已完成。保留当前草稿。"}
        ]}]}""").jsonObject)
        val questions = QuestionBatches.blocking("v05-local-question", pocketJson.parseToJsonElement("""{"questions":[
            {"id":"client","header":"客户端","question":"优先使用哪个客户端？","isOther":true,"options":[{"label":"手机 (Recommended)","description":"先核对窄屏与触控交互"},{"label":"电脑","description":"检查桌面端布局"}]},
            {"id":"region","header":"范围","question":"使用哪组本地样本？","options":[{"label":"浅色","description":"普通文字大小"},{"label":"深色","description":"检查大字体布局"}]},
            {"id":"extra","header":"补充","question":"还有哪些界面细节需要保留？"}
        ]}""").jsonObject)
        compose.activity.runOnUiThread { compose.activity.setContent { FixtureScreen(fixture, models, timeline, questions) } }
        exerciseVariant(fixture, timeline, "light")
        compose.runOnIdle {
            fixture.variant = "dark-large"
            fixture.page = Page.Task
            fixture.compact = true
            fixture.menu = false
            fixture.selectedModel = 0
            fixture.effort = "medium"
            fixture.acknowledged = false
        }
        exerciseVariant(fixture, timeline, "dark-large")
        val report = buildJsonObject {
            put("passed", true); put("fixtureOnly", true); put("repositoryCallbacks", 0); put("modelRequests", 0)
            put("stopCallbacks", fixture.stopped); put("modelSelections", fixture.selected); put("questionSubmissions", fixture.submitted)
            put("completedProcess", true); put("separateReasoningParts", true); put("compactDraftAttachmentStop", true)
            put("discreteModelSearch", true); put("pagedQuestions", true); put("themes", buildJsonArray { add("light"); add("dark-large-1.4") })
            put("screenshots", buildJsonArray { screenshots.forEach { add(it) } })
        }
        File(proofDirectory, "report.json").writeText(report.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("v05InteractionProof", proofDirectory.absolutePath)
            putString("v05InteractionReport", report.toString())
        })
    }

    private fun exerciseVariant(fixture: Fixture, timeline: NativeTimeline, variant: String) {
        val stopBefore = fixture.stopped
        val selectBefore = fixture.selected
        val submitBefore = fixture.submitted
        val process = ConversationPresentation.rows(timeline).single { it.kind == TranscriptKind.Process }
        compose.onNodeWithTag("conversation-title", useUnmergedTree = true).assertTextEquals("界面审阅 · 已完成")
        compose.onNodeWithText("完成界面审阅").assertExists()
        waitNativeText("界面审阅已完成")
        assertFalse(nativeTexts().any { it.contains("正在核对布局细节") })
        capture("$variant-task-collapsed")
        compose.onNodeWithText(process.processLabel!!).performClick()
        waitNativeText("正在核对布局细节")
        compose.onNodeWithTag("fixture-transcript").performScrollToNode(hasTestTag("fixture-final"))
        waitNativeText("界面审阅已完成")
        capture("$variant-task-expanded")
        compose.runOnIdle { fixture.page = Page.Reasoning }
        compose.onNodeWithTag("live-reasoning-summary").assertDoesNotExist()
        compose.onNodeWithTag("live-reasoning-toggle").assertExists()
        capture("$variant-reasoning-collapsed")
        compose.onNodeWithTag("live-reasoning-toggle").performClick()
        compose.onNodeWithTag("native-summary-paragraph:0", useUnmergedTree = true).assertTextContains("第一段摘要", substring = true)
        compose.onNodeWithTag("native-summary-paragraph:1", useUnmergedTree = true).assertTextContains("第二段摘要", substring = true)
        assertFalse(nativeTexts().any { it.contains("第一段摘要") && it.contains("第二段摘要") })
        capture("$variant-reasoning-expanded")
        compose.onNodeWithTag("live-reasoning-toggle").performClick()
        compose.onNodeWithTag("live-reasoning-summary").assertDoesNotExist()
        compose.runOnIdle { fixture.page = Page.Composer }
        compose.onNodeWithTag("composer-history-preview").assertTextEquals(fixture.draft.replace('\n', ' '))
        compose.onNodeWithTag("composer-attachment-count").assertTextEquals("2")
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.onNodeWithTag("composer-surface").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "紧凑"))
        capture("$variant-composer-compact")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.stop_turn)).performClick()
        assertEquals(stopBefore + 1, fixture.stopped)
        compose.onNodeWithTag("composer-history-preview").assertTextEquals(fixture.draft.replace('\n', ' '))
        compose.runOnIdle { fixture.compact = false }
        compose.onNodeWithTag("composer-model").assertExists()
        capture("$variant-composer-expanded")
        compose.onNodeWithTag("composer-model").performClick()
        compose.onNodeWithTag("model-search").assertDoesNotExist()
        compose.onNodeWithTag("effort-track").performTouchInput { click(percentOffset(.75f, .5f)) }
        assertEquals("high", fixture.effort)
        capture("$variant-model-discrete")
        compose.onNodeWithTag("model-picker-open").performClick()
        compose.onNodeWithTag("model-search").performTextInput("移动")
        compose.onNodeWithTag("model-row-v05-local-1").assertExists()
        compose.onNodeWithTag("model-row-v05-local-0").assertDoesNotExist()
        capture("$variant-model-search")
        compose.onNodeWithTag("model-row-v05-local-1").performClick()
        assertEquals(selectBefore + 1, fixture.selected)
        assertEquals(1, fixture.selectedModel)
        compose.onNodeWithTag("model-search").assertDoesNotExist()
        hideKeyboard()
        compose.runOnIdle { fixture.page = Page.Questions }
        compose.onNodeWithTag("question-page").assertTextEquals("1/3")
        compose.onNodeWithText("推荐").assertExists()
        compose.onNodeWithTag("question-option:client:0").performClick()
        capture("$variant-question-first")
        compose.onNodeWithTag("question-next").performClick()
        compose.onNodeWithTag("question-page").assertTextEquals("2/3")
        compose.onNodeWithTag("question-option:region:1").performClick()
        capture("$variant-question-second")
        compose.onNodeWithTag("question-previous").performClick()
        compose.onNodeWithTag("question-option:client:0").assertIsSelected()
        compose.onNodeWithTag("question-next").performClick()
        compose.onNodeWithTag("question-next").performClick()
        compose.onNodeWithTag("question-page").assertTextEquals("3/3")
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        assertEquals(submitBefore, fixture.submitted)
        capture("$variant-question-invalid")
        compose.onNodeWithTag("question-answer:extra").performTextInput("保留已有草稿")
        hideKeyboard()
        compose.onNodeWithTag("question-submit").assertIsEnabled()
        assertEquals(submitBefore, fixture.submitted)
        capture("$variant-question-complete")
        compose.onNodeWithTag("question-submit").performClick()
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        assertEquals(submitBefore + 1, fixture.submitted)
        assertEquals(listOf("client", "region", "extra"), fixture.lastAnswers.map { it.first })
        assertEquals(listOf("手机 (Recommended)", "深色", "保留已有草稿"), fixture.lastAnswers.map { it.second })
        compose.runOnIdle { fixture.acknowledged = true }
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("question-status").fetchSemanticsNode().config[SemanticsProperties.Text]
                ?.any { it.text.contains("已提交") } == true
        }
        compose.onNodeWithTag("question-status").assertTextContains("已提交", substring = true)
        capture("$variant-question-submitted")
    }

    @Composable
    private fun FixtureScreen(fixture: Fixture, models: List<NativeModel>, timeline: NativeTimeline, questions: QuestionBatch) {
        val dark = fixture.variant == "dark-large"
        PocketTheme(if (dark) "dark" else "light") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark) 1.4f else 1f)) {
                ProvideMarkdownRendering { PocketBackdrop { key(fixture.variant) {
                    var anchor by remember { mutableStateOf<Rect?>(null) }
                    val chatKey = ThreadKey("fixture-pc", "fixture-store", "fixture-chat")
                    val title = if (fixture.page == Page.Task) "界面审阅 · 已完成" else "继续界面审阅"
                    val chat = ChatEntity(chatKey, "fixture-runtime", "desktop", title, "", "D:/fixture", 0,
                        if (fixture.page == Page.Composer) "inProgress" else "idle", false)
                    val runtime = RuntimeInfo("fixture-runtime", "desktop", "fixture", "fixture-store", true, emptySet(), setOf("turn/start"))
                    val s = PocketState(paired = true, online = true, selected = chat, computerName = "本地电脑",
                        runtimes = listOf(runtime), models = models, model = models[fixture.selectedModel].model, effort = fixture.effort,
                        modelCatalogState = ModelCatalogState.Ready, pushConfigured = false)
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Column(Modifier.fillMaxSize()) {
                            ConversationHeader(s, {}, {}, { fixture.menu = true })
                            when (fixture.page) {
                                Page.Task -> {
                                    var expansions by remember { mutableStateOf(emptySet<String>()) }
                                    val rows = expandTaskRows(ConversationPresentation.rows(timeline), expansions)
                                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("fixture-transcript"),
                                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(rows, key = { it.key }) { row ->
                                            Box(if (row.items.first().itemId == "final") Modifier.testTag("fixture-final") else Modifier) {
                                                TranscriptRowView(row, "v05:${fixture.variant}", row.key in expansions,
                                                    { expansions = if (row.key in expansions) expansions - row.key else expansions + row.key }, {})
                                            }
                                        }
                                    }
                                }
                                Page.Reasoning -> Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                    Text("分段原生摘要", style = MaterialTheme.typography.titleMedium)
                                    val reasoning = TimelineItem.from("reasoning-fixture", pocketJson.parseToJsonElement("""{"id":"reasoning","type":"reasoning","summary":["**第一段摘要**\n\n核对输入边界。","**第二段摘要**\n\n核对输出边界。"]}""").jsonObject)
                                    LiveTurnStatus("reasoning-fixture", ConversationPresentation.LiveStatus.Thinking, reasoning = reasoning)
                                }
                                Page.Composer -> {
                                    Column(Modifier.weight(1f).padding(20.dp)) {
                                        Text("运行中 · 保留已有草稿", style = MaterialTheme.typography.titleMedium)
                                        Text("附件入口显示数量，停止按钮只触发本地回调。", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    PocketComposer(fixture.draft, models[fixture.selectedModel].name, fixture.effort ?: "默认", true,
                                        ComposerAvailability(true, true, true, true), { fixture.draft = it }, { fixture.menu = true }, {}, {},
                                        { fail("Unexpected send callback") }, { fail("Unexpected steer callback") }, { fixture.stopped++ }, false,
                                        attachments = { Text("notes.txt · layout.png", style = MaterialTheme.typography.bodySmall) },
                                        readingHistory = fixture.compact, transcriptScrolling = fixture.compact, attachmentCount = 2,
                                        onModelAnchor = { anchor = it }, modelMenuExpanded = fixture.menu)
                                }
                                Page.Questions -> Surface(Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.surface) {
                                    val session = remember { QuestionSession(questions) }
                                    DisposableEffect(session) { onDispose { session.clear() } }
                                    session.observe(fixture.acknowledged, submitting = false)
                                    // Match QuestionDock: pass the observed phase as a Compose input.
                                    // Mutating plain session memory alone is not an invalidation signal.
                                    QuestionCard(questions, session, session.presentationPhase(QuestionPhase.Waiting), { answers ->
                                        fixture.lastAnswers = answers; fixture.submitted++
                                    }, Modifier.padding(12.dp))
                                }
                            }
                        }
                    }
                    if (fixture.menu) PocketAnchoredMenu("模型", { fixture.menu = false }, anchor, widthDp = 280f) {
                        ModelSheet(s, { model, effort ->
                            fixture.selectedModel = models.indexOf(model); fixture.effort = effort
                            fixture.selected++; fixture.menu = false
                        }, {}, onRetry = { fail("Unexpected catalog request") }, onEffort = { _, effort -> fixture.effort = effort })
                    }
                } } }
            }
        }
    }

    private fun nativeTexts(): List<String> = compose.runOnIdle { nativeText(compose.activity.window.decorView) }
    private fun nativeText(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { nativeText(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun waitNativeText(text: String) = compose.waitUntil(10_000) { nativeTexts().any { text in it } }

    private fun hideKeyboard() {
        compose.activity.runOnUiThread {
            compose.activity.currentFocus?.clearFocus()
            (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    /** Capture the tested Activity window, including when MuMu hosts it on a secondary display. */
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latch = CountDownLatch(1)
        var result = -1
        lateinit var bitmap: Bitmap
        compose.activity.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(compose.activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        }
        try {
            check(latch.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "Window capture failed: $result" }
            check(bitmap.width > 0 && bitmap.height > 0)
            val file = File(proofDirectory, "$name.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshots += file.name
            instrumentation.sendStatus(0, Bundle().apply { putString("v05InteractionScreenshot", file.absolutePath) })
        } finally { bitmap.recycle() }
    }
}
