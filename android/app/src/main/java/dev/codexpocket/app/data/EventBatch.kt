package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** A transport-local optimization, never an accepted wire field. */
internal data class EventBatch(val envelope: JsonObject, val firstSequence: Long)

internal object EventBatcher {
    private val deltas = setOf("item/agentMessage/delta", "item/commandExecution/outputDelta", "item/reasoning/summaryTextDelta", "item/plan/delta")

    fun coalesce(envelopes: List<JsonObject>, current: Map<String, Cursor>, snapshots: Map<Pair<String, String>, Cursor> = emptyMap()): List<EventBatch> {
        val result = mutableListOf<EventBatch>()
        for (envelope in envelopes) {
            val cursor = envelope.obj("cursor")
            val sequence = cursor.number("sequence")
            val event = envelope.obj("event")
            val params = event.obj("params")
            val last = result.lastOrNull()
            val previous = last?.envelope
            val previousEvent = previous?.obj("event")
            val previousParams = previousEvent?.obj("params")
            val runtime = envelope.string("runtimeId")
            val known = current[runtime]
            val snapshot = snapshots[runtime to params.string("threadId")]
            val merge = last != null && previous != null && event.string("method") in deltas && event["id"] == null &&
                params.string("itemId").isNotBlank() && params.string("turnId").isNotBlank() &&
                previous.string("runtimeId") == runtime && previous.obj("cursor").string("epoch") == cursor.string("epoch") &&
                previous.obj("cursor").number("sequence") + 1 == sequence &&
                previousEvent?.get("id") == null && previousEvent?.string("method") == event.string("method") && previousParams?.let { JsonObject(it - "delta") == JsonObject(params - "delta") } == true &&
                (known == null || known.epoch == cursor.string("epoch") && known.sequence < last.firstSequence) &&
                (snapshot == null || snapshot.epoch != cursor.string("epoch") || snapshot.sequence < last.firstSequence || snapshot.sequence >= sequence) &&
                previousParams.string("delta").length + params.string("delta").length <= 64_000
            if (merge) {
                val mergedParams = JsonObject(params + ("delta" to JsonPrimitive(previousParams!!.string("delta") + params.string("delta"))))
                val merged = JsonObject(envelope + ("event" to JsonObject(event + ("params" to mergedParams))))
                result[result.lastIndex] = EventBatch(merged, last!!.firstSequence)
            } else result += EventBatch(envelope, sequence)
        }
        return result
    }
}
