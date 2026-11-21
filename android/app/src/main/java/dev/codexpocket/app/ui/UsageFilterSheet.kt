package dev.codexpocket.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import java.time.LocalDate
import java.time.ZoneId

@Composable internal fun UsageFilterBar(f: UsageFilter, onOpen: () -> Unit, onFilter: (UsageFilter) -> Unit) {
    val active = listOfNotNull(
        f.status.takeUnless { it == UsageStatusFilter.All }?.let { "状态：${it.label}" },
        f.providerId?.let { "上游：${usageShortId(it)}" },
        f.model?.let { "模型：$it" },
        f.sessionId?.let { "对话：${usageShortId(it)}" },
    )
    val date = "${f.startDate.toString().takeLast(5)} – ${f.endDate.toString().takeLast(5)}"
    val filterLabel = if (active.isEmpty()) "筛选" else "筛选 · ${active.size}"
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (largeText) {
            PocketPill(date, false, onOpen, modifier = Modifier.fillMaxWidth().testTag("usage-filter-open"))
            PocketPill(filterLabel, active.isNotEmpty(), onOpen, modifier = Modifier.fillMaxWidth().testTag("usage-filters-button"))
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PocketPill(date, false, onOpen, modifier = Modifier.weight(1f).testTag("usage-filter-open"))
                PocketPill(filterLabel, active.isNotEmpty(), onOpen, modifier = Modifier.testTag("usage-filters-button"))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1 to "今天", 7 to "近 7 天", 30 to "近 30 天").forEach { (days, label) ->
                PocketTextButton({ val today = LocalDate.now(ZoneId.of(f.zoneId)); onFilter(f.withDates(today.minusDays(days - 1L), today)) },
                    Modifier.weight(1f).testTag("usage-date:$days")) { Text(label) }
            }
        }
        if (active.isNotEmpty()) UsageNote(active.joinToString(" · "), Modifier.testTag("usage-active-filters"))
    }
}

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
    PocketSheet("日期与筛选", onDismiss, subtitle = "按自然日统计 · 最多 31 天") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("usage-filter-sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UsageNote("日期包含开始与结束当天。")
                UsageChoices(listOf(1, 7, 30), 0, { "近 $it 天" }, "usage-filter-days") { days ->
                    runCatching { LocalDate.now(ZoneId.of(zone)) }.getOrNull()?.let { today -> start = today.minusDays(days - 1L).toString(); end = today.toString() }
                }
                PocketTextField(start, { start = it }, Modifier.testTag("usage-filter-start"), label = "开始日期 yyyy-MM-dd", singleLine = true)
                PocketTextField(end, { end = it }, Modifier.testTag("usage-filter-end"), label = "结束日期 yyyy-MM-dd", singleLine = true)
                PocketTextField(zone, { zone = it }, Modifier.testTag("usage-filter-zone"), label = "时区", singleLine = true)
                Text("应用", style = MaterialTheme.typography.titleSmall)
                UsageChoices(UsageFilter.apps, app, ::usageAppLabel, "usage-filter-app") { app = it; if (it != "codex") source = UsageDataSource.Proxy; provider = ""; model = ""; session = "" }
                Text("记录来源", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UsageDataSource.entries.forEach { option -> PocketPill(option.label, source == option, { source = option; provider = ""; model = ""; session = "" },
                        enabled = option != UsageDataSource.CodexSession || app == "codex", modifier = Modifier.fillMaxWidth().testTag("usage-filter-source:${option.wire}")) }
                }
                UsageNote("代理请求与会话导入分别统计。")
                Text("状态", style = MaterialTheme.typography.titleSmall)
                UsageChoices(UsageStatusFilter.entries, status, { it.label }, "usage-filter-status", { it.wire }) { status = it }
                PocketTextField(provider, { provider = it }, Modifier.testTag("usage-filter-provider"), label = "上游 ID（空白为全部）", singleLine = true)
                PocketTextField(model, { model = it }, Modifier.testTag("usage-filter-model"), label = "模型（空白为全部）", singleLine = true)
                PocketTextField(session, { session = it }, Modifier.testTag("usage-filter-session"), label = "对话 ID（空白为全部）", singleLine = true)
                if (candidate == null) Text("请检查日期、时区与筛选值。日期最多 31 天，标识最多 256 字节。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
            }
            PocketButton("应用筛选", { candidate?.let(onApply) }, Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("usage-filter-apply"), enabled = candidate != null)
        }
    }
}

@Composable private fun <T> UsageChoices(options: List<T>, selected: T, label: (T) -> String, tag: String,
    id: (T) -> String = { it.toString() }, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { option -> PocketPill(label(option), option == selected, { onSelect(option) }, modifier = Modifier.weight(1f).testTag("$tag:${id(option)}")) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        } }
    }
}
