package dev.codexpocket.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*

@Composable
internal fun MessageEditDock(edit: MessageEditState, online: Boolean, running: Boolean,
    onText: (String) -> Unit, onSubmit: () -> Unit, onCancel: () -> Unit, onCheck: () -> Unit,
    onPrepare: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().navigationBarsPadding().imePadding().testTag("message-edit-dock"), shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PocketIcons.Edit, null, Modifier.size(16.dp))
                Text("编辑消息", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
                ActionIcon(PocketIcons.Close, "取消编辑并恢复草稿", !edit.busy, onCancel)
            }
            Text(if (edit.reverted) "原消息及后续会话已撤回，编辑稿已保留。" else "重发会撤回此回合及后续会话，不会撤销文件修改。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextField(edit.text, onText, Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 180.dp).testTag("message-edit-text"),
                enabled = !edit.busy && !edit.uncertain, minLines = 2, maxLines = 6,
                shape = RoundedCornerShape(14.dp), colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
            if (edit.retainedItems > 0) Text("保留 ${edit.retainedItems} 个原附件或引用", style = MaterialTheme.typography.labelSmall)
            edit.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            when {
                edit.busy -> PocketProgressStatus(when(edit.phase) {
                    MessageEditPhase.Preparing -> "正在核对原消息"
                    MessageEditPhase.Reverting -> "正在撤回会话"
                    else -> "正在提交修改"
                })
                edit.uncertain -> PocketButton("核对操作结果", onCheck, enabled = online)
                running -> PocketButton("先停止当前任务", onStop, enabled = online)
                edit.phase == MessageEditPhase.Unavailable || edit.token.isBlank() -> PocketButton("重新读取消息", onPrepare, enabled = online)
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PocketButton(if (edit.reverted) "发送修改后的消息" else "撤回并重新发送", onSubmit, enabled = online && edit.canSubmit)
                }
            }
        }
    }
}
