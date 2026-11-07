package dev.codexpocket.app.data

import android.os.SystemClock
import android.util.Log

/** Bounded local facts. Never accepts exception text, paths, input or protocol payload. */
data class ConnectionDiagnostic(val generation: Long, val stage: String, val code: String, val elapsedMs: Long)
internal class ConnectionDiagnostics {
    private val entries = ArrayDeque<ConnectionDiagnostic>()
    private val started = SystemClock.elapsedRealtime()
    @Synchronized fun record(generation: Long, stage: String, code: String) {
        val safeStage = stage.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: "unknown"
        val safeCode = code.takeIf { it in allowed } ?: "unknown"
        val entry = ConnectionDiagnostic(generation, safeStage, safeCode, SystemClock.elapsedRealtime() - started)
        if (entries.size == 64) entries.removeFirst()
        entries.addLast(entry)
        Log.i("PocketConnectionV4", "generation=${entry.generation} stage=${entry.stage} code=${entry.code} elapsedMs=${entry.elapsedMs}")
    }
    @Synchronized fun snapshot(): List<ConnectionDiagnostic> = entries.toList()
    private val allowed = setOf("started", "ready", "completed", "cancelled", "offline", "disconnected", "timeout", "backpressure", "transport_failed", "invalid_frame", "invalid_reply", "invalid_notification", "invalid_transport", "unauthorized", "revoked", "device_revoked", "authentication_failed", "remote_error", "unknown", "payload_too_large", "cursor_gap", "stale", "history_overflow", "unsupported")
}
