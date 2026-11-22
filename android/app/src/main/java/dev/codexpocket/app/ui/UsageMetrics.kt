package dev.codexpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

/** A reading with its exact text, a shorter fallback for narrow cards, and how to render steps while it counts. */
internal class UsageFigure(val value: Double?, val text: String, val format: (Double) -> String, val compact: String = text,
    val compactFormat: (Double) -> String = format)

private fun whole(value: Double) = BigDecimal.valueOf(value.roundToLong().coerceAtLeast(0))
internal fun usageCostFigure(amount: UsageCostAmount) = BigDecimal(amount.knownUsd).let { usd ->
    UsageFigure(usd.toDouble(), usageMoney(usd), { usageMoney(BigDecimal.valueOf(it)) }, "$" + usageShort(usd)) { "$" + usageShort(BigDecimal.valueOf(it)) }
}
internal fun usageCountFigure(count: ULong) = UsageFigure(count.toDouble(), usageCount(count), { usageCount(it.roundToLong().coerceAtLeast(0).toULong()) },
    usageShort(BigDecimal(count.toString()))) { usageShort(whole(it)) }
internal fun usageTokenFigure(t: UsageTotals) = if (t.requests > 0uL && t.tokens.invalidRows == t.requests) UsageFigure(null, "—", { "—" })
    else UsageFigure(t.tokens.total.toDouble(), usageShort(BigDecimal(t.tokens.total.toString())), { usageShort(whole(it)) })
internal fun usagePercentFigure(numerator: ULong, denominator: ULong) = if (denominator == 0uL) UsageFigure(null, "—", { "—" })
    else UsageFigure(numerator.toDouble() * 100 / denominator.toDouble(), usagePercent(numerator, BigDecimal(denominator.toString())),
        { BigDecimal.valueOf(it).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%" })

/** The largest size at which the exact reading fits [widthPx] on one line; the short form when none does. */
@Composable
internal fun UsageFigureText(figure: UsageFigure, widthPx: Int, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val base = Pocket.type.number
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val (short, size) = remember(figure.text, figure.compact, widthPx, density.density, density.fontScale, base) {
        fun fits(text: String, sp: Int) = measurer.measure(text, base.copy(fontSize = sp.sp), softWrap = false, maxLines = 1, density = density).size.width <= widthPx
        val exact = listOf(26, 22).map { false to it }
        val candidates = if (figure.compact == figure.text) exact + listOf(19, 16, 14, 12).map { false to it }
            else exact + listOf(26, 22, 19, 16, 14, 12).map { true to it }
        candidates.firstOrNull { (compact, sp) -> fits(if (compact) figure.compact else figure.text, sp) } ?: candidates.last()
    }
    PRollingNumber(figure.value, if (short) figure.compact else figure.text, modifier.fillMaxWidth(), base.copy(fontSize = size.sp, lineHeight = (size + 6).sp), color,
        format = if (short) figure.compactFormat else figure.format)
}

private class UsageTile(val id: String, val label: String, val figure: UsageFigure, val note: String, val series: List<Double?>,
    val detail: String = figure.text)

@Composable
private fun UsageTileHeader(label: String, id: String, series: List<Double?>?, grow: () -> Float) {
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PText(label, Modifier.weight(1f), style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (series != null) UsageSparkline(series, Modifier.size(44.dp, 20.dp).testTag("usage-spark:$id"), grow = grow)
    }
}

@Composable
private fun UsageMetricTile(tile: UsageTile, width: Dp, grow: () -> Float, modifier: Modifier, onDetail: (String) -> Unit) {
    val inner = with(LocalDensity.current) { (width - 28.dp).roundToPx().coerceAtLeast(1) }
    UsagePressCard({ onDetail(tile.label) }, "${tile.label}：${tile.detail}；${tile.note}；点按查看明细", modifier.testTag("usage-metric:${tile.id}")) {
        UsageTileHeader(tile.label, tile.id, tile.series, grow)
        UsageFigureText(tile.figure, inner, Modifier.testTag("usage-value:${tile.id}"))
        UsageNote(tile.note)
    }
}

/**
 * Tiles side by side only while every exact reading still fits its half; otherwise one to a row,
 * because a full-width tile is better than a shortened figure.
 */
@Composable
private fun UsageTileRow(tiles: List<UsageTile>, paired: Boolean, width: Dp, grow: () -> Float, onDetail: (String) -> Unit) {
    if (tiles.isEmpty()) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = Pocket.type.number
    val each = (width - 8.dp * (tiles.size - 1)) / tiles.size
    val exact = remember(tiles.map { it.figure.text }, each, density.density, density.fontScale, style) {
        val room = with(density) { (each - 28.dp).roundToPx() }
        tiles.all { measurer.measure(it.figure.text, style.copy(fontSize = 22.sp), softWrap = false, maxLines = 1, density = density).size.width <= room }
    }
    if (paired && exact && tiles.size > 1) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { UsageMetricTile(it, each, grow, Modifier.weight(1f).fillMaxHeight(), onDetail) }
    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { tiles.forEach { UsageMetricTile(it, width, grow, Modifier, onDetail) } }
}

@Composable
private fun usagePaired(width: Dp) = width / LocalDensity.current.fontScale >= 240.dp

/** Cost and calls first, then what the tokens were made of and how many calls succeeded. */
@Composable
internal fun UsageOverview(t: UsageTotals, trend: List<UsageTrendBucket>, grow: () -> Float, onDetail: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("usage-metrics")) {
        val width = maxWidth
        val paired = usagePaired(width)
        val primary = remember(t, trend) {
            listOfNotNull(
                // A source that saved no amount has no cost tile; the detail sheet still explains the coverage.
                if (t.costs.total.knownRows > 0uL) UsageTile("cost", "费用", usageCostFigure(t.costs.total),
                    "${usageCount(t.costs.total.missingRows + t.costs.total.invalidRows)} 次费用未记录", trend.map { it.totals.chartValue(UsageTrendMetric.Cost) },
                    detail = t.costs.total.display()) else null,
                UsageTile("requests", "调用", usageCountFigure(t.requests), "成功 ${usageCompact(t.success)} 次", trend.map { it.totals.requests.toDouble() }),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageTileRow(primary, paired, width, grow, onDetail)
            if (paired) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val narrow = (width - 8.dp) * (1f / 2.6f)
                UsageTokenTile(t, trend, width - 8.dp - narrow, grow, Modifier.weight(1.6f).fillMaxHeight(), onDetail)
                UsageSuccessTile(t, trend, narrow, false, grow, Modifier.weight(1f).fillMaxHeight(), onDetail)
            } else {
                UsageTokenTile(t, trend, width, grow, Modifier, onDetail)
                UsageSuccessTile(t, trend, width, true, grow, Modifier, onDetail)
            }
        }
    }
}

@Composable
private fun UsageTokenTile(t: UsageTotals, trend: List<UsageTrendBucket>, width: Dp, grow: () -> Float, modifier: Modifier, onDetail: (String) -> Unit) {
    val c = Pocket.colors
    val series = usageSeries()
    val density = LocalDensity.current
    val inner = with(density) { (width - 28.dp).roundToPx().coerceAtLeast(1) }
    val parts = listOf(t.tokens.cacheRead, t.tokens.freshInput, t.tokens.cacheWrite, t.tokens.output)
    val legend = parts.mapIndexed { i, part -> "${UsageTokenLabels[i]} ${usageCompact(part)}" }
    val measurer = rememberTextMeasurer()
    val legendStyle = usageLegendStyle()
    val textWidth = rememberUsageTextWidth()
    val (first, second) = remember(legend, density.density, density.fontScale, legendStyle) {
        val mark = with(density) { 12.dp.roundToPx() }
        val widths = legend.map { measurer.measure(it, legendStyle, softWrap = false, maxLines = 1, density = density).size.width + mark }
        maxOf(widths[0], widths[2]) to maxOf(widths[1], widths[3])
    }
    val gap = with(density) { 12.dp.roundToPx() }
    val mix = UsageTokenLabels.indices.joinToString("，") { "${UsageTokenLabels[it]} ${usageCompact(parts[it])}" }
    val figure = usageTokenFigure(t)
    val input = t.tokens.freshInput + t.tokens.cacheRead + t.tokens.cacheWrite
    val cache = usagePercentFigure(t.tokens.cacheRead, input)
    UsagePressCard({ onDetail("总令牌") }, "总令牌：${figure.text}；$mix；缓存命中率 ${cache.text}；点按查看明细", modifier.testTag("usage-metric:tokens")) {
        UsageTileHeader("令牌", "tokens", remember(trend) { trend.map { it.totals.chartValue(UsageTrendMetric.Tokens) } }, grow)
        UsageFigureText(figure, inner, Modifier.testTag("usage-value:tokens"))
        Canvas(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(8.dp).clip(PocketRadii.capsule).testTag("usage-token-mix")
            .semantics { contentDescription = "令牌构成：$mix" }) {
            drawRect(c.fillStrong)
            val sum = parts.sumOf { it.toDouble() }
            if (sum <= 0) return@Canvas
            var x = 0f
            parts.forEachIndexed { i, part ->
                val w = (part.toDouble() / sum).toFloat() * size.width * grow().coerceIn(0f, 1f)
                if (w > 0f) drawRect(series.tokens[i], Offset(x, 0f), Size(w, size.height))
                x += w
            }
        }
        // Two columns when both fit, one otherwise; decided here so the tile's height never depends on a wrap it cannot predict.
        if (first + second + gap <= inner) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(0, 2).forEach { i -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UsageLegend(series.tokens[i], legend[i], Modifier.width(with(density) { first.toDp() }))
                UsageLegend(series.tokens[i + 1], legend[i + 1])
            } }
        } else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { legend.forEachIndexed { i, text -> UsageLegend(series.tokens[i], text) } }
        if (cache.value != null) {
            PDivider(Modifier.padding(top = 2.dp), color = c.line.copy(alpha = .7f))
            val name = "缓存命中率"
            val rates = remember(trend) { trend.map { usageCacheRate(it.totals) } }
            val row = Modifier.fillMaxWidth().testTag("usage-metric:cache").semantics(mergeDescendants = true) { }
            val rate = @Composable { PRollingNumber(cache.value, cache.text, Modifier.width(IntrinsicSize.Max).testTag("usage-value:cache"), usageNumber(), format = cache.format) }
            // The name keeps one line: beside the series and the rate while all three fit, above them otherwise.
            if (textWidth(name, Pocket.type.caption) + textWidth(cache.text, usageNumber()) + 60.dp <= width - 28.dp)
                Row(row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageNote(name, Modifier.weight(1f), maxLines = 1)
                    UsageSparkline(rates, Modifier.size(44.dp, 20.dp).testTag("usage-spark:cache"), grow = grow)
                    rate()
                }
            else Column(row, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                UsageNote(name, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageSparkline(rates, Modifier.weight(1f).height(20.dp).testTag("usage-spark:cache"), grow = grow)
                    rate()
                }
            }
        }
    }
}

@Composable
private fun UsageSuccessTile(t: UsageTotals, trend: List<UsageTrendBucket>, width: Dp, wide: Boolean, grow: () -> Float, modifier: Modifier,
    onDetail: (String) -> Unit) {
    val c = Pocket.colors
    val series = usageSeries()
    val rate = usagePercentFigure(t.success, t.requests)
    val failed = usageCountFigure(t.errors)
    val failedInk = if (t.errors > 0uL) c.danger else c.inkMuted
    val ring = @Composable { size: Dp ->
        PRing(if (t.requests == 0uL) 0f else (t.success.toDouble() / t.requests.toDouble()).toFloat(), Modifier.size(size), stroke = 6.dp,
            track = if (t.errors > 0uL) series.failed.copy(alpha = .3f) else c.fillStrong, color = c.positive)
    }
    val errors = remember(trend) { trend.map { it.totals.errors.toDouble() } }
    // The failures and their daily course sit together, so the red line is never read as the success rate.
    val failures = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            UsageNote("失败", color = failedInk, maxLines = 1)
            PRollingNumber(failed.value, if (t.errors >= 100000uL) failed.compact else failed.text, Modifier.width(IntrinsicSize.Max).testTag("usage-value:errors"),
                Pocket.type.caption.copy(fontFeatureSettings = "tnum"), failedInk, format = if (t.errors >= 100000uL) failed.compactFormat else failed.format)
        }
    }
    val px = with(LocalDensity.current) { (width - 28.dp - if (wide) 124.dp else 0.dp).roundToPx().coerceAtLeast(1) }
    UsagePressCard({ onDetail("成功率") }, "成功率：${rate.text}；成功 ${usageCount(t.success)} 次，失败 ${usageCount(t.errors)} 次；点按查看明细",
        modifier.testTag("usage-metric:errors")) {
        UsageTileHeader("成功率", "errors", null, grow)
        if (wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ring(56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                UsageFigureText(rate, px, Modifier.testTag("usage-value:success"))
                failures()
            }
            UsageSparkline(errors, Modifier.size(44.dp, 20.dp).testTag("usage-spark:errors"), series.failed, grow)
        } else {
            Box(Modifier.padding(top = 2.dp)) { ring(48.dp) }
            UsageFigureText(rate, px, Modifier.testTag("usage-value:success"))
            failures()
            UsageSparkline(errors, Modifier.fillMaxWidth().height(20.dp).testTag("usage-spark:errors"), series.failed, grow)
        }
    }
}

/** First-token time and output speed, each only when the source measured it. */
@Composable
internal fun UsagePerformanceTiles(t: UsageTotals, trend: List<UsageTrendBucket>, grow: () -> Float, onDetail: (String) -> Unit) {
    val p = t.performance
    val tiles = remember(t, trend) {
        val average = p.duration.averageMs?.let { "平均每次 ${usageDecimal(it / 1000)}s" }
        listOfNotNull(
            p.ttft.averageMs?.let { ms -> UsageTile("ttft", "首字耗时", UsageFigure(ms, usageMillis(ms), ::usageMillis), average ?: "仅成功流式请求",
                trend.map { it.totals.performance.ttft.averageMs }) },
            if (p.ttft.averageMs == null) p.duration.averageMs?.let { ms -> UsageTile("duration", "平均耗时", UsageFigure(ms, usageMillis(ms), ::usageMillis),
                "仅成功请求", trend.map { it.totals.performance.duration.averageMs }) } else null,
            p.outputSpeed.tokensPerSecond?.let { speed -> UsageTile("speed", "输出速度", UsageFigure(speed, usageDecimal(speed), ::usageDecimal),
                "tok/s · 输出 ${usageCompact(t.tokens.output)}", trend.map { it.totals.performance.outputSpeed.tokensPerSecond }, detail = usageDecimal(speed) + " tok/s") },
        )
    }
    if (tiles.isEmpty()) return
    Column(Modifier.testTag("usage-performance-tiles"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UsageHeading("性能", Modifier.padding(horizontal = 4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) { UsageTileRow(tiles, usagePaired(maxWidth), maxWidth, grow, onDetail) }
    }
}

/** Whether [UsagePerformanceTiles] has anything to show. */
internal fun usageHasPerformance(t: UsageTotals) = t.performance.let { it.ttft.averageMs != null || it.duration.averageMs != null || it.outputSpeed.tokensPerSecond != null }
