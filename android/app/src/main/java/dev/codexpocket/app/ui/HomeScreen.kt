package dev.codexpocket.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import java.text.DateFormat
import java.util.Date

internal enum class HomeTab(val label: Int) { Chats(R.string.v02_chats), Attention(R.string.v02_attention), Devices(R.string.v02_devices) }

@Composable
internal fun HomeNavigation(tab: HomeTab, attention: Int, onSelect: (HomeTab) -> Unit) = PocketHomeNavigation(tab, attention, onSelect = onSelect)

/** Scope state by paired computer and runtime before remembering the selected project. */
@Composable
internal fun ChatsHome(
    s: PocketState,
    onSelect: (ChatEntity) -> Unit,
    onNew: () -> Unit,
    onRefresh: (String, Boolean, Boolean) -> Unit,
    onPin: (ChatEntity) -> Unit,
    onArchive: (ChatEntity) -> Unit,
    runtimeFilter: String? = null,
    onScrollState: (Boolean) -> Unit = {},
    onAttention: () -> Unit = {},
    onEnsureDirectory: (String, Boolean) -> Unit = { search, archived -> onRefresh(search, archived, false) },
) {
    val scopes = rememberSaveableStateHolder()
    scopes.SaveableStateProvider(homeScopeKey(s.computerId, runtimeFilter)) {
        var savedProject by rememberSaveable { mutableStateOf(emptyList<String>()) }
        val project = savedProject.takeIf { it.size == 2 }?.let { HomeProject(it[0], it[1]) }
        val selectProject: (HomeProject?) -> Unit = { savedProject = it?.let { p -> listOf(p.computerId, p.cwd) }.orEmpty() }
        val projects = remember(s.chats, s.computerId, runtimeFilter, project) { homeProjects(s.chats, s.computerId, runtimeFilter, project) }
        var showProjects by rememberSaveable { mutableStateOf(false) }
        val projectStates = rememberSaveableStateHolder()
        Column(Modifier.fillMaxSize().padding(top = LocalHomeContentPadding.current.calculateTopPadding())) {
            HomeProjectChips(projects, project, selectProject, { showProjects = true })
            projectStates.SaveableStateProvider(project?.id ?: "all-projects") {
                HomeProjectSessions(s, project, runtimeFilter, onSelect, onNew, onRefresh, onPin, onArchive,
                    onScrollState, onAttention, onClearProject = { selectProject(null) }, onEnsureDirectory = onEnsureDirectory)
            }
        }
        if (showProjects) HomeProjectsSheet(projects, project, { showProjects = false }) {
            selectProject(it)
            showProjects = false
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.HomeProjectSessions(
    s: PocketState, project: HomeProject?, runtimeFilter: String?, onSelect: (ChatEntity) -> Unit,
    onNew: () -> Unit, onRefresh: (String, Boolean, Boolean) -> Unit, onPin: (ChatEntity) -> Unit,
    onArchive: (ChatEntity) -> Unit, onScrollState: (Boolean) -> Unit, onAttention: () -> Unit, onClearProject: () -> Unit,
    onEnsureDirectory: (String, Boolean) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var collapsedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val chats = remember(s.chats, s.computerId, runtimeFilter, project, search, archived) {
        homeFilteredChats(s.chats, s.computerId, runtimeFilter, project, search, archived)
    }
    val statuses = remember(chats, s.online, s.runtimes, s.approvals, s.commands, s.pendingQuestions, s.advanced.asyncQuestions, s.selected?.key, s.timeline.activeTurn, s.timeline.status) {
        chats.associate { it.key.encoded to homeChatStatus(s, it) }
    }
    val groups = remember(chats, statuses, project, search, archived) {
        if (project == null && search.isBlank() && !archived) homeProjectGroups(chats)
        else homeChatGroups(chats, statuses, search, archived)
    }
    val attentionCount = remember(s.approvals, s.pendingQuestions, s.advanced.asyncQuestions) {
        val approvals = s.approvals.filter { !it.submitted && !ConversationPresentation.hookType(it.method) }.distinctBy { it.key }.size
        val questions = s.pendingQuestions.values.sumOf { values -> values.filter { !it.flag("submitted") }.distinctBy { it.string("id") }.size }
        approvals + if (s.pendingQuestions.isNotEmpty()) questions else s.advanced.asyncQuestions.filter { !it.flag("submitted") }.distinctBy { it.string("id") }.size
    }
    val runtimes = remember(s.runtimes) { s.runtimes.associateBy { it.id } }
    val canCreate = s.online && (s.supports(BrokerCapability.EnsureRuntime) || s.runtimes.any { (runtimeFilter == null || it.id == runtimeFilter) && s.supports("thread/start", it) })
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val enterScrollThreshold = with(density) { 48.dp.roundToPx() }
    val leaveScrollThreshold = with(density) { 8.dp.roundToPx() }
    var scrolledFromTop by remember { mutableStateOf(false) }
    val reportScrollState by rememberUpdatedState(onScrollState)
    LaunchedEffect(listState, enterScrollThreshold, leaveScrollThreshold) {
        snapshotFlow {
            when {
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset >= enterScrollThreshold -> true
                listState.firstVisibleItemScrollOffset <= leaveScrollThreshold -> false
                else -> scrolledFromTop
            }
        }.distinctUntilChanged().collect { scrolledFromTop = it; reportScrollState(it) }
    }
    DisposableEffect(listState) { onDispose { reportScrollState(false) } }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(listState, keyboard) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) keyboard?.hide() }
    }
    val toggleSearch = { searchExpanded = !searchExpanded; if (!searchExpanded) keyboard?.hide(); Unit }
    val searchCoordinator = LocalHomeSearchCoordinator.current
    SideEffect { searchCoordinator?.expanded = searchExpanded }
    LaunchedEffect(searchCoordinator) {
        searchCoordinator?.requests?.collect { toggleSearch() }
    }
    // This value is restored with the project, so returning never resets its saved position.
    val filterKey = listOf(search, archived.toString())
    var previousFilterKey by rememberSaveable { mutableStateOf(filterKey) }
    LaunchedEffect(filterKey) {
        if (previousFilterKey != filterKey) { previousFilterKey = filterKey; listState.scrollToItem(0) }
    }
    val ensureDirectory by rememberUpdatedState(onEnsureDirectory)
    LaunchedEffect(search, archived, s.online) { if (s.online) { delay(350); ensureDirectory(search, archived) } }
    var showInitialLoading by remember { mutableStateOf(false) }
    LaunchedEffect(s.directoryLoading, chats.isEmpty()) {
        showInitialLoading = false
        if (s.directoryLoading && chats.isEmpty()) { delay(150); showInitialLoading = true }
    }
    HomeLibraryHeader(archived, { archived = it }, chats.size,
        searching = searchExpanded, onSearch = toggleSearch.takeIf { searchCoordinator == null })
    androidx.compose.animation.AnimatedVisibility(searchExpanded,
        enter = androidx.compose.animation.fadeIn(tween(pocketMotionMillis(160))) + androidx.compose.animation.expandVertically(tween(pocketMotionMillis(180))),
        exit = androidx.compose.animation.fadeOut(tween(pocketMotionMillis(100))) + androidx.compose.animation.shrinkVertically(tween(pocketMotionMillis(180)))) {
        Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) { PocketSearch(search, { search = it }, stringResource(R.string.search_chats)) }
    }
    if (!searchExpanded && search.isNotBlank()) Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp)) {
        PocketPill("搜索：$search", true, { searchExpanded = true })
    }
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().testTag("chats-home"), state = listState,
        contentPadding = PaddingValues(top = 2.dp, bottom = LocalHomeContentPadding.current.calculateBottomPadding() + 24.dp),
    ) {
        if (!s.online || showInitialLoading || s.directoryError != null) item(key = "home-state", contentType = "state") {
            HomeSyncState(s, onRetry = { onRefresh(search, archived, false) })
        }
        if (attentionCount > 0 && !archived && search.isBlank()) item(key = "home-attention", contentType = "attention") {
            HomeAttentionEntry(attentionCount, onAttention)
        }
        if (chats.isEmpty() && !s.directoryLoading) item(key = "home-empty", contentType = "empty") {
            HomeEmptyState(search, project?.cwd, archived, s.online, canCreate,
                onClearFilters = { search = ""; onClearProject() }, onShowRecent = { archived = false }, onNew = onNew)
        }
        groups.forEach { group ->
            val expanded = group.id !in collapsedGroups
            item(key = "home-group-${group.id}", contentType = "group-header") {
                HomeGroupHeader(group, expanded, { collapsedGroups = if (expanded) collapsedGroups + group.id else collapsedGroups - group.id })
            }
            // Both project and status sections use the same lazy vertical flow.
            if (expanded) itemsIndexed(group.chats, key = { _, chat -> chat.key.encoded }, contentType = { _, _ -> "chat" }) { index, chat ->
                val archiveMethod = if (chat.archived) "thread/unarchive" else "thread/archive"
                ChatHomeRow(chat, statuses[chat.key.encoded].orEmpty(), chat.key.encoded in s.unreadKeys, s.online,
                    { onSelect(chat) }, { onPin(chat) }, { onArchive(chat) },
                    archiveEnabled = s.online && s.supports(archiveMethod, runtimes[chat.runtimeId]), showDivider = index < group.chats.lastIndex,
                    modifier = Modifier.animateItem(fadeInSpec = tween(pocketMotionMillis(150)), placementSpec = tween(pocketMotionMillis(180)), fadeOutSpec = tween(pocketMotionMillis(120))))
            }
        }
        if (s.nextThreads != null && s.online) item(key = "home-load-more", contentType = "footer") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
                PocketButton(stringResource(R.string.load_more), { onRefresh(search, archived, true) }, secondary = true, enabled = !s.directoryRefreshing)
            }
        }
    }
}

@Composable
private fun HomeSyncState(s: PocketState, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("home-sync-state"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.restoringConnection || (s.online && s.directoryLoading && s.directoryError == null)) PocketLoader()
        else Icon(if (s.directoryError != null) PocketIcons.ErrorOutline else PocketIcons.Computer, null, Modifier.size(18.dp),
            tint = if (s.directoryError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when { s.restoringConnection -> "正在恢复连接 · 已保留本机记录"; s.directoryError != null -> s.directoryError; !s.online -> stringResource(R.string.v03_home_cached); else -> "正在读取会话列表…" },
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.online && s.directoryError != null) PocketTextButton(onRetry, enabled = !s.directoryRefreshing) { Text(stringResource(R.string.v03_home_retry)) }
    }
}
@Composable
internal fun HomeScopeTab(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val indicator by animateFloatAsState(if (selected) 1f else 0f, tween(pocketMotionMillis(180)), label = "home-scope-selection")
    Box(
        modifier.heightIn(min = 48.dp).widthIn(min = 64.dp).clip(RoundedCornerShape(8.dp))
            .background(if (pressed || focused) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, Modifier.padding(horizontal = 2.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.align(Alignment.BottomCenter).width(26.dp).height(2.dp).graphicsLayer { alpha = indicator; scaleX = .6f + .4f * indicator }.background(MaterialTheme.colorScheme.onSurface, CircleShape))
    }
}

@Composable
private fun HomeGroupHeader(group: HomeChatGroup, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, tween(pocketMotionMillis(200)), label = "home-group-${group.id}")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val c = MaterialTheme.colorScheme
    val tint by animateColorAsState(if (pressed) c.onSurface else c.onSurfaceVariant, tween(pocketMotionMillis(120)), label = "home-group-tint")
    // A section label like the Codex sidebar: folder, project name, count. The full path stays in accessibility only.
    Row(modifier.padding(horizontal = 10.dp).fillMaxWidth().heightIn(min = 44.dp).padding(top = 6.dp)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onToggle)
        .testTag("home-group-${group.id}")
        .semantics { heading(); stateDescription = if (expanded) "已展开，${group.chats.size} 个会话" else "已收起，${group.chats.size} 个会话"
            group.subtitle?.let { contentDescription = "${group.title}，$it" } }
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (group.subtitle != null) Icon(PocketIcons.Folder, null, Modifier.size(15.dp), tint = tint)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tint,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(group.chats.size.toString(), color = c.onSurfaceVariant.copy(alpha = .7f), style = MaterialTheme.typography.labelMedium)
        }
        Icon(PocketIcons.ChevronRight, null, Modifier.size(14.dp).rotate(rotation), tint = tint)
    }
}

/** "刚刚", "5 分钟前", "14:32", "昨天", "11/3": short enough to share the title line. */
internal fun homeRelativeTime(millis: Long, now: Long): String? {
    if (millis <= 0) return null
    val diff = now - millis
    if (diff < 0) return null
    val minutes = diff / 60_000
    val zone = java.util.TimeZone.getDefault()
    val day = { t: Long -> (t + zone.getOffset(t)) / 86_400_000 }
    val days = day(now) - day(millis)
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        days == 0L -> "%02d:%02d".format(calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE))
        days == 1L -> "昨天"
        days < 7 -> "$days 天前"
        java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.YEAR) == calendar.get(java.util.Calendar.YEAR) ->
            "${calendar.get(java.util.Calendar.MONTH) + 1}/${calendar.get(java.util.Calendar.DAY_OF_MONTH)}"
        else -> "${calendar.get(java.util.Calendar.YEAR)}/${calendar.get(java.util.Calendar.MONTH) + 1}/${calendar.get(java.util.Calendar.DAY_OF_MONTH)}"
    }
}

@Composable
private fun HomeAttentionEntry(count: Int, onClick: () -> Unit) {
    PocketTextButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().testTag("home-attention")
            .border(.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f), RoundedCornerShape(14.dp)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Icon(PocketIcons.PendingActions, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.secondary)
        Text("需要你确认", Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(PocketIcons.ChevronRight, null, Modifier.padding(start = 8.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeProjectsSheet(projects: List<HomeProject>, selected: HomeProject?, onDismiss: () -> Unit, onSelect: (HomeProject?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(projects, query) { projects.filter { it.cwd.contains(query.trim(), ignoreCase = true) || it.title.contains(query.trim(), ignoreCase = true) } }
    PocketSheet(title = stringResource(R.string.projects), onDismiss = onDismiss, subtitle = "选择这台电脑上的项目") {
        Box(Modifier.padding(horizontal = 16.dp)) { PocketSearch(query, { query = it }, "搜索项目名称或路径") }
        Spacer(Modifier.height(8.dp))
        PocketActionRow(
            title = stringResource(R.string.all_projects),
            icon = PocketIcons.FolderOpen,
            onClick = { onSelect(null) },
            selected = selected == null,
        )
        PocketSectionLabel(if (query.isBlank()) "项目目录" else "搜索结果")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("home-projects-list"), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            items(matches, key = { it.id }) { project ->
                PocketActionRow(
                    title = project.title,
                    subtitle = project.cwd,
                    icon = PocketIcons.FolderOpen,
                    onClick = { onSelect(project) },
                    selected = selected == project,
                )
            }
            if (matches.isEmpty()) item {
                Text(
                    if (query.isBlank()) "已同步对话的项目会显示在这里" else stringResource(R.string.v02_no_results),
                    Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatHomeRow(
    chat: ChatEntity,
    status: String,
    unread: Boolean,
    online: Boolean,
    onSelect: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    archiveEnabled: Boolean = online,
    showDivider: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menu by rememberSaveable(chat.key.encoded) { mutableStateOf(false) }
    val title = chat.title.ifBlank { stringResource(R.string.untitled_chat) }
    val actionsLabel = stringResource(R.string.chat_actions_named, title)
    val unreadLabel = stringResource(R.string.v02_unread)
    val rowStatus = context.stateLabel(status.ifBlank { "idle" })
    val preview = remember(chat.preview) { homeSafePreview(chat.preview) }
    val configuration = LocalConfiguration.current
    val timestamp = remember(chat.updatedAt, configuration.locales.toLanguageTags()) {
        homeUpdatedAtMillis(chat.updatedAt).takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        }.orEmpty()
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressColor by animateColorAsState(if (pressed || focused) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
        tween(pocketMotionMillis(120)), label = "home-row-press")
    val scale by animateFloatAsState(if (pressed) .985f else 1f, pocketPressSpring(), label = "home-row-scale")
    val relative = remember(chat.updatedAt, timestamp) { homeRelativeTime(homeUpdatedAtMillis(chat.updatedAt), System.currentTimeMillis()) ?: timestamp }
    val c = MaterialTheme.colorScheme
    // Two quiet lines: title + time, then a one-line preview. State shows only when it needs attention;
    // actions are a long press away (also exposed to accessibility as the long-click action).
    Column(modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp)).background(pressColor)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onSelect, onLongClick = { menu = true },
                onLongClickLabel = actionsLabel, role = Role.Button)
            .semantics { stateDescription = listOfNotNull(unreadLabel.takeIf { unread }, rowStatus).joinToString("，") }
            .testTag("home-chat-${chat.key.encoded}").padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HomeRowStatus(status, unread, chat.pinned)
                Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium)
                if (relative.isNotBlank()) Text(relative, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            }
            val line = when (status) {
                "failed", "pending", "inProgress" -> rowStatus + if (preview.isNotBlank()) " · $preview" else ""
                else -> preview
            }
            if (line.isNotBlank()) Text(line, Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = if (status == "failed") c.error else c.onSurfaceVariant)
        }
    }
    if (menu) {
        PocketSheet(title = title, onDismiss = { menu = false }, subtitle = chat.cwd.takeIf(String::isNotBlank)) {
            PocketSectionLabel(stringResource(R.string.chat_actions))
            PocketActionRow(
                title = stringResource(if (chat.pinned) R.string.unpin else R.string.pin),
                icon = PocketIcons.PushPin,
                subtitle = if (chat.pinned) "恢复到最近聊天排序" else "在这台手机上优先显示",
                onClick = { menu = false; onPin() },
            )
            PocketActionRow(
                title = stringResource(if (chat.archived) R.string.unarchive else R.string.archive),
                icon = PocketIcons.Inventory2,
                enabled = online && archiveEnabled,
                subtitle = when {
                    !online -> stringResource(R.string.offline_error)
                    !archiveEnabled -> stringResource(R.string.v02_unavailable)
                    chat.archived -> "移回最近聊天，保留对话内容"
                    else -> "移到已归档，对话内容仍会保留"
                },
                onClick = { if (online && archiveEnabled) { menu = false; onArchive() } },
            )
        }
    }
}

@Composable
private fun HomeRowStatus(status: String, unread: Boolean, pinned: Boolean) {
    val icon = when (status) {
        "inProgress" -> PocketIcons.PlayCircle
        "pending" -> PocketIcons.PendingActions
        "unknown" -> PocketIcons.Info
        "failed" -> PocketIcons.ErrorOutline
        "offline" -> PocketIcons.Computer
        else -> if (pinned) PocketIcons.PushPin else null
    }
    // Leading marker only when there is something to say; idle/stopped rows carry no badge.
    val shown = icon.takeIf { status in setOf("inProgress", "pending", "failed") || (pinned && status !in setOf("unknown", "offline")) }
    if (!unread && shown == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (unread) Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.secondary, CircleShape).testTag("unread"))
        if (shown != null) Icon(
            shown, null, Modifier.size(15.dp),
            tint = when (status) {
                "pending" -> MaterialTheme.colorScheme.secondary
                "failed" -> MaterialTheme.colorScheme.error
                "inProgress" -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

internal fun projectName(path: String): String = path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { path }

@Composable
internal fun HomeIconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressColor by animateColorAsState(if (pressed || focused) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, tween(pocketMotionMillis(120)), label = "home-icon-press")
    val iconScale by animateFloatAsState(if (pressed) .88f else 1f, tween(pocketMotionMillis(160)), label = "home-icon-scale")
    Box(
        modifier.size(48.dp).clip(CircleShape).background(pressColor)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(20.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun HomeEmptyState(search: String, project: String?, archived: Boolean, online: Boolean, canCreate: Boolean, onClearFilters: () -> Unit, onShowRecent: () -> Unit, onNew: () -> Unit) {
    val filtered = search.isNotBlank() || project != null
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(if (filtered) PocketIcons.Search else if (archived) PocketIcons.Inventory2 else PocketIcons.ChatBubbleOutline, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (filtered) stringResource(R.string.no_matches) else if (archived) "还没有归档的聊天" else stringResource(R.string.no_chats),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            when {
                filtered -> "试试其他关键词，或清除项目筛选。"
                !online -> stringResource(R.string.no_cache)
                archived -> "归档后的对话会保留在这里，随时可以移回。"
                else -> "从一个问题开始，或继续电脑上的工作。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        when {
            filtered -> PocketButton("清除筛选", onClearFilters, secondary = true)
            archived -> PocketButton(stringResource(R.string.v02_recent), onShowRecent, secondary = true)
            canCreate -> PocketButton(stringResource(R.string.new_chat), onNew)
        }
    }
}

@Composable
internal fun PocketSearch(value: String, onValue: (String) -> Unit, hint: String) {
    val focus = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(if (focused) MaterialTheme.colorScheme.outline.copy(alpha = .6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f), tween(pocketMotionMillis(160)), label = "home-search-focus")
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .45f), border = BorderStroke(.7.dp, borderColor), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 48.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(PocketIcons.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value, onValue,
                Modifier.weight(1f).padding(vertical = 12.dp).semantics { contentDescription = hint },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                decorationBox = { inner ->
                    Box { if (value.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge); inner() }
                },
            )
            if (value.isNotEmpty()) HomeIconAction(PocketIcons.Close, "清空搜索", { onValue("") }) else Spacer(Modifier.width(10.dp))
        }
    }
}

@Composable
internal fun EmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun DeviceOverview(s: PocketState, onInspect: () -> Unit, onSettings: () -> Unit, onReconnect: () -> Unit, onPair: (() -> Unit)? = null) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalHomeContentPadding.current).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(PocketIcons.Computer, null, Modifier.size(32.dp))
            Column { Text(s.computerName, style = MaterialTheme.typography.titleLarge); Text(context.stateLabel(s.connectionPhase.ifBlank { s.connection }), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (s.connectionDetail.isNotBlank()) Text(s.connectionDetail, style = MaterialTheme.typography.bodyMedium)
        if (s.lastSyncAt > 0) Text(stringResource(R.string.v02_sync, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.lastSyncAt))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        s.runtimes.forEach { runtime ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(7.dp).background(if (runtime.online) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline, CircleShape))
                Column(Modifier.weight(1f)) { Text(context.sourceLabel(runtime.source)); Text(runtimeVersionLabel(runtime.version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(context.stateLabel(if (runtime.online) "online" else "offline"), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (s.runtimes.isEmpty()) Text(stringResource(R.string.v02_no_runtime), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(s.connectionPhase == "authorization_required" && onPair != null) {
            PocketButton("重新扫码配对", onPair, modifier = Modifier.fillMaxWidth().testTag("repair-pairing"))
            Text("在电脑上生成新的配对码。当前会话缓存和草稿会保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Row { PocketTextButton(onClick = onInspect, enabled = s.online) { Text(stringResource(R.string.v02_inspect)) }; PocketTextButton(onClick = onReconnect) { Text(stringResource(R.string.reconnect)) } }
        HorizontalDivider()
        PocketTextButton(onClick = onSettings) { Icon(PocketIcons.Tune, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.settings)) }
    }
}
