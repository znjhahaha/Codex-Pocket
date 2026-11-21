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
@Config(sdk=[35],qualifiers="w320dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantEndedQuestionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val actions=mutableListOf<AssistantAction>()
    private fun snapshot(resolution:String="unresolved",answer:String="not_submitted",phase:String="Completed",terminal:String="completed",recordOperation:String="original") = AssistantSnapshot.from(Json.parseToJsonElement("""
        {"enabled":true,"ready":true,"runtimeId":"assistant","operations":[{
          "id":"original","role":"Coordinator","phase":"$phase","suspended":false,
          "expected_generation":"generation","mapping":{"host_generation":"generation","thread_id":"thread","turn_id":"turn"},
          "result":"Waiting for your choice.","held_inputs":[{
            "operation_id":"$recordOperation","mapping":{"host_generation":"generation","thread_id":"thread","turn_id":"turn"},
            "call_id":"original-call","question":{"request_id":"choice","kind":"Input","text":"Choose alphabetical or reverse order."},
            "native_terminal":"$terminal","resolution":"$resolution","answer_state":"$answer"
          }]}],"events":[{"id":"done","sequence":1,"operation_id":"original","kind":"completed","detail":"","at_ms":1234}]}
    """).jsonObject)
    private fun content(state:()->AssistantUiState,theme:()->String={"light"}) {
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme(theme()) {
            CompositionLocalProvider(LocalPocketGlass provides false) {
                AssistantScreen(state(),emptyList(),emptyList(),emptyList(),actions::add,{_,_->},{},connected=true)
            }
        }}}
        compose.waitForIdle()
    }
    @Test fun nativeFinalKeepsOriginalQuestionAndNeverReopensAnswer() {
        content({AssistantUiState(snapshot())})
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-ended-question:original:original-call"))
        compose.onNodeWithText("Choose alphabetical or reverse order.").assertExists()
        compose.onNodeWithText("这轮对话已结束").assertExists()
        compose.onNodeWithText("这轮结束时未提交回答，原请求尚未确认结束。").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1) // Only the ordinary new-message composer.
        assertTrue(actions.isEmpty())
    }
    @Test fun resolvedRequestKeepsUnknownAnswerAcrossReadFailureAndThemeChange() {
        var state by mutableStateOf(AssistantUiState(snapshot(answer="unknown"),unconfirmedActions=setOf("assistant/respond")))
        var theme by mutableStateOf("light")
        content({state},{theme})
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-ended-question:original:original-call"))
        compose.onNodeWithText("回答结果尚未确认，请先核对原请求。").assertExists()
        compose.runOnIdle {state=state.copy(snapshot=snapshot("resolved","unknown"),readError="助理状态暂未读取");theme="dark"}
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-ended-question:original:original-call"))
        compose.onNodeWithText("原请求已结束，回答是否送达仍待核对。").assertExists()
        compose.onNodeWithText("Choose alphabetical or reverse order.").assertExists()
        compose.onAllNodes(hasTestTag("assistant-ended-question:original:original-call")).assertCountEquals(1)
        assertTrue(actions.isEmpty())
    }
    @Test fun foreignRecordCannotSupplyQuestionTextForThisOperation() {
        content({AssistantUiState(snapshot(recordOperation="foreign"))})
        compose.onNodeWithText("Choose alphabetical or reverse order.").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }
}
