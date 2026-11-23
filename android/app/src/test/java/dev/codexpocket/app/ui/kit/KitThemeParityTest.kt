package dev.codexpocket.app.ui.kit

import androidx.activity.compose.setContent
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.PocketTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The theme is Pocket's own in both appearances, and what it provides can be read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KitThemeParityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = if (a.luminance() > b.luminance()) a.luminance() to b.luminance() else b.luminance() to a.luminance()
        return (hi + .05f) / (lo + .05f)
    }

    @Test fun eachAppearanceProvidesItsOwnTokensInkAndPressResponse() {
        for (theme in listOf("light", "dark")) {
            lateinit var tokens: PocketColors
            var ink = Color.Unspecified
            var indication: Any? = null
            compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
                tokens = Pocket.colors; ink = Pocket.ink; indication = LocalIndication.current
            } } }
            compose.waitForIdle()
            assertEquals(if (theme == "dark") PocketDarkColors else PocketLightColors, tokens)
            assertEquals("Content starts in the page's ink", tokens.ink, ink)
            assertTrue("Plain clickables answer with Pocket's press, not a ripple", indication is PocketPressIndication)
        }
    }

    @Test fun textAndOutcomeColoursAreLegibleOnTheSurfacesTheySitOn() {
        for (c in listOf(PocketLightColors, PocketDarkColors)) {
            val name = if (c.dark) "dark" else "light"
            for (surface in listOf(c.bg, c.surface, c.sunken)) {
                assertTrue("$name body text", contrast(c.ink, surface) >= 7f)
                assertTrue("$name secondary text", contrast(c.inkMuted, surface) >= 4.5f)
                assertTrue("$name faint text and glyphs", contrast(c.inkFaint, surface) >= 3f)
                assertTrue("$name accent", contrast(c.accent, surface) >= 4.5f)
                assertTrue("$name danger", contrast(c.danger, surface) >= 4.5f)
            }
            assertTrue("$name label on the solid action", contrast(c.onSolid, c.solid) >= 7f)
            assertTrue("$name label on the accent tint", contrast(c.onAccentSoft, c.accentSoft) >= 4.5f)
            assertTrue("$name label on the danger tint", contrast(c.onDangerSoft, c.dangerSoft) >= 4.5f)
        }
    }
}
