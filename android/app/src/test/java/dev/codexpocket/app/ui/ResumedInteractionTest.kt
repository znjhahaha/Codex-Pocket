package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
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
class ResumedInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun readingHistoryHidesTheModelEvenWithDraftAndActiveTurn() {
        var readingHistory by mutableStateOf(false)
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            PocketComposer("existing draft", "Model", "高", true, ComposerAvailability(false, false, true, true),
                {}, {}, {}, {}, {}, {}, {}, true, readingHistory = readingHistory)
        } } }
        compose.onNodeWithTag("composer-model").assertExists()
        compose.runOnIdle { readingHistory = true }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.onNodeWithTag("composer-input").assertDoesNotExist()
        compose.onNodeWithTag("composer-history-preview").assertTextEquals("existing draft")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        compose.runOnIdle { readingHistory = false }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-model").assertExists()
        compose.onNodeWithTag("composer-history-preview").assertDoesNotExist()
        compose.onNodeWithTag("composer-input").assertTextEquals("existing draft")
    }

    @Test fun summaryFoldSurvivesNativeTextUpdatesAndNoSummaryHasNoDisclosure() {
        var summary by mutableStateOf<TimelineItem?>(TimelineItem("turn", "r", "reasoning", "**Reviewing layout**"))
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { ProvideMarkdownRendering {
            LiveTurnStatus("turn", ConversationPresentation.LiveStatus.Thinking, reasoning = summary)
        } } } }
        compose.onNodeWithTag("live-reasoning-summary", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("live-reasoning-toggle").performClick()
        compose.onNodeWithTag("live-reasoning-summary", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("native-summary-paragraph:0", useUnmergedTree = true).assertTextEquals("Reviewing layout")
        compose.runOnIdle { summary = summary?.copy(text = "**Reviewing constraints**") }
        compose.onNodeWithTag("live-reasoning-summary", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("native-summary-paragraph:0", useUnmergedTree = true).assertTextEquals("Reviewing constraints")
        compose.onNodeWithTag("live-reasoning-toggle").performClick()
        compose.onNodeWithTag("live-reasoning-summary", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { summary = summary?.copy(text = "**Reviewing changes**") }
        compose.onNodeWithTag("live-reasoning-summary", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("live-reasoning-toggle").assertTextEquals("Reviewing changes")
        compose.runOnIdle { summary = null }
        compose.onNodeWithTag("live-reasoning-toggle").assertDoesNotExist()
        compose.onNodeWithTag("live-turn-status").assertDoesNotExist()
        compose.onNodeWithTag("live-thinking-placeholder").assertTextEquals("正在思考")
    }

    @Test fun agentDetailsOfferOnlyDirectoryVerifiedChildAndKeepActualIdentity() {
        val item = TimelineItem.from("turn", pocketJson.parseToJsonElement("""{"id":"agents","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","receiverThreadIds":["child-1","child-2"],"agentsStates":{"child-1":{"name":"Reviewer","status":"running"},"child-2":{"name":"Reader","status":"errored","message":"Local fixture failure"}}}""").jsonObject)
        var opened: String? = null
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { ProvideMarkdownRendering {
            AgentDetailsScreen(item, {}, setOf("child-1")) { opened = it }
        } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reviewer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("child-2").assertExists()
        compose.onNodeWithTag("open-agent:child-2").assertDoesNotExist()
        compose.onNodeWithTag("open-agent:child-1").performScrollTo().performClick()
        assertEquals("child-1", opened)
    }
}
