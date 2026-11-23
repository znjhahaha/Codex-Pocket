package dev.codexpocket.app.ui.kit

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.HomeTab
import dev.codexpocket.app.ui.LocalPocketGlass
import dev.codexpocket.app.ui.LocalPocketGlassPreferences
import dev.codexpocket.app.ui.PocketGlassPreferences
import dev.codexpocket.app.ui.PocketHomeNavigation
import dev.codexpocket.app.ui.PocketSegmented
import dev.codexpocket.app.ui.PocketTheme
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** A sliding selection can be carried by a finger as well as sent by a tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitSlideUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))
    private val haptics = mutableListOf<PocketHaptic>()
    private var selected by mutableIntStateOf(0)
    private var accept = true
    private lateinit var slide: PSlideState
    private lateinit var scope: kotlinx.coroutines.CoroutineScope

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false,
                LocalPocketHaptics provides PocketHaptics { haptics += it }) { block() }
        } } }
        compose.waitForIdle()
    }
    /** Three items of 100dp with a thumb drawn from the state, so its place can be read. */
    private fun row(count: Int = 3) = content {
        slide = rememberPSlide(count, selected) { if (accept) selected = it }
        scope = rememberCoroutineScope()
        Box(Modifier.padding(start = 20.dp, top = 40.dp).width(100.dp * count).height(48.dp).pocketSlide(slide).testTag("row")) {
            Box(Modifier.offset(x = 100.dp * slide.start.value).width(100.dp * (slide.end.value - slide.start.value)).fillMaxHeight().testTag("thumb"))
        }
    }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun advance(ms: Long) { repeat((ms / 16).toInt().coerceAtLeast(1)) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
    /** Down at [x] across the row and along by [dx], in steps that pass the touch slop first. */
    private fun carry(x: Float, dx: Float, tag: String = "row") = compose.onNodeWithTag(tag).performTouchInput {
        down(Offset(x, centerY))
        val steps = 8
        repeat(steps) { moveBy(Offset(dx / steps, 0f)) }
    }
    private fun letGo(tag: String = "row") = compose.onNodeWithTag(tag).performTouchInput { up() }

    @Test fun theThumbFollowsTheFingerAndSelectsTheItemItIsLetGoOver() {
        row()
        val item = bounds("row").width / 3
        val rest = bounds("thumb")
        assertEquals(item, rest.width, .5f)
        carry(item * .5f, item * 1.1f)
        compose.waitForIdle()
        val carried = bounds("thumb")
        // The system keeps the first few pixels to tell a drag from a tap.
        assertTrue("It came with the finger: ${carried.left - rest.left} of ${item * 1.1f}", carried.left - rest.left in item * .9f..item * 1.12f)
        compose.runOnIdle {
            assertTrue(slide.dragging); assertEquals("Nothing is chosen until it is let go", 0, selected)
            assertEquals("but the item under it already reads as chosen", 1, slide.shown)
            assertTrue("Held, it is lifted", slide.lift.value > .9f)
            assertEquals("One tick for the one item it crossed into", listOf(PocketHaptic.Tick), haptics)
        }
        letGo()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, selected); assertFalse(slide.dragging); assertEquals(0f, slide.lift.value, .01f) }
        assertEquals("It rests exactly on the item", rest.left + item, bounds("thumb").left, .5f)
        assertEquals(item, bounds("thumb").width, .5f)
    }

    @Test fun pulledPastAnEndItGivesLessAndLessAndComesBack() {
        selected = 2
        row()
        val item = bounds("row").width / 3
        val rest = bounds("thumb")
        carry(item * 2.5f, item * .6f)
        compose.waitForIdle()
        val pulled = bounds("thumb").left - rest.left
        assertTrue("It goes a little way past the end: $pulled", pulled > 4f && pulled < item * .32f)
        compose.onNodeWithTag("row").performTouchInput { moveBy(Offset(item * 3f, 0f)) }
        compose.waitForIdle()
        assertTrue("and never further than a third of an item, however far the finger goes", bounds("thumb").left - rest.left < item * .33f)
        letGo()
        compose.waitForIdle()
        assertEquals(rest.left, bounds("thumb").left, .5f)
        compose.runOnIdle { assertEquals(2, selected) }
    }

    @Test fun aQuickFlickCarriesItToTheNextItemAndASlowNudgeDoesNot() {
        row()
        val item = bounds("row").width / 3
        compose.onNodeWithTag("row").performTouchInput { swipe(Offset(item * .5f, centerY), Offset(item * .88f, centerY), durationMillis = 60) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Thrown, it lands one along", 1, selected) }
        compose.onNodeWithTag("row").performTouchInput { swipe(Offset(item * 1.5f, centerY), Offset(item * 1.82f, centerY), durationMillis = 900) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Carried less than half-way and set down, it stays", 1, selected) }
        assertEquals(bounds("row").left + item, bounds("thumb").left, .5f)
    }

    @Test fun anOwnerThatRefusesGetsItsThumbBack() {
        accept = false
        row()
        val item = bounds("row").width / 3
        val rest = bounds("thumb")
        carry(item * .5f, item * 2f)
        letGo()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, selected); assertEquals(0, slide.shown) }
        assertEquals("The thumb shows what the owner holds", rest.left, bounds("thumb").left, .5f)
    }

    @Test fun aLandingThatSomethingElseTakesOverIsNotTakenForARefusal() {
        row()
        compose.mainClock.autoAdvance = false
        var landed: Boolean? = null
        compose.runOnIdle { scope.launch { landed = slide.settle(2) } }
        advance(48)
        // The thumb is on its way when another animation takes it: a later selection, a new drag.
        compose.runOnIdle { scope.launch { slide.start.snapTo(1f); slide.end.snapTo(2f) } }
        advance(48)
        compose.runOnIdle { assertEquals("It did not get there, and says so", false, landed) }
        compose.runOnIdle { scope.launch { landed = slide.settle(2) } }
        advance(1600)
        compose.runOnIdle { assertEquals(true, landed); assertEquals(2f, slide.start.value, .001f) }
    }

    @Test fun withMotionOffItStillFollowsAndLandsAtOnce() {
        preferences = PocketGlassPreferences(false, false, false)
        row()
        val item = bounds("row").width / 3
        val rest = bounds("thumb")
        compose.mainClock.autoAdvance = false
        carry(item * .5f, item * 2f)
        advance(32)
        assertTrue(bounds("thumb").left - rest.left > item * 1.8f)
        letGo()
        advance(32)
        compose.runOnIdle { assertEquals(2, selected) }
        assertEquals(rest.left + item * 2, bounds("thumb").left, .5f)
    }

    @Test fun aTapStillSendsItThereWithItsLeadingEdgeAhead() {
        row()
        val item = bounds("row").width / 3
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = 2 }
        advance(96)
        val moving = bounds("thumb")
        assertTrue("It stretches the way it is going: ${moving.width}", moving.width > item + 12f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(bounds("row").left + item * 2, bounds("thumb").left, .5f)
        assertEquals(item, bounds("thumb").width, .5f)
        compose.runOnIdle { assertTrue("A tap is not a drag", haptics.isEmpty()) }
    }

    @Test fun theKitsSegmentedControlAndTheViewersCanBothBeDragged() {
        var segment by mutableIntStateOf(0)
        var view by mutableIntStateOf(0)
        content { Column(Modifier.padding(20.dp)) {
            PSegmented(listOf("上游", "模型", "对话"), segment, { segment = it }, Modifier.testTag("segments"))
            PocketSegmented(listOf("预览", "源码"), view, { view = it }, Modifier.testTag("viewer"))
        } }
        val width = bounds("segments").width
        carry(width / 6, width * .66f, "segments")
        compose.onNodeWithText("上游").assertIsSelected()
        letGo("segments")
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, segment) }
        compose.onNodeWithText("对话").assertIsSelected()
        compose.onNodeWithText("模型").performClick()
        compose.runOnIdle { assertEquals("Tapping is unchanged", 1, segment) }

        val viewer = bounds("viewer").width
        carry(viewer * .25f, viewer * .5f, "viewer")
        letGo("viewer")
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, view) }
        compose.onNodeWithText("源码").assertIsSelected()
    }

    @Test fun theHomeBarsPillCanBeCarriedToAnotherTab() {
        var tab by mutableStateOf(HomeTab.Chats)
        content { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { PocketHomeNavigation(tab, 0, onSelect = { tab = it }) } }
        val tabs = HomeTab.entries.map { bounds("home-tab-${it.name.lowercase()}") }
        val item = tabs[0].width
        assertEquals(tabs[0], bounds("home-tab-selection"))
        compose.onNodeWithTag("home-tab-chats").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(item * .2f, 0f)) }
        }
        compose.waitForIdle()
        val carried = bounds("home-tab-selection")
        assertTrue("The pill is over the last tab: $carried", abs(carried.center.x - tabs[2].center.x) < item * .2f)
        compose.runOnIdle { assertEquals("The page has not changed yet", HomeTab.Chats, tab) }
        compose.onNodeWithTag("home-tab-chats").assertIsSelected()
        compose.onNodeWithTag("home-tab-chats").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(HomeTab.Devices, tab) }
        assertEquals(tabs[2], bounds("home-tab-selection"))
        compose.onNodeWithTag("home-tab-devices").assertIsSelected()
        assertEquals("Hit targets never moved", tabs, HomeTab.entries.map { bounds("home-tab-${it.name.lowercase()}") })
    }

    @Test fun aRowWithNothingSelectedCannotBeDragged() {
        selected = -1
        row()
        val rest = bounds("thumb")
        carry(50f, 400f)
        letGo()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(-1, selected); assertFalse(slide.dragging) }
        assertEquals(rest, bounds("thumb"))
    }
}
