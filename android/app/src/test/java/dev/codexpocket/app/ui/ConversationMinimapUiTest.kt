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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
    private lateinit var minimapState: ConversationMinimapState
    private val focusRequester = FocusRequester()
    private lateinit var focusManager: FocusManager
    private var conversationKey by mutableStateOf("chat-a")
    private var density = 1f
    private var rows by mutableStateOf(fixture())
    private var railHeight by mutableStateOf(360.dp)
    private fun fixture() = (0 until 100).map {
        TranscriptRow("row-$it", if (it % 4 == 0) TranscriptKind.User else TranscriptKind.Answer,
            listOf(TimelineItem("turn", "$it", "agentMessage", "Loaded row $it\nA second line")))
    }
    private fun content(theme: String = "light", motion: Boolean = false, rowHeight: Dp? = 72.dp,
        railCap: Dp? = null, useVisibility: Boolean = false, onSeek: () -> Unit = {}) {
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme(theme) {
                CompositionLocalProvider(LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, motion)) {
                    density = LocalDensity.current.density
                    focusManager = LocalFocusManager.current
                    list = rememberLazyListState()
                    minimapState = rememberConversationMinimapState(list)
                    val shown = rememberConversationMinimapVisibility(conversationKey, rows, list)
                    // Host supplies the gutter and restricts hit testing to the transcript rectangle.
                    Column {
                        Box(Modifier.height(60.dp).fillMaxWidth().testTag("top-chrome"))
                        BoxWithConstraints(Modifier.height(railHeight).fillMaxWidth()) {
                            val visible = !useVisibility || shown
                            val gutter = if (visible) minOf(minimapState.reservedWidth, railCap ?: maxWidth * .18f) else 0.dp
                            val keys = listOf("history") + rows.map { it.key } + listOf("live", "end")
                            LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(start = gutter).testTag("transcript"),
                                contentPadding = PaddingValues(horizontal = 16.dp)) {
                                item(key = "history") { Spacer(Modifier.height(32.dp)) }
                                items(rows, key = { it.key }) { row ->
                                    Box(Modifier.fillMaxWidth().then(rowHeight?.let { Modifier.height(it) } ?: Modifier)
                                        .testTag("transcript-row-${row.key}")) { Text(row.items.first().text) }
                                }
                                item(key = "live") { Spacer(Modifier.height(24.dp)) }
                                item(key = "end") { Spacer(Modifier.height(1.dp)) }
                            }
                            if (visible) ConversationMinimap(rows, keys, list,
                                Modifier.align(Alignment.CenterStart).width(gutter).focusRequester(focusRequester),
                                backdrop = null, onSeek = onSeek, state = minimapState)
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
        val width = compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot.width
        val rowWidth = compose.onNodeWithTag("transcript-row-row-0").fetchSemanticsNode().boundsInRoot.width
        assertEquals(24f * density, rail().fetchSemanticsNode().boundsInRoot.width, 1f)
        thumb.assertDoesNotExist()
        open()
        assertExpanded(true)
        assertEquals(first, list.firstVisibleItemIndex)
        assertEquals(48f * density, rail().fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(30f * density, thumb.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(width - 24f * density, compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(rowWidth - 24f * density, compose.onNodeWithTag("transcript-row-row-0").fetchSemanticsNode().boundsInRoot.width, 1f)
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
        // Release removes any press preview and restarts the one-shot idle effect. Commit
        // that recomposition before advancing virtual time, as in the glass-thumb test.
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(minimapState.held); assertFalse(minimapState.isInteracting) }
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
        assertEquals(30f * density, compose.onNodeWithTag("conversation-minimap-thumb", useUnmergedTree = true)
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
        assertEquals(48f * density, bounds.width, 1f)
    }

    @Test fun accessibilityProgressSeeksWithoutTouchAndEmptyRowsRemoveTheHitZone() {
        var seeks = 0
        content(onSeek = { seeks++ })
        rail().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.4f) }
        compose.runOnIdle { assertTrue(hasVisibleKey("row-40")); assertEquals(1, seeks) }
        compose.runOnIdle { rows = emptyList() }
        rail().assertDoesNotExist()
    }

    @Test fun narrowHostCapConstrainsBothDrawingAndTheHitZone() {
        content(railCap = 20.dp)
        assertEquals(20f * density, rail().fetchSemanticsNode().boundsInRoot.width, 1f)
        val oldWidth = compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot.width
        open()
        val bounds = rail().fetchSemanticsNode().boundsInRoot
        val transcript = compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot
        val thumb = compose.onNodeWithTag("conversation-minimap-thumb", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(20f * density, bounds.width, 1f)
        assertEquals(oldWidth, transcript.width, 1f)
        assertTrue(bounds.right <= transcript.left)
        assertTrue(thumb.left >= bounds.left && thumb.right <= bounds.right)
    }

    @Test fun collapsedScrubPreviewsThenReleaseJumpsWithoutExpanding() {
        var seeks = 0
        content(onSeek = { seeks++ })
        val first = list.firstVisibleItemIndex
        rail().performTouchInput {
            down(Offset(centerX, fraction(.2f, height.toFloat())))
            moveTo(Offset(centerX, fraction(.8f, height.toFloat())), delayMillis = 100)
        }
        assertExpanded(false)
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertExists()
        // The collapsed question spacing now caps at 10dp. This pointer is nearest row 88.
        compose.onNodeWithText("89 / 100 · 已加载", useUnmergedTree = true).assertExists()
        assertEquals(24f * density, rail().fetchSemanticsNode().boundsInRoot.width, 1f)
        rail().performTouchInput { up() }
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertDoesNotExist()
        assertExpanded(false)
        compose.runOnIdle { assertEquals(1, seeks); assertTrue(hasVisibleKey("row-88")) }
    }

    @Test fun collapsedPointerDownPreviewsAndOnlyShortReleaseExpands() {
        content()
        val width = compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot.width
        rail().performTouchInput { down(center) }
        assertExpanded(false)
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertExists()
        assertEquals(width, compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot.width, 1f)
        rail().performTouchInput { up() }
        assertExpanded(true)
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("conversation-minimap-thumb", useUnmergedTree = true).assertExists()
    }

    @Test fun collapsedHoldJumpsOnReleaseWhileCancelledTouchDoesNot() {
        var seeks = 0
        content(onSeek = { seeks++ })
        rail().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        assertExpanded(false)
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertExists()
        rail().performTouchInput { advanceEventTime(700); up() }
        assertExpanded(false)
        compose.runOnIdle { assertEquals(1, seeks); assertTrue(hasVisibleKey("row-48")) }
        rail().performTouchInput { down(center); cancel() }
        assertExpanded(false)
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertFalse(minimapState.isInteracting); assertEquals(1, seeks) }
    }

    @Test fun changingAvailableHeightCancelsOldCoordinatesAndNextDragUsesNewTrack() {
        var seeks = 0
        content(onSeek = { seeks++ })
        rail().performTouchInput { down(center) }
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertExists()
        compose.runOnIdle { railHeight = 180.dp }
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertDoesNotExist()
        rail().performTouchInput { up() }
        assertExpanded(false)
        compose.runOnIdle { assertFalse(minimapState.isInteracting); assertEquals(0, seeks) }
        open()
        rail().performTouchInput {
            // The 44dp accessible thumb covers the first quarter of this short track.
            // Start outside it: this test checks track coordinates, not thumb-grab offset.
            down(Offset(centerX, fraction(.4f, height.toFloat())))
            moveTo(Offset(centerX, fraction(.95f, height.toFloat())), delayMillis = 80)
            up()
        }
        compose.runOnIdle { assertTrue(hasVisibleKey("row-95")) }
    }

    @Test fun longPressShowsAtMostThreePlainLinesAndReleaseDismissesWithoutSeek() {
        var seeks = 0
        content(onSeek = { seeks++ })
        open()
        // The current row's mark is always retained, independent of sampling.
        rail().performTouchInput { down(Offset(centerX, fraction(.005f, height.toFloat()))) }
        compose.mainClock.advanceTimeBy(700)
        val preview = compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true)
        preview.assertExists()
        compose.onNodeWithText("提问：Loaded row 0 A second line", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("邻近：Loaded row 1 A second line", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("1 / 100 · 已加载", useUnmergedTree = true).assertExists()
        compose.runOnIdle { assertTrue(minimapState.held); assertTrue(minimapState.isInteracting) }
        compose.mainClock.advanceTimeBy(3000)
        assertExpanded(true)
        rail().performTouchInput { up() }
        preview.assertDoesNotExist()
        compose.runOnIdle { assertFalse(minimapState.isInteracting); assertEquals(0, seeks) }
    }

    @Test fun focusKeepsTheRailOpenUntilItLeaves() {
        content()
        open()
        compose.runOnIdle { focusRequester.requestFocus() }
        compose.mainClock.advanceTimeBy(3000)
        assertExpanded(true)
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2800)
        assertExpanded(false)
    }

    @Test fun visibilityIsStickyAcrossFoldingAndResetsForAnotherConversation() {
        content(useVisibility = true)
        rail().assertExists()
        compose.runOnIdle { rows = rows.take(1) }
        rail().assertExists()
        compose.runOnIdle { conversationKey = "chat-b" }
        rail().assertDoesNotExist()
    }

    @Test fun aCollapsedPreviewTargetDisappearsInsteadOfChangingToAnotherRow() {
        content()
        open()
        rail().performTouchInput { down(Offset(centerX, fraction(.005f, height.toFloat()))) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertExists()
        compose.runOnIdle { rows = rows.drop(1) }
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertDoesNotExist()
        rail().performTouchInput { up() }
    }

    @Test fun firstTapReflowsWrappedRowsWhileRetainingTheMidHistoryKeyAndOffset() {
        rows = rows.map { row -> row.copy(items = listOf(row.items.first().copy(text = "Loaded ${row.key} " +
            "Words wrap as the host reserves the minimap width. ".repeat(60)))) }
        var seeks = 0
        content(rowHeight = null, onSeek = { seeks++ })
        rail().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.455f) }
        rail().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val key = list.layoutInfo.visibleItemsInfo.first().key
        val offset = list.firstVisibleItemScrollOffset
        val width = compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot.width
        val beforeSeeks = seeks
        open()
        compose.runOnIdle {
            assertEquals(key, list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
            assertEquals(beforeSeeks, seeks)
        }
        assertEquals(width - 24f * density, compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun aNewSeekToTheSamePositionStillWorksAfterScrollingTheTranscript() {
        var seeks = 0
        content(onSeek = { seeks++ })
        rail().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.4f) }
        val first = list.firstVisibleItemIndex
        compose.onNodeWithTag("transcript").performTouchInput { swipeUp(durationMillis = 500) }
        compose.runOnIdle { assertTrue(list.firstVisibleItemIndex > first) }
        rail().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.4f) }
        compose.runOnIdle { assertTrue(hasVisibleKey("row-40")); assertEquals(2, seeks) }
    }
}
