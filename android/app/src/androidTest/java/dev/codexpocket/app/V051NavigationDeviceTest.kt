package dev.codexpocket.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import dev.codexpocket.app.data.ChatEntity
import dev.codexpocket.app.data.PocketState
import dev.codexpocket.app.data.ThreadKey
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

/**
 * Opt-in navigation regression, using the real callback-only composer/header/overlay.
 * Navigation and drafts belong to this fixture; no repository, model or control calls.
 * Parent builds/runs with -e v051Navigation true. PixelCopy targets this Activity's
 * window, so proof remains correct on MuMu's secondary displays.
 */
@RunWith(AndroidJUnit4::class)

@SdkSuppress(minSdkVersion = 30)
class V051NavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var proofDirectory: File
    private val screenshots = mutableListOf<String>()
    private val cycles = mutableListOf<JsonObject>()
    // Compose's test input interception may not create a system IME connection.
    // An explicit false runs only navigation/selection; never claim keyboard coverage.
    private val verifySystemIme get() = InstrumentationRegistry.getArguments().getString("v051NavigationIme") != "false"

    private class Fixture {
        var inConversation by mutableStateOf(false)
        var permissions by mutableStateOf(false)
        var draft by mutableStateOf("本地待编辑草稿")
        var cycle by mutableIntStateOf(0)
        var mounts = 0
        var unmounts = 0
        var returns = 0
        var systemReturns = 0
        var headerReturns = 0
        var menuDismissals = 0
        var draftWrites = 0
        var selectionChecks = 0
        var keyboardOpens = 0
        var keyboardCloses = 0
        var clearFocus: () -> Unit = {}

        fun leave(system: Boolean) {
            if (!inConversation) return
            inConversation = false
            permissions = false
            returns++
            if (system) systemReturns++ else headerReturns++
            clearFocus()
        }
    }

    @Test fun fiftyAnimatedNavigationReturnsPreserveDraftAndSelection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("v051Navigation") == "true")
        proofDirectory = File(compose.activity.getExternalFilesDir(null),
            "v051-navigation-proof/" + System.currentTimeMillis())
        check(proofDirectory.mkdirs())
        val fixture = Fixture()
        var failure: Throwable? = null
        var finished = false
        compose.activity.runOnUiThread {
            compose.activity.setContent { FixtureScreen(fixture) }
        }
        try {
            writeReport(fixture, null, false)
            repeat(50) { index ->
                exerciseReturn(fixture, index)
                // A process crash cannot run finally. Keep a never-passing checkpoint on disk.
                writeReport(fixture, null, false)
            }
            compose.runOnIdle {
                assertEquals(50, fixture.mounts)
                assertEquals(50, fixture.unmounts)
                assertEquals(50, fixture.returns)
                assertEquals(DRAFT, fixture.draft)
                assertTrue("The multiline editor must expose selection semantics", fixture.selectionChecks > 0)
                assertEquals(if (verifySystemIme) 5 else 0, fixture.keyboardOpens)
                assertEquals(if (verifySystemIme) 5 else 0, fixture.keyboardCloses)
                assertEquals(10, fixture.menuDismissals)
                assertTrue(fixture.headerReturns > 0 && fixture.systemReturns > 0)
            }
            capture("50-returns-home")
            finished = true
        } catch (error: Throwable) {
            failure = error
            runCatching { capture("failure") }
            throw error
        } finally {
            compose.mainClock.autoAdvance = true
            val report = writeReport(fixture, failure, finished)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("v051NavigationProof", proofDirectory.absolutePath)
                putString("v051NavigationReport", report.toString())
            })
        }
    }

    private fun writeReport(fixture: Fixture, failure: Throwable?, finished: Boolean): JsonObject {
        val report = buildJsonObject {
            put("passed", finished && failure == null)
            put("finished", finished || failure != null)
            put("fixtureOnly", true)
            put("repositoryCallbacks", 0)
            put("modelRequests", 0)
            put("completedTransitions", cycles.size)
            put("mounts", fixture.mounts)
            put("unmounts", fixture.unmounts)
            put("navigationReturns", fixture.returns)
            put("headerReturns", fixture.headerReturns)
            put("systemReturns", fixture.systemReturns)
            put("draftPreserved", fixture.draft == DRAFT)
            put("draftCharacters", fixture.draft.length)
            put("draftWrites", fixture.draftWrites)
            put("attachmentCount", 1)
            put("selectionChecks", fixture.selectionChecks)
            put("keyboardOpens", fixture.keyboardOpens)
            put("keyboardCloses", fixture.keyboardCloses)
            put("systemImeVerified", verifySystemIme && fixture.keyboardOpens == 5 && fixture.keyboardCloses == 5)
            put("systemImeRequested", verifySystemIme)
            put("menuDismissals", fixture.menuDismissals)
            put("failureType", failure?.javaClass?.simpleName ?: "")
            put("cycles", JsonArray(cycles))
            put("screenshots", buildJsonArray { screenshots.forEach { add(it) } })
        }
        File(proofDirectory, "report.json").writeText(report.toString())
        return report
    }

    private fun exerciseReturn(fixture: Fixture, index: Int) {
        compose.runOnIdle { fixture.cycle = index }
        compose.onNodeWithTag("v051-open-conversation").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assertIsDisplayed()
        compose.onNodeWithTag("v051-attachment").assertTextEquals("中文草稿.txt · 本地附件")
        compose.onNodeWithTag("composer-attachment-count").assertTextEquals("1")
        if (index == 0) {
            compose.onNodeWithTag("composer-input").performTextReplacement(DRAFT)
            hideKeyboard(fixture)
            capture("multiline-draft-attachment")
        }
        compose.onNodeWithTag("composer-input").assertTextEquals(DRAFT)
        // A genuine gesture drives compactness, rather than a test-written compact flag.
        compose.onNodeWithTag("v051-history").performTouchInput { swipeUp() }
        waitForPreview()
        val preview = compose.onNodeWithTag("composer-history-preview").fetchSemanticsNode()
        assertFalse("History preview must be noneditable", SemanticsActions.SetText in preview.config)
        compose.onNodeWithTag("composer-input").assertDoesNotExist()
        compose.onNodeWithTag("composer-attachment-count").assertTextEquals("1")
        if (index == 0) capture("compact-preview")

        compose.onNodeWithTag("composer-history-preview").performClick()
        compose.onNodeWithTag("composer-input").assertIsFocused().assertTextEquals(DRAFT)
        val input = compose.onNodeWithTag("composer-input")
        val supportsSelection = SemanticsActions.SetSelection in input.fetchSemanticsNode().config
        if (supportsSelection) input.performTextInputSelection(SELECTION)
        hideKeyboard(fixture)
        waitForPreview()
        compose.onNodeWithTag("composer-history-preview").performClick()
        val restored = compose.onNodeWithTag("composer-input").assertIsFocused().assertTextEquals(DRAFT)
        if (supportsSelection) {
            assertEquals("Selection after preview round trip " + index, SELECTION,
                restored.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            compose.runOnIdle { fixture.selectionChecks++ }
        }

        if (verifySystemIme && index % 10 == 0) {
            showKeyboard(fixture)
            if (index == 0) capture("keyboard-open")
            hideKeyboard(fixture, recordClose = true)
            waitForPreview()
        } else {
            hideKeyboard(fixture)
            waitForPreview()
        }
        if (index % 5 == 2) {
            compose.onNodeWithTag("composer-history-preview").performClick()
            hideKeyboard(fixture, clearFocus = false)
            compose.onNodeWithContentDescription("会话权限").performClick()
            compose.onNodeWithTag("v051-permission-menu").assertIsDisplayed()
            if (index == 2) capture("permissions-menu")
            systemBack()
            compose.onNodeWithTag("v051-permission-menu").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue("Menu back must retain the conversation", fixture.inConversation)
            }
        }

        val system = index % 2 == 1
        val interrupt = index % 5 == 4
        if (interrupt) compose.mainClock.autoAdvance = false
        try {
            if (system) systemBack()
            else compose.onNodeWithContentDescription(compose.activity.getString(R.string.open_chats))
                .performClick()
            if (interrupt) {
                compose.runOnIdle {
                    assertFalse(fixture.inConversation)
                    // Repeat the dispatcher while exit is pending. Home consumes it harmlessly.
                    compose.activity.onBackPressedDispatcher.onBackPressed()
                }
                compose.mainClock.advanceTimeBy(96)
                if (index == 4) capture("animated-exit")
                compose.mainClock.advanceTimeBy(400)
            }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        compose.onNodeWithTag("v051-home-draft").assertTextEquals(DRAFT)
        compose.onNodeWithTag("composer").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(index + 1, fixture.mounts)
            assertEquals(index + 1, fixture.unmounts)
            assertEquals(index + 1, fixture.returns)
            assertEquals(DRAFT, fixture.draft)
        }
        cycles += buildJsonObject {
            put("index", index + 1)
            put("back", if (system) "dispatcher" else "header")
            put("interruptedExit", interrupt)
            put("selectionSupported", supportsSelection)
            put("draftPreserved", true)
            put("attachmentPreserved", true)
        }
    }

    @Composable
    private fun FixtureScreen(fixture: Fixture) {
        PocketTheme("light") {
            PocketBackdrop {
                val holder = rememberSaveableStateHolder()
                // The dispatcher is real; a late second back must not finish the test Activity.
                BackHandler { fixture.leave(system = true) }
                Box(Modifier.fillMaxSize()) {
                    if (!fixture.inConversation) Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("本地导航回归", style = MaterialTheme.typography.titleLarge)
                        PocketButton("打开对话", { fixture.inConversation = true },
                            modifier = Modifier.testTag("v051-open-conversation"))
                        Text(fixture.draft, Modifier.testTag("v051-home-draft"))
                    }
                    AnimatedVisibility(
                        fixture.inConversation,
                        enter = fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 12 },
                        exit = fadeOut(tween(260)) + slideOutHorizontally(tween(260)) { it / 12 }
                    ) {
                        holder.SaveableStateProvider("v051-conversation") {
                            ConversationFixture(fixture)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ConversationFixture(fixture: Fixture) {
        val focus = LocalFocusManager.current
        // Each freshly mounted fixture starts at its live edge. The SaveableStateHolder
        // retains the composer's selection, but intentionally not this synthetic history list.
        val listState = remember { LazyListState() }
        val readingHistory by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
        }
        var permissionAnchor by remember { mutableStateOf<Rect?>(null) }
        val inheritedInput = LocalPocketInputActive.current
        val active by rememberUpdatedState(fixture.inConversation)
        SideEffect { fixture.clearFocus = { focus.clearFocus(force = true) } }
        DisposableEffect(Unit) {
            fixture.mounts++
            onDispose { fixture.unmounts++ }
        }
        CompositionLocalProvider(LocalPocketInputActive provides { active && inheritedInput() }) {
            val key = ThreadKey("v051-local-computer", "v051-local-store", "v051-local-chat")
            val chat = ChatEntity(key, "v051-local-runtime", "desktop", "导航退出回归",
                "", "D:/fixture", 0, "idle", false)
            val state = PocketState(paired = true, online = true, selected = chat, computerName = "本地夹具")
            Column(Modifier.fillMaxSize()) {
                ConversationHeader(state, { fixture.leave(system = false) }, {}, {},
                    compact = readingHistory)
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("v051-history"),
                    state = listState, contentPadding = PaddingValues(16.dp)
                ) {
                    items((0 until 32).toList(), key = { it }) { item ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(vertical = 12.dp)) {
                            Text("已有历史记录 " + item, style = MaterialTheme.typography.labelMedium)
                            Text("这是确定性本地正文。\n上滑查看历史，保留多行中文草稿和附件。",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                PocketComposer(
                    text = fixture.draft, model = "本地模型", detail = "高",
                    running = fixture.cycle % 2 == 1,
                    availability = ComposerAvailability(false, false, true, false),
                    onText = { fixture.draft = it; fixture.draftWrites++ },
                    onModels = { fail("Unexpected model callback") },
                    onAttach = { fail("Unexpected attachment callback") },
                    onReferences = { fail("Unexpected reference callback") },
                    onSend = { fail("Unexpected send callback") },
                    onSteer = { fail("Unexpected steer callback") },
                    onStop = { fail("Unexpected stop callback") },
                    referencesEnabled = false,
                    attachments = {
                        Text("中文草稿.txt · 本地附件", Modifier.testTag("v051-attachment"),
                            style = MaterialTheme.typography.bodySmall)
                    },
                    readingHistory = readingHistory, transcriptScrolling = listState.isScrollInProgress,
                    attachmentCount = 1,
                    onPermissions = { fixture.permissions = true },
                    permissionsExpanded = fixture.permissions,
                    onPermissionAnchor = { permissionAnchor = it }
                )
            }
            if (fixture.permissions) PocketAnchoredMenu(
                "会话权限", {
                    fixture.permissions = false
                    fixture.menuDismissals++
                }, permissionAnchor, widthDp = 280f
            ) {
                Text("本地权限菜单 · 不执行权限修改", Modifier.testTag("v051-permission-menu")
                    .padding(12.dp))
            }
        }
    }

    private fun waitForPreview() = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag("composer-history-preview").fetchSemanticsNodes().isNotEmpty()
    }

    private fun systemBack() = compose.runOnIdle {
        compose.activity.onBackPressedDispatcher.onBackPressed()
    }

    private fun imeVisible(): Boolean = compose.runOnIdle {
        compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
    }

    private fun showKeyboard(fixture: Fixture) {
        // A focus semantic alone does not start an input connection on MuMu's
        // secondary display; use the same pointer interaction as the user.
        compose.onNodeWithTag("composer-input").performTouchInput { click(center) }
        compose.runOnIdle {
            val activity = compose.activity
            val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showSoftInput(activity.currentFocus ?: activity.window.decorView, InputMethodManager.SHOW_IMPLICIT)
            activity.window.insetsController?.show(WindowInsets.Type.ime())
        }
        compose.waitUntil(5_000) { imeVisible() }
        compose.onNodeWithTag("composer-input").assertIsFocused().assertTextEquals(DRAFT)
        compose.runOnIdle { fixture.keyboardOpens++ }
    }

    private fun hideKeyboard(fixture: Fixture, clearFocus: Boolean = true, recordClose: Boolean = false) {
        val wasVisible = imeVisible()
        compose.runOnIdle {
            if (clearFocus) fixture.clearFocus()
            val activity = compose.activity
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.window.insetsController?.hide(WindowInsets.Type.ime())
        }
        compose.waitUntil(5_000) { !imeVisible() }
        if (recordClose) {
            assertTrue("Explicit keyboard exercise must close a visible IME", wasVisible)
            compose.runOnIdle { fixture.keyboardCloses++ }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val latch = CountDownLatch(1)
        var result = -1
        var bitmap: Bitmap? = null
        compose.activity.runOnUiThread {
            val view = compose.activity.window.decorView
            val capture = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            bitmap = capture
            PixelCopy.request(compose.activity.window, capture,
                { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        }
        try {
            check(latch.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) {
                "Window capture failed: " + result
            }
            val capture = checkNotNull(bitmap)
            val file = File(proofDirectory, name + ".png")
            file.outputStream().use { check(capture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshots += file.name
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("v051NavigationScreenshot", file.absolutePath)
            })
        } finally { bitmap?.recycle() }
    }

    private companion object {
        const val DRAFT = "保留多行中文草稿。\n第二行核对附件和退出动画。\n第三行仍可继续编辑。"
        val SELECTION = TextRange(2, 8)
    }
}
