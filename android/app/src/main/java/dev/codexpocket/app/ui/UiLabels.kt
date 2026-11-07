package dev.codexpocket.app.ui

import android.content.Context
import dev.codexpocket.app.R
import java.util.Locale

internal fun runtimeVersionLabel(value: String): String =
    Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.+-]+)?").find(value)?.value ?: value.take(48)

internal fun Context.sourceLabel(value: String) = when (value.lowercase(Locale.ROOT)) {
    "desktop" -> getString(R.string.source_desktop)
    "cli" -> getString(R.string.source_cli)
    else -> value
}

internal fun Context.stateLabel(value: String): String {
    when(value.lowercase(Locale.ROOT)) {
        "authorization_required" -> return "需要重新配对"
        "authenticating" -> return "正在验证身份"
        "syncing" -> return "正在同步"
        "recovering" -> return "正在恢复连接"
        "ready" -> return getString(R.string.state_connected)
    }
    val resource = when (value.lowercase(Locale.ROOT)) {
        "offline" -> R.string.state_offline
        "connecting" -> R.string.state_connecting
        "reconnecting" -> R.string.state_reconnecting
        "connected", "online" -> R.string.state_connected
        "inprogress", "in_progress", "running", "active" -> R.string.state_in_progress
        "completed" -> R.string.state_completed
        "failed", "systemerror" -> R.string.state_failed
        "interrupted" -> R.string.state_interrupted
        "unknown" -> R.string.state_unknown
        "submitted" -> R.string.state_submitted
        "accepted" -> R.string.state_accepted
        "rejected", "declined" -> R.string.state_rejected
        "not_found" -> R.string.state_not_found
        "idle" -> R.string.state_idle
        "waiting", "pending", "waitingonapproval", "waitingonuserinput" -> R.string.state_waiting
        "notloaded" -> R.string.delivery_not_loaded
        else -> return value
    }
    return getString(resource)
}

internal fun Context.changeLabel(kind: String) = when (kind.lowercase(Locale.ROOT)) {
    "add", "added" -> getString(R.string.delivery_file_added)
    "delete", "deleted" -> getString(R.string.delivery_file_deleted)
    "update", "modify", "modified" -> getString(R.string.delivery_file_modified)
    "rename", "renamed" -> getString(R.string.delivery_file_renamed)
    else -> kind
}

internal fun Context.themeLabel(value: String) = getString(when (value) {
    "light" -> R.string.light
    "dark" -> R.string.dark
    else -> R.string.system
})

internal fun Context.effortLabel(value: String): String {
    val resource = when (value) {
    "none" -> R.string.effort_none
    "minimal" -> R.string.effort_minimal
    "low" -> R.string.effort_low
    "medium" -> R.string.effort_medium
    "high" -> R.string.effort_high
    "xhigh" -> R.string.effort_xhigh
    "max" -> R.string.effort_max
    "ultra" -> R.string.effort_ultra
    else -> return value
    }
    return getString(resource)
}

internal fun Context.modeLabel(mode: String, name: String) = when (mode) {
    "plan" -> getString(R.string.plan)
    "default" -> getString(R.string.mode_default)
    else -> name
}

internal fun Context.actionLabel(method: String) = when (method) {
    "thread/start" -> getString(R.string.new_chat)
    "thread/resume" -> getString(R.string.resume_windows)
    "thread/name/set" -> getString(R.string.rename)
    "thread/archive" -> getString(R.string.archive)
    "thread/unarchive" -> getString(R.string.unarchive)
    "turn/start" -> getString(R.string.send_message)
    "turn/steer" -> getString(R.string.steer_turn)
    "turn/interrupt" -> getString(R.string.stop_turn)
    "approvals/respond" -> getString(R.string.pending_requests)
    "attachment/upload" -> getString(R.string.attach_images)
    "devices/revoke" -> getString(R.string.revoke)
    else -> method
}
