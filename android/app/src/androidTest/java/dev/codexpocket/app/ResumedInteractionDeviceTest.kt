package dev.codexpocket.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
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

/** Isolated callback-only screens: no turn/start, steer, queue or any repository command. */
@RunWith(AndroidJUnit4::class)
class ResumedInteractionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun localInteractions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localInteractions") == "true")
        val models = (1..10).map { i -> NativeModel("fixture-$i", "fixture-$i", if (i == 1) "本地模型 · 长名称布局样本" else "本地模型 $i",
            "本地样本", listOf("low", "medium", "high", "xhigh"), "medium", true, i == 1) }
        val key = ThreadKey("fixture", "fixture", "fixture")
        val chat = ChatEntity(key, "fixture", "desktop", "本地交互样本", "", "D:/fixture", 0, "inProgress", false)
        val runtime = RuntimeInfo("fixture", "desktop", "fixture", "fixture", true, emptySet(), setOf("collaborationMode/list", "turn/start"))
        val summary = TimelineItem("local-turn", "reasoning", "reasoning", "**检查布局与交互**\n\n保留连续正文，核对模型菜单、状态和子任务的展示。")
        val agent = TimelineItem.from("local-turn", pocketJson.parseToJsonElement("""{"id":"agent","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","receiverThreadIds":["local-child-1","local-child-2"],"agentsStates":{"local-child-1":{"name":"布局审阅","status":"running","message":"检查窄屏和大字体"},"local-child-2":{"name":"文件审阅","status":"completed","message":"检查完成"}}}""").jsonObject)
        var dark by mutableStateOf(false)
        var large by mutableStateOf(false)
        var scrolling by mutableStateOf(false)
        var details by mutableStateOf(false)
        var openedChild: String? = null
        var selected by mutableStateOf(models.first())
        var effort by mutableStateOf<String?>(null)
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme(if (dark) "dark" else "light") {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.4f else 1f)) {
                    ProvideMarkdownRendering { PocketBackdrop {
                        var menu by remember { mutableStateOf(false) }
                        var anchor by remember { mutableStateOf<Rect?>(null) }
                        if (details) AgentDetailsScreen(agent, { details = false }, setOf("local-child-1")) { openedChild = it }
                        else Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("本地界面样本 · 不发送请求", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("继续处理界面细节", style = MaterialTheme.typography.titleLarge)
                                Text("正文和代码保持稳定。模型控件在滚动时收起，原生摘要按需展开。")
                                TranscriptRowView(TranscriptRow("agents", TranscriptKind.Activity, listOf(agent)), "local", true, {}, { details = true })
                                LiveTurnStatus("local-turn", ConversationPresentation.LiveStatus.Thinking, reasoning = summary,
                                    observedAtMillis = android.os.SystemClock.elapsedRealtime() - 65_000)
                            }
                            Box(Modifier.align(Alignment.BottomCenter)) {
                                PocketComposer("", selected.name, effort ?: "中", false, ComposerAvailability(false, false, false, false), {}, { menu = true }, {}, {}, {}, {}, {}, false,
                                    onModelAnchor = { anchor = it }, modelMenuExpanded = menu, transcriptScrolling = scrolling)
                            }
                        }
                        if (menu) PocketAnchoredMenu("模型", { menu = false }, anchor, widthDp = 264f) {
                            ModelSheet(PocketState(paired = true, online = true, selected = chat, runtimes = listOf(runtime), models = models,
                                model = selected.model, effort = effort, modelCatalogState = ModelCatalogState.Ready),
                                { model, level -> selected = model; effort = level; menu = false }, {}, onEffort = { _, level -> effort = level })
                        }
                    } }
                }
            }
        } }
        compose.onNodeWithTag("live-reasoning-summary").assertExists()
        capture("thinking-light")
        compose.onNodeWithTag("live-reasoning-toggle").performClick()
        compose.onNodeWithTag("live-reasoning-summary").assertDoesNotExist()
        compose.onNodeWithTag("live-reasoning-toggle").performClick()
        compose.runOnIdle { scrolling = true }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        capture("scroll-hidden")
        compose.runOnIdle { scrolling = false }
        compose.onNodeWithTag("composer-model").performClick()
        capture("model-effort")
        compose.onNodeWithTag("model-picker-entry").performClick()
        compose.onNodeWithTag("model-search").performTextInput("本地模型 9")
        capture("model-search")
        compose.onNode(hasText("本地模型 9") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.RadioButton)).performClick()
        assertEquals("fixture-9", selected.id)
        compose.runOnIdle { details = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("open-agent:local-child-1").fetchSemanticsNodes().isNotEmpty() }
        capture("agents")
        compose.onNodeWithTag("open-agent:local-child-1").performScrollTo().performClick()
        assertEquals("local-child-1", openedChild)
        compose.onNodeWithTag("open-agent:local-child-2").assertDoesNotExist()
        compose.runOnIdle { details = false; dark = true; large = true }
        compose.onNodeWithTag("live-reasoning-summary").assertExists()
        capture("thinking-dark-large")
        writeLiveReport("resumed-report.json", buildJsonObject {
            put("passed", true); put("fixtureOnly", true); put("modelRequests", 0)
            put("screenshots", 6); put("reasoningFold", true); put("scrollHide", true); put("modelSearchSelect", true); put("directoryGatedAgent", true)
        })
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val latch = CountDownLatch(1)
        var result = -1
        lateinit var bitmap: Bitmap
        compose.activity.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(compose.activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        }
        check(latch.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "Window capture failed: $result" }
        val directory = File(compose.activity.getExternalFilesDir(null), "resumed-interaction-proof").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
