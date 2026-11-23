package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The assistant's entry is alive while it waits, answers a tap, and never changes size or moves its neighbours. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantEntryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))
    private var attention by mutableStateOf(false)
    private var theme by mutableStateOf("light")
    private var opened = 0
    private lateinit var view: android.view.View

    /** Call with the test clock already stopped: a loop that starts while it runs by itself is cancelled by the test framework. */
    private fun content() {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) {
                Row(Modifier.padding(40.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    AssistantPocketButton({ opened++ }, attention = attention)
                    AssistantAvatar(Modifier.testTag("avatar"), size = 44.dp)
                }
            }
        } } }
        compose.waitForIdle()
    }
    private fun advance(ms: Long) { repeat((ms / 16).toInt().coerceAtLeast(1)) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
    private fun pixels(name: String? = null): IntArray = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        if (name != null) java.io.File("build/reports/kit/$name.png").also { it.parentFile.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height); bitmap.recycle() }
    }
    private fun region(all: IntArray, tag: String): IntArray {
        val b = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return IntArray(b.width.toInt() * b.height.toInt()).also { out ->
            var i = 0
            for (y in b.top.toInt() until b.top.toInt() + b.height.toInt()) for (x in b.left.toInt() until b.left.toInt() + b.width.toInt()) out[i++] = all[y * view.width + x]
        }
    }
    private fun button() = compose.onNodeWithTag("assistant-pocket-button")

    @Test fun theFaceBlinksWhileItWaitsAndTheButtonKeepsItsSize() {
        compose.mainClock.autoAdvance = false
        content()
        advance(64)
        button().assertWidthIsEqualTo(60.dp).assertHeightIsEqualTo(60.dp).assertHasClickAction()
        val bounds = button().fetchSemanticsNode().boundsInRoot
        val frames = (0 until 30).map { advance(160); region(pixels(if (it == 9) "assistant-entry-idle" else null), "assistant-pocket-face") }
        assertTrue("The face never moves while it waits", frames.distinctBy { it.contentHashCode() }.size > 2)
        assertEquals(bounds, button().fetchSemanticsNode().boundsInRoot)
        // The avatar elsewhere is the same face, at rest.
        val calm = pixels(); advance(900)
        assertArrayEquals(region(calm, "avatar"), region(pixels(), "avatar"))
    }

    @Test fun aTapMakesItHopSendsARingOutAndOpensTheAssistantOnce() {
        compose.mainClock.autoAdvance = false
        content()
        advance(64)
        val before = pixels()
        button().performTouchInput { down(center) }
        advance(160)
        assertFalse("Held, the face smiles", region(before, "assistant-pocket-face").contentEquals(region(pixels("assistant-entry-held"), "assistant-pocket-face")))
        button().performTouchInput { up() }
        advance(180)
        assertEquals(1, opened)
        assertFalse(region(before, "assistant-pocket-face").contentEquals(region(pixels("assistant-entry-tapped"), "assistant-pocket-face")))
        val avatar = compose.onNodeWithTag("avatar").fetchSemanticsNode().boundsInRoot
        advance(1400)
        assertEquals("Nothing beside it moved", avatar, compose.onNodeWithTag("avatar").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun attentionCirclesTheDiscAndMotionOffLeavesAStillFace() {
        attention = true; theme = "dark"
        compose.mainClock.autoAdvance = false
        content()
        advance(64)
        compose.onNodeWithContentDescription("打开口袋助理，有待确认事项").assertExists()
        val first = region(pixels("assistant-entry-attention"), "assistant-pocket-button"); advance(480)
        assertFalse("The arc keeps circling", first.contentEquals(region(pixels(), "assistant-pocket-button")))
        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, false) }
        advance(64)
        val still = pixels(); advance(1200)
        assertArrayEquals(still, pixels())
        button().performClick()
        advance(300)
        assertEquals(1, opened)
    }
}
