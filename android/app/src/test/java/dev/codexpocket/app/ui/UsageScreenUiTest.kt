package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.LocalPocketHaptics
import dev.codexpocket.app.ui.kit.PRollingNumberMaxMillis
import dev.codexpocket.app.ui.kit.PocketHaptic
import dev.codexpocket.app.ui.kit.PocketHaptics
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Shared states for the usage screen tests: the wire fixture, a week of device-sized figures, and a fuller page. */
internal object UsageUiFixtures {
    val response: UsageResponse = UsageResponse.fromJson(usageV070Fixture())
    fun filter(): UsageFilter = UsageFilter.lastDays(7, "UTC", LocalDate.of(2026, 11, 10))
    fun ready(filter: UsageFilter = filter()) = UsageScreenState(filter, UsageSnapshot(filter, response, 1794268800), UsagePhase.Ready, canQuery = true, canPageRequests = true)
    /** The native ledger on a computer that offers both sources: tokens only, no amounts, outcomes or timings. */
    fun native(filter: UsageFilter = filter().withSource(UsageSourceKind.NativeCodex)) = UsageScreenState(filter,
        UsageSnapshot(filter, UsageResponse.fromJson(usageNativeFixtureJson(filter, requests = 1280)), 1794268800), UsagePhase.Ready, canQuery = true, canPageRequests = true,
        sources = listOf(UsageSourceKind.NativeCodex, UsageSourceKind.CcSwitch))

    private fun UsageTotals.weighted(weight: Double) = copy(requests = (requests.toDouble() * weight).toULong(), success = (success.toDouble() * weight).toULong(),
        errors = (errors.toDouble() * weight).toULong(),
        tokens = tokens.copy(freshInput = (tokens.freshInput.toDouble() * weight).toULong(), cacheRead = (tokens.cacheRead.toDouble() * weight).toULong(),
            cacheWrite = (tokens.cacheWrite.toDouble() * weight).toULong(), output = (tokens.output.toDouble() * weight).toULong(), total = (tokens.total.toDouble() * weight).toULong()),
        costs = costs.copy(total = costs.total.copy(knownUsd = java.math.BigDecimal(costs.total.knownUsd).multiply(java.math.BigDecimal.valueOf(weight)).setScale(4, java.math.RoundingMode.HALF_UP).toPlainString())),
        performance = performance.copy(ttft = performance.ttft.copy(averageMs = performance.ttft.averageMs?.times(.6 + weight)),
            outputSpeed = performance.outputSpeed.copy(tokensPerSecond = performance.outputSpeed.tokensPerSecond?.times(1.4 - weight))))

    /** A controlled seven-day week: every sparkline and bar must use exactly these daily values. */
    fun device(filter: UsageFilter = filter(), cost: String = "2112.28012345", requests: ULong = 12600u, sampledAt: Long = 1794268800): UsageScreenState {
        val data = response.data!!
        val totals = data.totals.copy(requests = requests, success = requests - 775u, errors = 775u,
            tokens = data.totals.tokens.copy(freshInput = 90_000_000u, cacheRead = 1_500_000_000u, cacheWrite = 52_000_000u, output = 7_700_000u, total = 1_649_700_000u),
            costs = data.totals.costs.copy(total = data.totals.costs.total.copy(knownUsd = cost)),
            performance = data.totals.performance.copy(ttft = data.totals.performance.ttft.copy(averageMs = 12440.0),
                outputSpeed = data.totals.performance.outputSpeed.copy(tokensPerSecond = 44.38)))
        val first = filter.startDate.atStartOfDay(java.time.ZoneId.of(filter.zoneId)).toEpochSecond()
        val trend = listOf(0.7, 1.0, 0.5, 1.2, 1.1, 1.3, 1.2).mapIndexed { i, weight ->
            val sample = totals.copy(requests = (1800 * weight).toULong(), errors = (100 * weight).toULong(),
                tokens = totals.tokens.copy(total = (235_000_000 * weight).toULong(), cacheRead = (200_000_000 * weight).toULong(),
                    freshInput = (13_000_000 * weight).toULong(), cacheWrite = (7_000_000 * weight).toULong(), output = (1_100_000 * weight).toULong()),
                costs = totals.costs.copy(total = totals.costs.total.copy(knownUsd = (300 * weight).toString())),
                performance = totals.performance.copy(ttft = totals.performance.ttft.copy(averageMs = 12440 * weight),
                    outputSpeed = totals.performance.outputSpeed.copy(tokensPerSecond = 44.38 * weight)))
            UsageTrendBucket(first + i * 86400L, first + (i + 1) * 86400L, sample)
        }
        val updated = response.copy(data = data.copy(totals = totals, trend = trend), cache = UsageCache("fresh", sampledAt, 0u, 60u, null))
        return ready(filter).copy(snapshot = UsageSnapshot(filter, updated, sampledAt))
    }

    /** [device] with several upstreams, models, conversations and requests, as a real week looks. */
    fun rich(filter: UsageFilter = filter(), sampledAt: Long = 1794268800): UsageScreenState {
        val base = device(filter, sampledAt = sampledAt)
        val data = base.snapshot!!.response.data!!
        val t = data.totals
        val providers = listOf(Triple("openai-main", "OpenAI 官方", .62), Triple("9b1f6c2e-77aa-4c0d-b1c3-5e2f8a441d07", "Azure 东亚中转", .27), Triple("backup", null, .11))
            .map { (id, name, w) -> UsageProviderGroup(id, "codex", name, t.weighted(w)) }
        val models = listOf("gpt-5.2-codex" to .55, "gpt-5.2" to .3, "gpt-5.1-codex-mini" to .12, "o4-mini-deep-research-2025-06-26" to .03).map { (m, w) -> UsageModelGroup(m, t.weighted(w)) }
        val sessions = listOf(Triple("0199a7c2-6f0e-7b61-a3c4-9d2e8f6b1a20", "重构用量页并补齐测试", .41), Triple("0199a7c2-7a11-7c02-b5d6-1e3f9a7c2b31", null, .33),
            Triple("0199a7c2-8b22-7d13-c6e7-2f4a0b8d3c42", "排查 SSH 通道在弱网下的重连", .26)).map { (id, title, w) -> UsageSessionGroup(id, title, t.weighted(w)) }
        val tokens = UsageTokens(18_400u, 212_000u, 9_600u, 3_150u, 243_150u, 0u)
        val requests = listOf(
            UsageRequestDetail("req_01JD3X8Q", sampledAt - 95, "openai-main", "OpenAI 官方", "gpt-5.2-codex", sessions[0].sessionId, sessions[0].title, 200, tokens, "0.4821", 1840u, 21650u, 61.2),
            UsageRequestDetail("req_01JD3X7M", sampledAt - 340, "9b1f6c2e-77aa-4c0d-b1c3-5e2f8a441d07", "Azure 东亚中转", "gpt-5.2", sessions[2].sessionId, sessions[2].title, 502, null, null, null, 30012u, null),
            UsageRequestDetail("req_01JD3X5C", sampledAt - 910, "backup", null, "gpt-5.1-codex-mini", sessions[1].sessionId, null, 200, tokens.copy(total = 58_200u), "0.0312", 620u, 4380u, 88.5),
            UsageRequestDetail("req_01JD3X2A", sampledAt - 1500, "openai-main", "OpenAI 官方", "gpt-5.2-codex", null, null, 200, tokens.copy(total = 1_204_000u), null, 2210u, 48110u, 52.7),
        )
        val page = UsageRequestDetailsPage(requests, 50, true, UsageRequestCursor(requests.last().createdAt, requests.last().requestId))
        val richer = data.copy(providerGroups = UsageGroupPage(providers, 0, 50, providers.size, false), modelGroups = UsageGroupPage(models, 0, 50, models.size, false),
            sessionGroups = UsageGroupPage(sessions, 0, 50, sessions.size, false), recentRequests = page)
        return base.copy(snapshot = base.snapshot!!.copy(response = base.snapshot!!.response.copy(data = richer)))
    }

    /** A successful read of a range in which nothing was called. */
    fun empty(filter: UsageFilter = filter(), sampledAt: Long = 1794268800): UsageScreenState {
        val data = response.data!!
        val none = data.totals.copy(requests = 0u, success = 0u, errors = 0u)
        val blank = response.copy(data = data.copy(totals = none, trend = emptyList(), providerGroups = UsageGroupPage(emptyList(), 0, 50, 0, false),
            modelGroups = UsageGroupPage(emptyList(), 0, 50, 0, false), sessionGroups = null, recentRequests = null), cache = UsageCache("fresh", sampledAt, 0u, 60u, null))
        return ready(filter).copy(snapshot = UsageSnapshot(filter, blank, sampledAt))
    }

    /** A source that saved neither amounts nor timings. */
    fun bare(filter: UsageFilter = filter()): UsageScreenState {
        val base = device(filter)
        val data = base.snapshot!!.response.data!!
        fun strip(t: UsageTotals) = t.copy(costs = t.costs.copy(total = t.costs.total.copy(knownUsd = "0", knownRows = 0u, zeroRows = 0u)),
            performance = t.performance.copy(ttft = UsageTimingMetric(), duration = UsageTimingMetric(), outputSpeed = UsageSpeedMetric()))
        val stripped = data.copy(totals = strip(data.totals), trend = data.trend.map { it.copy(totals = strip(it.totals)) })
        return base.copy(snapshot = base.snapshot!!.copy(response = base.snapshot!!.response.copy(data = stripped)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val filter = UsageUiFixtures.filter()
    private val response = UsageUiFixtures.response
    private val haptics = mutableListOf<PocketHaptic>()
    private lateinit var view: android.view.View
    private fun ready() = UsageUiFixtures.ready()
    private fun deviceAmount(cost: String = "2112.28012345", requests: ULong = 12600u) = UsageUiFixtures.device(cost = cost, requests = requests)
    private val states = listOf("usage-skeleton", "usage-empty", "usage-notice", "usage-metrics")

    private fun content(theme: String = "light", font: Float = 1f, motion: Boolean = true, block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalPocketGlass provides false, LocalPocketHaptics provides PocketHaptics { haptics += it },
                LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, motion),
                LocalDensity provides Density(density.density, font)) { PocketOverlayHost { block() } }
        } } }
        compose.waitForIdle()
    }
    private fun scroll(tag: String) = compose.onNodeWithTag("usage-list").performScrollToNode(hasTestTag(tag))
    private fun raw(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun shown(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private val dp get() = compose.activity.resources.displayMetrics.density

    private fun assertOnly(state: String) = states.forEach { tag -> assertEquals("$tag while expecting only $state", tag == state, shown(tag)) }

    /** [sized] texts fill or exactly wrap their box, so the layout's own overflow report is meaningful for them. */
    private fun assertSingleLine(node: SemanticsNodeInteraction, sized: Boolean = true) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals("${layout.layoutInput.text} must remain one line", 1, layout.lineCount)
        // A wrapped one-line label reports its box against the width it was offered; for those compare the drawn line with the box.
        if (sized) assertFalse("${layout.layoutInput.text} reports visual overflow", layout.hasVisualOverflow)
        assertFalse("${layout.layoutInput.text} must not clip: box ${layout.size}, text ${layout.multiParagraph.width}x${layout.multiParagraph.height}", layout.didOverflowHeight)
        assertFalse(layout.isLineEllipsized(0))
        assertTrue("${layout.layoutInput.text} is wider than its box", layout.getLineRight(0) <= layout.size.width + 1f)
    }

    /** Everything tappable that is currently composed inside the page offers a 48dp target. */
    private fun assertTargets() {
        val nodes = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("usage-screen"))).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        nodes.forEach { node ->
            val name = node.config.getOrNull(SemanticsProperties.TestTag) ?: node.config.toString()
            assertTrue("$name is ${node.size.width / dp}x${node.size.height / dp}dp", node.size.height / dp >= 47.9f && node.size.width / dp >= 47.9f)
        }
    }

    @Test fun unsupportedSchemaShowsOneRecoveryStateWithoutAnEmptyResult() {
        content { UsageScreen(UsageScreenState(filter, phase = UsagePhase.UnsupportedSchema, canQuery = true), {}, {}) }
        compose.onNodeWithTag("usage-status").assertExists()
        assertOnly("usage-notice")
        compose.onNodeWithText("暂不支持当前用量格式").assertIsDisplayed()
        compose.onNodeWithText("所选范围没有调用记录").assertDoesNotExist()
        compose.onNodeWithText("连接电脑后刷新", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("usage-retry").assertIsEnabled()
    }

    @Test fun firstLoadUsesOneLoadingSurfaceWithoutShowingAnEmptyResult() {
        content { UsageScreen(UsageScreenState(filter, phase = UsagePhase.Loading, refreshing = true, canQuery = true), {}, {}) }
        compose.onAllNodesWithTag("usage-status").assertCountEquals(1)
        assertOnly("usage-skeleton")
        compose.onNodeWithText("正在读取用量", substring = true).assertExists()
        compose.onNodeWithTag("usage-retry").assertDoesNotExist()
        compose.onNodeWithTag("usage-refresh").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正在刷新"))
    }

    @Test fun theFourStatesAreExclusiveAndAFailureNeverSitsBesideAnEmptyResult() {
        var state by mutableStateOf(UsageScreenState(filter, phase = UsagePhase.Loading, refreshing = true, canQuery = true))
        content { UsageScreen(state, {}, {}) }
        assertOnly("usage-skeleton")
        // Success with nothing recorded: the empty result, under the ordinary freshness line.
        compose.runOnIdle { state = UsageUiFixtures.empty() }
        assertOnly("usage-empty")
        compose.onNodeWithText("所选范围没有调用记录").assertIsDisplayed()
        compose.onNodeWithText("已更新", substring = true).assertExists()
        compose.onNodeWithTag("usage-retry").assertDoesNotExist()
        // Failure with nothing cached: the reason and the retry, and no empty result.
        compose.runOnIdle { state = UsageScreenState(filter, phase = UsagePhase.Failed, canQuery = true, failureCode = "backend_failed") }
        assertOnly("usage-notice")
        compose.onNodeWithText("用量读取失败").assertIsDisplayed()
        compose.onAllNodesWithTag("usage-retry").assertCountEquals(1)
        // Failure with earlier figures: the figures stay, with one status line that carries the retry.
        compose.runOnIdle { state = deviceAmount().copy(phase = UsagePhase.Failed, cached = true, failureCode = "backend_failed") }
        assertOnly("usage-metrics")
        compose.onAllNodesWithTag("usage-status").assertCountEquals(1)
        compose.onAllNodesWithTag("usage-retry").assertCountEquals(1)
        compose.onNodeWithText("用量读取失败").assertIsDisplayed()
        // An empty earlier read that then fails is still one status line above one empty result.
        compose.runOnIdle { state = UsageUiFixtures.empty().copy(phase = UsagePhase.Failed, cached = true) }
        assertOnly("usage-empty")
        compose.onAllNodesWithTag("usage-status").assertCountEquals(1)
        compose.onAllNodesWithTag("usage-retry").assertCountEquals(1)
    }

    @Test fun diagnosticsStayBehindOneControlInBothFailureForms() {
        var state by mutableStateOf(UsageScreenState(filter, phase = UsagePhase.UnsupportedSchema, canQuery = true, observedSchemaVersion = 31, failureCode = "unsupported_schema"))
        content { UsageScreen(state, {}, {}) }
        compose.onNodeWithText("CC Switch 数据格式：31").assertDoesNotExist()
        compose.onNodeWithTag("usage-diagnostics").performClick()
        compose.onNodeWithText("CC Switch 数据格式：31").assertIsDisplayed()
        compose.onNodeWithText("读取状态：unsupported_schema").assertIsDisplayed()
        compose.runOnIdle { state = deviceAmount().copy(phase = UsagePhase.Locked, cached = true, failureCode = "database_locked") }
        compose.onNodeWithText("读取状态：database_locked").assertDoesNotExist()
        compose.onNodeWithTag("usage-diagnostics").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("读取详情"))).performClick()
        compose.onNodeWithText("读取状态：database_locked").assertIsDisplayed()
    }

    @Test fun filterActionStaysVisibleAtNarrowWidthAndLargeFont() {
        var opened = false
        content(font = 1.8f) {
            Box(Modifier.width(320.dp)) { UsageFilterBar(filter.withModel("a-long-model-name"), { opened = true }, {}) }
        }
        compose.onNodeWithTag("usage-filters-button").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(opened) }
        assertSingleLine(compose.onNodeWithText("筛选 · 1", useUnmergedTree = true), sized = false)
        listOf("usage-date:1", "usage-date:7", "usage-date:30", "usage-filter-open").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
    }

    @Test fun appliedStatusIsVisibleWithoutOpeningFiltersAndTracksChanges() {
        var current by mutableStateOf(filter.withStatus(UsageStatusFilter.Error))
        var opened = 0
        content {
            Box(Modifier.width(320.dp)) {
                UsageFilterBar(current, { opened++ }, { current = it })
            }
        }
        compose.onNodeWithText("状态：失败").assertIsDisplayed()
        compose.onNodeWithText("筛选 · 1").assertIsDisplayed()
        compose.onNodeWithTag("usage-filters-button").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            current = current.withStatus(UsageStatusFilter.Success)
        }
        compose.onNodeWithText("状态：失败").assertDoesNotExist()
        compose.onNodeWithText("状态：成功").assertIsDisplayed()
        compose.runOnIdle { current = current.withStatus(UsageStatusFilter.All) }
        compose.onNodeWithText("状态：成功").assertDoesNotExist()
        compose.onNodeWithTag("usage-active-filters").assertDoesNotExist()
        compose.onNodeWithText("筛选").assertIsDisplayed()
    }

    @Test fun dateRangeIsOneTapWithATickAndTheRangeStaysInWords() {
        val changes = mutableListOf<UsageFilter>()
        var opened = 0
        content { UsageFilterBar(filter, { opened++ }, { changes += it }) }
        // The fixture's week is not "today", so it reads as a custom range.
        compose.onNodeWithTag("usage-filter-open").assertIsSelected()
        compose.onNodeWithText("11-04 – 11-10 · Codex · 代理请求").assertIsDisplayed()
        compose.onNodeWithTag("usage-date:30").assertIsNotSelected().performClick()
        compose.runOnIdle {
            val today = LocalDate.now(java.time.ZoneId.of("UTC"))
            assertEquals(today, changes.single().endDate); assertEquals(today.minusDays(29), changes.single().startDate)
            assertEquals(listOf(PocketHaptic.Tick), haptics)
        }
        compose.onNodeWithTag("usage-filter-open").performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(1, changes.size) }
    }

    @Test @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun deviceCostRemainsCompleteOnOneLineWithManagementPadding() {
        content("dark") { UsageScreen(deviceAmount(), {}, {}, contentPadding = PaddingValues(horizontal = 16.dp)) }
        scroll("usage-metrics")
        raw("usage-value:cost").assertTextEquals("$2,112.28")
        assertSingleLine(raw("usage-value:cost"))
        val costTrend = raw("usage-spark:cost").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertEquals("真实每日趋势：210, 300, 150, 360, 330, 390, 360", costTrend)
    }

    @Test fun readyStatusIsCompactAndDoesNotExposeCacheInternals() {
        content { UsageScreen(deviceAmount(), {}, {}) }
        val bounds = compose.onNodeWithTag("usage-status").fetchSemanticsNode().boundsInRoot
        assertTrue("Ready status should fit within 40dp, was ${bounds.height / dp}", bounds.height <= 40 * dp)
        compose.onNodeWithText("缓存有效期", substring = true).assertDoesNotExist()
        compose.onNodeWithText("电脑端缓存", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已更新 · 11-10 00:00").assertExists()
        compose.onNodeWithTag("usage-retry").assertDoesNotExist()
    }

    @Test fun failedAndStaleKeepRecoveryAndOriginalSnapshotTime() {
        var state by mutableStateOf(deviceAmount().copy(phase = UsagePhase.Failed, cached = true))
        var attempts = 0
        content { UsageScreen(state, { attempts++ }, {}) }
        compose.onNodeWithText("用量读取失败").assertExists()
        compose.onNodeWithTag("usage-retry").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, attempts); assertEquals(listOf(PocketHaptic.Tap), haptics)
            state = state.copy(phase = UsagePhase.Ready, cached = false, snapshot = state.snapshot!!.let {
                it.copy(response = it.response.copy(cache = it.response.cache!!.copy(state = "stale", ageSeconds = 90u)))
            })
        }
        compose.onNodeWithText("显示上次统计").assertExists()
        compose.onNodeWithText("缓存快照 · 2026-11-10 00:00:00 · UTC").assertExists()
        compose.onNodeWithText("已更新", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("usage-retry").assertIsEnabled()
        // While the retry is in flight the line says so and nothing can start a second read.
        compose.runOnIdle { state = state.copy(refreshing = true) }
        compose.onNodeWithTag("usage-retry").assertDoesNotExist()
        compose.onNodeWithTag("usage-refresh").assertIsNotEnabled()
        compose.onNodeWithText("正在刷新 · 上次更新 · 11-10 00:00").assertExists()
        assertOnly("usage-metrics")
    }

    @Test fun compactCostOpensExactSavedAmountWithUserFacingLabel() {
        content("light", 1.8f) {
            Box(Modifier.width(320.dp)) { UsageScreen(deviceAmount(cost = "123456789012.34"), {}, {}, contentPadding = PaddingValues(horizontal = 16.dp)) }
        }
        scroll("usage-metrics")
        // The exact amount cannot fit this card at this text size; the short form stands in and stays whole.
        raw("usage-value:cost").assertTextEquals("$123.5B")
        assertSingleLine(raw("usage-value:cost"))
        compose.onNodeWithTag("usage-metric:cost").performClick()
        compose.onNodeWithTag("usage-cost-breakdown").performScrollTo()
        compose.onNodeWithText("费用合计").assertExists()
        assertSingleLine(compose.onNodeWithText("USD 123456789012.34"))
        compose.onNodeWithText("最终 total", substring = true).assertDoesNotExist()
    }

    @Test fun disconnectedHasNoInventedMetricsAndRefreshIsDisabled() {
        content { UsageScreen(UsageScreenState(filter), {}, {}) }
        compose.onNodeWithText("用量尚未接入").assertExists()
        compose.onNodeWithTag("usage-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("usage-retry").assertDoesNotExist()
        assertOnly("usage-notice")
    }

    // Replaces the seven-tile KPI grid: the same seven readings, now as two pairs plus the performance pair.
    @Test fun overviewPairsCostWithCallsAndTokensWithSuccessAndKeepsEveryReadingAndSparkline() {
        content { UsageScreen(ready(), {}, {}) }
        scroll("usage-metrics")
        val cost = compose.onNodeWithTag("usage-metric:cost").fetchSemanticsNode().boundsInRoot
        val calls = compose.onNodeWithTag("usage-metric:requests").fetchSemanticsNode().boundsInRoot
        assertEquals(cost.top, calls.top, 1f); assertEquals(cost.height, calls.height, 1f)
        assertTrue(calls.left >= cost.right)
        val tokens = compose.onNodeWithTag("usage-metric:tokens").fetchSemanticsNode().boundsInRoot
        val success = compose.onNodeWithTag("usage-metric:errors").fetchSemanticsNode().boundsInRoot
        assertEquals(tokens.top, success.top, 1f); assertEquals(tokens.height, success.height, 1f)
        assertTrue(tokens.top >= cost.bottom && success.left >= tokens.right && tokens.width > success.width)
        // Equal heights must never cost a tile its last line: every part ends inside its card.
        listOf("usage-metric:cache" to tokens, "usage-spark:errors" to success, "usage-value:errors" to success).forEach { (tag, card) ->
            val part = raw(tag).fetchSemanticsNode()
            assertTrue("$tag is cut by its card", part.size.height > 0 && part.positionInRoot.y + part.size.height <= card.bottom - 8 * dp)
        }
        raw("usage-value:requests").assertTextEquals("5")
        raw("usage-value:tokens").assertTextEquals("330")
        raw("usage-value:success").assertTextEquals("80.0%")
        raw("usage-value:errors").assertTextEquals("1")
        raw("usage-value:cache").assertTextEquals("15.4%")
        raw("usage-token-mix").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("令牌构成：缓存读 20，输入 100，缓存写 10，输出 200")))
        scroll("usage-performance-tiles")
        raw("usage-value:ttft").assertTextEquals("150ms")
        raw("usage-value:speed").assertTextEquals("100")
        // Each of the seven readings is still its own reachable node with a real daily series beside it.
        listOf("tokens", "cost", "requests", "cache", "ttft", "speed", "errors").forEach { id ->
            scroll("usage-metric:$id")
            compose.onNodeWithTag("usage-metric:$id").assertIsDisplayed()
            assertTrue(raw("usage-spark:$id").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single().startsWith("真实每日趋势："))
            assertSingleLine(raw("usage-value:$id"))
        }
    }

    @Test fun largerTextStacksTheTilesRatherThanShorteningAnExactFigure() {
        content(font = 1.3f) { UsageScreen(deviceAmount(cost = "21112.28012345"), {}, {}, contentPadding = PaddingValues(horizontal = 16.dp)) }
        scroll("usage-metrics")
        raw("usage-value:cost").assertTextEquals("$21,112.28")
        raw("usage-value:requests").assertTextEquals("12,600")
        listOf("cost", "requests").forEach { assertSingleLine(raw("usage-value:$it")) }
        val cost = compose.onNodeWithTag("usage-metric:cost").fetchSemanticsNode()
        val calls = compose.onNodeWithTag("usage-metric:requests").fetchSemanticsNode()
        assertTrue("the tiles stack", calls.positionInRoot.y >= cost.positionInRoot.y + cost.size.height && calls.size.width == cost.size.width)
    }

    @Test fun figuresTheSourceDidNotRecordAreOmittedInsteadOfShownEmpty() {
        content { UsageScreen(UsageUiFixtures.bare(), {}, {}) }
        compose.onNodeWithTag("usage-metric:requests").assertExists()
        compose.onNodeWithTag("usage-metric:cost").assertDoesNotExist()
        compose.onNodeWithTag("usage-performance-tiles").assertDoesNotExist()
        scroll("usage-trend")
        listOf("Cost", "Latency", "Speed").forEach { compose.onNodeWithTag("usage-trend:$it").assertDoesNotExist() }
        compose.onNodeWithTag("usage-trend:Tokens").assertIsSelected()
        compose.onNodeWithTag("usage-trend:Requests").assertExists()
        // With no amount to rank by, the breakdown ranks by tokens and offers no cost switch.
        scroll("usage-provider:0")
        compose.onNodeWithTag("usage-provider-cost").assertDoesNotExist()
        raw("usage-provider:0:value").assertTextEquals("330")
        compose.onNodeWithText("—").assertDoesNotExist()
    }

    @Test fun refreshKeepsSameSnapshotButFilterChangeHidesItImmediately() {
        var state by mutableStateOf(ready())
        var refresh = 0
        content { UsageScreen(state, { refresh++ }, {}) }
        compose.onNodeWithTag("usage-refresh").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("刷新"))).performClick()
        compose.runOnIdle { assertEquals(1, refresh); assertEquals(listOf(PocketHaptic.Tap), haptics); state = state.copy(refreshing = true, cached = true) }
        assertOnly("usage-metrics")
        compose.onNodeWithText("正在刷新", substring = true).assertExists()
        compose.onNodeWithTag("usage-refresh").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(filter = filter.withModel("other"), phase = UsagePhase.Loading) }
        assertOnly("usage-skeleton")
    }

    @Test fun offlineSnapshotIsExplicitAndNeverReportedAsCurrent() {
        content { UsageScreen(ready().copy(phase = UsagePhase.Offline, canQuery = false, cached = true), {}, {}) }
        compose.onNodeWithText("电脑已离线").assertExists()
        compose.onNodeWithText("缓存快照 · 2026-11-10 00:00:00 · UTC").assertExists()
        compose.onNodeWithText("已更新", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("usage-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("usage-retry").assertDoesNotExist()
    }

    @Test fun pagingCallbacksKeepProviderAndModelOffsetsIndependent() {
        val changes = mutableListOf<UsageFilter>()
        content { UsageScreen(ready(), {}, { changes += it }) }
        scroll("usage-provider-next")
        compose.onNodeWithText("1–1 / 51").assertExists()
        compose.onNodeWithTag("usage-provider-prev").assertIsNotEnabled()
        compose.onNodeWithTag("usage-provider-next").performClick()
        compose.runOnIdle {
            assertEquals(50, changes.single().providerPage.offset)
            assertEquals(0, changes.single().modelPage.offset)
        }
        // The model grouping has a single page: no paging controls at all.
        compose.onNodeWithTag("usage-group:model").performClick()
        compose.onNodeWithTag("usage-model-next").assertDoesNotExist()
    }

    @Test fun groupingSwitchShowsExactlyOneBreakdownAndSwitchesIt() {
        val changes = mutableListOf<UsageFilter>()
        content { UsageScreen(UsageUiFixtures.rich(), {}, { changes += it }) }
        scroll("usage-grouping")
        fun only(key: String) = listOf("provider", "model", "session").forEach { other ->
            assertEquals("usage-$other-groups", other == key, shown("usage-$other-groups"))
            assertEquals("usage-$other:0", other == key, shown("usage-$other:0"))
        }
        only("provider")
        compose.onNodeWithTag("usage-group:provider").assertIsSelected()
        compose.onNodeWithText("OpenAI 官方").assertExists()
        compose.onNodeWithTag("usage-group:model").performClick().assertIsSelected()
        only("model")
        compose.onNodeWithText("OpenAI 官方").assertDoesNotExist()
        compose.onNodeWithTag("usage-model:0").assertTextContains("gpt-5.2-codex")
        compose.runOnIdle { assertEquals(listOf(PocketHaptic.Tick), haptics) }
        // Cost or tokens is remembered per grouping, as the three separate lists did.
        raw("usage-model:0:value").assertTextEquals("$1,161.75")
        compose.onNodeWithTag("usage-model-tokens").performClick().assertIsSelected()
        raw("usage-model:0:value").assertTextEquals("907.3M")
        compose.onNodeWithTag("usage-group:session").performClick()
        only("session")
        compose.onNodeWithTag("usage-session-cost").assertIsSelected()
        compose.onNodeWithTag("usage-group:model").performClick()
        compose.onNodeWithTag("usage-model-tokens").assertIsSelected()
        // A row still narrows the whole screen to itself.
        scroll("usage-model:1")
        compose.onNodeWithTag("usage-model:1").performClick()
        compose.runOnIdle { assertEquals("gpt-5.2", changes.single().model) }
    }

    @Test fun fiveTrendModesAndTouchDetailsExposeMeasuredValuesInDarkLargeFont() {
        content("dark", 1.6f) { UsageScreen(ready(), {}, {}) }
        scroll("usage-trend-chart")
        val text = compose.onNodeWithTag("usage-trend-chart").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(text.contains("2026-11-10")); assertTrue(text.contains("请求 5"))
        listOf("Cost", "Tokens", "Requests", "Latency", "Speed").forEach { mode ->
            compose.onNodeWithTag("usage-trend:$mode").performClick().assertIsSelected()
            val summary = compose.onNodeWithTag("usage-trend-chart").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
            assertTrue(summary, summary.startsWith(UsageTrendMetric.valueOf(mode).label + "趋势"))
        }
        scroll("usage-trend-chart")
        compose.onNodeWithTag("usage-trend-chart").performTouchInput { click(center) }
        compose.onNodeWithTag("usage-insight-detail").assertExists()
        compose.onNodeWithTag("usage-performance").performScrollTo()
        compose.onNodeWithText("100 token/s").assertExists()
    }

    @Test fun trendDragReadsEachDayInPlaceWithOneTickPerDayAndSaysWhichDayIsFocused() {
        content { UsageScreen(deviceAmount(), {}, {}) }
        scroll("usage-trend-chart")
        val chart = compose.onNodeWithTag("usage-trend-chart")
        fun state() = chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals("未选择日期", state())
        compose.onNodeWithTag("usage-trend-detail").assertDoesNotExist()
        haptics.clear()
        chart.performTouchInput { swipe(Offset(width * .03f, centerY), Offset(width * .97f, centerY), durationMillis = 600) }
        compose.runOnIdle {
            // Seven days: the drag starts inside the first or second and ends on the last.
            assertTrue("ticks: $haptics", haptics.size in 6..7 && haptics.all { it == PocketHaptic.Tick })
        }
        assertEquals("已选 2026-11-10：360 USD，请求 2,160", state())
        raw("usage-trend-focus").assertExists()
        compose.onNodeWithText("$360.00").assertIsDisplayed()
        compose.onNodeWithText("2026-11-10 · 2,160 次").assertIsDisplayed()
        // Reading in place opens nothing; the focused day's sheet is one tap away.
        compose.onNodeWithTag("usage-insight-detail").assertDoesNotExist()
        haptics.clear()
        chart.performTouchInput { swipe(Offset(width * .97f, centerY), Offset(width * .5f, centerY), durationMillis = 300) }
        assertEquals("已选 2026-11-07：360 USD，请求 2,160", state())
        compose.runOnIdle { assertEquals(listOf(PocketHaptic.Tick, PocketHaptic.Tick, PocketHaptic.Tick), haptics) }
        // Assistive technology steps through days with the same control.
        chart.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertEquals("已选 2026-11-05：300 USD，请求 1,800", state())
        compose.onNodeWithTag("usage-trend-detail").performClick()
        compose.onNodeWithTag("usage-insight-detail").assertExists()
        compose.onNodeWithText("2026-11-05").assertExists()
    }

    @Test fun rollingFiguresShowTheNewValueAtOnceWithoutMotionAndCountToItWithMotion() {
        var state by mutableStateOf(deviceAmount())
        var motion by mutableStateOf(false)
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlass provides false, LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, motion)) {
                PocketOverlayHost { UsageScreen(state, {}, {}) }
            }
        } } }
        compose.waitForIdle()
        raw("usage-value:cost").assertTextEquals("$2,112.28")
        raw("usage-value:requests").assertTextEquals("12,600")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = deviceAmount(cost = "2883.61", requests = 18300u) }
        // Two frames: one to recompose with the new figures, none to animate.
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        raw("usage-value:cost").assertTextEquals("$2,883.61")
        raw("usage-value:requests").assertTextEquals("18,300")
        raw("usage-value:success").assertTextEquals("95.8%")
        // With motion the same change counts up: part-way after a few frames, exact within the limit.
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { motion = true }
        compose.waitForIdle()
        raw("usage-value:cost").assertTextEquals("$2,883.61")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = deviceAmount(cost = "4000", requests = 20000u) }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.mainClock.advanceTimeBy(48)
        fun amount() = raw("usage-value:cost").fetchSemanticsNode().config[SemanticsProperties.Text].single().text.filter { it.isDigit() || it == '.' }.toDouble()
        val early = amount()
        assertTrue("counting from 2883.61 to 4000, was $early", early > 2883.61 && early < 4000.0)
        compose.mainClock.advanceTimeBy(64)
        assertTrue("moves toward the new value", amount() > early)
        compose.mainClock.advanceTimeBy(PRollingNumberMaxMillis.toLong())
        raw("usage-value:cost").assertTextEquals("$4,000.00")
        raw("usage-value:requests").assertTextEquals("20,000")
        compose.mainClock.autoAdvance = true
    }

    /** What is drawn inside [tag], as pixels. */
    private fun pixels(tag: String): IntArray {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val width = bounds.width.toInt(); val height = bounds.height.toInt()
            IntArray(width * height).also { bitmap.getPixels(it, 0, width, bounds.left.toInt(), bounds.top.toInt(), width, height); bitmap.recycle() }
        }
    }

    @Test fun chartsRiseOnceWhenFiguresFirstArriveThenStayStillOnScrollAndWithoutMotion() {
        var state by mutableStateOf(UsageScreenState(filter, phase = UsagePhase.Loading, refreshing = true, canQuery = true))
        content { UsageScreen(state, {}, {}) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = deviceAmount() }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val rising = pixels("usage-metrics")
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        val risen = pixels("usage-metrics")
        assertFalse("sparklines and the token bar are still rising two frames in", rising.contentEquals(risen))
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        assertArrayEquals("risen within 300 ms and at rest afterwards", risen, pixels("usage-metrics"))
        // Scrolled away and back, the cards are simply there: no frame is needed to finish anything.
        compose.mainClock.autoAdvance = true
        scroll("usage-coverage-open")
        compose.onNodeWithTag("usage-list").performScrollToIndex(0)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        assertArrayEquals("no replay after scrolling", risen, pixels("usage-metrics"))
        compose.mainClock.autoAdvance = true
        // Without motion the first figures are drawn complete in the frame they arrive.
        var still by mutableStateOf(UsageScreenState(filter, phase = UsagePhase.Loading, refreshing = true, canQuery = true))
        content(motion = false) { UsageScreen(still, {}, {}) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { still = deviceAmount() }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertArrayEquals("complete at once without motion", risen, pixels("usage-metrics"))
        compose.mainClock.autoAdvance = true
    }

    @Test fun aRiseCutShortByAFilterChangeFinishesWhenFiguresReturn() {
        content(motion = false) { UsageScreen(deviceAmount(), {}, {}) }
        val risen = pixels("usage-metrics")
        val loading = UsageScreenState(filter.withModel("other"), phase = UsagePhase.Loading, refreshing = true, canQuery = true)
        var state by mutableStateOf(loading)
        content { UsageScreen(state, {}, {}) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = deviceAmount() }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.mainClock.advanceTimeBy(96)
        // The figures leave a third of the way up and come back: they must not stay a third of the way up.
        compose.runOnIdle { state = loading }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { state = deviceAmount() }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        assertArrayEquals("risen after the interruption", risen, pixels("usage-metrics"))
        compose.mainClock.autoAdvance = true
    }

    @Test fun sessionShortIdFilterAndRequestDetailsKeepUnknownCostVisible() {
        val changes = mutableListOf<UsageFilter>()
        content { UsageScreen(ready(), {}, { changes += it }) }
        scroll("usage-grouping")
        compose.onNodeWithTag("usage-group:session").performClick()
        scroll("usage-session:0")
        compose.onNodeWithTag("usage-session:0").assertTextContains("会话 abcdefgh…mnop").performClick()
        compose.runOnIdle { assertEquals("abcdefghijklmnop", changes.single().sessionId) }
        scroll("usage-request:0")
        compose.onNodeWithTag("usage-request:0").assertTextContains("费用未记录", substring = true).performClick()
        compose.onNodeWithTag("usage-request-detail").assertExists()
        compose.onNodeWithTag("usage-request-field:cost").performScrollTo().assertTextEquals("费用", "未记录")
        compose.onNodeWithTag("usage-request-field:session").performScrollTo().assertTextEquals("对话 ID", "abcdefghijklmnop")
        compose.onNodeWithTag("usage-request-field:id").assertTextEquals("请求 ID", "request-1")
    }

    @Test fun requestPagingIsExplicitAndDisabledWithoutParentCallback() {
        content { UsageScreen(ready(), {}, {}) }
        scroll("usage-requests-next")
        compose.onNodeWithTag("usage-requests-next").assertIsNotEnabled()
        var cursor: UsageRequestCursor? = null
        var state by mutableStateOf(ready())
        content { UsageScreen(state, {}, {}, onRequests = { cursor = it }) }
        scroll("usage-requests-next")
        compose.onNodeWithTag("usage-requests-next").assertTextEquals("加载更多请求").performClick()
        compose.runOnIdle { assertEquals(response.data!!.recentRequests!!.nextCursor, cursor); state = state.copy(requestLoading = true) }
        compose.onNodeWithTag("usage-requests-next").assertTextEquals("正在读取…").assertIsNotEnabled()
    }

    @Test fun coverageStaysOneTapAwayBelowTheRequests() {
        content { UsageScreen(ready(), {}, {}) }
        scroll("usage-coverage-open")
        compose.onNodeWithTag("usage-coverage-open").performClick()
        compose.onNodeWithTag("usage-coverage").assertExists()
        compose.onNodeWithTag("usage-coverage-field:matched").assertTextEquals("匹配记录", "5 条")
        compose.onNodeWithTag("usage-coverage-field:rollups").assertTextEquals("日汇总表", "存在，未计入")
        compose.onNodeWithText("已记录为 0 的费用有 1 条，定价状态未知；无法确认免费条数。").assertExists()
    }

    // 320dp wide at 1.4x text: nothing numeric is cut, every control keeps a 48dp target, nothing leaves the screen.
    @Test @Config(qualifiers = "w320dp-h800dp-xxhdpi")
    fun narrowWidthWithLargeTextKeepsEveryNumberWholeAndEveryTargetFortyEightDp() {
        content("dark", 1.4f, motion = false) { UsageScreen(UsageUiFixtures.rich(), {}, {}, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) }
        val screen = compose.onNodeWithTag("usage-screen").fetchSemanticsNode().boundsInRoot
        assertEquals(320f, screen.width / dp, .5f)
        fun whole(tag: String) { assertSingleLine(raw(tag)); raw(tag).fetchSemanticsNode().boundsInRoot.let { assertTrue("$tag leaves the screen", it.left >= screen.left && it.right <= screen.right) } }
        assertTargets()
        scroll("usage-metrics")
        listOf("cost", "requests", "tokens", "cache").forEach { whole("usage-value:$it") }
        raw("usage-value:cost").assertTextEquals("$2,112.28")
        raw("usage-value:requests").assertTextEquals("12,600")
        assertTargets()
        scroll("usage-metric:errors")
        listOf("success", "errors").forEach { whole("usage-value:$it") }
        scroll("usage-trend")
        assertTargets()
        listOf("Cost", "Tokens", "Requests", "Latency", "Speed").forEach { assertSingleLine(compose.onNode(hasText(UsageTrendMetric.valueOf(it).label) and hasParent(hasTestTag("usage-trend:$it")), useUnmergedTree = true), sized = false) }
        scroll("usage-trend-chart")
        compose.onNodeWithTag("usage-trend-chart").performTouchInput { swipe(Offset(width * .1f, centerY), Offset(width * .9f, centerY), durationMillis = 200) }
        assertSingleLine(compose.onNodeWithText("$360.00", useUnmergedTree = true), sized = false)
        assertTargets()
        scroll("usage-performance-tiles")
        listOf("ttft", "speed").forEach { whole("usage-value:$it") }
        scroll("usage-grouping")
        assertTargets()
        listOf("provider", "model", "session").forEach { key ->
            compose.onNodeWithTag("usage-group:$key").performClick()
            repeat(3) { i -> scroll("usage-$key:$i"); whole("usage-$key:$i:value") }
            assertTargets()
        }
        scroll("usage-request:3")
        assertTargets()
        scroll("usage-coverage-open")
        assertTargets()
    }

    // Between and beyond the comfortable sizes the controls change shape rather than shrink a target or cut a label.
    @Test fun controlsKeepFortyEightDpTargetsAndWholeLabelsAtEveryWidthAndTextSize() {
        val narrowed = UsageUiFixtures.rich(filter.withStatus(UsageStatusFilter.Success).withModel("gpt-5.2-codex"))
        fun label(text: String, parent: String) = compose.onNode(hasText(text) and hasParent(hasTestTag(parent)), useUnmergedTree = true)
        listOf(320, 360).forEach { width ->
          listOf(1f, 1.15f, 1.5f, 2f).forEach { scale ->
            content(font = scale, motion = false) { Box(Modifier.width(width.dp).fillMaxHeight()) { UsageScreen(narrowed, {}, {}, contentPadding = PaddingValues(horizontal = 16.dp)) } }
            compose.onNodeWithTag("usage-list").performScrollToIndex(0)
            listOf("这台电脑", "CC Switch", "筛选 · 2").forEach { assertSingleLine(compose.onNodeWithText(it, useUnmergedTree = true), sized = false) }
            listOf("今天" to "usage-date:1", "7 天" to "usage-date:7", "30 天" to "usage-date:30", "自定义" to "usage-filter-open").forEach { (text, tag) ->
                assertSingleLine(label(text, tag), sized = false)
            }
            assertTargets()
            scroll("usage-metric:cache")
            assertSingleLine(compose.onNodeWithText("缓存命中率", useUnmergedTree = true), sized = false)
            scroll("usage-trend")
            UsageTrendMetric.entries.forEach { assertSingleLine(label(it.label, "usage-trend:${it.name}"), sized = false) }
            assertTargets()
            scroll("usage-grouping")
            UsageGrouping.entries.forEach { assertSingleLine(label(it.label, "usage-group:${it.key}"), sized = false) }
            listOf("费用" to "usage-provider-cost", "令牌" to "usage-provider-tokens").forEach { (text, tag) -> assertSingleLine(label(text, tag), sized = false) }
            assertTargets()
          }
        }
    }

    // Until the backend announces a second source or a choice of location, the capsule states both and only refresh can be pressed.
    @Test fun sourceCapsuleStatesTheOnlySourceAndOffersNoControlThatDoesNothing() {
        content { UsageScreen(ready(), {}, {}) }
        compose.onNodeWithTag("usage-source:cc_switch").assertIsSelected().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithContentDescription("执行位置：这台电脑").assertIsDisplayed().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("原生", substring = true).assertDoesNotExist()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("usage-source"))).assertCountEquals(1)
        compose.onNodeWithTag("usage-refresh").assertHasClickAction().assertIsEnabled()
    }

    @Test fun sourceCapsuleNamesTheSelectedLocationAndSwitchesItWhereTheAppCan() {
        var opened = 0
        listOf(1f, 2f).forEach { scale ->
            content(font = scale) {
                CompositionLocalProvider(LocalExecutionLocationPicker provides { opened++ },
                    LocalExecutionLocation provides dev.codexpocket.wire.ExecutionTarget("ssh", dev.codexpocket.wire.ExecutionTargetKind.LinuxSsh, "开发服务器")) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { UsageScreen(ready(), {}, {}) }
                }
            }
            compose.onNodeWithText("这台电脑").assertDoesNotExist()
            val location = compose.onNodeWithTag("usage-location").assertIsDisplayed().assertHasClickAction()
                .assert(hasContentDescription("执行位置：开发服务器"))
            val bounds = location.fetchSemanticsNode().boundsInRoot
            assertTrue("${bounds.height / dp}dp at ${scale}x", bounds.height >= 48 * dp - 1f && bounds.width >= 48 * dp - 1f)
            location.performClick()
        }
        assertEquals(2, opened)
        assertTrue(PocketHaptic.Tap in haptics)
    }

    @Test fun nativeSourceSaysCostIsNotProvidedAndDrawsNothingItDoesNotRecord() {
        var chosen: UsageFilter? = null
        content { UsageScreen(UsageUiFixtures.native(), {}, { chosen = it }) }
        compose.onNodeWithTag("usage-source:native_codex").assertIsSelected()
        compose.onNodeWithTag("usage-scope").assertTextContains("原生会话", substring = true)
        scroll("usage-metrics")
        raw("usage-value:cost").assertTextEquals("未提供费用")
        raw("usage-value:requests").assertTextEquals("1,280")
        compose.onNodeWithTag("usage-metric:tokens").assertExists()
        // No outcome, amount or timing is recorded, so no success rate, cost trend or performance is drawn from zeros.
        listOf("usage-metric:errors", "usage-metric:ttft", "usage-metric:speed", "usage-trend:Cost", "usage-trend:Latency", "usage-trend:Speed")
            .forEach { compose.onAllNodesWithTag(it).assertCountEquals(0) }
        compose.onAllNodesWithText("成功", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("$0", substring = true).assertCountEquals(0)
        scroll("usage-request:0")
        compose.onNodeWithTag("usage-request:0").assert(hasAnyDescendant(hasText("200")).not()).assert(hasAnyDescendant(hasText("费用未记录", substring = true)).not())
        scroll("usage-footer")
        compose.onNodeWithTag("usage-footer").assertTextContains("未提供费用", substring = true).assertTextContains("不与 CC Switch 合计", substring = true)
        scroll("usage-metrics")
        compose.onNodeWithTag("usage-metric:cost").performClick()
        compose.onNodeWithTag("usage-cost-not-provided").assertExists()
        compose.onAllNodesWithTag("usage-cost-breakdown").assertCountEquals(0)
        compose.onAllNodesWithTag("usage-performance").assertCountEquals(0)
        compose.onAllNodesWithTag("usage-insight-field:success").assertCountEquals(0)
    }

    @Test fun choosingTheOtherSourceAsksForItsFiguresAndTheNativeFilterSheetOffersOnlyWhatApplies() {
        var chosen: UsageFilter? = null
        listOf(1f, 2f).forEach { scale ->
            content(font = scale) { Box(Modifier.width(320.dp).fillMaxHeight()) { UsageScreen(UsageUiFixtures.native(), {}, { chosen = it }) } }
            listOf("usage-source:native_codex", "usage-source:cc_switch", "usage-refresh").forEach { tag ->
                val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("$tag ${bounds.height / dp}dp at ${scale}x", bounds.height >= 48 * dp - 1f && bounds.width >= 48 * dp - 1f)
            }
            chosen = null
            compose.onNodeWithTag("usage-source:cc_switch").assertIsNotSelected().performClick()
            assertEquals(UsageSourceKind.CcSwitch, chosen!!.source)
            assertEquals(UsageUiFixtures.native().filter.startDate, chosen!!.startDate)
        }
        content { UsageScreen(UsageUiFixtures.native(), {}, { chosen = it }) }
        compose.onNodeWithTag("usage-filters-button").performClick()
        listOf("usage-filter-app:claude", "usage-filter-source:codex_session", "usage-filter-status:error").forEach { compose.onAllNodesWithTag(it).assertCountEquals(0) }
        compose.onNodeWithTag("usage-filter-model").performScrollTo().performTextInput("gpt-5.2-codex")
        compose.onNodeWithTag("usage-filter-apply").performClick()
        assertEquals(UsageSourceKind.NativeCodex, chosen!!.source)
        assertEquals("gpt-5.2-codex", chosen!!.model)
    }

    @Test fun narrowLargeFontLightAndDarkRemainInsideCards() {
        listOf("light", "dark").forEach { theme ->
          listOf(1f, 1.8f, 2f).forEach { scale ->
            content(theme, scale) { Box(Modifier.width(320.dp).fillMaxHeight()) { UsageScreen(deviceAmount(), {}, {}, contentPadding = PaddingValues(horizontal = 16.dp)) } }
            scroll("usage-metrics")
            val card = compose.onNodeWithTag("usage-metric:tokens").fetchSemanticsNode()
            assertTrue(card.boundsInRoot.width <= 288 * dp + 1f)
            assertTrue(card.boundsInRoot.width > 0)
            val cache = raw("usage-metric:cache").fetchSemanticsNode()
            assertTrue("$theme $scale: the token card cuts its last row", cache.positionInRoot.y + cache.size.height <= card.positionInRoot.y + card.size.height - 8 * dp)
            listOf("cost", "requests", "tokens", "cache", "success", "errors").forEach { id ->
                scroll(if (id == "success") "usage-metric:errors" else "usage-metric:$id")
                assertSingleLine(raw("usage-value:$id"))
            }
            scroll("usage-performance-tiles")
            listOf("ttft", "speed").forEach { id -> scroll("usage-metric:$id"); assertSingleLine(raw("usage-value:$id")); raw("usage-spark:$id").assertExists() }
            scroll("usage-trend-chart")
            compose.onNodeWithTag("usage-trend-chart").assertExists()
          }
        }
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

    @Test fun filterSheetKeepsAppSourceAndQuickRangeChoices() {
        val changes = mutableListOf<UsageFilter>()
        content { UsageScreen(ready(), {}, { changes += it }) }
        compose.onNodeWithTag("usage-filters-button").performClick()
        compose.onNodeWithTag("usage-filter-sheet").assertExists()
        compose.onNodeWithTag("usage-filter-days:7").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("usage-filter-source:codex_session").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("usage-filter-app:claude").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("usage-filter-source:codex_session").assertIsNotEnabled()
        compose.onNodeWithTag("usage-filter-source:proxy").assertIsSelected()
        compose.onNodeWithTag("usage-filter-apply").performClick()
        compose.runOnIdle {
            val today = LocalDate.now(java.time.ZoneId.of("UTC"))
            assertEquals("claude", changes.single().appType); assertEquals(today.minusDays(6), changes.single().startDate); assertEquals(today, changes.single().endDate)
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
        compose.onNodeWithText("调用统计").assertIsDisplayed()
        compose.onNodeWithTag("usage-source").assertIsDisplayed()
        compose.onNodeWithText("CC Switch").assertIsDisplayed()
    }
}
