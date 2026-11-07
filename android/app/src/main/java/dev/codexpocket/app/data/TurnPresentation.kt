package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** Optional native metadata. Observation starts are deliberately not persisted across processes. */
data class NativeTurnInfo(
    val id: String, val rootId: String? = null, val status: String = "",
    val startedAtMs: Long? = null, val completedAtMs: Long? = null, val durationMs: Long? = null,
    val observedStartNanos: Long? = null, val observedDurationMs: Long? = null,
    val observedParentId: String? = null,
) {
    private val nativeElapsedMs: Long? get() = durationMs?.takeIf { it >= 0 } ?:
        startedAtMs?.takeIf { it >= 0 }?.let { start -> completedAtMs?.takeIf { it >= start }?.minus(start) }
    val elapsedMs: Long? get() = nativeElapsedMs ?: observedDurationMs?.takeIf { it >= 0 }
    val observed: Boolean get() = nativeElapsedMs == null && observedDurationMs?.takeIf { it >= 0 } != null
    fun json() = buildJsonObject {
        put("id", id); rootId?.let { put("rootTurnId", it) }; put("status", status)
        startedAtMs?.let { put("startedAtMs", it) }; completedAtMs?.let { put("completedAtMs", it) }
        durationMs?.let { put("durationMs", it) }; observedDurationMs?.let { put("observedDurationMs", it) }
        observedParentId?.let { put("observedParentId", it) }
    }
    companion object {
        fun from(o: JsonObject, cached: Boolean = false): NativeTurnInfo {
            fun value(key: String) = (o[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
            return NativeTurnInfo(o.string("id"), o.string("rootTurnId").takeIf(String::isNotBlank), o.string("status"),
                if (cached) value("startedAtMs") else value("startedAt")?.takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000),
                if (cached) value("completedAtMs") else value("completedAt")?.takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000),
                value("durationMs"), observedDurationMs = if (cached) value("observedDurationMs") else null,
                observedParentId = if (cached) o.string("observedParentId").takeIf(String::isNotBlank) else null)
        }
    }
}

/** Preserve missing optional metadata only for the same native identity. New values/status win. */
fun NativeTimeline.withObservedTurns(previous: NativeTimeline): NativeTimeline = copy(turns = turns.mapValues { (id, native) ->
    val old = previous.turns[id]
    // A snapshot that marks an old terminal turn running must not inherit its completed clock.
    val keepFinishedTiming = native.status != "inProgress" || old?.status == "inProgress"
    native.copy(rootId = native.rootId ?: old?.rootId,
        startedAtMs = native.startedAtMs ?: old?.startedAtMs,
        completedAtMs = native.completedAtMs ?: old?.completedAtMs?.takeIf { keepFinishedTiming },
        durationMs = native.durationMs ?: old?.durationMs?.takeIf { keepFinishedTiming },
        observedStartNanos = old?.observedStartNanos?.takeIf { native.status == "inProgress" && old.status == "inProgress" },
        observedDurationMs = old?.observedDurationMs?.takeIf { native.status == old.status },
        observedParentId = old?.observedParentId)
})

/** Only a confirmed Steer response may link two otherwise unrelated native turns. */
fun NativeTimeline.withConfirmedSteer(expectedTurn: String, returnedTurn: String): NativeTimeline {
    if (expectedTurn.isBlank() || returnedTurn.isBlank() || expectedTurn == returnedTurn) return this
    val info = turns[returnedTurn] ?: NativeTurnInfo(returnedTurn)
    return copy(turns = turns + (returnedTurn to info.copy(observedParentId = info.observedParentId ?: expectedTurn)))
}

fun NativeTimeline.withCachedTurns(): NativeTimeline {
    val meta = items.values.mapNotNull { item -> (item.raw["_pocketTurn"] as? JsonObject)?.let { NativeTurnInfo.from(it, true) } }
        .filter { it.id.isNotBlank() }.associateBy { it.id }
    return copy(turns = meta, completedTurns = meta.values.filter { it.status in setOf("completed", "failed", "interrupted") }.map { it.id }.toSet())
}

fun processDurationLabel(milliseconds: Long): String {
    val s = milliseconds.coerceAtLeast(0) / 1000
    return when { s >= 3600 -> "${s / 3600} 小时 ${s / 60 % 60} 分"; s >= 60 -> "${s / 60} 分 ${s % 60} 秒"; else -> "$s 秒" }
}

fun TimelineItem.withTurnCache(turn: NativeTurnInfo?): TimelineItem = if (turn == null) this else
    copy(raw = JsonObject(raw + ("_pocketTurn" to turn.json())))
