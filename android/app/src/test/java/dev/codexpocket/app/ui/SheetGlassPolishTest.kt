package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.kit.LocalPocketHaptics
import dev.codexpocket.app.ui.kit.PocketHaptic
import dev.codexpocket.app.ui.kit.PocketHaptics
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Sheets settle on a spring and tick at the dismiss point; glass answers a press the same way whoever owns the click. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetGlassPolishTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View
    private val haptics = mutableListOf<PocketHaptic>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))

    private fun content(theme: String = "light", block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false,
                LocalPocketHaptics provides PocketHaptics { haptics += it }) { PocketBackdrop { block() } }
        } } }
        compose.waitForIdle()
    }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun pixels(b: Rect): IntArray = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val width = b.right.toInt() - b.left.toInt(); val height = b.bottom.toInt() - b.top.toInt()
        IntArray(width * height).also { bitmap.getPixels(it, 0, width, b.left.toInt(), b.top.toInt(), width, height); bitmap.recycle() }
    }
    private fun advance(ms: Long) { compose.waitForIdle(); compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun drag(dp: Int) { compose.onNodeWithText("会话操作").performTouchInput { moveBy(Offset(0f, dp * 3f)) }; compose.waitForIdle() }

    @Test fun sheetTicksAtTheDismissPointSpringsBackWhenShortAndDismissesOncePastIt() {
        var open by mutableStateOf(true)
        var dismissals = 0
        content { if (open) PocketSheet("会话操作", { dismissals++; open = false }) { Text("内容") } }
        val rest = bounds("pocket-modal")
        compose.onNodeWithText("会话操作").performTouchInput { down(center) }
        drag(50)
        val pulled = bounds("pocket-modal")
        assertTrue("The sheet follows the finger", pulled.top > rest.top + 60f)
        assertTrue("Short of 72dp nothing is felt", haptics.isEmpty())
        drag(50); drag(10)
        assertEquals("Crossing the dismiss point is felt once", listOf(PocketHaptic.Threshold), haptics)
        drag(-50)
        assertEquals("And so is coming back", listOf(PocketHaptic.Threshold, PocketHaptic.Tick), haptics)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("会话操作").performTouchInput { up() }
        advance(48)
        val returning = bounds("pocket-modal").top
        assertTrue("It springs back rather than jumping: $returning", returning > rest.top + 1f && returning < rest.top + 60 * 3f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(rest, bounds("pocket-modal"))
        assertEquals(0, dismissals)

        // Motion off: let go short and the sheet is back at once.
        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, false) }
        compose.onNodeWithText("会话操作").performTouchInput { down(center) }
        drag(50)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("会话操作").performTouchInput { up() }
        advance(32)
        assertEquals(rest, bounds("pocket-modal"))
        compose.mainClock.autoAdvance = true

        compose.onNodeWithText("会话操作").performTouchInput { down(center) }
        drag(60); drag(60)
        compose.onNodeWithText("会话操作").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, dismissals)
        assertEquals(listOf(PocketHaptic.Threshold, PocketHaptic.Tick, PocketHaptic.Threshold), haptics)
        compose.onNodeWithTag("pocket-modal").assertDoesNotExist()
    }

    @Test fun topHighlightFollowsTheGlassSettingNotTheBackdrop() {
        var glass by mutableStateOf(true)
        content("dark") {
            CompositionLocalProvider(LocalPocketGlass provides glass) {
                // No backdrop to sample: the surface is an opaque fill, as on releases without the lens.
                PocketGlassSurface(Modifier.size(120.dp, 60.dp).testTag("surface"), backdrop = null) { }
            }
        }
        val box = bounds("surface")
        val width = box.width.toInt()
        fun red(pixels: IntArray, x: Int, y: Int) = (pixels[y * width + x] shr 16) and 255
        val lit = pixels(box)
        val fill = red(lit, 90, 90)
        assertTrue("The upper-left edge carries the highlight: ${red(lit, 90, 0)} over $fill", red(lit, 90, 0) > fill + 12)
        assertEquals("It fades out along the contour", fill, red(lit, width - 91, box.height.toInt() - 1))
        compose.runOnIdle { glass = false }
        val flat = pixels(box)
        assertEquals("Glass off draws no highlight", red(flat, 90, 90), red(flat, 90, 0))
        assertEquals(box, bounds("surface"))
    }

    @Test fun glassAnswersAPressTheSameWayWhetherItOwnsTheClickOrTheCallerDoes() {
        var clicks = 0
        content {
            Column {
                PocketGlassSurface(Modifier.size(120.dp, 60.dp).testTag("own"), backdrop = null, onClick = { clicks++ }) { Text("动作") }
                val press = remember { MutableInteractionSource() }
                PocketGlassSurface(Modifier.size(120.dp, 60.dp).clickable(press, indication = null) { clicks += 10 }.testTag("lent"),
                    backdrop = null, interactionSource = press) { Text("动作") }
                PocketGlassSurface(Modifier.size(120.dp, 60.dp).testTag("plain"), backdrop = null) { Text("动作") }
            }
        }
        val boxes = listOf("own", "lent", "plain").associateWith { bounds(it) }
        boxes.values.forEach { assertEquals(120 * 3f, it.width, .01f); assertEquals(60 * 3f, it.height, .01f) }
        val rest = boxes.mapValues { pixels(it.value) }
        assertTrue("Lending the press changes nothing at rest",
            rest.getValue("lent").indices.count { rest.getValue("lent")[it] != rest.getValue("plain")[it] } <= rest.getValue("lent").size / 200)
        compose.mainClock.autoAdvance = false
        fun pressed(tag: String): IntArray {
            compose.onNodeWithTag(tag).performTouchInput { down(center) }
            advance(400)
            assertEquals("The hit target stays outside the response", boxes[tag], bounds(tag))
            return pixels(boxes.getValue(tag)).also { compose.onNodeWithTag(tag).performTouchInput { up() }; advance(600) }
        }
        val own = pressed("own"); val lent = pressed("lent")
        assertFalse(rest.getValue("own").contentEquals(own)); assertFalse(rest.getValue("lent").contentEquals(lent))
        fun changed(a: IntArray, b: IntArray) = a.indices.count { a[it] != b[it] }
        val ownChange = changed(rest.getValue("own"), own); val lentChange = changed(rest.getValue("lent"), lent)
        assertTrue("Both respond over the whole surface: $ownChange and $lentChange", kotlin.math.abs(ownChange - lentChange) <= own.size / 50 && lentChange > own.size / 4)
        assertEquals("The lit fill is the same", own[own.size / 2 + 20], lent[lent.size / 2 + 20])
        assertArrayEquals("Both return to rest", rest.getValue("lent"), pixels(boxes.getValue("lent")))
        assertEquals(11, clicks)
    }
}
