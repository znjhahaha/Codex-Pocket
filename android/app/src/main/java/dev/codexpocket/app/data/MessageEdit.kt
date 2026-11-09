package dev.codexpocket.app.data

import kotlinx.serialization.json.*

enum class MessageEditPhase { Preparing, Ready, Reverting, Reverted, Sending, UnknownRevert, UnknownSend, Failed, Unavailable }

/** Independent editor: the regular composer draft and attachments are never swapped or cleared. */
data class MessageEditState(
    val key: ThreadKey, val runtimeId: String, val turnId: String, val itemId: String,
    val text: String, val originalText: String, val phase: MessageEditPhase = MessageEditPhase.Preparing,
    val version: String = "", val token: String = "", val reason: String? = null,
    val retainedItems: Int = 0, val reverted: Boolean = false,
    val commandId: String? = null,
) {
    val busy get() = phase in setOf(MessageEditPhase.Preparing, MessageEditPhase.Reverting, MessageEditPhase.Sending)
    val uncertain get() = phase in setOf(MessageEditPhase.UnknownRevert, MessageEditPhase.UnknownSend)
    val canSubmit get() = phase in setOf(MessageEditPhase.Ready, MessageEditPhase.Reverted, MessageEditPhase.Failed) &&
        text.isNotBlank() && version.isNotBlank() && token.isNotBlank()
}

fun editFailureMessage(code: String): String = when (code) {
    "thread_not_idle_or_identity_mismatch", "history_incomplete_or_busy" -> "会话仍在运行或历史未读完，请稍后重试"
    "only_first_user_message_is_editable", "message_not_found" -> "原生协议只能撤回回合首条用户消息"
    "retained_image_unavailable" -> "原附件已无法读取，暂不能安全重发此消息"
    "native_revert_unsupported" -> "当前运行时不支持撤回，请更新电脑端"
    "input_preservation_unsupported" -> "此消息包含当前无法保留的引用类型"
    "turn_not_found" -> "这条消息已被修改或不在当前历史中，请重新加载"
    else -> "编辑准备未完成，请重新读取此消息"
}

/** A native revert addresses a whole turn. Later user interjections are not independently editable. */
fun messageEditUnavailable(state: PocketState, item: TimelineItem): String? {
    val chat = state.selected ?: return "请先打开会话"
    if (chat.isSubagent) return "子代理会话只读"
    if (chat.conflict || chat.archived) return "当前会话不可编辑"
    if (item.type != "userMessage") return "只能编辑用户消息"
    if (state.timeline.items.values.firstOrNull { it.turnId == item.turnId && it.type == "userMessage" }?.key != item.key)
        return "此消息是回合内插话，原生协议不能独立撤回"
    if (state.timeline.activeTurn != null) return "请先停止当前任务再编辑"
    if (!state.online) return "连接电脑后可编辑"
    if (state.uncertainSend || state.sendBusy) return "先核对上一操作的结果"
    if (!state.supports("thread/edit/prepare")) return "需要更新电脑助手或当前运行时不支持编辑"
    return null
}
