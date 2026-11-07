package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.PermissionPreset
import dev.codexpocket.app.data.PermissionState
import kotlinx.serialization.json.JsonPrimitive
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
class PermissionPanelUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val request = PermissionPreset("request", "请求批准", "外部访问需要批准", true)
    private val auto = PermissionPreset("auto", "帮我批准", "检测到风险时请求批准", true)
    private val full = PermissionPreset("full", "完全访问权限", "允许电脑提供的完整访问", true)
    private fun ready() = PermissionState(status = "ready", revision = JsonPrimitive("r1"),
        currentPresetId = "request", presets = listOf(request, auto, full))

    private fun content(fontScale: Float = 1f, block: @Composable () -> Unit) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                PocketTheme("light") {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalPocketGlass provides false) {
                        PocketBackdrop { Box(Modifier.fillMaxSize()) { block() } }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun selectionWaitsForAuthoritativeStateAndRapidClickOnlyRequestsOnce() {
        var state by mutableStateOf(ready())
        val requested = mutableListOf<String>()
        var dismissals = 0
        content { PermissionPanel(state, true, false, { dismissals++ }, {}, { requested += it }) }
        compose.onNodeWithTag("permission-preset:request").assertIsSelected()
        compose.onNodeWithTag("permission-preset:full").assertIsNotSelected().performClick()
        compose.onNodeWithTag("permission-preset:full").assertIsNotSelected().assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("permission-preset:request").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf("full"), requested); assertEquals(0, dismissals); state = state.copy(updating = true) }
        compose.onNodeWithTag("permission-preset:auto").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(updating = false, revision = JsonPrimitive("r2"), currentPresetId = "full") }
        compose.onNodeWithTag("permission-preset:full").assertIsSelected()
        compose.onNodeWithTag("permission-preset:auto").assertIsEnabled()
    }

    @Test fun rejectedUpdateKeepsOldCheckAndAllowsRetryAfterStateResolution() {
        var state by mutableStateOf(ready())
        var requests = 0
        content { PermissionPanel(state, true, false, {}, {}, { requests++ }) }
        compose.onNodeWithTag("permission-preset:full").performClick()
        compose.runOnIdle { state = state.copy(updating = true) }
        compose.runOnIdle { state = state.copy(updating = false, error = "电脑拒绝了权限修改") }
        compose.onNodeWithTag("permission-preset:request").assertIsSelected()
        compose.onNodeWithTag("permission-preset:full").assertIsNotSelected()
        compose.onNodeWithText("电脑拒绝了权限修改").assertIsDisplayed()
        compose.onNodeWithTag("permission-preset:full").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(error = null) }
        compose.onNodeWithTag("permission-preset:full").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun offlineLoadingUnsupportedUnknownAndErrorRemainDistinct() {
        var state by mutableStateOf(ready())
        var online by mutableStateOf(false)
        var refreshes = 0
        content { PermissionPanel(state, online, false, {}, { refreshes++ }, { fail("Unavailable choice must not submit") }) }
        compose.onNodeWithText("离线 · 连接电脑后可选择权限").assertIsDisplayed()
        compose.onNodeWithTag("permission-preset:request").assertIsSelected()
        compose.onNodeWithTag("permission-preset:full").assertIsNotEnabled()
        compose.onNodeWithTag("permission-refresh").assertIsNotEnabled()
        compose.runOnIdle { online = true; state = state.copy(loading = true) }
        compose.onNodeWithTag("permission-loading").assertExists()
        compose.onNodeWithTag("permission-preset:full").assertIsNotEnabled()
        compose.runOnIdle { state = PermissionState(status = "unsupported") }
        compose.onNodeWithText("当前运行时不支持会话权限选择").assertIsDisplayed()
        compose.onNodeWithTag("permission-refresh").assertIsNotEnabled()
        compose.runOnIdle { state = PermissionState() }
        compose.onNodeWithText("会话权限未同步").assertIsDisplayed()
        compose.onNodeWithTag("permission-refresh").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, refreshes); state = PermissionState(status = "error", error = "读取失败，请重新加载") }
        compose.onNodeWithText("读取失败，请重新加载").assertIsDisplayed()
        compose.onNodeWithTag("permission-refresh").assertIsEnabled()
    }

    @Test fun runningChoicesApplyLaterAndDisabledPresetShowsNativeReason() {
        val state = ready().copy(presets = listOf(request, auto, full.copy(enabled = false, reason = "电脑策略禁止完全访问")))
        var selected: String? = null
        content { PermissionPanel(state, true, true, {}, {}, { selected = it }) }
        compose.onNodeWithText("后续回合生效 · 当前审批不变").assertIsDisplayed()
        compose.onNodeWithTag("permission-preset:full").assertIsNotEnabled()
        compose.onNodeWithText("电脑策略禁止完全访问").assertDoesNotExist()
        compose.onNodeWithContentDescription("查看不可用原因：完全访问权限").performClick()
        compose.onNodeWithText("电脑策略禁止完全访问").assertIsDisplayed()
        compose.onNodeWithTag("permission-preset:auto").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("auto", selected) }
    }

    @Test fun missingRevisionAndEmptyCatalogNeverExposeWritableDefaults() {
        var state by mutableStateOf(ready().copy(revision = null, currentPresetId = null))
        content { PermissionPanel(state, true, false, {}, {}, { fail("Missing revision must block dispatch") }) }
        compose.onNodeWithText("权限状态需要重新读取").assertIsDisplayed()
        compose.onNodeWithTag("permission-preset:request").assertIsNotSelected().assertIsNotEnabled()
        compose.runOnIdle { state = ready().copy(presets = emptyList(), currentPresetId = null) }
        compose.onNodeWithText("当前运行时未提供可选权限").assertIsDisplayed()
        compose.onNodeWithTag("permission-preset:request").assertDoesNotExist()
    }

    @Test fun anchoredLargeFontCatalogStaysBoundedAndCustomChoicesScrollInside() {
        var density = 1f
        var selected = ""
        val custom = (0..19).map { PermissionPreset("profile:$it", "自定义配置 $it", "原生已启用的配置，路径和正文不显示", true) }
        content(1.6f) {
            density = LocalDensity.current.density
            PermissionPanel(ready().copy(presets = ready().presets + custom), true, false, {}, {}, { selected = it },
                anchor = Rect(24f * density, 700f * density, 72f * density, 748f * density))
        }
        val panel = compose.onNodeWithTag("pocket-modal-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.width <= 320f * density + 1f)
        assertTrue(panel.height <= 800f * density * .65f + 1f)
        assertTrue(panel.bottom <= 700f * density)
        compose.onNodeWithTag("permission-preset:request").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("permission-preset:profile:19").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("profile:19", selected) }
    }
}
