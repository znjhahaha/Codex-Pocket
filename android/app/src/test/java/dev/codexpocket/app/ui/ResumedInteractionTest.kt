package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
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
class ResumedInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun scrollingHidesTheModelEvenWithDraftAndActiveTurn() {
        var scrolling by mutableStateOf(false)
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            PocketComposer("existing draft", "Model", "高", true, ComposerAvailability(false, false, true, true),
                {}, {}, {}, {}, {}, {}, {}, true, transcriptScrolling = scrolling)
        } } }
        compose.onNodeWithTag("composer-model").assertExists()
        compose.runOnIdle { scrolling = true }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.onNodeWithTag("composer-input").assertTextEquals("existing draft")
        compose.runOnIdle { scrolling = false }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-model").assertExists()
    }

    @Test fun summaryFoldSurvivesNativeTextUpdatesAndNoSummaryHasNoDisclosure() {
        var summary by mutableStateOf<TimelineItem?>(TimelineItem("turn", "r", "reasoning", "**Reviewing layout**"))
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { ProvideMarkdownRendering {
            LiveTurnStatus("turn", ConversationPresentation.LiveStatus.Thinking, reasoning = summary)
        } } } }
        compose.onNodeWithTag("live-reasoning-summary").assertExists()
        compose.onNodeWithTag("live-reasoning-toggle").performClick()
        compose.onNodeWithTag("live-reasoning-summary").assertDoesNotExist()
        compose.runOnIdle { summary = summary?.copy(text = "**Reviewing constraints**") }
        compose.onNodeWithTag("live-reasoning-summary").assertDoesNotExist()
        compose.runOnIdle { summary = null }
        compose.onNodeWithTag("live-reasoning-toggle").assertDoesNotExist()
        compose.onNodeWithText("正在思考").assertExists()
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
