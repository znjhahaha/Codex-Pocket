package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantHandoffOfferTest {
    private fun operation()=Json.parseToJsonElement("""{
        "id":"original","role":"Coordinator","phase":"Paused","suspended":false,"mapping":null,
        "expected_generation":"generation","user_message_ids":["user:retained"],
        "handoffRecovery":{"action":"resume_original_handoff","operationId":"original",
            "generation":"generation","messageIds":["user:retained"]}
    }""").jsonObject
    private fun offer(operation:JsonObject)=AssistantSnapshot.from(buildJsonObject {
        putJsonArray("operations") {add(operation)}
    }).operations.single().handoffRecovery
    private fun alteredOffer(key:String,value:JsonElement):JsonObject {
        val original=operation()
        return JsonObject(original+("handoffRecovery" to JsonObject(original.getValue("handoffRecovery").jsonObject+(key to value))))
    }

    @Test fun recoveryPreservesOriginalMessageBinding() {
        assertEquals(listOf("user:retained"),offer(operation())!!.messageIds)
        val action=AssistantActions.resumeHandoff("runtime","original")
        assertEquals("assistant/resume",action.method)
        assertEquals(setOf("runtimeId","operationId"),action.params.keys)
        assertEquals(JsonPrimitive("original"),action.params["operationId"])
    }
    @Test fun anotherOperationGenerationOrMessageCannotBorrowTheOffer() {
        assertNull(offer(alteredOffer("operationId",JsonPrimitive("another"))))
        assertNull(offer(alteredOffer("generation",JsonPrimitive("later-generation"))))
        assertNull(offer(alteredOffer("messageIds",JsonArray(listOf(JsonPrimitive("user:later"))))))
    }
    @Test fun nativeDispatchOrUnknownPhaseCannotBeRetriedAsAnOriginalHandoff() {
        assertNull(offer(JsonObject(operation()+("mapping" to buildJsonObject {put("thread_id","native")}))))
        assertNull(offer(JsonObject(operation()+("phase" to JsonPrimitive("Unknown")))))
        assertNull(offer(JsonObject(operation()+("role" to JsonPrimitive("Worker")))))
    }
    @Test fun stringSuspensionCannotOfferRecovery() {
        assertNull(offer(JsonObject(operation()+("suspended" to JsonPrimitive("false")))))
    }
    @Test fun emptyDuplicateOrInvalidMessageIdsNeverOfferRecovery() {
        assertNull(offer(alteredOffer("messageIds",JsonArray(emptyList()))))
        val duplicate=JsonArray(listOf(JsonPrimitive("user:retained"),JsonPrimitive("user:retained")))
        val duplicateOperation=JsonObject(operation()+("user_message_ids" to duplicate))
        assertNull(offer(JsonObject(duplicateOperation+("handoffRecovery" to JsonObject(
            duplicateOperation.getValue("handoffRecovery").jsonObject+("messageIds" to duplicate))))))
        assertNull(offer(alteredOffer("generation",JsonPrimitive("bad\u0000generation"))))
    }
}
