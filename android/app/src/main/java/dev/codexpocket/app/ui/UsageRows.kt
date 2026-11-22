package dev.codexpocket.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import java.math.BigDecimal

internal enum class UsageGrouping(val key: String, val label: String) { Provider("provider", "上游"), Model("model", "模型"), Session("session", "对话") }

/** One breakdown at a time: the grouping switch, the measure the rows are ranked by, and paging. */
@Composable internal fun UsageGroupHeader(grouping: UsageGrouping, options: List<UsageGrouping>, onGrouping: (UsageGrouping) -> Unit,
    page: UsageGroupPage<*>, enabled: Boolean, tokens: Boolean, measures: Boolean, onTokens: (Boolean) -> Unit, onOffset: (Int) -> Unit) {
    val key = grouping.key
    Column(Modifier.testTag("usage-$key-groups"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = PocketSpace.touch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageHeading("用量分布", Modifier.weight(1f).padding(horizontal = 4.dp))
            if (measures) UsageSegmented(listOf(UsageSegment("费用", "usage-$key-cost"), UsageSegment("令牌", "usage-$key-tokens")),
                if (tokens) 1 else 0, { onTokens(it == 1) }, Modifier.width(128.dp))
        }
        UsageSegmented(options.map { UsageSegment(it.label, "usage-group:${it.key}") }, options.indexOf(grouping), { onGrouping(options[it]) },
            Modifier.testTag("usage-grouping"))
        if (page.hasMore || page.offset > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PTextAction("上一页", { onOffset((page.offset - page.limit).coerceAtLeast(0)) }, Modifier.testTag("usage-$key-prev"), enabled = enabled && page.offset > 0)
            UsageNote("${page.offset + 1}–${page.offset + page.items.size} / ${page.totalGroups}", Modifier.weight(1f), textAlign = TextAlign.Center)
            PTextAction("下一页", { onOffset(page.offset + page.limit) }, Modifier.testTag("usage-$key-next"),
                enabled = enabled && page.hasMore && page.offset + page.limit <= 10000)
        }
    }
}

/** A group and its share of the range. Tapping narrows the whole screen to it. */
@Composable internal fun UsageGroupRow(title: String, identity: String?, totals: UsageTotals, all: UsageTotals, tag: String, tokens: Boolean,
    first: Boolean, last: Boolean, onSelect: () -> Unit) {
    val known = totals.costs.total.knownRows > 0uL
    val value = if (tokens) usageCompact(totals.tokens.total) else usageUsd(totals.costs.total)
    val numerator = if (tokens) totals.tokens.total.toDouble() else totals.costs.total.knownUsd.toDouble()
    val denominator = if (tokens) all.tokens.total.toDouble() else all.costs.total.knownUsd.toDouble()
    val fraction = if (denominator > 0) (numerator / denominator).toFloat().coerceIn(0f, 1f) else 0f
    val facts = listOfNotNull(
        if (tokens) usageUsd(totals.costs.total).takeIf { known } else "${usageCompact(totals.tokens.total)} token",
        totals.performance.outputSpeed.tokensPerSecond?.let { "${usageDecimal(it)} tok/s" },
        "${usageCompact(totals.requests)} 次")
    // The share bar closes each row, so the rows need no hairline between them.
    UsageListRow(first, last, onSelect, Modifier.testTag(tag), onClickLabel = "只看这一项", divider = false) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PText(title, Modifier.weight(1f), style = Pocket.type.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            PText(value, Modifier.width(IntrinsicSize.Max).testTag("$tag:value"), style = usageNumber(), maxLines = 1, softWrap = false)
        }
        if (identity != null) UsageNote(identity, maxLines = 2)
        UsageNote(usageFacts(facts))
        UsageShareBar(fraction, if (tokens) "令牌占比 ${(fraction * 100).toInt()}%" else "已知费用占比 ${(fraction * 100).toInt()}%", Modifier.padding(top = 4.dp))
    }
}

@Composable internal fun UsageRequestRow(request: UsageRequestDetail, zone: String, tag: String, first: Boolean, last: Boolean, onDetail: () -> Unit) {
    val ok = request.statusCode in 200..299
    UsageListRow(first, last, onDetail, Modifier.testTag(tag), onClickLabel = "查看请求明细") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PText(request.model, Modifier.weight(1f), style = Pocket.type.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            PBadge(request.statusCode.toString(), tone = if (ok) PTone.Positive else PTone.Danger)
        }
        UsageNote(usageFacts(listOf(request.providerName ?: usageShortId(request.providerId), usageTime(request.createdAt, zone).takeLast(14))))
        UsageNote(usageFacts(listOf(request.tokens?.total?.let { "${usageCompact(it)} token" } ?: "令牌未记录",
            request.costUsd?.let { usageMoney(BigDecimal(it)) } ?: "费用未记录")))
        UsageNote(request.sessionLabel, maxLines = 2)
    }
}

@Composable private fun UsageField(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PText(label, style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
        PText(value, Modifier.weight(1f), style = Pocket.type.bodySmall.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.End)
    }
}

@Composable internal fun UsageRequestSheet(r: UsageRequestDetail, zone: String, onDismiss: () -> Unit) {
    PSheet("请求明细", onDismiss, subtitle = usageTime(r.createdAt, zone) + " · " + zone, maxHeightFraction = .8f) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 8.dp).testTag("usage-request-detail"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PCard(spacing = 8.dp) {
                PText(r.model, style = Pocket.type.title)
                PText(r.sessionLabel, style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
            }
            SelectionContainer {
                PCard(spacing = 8.dp) {
                    UsageField("状态", r.statusCode.toString(), "usage-request-field:status")
                    UsageField("上游", r.providerName ?: r.providerId, "usage-request-field:provider")
                    UsageField("费用", r.costUsd?.let { "USD $it" } ?: "未记录", "usage-request-field:cost")
                    r.tokens?.let { t ->
                        UsageField("输入", usageCount(t.freshInput), "usage-request-field:input")
                        UsageField("缓存读", usageCount(t.cacheRead), "usage-request-field:cache-read")
                        UsageField("缓存写", usageCount(t.cacheWrite), "usage-request-field:cache-write")
                        UsageField("输出", usageCount(t.output), "usage-request-field:output")
                    } ?: UsageField("令牌", "未记录或无效", "usage-request-field:tokens")
                    UsageField("首字耗时", r.ttftMs?.let { "$it ms" } ?: "未记录", "usage-request-field:ttft")
                    UsageField("请求耗时", r.latencyMs?.let { "$it ms" } ?: "未记录", "usage-request-field:latency")
                    UsageField("输出速度", r.tokensPerSecond?.let { usageDecimal(it) + " tok/s" } ?: "未记录", "usage-request-field:speed")
                }
            }
            SelectionContainer {
                PCard(spacing = 8.dp) {
                    UsageField("请求 ID", r.requestId, "usage-request-field:id")
                    r.sessionId?.let { UsageField("对话 ID", it, "usage-request-field:session") }
                }
            }
        }
    }
}

/** Every saved total behind a figure or a day: counts, the stored amounts and the measured performance. */
@Composable internal fun UsageInsightSheet(title: String, totals: UsageTotals, source: UsageDataSource, onDismiss: () -> Unit) {
    PSheet(title, onDismiss, maxHeightFraction = .8f) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 8.dp).testTag("usage-insight-detail"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PCard(spacing = 8.dp) {
                UsageField("调用", usageCount(totals.requests), "usage-insight-field:requests")
                UsageField("成功", usageCount(totals.success), "usage-insight-field:success")
                UsageField("失败", usageCount(totals.errors), "usage-insight-field:errors")
                PDivider()
                UsageField("输入", usageCount(totals.tokens.freshInput), "usage-insight-field:input")
                UsageField("缓存读", usageCount(totals.tokens.cacheRead), "usage-insight-field:cache-read")
                UsageField("缓存写", usageCount(totals.tokens.cacheWrite), "usage-insight-field:cache-write")
                UsageField("输出", usageCount(totals.tokens.output), "usage-insight-field:output")
            }
            UsageCostCard(totals.costs)
            UsagePerformanceCard(totals.performance, source)
        }
    }
}

@Composable internal fun UsageCostCard(costs: UsageCosts) {
    PCard(Modifier.testTag("usage-cost-breakdown"), spacing = 6.dp) {
        UsageHeading("保存的费用")
        listOf("费用合计" to costs.total, "输入基础费用" to costs.input, "输出基础费用" to costs.output,
            "缓存读取基础费用" to costs.cacheRead, "缓存写入基础费用" to costs.cacheWrite).forEachIndexed { index, (title, amount) ->
            if (index > 0) PDivider(Modifier.padding(vertical = 2.dp))
            PText(title, style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
            val amountScroll = rememberScrollState()
            SelectionContainer {
                PText(amount.display(), Modifier.fillMaxWidth().horizontalScroll(amountScroll), style = usageNumber(), maxLines = 1, softWrap = false)
            }
            if (amountScroll.maxValue > 0) UsageNote("左右滑动查看完整金额")
            UsageNote(amount.coverageLabel())
        }
        PDivider(Modifier.padding(vertical = 2.dp))
        UsageNote("费用合计为已保存的含倍率金额，四项基础费用可能与合计不同；未按令牌补价或再次乘倍率。")
    }
}

@Composable internal fun UsagePerformanceCard(p: UsagePerformance, source: UsageDataSource) {
    PCard(Modifier.testTag("usage-performance"), spacing = 6.dp) {
        UsageHeading("成功请求实测性能")
        if (source == UsageDataSource.CodexSession) UsageNote("会话导入的推测性能已排除，没有实测样本时显示未知。")
        else UsageNote("仅成功代理请求贡献样本；TTFT 和输出速度仅使用成功流式记录。")
        listOf(Triple("TTFT", p.ttft.display(), p.ttft.coverageLabel()),
            Triple("平均耗时", p.duration.display(), p.duration.coverageLabel()),
            Triple("输出速度", p.outputSpeed.display(), p.outputSpeed.coverageLabel())).forEach { (label, value, coverage) ->
            PDivider(Modifier.padding(vertical = 2.dp))
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PText(label, Modifier.weight(1f), style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
                    PText(value, style = usageNumber(), textAlign = TextAlign.End)
                }
                UsageNote(coverage)
            }
        }
        PDivider(Modifier.padding(vertical = 2.dp))
        UsageNote("耗时读取保存的 latency_ms；输出速度为合格样本总输出 / 总生成时间，排除输出少于 100 token 或生成少于 100 ms 的样本。")
    }
}

@Composable internal fun UsageCoverageSheet(c: UsageCoverage, zone: String, onDismiss: () -> Unit) {
    PSheet("统计口径", onDismiss, maxHeightFraction = .8f) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            PCard(Modifier.testTag("usage-coverage"), spacing = 8.dp) {
                UsageHeading("记录覆盖")
                UsageNote("仅统计保留明细，历史可能不完整；未混加日汇总或其他来源。")
                PDivider()
                UsageField("匹配记录", "${usageCount(c.matchedRows)} 条", "usage-coverage-field:matched")
                UsageField("费用已知", usageCount(c.costKnownRows), "usage-coverage-field:cost-known")
                UsageField("费用缺失", usageCount(c.costMissingRows), "usage-coverage-field:cost-missing")
                UsageField("费用无效", usageCount(c.costInvalidRows), "usage-coverage-field:cost-invalid")
                UsageField("无效 token 行", usageCount(c.tokenInvalidRows), "usage-coverage-field:token-invalid")
                UsageField("日汇总表", if (c.rollupsPresent) "存在，未计入" else "未发现", "usage-coverage-field:rollups")
                UsageField("首次保留记录", c.firstObservedAt?.let { usageTime(it, zone) } ?: "未知", "usage-coverage-field:first")
                UsageField("最后保留记录", c.lastObservedAt?.let { usageTime(it, zone) } ?: "未知", "usage-coverage-field:last")
                PDivider()
                UsageNote("已记录为 0 的费用有 ${usageCount(c.zeroCostRows)} 条，定价状态未知；无法确认免费条数。")
            }
        }
    }
}
