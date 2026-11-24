package dev.codexpocket.app.ui.kit

import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
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

/** A page reached from a row opens out of that row and folds back into it, wherever the row now is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitMorphUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))
    private var page by mutableStateOf("home")
    private var rowTop by mutableStateOf(300.dp)
    private var marked by mutableStateOf(true)
    private lateinit var stage: PStageState<String>
    private lateinit var view: android.view.View

    private fun content() {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) {
                stage = rememberPStageState(page)
                PStage(page, depth = { if (it == "home") 0 else 1 }, back = "home".takeIf { page != "home" },
                    onBack = ({ page = "home" }).takeIf { page != "home" }, state = stage,
                    source = { if (it == "chat") "row:chat" else null }) { shown ->
                    if (shown == "home") Box(Modifier.fillMaxSize()) {
                        Box(Modifier.padding(start = 24.dp, end = 24.dp, top = rowTop).fillMaxWidth().height(64.dp)
                            .then(if (marked) Modifier.pocketMorphSource("row:chat") else Modifier).testTag("row")) { PText("一行") }
                    } else Box(Modifier.fillMaxSize()) { PText("会话页", Modifier.testTag("chat-title")) }
                }
            }
        } } }
        compose.waitForIdle()
    }
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

    @Test fun aPageOpensOutOfTheRowThatLedToItAndFoldsBackIntoIt() {
        content()
        val row = bounds("row")
        val width = bounds("stage-page").width
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "chat" }
        advance(48)
        compose.runOnIdle { assertNotNull("The page beneath holds the row, so the page grows from it", stage.morph) }
        val early = bounds("stage-page")
        assertTrue("It starts at the row, not at the trailing edge: $early from $row;", early.left > row.left * .6f && early.left <= row.left)
        assertTrue(early.top > row.top * .6f && early.top <= row.top)
        assertTrue("and at about the row's width", early.width < row.width + (width - row.width) * .4f)
        assertEquals("The page beneath stays where it is", 0f, bounds("stage-page-leaving").left, width * .03f)
        compose.onNodeWithTag("chat-title").assertExists()
        capture("morph-open-048")
        var last = early
        repeat(8) {
            advance(48)
            val now = bounds("stage-page")
            assertTrue("Every edge only moves outward", now.left <= last.left + .5f && now.top <= last.top + .5f && now.width >= last.width - .5f)
            last = now
        }
        capture("morph-open-416")
        advance(1200)
        val settled = bounds("stage-page")
        assertEquals(0f, settled.left, .5f); assertEquals(0f, settled.top, .5f); assertEquals(width, settled.width, .5f)
        compose.onNodeWithTag("stage-page-leaving").assertDoesNotExist()
        compose.runOnIdle { assertNull(stage.morph) }

        compose.runOnIdle { page = "home" }
        advance(400)
        compose.runOnIdle { assertEquals(PStageMove.Pop, stage.move); assertNotNull("It goes back the way it came", stage.morph) }
        val folding = bounds("stage-page-leaving")
        assertTrue("Nearly back at the row: $folding", abs(folding.left - row.left) < row.left * .15f + 2f && abs(folding.top - row.top) < row.top * .15f + 2f)
        compose.onNodeWithTag("row").assertExists()
        capture("morph-close-400")
        advance(1500)
        compose.onNodeWithTag("stage-page-leaving").assertDoesNotExist()
        compose.runOnIdle { assertNull(stage.morph) }
        assertEquals(0f, bounds("stage-page").left, .5f)
    }

    @Test fun theRowIsCarriedInThePagesFrameAndIsNeverSeenTwice() {
        content()
        compose.mainClock.autoAdvance = false
        val lifted = { stage.sources.find("row:chat")?.first?.lifted }
        assertEquals(false, lifted())
        compose.runOnIdle { page = "chat" }
        advance(96)
        assertEquals("On the way out the row is in the page's frame, not in the list as well", true, lifted())
        assertNotNull("Its picture is what the frame shows first", stage.morph?.picture)
        advance(1200)
        compose.runOnIdle { page = "home" }
        advance(96)
        assertEquals("On the way back the row stays out of the list until the page has become it", true, lifted())
        advance(1200)
        assertEquals("Home again, the row is drawn where it stands", false, lifted())
        // The frame holds the row's picture at the start and the whole page at the end, and never neither.
        assertEquals(1f, pocketMorphPicture(0f), 0f); assertEquals(0f, pocketMorphPage(0f), 0f)
        assertEquals(0f, pocketMorphPicture(1f), 0f); assertEquals(1f, pocketMorphPage(1f), 0f)
        assertEquals("A page held by a back gesture is still all there", 1f, pocketMorphPage(.7f), 0f)
        for (step in 0..20) assertTrue(pocketMorphPicture(step / 20f) + pocketMorphPage(step / 20f) > .35f)
    }

    @Test fun itFoldsBackToWhereTheRowIsNowNotWhereItWas() {
        content()
        compose.runOnIdle { page = "chat" }
        compose.waitForIdle()
        compose.runOnIdle { rowTop = 520.dp }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "home" }
        advance(400)
        val row = bounds("row")
        val folding = bounds("stage-page-leaving")
        assertTrue("The row moved while the page was open, and the page follows it: $folding to $row", abs(folding.top - row.top) < row.top * .15f + 2f)
        advance(1500)
        compose.onNodeWithTag("stage-page-leaving").assertDoesNotExist()
    }

    @Test fun withNothingToGrowFromThePageSlidesAsBefore() {
        marked = false
        content()
        val width = bounds("stage-page").width
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "chat" }
        advance(96)
        compose.runOnIdle { assertNull(stage.morph) }
        val arriving = bounds("stage-page")
        assertTrue("It comes in from the trailing edge", arriving.left > width * .2f)
        assertEquals(0f, arriving.top, .5f)
        advance(1500)
        compose.runOnIdle { page = "home" }
        advance(96)
        compose.runOnIdle { assertNull("A page that slid in slides out", stage.morph) }
        assertEquals(0f, bounds("stage-page-leaving").top, .5f)
        advance(1500)
    }

    @Test fun aBackGestureFoldsThePageTowardItsRowAndLettingGoRestoresIt() {
        content()
        val row = bounds("row")
        compose.runOnIdle { page = "chat" }
        compose.waitForIdle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 400f, .5f, BackEventCompat.EDGE_LEFT)) }
        compose.waitForIdle()
        val held = bounds("stage-page")
        assertTrue("The page draws in toward the row from every side: $held", held.left > 0f && held.left < row.left && held.top > 0f && held.top < row.top)
        compose.onNodeWithTag("chat-title").assertExists()
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(0f, bounds("stage-page").left, .5f); assertEquals(0f, bounds("stage-page").top, .5f)
        compose.runOnIdle { assertNull(stage.morph); assertEquals("chat", stage.target) }
    }

    @Test fun withMotionOffNothingGrows() {
        preferences = PocketGlassPreferences(false, false, false)
        content()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "chat" }
        advance(64)
        compose.onNodeWithTag("stage-page-leaving").assertDoesNotExist()
        assertEquals(0f, bounds("stage-page").left, .5f)
        compose.runOnIdle { assertNull(stage.morph) }
    }

    @Test fun aPageThatIsComeBackToShowsItsContentAsItWasWithoutStaggeringItInAgain() {
        val opened = mutableListOf<Boolean>()
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) {
                PStage(page, depth = { if (it == "home") 0 else 1 }) { shown ->
                    if (shown == "home") { val entrance = rememberPocketEntrance(); remember { opened.add(entrance.open) } }
                    PText(shown)
                }
            }
        } } }
        compose.waitForIdle()
        compose.runOnIdle { page = "chat" }
        compose.waitForIdle()
        compose.runOnIdle { page = "home" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Entered, its rows arrive in turn; come back to, they are simply there", listOf(true, false), opened) }
    }

    @Test fun aLateFrameMovesTheTransitionOnByOneStepNotByTheTimeItTook() {
        assertEquals(16_000_000L, steadyStep(16_000_000L))
        assertEquals("A third of a second of stall counts as two frames", 34_000_000L, steadyStep(330_000_000L))
        assertEquals(0L, steadyStep(-5L))
        val real = longArrayOf(1_000_000_000L, 1_016_000_000L, 1_346_000_000L, 1_362_000_000L)
        val seen = mutableListOf<Long>()
        var frame = 0
        val parent = object : androidx.compose.runtime.MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(real[frame++])
        }
        val steady = SteadyFrameClock(parent)
        kotlinx.coroutines.runBlocking { repeat(real.size) { steady.withFrameNanos { seen += it } } }
        assertEquals(listOf(1_000_000_000L, 1_016_000_000L, 1_050_000_000L, 1_066_000_000L), seen)
    }

    @Test fun theFrameRunsStraightFromTheSourceToTheWholeStage() {
        val source = Rect(72f, 900f, 1008f, 1092f)
        assertEquals(source, pocketMorphFrame(source, 1080f, 2400f, 0f))
        assertEquals(Rect(0f, 0f, 1080f, 2400f), pocketMorphFrame(source, 1080f, 2400f, 1f))
        assertEquals(pocketMorphFrame(source, 1080f, 2400f, 1f), pocketMorphFrame(source, 1080f, 2400f, 1.4f))
        var last = source
        for (step in 1..50) {
            val frame = pocketMorphFrame(source, 1080f, 2400f, step / 50f)
            assertTrue(frame.left <= last.left && frame.top <= last.top && frame.right >= last.right && frame.bottom >= last.bottom)
            assertTrue(frame.left >= 0f && frame.top >= 0f && frame.right <= 1080f && frame.bottom <= 2400f)
            last = frame
        }
        val shape = PMorphShape(400f, 24f)
        val outline = shape.createOutline(androidx.compose.ui.geometry.Size(1080f, 2400f), androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.unit.Density(3f))
        assertEquals("Only the height the frame has shows", 400f, outline.bounds.height, 0f)
        assertEquals(1080f, outline.bounds.width, 0f)
    }
}
