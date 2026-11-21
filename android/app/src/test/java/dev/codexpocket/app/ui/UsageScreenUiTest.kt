package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
    private val response = UsageResponse.fromJson(usageV070Fixture())
    private fun ready() = UsageScreenState(filter, UsageSnapshot(filter, response, 1794268800), UsagePhase.Ready, canQuery = true, canPageRequests = true)
    private fun content(theme: String = "light", font: Float = 1f, glass: () -> Boolean = { true }, block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalPocketGlass provides glass(), LocalDensity provides Density(density.density, font)) { PocketOverlayHost { block() } }
        } } }
        compose.waitForIdle()
    }
    private fun scroll(tag: String) = compose.onNodeWithTag("usage-list").performScrollToNode(hasTestTag(tag))

    @Test fun appliedStatusIsVisibleWithoutOpeningFiltersAndTracksChanges() {
        var current by mutableStateOf(filter.withStatus(UsageStatusFilter.Error))
        var opened = 0
        content {
            Box(Modifier.width(320.dp)) {
                UsageFilterBar(current, { opened++ }, { current = it })
            }
        }
        compose.onNodeWithText("状态：失败").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            current = current.withStatus(UsageStatusFilter.Success)
        }
        compose.onNodeWithText("状态：失败").assertDoesNotExist()
        compose.onNodeWithText("状态：成功").assertIsDisplayed()
        compose.runOnIdle { current = current.withStatus(UsageStatusFilter.All) }
        compose.onNodeWithText("状态：成功").assertDoesNotExist()
        compose.onNodeWithText("状态：全部").assertIsDisplayed()
    }

    private fun deviceAmount(): UsageScreenState {
        val data = response.data!!
        val totals = data.totals.copy(requests = 12600u, success = 11825u, errors = 775u,
            tokens = data.totals.tokens.copy(freshInput = 90_000_000u, cacheRead = 1_500_000_000u, cacheWrite = 52_000_000u, output = 7_700_000u, total = 1_649_700_000u),
            costs = data.totals.costs.copy(total = data.totals.costs.total.copy(knownUsd = "2112.28012345")),
            performance = data.totals.performance.copy(ttft = data.totals.performance.ttft.copy(averageMs = 12440.0),
                outputSpeed = data.totals.performance.outputSpeed.copy(tokensPerSecond = 44.38)))
        // Controlled seven-day fixture: each sparkline must use the supplied daily values.
        val trend = listOf(0.7, 1.0, 0.5, 1.2, 1.1, 1.3, 1.2).mapIndexed { i, weight ->
            val sample = totals.copy(requests = (1800 * weight).toULong(), errors = (100 * weight).toULong(),
                tokens = totals.tokens.copy(total = (235_000_000 * weight).toULong(), cacheRead = (200_000_000 * weight).toULong()),
                costs = totals.costs.copy(total = totals.costs.total.copy(knownUsd = (300 * weight).toString())),
                performance = totals.performance.copy(ttft = totals.performance.ttft.copy(averageMs = 12440 * weight),
                    outputSpeed = totals.performance.outputSpeed.copy(tokensPerSecond = 44.38 * weight)))
            val start = 1793750400L + i * 86400
            UsageTrendBucket(start, start + 86400, sample)
        }
        val updated = response.copy(data = data.copy(totals = totals, trend = trend), cache = UsageCache("fresh", 1794268800, 0u, 60u, null))
        return ready().copy(snapshot = UsageSnapshot(filter, updated, 1794268800))
    }

    private fun assertSingleLine(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals("${layout.layoutInput.text} must remain one line", 1, layout.lineCount)
        assertFalse("${layout.layoutInput.text} must not clip or ellipsize", layout.hasVisualOverflow)
        assertFalse(layout.isLineEllipsized(0))
        assertTrue(layout.getLineRight(0) <= layout.size.width + 1f)
    }

    @Test @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun deviceCostRemainsCompleteOnOneLineWithManagementPadding() {
        var optics by mutableStateOf(true)
        content("dark", glass = { optics }) {
            UsageScreen(deviceAmount(), {}, {}, contentPadding = PaddingValues(horizontal = 16.dp))
        }
        compose.runOnIdle { optics = false }
        exportScreen("usage-device-dark-ready-411-font100.png")
        scroll("usage-metrics")
        assertSingleLine(compose.onNode(hasText("$2112.28") and hasAnyAncestor(hasTestTag("usage-metric:cost")), useUnmergedTree = true))
        val costTrend = compose.onNodeWithTag("usage-spark:cost", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertEquals("真实每日趋势：210, 300, 150, 360, 330, 390, 360", costTrend)
        exportScreen("usage-device-dark-kpis-411-font100.png")
    }

    @Test fun readyStatusIsCompactAndDoesNotExposeCacheInternals() {
        content { UsageScreen(deviceAmount(), {}, {}) }
        val bounds = compose.onNodeWithTag("usage-status").fetchSemanticsNode().boundsInRoot
        assertTrue("Ready status should fit within 40dp, was ${bounds.height / LocalTestDensity}", bounds.height <= 40 * LocalTestDensity)
        compose.onNodeWithText("缓存有效期", substring = true).assertDoesNotExist()
        compose.onNodeWithText("电脑端缓存", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已更新", substring = true).assertExists()
    }

    @Test fun failedAndStaleKeepRecoveryAndOriginalSnapshotTime() {
        var state by mutableStateOf(deviceAmount().copy(phase = UsagePhase.Failed, cached = true))
        var attempts = 0
        content { UsageScreen(state, { attempts++ }, {}) }
        compose.onNodeWithText("用量读取失败").assertExists()
        compose.onNodeWithTag("usage-retry").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, attempts)
            state = state.copy(phase = UsagePhase.Ready, cached = false, snapshot = state.snapshot!!.let {
                it.copy(response = it.response.copy(cache = it.response.cache!!.copy(state = "stale", ageSeconds = 90u)))
            })
        }
        compose.onNodeWithText("显示上次统计").assertExists()
        compose.onNodeWithText("缓存快照 · 2026-11-10 00:00:00 · UTC").assertExists()
        compose.onNodeWithText("已更新", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("usage-retry").assertIsEnabled()
        compose.runOnIdle { state = state.copy(refreshing = true) }
        compose.onNodeWithTag("usage-retry").assertIsNotEnabled()
    }

    @Test fun compactCostOpensExactSavedAmountWithUserFacingLabel() {
        content("light", 1.8f) {
            Box(Modifier.width(320.dp)) { UsageScreen(deviceAmount(), {}, {}, contentPadding = PaddingValues(horizontal = 16.dp)) }
        }
        scroll("usage-metrics")
        compose.onNodeWithTag("usage-value:cost", useUnmergedTree = true).assertTextEquals("$2.1K")
        assertSingleLine(compose.onNodeWithTag("usage-value:cost", useUnmergedTree = true))
        compose.onNodeWithTag("usage-metric:cost").performClick()
        compose.onNodeWithTag("usage-cost-breakdown").performScrollTo()
        compose.onNodeWithText("费用合计").assertExists()
        assertSingleLine(compose.onNodeWithText("USD 2112.28012345"))
        compose.onNodeWithText("最终 total", substring = true).assertDoesNotExist()
    }

    @Test fun disconnectedHasNoInventedMetricsAndRefreshIsDisabled() {
        content { UsageScreen(UsageScreenState(filter), {}, {}) }
        compose.onNodeWithText("用量尚未接入").assertExists()
        compose.onNodeWithTag("usage-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("usage-metrics").assertDoesNotExist()
    }
    @Test fun sevenKpisUseTwoColumnsWithFinalFullWidthAndRealSparklines() {
        content { UsageScreen(ready(), {}, {}) }
        scroll("usage-metrics")
        val tokens = compose.onNodeWithTag("usage-metric:tokens").fetchSemanticsNode().boundsInRoot
        val cost = compose.onNodeWithTag("usage-metric:cost").fetchSemanticsNode().boundsInRoot
        assertEquals(tokens.top, cost.top, 1f)
        assertTrue(cost.left >= tokens.right)
        listOf("tokens", "cost", "requests", "cache", "ttft", "speed", "errors").forEach { id ->
            compose.onNodeWithTag("usage-metric:$id").assertExists()
            compose.onNodeWithTag("usage-spark:$id", useUnmergedTree = true).assertExists()
        }
        scroll("usage-metric:errors")
        val errors = compose.onNodeWithTag("usage-metric:errors").fetchSemanticsNode().boundsInRoot
        assertTrue(errors.width > tokens.width * 1.8f)
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
    @Test fun fiveTrendModesAndTouchDetailsExposeMeasuredValuesInDarkLargeFont() {
        content("dark", 1.6f) { UsageScreen(ready(), {}, {}) }
        scroll("usage-trend-chart")
        val text = compose.onNodeWithTag("usage-trend-chart").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(text.contains("2026-11-10")); assertTrue(text.contains("请求 5"))
        listOf("Cost", "Tokens", "Requests", "Latency", "Speed").forEach { mode ->
            compose.onNodeWithTag("usage-trend:$mode").performScrollTo().performClick()
        }
        scroll("usage-trend-chart")
        compose.onNodeWithTag("usage-trend-chart").performTouchInput { click(center) }
        compose.onNodeWithTag("usage-insight-detail").assertExists()
        compose.onNodeWithTag("usage-performance").performScrollTo()
        compose.onNodeWithText("100 token/s").assertExists()
    }
    @Test fun sessionShortIdFilterAndRequestDetailsKeepUnknownCostVisible() {
        val changes = mutableListOf<UsageFilter>()
        content { UsageScreen(ready(), {}, { changes += it }) }
        scroll("usage-session:0")
        compose.onNodeWithTag("usage-session:0").performClick()
        compose.runOnIdle { assertEquals("abcdefghijklmnop", changes.single().sessionId) }
        scroll("usage-request:0")
        compose.onNodeWithTag("usage-request:0").performClick()
        compose.onNodeWithTag("usage-request-detail").assertExists()
        compose.onNodeWithText("费用：未记录").assertExists()
        compose.onNodeWithText("对话 ID：abcdefghijklmnop").assertExists()
    }
    @Test fun requestPagingIsExplicitAndDisabledWithoutParentCallback() {
        content { UsageScreen(ready(), {}, {}) }
        scroll("usage-requests-next")
        compose.onNodeWithTag("usage-requests-next").assertIsNotEnabled()
        var cursor: UsageRequestCursor? = null
        content { UsageScreen(ready(), {}, {}, onRequests = { cursor = it }) }
        scroll("usage-requests-next")
        compose.onNodeWithTag("usage-requests-next").performClick()
        compose.runOnIdle { assertEquals(response.data!!.recentRequests!!.nextCursor, cursor) }
    }
    @Test fun narrowLargeFontLightAndDarkRemainInsideCards() {
        listOf("light", "dark").forEach { theme ->
          listOf(1f, 1.8f, 2f).forEach { scale ->
            var optics by mutableStateOf(true)
            content(theme, scale, { optics }) { Box(Modifier.width(320.dp).fillMaxHeight()) { UsageScreen(deviceAmount(), {}, {}, contentPadding = PaddingValues(horizontal = 16.dp)) } }
            scroll("usage-metrics")
            val card = compose.onNodeWithTag("usage-metric:tokens").fetchSemanticsNode()
            assertTrue(card.boundsInRoot.width < 170 * LocalTestDensity)
            assertTrue(card.boundsInRoot.width > 0)
            listOf("tokens", "cost", "requests", "cache", "ttft", "speed", "errors").forEach { id ->
                assertSingleLine(compose.onNodeWithTag("usage-value:$id", useUnmergedTree = true))
                compose.onNodeWithTag("usage-spark:$id", useUnmergedTree = true).assertExists()
            }
            // Native Skia cannot replay the hardware backdrop graph; export the real software fallback.
            compose.runOnIdle { optics = false }; compose.waitForIdle()
            compose.onNodeWithTag("usage-list").performScrollToIndex(3)
            exportScreen("usage-$theme-320-font${(scale * 100).toInt()}.png")
            scroll("usage-trend-chart")
            compose.onNodeWithTag("usage-trend-chart").assertExists()
            if (scale == 1.8f) exportScreen("usage-$theme-trend-320-font180.png")
          }
        }
    }
    private val LocalTestDensity get() = compose.activity.resources.displayMetrics.density
    private fun exportScreen(name: String) {
        val dir = System.getenv("POCKET_USAGE_QA_DIR")?.let { java.io.File(it) } ?: return
        dir.mkdirs()
        var renderer = "captureToImage"
        val bitmap = runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }.getOrElse {
            renderer = "software-view-draw"
            lateinit var image: android.graphics.Bitmap
            compose.runOnIdle {
                val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
                image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(image))
            }
            image
        }
        java.io.File(dir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        java.io.File(dir, "$name.renderer.txt").writeText(renderer)

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
