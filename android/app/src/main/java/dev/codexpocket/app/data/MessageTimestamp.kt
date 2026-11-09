package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import java.time.Instant

data class MessageTimestamp(val epochMillis: Long, val fromTurnStart: Boolean = false)

/** Persisted native timestamps only; never reuse last-sync, chat updatedAt, or observation clocks. */
fun messageTimestamp(item: TimelineItem, timeline: NativeTimeline?): MessageTimestamp? {
    fun positiveMillis(value: JsonElement?, millis: Boolean = false): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { number ->
            if (number <= 0) return null
            val result = if (millis || number >= 100_000_000_000L) number else
                number.takeIf { it <= Long.MAX_VALUE / 1_000 }?.times(1_000) ?: return null
            return result.takeIf { it < 32_503_680_000_000L }
        }
        return primitive.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.takeIf { it > 0 }
    }
    for (field in listOf("sentAtMs", "createdAtMs", "sentAt", "createdAt", "timestamp")) {
        positiveMillis(item.raw[field], field.endsWith("Ms"))?.let { return MessageTimestamp(it) }
    }
    // Native userMessage uses item.startedAtMs for both the initiating input and mid-turn
    // inserts. It is per-message data, distinct from turn.startedAtMs and local observation.
    if (item.type == "userMessage") {
        positiveMillis(item.raw["startedAtMs"], millis = true)?.let { return MessageTimestamp(it) }
    }
    // Only the initiating user message can use its native turn's start. Never assign that
    // time to later inserted messages, whose own timestamps may not be available.
    if (item.type != "userMessage" || timeline == null) return null
    val first = timeline.items.values.firstOrNull { it.turnId == item.turnId }
    if (first?.key != item.key) return null
    return timeline.turns[item.turnId]?.startedAtMs?.takeIf { it > 0 }?.let { MessageTimestamp(it, true) }
}
