package dev.codexpocket.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import dev.codexpocket.app.data.*

/** Tags of the stage's composer, shared by every test that types or sends. */
internal const val AssistantDraft = "assistant-workspace-draft"
internal const val AssistantSend = "assistant-workspace-send"

internal fun ComposeTestRule.onAssistantMain() = onAllNodesWithTag(AssistantDraft).fetchSemanticsNodes().isNotEmpty()

/** Plans open from the bar; tasks, results, memory and settings from the more menu. Pages are pushed, so start from the stage. */
internal fun ComposeTestRule.openAssistantPage(page: String) {
    returnToAssistantChat()
    val destination = assistantDestination(page)
    if (destination == "Plans") onNodeWithTag("assistant-schedules").assertIsDisplayed().performClick()
    else {
        onNodeWithTag("assistant-title-menu").assertIsDisplayed().performClick()
        onNodeWithTag("assistant-menu:$destination").assertIsDisplayed().performClick()
    }
    waitForIdle()
}

internal fun AssistantUiState.acceptSend(action: AssistantAction, status: String = "accepted"): AssistantUiState {
    val binding = action.composerBinding ?: requireNotNull(snapshot).composerBinding(computerId)
    return copy(sendReceipts = listOf(AssistantSendReceipt(requireNotNull(action.commandId), binding.computerId, binding.runtimeId, binding.conversationId, status)))
}

/** Back, one level at a time, until the conversation and its composer are on screen again. */
internal fun ComposeTestRule.returnToAssistantChat() {
    repeat(6) { if (!onAssistantMain()) { onNodeWithTag("assistant-back").assertIsDisplayed().performClick(); waitForIdle() } }
    onNodeWithTag(AssistantDraft).assertIsDisplayed()
}

/** `＋` opens the project list; "none" is the assistant's own workspace. Choosing closes the sheet. */
internal fun ComposeTestRule.selectAssistantWorkspace(threadId: String) {
    onNodeWithTag("assistant-tools").assertIsDisplayed().performClick()
    onNodeWithTag("assistant-workspace-project:$threadId").assertIsDisplayed().performClick()
    waitForIdle()
}

internal fun ComposeTestRule.scrollAssistantChatTo(tag: String): SemanticsNodeInteraction {
    onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag(tag))
    return onNodeWithTag(tag)
}

/** Opens a task's card in the conversation, where its summary, change and actions are. */
internal fun ComposeTestRule.expandAssistantTask(taskId: String) {
    scrollAssistantChatTo("assistant-chat-task:$taskId")
    if (onAllNodesWithTag("assistant-chat-task-open:$taskId").fetchSemanticsNodes().isEmpty()) onNodeWithTag("assistant-chat-task-toggle:$taskId").performClick()
    waitForIdle()
}

/** The light theme with glass on its solid fallback: software rendering cannot replay the backdrop graph, and pictures taken through it ghost. */
@androidx.compose.runtime.Composable
internal fun AssistantTestTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    PocketTheme("light") { androidx.compose.runtime.CompositionLocalProvider(LocalPocketGlass provides false, content = content) }
}
