package dev.codexpocket.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in production presentation with native-shaped events, no model calls or saved chats. */
@RunWith(AndroidJUnit4::class)
class ReasoningLifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun thinkingAfterToolsSummaryAndCompletedHistoryStayVisible() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pocketMotionFixture") == "true")
        var timeline by mutableStateOf(NativeTimeline())
        fun event(method: String, params: String) {
            compose.runOnIdle { timeline = NativeReducer.reduce(timeline, buildJsonObject {
                put("method", method); put("params", pocketJson.parseToJsonElement(params))
            }) }
        }
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("dark") { PocketBackdrop {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("任务过程")
                ConversationPresentation.rows(timeline, foldTasks = false).forEach { row ->
                    TranscriptRowView(row, "reasoning-device", false, {}, {})
                }
                ConversationPresentation.liveStatus(timeline)?.let { status ->
                    LiveTurnStatus("t", status, reasoning = ConversationPresentation.liveReasoning(timeline),
                        timing = timeline.turns["t"])
                }
            }
        } } } }
        event("turn/started", """{"turn":{"id":"t","status":"inProgress"}}""")
        event("item/started", """{"turnId":"t","item":{"id":"c","type":"commandExecution","command":"git status","status":"inProgress"}}""")
        compose.onNodeWithText("正在思考").assertDoesNotExist()
        event("item/completed", """{"turnId":"t","item":{"id":"c","type":"commandExecution","command":"git status","status":"completed","exitCode":0}}""")
        compose.onNodeWithTag("live-thinking-placeholder").assertIsDisplayed()
        fun record(name: String) {
            NativeWindowRecording(compose.activity, File(compose.activity.getExternalFilesDir(null), "rc37-reasoning/$name")).use { Thread.sleep(350) }
        }
        record("thinking")
        event("item/reasoning/summaryTextDelta", """{"turnId":"t","itemId":"r","summaryIndex":0,"delta":"正在核对项目改动与检查结果。"}""")
        event("item/completed", """{"turnId":"t","item":{"id":"r","type":"reasoning"}}""")
        compose.onNodeWithTag("live-reasoning-toggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("live-reasoning-summary", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("正在核对项目改动与检查结果。").assertIsDisplayed()
        record("summary")
        event("turn/completed", """{"turn":{"id":"t","status":"completed"}}""")
        compose.onNodeWithTag("live-thinking-placeholder").assertDoesNotExist()
        compose.onNodeWithTag("live-turn-status").assertDoesNotExist()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("正在核对项目改动与检查结果。").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("正在核对项目改动与检查结果。").assertIsDisplayed()
        record("history")
    }
}
