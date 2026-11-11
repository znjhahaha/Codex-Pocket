package dev.codexpocket.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ContextIndicator(s: PocketState, onCompact: () -> Unit, onRefresh: () -> Unit = {}) {
    val usage = remember(s.advanced.usage) { ContextUsage.from(s.advanced.usage) }
    var open by remember(s.selected?.key) { mutableStateOf(false) }
    var details by remember(s.selected?.key) { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val c = MaterialTheme.colorScheme
    val percent = usage.fraction?.let { "${(it * 100).toInt()}%" } ?: "—"
    Row(Modifier.heightIn(min = 48.dp).onGloballyPositioned { if (!open) anchor = it.boundsInRoot() }
        .clickable { open = true; onRefresh() }.padding(horizontal = 6.dp).testTag("context-indicator")
        .semantics { contentDescription = "上下文 $percent" }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 3.dp.toPx()
            drawCircle(c.outlineVariant, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            usage.fraction?.let { drawArc(c.onSurfaceVariant, -90f, 360f * it, false,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round)) }
        }
        Text(percent, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
    }
    if (open) PocketAnchoredMenu("上下文", { open = false }, anchor, widthDp = 240f, below = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("上下文", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(percent, style = MaterialTheme.typography.titleSmall)
                Box(Modifier.size(40.dp).testTag("context-refresh").clickable(enabled = s.online && !s.contextSnapshot.loading, onClick = onRefresh)
                    .semantics { contentDescription = "刷新上下文" }, contentAlignment = Alignment.Center) {
                    if (s.contextSnapshot.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Icon(PocketIcons.Refresh, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                }
            }
            val snapshot = s.contextSnapshot
            if (usage.used != null) {
                Text("${compactContextCount(usage.used)} / ${usage.capacity?.let(::compactContextCount) ?: "—"} tokens",
                    Modifier.testTag("context-used-window"), style = MaterialTheme.typography.bodyMedium)
                Text("最近一轮 · 已使用 / 窗口", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            } else Text(when {
                snapshot.loading -> "正在读取上下文用量"
                !s.online -> "电脑离线，暂无已保存的用量"
                snapshot.status == "unsupported" -> "当前助手不支持上下文回读"
                snapshot.status == "failed" -> "上下文读取失败，可重试"
                else -> "暂无可验证的用量记录"
            }, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            usage.fraction?.let { fraction ->
                Canvas(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 1.dp)) {
                    val corner = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                    drawRoundRect(c.outlineVariant, cornerRadius = corner)
                    drawRoundRect(c.onSurfaceVariant, size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height), cornerRadius = corner)
                }
            }
            if (usage.used != null && (!s.online || snapshot.status != "ok" || snapshot.error != null)) Text(
                when { !s.online -> "离线 · 上次记录"; snapshot.error != null -> "刷新失败 · 保留上次记录"; else -> "用量待更新" },
                style = MaterialTheme.typography.labelSmall, color = if (snapshot.error != null) c.error else c.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable { details = !details }.testTag("context-details-toggle"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("详细用量", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                ActivityExpansionIcon(details)
            }
            if (details) Column(Modifier.testTag("context-details"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.advanced.usage?.obj("last")?.let { last ->
                    Text("输入 ${last.number("inputTokens")} · 输出 ${last.number("outputTokens")}", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
                usage.used?.let { Text("最近一轮 $it tokens", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
                usage.capacity?.let { Text("窗口 $it tokens", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
                usage.cumulative?.let { Text("累计使用 $it tokens", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
                if (snapshot.updatedAt > 0) Text("更新于 " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.updatedAt)),
                    style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
            HorizontalDivider(color = c.outlineVariant.copy(alpha = .55f))
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .clickable(enabled = actionUnavailableReason(s, "thread/compact/start", true) == null) { open = false; onCompact() }
                .testTag("context-compact").padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(PocketIcons.FoldVertical, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Text("压缩上下文", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun compactContextCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fm", value / 1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

internal enum class ConversationEditor { None, Rename, Goal, Fork, SideChat }

@Composable
internal fun ConversationPanel(s: PocketState, repo: PocketRepository, anchor: Rect?, onDismiss: () -> Unit, onFiles: (() -> Unit)?, initialEditor: ConversationEditor = ConversationEditor.None) {
    var showAccess by rememberSaveable(s.selected?.key?.encoded) { mutableStateOf(false) }
    var editor by rememberSaveable(s.selected?.key?.encoded, initialEditor) { mutableStateOf(initialEditor) }
    var text by rememberSaveable(editor, s.selected?.key?.encoded) { mutableStateOf(when (editor) {
        ConversationEditor.Rename -> s.selected?.title.orEmpty()
        ConversationEditor.Goal -> s.advanced.goal?.string("objective").orEmpty()
        else -> ""
    }) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var submitting by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val runtime = s.selected?.runtimeId
    val quota = s.quota.takeIf { it.matches(s.computerId, runtime) }
    val latestQuota by rememberUpdatedState(quota)
    LaunchedEffect(repo, s.computerId, runtime, s.online) { repo.refreshQuotaSources().join() }
    val quotaSource = quota?.sources?.firstOrNull { it.string("id") == quota.selectedSourceId }
    // This effect exists only while the menu is composed. No unattended polling or model calls.
    LaunchedEffect(repo, s.computerId, runtime, s.online, quota?.selectedSourceId, quotaSource?.number("autoRefreshSeconds", 60)) {
        if (!s.online || runtime == null) return@LaunchedEffect
        while (isActive) {
            val current = latestQuota ?: return@LaunchedEffect
            val id = current.selectedSourceId ?: return@LaunchedEffect
            val source = current.sources.firstOrNull { it.string("id") == id } ?: return@LaunchedEffect
            val wait = quotaRefreshDelayMillis(source, current.readTimings[id] ?: QuotaReadTiming(), false, System.currentTimeMillis())
                ?: return@LaunchedEffect
            delay(wait.coerceAtLeast(1_000))
            repo.refreshQuota(id).join()
        }
    }
    PocketAnchoredMenu("会话操作", onDismiss, anchor, widthDp = 320f, below = true) {
        Column(Modifier.verticalScroll(scroll).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showAccess) {
                PocketMenuItem("返回会话操作", PocketIcons.ArrowBack, { showAccess = false })
                ThreadOwnership(s, repo)
            } else if (editor != ConversationEditor.None) {
                PocketMenuItem("返回会话操作", PocketIcons.ArrowBack, { editor = ConversationEditor.None }, enabled = !submitting)
                val title = when (editor) { ConversationEditor.Rename -> "重命名"; ConversationEditor.Goal -> "目标"; ConversationEditor.Fork -> "分支聊天"; else -> "旁支讨论" }
                Text(title, Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.titleMedium)
                if (editor in setOf(ConversationEditor.Fork, ConversationEditor.SideChat)) Text("创建独立对话，补充内容会留作草稿。", Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall)
                PocketTextField(text, { text = it }, Modifier.fillMaxWidth().padding(6.dp), singleLine = editor == ConversationEditor.Rename,
                    label = if (editor in setOf(ConversationEditor.Fork, ConversationEditor.SideChat)) "补充说明（选填）" else title, enabled = !submitting)
                val method = when (editor) { ConversationEditor.Rename -> "thread/name/set"; ConversationEditor.Goal -> "thread/goal/set"; else -> "thread/fork" }
                PocketButton(if (submitting) "正在提交" else "确认", enabled = !submitting && actionUnavailableReason(s, method) == null &&
                    (text.isNotBlank() || editor in setOf(ConversationEditor.Fork, ConversationEditor.SideChat)), onClick = {
                    val selected = editor
                    submitting = true
                    scope.launch {
                        try {
                            when (selected) {
                                ConversationEditor.Rename -> repo.rename(text)
                                ConversationEditor.Goal -> repo.setGoal(text)
                                else -> repo.fork(sideChat = selected == ConversationEditor.SideChat, prompt = text)
                            }.join()
                            // Errors/unknown outcomes remain visible in the conversation.
                            onDismiss()
                        } finally { submitting = false }
                    }
                }, modifier = Modifier.fillMaxWidth().padding(6.dp))
                if (editor == ConversationEditor.Goal && s.advanced.goal != null) {
                    PocketMenuItem(if (s.advanced.goal.string("status") == "paused") "恢复目标" else "暂停目标", PocketIcons.Checklist,
                        { repo.setGoal(s.advanced.goal.string("objective"), paused = s.advanced.goal.string("status") != "paused"); onDismiss() }, enabled = !submitting && canMutate(s, "thread/goal/set"))
                    PocketMenuItem("清除目标", PocketIcons.DeleteOutline, { repo.setGoal(null); onDismiss() }, enabled = !submitting && canMutate(s, "thread/goal/clear"))
                }
            } else {
                val access = s.accessFor()
                PocketMenuItem(access?.let { "${it.mode.label} · ${it.phase.label}" } ?: "会话接手", PocketIcons.Computer,
                    { showAccess = true; repo.refreshThreadAccess() },
                    hint = if (!s.supports(BrokerCapability.AccessRead)) "电脑助手待更新" else null)
                PocketMenuItem(if (s.selected?.pinned == true) "取消置顶" else "置顶", PocketIcons.PushPin, { s.selected?.let(repo::pin) })
                CopyConversationLinkItem(s.selected) { url ->
                    clipboard.setText(AnnotatedString(url))
                    android.widget.Toast.makeText(context, "已复制会话链接", android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                PocketMenuItem("项目文件", PocketIcons.FolderOpen, { onDismiss(); onFiles?.invoke() }, enabled = onFiles != null)
                listOf(Triple("重命名", "thread/name/set", ConversationEditor.Rename), Triple("分支聊天", "thread/fork", ConversationEditor.Fork),
                    Triple("旁支讨论", "thread/fork", ConversationEditor.SideChat), Triple("目标", "thread/goal/set", ConversationEditor.Goal)).forEach { (label, method, page) ->
                    val reason = actionUnavailableReason(s, method)
                    PocketMenuItem(label, when(page) { ConversationEditor.Rename -> PocketIcons.Edit; ConversationEditor.Goal -> PocketIcons.Checklist; else -> PocketIcons.CallSplit },
                        { editor = page }, enabled = reason == null, hint = reason)
                }
                PocketMenuItem("在电脑上继续", PocketIcons.Computer, { repo.resume(); onDismiss() }, enabled = actionUnavailableReason(s, "thread/resume") == null)
                val archived = s.selected?.archived == true
                PocketMenuItem(if (archived) "恢复会话" else "归档会话", PocketIcons.Inventory2, { s.selected?.let(repo::archive); onDismiss() },
                    enabled = s.online && s.selected?.conflict == false && !s.uncertainSend && !s.busy && s.supports(if (archived) "thread/unarchive" else "thread/archive"))
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                QuotaPanel(s, repo)
            }
        }
    }
}

@Composable
internal fun CopyConversationLinkItem(chat: ChatEntity?, onCopy: (String) -> Unit) {
    val url = ThreadDeepLink.copyUrl(chat)
    PocketMenuItem("复制会话链接", PocketIcons.ChatBubbleOutline, { url?.let(onCopy) }, enabled = url != null,
        hint = if (url == null) "会话尚无有效原生 ID" else null)
}

@Composable
private fun QuotaPanel(s: PocketState, repo: PocketRepository) {
    val q = s.quota.takeIf { it.matches(s.computerId, s.selected?.runtimeId) } ?: QuotaState()
    var choosing by remember(s.computerId, s.selected?.runtimeId) { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("账户额度", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            val currentSource = q.sources.firstOrNull { it.string("id") == q.selectedSourceId }
            val canRead = currentSource != null && s.supports("quota/read") && q.status !in setOf("unavailable", "unsupported")
            PocketTextButton(onClick = { if (canRead) repo.refreshQuota(q.selectedSourceId!!, true) else repo.refreshQuotaSources() }, enabled = s.online && !q.loading) { Text("刷新", style = MaterialTheme.typography.labelSmall) }
        }
        val source = q.sources.firstOrNull { it.string("id") == q.selectedSourceId }
        PocketMenuItem(source?.string("label") ?: "选择额度来源", null, { choosing = !choosing }, enabled = q.sources.isNotEmpty(),
            hint = when { source == null -> null; source.flag("associated") -> "当前运行时"; else -> "来源未关联 · 独立查看" })
        if (choosing) q.sources.forEach { option -> PocketMenuItem(option.string("label"), null,
            { choosing = false; repo.refreshQuota(option.string("id")) }, selected = option.string("id") == q.selectedSourceId,
            hint = if (option.string("status") == "unconfigured") "未配置查询" else null) }
        if (q.loading && s.online) PocketProgressStatus("正在查询额度")
        val state = if (!s.online) "offline" else q.status
        val stale = q.result?.flag("stale") == true || state !in setOf("ready", "ok", "idle", "cooldown", "rate_limited")
        quotaStatusMessage(state, stale)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
        q.result?.array("items")?.forEach { element ->
            val item = element.asObject()
            fun number(key: String) = (item[key] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
            val used = number("used"); val total = number("total"); val remaining = number("remaining")
            val fraction = number("usedPercent")?.div(100) ?: if (used != null && total != null && total > 0) used / total else null
            Text(item.string("label", "额度"), style = MaterialTheme.typography.labelMedium)
            if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0.0, 1.0).toFloat() }, Modifier.fillMaxWidth().height(4.dp))
            fun format(v: Double) = java.text.DecimalFormat("#,##0.##").format(v)
            val unit = item.string("unit")
            Text(when { remaining != null -> "剩余 ${format(remaining)} $unit"; used != null && total != null -> "${format(used)} / ${format(total)} $unit"; fraction != null -> "已使用 ${format(fraction * 100)}%"; else -> "未提供数值" }, style = MaterialTheme.typography.bodySmall)
            val reset = number("resetsAt")
            if (reset != null && reset > 0) Text("重置 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(reset.toLong()))}", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
        q.result?.number("updatedAt")?.takeIf { it > 0 }?.let { Text((if (stale) "上次结果 · " else "更新于 ") + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
    }
}
