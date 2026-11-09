package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import dev.codexpocket.app.data.ConversationPresentation.LiveStatus
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The main conversation and read-only child both use these shared components. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class CompactionLifecycleUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { Column { block() } } } }
        compose.waitForIdle()
    }

    @Test fun compactionTakesPrecedenceOverAnEarlierReasoningSummary() {
        var status by mutableStateOf(LiveStatus.Compacting)
        val summary = TimelineItem("turn", "reasoning", "reasoning", "Earlier native summary")
        content { LiveTurnStatus("turn", status, reasoning = summary) }
        compose.onNodeWithText("正在压缩上下文").assertExists()
        compose.onNodeWithTag("compaction-status-icon").assertWidthIsEqualTo(16.dp).assertHeightIsEqualTo(16.dp)
        val liveBounds = compose.onNodeWithTag("live-turn-status").getUnclippedBoundsInRoot()
        assertTrue(liveBounds.bottom - liveBounds.top <= 28.dp)
        compose.onNodeWithText("Earlier native summary").assertDoesNotExist()
        compose.runOnIdle { status = LiveStatus.Thinking }
        compose.onNodeWithText("Earlier native summary").assertExists()
        compose.onNodeWithText("正在压缩上下文").assertDoesNotExist()
    }

    @Test fun sharedTranscriptShowsCompletedFailedInterruptedAndUnknownCompaction() {
        val item = TimelineItem("turn", "compact", "contextCompaction", complete = true)
        var timeline by mutableStateOf(NativeTimeline(items = mapOf(item.key to item)))
        content { ConversationPresentation.rows(timeline).forEach { row ->
            TranscriptRowView(row, "compaction-test", false, {}, {}, timeline = timeline)
        } }
        compose.onNodeWithText("上下文已压缩").assertExists()
        compose.onNodeWithTag("compaction-status-icon").assertWidthIsEqualTo(16.dp).assertHeightIsEqualTo(16.dp)
        val rowBounds = compose.onNodeWithTag("compaction-status:${item.key}").getUnclippedBoundsInRoot()
        assertTrue(rowBounds.bottom - rowBounds.top <= 28.dp)
        compose.runOnIdle { timeline = timeline.copy(items = mapOf(item.key to item.copy(complete = false)),
            turns = mapOf("turn" to NativeTurnInfo("turn", status = "failed")), completedTurns = setOf("turn")) }
        compose.onNodeWithText("上下文压缩失败").assertExists()
        compose.onNodeWithText("上下文已压缩").assertDoesNotExist()
        compose.runOnIdle { timeline = timeline.copy(turns = mapOf("turn" to NativeTurnInfo("turn", status = "interrupted"))) }
        compose.onNodeWithText("上下文压缩已中断").assertExists()
        compose.runOnIdle { timeline = timeline.copy(turns = emptyMap(), completedTurns = emptySet()) }
        compose.onNodeWithText("上下文压缩状态未知").assertExists()
        compose.onNodeWithText("正在压缩上下文").assertDoesNotExist()
    }

    @Test fun foldedSuccessfulTaskKeepsCompletedCompactionVisible() {
        val user = TimelineItem("turn", "user", "userMessage", "hello", complete = true)
        val compact = TimelineItem("turn", "compact", "contextCompaction", complete = true)
        val answer = TimelineItem("turn", "answer", "agentMessage", "done", complete = true)
        val timeline = NativeTimeline(items = listOf(user, compact, answer).associateBy { it.key },
            turns = mapOf("turn" to NativeTurnInfo("turn", status = "completed")), completedTurns = setOf("turn"))
        val process = ConversationPresentation.rows(timeline).single { it.kind == TranscriptKind.Process }
        content { TranscriptRowView(process, "folded-compaction", false, {}, {}, timeline = timeline) }
        compose.onNodeWithText("上下文已压缩").assertExists()
        compose.onNodeWithTag("compaction-status-icon").assertWidthIsEqualTo(16.dp).assertHeightIsEqualTo(16.dp)
    }

    @Test fun sharedRowDoesNotCallAnEndedFailedNativeItemSuccessful() {
        val item = TimelineItem.from("turn", pocketJson.parseToJsonElement("""{"id":"compact","type":"contextCompaction","status":"failed","completedAtMs":200}""").asObject(), complete = true)
        val timeline = NativeTimeline(items = mapOf(item.key to item), completedTurns = setOf("turn"))
        val row = ConversationPresentation.rows(timeline).single()
        content { TranscriptRowView(row, "failed-compaction", false, {}, {}, timeline = timeline) }
        compose.onNodeWithText("上下文压缩失败").assertExists()
        compose.onNodeWithText("上下文已压缩").assertDoesNotExist()
    }
}
