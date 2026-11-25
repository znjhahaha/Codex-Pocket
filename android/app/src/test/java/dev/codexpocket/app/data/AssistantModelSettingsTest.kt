package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantModelSettingsTest {
    @Test fun settingsPagePatchNeverContainsEitherModel() {
        val settings = AssistantSettings("Helpful", "Brief", "old-work", "Asia/Shanghai", AssistantNotifications(true,true,true), "old-chief")
        val patch = AssistantActions.preferences("scope", settings).params["patch"]!!.jsonObject
        assertFalse(patch.containsKey("model"))
        assertFalse(patch.containsKey("coordinator_model"))
        assertEquals(JsonPrimitive("Brief"), patch["tone"])
    }
    @Test fun rolePatchesUseExplicitNullAndTouchOneField() {
        assertEquals(buildJsonObject { put("coordinator_model", JsonNull) }, AssistantActions.model("scope", true, null).params["patch"])
        assertEquals(buildJsonObject { put("model", "worker") }, AssistantActions.model("scope", false, "worker").params["patch"])
    }
    @Test fun scopeIdentitySurvivesFirstStartupButChangesWithProfile() {
        val idle = AssistantSnapshot(runtimeId="scope", scopeId="scope", preparationStatus="idle")
        val started = idle.copy(runtimeId="host", assistantId="identity", ready=true)
        assertEquals(idle.composerBinding("pc"), started.composerBinding("pc"))
        assertNotEquals(idle.composerBinding("pc"), started.copy(scopeId="other").composerBinding("pc"))
        assertNull(idle.preparationIssue)
        assertFalse(idle.prepared)
    }
    @Test fun effortsPatchOnlyChosenRoleAndExplicitNullRestoresInheritance() {
        assertEquals(buildJsonObject { put("coordinator_effort", "high") }, AssistantActions.effort("scope", true, "high").params["patch"])
        assertEquals(buildJsonObject { put("effort", JsonNull) }, AssistantActions.effort("scope", false, null).params["patch"])
    }
}
