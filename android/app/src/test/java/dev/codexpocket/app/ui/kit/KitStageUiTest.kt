package dev.codexpocket.app.ui.kit

import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.LocalPocketGlass
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
import kotlin.math.abs

/** Pages move like a navigation stack: both are seen while one replaces the other, and only the one asked for is live. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitStageUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))
    private var page by mutableStateOf("home")
    private var backs = 0
    private lateinit var stage: PStageState<String>
    private lateinit var view: android.view.View
    private val depth = mapOf("home" to 0, "chat" to 1, "detail" to 2, "other" to 0)

    private fun content(peek: () -> String? = { null }) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) {
                stage = rememberPStageState(page)
                PStage(page, depth = { depth.getValue(it) }, back = peek(), onBack = ({ backs++; page = "home" }).takeIf { page != "home" }, state = stage) { shown ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PButton("打开 $shown", { page = if (shown == "home") "chat" else "detail" }, Modifier.testTag("button-$shown"))
                    }
                }
            }
        } } }
        compose.waitForIdle()
    }
    // Frame by frame: a state change needs an idle pass between frames to reach its effects.
    private fun advance(ms: Long) { repeat((ms / 16).toInt().coerceAtLeast(1)) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun capture(name: String) = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File("build/reports/kit/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun pushBringsThePageInOverTheOneItLeavesAndOnlyTheNewPageIsLive() {
        content()
        val width = bounds("stage-page").width
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "chat" }
        advance(96)
        val arriving = bounds("stage-page")
        val leaving = bounds("stage-page-leaving")
        assertTrue("The new page is still on its way in from the trailing edge", arriving.left > width * .2f)
        assertTrue("The page beneath has started to recede the other way", leaving.left < 0f && leaving.left > -width * .3f)
        compose.onNodeWithTag("button-chat").assertExists()
        compose.onNodeWithTag("button-home").assertDoesNotExist()
        capture("stage-push-096")
        var last = arriving.left
        repeat(6) {
            advance(64)
            val now = bounds("stage-page").left
            assertTrue("The page only ever moves toward its place", now <= last + .5f)
            last = now
        }
        capture("stage-push-480")
        advance(1200)
        assertEquals(0f, bounds("stage-page").left, .5f)
        compose.onNodeWithTag("stage-page-leaving").assertDoesNotExist()
        assertEquals(1f, stage.progress.value, 0f)
    }

    @Test fun reversingMidWayTurnsRoundWhereItIsWithoutAJump() {
        content()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "chat" }
        advance(160)
        val before = bounds("stage-page").left
        compose.runOnIdle { page = "home" }
        advance(32)
        // The chat page is now the one leaving, and it is where it was a frame ago.
        val after = bounds("stage-page-leaving").left
        // Two frames at the speed it had: it keeps moving for a moment before it turns, and never leaps.
        assertTrue("Turning round does not move the page: $before then $after", abs(after - before) < 130f)
        assertEquals(PStageMove.Pop, stage.move)
        compose.onNodeWithTag("button-home").assertExists()
        advance(1500)
        compose.onNodeWithTag("stage-page-leaving").assertDoesNotExist()
        compose.onNodeWithTag("button-home").assertIsDisplayed()
        assertEquals(0f, bounds("stage-page").left, .5f)
    }

    @Test fun siblingsFadeThroughAndStillEndWithOnePage() {
        content()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "other" }
        advance(64)
        assertEquals(PStageMove.Fade, stage.move)
        assertEquals("Pages at one level do not slide over each other", 0f, bounds("stage-page-leaving").left, .5f)
        advance(1200)
        compose.onNodeWithTag("stage-page-leaving").assertDoesNotExist()
        compose.onNodeWithTag("button-other").assertIsDisplayed()
    }

    @Test fun withMotionOffThePageIsSimplyReplaced() {
        preferences = PocketGlassPreferences(false, false, false)
        content()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "chat" }
        advance(64)
        compose.onNodeWithTag("stage-page-leaving").assertDoesNotExist()
        compose.onNodeWithTag("button-chat").assertIsDisplayed()
        assertEquals(0f, bounds("stage-page").left, .5f)
    }

    @Test fun aBackGestureDragsThePageAsideShowsWhereBackLeadsAndCanBeAbandoned() {
        page = "chat"
        content(peek = { if (page == "chat") "home" else null })
        val dispatcher = compose.activity.onBackPressedDispatcher
        fun gesture(progress: Float, start: Boolean = false) {
            compose.runOnIdle {
                val event = BackEventCompat(0f, 400f, progress, BackEventCompat.EDGE_LEFT)
                if (start) dispatcher.dispatchOnBackStarted(event) else dispatcher.dispatchOnBackProgressed(event)
            }
            compose.waitForIdle()
        }
        val width = bounds("stage-page").width
        gesture(0f, start = true); gesture(.5f)
        assertTrue("The page follows the gesture", bounds("stage-page").left > width * .3f)
        assertTrue("The page Back leads to shows beneath, not yet live", bounds("stage-page-leaving").left < 0f)
        compose.onNodeWithTag("button-chat").assertExists()
        compose.onNodeWithTag("button-home").assertDoesNotExist()
        assertEquals("A preview goes nowhere", 0, backs)
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals("Abandoned, the page returns", 0f, bounds("stage-page").left, .5f)
        compose.onNodeWithTag("stage-page-leaving").assertDoesNotExist()
        assertEquals(0, backs)

        gesture(0f, start = true); gesture(.6f)
        val held = bounds("stage-page").left
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { dispatcher.onBackPressed(); assertEquals("Committing is the same synchronous call", 1, backs) }
        advance(32)
        assertTrue("It leaves from where the gesture held it, not from the start", bounds("stage-page-leaving").left >= held - 1f)
        advance(1500)
        compose.onNodeWithTag("stage-page-leaving").assertDoesNotExist()
        compose.onNodeWithTag("button-home").assertIsDisplayed()
        assertEquals(1, backs)
    }

    @Test fun aStageWithNothingBehindItLetsTheOneAroundItPreviewTheWayOut() {
        var outerPage by mutableStateOf("assistant")
        var innerBacks = 0
        lateinit var outer: PStageState<String>
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) {
                outer = rememberPStageState(outerPage)
                PStage(outerPage, depth = { if (it == "home") 0 else 1 }, back = "home".takeIf { outerPage != "home" },
                    onBack = ({ outerPage = "home" }).takeIf { outerPage != "home" }, state = outer) { shown ->
                    if (shown == "home") PText("首页", Modifier.testTag("outer-home"))
                    else PStage("main", onBack = { innerBacks++; outerPage = "home" }) { PText("助理", Modifier.testTag("inner-main")) }
                }
            }
        } } }
        compose.waitForIdle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 400f, .5f, BackEventCompat.EDGE_LEFT)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("The outer stage holds the page for the inner one", outer.held)
            assertEquals("home", outer.to)
            assertTrue(outer.progress.value > .3f)
            assertEquals(0, innerBacks)
        }
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(outer.held); assertEquals("assistant", outer.to) }
        compose.onNodeWithTag("inner-main").assertIsDisplayed()
        compose.runOnIdle { dispatcher.onBackPressed(); assertEquals("The inner stage still makes the call", 1, innerBacks) }
        compose.waitForIdle()
        compose.onNodeWithTag("outer-home").assertIsDisplayed()
        compose.onNodeWithTag("inner-main").assertDoesNotExist()
    }

    @Test fun onlyWhatAppearsAfterThePageHasSettledArrivesAndOnlyOnce() {
        var rows by mutableStateOf(listOf("a", "b"))
        lateinit var arrivals: PArrivals
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) {
                arrivals = rememberPocketArrivals()
                Column { rows.forEach { row -> key(row) { PText("行 $row", Modifier.pocketArrival(arrivals, row).testTag("row-$row")) } } }
            }
        } } }
        compose.waitForIdle()
        val rest = bounds("row-b").top
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { rows = rows + "c" }
        advance(64)
        val arriving = bounds("row-c").top
        assertEquals("What was there when the page opened did not move", rest, bounds("row-b").top, .5f)
        advance(1200)
        val settled = bounds("row-c").top
        assertTrue("The new row rose into place: $arriving then $settled", arriving > settled + 4f)
        compose.runOnIdle { assertFalse("A key that has been shown never arrives again", arrivals.fresh("c", true)) }
    }

    @Test fun plainBackStillGoesBackOnce() {
        page = "chat"
        content()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, backs)
        compose.onNodeWithTag("button-home").assertIsDisplayed()
        compose.onNodeWithTag("stage-page-leaving").assertDoesNotExist()
    }

    @Test fun aListPulledPastItsEdgeFollowsTheFingerLessAndLessAndSpringsBack() {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) {
                LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                    items(40) { index -> Box(Modifier.fillMaxWidth().height(56.dp).testTag("row-$index")) { PText("第 $index 行") } }
                }
            }
        } } }
        compose.waitForIdle()
        val rest = bounds("row-0").top
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("list").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 60f)); moveBy(androidx.compose.ui.geometry.Offset(0f, 300f)) }
        advance(32)
        val pulled = bounds("row-0").top - rest
        assertTrue("The list comes with the finger past its top: $pulled", pulled > 40f)
        assertTrue("but gives less than the finger travelled", pulled < 300f)
        compose.onNodeWithTag("list").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, 300f)) }
        advance(32)
        val further = bounds("row-0").top - rest
        assertTrue("The second stretch of the same length gives less than the first", further - pulled < pulled)
        compose.onNodeWithTag("list").performTouchInput { up() }
        advance(1500)
        assertEquals("Let go, it returns to rest", rest, bounds("row-0").top, .5f)
    }

    @Test fun theRubberBandIsMonotonicBoundedAndInvertible() {
        var last = 0f
        for (distance in 1..4000 step 37) {
            val shown = rubberBand(distance.toFloat(), 800f)
            assertTrue(shown > last && shown < 800f && shown <= distance)
            assertEquals(distance.toFloat(), rubberBandInverse(shown, 800f), distance * .01f + .5f)
            last = shown
        }
        assertEquals(-rubberBand(120f, 800f), rubberBand(-120f, 800f), 0f)
        assertEquals(0f, rubberBand(0f, 800f), 0f)
        // SwiftUI's default spring: response 0.55 is a stiffness of about 130.5.
        assertEquals(130.5f, PocketSprings.stiffness(.55f), .2f)
    }
}
