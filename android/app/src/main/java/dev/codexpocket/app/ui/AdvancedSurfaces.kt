package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

internal fun canMutate(s: PocketState, method: String) = s.online && s.accessAllowsSending() && !s.busy && !s.sendBusy && !s.uncertainSend && s.selected?.conflict == false && s.selected?.archived == false && s.selected?.isSubagent == false && s.supports(method)

@Composable
internal fun AsyncQuestionForm(question: JsonObject, s: PocketState, onDelete: ((String) -> Unit)? = null,
    onAnswer: (String, JsonObject) -> Any) {
    val batch = QuestionBatches.async(s.selected?.key?.encoded.orEmpty(), listOf(question)).single()
    val session = remember(batch.id) { QuestionSession(batch) }
    DisposableEffect(session) { onDispose { session.clear() } }
    session.update(batch)
    val availability = questionAvailability(s, QuestionDockEntry(batch.id, batch))
    session.observe(question.flag("submitted"), session.phase == QuestionPhase.Submitting && (s.sendBusy || s.busy),
        pendingConfirmed = availability == QuestionPhase.Waiting)
    QuestionCard(batch, session, availability, onSubmit = { answers ->
        answers.singleOrNull()?.let { (_, answer) -> onAnswer(question.string("id"), buildJsonObject { put("answer", answer) }) }
    }, modifier = Modifier.padding(vertical = 12.dp), enabled = !s.busy && !s.sendBusy, onDelete = onDelete)
}

@Composable
internal fun AttentionHome(s: PocketState, repo: PocketRepository, onOpenChat: (ChatEntity) -> Unit) {
    val approvals = s.approvals.filter { approvalNeedsAction(s, it) }.distinctBy { it.key }
    val questions = s.pendingQuestions.flatMap { (key, values) -> values.filter { !it.flag("submitted") }.distinctBy { it.string("id") }.map { key to it } }
    val fallbackQuestions = if (s.pendingQuestions.isEmpty()) s.advanced.asyncQuestions.filter { !it.flag("submitted") }.distinctBy { it.string("id") } else emptyList()
    val results = s.chats.filter { it.key.encoded in s.unreadKeys || it.status == "failed" }
    val insets = LocalHomeContentPadding.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = insets.calculateTopPadding() + 20.dp, bottom = insets.calculateBottomPadding() + 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (approvals.isEmpty() && questions.isEmpty() && fallbackQuestions.isEmpty() && results.isEmpty()) item {
            EmptyState(stringResource(R.string.v02_all_clear), stringResource(R.string.v02_all_clear_detail))
        }
        items(approvals, key = { it.key }, contentType = { "approval" }) { approval ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.chats.firstOrNull { it.runtimeId == approval.runtimeId && it.key.nativeId == approval.threadId }?.let { chat ->
                    Box(Modifier.pocketMorphSource("chat:${chat.key.encoded}")) { PocketActionRow(chat.title, PocketIcons.ChatBubbleOutline, { onOpenChat(chat) }, trailing = { PIcon(PocketIcons.ChevronRight, null, Modifier.size(18.dp)) }) }
                }
                if (!s.approvalsFresh) PText("待重新同步 · 连接电脑后确认是否仍需审批", style = Pocket.type.bodySmall)
                ApprovalCard(approval, s.online && s.approvalsFresh && !s.busy && s.runtimes.any { it.id == approval.runtimeId && it.online }, repo::respond)
            }
        }
        items(questions, key = { it.first + it.second.string("id") }, contentType = { "question" }) { (key, question) ->
            s.chats.firstOrNull { it.key.encoded == key }?.let { chat ->
                if (chat.key == s.selected?.key) AsyncQuestionForm(question, s,
                    onDelete = { repo.deleteAsyncQuestion(chat.key, it) }, onAnswer = repo::answerAsync)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).pocketMorphSource("chat:${chat.key.encoded}")) { PocketActionRow(
                        question.string("title"), PocketIcons.ChatBubbleOutline, {
                            repo.restoreAsyncQuestions(chat.key, setOf(question.string("id")))
                            onOpenChat(chat)
                        }, subtitle = chat.title, trailing = { PIcon(PocketIcons.ChevronRight, null, Modifier.size(18.dp)) },
                    ) }
                    PIconButton(PocketIcons.DeleteOutline, "从待回答中删除", { repo.deleteAsyncQuestion(chat.key, question.string("id")) },
                        Modifier.testTag("attention-delete:${question.string("id")}"))
                }
            }
        }
        items(fallbackQuestions, key = { it.string("id") }, contentType = { "question" }) { question ->
            AsyncQuestionForm(question, s, onDelete = { id -> s.selected?.key?.let { repo.deleteAsyncQuestion(it, id) } },
                onAnswer = repo::answerAsync)
        }
        items(results, key = { "result:${it.key.encoded}" }, contentType = { "result" }) { chat ->
            Box(Modifier.pocketMorphSource("chat:${chat.key.encoded}")) { PocketActionRow(
                title = chat.title,
                icon = if (chat.status == "failed") PocketIcons.ErrorOutline else PocketIcons.CheckCircle,
                onClick = { onOpenChat(chat) },
                subtitle = stringResource(if (chat.status == "failed") R.string.v02_failed_result else R.string.v02_unread_result),
                trailing = { PIcon(PocketIcons.ChevronRight, null, Modifier.size(18.dp)) },
            ) }
        }
    }
}

@Composable
internal fun AttachmentActionSheet(images: Boolean, onDismiss: () -> Unit, onCamera: () -> Unit, onImages: () -> Unit, onText: () -> Unit) {
    PocketSheet(title = stringResource(R.string.v02_attach), onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
            PocketActionRow(stringResource(R.string.v02_camera), PocketIcons.PhotoCamera, onCamera, enabled = images)
            PocketActionRow(stringResource(R.string.v02_pick_images), PocketIcons.Image, onImages, enabled = images)
            if (!images) PText(stringResource(R.string.v02_images_unsupported), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Pocket.colors.inkMuted, style = Pocket.type.bodySmall)
            PocketActionRow(stringResource(R.string.v02_text_context), PocketIcons.Description, onText, subtitle = stringResource(R.string.v02_text_context_detail))
        }
    }
}

@Composable
internal fun GoalSheet(s: PocketState, repo: PocketRepository, onDismiss: () -> Unit) {
    var objective by rememberSaveable(s.selected?.key?.encoded, s.advanced.goal) { mutableStateOf(s.advanced.goal?.string("objective").orEmpty()) }
    PocketSheet(title = stringResource(R.string.v02_goal), onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PocketTextField(objective, { objective = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.v02_goal_prompt), minLines = 3, maxLines = 8)
            PocketButton(stringResource(R.string.save), { repo.setGoal(objective); onDismiss() }, modifier = Modifier.fillMaxWidth(), enabled = objective.isNotBlank() && canMutate(s, "thread/goal/set"))
            if (s.advanced.goal != null) {
                val paused = s.advanced.goal.string("status") == "paused"
                PocketActionRow(
                    stringResource(if (paused) R.string.v02_goal_resume else R.string.v02_goal_pause),
                    if (paused) PocketIcons.PlayCircle else PocketIcons.StopCircle,
                    { repo.setGoal(objective, paused = !paused); onDismiss() },
                    enabled = canMutate(s, "thread/goal/set"),
                )
                PocketActionRow(stringResource(R.string.v02_goal_clear), PocketIcons.DeleteOutline, { repo.setGoal(null); onDismiss() }, enabled = canMutate(s, "thread/goal/clear"), destructive = true)
            }
        }
    }
}

@Composable
internal fun UsageSheet(s: PocketState, onDismiss: () -> Unit) {
    val usage = s.advanced.usage
    PocketSheet(title = stringResource(R.string.v02_usage), onDismiss = onDismiss) {
        val total = usage?.obj("total") ?: buildJsonObject {}
        val tokenCount = total.string("totalTokens", usage?.string("totalTokens").orEmpty())
        val window = usage?.string("modelContextWindow").orEmpty()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (tokenCount.isNotBlank()) PText(stringResource(R.string.v02_tokens, tokenCount), style = Pocket.type.title)
            if (window.isNotBlank()) PText(stringResource(R.string.v02_context, window), style = Pocket.type.body, color = Pocket.colors.inkMuted)
            if (tokenCount.isBlank() && window.isBlank()) PText(stringResource(R.string.v02_usage_empty), color = Pocket.colors.inkMuted)
        }
    }
}

@Composable
internal fun ReferenceSheet(s: PocketState, repo: PocketRepository, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var workspace by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(query, workspace, s.selected?.key) { if (!workspace && s.online && s.supports("references/list")) { delay(250); repo.loadReferences(query) } }
    PocketSheet(title = stringResource(R.string.v02_references), onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PocketTextField(query, { query = it; workspace = false }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.v02_reference_search), singleLine = true)
            PocketActionRow(stringResource(R.string.v02_workspace), PocketIcons.FolderOpen, { workspace = true; repo.browseWorkspace() }, enabled = s.online && s.supports("workspace/list"), selected = workspace)
            val entries = if (workspace) s.advanced.workspaceEntries else s.advanced.references
            LazyColumn(Modifier.heightIn(max = 420.dp), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (entries.isEmpty()) item { PText(stringResource(R.string.v02_no_results), Modifier.padding(horizontal = 12.dp, vertical = 20.dp), color = Pocket.colors.inkMuted) }
                items(entries) { entry ->
                    val directory = entry.string("kind") == "directory"
                    val supported = s.online && (!workspace || s.supports(if (directory) "workspace/list" else "workspace/read"))
                    PocketActionRow(
                        title = entry.string("name", entry.string("path").substringAfterLast('/')),
                        icon = if (directory) PocketIcons.FolderOpen else if (entry.string("type") == "skill") PocketIcons.AutoAwesome else PocketIcons.Description,
                        subtitle = entry.string("path"),
                        enabled = supported,
                        onClick = {
                            if (workspace) { if (directory) repo.browseWorkspace(entry.string("path")) else { repo.readSource(entry.string("path")); onDismiss() } }
                            else { repo.insertReference(entry); onDismiss() }
                        },
                        trailing = {
                            if (!workspace && entry.string("type") == "file") ActionIcon(PocketIcons.Code, stringResource(R.string.v02_open_source), s.online && s.supports("workspace/read")) { repo.readSource(entry.string("path")); onDismiss() }
                            else if (directory) PIcon(PocketIcons.ChevronRight, null, Modifier.size(18.dp))
                        },
                    )
                }
            }
        }
    }
}
