package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Component fixtures dispatch callbacks only; no repository, bridge or network is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManagementSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val desktop = RuntimeInfo("desktop-a", "desktop", "0.166.0", "store-a", true, emptySet(), emptySet())
    private val other = desktop.copy(id = "desktop-b", version = "0.167.0")
    private val mcp = ManagedExtension("mcp-one", "mcp", "Search tools", description = "Search configured sources",
        enabled = true, status = "ready", canToggle = true)
    private fun ready() = PocketState(paired = true, online = true, computerName = "My PC", computerId = "pc",
        runtimes = listOf(desktop, other), extensions = ExtensionManagementState(computerId = "pc", runtimeId = desktop.id,
            phase = ExtensionPhase.Ready, entries = listOf(mcp, ManagedExtension("skill-one", "skill", "Writer"),
                ManagedExtension("app-one", "app", "Calendar"), ManagedExtension("plugin-one", "plugin", "Files"))))

    private fun content(fontScale: Float = 1f, block: @Composable () -> Unit) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                PocketTheme("light") {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalPocketGlass provides false,
                        LocalDensity provides Density(density.density, fontScale)) {
                        block()
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun panel(state: () -> PocketState, onRuntime: (String) -> Unit = {},
        onToggle: (String, Boolean) -> Unit = { _, _ -> }, onRefresh: () -> Unit = {}) {
        content {
            SettingsPageLayout(SettingsPage.Extensions, extensionContent = { modifier, padding ->
                ExtensionManagementPanel(state(), onRuntime, onToggle, onRefresh, modifier, padding)
            }) { }
        }
    }

    @Test fun segmentedPagesRetainTheirOwnScrollPosition() {
        content {
            SettingsPageLayout {
                repeat(36) { index -> Text("${it.name} item $index", Modifier.fillMaxWidth().height(54.dp).testTag("${it.name}:$index")) }
            }
        }
        compose.onNodeWithTag("settings-tab:Connection").assertIsSelected()
        compose.onNodeWithTag("Connection:24").performScrollTo().assertIsDisplayed()
        val before = compose.onNodeWithTag("Connection:24").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("settings-tab:Extensions").performClick()
        compose.onNodeWithTag("Extensions:0").assertIsDisplayed()
        compose.onNodeWithTag("Extensions:16").performScrollTo()
        compose.onNodeWithTag("settings-tab:App").performClick()
        compose.onNodeWithTag("App:0").assertIsDisplayed()
        compose.onNodeWithTag("settings-tab:Connection").performClick()
        compose.onNodeWithTag("Connection:24").assertIsDisplayed()
        val restored = compose.onNodeWithTag("Connection:24").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, restored, 1f)
    }

    @Test fun categoryCountsAndToggleUseOriginalIdentityAndAcknowledgedState() {
        var state by mutableStateOf(ready())
        val toggles = mutableListOf<Pair<String, Boolean>>()
        var refreshes = 0
        panel({ state }, onToggle = { id, enabled -> toggles += id to enabled }, onRefresh = { refreshes++ })
        compose.onNodeWithText("MCP · 1").assertExists()
        compose.onNodeWithText("Skills · 1").assertExists()
        compose.onNodeWithText("应用 / 插件 · 2").assertExists()
        compose.onNodeWithTag("extension-toggle:mcp-one").performScrollTo().assertIsOn().performClick()
        compose.runOnIdle { assertEquals(listOf("mcp-one" to false), toggles) }
        compose.onNodeWithTag("extension-toggle:mcp-one").assertIsOn()
        compose.runOnIdle { state = state.copy(extensions = state.extensions.copy(pendingIds = setOf(mcp.id))) }
        compose.onNodeWithTag("extension-toggle:mcp-one").assertIsNotEnabled()
        compose.onNodeWithTag("extension-refresh").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(extensions = state.extensions.copy(pendingIds = emptySet(), uncertainIds = setOf(mcp.id))) }
        compose.onNodeWithTag("extension-toggle:mcp-one").assertIsNotEnabled()
        compose.onNodeWithTag("extension-refresh").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun changingRuntimeHidesPreviousErrorInventoryAndCounts() {
        var state by mutableStateOf(ready().let { it.copy(extensions = it.extensions.copy(
            phase = ExtensionPhase.Unsupported, status = "update_required", error = "Old runtime error")) })
        val loads = mutableListOf<String>()
        panel({ state }, onRuntime = { loads += it })
        compose.onNodeWithText("需要更新电脑桥接").assertExists()
        compose.onNodeWithTag("extension-runtime-summary").performClick()
        compose.onNodeWithTag("extension-runtime:${other.id}").performClick()
        compose.onNodeWithText("正在切换运行时").assertExists()
        compose.onNodeWithText("Old runtime error").assertDoesNotExist()
        compose.onNodeWithText("需要更新电脑桥接").assertDoesNotExist()
        compose.onNodeWithTag("extension:${mcp.id}").assertDoesNotExist()
        compose.onNodeWithText("MCP · —").assertExists()
        compose.runOnIdle { assertEquals(listOf(desktop.id, other.id), loads) }
        compose.onNodeWithTag("extension-refresh").performClick()
        compose.runOnIdle { assertEquals(other.id, loads.last()) }
        compose.runOnIdle { state = state.copy(extensions = ExtensionManagementState("pc", other.id, ExtensionPhase.Ready)) }
        compose.onNodeWithText("尚未配置 MCP").assertExists()
    }

    @Test fun nativeStatusOfflineLoadingFailureAndEmptyHaveSpecificNextSteps() {
        var state by mutableStateOf(ready())
        panel({ state })
        fun status(phase: ExtensionPhase, code: String, title: String, step: String) {
            compose.runOnIdle { state = state.copy(extensions = state.extensions.copy(phase = phase, status = code, error = null, entries = emptyList())) }
            compose.onNodeWithText(title).assertExists()
            compose.onNodeWithText(step).assertExists()
        }
        status(ExtensionPhase.Unsupported, "update_required", "需要更新电脑桥接", "先完成当前桌面任务，再关闭并重新打开 Codex，回到这里刷新。")
        status(ExtensionPhase.Unsupported, "bridge_missing", "电脑桥接尚未连接", "在电脑上打开 Codex；若仍未连接，完成当前桌面任务后重启 Codex，再刷新。")
        status(ExtensionPhase.Idle, "initializing", "扩展管理正在初始化", "等待电脑端初始化完成，然后刷新此运行时。")
        status(ExtensionPhase.Unsupported, "unsupported", "此运行时暂不支持扩展管理", "在电脑端管理扩展，或选择另一个支持扩展管理的运行时。")
        status(ExtensionPhase.Loading, "update_required", "正在读取扩展", "正在读取所选运行时的配置，请稍候。")
        status(ExtensionPhase.Failed, "update_required", "扩展读取失败", "确认电脑端 Codex 正常运行，然后刷新重试。")
        compose.runOnIdle { state = state.copy(online = false) }
        compose.onNodeWithText("电脑已离线").assertExists()
        compose.onNodeWithTag("extension-refresh").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(online = true, extensions = state.extensions.copy(phase = ExtensionPhase.Ready, status = "ready")) }
        compose.onNodeWithText("尚未配置 MCP").assertExists()
        compose.onNodeWithText("先在电脑端添加 MCP 服务，然后刷新此列表。").assertExists()
    }

    @Test fun unavailableAppsAreNotShownAsAnEmptyInstalledCatalog() {
        var state by mutableStateOf(ready().let { it.copy(extensions = it.extensions.copy(
            appInventoryStatus = "unsupported", entries = emptyList())) })
        panel({ state })
        compose.onNodeWithTag("extension-category:plugin").performClick()
        compose.onNodeWithText("应用 / 插件 · —").assertExists()
        compose.onNodeWithText("当前运行时未提供应用目录").assertExists()
        compose.onNodeWithText("尚未配置应用或插件").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(extensions = state.extensions.copy(appInventoryStatus = "ready")) }
        compose.onNodeWithText("应用 / 插件 · 0").assertExists()
        compose.onNodeWithText("尚未配置应用或插件").assertExists()
    }

    @Test fun identicalRuntimeVersionsRemainDistinctAndLargeFontTabsStayUsable() {
        content(1.6f) {
            SettingsPageLayout(SettingsPage.Extensions, extensionContent = { modifier, padding ->
                ExtensionManagementPanel(ready().copy(runtimes = listOf(desktop, other.copy(version = desktop.version))), {}, { _, _ -> }, {}, modifier, padding)
            }) { }
        }
        compose.onNodeWithTag("extension-runtime-summary").performClick()
        compose.onNodeWithTag("extension-runtime:desktop-a").assertTextContains("桌面端 · 0.166.0 · 实例 1")
        compose.onNodeWithTag("extension-runtime:desktop-b").assertTextContains("桌面端 · 0.166.0 · 实例 2")
        compose.onNodeWithTag("extension-runtime:desktop-b").performClick()
        compose.onNodeWithTag("settings-tab:App").assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
    }
    @Test fun lazyInventoryKeepsCategorySearchAndScrollAcrossPages() {
        val many = (0 until 200).map { mcp.copy(id = "mcp-$it", name = "Tool $it") }
        panel({ ready().let { it.copy(extensions = it.extensions.copy(entries = many + ManagedExtension("skill", "skill", "Writer"))) } })
        compose.onNodeWithTag("extension:mcp-199").assertDoesNotExist()
        compose.onNodeWithTag("extension-list").performScrollToNode(hasTestTag("extension:mcp-120"))
        val before = compose.onNodeWithTag("extension:mcp-120").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("extension-category:skill").performClick()
        compose.onNodeWithTag("extension-search").performTextInput("Writer")
        compose.onNodeWithTag("extension-category:mcp").performClick()
        compose.onNodeWithTag("extension:mcp-120").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("extension:mcp-120").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("settings-tab:App").performClick()
        compose.onNodeWithTag("settings-tab:Extensions").performClick()
        compose.onNodeWithTag("extension:mcp-120").assertIsDisplayed()
        compose.onNodeWithTag("extension-category:skill").performClick()
        compose.onNodeWithTag("extension-search").assertTextContains("Writer")
    }

    @Test fun reducedMotionStillAllowsTabsAndDetails() {
        content {
            CompositionLocalProvider(LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false)) {
                SettingsPageLayout(SettingsPage.Extensions, extensionContent = { modifier, padding ->
                    ExtensionManagementPanel(ready(), {}, { _, _ -> }, {}, modifier, padding)
                }) { }
            }
        }
        compose.onNodeWithTag("extension-list").performScrollToNode(hasTestTag("extension:mcp-one"))
        compose.onNodeWithText("Search tools").performClick()
        compose.onNodeWithText("Search configured sources").assertIsDisplayed()
        compose.onNodeWithTag("settings-tab:App").performClick().assertIsSelected()
        compose.onNodeWithTag("settings-tab:Extensions").performClick().assertIsSelected()
        compose.onNodeWithText("Search configured sources").assertIsDisplayed()
    }

}
