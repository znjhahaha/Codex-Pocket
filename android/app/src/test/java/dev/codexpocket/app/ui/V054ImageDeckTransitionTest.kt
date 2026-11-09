package dev.codexpocket.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.MentionedFile
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class V054ImageDeckTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun twoImagesLightReversalDuringDecodeKeepsFrameAndAnchor() = exercise(2, "light")
    @Test fun twoImagesDarkReversalDuringDecodeKeepsFrameAndAnchor() = exercise(2, "dark")
    @Test fun fourImagesLightReversalDuringDecodeKeepsFrameAndAnchor() = exercise(4, "light")
    @Test fun fourImagesDarkReversalDuringDecodeKeepsFrameAndAnchor() = exercise(4, "dark")

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun assertSameBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1f); assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.width, actual.width, 1f); assertEquals(expected.height, actual.height, 1f)
    }
    private fun exercise(count: Int, theme: String) {
        val gate = CompletableDeferred<Unit>()
        val loaded = java.util.concurrent.atomic.AtomicInteger()
        val files = (0 until count).map { index ->
            val file = File(compose.activity.cacheDir, "deck-${UUID.randomUUID()}.png")
            // Extreme intrinsic aspect ratios reproduce the asynchronous sizing pressure cheaply.
            val bitmap = Bitmap.createBitmap(if (index % 2 == 0) 2048 else 8, if (index % 2 == 0) 8 else 2048, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (theme == "dark") 0xff228866.toInt() else 0xff66aa44.toInt())
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
            file
        }
        val mentions = files.mapIndexed { index, file -> MentionedFile("test-image-$index.png", file.path, true) }
        try {
            compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
                CompositionLocalProvider(LocalDesktopImages provides DesktopImageSource { path -> gate.await(); loaded.incrementAndGet(); files.firstOrNull { it.path == path } }) {
                    Surface { Column(Modifier.width(340.dp)) {
                        Text("Pauli stays readable", Modifier.testTag("anchor-before"))
                        ImageGrid(mentions, alignEnd = theme == "dark")
                        Text("Next message", Modifier.testTag("anchor-after"))
                    } }
                }
            } } }
            compose.waitForIdle()
            val frame = bounds("image-transition-frame")
            val before = bounds("anchor-before"); val after = bounds("anchor-after")
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("image-deck-toggle").performClick()
            repeat(3) {
                compose.mainClock.advanceTimeBy(16)
                assertSameBounds(frame, bounds("image-transition-frame")); assertSameBounds(before, bounds("anchor-before")); assertSameBounds(after, bounds("anchor-after"))
            }
            // Reverse before outgoing content disappears, then reverse after the incoming tree mounts.
            compose.onNodeWithTag("image-deck-toggle").performClick()
            compose.mainClock.advanceTimeBy(240)
            compose.onNodeWithTag("image-deck").assertExists()
            compose.onNodeWithTag("image-deck-toggle").performClick()
            compose.mainClock.advanceTimeBy(160)
            compose.onNodeWithTag("image-deck").assertDoesNotExist()
            compose.onNodeWithTag("image-deck-toggle").performClick()
            compose.mainClock.advanceTimeBy(240)
            compose.onNodeWithTag("image-deck").assertExists()
            assertSameBounds(frame, bounds("image-transition-frame")); assertSameBounds(after, bounds("anchor-after"))
            gate.complete(Unit)
            compose.mainClock.autoAdvance = true
            compose.waitUntil(10_000) { loaded.get() > 0 }
            compose.onNodeWithTag("image-deck-toggle").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("image-deck").assertDoesNotExist()
            assertEquals(count, compose.onAllNodes(hasContentDescription("test-image-", substring = true)).fetchSemanticsNodes().size)
            assertSameBounds(frame, bounds("image-transition-frame")); assertSameBounds(before, bounds("anchor-before")); assertSameBounds(after, bounds("anchor-after"))
            compose.onAllNodes(hasContentDescription("test-image-", substring = true)).fetchSemanticsNodes().forEach { cell ->
                assertEquals(cell.boundsInRoot.width, cell.boundsInRoot.height, 1f)
                assertTrue(cell.boundsInRoot.width <= frame.width && cell.boundsInRoot.height <= frame.height)
            }
            compose.onNodeWithContentDescription("test-image-0.png").performClick()
            compose.onNodeWithTag("image-viewer").assertExists()
        } finally {
            gate.complete(Unit); compose.mainClock.autoAdvance = true
            files.forEach { it.delete() }
        }
    }
    @Test fun geometryUsesCountAndConstraintsRatherThanBitmapAspectOrDecodeState() {
        val two = multiImageGeometry(2, 240.dp); val four = multiImageGeometry(4, 240.dp)
        assertEquals(212.dp, two.width); assertEquals(174.dp, two.height)
        assertEquals(212.dp, four.width); assertEquals(212.dp, four.height)
        for (count in listOf(2, 4, 9)) {
            val narrow = multiImageGeometry(count, 144.dp)
            assertTrue(narrow.width <= 144.dp); assertTrue(narrow.height > 0.dp); assertTrue(narrow.cell > 0.dp)
        }
    }
}
