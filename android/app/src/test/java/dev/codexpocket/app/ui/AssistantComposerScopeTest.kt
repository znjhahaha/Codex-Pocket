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
        compose.onNodeWithTag(AssistantDraft).performTextInput(text)
        compose.onNodeWithTag(AssistantSend).assertIsEnabled().performClick()
    }
    private fun assertEmpty()=compose.onNodeWithTag(AssistantDraft).assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))

    @Test fun firstPreparationKeepsDraftOnlyWithinTheSameComputer() {
        state=AssistantUiState(AssistantSnapshot(enabled=false),computerId="pc")
        content()
        compose.onNodeWithTag(AssistantDraft).performTextInput("preparation draft")
        compose.runOnIdle {state=initial}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("preparation draft")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled()
        compose.runOnIdle {state=initial.copy(computerId="other-pc")}
        assertEmpty()
        compose.runOnIdle {state=initial}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("preparation draft")
        assertTrue(actions.isEmpty())
    }

    @Test fun runtimeAndCoordinatorPreparationStagesPreserveTheUnsentDraft() {
        state=AssistantUiState(AssistantSnapshot(enabled=false),computerId="pc")
        content()
        compose.onNodeWithTag(AssistantDraft).performTextInput("staged preparation")
        compose.runOnIdle {state=AssistantUiState(AssistantSnapshot(enabled=true,ready=false,runtimeId="r"),computerId="pc")}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("staged preparation")
        compose.runOnIdle {state=initial.copy(snapshot=initial.snapshot!!.copy(ready=false))}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("staged preparation")
        compose.runOnIdle {state=initial}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("staged preparation")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test fun laterPreparationCannotBorrowASentDraftFromTheFirstRuntime() {
        state=AssistantUiState(AssistantSnapshot(enabled=false),computerId="pc")
        content()
        compose.onNodeWithTag(AssistantDraft).performTextInput("original pending")
        compose.runOnIdle {state=initial}
        compose.onNodeWithTag(AssistantSend).assertIsEnabled().performClick()
        val original=actions.single()
        compose.runOnIdle {state=AssistantUiState(AssistantSnapshot(enabled=false),computerId="pc")}
        assertEmpty()
        compose.onNodeWithTag(AssistantDraft).performTextInput("new preparation")
        compose.runOnIdle {state=initial.copy(snapshot=initial.snapshot!!.copy(runtimeId="later-runtime"))}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("new preparation")
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
        compose.onNodeWithTag(AssistantDraft).performTextInput("next message")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled()
    }

    @Test fun runtimeScopeDraftIsIsolatedAndOriginalReceiptReleasesOriginalPendingOnReturn() {
        content();send("original pending")
        val original=actions.single()
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(runtimeId="another-runtime"))}
        assertEmpty()
        compose.onNodeWithTag(AssistantDraft).performTextInput("new runtime draft")
        compose.runOnIdle {state=state.acceptSend(original)}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("new runtime draft")
        compose.runOnIdle {state=state.copy(snapshot=initial.snapshot!!.copy(coordinatorThreadId="successor"))}
        assertEmpty()
        compose.onNodeWithTag(AssistantDraft).performTextInput("original scope can send again")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled()
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(runtimeId="another-runtime"))}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("new runtime draft")
        assertEquals(1,actions.size)
    }

    @Test fun anotherPublicAssistantAndComputerCannotConsumeOrBorrowOriginalDraft() {
        content();send("original pending")
        val action=actions.single()
        compose.runOnIdle {state=state.copy(computerId="pc2",snapshot=state.snapshot!!.copy(assistantId="another-assistant"))}
        assertEmpty()
        compose.onNodeWithTag(AssistantDraft).performTextInput("other computer draft")
        compose.runOnIdle {state=state.acceptSend(action)}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("other computer draft")
        compose.runOnIdle {state=initial.copy(sendReceipts=state.sendReceipts)}
        assertEmpty()
        compose.runOnIdle {state=state.copy(computerId="pc2",snapshot=state.snapshot!!.copy(assistantId="another-assistant"))}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("other computer draft")
        assertEquals(1,actions.size)
    }

    @Test fun restoredPendingComposerAcceptsOriginalReceiptAfterNativeSuccessor() {
        val restoration=content();send("restore pending")
        val action=actions.single()
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(coordinatorThreadId="successor"))}
        restoration.emulateSavedInstanceStateRestore()
        assertEmpty()
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled()
        compose.runOnIdle {state=state.acceptSend(action)}
        assertEmpty()
        assertEquals(1,actions.size)
    }

    @Test fun unknownAndRejectedReceiptsStayBesideMessageAndDoNotLockNewDraft() {
        content();send("retain draft")
        val action=actions.single()
        compose.runOnIdle {state=state.acceptSend(action,"unknown")}
        assertEmpty()
        compose.onNodeWithTag(AssistantDraft).performTextInput("next draft")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled()
        compose.runOnIdle {state=state.acceptSend(action,"rejected")}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("next draft")
        compose.onNodeWithTag(AssistantSend).assertIsEnabled()
    }

    @Test fun conversationSwitchRetainsDraftAndLateReceiptCannotClearNewerDraft() {
        state=initial.copy(snapshot=initial.snapshot!!.copy(conversationId="chat-a"))
        content();send("first message")
        val action=actions.single()
        assertEmpty()
        compose.onNodeWithTag(AssistantDraft).performTextInput("new draft in a")
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(conversationId="chat-b"))}
        assertEmpty()
        compose.onNodeWithTag(AssistantDraft).performTextInput("draft in b")
        compose.runOnIdle {state=state.acceptSend(action)}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("draft in b")
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(conversationId="chat-a"))}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("new draft in a")
    }

    @Test fun newConversationCannotBorrowOrConsumeThePreparationDraftAndAttachments() {
        val app=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val file=LocalAttachment("preparing-file","preparing.txt","text/plain")
        AssistantAttachmentDraft(app,"main:pc:preparing").add(listOf(file))
        state=AssistantUiState(AssistantSnapshot(enabled=false),computerId="pc")
        content()
        compose.onNodeWithTag(AssistantDraft).performTextInput("original preparation draft")

        compose.runOnIdle {state=initial.copy(snapshot=initial.snapshot!!.copy(conversationId="chat-new"))}
        assertEmpty()
        compose.onNodeWithTag("assistant-attachment:${file.id}").assertDoesNotExist()
        compose.onNodeWithTag(AssistantDraft).performTextInput("new conversation draft")

        compose.runOnIdle {state=initial.copy(snapshot=initial.snapshot!!.copy(conversationId="main:public-assistant"))}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("original preparation draft")
        compose.onNodeWithTag("assistant-attachment:${file.id}").assertExists()
        compose.runOnIdle {state=initial.copy(snapshot=initial.snapshot!!.copy(conversationId="chat-new"))}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("new conversation draft")
        compose.onNodeWithTag("assistant-attachment:${file.id}").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    @Test fun stopReplyWaitsForActualStoppedSnapshot() {
        val running=AssistantOperation("reply", "Coordinator", null, AssistantOperationPhase.Running, false, "native", null, null, conversationId="chat-a")
        state=initial.copy(snapshot=initial.snapshot!!.copy(conversationId="chat-a", activeCoordinatorOperationId="reply", operations=listOf(running)))
        content()
        compose.onNodeWithTag("assistant-stop-reply").performClick()
        assertEquals("assistant/v2/conversation/interrupt",actions.single().method)
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(operations=listOf(running.copy(suspended=true))))}
        compose.onNodeWithTag("assistant-stop-reply").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(operations=listOf(running.copy(phase=AssistantOperationPhase.Paused, suspended=true))))}
        compose.onNodeWithTag("assistant-stop-reply").assertDoesNotExist()
    }

    @Test fun confirmedStopAllowsStoppingASuccessorWithoutAnIdleFrame() {
        val running=AssistantOperation("reply-a", "Coordinator", null, AssistantOperationPhase.Running, false, "native-a", null, null, conversationId="chat-a")
        state=initial.copy(snapshot=initial.snapshot!!.copy(conversationId="chat-a", activeCoordinatorOperationId=running.id, operations=listOf(running)))
        content()
        compose.onNodeWithTag("assistant-stop-reply").performClick()
        assertEquals(assistantConversationInterrupt("chat-a","reply-a").params,actions.single().params)

        val stopped=running.copy(phase=AssistantOperationPhase.Paused,suspended=true)
        val successor=running.copy(id="reply-b",threadId="native-b")
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(fresh=false,activeCoordinatorOperationId=successor.id,operations=listOf(stopped,successor)))}
        compose.onNodeWithTag("assistant-stop-reply").assertIsNotEnabled()
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(fresh=true))}
        compose.onNodeWithTag("assistant-stop-reply").assertIsEnabled().performClick()
        assertEquals(2,actions.size)
        assertEquals(assistantConversationInterrupt("chat-a","reply-b").params,actions.last().params)
    }

    @Test fun legacyCoordinatorChangeHasIsolatedStateWithoutInventingPublicIdentity() {
        state=initial.copy(snapshot=initial.snapshot!!.copy(assistantId=null))
        content();send("legacy pending")
        val action=actions.single()
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(coordinatorThreadId="successor"))}
        assertEmpty()
        compose.onNodeWithTag(AssistantDraft).performTextInput("new legacy scope")
        compose.runOnIdle {state=state.acceptSend(action)}
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("new legacy scope")
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(coordinatorThreadId="original"))}
        assertEmpty()
    }
}
