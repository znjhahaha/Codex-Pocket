package dev.codexpocket.app.data

import dev.codexpocket.wire.WireException
import kotlinx.serialization.json.JsonObject

data class ConversationRecovery(val phase: String, val reason: String? = null, val operationId: String? = null) {
    val pending get() = phase in setOf("queued", "waiting_idle", "waiting_release", "attaching")
    val uncertain get() = reason in setOf("resume_result_unknown", "request_result_unknown")
    val connecting get() = phase in setOf("queued", "attaching") && !uncertain
    val title get() = when {
        uncertain -> "等待确认"
        reason == "thread_busy" || phase == "waiting_idle" -> "等待本轮结束"
        reason == "external_runtime_active" -> "等待会话释放"
        reason in setOf("native_start_unconfirmed", "runtime_unavailable") -> "等待执行服务"
        reason == "native_takeover_unsupported" -> "暂无法接入"
        reason == "runtime_restarted" -> "执行服务已重启"
        reason == "runtime_disconnected" || phase == "disconnected" -> "连接已中断"
        phase == "queued" -> "正在准备连接"
        phase == "attaching" -> "正在连接会话"
        phase == "blocked" -> "连接未完成"
        else -> "会话连接"
    }
    val message get() = when {
        uncertain -> "上次恢复的结果尚未确认。检查状态后继续，避免重复启动。"
        reason == "thread_busy" || phase == "waiting_idle" -> "电脑仍在执行这一轮，结束后会自动恢复控制。"
        reason == "external_runtime_active" -> "原 Codex 仍占用这条会话，释放后会自动连接。"
        reason in setOf("thread_owned_elsewhere", "owner_uncertain") -> "正在确认这条会话由哪个运行端执行，历史仍可阅读。"
        reason == "saved_settings_unavailable" -> "尚未读到原会话的模型或权限设置，请在电脑打开一次这条会话后重试。"
        reason in setOf("native_start_unconfirmed", "runtime_unavailable") -> "Codex 启动尚未确认，稍后会自动检查。"
        reason == "native_takeover_unsupported" -> "当前接入缺少恢复能力，请更新电脑端后检查连接。"
        reason == "runtime_restarted" -> "重新连接后可在原会话继续。"
        reason == "runtime_disconnected" -> "正在核对原执行端，已有消息仍可阅读。"
        phase == "queued" -> "恢复请求已接收，正在检查原会话。"
        phase == "attaching" -> "正在恢复原会话，模型、权限和目录保持不变。"
        phase == "blocked" -> "这次恢复未完成，已有正文和草稿已保留。可重新检查连接。"
        else -> "历史可阅读，执行端尚未连接。"
    }
    val action get() = when {
        uncertain -> "检查结果"
        phase == "disconnected" || phase == "blocked" -> "重新连接"
        pending -> "检查连接"
        else -> "恢复控制"
    }
    companion object {
        fun from(raw: JsonObject) = ConversationRecovery(raw.string("phase"), raw.string("reason").takeIf(String::isNotBlank),
            raw.string("operationId").takeIf(String::isNotBlank))
    }
}

/** Conversation chrome owns the small connection indicator; it never blocks the transcript. */
fun PocketState.automaticConnectionLabel(): String? {
    val chat=selected ?: return null
    if (chat.archived || chat.isSubagent) return null
    if (!online) return if (connectionPhase in setOf("offline","authorization_required")) "离线" else "连接中"
    val recovery=conversationRecovery[chat.key]
    if (uncertainSend || recovery?.uncertain==true) return "确认结果中"
    if (ownershipVerifiedFor(chat) && !chat.conflict) return null
    if (recovery?.phase=="waiting_idle") return "等待本轮结束"
    if (chat.runtimeId.startsWith("history:") && !chat.conflict && recovery?.pending != true &&
        conversationLoadState == ConversationLoadState.Ready && conversationReadSource == "persisted") return "历史会话"
    if (chat.conflict || chat.runtimeId.startsWith("history:") || activeRuntime?.online!=true ||
        supports(BrokerCapability.AccessRead)&&!accessAllowsSending(chat)) return "连接中"
    return null
}

internal fun automaticConnectionFailure(failure: Exception): Boolean =
    ((failure as? PocketOperationException)?.failure ?: (failure as? WireException)?.code?.let(PocketFailure::fromCode)) in
        setOf(PocketFailure.Ownership,PocketFailure.OwnerUncertain,PocketFailure.MultipleOwners,PocketFailure.Handoff,PocketFailure.Runtime)

fun automaticConnectionMessage(message: String): Boolean = message in setOf(PocketFailure.Ownership.label,
    PocketFailure.OwnerUncertain.label,PocketFailure.MultipleOwners.label,PocketFailure.Handoff.label,PocketFailure.Runtime.label)

internal fun conversationReadFailure(failure: Exception, cached: Boolean): String {
    val reason = when ((failure as? WireException)?.code) {
        "offline", "disconnected", "runtime_offline", "runtime_unavailable" -> "电脑的会话读取连接已断开，请重新连接。"
        "request_timeout", "timeout" -> "电脑读取超时，可稍后重新读取。"
        "native_history_unavailable" -> "电脑暂时无法读取这条会话的历史，请恢复会话连接后重试。"
        "not_found" -> "电脑未找到这条会话，请刷新列表确认它是否已移动或归档。"
        "unsupported", "unsupported_method", "-32601" -> "电脑助手不支持此历史格式，请更新电脑助手。"
        else -> "最新消息同步失败，可重新读取或恢复会话连接。"
    }
    return reason + if (cached) " 已显示的正文与草稿已保留。" else " 草稿已保留。"
}
