package dev.codexpocket.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class V04TranscriptUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") {
                CompositionLocalProvider(LocalPocketGlass provides false) {
                    Surface { ProvideMarkdownRendering { block() } }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun nativeText(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { nativeText(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun waitMarkdown(text: String) {
        compose.waitUntil(10_000) {
            compose.runOnIdle { nativeText(compose.activity.window.decorView).any { text in it && "**" !in it } }
        }
    }

    @Test fun proposalHeadingsAndListsHaveSeparateVerticalBoundsInsideThePlanBox() {
        val source = "# 第一章：权限闭环\n\n解释一段内容。\n\n## 第二章：对话阅读\n\n- 保留草稿\n- 保留原生身份\n\n## 第三章：验收\n\n最后一段说明。"
        val proposal = TimelineItem("turn", "proposal-flow", "plan", source, complete = true)
        content { Column(Modifier.verticalScroll(rememberScrollState())) {
            NativeActivityCard(proposal, "flow-plan", true, {}, {})
        } }
        waitMarkdown("最后一段说明。")
        fun views(view: View): List<TextView> = when(view) {
            is TextView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else -> emptyList()
        }
        compose.runOnIdle {
            val blocks = views(compose.activity.window.decorView).filter { view ->
                listOf("第一章", "第二章", "第三章").any { view.text.startsWith(it) }
            }
            assertEquals(3, blocks.size)
            blocks.zipWithNext().forEach { (before, after) ->
                val a = IntArray(2); val b = IntArray(2)
                before.getLocationInWindow(a); after.getLocationInWindow(b)
                assertTrue("Markdown blocks overlap: ${before.text.take(8)}", a[1] + before.height <= b[1])
            }
        }
    }

    @Test fun commentaryUsesMarkdownAndReasoningIsReadOnlyNativeProse() {
        val prose = TimelineItem("t", "comment", "agentMessage", "Checking **sources** before continuing.", raw = buildJsonObject { put("phase", "commentary") })
        val reasoning = TimelineItem("t", "reasoning", "reasoning", "**Compare options** then inspect the result.", liveStarted = true)
        content { Column {
            TranscriptRowView(TranscriptRow(prose.key, TranscriptKind.Commentary, listOf(prose)), "test", false, {}, {})
            TranscriptRowView(TranscriptRow("activity:${reasoning.key}", TranscriptKind.Activity, listOf(reasoning)), "test", false, {}, {})
        } }
        waitMarkdown("Checking sources before continuing.")
        compose.onNodeWithText(compose.activity.getString(R.string.v02_commentary)).assertDoesNotExist()
        compose.onNodeWithTag("activity-toggle:${reasoning.key}").assertDoesNotExist()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Compare options then inspect the result.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Compare options then inspect the result.").assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText(compose.activity.getString(R.string.v04_trace_thought)).assertDoesNotExist()
        compose.onNodeWithText("**Compare options**", substring = true).assertDoesNotExist()
    }

    @Test fun readFailureKeepsCachedContentAndRetryTransitionsLocallyToRefreshing() {
        var state by mutableStateOf(ConversationLoadState.Failed)
        var retried = false
        content { Column {
            ConversationReadStatus(conversationReadPresentation(state, true), "Read timed out") {
                retried = true
                state = ConversationLoadState.Loading
            }
            Text("Cached conversation stays readable")
        } }
        compose.onNodeWithText("Cached conversation stays readable").assertIsDisplayed()
        compose.onNodeWithText("Read timed out", substring = true).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.v04_transcript_retry)).performClick()
        compose.runOnIdle { assertTrue(retried) }
        compose.onNodeWithTag("conversation-skeleton").assertDoesNotExist()
        compose.onNodeWithText("Cached conversation stays readable").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.v04_transcript_refreshing)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.offline_history)).assertDoesNotExist()
    }

    @Test fun emptyLoadingShowsSkeletonAndReadFailureOffersRetryWithoutEmptyPrompt() {
        var state by mutableStateOf(ConversationLoadState.Loading)
        content { ConversationReadStatus(conversationReadPresentation(state, false), null) { state = ConversationLoadState.Loading } }
        compose.onNodeWithTag("conversation-skeleton").assertExists()
        compose.runOnIdle { state = ConversationLoadState.Failed }
        compose.onNodeWithTag("conversation-skeleton").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.v04_transcript_retry)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.what_working)).assertDoesNotExist()
    }

    @Test fun nativeKeyAndPixelAnchorSurviveOutputDeltasCompletionAndHistoryPrepend() {
        val history = (0..24).map { index -> TimelineItem("turn-$index", "command", "commandExecution", "echo", liveStarted = true) }
        var timeline by mutableStateOf(NativeTimeline(history.associateBy { it.key }))
        lateinit var list: LazyListState
        content {
            list = rememberLazyListState()
            val rows = ConversationPresentation.rows(timeline)
            LazyColumn(Modifier.height(240.dp).testTag("transcript-fixture"), state = list) {
                items(rows, key = { it.key }) { row -> TranscriptRowView(row, "test", false, {}, {}) }
            }
        }
        compose.onNodeWithTag("transcript-fixture").performScrollToIndex(10)
        val before = compose.runOnIdle { list.layoutInfo.visibleItemsInfo.first().key to list.firstVisibleItemScrollOffset }
        val older = TimelineItem("older-turn", "command", "commandExecution", complete = true)
        compose.runOnIdle {
            val streamed = history.map { if (it == history.last()) it.copy(text = "echo\n\nNew output") else it }
            timeline = NativeTimeline((listOf(older) + streamed).associateBy { it.key }, completedTurns = setOf(history.last().turnId))
        }
        compose.waitForIdle()
        val after = compose.runOnIdle { list.layoutInfo.visibleItemsInfo.first().key to list.firstVisibleItemScrollOffset }
        assertEquals(before, after)
    }

    @Test fun draftItemExpansionSurvivesAFailedToolSplittingItsGroup() {
        val first = TimelineItem("t", "first", "commandExecution", liveStarted = true)
        val tool = TimelineItem("t", "second", "commandExecution", "echo\n\nvisible result", raw = buildJsonObject { put("command", "echo") }, liveStarted = true)
        var current by mutableStateOf(tool)
        var expansions by mutableStateOf(emptySet<String>())
        content { Column {
            ConversationPresentation.rows(NativeTimeline(listOf(first, current).associateBy { it.key })).forEach { row -> key(row.key) {
                TranscriptRowView(row, "test", row.key in expansions, {}, {}, activityExpansions = expansions,
                    onActivityExpansion = { key -> expansions = if (key in expansions) expansions - key else expansions + key })
            } }
        } }
        compose.onNodeWithTag("activity-toggle:${tool.key}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("visible result").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { current = tool.copy(status = "failed", complete = true) }
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("visible result").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("activity-toggle:${tool.key}").assertExists()
    }
}
