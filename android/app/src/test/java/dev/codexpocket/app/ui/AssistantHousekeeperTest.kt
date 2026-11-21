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
    @Test fun primaryDestinationsStayVisibleWithoutOpeningOverflow() {
        content {AssistantUiState(snapshot)}
        listOf("Tasks","Pending","Results").forEach {page ->
            compose.onNodeWithTag("assistant-shortcut:$page").assertIsDisplayed()
            compose.onNodeWithTag("assistant-tab:$page").assertDoesNotExist()
        }
        listOf("Tasks","Pending","Results").forEach {page ->
            compose.onNodeWithTag("assistant-shortcut:$page").performClick()
            compose.onNodeWithTag("assistant-task-center").assertIsDisplayed()
            compose.onNodeWithTag("assistant-center:$page").assertIsSelected()
            compose.onNodeWithTag("assistant-center-schedules").assertIsDisplayed()
            compose.onNodeWithTag("assistant-message-input").assertDoesNotExist()
            compose.returnToAssistantChat()
        }
        compose.openAssistantPage("Memory")
        compose.onNodeWithTag("assistant-memory-list").assertIsDisplayed()
        compose.onNodeWithTag("assistant-message-input").assertDoesNotExist()
        compose.returnToAssistantChat()
    }
    @Test fun stepDisclosureOpensSeparateDetailAndRetainsDraftOnReturn() {
        content {AssistantUiState(snapshot)}
        compose.onNodeWithTag("assistant-message-input").performTextInput("Keep this draft")
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-task-expand:task"))
        compose.onNodeWithTag("assistant-task-expand:task").performClick()
        compose.onNodeWithTag("assistant-task-full-prompt:task").assertDoesNotExist()
        compose.onNodeWithTag("assistant-task-detail:task").performClick()
        compose.onNodeWithTag("assistant-task-detail-page:task").assertIsDisplayed()
        compose.onNodeWithTag("assistant-message-input").assertDoesNotExist()
        compose.returnToAssistantChat()
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("Keep this draft")
        compose.onNodeWithTag("assistant-task-detail:task").assertExists()
    }
    @Test fun loginStageHasConcreteRecoveryAndKeepsDraftUntilReady() {
        var state by mutableStateOf(AssistantUiState(snapshot.copy(ready=false,errorCode="assistant_needs_login",preparationStatus="needs_login",
            startupStages=listOf(AssistantStartupStage("runtime","ready"),AssistantStartupStage("account","blocked","login_with_codex_cli"),
                AssistantStartupStage("capabilities","ready"),AssistantStartupStage("coordinator","waiting")))))
        content {state}
        compose.onNodeWithTag("assistant-message-input").performTextInput("Saved draft")
        listOf("runtime","account","capabilities","coordinator").forEach {id -> compose.onNodeWithTag("assistant-stage:$id").assertIsDisplayed()}
        compose.onNodeWithTag("assistant-stage-recovery").assertTextContains("在电脑端用现有 Codex 配置登录",substring=true)
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
        compose.runOnIdle {state=AssistantUiState(snapshot.copy(startupStages=state.snapshot!!.startupStages.map {it.copy(status="ready",recoveryAction=null)}))}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("Saved draft")
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
    }
}
