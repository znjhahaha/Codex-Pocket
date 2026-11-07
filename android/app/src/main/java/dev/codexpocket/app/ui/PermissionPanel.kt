package dev.codexpocket.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.PermissionPreset
import dev.codexpocket.app.data.PermissionState

@Composable
internal fun PermissionPanel(state: PermissionState, online: Boolean, running: Boolean,
    onDismiss: () -> Unit, onRefresh: () -> Unit, onSelect: (String) -> Unit, anchor: Rect? = null) {
    var requestedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) { if (!state.updating) requestedId = null }
    val updating = state.updating || requestedId != null
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(online, state.status) {
        if (online && state.status == "initializing") {
            kotlinx.coroutines.delay(1_000)
            refresh()
        }
    }
    val busy = state.loading || updating
    val writable = online && !busy && state.status == "ready" && state.revision != null && state.error == null
    val c = MaterialTheme.colorScheme
    val notice = when {
        !online -> "离线 · 连接电脑后可选择权限"
        busy -> null
        state.error != null -> state.error.take(300)
        state.status == "initializing" -> "正在读取原生能力，请稍候"
        state.status == "update_required" -> "电脑仍在使用旧桥接，重启 Codex 后启用"
        state.status == "unsupported" -> "当前运行时不支持会话权限选择"
        state.status in setOf("error", "failed") -> "读取会话权限失败，请重试"
        state.status != "ready" -> "会话权限未同步"
        state.presets.isEmpty() -> "当前运行时未提供可选权限"
        state.revision == null -> "权限状态需要重新读取"
        else -> null
    }
    PocketAnchoredMenu("会话权限", onDismiss, anchor, widthDp = 288f, alignStart = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("permission-panel").padding(2.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("会话权限", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant,
                        modifier = Modifier.semantics { heading() })
                    Text(if (updating) "等待电脑确认" else state.label, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(48.dp).testTag("permission-refresh")
                    .clickable(enabled = online && !busy && state.status != "unsupported", role = Role.Button, onClick = onRefresh)
                    .semantics { contentDescription = "重新读取" }, contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(PocketIcons.Refresh, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
                }
            }
            if (notice != null && notice != state.label) Text(notice, Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                .testTag("permission-status").semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelSmall, color = if (state.error != null) c.error else c.onSurfaceVariant)
            if (online && busy) Text(if (updating) "正在更新会话权限 · 等待电脑确认" else "正在读取会话权限",
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag(if (updating) "permission-updating" else "permission-loading"),
                style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                state.presets.forEach { preset -> key(preset.id) {
                    val selected = preset.id == state.currentPresetId
                    PermissionPresetRow(preset, selected, writable && preset.enabled && !selected) {
                        if (writable && preset.enabled && !selected && requestedId == null) {
                            requestedId = preset.id
                            onSelect(preset.id)
                        }
                    }
                } }
            }
            Text(if (running) "后续回合生效 · 当前审批不变" else "仅影响当前会话",
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionPresetRow(preset: PermissionPreset, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val full = preset.id == "full"
    val accent = if (full) Color(0xFFE76E2F) else c.onSurface
    val foreground = if (enabled || selected) accent else c.onSurfaceVariant
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, pocketPressSpring(), label = "permission-press")
    var showReason by remember(preset.id, preset.reason) { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp)).background(when {
                pressed -> c.surfaceContainerHighest.copy(alpha = .8f)
                selected -> if (full) accent.copy(alpha = .08f) else c.surfaceContainerHigh.copy(alpha = .6f)
                else -> Color.Transparent
            }).testTag("permission-preset:${preset.id}")
            .selectable(selected, enabled = enabled, role = Role.RadioButton, interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { stateDescription = if (selected) "已选择" else if (!enabled) "不可选择" else "可选择" }
            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(when (preset.id) {
                "request" -> PocketIcons.Lock
                "auto", "full" -> PocketIcons.ShieldCheck
                else -> PocketIcons.Settings
            }, null, Modifier.size(18.dp), tint = foreground)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(preset.label, style = MaterialTheme.typography.bodyMedium, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (preset.description.isNotBlank()) Text(preset.description, style = MaterialTheme.typography.labelSmall,
                    color = if (full && (enabled || selected)) accent else c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Icon(PocketIcons.Check, "当前权限", Modifier.size(16.dp), tint = accent)
            else if (!preset.enabled) Box(Modifier.size(40.dp).clickable { showReason = !showReason }
                .semantics { contentDescription = "查看不可用原因：${preset.label}" }, contentAlignment = Alignment.Center) {
                Icon(PocketIcons.Info, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
            }
        }
        if (showReason) Text(preset.reason?.takeIf(String::isNotBlank) ?: "当前运行时不允许此权限",
            Modifier.padding(start = 36.dp, end = 10.dp, bottom = 8.dp).testTag("permission-reason:${preset.id}"),
            style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
    }
}
