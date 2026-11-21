package dev.codexpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import java.math.BigDecimal

@Composable internal fun <T> UsageGroupHeader(title: String, page: UsageGroupPage<T>, key: String, enabled: Boolean,
    tokens: Boolean = false, onTokens: (Boolean) -> Unit = {}, onOffset: (Int) -> Unit) {
    Column(Modifier.testTag("usage-$key-groups"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PocketPill("费用", !tokens, { onTokens(false) }, modifier = Modifier.testTag("usage-$key-cost"))
                PocketPill("令牌", tokens, { onTokens(true) }, modifier = Modifier.testTag("usage-$key-tokens"))
            }
        }
        if (page.hasMore || page.offset > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PocketTextButton({ onOffset((page.offset - page.limit).coerceAtLeast(0)) }, Modifier.weight(1f).testTag("usage-$key-prev"), enabled = enabled && page.offset > 0) { Text("上一页") }
            UsageNote("${page.offset + 1}–${page.offset + page.items.size} / ${page.totalGroups}")
            PocketTextButton({ onOffset(page.offset + page.limit) }, Modifier.weight(1f).testTag("usage-$key-next"), enabled = enabled && page.hasMore && page.offset + page.limit <= 10000) { Text("下一页") }
        }
    }
}

@Composable internal fun UsageCompactGroup(title: String, identity: String, totals: UsageTotals, all: UsageTotals,
    tag: String, tokens: Boolean = false, onSelect: () -> Unit) {
    val large = LocalDensity.current.fontScale > 1.4f
    val value = if (tokens) usageCompact(totals.tokens.total) else usageUsd(totals.costs.total)
    val numerator = if (tokens) totals.tokens.total.toDouble() else totals.costs.total.knownUsd.toDouble()
    val denominator = if (tokens) all.tokens.total.toDouble() else all.costs.total.knownUsd.toDouble()
    val fraction = if (denominator > 0) (numerator / denominator).toFloat().coerceIn(0f, 1f) else 0f
    val c = MaterialTheme.colorScheme
    PocketGlassSurface(Modifier.fillMaxWidth().testTag(tag), dense = true, onClick = onSelect) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = if (large) 3 else 2, overflow = TextOverflow.Ellipsis)
                if (!large) Text(value, style = MaterialTheme.typography.titleSmall)
            }
            UsageNote("${usageCompact(totals.tokens.total)} · ${totals.performance.outputSpeed.tokensPerSecond?.let(::usageDecimal) ?: "—"} tok/s · ${usageCompact(totals.requests)} 次")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                UsageNote(identity, Modifier.weight(1f))
                if (large) Text(value, style = MaterialTheme.typography.titleSmall)
            }
            Canvas(Modifier.fillMaxWidth().height(3.dp).semantics { contentDescription = if (tokens) "令牌占比 ${(fraction * 100).toInt()}%" else "已知费用占比 ${(fraction * 100).toInt()}%" }) {
                drawRoundRect(c.outlineVariant.copy(alpha = .6f), size = size)
                if (fraction > 0) drawRoundRect(c.primary.copy(alpha = .75f), size = Size(size.width * fraction, size.height))
            }
        }
    }
}

@Composable internal fun UsageRequestRow(request: UsageRequestDetail, zone: String, tag: String, onDetail: () -> Unit) {
    PocketGlassSurface(Modifier.fillMaxWidth().testTag(tag), dense = true, onClick = onDetail) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(request.model, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(request.statusCode.toString(), color = if (request.statusCode in 200..299) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium)
            }
            UsageNote("${request.providerName ?: usageShortId(request.providerId)} · ${usageTime(request.createdAt, zone).takeLast(14)}")
            UsageNote("${request.tokens?.total?.let(::usageCompact) ?: "—"} token · ${request.costUsd?.let { "$" + BigDecimal(it).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() } ?: "费用未记录"}")
            Text(request.sessionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun UsageRequestSheet(r: UsageRequestDetail, zone: String, onDismiss: () -> Unit) {
    PocketSheet("请求明细", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("usage-request-detail"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(r.model, style = MaterialTheme.typography.titleMedium)
            UsageNote(usageTime(r.createdAt, zone) + " · " + zone)
            UsageNote("请求 ID：${r.requestId}")
            UsageNote("上游：${r.providerName ?: r.providerId}")
            UsageNote("状态：${r.statusCode}")
            Text(r.sessionLabel, style = MaterialTheme.typography.bodyMedium)
            r.sessionId?.let { UsageNote("对话 ID：$it") }
            UsageNote("费用：${r.costUsd?.let { "USD $it" } ?: "未记录"}")
            r.tokens?.let { t ->
                UsageNote("输入 ${usageCount(t.freshInput)} · 缓存读 ${usageCount(t.cacheRead)} · 缓存写 ${usageCount(t.cacheWrite)} · 输出 ${usageCount(t.output)}")
            } ?: UsageNote("令牌：未记录或无效")
            UsageNote("首字耗时：${r.ttftMs?.let { "$it ms" } ?: "未记录"}")
            UsageNote("请求耗时：${r.latencyMs?.let { "$it ms" } ?: "未记录"}")
            UsageNote("输出速度：${r.tokensPerSecond?.let { usageDecimal(it) + " tok/s" } ?: "未记录"}")
        }
    }
}
@Composable internal fun UsageInsightSheet(title: String, totals: UsageTotals, source: UsageDataSource, onDismiss: () -> Unit) {
    PocketSheet(title, onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState()).testTag("usage-insight-detail")) {
            UsageCard {
                UsageNote("调用 ${usageCount(totals.requests)} · 成功 ${usageCount(totals.success)} · 失败 ${usageCount(totals.errors)}")
                UsageNote("输入 ${usageCount(totals.tokens.freshInput)} · 缓存读 ${usageCount(totals.tokens.cacheRead)} · 缓存写 ${usageCount(totals.tokens.cacheWrite)} · 输出 ${usageCount(totals.tokens.output)}")
            }
            UsageCostCard(totals.costs)
            UsagePerformanceCard(totals.performance, source)
        }
    }
}
