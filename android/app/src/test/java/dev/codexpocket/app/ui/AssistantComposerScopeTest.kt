package dev.codexpocket.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Composer state and local receipts only; no transport, device, or model calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantComposerScopeTest {
    @get:Rule val compose=createComposeRule()
    private val initial=AssistantUiState(AssistantSnapshot(enabled=true,ready=true,runtimeId="r",coordinatorThreadId="original",
        assistantId="public-assistant",paused=false),computerId="pc")
    private val actions=mutableListOf<AssistantAction>()
    private var state by mutableStateOf(initial)
    private fun content():StateRestorationTester = StateRestorationTester(compose).also {tester->
        tester.setContent {
            PocketTheme("light") {
                CompositionLocalProvider(LocalPocketGlass provides false) {
                    AssistantScreen(state,emptyList(),emptyList(),emptyList(),{actions+=it},{_,_->},{})
                }
            }
        }
        compose.waitForIdle()
    }
    private fun send(text:String) {
        compose.onNodeWithTag("assistant-message-input").performTextInput(text)
        compose.onNodeWithTag("assistant-send").assertIsEnabled().performClick()
    }
    private fun assertEmpty()=compose.onNodeWithTag("assistant-message-input").assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))

    @Test fun firstPreparationKeepsDraftOnlyWithinTheSameComputer() {
        state=AssistantUiState(AssistantSnapshot(enabled=false),computerId="pc")
        content()
        compose.onNodeWithTag("assistant-message-input").performTextInput("preparation draft")
        compose.runOnIdle {state=initial}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("preparation draft")
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
        compose.runOnIdle {state=initial.copy(computerId="other-pc")}
        assertEmpty()
        compose.runOnIdle {state=initial}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("preparation draft")
        assertTrue(actions.isEmpty())
    }

    @Test fun runtimeAndCoordinatorPreparationStagesPreserveTheUnsentDraft() {
        state=AssistantUiState(AssistantSnapshot(enabled=false),computerId="pc")
        content()
        compose.onNodeWithTag("assistant-message-input").performTextInput("staged preparation")
        compose.runOnIdle {state=AssistantUiState(AssistantSnapshot(enabled=true,ready=false,runtimeId="r"),computerId="pc")}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("staged preparation")
        compose.runOnIdle {state=initial.copy(snapshot=initial.snapshot!!.copy(ready=false))}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("staged preparation")
        compose.runOnIdle {state=initial}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("staged preparation")
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test fun laterPreparationCannotBorrowASentDraftFromTheFirstRuntime() {
        state=AssistantUiState(AssistantSnapshot(enabled=false),computerId="pc")
        content()
        compose.onNodeWithTag("assistant-message-input").performTextInput("original pending")
        compose.runOnIdle {state=initial}
        compose.onNodeWithTag("assistant-send").assertIsEnabled().performClick()
        val original=actions.single()
        compose.runOnIdle {state=AssistantUiState(AssistantSnapshot(enabled=false),computerId="pc")}
        assertEmpty()
        compose.onNodeWithTag("assistant-message-input").performTextInput("new preparation")
        compose.runOnIdle {state=initial.copy(snapshot=initial.snapshot!!.copy(runtimeId="later-runtime"))}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("new preparation")
        compose.runOnIdle {state=initial.acceptSend(original)}
        assertEmpty()
        assertEquals(1,actions.size)
    }

    @Test fun nativeSuccessorUsesTheSameCapturedPublicConversationReceipt() {
        content();send("original draft")
        val action=actions.single()
        assertEquals(initial.snapshot!!.composerBinding("pc"),action.composerBinding)
        assertFalse(action.params.containsKey("composerBinding"))
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(coordinatorThreadId="successor")).acceptSend(action)}
        assertEmpty()
        assertEquals(1,actions.size)
        compose.onNodeWithTag("assistant-message-input").performTextInput("next message")
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
    }

    @Test fun runtimeScopeDraftIsIsolatedAndOriginalReceiptReleasesOriginalPendingOnReturn() {
        content();send("original pending")
        val original=actions.single()
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(runtimeId="another-runtime"))}
        assertEmpty()
        compose.onNodeWithTag("assistant-message-input").performTextInput("new runtime draft")
        compose.runOnIdle {state=state.acceptSend(original)}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("new runtime draft")
        compose.runOnIdle {state=state.copy(snapshot=initial.snapshot!!.copy(coordinatorThreadId="successor"))}
        assertEmpty()
        compose.onNodeWithTag("assistant-message-input").performTextInput("original scope can send again")
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(runtimeId="another-runtime"))}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("new runtime draft")
        assertEquals(1,actions.size)
    }

    @Test fun anotherPublicAssistantAndComputerCannotConsumeOrBorrowOriginalDraft() {
        content();send("original pending")
        val action=actions.single()
        compose.runOnIdle {state=state.copy(computerId="pc2",snapshot=state.snapshot!!.copy(assistantId="another-assistant"))}
        assertEmpty()
        compose.onNodeWithTag("assistant-message-input").performTextInput("other computer draft")
        compose.runOnIdle {state=state.acceptSend(action)}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("other computer draft")
        compose.runOnIdle {state=initial.copy(sendReceipts=state.sendReceipts)}
        assertEmpty()
        compose.runOnIdle {state=state.copy(computerId="pc2",snapshot=state.snapshot!!.copy(assistantId="another-assistant"))}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("other computer draft")
        assertEquals(1,actions.size)
    }

    @Test fun restoredPendingComposerAcceptsOriginalReceiptAfterNativeSuccessor() {
        val restoration=content();send("restore pending")
        val action=actions.single()
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(coordinatorThreadId="successor"))}
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("restore pending")
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
        compose.runOnIdle {state=state.acceptSend(action)}
        assertEmpty()
        assertEquals(1,actions.size)
    }

    @Test fun unknownReceiptKeepsPendingAndExplicitRejectionKeepsDraft() {
        content();send("retain draft")
        val action=actions.single()
        compose.runOnIdle {state=state.acceptSend(action,"unknown")}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("retain draft")
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
        compose.runOnIdle {state=state.acceptSend(action,"rejected")}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("retain draft")
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
    }

    @Test fun legacyCoordinatorChangeHasIsolatedStateWithoutInventingPublicIdentity() {
        state=initial.copy(snapshot=initial.snapshot!!.copy(assistantId=null))
        content();send("legacy pending")
        val action=actions.single()
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(coordinatorThreadId="successor"))}
        assertEmpty()
        compose.onNodeWithTag("assistant-message-input").performTextInput("new legacy scope")
        compose.runOnIdle {state=state.acceptSend(action)}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("new legacy scope")
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(coordinatorThreadId="original"))}
        assertEmpty()
    }
}
