package dev.codexpocket.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.ActivityStatus
import dev.codexpocket.app.data.AgentRosterEntry

/** Place beneath the conversation title. The panel stays readable while cached/offline/loading. */
@Composable
internal fun AgentRosterChip(
    agents: List<AgentRosterEntry>,
    online: Boolean,
    loading: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    names: Map<String, String> = emptyMap(),
    onRefresh: (() -> Unit)? = null,
    onShow: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(agents.isEmpty()) { if (agents.isEmpty()) expanded = false }
    if (agents.isEmpty()) return

    // Count explicit running states in the latest turn; historical states are labelled in the panel.
    val running = agents.count { it.isCurrent && it.status == ActivityStatus.Running }
    val label = "子代理 ${agents.size} · ${running}进行中"
    PocketGlassSurface(
        modifier = modifier.minimumInteractiveComponentSize()
            .onGloballyPositioned { if (!expanded) anchor = it.boundsInRoot() }
            .testTag("agent-roster-chip")
            .semantics {
                contentDescription = label
                stateDescription = if (expanded) "已展开" else "已折叠"
            },
        shape = RoundedCornerShape(50),
        onClick = {
            onRefresh?.invoke()
            if (onShow != null) {
                expanded = false
                onShow()
            } else expanded = true
        },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(PocketIcons.ExpandMore, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (expanded && onShow == null) AgentRosterPanel(agents, online, loading, anchor, { expanded = false }, { id ->
        // Resolve the latest list again rather than accepting a stale row's identity after refresh.
        if (online && !loading && agents.any { it.threadId == id }) {
            expanded = false
            onOpen(id)
        }
    }, names)
}

@Composable
internal fun AgentRosterPanel(
    agents: List<AgentRosterEntry>,
    online: Boolean,
    loading: Boolean,
    anchor: Rect?,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    names: Map<String, String> = emptyMap(),
) {
    val c = MaterialTheme.colorScheme
    PocketAnchoredMenu("子代理", onDismiss, anchor, widthDp = 320f, alignStart = true, below = true) {
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp).testTag("agent-roster-panel"),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val notice = when {
                !online -> "离线 · 显示上次记录，连接电脑后可打开"
                loading -> "正在读取 · 显示已知记录"
                else -> "状态来自原生记录"
            }
            Text(notice, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
            val current = agents.filter { it.isCurrent }
            val historical = agents.filterNot { it.isCurrent }
            if (current.isNotEmpty()) AgentRosterGroup("本轮子代理", current, names, online && !loading, onOpen)
            if (historical.isNotEmpty()) {
                if (current.isNotEmpty()) HorizontalDivider(color = c.outlineVariant.copy(alpha = .5f))
                AgentRosterGroup("历史子代理", historical, names, online && !loading, onOpen)
            }
        }
    }
}

/** The centered reader owns the toolbar and scroll state; historical records start collapsed. */
@Composable
internal fun AgentRosterList(
    agents: List<AgentRosterEntry>,
    names: Map<String, String>,
    online: Boolean,
    loading: Boolean,
    list: LazyListState,
    historyExpanded: Boolean,
    onHistoryExpanded: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val unique = remember(agents) { agents.distinctBy { it.threadId }.filter { it.threadId.isNotBlank() } }
    val current = remember(unique) { unique.filter { it.isCurrent } }
    val historical = remember(unique) { unique.filterNot { it.isCurrent } }
    LazyColumn(modifier.testTag("agent-reader-roster"), state = list,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "roster-notice", contentType = "notice") {
            Text(when {
                !online -> "离线 · 可查看已缓存内容"
                loading -> "正在读取 · 显示已知记录"
                else -> "状态来自原生记录"
            }, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
        }
        item(key = "roster-current", contentType = "section") {
            Text("本轮子代理", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
        }
        if (current.isEmpty()) item(key = "roster-current-empty", contentType = "notice") {
            Text(if (loading && unique.isEmpty()) "正在读取子代理" else "本轮没有子代理记录",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp))
        }
        items(current, key = { "child:${it.threadId}" }, contentType = { "child" }) { agent ->
            AgentRosterItem(agent, names, enabled = true, onOpen)
        }
        if (historical.isNotEmpty()) {
            item(key = "roster-history", contentType = "section") {
                val angle by androidx.compose.animation.core.animateFloatAsState(
                    if (historyExpanded) 180f else 0f, pocketPressSpring(), label = "agent-history-chevron")
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("agent-reader-history-toggle")
                    .semantics { stateDescription = if (historyExpanded) "已展开" else "已折叠" }
                    .clickable(role = Role.Button, onClick = onHistoryExpanded)
                    .padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("历史子代理 · ${historical.size}", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    Icon(PocketIcons.ExpandMore, null, Modifier.size(16.dp).graphicsLayer { rotationZ = angle },
                        tint = c.onSurfaceVariant)
                }
            }
            if (historyExpanded) items(historical, key = { "child:${it.threadId}" }, contentType = { "child" }) { agent ->
                AgentRosterItem(agent, names, enabled = true, onOpen)
            }
        }
    }
}

@Composable
private fun AgentRosterGroup(
    title: String,
    agents: List<AgentRosterEntry>,
    names: Map<String, String>,
    enabled: Boolean,
    onOpen: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Text(title, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
    agents.forEach { agent -> key(agent.threadId) { AgentRosterItem(agent, names, enabled, onOpen) } }
}

@Composable
private fun AgentRosterItem(agent: AgentRosterEntry, names: Map<String, String>, enabled: Boolean, onOpen: (String) -> Unit) {
        val c = MaterialTheme.colorScheme
        val name = names[agent.threadId]?.trim().orEmpty().ifBlank { agent.name }.ifBlank { "子代理 ${agent.threadId.take(8)}" }
        val state = agentRosterStatusLabel(agent)
        val color = when (agent.status) {
            ActivityStatus.Failed -> c.error
            ActivityStatus.Running -> c.primary
            else -> c.onSurfaceVariant
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("agent-roster-open:${agent.threadId}")
            .semantics { contentDescription = "$name · $state · 查看子会话" }
            .clickable(enabled = enabled, role = Role.Button) { onOpen(agent.threadId) }
            .padding(horizontal = 6.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = c.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state, style = MaterialTheme.typography.labelSmall, color = color)
            val preview = agent.resultPreview.ifBlank { agent.taskPreview }
            if (preview.isNotBlank()) {
                val kind = when {
                    agent.resultPreview.isBlank() -> "任务"
                    agent.status == ActivityStatus.Completed -> "结果"
                    else -> "消息"
                }
                Text("$kind · $preview", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else Text("查看子会话", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
}

internal fun agentRosterStatusLabel(agent: AgentRosterEntry): String {
    val state = when (agent.nativeStatus) {
        "notFound" -> "未找到"
        "shutdown" -> "已关闭"
        "pendingInit" -> "初始化中"
        else -> when (agent.status) {
            ActivityStatus.Running -> "进行中"
            ActivityStatus.Completed -> "已完成"
            ActivityStatus.Failed -> "失败"
            ActivityStatus.Waiting -> "等待中"
            ActivityStatus.Interrupted -> "已中断"
            ActivityStatus.Unknown -> "状态未知"
        }
    }
    return if (agent.isCurrent) state else "上次 · $state"
}
