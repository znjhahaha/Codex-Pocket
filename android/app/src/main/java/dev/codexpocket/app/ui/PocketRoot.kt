package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
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
import androidx.compose.ui.platform.LocalDensity
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
    LocalAssistantArtifacts provides remember(repo, s.computerId, s.mediaConnectionEpoch, s.online) {
        if (!s.online) null else object : AssistantArtifactSource {
            override val ownerKey = "${s.computerId}:${s.mediaConnectionEpoch}"
            override suspend fun file(artifact: AssistantArtifact) = repo.assistantArtifactFile(artifact)
        }
    },
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
    val chooseLocation = LocalExecutionLocationPicker.current
    val locationKind = LocalExecutionLocation.current?.kind
    var home by rememberSaveable { mutableStateOf(true) }
    var runtimeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(HomeTab.Chats) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var assistantReturn by rememberSaveable {mutableStateOf(false)}
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
            assistantReturn = assistantOpen || assistantReturn
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
    val backToParent: () -> Unit = { repo.clearSourcePreview(); repo.closeAgentReader(); if(assistantReturn) {assistantOpen=true;assistantReturn=false} else home=true }
    // The page opens once the conversation asked for is the one selected, so it never shows the last one read on its
    // way in. Selecting publishes within a frame or two, and the effect below turns the page as it does.
    val openChat: (ChatEntity) -> Unit = { repo.markRead(it); repo.select(it); if (s.selected?.key == it.key) home = false }
    val openAgent: (String) -> Unit = { id -> repo.clearSourcePreview(); repo.openAgentReader(id); detail = null }
    val openSettings: () -> Unit = { settings = true }
    val closeSettings: () -> Unit = { settings = false }
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
    val goBack: () -> Unit = {
        when { assistantOpen -> assistantOpen = false; repairPairing -> repairPairing = false; s.advanced.sourcePreview != null -> repo.clearSourcePreview(); projectFiles -> { repo.clearSourcePreview(); projectFiles = false }; detail != null -> { repo.clearSourcePreview(); detail = null }; settings -> settings = false; else -> backToParent() }
    }
    val canGoBack = assistantOpen || repairPairing || !home || settings || projectFiles || detail != null || s.advanced.sourcePreview != null
    // A detail on its way out is still drawn from the item it showed.
    val shownDetail = remember { mutableStateOf<TimelineItem?>(null) }
    detail?.let { shownDetail.value = it }
    fun pageFor(assistant: Boolean = assistantOpen, repair: Boolean = repairPairing, files: Boolean = projectFiles,
        detailOpen: Boolean = detail != null, inSettings: Boolean = settings, atHome: Boolean = home) =
        pocketPage(assistant, repair, files, detailOpen, inSettings, atHome || s.selected == null, s.initialized, s.paired)
    val page = pageFor()
    // Where Back leads, shown beneath the page while a back gesture is held.
    val backPage = when {
        assistantOpen -> pageFor(assistant = false)
        repairPairing -> pageFor(repair = false)
        s.advanced.sourcePreview != null -> null
        projectFiles -> pageFor(files = false)
        detail != null -> pageFor(detailOpen = false)
        settings -> pageFor(inSettings = false)
        assistantReturn -> PocketPage.Assistant
        else -> pageFor(atHome = true)
    }?.takeIf { it != page }
    PStage(page, depth = { it.depth }, back = backPage, onBack = goBack.takeIf { canGoBack }, source = { shown ->
        // What each page opens out of: its row on the home list, its card in the conversation, the assistant's button.
        when (shown) {
            PocketPage.Conversation -> s.selected?.key?.encoded?.let { "chat:$it" }
            PocketPage.Detail -> (detail ?: shownDetail.value)?.let { "detail:${it.key}" }
            PocketPage.Assistant -> "assistant"
            else -> null
        }
    }) { shown ->
        val settings = shown == PocketPage.Settings
        val conversation = shown == PocketPage.Conversation
        when (shown) {
            PocketPage.Assistant -> assistantHolder.SaveableStateProvider("assistant:${s.computerId}") { Box(Modifier.fillMaxSize()) {
                val assistantRuntime=s.assistant.snapshot?.runtimeId
                val store=s.runtimes.firstOrNull { it.id==assistantRuntime }?.configStoreId
                val threadLinks = ThreadLinkBinding(repo, if (store == null || assistantRuntime == null) null
                    else ThreadKey(s.computerId, store, s.assistant.snapshot?.coordinatorThreadId.orEmpty()))
                CompositionLocalProvider(LocalThreadLinkBinding provides threadLinks) {
                Column {
                s.error?.let { Notice(it, error = true, action = stringResource(R.string.dismiss), onAction = repo::clearError) }
                AssistantScreen(s.assistant, s.assistantModels,
                    s.runtimes.filter { it.role != "managedHost" || it.id == assistantRuntime }.map { AssistantRuntimeChoice(it.id, "${context.runtimeLabel(it, locationKind)} · ${runtimeVersionLabel(it.version)}", it.online && s.online) },
                    s.assistantWorkspaces(), repo::assistantAction,
                    onOpenThread = { runtime, thread -> assistantScope.launch {
                        repo.openAssistantThread(runtime, thread).join()
                        if (repo.state.value.selected?.key?.nativeId == thread) { assistantReturn=true;assistantOpen=false;home=false }
                    } }, onBack = {assistantOpen=false}, connected=s.online,
                    onProjectSelected = {repo.readAssistantProject(it)})
                }
                }
            }
            }
            PocketPage.Repair -> PairScreen(s.busy, repo::pair, phase = s.pairingPhase, error = s.error, onDismiss = { repairPairing = false })
            PocketPage.Files -> Box(Modifier.fillMaxSize()) { ProjectFilesScreen(s.advanced.projectFiles, s.advanced.projectFilesLoading, s.advanced.projectFilesError,
                onNavigate = navigateFiles, onOpen = openSource, onBack = { repo.clearSourcePreview(); projectFiles = false },
                canList = canListFiles, canRead = canReadFiles) }
            PocketPage.Detail -> Box(Modifier.fillMaxSize()) { val item = detail ?: shownDetail.value
                if (item != null) DetailScreen(item, { repo.clearSourcePreview(); detail = null }, onSource = openSource,
                onComment = repo::commentOnFile, onDraftComment = draftComment, canRead = canReadFiles,
                availableAgentIds = if (s.online) NativeActivityPresentation.describe(item).agents.map { it.threadId }.toSet() else emptySet(),
                onAgent = openAgent) }
            PocketPage.Home -> Box(Modifier.fillMaxSize()) { PocketHomeFrame(s, tab,
                s.approvals.count { !it.submitted && !ConversationPresentation.hookType(it.method) } + s.pendingQuestions.values.sumOf { values -> values.count { !it.flag("submitted") } },
                homeScrolled && tab == HomeTab.Chats, hostSheet, { hostSheet = true }, { newChat = true },
                { openSettings(); if (s.online) repo.devices() }, { tab = it; if (it == HomeTab.Devices && s.online) repo.devices() }, onAssistant = { assistantOpen=true }) {
                val insets = LocalHomeContentPadding.current
                val hasNotice = s.error != null
                Box(Modifier.fillMaxSize()) {
                PocketLightPoints(Modifier.matchParentSize(), edgeOnly = true, active = !newChat && !hostSheet)
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
                                    HomeTab.Chats -> ChatsHome(s, openChat, { newChat = true }, { q, a, m -> repo.refresh(q, a, m) }, { repo.pin(it) }, { repo.archive(it) }, runtimeFilter, onScrollState = { homeScrolled = it }, onAttention = { tab = HomeTab.Attention }, onEnsureDirectory = { q, a -> repo.ensureDirectory(q, a) })
                                    HomeTab.Attention -> AttentionHome(s, repo, openChat)
                                    HomeTab.Devices -> SettingsScreen(s, repo, { repo.preparePairing(); repairPairing = true }, Modifier.padding(LocalHomeContentPadding.current), management = true, usageState = s.usage, onUsageRefresh = { repo.loadUsage(refresh = true) }, onUsageFilter = { repo.loadUsage(it) })
                                }
                            }
                        }
                    }
                }
                }
            } }
            else -> Column(Modifier.fillMaxSize()) {
                val bar = !conversation && (s.paired || settings)
                if (bar) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = homeInset), verticalAlignment = Alignment.CenterVertically) {
                    if (settings) CircleAction(PocketIcons.ArrowBack, stringResource(R.string.back_conversation)) { closeSettings() }
                    PText(if (settings) stringResource(R.string.settings) else if (!s.paired) "Codex Pocket" else stringResource(tab.label), Modifier.weight(1f).padding(horizontal = 6.dp), style = Pocket.type.titleLarge)
                    if (!settings) CircleAction(PocketIcons.Settings, stringResource(R.string.settings)) { openSettings(); if (s.online) repo.devices() }
                }
                // The bar, where there is one, has already cleared the status bar.
                Column(Modifier.fillMaxSize().then(if (conversation) Modifier else Modifier.windowInsetsPadding(
                    if (bar) WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) else WindowInsets.systemBars))) {
                    if (!conversation && (s.paired || settings)) s.error?.let { Notice(it, error = true, action = stringResource(R.string.dismiss), onAction = repo::clearError) }
                    val selected = s.selected
                    when {
                        settings -> Box(Modifier.fillMaxSize()) { SettingsScreen(s, repo, { repo.preparePairing(); repairPairing = true }, usageState = s.usage, onUsageRefresh = { repo.loadUsage(refresh = true) }, onUsageFilter = { repo.loadUsage(it) }) }
                        shown == PocketPage.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PocketProgressStatus("正在准备") }
                        shown == PocketPage.Pairing -> PairScreen(s.busy, repo::pair, phase = s.pairingPhase, error = s.error, onDismiss = { openSettings() })
                        selected != null -> holder.SaveableStateProvider(selected.key.encoded) {
                            // The stage brings the page in; this replays only when the conversation itself changes.
                            PocketPageEntrance(selected.key.encoded, initial = false) {
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
    if (newChat) NewChatDialog(s, { newChat = false }) { mode, cwd, runtimeId, createDirectory -> createOrigin = s.selected?.key; repo.create(mode, cwd, runtimeId, linkProject = true, createDirectory = createDirectory) }
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
    if (hostSheet) PocketScreen(s.computerName, { hostSheet = false }, runtimeLocationSubtitle(locationKind)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            chooseLocation?.let { choose -> PocketButton("切换执行位置", { hostSheet = false; choose() }, Modifier.fillMaxWidth().testTag("execution-location-picker"), secondary = true) }
            s.runtimes.forEach { runtime ->
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { runtimeFilter = runtime.id; hostSheet = false; home = true; tab = HomeTab.Chats }, verticalAlignment = Alignment.CenterVertically) {
                    PIcon(runtimeIcon(runtime, locationKind), null)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { PText(context.runtimeLabel(runtime, locationKind)); PText(runtimeVersionLabel(runtime.version), style = Pocket.type.bodySmall) }
                    Box(Modifier.size(7.dp).background(if (runtime.online) Pocket.colors.accent else Pocket.colors.inkFaint, CircleShape))
                }
            }
            PocketTextButton(onClick = { runtimeFilter = null; hostSheet = false; home = true; tab = HomeTab.Chats }) { PText(stringResource(R.string.all_projects)) }
            PocketTextButton(onClick = { hostSheet = false; home = true; tab = HomeTab.Devices }) { PText("设备与管理") }
        }
    }
    CompositionLocalProvider(LocalPocketSheetAsScreen provides true) {
    if (goalSheet) GoalSheet(s, repo) { goalSheet = false }
    if (usageSheet) UsageSheet(s) { usageSheet = false }
    if (referenceSheet) ReferenceSheet(s, repo) { referenceSheet = false }
    }
    if (conversationMenu) ConversationPanel(s, repo, conversationMenuAnchor, { conversationMenu = false },
        onFiles = if (canListFiles) ({ projectFiles = true; repo.browseProjectFiles() }) else null, initialEditor = conversationInitialEditor)
    if (confirmCompact) PocketDialog({ confirmCompact = false }, title = { PText("压缩上下文") },
        text = { PText("压缩当前会话后继续工作。已有草稿会保留。") },
        confirmButton = { PocketButton("压缩", { confirmCompact = false; repo.compact() }, enabled = canMutate(s, "thread/compact/start")) },
        dismissButton = { PocketButton("取消", { confirmCompact = false }, secondary = true) })
    if (rename) {
        var text by remember { mutableStateOf(s.selected?.title.orEmpty()) }
        PocketDialog(onDismissRequest = { rename = false }, title = { PText(stringResource(R.string.rename_chat)) },
            text = { PocketTextField(text, { text = it }, singleLine = true, label = "会话标题") },
            confirmButton = { PocketButton(stringResource(R.string.save), enabled = text.isNotBlank() && canMutate(s, "thread/name/set"), onClick = { repo.rename(text); rename = false }) },
            dismissButton = { PocketButton(stringResource(R.string.cancel), onClick = { rename = false }, secondary = true) })
    }
}

/** The destinations of the root stage. Deeper pages push over shallower ones; equals fade. */
internal enum class PocketPage(val depth: Int) { Loading(0), Pairing(0), Home(0), Settings(1), Assistant(1), Conversation(2), Repair(3), Files(3), Detail(3) }

/** The page the root shows for these flags, in the order the screens take precedence. */
internal fun pocketPage(assistant: Boolean, repair: Boolean, files: Boolean, detail: Boolean, settings: Boolean, home: Boolean,
    initialized: Boolean, paired: Boolean): PocketPage = when {
    assistant -> PocketPage.Assistant
    repair -> PocketPage.Repair
    files -> PocketPage.Files
    detail -> PocketPage.Detail
    initialized && paired && !settings && home -> PocketPage.Home
    settings -> PocketPage.Settings
    !initialized -> PocketPage.Loading
    !paired -> PocketPage.Pairing
    else -> PocketPage.Conversation
}

@Composable
internal fun ConversationHeader(s: PocketState, onBack: () -> Unit, onHost: () -> Unit, onModels: () -> Unit, compact: Boolean = false, actions: @Composable () -> Unit = {}) {
    val syncLabel = LocalConversationSyncLabel.current
    val inset by animateDpAsState(if (compact) 6.dp else 14.dp, tween(pocketMotionMillis(220)), label = "header-inset")
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = inset).testTag("conversation-header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircleAction(PocketIcons.ArrowBack, stringResource(R.string.open_chats), onBack)
        PocketGlassSurface(onClick = onHost, shape = PocketOval, modifier = Modifier.weight(1f), dense = true) {
            Column(Modifier.heightIn(min = 48.dp).padding(horizontal = 13.dp, vertical = 7.dp), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PText(s.selected?.title ?: "Codex", modifier = Modifier.weight(1f).testTag("conversation-title"), style = Pocket.type.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (compact && syncLabel != null) ConversationSyncStatus(syncLabel, compact = true)
                }
                AnimatedVisibility(!compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Fixed subtitle slot: refresh events never change the transcript's top padding.
                    val lineHeight = with(LocalDensity.current) { Pocket.type.labelSmall.lineHeight.toDp() }
                    androidx.compose.animation.Crossfade(targetState = syncLabel, modifier = Modifier.weight(1f).height(lineHeight),
                        animationSpec = tween(pocketMotionMillis(140)), label = "header-sync-status") { label ->
                        if (label != null) ConversationSyncStatus(label)
                        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(5.dp).background(if (s.online) Pocket.colors.accent else Pocket.colors.inkFaint, CircleShape))
                            PText(s.computerName, style = Pocket.type.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
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
    var taps by remember { mutableIntStateOf(0) }
    PocketGlassSurface(modifier = Modifier.size(48.dp), shape = CircleShape, onClick = { taps++; onClick() }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PTapIcon(icon, taps, Modifier.size(21.dp), contentDescription = label) }
    }
}

@Composable
internal fun ActionIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    dev.codexpocket.app.ui.kit.PIconButton(icon, label, onClick, enabled = enabled)
}

@Composable
internal fun Notice(text: String, error: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    if (error) {
        ConversationErrorRow(remember(text) { ConversationErrorPresentation.fromText(text) }, "notice:${text.hashCode()}:${text.length}",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp), action, onAction)
        return
    }
    PSurface(color = if (error) Pocket.colors.dangerSoft.copy(alpha = .7f) else Pocket.colors.fillStrong, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PIcon(if (error) PocketIcons.ErrorOutline else PocketIcons.Info, null, Modifier.padding(end = 10.dp).size(18.dp), tint = if (error) Pocket.colors.danger else Pocket.colors.inkMuted)
            PText(text, Modifier.weight(1f), style = Pocket.type.bodySmall, color = if (error) Pocket.colors.onDangerSoft else Pocket.colors.inkMuted)
            if (action != null) PocketTextButton(onClick = onAction) { PText(action) }
        }
    }
}
@Composable
internal fun NewChatDialog(s: PocketState, onDismiss: () -> Unit, onCreate: (SessionMode, String, String?, Boolean) -> Unit) {
    ProjectCreationForm(s, onDismiss, onCreate)
}
