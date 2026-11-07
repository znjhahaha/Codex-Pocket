package dev.codexpocket.app

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EventBatchTest {
    private fun event(sequence: Int, method: String = "item/agentMessage/delta", item: String = "a", delta: String = "x") = buildJsonObject {
        put("runtimeId", "r"); put("cursor", buildJsonObject { put("epoch", "e"); put("sequence", sequence) })
        put("event", buildJsonObject { put("method", method); put("params", buildJsonObject { put("threadId", "t"); put("turnId", "turn"); put("itemId", item); put("delta", delta) }) })
    }
    @Test fun adjacentDeltasKeepFirstAndFinalCursorAndExactText() {
        val batch = EventBatcher.coalesce((1..100).map { event(it, delta = "$it,") }, mapOf("r" to Cursor("e", 0)))
        assertEquals(1, batch.size); assertEquals(1L, batch.single().firstSequence)
        assertEquals(100L, batch.single().envelope.obj("cursor").number("sequence"))
        assertEquals((1..100).joinToString("") { "$it," }, batch.single().envelope.obj("event").obj("params").string("delta"))
    }
    @Test fun duplicatesGapsAndLifecycleBoundariesCannotDisappear() {
        val batch = EventBatcher.coalesce(listOf(event(1), event(2), event(4), event(5, "item/completed"), event(6)), mapOf("r" to Cursor("e", 1)))
        assertEquals(5, batch.size)
        assertEquals(2, EventBatcher.coalesce(listOf(event(1), event(2, item = "b")), emptyMap()).size)
    }
    @Test fun snapshotBoundaryCannotCombineAlreadyHydratedTextWithFreshText() {
        val result = EventBatcher.coalesce((1..8).map { event(it) }, mapOf("r" to Cursor("e", 0)), mapOf(("r" to "t") to Cursor("e", 5)))
        assertEquals(2, result.size)
        assertEquals(5L, result[0].envelope.obj("cursor").number("sequence"))
        assertEquals(6L, result[1].firstSequence)
        assertEquals("xxx", result[1].envelope.obj("event").obj("params").string("delta"))
    }

    @Test fun requestEnvelopeCannotBeSwallowedByFollowingDelta() {
        val first = event(1)
        val request = JsonObject(first + ("event" to JsonObject(first.obj("event") + ("id" to JsonPrimitive("request")))))
        assertEquals(2, EventBatcher.coalesce(listOf(request, event(2)), emptyMap()).size)
    }
    @Test fun sameNativeIdInAnotherRuntimeDoesNotSplitItsSnapshotBoundary() {
        val result = EventBatcher.coalesce((1..8).map { event(it) }, mapOf("r" to Cursor("e", 0)), mapOf(("other-store-runtime" to "t") to Cursor("e", 5)))
        assertEquals(1, result.size)
        assertEquals(8L, result.single().envelope.obj("cursor").number("sequence"))
    }
}
