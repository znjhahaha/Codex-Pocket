@file:OptIn(kotlinx.coroutines.FlowPreview::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codexpocket.app.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.R
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

@Composable
internal fun ChatScreen(s: PocketState, repo: PocketRepository, onModels: () -> Unit, onDetail: (TimelineItem) -> Unit, onReferences: () -> Unit, onReadingHistory: (Boolean) -> Unit = {}, header: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val chat = s.selected ?: return
    key(chat.key.encoded) {
        val rows = remember(s.timeline.items, s.timeline.completedTurns) { ConversationPresentation.rows(s.timeline) }
        val read = conversationReadPresentation(s.conversationLoadState, rows.isNotEmpty())
        val live = s.timeline.activeTurn?.let { turn -> ConversationPresentation.liveStatus(s.timeline)?.let { turn to it } }
        val reasoning = remember(s.timeline) { ConversationPresentation.liveReasoning(s.timeline) }
        // Keep observation time above the lazy item: scrolling the footer away must not reset it.
        val observedAt = rememberSaveable(live?.first) { android.os.SystemClock.elapsedRealtime() }
        val keys = remember(rows, live != null) { listOf("history-control") + rows.map { it.key } + listOfNotNull("live-status".takeIf { live != null }) + "timeline-end" }
        var anchorKey by rememberSaveable { mutableStateOf(s.draft?.scrollItemKey) }
        var anchorOffset by rememberSaveable { mutableIntStateOf(s.draft?.scrollOffset ?: 0) }
        val list = rememberLazyListState(TranscriptAnchor(anchorKey).resolve(keys, s.draft?.scrollIndex ?: 0), anchorOffset)
        var following by rememberSaveable { mutableStateOf(s.draft?.followEnd ?: true) }
        var restoredAnchor by remember { mutableStateOf(false) }
        val reportReading by rememberUpdatedState(onReadingHistory)
        var readingHistory by remember { mutableStateOf(false) }
        LaunchedEffect(readingHistory) { reportReading(readingHistory) }
        DisposableEffect(Unit) { onDispose { reportReading(false) } }
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { repo.attach(it) }
        var attachmentSheet by rememberSaveable { mutableStateOf(false) }
        var capture by rememberSaveable { mutableStateOf<String?>(null) }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = capture?.let(android.net.Uri::parse)
            if (success && uri != null) repo.attach(listOf(uri))
            else if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
            capture = null
        }
        val expansions = remember(s.draft?.expansions) { runCatching { pocketJson.parseToJsonElement(s.draft?.expansions ?: "[]").jsonArray.map { it.jsonPrimitive.content }.toSet() }.getOrDefault(emptySet()) }
        val pending = s.approvals.filter { it.runtimeId == chat.runtimeId && it.threadId == chat.key.nativeId && !ConversationPresentation.hookType(it.method) }
        val background = MaterialTheme.colorScheme.background
        val transcriptBackdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
        val density = LocalDensity.current
        var composerHeight by remember { mutableIntStateOf(0) }
        var headerHeight by remember { mutableIntStateOf(0) }
        val bottomInset = with(density) { composerHeight.toDp() }
        val topInset = with(density) { headerHeight.toDp() }
        LaunchedEffect(list, density) {
            val enterDistance = with(density) { 240.dp.toPx() }
            val leaveDistance = with(density) { 32.dp.toPx() }
            snapshotFlow {
                val info = list.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                val remainingItems = if (last == null) 0 else info.totalItemsCount - 1 - last.index
                val remainingPixels = if (last == null) 0 else last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
                Triple(following, remainingItems, remainingPixels)
            }.distinctUntilChanged().collect { (follow, items, pixels) ->
                // Two thresholds keep changing chrome height from bouncing the UI near the end.
                if (follow || (items == 0 && pixels <= leaveDistance)) readingHistory = false
                else if (items > 1 || pixels >= enterDistance) readingHistory = true
            }
        }
        LaunchedEffect(list) {
            snapshotFlow { list.isScrollInProgress to list.canScrollForward }.distinctUntilChanged().collect { (scrolling, canForward) -> if (scrolling) following = !canForward }
        }
        LaunchedEffect(list, restoredAnchor) {
            if (!restoredAnchor) return@LaunchedEffect
            snapshotFlow { Triple(list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String, list.firstVisibleItemScrollOffset, following) }
                .onEach { (key, offset, _) -> if (key != null) { anchorKey = key; anchorOffset = offset } }
                .debounce(200).collect { (key, offset, follow) ->
                if (key != null) repo.scroll(list.firstVisibleItemIndex, offset, follow, key)
            }
        }
        LaunchedEffect(keys) {
            val resolved = ConversationPresentation.anchorKey(rows, anchorKey)
            // LazyColumn preserves existing keys on prepend/deltas. Restore only on the first
            // nonempty load or when a structural split/merge actually removed our anchor.
            if (rows.isNotEmpty()) {
                if (!following && !list.isScrollInProgress && (!restoredAnchor || anchorKey !in keys)) {
                    val fallback = if (!restoredAnchor) s.draft?.scrollIndex ?: 0 else list.firstVisibleItemIndex
                    list.scrollToItem(TranscriptAnchor(resolved).resolve(keys, fallback), anchorOffset)
                    anchorKey = resolved
                }
                restoredAnchor = true
            }
        }
        LaunchedEffect(rows.lastOrNull(), following) { if (following && rows.isNotEmpty() && !list.isScrollInProgress) list.scrollToItem(keys.lastIndex) }
        LaunchedEffect(list, following) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.let { Triple(it.key, it.offset, it.size) } }.distinctUntilChanged().collect {
                if (following && !list.isScrollInProgress && list.layoutInfo.totalItemsCount > 1) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
            }
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val transcriptInset = maxOf(22.dp, (maxWidth - 720.dp) / 2)
                // Only transcript is captured: the glass composer is a sibling, never its own source.
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().layerBackdrop(transcriptBackdrop), contentPadding = PaddingValues(start = transcriptInset, end = transcriptInset, top = topInset + 12.dp, bottom = bottomInset + 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "history-control") {
                        ConversationReadStatus(read.copy(failed = false, refreshing = false), null, onRetry = {})
                        if (rows.isNotEmpty() && s.online && s.supports("thread/turns/list") && !s.historyExhausted) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            PocketTextButton(onClick = { following = false; repo.older() }, enabled = !s.historyLoading) {
                                Text(stringResource(if (s.historyLoading) R.string.delivery_history_loading else R.string.earlier_messages), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(rows, key = { it.key }, contentType = { it.kind }) { row ->
                        Box(Modifier.fillMaxWidth().padding(top = when (row.kind) {
                            TranscriptKind.User -> 16.dp
                            TranscriptKind.Answer -> 10.dp
                            else -> 2.dp
                        })) {
                            TranscriptRowView(row, chat.key.encoded, row.key in expansions, { repo.toggleExpansion(row.key) }, onDetail,
                                // No one-tap fork under answers: it silently moved the chat to a new desktop thread.
                                // Branch/side chat stay in the "…" menu, where the choice is explicit.
                                {}, false,
                                onPlan = if (canMutate(s, "turn/start") && s.timeline.activeTurn == null) { edit ->
                                    val mode = if (edit) "plan" else "default"
                                    if (s.supports("collaborationMode/list") && s.modes.any { it.mode == mode }) repo.chooseMode(mode)
                                    repo.editDraft(listOf(s.draft?.text.orEmpty(), context.getString(if (edit) R.string.v02_plan_edit_draft else R.string.v02_plan_continue_draft)).filter(String::isNotBlank).joinToString("\n\n"))
                                } else null, activityExpansions = expansions, onActivityExpansion = repo::toggleExpansion)
                        }
                    }
                    if (live != null) item(key = "live-status", contentType = "live-status") {
                        LiveTurnStatus(live.first, live.second, reasoning = reasoning, observedAtMillis = observedAt)
                    }
                    item(key = "timeline-end") { Spacer(Modifier.height(1.dp)) }
                }
                if (read.empty) Box(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 420.dp).padding(30.dp)) {
                        EmptyState(stringResource(R.string.what_working), stringResource(R.string.continue_chat))
                    }
                }
            // Edge fades: text dissolves into the page before it reaches the header or composer,
            // instead of running underneath them. Drawn outside the backdrop layer, so glass is unaffected.
            val pageColor = MaterialTheme.colorScheme.background
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topInset + 12.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to pageColor, .55f to pageColor.copy(alpha = .86f), 1f to pageColor.copy(alpha = 0f))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomInset + 18.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to pageColor.copy(alpha = 0f), .3f to pageColor.copy(alpha = .9f), 1f to pageColor)))
            CompositionLocalProvider(LocalPocketBackdrop provides transcriptBackdrop) {
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { headerHeight = it.height }) {
                header()
                Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 752.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                    ConversationReadStatus(read.copy(skeleton = false), s.conversationLoadError, onRetry = { repo.reloadConversation() })
                }
                if (s.busy && s.conversationLoadState != ConversationLoadState.Loading) PocketProgressStatus(if (s.historyLoading) "正在加载历史" else "正在同步", Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp))
                Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 752.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                s.error?.let { Notice(it, error = true, action = stringResource(R.string.dismiss), onAction = repo::clearError) }
                if (!s.online) Notice(stringResource(R.string.offline_history), action = stringResource(R.string.reconnect), onAction = repo::reconnect)
                if (chat.conflict) Notice(stringResource(R.string.owner_conflict))
                if (s.uncertainSend) UnknownCommandNotice(s, repo)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { composerHeight = it.height }) {
            // One chrome row: "jump to latest" on the start edge, queue/attention on the end edge.
            // Separate floating layers overlapped each other above the composer.
            Row(Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp).widthIn(max = 720.dp).fillMaxWidth(),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.animation.AnimatedVisibility(readingHistory && rows.isNotEmpty(),
                enter = fadeIn(tween(pocketMotionMillis(160))) + scaleIn(tween(pocketMotionMillis(160)), initialScale = .9f),
                exit = fadeOut(tween(pocketMotionMillis(110))) + scaleOut(tween(pocketMotionMillis(110)), targetScale = .9f)) {
                val jumpLabel = stringResource(R.string.jump_latest)
                PocketGlassSurface(onClick = { scope.launch { following = true; if (keys.lastIndex - list.firstVisibleItemIndex > 30 || !android.animation.ValueAnimator.areAnimatorsEnabled()) list.scrollToItem(keys.lastIndex) else list.animateScrollToItem(keys.lastIndex) } },
                    modifier = Modifier.padding(bottom = 6.dp).semantics { contentDescription = jumpLabel }, backdrop = transcriptBackdrop, shape = CircleShape, dense = true) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(PocketIcons.ArrowDownward, null, Modifier.size(18.dp))
                    }
                }
            }
            FlowRow(Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pending.isNotEmpty() || s.advanced.asyncQuestions.isNotEmpty()) {
                var showAttention by rememberSaveable { mutableStateOf(false) }
                PocketGlassSurface(onClick = { showAttention = true }, modifier = Modifier.widthIn(max = 240.dp), shape = PocketOval) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(PocketIcons.PendingActions, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.secondary)
                        Text(stringResource(R.string.v02_attention) + " · " + (pending.size + s.advanced.asyncQuestions.size), Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(PocketIcons.ChevronRight, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (showAttention) PocketSheet(stringResource(R.string.v02_attention), { showAttention = false }, "此会话需要你确认的事项") {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    if (!s.approvalsFresh) item { Text("待重新同步 · 连接电脑后确认是否仍需审批", Modifier.padding(16.dp)) }
                    items(pending, key = { it.key }) { ApprovalCard(it, s.online && s.approvalsFresh && !s.busy && s.activeRuntime?.online == true, repo::respond) }
                    items(s.advanced.asyncQuestions, key = { it.string("id") }) { AsyncQuestionForm(it, s, repo::answerAsync) }
                }
                }
            }
            }
            }
            // Queued messages: inline rows directly above the input, never a separate screen.
            androidx.compose.animation.AnimatedVisibility(s.nativeQueue.isNotEmpty(), Modifier.align(Alignment.CenterHorizontally),
                enter = fadeIn(tween(pocketMotionMillis(160))) + androidx.compose.animation.expandVertically(tween(pocketMotionMillis(200)), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(pocketMotionMillis(110))) + androidx.compose.animation.shrinkVertically(tween(pocketMotionMillis(180)), shrinkTowards = Alignment.Bottom)) {
                NativeQueue(s, { id, text -> repo.queueEdit(id, text) }, { repo.queueDelete(it) }, { id, before -> repo.queueMove(id, before) },
                    onSteer = { repo.queueSteer(it) }, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).widthIn(max = 720.dp))
            }
            PocketComposer(s.draft?.text.orEmpty(), s.currentModel?.name?.takeIf(String::isNotBlank) ?: s.model ?: stringResource(R.string.native_default),
                listOfNotNull((s.effort ?: s.currentModel?.defaultEffort?.takeIf(String::isNotBlank))?.let(context::effortLabel), s.mode?.let { context.modeLabel(it, it) }).joinToString(" · "),
                s.timeline.activeTurn != null, composerAvailability(s), repo::editDraft, onModels,
                { attachmentSheet = true }, onReferences,
                { repo.send() }, { repo.send(steer = true) }, { repo.stop() }, s.online && s.supports("references/list"),
                attachments = {
                    if (s.attachments.isNotEmpty()) AttachmentStrip(s.attachments, repo::attachmentFile, repo::removeAttachment)
                    if (s.advanced.uploadProgress.values.any { it < 1f }) PocketProgressStatus("正在上传附件", Modifier.padding(vertical = 4.dp))
                }, readingHistory = readingHistory, transcriptScrolling = list.isScrollInProgress)
            }
            }
            }
        }
        if (attachmentSheet) AttachmentActionSheet(s.currentModel?.images == true, onDismiss = { attachmentSheet = false }, onCamera = {
            attachmentSheet = false
            runCatching { captureUri(context).also { capture = it.toString(); camera.launch(it) } }.onFailure { capture = null; repo.reportError(context.getString(R.string.v02_camera_unavailable)) }
        }, onImages = { attachmentSheet = false; picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }, onText = { attachmentSheet = false; picker.launch(arrayOf("text/plain", "text/markdown", "application/json")) })
    }
}

/** Read status belongs to the selected transcript; cached rows remain mounted throughout retry. */
@Composable
internal fun ConversationReadStatus(read: ConversationReadPresentation, error: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (read.skeleton) Column(Modifier.fillMaxWidth().testTag("conversation-skeleton").padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PocketProgressStatus(stringResource(R.string.v04_transcript_loading))
            // Shapes echo a real transcript: a right-aligned request, then prose lines and a work-log line.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { SkeletonBar(.46f, 34.dp) }
            listOf(.92f, .84f, .58f).forEach { fraction -> SkeletonBar(fraction, 12.dp) }
            SkeletonBar(.34f, 10.dp)
        }
        if (read.refreshing) Text(stringResource(R.string.v04_transcript_refreshing), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.animation.AnimatedVisibility(read.failed,
            enter = androidx.compose.animation.fadeIn(tween(pocketMotionMillis(160))) + androidx.compose.animation.slideInVertically(spring(dampingRatio = .82f, stiffness = 520f)) { -it / 3 },
            exit = androidx.compose.animation.fadeOut(tween(pocketMotionMillis(120)))) {
            ReadFailureCard(error?.takeIf(String::isNotBlank) ?: stringResource(R.string.v04_transcript_failed), onRetry)
        }
    }
}

/** Opaque floating card: the transcript never shows through, and one message is shown at a time. */
@Composable
private fun ReadFailureCard(message: String, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("conversation-read-failed"), shape = RoundedCornerShape(20.dp),
        // Hairline instead of an elevation shadow: the 6dp shadow drew a dark band along the card's left edge.
        color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 0.dp, tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(PocketOval).background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.Center) {
                Icon(PocketIcons.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            }
            Text(message, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            PocketTextButton(onClick = onRetry) { Text(stringResource(R.string.v04_transcript_retry)) }
        }
    }
}

@Composable
private fun SkeletonBar(fraction: Float, height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxWidth(fraction).height(height).clip(RoundedCornerShape(height / 2))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).pocketShimmer())
}
