package dev.codexpocket.app.ui.kit

import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.PocketTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Screens still on Material and screens on the kit must read identical colours and type. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KitThemeParityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun read(theme: String): Triple<PocketColors, ColorScheme, Pair<PocketType, Typography>> {
        lateinit var tokens: PocketColors
        lateinit var scheme: ColorScheme
        lateinit var type: Pair<PocketType, Typography>
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            tokens = Pocket.colors; scheme = MaterialTheme.colorScheme; type = Pocket.type to MaterialTheme.typography
        } } }
        compose.waitForIdle()
        return Triple(tokens, scheme, type)
    }

    @Test fun tokensMatchTheMaterialSchemeInBothAppearances() {
        for (theme in listOf("light", "dark")) {
            val (t, m, _) = read(theme)
            assertEquals(theme == "dark", t.dark)
            val pairs = listOf("bg" to (t.bg to m.background), "surface" to (t.surface to m.surface), "sunken" to (t.sunken to m.surfaceContainerLow),
                "fill" to (t.fill to m.surfaceContainer), "fillStrong" to (t.fillStrong to m.surfaceContainerHigh),
                "fillStrongest" to (t.fillStrongest to m.surfaceContainerHighest), "ink" to (t.ink to m.onSurface),
                "inkMuted" to (t.inkMuted to m.onSurfaceVariant), "inkFaint" to (t.inkFaint to m.outline), "line" to (t.line to m.outlineVariant),
                "solid" to (t.solid to m.primary), "onSolid" to (t.onSolid to m.onPrimary), "accent" to (t.accent to m.secondary),
                "accentSoft" to (t.accentSoft to m.primaryContainer), "onAccentSoft" to (t.onAccentSoft to m.onPrimaryContainer),
                "danger" to (t.danger to m.error), "onDanger" to (t.onDanger to m.onError), "dangerSoft" to (t.dangerSoft to m.errorContainer),
                "onDangerSoft" to (t.onDangerSoft to m.onErrorContainer))
            pairs.forEach { (name, pair) -> assertEquals("$theme $name", pair.second, pair.first) }
        }
    }

    @Test fun typeScaleMatchesTheMaterialTypography() {
        val (_, _, type) = read("light")
        val (t, m) = type
        assertEquals(m.headlineLarge, t.display); assertEquals(m.headlineMedium, t.headline)
        assertEquals(m.titleLarge, t.titleLarge); assertEquals(m.titleMedium, t.title); assertEquals(m.titleSmall, t.titleSmall)
        assertEquals(m.bodyLarge, t.body); assertEquals(m.bodySmall, t.bodySmall)
        assertEquals(m.labelLarge, t.label); assertEquals(m.labelMedium, t.labelSmall); assertEquals(m.labelSmall, t.caption)
    }
}
