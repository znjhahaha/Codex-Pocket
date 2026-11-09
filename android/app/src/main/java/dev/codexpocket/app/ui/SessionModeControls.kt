package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import kotlinx.coroutines.delay

@Composable
internal fun SessionModeChoices(mode: SessionMode?, enabled: Boolean, onChoose: (SessionMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionMode.entries.forEach { value ->
            PocketButton(value.label, { onChoose(value) }, enabled = enabled, secondary = mode != value,
                modifier = Modifier.weight(1f).testTag("session-mode:${value.wire}"))
        }
    }
}

@Composable
internal fun ObserveThreadHandoff(s: PocketState, repo: PocketRepository) {
    val chat = s.selected ?: return
    val access = s.accessFor()
    LaunchedEffect(chat.key, access?.phase, s.online) {
        while (s.online && access?.phase?.inProgress == true) {
            delay(1500)
            repo.refreshThreadAccess().join()
        }
    }
}

@Composable
internal fun ThreadOwnership(s: PocketState, repo: PocketRepository) {
    val chat = s.selected ?: return
    val access = s.accessFor()
    val status = s.actionStatus["access:${chat.key.encoded}"]
    PocketGlassSurface(Modifier.fillMaxWidth().testTag("thread-ownership"), dense = true) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val owner = access?.ownerRuntimeId?.let { id -> s.runtimes.firstOrNull { it.id == id } }
            Text(if (!s.supports(BrokerCapability.AccessRead)) "当前电脑助手不支持会话接手" else if (access == null) "会话归属尚未同步 · 来源 ${chat.source}" else
                "${access.mode.label} · ${access.phase.label} · 归属 ${owner?.source ?: access.ownerRuntimeId ?: "等待确认"}",
                style = MaterialTheme.typography.labelMedium)
            if (!s.supports(BrokerCapability.AccessRead)) Text("更新电脑助手，并在当前任务结束后重新打开 Codex。已有会话仍可阅读。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (access != null) Text("历史来源 ${chat.source.ifBlank { "未知" }} · 当前来源 ${access.source.ifBlank { "未知" }}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.activeRuntime?.bridgeUpdatePending == true) Text("电脑桥接组件等待更新 · 请在电脑上完成更新", style = MaterialTheme.typography.labelSmall)
            SessionModeChoices(access?.mode, s.online && s.supports(BrokerCapability.AccessUpdate) && status?.loading != true &&
                access?.phase?.inProgress != true && !s.sendBusy && !s.uncertainSend && !chat.isSubagent && !chat.archived, repo::setThreadSessionMode)
            if (access?.phase?.inProgress == true && s.supports(BrokerCapability.AccessCancel))
                PocketTextButton({ repo.cancelThreadHandoff() }, enabled = s.online && status?.loading != true) { Text("取消交接") }
            if (access?.reason != null) Text(PocketFailure.fromCode(access.reason)?.label ?: access.phase.label,
                style = MaterialTheme.typography.bodySmall)
            if (status?.loading == true) PocketProgressStatus("正在确认会话归属")
            status?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (access == null || access.phase in setOf(AccessPhase.Blocked, AccessPhase.Failed, AccessPhase.Cancelled) || chat.key !in s.accessFresh)
                PocketTextButton({ repo.refreshThreadAccess() }, enabled = s.online && s.supports(BrokerCapability.AccessRead) && status?.loading != true) { Text("刷新归属") }
        }
    }
}
