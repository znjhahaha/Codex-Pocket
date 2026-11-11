package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class NativeTraceDensityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var density = 1f
    private fun item(json: String) = TimelineItem.from("turn", pocketJson.parseToJsonElement(json).jsonObject)
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlass provides false) {
                density = LocalDensity.current.density
                Surface(Modifier.fillMaxSize()) { ProvideMarkdownRendering { Column { block() } } }
            }
        } } }
        compose.waitForIdle()
    }
    private fun waitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun rawCommandRowAndFoldedHeaderUse32dpAndKeepTrailingChevron() {
        val command = item("""{"id":"command","type":"commandExecution","command":"pwsh.exe -NoProfile -Command 'Get-Content README.md'","status":"completed","exitCode":0}""")
        content { NativeActivityTrace(TranscriptRow("row", TranscriptKind.Activity, listOf(command)), "test", false, {}, {}) }
        waitText("pwsh.exe -NoProfile")
        val row = compose.onNodeWithTag("activity-toggle:${command.key}").fetchSemanticsNode().boundsInRoot
        assertEquals(32f * density, row.height, 1f)
        compose.onNodeWithContentDescription("展开详情").assertIsDisplayed()
    }

    @Test fun selectedFileAndReadingStateSurviveGroupCollapseAndCompletion() {
        val file = item("""{"id":"files","type":"fileChange","status":"completed","changes":[{"path":"src/Created.kt","kind":"add","diff":"val new = 1"},{"path":"src/Deleted.kt","kind":"delete","diff":"val old = 1"}]}""")
        val command = item("""{"id":"command","type":"commandExecution","command":"echo fixture","status":"completed","exitCode":0}""")
        val row = TranscriptRow("group", TranscriptKind.Activity, listOf(command, file), true)
        content {
            var expanded by remember { mutableStateOf(true) }
            NativeActivityTrace(row, "test", expanded, { expanded = !expanded }, {})
        }
        waitText("Created.kt")
        compose.onNodeWithText("已创建").assertExists()
        compose.onNodeWithText("已删除").assertExists()
        compose.onNodeWithTag("activity-file:file:1").performClick()
        compose.onNodeWithTag("inline-diff:file:1").assertExists()
        compose.onNodeWithTag("inline-diff:file:0").assertDoesNotExist()
        compose.onNodeWithTag("activity-group-toggle:group").performClick()
        compose.onNodeWithTag("inline-diff:file:1").assertDoesNotExist()
        compose.onNodeWithTag("activity-group-toggle:group").performClick()
        // Reopening remounts the tool descriptor, which parses on Dispatchers.Default.
        // Assert the retained file choice after that presentation is available again.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("inline-diff:file:1").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("inline-diff:file:1").assertExists()
    }

    @Test fun largeGroupScrollsInsideItsBoundedViewport() {
        val commands = (1..25).map { n -> item("""{"id":"command$n","type":"commandExecution","command":"echo $n","status":"completed","exitCode":0}""") }
        content { NativeActivityTrace(TranscriptRow("group", TranscriptKind.Activity, commands, true), "test", true, {}, {}) }
        val list = compose.onNodeWithTag("activity-group-scroll:group")
        assertTrue(list.fetchSemanticsNode().boundsInRoot.height <= 360f * density + 1f)
        compose.onNodeWithTag("activity-toggle:${commands.last().key}").assertDoesNotExist()
        list.performScrollToIndex(commands.lastIndex)
        compose.onNodeWithTag("activity-toggle:${commands.last().key}").assertIsDisplayed()
    }
}
