package dev.codexpocket.app.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A back gesture previews a layer's way out; committing, cancelling and a plain Back behave as they always did. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PredictiveBackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) { PocketBackdrop { block() } }
        } } }
        compose.waitForIdle()
    }
    private val dispatcher get() = compose.activity.onBackPressedDispatcher
    private fun surfaces(): List<Rect> = compose.onAllNodesWithTag("pocket-modal").fetchSemanticsNodes().map { it.boundsInRoot }
    private fun gesture(progress: Float, start: Boolean = false) {
        compose.runOnIdle {
            val event = BackEventCompat(0f, 400f, progress, BackEventCompat.EDGE_LEFT)
            if (start) dispatcher.dispatchOnBackStarted(event) else dispatcher.dispatchOnBackProgressed(event)
        }
        compose.waitForIdle()
    }

    @Test fun manifestOptsTheActivityIntoBackCallbacks() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val activity = Regex("<activity android:name=\"[.]MainActivity\"[^>]*>").find(manifest)!!.value
        assertTrue("The opt-in is on the activity, so releases before Android 14 keep the old path", "android:enableOnBackInvokedCallback=\"true\"" in activity)
        assertFalse("enableOnBackInvokedCallback" in Regex("<application[^>]*>").find(manifest)!!.value)
    }

    @Test fun sheetSinksWithTheGestureReturnsOnCancelAndLeavesOnceOnCommit() {
        var open by mutableStateOf(true)
        var dismissals = 0
        content { if (open) PocketSheet("会话操作", { dismissals++; open = false }) { Text("内容") } }
        val rest = surfaces().single()
        gesture(0f, start = true); gesture(.5f)
        val half = surfaces().single()
        assertTrue("The sheet sinks as the gesture proceeds", half.top > rest.top + 20f)
        gesture(1f)
        assertTrue(surfaces().single().top > half.top)
        assertEquals("A preview dismisses nothing", 0, dismissals)
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals("Cancelling returns it to rest", rest, surfaces().single())
        assertEquals(0, dismissals)
        gesture(0f, start = true); gesture(.7f)
        compose.runOnIdle { dispatcher.onBackPressed(); assertEquals("Committing dismisses in the same call", 1, dismissals) }
        compose.waitForIdle()
        assertTrue(surfaces().isEmpty())
        assertEquals(1, dismissals)
    }

    @Test fun plainBackIsUnchangedAndALayerThatStaysReturnsToRest() {
        var dismissals = 0
        var closes by mutableStateOf(false)
        var open by mutableStateOf(true)
        content { if (open) PocketDialog({ dismissals++; if (closes) open = false }, { Text("确认操作") }, confirmButton = { PocketButton("好", {}) }) }
        val rest = surfaces().single()
        // No gesture: the same synchronous dismissal BackHandler gave.
        compose.runOnIdle { dispatcher.onBackPressed(); assertEquals(1, dismissals) }
        compose.waitForIdle()
        assertEquals(rest, surfaces().single())
        gesture(0f, start = true); gesture(.8f)
        val receded = surfaces().single()
        assertTrue("A dialog recedes: ${receded.width} of ${rest.width}", receded.width < rest.width - 4f)
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(2, dismissals)
        assertEquals("Its owner kept it, so it is back at rest", rest, surfaces().single())
        compose.runOnIdle { closes = true }
        gesture(0f, start = true); gesture(.8f)
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(surfaces().isEmpty())
        assertEquals(3, dismissals)
    }

    @Test fun onlyTheTopLayerFollowsAndPagesStartTowardTheTrailingEdge() {
        var page by mutableStateOf(true)
        var sheet by mutableStateOf(true)
        content {
            if (page) PocketPageOverlay("文件", { page = false }) { Text("页面") }
            if (sheet) PocketSheet("会话操作", { sheet = false }) { Text("内容") }
        }
        // A covered layer leaves the semantics tree, so only the sheet is found here.
        val rest = surfaces().single()
        gesture(0f, start = true); gesture(.6f)
        assertTrue(surfaces().single().top > rest.top)
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(sheet); assertTrue(page)
        val pageRest = surfaces().single()
        assertEquals("The covered page did not follow the sheet's gesture", 0f, pageRest.left, .01f)
        assertEquals(360 * 3f, pageRest.width, .01f)
        gesture(0f, start = true); gesture(.6f)
        val sliding = surfaces().single()
        assertTrue("The page moves aside: ${sliding.left}", sliding.left > pageRest.left + 40f)
        assertEquals(pageRest.top, sliding.top, .01f); assertEquals(pageRest.width, sliding.width, .01f)
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(page)
    }

    @Test fun reducedMotionShowsNoPreviewAndStillGoesBack() {
        preferences = PocketGlassPreferences(false, false, false)
        var open by mutableStateOf(true)
        content { if (open) PocketSheet("会话操作", { open = false }) { Text("内容") } }
        val rest = surfaces().single()
        gesture(0f, start = true); gesture(.9f)
        assertEquals(rest, surfaces().single())
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(open)
    }
}
