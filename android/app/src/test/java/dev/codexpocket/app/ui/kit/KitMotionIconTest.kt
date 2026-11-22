@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codexpocket.app.ui.kit

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
import dev.codexpocket.app.ui.LocalPocketGlassPreferences
import dev.codexpocket.app.ui.PocketGlassPreferences
import dev.codexpocket.app.ui.PocketTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitMotionIconTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))

    private fun pixels(name: String? = null): IntArray = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        if (name != null) java.io.File("build/reports/kit/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height); bitmap.recycle() }
    }
    /** Pixels inside the tagged node only, so unrelated window chrome cannot affect a comparison. */
    private fun region(tag: String, name: String? = null): IntArray {
        val all = pixels(name)
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val left = b.left.toInt(); val right = b.right.toInt(); val width = view.width
        return IntArray((right - left) * (b.bottom.toInt() - b.top.toInt())).also { out ->
            var i = 0
            for (y in b.top.toInt() until b.bottom.toInt()) for (x in left until right) out[i++] = all[y * width + x]
        }
    }
    private fun same(message: String, expected: IntArray, actual: IntArray) {
        val changed = expected.indices.count { expected[it] != actual[it] }
        assertEquals("$message ($changed of ${expected.size} pixels differ)", 0, changed)
    }
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences) { block() }
        } } }
        compose.waitForIdle()
    }

    @Test fun timingCurvesAreBoundedAndRestAtTheirEnds() {
        for (t in listOf(-1f, 0f, 1f, 2f)) {
            assertEquals(0f, GlyphMotion.pulse(t), 1e-5f); assertEquals(0f, GlyphMotion.swing(t), 1e-5f)
        }
        assertEquals(1f, GlyphMotion.pulse(.5f), 1e-5f)
        assertEquals(0f, GlyphMotion.window(.2f, .3f, .8f)); assertEquals(1f, GlyphMotion.window(.9f, .3f, .8f))
        var previous = -1f
        for (step in 0..100) {
            val t = step / 100f
            val eased = GlyphMotion.easeOut(t)
            assertTrue(eased in 0f..1f && eased >= previous); previous = eased
            assertTrue(GlyphMotion.smooth(t) in 0f..1f)
            assertTrue(kotlin.math.abs(GlyphMotion.swing(t)) <= 1f)
            // Knobs stay on their track and never cross its ends.
            assertTrue(MotionGlyphs.tuneUpperKnob(t) in 3f..16f); assertTrue(MotionGlyphs.tuneLowerKnob(t) in 3f..16f)
            assertTrue(MotionGlyphs.sparkleCompanion(t) in 0f..1f)
            assertTrue(MotionGlyphs.turn(t) in 0f..360f)
        }
        assertEquals(11f, MotionGlyphs.tuneUpperKnob(0f), 1e-4f); assertEquals(11f, MotionGlyphs.tuneUpperKnob(1f), 1e-4f)
        assertEquals(7f, MotionGlyphs.tuneLowerKnob(0f), 1e-4f); assertEquals(7f, MotionGlyphs.tuneLowerKnob(1f), 1e-4f)
        assertEquals(0f, MotionGlyphs.turn(1f)); assertEquals(0f, MotionGlyphs.turn(1f, 90f)); assertEquals(180f, MotionGlyphs.turn(.5f))
        assertEquals(1f, MotionGlyphs.sparkleCompanion(0f), 1e-5f); assertEquals(1f, MotionGlyphs.sparkleCompanion(1f), 1e-5f)
    }

    @Test fun restingGlyphsReturnToTheirStaticPoseAndTwoPoseGlyphsDiffer() {
        var t by mutableFloatStateOf(0f)
        content {
            Column {
                FlowRow(Modifier.testTag("rest")) { MotionGlyphs.restAtBothEnds.values.forEach { MotionIcon(it, { t }, Modifier.size(40.dp)) } }
                FlowRow(Modifier.testTag("pose")) { MotionGlyphs.twoPose.values.forEach { MotionIcon(it, { t }, Modifier.size(40.dp)) } }
            }
        }
        val rest = compose.onNodeWithTag("rest").fetchSemanticsNode().boundsInRoot
        fun crop(all: IntArray, top: Float, bottom: Float) = all.copyOfRange(top.toInt() * view.width, bottom.toInt() * view.width)
        val pose = compose.onNodeWithTag("pose").fetchSemanticsNode().boundsInRoot
        val start = pixels("glyphs-start")
        compose.runOnIdle { t = .37f }
        val moving = pixels("glyphs-moving")
        compose.runOnIdle { t = 1f }
        val end = pixels("glyphs-end")
        assertArrayEquals("Pulse and loop glyphs must finish where they began", crop(start, rest.top, rest.bottom), crop(end, rest.top, rest.bottom))
        assertFalse("Glyph geometry must move", crop(start, rest.top, rest.bottom).contentEquals(crop(moving, rest.top, rest.bottom)))
        assertFalse("Two-pose glyphs join different shapes", crop(start, pose.top, pose.bottom).contentEquals(crop(end, pose.top, pose.bottom)))
        assertEquals(rest, compose.onNodeWithTag("rest").fetchSemanticsNode().boundsInRoot)
        assertEquals(21, MotionGlyphs.restAtBothEnds.size + MotionGlyphs.twoPose.size)
    }

    @Test fun pulsePlaysOnlyOnChangeAndReducedMotionKeepsGlyphsStill() {
        var trigger by mutableIntStateOf(0)
        var on by mutableStateOf(false)
        var active by mutableStateOf(false)
        content {
            val pulse = rememberGlyphPulse(trigger)
            val toggle = rememberGlyphToggle(on)
            val loop = rememberGlyphLoop(active)
            Row(Modifier.testTag("icons")) {
                MotionIcon(MotionGlyphs.Bell, { pulse.value }, Modifier.size(32.dp))
                MotionIcon(MotionGlyphs.PlusClose, { toggle.value }, Modifier.size(32.dp))
                MotionIcon(MotionGlyphs.Users, { loop.value }, Modifier.size(32.dp))
            }
        }
        compose.mainClock.autoAdvance = false
        fun advance(ms: Long) { compose.waitForIdle(); compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
        val still = region("icons")
        advance(600)
        same("Nothing moves before a trigger", still, region("icons"))
        compose.runOnIdle { trigger++ }
        advance(200)
        assertFalse("A changed trigger plays the pulse", still.contentEquals(region("icons")))
        advance(900)
        same("The pulse ends at rest", still, region("icons"))
        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, false); trigger++; active = true }
        advance(200)
        same("Reduced motion shows the static glyph", still, region("icons"))
        compose.runOnIdle { on = true }
        advance(64)
        val closed = region("icons", "glyphs-reduced")
        advance(500)
        same("Reduced motion changes state at once", closed, region("icons"))
        assertFalse("The toggle still changes pose", still.contentEquals(closed))
    }
}
