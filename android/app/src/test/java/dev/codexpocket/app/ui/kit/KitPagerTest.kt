package dev.codexpocket.app.ui.kit

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.LocalPocketGlass
import dev.codexpocket.app.ui.LocalPocketGlassPreferences
import dev.codexpocket.app.ui.PocketGlassPreferences
import dev.codexpocket.app.ui.PocketTheme
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pages follow a finger one to one, resist at the ends and settle at most one page away. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitPagerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))
    private lateinit var pager: PPagerState
    private lateinit var scope: CoroutineScope

    private fun content() {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) {
                pager = rememberPPager(4); scope = rememberCoroutineScope()
                Column { PPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("pager")) { page, _ -> PText("第 $page 页") }; PPageDots(pager) }
            }
        } } }
        compose.waitForIdle()
    }
    private fun advance(ms: Long) { repeat((ms / 16).toInt().coerceAtLeast(1)) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
    private fun drag(fraction: Float, release: Boolean = true) = compose.onNodeWithTag("pager").performTouchInput {
        down(center); repeat(8) { moveBy(Offset(width * fraction / 8f, 0f), 16) }; if (release) up()
    }

    @Test fun aDragCarriesThePagesAndLettingGoSettlesOnTheNearestOne() {
        content()
        compose.mainClock.autoAdvance = false
        drag(-.4f, release = false)
        advance(16)
        assertTrue("The pages are between the first and second: ${pager.position}", pager.position > .2f && pager.position < .5f)
        assertTrue(pager.dragging)
        compose.onNodeWithTag("pager-page:0").assertExists(); compose.onNodeWithTag("pager-page:1").assertExists()
        compose.onNodeWithTag("pager").performTouchInput { repeat(4) { moveBy(Offset(-width * .08f, 0f), 16) }; up() }
        advance(900)
        assertEquals(1, pager.page); assertEquals(1f, pager.position, .001f)
        compose.onNodeWithTag("pager-page:0").assertDoesNotExist()
        // A short pull that is let go returns.
        drag(-.12f)
        advance(900)
        assertEquals(1, pager.page); assertEquals(1f, pager.position, .001f)
    }

    @Test fun theEndsResistAndAThrowNeverSkipsAPage() {
        content()
        compose.mainClock.autoAdvance = false
        drag(.9f, release = false)
        advance(16)
        assertTrue("Pulled past the first page it gives a little: ${pager.position}", pager.position < 0f && pager.position > -.3f)
        compose.onNodeWithTag("pager").performTouchInput { up() }
        advance(900)
        assertEquals(0f, pager.position, .001f)
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft(durationMillis = 60) }
        advance(1200)
        assertEquals("However hard it is thrown it lands on the next page", 1, pager.page)
        compose.runOnIdle { pager.open(3, scope) }
        advance(1200)
        assertEquals(3f, pager.position, .001f)
        compose.runOnIdle { pager.open(9, scope) }
        advance(100)
        assertEquals(3, pager.page)
    }

    @Test fun withMotionOffAPageIsThereAtOnce() {
        preferences = PocketGlassPreferences(false, false, false)
        content()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { pager.open(2, scope) }
        advance(32)
        assertEquals(2f, pager.position, 0f)
        compose.onNodeWithText("第 2 页").assertExists()
    }
}
