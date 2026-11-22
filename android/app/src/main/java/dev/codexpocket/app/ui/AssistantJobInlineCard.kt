package dev.codexpocket.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*

/** What the user is asked to confirm before a job action is sent. The dialog lives at the stage's root. */
internal enum class AssistantJobConfirmKind { Merge, Discard, Stop }
internal data class AssistantJobConfirm(val kind: AssistantJobConfirmKind, val taskId: String)

/** One task as the stage shows it: snapshot facts, plus the live line while the snapshot still shows the job moving. */
internal data class AssistantJobView(val task: AssistantTask, val job: AssistantJob?, val live: AssistantLiveJobStage?, val label: String,
    val stage: String, val tone: PTone, val running: Boolean, val unknown: Boolean, val threadId: String?, val pending: List<AssistantOperation>,
    val canStop: Boolean, val unread: Boolean) {
    val title get() = job?.title?.takeIf(String::isNotBlank) ?: task.prompt
    /** The last folder of the job's directory; the full path stays on the task page. */
    val project get() = job?.directory?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf(String::isNotBlank)
    val subagents get() = live?.activeSubagents ?: 0
}

/** Frames can outlive what they describe, so a live line is kept only for a task the snapshot still shows as moving. */
internal fun AssistantUiState.liveJobStage(task: AssistantTask): AssistantLiveJobStage? {
    val snapshot = snapshot ?: return null
    if (task.status !in setOf(AssistantTaskStatus.Running, AssistantTaskStatus.Queued) || task.reconciliationRequired) return null
    if (snapshot.taskRecord(task.id)?.state in setOf("stop_requested", "needs_verification")) return null
    val live = liveJobStages[task.id] ?: task.activeRunId?.let(liveJobStages::get) ?: return null
    return live.takeUnless { it.terminal || (task.activeRunId != null && it.operationId != task.activeRunId) }
}

internal fun AssistantUiState.jobView(task: AssistantTask): AssistantJobView {
    val snapshot = snapshot ?: AssistantSnapshot()
    val record = snapshot.taskRecord(task.id)
    val job = snapshot.job(task.id)
    val live = liveJobStage(task)
    val pending = snapshot.pendingOperations().filter { it.taskId == task.id }
    // An execution whose outcome is not established is never presented as finished or failed.
    val unknown = task.status == AssistantTaskStatus.Unknown || task.reconciliationRequired || record?.state == "needs_verification"
    val label = if (task.reconciliationRequired && record?.state !in setOf("stop_requested", "needs_verification")) "待核对" else snapshot.taskStateLabel(task)
    val tone = when {
        unknown || snapshot.taskNeedsAttention(task) || record?.state == "stop_requested" -> PTone.Warning
        task.status == AssistantTaskStatus.Failed -> PTone.Danger
        task.status == AssistantTaskStatus.Done -> PTone.Positive
        task.status == AssistantTaskStatus.Running -> PTone.Accent
        else -> PTone.Neutral
    }
    return AssistantJobView(task, job, live, label, live?.stageLabel?.takeIf(String::isNotBlank) ?: snapshot.taskStage(task), tone,
        task.status == AssistantTaskStatus.Running && !unknown, unknown,
        job?.threadId ?: snapshot.taskOperations(task).lastOrNull { it.threadId != null }?.threadId, pending,
        task.status in setOf(AssistantTaskStatus.Running, AssistantTaskStatus.Queued, AssistantTaskStatus.WaitingUser) && record?.state != "stop_requested",
        record?.unread == true)
}

/** The last merge attempt in plain words; null when none was made. A refused merge never changed the project. */
internal fun assistantMergeOutcome(job: AssistantJob): Pair<String, PTone>? {
    if (job.discardedAtMs != null) return "分支已丢弃，改动没有合入项目" to PTone.Neutral
    val merge = job.lastMerge ?: return if (job.mergedAtMs != null) "已合入项目" to PTone.Positive else null
    return when (merge.outcome) {
        "merged" -> "已合入项目${merge.commit?.take(7)?.let { " · $it" }.orEmpty()}" to PTone.Positive
        "nothing_to_merge" -> "没有需要合入的改动" to PTone.Neutral
        "conflict" -> ("合入时与项目里的改动冲突，项目没有被改动。" + (merge.files.takeIf { it.isNotEmpty() }?.let { files ->
            "冲突文件：${files.take(3).joinToString("、")}${if (files.size > 3) " 等 ${files.size} 个" else ""}。" }.orEmpty()) + "在原生会话里处理后可以再次合入。") to PTone.Warning
        "main_dirty" -> "项目里有尚未提交的改动，这次没有合入。先在电脑上提交或清理后再合入。" to PTone.Warning
        "main_detached" -> "项目当前不在任何分支上，这次没有合入。在电脑上切回分支后再合入。" to PTone.Warning
        "unsupported_git" -> "电脑上的 Git${merge.version?.let { "（$it）" }.orEmpty()}不支持这种合入，这次没有合入。" to PTone.Warning
        else -> "合入结果待核对" to PTone.Warning
    }
}

@Composable
internal fun assistantToneInk(tone: PTone): Color = when (tone) {
    PTone.Neutral -> Pocket.colors.inkMuted; PTone.Accent -> Pocket.colors.accent; PTone.Positive -> Pocket.colors.positive
    PTone.Warning -> Pocket.colors.warning; PTone.Danger -> Pocket.colors.danger
}

/** "+42/-11 · 2 文件": additions and removals keep their outcome colours, the count stays quiet. */
@Composable
internal fun assistantDiffStats(stats: AssistantJobDiffStats): AnnotatedString {
    val c = Pocket.colors
    return buildAnnotatedString {
        withStyle(SpanStyle(color = c.positive)) { append("+${stats.insertions}") }
        withStyle(SpanStyle(color = c.inkFaint)) { append("/") }
        withStyle(SpanStyle(color = c.danger)) { append("-${stats.deletions}") }
        withStyle(SpanStyle(color = c.inkMuted)) { append(" · ${stats.files} 文件") }
    }
}

/** The status mark. A task that finishes while on screen draws its check once; history shows it at rest. */
@Composable
internal fun AssistantJobGlyph(view: AssistantJobView, modifier: Modifier = Modifier.size(18.dp)) {
    val ink = assistantToneInk(view.tone)
    val initial = remember(view.task.id) { view.task.status }
    when {
        view.unknown || view.tone == PTone.Warning -> PPulseGlyph(MotionGlyphs.Alert, view.label, modifier, ink)
        view.running -> PLoopGlyph(MotionGlyphs.Spin, true, modifier, ink, millis = 1100)
        view.task.status == AssistantTaskStatus.Done -> PEntranceGlyph(MotionGlyphs.CheckCircle, initial != AssistantTaskStatus.Done, modifier, ink)
        view.task.status == AssistantTaskStatus.Failed -> PIcon(PocketIcons.ErrorOutline, null, modifier, ink)
        view.task.status == AssistantTaskStatus.Paused -> PIcon(PocketIcons.StopCircle, null, modifier, ink)
        else -> PPulseGlyph(MotionGlyphs.Clock, view.label, modifier, ink)
    }
}

/** What the job is doing now: the live line while it is valid, otherwise the snapshot's wording. One line. */
@Composable
internal fun AssistantJobStageLine(view: AssistantJobView, modifier: Modifier = Modifier) {
    val c = Pocket.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (view.subagents > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PLoopGlyph(MotionGlyphs.Users, view.running, Modifier.size(15.dp), c.accent, millis = 2200)
            PText("${view.subagents} 子代理", style = Pocket.type.labelSmall, color = c.accent, maxLines = 1)
        }
        ActivityTextFlow(view.running, view.live != null, Modifier.weight(1f, fill = false)) {
            PText(view.stage, style = Pocket.type.bodySmall, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A compact action inside a card: a 48dp target around a smaller pill, so several fit a row and wrap at large text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantCardAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    tone: PTone = PTone.Neutral, primary: Boolean = false, haptic: PocketHaptic? = null, leading: (@Composable () -> Unit)? = null) {
    val c = Pocket.colors
    val ink = when { !enabled -> c.inkFaint; primary -> c.onSolid; tone == PTone.Neutral -> c.ink; else -> assistantToneInk(tone) }
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.heightIn(min = PocketSpace.touch).widthIn(min = PocketSpace.touch).pocketPressScale(interaction, .96f)
        .pocketPressable(enabled, Role.Button, interaction, pressedScale = 1f, highlight = false, haptic = haptic, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Row(Modifier.clip(PocketRadii.capsule).background(if (primary && enabled) c.solid else c.fillStrong).padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProvidePInk(ink, Pocket.type.labelSmall) { leading?.invoke(); PText(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * One card per task, changed in place as the task moves. Collapsed it says what the task is and what it is doing;
 * opened it adds the summary, the change and the actions. A request waiting for the user is always shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantJobInlineCard(task: AssistantTask, state: AssistantUiState, canAct: Boolean, act: (() -> AssistantAction) -> Unit,
    navigate: (String) -> Unit, open: (String, String) -> Unit, confirm: (AssistantJobConfirm) -> Unit, modifier: Modifier = Modifier,
    nested: Boolean = false) {
    val snapshot = state.snapshot ?: return
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val runtime = snapshot.runtimeId.orEmpty()
    val view = state.jobView(task)
    val job = view.job
    val outcome = job?.let(::assistantMergeOutcome)
    // Opens by itself when there is something to decide; the reader's own choice then stays.
    var chosen by rememberSaveable(task.id) { mutableStateOf<Boolean?>(null) }
    val expanded = chosen ?: (job?.readyToMerge == true || outcome?.second == PTone.Warning)
    PCard(modifier.testTag("assistant-chat-task:${task.id}").animateContentSize(PocketMotion.resize<androidx.compose.ui.unit.IntSize>().orSnap(motion)),
        color = if (nested) c.sunken else c.surface, padding = PaddingValues(0.dp), spacing = 0.dp,
        border = if (nested) null else BorderStroke(1.dp, (if (view.pending.isNotEmpty()) c.warning.copy(alpha = .55f) else c.line.copy(alpha = if (c.dark) .5f else .7f)))) {
        Column(Modifier.fillMaxWidth().pocketPressable(role = Role.Button, pressedScale = .99f, onClickLabel = if (expanded) "收起任务" else "展开任务") { chosen = !expanded }
            .testTag("assistant-chat-task-toggle:${task.id}").semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistantJobGlyph(view)
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PText(view.label, Modifier.weight(1f, fill = false), style = Pocket.type.labelSmall, color = assistantToneInk(view.tone), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (view.unread) PBadge("新进展", tone = PTone.Accent)
                }
                PToggleGlyph(MotionGlyphs.Chevron, expanded, Modifier.size(18.dp), c.inkFaint)
            }
            PText(view.title, Modifier.testTag("assistant-chat-task-title:${task.id}"), style = Pocket.type.titleSmall, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            view.project?.let { project ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    PIcon(PocketIcons.Folder, null, Modifier.size(13.dp), c.inkFaint)
                    PText(project, style = Pocket.type.caption, color = c.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            AssistantJobStageLine(view, Modifier.fillMaxWidth().testTag("assistant-job-stage:${task.id}"))
            if (!expanded && job?.diffStats != null && job.diffStats.files > 0) PText(assistantDiffStats(job.diffStats), style = Pocket.type.labelSmall, maxLines = 1)
        }
        if (view.pending.isNotEmpty()) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            view.pending.forEach { key(it.id) { AssistantHumanQuestion(it, runtime, state, canAct, act) } }
        }
        if (expanded) AssistantJobDetails(view, state, canAct, navigate, open, confirm, outcome)
    }
}

/** The job's branch can still be merged or dropped: it exists and neither has happened yet. */
internal fun AssistantJob.branchUndecided() = workspaceMode == "worktree" && branch != null && mergedAtMs == null && discardedAtMs == null

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantJobDetails(view: AssistantJobView, state: AssistantUiState, canAct: Boolean, navigate: (String) -> Unit,
    open: (String, String) -> Unit, confirm: (AssistantJobConfirm) -> Unit, outcome: Pair<String, PTone>?) {
    val snapshot = state.snapshot ?: return
    val c = Pocket.colors
    val task = view.task
    val job = view.job
    val runtime = snapshot.runtimeId.orEmpty()
    val children = snapshot.orderedTasks().filter { snapshot.taskRecord(it.id)?.parentId == task.id }
    val artifacts = snapshot.taskArtifacts(task.id)
    val result = snapshot.taskOperations(task).lastOrNull { it.role == "Execution" && !it.result.isNullOrBlank() &&
        it.phase in setOf(AssistantOperationPhase.Completed, AssistantOperationPhase.Failed) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PDivider(color = c.line.copy(alpha = .6f))
        (task.detail ?: job?.detail)?.takeIf(String::isNotBlank)?.let {
            PText(it, style = Pocket.type.bodySmall, color = c.inkMuted, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        if (children.isNotEmpty()) PText("子任务 ${children.count { it.status == AssistantTaskStatus.Done }}/${children.size} 已完成", style = Pocket.type.bodySmall)
        result?.let { op ->
            Column(Modifier.fillMaxWidth().testTag("assistant-result:${op.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                snapshot.operationMemberId(op.id, op.taskId)?.let { id -> snapshot.members.firstOrNull { it.id == id } }?.let { member ->
                    PText(member.name, style = Pocket.type.labelSmall, color = c.inkMuted)
                }
                PText(remember(op.result) { markdownPreview(op.result.orEmpty()) }, style = Pocket.type.body, maxLines = 4, overflow = TextOverflow.Ellipsis)
                AssistantCardAction("查看完整结果", { navigate("Result:${op.id}") }, Modifier.testTag("assistant-result-detail:${op.id}"))
            }
        }
        val branch = if (job?.workspaceMode == "worktree") job.branch else null
        if (job?.diffStats != null || branch != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            job?.diffStats?.let { PText(assistantDiffStats(it), Modifier.testTag("assistant-job-diff:${task.id}"), style = Pocket.type.labelSmall, maxLines = 1) }
            if (branch != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PIcon(PocketIcons.CallSplit, null, Modifier.size(13.dp), c.inkFaint)
                PText(branch, style = Pocket.type.mono, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        outcome?.let { (text, tone) ->
            PText(text, Modifier.testTag("assistant-job-merge-outcome:${task.id}"), style = Pocket.type.bodySmall, color = assistantToneInk(tone))
        }
        if (artifacts.isEmpty()) { if (task.status != AssistantTaskStatus.Done) PText("成果尚未交付", style = Pocket.type.caption, color = c.inkFaint) }
        else {
            PText("成果 · ${artifacts.size}", style = Pocket.type.labelSmall, color = c.inkMuted)
            artifacts.take(2).forEach { artifact ->
                PRow(artifact.name, { navigate("Artifact:${artifact.id}") }, icon = PocketIcons.Description, trailing = { PIcon(PocketIcons.ChevronRight, null, Modifier.size(15.dp), c.inkFaint) })
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (job?.readyToMerge == true) AssistantCardAction("合入", { confirm(AssistantJobConfirm(AssistantJobConfirmKind.Merge, task.id)) },
                Modifier.testTag("assistant-job-merge:${task.id}"), enabled = canAct && !state.busy("assistant/v2/task/merge", job.taskId), primary = true,
                leading = { PPulseGlyph(MotionGlyphs.Merge, job.lastMerge?.atMs, Modifier.size(15.dp)) })
            view.threadId?.let { thread ->
                AssistantCardAction(if (job?.diffStats != null) "查看过程/Diff" else "查看过程", { open(runtime, thread) },
                    Modifier.testTag("assistant-job-thread:${task.id}"), enabled = runtime.isNotBlank())
            }
            AssistantCardAction("查看任务", { navigate("Task:${task.id}") }, Modifier.testTag("assistant-chat-task-open:${task.id}"))
            if (job != null && job.branchUndecided() && !view.canStop) AssistantCardAction("丢弃分支", { confirm(AssistantJobConfirm(AssistantJobConfirmKind.Discard, task.id)) },
                Modifier.testTag("assistant-job-discard:${task.id}"), enabled = canAct && !state.busy("assistant/v2/task/discard_worktree", job.taskId), tone = PTone.Danger)
            if (view.canStop) AssistantCardAction("停止", { confirm(AssistantJobConfirm(AssistantJobConfirmKind.Stop, task.id)) },
                Modifier.testTag("assistant-job-stop:${task.id}"), enabled = canAct && !state.busy("assistant/task/pause", task.id), tone = PTone.Danger)
        }
    }
}

/** Related tasks under one heading. Closed it gives the count; open it holds each task's own card. */
@Composable
internal fun AssistantGroupInlineCard(group: AssistantGroup, tasks: List<AssistantTask>, state: AssistantUiState, canAct: Boolean,
    act: (() -> AssistantAction) -> Unit, navigate: (String) -> Unit, open: (String, String) -> Unit, confirm: (AssistantJobConfirm) -> Unit,
    modifier: Modifier = Modifier) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val done = tasks.count { it.status == AssistantTaskStatus.Done }
    val running = tasks.count { it.status == AssistantTaskStatus.Running }
    val waiting = tasks.count { state.snapshot?.taskNeedsAttention(it) == true }
    PCard(modifier.testTag("assistant-group:${group.id}").animateContentSize(PocketMotion.resize<androidx.compose.ui.unit.IntSize>().orSnap(motion)),
        color = c.surface, padding = PaddingValues(0.dp), spacing = 0.dp, border = BorderStroke(1.dp, c.line.copy(alpha = if (c.dark) .5f else .7f))) {
        Column(Modifier.fillMaxWidth().pocketPressable(role = Role.Button, pressedScale = .99f, onClickLabel = if (expanded) "收起任务组" else "展开任务组") { expanded = !expanded }
            .testTag("assistant-group-expand:${group.id}").semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PIcon(PocketIcons.Users, null, Modifier.size(18.dp), c.inkMuted)
                PText(group.title.ifBlank { "相关任务" }, Modifier.weight(1f), style = Pocket.type.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PText("$done/${tasks.size}", style = Pocket.type.labelSmall, color = c.inkMuted, maxLines = 1)
                PToggleGlyph(MotionGlyphs.Chevron, expanded, Modifier.size(18.dp), c.inkFaint)
            }
            PText("${tasks.size} 项分工：$done 已完成 · $running 执行中 · $waiting 待处理", style = Pocket.type.bodySmall, color = c.inkMuted)
            if (waiting > 0 && running > 0) PText("部分分工等待你处理，其余仍在执行", style = Pocket.type.bodySmall, color = c.warning)
        }
        if (expanded) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tasks.forEach { task -> key(task.id) { AssistantJobInlineCard(task, state, canAct, act, navigate, open, confirm, nested = true) } }
            AssistantCardAction("查看目标与分工", { navigate("Group:${group.id}") }, Modifier.testTag("assistant-group-detail:${group.id}"))
        }
    }
}

/** Merging, dropping a branch and stopping are each confirmed once, then sent through the ordinary action path. */
@Composable
internal fun AssistantJobConfirmDialog(confirm: AssistantJobConfirm, state: AssistantUiState, canAct: Boolean, act: (() -> AssistantAction) -> Unit,
    dismiss: () -> Unit) {
    val snapshot = state.snapshot
    val runtime = snapshot?.runtimeId.orEmpty()
    val job = snapshot?.job(confirm.taskId)
    val id = job?.taskId ?: confirm.taskId
    // A second tap can land before the dialog leaves; only the first one sends.
    var sent by remember(confirm) { mutableStateOf(false) }
    fun send(make: () -> AssistantAction) { if (!sent) { sent = true; act(make) }; dismiss() }
    val change = (job?.branch?.let { "分支 $it 上" } ?: "这项任务") + "的改动" + job?.diffStats?.let { "（+${it.insertions}/-${it.deletions} · ${it.files} 文件）" }.orEmpty()
    when (confirm.kind) {
        AssistantJobConfirmKind.Merge -> PDialog("合入这项任务的改动？", dismiss, "确认合入", { send { AssistantActions.mergeTask(runtime, id) } },
            text = "会把${change}合入项目。有冲突或项目里有未提交的改动时不会改动项目，结果以电脑回执为准。",
            confirmEnabled = canAct && job?.readyToMerge == true && !state.busy("assistant/v2/task/merge", id))
        AssistantJobConfirmKind.Discard -> PDialog("丢弃这个分支？", dismiss, "确认丢弃", { send { AssistantActions.discardWorktree(runtime, id) } },
            text = "${change}还没有合入，丢弃后会被删除，无法恢复。项目本身不受影响。", destructive = true,
            confirmEnabled = canAct && job?.branchUndecided() == true && !state.busy("assistant/v2/task/discard_worktree", id))
        AssistantJobConfirmKind.Stop -> PDialog("停止这项任务？", dismiss, "确认停止", { send { AssistantActions.task(runtime, confirm.taskId, false) } },
            text = "会请求停止当前尝试，已有成果和记录仍保留。收到执行器确认后显示已停止。", dismissLabel = "继续执行", destructive = true,
            confirmEnabled = canAct && !state.busy("assistant/task/pause", confirm.taskId))
    }
}
