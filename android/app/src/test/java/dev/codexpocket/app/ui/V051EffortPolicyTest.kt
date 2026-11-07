package dev.codexpocket.app.ui

import org.junit.Assert.*
import org.junit.Test

class V051EffortPolicyTest {
    @Test fun onlyAdvertisedLowThroughMaxAppearInAscendingOrder() {
        assertEquals(listOf("low", "high", "max"), supportedEffortChoices(listOf("max", "minimal", "low", "ultra", "high", "high")))
        assertTrue(supportedEffortChoices(listOf("none", "minimal", "ultra")).isEmpty())
    }
}
