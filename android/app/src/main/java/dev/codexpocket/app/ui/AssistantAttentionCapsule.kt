package dev.codexpocket.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*

/** What needs the user and what is moving, read from the snapshot alone. Live frames never add to it. */
internal class AssistantAttention(val questions: List<AssistantOperation>, val recoveries: List<AssistantRecoveryRequest>,
    val handoffs: List<AssistantOperation>, val running: List<AssistantTask>, val delivered: List<AssistantTask>, val first: String?) {
    val pending get() = questions.size + recoveries.size + handoffs.size
    /** Null when nothing is happening: the capsule then takes no room at all. */
    val summary get() = when {
        pending > 0 -> "$pending 项待你处理" + first?.let { " · $it" }.orEmpty()
        running.isNotEmpty() -> "${running.size} 个任务进行中" + first?.let { " · $it" }.orEmpty()
        else -> null
    }
}

internal fun AssistantSnapshot.attention(): AssistantAttention {
    val questions = pendingOperations().distinctBy { it.id }
    val handoffs = operations.filter { it.handoffRecovery != null }.distinctBy { it.id }
    val ordered = orderedTasks()
    fun title(id: String?) = id?.let { task -> job(task)?.title?.takeIf(String::isNotBlank) ?: tasks.firstOrNull { it.id == task }?.prompt }
    val running = ordered.filter { it.status in setOf(AssistantTaskStatus.Running, AssistantTaskStatus.Queued) && !it.reconciliationRequired &&
        !taskNeedsAttention(it) && taskRecord(it.id)?.state != "needs_verification" }
    val delivered = ordered.filter { it.status == AssistantTaskStatus.Done && !it.reconciliationRequired && taskRecord(it.id)?.state != "needs_verification" }
        .takeLast(5).asReversed()
    val first = questions.firstOrNull()?.let { op ->
        val what = if (op.question?.kind == AssistantQuestionKind.Approval) "审批" else "回答问题"
        domain?.attention?.firstOrNull { it.state == "open" && it.requestId == op.question?.requestId }?.title?.takeIf(String::isNotBlank)
            ?: (what + title(op.taskId)?.let { " · $it" }.orEmpty())
    } ?: if (recoveries.isNotEmpty()) "结束待恢复的原操作" else if (handoffs.isNotEmpty()) "恢复尚未开始的消息" else running.firstOrNull()?.let { title(it.id) }
    return AssistantAttention(questions, recoveries.distinct(), handoffs, running, delivered, first)
}

/**
 * Hidden with no height while nothing is happening; names the running work; outlined when something waits for the
 * user. It sits in the header's flow, so the conversation starts below it rather than under it.
 */
@Composable
internal fun AssistantAttentionCapsule(attention: AssistantAttention, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val summary = attention.summary
    val waiting = attention.pending > 0
    Box(modifier.fillMaxWidth().testTag("assistant-attention-slot").animateContentSize(PocketMotion.state<IntSize>().orSnap(motion))) {
        if (summary != null) PocketGlassSurface(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp).fillMaxWidth()
            .then(if (waiting) Modifier.border(1.5.dp, c.warning.copy(alpha = .8f), PocketRadii.capsule) else Modifier)
            .testTag("assistant-attention").semantics { contentDescription = "$summary，查看详情" }, PocketRadii.capsule, onClick = onOpen) {
            Row(Modifier.fillMaxWidth().heightIn(min = PocketSpace.touch).padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (waiting) PPulseGlyph(MotionGlyphs.Alert, attention.pending, Modifier.size(18.dp), c.warning)
                else PLoopGlyph(MotionGlyphs.Spin, true, Modifier.size(18.dp), c.accent, millis = 1100)
                PText(summary, Modifier.weight(1f), style = Pocket.type.labelSmall, color = c.ink, fontWeight = if (waiting) FontWeight.SemiBold else null,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                PIcon(PocketIcons.ExpandMore, null, Modifier.size(16.dp), c.inkFaint)
            }
        }
    }
}

@Composable
private fun AssistantAttentionHeading(text: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PText(text, Modifier.semantics { heading() }, style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted)
        PText("$count", style = Pocket.type.labelSmall, color = Pocket.colors.inkFaint)
    }
}

/**
 * The capsule opened: what waits for a decision (the original requests, answerable here), what is running and what
 * was delivered lately. It opens in Pocket's overlay layer, so Markdown and thread links are provided again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantAttentionSheet(attention: AssistantAttention, state: AssistantUiState, canAct: Boolean, act: (() -> AssistantAction) -> Unit,
    navigate: (String) -> Unit, open: (String, String) -> Unit, confirm: (AssistantJobConfirm) -> Unit, links: ThreadLinkBinding?, onDismiss: () -> Unit) {
    val snapshot = state.snapshot ?: return
    val c = Pocket.colors
    val runtime = snapshot.runtimeId.orEmpty()
    PSheet("待办与进展", onDismiss, subtitle = attention.summary ?: "现在没有需要处理的事", maxHeightFraction = .5f) {
        ProvideMarkdownRendering(links) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("assistant-attention-sheet").padding(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (attention.pending > 0) {
                    AssistantAttentionHeading("待你拍板", attention.pending)
                    attention.questions.forEach { op -> key("question:${op.id}") { AssistantHumanQuestion(op, runtime, state, canAct, act) } }
                    attention.recoveries.forEach { request -> key("recovery:${request.operationId}:${request.requestId}") {
                        AssistantCard { AssistantRecoveryCard(request, runtime, state, canAct, act) }
                    } }
                    attention.handoffs.forEach { op -> key("handoff:${op.id}") { AssistantCard { AssistantHandoffRecoveryCard(op, runtime, state, canAct, act) } } }
                }
                if (attention.running.isNotEmpty()) {
                    AssistantAttentionHeading("进行中", attention.running.size)
                    attention.running.forEach { task -> key("running:${task.id}") {
                        val view = state.jobView(task)
                        PCard(Modifier.testTag("assistant-attention-running:${task.id}"), color = c.fill, padding = PaddingValues(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 4.dp), spacing = 4.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistantJobGlyph(view)
                                PText(view.title, Modifier.weight(1f), style = Pocket.type.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            AssistantJobStageLine(view, Modifier.fillMaxWidth())
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                view.threadId?.let { thread -> AssistantCardAction("打开原生会话", { onDismiss(); open(runtime, thread) },
                                    Modifier.testTag("assistant-attention-thread:${task.id}"), enabled = runtime.isNotBlank()) }
                                if (view.canStop) AssistantCardAction("停止", { confirm(AssistantJobConfirm(AssistantJobConfirmKind.Stop, task.id)) },
                                    Modifier.testTag("assistant-attention-stop:${task.id}"), enabled = canAct && !state.busy("assistant/task/pause", task.id), tone = PTone.Danger)
                            }
                        }
                    } }
                }
                if (attention.delivered.isNotEmpty()) {
                    AssistantAttentionHeading("最近交付", attention.delivered.size)
                    attention.delivered.forEach { task -> key("delivered:${task.id}") {
                        val view = state.jobView(task)
                        val job = view.job
                        PCard(Modifier.testTag("assistant-attention-delivered:${task.id}"), color = c.fill, padding = PaddingValues(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 4.dp), spacing = 4.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistantJobGlyph(view)
                                PText(view.title, Modifier.weight(1f), style = Pocket.type.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            job?.diffStats?.let { PText(assistantDiffStats(it), style = Pocket.type.labelSmall, maxLines = 1) }
                            job?.let(::assistantMergeOutcome)?.let { (text, tone) -> PText(text, style = Pocket.type.bodySmall, color = assistantToneInk(tone)) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (job?.readyToMerge == true) AssistantCardAction("合入", { confirm(AssistantJobConfirm(AssistantJobConfirmKind.Merge, task.id)) },
                                    Modifier.testTag("assistant-attention-merge:${task.id}"), enabled = canAct && !state.busy("assistant/v2/task/merge", job.taskId), primary = true)
                                AssistantCardAction("查看", { onDismiss(); navigate("Task:${task.id}") }, Modifier.testTag("assistant-attention-view:${task.id}"))
                            }
                        }
                    } }
                }
                if (attention.pending == 0 && attention.running.isEmpty() && attention.delivered.isEmpty())
                    PText("没有等你决定的事，也没有进行中的任务。", Modifier.padding(4.dp), style = Pocket.type.bodySmall, color = c.inkMuted)
            }
        }
    }
}
