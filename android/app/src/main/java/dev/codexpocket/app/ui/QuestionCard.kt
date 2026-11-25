package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.JsonObject

/** Parent mounts this above the composer. Callbacks dispatch a complete batch; this surface never sends. */
@Composable
internal fun QuestionDock(
    s: PocketState,
    onRespond: (PendingApproval, JsonObject) -> Unit,
    onAsyncAnswers: (List<Pair<JsonObject, String>>) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (ThreadKey, Set<String>) -> Unit = { _, _ -> },
    onRestore: (ThreadKey, Set<String>) -> Unit = { _, _ -> },
    onDelete: (ThreadKey, String) -> Unit = { _, _ -> },
) {
    val memory = remember(s.computerId) { QuestionDockMemory() }
    val context = LocalContext.current
    val answerStore = remember(context) { QuestionAnswerStore(context) }
    DisposableEffect(memory) { onDispose { memory.clear() } }
    var selectedKey by remember(memory) { mutableStateOf<String?>(null) }
    var collapsed by remember(memory) { mutableStateOf(false) }
    val receipts = (answerStore.all().associateBy { it.commandId } + s.questionDeliveries.associateBy { it.commandId })
        .values.filter { it.chat.computerId == s.computerId }
    val pending = questionDockEntries(s, receipts)
    val (closed, entries) = pending.partition { entry ->
        entry.asynchronous && entry.batch!!.questions.all { it.id in s.closedQuestions }
    }
    val requests = questionDockRequests(s, receipts)
    val scope = s.selected?.key?.encoded.orEmpty()
    memory.reconcile(scope, requests, s.online && s.approvalsFresh && s.conversationLoadState == ConversationLoadState.Ready)
    // Synchronize every batch even while collapsed or while a different batch is selected.
    requests.forEach { entry -> entry.batch?.let { batch ->
        val session = memory.session(batch)
        entry.receipt?.let { session.restoreSubmitted(it, entry.approval) }
        val submitting = if (entry.approval != null) entry.key in s.submittingApprovalKeys
            else session.phase == QuestionPhase.Submitting && (s.sendBusy || s.busy)
        val availability = questionAvailability(s, entry)
        session.observe(entry.submitted || availability in setOf(QuestionPhase.Forwarded, QuestionPhase.AwaitingConfirmation,
            QuestionPhase.Confirmed, QuestionPhase.AnsweredElsewhere, QuestionPhase.Cancelled), submitting,
            pendingConfirmed = availability == QuestionPhase.Waiting)
    } }
    if (pending.isEmpty()) return
    val chatKey = s.selected?.key ?: return
    Column(modifier.fillMaxWidth()) {
        if (closed.isNotEmpty()) {
            val ids = closed.flatMap { it.batch!!.questions.map { q -> q.id } }.toSet()
            PTextAction("待回答 · ${ids.size}", {
                selectedKey = closed.first().key
                collapsed = false
                onRestore(chatKey, ids)
            }, Modifier.align(Alignment.End).testTag("question-restore"), leading = PocketIcons.ChatBubbleOutline)
        }
        if (entries.isEmpty()) return@Column
        val index = entries.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: 0
        val entry = entries[index]
        LaunchedEffect(scope, entry.key) { selectedKey = entry.key }
        val availability = questionAvailability(s, entry)
        val session = entry.batch?.let(memory::session)
        val phase = session?.presentationPhase(availability) ?: availability
        PSurface(Modifier.fillMaxWidth().testTag("question-dock"), shape = RoundedCornerShape(20.dp),
            color = Pocket.colors.surface, border = BorderStroke(1.dp, Pocket.colors.line)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PIcon(if (entry.batch != null) PocketIcons.ChatBubbleOutline else PocketIcons.ShieldCheck, null,
                        Modifier.padding(end = 8.dp).size(18.dp), tint = Pocket.colors.accent)
                    Column(Modifier.weight(1f)) {
                        PText("待处理 · ${entries.size}", Modifier.testTag("question-batch-count"), style = Pocket.type.label)
                        PText("${index + 1}/${entries.size} · " + if (entry.asynchronous) "异步问题" else if (entry.batch != null) "需要回答" else "权限审批",
                            style = Pocket.type.caption, color = Pocket.colors.inkMuted)
                    }
                    if (entries.size > 1) {
                        ActionIcon(PocketIcons.ChevronLeft, "上一批", index > 0) { selectedKey = entries[index - 1].key }
                        ActionIcon(PocketIcons.ChevronRight, "下一批", index < entries.lastIndex) { selectedKey = entries[index + 1].key }
                    }
                    if (entry.asynchronous) {
                        PIconButton(PocketIcons.Close, "关闭问题卡片", {
                            onClose(chatKey, entry.batch!!.questions.map { it.id }.toSet())
                        }, Modifier.testTag("question-close"))
                    } else ActionIcon(if (collapsed) PocketIcons.ExpandMore else PocketIcons.ExpandLess,
                        if (collapsed) "展开问题" else "收起问题") { collapsed = !collapsed }
                }
                if (collapsed && !entry.asynchronous) {
                    QuestionStatus(phase, !s.busy && !s.sendBusy)
                } else if (entry.batch != null && session != null) {
                    QuestionCard(entry.batch, session, phase, onSubmit = { answers ->
                        entry.approval?.let { approval ->
                            QuestionBatches.blockingResponse(entry.batch, answers)?.let { onRespond(approval, it) }
                        } ?: QuestionBatches.asyncAnswers(entry.batch, answers)?.let(onAsyncAnswers)
                    }, enabled = !s.busy && !s.sendBusy, revealState = memory.reveal(entry.batch),
                        onDelete = if (entry.asynchronous) ({ id -> onDelete(chatKey, id) }) else null)
                } else {
                    // Command/file approval decisions keep their own native allow/decline/cancel interface.
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        QuestionStatus(phase, !s.busy)
                        entry.approval?.let { ApprovalCard(it, availability == QuestionPhase.Waiting && !s.busy &&
                            !s.sendBusy && it.key !in s.submittingApprovalKeys, onRespond) }
                    }
                }
            }
        }
    }
}

/** Reused by blocking RPC cards, async batches and the legacy single-question form. */
@Composable
internal fun QuestionCard(
    batch: QuestionBatch,
    session: QuestionSession,
    availability: QuestionPhase,
    onSubmit: (List<Pair<String, String>>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    revealState: QuestionRevealState? = null,
    onDelete: ((String) -> Unit)? = null,
) {
    var revision by remember(session) { mutableIntStateOf(0) }
    // The session is plain memory so it can be tested without Compose or a persistence runtime.
    @Suppress("UNUSED_VARIABLE") val observedRevision = revision
    val phase = session.presentationPhase(availability)
    val interactive = enabled && phase == QuestionPhase.Waiting
    val question = session.currentQuestion
    val reveal = revealState ?: remember(session) { QuestionRevealState() }
    QuestionReveal(question?.id, reveal, modifier.fillMaxWidth().testTag("question-card")) {
        // Answers belong to the session; focus/IME/scroll nodes belong to this question only.
        key(batch.id, question?.id) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PText(question?.header?.takeIf { it.isNotBlank() } ?: if (batch.asynchronous) stringResource(R.string.v02_async) else stringResource(R.string.your_answer),
                        Modifier.weight(1f), style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted)
                    PText(if (question == null) "0/0" else "${session.page + 1}/${batch.questions.size}", Modifier.testTag("question-page"),
                        style = Pocket.type.label)
                    if (batch.asynchronous && question != null && onDelete != null) {
                        PIconButton(PocketIcons.DeleteOutline, "从待回答中删除", { onDelete(question.id) },
                            Modifier.testTag("question-delete:${question.id}"))
                    }
                }
                QuestionStatus(phase, enabled)
                if (question != null) {
                    val scroll = key(batch.id, question.id) { rememberScrollState() }
                    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PText(question.prompt, style = Pocket.type.body)
                        if (phase == QuestionPhase.Waiting || phase == QuestionPhase.Offline || phase == QuestionPhase.Stale || phase == QuestionPhase.Submitting) {
                            question.options.forEachIndexed { index, option ->
                                QuestionOptionRow(option.label, option.description, option.recommended,
                                    selected = !session.otherSelected(question.id) && session.answer(question.id) == option.value,
                                    enabled = interactive, modifier = Modifier.testTag("question-option:${question.id}:$index")) {
                                    if (session.selectOption(question.id, option.value)) revision++
                                }
                            }
                            if (question.allowsOther) {
                                if (question.options.isNotEmpty()) QuestionOptionRow(stringResource(R.string.other_answer), "", false,
                                    session.otherSelected(question.id), interactive, Modifier.testTag("question-other:${question.id}")) {
                                    if (session.selectOther(question.id)) revision++
                                }
                                if (question.options.isEmpty() || session.otherSelected(question.id)) {
                                    PocketTextField(session.otherText(question.id), { if (session.editOther(question.id, it)) revision++ },
                                        Modifier.fillMaxWidth().testTag("question-answer:${question.id}").semantics { if (question.secret) password() },
                                        label = stringResource(if (question.options.isEmpty()) R.string.your_answer else R.string.other_answer),
                                        enabled = interactive, singleLine = question.secret, maxLines = if (question.secret) 1 else 4,
                                        visualTransformation = if (question.secret) PasswordVisualTransformation() else VisualTransformation.None,
                                        keyboardOptions = if (question.secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false,
                                            platformImeOptions = PlatformImeOptions(privateImeOptions = "com.google.android.inputmethod.latin.noPersonalizedLearning=true")) else KeyboardOptions.Default)
                                    if (question.secret) PText("敏感回答仅在当前界面内存中保留", style = Pocket.type.caption,
                                        color = Pocket.colors.inkMuted)
                                }
                            }
                        }
                        if (!interactive && phase != QuestionPhase.Unsupported) {
                            if (question.secret) PText("敏感回答不会保存", style = Pocket.type.bodySmall)
                            else session.answer(question.id)?.let { answer ->
                                PText("提交的回答：$answer", Modifier.testTag("question-submitted-answer:${question.id}"),
                                    style = Pocket.type.body)
                            }
                        }
                    }
                }
                if (phase != QuestionPhase.Unsupported) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (session.page > 0) PocketButton("上一题", { session.move(-1); revision++ },
                        Modifier.weight(1f).testTag("question-previous"), secondary = true)
                    if (session.page < batch.questions.lastIndex) {
                        PocketButton("下一题", { session.move(1); revision++ }, Modifier.weight(1f).testTag("question-next"))
                    } else {
                        PocketButton(stringResource(R.string.submit_answers), {
                            session.beginSubmission(phase)?.let { answers -> revision++; onSubmit(answers) }
                        }, Modifier.weight(1f).testTag("question-submit"), enabled = interactive && session.complete)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionOptionRow(label: String, description: String, recommended: Boolean, selected: Boolean,
    enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PSurface(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(12.dp), color = if (selected) Pocket.colors.accentSoft else Pocket.colors.sunken) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PIcon(if (selected) PocketIcons.CheckCircle else PocketIcons.RadioButtonUnchecked, null, Modifier.size(18.dp),
                tint = if (enabled) Pocket.colors.accent else Pocket.colors.inkMuted)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PText(label, style = Pocket.type.label)
                if (description.isNotBlank()) PText(description, style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
            }
            if (recommended) PSurface(shape = RoundedCornerShape(6.dp), color = Pocket.colors.accentSoft) {
                PText("推荐", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = Pocket.type.caption,
                    color = Pocket.colors.onAccentSoft)
            }
        }
    }
}

@Composable
internal fun QuestionStatus(phase: QuestionPhase, enabled: Boolean = true) {
    PText(when (phase) {
        QuestionPhase.Waiting -> if (enabled) "等待回答 · 完成后提交全部答案" else "等待当前操作完成"
        QuestionPhase.Submitting -> "正在提交 · 等待电脑确认"
        QuestionPhase.Submitted -> "已提交 · 等待问题处理完成"
        QuestionPhase.Forwarded -> "已转交电脑 · 等待原生答案确认"
        QuestionPhase.AwaitingConfirmation -> "等待原生答案确认 · 回答已保留"
        QuestionPhase.Confirmed -> "原生已确认答案"
        QuestionPhase.AnsweredElsewhere -> "已在其他端回答"
        QuestionPhase.Cancelled -> "问题已取消"
        QuestionPhase.Offline -> "离线 · 重新连接后提交"
        QuestionPhase.Stale -> "待重新同步 · 确认问题仍有效后提交"
        QuestionPhase.Unsupported -> "此问题包含敏感字段，当前异步协议会保存为聊天消息，请在电脑端处理"
    }, Modifier.testTag("question-status").semantics { liveRegion = LiveRegionMode.Polite },
        style = Pocket.type.caption, color = Pocket.colors.inkMuted)
}
