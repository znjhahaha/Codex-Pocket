package dev.codexpocket.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import dev.codexpocket.app.ui.AssistantScreen
import dev.codexpocket.app.ui.LocalPocketGlass
import dev.codexpocket.app.ui.PocketTheme
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
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Run this class alone in a fresh instrumentation process with assistantChatDevice=true.
 * Only AssistantScreen and immutable fixture data are used: no repository, transport, DB or model.
 * PixelCopy targets the Activity window on its actual MuMu display, never UiAutomation's display.
 * The parent run owns all build/install/device commands.
 */
@Suppress("DEPRECATION") // Runner 1.7 supports this factory without adding a manifest Activity.
@RunWith(AndroidJUnit4::class)
class AssistantChatDeviceTest {
    private val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: ComponentActivity
    private lateinit var focus: FocusManager
    private lateinit var proofDirectory: File
    private val actions = CopyOnWriteArrayList<AssistantAction>()
    private val openedThreads = CopyOnWriteArrayList<Pair<String, String>>()
    private val repositoryAccesses = AtomicInteger()
    private val checks = mutableListOf<String>()
    private val screenshots = mutableListOf<JsonObject>()
    private val measurements = mutableListOf<JsonObject>()
    private var exits = 0
    private var stage = "isolation"
    private var theme by mutableStateOf("light")
    private var fontScale by mutableFloatStateOf(1f)
    private var narrow by mutableStateOf(false)
    private var online by mutableStateOf(true)
    private var state by mutableStateOf(populatedState())

    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                // Gate BEFORE the Compose rule and BEFORE any Activity/repository initialization.
                assumeTrue("Opt in with assistantChatDevice=true",
                    InstrumentationRegistry.getArguments().getString("assistantChatDevice") == "true")
                val app = instrumentation.targetContext.applicationContext as PocketApplication
                val field = PocketApplication::class.java.getDeclaredField("repository\$delegate")
                    .apply { isAccessible = true }
                val original = field.get(app) as Lazy<*>
                check(!original.isInitialized()) { "Run this class alone in a fresh instrumentation process" }
                val runner = instrumentation as MonitoringInstrumentation
                proofDirectory = File(checkNotNull(app.getExternalFilesDir(null)),
                    if(description.methodName=="realKeyboardDoesNotCoverComposer") "assistant-chat-keyboard-proof" else "assistant-chat-device-proof")
                    .apply { check(isDirectory || mkdirs()) }
                writeResult("running")
                // Same outer-rule isolation boundary as ContinuationDeviceTest, but no repo is
                // needed here. Any accidental application/repository access fails before disk/network.
                val forbiddenRepository = lazy {
                    repositoryAccesses.incrementAndGet()
                    error("AssistantScreen fixture must never initialize PocketApplication.repository")
                }
                instrumentation.runOnMainSync {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(app)
                    field.set(app, forbiddenRepository)
                }
                try {
                    runner.interceptActivityUsing(object : InterceptingActivityFactory {
                        override fun shouldIntercept(loader: ClassLoader, name: String, intent: Intent) =
                            name == MainActivity::class.java.name
                        override fun create(loader: ClassLoader, name: String, intent: Intent): Activity =
                            FixtureActivity { FixtureContent() }
                    })
                    base.evaluate()
                    assertEquals("No repository access, even during teardown", 0, repositoryAccesses.get())
                    assertFalse(original.isInitialized())
                    assertTrue("No deep links were opened", openedThreads.isEmpty())
                    assertEquals("Child-page back must not exit the fixture", 0, exits)
                    writeResult("passed")
                } catch (failure: Throwable) {
                    writeResult("failed", failure)
                    throw failure
                } finally {
                    runner.useDefaultInterceptingActivityFactory()
                    instrumentation.runOnMainSync { field.set(app, original) }
                    // As in ContinuationDeviceTest, leave the observer detached until this isolated
                    // instrumentation process ends: reattaching while STARTED would reconnect.
                }
            }
        }
    }).around(compose)

    private class FixtureActivity(private val render: @Composable () -> Unit) : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            enableEdgeToEdge()
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setContent(content = render)
        }
    }

    @Composable private fun FixtureContent() {
        val baseDensity = LocalDensity.current
        val baseConfig = LocalConfiguration.current
        val context = LocalContext.current
        val config = remember(baseConfig, fontScale, narrow) {
            Configuration(baseConfig).apply {
                this.fontScale = this@AssistantChatDeviceTest.fontScale
                if (narrow) screenWidthDp = 360
            }
        }
        // Override only this composition/context; never write the device's font/display settings.
        val scaledContext = remember(context, config) { context.createConfigurationContext(config) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            CompositionLocalProvider(
                LocalContext provides scaledContext,
                LocalConfiguration provides config,
                LocalDensity provides Density(baseDensity.density, fontScale),
                LocalPocketGlass provides false,
            ) {
                focus = LocalFocusManager.current
                key(theme, fontScale, narrow) {
                    PocketTheme(theme) {
                        AssistantScreen(
                            state, emptyList(), listOf(AssistantRuntimeChoice(RUNTIME, "我的电脑", online)),
                            listOf(AssistantWorkspaceChoice(RUNTIME, PROJECT, "Codex Pocket", true)),
                            onAction = { actions += it },
                            onOpenThread = { runtime, thread -> openedThreads += runtime to thread },
                            onBack = { exits++ },
                            modifier = if (narrow) Modifier.width(360.dp).fillMaxHeight() else Modifier.fillMaxSize(),
                            nowMs = NOW,
                        )
                    }
                }
            }
        }
    }

    @Test(timeout = 180_000) fun deterministicAssistantChatAcceptance() {
        // The registered manifest component is intercepted before MainActivity.onCreate runs.
        // Request the default display; report the actual display because emulators may override it.
        val displayOptions=ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()
        ActivityScenario.launch<ComponentActivity>(Intent(instrumentation.targetContext, MainActivity::class.java),displayOptions).use { activityScenario ->
            activityScenario.onActivity { activity = it }
            try {
                scenario("light_populated_chat") {
                    waitForTail()
                    waitForMarkdown("待办已分成三项")
                    assertBubbleGeometry()
                    assertComposerGeometry()
                    capture("01-light-chat")
                }
                scenario("tools_project_memory_back_preserves_draft") { navigation() }
                scenario("send_once_waits_for_explicit_ack") { sending() }
                scenario("approval_is_explicit_and_gated") { approvals() }
                scenario("history_new_message_and_long_markdown") { history() }
                scenario("dark_and_360dp_large_text") { appearance() }
            } catch (failure: Throwable) {
                runCatching { capture("failure") }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }

    /** Separate hard assertion: an emulator with no IME on its app display cannot pass this gate. */
    @Test(timeout = 60_000) fun realKeyboardDoesNotCoverComposer() {
        val options=ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()
        ActivityScenario.launch<ComponentActivity>(Intent(instrumentation.targetContext,MainActivity::class.java),options).use { scenario ->
            scenario.onActivity {activity=it}
            stage="real_keyboard_geometry"
            try {appearance();verifyRealKeyboard();checks+="real_keyboard_geometry"}
            catch(failure:Throwable) {runCatching {capture("failure")};throw failure}
        }
    }

    private fun scenario(name: String, block: () -> Unit) {
        stage = name
        block()
        checks += name
        writeResult("running")
    }

    private fun navigation() {
        node("assistant-tools").performClick()
        node("assistant-tools-panel").assertIsDisplayed()
        listOf("项目", "任务", "记忆", "定时").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        assertComposerGeometry()
        capture("02-tools")
        node("assistant-workspace").performClick()
        node("assistant-workspace:$PROJECT").assertIsDisplayed().performClick()
        node("assistant-workspace").assertTextContains("Codex Pocket")
        node(INPUT).performTextReplacement(DRAFT)
        hideKeyboard()
        node("assistant-more").performClick()
        node("assistant-tab:Memory").performClick()
        compose.onNodeWithText("待办按优先级整理，先给结论。").assertIsDisplayed()
        capture("03-memory")
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertDraft(DRAFT)
        node("assistant-workspace").assertTextContains("Codex Pocket")
        assertEquals(0, exits)
        assertTrue("Navigation and choosing a project never dispatch", actions.isEmpty())
    }

    private fun sending() {
        node(SEND).assertIsEnabled().performClick()
        assertEquals(1, actions.size)
        assertEquals("assistant/send", actions.single().method)
        assertEquals(DRAFT, actions.single().params.string("message"))
        assertEquals(PROJECT, actions.single().params.string("threadId"))
        assertDraft(DRAFT)
        node(SEND).assertIsNotEnabled().performTouchInput { click(); click() }
        compose.runOnIdle { state = state.copy(pendingActions = setOf("assistant/send")) }
        assertDraft(DRAFT)
        compose.runOnIdle { state = state.copy(pendingActions = emptySet()) }
        // Merely ending 'busy' is NOT an acknowledgement.
        node(SEND).assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)
        assertDraft(DRAFT)
        capture("04-send-awaiting-ack")
        acknowledgeSend()
        assertDraft("")
        node(SEND).assertIsNotEnabled()

        // A later acknowledgement must not erase edits made while the previous send is in flight.
        node(INPUT).performTextReplacement("请先核对待办")
        hideKeyboard()
        node(SEND).performClick()
        node(INPUT).performTextReplacement("这段是还没发送的新草稿")
        hideKeyboard()
        acknowledgeSend()
        assertDraft("这段是还没发送的新草稿")
        assertEquals(2, actions.count { it.method == "assistant/send" })
        node(INPUT).performTextClearance()
        hideKeyboard()
    }

    private fun approvals() {
        val request = AssistantQuestion("approval-1", AssistantQuestionKind.Approval,
            """{"command":"更新 docs/TODO.md","reason":"将已整理的待办写入项目文档","availableDecisions":["accept","decline"]}""")
        val operation = AssistantOperation("approval", "Execution", null, AssistantOperationPhase.Waiting,
            false, THREAD, request, null)
        fun install(op: AssistantOperation = operation, pending: Set<String> = emptySet()) {
            compose.runOnIdle {
                state = state.copy(pendingActions = pending, snapshot = state.snapshot!!.copy(
                    operations = listOf(runningOperation(), op),
                    events = state.snapshot!!.events.filterNot { it.operationId == "approval" } +
                        AssistantEvent(9, "approval-event", "approval", "waiting", "等待确认", NOW + 1_000),
                ))
            }
            node("assistant-chat-list").performScrollToNode(hasTestTag("assistant-question:approval"))
        }
        fun disabled() {
            val before = actions.size
            node("assistant-approve:approval").assertIsNotEnabled().performTouchInput { click() }
            node("assistant-deny:approval").assertIsNotEnabled().performTouchInput { click() }
            assertEquals("Disabled decisions cannot dispatch", before, actions.size)
        }
        install()
        node("assistant-approve:approval").assertIsEnabled()
        node("assistant-deny:approval").assertIsEnabled()
        assertInside("assistant-approve:approval", bounds("assistant-chat-list"))
        assertInside("assistant-deny:approval", bounds("assistant-chat-list"))
        capture("05-approval")
        // An ordinary chat saying 'agree' must never be turned into a decision.
        val beforeChat = actions.size
        node(INPUT).performTextReplacement("同意，先给我看看计划")
        hideKeyboard()
        node(SEND).performClick()
        assertEquals(beforeChat + 1, actions.size)
        assertEquals("assistant/send", actions.last().method)
        assertEquals(0, actions.count { it.method == "assistant/respond" })
        acknowledgeSend()
        install(pending = setOf("assistant/respond:approval")); disabled()
        install(operation.copy(suspended = true)); disabled()
        install(operation.copy(phase = AssistantOperationPhase.Unknown)); disabled()
        install()
        compose.runOnIdle { online = false }
        disabled()
        compose.runOnIdle { online = true }
        node("assistant-approve:approval").assertIsEnabled().performClick()
        assertDecision("approval-1", true)
        install(pending = setOf("assistant/respond:approval")); disabled()
        // A different explicitly supplied request is needed for the separate deny exercise.
        install(operation.copy(question = request.copy(requestId = "approval-2")))
        node("assistant-deny:approval").assertIsEnabled().performClick()
        assertDecision("approval-2", false)
        assertEquals(2, actions.count { it.method == "assistant/respond" })
    }

    private fun history() {
        compose.runOnIdle { state = populatedState().copy(sendAcknowledgement = state.sendAcknowledgement) }
        node("assistant-chat-list").performScrollToNode(hasTestTag("assistant-message:history-12"))
        // A real drag establishes reading intent; a programmatic scroll can miss that contract.
        node("assistant-chat-list").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(centerX,height*.4f),
                androidx.compose.ui.geometry.Offset(centerX,height*.55f),durationMillis=600)
        }
        node("assistant-latest").assertIsDisplayed()
        val viewport = bounds("assistant-chat-list")
        val anchor = compose.onAllNodes(SemanticsMatcher("visible history bubble") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("assistant-message:history-") == true
        }).fetchSemanticsNodes().firstOrNull {
            val b = rawBounds(it)
            b.top >= viewport.top && b.bottom <= viewport.bottom && b.height > 0
        } ?: error("A full history bubble must be visible after dragging")
        val tag = anchor.config[SemanticsProperties.TestTag]
        val top = rawBounds(anchor).top
        compose.runOnIdle {
            state = state.copy(messages = state.messages + AssistantChatMessage("new-arrival", "Assistant",
                "补充：已把键盘和大字检查加入待办。", NOW + 20_000))
        }
        compose.onNodeWithText("有新消息 · 回到最新").assertIsDisplayed()
        assertEquals("New arrivals must not move the history being read", top, bounds(tag).top, 3f)
        node("assistant-message:new-arrival").assertIsNotDisplayed()
        capture("06-history-new-message")
        node("assistant-latest").performClick()
        node("assistant-message:new-arrival").assertIsDisplayed()
        node("assistant-chat-list").performScrollToNode(hasTestTag("assistant-message:markdown"))
        waitForMarkdown("待办整理说明")
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                textViews(activity.window.decorView).any { view ->
                    val text = view.text as? Spanned
                    text != null && text.contains("项目原对话") &&
                        text.getSpans(0, text.length, ClickableSpan::class.java).isNotEmpty()
                }
            }
        }
        capture("07-long-markdown")
        assertTrue("Rendered deep link is not activated", openedThreads.isEmpty())
    }

    private fun appearance() {
        compose.runOnIdle { theme = "dark"; state = populatedState() }
        waitForTail()
        waitForMarkdown("待办已分成三项")
        assertBubbleGeometry()
        assertComposerGeometry()
        capture("08-dark-chat")
        compose.runOnIdle { theme = "light"; fontScale = 1.4f; narrow = true }
        waitForTail()
        val density = compose.runOnIdle { activity.resources.displayMetrics.density }
        assertEquals("Constrained viewport really measures 360dp", 360f,
            bounds("assistant-screen").width / density, 1f)
        node(INPUT).performTextReplacement("请按优先级整理\n保留第二行草稿")
        hideKeyboard()
        assertComposerGeometry()
        capture("09-360dp-font-1_4")
    }

    private fun verifyRealKeyboard() {
        // Touch starts a real input connection on MuMu; semantics focus alone is insufficient.
        node(INPUT).performTouchInput { click(center) }
        compose.runOnIdle {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(activity.currentFocus ?: activity.window.decorView, InputMethodManager.SHOW_IMPLICIT)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) { imeVisible() }
        node(INPUT).assertIsFocused()
        assertDraft("请按优先级整理\n保留第二行草稿")
        // Insets may become visible before the keyboard's entrance animation has finished.
        compose.waitUntil(5_000) {
            val top = keyboardTopInWindow()
            listOf(INPUT, SEND, "assistant-tools").all { bounds(it).bottom <= top + 2f }
        }
        assertComposerGeometry()
        val keyboardTop = keyboardTopInWindow()
        listOf(INPUT, SEND, "assistant-tools").forEach { tag ->
            assertTrue("$tag overlaps the visible keyboard", bounds(tag).bottom <= keyboardTop + 2f)
        }
        measurements += buildJsonObject {
            put("stage", stage); put("fontScale", 1.4); put("viewportDp", 360)
            put("imeVisible", true); put("keyboardTopPx", keyboardTop)
            put("composerBottomPx", bounds(INPUT).bottom)
        }
        capture("10-360dp-font-1_4-keyboard")
        hideKeyboard()
        assertDraft("请按优先级整理\n保留第二行草稿")
    }

    private fun assertDecision(request: String, approved: Boolean) {
        val action = actions.last()
        assertEquals("assistant/respond", action.method)
        assertEquals("approval", action.params.string("operationId"))
        assertEquals(request, action.params.string("requestId"))
        assertEquals(approved, action.params["response"]!!.jsonObject["Approval"]!!.jsonObject["approved"]!!.jsonPrimitive.boolean)
    }

    private fun acknowledgeSend() {
        compose.runOnIdle {
            state = state.copy(pendingActions = emptySet(), sendAcknowledgement = state.sendAcknowledgement + 1)
        }
        compose.waitForIdle()
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun bounds(tag: String) = rawBounds(node(tag).fetchSemanticsNode())
    private fun rawBounds(node: SemanticsNode) = Rect(node.positionInWindow,
        Size(node.size.width.toFloat(), node.size.height.toFloat()))

    private fun assertInside(tag: String, container: Rect) {
        node(tag).assertIsDisplayed()
        val b = bounds(tag)
        assertTrue("$tag has nonempty bounds", b.width > 0 && b.height > 0)
        assertTrue("$tag extends outside $container: $b", b.left >= container.left - 2 &&
            b.right <= container.right + 2 && b.top >= container.top - 2 && b.bottom <= container.bottom + 2)
    }

    private fun assertComposerGeometry() {
        val screen = bounds("assistant-screen")
        listOf(INPUT, SEND, "assistant-tools", "assistant-back", "assistant-more").forEach { assertInside(it, screen) }
        val input = bounds(INPUT)
        val send = bounds(SEND)
        val tools = bounds("assistant-tools")
        assertTrue("Tools must not overlap the editor", tools.right <= input.left + 1)
        assertTrue("Editor must not overlap Send", input.right <= send.left + 1)
        assertTrue("History must stay above the composer", bounds("assistant-chat-list").bottom <= input.top + 2)
        measurements += buildJsonObject {
            put("stage", stage); put("screenWidthPx", screen.width)
            put("input", rectangle(input)); put("send", rectangle(send)); put("tools", rectangle(tools))
        }
    }

    private fun assertBubbleGeometry() {
        val viewport = bounds("assistant-chat-list")
        assertInside("assistant-message:user-todo", viewport)
        assertInside("assistant-message:assistant-result", viewport)
        assertInside("assistant-task:todo", viewport)
        val outgoing = bounds("assistant-message:user-todo")
        val incoming = bounds("assistant-message:assistant-result")
        assertTrue("User bubble should be to the right of the assistant bubble", outgoing.center.x > incoming.center.x)
        assertTrue("User bubble has the rightmost edge", outgoing.right > incoming.right)
        measurements += buildJsonObject { put("stage", stage); put("user", rectangle(outgoing)); put("assistant", rectangle(incoming)) }
    }

    private fun assertDraft(expected: String) = assertEquals(expected,
        node(INPUT).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

    private fun waitForTail() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("assistant-task:todo").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        node("assistant-task:todo").assertIsDisplayed()
    }

    private fun waitForMarkdown(text: String) = compose.waitUntil(10_000) {
        compose.runOnIdle { textViews(activity.window.decorView).any { it.text.contains(text) } }
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun imeVisible() = compose.runOnIdle {
        ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun keyboardTopInWindow() = compose.runOnIdle {
        val decor = activity.window.decorView
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
        check(insets.isVisible(WindowInsetsCompat.Type.ime()))
        val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        check(imeHeight > 0)
        // Both sources are in screen coordinates, unlike the unclipped semantics rectangles.
        val visible = android.graphics.Rect().also(decor::getWindowVisibleDisplayFrame)
        val screen = IntArray(2).also(decor::getLocationOnScreen)
        val window = IntArray(2).also(decor::getLocationInWindow)
        val top = if (Build.VERSION.SDK_INT >= 30)
            minOf(visible.bottom, activity.windowManager.currentWindowMetrics.bounds.bottom - imeHeight)
        else visible.bottom
        top.toFloat() - (screen[1] - window[1])
    }

    private fun hideKeyboard() {
        compose.runOnIdle {
            focus.clearFocus(force = true)
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) { !imeVisible() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Wait for a submitted frame, including async AndroidView Markdown layout, without sleeps.
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync { activity.window.decorView.postOnAnimation {
            activity.window.decorView.postOnAnimation { frame.countDown() }
        } }
        check(frame.await(5, TimeUnit.SECONDS)) { "No Activity frame at $stage" }
        val copied = CountDownLatch(1)
        var result = -1
        lateinit var bitmap: Bitmap
        var display = -1
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            check(decor.width > 0 && decor.height > 0)
            display = decor.display?.displayId ?: -1
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        try {
            assertTrue("PixelCopy timed out at $stage", copied.await(5, TimeUnit.SECONDS))
            assertEquals("PixelCopy at $stage", PixelCopy.SUCCESS, result)
            val file = File(proofDirectory, "$name.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshots += buildJsonObject {
                put("file", file.name); put("stage", stage); put("displayId", display)
                put("widthPx", bitmap.width); put("heightPx", bitmap.height); put("source", "Activity.window PixelCopy")
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("assistantChatDeviceScreenshot", file.absolutePath) })
        } finally { bitmap.recycle() }
    }

    private fun writeResult(status: String, failure: Throwable? = null) {
        val result = buildJsonObject {
            put("status", status); put("stage", stage); put("fixtureOnly", true)
            put("passedScenarios", checks.size); put("checks", JsonArray(checks.map(::JsonPrimitive)))
            put("repositoryAccessAttempts", repositoryAccesses.get())
            put("stateStorage", "in-memory only; no repository, pairing, configuration or database writes")
            put("transport", "none"); put("modelExecution", "none")
            put("openedThreads", openedThreads.size); put("exits", exits)
            put("actions", buildJsonArray { actions.forEach { add(buildJsonObject { put("method", it.method); put("params", it.params) }) } })
            put("screenshots", JsonArray(screenshots)); put("measurements", JsonArray(measurements))
            put("keyboardEvidence",if(checks.contains("real_keyboard_geometry"))
                "Focused editor, real Activity IME insets and visible frame; PixelCopy excludes the separate IME window"
                else "Not verified in the UI suite; requires separate realKeyboardDoesNotCoverComposer test")
            failure?.let { put("failure", "${it.javaClass.name}: ${it.message}") }
        }
        File(proofDirectory, "result.json").writeText(result.toString())
    }

    private fun rectangle(r: Rect) = buildJsonObject {
        put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom)
    }

    private fun runningOperation() = AssistantOperation("running", "Execution", "todo",
        AssistantOperationPhase.Running, false, THREAD, null, null)

    private fun populatedState(): AssistantUiState = AssistantUiState(
        snapshot = AssistantSnapshot(
            enabled = true, runtimeId = RUNTIME, coordinatorThreadId = THREAD, paused = false,
            settings = AssistantSettings("先给结论，再说明步骤", "直接、清楚", null, "Asia/Shanghai"),
            tasks = listOf(AssistantTask("todo", "核对 Codex Pocket 的设备验收待办", AssistantTaskStatus.Running,
                "running", false, "正在整理聊天、键盘和历史阅读的检查项。")),
            operations = listOf(runningOperation()),
            events = listOf(AssistantEvent(1, "running-event", "running", "started", "整理验收清单", NOW - 10_000)),
            memories = listOf(AssistantMemory("style", "待办按优先级整理，先给结论。")),
        ),
        messages = listOf(AssistantChatMessage("markdown", "Assistant", LONG_MARKDOWN, NOW - 3_600_000)) +
            (1..24).map { i -> AssistantChatMessage("history-$i", if (i % 2 == 0) "User" else "Assistant",
                if (i % 2 == 0) "第 $i 项：保留项目上下文，下次接着核对。" else "第 $i 项已整理：先检查聊天体验，再核对设备表现。",
                NOW - 1_800_000 + i * 30_000) } + listOf(
                AssistantChatMessage("user-todo", "User", "帮我整理 Codex Pocket 的待办", NOW - 30_000),
                AssistantChatMessage("assistant-result", "Assistant", "待办已分成三项：聊天、键盘、历史。", NOW - 20_000),
            ),
    )

    private companion object {
        const val RUNTIME = "fixture-desktop"
        const val PROJECT = "fixture-project"
        const val THREAD = "01a1c7fb-7bda-7a71-a782-a020229c9573"
        const val INPUT = "assistant-message-input"
        const val SEND = "assistant-send"
        const val DRAFT = "先整理 Codex Pocket 的待办\n把设备验收放在第一项"
        val NOW = Instant.parse("2026-11-11T08:00:00Z").toEpochMilli()
        val LONG_MARKDOWN = """
            ## 待办整理说明

            已按 **优先级** 分组。可以回看 [项目原对话](codex://threads/$THREAD)，再继续核对。

            1. 聊天：发送前保留草稿，收到确认后再清除。
            2. 键盘：输入框和发送按钮始终可见。
            3. 历史：阅读旧消息时，新内容不要打断当前位置。

            > 需要你决定的操作会单独提问，聊天文字不会代替审批。

            ```text
            Codex Pocket
              Android / 聊天与工具
              Windows / 后续验收
              待办 / 按优先级逐项确认
            ```

            ### 核对步骤

        """.trimIndent() + "\n\n" + (1..12).joinToString("\n\n") {
            "第 $it 轮：保留多行中文草稿，返回记忆页面后继续阅读。窄屏、大字和深色主题都需要检查；记录发现的问题，再明确下一步。"
        }
    }
}
