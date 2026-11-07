package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PermissionStateTest {
    @Test fun knownCustomAndInitializationAreNotUnsyncedDefaults() {
        val custom = PermissionState.from(json("""{"status":"ready","revision":"r","currentPresetId":"custom","currentLabel":"自定义权限"}"""))
        assertEquals("自定义权限", custom.label)
        assertEquals("ready", custom.status)
        assertEquals("正在同步权限", PermissionState.from(json("""{"status":"initializing"}""")).label)
        assertEquals("电脑桥接待更新", PermissionState.from(json("""{"status":"update_required"}""")).label)
    }
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun parsesOnlyAdvertisedPresetsAndKeepsOpaqueRevision() {
        val state = PermissionState.from(json("""{
            "runtimeId":"runtime","threadId":"thread","status":"ready","revision":"opaque:12",
            "currentPresetId":"auto","presets":[
                {"id":"request","label":"请求批准","description":"外部访问需要批准","enabled":true},
                {"id":"auto","label":"帮我批准","description":"原生审批执行者","enabled":true},
                {"id":"full","label":"完全访问权限","description":"电脑允许的完全访问","enabled":false,"reason":"被电脑策略禁止"},
                {"id":"profile:review","label":"自定义 (review)","description":"已有配置","enabled":true}
            ]
        }"""))
        assertEquals("ready", state.status)
        assertEquals(JsonPrimitive("opaque:12"), state.revision)
        assertEquals("auto", state.currentPresetId)
        assertEquals("帮我批准", state.label)
        assertEquals(listOf("request", "auto", "full", "profile:review"), state.presets.map { it.id })
        assertFalse(state.presets[2].enabled)
        assertEquals("被电脑策略禁止", state.presets[2].reason)
        assertFalse(state.loading)
        assertFalse(state.updating)
    }

    @Test fun malformedValuesCannotEnableAPresetOrForgeSelection() {
        val state = PermissionState.from(json("""{
            "status":[],"revision":{"token":"not-a-revision"},"currentPresetId":42,
            "presets":[null,42,{},
                {"id":false,"label":"Bad ID","enabled":true},
                {"id":" ","label":"Empty ID","enabled":true},
                {"id":"missing-label","enabled":true},
                {"id":"request","label":"请求批准","description":{},"enabled":"true","reason":[]},
                {"id":"auto","label":"帮我批准"}
            ]
        }"""))
        assertEquals("unknown", state.status)
        assertNull(state.revision)
        assertNull(state.currentPresetId)
        assertEquals(listOf("request", "auto"), state.presets.map { it.id })
        assertTrue(state.presets.none { it.enabled })
        assertEquals("", state.presets.first().description)
        assertNull(state.presets.first().reason)
        assertEquals("会话权限未同步", state.label)
    }

    @Test fun absenceUnknownSelectionAndUnsupportedDoNotGuessADefault() {
        assertEquals(PermissionState(), PermissionState.from(json("{}")))
        val missing = PermissionState.from(json("""{"status":"ready","currentPresetId":"gone","presets":[
            {"id":"request","label":"请求批准","enabled":true}
        ]}"""))
        assertEquals("会话权限未同步", missing.label)
        assertNull(PermissionState.from(json("""{"currentPresetId":null,"revision":null}""")).currentPresetId)
        val unsupported = PermissionState.from(json("""{"status":"unsupported","presets":null}"""))
        assertEquals("unsupported", unsupported.status)
        assertTrue(unsupported.presets.isEmpty())
    }

    @Test fun conflictingDuplicateIdsAreNotSelectable() {
        val state = PermissionState.from(json("""{"status":"ready","currentPresetId":"full","presets":[
            {"id":"full","label":"完全访问权限","enabled":true},
            {"id":"full","label":"Different policy","enabled":false},
            {"id":"request","label":"请求批准","enabled":true}
        ]}"""))
        assertEquals(listOf("request"), state.presets.map { it.id })
        assertEquals("会话权限未同步", state.label)
    }

    @Test fun noCredentialsRawConfigurationOrRemoteBusyFlagsAreRetained() {
        val state = PermissionState.from(json("""{
            "status":"ready","revision":"revision","loading":true,"updating":true,
            "headers":{"Authorization":"fixture-secret"},"config":"fixture-config-text",
            "presets":[{"id":"request","label":"请求批准","enabled":true,
                "credentials":"fixture-secret","profileConfig":"fixture-config-text"}]
        }"""))
        assertFalse(state.loading)
        assertFalse(state.updating)
        assertFalse(state.toString().contains("fixture-secret"))
        assertFalse(state.toString().contains("fixture-config-text"))
    }

    @Test fun revisionsMustBeNonemptyOpaqueStringsAndErrorsDoNotRetainRawPayloads() {
        listOf("null", "false", "12", "[]", "{}", "\"\"").forEach { value ->
            assertNull(PermissionState.from(json("""{"revision":$value}""")).revision)
        }
        val state = PermissionState.from(json("""{
            "status":"error","error":{"message":"fixture-secret","config":"fixture-config-text"}
        }"""))
        assertEquals("error", state.status)
        assertEquals("读取会话权限失败，请重试", state.error)
        assertFalse(state.toString().contains("fixture-secret"))
    }
}
