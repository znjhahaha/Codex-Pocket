package dev.codexpocket.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Display
import android.view.PixelCopy
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import dev.codexpocket.app.ui.PocketBackdrop
import dev.codexpocket.app.ui.PocketTheme
import dev.codexpocket.app.ui.kit.*
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Run alone in a fresh instrumentation process with assistantModelPanelDevice=true.
 * Intercepts MainActivity before its onCreate, mounts the real AssistantScreen with fixture models,
 * and blocks ExecutionLocations initialization. The parent owns build/install/ADB and IME selection.
 * A real nonzero-height IME is required; the existing FixtureKeyboardService works on MuMu.
 */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class AssistantModelPanelDeviceTest {
    private val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: FixtureActivity
    private lateinit var focus: FocusManager
    private lateinit var proofDirectory: File
    private val locationsAccesses = AtomicInteger()
    private val actions = CopyOnWriteArrayList<AssistantAction>()
    private val screenshots = mutableListOf<JsonObject>()
    private val measurements = mutableListOf<JsonObject>()
    private val checks = mutableListOf<String>()
    private var stage = "isolation"
    private var caseName = "setup"
    private var theme by mutableStateOf("light")
    private var assistantVisible by mutableStateOf(true)
    private var fixture by mutableStateOf(FIXTURE)
    private var retryHeightBeforeIme = 0f

    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Opt in with assistantModelPanelDevice=true",
                    InstrumentationRegistry.getArguments().getString("assistantModelPanelDevice") == "true")
                val app = instrumentation.targetContext.applicationContext as PocketApplication
                proofDirectory = File(checkNotNull(app.getExternalFilesDir(null)), "assistant-model-panel-device-proof")
                    .apply { check(isDirectory || mkdirs()) }
                writeResult("running")
                // repository now delegates through locations. Guard the current lazy authority,
                // so neither a computer connection nor the packaged phone runtime can initialize.
                val field = PocketApplication::class.java.getDeclaredField("locations\$delegate")
                    .apply { isAccessible = true }
                val original = field.get(app) as Lazy<*>
                check(!original.isInitialized()) { "Run this class alone in a fresh instrumentation process" }
                val forbiddenLocations = lazy {
                    locationsAccesses.incrementAndGet()
                    error("Model-panel fixture must never initialize ExecutionLocations or a repository")
                }
                val runner = instrumentation as MonitoringInstrumentation
                instrumentation.runOnMainSync {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(app)
                    field.set(app, forbiddenLocations)
                }
                try {
                    runner.interceptActivityUsing(object : InterceptingActivityFactory {
                        override fun shouldIntercept(loader: ClassLoader, name: String, intent: Intent) =
                            name == MainActivity::class.java.name
                        override fun create(loader: ClassLoader, name: String, intent: Intent): Activity =
                            FixtureActivity { FixtureContent() }
                    })
                    base.evaluate()
                    assertEquals("No runtime/repository access, including teardown", 0, locationsAccesses.get())
                    assertFalse(original.isInitialized())
                    assertTrue("Only fixture callbacks are allowed", actions.all { it.method in setOf("assistant/read", "assistant/send",
                        "assistant/v2/conversation/list", "assistant/conversation/select", "assistant/v2/conversation/interrupt") })
                    writeResult("passed")
                } catch (failure: Throwable) {
                    writeResult("failed", failure)
                    throw failure
                } finally {
                    runner.useDefaultInterceptingActivityFactory()
                    instrumentation.runOnMainSync {
                        if (::activity.isInitialized) activity.clearFixturePreferences()
                        field.set(app, original)
                    }
                    // Do not reattach the foreground observer during this isolated process:
                    // reattaching while STARTED would initialize real execution locations.
                }
            }
        }
    }).around(compose)

    private class FixtureActivity(private val render: @Composable () -> Unit) : ComponentActivity() {
        private var mounted by mutableStateOf(false)
        private val prefix = "assistant-model-device-${UUID.randomUUID()}-"
        private val fixturePreferences = mutableSetOf<String>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            fixturePreferences += isolatedName
            return super.getSharedPreferences(isolatedName, mode)
        }
        fun clearFixturePreferences() {
            fixturePreferences.forEach { name ->
                check(name.startsWith(prefix))
                deleteSharedPreferences(name)
            }
        }
        fun mount() { mounted = true }
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            enableEdgeToEdge()
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setContent { if (mounted) render() }
        }
    }

    @Composable private fun FixtureContent() {
        focus = LocalFocusManager.current
        PocketTheme(theme) {
            PocketBackdrop {
                if (assistantVisible) AssistantScreen(
                    state = fixture,
                    models = MODELS,
                    runtimes = listOf(AssistantRuntimeChoice(RUNTIME, "测试电脑", true)),
                    workspaces = emptyList(),
                    onAction = { actions += it },
                    onOpenThread = { _, _ -> error("The fixture must not open a native thread") },
                    onBack = { assistantVisible = false },
                    nowMs = 1_795_564_800_000L,
                    connected = true,
                ) else Box(Modifier.fillMaxSize().testTag("fixture-conversations"), contentAlignment = Alignment.Center) {
                    PText("会话列表", style = Pocket.type.title)
                }
            }
        }
    }

    @Test(timeout = 180_000) fun modelPanelGesturesKeyboardAndAppearance() {
        caseName = "model-panel"
        compose.mainClock.autoAdvance = false
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        ActivityScenario.launch<ComponentActivity>(Intent(instrumentation.targetContext, MainActivity::class.java), options).use { scenario ->
            mountOnDefaultDisplay(scenario)
            try {
                checkStage("light_collapse_restore") { collapseAndRestore() }
                checkStage("content_and_list_swipes") { contentAndListSwipes() }
                checkStage("hidden_on_child_page") { childPageHidesBall() }
                checkStage("dark_rendering") {
                    compose.runOnIdle { theme = "dark" }; settle()
                    node(BALL).assertIsDisplayed()
                    capture("04-dark-ball")
                    node(BALL).performClick(); settle()
                    node("model-search").assertTextEquals("model")
                    capture("05-dark-panel")
                    node("assistant-model-collapse").performClick(); settle()
                }
                checkStage("real_keyboard_avoidance") { keyboardAvoidance() }
                checkStage("hidden_after_leaving_assistant") {
                    node("assistant-back").performClick(); settle()
                    node("fixture-conversations").assertIsDisplayed()
                    node(BALL).assertDoesNotExist()
                    node(PANEL).assertDoesNotExist()
                }
            } catch (failure: Throwable) {
                runCatching { capture("failure") }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            } finally {
                hideKeyboard()
            }
        }
    }

    @Test(timeout = 90_000) fun conversationOutboxLateReceiptAndConfirmedStop() {
        caseName = "conversation-outbox"
        compose.mainClock.autoAdvance = false
        fixture = AssistantUiState(snapshot = AssistantSnapshot(enabled = true, ready = true, protocolVersion = 2,
            acceptingMessages = true, runtimeId = RUNTIME, scopeId = "scope", conversationId = "chat-a", settings = AssistantSettings()),
            computerId = "outbox-device-fixture", conversations = listOf(AssistantConversation("chat-a","第一段对话",1,1), AssistantConversation("chat-b","第二段对话",2,2)))
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        ActivityScenario.launch<ComponentActivity>(Intent(instrumentation.targetContext, MainActivity::class.java), options).use { scenario ->
            mountOnDefaultDisplay(scenario)
            checkStage("independent_outbox") {
                node(INPUT).performTextInput("第一条消息");node(SEND).performClick();settle()
                val first=actions.single { it.method=="assistant/send" }
                node(INPUT).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                node(INPUT).performTextInput("第二条消息")
                compose.runOnIdle {fixture=fixture.copy(pendingActions=setOf("assistant/send"))};settle()
                node(SEND).assertIsEnabled().performClick();settle()
                val second=actions.last {it.method=="assistant/send"}
                assertNotEquals(first.commandId,second.commandId)
                node(INPUT).performTextInput("新的草稿")
                compose.runOnIdle {fixture=fixture.copy(pendingActions=emptySet(),sendReceipts=listOf(
                    AssistantSendReceipt(first.commandId!!,"outbox-device-fixture","scope","chat-a","accepted"),
                    AssistantSendReceipt(second.commandId!!,"outbox-device-fixture","scope","chat-a","rejected")))};settle()
                node(INPUT).assertTextEquals("新的草稿")
                node("assistant-chat-list").performScrollToNode(hasTestTag("assistant-send-failure:${second.commandId}"));settle()
                node("assistant-send-failure:${second.commandId}").performClick();settle()
                node("assistant-send-retry:${second.commandId}").performClick();settle()
                val retry=actions.last {it.method=="assistant/send"}
                assertNotEquals(second.commandId,retry.commandId);assertTrue(retry.retry)
                capture("08-outbox-message-state")
            }
            checkStage("conversation_drafts") {
                node("assistant-title-menu").performClick();settle()
                node("assistant-menu:History").performClick();settle()
                node("assistant-conversation:chat-b").performClick();settle()
                assertEquals("chat-b",actions.last().params.string("conversationId"))
                compose.runOnIdle {fixture=fixture.copy(snapshot=fixture.snapshot!!.copy(conversationId="chat-b"))};settle()
                node(INPUT).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                node(INPUT).performTextInput("第二段草稿")
                compose.runOnIdle {fixture=fixture.copy(snapshot=fixture.snapshot!!.copy(conversationId="chat-a"))};settle()
                node(INPUT).assertTextEquals("新的草稿")
            }
            checkStage("confirmed_stop") {
                val operation=AssistantOperation("reply","Coordinator",null,AssistantOperationPhase.Running,false,"native",null,null,conversationId="chat-a")
                compose.runOnIdle {fixture=fixture.copy(snapshot=fixture.snapshot!!.copy(activeCoordinatorOperationId="reply",operations=listOf(operation)))};settle()
                node("assistant-stop-reply").performClick();settle()
                assertEquals("assistant/v2/conversation/interrupt",actions.last().method)
                compose.runOnIdle {fixture=fixture.copy(snapshot=fixture.snapshot!!.copy(operations=listOf(operation.copy(suspended=true))))};settle()
                node("assistant-stop-reply").assertIsDisplayed().assertIsNotEnabled()
                compose.runOnIdle {fixture=fixture.copy(snapshot=fixture.snapshot!!.copy(operations=listOf(operation.copy(phase=AssistantOperationPhase.Paused,suspended=true))))};settle()
                node("assistant-stop-reply").assertDoesNotExist()
            }
        }
    }

    private fun mountOnDefaultDisplay(scenario: ActivityScenario<ComponentActivity>) {
        scenario.onActivity { activity = it as FixtureActivity }
        val initialDisplay = activity.window.decorView.display?.displayId ?: -1
        if (initialDisplay != Display.DEFAULT_DISPLAY) {
            // As in AssistantChatDeviceTest, move only the blank intercepted task before creating
            // a Compose input connection. MuMu may ignore the launch-display option.
            val task = activity.taskId
            val result = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am display move-stack $task ${Display.DEFAULT_DISPLAY}"))
                .bufferedReader().use { it.readText() }
            measurements += buildJsonObject {
                put("kind", "fixture_task_display_move"); put("taskId", task)
                put("beforeDisplayId", initialDisplay); put("result", result)
            }
            compose.waitUntil(5_000) {
                var ready = false
                scenario.onActivity { activity = it as FixtureActivity; ready = it.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY }
                ready
            }
        }
        scenario.onActivity {
            assertEquals(Display.DEFAULT_DISPLAY, it.window.decorView.display?.displayId ?: -1)
            (it as FixtureActivity).mount()
        }
        settle()
        assertTrue("Opening the assistant must not dispatch any action", actions.isEmpty())
        node("assistant-prepare").assertDoesNotExist()
    }

    private fun collapseAndRestore() {
        node("assistant-model-open").assertIsDisplayed().performClick(); settle()
        node(PANEL).assertIsDisplayed()
        node("assistant-model-choose").performClick(); settle()
        node("model-search").performTextInput("model"); settle()
        hideKeyboard()
        node("model-list").performTouchInput { swipeUp() }; settle()
        node(PANEL).assertIsDisplayed()
        node(BALL).assertDoesNotExist()
        node("model-list").performScrollToIndex(15); settle()
        val offset = rowOffset()
        assertFullyInsidePanel(RETRY)
        retryHeightBeforeIme = bounds(RETRY).height
        capture("01-light-panel")
        val travel = compose.runOnIdle { 80f * activity.resources.displayMetrics.density }
        node("assistant-model-handle").performTouchInput {
            swipe(center, center + Offset(0f, travel), 350)
        }
        settle()
        node(PANEL).assertDoesNotExist()
        assertBallGeometry()
        capture("02-light-ball")
        node(BALL).performClick(); settle()
        node("model-search").assertTextEquals("model")
        assertEquals("Restoring the panel retains its scroll offset", offset, rowOffset(), 3f)
        compose.onNodeWithText("工作").performClick(); settle()
        node("model-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        node("model-search").performTextInput("Model 2"); settle(); hideKeyboard()
        node("assistant-model-collapse").performClick(); settle()
        node(BALL).performClick(); settle()
        node("model-search").assertTextEquals("Model 2")
        compose.onNodeWithText("助理").performClick(); settle()
        node("model-search").assertTextEquals("model")
        assertEquals("Each role retains its own scroll offset", offset, rowOffset(), 3f)
        node("assistant-model-collapse").performClick(); settle()
        assertReadOnlyActions()
    }

    private fun contentAndListSwipes() {
        val travel = compose.runOnIdle { 80f * activity.resources.displayMetrics.density }
        node(BALL).performClick();settle()
        node("model-search").performTouchInput { swipe(center, center + Offset(0f, travel), 350) };settle()
        node(PANEL).assertDoesNotExist()
        node(BALL).performClick();settle()
        node("model-search").assertTextEquals("model")
        node("model-list").performScrollToIndex(14);settle()
        node("model-list").performTouchInput { swipeDown() };settle()
        node(PANEL).assertIsDisplayed()
        node("model-list").performScrollToIndex(0);settle()
        node("model-list").performTouchInput { swipe(center, center + Offset(0f, travel), 350) };settle()
        node(PANEL).assertDoesNotExist()
        node(BALL).assertIsDisplayed()
        assertReadOnlyActions()
    }

    private fun childPageHidesBall() {
        node("assistant-title-menu").performClick(); settle()
        node("assistant-menu:Memory").performClick(); settle()
        node(BALL).assertDoesNotExist()
        node(PANEL).assertDoesNotExist()
        compose.onNodeWithText("记忆").assertIsDisplayed()
        capture("03-light-away")
        node("assistant-back").performClick(); settle()
        node(BALL).assertIsDisplayed()
    }

    private fun keyboardAvoidance() {
        val before = bounds(BALL).bottom
        node(INPUT).performTextInput("仅保留测试草稿"); settle()
        showKeyboard(INPUT)
        compose.waitUntil(5_000) {
            settle(1)
            val top = keyboardTop()
            listOf(BALL, INPUT, SEND).all { bounds(it).bottom <= top + 2f }
        }
        node(INPUT).assertTextEquals("仅保留测试草稿")
        assertTrue("Ball must rise with the real IME", bounds(BALL).bottom < before - 2f)
        assertBallGeometry()
        recordKeyboard("ball", listOf(BALL, INPUT, SEND))
        capture("06-dark-keyboard-ball")
        node(BALL).performClick(); settle()
        showKeyboard("model-search")
        compose.waitUntil(5_000) {
            settle(1)
            bounds(PANEL).bottom <= keyboardTop() + 2f
        }
        node("model-search").assertTextEquals("model")
        node(PANEL).assertIsDisplayed()
        listOf("assistant-model-handle", "model-search", RETRY).forEach(::assertFullyInsidePanel)
        assertEquals("IME must not shrink the retry touch target", retryHeightBeforeIme, bounds(RETRY).height, 2f)
        recordKeyboard("panel", listOf(PANEL, "model-search", RETRY))
        capture("07-dark-keyboard-panel")
        val beforeRetry = actions.size
        node(RETRY).assertIsEnabled().performTouchInput { click(center) }; settle()
        assertEquals("Retry must be physically clickable with the IME open", beforeRetry + 1, actions.size)
        assertEquals("assistant/read", actions.last().method)
        hideKeyboard()
        node("assistant-model-collapse").performClick(); settle()
        node(INPUT).assertTextEquals("仅保留测试草稿")
        assertReadOnlyActions()
    }

    private fun showKeyboard(tag: String) {
        node(tag).performTouchInput { click(center) }
        compose.runOnIdle {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(activity.currentFocus ?: activity.window.decorView, InputMethodManager.SHOW_IMPLICIT)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) {
            settle(1)
            compose.runOnIdle {
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                insets?.isVisible(WindowInsetsCompat.Type.ime()) == true && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
            }
        }
        node(tag).assertIsFocused()
    }

    private fun hideKeyboard() {
        compose.runOnIdle {
            focus.clearFocus(force = true)
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) {
            settle(1)
            compose.runOnIdle { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
        }
        settle()
    }

    private fun keyboardTop(): Float = compose.runOnIdle {
        val decor = activity.window.decorView
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
        check(insets.isVisible(WindowInsetsCompat.Type.ime()))
        val height = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        check(height > 0) { "Select a real IME, such as the test APK's FixtureKeyboardService" }
        val visible = android.graphics.Rect().also(decor::getWindowVisibleDisplayFrame)
        val screen = IntArray(2).also(decor::getLocationOnScreen)
        val window = IntArray(2).also(decor::getLocationInWindow)
        val top = if (Build.VERSION.SDK_INT >= 30)
            minOf(visible.bottom, activity.windowManager.currentWindowMetrics.bounds.bottom - height)
        else visible.bottom
        top.toFloat() - (screen[1] - window[1])
    }

    private fun recordKeyboard(kind: String, tags: List<String>) {
        val top = keyboardTop()
        measurements += buildJsonObject {
            put("kind", "keyboard_$kind"); put("imeVisible", true); put("keyboardTopPx", top)
            put("elements", buildJsonObject { tags.forEach { tag ->
                val r = bounds(tag)
                assertTrue("$tag overlaps the real keyboard", r.bottom <= top + 2f)
                put(tag, buildJsonObject { put("top", r.top); put("bottom", r.bottom); put("left", r.left); put("right", r.right) })
            } })
        }
    }

    private fun assertBallGeometry() {
        node(BALL).assertIsDisplayed()
        val ball = bounds(BALL)
        val dock = bounds("assistant-bottom-dock")
        assertTrue("Ball is right-aligned inside the dock", ball.center.x > dock.center.x && ball.right <= dock.right + 2f)
        assertTrue("Ball stays above the composer", ball.bottom <= bounds(INPUT).top + 2f)
    }

    private fun assertFullyInsidePanel(tag: String) {
        node(tag).assertIsDisplayed()
        val panelNode = node(PANEL).fetchSemanticsNode()
        // Compare the full child with the clipped visible panel in the same window coordinates.
        val panel = panelNode.boundsInRoot.translate(panelNode.positionInWindow - panelNode.positionInRoot)
        val child = bounds(tag)
        assertTrue("$tag must be fully visible inside the menu: $child / $panel", child.height > 0 && child.width > 0 &&
            child.top >= panel.top - 2f && child.bottom <= panel.bottom + 2f &&
            child.left >= panel.left - 2f && child.right <= panel.right + 2f)
    }

    private fun assertReadOnlyActions() {
        assertTrue("UI fixture may only request the model directory", actions.all {
            it.method == "assistant/read" && it.params["models"] == JsonPrimitive(true)
        })
        assertEquals(0, locationsAccesses.get())
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun bounds(tag: String): Rect {
        val node = node(tag).fetchSemanticsNode()
        return Rect(node.positionInWindow, Size(node.size.width.toFloat(), node.size.height.toFloat()))
    }
    private fun rowOffset() = bounds("model-row-m14").top - bounds("model-list").top
    private fun settle(frames: Int = 48) {
        repeat(frames) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
    }
    private fun checkStage(name: String, block: () -> Unit) {
        stage = name; block(); checks += name; writeResult("running")
    }

    private fun capture(name: String) {
        settle(2)
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync { activity.window.decorView.postOnAnimation {
            activity.window.decorView.postOnAnimation { frame.countDown() }
        } }
        check(frame.await(5, TimeUnit.SECONDS)) { "No Activity frame at $stage" }
        val copied = CountDownLatch(1)
        var result = -1
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        try {
            assertTrue("PixelCopy timed out at $stage", copied.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            val file = File(proofDirectory, "$name.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshots += buildJsonObject {
                put("file", file.name); put("theme", theme); put("stage", stage)
                put("widthPx", bitmap.width); put("heightPx", bitmap.height)
                put("displayId", activity.window.decorView.display?.displayId ?: -1)
                put("source", "Activity.window PixelCopy")
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("assistantModelPanelScreenshot", file.absolutePath) })
        } finally { bitmap.recycle() }
    }

    private fun writeResult(status: String, failure: Throwable? = null) {
        val result = buildJsonObject {
            put("status", status); put("stage", stage); put("checks", JsonArray(checks.map(::JsonPrimitive)))
            put("locationsAccessAttempts", locationsAccesses.get()); put("transport", "none"); put("modelExecution", "none")
            put("actions", JsonArray(actions.map { JsonPrimitive(it.method) }))
            put("screenshots", JsonArray(screenshots)); put("measurements", JsonArray(measurements))
            put("keyboardEvidence", "Nonzero Activity IME insets and window-coordinate bounds; PixelCopy excludes the separate IME window")
            put("fixtureIme", "${instrumentation.context.packageName}/dev.codexpocket.app.FixtureKeyboardService")
            failure?.let { put("failure", "${it.javaClass.name}: ${it.message}") }
        }.toString()
        File(proofDirectory, "result.json").writeText(result)
        File(proofDirectory, "result-$caseName.json").writeText(result)
    }

    private companion object {
        const val RUNTIME = "fixture-desktop"
        const val PANEL = "assistant-model-panel"
        const val BALL = "assistant-model-ball"
        const val RETRY = "assistant-model-retry"
        const val INPUT = "assistant-workspace-draft"
        const val SEND = "assistant-workspace-send"
        val FIXTURE = AssistantUiState(
            snapshot = AssistantSnapshot(enabled = false, runtimeId = RUNTIME, scopeId = "assistant:fixture:$RUNTIME",
                preparationStatus = "idle", settings = AssistantSettings(coordinatorModel = "model-14")),
            computerId = "model-panel-device-fixture",
        )
        val MODELS = List(35) { i -> NativeModel("m$i", "model-$i", "Model $i", "", emptyList(), "", false, false) }
    }
}
