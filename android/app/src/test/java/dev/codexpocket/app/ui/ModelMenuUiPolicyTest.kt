package dev.codexpocket.app.ui

import org.junit.Assert.*
import org.junit.Test

class ModelMenuUiPolicyTest {
    private data class Model(val key: String, val default: Boolean = false)
    private fun selected(models: List<Model>, requested: String?) =
        requested?.let { modelMenuSelection(models, it, Model::key, Model::default) }

    @Test fun defaultIsExplicitAndCatalogLevelsKeepTheirOrder() {
        assertEquals(listOf("low", "high"), supportedEffortChoices(listOf("high", "", "low", "high", " ")))
        assertTrue(supportedEffortChoices(emptyList()).isEmpty())
    }

    @Test fun missingAndAmbiguousModelsNeverBecomeTheFirstRow() {
        val a = Model("provider-a/astra")
        val b = Model("provider-b/astra")
        assertNull(selected(listOf(a, b), null))
        assertNull(selected(listOf(a.copy(default = true), b), "missing"))
        assertEquals(b, selected(listOf(a, b), b.key))
        assertNull(selected(listOf(a.copy(default = true), b.copy(default = true)), null))
        assertNull(selected(listOf(a, a), a.key))
    }

    @Test fun singleAdvertisedDefaultRequiresKnownModelSelection() {
        val model = Model("provider-a/astra", true)
        assertNull(selected(listOf(Model("other"), model), null))
        assertEquals(model, selected(listOf(Model("other"), model), model.key))
    }
}
