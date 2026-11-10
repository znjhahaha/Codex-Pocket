package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class V051LiveSummaryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            Surface { ProvideMarkdownRendering { block() } }
        } } }
        compose.waitForIdle()
    }

    @Test fun activeTurnWithoutFirstContentShowsThinkingButNoInventedTimer() {
        content { Column {
            LiveTurnStatus("a", ConversationPresentation.LiveStatus.Thinking, timing = NativeTurnInfo("a", status = "inProgress", startedAtMs = 1000))
            LiveTurnStatus("b", ConversationPresentation.LiveStatus.Working)
        } }
        compose.onNodeWithTag("live-turn-status").assertDoesNotExist()
        compose.onNodeWithTag("task-elapsed-label").assertDoesNotExist()
        compose.onNodeWithTag("live-thinking-placeholder").assertTextEquals("正在思考")
        compose.onNodeWithText("正在处理").assertDoesNotExist()
    }

    @Test fun nativeSummaryIsQuietProseAndClickRevealsSeparateCleanParagraphs() {
        var summary by mutableStateOf(TimelineItem("t", "r", "reasoning", raw = buildJsonObject {
            put("summary", JsonArray(listOf(JsonPrimitive("**Assigning new data/UI work**"), JsonPrimitive("## Checking NativeSettings length"))))
        }))
        content { LiveTurnStatus("t", ConversationPresentation.LiveStatus.Thinking, reasoning = summary, timing = NativeTurnInfo("t", startedAtMs = 1000)) }
        compose.onNodeWithText("Checking NativeSettings length").assertExists()
        compose.onNodeWithText("Assigning new data/UI work").assertDoesNotExist()
        compose.onNodeWithTag("task-elapsed-label").assertDoesNotExist()
        compose.onNodeWithTag("live-reasoning-toggle").performClick()
        compose.onNodeWithTag("native-summary-paragraph:0", useUnmergedTree = true).assertTextEquals("Assigning new data/UI work")
        compose.onNodeWithTag("native-summary-paragraph:1", useUnmergedTree = true).assertTextEquals("Checking NativeSettings length")
        compose.onNodeWithText("**", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { summary = summary.copy(raw = buildJsonObject {
            put("summary", JsonArray(listOf(JsonPrimitive("First paragraph stays separate."), JsonPrimitive("Second paragraph streams independently."))))
        }) }
        compose.onNodeWithTag("live-reasoning-summary", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("native-summary-paragraph:0", useUnmergedTree = true).assertTextEquals("First paragraph stays separate.")
        compose.onNodeWithTag("native-summary-paragraph:1", useUnmergedTree = true).assertTextEquals("Second paragraph streams independently.")
    }

    @Test fun thinkingPlaceholderEndsOnDisconnectAndNeverReplacesActualSummary() {
        var online by mutableStateOf(true)
        var summary by mutableStateOf<TimelineItem?>(null)
        content {LiveTurnStatus("t",ConversationPresentation.LiveStatus.Thinking,reasoning=summary,online=online)}
        compose.onNodeWithTag("live-thinking-placeholder").assertTextEquals("正在思考")
        compose.runOnIdle {online=false}
        compose.onNodeWithTag("live-thinking-placeholder").assertDoesNotExist()
        compose.runOnIdle {online=true;summary=TimelineItem("t","r","reasoning","Native summary")}
        compose.onNodeWithTag("live-thinking-placeholder").assertDoesNotExist()
        compose.onNodeWithText("Native summary").assertExists()
    }

    @Test fun headerClockUsesTrustworthyDurationAndHasNoTaskPrefixInVisibleText() {
        content { TaskElapsedLabel(NativeTurnInfo("t", status = "completed", startedAtMs = 1000, completedAtMs = 66_000)) }
        compose.onNodeWithTag("task-elapsed-label").assertTextEquals("1:05")
        compose.onNodeWithContentDescription("任务用时 1:05").assertExists()
        compose.onNodeWithText("任务 1:05").assertDoesNotExist()
    }

    @Test fun terminalOrMismatchedTurnMetadataCannotShowThinking() {
        content { Column {
            LiveTurnStatus("t", ConversationPresentation.LiveStatus.Thinking, timing = NativeTurnInfo("t", status = "completed"))
            LiveTurnStatus("t", ConversationPresentation.LiveStatus.Thinking, timing = NativeTurnInfo("other", status = "inProgress"))
        } }
        compose.onNodeWithTag("live-thinking-placeholder").assertDoesNotExist()
    }

    @Test fun earlierTurnSummaryCannotReplaceCurrentThinking() {
        val old = TimelineItem("old", "r", "reasoning", "Historical summary")
        content { LiveTurnStatus("t", ConversationPresentation.LiveStatus.Thinking, reasoning = old,
            timing = NativeTurnInfo("t", status = "inProgress")) }
        compose.onNodeWithTag("live-thinking-placeholder").assertTextEquals("正在思考")
        compose.onNodeWithText("Historical summary").assertDoesNotExist()
    }

    @Test fun incompleteOrInvalidTimesNeverInventElapsedAndCompletionStopsClock() {
        assertNull(taskElapsedSeconds(NativeTurnInfo("t", status = "inProgress"), 20_000, 9_000_000_000))
        assertNull(taskElapsedSeconds(NativeTurnInfo("t", status = "inProgress", startedAtMs = 30_000), 20_000, 9_000_000_000))
        assertEquals(12L, taskElapsedSeconds(NativeTurnInfo("t", status = "inProgress", startedAtMs = 8000), 20_000, 9_000_000_000))
        assertEquals(5L, taskElapsedSeconds(NativeTurnInfo("t", status = "inProgress", observedStartNanos = 4_000_000_000), 20_000, 9_000_000_000))
        val stopped = NativeTurnInfo("t", status = "interrupted", observedStartNanos = 1, observedDurationMs = 4000)
        assertEquals(4L, taskElapsedSeconds(stopped, 20_000, 9_000_000_000))
        assertEquals(4L, taskElapsedSeconds(stopped, 90_000, 50_000_000_000))
        assertEquals("正在思考", liveStatusLabel(ConversationPresentation.LiveStatus.Thinking))
        assertEquals("", liveStatusLabel(ConversationPresentation.LiveStatus.Working))
    }
}
