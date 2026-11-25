package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantConversationsTest {
    @Test fun selectedConversationIsBlankWhileOldTasksRemainInGlobalCatalog() {
        val oldTask=AssistantTask("old","Continue working",AssistantTaskStatus.Running,"op",false,null,conversationId="chat-a")
        val oldOp=AssistantOperation("op","Execution","old",AssistantOperationPhase.Running,false,"native",null,null,conversationId="chat-a")
        val global=AssistantSnapshot(conversationId="chat-b",tasks=listOf(oldTask),operations=listOf(oldOp),
            events=listOf(AssistantEvent(1,"event","op","summary","old result",1,conversationId="chat-a")))
        assertEquals(listOf(oldTask),global.orderedTasks())
        val selected=global.selectedConversation()
        assertTrue(selected.tasks.isEmpty())
        assertTrue(selected.operations.isEmpty())
        assertTrue(selected.coordinatorReplies.isEmpty())
    }

    @Test fun oldConversationStreamCannotAppearInNewChat() {
        val state=AssistantUiState(snapshot=AssistantSnapshot(runtimeId="r",conversationId="chat-b",activeCoordinatorOperationId="new"))
        val reduced=AssistantStream.reduce(state,buildJsonObject {
            put("runtimeId","r");put("epoch","e");put("sequence",1);put("kind","coordinator_delta");put("operationId","old");put("delta","wrong chat")
        })!!
        assertEquals("",reduced.state.streamingCoordinatorText)
        assertTrue(reduced.reconcile)
    }
    @Test fun attributedStreamArrivesBeforeItsOperationSnapshotWithoutBorrowingOtherChatText() {
        val state=AssistantUiState(snapshot=AssistantSnapshot(runtimeId="r",conversationId="chat-b"))
        fun frame(conversation:String,sequence:Int,text:String)=buildJsonObject {
            put("runtimeId","r");put("epoch","e");put("sequence",sequence);put("kind","coordinator_delta")
            put("operationId","op-$conversation");put("conversationId",conversation);put("delta",text)
        }
        val other=AssistantStream.reduce(state,frame("chat-a",1,"other"))!!
        assertEquals("",other.state.streamingCoordinatorText)
        assertFalse(other.reconcile)
        val selected=AssistantStream.reduce(other.state,frame("chat-b",2,"selected"))!!
        assertEquals("selected",selected.state.streamingCoordinatorText)
        assertTrue(selected.reconcile)
        assertEquals(2L,selected.state.lastStreamSequence)
    }

    @Test fun exactConversationAndOperationAreSentForInterrupt() {
        val action=assistantConversationInterrupt("chat-a","op-a")
        assertEquals("assistant/v2/conversation/interrupt",action.method)
        assertEquals(buildJsonObject {put("conversationId","chat-a");put("operationId","op-a")},action.params)
        assertNotEquals(AssistantSnapshot(scopeId="s",conversationId="a").composerBinding("pc"),AssistantSnapshot(scopeId="s",conversationId="b").composerBinding("pc"))
    }
    @Test fun confirmedPausedReplyClearsLiveTextEvenWhenDoneFrameWasLost() {
        val stopped=AssistantOperation("reply","Coordinator",null,AssistantOperationPhase.Paused,true,"native",null,null,conversationId="chat-a")
        val state=AssistantUiState(snapshot=AssistantSnapshot(runtimeId="r",conversationId="chat-a",operations=listOf(stopped)),
            streamingCoordinatorOpId="reply",streamingCoordinatorText="partial response")
        val reconciled=state.streamReconciled("r",0)
        assertNull(reconciled.streamingCoordinatorOpId)
        assertEquals("",reconciled.streamingCoordinatorText)
    }
}
