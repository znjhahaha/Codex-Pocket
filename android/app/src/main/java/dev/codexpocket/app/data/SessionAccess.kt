package dev.codexpocket.app.data

import kotlinx.serialization.json.*

enum class SessionMode(val wire: String, val label: String) {
    Desktop("desktop", "跟随桌面"), Cli("cli", "独立 CLI");
    companion object { fun from(value: String) = entries.firstOrNull { it.wire == value } }
}

enum class BrokerCapability(val method: String) {
    PreferencesRead("session/preferences/read"), PreferencesUpdate("session/preferences/update"),
    AccessRead("thread/access/read"), AccessUpdate("thread/access/update"), AccessCancel("thread/access/cancel"),
    EnsureRuntime("runtime/ensure");
}

enum class AccessPhase(val wire: String, val label: String) {
    Ready("ready", "已就绪"), StartingDesktop("starting_desktop", "正在启动桌面"),
    WaitingIdle("waiting_idle", "等待当前任务结束"), ReleasingCli("releasing_cli", "正在释放 CLI"),
    WaitingRelease("waiting_release", "等待原运行时释放"), AttachingDesktop("attaching_desktop", "正在连接桌面会话"),
    Blocked("blocked", "交接受阻"), Failed("failed", "交接失败"), Cancelled("cancelled", "交接已取消");
    val inProgress get() = this !in setOf(Ready, Blocked, Failed, Cancelled)
}

data class SessionPreferences(val defaultMode: SessionMode = SessionMode.Desktop, val revision: Int? = null) {
    companion object {
        fun from(raw: JsonObject): SessionPreferences {
            val mode = SessionMode.from(raw.string("defaultMode")) ?: throw PocketOperationException(PocketFailure.InvalidSnapshot)
            val revision = (raw["revision"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
                ?: throw PocketOperationException(PocketFailure.InvalidSnapshot)
            return SessionPreferences(mode, revision)
        }
    }
}

data class ThreadAccess(val mode: SessionMode, val ownerRuntimeId: String?, val source: String,
    val phase: AccessPhase, val canSend: Boolean, val reason: String? = null, val operationId: String? = null) {
    fun canSendFrom(runtimeId: String) = phase == AccessPhase.Ready && canSend && ownerRuntimeId == runtimeId
    companion object {
        fun from(raw: JsonObject): ThreadAccess {
            val mode = SessionMode.from(raw.string("mode")) ?: throw PocketOperationException(PocketFailure.InvalidSnapshot)
            val phase = AccessPhase.entries.firstOrNull { it.wire == raw.string("phase") }
                ?: throw PocketOperationException(PocketFailure.InvalidSnapshot)
            return ThreadAccess(mode, raw.string("ownerRuntimeId").takeIf { it.isNotBlank() && it != "null" },
                raw.string("source"), phase, raw.flag("canSend"), raw.string("reason").takeIf { it.isNotBlank() && it != "null" },
                raw.string("operationId").takeIf { it.isNotBlank() && it != "null" })
        }
    }
}

data class ActionStatus(val loading: Boolean = false, val phase: String? = null, val error: String? = null)

enum class PocketFailure(val label: String) {
    Ownership("会话归属已变化，请刷新归属后继续"), Handoff("会话正在交接，请等待完成"),
    Runtime("所选运行环境尚未就绪，请稍后重试"), Unsupported("电脑助手尚未支持此功能，请更新后重试"),
    ModelSpeed("当前模型未提供 Fast 速度档位"), RuntimeSpeed("当前运行时未提供 serviceTier 能力"),
    StaleRevision("默认模式已在其他设备更新，请刷新后重试"), InvalidSnapshot("电脑返回的状态不完整，请刷新后重试"),
    MediaOwner("图片所属会话或连接已变化，请重新打开图片"), MediaExpired("图片已过期"),
    MediaUnsupported("暂不支持这张图片"), MediaFailed("图片读取失败，请重试");
    companion object {
        fun fromCode(code: String): PocketFailure? = when (code) {
            "thread_owned_elsewhere", "ownership_conflict", "owner_conflict", "owner_mismatch", "runtime_mismatch", "thread_owner_changed", "not_owner", "read_only", "conflict", "thread_owner_conflict", "thread_owned_by_other_runtime" -> Ownership
            "operation_in_progress", "handoff_pending", "handoff_in_progress", "waiting_idle", "waiting_release", "thread_busy", "thread_handoff_pending", "handoff_blocked" -> Handoff
            "request_timeout", "runtime_unavailable", "runtime_not_ready", "runtime_offline", "desktop_unavailable", "desktop_not_ready", "desktop_start_failed", "desktop_starting", "source_unavailable", "no_runtime", "runtime_not_found" -> Runtime
            "unsupported", "unsupported_method", "method_not_found", "-32601", "unsupported_source", "source_unsupported", "capability_unavailable" -> Unsupported
            "service_tier_unsupported", "unsupported_service_tier", "fast_model_unsupported", "model_fast_unsupported" -> ModelSpeed
            "version_conflict", "revision_conflict", "stale_revision", "preferences_revision_conflict" -> StaleRevision
            "stale_media", "media_owner_mismatch", "media_connection_mismatch", "media_not_owned" -> MediaOwner
            "media_expired", "expired", "media_not_found" -> MediaExpired
            "media_unsupported" -> MediaUnsupported
            "media_failed" -> MediaFailed
            else -> null
        }
    }
}
class PocketOperationException(val failure: PocketFailure) : Exception(failure.label)

fun sessionRuntimes(runtimes: List<RuntimeInfo>, mode: SessionMode) = runtimes.filter { it.online && it.source == mode.wire }

/** Source is historical provenance; only the broker's owner snapshot grants control. */
fun PocketState.supports(capability: BrokerCapability) = capability.method in capabilities
fun PocketState.accessFor(chat: ChatEntity? = selected) = chat?.let { threadAccess[it.key] }
fun PocketState.accessAllowsSending(chat: ChatEntity? = selected): Boolean = chat != null &&
    (!supports(BrokerCapability.AccessRead) || (chat.key in accessFresh && accessFor(chat)?.canSendFrom(chat.runtimeId) == true &&
        runtimes.firstOrNull { it.id == chat.runtimeId }?.source == accessFor(chat)?.mode?.wire))

/** The send button can request one prepare; generation remains gated on the confirmed owner. */
fun PocketState.accessAllowsSendPreparation(chat: ChatEntity? = selected): Boolean = chat != null &&
    supports(BrokerCapability.AccessRead) && supports(BrokerCapability.AccessUpdate) && chat.key in accessFresh &&
    accessFor(chat)?.phase == AccessPhase.Ready && actionStatus["access:${chat.key.encoded}"]?.loading != true

fun PocketState.fastCapabilityFailure(enabling: Boolean = !advanced.fastMode): PocketFailure? = when {
    enabling && currentModel?.supportsFast != true -> PocketFailure.ModelSpeed
    activeRuntime?.supportsServiceTier != true -> PocketFailure.RuntimeSpeed
    else -> null
}
