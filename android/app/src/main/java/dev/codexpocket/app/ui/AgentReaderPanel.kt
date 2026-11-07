package dev.codexpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonNull
import java.text.DateFormat
import java.util.Date

internal enum class AgentReaderBack { Preview, Roster, Close }

internal fun agentReaderBack(state: AgentReaderState): AgentReaderBack = when {
    state.child != null && state.fullScreen -> AgentReaderBack.Preview
    state.child != null -> AgentReaderBack.Roster
    else -> AgentReaderBack.Close
}

/** Stored above PocketModal, so resizing the same overlay never replaces the list or its choices. */
private class AgentReaderPageMemory {
    val projection = TranscriptProjectionMemory()
    val list = LazyListState()
    var expansions by mutableStateOf(emptySet<String>())
    var following by mutableStateOf(true)
    var detailKey by mutableStateOf<String?>(null)
    var anchor = TranscriptAnchor(null)

    fun toggle(key: String) {
        anchor = TranscriptAnchor(list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String,
            list.firstVisibleItemScrollOffset)
        following = false
        expansions = if (key in expansions) expansions - key else expansions + key
    }
}

/** Read-only native child view: all network selection stays in the parent's AgentReaderState. */
@Composable
internal fun AgentReaderPanel(
    state: AgentReaderState,
    agents: List<AgentRosterEntry>,
    names: Map<String, String>,
    online: Boolean,
    onOpen: (String) -> Unit,
    onList: () -> Unit,
    onClose: () -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onOlder: () -> Unit,
    onRetry: () -> Unit,
) {
    val pages = remember(state.parent.encoded) { mutableMapOf<String, AgentReaderPageMemory>() }
    val transcriptStates = rememberSaveableStateHolder()
    val rosterList = rememberLazyListState()
    var historyExpanded by rememberSaveable(state.parent.encoded) { mutableStateOf(false) }
    val child = state.child
    val memory = child?.let { pages.getOrPut(it.key.encoded) { AgentReaderPageMemory() } }
    val name = child?.let { names[it.key.nativeId]?.trim().orEmpty().ifBlank { it.title.trim() }
        .ifBlank { "子代理 ${it.key.nativeId.take(8)}" } }
    val fullScreen = child != null && state.fullScreen
    val dismiss = {
        when (agentReaderBack(state)) {
            AgentReaderBack.Preview -> onFullscreen(false)
            AgentReaderBack.Roster -> onList()
            AgentReaderBack.Close -> onClose()
        }
    }
    PocketModal(
        title = name ?: "子代理",
        onDismiss = dismiss,
        sheet = false,
        screen = fullScreen,
        maxWidthDp = 720f,
        maxHeightFraction = .78f,
    ) {
        Column(Modifier.fillMaxSize().testTag("agent-reader-panel")) {
            AgentReaderToolbar(name, fullScreen, agents.size, onList, onClose, onFullscreen)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            if (child == null) {
                if (state.error != null) AgentReaderNotice(state.error, online, state.loading, onRetry)
                AgentRosterList(agents, names, online, state.loading, rosterList, historyExpanded,
                    { historyExpanded = !historyExpanded }, { id ->
                        // Call with a still-present native ID; parent verifies its relation and generation.
                        if (agents.any { it.threadId == id }) onOpen(id)
                    }, Modifier.fillMaxWidth().weight(1f))
            } else if (memory != null) {
                transcriptStates.SaveableStateProvider(child.key.encoded) {
                    CompositionLocalProvider(LocalInlineDiffComment provides null) {
                        AgentReaderTranscript(state, online, memory, onOlder, onRetry, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentReaderToolbar(name: String?, fullScreen: Boolean, count: Int,
    onList: () -> Unit, onClose: () -> Unit, onFullscreen: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(c.surface.copy(alpha = .94f)).testTag("agent-reader-toolbar")) {
        if (fullScreen) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PocketTextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("agent-reader-return-main")) {
                    Icon(PocketIcons.ArrowBack, null, Modifier.size(18.dp))
                    Text("返回主任务", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.weight(1f))
                AgentReaderSizeButton(fullScreen, onFullscreen)
                ActionIcon(PocketIcons.Close, "关闭子代理阅读", onClick = onClose)
            }
            Text(name.orEmpty(), Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 4.dp)
                .testTag("agent-reader-name"), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("子代理 · 只读", Modifier.padding(start = 18.dp, bottom = 10.dp),
                style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (name != null) ActionIcon(PocketIcons.ArrowBack, "返回子代理列表", onClick = onList)
                Column(Modifier.weight(1f).padding(start = if (name == null) 10.dp else 2.dp, end = 4.dp)) {
                    Text(name ?: "子代理", Modifier.testTag("agent-reader-name"),
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (name == null) "$count 个原生子任务" else "子代理 · 只读",
                        style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
                if (name != null) AgentReaderSizeButton(fullScreen, onFullscreen)
                ActionIcon(PocketIcons.Close, "关闭子代理阅读", onClick = onClose)
            }
        }
    }
}

@Composable
private fun AgentReaderSizeButton(fullScreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    ActionIcon(if (fullScreen) PocketIcons.CloseFullscreen else PocketIcons.OpenInFull,
        if (fullScreen) "缩小子代理阅读" else "放大子代理阅读",
        onClick = { onFullscreen(!fullScreen) })
}

@Composable
private fun AgentReaderNotice(error: String, online: Boolean, loading: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("agent-reader-error")) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        PocketTextButton(onClick = onRetry, enabled = online && !loading,
            modifier = Modifier.testTag("agent-reader-retry")) { Text("重新加载此子代理") }
    }
}

@Composable
private fun AgentReaderTranscript(state: AgentReaderState, online: Boolean, memory: AgentReaderPageMemory,
    onOlder: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    val child = state.child ?: return
    val scope = "agent-reader:${child.key.encoded}:"
    val rows = rememberTranscriptRows(scope, state.timeline, memory.expansions, retained = memory.projection)
    val live = ConversationPresentation.liveStatus(state.timeline)
    val turn = state.timeline.activeTurn
    val reasoning = remember(state.timeline) { ConversationPresentation.liveReasoning(state.timeline) }
    val detail = state.timeline.items.values.firstOrNull { it.key == memory.detailKey }
    val keys = remember(rows, live, turn, detail?.key) {
        listOf("history-control") + rows.flatMap { row -> listOf(row.key) +
            listOfNotNull(detail?.takeIf { value -> row.kind != TranscriptKind.Process && row.items.any { it.key == value.key } }
                ?.let { "detail:${it.key}" }) } +
            listOfNotNull(turn?.takeIf { live != null }?.let { "live:$it" }) +
            listOfNotNull("empty".takeIf { rows.isEmpty() }) + "timeline-end"
    }
    val list = memory.list
    val latestKeys by rememberUpdatedState(keys)
    LaunchedEffect(memory) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.distinctUntilChanged().collect { (scrolling, forward) ->
            if (scrolling) memory.following = !forward
        }
    }
    LaunchedEffect(memory) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull()?.let { TranscriptAnchor(it.key as? String,
            list.firstVisibleItemScrollOffset) } }.distinctUntilChanged().collect { anchor ->
            if (anchor != null) memory.anchor = anchor
        }
    }
    LaunchedEffect(memory, keys) {
        if (!list.isScrollInProgress && memory.anchor.key != null && memory.anchor.key !in keys) {
            val resolved = ConversationPresentation.anchorKey(rows, memory.anchor.key)
            list.scrollToItem(TranscriptAnchor(resolved).resolve(keys, list.firstVisibleItemIndex), 0)
        }
    }
    LaunchedEffect(memory, memory.following, rows.lastOrNull(), live) {
        if (memory.following && rows.isNotEmpty() && !list.isScrollInProgress) list.scrollToItem(latestKeys.lastIndex)
    }
    LaunchedEffect(memory) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.let { Triple(it.key, it.offset, it.size) } }
            .distinctUntilChanged().collect {
                if (memory.following && !list.isScrollInProgress && list.layoutInfo.totalItemsCount > 1)
                    list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
            }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("agent-reader-update"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val updated = remember(state.updatedAt) { state.updatedAt.takeIf { it > 0 }?.let {
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
            } }
            Text(if (!online) "离线 · 缓存内容" else if (state.loading) "正在读取" else if (turn != null) "实时更新" else "原生会话记录",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (updated != null) Text("更新于 $updated", Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.error != null) AgentReaderNotice(state.error, online, state.loading, onRetry)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("agent-reader-transcript"), state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "history-control", contentType = "history") {
                if (state.nextTurns != null && state.nextTurns != JsonNull) PocketTextButton(
                    enabled = online && !state.loading, modifier = Modifier.testTag("agent-reader-older"), onClick = {
                        memory.following = false
                        memory.anchor = TranscriptAnchor(list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String,
                            list.firstVisibleItemScrollOffset)
                        onOlder()
                    }) { Text("加载更早记录") }
            }
            rows.forEach { row ->
                item(key = row.key, contentType = row.kind) {
                    Column(Modifier.fillMaxWidth().testTag("agent-reader-row:${row.key}")) {
                        TranscriptRowView(row, scope, row.key in memory.expansions, { memory.toggle(row.key) },
                            onDetail = { item ->
                                memory.following = false
                                // Files are already shown by the shared inline diff/reader UI.
                                // Never append a generic raw file detail in the child transcript.
                                if (item.type == "fileChange" || item.type == "turn/diff/updated") return@TranscriptRowView
                                // Replace the compact item's detail with the full read-only text below it;
                                // no third overlay, plan mutation, side chat or source operation is reachable.
                                memory.expansions = memory.expansions - "activity-item:${item.key}"
                                memory.detailKey = item.key
                            }, canFork = false, onPlan = null,
                            activityExpansions = memory.expansions, onActivityExpansion = memory::toggle,
                            timeline = state.timeline)
                    }
                }
                detail?.takeIf { value -> row.kind != TranscriptKind.Process && row.items.any { it.key == value.key } }?.let { value ->
                    item(key = "detail:${value.key}", contentType = "read-only-detail") {
                        AgentReaderToolDetail(value) { memory.detailKey = null }
                    }
                }
            }
            if (live != null && turn != null) item(key = "live:$turn", contentType = "live-status") {
                LiveTurnStatus(turn, live, reasoning = reasoning, timing = state.timeline.turns[turn])
            }
            if (rows.isEmpty()) item(key = "empty", contentType = "notice") {
                Text(if (state.loading) "正在加载子代理对话…" else if (!online) "此子代理暂无缓存内容" else "此子代理尚无对话内容",
                    Modifier.padding(vertical = 24.dp).testTag("agent-reader-empty"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = "timeline-end") { Spacer(Modifier.height(1.dp)) }
        }
    }
}

/** Only presentation text is read; raw protocol objects and file/mutation controls stay absent. */
@Composable
private fun AgentReaderToolDetail(item: TimelineItem, onClose: () -> Unit) {
    if (item.type == "fileChange" || item.type == "turn/diff/updated") return
    val clipboard = LocalClipboardManager.current
    val value = remember(item) { NativeActivityPresentation.describe(item) }
    val text = remember(item) { ConversationPresentation.detail(item) }
    val shown = remember(text) { text.take(65_536) }
    Column(Modifier.fillMaxWidth().testTag("agent-reader-detail:${item.key}")
        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("工具详情 · 只读", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            ActionIcon(PocketIcons.ContentCopy, "复制工具详情", enabled = text.isNotBlank(),
                onClick = { clipboard.setText(AnnotatedString(text)) })
            ActionIcon(PocketIcons.Close, "收起工具详情", onClick = onClose)
        }
        value.command?.let { command ->
            if (command.cwd.isNotBlank()) Text(command.cwd, style = MaterialTheme.typography.labelSmall)
            command.exitCode?.let { Text("退出码 $it", style = MaterialTheme.typography.labelSmall) }
        }
        if (shown.isNotBlank()) Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer { Text(shown, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace) }
        } else Text("此工具没有可用的文本详情", style = MaterialTheme.typography.bodySmall)
        if (shown.length < text.length) Text("预览显示前 65,536 个字符 · 复制保留全文", style = MaterialTheme.typography.labelSmall)
        if (value.output.truncated) Text("原生输出已截断", style = MaterialTheme.typography.labelSmall)
    }
}
