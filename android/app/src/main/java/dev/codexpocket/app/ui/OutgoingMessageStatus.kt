package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.OutgoingUserMessage
import dev.codexpocket.app.ui.kit.*

/** Delivery belongs to this bubble. A native echo is stronger evidence than a late ACK. */
@Composable
internal fun OutgoingMessageStatus(message: OutgoingUserMessage, online: Boolean, onRetry: () -> Unit, onCheck: () -> Unit) {
    if (message.nativeKey != null || message.phase == "reconciled") return
    var details by remember(message.clientId) { mutableStateOf(false) }
    when (message.phase) {
        "preparing", "submitted" -> PDots(Modifier.padding(horizontal = 10.dp).testTag("message-sending:${message.clientId}")
            .semantics { contentDescription = "正在发送" }, dot = 4.dp)
        "rejected" -> PIconButton(PocketIcons.ErrorOutline, "消息发送失败，查看或重试", { details = true },
            Modifier.testTag("message-failed:${message.clientId}"), tint = Pocket.colors.danger)
        "unknown" -> PTextAction("待核对", onCheck, Modifier.testTag("message-check:${message.clientId}"), enabled = online)
        "queued" -> PText("已排队", Modifier.padding(horizontal = 6.dp), style = Pocket.type.caption, color = Pocket.colors.inkMuted)
        "accepted" -> PText("已提交", Modifier.padding(horizontal = 6.dp), style = Pocket.type.caption, color = Pocket.colors.inkMuted)
    }
    if (details && message.phase == "rejected") PDialog("消息未发送", { details = false }, "重新发送",
        { details = false; onRetry() }, text = "电脑未接收这条消息。内容已保留，可以重新发送。", confirmEnabled = online,
        dismissLabel = "关闭")
}
