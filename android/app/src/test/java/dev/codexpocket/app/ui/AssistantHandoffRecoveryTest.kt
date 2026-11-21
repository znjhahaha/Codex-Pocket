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
class AssistantHandoffRecoveryTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val actions=mutableListOf<AssistantAction>()
    private fun snapshot(offer:Boolean=true,phase:String="Paused",mapping:JsonElement=JsonNull)=AssistantSnapshot.from(buildJsonObject {
        put("enabled",true);put("ready",true);put("fresh",true);put("runtimeId","assistant-runtime");put("paused",false)
        putJsonArray("operations") {add(buildJsonObject {
            put("id","original-op");put("role","Coordinator");put("phase",phase);put("suspended",false);put("mapping",mapping)
            put("expected_generation","original-generation");putJsonArray("user_message_ids") {add("user:original-message")}
            if(offer) putJsonObject("handoffRecovery") {
                put("action","resume_original_handoff");put("operationId","original-op");put("generation","original-generation")
                putJsonArray("messageIds") {add("user:original-message")}
            }
        })}
    })
    private fun content(state:()->AssistantUiState,online:()->Boolean={true}) {
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlass provides false) {
                AssistantScreen(state(),emptyList(),emptyList(),emptyList(),actions::add,{_,_->},{},connected=online())
            }
        }}}
        compose.waitForIdle()
    }
    @Test fun explicitRecoveryUsesOriginalOperationAndNeverSendsAnotherMessage() {
        val original=snapshot()
        content({AssistantUiState(original)})
        assertTrue(actions.isEmpty())
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-handoff-resume:original-op"))
        compose.onNodeWithTag("assistant-handoff-resume:original-op").assertIsEnabled().performClick()
        val action=actions.single()
        assertEquals("assistant/resume",action.method)
        assertEquals(JsonPrimitive("original-op"),action.params["operationId"])
        assertEquals(JsonPrimitive("assistant-runtime"),action.params["runtimeId"])
        assertFalse(action.params.containsKey("message"))
        assertEquals(AssistantOperationPhase.Paused,original.operations.single().phase)
    }
    @Test fun offlineStaleReadAndUnknownReceiptKeepRecoveryDisabled() {
        var online by mutableStateOf(false)
        var state by mutableStateOf(AssistantUiState(snapshot()))
        content({state},{online})
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-handoff-resume:original-op"))
        val button=compose.onNodeWithTag("assistant-handoff-resume:original-op")
        button.assertIsNotEnabled()
        compose.runOnIdle {online=true;state=state.copy(snapshot=state.snapshot!!.copy(fresh=false))}
        button.assertIsNotEnabled()
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(fresh=true),readError="读取失败")}
        button.assertIsNotEnabled()
        compose.runOnIdle {state=state.copy(readError=null,unconfirmedActions=setOf("assistant/resume"))}
        button.assertIsNotEnabled()
        compose.onNodeWithText("恢复结果尚未确认，请勿再次提交").assertExists()
        compose.runOnIdle {state=state.copy(unconfirmedActions=emptySet(),pendingActions=setOf("assistant/resume"))}
        button.assertIsNotEnabled()
        assertTrue(actions.isEmpty())
    }
    @Test fun pausedAloneAndContradictoryNativeMappingNeverOfferRecovery() {
        var state by mutableStateOf(AssistantUiState(snapshot(offer=false)))
        content({state})
        compose.onNodeWithTag("assistant-handoff-resume:original-op").assertDoesNotExist()
        compose.runOnIdle {state=state.copy(snapshot=snapshot(phase="Unknown"))}
        compose.onNodeWithTag("assistant-handoff-resume:original-op").assertDoesNotExist()
        compose.runOnIdle {state=state.copy(snapshot=snapshot(mapping=buildJsonObject {put("thread_id","native-thread");put("turn_id","native-turn")}))}
        compose.onNodeWithTag("assistant-handoff-resume:original-op").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }
}
