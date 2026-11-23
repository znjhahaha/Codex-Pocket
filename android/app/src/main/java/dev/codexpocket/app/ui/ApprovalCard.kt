package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.codexpocket.app.data.*
import dev.codexpocket.app.R
import kotlinx.serialization.json.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApprovalCard(a: PendingApproval, online: Boolean, onRespond: (PendingApproval, JsonObject) -> Unit) {
    if (ConversationPresentation.hookType(a.method)) return
    if (a.method == "item/tool/requestUserInput") {
        BlockingQuestionApproval(a, online, onRespond)
        return
    }
    val context = LocalContext.current
    val c = Pocket.colors
    var sent by remember(a.key) { mutableStateOf(false) }
    var details by rememberSaveable(a.key) { mutableStateOf(false) }
    LaunchedEffect(a.submitted, online) { if (!a.submitted && online) sent = false }
    val enabled = online && !a.submitted && !sent
    val supported = a.method in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval")
    val command = a.params.string("command")
    val paths = a.params.array("changes").map { it.asObject().string("path") }.filter(String::isNotBlank)
    val haptics = rememberPocketHaptics()
    fun respond(decision: String) {
        if (!enabled || sent) return
        sent = true
        haptics.perform(if (decision == "accept" || decision == "acceptForSession") PocketHaptic.Confirm else PocketHaptic.Reject)
        onRespond(a, buildJsonObject { put("decision", decision) })
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("approval-card"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PIcon(PocketIcons.ShieldCheck, null, Modifier.size(18.dp), tint = c.inkMuted)
            PText(if (a.method.contains("commandExecution")) "允许执行此命令？" else if (supported) "允许这些文件更改？" else context.getString(R.string.desktop_required),
                Modifier.weight(1f), style = Pocket.type.label)
            if (a.submitted || sent) PText("等待确认", style = Pocket.type.caption, color = c.inkMuted)
        }
        if (!online) PText("待重新同步 · 确认请求仍有效后可审批", style = Pocket.type.caption, color = c.inkMuted)
        if (supported) {
            if (command.isNotBlank()) PText(command.replace('\n', ' '), fontFamily = FontFamily.Monospace,
                style = Pocket.type.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (paths.isNotEmpty()) PText(paths.joinToString("、") { compactFileName(it) }, style = Pocket.type.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            a.params.string("reason").takeIf(String::isNotBlank)?.let {
                PText(it, style = Pocket.type.bodySmall, color = c.inkMuted, maxLines = if (details) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
            a.params.string("cwd").takeIf(String::isNotBlank)?.let {
                PText("工作目录 · $it", style = Pocket.type.caption, color = c.inkMuted,
                    maxLines = if (details) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.heightIn(min = 40.dp).clickable { details = !details }.testTag("approval-details-toggle"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PText(if (details) "收起操作详情" else "查看完整操作与权限", style = Pocket.type.caption, color = c.inkMuted)
                ActivityExpansionIcon(details)
            }
            if (details) Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                .background(c.sunken, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (command.isNotBlank()) SelectionContainer {
                    PText(command, Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace,
                        style = Pocket.type.bodySmall, softWrap = true)
                }
                paths.forEach { PText(it, fontFamily = FontFamily.Monospace, style = Pocket.type.bodySmall) }
                PText("仅批准本次请求公布的操作；不会更改会话权限预设。", style = Pocket.type.caption, color = c.inkMuted)
            }
            val advertised = a.params["availableDecisions"] as? JsonArray
            fun allowed(decision: String) = if (advertised == null) decision in setOf("accept", "decline", "cancel") else JsonPrimitive(decision) in advertised
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (allowed("accept")) ApprovalDecision("允许一次", enabled, primary = true) { respond("accept") }
                if (allowed("acceptForSession")) ApprovalDecision("本会话允许", enabled) { respond("acceptForSession") }
                if (allowed("decline")) ApprovalDecision("拒绝", enabled) { respond("decline") }
                if (allowed("cancel")) ApprovalDecision("取消任务", enabled, destructive = true) { respond("cancel") }
            }
        }
    }
}

@Composable
private fun ApprovalDecision(label: String, enabled: Boolean, primary: Boolean = false, destructive: Boolean = false, onClick: () -> Unit) {
    val c = Pocket.colors
    Box(Modifier.heightIn(min = 48.dp).clip(PocketCapsule)
        .background(if (primary) c.ink.copy(alpha = if (enabled) 1f else .35f) else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        PText(label, style = Pocket.type.label,
            color = if (primary) c.surface else if (destructive && enabled) c.danger else if (enabled) c.ink else c.inkMuted)
    }
}

@Composable
private fun BlockingQuestionApproval(a: PendingApproval, online: Boolean, onRespond: (PendingApproval, JsonObject) -> Unit) {
    val batch = QuestionBatches.blocking(a.key, a.params)
    val session = remember(a.key) { QuestionSession(batch) }
    DisposableEffect(session) { onDispose { session.clear() } }
    session.update(batch)
    session.observe(a.submitted, submitting = false, pendingConfirmed = online)
    PSurface(color = Pocket.colors.surface, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Pocket.colors.line.copy(alpha = .7f))) {
        QuestionCard(batch, session, if (!online) QuestionPhase.Offline else session.phase,
            onSubmit = { answers -> QuestionBatches.blockingResponse(batch, answers)?.let { onRespond(a, it) } },
            modifier = Modifier.padding(16.dp))
    }
}
