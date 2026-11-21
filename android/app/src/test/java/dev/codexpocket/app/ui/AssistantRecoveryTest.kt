package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
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
class AssistantRecoveryTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val actions=mutableListOf<AssistantAction>()
    private val recovery=AssistantRecoveryRequest("original","n:1")
    private val snapshot=AssistantSnapshot(enabled=true,ready=false,runtimeId="assistant",
        errorCode="service_reconciliation_required",preparationStatus="recoverable_error",
        operations=listOf(AssistantOperation("original","Coordinator",null,AssistantOperationPhase.Completed,false,"thread",null,"Original result")),
        events=listOf(AssistantEvent(1,"done","original","completed","",1234)),recoveries=listOf(recovery))
    private fun content(state:()->AssistantUiState,connected:()->Boolean={true}) {
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlass provides false) {
                AssistantScreen(state(),emptyList(),emptyList(),emptyList(),actions::add,{_,_->},{},connected=connected())
            }
        }}}
        compose.waitForIdle()
    }
    @Test fun completedOperationOffersOnlyExplicitOriginalRejection() {
        content(state={AssistantUiState(snapshot,error=snapshot.preparationIssue)})
        assertTrue(actions.isEmpty())
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-recovery-end:original:n:1"))
        compose.onNodeWithTag("assistant-recovery-end:original:n:1").assertIsEnabled().performClick()
        val action=actions.single()
        assertEquals("assistant/respond",action.method)
        assertEquals(JsonPrimitive("original"),action.params["operationId"])
        assertEquals(JsonPrimitive("n:1"),action.params["requestId"])
        assertEquals(JsonPrimitive(false),action.params["response"]!!.jsonObject["Approval"]!!.jsonObject["approved"])
        assertEquals(AssistantOperationPhase.Completed,snapshot.operations.single().phase)
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
    }
    @Test fun offlinePendingAndFailedReadsDisableRecoveryWithoutAutomaticSend() {
        var online by mutableStateOf(false)
        var state by mutableStateOf(AssistantUiState(snapshot,error=snapshot.preparationIssue))
        content({state},{online})
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-recovery-end:original:n:1"))
        val button=compose.onNodeWithTag("assistant-recovery-end:original:n:1")
        button.assertIsNotEnabled()
        compose.runOnIdle {online=true;state=state.copy(pendingActions=setOf("assistant/respond:original"))}
        button.assertIsNotEnabled()
        compose.runOnIdle {state=state.copy(pendingActions=emptySet(),error="助理状态暂未读取")}
        button.assertIsNotEnabled()
        compose.runOnIdle {state=state.copy(error=snapshot.preparationIssue)}
        button.assertIsEnabled()
        assertTrue(actions.isEmpty())
    }
    @Test fun durableUnknownKeepsRecoveryFeedbackAndButtonDisabledAfterRefresh() {
        var state by mutableStateOf(AssistantUiState(snapshot,error=snapshot.preparationIssue,unconfirmedActions=setOf("assistant/respond")))
        content({state})
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-recovery-end:original:n:1"))
        compose.onNodeWithTag("assistant-recovery-end:original:n:1").assertIsNotEnabled()
        compose.onNodeWithText("结果尚未确认，不能重复发送").assertExists()
        compose.runOnIdle {state=state.copy(snapshot=snapshot.copy(errorCode="assistant_account_unavailable"),error=snapshot.preparationIssue)}
        compose.onNodeWithTag("assistant-recovery-end:original:n:1").assertIsNotEnabled()
        compose.onNodeWithText("结果尚未确认，不能重复发送").assertExists()
        assertTrue(actions.isEmpty())
    }
}
