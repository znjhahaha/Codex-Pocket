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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
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
class V054MinimapUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var list: LazyListState
    private lateinit var state: ConversationMinimapState
    private lateinit var map: ConversationMinimapMap
    private lateinit var projection: TranscriptProjectionMemory
    private var projected = emptyList<TranscriptRow>()
    private var timeline by mutableStateOf(fixture())
    private var expansions by mutableStateOf(emptySet<String>())
    private var scope by mutableStateOf("chat-a")
    private var epoch by mutableIntStateOf(0)
    private var autoExpand = true
    private var density = 1f
    private val requestedOwners = mutableListOf<String>()

    private fun fixture(): NativeTimeline {
        val items = (0 until 8).flatMap { n -> listOf(
            TimelineItem("t$n", "initial", "userMessage", "Initial $n", complete = true),
            TimelineItem("t$n", "hidden", "userMessage", "Hidden question $n", complete = true),
            TimelineItem("t$n", "comment", "agentMessage", "Process $n", raw = buildJsonObject { put("phase", "commentary") }, complete = true),
            TimelineItem("t$n", "final", "agentMessage", "Final $n", raw = buildJsonObject { put("phase", "final_answer") }, complete = true)) }
        return NativeTimeline(items = items.associateTo(linkedMapOf()) { it.key to it },
            turns = (0 until 8).associate { "t$it" to NativeTurnInfo("t$it", status = "completed") })
    }
    private fun hidden(n: Int) = TimelineItem("t$n", "hidden", "userMessage").key
    private fun content() {
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") {
                CompositionLocalProvider(LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false)) {
                    density = LocalDensity.current.density
                    list = rememberLazyListState()
                    state = rememberConversationMinimapState(list, scope)
                    projection = remember(scope) { TranscriptProjectionMemory() }
                    val rows = rememberTranscriptRows(scope, timeline, expansions, retained = projection)
                    projected = rows
                    val keys = listOf("history") + rows.map { it.key } + "end"
                    map = remember(rows, keys) { ConversationMinimapMap(rows, keys) }
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        LazyColumn(Modifier.fillMaxSize().padding(start = state.reservedWidth).testTag("v054-transcript"), state = list) {
                            item(key = "history") { Spacer(Modifier.height(24.dp)) }
                            items(rows, key = { it.key }) { row ->
                                Box(Modifier.fillMaxWidth().height(72.dp).testTag("v054-row:${row.key}")) {
                                    Text(row.processLabel ?: row.items.first().text)
                                }
                            }
                            item(key = "end") { Spacer(Modifier.height(1.dp)) }
                        }
                        ConversationMinimap(rows, keys, list, Modifier.align(Alignment.CenterStart).width(state.reservedWidth),
                            backdrop = null, state = state, scope = scope, navigationEpoch = epoch, onExpandProcess = { owner ->
                                requestedOwners += owner
                                if (autoExpand) expansions = expansions + owner
                            })
                    }
                }
            }
        } }
        compose.waitUntil(3_000) { projected.isNotEmpty() }
        compose.waitForIdle()
    }
    private fun rail() = compose.onNodeWithTag("conversation-minimap")
    private fun open() { rail().performTouchInput { click(center) } }
    private fun y(key: String): Float {
        var result = 0f
        val height = rail().fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle {
            val mark = minimapMarks(map, null, 48f * density, height, density, true).first { it.key == key }
            result = mark.top + mark.height / 2f
        }
        return result
    }

    @Test fun changingThreadScopeCancelsOutstandingHiddenExpansionLocate() {
        autoExpand = false
        content()
        commit(hidden(6))
        compose.waitUntil(3_000) { requestedOwners.isNotEmpty() }
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { scope = "chat-b" } }
        // UI idleness does not include the Default-dispatcher projection. Wait
        // for the new native scope before publishing its delayed expansion.
        compose.waitUntil(3_000) { projection.scope == "chat-b" && projected.isNotEmpty() }
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { expansions = setOf("process:t6") } }
        compose.waitUntil(3_000) { map.listIndex(hidden(6)) != null }
        compose.runOnIdle { assertFalse(state.isInteracting); assertFalse(visible(hidden(6))) }
    }
    private fun commit(key: String) {
        val fraction = map.tick(map.slot(key)!!).fraction
        rail().performSemanticsAction(SemanticsActions.SetProgress) { it(fraction) }
    }
    private fun visible(key: String) = list.layoutInfo.visibleItemsInfo.any { it.key == key }

    @Test fun openingMinimapLeavesEveryMainProcessFoldedAndThumbHasRequiredSize() {
        content()
        open()
        compose.runOnIdle {
            assertTrue(requestedOwners.isEmpty())
            assertEquals(8, projected.count { it.kind == TranscriptKind.Process })
            assertTrue((0 until 8).all { map.listIndex(hidden(it)) == null })
        }
        val thumb = compose.onNodeWithTag("conversation-minimap-thumb", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(30f * density, thumb.width, 1f)
        assertTrue(thumb.height >= 44f * density - 1f)
    }

    @Test fun hiddenTapExpandsOnlyItsOwnerThenLocatesItsExactNativeRowKey() {
        content()
        open()
        val key = hidden(6)
        val y = y(key)
        rail().performTouchInput { click(Offset(centerX, y)) }
        compose.waitUntil(3_000) { visible(key) && !state.isInteracting }
        compose.runOnIdle {
            assertEquals(listOf("process:t6"), requestedOwners)
            assertEquals(setOf("process:t6"), expansions)
            assertEquals(1, map.entries.count { it.key == key })
            assertFalse(state.isInteracting)
        }
    }

    @Test fun hiddenLongPressPreviewsUntilReleaseAndThenExpandsAndLocates() {
        content()
        open()
        val key = hidden(6)
        val y = y(key)
        rail().performTouchInput { down(Offset(centerX, y)) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertExists()
        compose.runOnIdle { assertTrue(requestedOwners.isEmpty()) }
        rail().performTouchInput { up() }
        compose.waitUntil(3_000) { visible(key) && !state.isInteracting }
        compose.runOnIdle { assertEquals(listOf("process:t6"), requestedOwners) }
    }

    @Test fun dragOnlyScrollsAndPreviewsOwnersUntilItCommitsOneFinalHiddenQuestion() {
        content()
        open()
        val start = y(hidden(4))
        val end = y(hidden(6))
        rail().performTouchInput { down(Offset(centerX, start)); moveTo(Offset(centerX, end), delayMillis = 100) }
        compose.waitUntil(3_000) { visible("process:t6") }
        compose.runOnIdle { assertTrue(expansions.isEmpty()); assertTrue(requestedOwners.isEmpty()) }
        compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertExists()
        rail().performTouchInput { up() }
        compose.waitUntil(3_000) { visible(hidden(6)) && !state.isInteracting }
        compose.runOnIdle { assertEquals(listOf("process:t6"), requestedOwners) }
    }

    @Test fun commitWaitsForAsynchronousProjectionBeforeLocating() {
        autoExpand = false
        content()
        val key = hidden(6)
        commit(key)
        compose.waitUntil(3_000) { requestedOwners.isNotEmpty() }
        compose.runOnIdle { assertTrue(state.seeking); assertFalse(visible(key)) }
        compose.runOnIdle { expansions = expansions + requestedOwners.single(); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        compose.waitUntil(3_000) { visible(key) && !state.isInteracting }
        compose.runOnIdle { assertFalse(state.seeking) }
    }

    @Test fun paginationEpochCancelsPendingLocateInsteadOfApplyingItsOldIntent() {
        autoExpand = false
        content()
        commit(hidden(6))
        compose.waitUntil(3_000) { requestedOwners.isNotEmpty() }
        compose.runOnIdle { epoch++ }
        compose.waitForIdle()
        compose.runOnIdle { expansions = expansions + "process:t6" }
        compose.waitUntil(3_000) { map.listIndex(hidden(6)) != null }
        compose.runOnIdle { assertFalse(state.seeking); assertFalse(visible(hidden(6))) }
    }

    @Test fun threadScopeAndNativeKeyLossCancelPendingLocate() {
        autoExpand = false
        content()
        val key = hidden(6)
        commit(key)
        compose.waitUntil(3_000) { requestedOwners.isNotEmpty() }
        compose.runOnIdle { timeline = timeline.copy(items = timeline.items.filterKeys { it != key }) }
        compose.waitUntil(3_000) { map.slot(key) == null }
        compose.runOnIdle { assertFalse(state.seeking); scope = "chat-b" }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(state.isInteracting); assertTrue(expansions.isEmpty()) }
    }
}
