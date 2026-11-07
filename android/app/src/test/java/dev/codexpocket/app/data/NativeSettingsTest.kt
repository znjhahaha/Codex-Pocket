package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeSettingsTest {
    private fun json(value: String) = pocketJson.parseToJsonElement(value).jsonObject

    @Test fun nativeNotificationUsesThreadSettingsAndCollaborationSettings() {
        val settings = NativeSettings.notification(json("""{
            "threadId":"t", "threadSettings":{
                "model":"desktop-model", "effort":"high", "serviceTier":"fast",
                "collaborationMode":{"mode":"plan","settings":{"model":"plan-model","reasoning_effort":"xhigh"}}
            }
        }"""))
        assertEquals(NativeSetting(true, "plan-model"), settings.model)
        assertEquals(NativeSetting(true, "xhigh"), settings.effort)
        assertEquals(NativeSetting(true, "plan"), settings.mode)
        assertEquals(NativeSetting(true, "fast"), settings.serviceTier)
    }

    @Test fun absentEffortDoesNotClearButExplicitNullMeansNativeDefault() {
        val high = NativeSettings.parse(json("""{"model":"m","effort":"high","serviceTier":"fast"}"""))
        val absent = NativeSettings.parse(json("""{"model":"m2"}"""))
        assertFalse(absent.effort.known)
        assertEquals("high", high.merge(absent).effort.value)
        val defaults = high.merge(NativeSettings.parse(json("""{"effort":null,"serviceTier":null}""")))
        assertEquals(NativeSetting(true, null), defaults.effort)
        assertEquals(NativeSetting(true, null), defaults.serviceTier)
    }

    @Test fun collaborationDefaultIsAnExplicitModeAndNullEffortOverridesTopLevel() {
        val settings = NativeSettings.parse(json("""{"effort":"high","collaborationMode":{
            "mode":"default","settings":{"model":"m","reasoning_effort":null}}}"""))
        assertEquals("default", settings.mode.value)
        assertEquals(NativeSetting(true, null), settings.effort)
    }

    @Test fun malformedValuesAreUnknownAndLegacyNotificationCannotInventSettings() {
        val invalid = NativeSettings.parse(json("""{"model":23,"effort":"","serviceTier":false,"collaborationMode":{"mode":"maybe"}}"""))
        assertFalse(invalid.anyKnown)
        assertFalse(NativeSettings.notification(json("""{"settings":{"model":"invented","serviceTier":"fast"}}""")).anyKnown)
    }

    @Test fun helperResponseRequiresKnownAndExactRuntimeAndThreadIdentity() {
        val valid = json("""{"runtimeId":"r","threadId":"t","known":true,"settings":{"model":"m","effort":null}}""")
        assertEquals("m", NativeSettings.readResponse(valid, "r", "t")!!.model.value)
        assertNull(NativeSettings.readResponse(valid, "other", "t"))
        assertNull(NativeSettings.readResponse(valid, "r", "other"))
        assertNull(NativeSettings.readResponse(JsonObject(valid + ("known" to JsonPrimitive(false))), "r", "t"))
        assertNull(NativeSettings.readResponse(JsonObject(valid - "known"), "r", "t"))
    }

    @Test fun lateSnapshotCannotRestoreHighOrPlanAfterDesktopChanges() {
        val first = NativeSettingsRecord().event(NativeSettings.parse(json("""{"model":"a","effort":"high","collaborationMode":{"mode":"plan","settings":{"model":"a"}}}""")))
        val ticket = first.revisions
        val live = first.event(NativeSettings.parse(json("""{"model":"b","effort":"xhigh","collaborationMode":{"mode":"default","settings":{"model":"b"}}}""")))
        val after = live.snapshot(first.settings, ticket)
        assertEquals("b", after.settings.model.value)
        assertEquals("xhigh", after.settings.effort.value)
        assertEquals("default", after.settings.mode.value)
    }

    @Test fun mobileEditSurvivesReadsAndReadEventsUntilNativeAcknowledgesItsValue() {
        val first = NativeSettingsRecord().event(NativeSettings.parse(json("""{"model":"desktop","effort":"high"}""")))
        val ticket = first.revisions
        val edit = first.edit(NativeSettings.parse(json("""{"model":"phone","effort":null,"collaborationMode":null}""")))
        val old = edit.snapshot(first.settings, ticket)
        val reconnect = old.snapshot(first.settings, old.revisions)
        assertEquals("phone", reconnect.settings.model.value)
        assertEquals(NativeSetting(true, null), reconnect.settings.effort)
        assertEquals(NativeSetting(true, null), reconnect.settings.mode)
        assertTrue(NativeSettingsField.Model in reconnect.pending)
        val staleEvent = reconnect.event(first.settings)
        assertEquals("phone", staleEvent.settings.model.value)
        assertTrue(NativeSettingsField.Model in staleEvent.pending)
        val live = staleEvent.event(NativeSettings.parse(json("""{"model":"phone","effort":null}""")))
        assertEquals("phone", live.settings.model.value)
        assertFalse(NativeSettingsField.Model in live.pending)
    }

    @Test fun fieldFenceStillAllowsUnrelatedServiceTierSnapshot() {
        val first = NativeSettingsRecord()
        val edit = first.edit(NativeSettings.parse(json("""{"model":"phone"}""")))
        val read = edit.snapshot(NativeSettings.parse(json("""{"model":"desktop","serviceTier":"fast"}""")), first.revisions)
        assertEquals("phone", read.settings.model.value)
        assertEquals("fast", read.settings.serviceTier.value)
    }

    @Test fun snapshotsAreOrderedAgainstOtherSnapshotsToo() {
        val first = NativeSettingsRecord()
        val newer = first.snapshot(NativeSettings.parse(json("""{"model":"new","effort":null}""")), first.revisions)
        val old = newer.snapshot(NativeSettings.parse(json("""{"model":"old","effort":"high"}""")), first.revisions)
        assertEquals("new", old.settings.model.value)
        assertEquals(NativeSetting(true, null), old.settings.effort)
    }

    @Test fun persistenceContainsOnlySafeSettingsAndPreservesPresenceAndEdits() {
        val record = NativeSettingsRecord().edit(NativeSettings.parse(json("""{"model":"m","effort":null,
            "collaborationMode":{"mode":"plan","settings":{"model":"m","reasoning_effort":null,"developer_instructions":"secret"}},
            "apiKey":"secret","cwd":"private"}""")))
        val saved = record.persisted("r")
        assertFalse(saved.toString().contains("secret"))
        assertFalse(saved.toString().contains("private"))
        val restored = NativeSettingsRecord.restore(saved, "r")!!
        assertEquals(record.settings, restored.settings)
        assertEquals(record.pending, restored.pending)
        assertNull(NativeSettingsRecord.restore(saved, "other"))
        assertFalse(restored.settings.serviceTier.known)
    }

    @Test fun knownDefaultEffortRemainsNullInPlanPayloadWithoutCatalogFallback() {
        val settings = NativeSettings(effort = NativeSetting(true, null), mode = NativeSetting(true, "plan"))
        val payload = settings.turnSelection("selected", null, "plan", emptySet())
        assertEquals(JsonNull, payload["effort"])
        assertEquals(JsonNull, payload.obj("collaborationMode").obj("settings")["reasoning_effort"])
        assertEquals("selected", payload.obj("collaborationMode").obj("settings").string("model"))
    }

    @Test fun unknownEffortAndModeStayAbsentInTurnPayload() {
        val settings = NativeSettings()
        assertTrue(settings.turnSelection(null, null, null, emptySet()).isEmpty())
        val plan = settings.turnSelection("selected", null, "plan", emptySet()).obj("collaborationMode")
        assertEquals("plan", plan.string("mode"))
        assertFalse(plan.obj("settings").containsKey("reasoning_effort"))
    }

    @Test(expected = IllegalArgumentException::class) fun knownPlanWithoutModelRequiresExplicitSelection() {
        NativeSettings().turnSelection(null, null, "plan", emptySet())
    }

    @Test fun phoneLeavingPlanRequestsDefaultAndExplicitXhighIsRetained() {
        val payload = NativeSettings().turnSelection("m", "xhigh", null, setOf(NativeSettingsField.Mode))
        assertEquals("xhigh", payload.string("effort"))
        assertEquals("default", payload.obj("collaborationMode").string("mode"))
        assertEquals("xhigh", payload.obj("collaborationMode").obj("settings").string("reasoning_effort"))
    }

}
