package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class PlanSettingsAckTest {
    private val plan = NativeSettings(mode = NativeSetting(true, "plan"))
    private val execute = NativeSettings(mode = NativeSetting(true, "default"))
    @Test fun submissionSupersedesOnlyThePendingChoiceThatWasSubmitted() {
        val before = NativeSettingsRecord().edit(plan)
        val acknowledged = before.submitted(execute, before.revisions)
        assertEquals("default", acknowledged.settings.mode.value)
        assertFalse(NativeSettingsField.Mode in acknowledged.pending)
        val newer = before.edit(plan).submitted(execute, before.revisions)
        assertEquals("plan", newer.settings.mode.value)
        assertTrue(NativeSettingsField.Mode in newer.pending)
    }
    @Test fun restartPreservesTheVersionFenceForUnknownSubmissionReconciliation() {
        val submitted = NativeSettingsRecord().edit(plan)
        val restored = NativeSettingsRecord.restore(submitted.persisted("runtime"), "runtime")!!
        assertEquals(submitted.revisions, restored.revisions)
        assertEquals("default", restored.submitted(execute, submitted.revisions).settings.mode.value)
        val edited = restored.edit(plan)
        val restoredEdited = NativeSettingsRecord.restore(edited.persisted("runtime"), "runtime")!!
        assertEquals("plan", restoredEdited.submitted(execute, submitted.revisions).settings.mode.value)
    }
}
