package dev.codexpocket.app.ui

import androidx.compose.runtime.*
import dev.codexpocket.app.data.RuntimeInfo
import kotlinx.coroutines.delay

internal fun runtimeReadinessMessage(stage: String?, timedOut: Boolean): String? {
    val step = when (stage) {
        "native_handshake" -> "原生 Codex 握手"
        "native_schema" -> "原生能力读取"
        "session_inventory" -> "会话归属核验"
        "disconnected" -> "原生 Codex 连接"
        else -> return null
    }
    return if (timedOut) "$step 未完成。请重新核验，或打开电脑上的“总览”查看恢复操作。" else "正在完成$step"
}

@Composable
internal fun RuntimeReadinessNotice(runtime: RuntimeInfo?, onRefresh: () -> Unit) {
    if (runtime == null || !runtime.online || runtime.readinessStage == null || runtime.readinessStage == "ready") return
    var timedOut by remember(runtime.id, runtime.readinessStage) { mutableStateOf(runtime.readinessTimedOut) }
    LaunchedEffect(runtime.id, runtime.readinessStage) { delay(15_000); timedOut = true }
    runtimeReadinessMessage(runtime.readinessStage, timedOut || runtime.readinessTimedOut)?.let { message ->
        Notice(message, error = timedOut || runtime.readinessTimedOut, action = "重新核验", onAction = onRefresh)
    }
}
