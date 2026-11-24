package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SavedPermissionStateTest {
    @Test fun savedCustomPermissionKeepsItsDisplayLabelWithoutCurrentAuthority() {
        val value = PermissionState.from(Json.parseToJsonElement("""{
            "status":"saved","revision":null,"currentPresetId":"custom",
            "currentLabel":"此前的自定义权限","presets":[]
        }""").jsonObject)
        assertEquals("saved", value.status)
        assertEquals("此前的自定义权限", value.label)
        assertNull(value.revision)
    }

    @Test fun savedPermissionCannotCarryAnActionRevisionOrEnabledPresets() {
        val value = PermissionState.from(Json.parseToJsonElement("""{
            "status":"saved","revision":"stale-revision","currentPresetId":"ask",
            "presets":[{"id":"ask","label":"请求批准","enabled":true}]
        }""").jsonObject)
        assertEquals("saved", value.status)
        assertEquals("请求批准", value.label)
        assertNull("A persisted permission has no usable mutation revision", value.revision)
        assertTrue(value.presets.none { it.enabled })
    }
}
