package dev.codexpocket.app.ui

import android.text.SpannedString
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanStreamingRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var source by mutableStateOf("first")
    private var identity by mutableStateOf("plan")
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val keys = mutableListOf<String>()

    private fun start() {
        gates["first"] = CompletableDeferred(Unit)
        compose.activity.runOnUiThread { compose.activity.setContent {
            val rendered by rememberPlanRendering(identity, source, "renderer") { key, body ->
                keys += key
                // Simulate a parser that finishes its CPU work after cancellation.
                withContext(NonCancellable) { gates.getOrPut(body) { CompletableDeferred() }.await() }
                RenderedMarkdown(body, SpannedString(body), emptyList())
            }
            Text(rendered?.source ?: "initial", Modifier.testTag("document"))
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("first").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun continuousDeltasRetainFormattingAndPublishProgressWithoutStarvation() {
        start()
        compose.runOnIdle { source = "second" }
        compose.waitForIdle()
        compose.onNodeWithTag("document").assertTextEquals("first")
        compose.runOnIdle { source = "third" }
        compose.waitForIdle()
        compose.runOnIdle { source = "fourth" }
        compose.waitForIdle()
        compose.runOnIdle { gates.getValue("second").complete(Unit) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("second").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertFalse(gates.containsKey("third")); gates.getValue("fourth").complete(Unit) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("fourth").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("document").assertTextEquals("fourth")
        compose.runOnIdle { assertEquals(1, keys.distinct().size) }
    }

    @Test fun differentPlanCannotShowPreviousDocument() {
        start()
        compose.runOnIdle { source = "old-pending" }
        compose.waitForIdle()
        compose.runOnIdle { identity = "other-plan"; source = "new-document" }
        compose.waitForIdle()
        compose.onNodeWithTag("document").assertTextEquals("initial")
        compose.runOnIdle { gates.getValue("new-document").complete(Unit) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("new-document").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { gates.getValue("old-pending").complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag("document").assertTextEquals("new-document")
    }
}
