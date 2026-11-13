package dev.codexpocket.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.PixelCopy
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.MonitoringInstrumentation
import androidx.test.runner.intercepting.InterceptingActivityFactory
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Run each method alone in a fresh process with assistantCollaborationDevice=true.
 * The outer rule blocks repository access and lifecycle reconnects before any Activity launch.
 * Only fixtures and captured callbacks are used; no DB, transport or model is initialized.
 * PixelCopy records the Activity's actual display; MuMu evidence is not physical-phone acceptance.
 */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class AssistantCollaborationDeviceTest {
    private val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: ComponentActivity
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var proof: File
    private lateinit var focus: FocusManager
    private var originalClip: ClipData? = null
    private var clipboardChanged = false
    private val actions = CopyOnWriteArrayList<AssistantAction>()
    private val opened = CopyOnWriteArrayList<Pair<String, String>>()
    private val repositoryAccesses = AtomicInteger()
    private val checks = mutableListOf<String>()
    private val captures = mutableListOf<JsonObject>()
    private val measurements = mutableListOf<JsonObject>()
    private var exits = 0
    private var stage = "isolation"
    private var theme by mutableStateOf("light")
    private var optics by mutableStateOf(true)
    private var fontScale by mutableFloatStateOf(1f)
    private var reducedMotion by mutableStateOf(false)
    private var mediaPage by mutableStateOf(false)
    private var state by mutableStateOf(populatedState())
    private val imageReads = CopyOnWriteArrayList<String>()
    private val refreshes = CopyOnWriteArrayList<String>()
    private val repaired = AtomicBoolean(false)
    private val imageBytes by lazy {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(202, 78, 62))
            ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
        } finally { bitmap.recycle() }
    }
    private val imageSource = object : ToolMediaImageSource {
        override suspend fun read(media: ToolMediaRef, offset: Long, maxBytes: Int): ToolMediaChunk {
            check(media.runtimeId == RUNTIME && media.threadId == THREAD)
            check(media.mediaId in setOf(READY_IMAGE, RETRY_IMAGE))
            imageReads += media.mediaId
            if (media.mediaId == RETRY_IMAGE && !repaired.get()) throw ToolMediaLoadException(ToolMediaStatus.Failed)
            val data = imageBytes.copyOfRange(offset.toInt(), minOf(imageBytes.size, offset.toInt() + maxBytes))
            return ToolMediaChunk(media.mediaId, ToolMediaStatus.Ready, "image/png", imageBytes.size.toLong(), offset,
                offset + data.size == imageBytes.size.toLong(), Base64.encodeToString(data, Base64.NO_WRAP))
        }
        override suspend fun refresh(media: ToolMediaRef, manual: Boolean) {
            check(media.mediaId == RETRY_IMAGE && manual)
            refreshes += media.mediaId
            repaired.set(true)
        }
    }

    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue("Opt in with assistantCollaborationDevice=true",
                InstrumentationRegistry.getArguments().getString("assistantCollaborationDevice") == "true")
            val app = instrumentation.targetContext.applicationContext as PocketApplication
            val field = PocketApplication::class.java.getDeclaredField("repository\$delegate").apply { isAccessible = true }
            val original = field.get(app) as Lazy<*>
            check(!original.isInitialized()) { "Use a fresh process; production repository is already initialized" }
            val runner = instrumentation as MonitoringInstrumentation
            val proofRun = InstrumentationRegistry.getArguments().getString("assistantCollaborationProofRun") ?: "isolated"
            check(proofRun.matches(Regex("[a-z0-9_-]+")))
            proof = File(checkNotNull(app.getExternalFilesDir(null)), "assistant-collaboration-proof/$proofRun/${description.methodName}")
                .apply { check(isDirectory || mkdirs()) }
            writeResult("running")
            instrumentation.runOnMainSync {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(app)
                field.set(app, lazy { repositoryAccesses.incrementAndGet(); error("Fixture attempted production repository access") })
            }
            try {
                runner.interceptActivityUsing(object : InterceptingActivityFactory {
                    override fun shouldIntercept(loader: ClassLoader, name: String, intent: Intent) = name == MainActivity::class.java.name
                    override fun create(loader: ClassLoader, name: String, intent: Intent): Activity = FixtureActivity { FixtureContent() }
                })
                base.evaluate()
                assertEquals("No repository access including teardown", 0, repositoryAccesses.get())
                assertFalse(original.isInitialized())
                assertEquals("Child-page back never exits fixture", 0, exits)
                writeResult("passed")
            } catch (failure: Throwable) {
                writeResult("failed", failure); throw failure
            } finally {
                runner.useDefaultInterceptingActivityFactory()
                instrumentation.runOnMainSync { field.set(app, original) }
                // Do not reattach while STARTED: that would reconnect. The isolated process ends.
            }
        }
    } }).around(compose)

    private class FixtureActivity(private val render: @Composable () -> Unit) : ComponentActivity() {
        private var contentMounted by mutableStateOf(false)
        fun mountContent() { contentMounted = true }
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState); enableEdgeToEdge()
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            // Mount the optical tree only after the blank fixture window is on its final display.
            setContent { if (contentMounted) render() }
        }
    }

    @Before fun launchIsolatedFixture() {
        stage = "setup_launch_blank"; writeResult("running")
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()
        scenario = ActivityScenario.launch<ComponentActivity>(Intent(instrumentation.targetContext, MainActivity::class.java), options)
        scenario.onActivity { activity = it }
        // MuMu creates a secondary display per app launch even when DEFAULT_DISPLAY is requested.
        // Compose's injected clicks and the emulator IME target display 0. Move only our intercepted
        // fixture task, then verify the real window; do not change global device/display settings.
        var before = -1
        scenario.onActivity { before = it.window.decorView.display?.displayId ?: -1 }
        stage = "setup_move_blank_display"; writeResult("running")
        if (before != android.view.Display.DEFAULT_DISPLAY) {
            var task = -1
            scenario.onActivity { task = it.taskId }
            val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am display move-stack $task ${android.view.Display.DEFAULT_DISPLAY}"))
                .bufferedReader().use { it.readText() }
            measurements += buildJsonObject { put("kind", "fixture_task_display_move"); put("taskId", task); put("beforeDisplayId", before); put("requestedDisplayId", 0); put("result", output) }
            compose.waitUntil(5_000) {
                var ready = false
                scenario.onActivity { activity = it; ready = it.window.decorView.display?.displayId == android.view.Display.DEFAULT_DISPLAY }
                ready
            }
        }
        var actualDisplay = -1
        scenario.onActivity { actualDisplay = it.window.decorView.display?.displayId ?: -1 }
        assertEquals("Fixture click/IME display must be the verified default display", 0,
            actualDisplay)
        stage = "setup_mount_content"; writeResult("running")
        scenario.onActivity { activity = it; (it as FixtureActivity).mountContent() }
        compose.waitForIdle()
        stage = "setup_content_ready"; writeResult("running")
    }
    @After fun closeIsolatedFixture() {
        if (clipboardChanged) compose.runOnIdle {
            val manager = clipboardManager()
            originalClip?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip()
        }
        if (::scenario.isInitialized) scenario.close()
    }

    @Composable private fun FixtureContent() {
        val baseDensity = LocalDensity.current
        val baseConfig = LocalConfiguration.current
        val context = LocalContext.current
        val config = remember(baseConfig, fontScale) { Configuration(baseConfig).apply { this.fontScale = this@AssistantCollaborationDeviceTest.fontScale } }
        val scaledContext = remember(context, config) { context.createConfigurationContext(config) }
        CompositionLocalProvider(LocalContext provides scaledContext, LocalConfiguration provides config,
            LocalDensity provides Density(baseDensity.density, fontScale), LocalPocketGlass provides optics,
            LocalPocketGlassPreferences provides if (reducedMotion) PocketGlassPreferences(false, false, false) else null,
            LocalToolMediaImages provides imageSource, LocalImageConnection provides (1L to false),
            LocalDesktopImages provides DesktopImageSource { error("Opaque media must not request desktop paths") }) {
            focus = LocalFocusManager.current
            PocketTheme(theme) { PocketBackdrop {
                if (mediaPage) Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("受控图片夹具：可读、单图重试、过期引用")
                    // SingleImage owns its retry row. A multi-image deck intentionally has no
                    // inline retry action; testing that row while the deck is collapsed was invalid.
                    listOf(READY_IMAGE, RETRY_IMAGE, EXPIRED_IMAGE).forEach { id ->
                        Column(Modifier.testTag("fixture-media:$id")) {
                            ToolMediaGrid(listOf(imageRef(id).let { if (id == EXPIRED_IMAGE) it.copy(status = ToolMediaStatus.Expired) else it }))
                        }
                    }
                } else AssistantScreen(state, emptyList(), emptyList(), listOf(
                    AssistantWorkspaceChoice(RUNTIME, THREAD, "研究员完整对话"),
                    AssistantWorkspaceChoice(RUNTIME, CHILD, "受控子代理", parentThreadId = THREAD, isSubagent = true)),
                    onAction = { actions += it }, onOpenThread = { r, t -> opened += r to t }, onBack = { exits++ }, nowMs = NOW)
            } }
        }
    }

    @Test fun preparationRetryKeepsDraftAndDoesNotSelectARuntime() = step("preparation_draft") {
        compose.runOnIdle { state = AssistantUiState(AssistantSnapshot(enabled = false)) }
        node(INPUT).performTextReplacement(DRAFT); hideKeyboard()
        node("assistant-prepare").performClick()
        assertEquals("assistant/prepare", actions.single().method); assertTrue(actions.single().params.isEmpty())
        assertDraft(DRAFT); capture("preparation-draft")
    }

    @Test fun completedQuestionIsCollapsedAndHasNoSubmissionAction() = step("completed_history") {
        val history = AssistantSnapshot.from(Json.parseToJsonElement("""{"questionHistory":[{"id":"history","operationId":"op","requestId":"q","atMs":9000,"item":{
          "type":"dynamicToolCall","tool":"request_user_input","status":"completed","success":true,
          "arguments":{"questions":[{"id":"q","question":"采用哪个方案？"}]},
          "contentItems":[{"type":"text","text":"{\"answers\":{\"q\":{\"answers\":[\"方案 A\"]}}}"}]
        }}]}""").jsonObject).questionHistory
        checkpoint("history_update_state")
        compose.runOnIdle { state = state.copy(snapshot = state.snapshot!!.copy(questionHistory = history,
            operations = listOf(state.snapshot!!.operations.single().copy(phase = AssistantOperationPhase.Completed)))) }
        checkpoint("history_scroll_collapsed")
        scroll("question-history-toggle:assistant:history")
        compose.onNodeWithText("已询问 1 个问题").assertIsDisplayed()
        compose.onNodeWithText("采用哪个方案？").assertDoesNotExist(); node("question-submit").assertDoesNotExist()
        capture("history-collapsed")
        checkpoint("history_expand")
        node("question-history-toggle:assistant:history").performClick()
        compose.onNodeWithText("回答：方案 A").assertExists()
        checkpoint("history_open_source")
        scroll("question-history-source:assistant:history"); node("question-history-source:assistant:history").performClick()
        assertEquals(listOf(RUNTIME to THREAD), opened); node("question-submit").assertDoesNotExist()
        assertTrue(actions.isEmpty()); capture("history-expanded")
    }

    @Test fun collapsedGroupMemberDetailsAndReturnPreserveDraftAndReadingState() = step("progressive_disclosure_and_return") {
        scroll(GROUP); node(TASK).assertDoesNotExist(); capture("group-collapsed")
        node(GROUP).performClick(); waitForNode(TASK); scroll(TASK); node(TASK).performClick()
        waitForNode("assistant-task-full-prompt:task")
        scroll("assistant-task-full-prompt:task"); node("assistant-task-full-prompt:task").assertTextEquals("核查资料并引用可验证来源")
        scroll(MEMBER); node(MEMBER).performClick(); waitForNode("assistant-native-agents:$THREAD")
        scroll("assistant-native-agents:$THREAD"); node("assistant-native-agents:$THREAD").performClick()
        scroll("assistant-native-thread:$CHILD"); node("assistant-native-thread:$CHILD").assertIsDisplayed()
        node(INPUT).performTextReplacement(DRAFT); hideKeyboard(); scroll("assistant-native-thread:$CHILD")
        val before = bounds("assistant-native-thread:$CHILD")
        capture("member-and-native-descendants")
        node("assistant-more").performClick(); node("assistant-tab:Memory").performClick()
        compose.onNodeWithText("保留阅读位置和草稿").assertIsDisplayed(); capture("memory-details")
        node("assistant-back").performClick(); assertDraft(DRAFT)
        node("assistant-native-thread:$CHILD").assertIsDisplayed()
        val after = bounds("assistant-native-thread:$CHILD")
        assertEquals("Reading position survives return", before.top, after.top, 2f)
        measurements += buildJsonObject { put("kind", "return_reading_position"); put("beforeTopPx", before.top); put("afterTopPx", after.top) }
        capture("returned-preserved-state")
        scroll(GROUP); node(GROUP).performClick(); node(TASK).assertDoesNotExist()
        node(GROUP).performClick(); waitForNode(TASK); scroll("assistant-native-thread:$CHILD"); node("assistant-native-thread:$CHILD").assertIsDisplayed()
        assertTrue(actions.isEmpty()); assertTrue(opened.isEmpty())
    }

    @Test fun hardwareGlassLightDarkLargeFontAndReducedMotion() = step("hardware_glass_and_appearance") {
        assertTrue("Hardware accelerated Activity required", compose.runOnIdle { activity.window.decorView.isHardwareAccelerated })
        for (mode in listOf("light", "dark")) {
            compose.runOnIdle { theme = mode; optics = true }
            node("assistant-chat-list").performScrollToIndex(4)
            node("assistant-chat-list").performTouchInput { swipeUp() }
            assertChrome(); capture("$mode-optics")
            compose.runOnIdle { optics = false }
            assertChrome(); capture("$mode-official-fallback")
        }
        compose.runOnIdle { optics = true; theme = "light"; fontScale = 1.4f; reducedMotion = true }
        assertChrome(); scroll(GROUP); node(GROUP).performClick(); waitForNode(TASK); scroll(TASK); node(TASK).performClick()
        waitForNode("assistant-task-full-prompt:task")
        scroll(MEMBER); node(MEMBER).performClick()
        compose.onNodeWithText("核对来源，保留成员署名").assertExists()
        node(INPUT).performTextReplacement(DRAFT); hideKeyboard(); assertDraft(DRAFT); capture("font-1_4-reduced-motion")
        compose.runOnIdle { fontScale = 1f; reducedMotion = false }
        assertChrome(); assertDraft(DRAFT); assertTrue(actions.isEmpty())
    }

    @Test fun sentLinkAndControlledImagesNeverConnectToRepository() = step("links_and_controlled_media") {
        compose.runOnIdle { state = state.copy(messages = listOf(AssistantChatMessage("link", "User", "查看 [验收原任务]($LINK)", 9500))) }
        scroll("assistant-message:link")
        compose.onNodeWithText("验收原任务", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("会话链接", useUnmergedTree = true).performClick()
        node("thread-reference-open").assertIsNotEnabled(); capture("link-label-and-menu")
        compose.runOnIdle { originalClip = clipboardManager().primaryClip; clipboardChanged = true }
        node("thread-reference-copy").performClick()
        compose.runOnIdle { assertEquals(LINK, clipboardManager().primaryClip?.getItemAt(0)?.text?.toString()) }
        compose.runOnIdle { mediaPage = true }
        node("fixture-media:$READY_IMAGE").assertExists()
        compose.waitUntil(10_000) { imageReads.contains(READY_IMAGE) && imageReads.contains(RETRY_IMAGE) }
        waitForNode("image-retry:$RETRY_IMAGE", unmerged = true)
        node("image-retry:$RETRY_IMAGE", unmerged = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("图片读取失败").assertExists()
        val readyReads = imageReads.count { it == READY_IMAGE }
        node("image-retry:$RETRY_IMAGE", unmerged = true).performClick()
        compose.waitUntil(10_000) { imageReads.count { it == RETRY_IMAGE } >= 2 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("图片读取失败").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(RETRY_IMAGE), refreshes)
        assertEquals("Other image not retried", readyReads, imageReads.count { it == READY_IMAGE })
        compose.onNodeWithText("图片引用已失效，点击重试").assertExists(); assertFalse(imageReads.contains(EXPIRED_IMAGE))
        capture("controlled-image-grid-after-retry")
        compose.onNode(hasContentDescription("图片 1") and hasAnyAncestor(hasTestTag("fixture-media:$READY_IMAGE")))
            .performScrollTo().performClick(); node("image-viewer").assertExists()
        // Gallery Dialog is a separate window; Activity PixelCopy cannot claim its screenshot.
        compose.onNodeWithContentDescription("关闭").performClick(); node("image-viewer").assertDoesNotExist()
        assertTrue(actions.isEmpty()); assertTrue(opened.isEmpty())
    }

    @Test fun realKeyboardDoesNotCoverComposer() = step("real_ime_geometry") {
        assertEquals("Runner must select the test-only system IME", "dev.codexpocket.app.test/dev.codexpocket.app.FixtureKeyboardService",
            android.provider.Settings.Secure.getString(instrumentation.targetContext.contentResolver, "default_input_method"))
        compose.runOnIdle { fontScale = 1.4f; reducedMotion = true }
        node(INPUT).performTextReplacement("保留第一行\n保留第二行草稿"); node(INPUT).performClick()
        compose.runOnIdle {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(activity.currentFocus, InputMethodManager.SHOW_IMPLICIT)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) { imeVisible() && imeHeight() >= 100 * activity.resources.displayMetrics.density }; node(INPUT).assertIsFocused()
        compose.waitUntil(5_000) { listOf(INPUT, "assistant-send", "assistant-tools").all { bounds(it).bottom <= keyboardTop() + 2f } }
        val top = keyboardTop()
        listOf(INPUT, "assistant-send", "assistant-tools").forEach { assertTrue("$it overlaps visible IME", bounds(it).bottom <= top + 2f) }
        val height = imeHeight()
        assertTrue("A positive on-screen keyboard must exist", height > 0 && top < bounds("assistant-screen").bottom)
        measurements += buildJsonObject { put("kind", "real_ime"); put("source", "test-only Android InputMethodService"); put("imeVisible", true); put("imeHeightPx", height); put("keyboardTopPx", top); put("composerBottomPx", bounds(INPUT).bottom) }
        capture("real-ime-font-1_4")
        val displayCapture = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Default display screenshot unavailable" }
        try { File(proof, "real-ime-display.png").outputStream().use { check(displayCapture.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { displayCapture.recycle() }
        hideKeyboard(); assertDraft("保留第一行\n保留第二行草稿")
        compose.runOnIdle { fontScale = 1f; reducedMotion = false }; assertTrue(actions.isEmpty())
    }

    private fun checkpoint(name: String) { stage = name; writeResult("running") }
    private fun step(name: String, block: () -> Unit) {
        checkpoint(name)
        try { block(); checks += name }
        catch (failure: Throwable) {
            runCatching { File(proof, "failure-semantics.txt").writeText(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString()) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            runCatching {
                for (service in listOf("input_method", "window windows")) {
                    val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys $service"))
                        .bufferedReader().use { it.readText() }
                    File(proof, "failure-${service.replace(' ', '-')}.txt").writeText(dump)
                }
                val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(proof, "failure-default-display.png").outputStream().use { check(screen.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { screen.recycle() }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { capture("failure") }.exceptionOrNull()?.let(failure::addSuppressed); throw failure
        }
    }
    private fun clipboardManager() = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    private fun node(tag: String, unmerged: Boolean = false) = compose.onNodeWithTag(tag, useUnmergedTree = unmerged)
    private fun waitForNode(tag: String, unmerged: Boolean = false) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().size == 1
    }
    private fun scroll(tag: String) {
        node("assistant-chat-list").performScrollToNode(hasTestTag(tag)); compose.waitForIdle()
        // The product intentionally scrolls behind floating chrome. Compose sees the full-height
        // LazyColumn viewport, so performScrollToNode alone can leave the hit point under the dock.
        val top = bounds("assistant-top-chrome").bottom + 12f
        val bottom = bounds("assistant-bottom-dock").top - 12f
        check(bottom > top) { "No unobscured chat viewport" }
        val center = bounds(tag).center.y
        // Land inside the visible region, not on a fractional-pixel clipping boundary.
        val delta = if (center !in top..bottom) center - (top + bottom) / 2f else 0f
        if (delta != 0f) {
            node("assistant-chat-list").performSemanticsAction(SemanticsActions.ScrollBy) { check(it(0f, delta)) }
            compose.waitForIdle()
        }
        val target = bounds(tag)
        assertTrue("$tag click point is covered by chrome/dock: $target, safe $top..$bottom", target.center.y in top..bottom)
        node(tag).assertIsDisplayed()
    }
    private fun bounds(tag: String): Rect {
        val node = node(tag).fetchSemanticsNode()
        return Rect(node.positionInWindow, Size(node.size.width.toFloat(), node.size.height.toFloat()))
    }
    private fun assertDraft(expected: String) = assertEquals(expected, node(INPUT).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    private fun assertChrome() {
        val screen = bounds("assistant-screen")
        listOf(INPUT, "assistant-send", "assistant-tools", "assistant-back", "assistant-more", "assistant-header-glass", "assistant-composer-glass").forEach {
            node(it).assertIsDisplayed(); val b = bounds(it)
            assertTrue("$it outside Activity", b.left >= screen.left - 2 && b.right <= screen.right + 2 && b.top >= screen.top - 2 && b.bottom <= screen.bottom + 2)
        }
        assertTrue(bounds("assistant-tools").right <= bounds(INPUT).left + 1); assertTrue(bounds(INPUT).right <= bounds("assistant-send").left + 1)
        measurements += buildJsonObject { put("kind", "chrome"); put("theme", theme); put("fontScale", fontScale); put("inputBottomPx", bounds(INPUT).bottom) }
    }
    private fun imeVisible() = compose.runOnIdle { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
    private fun imeHeight() = compose.runOnIdle { ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
    private fun keyboardTop() = compose.runOnIdle {
        val decor = activity.window.decorView
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor)); check(insets.isVisible(WindowInsetsCompat.Type.ime()))
        val visible = android.graphics.Rect().also(decor::getWindowVisibleDisplayFrame)
        val screen = IntArray(2).also(decor::getLocationOnScreen); val window = IntArray(2).also(decor::getLocationInWindow)
        val top = if (Build.VERSION.SDK_INT >= 30) minOf(visible.bottom, activity.windowManager.currentWindowMetrics.bounds.bottom - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom) else visible.bottom
        top.toFloat() - (screen[1] - window[1])
    }
    private fun hideKeyboard() {
        compose.runOnIdle {
            focus.clearFocus(force = true)
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) { !imeVisible() }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync { activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { frame.countDown() } } }
        check(frame.await(5, TimeUnit.SECONDS)) { "No Activity frame at $stage" }
        val copied = CountDownLatch(1)
        var result = -1; var display = -1; var hardware = false
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            display = decor.display?.displayId ?: -1; hardware = decor.isHardwareAccelerated
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        try {
            assertTrue("PixelCopy timeout", copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
            File(proof, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            captures += buildJsonObject {
                put("file", "$name.png"); put("displayId", display); put("hardwareAccelerated", hardware); put("apiLevel", Build.VERSION.SDK_INT)
                put("widthPx", bitmap.width); put("heightPx", bitmap.height); put("theme", theme); put("fontScale", fontScale)
                put("glassRequested", optics); put("reducedMotionFixture", reducedMotion); put("source", "Activity.window PixelCopy")
                put("excludesOtherWindows", "IME and Dialogs are not captured by Activity PixelCopy")
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("assistantCollaborationScreenshot", File(proof, "$name.png").absolutePath) })
        } finally { bitmap.recycle() }
    }
    private fun writeResult(status: String, failure: Throwable? = null) {
        File(proof, "result.json").writeText(buildJsonObject {
            put("status", status); put("stage", stage); put("fixtureOnly", true); put("repositoryAccessAttempts", repositoryAccesses.get())
            put("transport", "none"); put("modelExecution", "none"); put("storage", "in-memory fixture; suite screenshots/results only")
            put("checks", JsonArray(checks.map(::JsonPrimitive))); put("screenshots", JsonArray(captures)); put("measurements", JsonArray(measurements))
            put("capturedActions", buildJsonArray { actions.forEach { add(buildJsonObject { put("method", it.method); put("params", it.params) }) } })
            put("threadOpenCallbacks", opened.size); put("imageReads", JsonArray(imageReads.map(::JsonPrimitive))); put("manualRefreshes", JsonArray(refreshes.map(::JsonPrimitive)))
            put("appearanceScope", "Composition-local theme/font/motion; no persistent settings changed")
            put("untested", "physical phone, third-party keyboard compatibility, authenticated link navigation, real model/backend, actual media ownership/reconnect, gallery Dialog screenshot")
            failure?.let { put("failure", "${it.javaClass.name}: ${it.message}") }
        }.toString())
    }
    private fun imageRef(id: String) = ToolMediaRef(id, RUNTIME, THREAD, "fixture-turn", "fixture-image-$id", "image/png", imageBytes.size.toLong(), ToolMediaStatus.Ready)
    private fun populatedState() = AssistantUiState(AssistantSnapshot(enabled = true, runtimeId = RUNTIME, ready = true,
        tasks = listOf(AssistantTask("task", "核查资料并引用可验证来源", AssistantTaskStatus.Running, "op", false, null,
            AssistantAssignment("task", "member", "g", completionCriteria = "引用可验证"))),
        members = listOf(AssistantMember("member", "研究员", "核对来源，保留成员署名", "fixture-main", "D:/fixture", THREAD, true)),
        groups = listOf(AssistantGroup("g", "调整助理界面", listOf("task"), true, true, "running")),
        operations = listOf(AssistantOperation("op", "Execution", "task", AssistantOperationPhase.Running, false, THREAD, null, null)),
        events = listOf(AssistantEvent(1, "fixture-start", "op", "started", "", 9000, "task", "member", "g", THREAD)),
        memories = listOf(AssistantMemory("memory", "保留阅读位置和草稿"))),
        messages = List(18) { i -> AssistantChatMessage("fixture-$i", if (i % 2 == 0) "User" else "Assistant",
            "记录 ${i + 1}：核对来源与项目要求，保留完整对话并整理下一步工作。", 1000L + i * 200) })
    private companion object {
        const val RUNTIME = "fixture-desktop"
        const val THREAD = "01a1ca02-c5db-74a0-a1d5-cf3ef5eb0473"
        const val CHILD = "fixture-child"
        const val LINK = "codex://threads/$THREAD"
        const val INPUT = "assistant-message-input"
        const val GROUP = "assistant-group:g"
        const val TASK = "assistant-task:task"
        const val MEMBER = "assistant-member:task:member"
        const val DRAFT = "保留草稿，返回后继续核查"
        const val READY_IMAGE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val RETRY_IMAGE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val EXPIRED_IMAGE = "cccccccccccccccccccccccccccccccc"
        const val NOW = 1794400000000L
    }
}
