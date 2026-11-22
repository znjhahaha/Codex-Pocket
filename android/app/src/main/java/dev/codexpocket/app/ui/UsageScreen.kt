package dev.codexpocket.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*

private class UsageGroupEntry(val key: String, val title: String, val identity: String?, val totals: UsageTotals, val narrowed: UsageFilter)

/**
 * Charts rise once, the first time figures arrive, and stay risen: scrolling a card back into view
 * or returning to the page never replays it. With motion off there is no clock at all.
 */
@Composable
private fun rememberUsageGrowth(hasData: Boolean): () -> Float {
    if (!rememberPocketMotion()) return { 1f }
    var grown by rememberSaveable { mutableStateOf(false) }
    val rise = remember { Animatable(if (grown) 1f else 0f) }
    LaunchedEffect(hasData) {
        if (hasData && rise.value < 1f) { grown = true; rise.animateTo(1f, tween(280, easing = PocketMotion.EaseOut)) }
    }
    return remember(rise) { { rise.value } }
}

/** Pure projection and callbacks; parent wiring owns connection freshness and navigation. */
@Composable
fun UsageScreen(
    state: UsageScreenState,
    onRefresh: () -> Unit,
    onFilter: (UsageFilter) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onRequests: ((UsageRequestCursor) -> Unit)? = null,
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var trendMetric by rememberSaveable { mutableStateOf(UsageTrendMetric.Cost) }
    var grouping by rememberSaveable { mutableStateOf(UsageGrouping.Provider) }
    var insight by remember { mutableStateOf<Pair<String, UsageTotals>?>(null) }
    var request by remember { mutableStateOf<UsageRequestDetail?>(null) }
    var providerTokens by rememberSaveable { mutableStateOf(false) }
    var modelTokens by rememberSaveable { mutableStateOf(false) }
    var sessionTokens by rememberSaveable { mutableStateOf(false) }
    var coverageOpen by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.visibleSnapshot
    val data = snapshot?.response?.data
    val f = state.filter
    val native = f.source == UsageSourceKind.NativeCodex
    val can = snapshot?.response?.capabilities ?: UsageCapabilities()
    val c = Pocket.colors
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val start = contentPadding.calculateStartPadding(direction).coerceAtLeast(16.dp)
    val end = contentPadding.calculateEndPadding(direction).coerceAtLeast(16.dp)
    val top = contentPadding.calculateTopPadding()
    var capsule by remember { mutableStateOf(52.dp) }
    val grow = rememberUsageGrowth(data != null && data.totals.requests > 0uL)
    val busy = state.refreshing || state.phase == UsagePhase.Loading
    val background = c.bg
    val backdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
    Box(modifier.fillMaxSize().background(background)) {
      Box(Modifier.fillMaxSize().testTag("usage-screen")) {
        LazyColumn(Modifier.fillMaxSize().layerBackdrop(backdrop).testTag("usage-list"),
            contentPadding = PaddingValues(start, top + capsule + 10.dp, end, contentPadding.calculateBottomPadding())) {
            item(key = "filters") { UsageFilterBar(f, { filtersOpen = true }, onFilter) }
            // Exactly one of: outline while first reading, the reason nothing can be shown, an empty range, or figures.
            when {
                data == null && state.phase == UsagePhase.Loading -> {
                    item(key = "status") { Box(Modifier.padding(top = 10.dp)) { UsageStatusLine(state, null, onRefresh) } }
                    item(key = "skeleton") { Box(Modifier.padding(top = 12.dp)) { UsageSkeleton() } }
                }
                data == null -> item(key = "status") { Box(Modifier.padding(top = 12.dp)) { UsageNotice(state, onRefresh) } }
                data.totals.requests == 0uL -> {
                    item(key = "status") { Box(Modifier.padding(top = 10.dp)) { UsageStatusLine(state, snapshot, onRefresh) } }
                    item(key = "no-data") { Box(Modifier.padding(top = 12.dp)) { UsageEmpty() } }
                }
                else -> {
                    val costKnown = data.totals.costs.total.knownRows > 0uL
                    val metrics = usageTrendMetrics(data)
                    val metric = trendMetric.takeIf { it in metrics } ?: metrics.first()
                    val groupings = UsageGrouping.entries.filter { it != UsageGrouping.Session || data.sessionGroups != null }
                    val shown = grouping.takeIf { it in groupings } ?: UsageGrouping.Provider
                    val page: UsageGroupPage<*> = when (shown) {
                        UsageGrouping.Provider -> data.providerGroups; UsageGrouping.Model -> data.modelGroups; UsageGrouping.Session -> data.sessionGroups!!
                    }
                    val entries = when (shown) {
                        UsageGrouping.Provider -> data.providerGroups.items.map { g -> UsageGroupEntry("${g.appType}:${g.providerId}", g.name ?: usageShortId(g.providerId),
                            "${usageAppLabel(g.appType)} · ${usageShortId(g.providerId)}", g.totals, f.withProvider(g.providerId)) }
                        UsageGrouping.Model -> data.modelGroups.items.map { g -> UsageGroupEntry(g.model, g.model, null, g.totals, f.withModel(g.model)) }
                        UsageGrouping.Session -> data.sessionGroups!!.items.map { g -> UsageGroupEntry(g.sessionId, g.label, usageShortId(g.sessionId), g.totals, f.withSession(g.sessionId)) }
                    }
                    // Without a saved amount there is nothing to rank by cost, so the rows rank by tokens.
                    val tokens = !costKnown || when (shown) { UsageGrouping.Provider -> providerTokens; UsageGrouping.Model -> modelTokens; UsageGrouping.Session -> sessionTokens }
                    item(key = "status") { Box(Modifier.padding(top = 10.dp)) { UsageStatusLine(state, snapshot, onRefresh) } }
                    item(key = "metrics") { Box(Modifier.padding(top = 12.dp)) { UsageOverview(data.totals, data.trend, grow, can) { label -> insight = label to data.totals } } }
                    item(key = "trend") {
                        Box(Modifier.padding(top = 8.dp)) {
                            UsageTrendChart(data.trend, f.zoneId, metric, metrics, { trendMetric = it }, grow) { b -> insight = bucketRange(b, f.zoneId) to b.totals }
                        }
                    }
                    if (usageHasPerformance(data.totals)) item(key = "performance") {
                        Box(Modifier.padding(top = 20.dp)) { UsagePerformanceTiles(data.totals, data.trend, grow) { label -> insight = label to data.totals } }
                    }
                    item(key = "groups-header") {
                        Box(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                            UsageGroupHeader(shown, groupings, { grouping = it }, page, state.canQuery && !state.refreshing, tokens, costKnown, { value ->
                                when (shown) { UsageGrouping.Provider -> providerTokens = value; UsageGrouping.Model -> modelTokens = value; UsageGrouping.Session -> sessionTokens = value }
                            }) { offset ->
                                onFilter(when (shown) {
                                    UsageGrouping.Provider -> f.copy(providerPage = f.providerPage.copy(offset = offset))
                                    UsageGrouping.Model -> f.copy(modelPage = f.modelPage.copy(offset = offset))
                                    UsageGrouping.Session -> f.copy(sessionPage = f.sessionPage.copy(offset = offset))
                                })
                            }
                        }
                    }
                    itemsIndexed(entries, key = { _, g -> "${shown.key}:${g.key}" }) { i, g ->
                        UsageGroupRow(g.title, g.identity, g.totals, data.totals, "usage-${shown.key}:$i", tokens, i == 0, i == entries.lastIndex) { onFilter(g.narrowed) }
                    }
                    if (entries.isEmpty()) item(key = "groups-empty") {
                        UsageNote(if (shown == UsageGrouping.Session) "当前记录未包含可展示的对话标识。" else "当前范围没有可展示的分组。", Modifier.padding(horizontal = 4.dp))
                    }
                    state.visibleRequests?.let { requests ->
                        item(key = "requests-header") {
                            UsageHeading("最近请求", Modifier.padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 8.dp).testTag("usage-requests"))
                        }
                        itemsIndexed(requests.items, key = { _, r -> "request:${r.requestId}" }) { i, r ->
                            UsageRequestRow(r, f.zoneId, "usage-request:$i", i == 0, i == requests.items.lastIndex, can) { request = r }
                        }
                        item(key = "requests-footer") {
                            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.requestError?.let { PText(it, Modifier.padding(horizontal = 4.dp), style = Pocket.type.bodySmall, color = c.danger) }
                                if (requests.hasMore) PButton(if (state.requestLoading) "正在读取…" else "加载更多请求",
                                    { requests.nextCursor?.let { onRequests?.invoke(it) } }, Modifier.fillMaxWidth().testTag("usage-requests-next"), PButtonKind.Secondary,
                                    enabled = state.canPageRequests && onRequests != null && !state.requestLoading && !state.refreshing)
                                else UsageNote(if (requests.items.size >= 1000) "已展示 1000 条，请缩小日期或筛选范围。" else "已展示当前范围内的请求。", Modifier.padding(horizontal = 4.dp))
                            }
                        }
                    }
                    item(key = "coverage-toggle") {
                        Box(Modifier.padding(top = 16.dp)) {
                            UsageListRow(first = true, last = true, onClick = { coverageOpen = true }, modifier = Modifier.testTag("usage-coverage-open")) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PIcon(PocketIcons.Info, null, Modifier.size(18.dp), tint = c.inkMuted)
                                    PText("统计口径与记录覆盖", Modifier.weight(1f), style = Pocket.type.titleSmall)
                                    PIcon(PocketIcons.ChevronRight, null, Modifier.size(16.dp), tint = c.inkFaint)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "footer") {
                UsageNote(if (native) "${f.zoneId} · 原生 Codex 未提供费用；只统计这台电脑观察到或保留在本机历史中的用量，不与 CC Switch 合计。"
                    else "${f.zoneId} · 费用为 CC Switch 保存金额；代理与导入来源分别统计。", Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp).testTag("usage-footer"))
            }
        }
        CompositionLocalProvider(LocalPocketBackdrop provides backdrop) {
            UsageSourceCapsule(busy, state.canQuery && !busy, onRefresh, state.sources, f.source, { onFilter(f.withSource(it)) },
                modifier = Modifier.padding(start = start, end = end, top = top).onSizeChanged { capsule = with(density) { it.height.toDp() } })
        }
        if (filtersOpen) UsageFilterSheet(f, { filtersOpen = false }) { filtersOpen = false; onFilter(it) }
        insight?.let { (title, totals) -> UsageInsightSheet(title, totals, f.dataSource, can) { insight = null } }
        request?.let { UsageRequestSheet(it, f.zoneId, can) { request = null } }
        if (coverageOpen && snapshot != null) UsageCoverageSheet(snapshot.response.coverage, f.zoneId, native) { coverageOpen = false }
      }
    }
}
