package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
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

/** Deterministic UI actions only; no repository, transport, or model execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantChatNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val runtimes = listOf(AssistantRuntimeChoice("r", "我的电脑"))
    private val workspaces = listOf(AssistantWorkspaceChoice("r", "authorized-thread", "已有项目", true))
    private val snapshot = AssistantSnapshot(
        enabled = true, ready=true, runtimeId = "r", coordinatorThreadId = "coordinator", paused = false,
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
        connected: Boolean = true,
    ) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                PocketTheme("light") {
                    CompositionLocalProvider(LocalPocketGlass provides false) {
                        AssistantScreen(state(), emptyList(), runtimes, workspaces,
                            { actions += it }, { runtime, thread -> opens += runtime to thread }, onBack,
                            nowMs = 1_893_456_000_000L, connected = connected)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun desktopProjectIsSelectableForTheDedicatedAssistantWithoutRewritingItsSource() {
        val actions=mutableListOf<AssistantAction>()
        content({AssistantUiState(snapshot.copy(runtimeId="managed"))},actions)
        compose.selectAssistantWorkspace("authorized-thread")
        compose.onNodeWithTag(AssistantDraft).performTextInput("Read this selected project")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled().performClick()
        assertEquals("managed",actions.single().params.string("runtimeId"))
        assertEquals("r",actions.single().params.string("sourceRuntimeId"))
        assertEquals("authorized-thread",actions.single().params.string("threadId"))
    }

    @Test fun draftAndSelectedProjectSurviveMemoryAndSettingsBack() {
        val actions = mutableListOf<AssistantAction>()
        var exits = 0
        content({ AssistantUiState(snapshot) }, actions, onBack = { exits++ })
        val draft = "先核对当前项目\n保留这段未发送的草稿"
        compose.onNodeWithTag(AssistantDraft).performTextInput(draft)
        compose.selectAssistantWorkspace("authorized-thread")

        listOf("Memory", "Settings").forEach { page ->
            compose.openAssistantPage(page)
            compose.onNodeWithTag(AssistantDraft).assertDoesNotExist()
            compose.returnToAssistantChat()
            compose.onNodeWithTag(AssistantDraft).assertTextEquals(draft)
            compose.onNodeWithTag("assistant-workspace-project").assertIsDisplayed().assert(hasText("已有项目"))
            assertEquals(0, exits)
            assertTrue(actions.isEmpty())
        }

        compose.onNodeWithTag(AssistantSend).performClick()
        assertEquals("assistant/send", actions.single().method)
        assertEquals("r", actions.single().params.string("runtimeId"))
        assertEquals("authorized-thread", actions.single().params.string("threadId"))
        assertEquals(draft, actions.single().params.string("message"))
        assertFalse(actions.single().params.containsKey("path"))
        compose.onNodeWithTag("assistant-back").performClick()
        assertEquals(1, exits)
    }

    @Test fun workspaceSelectionClosesToolsAndLeavesAReusableContextChip() {
        val actions = mutableListOf<AssistantAction>()
        content({ AssistantUiState(snapshot) }, actions)
        compose.onNodeWithTag("assistant-workspace-project").assertDoesNotExist()
        compose.onNodeWithTag("assistant-tools-panel").assertDoesNotExist()
        compose.onNodeWithTag("assistant-workspace-project:authorized-thread").assertDoesNotExist()
        compose.onNodeWithTag("assistant-tools").performClick()
        compose.onNodeWithTag("assistant-tools-panel").assertIsDisplayed()
        compose.onNodeWithTag("assistant-workspace-project:authorized-thread").assertIsDisplayed().performClick()
        // Exactly one chip must remain, and the chooser must be dismissed.
        compose.onAllNodesWithTag("assistant-workspace-project").assertCountEquals(1)
        compose.onNodeWithTag("assistant-workspace-project").assert(hasText("已有项目"))
        compose.onNodeWithTag("assistant-tools-panel").assertDoesNotExist()
        compose.onNodeWithTag("assistant-workspace-project:authorized-thread").assertDoesNotExist()

        // The chip reopens the chooser; the assistant's own workspace removes the chip.
        compose.onNodeWithTag("assistant-workspace-project").performClick()
        compose.onNodeWithTag("assistant-workspace-project:none").assertIsDisplayed().performClick()
        compose.onNodeWithTag("assistant-workspace-project").assertDoesNotExist()
        compose.onNodeWithTag("assistant-tools-panel").assertDoesNotExist()
        compose.selectAssistantWorkspace("authorized-thread")
        compose.onNodeWithTag("assistant-workspace-project").assert(hasText("已有项目"))
        compose.onNodeWithTag("assistant-tools-panel").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    @Test fun offlineDraftRemainsEditableWhileSendIsBlocked() {
        val actions = mutableListOf<AssistantAction>()
        content({ AssistantUiState(snapshot, error = "连接电脑后可继续使用助理") }, actions, connected = false)
        compose.onNodeWithTag(AssistantDraft).assertIsEnabled().performTextInput("离线草稿")
        compose.onNodeWithTag(AssistantDraft).performTextReplacement("离线修改后的草稿")
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("离线修改后的草稿")
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled().performTouchInput { click() }
        assertTrue(actions.isEmpty())
        compose.openAssistantPage("Memory")
        compose.returnToAssistantChat()
        compose.onNodeWithTag(AssistantDraft).assertIsEnabled().assertTextEquals("离线修改后的草稿")
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled()
    }

    @Test fun repeatedSendStaysBlockedUntilTheMatchingDraftIsAcknowledged() {
        val actions = mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot))
        content({ state }, actions)
        compose.onNodeWithTag(AssistantDraft).performTextInput("只发送一次")
        compose.onNodeWithTag(AssistantSend).performClick()
        assertEquals("assistant/send", actions.single().method)

        // The local latch covers the interval before the repository publishes pendingActions.
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)
        compose.runOnIdle { state = state.copy(pendingActions = setOf("assistant/send")) }
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)
        compose.runOnIdle { state = state.copy(pendingActions = emptySet()) }
        compose.openAssistantPage("Settings")
        compose.returnToAssistantChat()
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("只发送一次")
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)

        compose.runOnIdle { state = state.acceptSend(actions.single()) }
        compose.onNodeWithTag(AssistantDraft).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled()
        compose.onNodeWithTag(AssistantDraft).performTextInput("只发送一次")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled().performClick()
        assertEquals(listOf("assistant/send", "assistant/send"), actions.map { it.method })
        assertEquals(listOf("只发送一次", "只发送一次"), actions.map { it.params.string("message") })
    }

    @Test fun submittedFeedbackDoesNotDiscardTheDraftAwaitingAcknowledgement() {
        val actions=mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot))
        content({state},actions)
        compose.onNodeWithTag(AssistantDraft).performTextInput("一次发送")
        compose.onNodeWithTag(AssistantSend).performClick()
        compose.runOnIdle {state=state.copy(pendingActions=setOf("assistant/send"),error="原操作结果尚未确认，正在核对；不会重复提交")}
        compose.runOnIdle {state=state.copy(pendingActions=emptySet(),error=null).acceptSend(actions.single())}
        compose.onNodeWithTag(AssistantDraft).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        assertEquals(1,actions.size)
    }

    @Test fun acknowledgementKeepsANewerDraftEditableAndSendable() {
        val actions = mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot))
        content({ state }, actions)
        compose.onNodeWithTag(AssistantDraft).performTextInput("上一条消息")
        compose.onNodeWithTag(AssistantSend).performClick()
        compose.onNodeWithTag(AssistantDraft).assertIsEnabled().performTextReplacement("还未发送的新草稿")
        // Editing must not bypass the local latch before pendingActions reaches the UI.
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)
        compose.runOnIdle { state = state.copy(pendingActions = setOf("assistant/send")) }
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)
        compose.runOnIdle { state = state.copy(pendingActions = emptySet()) }
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, actions.size)
        compose.runOnIdle { state = state.copy(pendingActions = emptySet()).acceptSend(actions.single()) }
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("还未发送的新草稿")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled().performClick()
        assertEquals(listOf("上一条消息", "还未发送的新草稿"), actions.map { it.params.string("message") })
    }

    @Test fun foreignOrOldReceiptsCannotConsumeThePendingComposerAndRejectionKeepsText() {
        val actions=mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot,computerId="pc"))
        content({state},actions)
        compose.onNodeWithTag(AssistantDraft).performTextInput("保留原稿")
        compose.onNodeWithTag(AssistantSend).performClick()
        val receipt=state.acceptSend(actions.single()).sendReceipts.single()
        for(other in listOf(receipt.copy(commandId="old"),receipt.copy(computerId="other"),receipt.copy(runtimeId="other"),receipt.copy(conversationId="other"))) {
            compose.runOnIdle {state=state.copy(sendReceipts=listOf(other))}
            compose.onNodeWithTag(AssistantDraft).assertTextEquals("保留原稿")
            compose.onNodeWithTag(AssistantSend).assertIsNotEnabled()
        }
        compose.runOnIdle {state=state.acceptSend(actions.single(),"rejected")}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("保留原稿")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled().performClick()
        assertEquals(2,actions.size)
        assertNotEquals(actions[0].commandId,actions[1].commandId)
    }

    /** The sent draft is recognised by its text, which is what survives a restart: typing the same text again is still that draft. */
    @Test fun editingAwayKeepsTheNewTextAndEditingBackIsTheSentDraftAgain() {
        val actions=mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot))
        content({state},actions)
        compose.onNodeWithTag(AssistantDraft).performTextInput("相同内容")
        compose.onNodeWithTag(AssistantSend).performClick()
        compose.onNodeWithTag(AssistantDraft).performTextReplacement("新内容")
        compose.onNodeWithTag(AssistantDraft).performTextReplacement("相同内容")
        compose.runOnIdle {state=state.acceptSend(actions.single())}
        compose.onNodeWithTag(AssistantDraft).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        compose.onNodeWithTag(AssistantDraft).performTextInput("新内容")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled()
        assertEquals(1,actions.size)
    }

    @Test fun moreMenuAndChildPageNavigationNeverDispatchActionsOrOpenThreads() {
        val actions = mutableListOf<AssistantAction>()
        val opens = mutableListOf<Pair<String, String>>()
        var exits = 0
        content({ AssistantUiState(snapshot) }, actions, opens, onBack = { exits++ })
        compose.onNodeWithTag("assistant-pocket-button").assertDoesNotExist()
        listOf("Main", "Tasks", "Artifacts", "Plans").forEach { compose.onNodeWithTag("assistant-nav:$it").assertDoesNotExist() }
        val pages = listOf("Memory", "Artifacts", "Settings", "Tasks")
        pages.forEach { compose.onNodeWithTag("assistant-menu:$it").assertDoesNotExist() }
        pages.forEach { page ->
            compose.onNodeWithTag("assistant-title-menu").performClick()
            pages.forEach { compose.onNodeWithTag("assistant-menu:$it").assertIsDisplayed() }
            compose.onNodeWithTag("assistant-menu:Refresh").assertIsDisplayed()
            assertTrue(actions.isEmpty())
            assertTrue(opens.isEmpty())
            compose.onNodeWithTag("assistant-menu:$page").performClick()
            compose.onNodeWithTag(AssistantDraft).assertDoesNotExist()
            compose.onNodeWithTag("assistant-back").assertIsDisplayed()
            pages.forEach { compose.onNodeWithTag("assistant-menu:$it").assertDoesNotExist() }
            assertTrue(actions.isEmpty())
            assertTrue(opens.isEmpty())
            assertEquals(0, exits)
            compose.returnToAssistantChat()
        }
        // Plans have their own entry on the bar.
        compose.onNodeWithTag("assistant-schedules").performClick()
        compose.onNodeWithTag("assistant-schedules-list").assertIsDisplayed()
        compose.returnToAssistantChat()
        compose.onNodeWithTag(AssistantDraft).assertIsDisplayed()
        assertTrue(actions.isEmpty())
        assertTrue(opens.isEmpty())
        assertEquals(0, exits)
    }

    @Test fun allTasksMenuShowsCompactCardsAndOpeningOneDoesNotExecuteIt() {
        val actions = mutableListOf<AssistantAction>()
        val opens = mutableListOf<Pair<String, String>>()
        content({ AssistantUiState(snapshot) }, actions, opens)
        compose.openAssistantPage("Tasks")
        compose.onNodeWithTag("assistant-task:task").assertIsDisplayed()
        compose.onNodeWithTag("assistant-task-stop:task").assertDoesNotExist()
        compose.onNodeWithText("真实任务详情").assertDoesNotExist()
        compose.onNodeWithTag("assistant-task-open:task").assertHasClickAction().performClick()
        compose.onNodeWithText("真实任务详情").assertIsDisplayed()
        val page = compose.onNodeWithTag("assistant-task-detail-page:task")
        page.performScrollToNode(hasTestTag("assistant-task-stop:task"))
        compose.onNodeWithTag("assistant-task-stop:task").assertIsDisplayed().assertIsEnabled()
        assertTrue(actions.isEmpty())
        assertTrue(opens.isEmpty())
        page.performScrollToNode(hasTestTag("assistant-task-section:Process"))
        compose.onNodeWithTag("assistant-task-section:Process").performClick()
        page.performScrollToNode(hasText("完整执行过程"))
        compose.onNodeWithText("完整执行过程").performClick()
        assertEquals(listOf("r" to "native-task"), opens)
        assertTrue(actions.isEmpty())
        page.performScrollToNode(hasTestTag("assistant-task-stop:task"))
        compose.onNodeWithTag("assistant-task-stop:task").performClick()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("确认停止").performClick()
        assertEquals("assistant/task/pause", actions.single().method)
        assertEquals("task", actions.single().params.string("taskId"))
    }
}
