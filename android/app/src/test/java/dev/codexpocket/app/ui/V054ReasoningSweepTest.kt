package dev.codexpocket.app.ui

import dev.codexpocket.app.data.NativeTurnInfo
import dev.codexpocket.app.data.TimelineItem
import org.junit.Assert.*
import org.junit.Test

class V054ReasoningSweepTest {
    @Test fun onlyMatchingNativeActiveTurnCanSweepItsSummary() {
        val summary = TimelineItem("turn", "reasoning", "reasoning")
        val timing = NativeTurnInfo("turn", status = "inProgress")
        assertTrue(nativeSummaryActive("turn", summary, timing))
        assertFalse(nativeSummaryActive("turn", summary, null))
        assertFalse(nativeSummaryActive("turn", null, timing))
        assertFalse(nativeSummaryActive("turn", summary, timing.copy(status = "")))
        for (status in listOf("completed", "interrupted", "failed")) {
            assertFalse(nativeSummaryActive("turn", summary, timing.copy(status = status)))
        }
        assertFalse(nativeSummaryActive("turn", summary.copy(turnId = "old"), timing))
        assertFalse(nativeSummaryActive("turn", summary, timing.copy(id = "other")))
        assertFalse(nativeSummaryActive("turn", summary.copy(type = "agentMessage"), timing))
    }
    @Test fun everyAccessibilityPowerAndForegroundFallbackIsStatic() {
        for (highContrast in listOf(false, true)) {
            for (batterySaver in listOf(false, true)) {
                for (animators in listOf(false, true)) {
                    for (foreground in listOf(false, true)) {
                        val preferences = PocketGlassPreferences(highContrast, batterySaver, animators)
                        assertEquals(foreground && animators && !highContrast && !batterySaver,
                            nativeMotionAllowed(preferences, foreground))
                    }
                }
            }
        }
        assertEquals(1400, ReasoningSweepMillis)
    }
}
