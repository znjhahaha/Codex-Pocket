package dev.codexpocket.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
    var insight by remember { mutableStateOf<Pair<String, UsageTotals>?>(null) }
    var request by remember { mutableStateOf<UsageRequestDetail?>(null) }
    var providerTokens by rememberSaveable { mutableStateOf(false) }
    var modelTokens by rememberSaveable { mutableStateOf(false) }
    var sessionTokens by rememberSaveable { mutableStateOf(false) }
    var coverageOpen by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.visibleSnapshot
    val data = snapshot?.response?.data
    val f = state.filter
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val c = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().background(c.background)) {
      Box(Modifier.fillMaxSize().testTag("usage-screen")) {
        UsageBackdrop(backdrop)
        CompositionLocalProvider(LocalPocketBackdrop provides backdrop, LocalPocketClearGlass provides false) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("usage-list"), contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "header") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("调用统计", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            UsageNote("${usageAppLabel(f.appType)} · ${f.dataSource.label}")
                        }
                        PocketTextButton(onRefresh, Modifier.testTag("usage-refresh"), enabled = state.canQuery && !state.refreshing && state.phase != UsagePhase.Loading) { Text("刷新") }
                    }
                }
                item(key = "filters") {
                    UsageFilterBar(f, { filtersOpen = true }, onFilter)
                }
                item(key = "status") { UsageStatus(state, snapshot, onRefresh) }
                if (data != null && data.totals.requests == 0uL) {
                    item(key = "no-data") { UsageCard {
                        Text("所选范围没有调用记录", style = MaterialTheme.typography.titleMedium)
                        UsageNote("可以更换日期或清除筛选条件。")
                    } }
                } else if (data != null) {
                    item(key = "metrics") { UsageKpis(data.totals, data.trend) { label -> insight = label to data.totals } }
                    item(key = "trend") { UsageTrendChart(data.trend, f.zoneId, trendMetric, { trendMetric = it }) { b -> insight = bucketRange(b, f.zoneId) to b.totals } }
                    item(key = "providers-header") { UsageGroupHeader("按上游", data.providerGroups, "provider", state.canQuery && !state.refreshing, providerTokens, { providerTokens = it }) { offset -> onFilter(f.copy(providerPage = f.providerPage.copy(offset = offset))) } }
                    itemsIndexed(data.providerGroups.items, key = { _, g -> "provider:${g.appType}:${g.providerId}" }) { i, g ->
                        UsageCompactGroup(g.name ?: usageShortId(g.providerId), "${usageAppLabel(g.appType)} · ${usageShortId(g.providerId)}", g.totals,
                            data.totals, "usage-provider:$i", providerTokens) { onFilter(f.withProvider(g.providerId)) }
                    }
                    item(key = "models-header") { UsageGroupHeader("按模型", data.modelGroups, "model", state.canQuery && !state.refreshing, modelTokens, { modelTokens = it }) { offset -> onFilter(f.copy(modelPage = f.modelPage.copy(offset = offset))) } }
                    itemsIndexed(data.modelGroups.items, key = { _, g -> "model:${g.model}" }) { i, g ->
                        UsageCompactGroup(g.model, "模型", g.totals, data.totals, "usage-model:$i", modelTokens) { onFilter(f.withModel(g.model)) }
                    }
                    data.sessionGroups?.let { sessions ->
                        item(key = "sessions-header") { UsageGroupHeader("按对话", sessions, "session", state.canQuery && !state.refreshing, sessionTokens, { sessionTokens = it }) { offset -> onFilter(f.copy(sessionPage = f.sessionPage.copy(offset = offset))) } }
                        itemsIndexed(sessions.items, key = { _, g -> "session:${g.sessionId}" }) { i, g ->
                            UsageCompactGroup(g.label, usageShortId(g.sessionId), g.totals, data.totals, "usage-session:$i", sessionTokens) { onFilter(f.withSession(g.sessionId)) }
                        }
                        if (sessions.items.isEmpty()) item(key = "sessions-empty") { UsageNote("当前记录未包含可展示的对话标识。") }
                    }
                    state.visibleRequests?.let { page ->
                        item(key = "requests-header") { Text("最近请求", Modifier.semantics { heading() }.testTag("usage-requests"), style = MaterialTheme.typography.titleMedium) }
                        itemsIndexed(page.items, key = { _, r -> "request:${r.requestId}" }) { i, r -> UsageRequestRow(r, f.zoneId, "usage-request:$i") { request = r } }
                        item(key = "requests-footer") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.requestError?.let { Text(it, color = c.error, style = MaterialTheme.typography.bodySmall) }
                                if (page.hasMore) PocketButton(if (state.requestLoading) "正在读取…" else "加载更多请求",
                                    { page.nextCursor?.let { onRequests?.invoke(it) } }, Modifier.fillMaxWidth().testTag("usage-requests-next"),
                                    enabled = state.canPageRequests && onRequests != null && !state.requestLoading && !state.refreshing, secondary = true)
                                else UsageNote(if (page.items.size >= 1000) "已展示 1000 条，请缩小日期或筛选范围。" else "已展示当前范围内的请求。")
                            }
                        }
                    }
                    item(key = "coverage-toggle") { PocketTextButton({ coverageOpen = true }, Modifier.fillMaxWidth().testTag("usage-coverage-open")) { Text("统计口径与记录覆盖") } }
                }
                item(key = "footer") { UsageNote("${f.zoneId} · 费用为 CC Switch 保存金额；代理与导入来源分别统计。") }
            }
            if (filtersOpen) UsageFilterSheet(f, { filtersOpen = false }) { filtersOpen = false; onFilter(it) }
            insight?.let { (title, totals) -> UsageInsightSheet(title, totals, f.dataSource) { insight = null } }
            request?.let { UsageRequestSheet(it, f.zoneId) { request = null } }
            if (coverageOpen && snapshot != null) PocketSheet("统计口径", { coverageOpen = false }) {
                UsageCoverageCard(snapshot.response.coverage, f.zoneId)
            }
        }
      }
    }
}

@Composable
internal fun UsageCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable
internal fun UsageNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun UsageStatus(state: UsageScreenState, snapshot: UsageSnapshot?, onRefresh: () -> Unit) {
    var details by rememberSaveable(state.phase, state.observedSchemaVersion) { mutableStateOf(false) }
    val stale = state.cached || snapshot?.response?.cache?.state == "stale"
    val time = snapshot?.let { it.response.cache?.sampledAt ?: it.updatedAt }
    if (state.phase == UsagePhase.Ready && snapshot != null && !stale) {
        val label = if (state.refreshing) "正在刷新 · 上次更新" else "已更新"
        UsageNote("$label · ${usageTime(time!!, state.filter.zoneId).substring(5, 16)}",
            Modifier.fillMaxWidth().testTag("usage-status").semantics {
                contentDescription = "$label · ${usageTime(time, state.filter.zoneId)} · ${state.filter.zoneId}"
            })
        return
    }
    val status = when (state.phase) {
        UsagePhase.NotConnected -> "用量尚未接入" to "连接执行位置后，可读取该位置的用量。"
        UsagePhase.Idle -> "等待读取用量" to "刷新后读取当前筛选范围。"
        UsagePhase.Loading -> "正在读取用量" to "正在读取所选日期的记录。"
        UsagePhase.Ready -> "显示上次统计" to "本次未能更新，数值来自上次成功读取。"
        UsagePhase.Offline -> "电脑已离线" to "请切换到「电脑连接」重新连接，然后刷新。"
        UsagePhase.Missing -> "尚未找到 CC Switch 用量" to "当前执行位置没有 CC Switch 请求记录。"
        UsagePhase.Locked -> "用量数据库暂时被锁定" to "稍后刷新重试。"
        UsagePhase.UnsupportedSchema -> "暂不支持当前用量格式" to "CC Switch 的记录格式与当前读取器不兼容。原始记录仍保留。"
        UsagePhase.Corrupt -> "CC Switch 数据库无法打开" to "数据库文件损坏或不是有效的 SQLite 数据库，请在 CC Switch 中检查或恢复备份。"
        UsagePhase.UnsupportedHelper -> "电脑端尚不支持用量读取" to "更新电脑端 helper 后重新连接。"
        UsagePhase.InvalidPath -> "用量数据库路径不可安全读取" to "请在电脑端检查默认用量数据库文件。"
        UsagePhase.Rejected -> "统计请求被拒绝" to "缩小日期范围或检查筛选条件后刷新。"
        UsagePhase.Failed -> "用量读取失败" to "确认电脑端正常运行，然后刷新重试。"
    }
    UsageCard(Modifier.testTag("usage-status")) {
        if (state.phase == UsagePhase.Loading) PocketProgressStatus(status.first)
        else Text(status.first, style = MaterialTheme.typography.titleSmall)
        UsageNote(status.second)
        if (state.refreshing && state.phase != UsagePhase.Loading) PocketProgressStatus("正在刷新，暂时显示上次统计")
        time?.let { UsageNote("缓存快照 · ${usageTime(it, state.filter.zoneId)} · ${state.filter.zoneId}") }
        if (state.canQuery && state.phase != UsagePhase.Loading) {
            PocketTextButton(onRefresh, Modifier.testTag("usage-retry"), enabled = !state.refreshing) { Text("重试") }
        }
        if (state.observedSchemaVersion != null || state.failureCode != null) {
            PocketTextButton({ details = !details }, Modifier.testTag("usage-diagnostics")) { Text(if (details) "收起详情" else "读取详情") }
            if (details) {
                state.observedSchemaVersion?.let { UsageNote("CC Switch 数据格式：$it") }
                state.failureCode?.let { UsageNote("读取状态：$it") }
            }
        }
    }
}

@Composable
internal fun UsagePerformanceCard(p: UsagePerformance, source: UsageDataSource) {
    UsageCard(Modifier.testTag("usage-performance")) {
        Text("成功请求实测性能", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        if (source == UsageDataSource.CodexSession) UsageNote("会话导入的推测性能已排除，没有实测样本时显示未知。")
        else UsageNote("仅成功代理请求贡献样本；TTFT 和输出速度仅使用成功流式记录。")
        listOf(Triple("TTFT", p.ttft.display(), p.ttft.coverageLabel()),
            Triple("平均耗时", p.duration.display(), p.duration.coverageLabel()),
            Triple("输出速度", p.outputSpeed.display(), p.outputSpeed.coverageLabel())).forEach { (label, value, coverage) ->
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(value, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                }
                UsageNote(coverage)
            }
        }
        UsageNote("耗时读取保存的 latency_ms；输出速度为合格样本总输出 / 总生成时间，排除输出少于 100 token 或生成少于 100 ms 的样本。")
    }
}

internal fun bucketRange(b: UsageTrendBucket, zone: String): String =
    Instant.ofEpochSecond(b.startAt).atZone(ZoneId.of(zone)).toLocalDate().toString()

@Composable
internal fun UsageCostCard(costs: UsageCosts) {
    UsageCard(Modifier.testTag("usage-cost-breakdown")) {
        Text("保存的费用", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        listOf("费用合计" to costs.total, "输入基础费用" to costs.input, "输出基础费用" to costs.output,
            "缓存读取基础费用" to costs.cacheRead, "缓存写入基础费用" to costs.cacheWrite).forEach { (title, amount) ->
            Text(title, style = MaterialTheme.typography.bodyMedium)
            val amountScroll = rememberScrollState()
            SelectionContainer {
                Text(amount.display(), Modifier.fillMaxWidth().horizontalScroll(amountScroll), maxLines = 1, softWrap = false,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (amountScroll.maxValue > 0) UsageNote("左右滑动查看完整金额")
            UsageNote(amount.coverageLabel())
        }
        UsageNote("费用合计为已保存的含倍率金额，四项基础费用可能与合计不同；未按令牌补价或再次乘倍率。")
    }
}

@Composable
private fun UsageCoverageCard(c: UsageCoverage, zone: String) {
    UsageCard(Modifier.testTag("usage-coverage")) {
        Text("记录覆盖", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        UsageNote("仅统计保留明细，历史可能不完整；未混加日汇总或其他来源。")
        UsageNote("匹配 ${usageCount(c.matchedRows)} 条 · 费用已知 ${usageCount(c.costKnownRows)} · 缺失 ${usageCount(c.costMissingRows)} · 无效 ${usageCount(c.costInvalidRows)}")
        UsageNote("已记录为 0 的费用有 ${usageCount(c.zeroCostRows)} 条，定价状态未知；无法确认免费条数。")
        UsageNote("无效 token 行 ${usageCount(c.tokenInvalidRows)} · ${if (c.rollupsPresent) "存在日汇总表，未计入" else "未发现日汇总表"}")
        UsageNote("首次保留记录：${c.firstObservedAt?.let { usageTime(it, zone) } ?: "未知"}")
        UsageNote("最后保留记录：${c.lastObservedAt?.let { usageTime(it, zone) } ?: "未知"}")
    }
}

internal fun usageAppLabel(app: String) = when (app) { "codex" -> "Codex"; "claude" -> "Claude"; "gemini" -> "Gemini"; else -> "Grok Build" }
