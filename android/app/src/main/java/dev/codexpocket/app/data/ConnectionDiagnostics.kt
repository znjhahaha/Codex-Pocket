package dev.codexpocket.app.data

import android.os.SystemClock
import android.util.Log

/** Bounded local facts. Never accepts exception text, paths, input or protocol payload. */
data class ConnectionDiagnostic(val generation: Long, val stage: String, val code: String, val elapsedMs: Long,
    val exceptionType: String? = null, val location: String? = null)
internal class ConnectionDiagnostics {
    private val entries = ArrayDeque<ConnectionDiagnostic>()
    private val started = SystemClock.elapsedRealtime()
    @Synchronized fun record(generation: Long, stage: String, code: String, failure: Throwable? = null) {
        val safeStage = stage.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: "unknown"
        val safeCode = code.takeIf { it in allowed } ?: "unknown"
        // Type and our own source location only: exception messages may contain native payloads.
        val type = failure?.javaClass?.simpleName?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }
        val frame = failure?.stackTrace?.firstOrNull { it.className.startsWith("dev.codexpocket.") }
        val location = frame?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
            ?.takeIf { it.length <= 240 && it.matches(Regex("[A-Za-z0-9_.$:<>-]+")) }
        val entry = ConnectionDiagnostic(generation, safeStage, safeCode, SystemClock.elapsedRealtime() - started, type, location)
        if (entries.size == 64) entries.removeFirst()
        entries.addLast(entry)
        Log.i("PocketConnectionV4", "generation=${entry.generation} stage=${entry.stage} code=${entry.code} elapsedMs=${entry.elapsedMs} type=${entry.exceptionType} location=${entry.location}")
    }
    @Synchronized fun snapshot(): List<ConnectionDiagnostic> = entries.toList()
    private val allowed = setOf("started", "ready", "completed", "cancelled", "offline", "disconnected", "timeout", "backpressure", "transport_failed", "invalid_frame", "invalid_reply", "invalid_notification", "invalid_transport", "unauthorized", "revoked", "device_revoked", "authentication_failed", "remote_error", "unknown", "payload_too_large", "cursor_gap", "stale", "history_overflow", "unsupported")
}
