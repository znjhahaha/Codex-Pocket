package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.TimelineItem
import dev.codexpocket.app.data.TranscriptKind
import dev.codexpocket.app.data.TranscriptRow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationMinimapUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var list: LazyListState
    private var density = 1f
    private var rows by mutableStateOf(fixture())
    private fun fixture() = (0 until 100).map {
        TranscriptRow("row-$it", if (it % 4 == 0) TranscriptKind.User else TranscriptKind.Answer,
            listOf(TimelineItem("turn", "$it", "agentMessage", "Loaded row $it\nA second line")))
    }
    private fun content(theme: String = "light", motion: Boolean = false, rowHeight: Dp = 72.dp, onSeek: () -> Unit = {}) {
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme(theme) {
                CompositionLocalProvider(LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, motion)) {
                    density = LocalDensity.current.density
                    list = rememberLazyListState()
                    // Host supplies the gutter and restricts hit testing to the transcript rectangle.
                    Column {
                        Box(Modifier.height(60.dp).fillMaxWidth().testTag("top-chrome"))
                        Box(Modifier.height(360.dp).fillMaxWidth()) {
                            val keys = listOf("history") + rows.map { it.key } + listOf("live", "end")
                            LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(start = 60.dp).testTag("transcript")) {
                                item(key = "history") { Spacer(Modifier.height(32.dp)) }
                                items(rows, key = { it.key }) { row ->
                                    Box(Modifier.fillMaxWidth().height(rowHeight)) { Text(row.items.first().text) }
                                }
                                item(key = "live") { Spacer(Modifier.height(24.dp)) }
                                item(key = "end") { Spacer(Modifier.height(1.dp)) }
                            }
                            ConversationMinimap(rows, keys, list, Modifier.align(Alignment.CenterStart), backdrop = null, onSeek = onSeek)
                        }
                        Box(Modifier.height(80.dp).fillMaxWidth().testTag("keyboard-chrome"))
                    }
                }
            }
        } }
        compose.waitForIdle()
    }
    private fun rail() = compose.onNodeWithTag("conversation-minimap")
    private fun assertExpanded(value: Boolean) = rail().assert(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (value) "已展开" else "已收起"))
    private fun open() { rail().performTouchInput { click(center) } }
    private fun fraction(y: Float, height: Float) = 8f * density + y * (height - 16f * density)
    private fun hasVisibleKey(key: String) = list.layoutInfo.visibleItemsInfo.any { it.key == key }

    @Test fun firstTapOpensWithoutMovingTheTranscriptAndReducedMotionIsImmediate() {
        content()
        val first = list.firstVisibleItemIndex
        val thumb = compose.onNodeWithTag("conversation-minimap-thumb", useUnmergedTree = true)
        assertEquals(20f * density, rail().fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(12f * density, thumb.fetchSemanticsNode().boundsInRoot.width, 1f)
        open()
        assertExpanded(true)
        assertEquals(first, list.firstVisibleItemIndex)
        assertEquals(52f * density, thumb.fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun overviewTapAndDragNavigateTheLoadedRowsInBothDirections() {
        content("dark")
        open()
        rail().performTouchInput { click(Offset(centerX, fraction(.8f, height.toFloat()))) }
        compose.runOnIdle { assertTrue(hasVisibleKey("row-80")) }
        rail().performTouchInput {
            down(Offset(centerX, fraction(.7f, height.toFloat())))
            moveTo(Offset(centerX, fraction(.15f, height.toFloat())), delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertTrue(list.firstVisibleItemIndex < 30) }
    }

    @Test fun prependingDuringADragKeepsTheOriginalGestureRows() {
        content()
        open()
        rail().performTouchInput { down(Offset(centerX, fraction(.5f, height.toFloat()))) }
        compose.runOnIdle { rows = (0 until 20).map { rows[it].copy(key = "older-$it") } + rows }
        rail().performTouchInput {
            moveTo(Offset(centerX, fraction(.65f, height.toFloat())), delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertTrue(hasVisibleKey("row-65")) }
    }

    @Test fun aTapCanSeekInsideOneTallLoadedRow() {
        rows = rows.take(1)
        content(rowHeight = 1200.dp)
        open()
        rail().performTouchInput { click(Offset(centerX, fraction(.7f, height.toFloat()))) }
        compose.runOnIdle {
            assertTrue(hasVisibleKey("row-0"))
            assertEquals(1, list.firstVisibleItemIndex)
            assertTrue(list.firstVisibleItemScrollOffset > 600f * density)
        }
    }

    @Test fun aFoldedAwayTapTargetDoesNotSeekToItsReplacement() {
        content()
        open()
        rail().performTouchInput { down(Offset(centerX, fraction(.5f, height.toFloat()))) }
        compose.runOnIdle { rows = rows.filter { it.key != "row-50" } }
        val first = list.firstVisibleItemIndex
        rail().performTouchInput { up() }
        compose.runOnIdle { assertEquals(first, list.firstVisibleItemIndex) }
    }

    @Test fun idleCollapseWaitsUntilThePointerIsReleased() {
        content()
        open()
        rail().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(3000)
        assertExpanded(true)
        rail().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(2000)
        assertExpanded(true)
        compose.mainClock.advanceTimeBy(800)
        assertExpanded(false)
    }

    @Test fun holdingTheGlassThumbAlsoPreventsCollapse() {
        content()
        open()
        val thumb = compose.onNodeWithTag("conversation-minimap-thumb", useUnmergedTree = true)
        thumb.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(3000)
        assertExpanded(true)
        thumb.performTouchInput { up() }
        // The thumb does not seek/re-layout on release. Commit the restarted idle effect
        // before jumping the clock; otherwise its fresh 2.5s starts after the jump.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2800)
        assertExpanded(false)
    }

    @Test fun aPressNearTheOldDeadlineStartsANewIdlePeriodOnRelease() {
        content()
        open()
        compose.mainClock.advanceTimeBy(2400)
        rail().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        assertExpanded(true)
        rail().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(2000)
        assertExpanded(true)
        compose.mainClock.advanceTimeBy(800)
        assertExpanded(false)
    }

    @Test fun reopeningInterruptsCollapseWithoutAnimatingTheTranscript() {
        content(motion = true)
        compose.mainClock.autoAdvance = false
        open()
        val first = list.firstVisibleItemIndex
        compose.mainClock.advanceTimeBy(2520)
        // A tap during closing only reopens; it cannot inherit an old seek.
        rail().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(300)
        assertExpanded(true)
        assertEquals(52f * density, compose.onNodeWithTag("conversation-minimap-thumb", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(first, list.firstVisibleItemIndex)
        compose.mainClock.autoAdvance = true
    }

    @Test fun hitZoneStaysInTheReservedGutterAndBetweenChrome() {
        content()
        open()
        val bounds = rail().fetchSemanticsNode().boundsInRoot
        val transcript = compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithTag("top-chrome").fetchSemanticsNode().boundsInRoot
        val bottom = compose.onNodeWithTag("keyboard-chrome").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.right <= transcript.left)
        assertTrue(bounds.top >= top.bottom)
        assertTrue(bounds.bottom <= bottom.top)
        assertEquals(52f * density, bounds.width, 1f)
    }

    @Test fun accessibilityProgressSeeksWithoutTouchAndEmptyRowsRemoveTheHitZone() {
        var seeks = 0
        content(onSeek = { seeks++ })
        rail().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.4f) }
        compose.runOnIdle { assertTrue(hasVisibleKey("row-40")); assertEquals(1, seeks) }
        compose.runOnIdle { rows = emptyList() }
        rail().assertDoesNotExist()
    }
}
