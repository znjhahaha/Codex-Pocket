package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantHousekeeperTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val snapshot=AssistantSnapshot(enabled=true,ready=true,runtimeId="r",
        tasks=listOf(AssistantTask("task","Review the project",AssistantTaskStatus.Running,"run",false,"Evidence and results")),
        operations=listOf(AssistantOperation("run","Execution","task",AssistantOperationPhase.Running,false,"native",null,null)))
    private fun content(state:()->AssistantUiState) {
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlass provides false) {
                AssistantScreen(state(),emptyList(),emptyList(),emptyList(),{}, {_,_->}, {})
            }
        }}}
        compose.waitForIdle()
    }
    @Test fun destinationsOpenFromTheBarAndTheMenuAndReturnToTheStage() {
        content {AssistantUiState(snapshot)}
        // Plans are one tap away on the bar; the other pages are listed only once the menu is opened.
        compose.onNodeWithTag("assistant-schedules").assertIsDisplayed()
        listOf("Tasks","Artifacts","Memory","Settings").forEach {compose.onNodeWithTag("assistant-menu:$it").assertDoesNotExist()}
        mapOf("Tasks" to "assistant-tasks-list","Artifacts" to "assistant-artifact-filter","Plans" to "assistant-schedules-list","Memory" to "assistant-memory-list").forEach {(page,tag) ->
            compose.openAssistantPage(page)
            compose.onNodeWithTag(tag).assertIsDisplayed()
            compose.onNodeWithTag(AssistantDraft).assertDoesNotExist()
            compose.onNodeWithTag("assistant-menu:$page").assertDoesNotExist()
            compose.returnToAssistantChat()
        }
    }
    @Test fun stepDisclosureOpensSeparateDetailAndRetainsDraftOnReturn() {
        content {AssistantUiState(snapshot)}
        compose.onNodeWithTag(AssistantDraft).performTextInput("Keep this draft")
        compose.scrollAssistantChatTo("assistant-chat-task:task")
        // Collapsed, the card names the task and its stage; the actions come with the disclosure.
        compose.onNodeWithTag("assistant-chat-task-open:task").assertDoesNotExist()
        compose.onNodeWithText("Evidence and results").assertDoesNotExist()
        compose.onNodeWithTag("assistant-chat-task-toggle:task").performClick()
        compose.onNodeWithText("Evidence and results").assertExists()
        compose.onNodeWithTag("assistant-chat-task-open:task").performScrollTo().performClick()
        compose.onNodeWithTag("assistant-task-detail-page:task").assertIsDisplayed()
        compose.onNodeWithTag(AssistantDraft).assertDoesNotExist()
        compose.returnToAssistantChat()
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("Keep this draft")
        compose.onNodeWithTag("assistant-chat-task-open:task").assertExists()
    }
    @Test fun loginStageHasConcreteRecoveryAndKeepsDraftUntilReady() {
        var state by mutableStateOf(AssistantUiState(snapshot.copy(ready=false,errorCode="assistant_needs_login",preparationStatus="needs_login",
            startupStages=listOf(AssistantStartupStage("runtime","ready"),AssistantStartupStage("account","blocked","login_with_codex_cli"),
                AssistantStartupStage("capabilities","ready"),AssistantStartupStage("coordinator","waiting")))))
        content {state}
        compose.onNodeWithTag(AssistantDraft).performTextInput("Saved draft")
        compose.scrollAssistantChatTo("assistant-prepare")
        listOf("runtime","account","capabilities","coordinator").forEach {id -> compose.onNodeWithTag("assistant-stage:$id").assertIsDisplayed()}
        compose.onNodeWithTag("assistant-stage-recovery").assertTextContains("在电脑端用现有 Codex 配置登录",substring=true)
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled()
        compose.runOnIdle {state=AssistantUiState(snapshot.copy(startupStages=state.snapshot!!.startupStages.map {it.copy(status="ready",recoveryAction=null)}))}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("Saved draft")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled()
    }
}
