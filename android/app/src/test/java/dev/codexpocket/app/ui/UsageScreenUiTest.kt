package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val filter = UsageFilter.lastDays(7, "UTC", LocalDate.of(2026, 11, 10))
    private val response = UsageResponse.fromJson(usageFixtureJson())
    private fun ready() = UsageScreenState(filter, UsageSnapshot(filter, response, 1794268800), UsagePhase.Ready, canQuery = true)
    private fun content(theme: String = "light", font: Float = 1f, block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalPocketGlass provides false, LocalDensity provides Density(density.density, font)) { block() }
        } } }
        compose.waitForIdle()
    }
    private fun scroll(tag: String) = compose.onNodeWithTag("usage-list").performScrollToNode(hasTestTag(tag))

    @Test fun disconnectedHasNoInventedMetricsAndRefreshIsDisabled() {
        content { UsageScreen(UsageScreenState(filter), {}, {}) }
        compose.onNodeWithText("用量尚未接入").assertExists()
        compose.onNodeWithTag("usage-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("usage-metrics").assertDoesNotExist()
    }
    @Test fun metricsUseTwoColumnsAndCostAndPerformanceCoverageStayHonest() {
        content { UsageScreen(ready(), {}, {}) }
        scroll("usage-metrics")
        val requests = compose.onNodeWithTag("usage-metric:requests").fetchSemanticsNode().boundsInRoot
        val cost = compose.onNodeWithTag("usage-metric:cost").fetchSemanticsNode().boundsInRoot
        assertEquals(requests.top, cost.top, 1f)
        assertTrue(cost.left >= requests.right)
        scroll("usage-performance")
        compose.onAllNodesWithText("150 ms").assertCountEquals(2)
        compose.onNodeWithText("100 token/s").assertExists()
        scroll("usage-coverage")
        compose.onNodeWithText("仅统计保留明细，历史可能不完整；未混加日汇总或其他来源。").assertExists()
        compose.onNodeWithText("已记录为 0 的费用有 1 条，定价状态未知；无法确认免费条数。").assertExists()
    }
    @Test fun refreshKeepsSameSnapshotButFilterChangeHidesItImmediately() {
        var state by mutableStateOf(ready())
        var refresh = 0
        content { UsageScreen(state, { refresh++ }, {}) }
        compose.onNodeWithTag("usage-refresh").performClick()
        compose.runOnIdle { assertEquals(1, refresh); state = state.copy(refreshing = true) }
        scroll("usage-metrics")
        compose.onNodeWithTag("usage-metrics").assertExists()
        compose.runOnIdle { state = state.copy(filter = filter.withModel("other"), phase = UsagePhase.Loading) }
        compose.onNodeWithTag("usage-metrics").assertDoesNotExist()
    }
    @Test fun offlineSnapshotIsExplicitAndNeverReportedAsCurrent() {
        content { UsageScreen(ready().copy(phase = UsagePhase.Offline, canQuery = false, cached = true), {}, {}) }
        compose.onNodeWithText("电脑已离线").assertExists()
        compose.onNodeWithText("缓存快照 · 2026-11-10 00:00:00 · UTC").assertExists()
        compose.onNodeWithTag("usage-refresh").assertIsNotEnabled()
    }
    @Test fun pagingCallbacksKeepProviderAndModelOffsetsIndependent() {
        val changes = mutableListOf<UsageFilter>()
        content { UsageScreen(ready(), {}, { changes += it }) }
        scroll("usage-provider-next")
        compose.onNodeWithTag("usage-provider-next").performClick()
        compose.runOnIdle {
            assertEquals(50, changes.single().providerPage.offset)
            assertEquals(0, changes.single().modelPage.offset)
        }
    }
    @Test fun chartHasAccessibleTextAndBucketDetailsWithOpaqueDarkSurface() {
        content("dark", 1.6f) { UsageScreen(ready(), {}, {}) }
        scroll("usage-trend-chart")
        val node = compose.onNodeWithTag("usage-trend-chart").fetchSemanticsNode()
        val text = node.config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(text.contains("2026-11-10"))
        assertTrue(text.contains("请求 5"))
        val bounds = node.boundsInRoot
        compose.runOnIdle {
            val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val pixels = bitmap.asImageBitmap().toPixelMap()
            val color = pixels[bounds.left.toInt(), bounds.top.toInt()]
            assertEquals(1f, color.alpha, .01f)
            assertEquals(36 / 255f, color.red, .02f)
            bitmap.recycle()
        }
        scroll("usage-trend-details-toggle")
        compose.onNodeWithTag("usage-trend-details-toggle").performClick()
        scroll("usage-bucket:0")
        compose.onNodeWithTag("usage-bucket:0").assertExists()
    }
    @Test fun changingPhasesNeverStartsAutomaticQueries() {
        var state by mutableStateOf(UsageScreenState(filter))
        var calls = 0
        content { UsageScreen(state, { calls++ }, { calls++ }) }
        listOf(UsagePhase.Idle, UsagePhase.Loading, UsagePhase.Failed, UsagePhase.Ready, UsagePhase.Offline).forEach { phase ->
            compose.runOnIdle { state = state.copy(phase = phase) }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(0, calls) }
    }
    @Test fun filterDialogValidatesDatesAndAppliesExactStatusProviderAndModel() {
        val changes = mutableListOf<UsageFilter>()
        content { UsageScreen(ready(), {}, { changes += it }) }
        compose.onNodeWithTag("usage-filter-open").performClick()
        compose.onNodeWithTag("usage-filter-start").performTextReplacement("2026-11-11")
        compose.onNodeWithTag("usage-filter-apply").assertIsNotEnabled()
        compose.onNodeWithTag("usage-filter-start").performTextReplacement("2026-11-09")
        compose.onNodeWithTag("usage-filter-provider").performScrollTo().performTextReplacement("p2")
        compose.onNodeWithTag("usage-filter-model").performScrollTo().performTextReplacement("m2")
        compose.onNodeWithTag("usage-filter-status:error").performScrollTo().performClick()
        compose.onNodeWithTag("usage-filter-apply").performClick()
        compose.runOnIdle {
            val f = changes.single()
            assertEquals("p2", f.providerId); assertEquals("m2", f.model); assertEquals(UsageStatusFilter.Error, f.status)
            assertEquals(LocalDate.of(2026, 11, 9), f.startDate)
        }
    }
    @Test fun managementUsesUsageInsteadOfExtensionsWithoutChangingOtherTabs() {
        content { SettingsPageLayout(SettingsPage.Extensions, extensionContent = { modifier, padding ->
            UsageScreen(UsageScreenState(filter), {}, {}, modifier, padding)
        }) { } }
        compose.onNodeWithText("用量").assertExists()
        compose.onNodeWithText("扩展管理").assertDoesNotExist()
        compose.onNodeWithText("电脑连接").assertExists()
        compose.onNodeWithText("应用设置").assertExists()
        compose.onNodeWithTag("usage-screen").assertExists()
    }
}
