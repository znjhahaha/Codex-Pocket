package dev.codexpocket.app.ui

import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * The main composer's state. The draft lives in the `assistant-drafts-v2` preferences under the composer's scope,
 * so it survives navigation and restarts. Each send is moved into the durable outbox before the field is cleared.
 */
@Stable
internal class AssistantComposer(val key: String, val binding: AssistantComposerBinding, val attachments: AssistantAttachmentDraft,
    private val preferences: SharedPreferences, draftState: MutableState<String>, sentIdState: MutableState<String?>,
    sentTextState: MutableState<String?>, private val projectState: MutableState<String?>, private val targetState: MutableState<String?>) {
    var draft by draftState; private set
    /** Legacy single-send marker, retained only for upgrade reconciliation. */
    var sentId by sentIdState; private set
    private var sentText by sentTextState
    /** Selection key of the project the next message is about; null is the assistant's own workspace. */
    var project: String?
        get() = projectState.value
        set(value) { projectState.value = value; preferences.edit().putString("project:$key", value).apply() }
    /** The task the next message is addressed to, chosen with `@`. */
    var target: String?
        get() = targetState.value
        set(value) { targetState.value = value; preferences.edit().putString("target:$key", value).apply() }

    fun edit(text: String) { draft = text; preferences.edit().putString(key, text).apply() }
    val outbox = AssistantOutbox(preferences)
    fun sent(action: AssistantAction) {
        outbox.enqueue(action, key, attachments.key)
        draft = ""; target = null; sentId = null; sentText = null; attachments.releasedToOutbox()
    }
    /** Legacy retained composers may still have a single pending send from an earlier installation. */
    fun settle(status: String) {
        if (status in setOf("accepted", "rejected")) { sentId = null; sentText = null }
    }
    fun adoptLegacy(original: AssistantComposerBinding) {
        val id=attachments.pendingId ?: return
        outbox.adoptLegacy(original,id,attachments.sentText.orEmpty(),attachments.retainedFiles(),key,attachments.key)
        draft=preferences.getString(key,"").orEmpty();attachments.reload();sentId=null;sentText=null
    }
    fun newGoal() { sentId = null; sentText = null; draft = ""; target = null; attachments.newGoal(); preferences.edit().putString(key, "").apply() }
}

@Composable
internal fun rememberAssistantComposer(state: AssistantUiState): AssistantComposer {
    val snapshot = state.snapshot ?: AssistantSnapshot()
    val binding = snapshot.composerBinding(state.computerId)
    val preferences = LocalContext.current.getSharedPreferences("assistant-drafts-v2", 0)
    val preparationKey = "preparing:${state.computerId}:${snapshot.scopeId.orEmpty()}"
    val mainConversation = snapshot.conversationId == null || snapshot.conversationId.startsWith("main:")
    val key = if (snapshot.scopeId != null) "main:${binding.scopeKey}" else if (snapshot.assistantId == null && snapshot.enabled != true) preparationKey else "main:${binding.scopeKey}"
    val draft = rememberSaveable(key) {
        val legacyKey = "main:${snapshot.copy(conversationId = null).composerBinding(state.computerId).scopeKey}"
        val legacy = if (snapshot.conversationId?.startsWith("main:") == true && legacyKey != key) preferences.getString(legacyKey, null) else null
        val text = preferences.getString(key, null) ?: legacy ?: if (mainConversation) preferences.getString(preparationKey, "").orEmpty() else ""
        if (legacy != null && !preferences.contains(key)) preferences.edit().putString(key, text).remove(legacyKey).apply()
        mutableStateOf(text)
    }
    val attachments = rememberAssistantAttachments(if (snapshot.conversationId != null && !snapshot.conversationId.startsWith("main:")) "main:${binding.scopeKey}" else "main:${state.computerId}:${snapshot.scopeId ?: snapshot.assistantId ?: "preparing"}")
    LaunchedEffect(attachments.key) {
        if (mainConversation && snapshot.assistantId != null) attachments.adoptPreparingDraft("main:${state.computerId}:preparing")
        if (snapshot.conversationId?.startsWith("main:") == true) attachments.adoptPreparingDraft("main:${state.computerId}:${snapshot.scopeId ?: snapshot.assistantId ?: "preparing"}")
    }
    LaunchedEffect(key) {
        if (mainConversation && key != preparationKey && snapshot.prepared) preferences.edit().putString(key, draft.value).remove(preparationKey).apply()
    }
    val sentId = rememberSaveable(key) { mutableStateOf(attachments.pendingId) }
    val sentText = rememberSaveable(key) { mutableStateOf(attachments.sentText) }
    val project = rememberSaveable(key) { mutableStateOf(preferences.getString("project:$key", null)) }
    val target = rememberSaveable(key) { mutableStateOf(preferences.getString("target:$key", null)) }
    val composer = remember(key, binding, attachments, draft, sentId, sentText, project, target) {
        AssistantComposer(key, binding, attachments, preferences, draft, sentId, sentText, project, target)
    }
    // Keyed by the composer too: two scopes can hold the same pending command, and each settles under its own binding.
    LaunchedEffect(state.sendReceipts, sentId.value, composer) {
        composer.adoptLegacy(snapshot.copy(conversationId=null).composerBinding(state.computerId))
        val receipt = state.sendReceipts.firstOrNull { it.key == sentId.value?.let(binding::receiptKey) }
        if (receipt != null) composer.settle(receipt.status)
    }
    return composer
}

@Composable
internal fun rememberAssistantOutbox(composer: AssistantComposer, receipts: List<AssistantSendReceipt>): List<AssistantOutgoingMessage> {
    val preferences = LocalContext.current.getSharedPreferences("assistant-drafts-v2", 0)
    var entries by remember(composer.key) { mutableStateOf(composer.outbox.messages(composer.binding)) }
    DisposableEffect(composer, preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AssistantOutbox.STORAGE) entries = composer.outbox.messages(composer.binding)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(receipts, composer) { receipts.forEach(composer.outbox::receipt) }
    return entries
}

/** The `@word` being typed at the end of the draft, if any: its start and what follows the sign. */
internal fun assistantMention(draft: String): Pair<Int, String>? =
    Regex("(?:^|\\s)@([^\\s@]*)$").find(draft)?.let { match -> match.groups[1]!!.range.first - 1 to match.groupValues[1] }

@Composable
private fun AssistantDockChip(text: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: ImageVector? = null, removable: Boolean = false, tone: PTone = PTone.Neutral) {
    val c = Pocket.colors
    val ink = if (tone == PTone.Neutral) c.ink else assistantToneInk(tone)
    Box(modifier.heightIn(min = PocketSpace.touch).pocketPressable(enabled, Role.Button, pressedScale = .96f, highlight = false, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Row(Modifier.clip(PocketRadii.capsule).background(if (tone == PTone.Accent) c.accentSoft else c.fillStrong).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (icon != null) PIcon(icon, null, Modifier.size(14.dp), ink)
            PText(text, Modifier.widthIn(max = 168.dp), style = Pocket.type.labelSmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (removable) PIcon(PocketIcons.Close, null, Modifier.size(12.dp), if (enabled) c.inkMuted else c.inkFaint)
        }
    }
}

/**
 * The glass dock: what the message is about (task, project, files), the draft, and send. `@` offers the tasks still
 * open; choosing one addresses the message to it. The frame keeps the dock above the keyboard.
 */
@Composable
internal fun AssistantInputDock(composer: AssistantComposer, state: AssistantUiState, workspaces: List<AssistantWorkspaceChoice>, canAct: Boolean,
    connected: Boolean, act: (() -> AssistantAction) -> Unit, onTools: () -> Unit, toolsOpen: Boolean, modifier: Modifier = Modifier, models: List<NativeModel> = emptyList(), modelPanelVisible: Boolean = true) {
    val snapshot = state.snapshot ?: AssistantSnapshot()
    val c = Pocket.colors
    val project = workspaces.firstOrNull { !it.isSubagent && it.selectionKey == composer.project }
    val open = snapshot.orderedTasks().filter { it.status !in setOf(AssistantTaskStatus.Done, AssistantTaskStatus.Failed) }
    fun title(task: AssistantTask) = snapshot.job(task.id)?.title?.takeIf(String::isNotBlank) ?: task.prompt
    val target = composer.target?.let { id -> snapshot.tasks.firstOrNull { it.id == id } }
    val missingTarget = composer.target != null && target == null
    val mention = if (composer.target == null) assistantMention(composer.draft) else null
    val offered = mention?.let { (_, query) -> open.filter { query.isBlank() || title(it).contains(query, true) }.take(8) }.orEmpty()
    val files = composer.attachments.files
    val canSend = connected && snapshot.enabled != null && !state.loading && state.readError == null && snapshot.errorCode.isNullOrBlank() && snapshot.preparationStatus !in setOf("starting", "preparing", "needs_login", "missing_cli", "config", "missing_config", "recoverable_error") && snapshot.paused != true && (composer.draft.isNotBlank() || files.isNotEmpty()) &&
        "assistant/prepare" !in state.pendingActions && composer.sentId == null && !missingTarget
    val note = when { snapshot.paused == true -> "助理已暂停"; !connected -> "电脑离线 · 草稿已保存"; else -> null }
    val currentReply = snapshot.operations.lastOrNull { it.role == "Coordinator" &&
        (snapshot.conversationId == null || it.id == snapshot.activeCoordinatorOperationId) &&
        it.phase in setOf(AssistantOperationPhase.Running, AssistantOperationPhase.Unknown) }
    val replying = currentReply != null || state.streamingCoordinatorOpId != null
    var stoppingReply by rememberSaveable(composer.binding.scopeKey) { mutableStateOf<String?>(null) }
    val stopping = stoppingReply != null
    LaunchedEffect(stoppingReply, replying, snapshot.operations, snapshot.fresh, state.readError) {
        val stopped = snapshot.operations.any { it.id == stoppingReply &&
            it.phase in setOf(AssistantOperationPhase.Completed, AssistantOperationPhase.Failed, AssistantOperationPhase.Paused) }
        if ((!replying || stopped) && snapshot.fresh != false && state.readError == null) stoppingReply = null
    }
    LaunchedEffect(state.error, state.pendingActions) {
        if (state.error != null && "assistant/v2/conversation/interrupt" !in state.pendingActions) stoppingReply = null
    }
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp).testTag("assistant-bottom-dock"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistantModelControls(state, models, connected, modelPanelVisible, act)
        if (offered.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("assistant-mention-strip"),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            offered.forEach { task -> key(task.id) {
                PSurface(Modifier.testTag("assistant-mention:${task.id}").clip(PocketRadii.capsule)
                    .pocketPressable(role = Role.Button, haptic = PocketHaptic.Tick) { composer.target = task.id; composer.edit(composer.draft.substring(0, mention!!.first)) }
                    .semantics { contentDescription = "发给任务 ${title(task)}" }, PocketRadii.capsule, c.surface, border = BorderStroke(1.dp, c.line)) {
                    Row(Modifier.heightIn(min = PocketSpace.touch).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        PText("@${title(task)}", Modifier.widthIn(max = 200.dp), style = Pocket.type.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } }
        }
        PocketGlassSurface(Modifier.fillMaxWidth().testTag("assistant-composer-glass"), PocketRadii.panel, dense = true) {
            Column(Modifier.fillMaxWidth()) {
                if (composer.target != null || project != null || files.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (composer.target != null) AssistantDockChip(if (missingTarget) "所选任务已不可用" else "@${title(target!!)}",
                        if (missingTarget) "所选任务已不可用，移除" else "不再发给任务 ${title(target!!)}", { composer.target = null },
                        Modifier.testTag("assistant-target-chip"), removable = true, tone = if (missingTarget) PTone.Danger else PTone.Accent)
                    if (project != null) AssistantDockChip(project.label, "关联项目 ${project.label}，更换", onTools, Modifier.testTag("assistant-workspace-project"),
                        icon = PocketIcons.Folder)
                    files.forEach { file -> key(file.id) {
                        AssistantDockChip(file.name, "移除附件 ${file.name}", { composer.attachments.remove(file.id) }, Modifier.testTag("assistant-attachment:${file.id}"),
                            icon = PocketIcons.Description, removable = true)
                    } }
                }
                if (note != null) Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PText(note, Modifier.weight(1f).testTag("assistant-send-status"), style = Pocket.type.caption, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                    PIconButton(PocketIcons.Add, if (toolsOpen) "收起项目与附件" else "选择项目或添加附件", onTools, Modifier.testTag("assistant-tools"),
                        glyph = { PToggleGlyph(MotionGlyphs.PlusClose, toolsOpen, Modifier.size(20.dp)) })
                    PTextField(composer.draft, composer::edit, Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 11.dp).testTag("assistant-workspace-draft"),
                        placeholder = "交办新目标，或继续聊", minLines = 1, maxLines = 5, bare = true)
                    if (replying || stopping) PIconButton(PocketIcons.StopCircle, if (stopping) "正在停止回复，等待确认" else "停止当前回复", {
                        stoppingReply = snapshot.activeCoordinatorOperationId
                        act { assistantConversationInterrupt(requireNotNull(snapshot.conversationId), requireNotNull(snapshot.activeCoordinatorOperationId)) }
                    }, Modifier.testTag("assistant-stop-reply"), enabled = connected && !stopping && snapshot.conversationId != null && snapshot.activeCoordinatorOperationId != null)
                    Box(Modifier.size(PocketSpace.touch).testTag("assistant-workspace-send")
                        .pocketPressable(canSend, Role.Button, pressedScale = .9f, highlight = false, haptic = PocketHaptic.Confirm) {
                            act {
                                val request = AssistantActions.send(snapshot.runtimeId.orEmpty(), composer.draft.ifBlank { "请查看附件。" }, project, taskId = composer.target)
                                val action = request.copy(commandId = UUID.randomUUID().toString(), composerBinding = composer.binding, attachments = files.toList(),
                                    params = JsonObject(request.params + buildJsonObject {
                                        snapshot.scopeId?.let { put("scopeId", it) }
                                        snapshot.conversationId?.let { put("conversationId", it) }
                                    }))
                                composer.sent(action); action
                            }
                        }.semantics { contentDescription = "发送" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(if (canSend) c.solid else c.fillStrong), contentAlignment = Alignment.Center) {
                            PIcon(PocketIcons.ArrowUpward, null, Modifier.size(18.dp), if (canSend) c.onSolid else c.inkFaint)
                        }
                    }
                }
            }
        }
    }
}

/** Opened by `＋`: which project the message is about, and the files that go with it. */
@Composable
internal fun AssistantToolsSheet(composer: AssistantComposer, workspaces: List<AssistantWorkspaceChoice>, canAct: Boolean,
    onProjectSelected: (AssistantWorkspaceChoice?) -> Unit, onDismiss: () -> Unit) {
    val c = Pocket.colors
    val projects = workspaces.filter { !it.isSubagent }.distinctBy { it.selectionKey }
    val project = projects.firstOrNull { it.selectionKey == composer.project }
    PSheet("项目与附件", onDismiss, maxHeightFraction = .62f) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("assistant-tools-panel").padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PText("这条消息关于", Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp).semantics { heading() }, style = Pocket.type.labelSmall, color = c.inkMuted)
            PRow("助理工作区", { composer.project = null; onProjectSelected(null); onDismiss() }, Modifier.testTag("assistant-workspace-project:none"),
                icon = AssistantIcons.Mark, selected = project == null)
            projects.forEach { choice -> key(choice.selectionKey) {
                PRow(choice.label, { composer.project = choice.selectionKey; onProjectSelected(choice); onDismiss() },
                    Modifier.testTag("assistant-workspace-project:${choice.threadId}"), icon = PocketIcons.Folder, selected = choice.selectionKey == composer.project)
            } }
            if (project != null) Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PText(project.permissionStatus, Modifier.weight(1f).testTag("assistant-project-permission-status"), style = Pocket.type.caption, color = c.inkMuted)
                PTextAction("重新核验项目权限", { onProjectSelected(project) }, Modifier.testTag("assistant-project-recheck"), enabled = canAct)
            }
            PDivider(Modifier.padding(vertical = 8.dp, horizontal = 10.dp))
            PText("附件", Modifier.padding(start = 10.dp, bottom = 2.dp).semantics { heading() }, style = Pocket.type.labelSmall, color = c.inkMuted)
            Box(Modifier.padding(horizontal = 6.dp)) { AssistantAttachmentPicker(composer.attachments, "main") }
        }
    }
}
