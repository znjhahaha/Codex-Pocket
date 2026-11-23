package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    PSurface(modifier.fillMaxWidth().navigationBarsPadding().imePadding().testTag("message-edit-dock"), shape = RoundedCornerShape(22.dp),
        color = Pocket.colors.surface, border = BorderStroke(.5.dp, Pocket.colors.line)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PIcon(PocketIcons.Edit, null, Modifier.size(16.dp))
                PText("编辑消息", Modifier.weight(1f).padding(start = 8.dp), style = Pocket.type.titleSmall)
                ActionIcon(PocketIcons.Close, "取消编辑并恢复草稿", !edit.busy, onCancel)
            }
            PText(if (edit.reverted) "原消息及后续会话已撤回，编辑稿已保留。" else "重发会撤回此回合及后续会话，不会撤销文件修改。",
                style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
            PTextField(edit.text, onText, Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 180.dp).testTag("message-edit-text"),
                enabled = !edit.busy && !edit.uncertain, minLines = 2, maxLines = 6)
            if (edit.retainedItems > 0) PText("保留 ${edit.retainedItems} 个原附件或引用", style = Pocket.type.caption)
            edit.reason?.let { PText(it, style = Pocket.type.bodySmall, color = Pocket.colors.danger) }
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
