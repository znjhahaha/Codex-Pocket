package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.MentionedFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class ImageDeckMorphUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    // Release declares MainActivity, and its onCreate already installs content. Route the
    // restoration fixture through that activity while retaining the rule's clock and assertions.
    private val host = object : ComposeContentTestRule by compose {
        override fun setContent(composable: @Composable () -> Unit) {
            compose.activity.runOnUiThread { compose.activity.setContent { composable() } }
        }
    }
    private val reads = ConcurrentHashMap<String, AtomicInteger>()
    private val source = DesktopImageSource { path -> reads.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet(); null }
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))
    private fun files(count: Int) = (0 until count).map { MentionedFile("morph-$it.png", "fixture-morph-$it.png", true) }
    private fun show(count: Int = 4): StateRestorationTester = StateRestorationTester(host).also { tester ->
        val images = files(count)
        tester.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalDesktopImages provides source, LocalPocketGlassPreferences provides preferences) {
                Surface { Column(Modifier.width(340.dp)) {
                    Text("Before", Modifier.testTag("morph-before"))
                    ImageGrid(images, alignEnd = true)
                    Text("After", Modifier.testTag("morph-after"))
                } }
            }
        } }
        compose.waitForIdle()
    }
    private fun node(index: Int) = compose.onNodeWithTag("image-card:$index", useUnmergedTree = true)
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun sameBounds(a: Rect, b: Rect) {
        val message = "clock=${compose.mainClock.currentTime}ms expected=$a actual=$b"
        assertEquals(message, a.left, b.left, 1f); assertEquals(message, a.top, b.top, 1f)
        assertEquals(message, a.width, b.width, 1f); assertEquals(message, a.height, b.height, 1f)
    }

    // Per-frame movement and interrupted reversals are verified by
    // TranscriptScrollingDeviceTest against actual rendered device coordinates.
    @Test fun repeatedExpansionKeepsTheSameCardsAndImageLoadsWithoutMovingTheTranscript() {
        show()
        compose.waitUntil(5_000) { reads.size == 3 }
        val identities = (0..3).map { node(it).fetchSemanticsNode().id }
        val frame = bounds("image-transition-frame"); val after = bounds("morph-after")
        repeat(3) {
            compose.onNodeWithTag("image-deck-toggle").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("image-deck").assertDoesNotExist()
            assertEquals(identities, (0..3).map { node(it).fetchSemanticsNode().id })
            sameBounds(frame, bounds("image-transition-frame")); sameBounds(after, bounds("morph-after"))
            compose.onNodeWithTag("image-deck-toggle").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("image-deck").assertExists()
            assertEquals(identities, (0..3).map { node(it).fetchSemanticsNode().id })
            sameBounds(frame, bounds("image-transition-frame")); sameBounds(after, bounds("morph-after"))
        }
        compose.waitUntil(5_000) { reads.size == 4 }
        assertTrue("Each stable card loads once, including after reversal", reads.values.all { it.get() == 1 })
    }

    @Test fun ninthTileKeepsItsIndexOverflowAndExpandedStateAfterRestore() {
        preferences = preferences.copy(animatorsEnabled = false)
        val restoration = show(12)
        compose.onNodeWithTag("image-deck-toggle").performClick()
        compose.onNodeWithText("+3").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("morph-", substring = true)).assertCountEquals(9)
        compose.onNodeWithContentDescription("morph-8.png").performClick()
        compose.onNodeWithText("9 / 12").assertExists()
        compose.onNodeWithContentDescription("关闭").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("收起").assertExists()
        compose.onNodeWithTag("image-deck").assertDoesNotExist()
        compose.onNodeWithTag("image-deck-toggle").performClick()
        compose.onNodeWithTag("image-deck").assertExists()
    }

    @Test fun reducedMotionSnapsCardsInsideTheSameFrame() {
        preferences = preferences.copy(batterySaver = true)
        show()
        val frame = bounds("image-transition-frame")
        val closed = node(1).fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("image-deck-toggle").performClick()
            compose.mainClock.advanceTimeBy(32)
            val settled = node(1).fetchSemanticsNode().boundsInRoot
            assertNotEquals(closed, settled)
            compose.mainClock.advanceTimeBy(160)
            sameBounds(settled, node(1).fetchSemanticsNode().boundsInRoot)
            sameBounds(frame, bounds("image-transition-frame"))
        } finally { compose.mainClock.autoAdvance = true }
    }
}
