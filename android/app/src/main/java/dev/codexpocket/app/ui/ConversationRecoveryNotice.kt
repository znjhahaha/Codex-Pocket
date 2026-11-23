package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*

@Composable
internal fun ConversationRecoveryNotice(s: PocketState, onRecover: () -> Unit) {
    val chat=s.selected ?: return
    val recovery=s.conversationRecovery[chat.key] ?: ConversationRecovery("absent")
    val busy=s.actionStatus["access:${chat.key.encoded}"]?.loading==true
    val error=s.actionStatus["access:${chat.key.encoded}"]?.error
    val title=when { !s.online -> "电脑已离线"; busy -> "正在检查连接"; error!=null -> "连接未完成"; else -> recovery.title }
    val message=when {
        !s.online -> "连接电脑后可继续恢复。"
        busy -> "正在核对原会话的连接状态。"
        error!=null -> error
        recovery.phase=="absent"&&!s.conversationCached -> "已找到会话，连接后可继续发送。"
        else -> recovery.message
    }
    PocketGlassSurface(Modifier.fillMaxWidth().padding(vertical=6.dp).testTag("conversation-recovery"),dense=true) {
        Column(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ActivityMotionIcon(ActivityMotion.Connection,s.online&&(busy||recovery.connecting),Modifier.size(22.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    PocketDestinationTitle(title,style=Pocket.type.label)
                    PText(message,Modifier.heightIn(min=36.dp),style=Pocket.type.bodySmall,
                        color=if(error!=null&&!busy) Pocket.colors.danger else Pocket.colors.inkMuted)
                }
                if ("threads/takeover" in s.capabilities) PocketTextButton(onRecover,
                    modifier=Modifier.widthIn(min=86.dp).testTag("recovery-action"),enabled=s.online&&!busy) {
                    PocketDestinationTitle(if(busy) "检查中" else recovery.action,style=Pocket.type.label)
                }
            }
        }
    }
}
