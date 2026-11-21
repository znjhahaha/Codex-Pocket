package dev.codexpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.codexpocket.app.data.*
import java.math.BigDecimal
import java.math.RoundingMode

internal enum class UsageTrendMetric(val label: String, val unit: String) {
    Cost("费用", "USD"), Tokens("令牌", "token"), Requests("调用", "次"), Latency("延迟", "ms"), Speed("速度", "tok/s")
}
internal fun UsageTotals.chartValue(metric: UsageTrendMetric): Double? = when (metric) {
    UsageTrendMetric.Cost -> costs.total.takeIf { it.knownRows > 0uL || requests == 0uL }?.knownUsd?.toDouble()
    UsageTrendMetric.Tokens -> tokens.total.toDouble().takeUnless { requests > 0uL && tokens.invalidRows == requests }
    UsageTrendMetric.Requests -> requests.toDouble()
    UsageTrendMetric.Latency -> performance.ttft.averageMs
    UsageTrendMetric.Speed -> performance.outputSpeed.tokensPerSecond
}
internal fun usageCompact(value: ULong): String = usageCompact(BigDecimal(value.toString()))
internal fun usageCompact(value: BigDecimal): String {
    val unit = when { value >= BigDecimal("1000000000") -> "B" to BigDecimal("1000000000")
        value >= BigDecimal("1000000") -> "M" to BigDecimal("1000000")
        value >= BigDecimal("1000") -> "K" to BigDecimal("1000")
        else -> "" to BigDecimal.ONE }
    return value.divide(unit.second, if (unit.first.isEmpty()) 2 else 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + unit.first
}
internal fun usageUsd(amount: UsageCostAmount): String = if (amount.knownRows == 0uL) "—" else "$" + BigDecimal(amount.knownUsd).setScale(2, RoundingMode.HALF_UP).toPlainString()
internal fun usagePercent(numerator: ULong, denominator: BigDecimal): String = if (denominator.signum() == 0) "—" else
    BigDecimal(numerator.toString()).multiply(BigDecimal(100)).divide(denominator, 1, RoundingMode.HALF_UP).toPlainString() + "%"
private fun cacheValue(t: UsageTotals): Double? {
    val input = t.tokens.freshInput.toDouble() + t.tokens.cacheRead.toDouble() + t.tokens.cacheWrite.toDouble()
    return if (input > 0) t.tokens.cacheRead.toDouble() * 100 / input else null
}

@Composable internal fun UsageBackdrop(backdrop: LayerBackdrop) {
    val c = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
        drawRect(c.background)
        drawRect(Brush.radialGradient(listOf(c.primary.copy(alpha = .10f), Color.Transparent), Offset(size.width * .08f, size.height * .12f), size.width * .8f))
        drawRect(Brush.radialGradient(listOf(c.tertiary.copy(alpha = .06f), Color.Transparent), Offset(size.width, size.height * .65f), size.width))
    }
}
private data class Kpi(val id: String, val label: String, val value: String, val note: String, val series: List<Double?>,
    val compact: String = value, val detail: String = value)

/** A short alternative for constrained KPI cards. Exact saved values remain in the detail sheet. */
private fun kpiNumber(value: BigDecimal): String {
    val rounded = value.round(java.math.MathContext(3, RoundingMode.HALF_UP)).stripTrailingZeros()
    return if (rounded >= BigDecimal("1000000000000")) rounded.toString().replace("E+", "e") else usageCompact(rounded)
}

@Composable internal fun UsageKpis(t: UsageTotals, trend: List<UsageTrendBucket>, onDetail: (String) -> Unit) {
    val input = BigDecimal(t.tokens.freshInput.toString()) + BigDecimal(t.tokens.cacheRead.toString()) + BigDecimal(t.tokens.cacheWrite.toString())
    val metrics = remember(t, trend) {
        listOf(
            Kpi("tokens", "总令牌", if (t.requests > 0uL && t.tokens.invalidRows == t.requests) "—" else usageCompact(t.tokens.total),
                "输入 ${usageCompact(input)} · 输出 ${usageCompact(t.tokens.output)}", trend.map { it.totals.chartValue(UsageTrendMetric.Tokens) },
                compact = if (t.requests > 0uL && t.tokens.invalidRows == t.requests) "—" else kpiNumber(BigDecimal(t.tokens.total.toString()))),
            Kpi("cost", "费用", usageUsd(t.costs.total), "${usageCount(t.costs.total.missingRows + t.costs.total.invalidRows)} 次费用未记录", trend.map { it.totals.chartValue(UsageTrendMetric.Cost) },
                compact = if (t.costs.total.knownRows == 0uL) "—" else "$" + kpiNumber(BigDecimal(t.costs.total.knownUsd)), detail = t.costs.total.display()),
            Kpi("requests", "调用次数", usageCompact(t.requests), "成功 ${usageCompact(t.success)} 次", trend.map { it.totals.requests.toDouble() }, compact = kpiNumber(BigDecimal(t.requests.toString()))),
            Kpi("cache", "缓存命中率", usagePercent(t.tokens.cacheRead, input), "读 ${usageCompact(t.tokens.cacheRead)} · 写 ${usageCompact(t.tokens.cacheWrite)}", trend.map { cacheValue(it.totals) }),
            Kpi("ttft", "首字耗时", t.performance.ttft.averageMs?.let { if (it >= 1000) usageDecimal(it / 1000) + "s" else usageDecimal(it) + "ms" } ?: "—",
                "平均每次 ${t.performance.duration.averageMs?.let { usageDecimal(it / 1000) + "s" } ?: "—"}", trend.map { it.totals.performance.ttft.averageMs },
                compact = t.performance.ttft.averageMs?.let { kpiNumber(BigDecimal.valueOf(if (it >= 1000) it / 1000 else it)) + if (it >= 1000) "s" else "ms" } ?: "—"),
            Kpi("speed", "输出速度", t.performance.outputSpeed.tokensPerSecond?.let(::usageDecimal) ?: "—", "tok/s · 输出 ${usageCompact(t.tokens.output)}", trend.map { it.totals.performance.outputSpeed.tokensPerSecond },
                compact = t.performance.outputSpeed.tokensPerSecond?.let { kpiNumber(BigDecimal.valueOf(it)) } ?: "—"),
            Kpi("errors", "失败", usageCompact(t.errors), "占 ${usagePercent(t.errors, BigDecimal(t.requests.toString()))}", trend.map { it.totals.errors.toDouble() }, compact = kpiNumber(BigDecimal(t.errors.toString()))),
        )
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val numberStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val labelStyle = MaterialTheme.typography.bodySmall
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val gridWidth = maxWidth
    Column(Modifier.testTag("usage-metrics"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { row ->
            // Resolve widths outside the equal-height row: no text/graph competition or intrinsic subcomposition.
            val innerWidth = (gridWidth - 8.dp * (row.size - 1)) / row.size - 24.dp
            val widthPx = with(density) { innerWidth.toPx() }.toInt().coerceAtLeast(1)
            fun fits(text: String, style: TextStyle, width: Int = widthPx) =
                measurer.measure(text, style, softWrap = false, maxLines = 1).size.width <= width
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    val candidates = listOf(m.value to 26, m.value to 24) + listOf(26, 24, 22, 20, 18, 16, 14, 12).map { m.compact to it }
                    val (value, size) = candidates.firstOrNull { (text, sp) -> fits(text, numberStyle.copy(fontSize = sp.sp)) } ?: candidates.last()
                    val headerTrend = density.fontScale <= 1.4f && fits(m.label, labelStyle, widthPx - with(density) { 48.dp.roundToPx() })
                    PocketGlassSurface(Modifier.weight(1f).fillMaxHeight().testTag("usage-metric:${m.id}")
                        .semantics(mergeDescendants = true) { contentDescription = "${m.label}：${m.detail}；${m.note}；点按查看明细" },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), dense = true, onClick = { onDetail(m.label) }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                UsageNote(m.label, Modifier.weight(1f))
                                if (headerTrend) UsageSparkline(m.series, Modifier.width(42.dp).height(20.dp).testTag("usage-spark:${m.id}"), m.id == "errors")
                            }
                            Text(value, Modifier.fillMaxWidth().testTag("usage-value:${m.id}"), style = numberStyle.copy(fontSize = size.sp),
                                maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurface)
                            if (!headerTrend) {
                                UsageSparkline(m.series, Modifier.fillMaxWidth().height(24.dp).testTag("usage-spark:${m.id}"), m.id == "errors")
                            }
                            UsageNote(m.note)
                        }
                    }
                }
            }
        }
    }
    }
}

/** Marks only recorded buckets; null samples break the line, and a single bucket stays a point. */
@Composable internal fun UsageSparkline(values: List<Double?>, modifier: Modifier, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = "真实每日趋势：" + values.joinToString { it?.let(::usageDecimal) ?: "未知" } }) {
        val max = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val margin = 2.dp.toPx()
        var previous: Offset? = null
        values.forEachIndexed { i, v ->
            if (v == null) previous = null else {
                val point = Offset(if (values.size == 1) size.width / 2 else i * size.width / (values.size - 1),
                    size.height - margin - (v / max).toFloat() * (size.height - margin * 2))
                previous?.let { drawLine(color, it, point, 1.5.dp.toPx(), StrokeCap.Round) }
                drawCircle(color, 1.5.dp.toPx(), point)
                previous = point
            }
        }
    }
}

@Composable internal fun UsageTrendChart(buckets: List<UsageTrendBucket>, zone: String, metric: UsageTrendMetric,
    onMetric: (UsageTrendMetric) -> Unit, onDetail: (UsageTrendBucket) -> Unit) {
    val values = remember(buckets, metric) { buckets.map { it.totals.chartValue(metric) } }
    val max = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val c = MaterialTheme.colorScheme
    val tokenColors = listOf(Color(0xFF279F78), Color(0xFF3D80CA), Color(0xFFAC5899), Color(0xFFBC862F))
    UsageCard(Modifier.testTag("usage-trend")) {
        Text("趋势", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UsageTrendMetric.entries.forEach { m -> PocketPill(m.label, metric == m, { onMetric(m) }, modifier = Modifier.testTag("usage-trend:${m.name}")) }
        }
        UsageNote("${metric.unit} · 点按查看当日明细")
        if (metric == UsageTrendMetric.Tokens) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("缓存读", "输入", "缓存写", "输出").forEachIndexed { i, label -> Text("● $label", color = tokenColors[i], style = MaterialTheme.typography.labelSmall) }
        }
        Row(Modifier.fillMaxWidth().height(168.dp)) {
            Column(Modifier.width(40.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                UsageNote(usageCompact(BigDecimal.valueOf(max)))
                UsageNote("0")
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().testTag("usage-trend-chart")
                .semantics { contentDescription = "${metric.label}趋势，时区 $zone，纵轴 0 到 ${usageDecimal(max)}；" + buckets.mapIndexed { i, b -> "${bucketRange(b, zone)}：${values[i]?.let(::usageDecimal) ?: "未知"} ${metric.unit}，请求 ${usageCount(b.totals.requests)}" }.joinToString("；") }
                .pointerInput(buckets, metric) { detectTapGestures { point ->
                    if (buckets.isNotEmpty()) onDetail(buckets[((point.x / size.width) * buckets.size).toInt().coerceIn(buckets.indices)])
                } }) {
                val bottom = size.height - 4.dp.toPx()
                val height = bottom - 8.dp.toPx()
                repeat(4) { i -> drawLine(c.outlineVariant.copy(alpha = .6f), Offset(0f, bottom - height * i / 3), Offset(size.width, bottom - height * i / 3), 1.dp.toPx()) }
                if (buckets.isNotEmpty()) {
                    val slot = size.width / buckets.size
                    var previous: Offset? = null
                    values.forEachIndexed { i, value ->
                        val x = (i + .5f) * slot
                        if (value == null) { previous = null; drawLine(c.onSurfaceVariant, Offset(x - 3.dp.toPx(), bottom), Offset(x + 3.dp.toPx(), bottom), 2.dp.toPx()) }
                        else {
                            val y = bottom - (value / max).toFloat() * height
                            if (metric == UsageTrendMetric.Latency || metric == UsageTrendMetric.Speed) {
                                val point = Offset(x, y)
                                previous?.let { drawLine(c.primary, it, point, 2.dp.toPx()) }
                                drawCircle(c.primary, 3.dp.toPx(), point); previous = point
                            } else if (metric == UsageTrendMetric.Tokens) {
                                val t = buckets[i].totals.tokens
                                var base = bottom
                                listOf(t.cacheRead, t.freshInput, t.cacheWrite, t.output).forEachIndexed { j, n ->
                                    val h = (n.toDouble() / max).toFloat() * height
                                    drawRect(tokenColors[j], Offset(x - slot * .28f, base - h), Size(slot * .56f, h)); base -= h
                                }
                            } else if (value > 0) drawRoundRect(c.primary, Offset(x - slot * .28f, y), Size(slot * .56f, bottom - y), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                            else drawCircle(c.primary, 2.dp.toPx(), Offset(x, bottom))
                        }
                    }
                }
            }
        }
        if (buckets.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            UsageNote(bucketRange(buckets.first(), zone).takeLast(5))
            if (buckets.size > 1) UsageNote(bucketRange(buckets.last(), zone).takeLast(5))
        }
        UsageNote(if (metric == UsageTrendMetric.Cost) "已知费用小计；未记录费用留空。" else "无样本留空；已记录的零值保留。")
    }
}
