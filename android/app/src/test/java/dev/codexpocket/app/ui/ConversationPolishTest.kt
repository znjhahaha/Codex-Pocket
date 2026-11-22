package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.LocalPocketHaptics
import dev.codexpocket.app.ui.kit.PocketHaptic
import dev.codexpocket.app.ui.kit.PocketHaptics
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The conversation's glyph morphs and haptics: one per deliberate tap, the old boxes at rest, still when motion is off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationPolishTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View
    private val haptics = mutableListOf<PocketHaptic>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false,
                LocalPocketHaptics provides PocketHaptics { haptics += it }) { PocketBackdrop { block() } }
        } } }
        compose.waitForIdle()
    }
    private fun pixels(b: Rect): IntArray = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val width = b.right.toInt() - b.left.toInt(); val height = b.bottom.toInt() - b.top.toInt()
        IntArray(width * height).also { bitmap.getPixels(it, 0, width, b.left.toInt(), b.top.toInt(), width, height); bitmap.recycle() }
    }
    private fun described(label: String): Rect = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
    private fun tagged(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun advance(ms: Long) { compose.waitForIdle(); compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun text(id: Int) = compose.activity.getString(id)

    @Test fun sendAndStopAreFeltOncePerAcceptedTapAndNeverOnRecomposition() {
        var running by mutableStateOf(false)
        var ready by mutableStateOf(true)
        var draft by mutableStateOf("draft")
        var sends = 0; var stops = 0
        content {
            PocketComposer(draft, "Model", "", running, ComposerAvailability(ready, false, true, running && ready),
                {}, {}, {}, {}, { sends++ }, {}, { stops++ }, true)
        }
        // 48dp send and add targets, as before the glyphs moved.
        assertEquals(48 * 3f, tagged("composer-send").width, .01f); assertEquals(48 * 3f, tagged("composer-send").height, .01f)
        assertEquals(48 * 3f, described(text(R.string.v02_references)).width, .01f)
        compose.onNodeWithContentDescription(text(R.string.send_message)).assertExists()
        compose.runOnIdle { assertTrue("Composing is silent", haptics.isEmpty()) }
        compose.onNodeWithTag("composer-send").performClick()
        compose.runOnIdle { assertEquals(1, sends); assertEquals(listOf(PocketHaptic.Confirm), haptics) }
        compose.runOnIdle { draft = "draft, edited" }
        compose.runOnIdle { ready = false }
        compose.onNodeWithTag("composer-send").performClick()
        compose.runOnIdle { assertEquals("A disabled send neither acts nor buzzes", 1, sends); assertEquals(1, haptics.size) }

        compose.runOnIdle { ready = true; running = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("A turn starting by itself is not a touch", 1, haptics.size) }
        assertEquals(44 * 3f, described(text(R.string.stop_turn)).width, .01f); assertEquals(44 * 3f, described(text(R.string.stop_turn)).height, .01f)
        compose.onNodeWithContentDescription(text(R.string.v02_queue)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.stop_turn)).performClick()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(listOf(PocketHaptic.Confirm, PocketHaptic.Tap), haptics) }
        compose.runOnIdle { running = false }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(R.string.send_message)).assertExists()
        compose.runOnIdle { assertEquals(2, haptics.size) }
    }

    @Test fun stopSquareTurnsInWhenATurnStartsAndTheArrowReturnsWhenItEnds() {
        var running by mutableStateOf(false)
        content {
            PocketComposer("draft", "Model", "", running, ComposerAvailability(true, false, true, running),
                {}, {}, {}, {}, {}, {}, {}, true, readingHistory = true)
        }
        val send = tagged("composer-send")
        val arrow = pixels(send)
        compose.runOnIdle { running = true }
        compose.waitForIdle()
        val stop = described(text(R.string.stop_turn))
        assertEquals(44 * 3f, stop.width, .01f)
        val square = pixels(stop)
        compose.runOnIdle { running = false }
        compose.waitForIdle()
        assertArrayEquals("Send rests as the same arrow after a turn", arrow, pixels(send))

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { running = true }
        advance(64)
        assertEquals(stop, described(text(R.string.stop_turn)))
        val turning = pixels(stop)
        assertFalse("The square is still arriving", square.contentEquals(turning))
        advance(1500)
        assertArrayEquals("And ends as the stop square", square, pixels(stop))
        compose.runOnIdle { running = false }
        advance(64)
        assertEquals(send, tagged("composer-send"))
        assertFalse("The arrow is still returning", arrow.contentEquals(pixels(send)))
        advance(1500)
        assertArrayEquals(arrow, pixels(send))

        // Motion off: the first frames already show the end state.
        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, false) }
        compose.runOnIdle { running = true }
        advance(32)
        assertArrayEquals("Reduced motion snaps to the square", square, pixels(stop))
    }

    @Test fun openingOnARunningTurnShowsTheSquareWithoutPlaying() {
        content {
            PocketComposer("draft", "Model", "", true, ComposerAvailability(true, false, true, true),
                {}, {}, {}, {}, {}, {}, {}, true, readingHistory = true)
        }
        compose.mainClock.autoAdvance = false
        val stop = described(text(R.string.stop_turn))
        val first = pixels(stop)
        advance(800)
        assertArrayEquals("History creates no animation", first, pixels(stop))
    }

    @Test fun copyDrawsACheckAndReturnsToCopy() {
        var copies = 0
        content { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CopyAction("answer") { copies++ } } }
        val label = text(R.string.copy)
        val box = described(label)
        assertEquals(40 * 3f, box.width, .01f); assertEquals(40 * 3f, box.height, .01f)
        val copy = pixels(box)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(label).performClick()
        advance(48)
        assertEquals(1, copies)
        val drawing = pixels(box)
        advance(700)
        val check = pixels(box)
        assertFalse(copy.contentEquals(check)); assertFalse("The check is drawn, not swapped in", drawing.contentEquals(check))
        compose.onAllNodesWithContentDescription(label).assertCountEquals(1)
        assertEquals("The box never changes", box, described(label))
        advance(1400)
        advance(900)
        assertArrayEquals("The copy glyph comes back", copy, pixels(box))

        compose.runOnIdle { preferences = PocketGlassPreferences(false, true, true) }
        compose.onNodeWithContentDescription(label).performClick()
        advance(48)
        assertArrayEquals("Battery saver shows the check at once", check, pixels(box))
        advance(1400)
        advance(48)
        assertArrayEquals(copy, pixels(box))
        assertEquals(2, copies)
    }

    @Test fun approvingAndRejectingAreFeltOnceEach() {
        fun request(id: Int) = PendingApproval("runtime", JsonPrimitive(id), "item/commandExecution/requestApproval",
            buildJsonObject { put("threadId", "chat"); put("command", "cargo test") })
        var approval by mutableStateOf(request(1))
        var online by mutableStateOf(true)
        val decisions = mutableListOf<String>()
        content { ApprovalCard(approval, online) { _, response -> decisions += response.string("decision") } }
        compose.runOnIdle { assertTrue(haptics.isEmpty()) }
        compose.onNodeWithText("允许一次").performClick()
        compose.onNodeWithText("允许一次").performClick()
        compose.runOnIdle { assertEquals(listOf("accept"), decisions); assertEquals(listOf(PocketHaptic.Confirm), haptics) }
        // A passive update re-enables the card without a touch.
        compose.runOnIdle { online = false }
        compose.runOnIdle { online = true }
        compose.runOnIdle { assertEquals(1, haptics.size) }
        compose.runOnIdle { approval = request(2) }
        compose.onNodeWithText("拒绝").performClick()
        compose.runOnIdle { assertEquals(listOf("accept", "decline"), decisions); assertEquals(listOf(PocketHaptic.Confirm, PocketHaptic.Reject), haptics) }
        compose.runOnIdle { approval = request(3) }
        compose.onNodeWithText("取消任务").performClick()
        compose.runOnIdle { assertEquals(PocketHaptic.Reject, haptics.last()); assertEquals(3, haptics.size) }
    }

    @Test fun expansionChevronTurnsOnASpringAndSnapsWhenMotionIsOff() {
        var expanded by mutableStateOf(false)
        content { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Box(Modifier.testTag("chevron")) { ActivityExpansionIcon(expanded) } } }
        val box = tagged("chevron")
        assertEquals(16 * 3f, box.width, .01f); assertEquals(16 * 3f, box.height, .01f)
        val down = pixels(box)
        compose.onNodeWithContentDescription(text(R.string.expand_details)).assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded = true }
        advance(48)
        val turning = pixels(box)
        advance(1500)
        val up = pixels(box)
        assertFalse(down.contentEquals(up)); assertFalse(turning.contentEquals(up)); assertFalse(turning.contentEquals(down))
        compose.onNodeWithContentDescription(text(R.string.collapse_details)).assertExists()
        assertEquals(box, tagged("chevron"))
        compose.runOnIdle { expanded = false }
        advance(1500)
        assertArrayEquals(down, pixels(box))
        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, false); expanded = true }
        advance(32)
        assertArrayEquals("Reduced motion turns at once", up, pixels(box))
    }
}
