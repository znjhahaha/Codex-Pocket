package dev.codexpocket.app.ui

import dev.codexpocket.app.data.NativeModel
import org.junit.Assert.*
import org.junit.Test

class ModelFamiliesTest {
    private fun m(id: String, name: String) = NativeModel(id, id, name, "", emptyList(), "", false, false)

    @Test fun variantsGroupUnderFamilyAndSinglesKeepFullName() {
        val families = modelFamilies(listOf(m("a", "GPT-6-Astra"), m("b", "GPT-6-Astra（anyL）"), m("c", "GPT-6.1-Sol（anyL）"),
            m("d", "GPT-6-Astra（aggit）"), m("e", "Solo (beta)")))
        assertEquals(listOf("GPT-6-Astra", "GPT-6.1-Sol", "Solo"), families.map { it.name })
        assertEquals(listOf("默认", "anyL", "aggit"), families[0].models.map { it.second })
        assertEquals(listOf("GPT-6.1-Sol（anyL）"), families[1].models.map { it.second })
        assertEquals(listOf("Solo (beta)"), families[2].models.map { it.second })
    }
}
