package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class NativeSettingsLabelsTest {
    private val model = NativeModel("catalog", "m", "Catalog model", "", listOf("high"), "high", false, true)

    @Test fun unknownSelectionDoesNotBorrowCatalogNameOrDefaultEffort() {
        val state = PocketState(models = listOf(model))
        assertNull(state.currentModel)
        assertEquals("模型未同步", state.modelSelectionLabel)
        assertEquals("", state.effortSelectionLabel)
        assertEquals("", state.modeSelectionLabel)
    }

    @Test fun knownNullMeansNativeDefaultEvenIfCatalogAdvertisesHigh() {
        val state = PocketState(models = listOf(model), model = model.model,
            nativeSettings = NativeSettings(model = NativeSetting(true, model.model), effort = NativeSetting(true, null), mode = NativeSetting(true, null)))
        assertEquals("Catalog model", state.modelSelectionLabel)
        assertEquals("", state.effortSelectionLabel)
        assertEquals("", state.modeSelectionLabel)
    }

    @Test fun explicitUnlistedModelAndEffortKeepTheirIdentity() {
        val state = PocketState(models = listOf(model), model = "desktop-only", effort = "xhigh", mode = "plan")
        assertNull(state.currentModel)
        assertEquals("desktop-only", state.modelSelectionLabel)
        assertEquals("xhigh", state.effortSelectionLabel)
        assertEquals("plan", state.modeSelectionLabel)
    }
}
