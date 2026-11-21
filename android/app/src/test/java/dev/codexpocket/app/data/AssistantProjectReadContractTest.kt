package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantProjectReadContractTest {
    private val identity=AssistantProjectIdentity("pc","assistant","source","store","thread")
    private val raw=buildJsonObject {put("sourceRuntimeId","source");put("sourceConfigStoreId","store");put("threadId","thread");put("verified",true);put("writable",true);put("sourceGeneration","generation");put("permissionRevision","revision")}
    @Test fun unknownMalformedAndWrongIdentityRepliesNeverBecomeProof() {
        assertNotNull(AssistantProjectProof.from(raw,identity))
        for((key,value) in listOf("sourceRuntimeId" to JsonPrimitive("other"),"sourceConfigStoreId" to JsonPrimitive("other"),
            "threadId" to JsonPrimitive("other"),"verified" to JsonPrimitive(false),"verified" to JsonPrimitive("true"),
            "writable" to JsonPrimitive("true"),"sourceGeneration" to JsonPrimitive(""),"permissionRevision" to JsonNull)) {
            assertNull(key,AssistantProjectProof.from(JsonObject(raw+(key to value)),identity))
        }
        assertNull(AssistantProjectProof.from(JsonObject(raw+("commandId" to JsonPrimitive("injected"))),identity))
        assertNull(AssistantProjectProof.from(JsonObject(raw-"writable"),identity))
        assertFalse(AssistantProjectProof.from(JsonObject(raw+("writable" to JsonPrimitive(false))),identity)!!.writable)
    }
    @Test fun scopeRequiresTheSameTransportGenerationAndFullPairedIdentity() {
        val transport=Any();val scope=AssistantProjectReadScope(transport,1,identity)
        assertTrue(scope.matches(AssistantProjectReadScope(transport,1,identity)))
        assertFalse(scope.matches(AssistantProjectReadScope(Any(),1,identity)))
        assertFalse(scope.matches(AssistantProjectReadScope(transport,2,identity)))
        for(other in listOf(identity.copy(computerId="other"),identity.copy(assistantRuntimeId="other"),identity.copy(sourceRuntimeId="other"),identity.copy(sourceConfigStoreId="other"),identity.copy(threadId="other"))) {
            assertFalse(scope.matches(AssistantProjectReadScope(transport,1,other)))
        }
    }
    @Test fun uncertainFeedbackOnlyEndsOnAnAuthoritativeTerminalCommand() {
        val command=CommandEntity("once","pc",null,"assistant/respond","unknown",1)
        val state=AssistantUiState().withCommandFeedback(listOf(command))
        assertTrue(state.unconfirmedActions.contains("assistant/respond"))
        assertTrue(state.error!!.contains("尚未确认"))
        assertTrue(state.copy(snapshot=AssistantSnapshot()).withCommandFeedback(listOf(command.copy(status="not_found"))).unconfirmedActions.isNotEmpty())
        val ended=state.withCommandFeedback(listOf(command.copy(status="accepted")))
        assertTrue(ended.unconfirmedActions.isEmpty());assertNull(ended.error)
    }
}
