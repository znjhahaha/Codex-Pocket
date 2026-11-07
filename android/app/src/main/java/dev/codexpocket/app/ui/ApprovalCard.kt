package dev.codexpocket.app.ui

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    val c = MaterialTheme.colorScheme
    var sent by remember(a.key) { mutableStateOf(false) }
    var details by rememberSaveable(a.key) { mutableStateOf(false) }
    LaunchedEffect(a.submitted, online) { if (!a.submitted && online) sent = false }
    val enabled = online && !a.submitted && !sent
    val supported = a.method in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval")
    val command = a.params.string("command")
    val paths = a.params.array("changes").map { it.asObject().string("path") }.filter(String::isNotBlank)
    fun respond(decision: String) {
        if (!enabled || sent) return
        sent = true
        onRespond(a, buildJsonObject { put("decision", decision) })
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("approval-card"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(PocketIcons.ShieldCheck, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
            Text(if (a.method.contains("commandExecution")) "允许执行此命令？" else if (supported) "允许这些文件更改？" else context.getString(R.string.desktop_required),
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (a.submitted || sent) Text("等待确认", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
        if (!online) Text("待重新同步 · 确认请求仍有效后可审批", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        if (supported) {
            if (command.isNotBlank()) Text(command.replace('\n', ' '), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (paths.isNotEmpty()) Text(paths.joinToString("、") { compactFileName(it) }, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            a.params.string("reason").takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = if (details) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
            a.params.string("cwd").takeIf(String::isNotBlank)?.let {
                Text("工作目录 · $it", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
                    maxLines = if (details) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.heightIn(min = 40.dp).clickable { details = !details }.testTag("approval-details-toggle"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (details) "收起操作详情" else "查看完整操作与权限", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                ActivityExpansionIcon(details)
            }
            if (details) Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                .background(c.surfaceContainerLow, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (command.isNotBlank()) SelectionContainer {
                    Text(command, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, softWrap = false)
                }
                paths.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text("仅批准本次请求公布的操作；不会更改会话权限预设。", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
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
    val c = MaterialTheme.colorScheme
    Box(Modifier.heightIn(min = 48.dp).clip(PocketCapsule)
        .background(if (primary) c.onSurface.copy(alpha = if (enabled) 1f else .35f) else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = if (primary) c.surface else if (destructive && enabled) c.error else if (enabled) c.onSurface else c.onSurfaceVariant)
    }
}

@Composable
private fun BlockingQuestionApproval(a: PendingApproval, online: Boolean, onRespond: (PendingApproval, JsonObject) -> Unit) {
    val batch = QuestionBatches.blocking(a.key, a.params)
    val session = remember(a.key) { QuestionSession(batch) }
    DisposableEffect(session) { onDispose { session.clear() } }
    session.update(batch)
    session.observe(a.submitted, submitting = false, pendingConfirmed = online)
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) {
        QuestionCard(batch, session, if (!online) QuestionPhase.Offline else session.phase,
            onSubmit = { answers -> QuestionBatches.blockingResponse(batch, answers)?.let { onRespond(a, it) } },
            modifier = Modifier.padding(16.dp))
    }
}
