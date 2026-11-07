package dev.codexpocket.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.PocketState

internal fun actionUnavailableReason(s: PocketState, method: String, idle: Boolean = false): String? = when {
    !s.online -> "连接电脑后可用"
    s.activeRuntime?.online != true -> "等待 Codex 就绪"
    !s.supports(method) -> "当前 Codex 版本未提供此能力"
    s.selected?.conflict == true -> "先在设备页解决会话归属冲突"
    s.selected?.archived == true -> "恢复此会话后可用"
    s.uncertainSend -> "先确认上一条消息的发送结果"
    s.busy || s.sendBusy -> "当前操作完成后可用"
    idle && s.timeline.activeTurn != null -> "本轮回复结束后可用"
    else -> null
}

private data class ConversationAction(val title: String, val icon: ImageVector, val reason: String?, val run: () -> Unit)

/**
 * Toolbar dropdown for the open chat, in the manner of a UIMenu from a navigation bar button:
 * a row of quick tiles, then one line per action. Unavailable actions fold into one group
 * that still explains each reason.
 */
@Composable
internal fun ConversationActionsMenu(s: PocketState, anchor: Rect?, onDismiss: () -> Unit, onFiles: (() -> Unit)?,
    onRename: () -> Unit, onFork: () -> Unit, onSideChat: () -> Unit, onGoal: () -> Unit, onUsage: () -> Unit,
    onCompact: () -> Unit, onFast: () -> Unit, onResume: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit) {
    PocketAnchoredMenu("会话操作", onDismiss, anchor, widthDp = 272f, below = true) {
        ConversationActionsList(s, onDismiss, onFiles, onRename, onFork, onSideChat, onGoal, onUsage, onCompact, onFast, onResume, onPin, onArchive)
    }
}

@Composable
internal fun ConversationActionsList(s: PocketState, onDismiss: () -> Unit, onFiles: (() -> Unit)?,
    onRename: () -> Unit, onFork: () -> Unit, onSideChat: () -> Unit, onGoal: () -> Unit, onUsage: () -> Unit,
    onCompact: () -> Unit, onFast: () -> Unit, onResume: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit) {
    var showUnavailable by remember { mutableStateOf(false) }
    fun done(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    val c = MaterialTheme.colorScheme
    val actions = listOf(
        ConversationAction("重命名", PocketIcons.Edit, actionUnavailableReason(s, "thread/name/set"), onRename),
        ConversationAction("分支聊天", PocketIcons.CallSplit, actionUnavailableReason(s, "thread/fork"), onFork),
        ConversationAction("旁支讨论", PocketIcons.ChatBubbleOutline, actionUnavailableReason(s, "thread/fork"), onSideChat),
        ConversationAction("目标", PocketIcons.Checklist, actionUnavailableReason(s, "thread/goal/set"), onGoal),
        ConversationAction("压缩上下文", PocketIcons.FoldVertical, actionUnavailableReason(s, "thread/compact/start", true), onCompact),
        ConversationAction("在电脑上继续", PocketIcons.Computer, actionUnavailableReason(s, "thread/resume"), onResume),
    )
    val fastReason = actionUnavailableReason(s, "thread/settings/update")
    val unavailable = actions.filter { it.reason != null } +
        listOfNotNull(fastReason?.let { ConversationAction("快速模式", PocketIcons.AutoAwesome, it) {} })
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuickTile(if (s.selected?.pinned == true) "取消置顶" else "置顶", PocketIcons.PushPin, done(onPin), Modifier.weight(1f))
            QuickTile("用量", PocketIcons.Info, done(onUsage), Modifier.weight(1f))
            QuickTile("文件", PocketIcons.FolderOpen, done { onFiles?.invoke() }, Modifier.weight(1f), enabled = onFiles != null)
        }
        val ready = actions.filter { it.reason == null }
        ready.forEach { action -> PocketMenuItem(action.title, action.icon, done(action.run)) }
        // The switch flips in place; the menu stays open so the new state is visible.
        if (fastReason == null) PocketMenuItem("快速模式", PocketIcons.AutoAwesome, onFast,
            trailing = { PocketToggle(s.advanced.fastMode, null, Modifier.size(46.dp, 32.dp)) })
        // The tiles already separate themselves; a divider only belongs under real rows.
        if (ready.isNotEmpty() || fastReason == null) MenuDivider()
        val archived = s.selected?.archived == true
        val archiveMethod = if (archived) "thread/unarchive" else "thread/archive"
        val archiveReason = when {
            !s.online -> "连接电脑后可用"
            !s.supports(archiveMethod) -> "当前 Codex 版本未提供此能力"
            s.selected?.conflict != false -> "先在设备页解决会话归属冲突"
            s.uncertainSend -> "先确认上一条消息的发送结果"
            s.busy || s.sendBusy -> "当前操作完成后可用"
            else -> null
        }
        PocketMenuItem(if (archived) "恢复会话" else "归档会话", PocketIcons.Inventory2, done(onArchive), enabled = archiveReason == null, hint = archiveReason)
        if (unavailable.isNotEmpty()) {
            MenuDivider()
            PocketMenuItem("暂不可用 · ${unavailable.size}", null, { showUnavailable = !showUnavailable }, trailing = {
                val turn by animateFloatAsState(if (showUnavailable) 180f else 0f, pocketPressSpring(), label = "unavailable-chevron")
                Icon(PocketIcons.ExpandMore, null, Modifier.size(18.dp).graphicsLayer { rotationZ = turn }, tint = c.onSurfaceVariant)
            })
            AnimatedVisibility(showUnavailable, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column { unavailable.forEach { action -> PocketMenuItem(action.title, action.icon, {}, enabled = false, hint = action.reason) } }
            }
        }
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
}

/** iOS context-menu style quick action: icon over a short label, oval tile. */
@Composable
private fun QuickTile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .92f else 1f, pocketPressSpring(), label = "quick-tile")
    Column(modifier.heightIn(min = 60.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(PocketOval)
        .background(if (pressed) c.surfaceContainerHighest else c.surfaceContainerHigh.copy(alpha = .75f))
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        val tint = if (enabled) c.onSurface else c.onSurfaceVariant.copy(alpha = .5f)
        Icon(icon, null, Modifier.size(19.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
