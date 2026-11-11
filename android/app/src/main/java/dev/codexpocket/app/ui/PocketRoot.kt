@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.codexpocket.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.R
import kotlinx.coroutines.launch

@Composable
fun PocketRoot(s: PocketState, repo: PocketRepository) { CompositionLocalProvider(LocalPocketGlass provides s.glassEffects,
    LocalImageConnection provides (s.mediaConnectionEpoch to s.online),
    LocalDesktopImages provides remember(repo, s.selected?.key, s.selected?.runtimeId) { s.selected?.let { owner -> object : DesktopImageSource {
        override val ownerKey = "${owner.key.encoded}:${owner.runtimeId}"
        override suspend fun file(path: String) = repo.desktopImage(path, owner)
        override suspend fun retry(path: String) { repo.clearDesktopImageFailure(path, owner) }
    } } },
    LocalToolMediaImages provides remember(repo, s.selected?.key, s.selected?.runtimeId, s.agentReader?.child) {
        val main = s.selected; val child = s.agentReader?.child
        object : ToolMediaImageSource {
            override suspend fun read(media: ToolMediaRef, offset: Long, maxBytes: Int) = repo.toolMedia(media, offset, maxBytes,
                if (child?.key?.nativeId == media.threadId && child.runtimeId == media.runtimeId) child else main)
            override suspend fun refresh(media: ToolMediaRef, manual: Boolean) { repo.refreshToolMedia(media, manual) }
        }
    }) { ProvideMarkdownRendering(ThreadLinkBinding(repo, s.selected?.key)) { PocketBackdrop { PocketContent(s, repo) } } } }

@Composable
internal fun PocketBackdrop(content: @Composable () -> Unit) {
    PocketOverlayHost(content = content)
}

@Composable
private fun PocketContent(s: PocketState, repo: PocketRepository) {
    val context = LocalContext.current
    var home by rememberSaveable { mutableStateOf(true) }
    var runtimeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(HomeTab.Chats) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var assistantOpen by rememberSaveable { mutableStateOf(false) }
    val assistantScope = rememberCoroutineScope()
    LaunchedEffect(assistantOpen, s.online, s.computerId) {
        if (assistantOpen) while (true) { repo.loadAssistant().join(); kotlinx.coroutines.delay(2000) }
    }
    LaunchedEffect(home, tab, s.online, s.computerId) { if (home && tab == HomeTab.Devices) repo.loadUsage() }
    var repairPairing by rememberSaveable { mutableStateOf(false) }
    var newChat by rememberSaveable { mutableStateOf(false) }
    var modelSheet by rememberSaveable { mutableStateOf(false) }
    var modelAnchor by remember(s.selected?.key) { mutableStateOf<Rect?>(null) }
    var hostSheet by rememberSaveable { mutableStateOf(false) }
    var goalSheet by rememberSaveable { mutableStateOf(false) }
    var usageSheet by rememberSaveable { mutableStateOf(false) }
    var referenceSheet by rememberSaveable { mutableStateOf(false) }
    var projectFiles by rememberSaveable { mutableStateOf(false) }
    var conversationMenu by rememberSaveable { mutableStateOf(false) }
    var conversationMenuAnchor by remember { mutableStateOf<Rect?>(null) }
    var conversationInitialEditor by remember { mutableStateOf(ConversationEditor.None) }
    var confirmCompact by remember { mutableStateOf(false) }
    var readingHistory by remember { mutableStateOf(false) }
    var homeScrolled by remember { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<TimelineItem?>(null) }
    var previousSelection by remember { mutableStateOf(s.selected?.key) }
    LaunchedEffect(repo) {
        repo.threadLinkOpened.collect {
            assistantOpen = false
            home = false
            settings = false
            projectFiles = false
            detail = null
            modelSheet = false
            conversationMenu = false
            repo.clearSourcePreview()
            repo.closeAgentReader()
        }
    }
    var createOrigin by remember { mutableStateOf<ThreadKey?>(null) }
    LaunchedEffect(home) { if (home) { repo.clearSourcePreview(); repo.closeAgentReader() } }
    val backToParent: () -> Unit = { repo.clearSourcePreview(); repo.closeAgentReader(); home = true }
    val openAgent: (String) -> Unit = { id -> repo.clearSourcePreview(); repo.openAgentReader(id); detail = null }
    val holder = rememberSaveableStateHolder()
    val homeHolder = rememberSaveableStateHolder()
    val assistantHolder = rememberSaveableStateHolder()
    val conversation = !home && s.selected != null && !settings
    val canListFiles = s.paired && s.online && s.selected != null && s.supports("workspace/list")
    val canReadFiles = s.paired && s.online && s.selected != null && s.supports("workspace/read")
    val sourcePreview = s.advanced.sourcePreview
    val sourceOwnerVisible = sourcePreview?.let { source ->
        if (source.readOnly) conversation && s.agentReader?.let { reader ->
            reader.parent == s.selected?.key && reader.child?.key == source.owner && !reader.loading &&
                reader.error == null && reader.updatedAt > 0 && reader.child?.conflict == false
        } == true
        else source.owner == null || source.owner == s.selected?.key
    } == true
    LaunchedEffect(sourcePreview, sourceOwnerVisible) {
        if (sourcePreview != null && !sourceOwnerVisible) repo.clearSourcePreview()
    }
    val openSource: (String) -> Unit = { if (canReadFiles) repo.readSource(it) }
    val navigateFiles: (String) -> Unit = { if (canListFiles) repo.browseProjectFiles(it) }
    val draftComment: (FileCommentDraft) -> Unit = { if (s.paired && s.selected != null) repo.commentOnFile(it) }
    LaunchedEffect(repairPairing, s.online, s.pairingPhase) {
        if (repairPairing && s.online && s.pairingPhase == PairingPhase.Paired) { repairPairing = false; settings = false; home = true }
    }
    val homeInset by animateDpAsState(if (homeScrolled && !settings) 4.dp else 8.dp, tween(pocketMotionMillis(220)), label = "home-header")
    val visibleConversation = conversation && !repairPairing && !newChat && !hostSheet && !goalSheet && !usageSheet &&
        !referenceSheet && !conversationMenu && !projectFiles && detail == null && s.advanced.sourcePreview == null
    val selectedUnread = s.selected?.key?.encoded?.let(s.unreadKeys::contains) == true
    LaunchedEffect(visibleConversation, s.selected?.key, selectedUnread) { if (visibleConversation) s.selected?.let(repo::markRead) }
    LaunchedEffect(s.selected?.key) {
        if (s.selected?.key != previousSelection) {
            modelSheet = false
            projectFiles = false
            conversationMenu = false
            detail = null
            if (s.selected != null) home = false
        }
        previousSelection = s.selected?.key
    }
    BackHandler(assistantOpen || repairPairing || !home || settings || projectFiles || detail != null || s.advanced.sourcePreview != null) {
        when { assistantOpen -> assistantOpen = false; repairPairing -> repairPairing = false; s.advanced.sourcePreview != null -> repo.clearSourcePreview(); projectFiles -> { repo.clearSourcePreview(); projectFiles = false }; detail != null -> { repo.clearSourcePreview(); detail = null }; settings -> settings = false; else -> backToParent() }
    }
    run {
        when {
            assistantOpen -> assistantHolder.SaveableStateProvider("assistant:${s.computerId}") { PocketPageEntrance("assistant") {
                val assistantRuntime=s.assistant.snapshot?.runtimeId
                val store=s.runtimes.firstOrNull { it.id==assistantRuntime }?.configStoreId
                val threadLinks = ThreadLinkBinding(repo, if (store == null || assistantRuntime == null) null
                    else ThreadKey(s.computerId, store, s.assistant.snapshot?.coordinatorThreadId.orEmpty()))
                CompositionLocalProvider(LocalThreadLinkBinding provides threadLinks) {
                Column {
                s.error?.let { Notice(it, error = true, action = stringResource(R.string.dismiss), onAction = repo::clearError) }
                AssistantScreen(s.assistant, s.assistantModels,
                    s.runtimes.filter { it.role != "managedHost" || it.id == assistantRuntime }.map { AssistantRuntimeChoice(it.id, "${if (it.role == "managedHost") "助理 CLI" else it.source} · ${it.version}", it.online && s.online) },
                    s.chats.filter { store==null || it.key.configStoreId==store }.map {
                        AssistantWorkspaceChoice(assistantRuntime ?: it.runtimeId, it.key.nativeId, it.title,
                            canWrite = s.online && !it.isSubagent)
                    }.distinctBy { it.threadId }, repo::assistantAction,
                    onOpenThread = { runtime, thread -> assistantScope.launch {
                        repo.openAssistantThread(runtime, thread).join()
                        if (repo.state.value.selected?.key?.nativeId == thread) { assistantOpen=false;home=false }
                    } }, onBack = {assistantOpen=false})
                }
                }
            }
            }
            repairPairing -> PairScreen(s.busy, repo::pair, phase = s.pairingPhase, error = s.error, onDismiss = { repairPairing = false })
            projectFiles -> PocketPageEntrance("files") { ProjectFilesScreen(s.advanced.projectFiles, s.advanced.projectFilesLoading, s.advanced.projectFilesError,
                onNavigate = navigateFiles, onOpen = openSource, onBack = { repo.clearSourcePreview(); projectFiles = false },
                canList = canListFiles, canRead = canReadFiles) }
            detail != null -> PocketPageEntrance("detail:${detail!!.key}") { DetailScreen(detail!!, { repo.clearSourcePreview(); detail = null }, onSource = openSource,
                onComment = repo::commentOnFile, onDraftComment = draftComment, canRead = canReadFiles,
                availableAgentIds = if (s.online) NativeActivityPresentation.describe(detail!!).agents.map { it.threadId }.toSet() else emptySet(),
                onAgent = openAgent) }
            s.initialized && s.paired && !settings && !conversation -> PocketPageEntrance("home", back = true) { PocketHomeFrame(s, tab,
                s.approvals.count { !it.submitted && !ConversationPresentation.hookType(it.method) } + s.pendingQuestions.values.sumOf { values -> values.count { !it.flag("submitted") } },
                homeScrolled && tab == HomeTab.Chats, hostSheet, { hostSheet = true }, { newChat = true },
                { settings = true; if (s.online) repo.devices() }, { tab = it; if (it == HomeTab.Devices && s.online) repo.devices() }, onAssistant = { assistantOpen=true }) {
                val insets = LocalHomeContentPadding.current
                val hasNotice = s.error != null
                Column(Modifier.fillMaxSize()) {
                    if (hasNotice) Column(Modifier.padding(top = insets.calculateTopPadding())) {
                        s.error?.let { Notice(it, error = true, action = stringResource(R.string.dismiss), onAction = repo::clearError) }
                    }
                    CompositionLocalProvider(LocalHomeContentPadding provides PaddingValues(top = if (hasNotice) 0.dp else insets.calculateTopPadding(), bottom = insets.calculateBottomPadding())) {
                        AnimatedContent(tab, modifier = Modifier.fillMaxSize(), transitionSpec = {
                            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (fadeIn(tween(pocketMotionMillis(180))) + slideInHorizontally(tween(pocketMotionMillis(220))) { direction * it / 16 }) togetherWith
                                (fadeOut(tween(pocketMotionMillis(120))) + slideOutHorizontally(tween(pocketMotionMillis(180))) { -direction * it / 20 })
                        }, label = "home-section") { shownTab ->
                            homeHolder.SaveableStateProvider(shownTab.name) {
                                when (shownTab) {
                                    HomeTab.Chats -> ChatsHome(s, { repo.markRead(it); repo.select(it); home = false }, { newChat = true }, { q, a, m -> repo.refresh(q, a, m) }, { repo.pin(it) }, { repo.archive(it) }, runtimeFilter, onScrollState = { homeScrolled = it }, onAttention = { tab = HomeTab.Attention }, onEnsureDirectory = { q, a -> repo.ensureDirectory(q, a) })
                                    HomeTab.Attention -> AttentionHome(s, repo) { repo.markRead(it); repo.select(it); home = false }
                                    HomeTab.Devices -> SettingsScreen(s, repo, { repo.preparePairing(); repairPairing = true }, Modifier.padding(LocalHomeContentPadding.current), management = true, usageState = s.usage, onUsageRefresh = { repo.loadUsage() }, onUsageFilter = { repo.loadUsage(it) })
                                }
                            }
                        }
                    }
                }
            } }
            else -> Scaffold(containerColor = Color.Transparent, topBar = {
                if (!conversation && (s.paired || settings)) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = homeInset), verticalAlignment = Alignment.CenterVertically) {
                    if (settings) CircleAction(PocketIcons.ArrowBack, stringResource(R.string.back_conversation)) { settings = false }
                    Text(if (settings) stringResource(R.string.settings) else if (!s.paired) "Codex Pocket" else stringResource(tab.label), Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.titleLarge)
                    if (!settings) CircleAction(PocketIcons.Settings, stringResource(R.string.settings)) { settings = true; if (s.online) repo.devices() }
                }
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(if (conversation) PaddingValues(0.dp) else padding)) {
                    if (!conversation && (s.paired || settings)) s.error?.let { Notice(it, error = true, action = stringResource(R.string.dismiss), onAction = repo::clearError) }
                    when {
                        settings -> PocketPageEntrance("settings") { SettingsScreen(s, repo, { repo.preparePairing(); repairPairing = true }, usageState = s.usage, onUsageRefresh = { repo.loadUsage() }, onUsageFilter = { repo.loadUsage(it) }) }
                        !s.initialized -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PocketProgressStatus("正在准备") }
                        !s.paired -> PairScreen(s.busy, repo::pair, phase = s.pairingPhase, error = s.error, onDismiss = { settings = true })
                        conversation -> holder.SaveableStateProvider(s.selected!!.key.encoded) {
                            PocketPageEntrance(s.selected!!.key.encoded) {
                                CompositionLocalProvider(LocalComposerMenuBinding provides ComposerMenuBinding(
                                    expanded = modelSheet, onAnchor = { if (!modelSheet) modelAnchor = it },
                                ), LocalNativeAgentNames provides s.agentChats.associate { it.key.nativeId to it.title }, LocalNativeAgentReader provides openAgent,
                                    LocalInlineDiffComment provides draftComment, LocalAttachmentFilePreview provides openSource) {
                                    ChatScreen(s, repo, { modelSheet = true }, { detail = it }, { referenceSheet = true }, onReadingHistory = { readingHistory = it }, onAgent = openAgent,
                                        onComposerAction = { action -> when(action) {
                                            ComposerAction.Goal, ComposerAction.Rename -> {
                                                conversationInitialEditor = if (action == ComposerAction.Goal) ConversationEditor.Goal else ConversationEditor.Rename
                                                conversationMenu = true
                                            }
                                            ComposerAction.Compact -> confirmCompact = true
                                            else -> Unit
                                        } }) {
                                        ConversationHeader(s, backToParent, { hostSheet = true }, { modelSheet = true }, compact = readingHistory) {
                                            ContextIndicator(s, repo::compact, { repo.refreshContext() })
                                            Box(Modifier.onGloballyPositioned { if (!conversationMenu) conversationMenuAnchor = it.boundsInRoot() }) {
                                                CircleAction(PocketIcons.MoreHoriz, stringResource(R.string.chat_actions)) { conversationInitialEditor = ConversationEditor.None; conversationMenu = true }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    s.agentReader?.takeIf { conversation && it.parent == s.selected?.key }?.let { reader ->
        CompositionLocalProvider(LocalNativeAgentNames provides s.agentChats.associate { it.key.nativeId to it.title },
            LocalDesktopImages provides remember(repo, reader.child?.key, reader.child?.runtimeId) { reader.child?.let { child -> object : DesktopImageSource {
                override val ownerKey = "${child.key.encoded}:${child.runtimeId}"
                override suspend fun file(path: String) = repo.desktopImage(path, child)
                override suspend fun retry(path: String) { repo.clearDesktopImageFailure(path, child) }
            } } }) {
        val liveStatuses = remember(s.agentStatuses, reader.parent) {
            s.agentStatuses.filterKeys { it.computerId == reader.parent.computerId && it.configStoreId == reader.parent.configStoreId }
                .mapKeys { it.key.nativeId }
        }
        AgentReaderPanel(reader.copy(liveStatuses = liveStatuses), s.currentAgents(), s.agentChats.associate { it.key.nativeId to it.title }, s.online,
            onOpen = openAgent, onList = { repo.clearSourcePreview(); repo.agentReaderList() },
            onClose = { repo.clearSourcePreview(); repo.closeAgentReader() },
            onFullscreen = repo::agentReaderFullscreen,
            onOlder = { repo.clearSourcePreview(); repo.olderAgent() }, onRetry = { repo.clearSourcePreview(); repo.retryAgent() },
            onSource = { owner, path -> repo.readSource(path, owner = owner) })
        }
    }
    // Keep DetailScreen/ProjectFilesScreen mounted underneath: returning restores their exact selection and scroll.
    sourcePreview?.takeIf { sourceOwnerVisible }?.let { source ->
        PocketPageOverlay(source.path, repo::clearSourcePreview) {
            val runtime = s.runtimes.firstOrNull { it.id == (if (source.readOnly) s.agentReader?.child?.runtimeId else s.selected?.runtimeId) }
            val canRead = s.paired && s.online && s.supports("workspace/read", runtime)
            val onOpen: (String) -> Unit = { if (canRead) repo.readSource(it, owner = source.owner) }
            if (source.readOnly) SourceScreen(source, onBack = repo::clearSourcePreview, onOpen = onOpen, canRead = canRead)
            else SourceScreen(source, onBack = repo::clearSourcePreview, onComment = repo::commentOnFile,
                onOpen = onOpen, onDraftComment = draftComment, canRead = canRead)
        }
    }
    if (newChat) NewChatDialog(s, { newChat = false }) { mode, cwd, runtimeId -> createOrigin = s.selected?.key; repo.create(mode, cwd, runtimeId) }
    LaunchedEffect(s.selected?.key, s.actionStatus["create"]?.loading) {
        if (newChat && s.actionStatus["create"]?.loading != true && s.selected != null && s.selected.key != createOrigin) { newChat = false; home = false }
    }
    if (modelSheet && s.paired && s.selected != null) {
        LaunchedEffect(s.selected?.key) { repo.refreshModels() }
        PocketAnchoredMenu("模型", { modelSheet = false }, modelAnchor, widthDp = 320f) {
            ModelSheet(s, { model, effort ->
                if (s.online && s.modelCatalogState == ModelCatalogState.Ready && model in s.models) {
                    repo.chooseModel(model, effort)
                    modelSheet = false
                }
            }, repo::chooseMode, { repo.refreshModels() }, onEffort = repo::chooseModel, onFast = { repo.setFastMode(it) })
        }
    }
    if (hostSheet) PocketScreen(s.computerName, { hostSheet = false }, "此电脑上的 Codex") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            s.runtimes.forEach { runtime ->
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { runtimeFilter = runtime.id; hostSheet = false; home = true; tab = HomeTab.Chats }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (runtime.source == "cli") PocketIcons.Terminal else PocketIcons.Computer, null)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(context.sourceLabel(runtime.source)); Text(runtimeVersionLabel(runtime.version), style = MaterialTheme.typography.bodySmall) }
                    Box(Modifier.size(7.dp).background(if (runtime.online) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline, CircleShape))
                }
            }
            PocketTextButton(onClick = { runtimeFilter = null; hostSheet = false; home = true; tab = HomeTab.Chats }) { Text(stringResource(R.string.all_projects)) }
            PocketTextButton(onClick = { hostSheet = false; home = true; tab = HomeTab.Devices }) { Text("设备与管理") }
        }
    }
    CompositionLocalProvider(LocalPocketSheetAsScreen provides true) {
    if (goalSheet) GoalSheet(s, repo) { goalSheet = false }
    if (usageSheet) UsageSheet(s) { usageSheet = false }
    if (referenceSheet) ReferenceSheet(s, repo) { referenceSheet = false }
    }
    if (conversationMenu) ConversationPanel(s, repo, conversationMenuAnchor, { conversationMenu = false },
        onFiles = if (canListFiles) ({ projectFiles = true; repo.browseProjectFiles() }) else null, initialEditor = conversationInitialEditor)
    if (confirmCompact) PocketDialog({ confirmCompact = false }, title = { Text("压缩上下文") },
        text = { Text("压缩当前会话后继续工作。已有草稿会保留。") },
        confirmButton = { PocketButton("压缩", { confirmCompact = false; repo.compact() }, enabled = canMutate(s, "thread/compact/start")) },
        dismissButton = { PocketButton("取消", { confirmCompact = false }, secondary = true) })
    if (rename) {
        var text by remember { mutableStateOf(s.selected?.title.orEmpty()) }
        PocketDialog(onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.rename_chat)) },
            text = { PocketTextField(text, { text = it }, singleLine = true, label = "会话标题") },
            confirmButton = { PocketButton(stringResource(R.string.save), enabled = text.isNotBlank() && canMutate(s, "thread/name/set"), onClick = { repo.rename(text); rename = false }) },
            dismissButton = { PocketButton(stringResource(R.string.cancel), onClick = { rename = false }, secondary = true) })
    }
}

@Composable
internal fun ConversationHeader(s: PocketState, onBack: () -> Unit, onHost: () -> Unit, onModels: () -> Unit, compact: Boolean = false, actions: @Composable () -> Unit = {}) {
    val inset by animateDpAsState(if (compact) 6.dp else 14.dp, tween(pocketMotionMillis(220)), label = "header-inset")
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = inset).testTag("conversation-header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircleAction(PocketIcons.ArrowBack, stringResource(R.string.open_chats), onBack)
        PocketGlassSurface(onClick = onHost, shape = PocketOval, modifier = Modifier.weight(1f), dense = true) {
            Column(Modifier.heightIn(min = 48.dp).padding(horizontal = 13.dp, vertical = 7.dp), verticalArrangement = Arrangement.Center) {
                Text(s.selected?.title ?: "Codex", modifier = Modifier.testTag("conversation-title"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AnimatedVisibility(!compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(5.dp).background(if (s.online) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline, CircleShape))
                    Text(s.computerName, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TaskElapsedLabel(s.timeline.activeTurn?.let { s.timeline.turns[it] })
                }
                }
            }
        }
        actions()
    }
}

@Composable
internal fun CircleAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    PocketGlassSurface(modifier = Modifier.size(48.dp), shape = CircleShape, onClick = onClick) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(21.dp)) }
    }
}

@Composable
internal fun ActionIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, pocketPressSpring(), label = "icon-press")
    Box(Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
        .background(if (pressed || focused) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp), tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
    }
}

@Composable
internal fun Notice(text: String, error: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    if (error) {
        ConversationErrorRow(remember(text) { ConversationErrorPresentation.fromText(text) }, "notice:${text.hashCode()}:${text.length}",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp), action, onAction)
        return
    }
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer.copy(alpha = .7f) else MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (error) PocketIcons.ErrorOutline else PocketIcons.Info, null, Modifier.padding(end = 10.dp).size(18.dp), tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) PocketTextButton(onClick = onAction) { Text(action) }
        }
    }
}
@Composable
private fun NewChatDialog(s: PocketState, onDismiss: () -> Unit, onCreate: (SessionMode, String, String?) -> Unit) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(s.sessionPreferences.defaultMode) }
    val available = sessionRuntimes(s.runtimes, mode).filter { s.supports("thread/start", it) }
    val preferences = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var runtimeId by rememberSaveable { mutableStateOf(available.firstOrNull { it.id == preferences.getString("newRuntime", null) }?.id ?: available.firstOrNull()?.id) }
    LaunchedEffect(mode, available.map { it.id }) { if (available.none { it.id == runtimeId }) runtimeId = available.firstOrNull()?.id }
    val creating = s.actionStatus["create"]?.loading == true
    var cwd by rememberSaveable { mutableStateOf(preferences.getString("newCwd", "").orEmpty()) }
    val projects = remember(s.chats, runtimeId) { s.chats.filter { it.runtimeId == runtimeId }.map { it.cwd }.filter(String::isNotBlank).distinct().take(6) }
    PocketScreen(context.getString(R.string.start_chat), onDismiss, "选择工作位置，然后开始对话") {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SessionModeChoices(mode, !creating) { mode = it }
            Text(if (mode == SessionMode.Desktop) "在桌面创建并跟随此会话" else "新建由 CLI 独立运行的会话", style = MaterialTheme.typography.bodySmall)
            Text(context.getString(R.string.choose_runtime), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (available.isEmpty()) Text(if (mode == SessionMode.Desktop && s.supports(BrokerCapability.EnsureRuntime)) "开始时将准备桌面运行环境" else context.getString(R.string.no_runtime))
            available.forEach { runtime ->
                PocketActionRow(context.sourceLabel(runtime.source), if (runtime.source == "cli") PocketIcons.Terminal else PocketIcons.Computer,
                    { runtimeId = runtime.id }, subtitle = runtimeVersionLabel(runtime.version), selected = runtime.id == runtimeId, enabled = !creating)
            }
            if (projects.isNotEmpty()) {
                Text(stringResource(R.string.v02_recent_projects), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                projects.forEach { path ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { cwd = path }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(PocketIcons.FolderOpen, null, Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) { Text(path.substringAfterLast('\\').substringAfterLast('/')); Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (cwd == path) Icon(PocketIcons.Check, null, Modifier.size(18.dp))
                    }
                }
            }
            PocketTextField(cwd, { cwd = it }, Modifier.fillMaxWidth(), label = context.getString(R.string.project_folder), placeholder = context.getString(R.string.use_native_default), singleLine = true)
            Text(stringResource(R.string.v02_existing_worktree), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.actionStatus["create"]?.phase?.let { PocketProgressStatus(it) }
            s.actionStatus["create"]?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PocketButton(context.getString(R.string.create), enabled = !creating && s.online &&
                (available.any { it.id == runtimeId } || (mode == SessionMode.Desktop && s.supports(BrokerCapability.EnsureRuntime))), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                preferences.edit().putString("newCwd", cwd).apply()
                runtimeId?.let { preferences.edit().putString("newRuntime", it).apply() }
                onCreate(mode, cwd, runtimeId)
            })
        }
    }
}
