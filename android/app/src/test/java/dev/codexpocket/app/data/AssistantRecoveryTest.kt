package dev.codexpocket.app.data

import dev.codexpocket.app.ui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantRecoveryTest {
    private fun pending(action:String="reject_original_tool_answer",method:String="item/tool/call",id:String="original")=buildJsonObject {
        put("operationId",id);put("requestId","n:1");put("method",method);put("recoveryAction",action)
        put("params",buildJsonObject {put("arguments","must not enter a display model")})
    }
    @Test fun onlyKnownBoundedRecoveryOffersParseAndBlockNewDispatch() {
        val snapshot=AssistantSnapshot.from(buildJsonObject {
            put("enabled",true);put("ready",true)
            put("pendingRequests",buildJsonArray {add(pending());add(pending());add(pending("unknown"));add(pending(method="requestApproval"));add(pending(id=""));add(pending(id="x".repeat(257)))})
        })
        assertEquals(listOf(AssistantRecoveryRequest("original","n:1")),snapshot.recoveries)
        assertFalse(snapshot.prepared)
        assertNotNull(snapshot.preparationIssue)
        assertTrue(snapshot.copy(recoveries=emptyList()).prepared)
    }
    @Test fun recoveryAppearsBesideTheKnownCoordinatorWithoutChangingTerminalPhase() {
        val op=AssistantOperation("original","Coordinator",null,AssistantOperationPhase.Completed,false,"thread",null,"result")
        val snapshot=AssistantSnapshot(operations=listOf(op),
            events=listOf(AssistantEvent(1,"done",op.id,"completed","",1234)),
            recoveries=listOf(AssistantRecoveryRequest(op.id,"n:1"),AssistantRecoveryRequest("unknown","n:2")))
        val timeline=assistantTimeline(AssistantUiState(snapshot))
        val recovery=timeline.filterIsInstance<AssistantTimeline.Recovery>().single()
        assertEquals(1234L,recovery.atMs)
        assertEquals(op.id,recovery.request.operationId)
        assertTrue(timeline.indexOf(recovery)>timeline.indexOfFirst {it is AssistantTimeline.Message})
        assertEquals(AssistantOperationPhase.Completed,snapshot.operations.single().phase)
        assertTrue(assistantTimeline(AssistantUiState(snapshot.copy(operations=listOf(op.copy(role="Execution"))))).none {it is AssistantTimeline.Recovery})
    }
}
