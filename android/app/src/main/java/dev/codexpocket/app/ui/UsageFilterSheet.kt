package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import java.time.LocalDate
import java.time.ZoneId

@Composable internal fun UsageFilterSheet(filter: UsageFilter, onDismiss: () -> Unit, onApply: (UsageFilter) -> Unit) {
    var start by rememberSaveable { mutableStateOf(filter.startDate.toString()) }
    var end by rememberSaveable { mutableStateOf(filter.endDate.toString()) }
    var zone by rememberSaveable { mutableStateOf(filter.zoneId) }
    var app by rememberSaveable { mutableStateOf(filter.appType) }
    var source by rememberSaveable { mutableStateOf(filter.dataSource) }
    var status by rememberSaveable { mutableStateOf(filter.status) }
    var provider by rememberSaveable { mutableStateOf(filter.providerId.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(filter.model.orEmpty()) }
    var session by rememberSaveable { mutableStateOf(filter.sessionId.orEmpty()) }
    val candidate = remember(start, end, zone, app, source, status, provider, model, session) {
        runCatching { filter.copy(startDate = LocalDate.parse(start), endDate = LocalDate.parse(end), zoneId = zone, appType = app,
            dataSource = source, status = status, providerId = provider.takeIf(String::isNotEmpty), model = model.takeIf(String::isNotEmpty),
            sessionId = session.takeIf(String::isNotEmpty), providerPage = filter.providerPage.copy(offset = 0),
            modelPage = filter.modelPage.copy(offset = 0), sessionPage = filter.sessionPage.copy(offset = 0)).also { it.toQuery() } }.getOrNull()
    }
    // The native ledger is Codex only and has neither an import source nor a per-request status to choose.
    val native = filter.source == UsageSourceKind.NativeCodex
    val quick = listOf(1, 7, 30)
    val today = runCatching { LocalDate.now(ZoneId.of(zone)) }.getOrNull()
    PSheet("日期与筛选", onDismiss, subtitle = "按自然日统计 · 最多 31 天", maxHeightFraction = .9f) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("usage-filter-sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UsageNote("日期包含开始与结束当天。", Modifier.padding(horizontal = 4.dp))
                UsageSegmented(quick.map { UsageSegment("近 $it 天", "usage-filter-days:$it") },
                    quick.indexOfFirst { today != null && end == today.toString() && start == today.minusDays(it - 1L).toString() },
                    { index -> today?.let { start = it.minusDays(quick[index] - 1L).toString(); end = it.toString() } })
                PTextField(start, { start = it }, Modifier.testTag("usage-filter-start"), label = "开始日期 yyyy-MM-dd", singleLine = true)
                PTextField(end, { end = it }, Modifier.testTag("usage-filter-end"), label = "结束日期 yyyy-MM-dd", singleLine = true)
                PTextField(zone, { zone = it }, Modifier.testTag("usage-filter-zone"), label = "时区", singleLine = true)
                if (!native) {
                UsageHeading("应用", Modifier.padding(horizontal = 4.dp))
                UsageChoices(UsageFilter.apps, app, ::usageAppLabel, "usage-filter-app") { app = it; if (it != "codex") source = UsageDataSource.Proxy; provider = ""; model = ""; session = "" }
                UsageHeading("记录来源", Modifier.padding(horizontal = 4.dp))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UsageDataSource.entries.forEach { option -> UsageChoice(option.label, source == option, { source = option; provider = ""; model = ""; session = "" },
                        Modifier.fillMaxWidth().testTag("usage-filter-source:${option.wire}"), enabled = option != UsageDataSource.CodexSession || app == "codex") }
                }
                UsageNote("代理请求与会话导入分别统计。", Modifier.padding(horizontal = 4.dp))
                UsageHeading("状态", Modifier.padding(horizontal = 4.dp))
                UsageSegmented(UsageStatusFilter.entries.map { UsageSegment(it.label, "usage-filter-status:${it.wire}") }, UsageStatusFilter.entries.indexOf(status),
                    { status = UsageStatusFilter.entries[it] })
                }
                PTextField(provider, { provider = it }, Modifier.testTag("usage-filter-provider"), label = "上游 ID（空白为全部）", singleLine = true)
                PTextField(model, { model = it }, Modifier.testTag("usage-filter-model"), label = "模型（空白为全部）", singleLine = true)
                PTextField(session, { session = it }, Modifier.testTag("usage-filter-session"), label = "对话 ID（空白为全部）", singleLine = true)
                if (candidate == null) PText("请检查日期、时区与筛选值。日期最多 31 天，标识最多 256 字节。", Modifier.padding(horizontal = 4.dp),
                    style = Pocket.type.bodySmall, color = Pocket.colors.danger)
                Spacer(Modifier.height(6.dp))
            }
            PButton("应用筛选", { candidate?.let(onApply) }, Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("usage-filter-apply"), enabled = candidate != null)
        }
    }
}

@Composable private fun <T> UsageChoices(options: List<T>, selected: T, label: (T) -> String, tag: String,
    id: (T) -> String = { it.toString() }, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { option -> UsageChoice(label(option), option == selected, { onSelect(option) }, Modifier.weight(1f).testTag("$tag:${id(option)}")) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        } }
    }
}
