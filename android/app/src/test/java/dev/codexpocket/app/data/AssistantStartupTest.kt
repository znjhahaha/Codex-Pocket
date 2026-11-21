package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantStartupTest {
    @Test fun missingOrUnknownStagesNeverClaimReadyAndLegacySnapshotsStillWork() {
        val base=buildJsonObject {put("enabled",true);put("ready",true)}
        assertTrue(AssistantSnapshot.from(base).prepared)
        val incomplete=JsonObject(base + ("startupStages" to buildJsonArray {
            add(buildJsonObject {put("id","runtime");put("status","ready")})
            add(buildJsonObject {put("id","account");put("status","ready")})
        }))
        assertFalse(AssistantSnapshot.from(incomplete).prepared)
        val stages=listOf("runtime","account","capabilities","coordinator")
        val ready=JsonObject(base + ("startupStages" to buildJsonArray {stages.forEach {id -> add(buildJsonObject {put("id",id);put("status","ready")})}}))
        assertTrue(AssistantSnapshot.from(ready).prepared)
        assertFalse(AssistantSnapshot.from(ready).copy(startupStages=listOf(AssistantStartupStage("runtime","unknown"))).prepared)
    }
}
