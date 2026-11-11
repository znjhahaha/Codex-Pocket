package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal fun ComposeContentTestRule.openAssistantPage(page: String) {
    onNodeWithTag("assistant-more").assertIsDisplayed().performClick()
    onNodeWithTag("assistant-tab:$page").assertIsDisplayed().performClick()
}

internal fun ComposeContentTestRule.returnToAssistantChat() {
    onNodeWithContentDescription("返回助理对话").assertIsDisplayed().performClick()
    onNodeWithContentDescription("返回原页面").assertIsDisplayed()
}

internal fun ComposeContentTestRule.selectAssistantWorkspace(threadId: String) {
    // The selected context chip replaces the tools-panel entry after a choice.
    if (onAllNodesWithTag("assistant-workspace").fetchSemanticsNodes().isEmpty()) {
        onNodeWithTag("assistant-tools").assertIsDisplayed().performClick()
    }
    onNodeWithTag("assistant-workspace").assertIsDisplayed().performClick()
    onNodeWithTag("assistant-workspace:$threadId").assertIsDisplayed().performClick()
}

internal fun ComposeContentTestRule.expandAssistantTask(taskId: String) {
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onFirst()
        .performScrollToNode(hasTestTag("assistant-task:$taskId"))
    onNodeWithTag("assistant-task:$taskId").assertHasClickAction().performClick()
}

/** Deterministic UI actions only; no repository, transport, or model execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantChatNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val runtimes = listOf(AssistantRuntimeChoice("r", "我的电脑"))
    private val workspaces = listOf(AssistantWorkspaceChoice("r", "authorized-thread", "已有项目", true))
    private val snapshot = AssistantSnapshot(
        enabled = true, runtimeId = "r", coordinatorThreadId = "coordinator", paused = false,
        settings = AssistantSettings("认真协助", "简洁", null, "UTC"),
        tasks = listOf(AssistantTask("task", "整理项目待办", AssistantTaskStatus.Running, "execution", false, "真实任务详情")),
        operations = listOf(AssistantOperation("execution", "Execution", "task", AssistantOperationPhase.Running, false, "native-task", null, null)),
        memories = listOf(AssistantMemory("memory", "已保存的真实记忆")),
    )

    private fun content(
        state: () -> AssistantUiState,
        actions: MutableList<AssistantAction>,
        opens: MutableList<Pair<String, String>> = mutableListOf(),
        onBack: () -> Unit = {},
    ) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                PocketTheme("light") {
                    CompositionLocalProvider(LocalPocketGlass provides false) {
                        AssistantScreen(state(), emptyList(), runtimes, workspaces,
                            { actions += it }, { runtime, thread -> opens += runtime to thread }, onBack,
                            nowMs = 1_893_456_000_000L)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun draftAndSelectedProjectSurviveMemoryAndSettingsBack() {
        val actions = mutableListOf<AssistantAction>()
        var exits = 0
        content({ AssistantUiState(snapshot) }, actions, onBack = { exits++ })
        val draft = "先核对当前项目\n保留这段未发送的草稿"
        compose.onNodeWithTag("assistant-message-input").performTextInput(draft)
        compose.selectAssistantWorkspace("authorized-thread")

        listOf("Memory", "Settings").forEach { page ->
            compose.openAssistantPage(page)
            compose.onNodeWithTag("assistant-message-input").assertDoesNotExist()
            compose.returnToAssistantChat()
            compose.onNodeWithTag("assistant-message-input").assertTextEquals(draft)
            compose.onNodeWithTag("assistant-workspace").assertIsDisplayed().assertTextContains("已有项目", substring = true)
            assertEquals(0, exits)
            assertTrue(actions.isEmpty())
        }

        compose.onNodeWithTag("assistant-send").performClick()
        assertEquals("assistant/send", actions.single().method)
        assertEquals("r", actions.single().params.string("runtimeId"))
        assertEquals("authorized-thread", actions.single().params.string("threadId"))
        assertEquals(draft, actions.single().params.string("message"))
        assertFalse(actions.single().params.containsKey("path"))
        compose.onNodeWithContentDescription("返回原页面").performClick()
        assertEquals(1, exits)
    }

    @Test fun workspaceSelectionClosesToolsAndLeavesAReusableContextChip() {
        val actions = mutableListOf<AssistantAction>()
        content({ AssistantUiState(snapshot) }, actions)
        compose.onNodeWithTag("assistant-workspace").assertDoesNotExist()
        compose.onNodeWithTag("assistant-tools-panel").assertDoesNotExist()
        compose.onNodeWithTag("assistant-workspace:authorized-thread").assertDoesNotExist()
        compose.onNodeWithTag("assistant-tools").performClick()
        compose.onNodeWithTag("assistant-tools-panel").assertIsDisplayed()
        compose.selectAssistantWorkspace("authorized-thread")
        // Exactly one chip must remain, and the chooser must be dismissed.
        compose.onAllNodesWithTag("assistant-workspace").assertCountEquals(1)
        compose.onNodeWithTag("assistant-workspace").assertTextContains("已有项目", substring = true)
        compose.onNodeWithTag("assistant-tools-panel").assertDoesNotExist()
        compose.onNodeWithTag("assistant-workspace:authorized-thread").assertDoesNotExist()

        // Selecting from the chip must not reopen the tools panel behind the chooser.
        compose.selectAssistantWorkspace("none")
        compose.onNodeWithTag("assistant-workspace").assertDoesNotExist()
        compose.onNodeWithTag("assistant-tools-panel").assertDoesNotExist()
        compose.selectAssistantWorkspace("authorized-thread")
        compose.onNodeWithTag("assistant-workspace").assertTextContains("已有项目", substring = true)
        compose.onNodeWithTag("assistant-tools-panel").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    @Test fun offlineDraftRemainsEditableWhileSendIsBlocked() {
        val actions = mutableListOf<AssistantAction>()
        content({ AssistantUiState(snapshot, error = "连接电脑后可继续使用助理") }, actions)
        compose.onNodeWithTag("assistant-message-input").assertIsEnabled().performTextInput("离线草稿")
        compose.onNodeWithTag("assistant-message-input").performTextReplacement("离线修改后的草稿")
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("离线修改后的草稿")
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled().performTouchInput { click() }
        assertTrue(actions.isEmpty())
        compose.openAssistantPage("Memory")
        compose.returnToAssistantChat()
        compose.onNodeWithTag("assistant-message-input").assertIsEnabled().assertTextEquals("离线修改后的草稿")
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
    }

    @Test fun repeatedSendStaysBlockedUntilTheMatchingDraftIsAcknowledged() {
        val actions = mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot, sendAcknowledgement = 7))
        content({ state }, actions)
        compose.onNodeWithTag("assistant-message-input").performTextInput("只发送一次")
        compose.onNodeWithTag("assistant-send").performClick()
        assertEquals("assistant/send", actions.single().method)

        // The local latch covers the interval before the repository publishes pendingActions.
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)
        compose.runOnIdle { state = state.copy(pendingActions = setOf("assistant/send")) }
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)
        compose.runOnIdle { state = state.copy(pendingActions = emptySet()) }
        compose.openAssistantPage("Settings")
        compose.returnToAssistantChat()
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("只发送一次")
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)

        compose.runOnIdle { state = state.copy(sendAcknowledgement = 8) }
        compose.onNodeWithTag("assistant-message-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
        compose.onNodeWithTag("assistant-message-input").performTextInput("只发送一次")
        compose.onNodeWithTag("assistant-send").assertIsEnabled().performClick()
        assertEquals(listOf("assistant/send", "assistant/send"), actions.map { it.method })
        assertEquals(listOf("只发送一次", "只发送一次"), actions.map { it.params.string("message") })
    }

    @Test fun acknowledgementKeepsANewerDraftEditableAndSendable() {
        val actions = mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot))
        content({ state }, actions)
        compose.onNodeWithTag("assistant-message-input").performTextInput("上一条消息")
        compose.onNodeWithTag("assistant-send").performClick()
        compose.onNodeWithTag("assistant-message-input").assertIsEnabled().performTextReplacement("还未发送的新草稿")
        // Editing must not bypass the local latch before pendingActions reaches the UI.
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)
        compose.runOnIdle { state = state.copy(pendingActions = setOf("assistant/send")) }
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)
        compose.runOnIdle { state = state.copy(pendingActions = emptySet()) }
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)
        compose.runOnIdle { state = state.copy(pendingActions = emptySet(), sendAcknowledgement = 1) }
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("还未发送的新草稿")
        compose.onNodeWithTag("assistant-send").assertIsEnabled().performClick()
        assertEquals(listOf("上一条消息", "还未发送的新草稿"), actions.map { it.params.string("message") })
    }

    @Test fun moreMenuAndChildPageNavigationNeverDispatchActionsOrOpenThreads() {
        val actions = mutableListOf<AssistantAction>()
        val opens = mutableListOf<Pair<String, String>>()
        var exits = 0
        content({ AssistantUiState(snapshot) }, actions, opens, onBack = { exits++ })
        compose.onNodeWithTag("assistant-pocket-button").assertDoesNotExist()
        compose.onNodeWithTag("assistant-tab:Chat").assertDoesNotExist()
        val pages = listOf("Memory", "Schedules", "Settings", "Tasks")
        pages.forEach { compose.onNodeWithTag("assistant-tab:$it").assertDoesNotExist() }
        pages.forEach { page ->
            compose.onNodeWithTag("assistant-more").performClick()
            pages.forEach { compose.onNodeWithTag("assistant-tab:$it").assertIsDisplayed() }
            compose.onNodeWithTag("assistant-original-thread").assertIsDisplayed()
            compose.onNodeWithTag("assistant-refresh").assertIsDisplayed()
            assertTrue(actions.isEmpty())
            assertTrue(opens.isEmpty())
            compose.onNodeWithTag("assistant-tab:$page").performClick()
            compose.onNodeWithTag("assistant-message-input").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回助理对话").assertIsDisplayed()
            pages.forEach { compose.onNodeWithTag("assistant-tab:$it").assertDoesNotExist() }
            assertTrue(actions.isEmpty())
            assertTrue(opens.isEmpty())
            assertEquals(0, exits)
        }
        compose.returnToAssistantChat()
        compose.onNodeWithTag("assistant-message-input").assertIsDisplayed()
        assertTrue(actions.isEmpty())
        assertTrue(opens.isEmpty())
        assertEquals(0, exits)
    }

    @Test fun allTasksMenuShowsCompactCardsAndExpansionDoesNotExecuteThem() {
        val actions = mutableListOf<AssistantAction>()
        val opens = mutableListOf<Pair<String, String>>()
        content({ AssistantUiState(snapshot) }, actions, opens)
        compose.openAssistantPage("Tasks")
        compose.onNodeWithTag("assistant-task:task").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("assistant-task-pause:task").assertDoesNotExist()
        compose.onNodeWithTag("assistant-task-thread:task").assertDoesNotExist()
        compose.onNodeWithText("真实任务详情").assertDoesNotExist()
        compose.expandAssistantTask("task")
        compose.onNodeWithText("真实任务详情").assertIsDisplayed()
        compose.onNodeWithTag("assistant-task-pause:task").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("assistant-task-thread:task").assertIsDisplayed()
        assertTrue(actions.isEmpty())
        assertTrue(opens.isEmpty())
        compose.onNodeWithTag("assistant-task-thread:task").performClick()
        assertEquals(listOf("r" to "native-task"), opens)
        assertTrue(actions.isEmpty())
        compose.onNodeWithTag("assistant-task-pause:task").performClick()
        assertEquals("assistant/task/pause", actions.single().method)
        assertEquals("task", actions.single().params.string("taskId"))
    }
}
