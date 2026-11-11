package dev.codexpocket.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
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

/** Pure projection and callbacks; the parent owns identity, capability, I/O and caching. */
@Composable
fun UsageScreen(
    state: UsageScreenState,
    onRefresh: () -> Unit,
    onFilter: (UsageFilter) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var trendMetric by rememberSaveable { mutableStateOf(UsageTrendMetric.Requests) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.visibleSnapshot
    val data = snapshot?.response?.data
    val f = state.filter
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(Modifier.fillMaxSize().testTag("usage-screen")) {
    LazyColumn(Modifier.fillMaxSize().testTag("usage-list"),
        contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("用量统计", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    UsageNote("CC Switch · ${usageAppLabel(f.appType)} · ${f.dataSource.label}")
                }
                PocketTextButton(onRefresh, Modifier.testTag("usage-refresh"), enabled = state.canQuery && !state.refreshing && state.phase != UsagePhase.Loading) {
                    Text(if (state.refreshing) "刷新中" else "刷新")
                }
            }
        }
        item(key = "filter") {
            UsageCard {
                Text("${f.startDate} — ${f.endDate}", style = MaterialTheme.typography.titleSmall)
                UsageNote("${f.zoneId} · ${f.status.label}")
                if (f.providerId != null) UsageNote("提供方 ID：${f.providerId}")
                if (f.model != null) UsageNote("模型：${f.model}")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1 to "今天", 7 to "近 7 天", 30 to "近 30 天").forEach { (days, text) ->
                        PocketTextButton({
                            val today = LocalDate.now(ZoneId.of(f.zoneId))
                            onFilter(f.withDates(today.minusDays(days - 1L), today))
                        }, Modifier.weight(1f).testTag("usage-date:$days")) { Text(text) }
                    }
                }
                PocketButton("筛选", { filtersOpen = true }, Modifier.fillMaxWidth().testTag("usage-filter-open"), secondary = true)
            }
        }
        item(key = "status") { UsageStatus(state, snapshot) }
        if (data == null) {
            item(key = "no-data") {
                UsageCard {
                    Text(if (state.phase == UsagePhase.Loading) "正在读取所选范围" else "暂无可展示统计", style = MaterialTheme.typography.titleMedium)
                    UsageNote("统计由所选电脑的保留明细提供。连接可用后刷新，不会补入样例数据。")
                }
            }
        } else {
            if (data.totals.requests == 0uL) item(key = "empty") {
                UsageCard { Text("所选范围没有保留记录", style = MaterialTheme.typography.titleMedium); UsageNote("这不代表历史从未发生请求。可更换日期或筛选条件。") }
            }
            item(key = "metrics") { UsageMetrics(data.totals) }
            item(key = "performance") { UsagePerformanceCard(data.totals.performance, f.dataSource) }
            item(key = "trend") {
                UsageTrend(data.trend, f.zoneId, trendMetric, { trendMetric = it }, detailsOpen, { detailsOpen = !detailsOpen })
            }
            if (detailsOpen) itemsIndexed(data.trend, key = { index, _ -> "bucket:$index" }) { index, bucket ->
                UsageCard(Modifier.testTag("usage-bucket:$index")) {
                    Text(bucketRange(bucket, f.zoneId), style = MaterialTheme.typography.titleSmall)
                    UsageNote(bucketSummary(bucket, f.zoneId))
                    UsageNote(bucket.totals.costs.total.coverageLabel())
                    UsageNote("TTFT ${bucket.totals.performance.ttft.display()} · 输出速度 ${bucket.totals.performance.outputSpeed.display()}")
                }
            }
            item(key = "costs") { UsageCostCard(data.totals.costs) }
            item(key = "providers-header") {
                UsageGroupHeader("提供方", data.providerGroups, "provider", state.canQuery && !state.refreshing, { next ->
                    onFilter(f.copy(providerPage = f.providerPage.copy(offset = next)))
                })
            }
            itemsIndexed(data.providerGroups.items, key = { _, group -> "provider:${group.appType}:${group.providerId}" }) { index, group ->
                UsageGroupRow(group.name ?: "未命名提供方", "${usageAppLabel(group.appType)} · ID ${group.providerId}", group.totals,
                    "usage-provider:$index", { onFilter(f.withProvider(group.providerId)) })
            }
            if (data.providerGroups.items.isEmpty()) item(key = "providers-empty") { UsageNote("当前页没有提供方分组。") }
            item(key = "models-header") {
                UsageGroupHeader("模型", data.modelGroups, "model", state.canQuery && !state.refreshing, { next ->
                    onFilter(f.copy(modelPage = f.modelPage.copy(offset = next)))
                })
            }
            itemsIndexed(data.modelGroups.items, key = { _, group -> "model:${group.model}" }) { index, group ->
                UsageGroupRow(group.model, "记录中的模型名称", group.totals, "usage-model:$index", { onFilter(f.withModel(group.model)) })
            }
            if (data.modelGroups.items.isEmpty()) item(key = "models-empty") { UsageNote("当前页没有模型分组。") }
            item(key = "coverage") { UsageCoverageCard(snapshot!!.response.coverage, f.zoneId) }
        }
        item(key = "footer") { UsageNote("金额是数据库保存的统计，未核验服务商账单；请求与 token 不跨来源相加。") }
    }
    }
    }
    if (filtersOpen) UsageFilterDialog(f, { filtersOpen = false }) { filtersOpen = false; onFilter(it) }
}

@Composable
private fun UsageCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable
private fun UsageNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun UsageStatus(state: UsageScreenState, snapshot: UsageSnapshot?) {
    val status = when (state.phase) {
        UsagePhase.NotConnected -> "用量尚未接入" to "请先连接电脑；需要电脑端提供用量读取能力。"
        UsagePhase.Idle -> "等待读取用量" to "刷新后读取当前筛选范围。"
        UsagePhase.Loading -> "正在读取用量" to "等待电脑端完成只读统计。"
        UsagePhase.Ready -> "统计已就绪" to "仅代表所选范围内仍保留的记录。"
        UsagePhase.Offline -> "电脑已离线" to "重新连接电脑后刷新。"
        UsagePhase.Missing -> "电脑上没有可用用量数据库" to "在电脑端确认 CC Switch 已记录用量，再刷新。"
        UsagePhase.Locked -> "用量数据库暂时被锁定" to "稍后刷新重试。"
        UsagePhase.UnsupportedSchema -> "用量数据库版本不支持" to "当前读取器支持 CC Switch schema 24，请核对电脑端版本。"
        UsagePhase.UnsupportedHelper -> "电脑端尚不支持用量读取" to "更新电脑端 helper 后重新连接。"
        UsagePhase.InvalidPath -> "用量数据库路径不可安全读取" to "请在电脑端检查默认用量数据库文件。"
        UsagePhase.Rejected -> "统计请求被拒绝" to "缩小日期范围或检查筛选条件后刷新。"
        UsagePhase.Failed -> "用量读取失败" to "确认电脑端正常运行，然后刷新重试。"
    }
    UsageCard(Modifier.testTag("usage-status")) {
        Text(status.first, style = MaterialTheme.typography.titleSmall)
        UsageNote(status.second)
        if (state.refreshing) PocketProgressStatus("正在刷新，保留同一筛选的快照")
        snapshot?.let {
            val cache = it.response.cache
            val stale = state.cached || cache?.state == "stale" || state.phase != UsagePhase.Ready
            val time = cache?.sampledAt ?: it.updatedAt
            UsageNote("${if (stale) "缓存快照" else "统计时间"} · ${usageTime(time, state.filter.zoneId)} · ${state.filter.zoneId}")
            if (cache != null) {
                val label = when (cache.state) { "fresh" -> "电脑端缓存有效"; "stale" -> "电脑端返回旧快照"; else -> "电脑端无可用缓存" }
                UsageNote(label + (cache.ageSeconds?.let { age -> " · 已 ${usageCount(age)} 秒" } ?: "") + " · 缓存有效期 ${usageCount(cache.ttlSeconds)} 秒")
                if (cache.state == "stale") UsageNote("本次读取未成功，数值来自此前快照；刷新失败不会延长快照时间。")
            }
        }
    }
}

private data class UsageMetric(val id: String, val label: String, val value: String, val note: String)
@Composable
private fun UsageMetrics(t: UsageTotals) {
    val successRate = if (t.requests == 0uL) "未知" else BigDecimal(t.success.toString()).multiply(BigDecimal(100))
        .divide(BigDecimal(t.requests.toString()), 1, RoundingMode.HALF_UP).toPlainString() + "%"
    val metrics = listOf(
        UsageMetric("requests", "请求数", usageCount(t.requests), "成功 ${usageCount(t.success)} · 失败 ${usageCount(t.errors)}"),
        UsageMetric("cost", "已知最终费用", t.costs.total.display(), t.costs.total.coverageLabel()),
        UsageMetric("tokens", "规范化总 token", usageCount(t.tokens.total), "无效 token 行 ${usageCount(t.tokens.invalidRows)}"),
        UsageMetric("success", "成功率", successRate, "成功状态码 200–299"),
        UsageMetric("input", "新输入 token", usageCount(t.tokens.freshInput), "按已核验的输入语义归一化"),
        UsageMetric("output", "输出 token", usageCount(t.tokens.output), "已保存的输出计数"),
        UsageMetric("cache-read", "缓存读取 token", usageCount(t.tokens.cacheRead), "已保存的缓存读取计数"),
        UsageMetric("cache-write", "缓存写入 token", usageCount(t.tokens.cacheWrite), "已保存的缓存写入计数"),
    )
    Column(Modifier.testTag("usage-metrics"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { metric ->
                    Surface(Modifier.weight(1f).fillMaxHeight().testTag("usage-metric:${metric.id}")
                        .semantics(mergeDescendants = true) { contentDescription = "${metric.label}：${metric.value}；${metric.note}" },
                        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            UsageNote(metric.label)
                            Text(metric.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            UsageNote(metric.note)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsagePerformanceCard(p: UsagePerformance, source: UsageDataSource) {
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

private enum class UsageTrendMetric(val label: String) { Requests("请求"), Tokens("Token"), Cost("费用") }
private fun UsageTrendBucket.value(metric: UsageTrendMetric): BigDecimal? = when (metric) {
    UsageTrendMetric.Requests -> BigDecimal(totals.requests.toString())
    UsageTrendMetric.Tokens -> BigDecimal(totals.tokens.total.toString())
    UsageTrendMetric.Cost -> if (totals.costs.total.knownRows == 0uL && totals.requests > 0uL) null else BigDecimal(totals.costs.total.knownUsd)
}
private fun bucketRange(b: UsageTrendBucket, zone: String): String {
    val z = ZoneId.of(zone)
    val start = Instant.ofEpochSecond(b.startAt).atZone(z)
    val end = Instant.ofEpochSecond(b.endAt).atZone(z)
    return if (end.toLocalDate() == start.toLocalDate().plusDays(1) && start.toLocalTime() == java.time.LocalTime.MIDNIGHT && end.toLocalTime() == java.time.LocalTime.MIDNIGHT)
        start.toLocalDate().toString() else "${usageTime(b.startAt, zone)} — ${usageTime(b.endAt, zone)}（结束不含）"
}
private fun bucketSummary(b: UsageTrendBucket, zone: String) = "${bucketRange(b, zone)}：请求 ${usageCount(b.totals.requests)} · token ${usageCount(b.totals.tokens.total)} · 已知费用 ${b.totals.costs.total.display()}"

@Composable
private fun UsageTrend(buckets: List<UsageTrendBucket>, zone: String, metric: UsageTrendMetric,
    onMetric: (UsageTrendMetric) -> Unit, expanded: Boolean, onDetails: () -> Unit) {
    val values = remember(buckets, metric) { buckets.map { it.value(metric) } }
    val max = values.filterNotNull().maxOrNull() ?: BigDecimal.ZERO
    val c = MaterialTheme.colorScheme
    // Opaque surface and canvas: no backdrop blur or glass compositing beneath data marks.
    UsageCard(Modifier.testTag("usage-trend")) {
        Text("每日趋势", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UsageTrendMetric.entries.forEach { option -> PocketPill(option.label, metric == option, { onMetric(option) }, modifier = Modifier.weight(1f)) }
        }
        UsageNote("时区 $zone · ${metric.label}${if (metric == UsageTrendMetric.Cost) "（USD 已知小计，缺失留空）" else ""}")
        if (buckets.isEmpty()) UsageNote("没有可展示的趋势桶。") else {
            UsageNote("纵轴上界 ${max.stripTrailingZeros().toPlainString()}${if (metric == UsageTrendMetric.Cost) " USD" else ""}")
            Canvas(Modifier.fillMaxWidth().height(172.dp).background(c.surface).testTag("usage-trend-chart").semantics {
                contentDescription = "${metric.label}每日趋势，时区 $zone，纵轴 0 到 ${max.toPlainString()}；" + buckets.joinToString("；") { bucketSummary(it, zone) }
            }) {
                val bottom = size.height - 8.dp.toPx()
                val top = 8.dp.toPx()
                repeat(4) { level ->
                    val y = bottom - (bottom - top) * level / 3f
                    drawLine(c.outlineVariant, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                val slot = size.width / buckets.size
                values.forEachIndexed { index, value ->
                    val left = slot * index + slot * .18f
                    val width = slot * .64f
                    if (value == null) {
                        drawLine(c.onSurfaceVariant, Offset(left, bottom - 4.dp.toPx()), Offset(left + width, bottom - 4.dp.toPx()), 2.dp.toPx())
                    } else {
                        val ratio = if (max.signum() == 0) 0f else value.divide(max, 8, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
                        val h = (bottom - top) * ratio
                        if (h > 0f) drawRect(c.secondary, Offset(left, bottom - h), Size(width, h))
                        else drawCircle(c.secondary, radius = 2.dp.toPx(), center = Offset(left + width / 2, bottom))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                UsageNote(Instant.ofEpochSecond(buckets.first().startAt).atZone(ZoneId.of(zone)).toLocalDate().toString())
                if (buckets.size > 1) UsageNote(Instant.ofEpochSecond(buckets.last().startAt).atZone(ZoneId.of(zone)).toLocalDate().toString())
            }
            UsageNote("纵轴从 0 起；圆点表示已记录的零值，短线表示费用未知。费用有缺失时，柱形仅表示已知小计。")
        }
        PocketTextButton(onDetails, Modifier.fillMaxWidth().testTag("usage-trend-details-toggle")) { Text(if (expanded) "收起按日明细" else "查看按日明细") }
    }
}

@Composable
private fun UsageCostCard(costs: UsageCosts) {
    UsageCard(Modifier.testTag("usage-cost-breakdown")) {
        Text("保存的费用", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        listOf("最终 total" to costs.total, "输入基础费用" to costs.input, "输出基础费用" to costs.output,
            "缓存读取基础费用" to costs.cacheRead, "缓存写入基础费用" to costs.cacheWrite).forEach { (title, amount) ->
            Text("$title · ${amount.display()}", style = MaterialTheme.typography.bodyMedium)
            UsageNote(amount.coverageLabel())
        }
        UsageNote("最终 total 是已保存的含倍率金额，四项基础费用可能不等于 total；未按 token 补价或再次乘倍率。")
    }
}

@Composable
private fun <T> UsageGroupHeader(title: String, page: UsageGroupPage<T>, key: String, enabled: Boolean, onOffset: (Int) -> Unit) {
    Column(Modifier.testTag("usage-$key-groups"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${title}分组", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        val range = if (page.items.isEmpty()) "当前页 0 项" else "${page.offset + 1}–${page.offset + page.items.size}"
        UsageNote("$range · 共 ${page.totalGroups} 组 · 每页 ${page.limit} · 按标识排序")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PocketButton("上一页", { onOffset((page.offset - page.limit).coerceAtLeast(0)) }, Modifier.weight(1f).testTag("usage-$key-prev"),
                enabled = enabled && page.offset > 0, secondary = true)
            PocketButton("下一页", { onOffset(page.offset + page.limit) }, Modifier.weight(1f).testTag("usage-$key-next"),
                enabled = enabled && page.hasMore && page.offset + page.limit <= 10000, secondary = true)
        }
    }
}
@Composable
private fun UsageGroupRow(title: String, identity: String, totals: UsageTotals, tag: String, onSelect: () -> Unit) {
    UsageCard(Modifier.testTag(tag)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        UsageNote(identity)
        UsageNote("请求 ${usageCount(totals.requests)} · 成功 ${usageCount(totals.success)} · 失败 ${usageCount(totals.errors)}")
        UsageNote("token ${usageCount(totals.tokens.total)} · 已知费用 ${totals.costs.total.display()}")
        UsageNote(totals.costs.total.coverageLabel())
        UsageNote("TTFT ${totals.performance.ttft.display()} · 输出速度 ${totals.performance.outputSpeed.display()}")
        PocketTextButton(onSelect, Modifier.fillMaxWidth().testTag("$tag-select")) { Text("按此分组筛选") }
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

private fun usageAppLabel(app: String) = when (app) { "codex" -> "Codex"; "claude" -> "Claude"; "gemini" -> "Gemini"; else -> "Grok Build" }

@Composable
private fun UsageFilterDialog(filter: UsageFilter, onDismiss: () -> Unit, onApply: (UsageFilter) -> Unit) {
    var start by rememberSaveable { mutableStateOf(filter.startDate.toString()) }
    var end by rememberSaveable { mutableStateOf(filter.endDate.toString()) }
    var zone by rememberSaveable { mutableStateOf(filter.zoneId) }
    var app by rememberSaveable { mutableStateOf(filter.appType) }
    var source by rememberSaveable { mutableStateOf(filter.dataSource) }
    var status by rememberSaveable { mutableStateOf(filter.status) }
    var provider by rememberSaveable { mutableStateOf(filter.providerId.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(filter.model.orEmpty()) }
    val candidate = remember(start, end, zone, app, source, status, provider, model) {
        runCatching {
            filter.copy(startDate = LocalDate.parse(start), endDate = LocalDate.parse(end), zoneId = zone, appType = app,
                dataSource = source, status = status, providerId = provider.takeUnless { it.isEmpty() }, model = model.takeUnless { it.isEmpty() },
                providerPage = filter.providerPage.copy(offset = 0), modelPage = filter.modelPage.copy(offset = 0)).also { it.toQuery() }
        }.getOrNull()
    }
    PocketDialog(onDismissRequest = onDismiss, title = { Text("用量筛选") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UsageNote("按所选时区自然日统计，结束日期包含当天；范围 1–31 天。")
            PocketTextField(start, { start = it }, Modifier.testTag("usage-filter-start"), label = "开始日期 yyyy-MM-dd", singleLine = true)
            PocketTextField(end, { end = it }, Modifier.testTag("usage-filter-end"), label = "结束日期 yyyy-MM-dd", singleLine = true)
            PocketTextField(zone, { zone = it }, Modifier.testTag("usage-filter-zone"), label = "时区", placeholder = "Asia/Shanghai", singleLine = true)
            Text("应用", style = MaterialTheme.typography.titleSmall)
            UsageFilterChoices(UsageFilter.apps, app, ::usageAppLabel, "usage-filter-app") { value ->
                app = value; if (value != "codex") source = UsageDataSource.Proxy; provider = ""; model = ""
            }
            Text("单一来源", style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                UsageDataSource.entries.forEach { option ->
                    PocketPill(option.label, source == option, { source = option; provider = ""; model = "" },
                        enabled = option != UsageDataSource.CodexSession || app == "codex", modifier = Modifier.fillMaxWidth().testTag("usage-filter-source:${option.wire}"))
                }
            }
            UsageNote("会话导入只支持 Codex；不会与代理请求相加。")
            Text("请求状态", style = MaterialTheme.typography.titleSmall)
            UsageFilterChoices(UsageStatusFilter.entries, status, { it.label }, "usage-filter-status", { it.wire }) { status = it }
            PocketTextField(provider, { provider = it }, Modifier.testTag("usage-filter-provider"), label = "提供方 ID（空白为全部，精确匹配）", singleLine = true)
            PocketTextField(model, { model = it }, Modifier.testTag("usage-filter-model"), label = "模型（空白为全部，精确匹配）", singleLine = true)
            if (candidate == null) Text("请检查日期、时区与筛选值；日期范围最多 31 天，标识最多 256 UTF-8 字节。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { PocketButton("应用筛选", { candidate?.let(onApply) }, Modifier.testTag("usage-filter-apply"), enabled = candidate != null) },
        dismissButton = { PocketButton("取消", onDismiss, secondary = true) })
}

@Composable
private fun <T> UsageFilterChoices(options: List<T>, selected: T, label: (T) -> String, tag: String,
    id: (T) -> String = { it.toString() }, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { option -> PocketPill(label(option), option == selected, { onSelect(option) }, modifier = Modifier.weight(1f).testTag("$tag:${id(option)}")) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
