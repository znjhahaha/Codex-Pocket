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
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class V051NativeActivityRowsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun item(raw: String) = TimelineItem.from("turn", pocketJson.parseToJsonElement(raw).jsonObject)
    private fun content(names: Map<String, String> = emptyMap(), block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlass provides false, LocalNativeAgentNames provides names) {
                Surface { ProvideMarkdownRendering { block() } }
            }
        } } }
        compose.waitForIdle()
    }
    private fun waitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun createdCountIsDefaultAndClickExpandsNamesPromptResultAndStatusInline() {
        val created = item("""{"id":"call","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","prompt":"Review the layout carefully.","receiverThreadIds":["raw-child-id"],"agentsStates":{"raw-child-id":{"status":"errored","message":"Fixture failure"}}}""")
        var opened: TimelineItem? = null
        content(mapOf("raw-child-id" to "Hooke")) {
            NativeActivityTrace(TranscriptRow("activity", TranscriptKind.Activity, listOf(created)), "test", false, {}, { opened = it })
        }
        waitText("已创建 1 个智能体")
        compose.onNodeWithText("Hooke").assertDoesNotExist()
        compose.onNodeWithText("Review the layout carefully.").assertDoesNotExist()
        compose.onNodeWithText("raw-child-id", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("activity-toggle:${created.key}").performClick()
        waitText("Hooke")
        compose.onNodeWithText("Review the layout carefully.").assertExists()
        compose.onNodeWithText("Fixture failure").assertExists()
        compose.onNodeWithText("raw-child-id", substring = true).assertDoesNotExist()
        assertEquals(PocketIcons.RobotOutline, kindIcon(ActivityKind.Agents))
        compose.runOnIdle { assertNull(opened) }
        compose.onNodeWithTag("agent-summary:raw-child-id").performClick()
        compose.runOnIdle { assertSame(created, opened) }
        compose.onNodeWithTag("activity-toggle:${created.key}").performClick()
        compose.onNodeWithText("Review the layout carefully.").assertDoesNotExist()
    }

    @Test fun sentMessageHasCountAndMissingNameNeverFallsBackToIdentity() {
        val sent = item("""{"id":"call","type":"collabAgentToolCall","tool":"sendInput","status":"completed","prompt":"Please review.","receiverThreadIds":["01a1b5ae-0123-4567-89ab-0123456789ab"],"agentsStates":{}}""")
        content { NativeActivityTrace(TranscriptRow("activity", TranscriptKind.Activity, listOf(sent)), "test", false, {}, {}) }
        waitText("已发送消息 1 个智能体")
        compose.onNodeWithText("Please review.").assertDoesNotExist()
        compose.onNodeWithTag("activity-toggle:${sent.key}").performClick()
        waitText("智能体 1")
        compose.onNodeWithText("Please review.").assertExists()
        compose.onNodeWithText("01a1b5ae", substring = true).assertDoesNotExist()
    }

    @Test fun completedEditsAndCommandsFoldToReferenceSentenceAndOpenOriginalDetails() {
        val command = item("""{"id":"c","type":"commandExecution","command":"pwsh.exe -NoProfile -Command 'Get-Content README.md'","status":"completed","exitCode":0,"aggregatedOutput":"Original output"}""")
        val file = item("""{"id":"f","type":"fileChange","status":"completed","changes":[{"path":"src/ChatScreen.kt","kind":{"type":"update"},"diff":"@@ -1 +1 @@\n-old\n+new"}]}""")
        val row = TranscriptRow("group", TranscriptKind.Activity, listOf(command, file), true)
        var detailed: TimelineItem? = null
        content {
            var expanded by remember { mutableStateOf(false) }
            NativeActivityTrace(row, "test", expanded, { expanded = !expanded }, { detailed = it })
        }
        waitText("编辑了文件运行了命令")
        compose.onNodeWithText("Get-Content README.md").assertDoesNotExist()
        compose.onNodeWithTag("activity-group-toggle:group").performClick()
        waitText("Get-Content README.md")
        compose.onNodeWithText("ChatScreen.kt").assertExists()
        compose.onNodeWithText("Original output").assertDoesNotExist()
        compose.onNodeWithTag("activity-toggle:${command.key}").performClick()
        compose.onNodeWithText("$ " + command.raw.string("command")).assertExists()
        compose.onNodeWithText("Original output").assertExists()
        compose.onNodeWithText("ChatScreen.kt").performClick()
        compose.onNodeWithTag("inline-diff:file:0").assertExists()
        compose.runOnIdle { assertNull(detailed) }
    }

    @Test fun activeAgentStripUsesCurrentNamesAndExcludesOldOrFinishedChildren() {
        val entries = listOf(
            AgentRosterEntry("a", status = ActivityStatus.Running, isCurrent = true),
            AgentRosterEntry("b", status = ActivityStatus.Running, isCurrent = true),
            AgentRosterEntry("c", status = ActivityStatus.Running, isCurrent = true),
            AgentRosterEntry("d", status = ActivityStatus.Running, isCurrent = true),
            AgentRosterEntry("old", "Old", status = ActivityStatus.Running),
            AgentRosterEntry("done", "Finished", status = ActivityStatus.Completed, isCurrent = true),
        )
        var opened: String? = null
        content(mapOf("a" to "Hooke", "b" to "Dewey")) { Column { NativeAgentWorkingStrip(entries, onOpen = { opened = it }) } }
        compose.onNodeWithText("Hooke、Dewey和另外2个子智能体正在工作").assertExists()
        compose.onNodeWithText("Old", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Finished", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("native-agents-working").performClick()
        compose.runOnIdle { assertEquals("a", opened) }
        assertEquals("", activeAgentWorkSummary(entries.filter { !it.isCurrent }, emptyMap()))
        assertEquals("", nativeAgentDisplayName("a", "a", emptyMap()))
        assertEquals("Hooke", nativeAgentDisplayName("a", "old", mapOf("a" to "Hooke")))
    }
}
