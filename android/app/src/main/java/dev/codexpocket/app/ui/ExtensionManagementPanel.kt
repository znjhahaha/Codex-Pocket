@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codexpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*

@Composable
internal fun ExtensionManagementPanel(s: PocketState, onRuntime: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit, onRefresh: () -> Unit,
    modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    var runtimeChoice by rememberSaveable(s.computerId) { mutableStateOf<String?>(null) }
    val runtime = s.runtimes.firstOrNull { it.id == runtimeChoice } ?: s.activeRuntime ?: s.runtimes.firstOrNull()
    var runtimeMenu by remember { mutableStateOf(false) }
    var kind by rememberSaveable(s.computerId, runtime?.id) { mutableStateOf("mcp") }
    val categories = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val data = s.extensions.takeIf { runtime != null && it.runtimeId == runtime.id && it.computerId == s.computerId }
    val entries = data?.entries.orEmpty()
    val hasInventory = data?.phase == ExtensionPhase.Ready || entries.isNotEmpty()
    val canRefresh = s.online && runtime?.online == true && data?.phase != ExtensionPhase.Loading &&
        data?.refreshing != true && data?.pendingIds.isNullOrEmpty()
    LaunchedEffect(s.computerId, runtime?.id, s.online, runtime?.online) { runtime?.id?.let(onRuntime) }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("extension-management")
        .padding(start = contentPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
            end = contentPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr), top = contentPadding.calculateTopPadding()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("扩展管理", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text(s.computerName.ifBlank { "当前电脑" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { if (data == null) runtime?.id?.let(onRuntime) else onRefresh() },
                enabled = canRefresh, modifier = Modifier.testTag("extension-refresh")) {
                Icon(PocketIcons.Refresh, "刷新扩展", Modifier.size(19.dp))
            }
        }
        Box {
            Surface(Modifier.fillMaxWidth().testTag("extension-runtime-summary").clickable { runtimeMenu = true },
                color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.heightIn(min = 48.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(runtime?.let { extensionRuntimeLabel(it, s.runtimes) } ?: "尚未发现运行时", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (s.online && runtime?.online == true) "已连接" else "离线", style = MaterialTheme.typography.labelSmall)
                    Icon(PocketIcons.ExpandMore, "选择运行时", Modifier.size(16.dp))
                }
            }
            DropdownMenu(expanded = runtimeMenu, onDismissRequest = { runtimeMenu = false }) {
                s.runtimes.forEach { candidate ->
                    key(candidate.id) {
                        DropdownMenuItem(text = { Text(extensionRuntimeLabel(candidate, s.runtimes)) },
                            onClick = { runtimeChoice = candidate.id; runtimeMenu = false },
                            modifier = Modifier.testTag("extension-runtime:" + candidate.id))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().selectableGroup().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("mcp" to "MCP", "skill" to "Skills", "plugin" to "应用 / 插件").forEach { (id, label) ->
                val count = if (hasInventory && !(id == "plugin" && data?.appInventoryStatus in setOf("unsupported", "unavailable")))
                    entries.count { it.kind == id || (id == "plugin" && it.kind == "app") }.toString() else "—"
                Surface(Modifier.weight(1f).testTag("extension-category:" + id)
                    .selectable(kind == id, role = Role.Tab, onClick = { kind = id }), shape = RoundedCornerShape(16.dp),
                    color = if (kind == id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(label + " · " + count, style = MaterialTheme.typography.labelMedium,
                            color = if (kind == id) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val runtimeKey = runtime?.id.orEmpty()
        categories.SaveableStateProvider("${s.computerId.length}:${s.computerId}${runtimeKey.length}:$runtimeKey:$kind") {
            var query by rememberSaveable { mutableStateOf("") }
            var detail by rememberSaveable { mutableStateOf<String?>(null) }
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            val rows = remember(entries, kind, query) { entries.filter {
                (it.kind == kind || (kind == "plugin" && it.kind == "app")) &&
                    (query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true))
            } }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("extension-search"),
                placeholder = { Text("搜索当前分类") }, singleLine = true)
            androidx.compose.foundation.lazy.LazyColumn(state = list,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("extension-list"),
                contentPadding = PaddingValues(top = 4.dp, bottom = contentPadding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "status", contentType = "status") {
                    ExtensionStatusCard(if (query.isNotBlank() && rows.isEmpty() && hasInventory)
                        ExtensionNotice("search-empty", "未找到匹配扩展", "试试其他关键词。")
                    else extensionNotice(s, runtime, data, kind, rows.isEmpty()))
                }
                items(rows.size, key = { rows[it].id }, contentType = { "extension" }) { index ->
                    val entry = rows[index]
                    ExtensionRow(entry, entry.id == detail, entry.id in data?.pendingIds.orEmpty(), entry.id in data?.uncertainIds.orEmpty(),
                        s.online && runtime?.online == true && data?.phase == ExtensionPhase.Ready && data.refreshing == false,
                        { detail = if (detail == entry.id) null else entry.id }, onToggle)
                }
                if (data?.reloadPending == true) item(key = "reload", contentType = "status") {
                    ExtensionStatusCard(ExtensionNotice("reload", "配置已保存，等待重载", "等待正在运行的工具结束，电脑端会重载配置；随后刷新确认。"))
                }
            }
        }
    }
}

@Composable
private fun ExtensionRow(entry: ManagedExtension, expanded: Boolean, pending: Boolean, uncertain: Boolean,
    online: Boolean, onExpand: () -> Unit, onToggle: (String, Boolean) -> Unit) {
    ExtensionSurface(Modifier.testTag("extension:" + entry.id)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button,
                    onClickLabel = if (expanded) "收起详情" else "展开详情", onClick = onExpand)
                    .semantics { stateDescription = if (expanded) "详情已展开" else "详情已收起" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsCircleIcon(when (entry.kind) { "mcp" -> PocketIcons.Tune; "skill" -> PocketIcons.BookOpen; else -> PocketIcons.Cube })
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(when { pending -> "正在更新"; uncertain -> "结果待核对，请刷新"; else -> extensionStatusLabel(entry) },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (expanded) PocketIcons.ExpandLess else PocketIcons.ExpandMore, null, Modifier.size(16.dp))
                }
                if (entry.enabled != null) PocketToggle(entry.enabled, { onToggle(entry.id, it) },
                    Modifier.testTag("extension-toggle:" + entry.id).semantics { contentDescription = "启用扩展：" + entry.name },
                    enabled = online && entry.canToggle && !pending && !uncertain)
            }
            androidx.compose.animation.AnimatedVisibility(expanded,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(pocketMotionMillis(180))) +
                    androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(pocketMotionMillis(200))),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(pocketMotionMillis(180))) +
                    androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(pocketMotionMillis(200)))) {
                Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    if (entry.description.isNotBlank()) Text(entry.description, style = MaterialTheme.typography.bodySmall)
                    if (entry.scope.isNotBlank()) Text("配置范围 · " + entry.scope, style = MaterialTheme.typography.bodySmall)
                    if (entry.reason.isNotBlank()) Text(extensionReasonLabel(entry.reason), style = MaterialTheme.typography.bodySmall)
                    else if (!entry.canToggle) Text("在电脑端管理此扩展的配置。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun extensionRuntimeLabel(runtime: RuntimeInfo, runtimes: List<RuntimeInfo>): String {
    fun base(value: RuntimeInfo) = (when (value.source) { "cli" -> "CLI"; "desktop" -> "桌面端"; else -> value.source.ifBlank { "运行时" } }) +
        " · " + runtimeVersionLabel(value.version).ifBlank { "版本未知" }
    val label = base(runtime)
    val same = runtimes.filter { base(it) == label }
    return if (same.size > 1) label + " · 实例 " + (same.indexOfFirst { it.id == runtime.id } + 1) else label
}

private data class ExtensionNotice(val key: String, val title: String, val nextStep: String, val detail: String? = null)

private fun extensionNotice(s: PocketState, runtime: RuntimeInfo?, data: ExtensionManagementState?,
    kind: String, empty: Boolean): ExtensionNotice = when {
    runtime == null -> ExtensionNotice("no-runtime", "尚未发现运行时", "在电脑上打开 Codex 或 CLI，再检查“电脑连接”中的连接状态。")
    !s.online -> ExtensionNotice("offline", "电脑已离线",
        if (data?.entries?.isNotEmpty() == true) "当前显示此运行时的上次列表。连接电脑后刷新，再修改扩展。"
        else "在“电脑连接”中恢复连接，然后刷新此运行时。")
    !runtime.online || data?.phase == ExtensionPhase.Offline -> ExtensionNotice("runtime-offline", "所选运行时已离线",
        "在电脑上打开此运行时，或选择一个已连接的运行时；连接后刷新。")
    data == null -> ExtensionNotice("switching", "正在切换运行时", "正在读取所选运行时的配置；之前的列表与状态已收起。")
    data.refreshing && data.entries.isNotEmpty() -> ExtensionNotice("refreshing", "正在后台刷新扩展", "当前列表仍可查看，等待刷新后再修改。")
    data.phase == ExtensionPhase.Loading -> ExtensionNotice("loading", "正在读取扩展", "正在读取所选运行时的配置，请稍候。")
    data.phase == ExtensionPhase.Failed -> ExtensionNotice("failure", "扩展读取失败", "确认电脑端 Codex 正常运行，然后刷新重试。", data.error)
    data.phase == ExtensionPhase.Ready && data.error != null -> ExtensionNotice("update-failure",
        if (data.uncertainIds.isNotEmpty()) "更新结果待核对" else "扩展更新未完成", "刷新读取电脑端的实际状态，再决定是否修改。", data.error)
    data.status == "update_required" -> ExtensionNotice("update-required", "需要更新电脑桥接",
        "先完成当前桌面任务，再关闭并重新打开 Codex，回到这里刷新。", data.error)
    data.status == "bridge_missing" -> ExtensionNotice("bridge-missing", "电脑桥接尚未连接",
        "在电脑上打开 Codex；若仍未连接，完成当前桌面任务后重启 Codex，再刷新。", data.error)
    data.status == "helper_update_required" -> ExtensionNotice("helper-update", "需要更新电脑助手",
        "在电脑端更新 Codex Pocket 助手并重新连接，然后刷新扩展列表。", data.error)
    data.status == "initializing" -> ExtensionNotice("initializing", "扩展管理正在初始化",
        "等待电脑端初始化完成，然后刷新此运行时。", data.error)
    data.status == "unsupported" || data.phase == ExtensionPhase.Unsupported -> ExtensionNotice("unsupported", "此运行时暂不支持扩展管理",
        "在电脑端管理扩展，或选择另一个支持扩展管理的运行时。", data.error)
    data.error != null -> ExtensionNotice("failure", "扩展读取失败", "确认电脑端 Codex 正常运行，然后刷新重试。", data.error)
    data.phase == ExtensionPhase.Idle -> ExtensionNotice("idle", "尚未读取扩展", "点击分类右侧的刷新按钮，读取此运行时的已有配置。")
    kind == "plugin" && data.appInventoryStatus == "unsupported" -> ExtensionNotice("apps-unsupported", "当前运行时未提供应用目录",
        "已取得的插件仍可查看；应用请在电脑端管理。这不代表没有安装应用。")
    kind == "plugin" && data.appInventoryStatus == "unavailable" -> ExtensionNotice("apps-unavailable", "应用目录暂未读到",
        "已取得的插件仍可查看。检查电脑端连接后刷新重试。")
    empty -> ExtensionNotice("empty", when (kind) { "mcp" -> "尚未配置 MCP"; "skill" -> "尚未配置技能"; else -> "尚未配置应用或插件" },
        when (kind) { "mcp" -> "先在电脑端添加 MCP 服务，然后刷新此列表。"; "skill" -> "先在电脑端添加技能，然后刷新此列表。"; else -> "先在电脑端配置应用或插件，然后刷新此列表。" })
    data.pendingIds.isNotEmpty() -> ExtensionNotice("updating", "正在更新扩展", "等待电脑端确认；开关会在读取实际状态后更新。")
    data.uncertainIds.isNotEmpty() -> ExtensionNotice("uncertain", "扩展状态待核对", "刷新读取电脑端的实际状态，再修改标记为待核对的扩展。")
    else -> ExtensionNotice("ready", "扩展列表已同步", "点击扩展查看详情；可修改的配置会显示启停开关。")
}

@Composable
private fun ExtensionStatusCard(notice: ExtensionNotice) {
    ExtensionSurface(Modifier.testTag("extension-status:" + notice.key)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCircleIcon(when (notice.key) {
                "loading", "switching", "initializing", "updating" -> PocketIcons.LoaderCircle
                "update-required", "helper-update", "bridge-missing" -> PocketIcons.Refresh
                "offline", "runtime-offline" -> PocketIcons.Wifi
                "ready" -> PocketIcons.Check
                else -> PocketIcons.Lightbulb
            })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(notice.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                Text(notice.nextStep, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                notice.detail?.takeIf { it.isNotBlank() && it != notice.nextStep }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ExtensionSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)), content = content)
}

private fun extensionStatusLabel(entry: ManagedExtension): String = when (entry.status) {
    "ready" -> "已启用"
    "starting" -> "正在启动"
    "failed" -> "启动失败 · 请在电脑端查看"
    "stopped", "disabled" -> "已停用"
    "unauthorized", "auth_required" -> "请在电脑端完成登录"
    "unavailable" -> if (entry.kind == "app") "仅可查看 · 此运行时未提供应用启停" else "暂不可用 · 请在电脑端查看"
    else -> if (entry.enabled == false) "已停用" else "已配置 · 状态尚未确认"
}
private fun extensionReasonLabel(reason: String): String = when (reason) {
    "native_app_toggle_not_supported" -> "当前运行时未提供应用启停设置，请在电脑端管理"
    "existing_plugin_config_not_supported" -> "原生未提供此插件的启停配置，请在电脑端处理"
    "native_toggle_not_supported" -> "此运行时未提供可安全修改的启停设置"
    else -> "暂不能在手机修改此配置，请在电脑端处理"
}
