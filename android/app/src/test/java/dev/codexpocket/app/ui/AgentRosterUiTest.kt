package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.ActivityStatus
import dev.codexpocket.app.data.AgentRosterEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentRosterUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val current = AgentRosterEntry("native-child-not-in-directory", "root/reviewer", ActivityStatus.Running, "running",
        taskPreview = "Review the current changes", isCurrent = true)
    private val historical = AgentRosterEntry("native-old", "root/previous", ActivityStatus.Completed, "completed",
        resultPreview = "Two issues fixed")

    private fun content(fontScale: Float = 1f, block: @Composable () -> Unit) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                PocketTheme("light") {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalPocketGlass provides false) {
                        PocketBackdrop {
                            Column(Modifier.fillMaxSize().padding(12.dp)) {
                                Text("Conversation title")
                                block()
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun noNativeChildrenMeansNoChip() {
        content { AgentRosterChip(emptyList(), true, false, {}) }
        compose.onNodeWithTag("agent-roster-chip").assertDoesNotExist()
    }

    @Test fun anchoredRosterShowsNamesSectionsAndReturnsOriginalNativeId() {
        var refreshes = 0
        var opened = ""
        content {
            AgentRosterChip(listOf(current, historical), true, false, { opened = it },
                names = mapOf(current.threadId to "Reviewer"), onRefresh = { refreshes++ })
        }
        val chip = compose.onNodeWithTag("agent-roster-chip")
        chip.assertContentDescriptionEquals("子代理 2 · 1进行中").performClick()
        compose.onNodeWithText("本轮子代理").assertIsDisplayed()
        compose.onNodeWithText("历史子代理").assertIsDisplayed()
        compose.onNodeWithText("Reviewer").assertIsDisplayed()
        compose.onNodeWithText("上次 · 已完成").assertIsDisplayed()
        compose.onNodeWithText("结果 · Two issues fixed").assertIsDisplayed()
        assertEquals(1, refreshes)
        compose.onNodeWithTag("agent-roster-open:${current.threadId}").performClick()
        compose.runOnIdle { assertEquals(current.threadId, opened) }
        compose.onNodeWithTag("agent-roster-panel").assertDoesNotExist()
        compose.onNodeWithTag("agent-roster-chip").performClick()
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    @Test fun cachedRosterIsReadableOfflineAndWhileLoading() {
        var online by mutableStateOf(false)
        var loading by mutableStateOf(false)
        var opens = 0
        content { AgentRosterChip(listOf(current, historical), online, loading, { opens++ }) }
        compose.onNodeWithTag("agent-roster-chip").performClick()
        compose.onNodeWithText("离线 · 显示上次记录，连接电脑后可打开").assertIsDisplayed()
        compose.onNodeWithTag("agent-roster-open:${current.threadId}").assertIsNotEnabled()
        compose.onNodeWithText("任务 · Review the current changes").assertIsDisplayed()
        compose.runOnIdle { online = true; loading = true }
        compose.onNodeWithText("正在读取 · 显示已知记录").assertIsDisplayed()
        compose.onNodeWithTag("agent-roster-open:${current.threadId}").assertIsNotEnabled()
        compose.runOnIdle { loading = false }
        compose.onNodeWithTag("agent-roster-open:${current.threadId}").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun longRosterAtLargeFontStaysBoundedAndScrollsToLastIdentity() {
        var density = 1f
        val agents = (0..19).map { index -> current.copy(threadId = "native-$index", name = "Long reviewer name $index", taskPreview = "Detailed task preview ".repeat(30)) }
        var opened = ""
        content(1.6f) {
            density = LocalDensity.current.density
            AgentRosterChip(agents, true, false, { opened = it })
        }
        compose.onNodeWithTag("agent-roster-chip").performClick()
        val panel = compose.onNodeWithTag("pocket-modal-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.width <= 320f * density + 1f)
        assertTrue(panel.height <= 320f * density + 24f * density)
        compose.onNodeWithTag("agent-roster-open:native-19").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("native-19", opened) }
    }

    @Test fun historicalRunningStateIsNotCountedAsCurrentWork() {
        content { AgentRosterChip(listOf(current.copy(isCurrent = false)), true, false, {}) }
        compose.onNodeWithTag("agent-roster-chip").assertContentDescriptionEquals("子代理 1 · 0进行中").performClick()
        compose.onNodeWithText("历史子代理").assertIsDisplayed()
        compose.onNodeWithText("上次 · 进行中").assertIsDisplayed()
    }
}
