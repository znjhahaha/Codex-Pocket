package dev.codexpocket.app

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.ActivityStatus
import dev.codexpocket.app.data.AgentRosterEntry
import dev.codexpocket.app.ui.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in component fixture; callbacks are local counters, with no repository or model requests. */
@RunWith(AndroidJUnit4::class)
class AgentRosterDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun compactRosterRemainsReadableAndOpensTheOriginalChildIdentity() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pocketLocalAgentRosterFixtures") == "true")
        var opened = ""
        var refreshes = 0
        var online by mutableStateOf(false)
        var loading by mutableStateOf(false)
        var large by mutableStateOf(false)
        var density = 1f
        val current = AgentRosterEntry("native-child-absent-from-directory", "root/reviewer", ActivityStatus.Running,
            "running", taskPreview = "检查紧凑入口、浮层位置和原生身份", isCurrent = true)
        val history = (1..12).map { index -> AgentRosterEntry("native-history-$index", "root/history/$index",
            ActivityStatus.Completed, "completed", resultPreview = "历史任务 $index 的结果已保留") }
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                PocketTheme(if (large) "dark" else "light") {
                    val actual = LocalDensity.current
                    SideEffect { density = actual.density }
                    CompositionLocalProvider(LocalDensity provides Density(actual.density, if (large) 1.6f else 1f)) {
                        PocketBackdrop {
                            Column(Modifier.fillMaxSize().padding(12.dp)) {
                                Text("原生父会话")
                                AgentRosterChip(listOf(current) + history, online, loading, { opened = it },
                                    names = mapOf(current.threadId to "Reviewer"), onRefresh = { refreshes++ })
                                Text("折叠后的会话正文")
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("agent-roster-chip").assertContentDescriptionEquals("子代理 13 · 1进行中").performClick()
        compose.onNodeWithText("Reviewer").assertIsDisplayed()
        compose.onNodeWithTag("agent-roster-open:${current.threadId}").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, refreshes); online = true; loading = true; large = true }
        compose.onNodeWithText("正在读取 · 显示已知记录").assertIsDisplayed()
        compose.onNodeWithTag("agent-roster-open:${current.threadId}").assertIsNotEnabled()
        compose.onNodeWithTag("agent-roster-open:native-history-12").performScrollTo().assertIsDisplayed()
        val panel = compose.onNodeWithTag("pocket-modal-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.width <= 320f * density + 1f)
        compose.runOnIdle { loading = false }
        compose.onNodeWithTag("agent-roster-open:${current.threadId}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(current.threadId, opened); assertEquals(1, refreshes) }
        compose.onNodeWithTag("agent-roster-panel").assertDoesNotExist()
        compose.onNodeWithTag("agent-roster-chip").assertIsDisplayed()
    }
}
