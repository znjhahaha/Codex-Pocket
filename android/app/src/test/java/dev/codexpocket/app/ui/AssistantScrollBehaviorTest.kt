package dev.codexpocket.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import dev.codexpocket.app.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose scrolling with a plain activity and in-memory action callbacks only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantScrollBehaviorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val actions = mutableListOf<AssistantAction>()
    private val baseTime = 1_893_456_000_000L
    private val history = List(36) { index ->
        AssistantChatMessage("history-$index", "User", "Recorded message $index\nLine two\nLine three\nLine four", baseTime + index * 1_000L)
    }

    private fun initialState(untimedTask: Boolean = false) = AssistantUiState(
        snapshot = AssistantSnapshot(
            enabled = true, ready=true, runtimeId = "r", paused = false, settings = AssistantSettings(timezone = "UTC"),
            tasks = if (untimedTask) listOf(AssistantTask("untimed-tail", "Task without an event time", AssistantTaskStatus.Queued, null, false, null)) else emptyList(),
        ),
        messages = history,
    )

    private fun content(fontScale: Float = 1f, state: () -> AssistantUiState) {
        // Avoid MainActivity/PocketApplication: the fixture must never construct a repository.
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                PocketTheme("light") {
                    CompositionLocalProvider(LocalPocketGlass provides false,
                        LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        AssistantScreen(state(), emptyList(), listOf(AssistantRuntimeChoice("r", "Fixture")), emptyList(),
                            onAction = { actions += it }, onOpenThread = { _, _ -> fail("Unexpected thread navigation") }, onBack = {},
                            nowMs = baseTime)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun closeActivity() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.close() }
    }

    private data class ReadingAnchor(val tag: String, val top: Float)

    private fun readHistory(): ReadingAnchor {
        val list = compose.onNodeWithTag("assistant-chat-list")
        list.performScrollToIndex(12)
        // A real gesture establishes user scroll intent; ScrollToIndex alone may be instantaneous.
        list.performTouchInput {
            swipe(Offset(centerX, height * .35f), Offset(centerX, height * .65f), durationMillis = 600)
        }
        compose.waitForIdle()
        // Reading history shows nothing by itself; the way back appears only when something new arrives below.
        compose.onNodeWithTag("assistant-new-progress").assertDoesNotExist()
        val viewport = compose.onNodeWithTag("assistant-chat-viewport").fetchSemanticsNode().boundsInRoot
        val node = compose.onAllNodes(SemanticsMatcher("recorded history bubble") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("assistant-message:history-") == true
        }).fetchSemanticsNodes().first {
            it.boundsInRoot.top > viewport.top + 1f && it.boundsInRoot.bottom < viewport.bottom - 1f
        }
        return ReadingAnchor(node.config[SemanticsProperties.TestTag], node.boundsInRoot.top)
    }

    private fun assertReadingAnchor(anchor: ReadingAnchor) {
        val node = compose.onNodeWithTag(anchor.tag).assertIsDisplayed().fetchSemanticsNode()
        assertEquals("The same history bubble must keep its viewport offset", anchor.top, node.boundsInRoot.top, 1f)
    }

    private fun incoming(id: String) = AssistantChatMessage(id, "Assistant", "New recorded reply", baseTime + 60_000L)

    private fun assertTailAboveDock(tag: String) {
        val tail = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val clear = compose.onNodeWithTag("assistant-chat-viewport").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("assistant-bottom-dock").fetchSemanticsNode().boundsInRoot
        assertTrue("The initial tail must clear the measured header", tail.top >= clear.top - 1f)
        assertTrue("The initial tail must clear the measured composer", tail.bottom <= dock.top + 1f)
        compose.onNodeWithTag("assistant-new-progress").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    @Test fun initialTailClearsMeasuredChromeWithoutManualScrolling() {
        val state = initialState(untimedTask = true)
        content { state }
        assertTailAboveDock("assistant-chat-task:untimed-tail")
    }

    @Test fun initialTailUsesTheTaskActiveRunEventTime() {
        val task = AssistantTask("todo", "Recorded device acceptance task", AssistantTaskStatus.Running, "running", false, null)
        val event = AssistantEvent(1, "running-event", "running", "started", "Recorded task start", baseTime + 60_000L)
        val state = initialState().let { it.copy(snapshot = it.snapshot!!.copy(tasks = listOf(task), events = listOf(event),
            operations = listOf(AssistantOperation("running", "Execution", "todo", AssistantOperationPhase.Running,
                false, "execution", null, null)))) }
        val tail = assistantTimeline(state).last()
        assertEquals("task:todo", tail.key)
        assertEquals(event.atMs, tail.atMs)
        content { state }
        assertTailAboveDock("assistant-chat-task:todo")
    }

    @Test fun initialTailClearsPreparationDockAtLargeText() {
        val state = initialState(untimedTask = true).let { it.copy(snapshot = it.snapshot!!.copy(ready = false)) }
        content(fontScale = 1.4f) { state }
        // What preparation asks for comes after the conversation: both it and the latest task are in view.
        assertTailAboveDock("assistant-prepare")
        compose.onNodeWithTag("assistant-chat-task:untimed-tail").assertIsDisplayed()
    }

    @Test fun lateMarkdownMeasurementKeepsTheLatestTaskVisible() {
        var state by mutableStateOf(initialState(untimedTask = true))
        content { state }
        assertTailAboveDock("assistant-chat-task:untimed-tail")
        compose.runOnIdle {
            state = state.copy(messages = state.messages.map { message ->
                message.copy(role = "Assistant", text = "## Recorded result\n\n" +
                    List(12) { "Measured native Markdown paragraph with several wrapped lines." }.joinToString("\n\n"))
            })
        }
        compose.waitForIdle()
        assertTailAboveDock("assistant-chat-task:untimed-tail")
    }

    @Test fun initialTailIncludesOlderMessageControlAndCoordinatorWorkingRow() {
        val state = initialState(untimedTask = true).let { it.copy(nextBefore = 1L, snapshot = it.snapshot!!.copy(
            operations = listOf(AssistantOperation("working", "Coordinator", null, AssistantOperationPhase.Running,
                false, "coordinator", null, null)))) }
        content { state }
        assertTailAboveDock("assistant-working")
        compose.onNodeWithTag("assistant-chat-task:untimed-tail").assertIsDisplayed()
    }

    @Test fun timedMessageBeforeUntimedTaskPreservesReadingPositionAndShowsUnread() {
        var state by mutableStateOf(initialState(untimedTask = true))
        content { state }
        val anchor = readHistory()

        compose.runOnIdle { state = state.copy(messages = state.messages + incoming("inserted")) }
        compose.waitForIdle()

        assertReadingAnchor(anchor)
        compose.onNodeWithTag("assistant-new-progress").assertIsDisplayed().assert(hasText("查看新进展"))
        compose.onNodeWithTag("assistant-new-progress").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("assistant-new-progress").assertDoesNotExist()
        compose.onNodeWithTag("assistant-message:inserted").assertIsDisplayed()
        compose.onNodeWithTag("assistant-chat-task:untimed-tail").assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }

    @Test fun returningFromSubpagePreservesPositionAndDoesNotResumeFollowing() {
        var state by mutableStateOf(initialState())
        content { state }
        val anchor = readHistory()

        compose.openAssistantPage("Memory")
        compose.onNodeWithTag(AssistantDraft).assertDoesNotExist()
        compose.returnToAssistantChat()
        compose.waitForIdle()
        assertReadingAnchor(anchor)

        // A new tail after restoration exposes a silently overwritten followLatest flag.
        compose.runOnIdle { state = state.copy(messages = state.messages + incoming("after-return")) }
        compose.waitForIdle()
        assertReadingAnchor(anchor)
        compose.onNodeWithTag("assistant-new-progress").assertIsDisplayed().assert(hasText("查看新进展"))
        assertTrue(actions.isEmpty())
    }

    @Test fun returningFromSubpageKeepsNearBottomAnchorWithMultilineDraft() {
        val state = initialState()
        content { state }
        compose.onNodeWithTag(AssistantDraft).performTextReplacement("Preserved draft\nSecond line")
        compose.onNodeWithTag("assistant-chat-list").performScrollToIndex(history.lastIndex)
        compose.waitForIdle()
        val tag = "assistant-message:history-35"
        val before = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        compose.openAssistantPage("Memory")
        compose.returnToAssistantChat()
        compose.waitForIdle()
        assertEquals("Near-bottom history must not be clamped by temporarily zero chrome padding",
            before, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag(AssistantDraft).assertTextContains("Preserved draft\nSecond line")
        assertTrue(actions.isEmpty())
    }

    /** A receipt settles the composer; it is not new content, so it neither moves the reader nor offers a way down. */
    @Test fun acceptedReceiptClearsTheDraftAndLeavesTheReaderInPlaceUntilNewContentArrives() {
        var state by mutableStateOf(initialState())
        content { state }
        compose.onNodeWithTag(AssistantDraft).performTextInput("Fixture send")
        compose.onNodeWithTag(AssistantSend).performClick()
        compose.runOnIdle { state = state.copy(pendingActions = setOf("assistant/send")) }
        compose.waitForIdle()
        val anchor = readHistory()

        // Repository acknowledgments precede the history reload; no item is added here.
        compose.runOnIdle { state = state.copy(pendingActions = emptySet()).acceptSend(actions.single()) }
        compose.waitForIdle()
        assertReadingAnchor(anchor)
        compose.onNodeWithTag("assistant-new-progress").assertDoesNotExist()
        compose.onNodeWithTag(AssistantDraft).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        // The reload brings the sent message and a reply: the reader keeps their place and is offered the way down.
        compose.runOnIdle { state = state.copy(messages = state.messages + AssistantChatMessage("sent", "User", "Fixture send", baseTime + 61_000L) + incoming("reply").copy(atMs = baseTime + 62_000L)) }
        compose.waitForIdle()
        assertReadingAnchor(anchor)
        compose.onNodeWithTag("assistant-new-progress").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("assistant-message:reply").assertIsDisplayed()
        assertEquals(listOf("assistant/send"), actions.map { it.method })
    }

    @Test fun streamingTextGrowthAndDetailReturnPreserveTheReadersAnchor() {
        val stream=AssistantChatMessage("stream","Assistant","Partial reply",baseTime+60_000L,"coordinator")
        var state by mutableStateOf(initialState().copy(messages=history+stream))
        content {state}
        val anchor=readHistory()
        compose.runOnIdle {state=state.copy(messages=history+stream.copy(text=(1..25).joinToString("\n") {"Recorded streaming line $it"}))}
        compose.waitForIdle()
        assertReadingAnchor(anchor)
        compose.openAssistantPage("Tasks")
        compose.returnToAssistantChat()
        assertReadingAnchor(anchor)
        assertTrue(actions.isEmpty())
    }

    @Test @Config(qualifiers = "w320dp-h710dp-xxhdpi")
    fun largeTextPreparationLeavesRoomToReadAndExposesAllStages() {
        val state=initialState().let {it.copy(snapshot=it.snapshot!!.copy(ready=false,
            errorCode="service_reconciliation_required",preparationStatus="recoverable_error",
            startupStages=listOf(AssistantStartupStage("runtime","ready"),AssistantStartupStage("account","ready"),
                AssistantStartupStage("capabilities","ready"),AssistantStartupStage("coordinator","reconciling","reconcile_retained_native_receipts"))))}
        content(fontScale=2f) {state}
        val screen=compose.onNodeWithTag("assistant-workspace").fetchSemanticsNode().boundsInRoot
        val clear=compose.onNodeWithTag("assistant-chat-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Preparation must leave a readable transcript viewport at large text", clear.height >= screen.height*.22f)
        // The stages are part of the conversation's last card, read by scrolling, not a separate sheet.
        listOf("runtime","account","capabilities","coordinator").forEach {id ->
            compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-stage:$id"))
            compose.onNodeWithTag("assistant-stage:$id").assertIsDisplayed()
        }
        compose.onNodeWithTag("assistant-stage-recovery").assertTextContains("正在读取电脑端回执核对结果",substring=true)
        compose.onNodeWithTag(AssistantDraft).assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }
}
