package dev.codexpocket.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Names used by pages written at different times, folded onto the pages that exist. */
internal fun assistantDestination(destination: String) = when (destination) {
    "Schedules" -> "Plans"; "Results" -> "Artifacts"; "Pending" -> "Tasks"; "Chat" -> "Main"; else -> destination
}
internal fun assistantPageTitle(page: String) = when {
    page == "Main" -> "口袋助理"; page == "Tasks" -> "任务"; page == "Artifacts" -> "成果"; page == "Plans" -> "计划"
    page == "Memory" -> "记忆"; page == "Settings" -> "助理设置"; page.startsWith("Task:") -> "任务详情"; page.startsWith("Artifact:") -> "成果详情"
    page.startsWith("Group:") -> "任务组"; page.startsWith("Result:") -> "完整结果"; else -> "计划"
}

/**
 * One stage: the conversation with the assistant, under a glass bar and above a glass dock. Tasks, results, plans,
 * memory and settings are pages pushed over it; Back returns one level at a time and the stage comes back as it was
 * left. Native execution and request identities continue through the existing repository and service.
 */
@Composable internal fun AssistantWorkspace(state: AssistantUiState, models: List<NativeModel>, runtimes: List<AssistantRuntimeChoice>,
    workspaces: List<AssistantWorkspaceChoice>, onAction: (AssistantAction) -> Unit, onOpenThread: (String, String) -> Unit, onBack: () -> Unit,
    modifier: Modifier, nowMs: Long, connected: Boolean, onProjectSelected: (AssistantWorkspaceChoice?) -> Unit) {
    ProvideMarkdownRendering {
        val snapshot = state.snapshot
        val owner = state.computerId
        var page by rememberSaveable(owner) { mutableStateOf("Main") }
        var stack by rememberSaveable(owner) { mutableStateOf(emptyList<String>()) }
        var error by remember { mutableStateOf<String?>(null) }
        val holder = rememberSaveableStateHolder()
        val canAct = connected && snapshot?.enabled == true && snapshot.fresh != false && state.readError == null && !state.loading
        fun act(make: () -> AssistantAction) { runCatching(make).onSuccess { error = null; onAction(it) }.onFailure { error = it.message } }
        fun navigate(destination: String) {
            val next = assistantDestination(destination)
            if (next != page) { stack = stack + page; page = next }
        }
        fun back() {
            if (stack.isNotEmpty()) { page = stack.last(); stack = stack.dropLast(1) }
            else if (page != "Main") page = "Main" else onBack()
        }
        // How deep each page sits, kept for a page that has just been left so the stage knows which way it goes.
        val depths = remember(owner) { HashMap<String, Int>() }
        depths[page] = if (page == "Main") 0 else stack.size.coerceAtLeast(1)
        val previous = stack.lastOrNull() ?: "Main".takeIf { page != "Main" }
        // A command whose outcome is unsettled is named for as long as it is, whatever a later refresh reports.
        val issue = error ?: state.readError ?: state.unconfirmedFeedback?.takeUnless { snapshot?.protocolVersion == 2 } ?: state.error?.takeUnless {
            (it == state.unconfirmedFeedback && snapshot?.protocolVersion == 2) || (page == "Main" && snapshot?.prepared != true && it == snapshot?.preparationIssue)
        }
        val notice: @Composable () -> Unit = {
            AssistantStatusRow(issue, issue != null && issue == state.unconfirmedFeedback, state.loading) { act { AssistantActions.read(snapshot?.after ?: 0) } }
        }
        // The overlay layer hands focus back to the page when a menu, sheet or dialog closes. Taking it here keeps it
        // from landing on the first text field, which would raise the keyboard on a page that was only opened.
        Box(modifier.fillMaxSize().background(Pocket.colors.bg).testTag("assistant-workspace").focusTarget()) {
            // On the first page there is nothing here to go back to: whoever opened the assistant previews the way out.
            PStage(page, depth = { depths[it] ?: 1 }, back = previous, onBack = ::back, source = { it.takeIf { name -> ':' in name } }) { page ->
                holder.SaveableStateProvider("$owner:$page") {
                    if (page == "Main") AssistantStage(state, workspaces, canAct, connected, ::act, ::navigate, onOpenThread, ::back, onProjectSelected, notice)
                    else AssistantSubpage(assistantPageTitle(page), ::back, notice) {
                        when {
                            page == "Tasks" -> AssistantTasksPage(state, ::navigate)
                            page == "Artifacts" -> AssistantResultsPage(state, ::navigate, onOpenThread)
                            page == "Plans" -> AssistantSchedules(state, workspaces, canAct, nowMs, onProjectSelected, ::navigate, ::act)
                            page == "Settings" -> AssistantSettingsPage(state, models, runtimes, canAct, ::act)
                            page == "Memory" -> AssistantMemoryPage(state, canAct, ::act)
                            page.startsWith("Task:") -> AssistantWorkDetail(page.substringAfter(':'), state, canAct, ::act, ::navigate, onOpenThread)
                            page.startsWith("ScheduleFrom:") -> {
                                val task = snapshot?.tasks?.firstOrNull { it.id == page.substringAfter(':') }
                                AssistantScheduleForm(snapshot?.runtimeId.orEmpty(), state, workspaces, nowMs, canAct, ::back, onProjectSelected, ::act, initialPrompt = task?.prompt.orEmpty())
                            }
                            else -> AssistantDetailPage(page, state, canAct, ::act, onOpenThread, workspaces, ::navigate)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where glass cannot refract what is under it (older systems, power saving, high contrast), the chrome stands on a
 * veil of the page colour, so the conversation fades out before it instead of showing between its parts.
 */
@Composable
private fun Modifier.assistantChromeVeil(top: Boolean): Modifier {
    val preferences = rememberPocketGlassPreferences()
    val optics = LocalPocketGlass.current && !preferences.highContrast && !preferences.batterySaver && LocalView.current.isHardwareAccelerated &&
        android.os.Build.VERSION.SDK_INT >= 31
    if (optics) return this
    val page = Pocket.colors.bg
    return background(if (top) Brush.verticalGradient(0f to page, .86f to page, 1f to page.copy(alpha = 0f))
        else Brush.verticalGradient(0f to page.copy(alpha = 0f), .22f to page, 1f to page))
}

/** The bar every assistant page shares: back, the title in a glass capsule with an optional status mark, then actions. */
@Composable
private fun AssistantTopBar(title: String, onBack: () -> Unit, status: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp).testTag("assistant-top-bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PGlassIconButton(PocketIcons.ArrowBack, "返回", onBack, Modifier.testTag("assistant-back"))
        PocketGlassSurface(Modifier.weight(1f).testTag("assistant-header-glass"), PocketRadii.capsule) {
            Row(Modifier.fillMaxWidth().heightIn(min = PocketSpace.touch).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProvidePInk(Pocket.colors.ink) {
                    PocketDestinationTitle(title, Modifier.weight(1f, fill = false).semantics { heading() })
                    status?.invoke()
                }
            }
        }
        actions()
    }
}

/** A failed read or an unsettled command, with the one way out; and the thin bar while a read is in flight. */
@Composable
private fun AssistantStatusRow(issue: String?, unconfirmed: Boolean, loading: Boolean, onRetry: () -> Unit) {
    val c = Pocket.colors
    Column(Modifier.fillMaxWidth()) {
        if (issue != null) PSurface(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp).fillMaxWidth().testTag("assistant-workspace-error"),
            PocketRadii.control, if (unconfirmed) c.warningSoft else c.dangerSoft, if (unconfirmed) c.ink else c.onDangerSoft) {
            // At large text the sentence takes the full width and the action moves under it.
            if (LocalDensity.current.fontScale > 1.3f) Column(Modifier.padding(start = 14.dp, end = 2.dp), horizontalAlignment = Alignment.End) {
                PText(issue, Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp).testTag("assistant-workspace-issue"), style = Pocket.type.bodySmall)
                PTextAction("重试读取", onRetry, Modifier.testTag("assistant-read-retry"))
            } else Row(Modifier.padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                PText(issue, Modifier.weight(1f).padding(vertical = 8.dp).testTag("assistant-workspace-issue"), style = Pocket.type.bodySmall)
                PTextAction("重试读取", onRetry, Modifier.testTag("assistant-read-retry"))
            }
        }
        if (loading) PLinearProgress(null, Modifier.padding(horizontal = 28.dp).padding(bottom = 4.dp).testTag("assistant-loading"))
    }
}

@Composable
private fun AssistantSubpage(title: String, onBack: () -> Unit, notice: @Composable () -> Unit, content: @Composable () -> Unit) {
    PFrame(header = { Column(Modifier.assistantChromeVeil(true)) { AssistantTopBar(title, onBack); notice() } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) { content() }
    }
}

/** Which rows arrived after the stage was first shown. History never animates; a row rises once, when it is new at the tail. */
private class AssistantArrivals {
    private val known = HashSet<String>()
    private val fresh = HashSet<String>()
    private var primed = false
    fun update(keys: List<String>) {
        if (!primed) { known += keys; primed = true; return }
        val lastKnown = keys.indexOfLast { it in known }
        keys.forEachIndexed { index, key -> if (known.add(key) && index > lastKnown) fresh += key }
    }
    fun take(key: String) = fresh.remove(key)
}

@Composable
private fun Modifier.assistantArrival(fresh: Boolean): Modifier {
    // Rows that were already there get no animation state and no clock.
    if (!fresh || !rememberPocketMotion()) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, PocketMotion.enter()) }
    val rise = with(LocalDensity.current) { PocketMotion.EnterOffsetDp.dp.toPx() }
    return graphicsLayer { alpha = progress.value; translationY = rise * (1f - progress.value) }
}

/**
 * The conversation in the order it is read: the timeline, with each task's card kept under the latest message about
 * it, a waiting request shown inside its task's card, and a finished execution's result inside that card too.
 */
internal fun assistantStageRows(timeline: List<AssistantTimeline>, snapshot: AssistantSnapshot): List<AssistantTimeline> {
    val grouped = timeline.filterIsInstance<AssistantTimeline.Group>().flatMap { it.tasks }.map { it.id }.toSet()
    val tasks = snapshot.tasks.map { it.id }.toSet()
    val cards = timeline.filterIsInstance<AssistantTimeline.Task>().associateBy { it.task.id }
    val anchors = timeline.filterIsInstance<AssistantTimeline.Message>().mapNotNull { row -> row.message.taskId?.takeIf { it in cards }?.let { it to row.key } }.toMap()
    val pending = snapshot.pendingOperations().map { it.id }.toSet()
    return buildList {
        timeline.forEach { row ->
            when (row) {
                is AssistantTimeline.Task -> if (row.task.id !in anchors) add(row)
                is AssistantTimeline.Message -> { add(row); row.message.taskId?.takeIf { anchors[it] == row.key }?.let { add(cards.getValue(it)) } }
                is AssistantTimeline.Question -> if (row.operation.id !in pending || row.operation.taskId !in tasks || row.operation.taskId in grouped) add(row)
                is AssistantTimeline.Result -> if (row.operation.taskId !in tasks) add(row)
                // Requests to recover are listed after the conversation, where they are acted on.
                is AssistantTimeline.Recovery, is AssistantTimeline.HandoffRecovery -> Unit
                else -> add(row)
            }
        }
    }.distinctBy { it.key }
}

@Composable
private fun AssistantReadiness(state: AssistantUiState, connected: Boolean) {
    val c = Pocket.colors
    val snapshot = state.snapshot
    val label = if (!connected) "电脑离线" else assistantStatus(state)
    val color = when {
        !connected || snapshot?.paused == true -> c.inkFaint
        snapshot?.prepared == true && state.error == null && state.readError == null -> c.positive
        else -> c.warning
    }
    Box(Modifier.size(8.dp).background(color, CircleShape).testTag("assistant-readiness").semantics { contentDescription = label })
}

@Composable
private fun AssistantStage(state: AssistantUiState, workspaces: List<AssistantWorkspaceChoice>, canAct: Boolean, connected: Boolean,
    act: (() -> AssistantAction) -> Unit, navigate: (String) -> Unit, open: (String, String) -> Unit, onBack: () -> Unit,
    onProjectSelected: (AssistantWorkspaceChoice?) -> Unit, notice: @Composable () -> Unit) {
    val snapshot = state.snapshot ?: AssistantSnapshot()
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val composer = rememberAssistantComposer(state)
    val projectCallback = rememberUpdatedState(onProjectSelected)
    DisposableEffect(Unit) { onDispose { projectCallback.value(null) } }
    val attention = remember(snapshot) { snapshot.attention() }
    val links = LocalThreadLinkBinding.current
    val anchor = rememberPAnchor()
    var menu by remember { mutableStateOf(false) }
    var attentionOpen by rememberSaveable { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<AssistantJobConfirm?>(null) }

    val timeline = remember(state.messages, snapshot) { assistantTimeline(state) }
    val rows = remember(timeline, snapshot) { assistantStageRows(timeline, snapshot) }
    val recoveries = snapshot.recoveries.distinct()
    val handoffs = snapshot.operations.filter { it.handoffRecovery != null }.distinctBy { it.id }
    val lastReply = (rows.lastOrNull { it is AssistantTimeline.Message && it.message.role == "Assistant" } as? AssistantTimeline.Message)?.message
    // The stored message replaces the live one: a reply is never on screen twice.
    val streamOp = state.streamingCoordinatorOpId?.takeIf { state.streamingCoordinatorText.isNotBlank() && lastReply?.operationId != it }
    val working = streamOp == null && snapshot.operations.any { it.phase == AssistantOperationPhase.Running && !it.suspended && it.role == "Coordinator" }
    val arrivals = remember(composer.key) { AssistantArrivals() }
    arrivals.update(rows.map { it.key } + listOfNotNull(streamOp?.let { "stream" }, "working".takeIf { working }))

    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val tail = "${state.messages.lastOrNull()?.id}|${rows.lastOrNull()?.key}|${streamOp ?: if (working) "working" else ""}"
    var previousTail by rememberSaveable(composer.key) { mutableStateOf<String?>(null) }
    var moreBelow by rememberSaveable(composer.key) { mutableStateOf(false) }
    // Whether the reader is at the end. Their own scrolling changes it; content growing below them does not.
    var follow by rememberSaveable(composer.key) { mutableStateOf(true) }
    suspend fun toEnd(animated: Boolean) {
        val last = snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 } - 1
        if (animated && motion) list.animateScrollToItem(last) else list.scrollToItem(last)
    }
    LaunchedEffect(list, composer.key) {
        var place: Pair<Any?, Int>? = null
        snapshotFlow {
            Triple(list.layoutInfo.visibleItemsInfo.firstOrNull()?.key to list.firstVisibleItemScrollOffset, list.layoutInfo.totalItemsCount, list.canScrollForward)
        }.collect { (now, count, forward) ->
            if (count == 0) return@collect
            val moved = place != null && place != now
            place = now
            if (!forward) { follow = true; moreBelow = false }
            else if (moved) follow = false
            // A reply growing, Markdown measured late or the keyboard opening, under a reader who is at the end.
            else if (follow && previousTail != null) list.scrollToItem(count - 1)
        }
    }
    LaunchedEffect(tail) {
        if (tail != previousTail) {
            if (previousTail == null || follow) { toEnd(false); moreBelow = false } else moreBelow = true
            previousTail = tail
        }
    }

    Box(Modifier.fillMaxSize()) {
        PFrame(header = {
            Column(Modifier.assistantChromeVeil(true)) {
                AssistantTopBar("口袋助理", onBack, status = { AssistantReadiness(state, connected) }) {
                    PGlassIconButton(AssistantIcons.Schedule, "计划", { navigate("Plans") }, Modifier.testTag("assistant-schedules"),
                        glyph = { PPulseGlyph(MotionGlyphs.Clock, snapshot.schedules.size, Modifier.size(20.dp)) })
                    PGlassIconButton(PocketIcons.MoreHoriz, "更多", { menu = true }, Modifier.testTag("assistant-title-menu").pocketAnchor(anchor))
                }
                notice()
                AssistantAttentionCapsule(attention, { attentionOpen = true })
            }
        }, dock = {
            AssistantInputDock(composer, state, workspaces, canAct, connected, act, { tools = !tools }, tools, Modifier.assistantChromeVeil(false))
        }) { padding ->
            AssistantConversation(state, snapshot, rows, recoveries, handoffs, streamOp, working, composer.key, arrivals, list, padding, canAct, connected,
                act, navigate, open) { confirm = it }
            Box(Modifier.fillMaxSize().padding(padding).testTag("assistant-chat-viewport"))
            if (moreBelow) Box(Modifier.fillMaxSize().padding(padding).padding(bottom = 4.dp), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.heightIn(min = PocketSpace.touch).testTag("assistant-new-progress")
                    .pocketPressable(role = Role.Button, pressedScale = .96f, highlight = false) { scope.launch { toEnd(true); moreBelow = false } },
                    contentAlignment = Alignment.Center) {
                    PSurface(Modifier.shadow(6.dp, PocketRadii.capsule), PocketRadii.capsule, c.surface, border = BorderStroke(1.dp, c.line)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PLoopGlyph(MotionGlyphs.ArrowDownNudge, true, Modifier.size(15.dp), c.accent, millis = 1800)
                            PText("查看新进展", style = Pocket.type.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (menu) PMenu("更多", { menu = false }, anchor, widthDp = 216f) {
            PRow("全部任务", { menu = false; navigate("Tasks") }, Modifier.testTag("assistant-menu:Tasks"), AssistantIcons.Tasks)
            PRow("成果", { menu = false; navigate("Artifacts") }, Modifier.testTag("assistant-menu:Artifacts"), PocketIcons.Inventory2)
            PRow("记忆", { menu = false; navigate("Memory") }, Modifier.testTag("assistant-menu:Memory"), AssistantIcons.Memory)
            PRow("助理设置", { menu = false; navigate("Settings") }, Modifier.testTag("assistant-menu:Settings"), AssistantIcons.Settings)
            PDivider(Modifier.padding(vertical = 4.dp, horizontal = 10.dp))
            PRow("刷新", { menu = false; act { AssistantActions.read(snapshot.after) } }, Modifier.testTag("assistant-menu:Refresh"), PocketIcons.Refresh,
                enabled = !state.busy("assistant/read"))
        }
        if (attentionOpen) AssistantAttentionSheet(attention, state, canAct, act, navigate, open, { confirm = it }, links) { attentionOpen = false }
        if (tools) AssistantToolsSheet(composer, workspaces, canAct, onProjectSelected) { tools = false }
        confirm?.let { AssistantJobConfirmDialog(it, state, canAct, act) { confirm = null } }
    }
}

@Composable
private fun AssistantConversation(state: AssistantUiState, snapshot: AssistantSnapshot, rows: List<AssistantTimeline>, recoveries: List<AssistantRecoveryRequest>,
    handoffs: List<AssistantOperation>, streamOp: String?, working: Boolean, scope: String, arrivals: AssistantArrivals,
    list: androidx.compose.foundation.lazy.LazyListState, padding: PaddingValues, canAct: Boolean, connected: Boolean, act: (() -> AssistantAction) -> Unit,
    navigate: (String) -> Unit, open: (String, String) -> Unit, confirm: (AssistantJobConfirm) -> Unit) {
    val c = Pocket.colors
    val runtime = snapshot.runtimeId.orEmpty()
    val unknown = snapshot.domain?.attention.orEmpty().filter { it.state == "open" && it.kind == "verification" }
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-chat-list"), state = list, verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 10.dp)) {
        if (state.nextBefore != null) item("older") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PTextAction("查看更早消息", { act { AssistantActions.read(0, state.nextBefore) } }, Modifier.testTag("assistant-older"),
                    enabled = !state.busy("assistant/read"), tint = c.inkMuted)
            }
        }
        if (rows.none { it is AssistantTimeline.Message } && snapshot.prepared) item("greeting") {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AssistantAvatar(size = 44.dp)
                PText("想推进哪件事？", Modifier.semantics { heading() }, style = Pocket.type.headline)
                PText("说清目标，助理会安排任务、汇报进展，把需要你决定的事带回来。", style = Pocket.type.body, color = c.inkMuted)
            }
        }
        itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, row -> row.javaClass.simpleName }) { index, row ->
            val fresh = remember(row.key) { arrivals.take(row.key) }
            Box(Modifier.fillMaxWidth().assistantArrival(fresh)) {
                when (row) {
                    is AssistantTimeline.Message -> {
                        val before = (rows.getOrNull(index - 1) as? AssistantTimeline.Message)?.message
                        AssistantMessageRow(row.message, scope, before?.role != row.message.role || before?.authorName != row.message.authorName)
                    }
                    is AssistantTimeline.Task -> AssistantJobInlineCard(row.task, state, canAct, act, navigate, open, confirm)
                    is AssistantTimeline.Group -> AssistantGroupInlineCard(row.group, row.tasks, state, canAct, act, navigate, open, confirm)
                    is AssistantTimeline.Question -> AssistantHumanQuestion(row.operation, runtime, state, canAct, act)
                    is AssistantTimeline.EndedQuestion -> AssistantCard { AssistantEndedQuestion(row.input) }
                    is AssistantTimeline.Delivery -> PText("${row.delivery.label} · ${row.delivery.message}", Modifier.fillMaxWidth().testTag("assistant-delivery:${row.delivery.id}"),
                        style = Pocket.type.labelSmall, color = c.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    is AssistantTimeline.History -> {
                        val op = snapshot.operations.firstOrNull { it.id == row.history.operationId }
                        val member = snapshot.operationMemberId(row.history.operationId, op?.taskId)
                        val source = op?.threadId ?: snapshot.events.lastOrNull { it.operationId == row.history.operationId && it.threadId != null }?.threadId
                        NativeQuestionHistory.from(row.history.item)?.let { history ->
                            HistoricalQuestionRow(history, "assistant:${row.history.id}", snapshot.members.firstOrNull { it.id == member }?.name,
                                if (source != null && runtime.isNotBlank()) ({ open(runtime, source) }) else null)
                        }
                    }
                    is AssistantTimeline.Result -> AssistantResultRow(row.operation, snapshot, navigate)
                    is AssistantTimeline.Recovery, is AssistantTimeline.HandoffRecovery -> Unit
                }
            }
        }
        items(recoveries, key = { "recovery:${it.operationId}:${it.requestId}" }) { request -> AssistantCard { AssistantRecoveryCard(request, runtime, state, canAct, act) } }
        items(handoffs, key = { "handoff:${it.id}" }) { op -> AssistantCard { AssistantHandoffRecoveryCard(op, runtime, state, canAct, act) } }
        // What the assistant's own state asks for sits after the conversation, next to the composer, not above a long history.
        if (!snapshot.prepared && !snapshot.dispatchInFlight) item("prepare") {
            AssistantCard(Modifier.testTag("assistant-prepare-card")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AssistantAvatar(size = 28.dp)
                    PText(if (connected) "准备好后，把事情交给助理" else "电脑离线，草稿会保留", Modifier.weight(1f), style = Pocket.type.titleSmall)
                }
                snapshot.preparationIssue?.let { PText(it, style = Pocket.type.bodySmall, color = c.inkMuted) }
                if (snapshot.startupStages.isNotEmpty()) AssistantStartupStatus(snapshot)
                PButton("准备助理", { act { AssistantActions.prepare() } }, Modifier.fillMaxWidth().testTag("assistant-prepare"),
                    enabled = connected && "assistant/prepare" !in state.pendingActions)
            }
        }
        if (unknown.isNotEmpty()) item("unknown") {
            AssistantCard(Modifier.testTag("assistant-unknown-notice")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PIcon(PocketIcons.Info, null, Modifier.size(18.dp), c.warning)
                    PText("${unknown.size} 次执行待核对", style = Pocket.type.titleSmall)
                }
                PText("保留原执行与回执，你可以继续交办其他目标。", style = Pocket.type.bodySmall, color = c.inkMuted)
                AssistantCardAction("查看待核对任务", { navigate("Tasks") }, Modifier.testTag("assistant-unknown-open"))
            }
        }
        if (streamOp != null) item("stream") {
            val fresh = remember { arrivals.take("stream") }
            Column(Modifier.fillMaxWidth().assistantArrival(fresh).testTag("assistant-streaming"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistantAuthor("助理 · 正在回复", sweep = true)
                MarkdownText(state.streamingCoordinatorText, "assistant:$scope:stream:$streamOp")
            }
        } else if (working) item("working") {
            val fresh = remember { arrivals.take("working") }
            Box(Modifier.fillMaxWidth().assistantArrival(fresh).testTag("assistant-working")) { AssistantAuthor("正在处理…", sweep = true) }
        }
        item("tail") { Spacer(Modifier.height(1.dp)) }
    }
}

/** Who is speaking, once per run of messages. The sweep marks a reply that is still being written. */
@Composable
private fun AssistantAuthor(name: String, sweep: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistantAvatar(size = 20.dp)
        PText(name, Modifier.reasoningSweep(sweep), style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AssistantMessageRow(message: AssistantChatMessage, scope: String, showAuthor: Boolean) {
    val c = Pocket.colors
    if (message.role == "User") Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showAuthor) PText("你", Modifier.padding(end = 6.dp), style = Pocket.type.labelSmall, color = c.inkFaint)
        PSurface(Modifier.padding(start = 44.dp).testTag("assistant-message:${message.id}"), PocketRadii.card, c.fillStrong) {
            SelectionContainer { ReferenceText(message.text, emptyList(), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = c.ink) }
        }
    } else Column(Modifier.fillMaxWidth().testTag("assistant-message:${message.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showAuthor) AssistantAuthor(message.authorName?.takeIf(String::isNotBlank) ?: "助理")
        MarkdownText(message.text, "assistant:$scope:${message.id}")
    }
}

/** An execution's result that has no task card of its own to sit in. */
@Composable
private fun AssistantResultRow(operation: AssistantOperation, snapshot: AssistantSnapshot, navigate: (String) -> Unit) {
    val c = Pocket.colors
    val failed = operation.phase == AssistantOperationPhase.Failed
    AssistantCard(Modifier.pocketMorphSource("Result:${operation.id}", 20.dp).testTag("assistant-result:${operation.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistantBadge(if (failed) "遇到问题" else "已完成", failed, if (failed) PTone.Danger else PTone.Positive)
            snapshot.operationMemberId(operation.id, operation.taskId)?.let { id -> snapshot.members.firstOrNull { it.id == id } }?.let { member ->
                PText(member.name, style = Pocket.type.labelSmall, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        PText(remember(operation.result) { markdownPreview(operation.result.orEmpty()) }, style = Pocket.type.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
        AssistantCardAction("查看完整结果", { navigate("Result:${operation.id}") }, Modifier.testTag("assistant-result-detail:${operation.id}"))
    }
}
