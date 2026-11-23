@file:OptIn(kotlinx.coroutines.FlowPreview::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.R
import dev.codexpocket.wire.ExecutionTargetKind
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

@Composable
internal fun ChatScreen(s: PocketState, repo: PocketRepository, onModels: () -> Unit, onDetail: (TimelineItem) -> Unit, onReferences: () -> Unit, onReadingHistory: (Boolean) -> Unit = {}, onAgent: (String) -> Unit = {}, onComposerAction: (ComposerAction) -> Unit = {}, header: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val locationKind = LocalExecutionLocation.current?.kind
    val chat = s.selected ?: return
    ObserveThreadHandoff(s, repo)
    DisposableEffect(repo, chat.key) {
        val observation = repo.observeConversation(chat.key)
        onDispose { observation.cancel() }
    }
    key(chat.key.encoded) {
        var composerFocusRequest by remember { mutableIntStateOf(0) }
        val proposedPlan = remember(s.timeline, s.resolvedPlans) { PlanPresentation.latest(s.timeline)?.takeUnless { it.revision in s.resolvedPlans } }
        val dockPlan = proposedPlan?.takeUnless { it.revision in s.dismissedPlans || s.messageEdit != null }
        val planFeedback = dockPlan?.let { plan -> s.planFeedback?.takeIf { it.matches(chat.key, chat.runtimeId, plan) } }
        val composerState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        val expansions = remember(s.draft?.expansions) { runCatching { pocketJson.parseToJsonElement(s.draft?.expansions ?: "[]").jsonArray.map { it.jsonPrimitive.content }.toSet() }.getOrDefault(emptySet()) }
        val blockedTurns = remember(s.approvals, s.timeline.activeTurn) { s.approvals.filter { it.runtimeId == chat.runtimeId && it.threadId == chat.key.nativeId && !ConversationPresentation.hookType(it.method) }.mapNotNull { it.params.string("turnId").takeIf(String::isNotBlank) ?: s.timeline.activeTurn }.toSet() }
        val agentLiveStatuses = remember(s.agentStatuses, chat.key) {
            s.agentStatuses.filterKeys { it.computerId == chat.key.computerId && it.configStoreId == chat.key.configStoreId }.mapKeys { it.key.nativeId }
        }
        val rows = rememberTranscriptRows(chat.key.encoded, s.timeline, expansions, blockedTurns,
            resolvedRequestIds = s.resolvedRequests[chat.runtimeId].orEmpty(), agentLiveStatuses = agentLiveStatuses)
        val changeCards = rememberTurnChangeCards(s.timeline, rows)
        val read = conversationReadPresentation(s.conversationLoadState, rows.isNotEmpty(), s.conversationCached)
        val live = remember(s.timeline) {
            s.timeline.activeTurn?.let { turn -> ConversationPresentation.liveStatus(s.timeline)?.let { turn to it } }
        }
        val reasoning = remember(s.timeline) { ConversationPresentation.liveReasoning(s.timeline) }
        val agents = remember(s.timeline, s.agentStatuses, chat.key) { s.currentAgents() }
        LaunchedEffect(chat.key, s.online, agents.map { it.threadId }) {
            if (s.online) repo.refreshAgentNames().join()
        }
        val agentNames = remember(s.agentChats, chat.key) { s.agentChats.filter { it.key.computerId == chat.key.computerId && it.key.configStoreId == chat.key.configStoreId }
            .associate { it.key.nativeId to it.title } }
        // Keep observation time above the lazy item: scrolling the footer away must not reset it.
        val timing = live?.first?.let { s.timeline.turns[it] }
        val nativeLive = s.online && (s.timeline.status.isBlank() || s.timeline.status in setOf("active", "inProgress", "running")) &&
            s.timeline.activeFlags.none { it in setOf("waitingOnApproval", "waitingOnUserInput") }
        val showSending = s.sendBusy && s.online && live == null && s.actionStatus["send:${chat.key.encoded}"]?.loading == true
        // A message is edited in its own bubble. Once its turn has been withdrawn there is no bubble left, so the editor takes the tail.
        val messageEdit = s.messageEdit?.takeIf { it.key == chat.key }?.let { edit ->
            MessageEditHost(edit, s.online, s.timeline.activeTurn != null, repo::changeMessageEdit, { repo.submitMessageEdit() }, repo::cancelMessageEdit,
                { repo.checkMessageEdit() }, { repo.prepareMessageEditAgain() }, { repo.stop() })
        }
        val editingRow = messageEdit?.let { host -> rows.firstOrNull { it.kind == TranscriptKind.User && host.owns(it.items.first()) }?.key }
        val editingKey = if (messageEdit == null) null else editingRow ?: "message-edit"
        val keys = remember(rows, live != null, showSending, editingKey) { listOf("history-control") + rows.map { it.key } + listOfNotNull("message-edit".takeIf { editingKey == it }) + listOfNotNull("live-status".takeIf { live != null || showSending }) + listOf("agent-working", "timeline-end") }
        var anchorKey by rememberSaveable { mutableStateOf(s.draft?.scrollItemKey) }
        var anchorOffset by rememberSaveable { mutableIntStateOf(s.draft?.scrollOffset ?: 0) }
        val list = rememberLazyListState(TranscriptAnchor(anchorKey).resolve(keys, s.draft?.scrollIndex ?: 0), anchorOffset)
        val minimapState = rememberConversationMinimapState(list, chat.key.encoded)
        val minimapShown = rememberConversationMinimapVisibility(chat.key.encoded, rows, list)
        var following by rememberSaveable { mutableStateOf(s.draft?.followEnd ?: true) }
        var transcriptContact by remember { mutableStateOf(false) }
        val transcriptGesture = remember(list) { TranscriptGestureState() }
        var jumpJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        // An edit made in a bubble holds the transcript still; when it ends the end is followed again, unless the reader moved away meanwhile.
        var followAfterEdit by remember { mutableStateOf(false) }
        val pauseFollowing = {
            jumpJob?.cancel()
            followAfterEdit = false
            if (following) {
                following = false
                // Persist intent immediately, including a drag consumed by a nested tool body.
                repo.scroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, false,
                    list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String)
            }
        }
        var restoredAnchor by remember { mutableStateOf(false) }
        val reportReading by rememberUpdatedState(onReadingHistory)
        val readingHistory = !following
        LaunchedEffect(readingHistory) { reportReading(readingHistory) }
        DisposableEffect(Unit) { onDispose { reportReading(false) } }
        val scope = rememberCoroutineScope()
        // The message being sent leaves the composer's field and lands as its bubble.
        val flight = rememberPSendFlight(chat.key.encoded)
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { repo.attach(it) }
        var attachmentSheet by rememberSaveable { mutableStateOf(false) }
        var permissionPanel by remember { mutableStateOf(false) }
        var permissionAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        var trigger by remember { mutableStateOf<ComposerTrigger?>(null) }
        LaunchedEffect(trigger?.kind, trigger?.query, s.online, chat.runtimeId) {
            if (trigger != null && s.online && s.supports("references/list")) {
                kotlinx.coroutines.delay(180)
                repo.loadReferences(trigger!!.query).join()
            }
        }
        val suggestionActions = buildSet {
            if (s.online && s.supports("model/list")) add(ComposerAction.Models)
            if (s.online && s.supports("collaborationMode/list") && s.modes.any { it.mode == "plan" }) add(ComposerAction.Plan)
            if (canMutate(s, "thread/goal/set")) add(ComposerAction.Goal)
            if (canMutate(s, "thread/name/set")) add(ComposerAction.Rename)
            if (canMutate(s, "thread/compact/start")) add(ComposerAction.Compact)
            if (s.online && s.supports("references/list")) add(ComposerAction.Files)
        }
        val queryMatches = s.advanced.referencesQuery == trigger?.query
        val suggestions = ComposerSuggestionsState(trigger = trigger, online = s.online,
            loading = trigger != null && s.supports("references/list") && (!queryMatches || s.advanced.referencesLoading),
            data = if (queryMatches) s.advanced.references else emptyList(),
            error = if (queryMatches) s.advanced.referencesError else null,
            unsupported = s.advanced.referencesUnsupported, actions = suggestionActions)
        var capture by rememberSaveable { mutableStateOf<String?>(null) }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = capture?.let(android.net.Uri::parse)
            if (success && uri != null) repo.attach(listOf(uri))
            else if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
            capture = null
        }
        val pending = s.approvals.filter { it.runtimeId == chat.runtimeId && it.threadId == chat.key.nativeId && !ConversationPresentation.hookType(it.method) }
        val background = Pocket.colors.bg
        val transcriptBackdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
        val density = LocalDensity.current
        var composerHeight by remember { mutableIntStateOf(0) }
        var headerHeight by remember { mutableIntStateOf(0) }
        var statusHeight by remember { mutableIntStateOf(0) }
        val bottomInset = with(density) { composerHeight.toDp() }
        val topInset = with(density) { headerHeight.toDp() }
        val statusInset = if (statusHeight > 0 && (chat.runtimeId.startsWith("history:") || read.failed || s.error != null)) topInset else 0.dp
        ObserveTranscriptDrag(list, transcriptGesture) {
            if (it) { following = true; repo.scroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, true,
                list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String) }
            else pauseFollowing()
        }
        val anchorReady = restoredAnchor && (following || anchorKey == null || anchorKey in keys)
        val latestKeys by rememberUpdatedState(keys)
        LaunchedEffect(list, anchorReady) {
            if (!anchorReady) return@LaunchedEffect
            // Layout can publish before the restoration effect cancels this observer.
            // Check readiness inside the snapshot, before adopting or persisting its first key.
            snapshotFlow {
                if (!restoredAnchor || transcriptContact || minimapState.isInteracting ||
                    list.layoutInfo.totalItemsCount != latestKeys.size ||
                    list.layoutInfo.visibleItemsInfo.any { it.key !in latestKeys } ||
                    (!following && anchorKey != null && anchorKey !in latestKeys)) null
                else Triple(list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }?.key as? String,
                    list.firstVisibleItemScrollOffset, following)
            }.onEach { position -> position?.let { (key, offset, _) -> if (key != null) { anchorKey = key; anchorOffset = offset } } }
                .debounce(200).collect { position -> position?.let { (key, offset, follow) ->
                if (key != null && restoredAnchor && !transcriptContact && !minimapState.isInteracting)
                    repo.scroll(list.firstVisibleItemIndex, offset, follow, key)
                }
            }
        }
        LaunchedEffect(list, keys, following) {
            val resolved = ConversationPresentation.anchorKey(rows, anchorKey)
            // LazyColumn preserves existing keys on prepend/deltas. Restore only on the first
            // nonempty load or when a structural split/merge actually removed our anchor.
            if (rows.isNotEmpty()) {
                if (!following && (!restoredAnchor || anchorKey !in keys)) {
                    val fallback = if (!restoredAnchor) s.draft?.scrollIndex ?: 0 else list.firstVisibleItemIndex
                    restoredAnchor = false
                    // A collapsed process header has a different height than its former child.
                    val restoredOffset = if (resolved == anchorKey) anchorOffset else 0
                    // Keep the saved anchor while contact/minimap/scrolling blocks restoration.
                    // Projection can precede LazyColumn measurement; do not seek its old indices.
                    snapshotFlow { !transcriptContact && !minimapState.isInteracting && !list.isScrollInProgress &&
                        list.layoutInfo.totalItemsCount == keys.size &&
                        list.layoutInfo.visibleItemsInfo.all { it.key in keys } }.first { it }
                    val restoredIndex = TranscriptAnchor(resolved).resolve(keys, fallback)
                    list.scrollToItem(restoredIndex, restoredOffset)
                    val restoredKey = keys[restoredIndex]
                    snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == restoredKey } &&
                        list.layoutInfo.visibleItemsInfo.all { it.key in keys } }.first { it }
                    anchorKey = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }?.key as? String ?: restoredKey
                    if (anchorKey !in keys) anchorKey = restoredKey
                    anchorOffset = list.firstVisibleItemScrollOffset
                    // Commit a structural relocation immediately. It must survive leaving
                    // the screen before the ordinary scrolling debounce expires.
                    repo.scroll(list.firstVisibleItemIndex, anchorOffset, false, anchorKey)
                } else if (!restoredAnchor) {
                    snapshotFlow { list.layoutInfo.totalItemsCount == keys.size }.first { it }
                }
                restoredAnchor = true
            }
        }
        FollowTranscriptEnd(list, { following }, { transcriptContact || minimapState.expanded || minimapState.isInteracting })
        // The bubble being edited stays clear of the keyboard: it is brought up as the keyboard arrives.
        LaunchedEffect(editingKey, composerHeight) {
            val target = editingKey ?: run { if (followAfterEdit) { followAfterEdit = false; following = true }; return@LaunchedEffect }
            if (target == "message-edit") { following = true; return@LaunchedEffect }
            val index = keys.indexOf(target)
            if (index < 0) return@LaunchedEffect
            val resume = following || followAfterEdit
            pauseFollowing()
            followAfterEdit = resume
            val shown = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == target }
            if (shown == null) { list.scrollToItem(index); return@LaunchedEffect }
            val over = shown.offset + shown.size - (list.layoutInfo.viewportEndOffset - list.layoutInfo.afterContentPadding)
            if (over > 0) list.animateScrollBy(over.toFloat(), PocketMotion.page<Float>().orSnap(android.animation.ValueAnimator.areAnimatorsEnabled()))
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val gutter = if (minimapShown) minOf(minimapState.reservedWidth, maxWidth * .18f) else 0.dp
                val transcriptInset = maxOf(16.dp, (maxWidth - gutter - 720.dp) / 2)
                val arrivals = rememberPocketArrivals(chat.key.encoded)
                // While one message is being edited the rest of the conversation steps back.
                val aside = androidx.compose.animation.core.animateFloatAsState(if (editingKey != null) .36f else 1f,
                    PocketMotion.state<Float>().orSnap(rememberPocketMotion()), label = "edit-aside")
                CompositionLocalProvider(LocalMessageEdit provides messageEdit) {
                // Only transcript is captured: the glass composer is a sibling, never its own source.
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(start = gutter, top = statusInset).testTag("chat-transcript")
                    .transcriptUserGesture(list, transcriptGesture, pauseFollowing, { transcriptContact = it; if (it) jumpJob?.cancel() })
                    .layerBackdrop(transcriptBackdrop), contentPadding = PaddingValues(start = transcriptInset, end = transcriptInset, top = topInset - statusInset + 12.dp, bottom = bottomInset + 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "history-control") {
                        ConversationReadStatus(read.copy(failed = false, refreshing = false), null, onRetry = {})
                        if (rows.isNotEmpty() && s.online && (s.supports("thread/turns/list") || s.conversationReadSource == "persisted") && !s.historyExhausted) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            PocketTextButton(onClick = { transcriptGesture.cancelResume(); pauseFollowing(); repo.older() }, enabled = !s.historyLoading) {
                                PText(stringResource(if (s.historyLoading) R.string.delivery_history_loading else R.string.earlier_messages), style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted)
                            }
                        }
                    }
                    itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, row -> row.kind }) { index, row ->
                        // Only the tail can be new: a sent message leaves from the composer's corner, a reply rises from the margin.
                        val tail = rows.size - index <= 2
                        val launch = remember(row.key) { flight.take(row.key, tail && row.kind == TranscriptKind.User) }
                        Column(Modifier.fillMaxWidth().testTag("transcript-row:${row.key}")
                            .pocketArrival(arrivals, row.key, tail && launch == null, origin = if (row.kind == TranscriptKind.User) TransformOrigin(1f, 1f) else TransformOrigin(0f, 1f))
                            .graphicsLayer { alpha = if (row.key == editingKey) 1f else aside.value }
                            .padding(top = when (row.kind) {
                            TranscriptKind.User -> 16.dp
                            TranscriptKind.Answer -> 10.dp
                            else -> 2.dp
                        })) {
                            TranscriptRowView(row, chat.key.encoded, row.kind == TranscriptKind.User || row.key in expansions, { repo.toggleExpansion(row.key) }, onDetail,
                                // No one-tap fork under answers: it silently moved the chat to a new desktop thread.
                                // Branch/side chat stay in the "…" menu, where the choice is explicit.
                                {}, false,
                                onPlan = null, activityExpansions = expansions, onActivityExpansion = repo::toggleExpansion, timeline = s.timeline,
                                onReopenPlan = proposedPlan?.takeIf { plan -> row.items.any { it.itemId == plan.itemId && it.turnId == plan.turnId } && PlanPresentation.unavailable(s, plan) == null }
                                    ?.let { plan -> { repo.reopenPlan(plan); following = true } },
                                onEdit = { repo.beginMessageEdit(it) }, online = s.online, sendLaunch = launch, sendFlight = flight)
                            changeCards[row.key].orEmpty().forEach { summary -> TurnChangesCard(summary, onDetail) }
                        }
                    }
                    if (editingKey == "message-edit" && messageEdit != null) item(key = "message-edit", contentType = "message-edit") {
                        MessageEditBubble(messageEdit, Modifier.padding(start = 48.dp, top = 16.dp))
                    }
                    if (live != null) item(key = "live-status", contentType = "live-status") {
                        LiveTurnStatus(live.first, live.second, reasoning = reasoning, timing = timing, online = nativeLive)
                    } else if (showSending) item(key = "live-status", contentType = "live-status") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.CenterEnd) {
                            PSendingBubble("正在发送", Modifier.testTag("live-sending-placeholder"))
                        }
                    }
                    item(key = "agent-working", contentType = "agent-working") { NativeAgentWorkingStrip(agents, onOpen = { repo.showAgentReader() }) }
                    item(key = "timeline-end") { Spacer(Modifier.height(1.dp)) }
                }
                }
                if (read.empty && live == null && !showSending) Box(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 420.dp).padding(30.dp)) {
                        EmptyState(stringResource(R.string.what_working), when (locationKind) {
                            ExecutionTargetKind.ThisPhone -> "在此手机上开始或继续对话。"
                            ExecutionTargetKind.LinuxSsh -> "在手机上继续 SSH 主机里的对话。"
                            ExecutionTargetKind.Computer, null -> stringResource(R.string.continue_chat)
                        })
                    }
                }
                if (minimapShown) ConversationMinimap(rows, keys, list,
                    Modifier.align(Alignment.CenterStart).width(gutter).padding(top = topInset + 8.dp, bottom = bottomInset + 8.dp),
                    backdrop = transcriptBackdrop, onSeek = { transcriptGesture.cancelResume(); pauseFollowing() }, state = minimapState,
                    scope = chat.key.encoded, navigationEpoch = s.historyLoading to s.nextTurns?.toString(), onExpandProcess = repo::expandProcess)
            CompositionLocalProvider(LocalPocketBackdrop provides transcriptBackdrop, LocalPSendFlight provides flight) {
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { headerHeight = it.height }) {
                CompositionLocalProvider(LocalPocketClearGlass provides true) {
                val syncLabel = when {
                    s.automaticConnectionLabel()!=null -> s.automaticConnectionLabel()
                    !s.online || read.failed -> null
                    read.refreshing -> stringResource(R.string.v04_transcript_refreshing)
                    s.busy && s.conversationLoadState != ConversationLoadState.Loading ->
                        if (s.historyLoading) "加载历史中" else "同步中"
                    else -> null
                }
                CompositionLocalProvider(LocalConversationSyncLabel provides syncLabel) { header() }
                AgentRosterChip(agents, s.online, s.busy, onAgent,
                    Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp, vertical = 2.dp),
                    names = agentNames, onShow = repo::showAgentReader)
                }
                // Each notice is a floating card; do not paint a full-width edge mask.
                Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 752.dp).fillMaxWidth()
                    .onSizeChanged { statusHeight = it.height }.testTag("conversation-status")) {
                Column {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    ConversationReadStatus(read.copy(skeleton = false), s.conversationLoadError, onRetry = { repo.reloadConversation() })
                }
                Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 752.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                s.error?.takeUnless(::automaticConnectionMessage)?.let { Notice(it, error = true, action = stringResource(R.string.dismiss), onAction = repo::clearError) }
                if (!s.online) Notice(stringResource(R.string.offline_history), action = stringResource(R.string.reconnect), onAction = repo::reconnect)
                // The visible-conversation observer restores control automatically. Connection
                // progress belongs in the existing header, never a second blocking banner.
                }
                }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("chat-bottom-dock").onSizeChanged { composerHeight = it.height }) {
            // One chrome row: "jump to latest" on the start edge, queue/attention on the end edge.
            // Separate floating layers overlapped each other above the composer.
            Row(Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp).widthIn(max = 720.dp).fillMaxWidth(),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.animation.AnimatedVisibility(!following && rows.isNotEmpty(),
                enter = fadeIn(tween(pocketMotionMillis(160))) + scaleIn(tween(pocketMotionMillis(160)), initialScale = .9f),
                exit = fadeOut(tween(pocketMotionMillis(110))) + scaleOut(tween(pocketMotionMillis(110)), targetScale = .9f)) {
                val jumpLabel = stringResource(R.string.jump_latest)
                PocketGlassSurface(onClick = { transcriptGesture.cancelResume(); jumpJob?.cancel(); jumpJob = scope.launch { following = true; if (keys.lastIndex - list.firstVisibleItemIndex > 30 || !android.animation.ValueAnimator.areAnimatorsEnabled()) list.scrollToItem(keys.lastIndex) else list.animateScrollToItem(keys.lastIndex) } },
                    modifier = Modifier.padding(bottom = 6.dp).semantics { contentDescription = jumpLabel }, backdrop = transcriptBackdrop, shape = CircleShape, dense = true) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        JumpToLatestGlyph(running = nativeLive && live != null)
                    }
                }
            }
            FlowRow(Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            }
            }
            QuestionDock(s, repo::respond, { repo.answerAsyncBatch(it) },
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 6.dp))
            if (dockPlan != null) PlanConfirmation(dockPlan, PlanPresentation.unavailable(s, dockPlan),
                onImplement = { repo.implementPlan(dockPlan) }, onModify = { repo.beginPlanFeedback(dockPlan) },
                feedback = planFeedback?.text, onFeedbackChange = { repo.editPlanFeedback(dockPlan, it) },
                onSubmitFeedback = { repo.submitPlanFeedback(dockPlan) }, onCancelFeedback = { repo.cancelPlanFeedback(dockPlan) },
                onDismiss = { repo.dismissPlan(dockPlan) },
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 6.dp))
            // Queued messages: inline rows directly above the input, never a separate screen.
            androidx.compose.animation.AnimatedVisibility(s.nativeQueue.isNotEmpty(), Modifier.align(Alignment.CenterHorizontally),
                enter = fadeIn(tween(pocketMotionMillis(160))) + androidx.compose.animation.expandVertically(tween(pocketMotionMillis(200)), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(pocketMotionMillis(110))) + androidx.compose.animation.shrinkVertically(tween(pocketMotionMillis(180)), shrinkTowards = Alignment.Bottom)) {
                NativeQueue(s, { id, text -> repo.queueEdit(id, text) }, { repo.queueDelete(it) }, { id, before -> repo.queueMove(id, before) },
                    onSteer = { repo.queueSteer(it) }, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).widthIn(max = 720.dp))
            }
            // Editing happens in the message's own bubble; the dock only keeps the keyboard from covering it.
            if (messageEdit != null) Spacer(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().height(8.dp).testTag("message-edit-keyboard-space"))
            if (s.uncertainSend) Row(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 720.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PText("正在确认上次发送，草稿已保留", style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
                PocketTextButton({ repo.checkCommands() }, enabled = s.online) { PText("查看状态") }
            }
            if (dockPlan == null && s.messageEdit == null) composerState.SaveableStateProvider("normal-composer") { PocketComposer(s.draft?.text.orEmpty(), s.modelSelectionLabel,
                listOfNotNull(s.effort?.let(context::effortLabel), s.mode?.takeUnless { it == "default" }?.let { context.modeLabel(it, it) }).filter(String::isNotBlank).joinToString(" · "),
                s.timeline.activeTurn != null, composerAvailability(s), repo::editDraft, onModels,
                { attachmentSheet = true }, onReferences,
                { if (s.timeline.activeTurn == null) flight.arm(rows.map { it.key }); repo.send() }, { repo.send(steer = true) }, { repo.stop() }, s.online && s.supports("references/list"),
                attachmentCount = s.attachments.size,
                stableLayout = s.attachments.isNotEmpty(),
                onPermissions = { permissionPanel = true; repo.refreshPermissions() }, permissionsExpanded = permissionPanel,
                permissionLabel = s.permissions.label,
                onPermissionAnchor = { permissionAnchor = it },
                suggestions = suggestions, onQuery = { trigger = it }, onReference = repo::insertReferenceAt,
                onSelectAction = { action ->
                    when (action) {
                        ComposerAction.Models -> onModels()
                        ComposerAction.Plan -> repo.chooseMode(if (s.mode == "plan") "default" else "plan")
                        ComposerAction.Files -> onReferences()
                        else -> onComposerAction(action)
                    }
                },
                attachments = {
                    if (s.attachments.isNotEmpty()) AttachmentStrip(s.attachments, repo::attachmentFile, repo::removeAttachment)
                    if (s.advanced.uploadProgress.values.any { it < 1f }) PocketProgressStatus("正在上传附件", Modifier.padding(vertical = 4.dp))
                }, readingHistory = readingHistory, transcriptScrolling = list.isScrollInProgress, focusRequest = composerFocusRequest,
                referenceSpans = remember(s.draft?.text, s.draft?.references) { restoreReferenceDraft(s.draft?.text.orEmpty(), s.draft?.references ?: "[]").spans },
                onTextEdit = { before, after, edit -> repo.editDraft(before, after, edit, s.selected?.key) }) }
            }
            }
            }
        }
        if (permissionPanel) PermissionPanel(s.permissions, s.online, s.timeline.activeTurn != null,
            onDismiss = { permissionPanel = false }, onRefresh = { repo.refreshPermissions() }, onSelect = { repo.choosePermission(it) }, anchor = permissionAnchor)
        if (attachmentSheet) AttachmentActionSheet(s.currentModel?.images == true, onDismiss = { attachmentSheet = false }, onCamera = {
            attachmentSheet = false
            runCatching { captureUri(context).also { capture = it.toString(); camera.launch(it) } }.onFailure { capture = null; repo.reportError(context.getString(R.string.v02_camera_unavailable)) }
        }, onImages = { attachmentSheet = false; picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }, onText = { attachmentSheet = false; picker.launch(arrayOf("text/plain", "text/markdown", "application/json")) })
    }
}

/** Read status belongs to the selected transcript; cached rows remain mounted throughout retry. */
@Composable
internal fun ConversationReadStatus(read: ConversationReadPresentation, error: String?, onRetry: () -> Unit) {
    var showSkeleton by remember { mutableStateOf(false) }
    LaunchedEffect(read.skeleton) {
        showSkeleton = false
        if (read.skeleton) { kotlinx.coroutines.delay(150); showSkeleton = true }
    }
    Column(Modifier.fillMaxWidth()) {
        // Shapes echo a real transcript and arrive one after another: a right-aligned request, then prose lines and a work-log line.
        if (read.skeleton && showSkeleton) PChatSkeleton(Modifier.testTag("conversation-skeleton").padding(vertical = 16.dp),
            label = stringResource(R.string.v04_transcript_loading))
        androidx.compose.animation.AnimatedVisibility(read.failed,
            enter = androidx.compose.animation.fadeIn(tween(pocketMotionMillis(180))),
            exit = androidx.compose.animation.fadeOut(tween(pocketMotionMillis(180)))) {
            ReadFailureCard(error?.takeIf(String::isNotBlank) ?: stringResource(R.string.v04_transcript_failed), onRetry)
        }
    }
}

/** Opaque floating card: the transcript never shows through, and one message is shown at a time. */
@Composable
private fun ReadFailureCard(message: String, onRetry: () -> Unit) {
    PSurface(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("conversation-read-failed"), shape = RoundedCornerShape(20.dp),
        // Hairline instead of an elevation shadow: the 6dp shadow drew a dark band along the card's left edge.
        color = Pocket.colors.surface,
        border = androidx.compose.foundation.BorderStroke(.5.dp, Pocket.colors.line.copy(alpha = .7f))) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(PocketOval).background(Pocket.colors.dangerSoft), contentAlignment = Alignment.Center) {
                PIcon(PocketIcons.ErrorOutline, null, Modifier.size(16.dp), tint = Pocket.colors.danger)
            }
            PText(message, Modifier.weight(1f).padding(horizontal = 10.dp), style = Pocket.type.bodySmall,
                color = Pocket.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            PocketTextButton(onClick = onRetry) { PText(stringResource(R.string.v04_transcript_retry)) }
        }
    }
}
