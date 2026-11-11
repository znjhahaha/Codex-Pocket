package dev.codexpocket.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
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
            enabled = true, runtimeId = "r", paused = false, settings = AssistantSettings(timezone = "UTC"),
            tasks = if (untimedTask) listOf(AssistantTask("untimed-tail", "Task without an event time", AssistantTaskStatus.Queued, null, false, null)) else emptyList(),
        ),
        messages = history,
    )

    private fun content(state: () -> AssistantUiState) {
        // Avoid MainActivity/PocketApplication: the fixture must never construct a repository.
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                PocketTheme("light") {
                    CompositionLocalProvider(LocalPocketGlass provides false) {
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
        compose.onNodeWithTag("assistant-latest").assertIsDisplayed()
        val viewport = list.fetchSemanticsNode().boundsInRoot
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

    @Test fun timedMessageBeforeUntimedTaskPreservesReadingPositionAndShowsUnread() {
        var state by mutableStateOf(initialState(untimedTask = true))
        content { state }
        val anchor = readHistory()

        compose.runOnIdle { state = state.copy(messages = state.messages + incoming("inserted")) }
        compose.waitForIdle()

        assertReadingAnchor(anchor)
        compose.onNodeWithTag("assistant-latest").assertIsDisplayed().assertTextContains("有新消息", substring = true)
        compose.onNodeWithTag("assistant-latest").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("assistant-message:inserted").assertIsDisplayed()
        compose.onNodeWithTag("assistant-task:untimed-tail").assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }

    @Test fun returningFromSubpagePreservesPositionAndDoesNotResumeFollowing() {
        var state by mutableStateOf(initialState())
        content { state }
        val anchor = readHistory()

        compose.onNodeWithTag("assistant-more").performClick()
        compose.onNodeWithTag("assistant-tab:Memory").performClick()
        compose.onNodeWithTag("assistant-message-input").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回助理对话").performClick()
        compose.waitForIdle()
        assertReadingAnchor(anchor)

        // A new tail after restoration exposes a silently overwritten followLatest flag.
        compose.runOnIdle { state = state.copy(messages = state.messages + incoming("after-return")) }
        compose.waitForIdle()
        assertReadingAnchor(anchor)
        compose.onNodeWithTag("assistant-latest").assertIsDisplayed().assertTextContains("有新消息", substring = true)
        assertTrue(actions.isEmpty())
    }

    @Test fun acceptedSendMovesToLatestEvenBeforeHistoryRefreshChangesTheTail() {
        var state by mutableStateOf(initialState())
        content { state }
        compose.onNodeWithTag("assistant-message-input").performTextInput("Fixture send")
        compose.onNodeWithTag("assistant-send").performClick()
        compose.runOnIdle { state = state.copy(pendingActions = setOf("assistant/send")) }
        compose.waitForIdle()
        readHistory()

        // Repository acknowledgments precede the history reload; no item is added here.
        compose.runOnIdle { state = state.copy(pendingActions = emptySet(), sendAcknowledgement = 1) }
        compose.waitForIdle()

        compose.onNodeWithTag("assistant-message:history-35").assertIsDisplayed()
        compose.onNodeWithTag("assistant-latest").assertDoesNotExist()
        compose.onNodeWithTag("assistant-message-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals(listOf("assistant/send"), actions.map { it.method })
    }
}
