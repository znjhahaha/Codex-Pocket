package dev.codexpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

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
/** Only the series the source recorded: a metric with no sample anywhere has no tab. */
internal fun usageTrendMetrics(data: UsageData): List<UsageTrendMetric> = UsageTrendMetric.entries.filter { metric ->
    when (metric) {
        UsageTrendMetric.Cost -> data.totals.costs.total.knownRows > 0uL
        UsageTrendMetric.Tokens, UsageTrendMetric.Requests -> true
        else -> data.totals.chartValue(metric) != null || data.trend.any { it.totals.chartValue(metric) != null }
    }
}
internal fun bucketRange(b: UsageTrendBucket, zone: String): String =
    Instant.ofEpochSecond(b.startAt).atZone(ZoneId.of(zone)).toLocalDate().toString()

internal fun usageCompact(value: ULong): String = usageCompact(BigDecimal(value.toString()))
internal fun usageCompact(value: BigDecimal): String {
    val unit = when { value >= BigDecimal("1000000000") -> "B" to BigDecimal("1000000000")
        value >= BigDecimal("1000000") -> "M" to BigDecimal("1000000")
        value >= BigDecimal("1000") -> "K" to BigDecimal("1000")
        else -> "" to BigDecimal.ONE }
    return value.divide(unit.second, if (unit.first.isEmpty()) 2 else 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + unit.first
}
/** The short form for constrained figures. Exact saved values remain in the detail sheet. */
internal fun usageShort(value: BigDecimal): String = if (value >= BigDecimal("1000000000000"))
    value.round(MathContext(3, RoundingMode.HALF_UP)).stripTrailingZeros().toString().replace("E+", "e") else usageCompact(value)
internal fun usageMoney(value: BigDecimal): String {
    val plain = value.setScale(2, RoundingMode.HALF_UP).toPlainString()
    return "$" + plain.substringBefore('.').reversed().chunked(3).joinToString(",").reversed() + "." + plain.substringAfter('.')
}
internal fun usageUsd(amount: UsageCostAmount): String = if (amount.knownRows == 0uL) "—" else usageMoney(BigDecimal(amount.knownUsd))
internal fun usagePercent(numerator: ULong, denominator: BigDecimal): String = if (denominator.signum() == 0) "—" else
    BigDecimal(numerator.toString()).multiply(BigDecimal(100)).divide(denominator, 1, RoundingMode.HALF_UP).toPlainString() + "%"
internal fun usageMillis(ms: Double): String = if (ms >= 1000) usageDecimal(ms / 1000) + "s" else usageDecimal(ms) + "ms"
internal fun usageCacheRate(t: UsageTotals): Double? {
    val input = t.tokens.freshInput.toDouble() + t.tokens.cacheRead.toDouble() + t.tokens.cacheWrite.toDouble()
    return if (input > 0) t.tokens.cacheRead.toDouble() * 100 / input else null
}
private fun trendReading(metric: UsageTrendMetric, value: Double?): String = when {
    value == null -> "未记录"
    metric == UsageTrendMetric.Cost -> usageMoney(BigDecimal.valueOf(value))
    metric == UsageTrendMetric.Tokens -> usageCompact(BigDecimal.valueOf(value)) + " token"
    metric == UsageTrendMetric.Requests -> usageCount(value.toLong().toULong()) + " 次"
    metric == UsageTrendMetric.Latency -> usageMillis(value)
    else -> usageDecimal(value) + " tok/s"
}

/** Marks only recorded buckets; null samples break the line, and a lone sample stays a point. */
@Composable internal fun UsageSparkline(values: List<Double?>, modifier: Modifier, color: Color = usageSeries().main, grow: () -> Float = { 1f }) {
    Canvas(modifier.semantics { contentDescription = "真实每日趋势：" + values.joinToString { it?.let(::usageDecimal) ?: "未知" } }) {
        val max = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val margin = 2.5.dp.toPx()
        val rise = (size.height - margin * 2) * grow().coerceIn(0f, 1f)
        fun point(i: Int, v: Double) = Offset(if (values.size == 1) size.width / 2 else margin + i * (size.width - margin * 2) / (values.size - 1),
            size.height - margin - (v / max).toFloat() * rise)
        var previous: Offset? = null
        val lastKnown = values.indexOfLast { it != null }
        values.forEachIndexed { i, v ->
            if (v == null) previous = null else {
                val at = point(i, v)
                val joined = previous != null
                previous?.let { drawLine(color, it, at, 1.5.dp.toPx(), StrokeCap.Round) }
                if (i == lastKnown || (!joined && values.getOrNull(i + 1) == null)) drawCircle(color, 2.dp.toPx(), at)
                previous = at
            }
        }
    }
}

@Composable @ReadOnlyComposable internal fun usageLegendStyle() = Pocket.type.caption.copy(fontFeatureSettings = "tnum")

/** A 7dp mark, a 5dp gap, then the label on one line. */
@Composable internal fun UsageLegend(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        UsageDot(color)
        PText(text, style = usageLegendStyle(), color = Pocket.colors.inkMuted, maxLines = 1, softWrap = false)
    }
}

/**
 * The daily series for one metric. Tap a day for its detail sheet; drag across to read each day in
 * place. The chart grows in with [grow] and never animates on its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun UsageTrendChart(buckets: List<UsageTrendBucket>, zone: String, metric: UsageTrendMetric, metrics: List<UsageTrendMetric>,
    onMetric: (UsageTrendMetric) -> Unit, grow: () -> Float = { 1f }, onDetail: (UsageTrendBucket) -> Unit) {
    val values = remember(buckets, metric) { buckets.map { it.totals.chartValue(metric) } }
    val max = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val c = Pocket.colors
    val series = usageSeries()
    val haptics = rememberPocketHaptics()
    val detail by rememberUpdatedState(onDetail)
    var focus by remember(buckets) { mutableStateOf<Int?>(null) }
    val focused = focus?.takeIf { it in buckets.indices }
    fun describe(i: Int) = "${bucketRange(buckets[i], zone)}：${values[i]?.let(::usageDecimal) ?: "未知"} ${metric.unit}，请求 ${usageCount(buckets[i].totals.requests)}"
    UsageCard(Modifier.testTag("usage-trend")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            UsageHeading("趋势", Modifier.weight(1f))
            UsageNote(metric.unit)
        }
        UsageSegmented(metrics.map { UsageSegment(it.label, "usage-trend:${it.name}") }, metrics.indexOf(metric), { onMetric(metrics[it]) })
        Row(Modifier.fillMaxWidth().heightIn(min = PocketSpace.touch).testTag("usage-trend-focus"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (focused == null) UsageNote("拖动查看每日数值，点按查看当日明细", Modifier.weight(1f))
            else {
                Column(Modifier.weight(1f)) {
                    UsageNote(bucketRange(buckets[focused], zone) + if (metric == UsageTrendMetric.Requests) "" else " · ${usageCount(buckets[focused].totals.requests)} 次", maxLines = 1)
                    PText(trendReading(metric, values[focused]), style = usageNumber(17, FontWeight.SemiBold), maxLines = 1, softWrap = false)
                }
                PTextAction("明细", { detail(buckets[focused]) }, Modifier.testTag("usage-trend-detail"), tint = c.accent)
            }
        }
        if (metric == UsageTrendMetric.Tokens) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            UsageTokenLabels.forEachIndexed { i, label -> UsageLegend(series.tokens[i], label) }
        }
        if (metric == UsageTrendMetric.Requests) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            UsageLegend(series.main, "成功"); UsageLegend(series.failed, "失败")
        }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.height(168.dp).padding(end = 8.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                UsageNote(usageCompact(BigDecimal.valueOf(max)), maxLines = 1)
                UsageNote("0", maxLines = 1)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(Modifier.fillMaxWidth().height(168.dp).testTag("usage-trend-chart")
                .semantics {
                    contentDescription = "${metric.label}趋势，时区 $zone，纵轴 0 到 ${usageDecimal(max)}；" + buckets.indices.joinToString("；") { describe(it) }
                    stateDescription = focused?.let { "已选 " + describe(it) } ?: "未选择日期"
                    if (buckets.isNotEmpty()) onClick("查看当日明细") { detail(buckets[focused ?: buckets.lastIndex]); true }
                    if (buckets.size > 1) {
                        progressBarRangeInfo = ProgressBarRangeInfo((focused ?: buckets.lastIndex).toFloat(), 0f..buckets.lastIndex.toFloat(), buckets.size - 2)
                        setProgress { target -> focus = target.roundToInt().coerceIn(buckets.indices); true }
                    }
                }
                .pointerInput(buckets) { detectTapGestures { point ->
                    if (buckets.isNotEmpty()) ((point.x / size.width) * buckets.size).toInt().coerceIn(buckets.indices).let { focus = it; detail(buckets[it]) }
                } }
                .pointerInput(buckets) {
                    // One tick per day crossed, none while the finger stays inside a day.
                    fun inspect(x: Float) {
                        if (buckets.isEmpty()) return
                        val index = ((x / size.width) * buckets.size).toInt().coerceIn(buckets.indices)
                        if (index != focus) { focus = index; haptics.perform(PocketHaptic.Tick) }
                    }
                    detectHorizontalDragGestures(onDragStart = { inspect(it.x) }) { change, _ -> change.consume(); inspect(change.position.x) }
                }) {
                val rise = grow().coerceIn(0f, 1f)
                val bottom = size.height - 4.dp.toPx()
                val height = bottom - 8.dp.toPx()
                repeat(4) { i -> drawLine(series.grid, Offset(0f, bottom - height * i / 3), Offset(size.width, bottom - height * i / 3), 1.dp.toPx()) }
                if (buckets.isEmpty()) return@Canvas
                val slot = size.width / buckets.size
                val bar = (slot * .5f).coerceAtMost(22.dp.toPx())
                val corner = CornerRadius((bar / 2).coerceAtMost(3.dp.toPx()))
                fun tone(color: Color, i: Int) = if (focused == null || focused == i) color else color.copy(alpha = color.alpha * .4f)
                focused?.let { drawRoundRect(c.fill, Offset(it * slot, 0f), Size(slot, bottom), CornerRadius(4.dp.toPx())) }
                var previous: Offset? = null
                values.forEachIndexed { i, value ->
                    val x = (i + .5f) * slot
                    if (value == null) {
                        previous = null
                        drawLine(series.idle, Offset(x - 3.dp.toPx(), bottom), Offset(x + 3.dp.toPx(), bottom), 2.dp.toPx(), StrokeCap.Round)
                        return@forEachIndexed
                    }
                    val y = bottom - (value / max).toFloat() * height * rise
                    when (metric) {
                        UsageTrendMetric.Latency, UsageTrendMetric.Speed -> {
                            val point = Offset(x, y)
                            previous?.let { drawLine(series.main, it, point, 2.dp.toPx(), StrokeCap.Round) }
                            drawCircle(tone(series.main, i), (if (focused == i) 4.5f else 3f).dp.toPx(), point)
                            previous = point
                        }
                        UsageTrendMetric.Tokens -> {
                            val t = buckets[i].totals.tokens
                            var base = bottom
                            listOf(t.cacheRead, t.freshInput, t.cacheWrite, t.output).forEachIndexed { j, n ->
                                val h = (n.toDouble() / max).toFloat() * height * rise
                                if (h > 0f) drawRect(tone(series.tokens[j], i), Offset(x - bar / 2, base - h), Size(bar, h))
                                base -= h
                            }
                        }
                        UsageTrendMetric.Requests -> {
                            val failed = (buckets[i].totals.errors.toDouble().coerceAtMost(value) / max).toFloat() * height * rise
                            if (value <= 0) drawCircle(tone(series.main, i), 2.dp.toPx(), Offset(x, bottom))
                            else {
                                drawRoundRect(tone(series.main, i), Offset(x - bar / 2, y), Size(bar, bottom - y), corner)
                                if (failed > 0f) drawRoundRect(tone(series.failed, i), Offset(x - bar / 2, y), Size(bar, failed.coerceAtLeast(1.5.dp.toPx())), corner)
                            }
                        }
                        UsageTrendMetric.Cost ->
                            if (value > 0) drawRoundRect(tone(series.main, i), Offset(x - bar / 2, y), Size(bar, bottom - y), corner)
                            else drawCircle(tone(series.main, i), 2.dp.toPx(), Offset(x, bottom))
                    }
                }
            }
            if (buckets.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                UsageNote(bucketRange(buckets.first(), zone).takeLast(5), maxLines = 1)
                if (buckets.size > 1) UsageNote(bucketRange(buckets.last(), zone).takeLast(5), maxLines = 1)
            }
            }
        }
        UsageNote(if (metric == UsageTrendMetric.Cost) "已知费用小计；未记录费用留空。" else "无样本留空；已记录的零值保留。")
    }
}
