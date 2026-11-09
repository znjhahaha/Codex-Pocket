package dev.codexpocket.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
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
import java.util.concurrent.atomic.AtomicInteger

/** Local actual-component fixture. Network/pairing access is rejected before Activity launch. */
@RunWith(AndroidJUnit4::class)
class V054FeaturesDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val connections = AtomicInteger()
    private val pairingWrites = AtomicInteger()
    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Opt in with v054Features=true",
                    InstrumentationRegistry.getArguments().getString("v054Features") == "true")
                val application = instrumentation.targetContext.applicationContext as PocketApplication
                val field = PocketApplication::class.java.getDeclaredField("repository\$delegate").apply { isAccessible = true }
                val original = field.get(application) as Lazy<*>
                check(!original.isInitialized()) { "Run V054 alone in a fresh instrumentation process" }
                val database = Room.inMemoryDatabaseBuilder(application, PocketDatabase::class.java).build()
                var isolated: PocketRepository? = null
                instrumentation.runOnMainSync {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(application)
                    field.set(application, lazy {
                        PocketRepository(application,
                            connectionFactory = { connections.incrementAndGet(); error("Reading fixture attempted a connection") },
                            pairStore = object : PairingStorage {
                                override fun load(): PairedComputer? = null
                                override fun save(pair: PairedComputer) { pairingWrites.incrementAndGet(); error("Reading fixture attempted pairing") }
                                override fun forget() { pairingWrites.incrementAndGet(); error("Reading fixture attempted to clear pairing") }
                            }, db = database, networkAvailable = emptyFlow()
                        ).also { isolated = it }
                    })
                }
                try { base.evaluate() }
                finally {
                    try {
                        isolated?.let { repository ->
                            val scopeField = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
                            val scope = scopeField.get(repository) as CoroutineScope
                            runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]?.cancelAndJoin() } }
                        }
                    } finally {
                        database.close()
                        val deadline = SystemClock.elapsedRealtime() + 5_000
                        while (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
                        instrumentation.runOnMainSync {
                            field.set(application, original)
                            check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                "Fixture Activity did not stop; cannot restore reconnect observer"
                            }
                            ProcessLifecycleOwner.get().lifecycle.addObserver(application)
                        }
                    }
                }
            }
        }
    }).around(compose)


    @Test(timeout = 150_000) fun mediaQuestionsTablesAndHome() {
        val directory = File(compose.activity.getExternalFilesDir(null), "v054-features-proof").apply { mkdirs() }
        val images = (0..3).map { index ->
            val file = File(compose.activity.cacheDir, "v054-device-$index.png")
            val bmp = Bitmap.createBitmap(if (index % 2 == 0) 1000 else 120, if (index % 2 == 0) 120 else 1000, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(if (index % 2 == 0) 0xff65a5da.toInt() else 0xff82bea7.toInt())
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle()
            file
        }
        val batch = QuestionBatches.blocking("v054-question", pocketJson.parseToJsonElement("""
            {"questions":[{"id":"one","header":"交付范围","question":"接下来优先检查哪一项？","options":[{"label":"手机阅读","description":"检查图片、表格和摘要。"},{"label":"连接状态","description":"只读取现有状态。"}]},{"id":"two","question":"还需要检查什么？"}]}
        """).jsonObject)
        val session = QuestionSession(batch)
        var theme by mutableStateOf("light")
        var large by mutableStateOf(false)
        var section by mutableIntStateOf(0)
        var imageCount by mutableIntStateOf(2)
        val runtime = RuntimeInfo("fixture", "desktop", "fixture", "store", true, emptySet(), setOf("thread/start"))
        val state = PocketState(initialized = true, paired = true, online = true, computerId = "fixture", computerName = "本地验收", runtimes = listOf(runtime))
        instrumentation.runOnMainSync { compose.activity.setContent {
            val density = LocalDensity.current
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val fontScale = if (large) 1.3f else 1f
            val configured = remember(context, configuration, fontScale) {
                context.createConfigurationContext(android.content.res.Configuration(configuration).apply { this.fontScale = fontScale })
            }
            CompositionLocalProvider(LocalContext provides configured, LocalDensity provides Density(density.density, fontScale),
                androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity,
                androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides compose.activity,
                LocalDesktopImages provides DesktopImageSource { path -> delay(80); images.firstOrNull { it.path == path } }) {
                PocketTheme(theme) { PocketBackdrop { ProvideMarkdownRendering {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().widthIn(max = 380.dp).padding(16.dp).testTag("v054-features")) {
                            when(section) {
                                0 -> {
                                    Text("图片展开 · 原位保持", style = MaterialTheme.typography.titleMedium)
                                    ImageGrid(images.take(imageCount).map { MentionedFile(it.name, it.path, true) }, alignEnd = true)
                                    Text("后续正文位置保持稳定", Modifier.testTag("after-images"))
                                    val summary = TimelineItem.from("turn", buildJsonObject { put("id", "summary"); put("type", "reasoning")
                                        put("summary", buildJsonArray { add("Locating app executable · 正在定位应用") }) })
                                    LiveTurnStatus("turn", ConversationPresentation.LiveStatus.Working, reasoning = summary,
                                        timing = NativeTurnInfo("turn", status = "inProgress"))
                                    QuestionCard(batch, session, QuestionPhase.Waiting, onSubmit = { error("Fixture cannot send") })
                                }
                                1 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                                    MarkdownText("""# 阅读排版

中文与 English，`very_long_inline_code_identifier_for_wrapping_123456789`，复制保持原文。

| 实测项目与长说明 | AWS | 华为 |
| --- | --- | --- |
| HTTPS 首字节延迟以及换行后的内容 | 208ms | **123ms** |
| 下载 1MiB，三次中位数 | **0.40秒** | 2.71秒 |
| 长文本与超长路径 D:/project/folder/example/document.md | 内容正常显示且可以左右滚动 | 已完成 |
""", "v054-device-table")
                                }
                                2 -> PocketHomeFrame(state, HomeTab.Chats, 0, false, false, {}, {}, {}, {}) {
                                    Text("管理整合入口与右上角新建", Modifier.padding(LocalHomeContentPadding.current))
                                }
                                3 -> PairingConfirmationDialog("验收电脑", "本机", {}, true,
                                    onCancel = { section = 2 }, onConnect = { error("Fixture cannot pair") })
                                4 -> SettingsScreen(state.copy(extensions = ExtensionManagementState("fixture", "fixture",
                                        phase = ExtensionPhase.Unsupported, status = "update_required",
                                        error = "运行中的电脑桥接需要更新，完成电脑任务后重新打开 Codex")),
                                    (instrumentation.targetContext.applicationContext as PocketApplication).repository, management = true)
                                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                                    val user = TimelineItem.from("fixture-turn", buildJsonObject {
                                        put("id", "sent-time"); put("type", "userMessage"); put("startedAtMs", 1794145742534L)
                                        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "工具调用的显示手机端学习 Codex 桌面端一样") }) }) })
                                    TranscriptRowView(TranscriptRow(user.key, TranscriptKind.User, listOf(user)), "fixture", false, {}, {})
                                    val tool = TimelineItem.from("fixture-turn", buildJsonObject {
                                        put("id", "shell-output"); put("type", "commandExecution"); put("status", "completed")
                                        put("command", "Get-Content .local/validation.log -Tail 14"); put("cwd", "D:/codex2"); put("exitCode", 0); put("durationMs", 1300)
                                        put("aggregatedOutput", "> Task :app:compileReleaseKotlin\n> Task :app:testDebugUnitTest\n> Task :app:lintDebug\n\nBUILD SUCCESSFUL\n中文与 English 输出保持原文。") })
                                    TranscriptRowView(TranscriptRow(tool.key, TranscriptKind.Activity, listOf(tool), true), "fixture", true, {}, {})
                                }
                            }
                        }
                    }
                } } }
            }
        } }
        compose.waitForIdle()
        val recording = if (InstrumentationRegistry.getArguments().getString("recordWindow") == "true") NativeWindowRecording(compose.activity, directory) else null
        try {
            for (dark in listOf(false,true)) {
                compose.runOnIdle { theme = if(dark) "dark" else "light"; section = 0; large = false }
                for (count in listOf(2,4)) {
                    compose.runOnIdle { imageCount = count }
                    compose.waitForIdle()
                    val before = compose.onNodeWithTag("after-images").fetchSemanticsNode().boundsInRoot
                    repeat(6) {
                        compose.onNodeWithTag("image-deck-toggle").performClick()
                        compose.mainClock.advanceTimeBy(48)
                    }
                    compose.waitForIdle()
                    val after = compose.onNodeWithTag("after-images").fetchSemanticsNode().boundsInRoot
                    assertEquals(before.top, after.top, 1f)
                    compose.onNodeWithTag("image-deck-toggle").performClick(); compose.waitForIdle()
                    screenshot(directory, "${theme}-images-$count")
                    compose.onNodeWithTag("image-deck-toggle").performClick(); compose.waitForIdle()
                }
                compose.onNodeWithTag("question-option:one:0").performClick()
                compose.onNodeWithTag("question-next").performClick()
                compose.onNodeWithTag("question-previous").performClick()
                assertEquals("手机阅读", session.answer("one"))
                compose.runOnIdle { section = 1; large = dark }
                compose.waitForIdle(); SystemClock.sleep(300)
                screenshot(directory, "${theme}-table")
                compose.onNodeWithTag("markdown-table").performTouchInput { swipeLeft(durationMillis = 300) }
                compose.waitForIdle(); screenshot(directory, "${theme}-table-end")
            }
            compose.runOnIdle { section = 2; large = false }
            compose.onNodeWithTag("home-new-chat").assertExists()
            compose.onAllNodesWithTag("home-new-chat").assertCountEquals(1)
            compose.onNodeWithText("管理").assertExists()
            screenshot(directory, "home-management")
            compose.runOnIdle { section = 3 }
            compose.onNodeWithTag("pair-confirm-computer").assertTextEquals("验收电脑")
            screenshot(directory, "scan-confirmation")
            compose.onNodeWithTag("pair-confirm-cancel").performClick()
            compose.onNodeWithTag("pair-confirm-connect").assertDoesNotExist()
            compose.runOnIdle { section = 4 }
            compose.onNodeWithTag("settings-tab:Connection").assertExists()
            screenshot(directory, "management-connection")
            compose.onNodeWithTag("settings-tab:Extensions").performClick()
            compose.waitForIdle(); screenshot(directory, "management-extensions")
            compose.onNodeWithTag("settings-tab:App").performClick()
            compose.waitForIdle(); screenshot(directory, "management-settings")
            for (dark in listOf(false, true)) {
                compose.runOnIdle { section = 5; theme = if (dark) "dark" else "light" }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("activity-toggle:[\"fixture-turn\",\"shell-output\"]").fetchSemanticsNodes().isNotEmpty() }
                if (compose.onAllNodesWithTag("tool-result-frame").fetchSemanticsNodes().isEmpty())
                    compose.onNodeWithTag("activity-toggle:[\"fixture-turn\",\"shell-output\"]").performClick()
                compose.waitForIdle(); screenshot(directory, "${theme}-tool-frame-time")
            }
            assertEquals(0,connections.get()); assertEquals(0,pairingWrites.get())
            File(directory,"report.json").writeText("""{"passed":true,"newModelRequests":0,"pairingWrites":0,"imageCounts":[2,4],"themes":["light","dark"],"deckReversals":24}""")
        } finally { recording?.close(); images.forEach { it.delete() } }
    }
    @Test(timeout = 90_000) fun compactErrorPresentation() {
        val directory = File(compose.activity.getExternalFilesDir(null), "v054-compact-errors-proof").apply { mkdirs() }
        var theme by mutableStateOf("light")
        val html = "unexpected status code 522: upstream_status: HTTP 522; cause: <!DOCTYPE html>\n" +
            "<html><head><title>522 Connection timed out</title></head><body>upstream error</body></html>\n".repeat(400)
        val failed = TimelineItem("failed-turn", "failure", "error", html, "failed", complete = true)
        val retry = TimelineItem("active-turn", "retry", "error", "stream disconnected before completion: stream closed before response.completed",
            raw = buildJsonObject { put("params", buildJsonObject { put("willRetry", true) }) })
        val timeline = NativeTimeline(linkedMapOf(failed.key to failed, retry.key to retry), activeTurn = "active-turn",
            completedTurns = setOf("failed-turn"))
        instrumentation.runOnMainSync { compose.activity.setContent {
            PocketTheme(theme) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (theme == "dark") 1.3f else 1f)) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("错误提示 · 紧凑阅读", style = MaterialTheme.typography.titleMedium)
                            TranscriptRowView(TranscriptRow(failed.key, TranscriptKind.Error, listOf(failed)), "device-error", false, {}, {}, timeline = timeline)
                            TranscriptRowView(TranscriptRow(retry.key, TranscriptKind.Error, listOf(retry)), "device-error", false, {}, {}, timeline = timeline)
                            Text("后续正文继续保持可读。", Modifier.testTag("after-error"))
                        }
                    }
                }
            }
        } }
        for (dark in listOf(false, true)) {
            compose.runOnIdle { theme = if (dark) "dark" else "light" }
            compose.onNodeWithText("上游连接超时 · HTTP 522").assertIsDisplayed()
            compose.onNodeWithText("正在重新连接").assertIsDisplayed()
            compose.onNodeWithText("<!DOCTYPE", substring = true).assertDoesNotExist()
            assertTrue(compose.onNodeWithTag("conversation-error:device-error${failed.key}").getUnclippedBoundsInRoot().let { it.bottom - it.top } <= 100.dp)
            screenshot(directory, "$theme-collapsed")
            compose.onNodeWithTag("error-toggle:device-error${failed.key}").performClick()
            compose.onNodeWithTag("error-detail:device-error${failed.key}").assertIsDisplayed()
            screenshot(directory, "$theme-expanded")
            val detailsHeight = compose.onNodeWithTag("error-detail:device-error${failed.key}").getUnclippedBoundsInRoot().let { it.bottom - it.top }
            assertTrue("Expanded detail height $detailsHeight", detailsHeight <= 300.dp)
            compose.onNodeWithTag("after-error").assertIsDisplayed()
            compose.onNodeWithTag("error-toggle:device-error${failed.key}").performClick()
        }
        assertEquals(0, connections.get()); assertEquals(0, pairingWrites.get())
        File(directory, "report.json").writeText("""{"passed":true,"themes":["light","dark-large"],"modelRequests":0,"connections":0,"collapsedMaxHeightDp":100,"expandedMaxHeightDp":300}""")
    }

    private fun screenshot(directory: File, name: String) {
        val latch=CountDownLatch(1)
        var result=-1
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val decor=compose.activity.window.decorView
            bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
            PixelCopy.request(compose.activity.window, bitmap,{result=it;latch.countDown()},Handler(Looper.getMainLooper()))
        }
        assertTrue(latch.await(5,TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS,result)
        File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
