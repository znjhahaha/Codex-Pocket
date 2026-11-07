package dev.codexpocket.app.data

import dev.codexpocket.app.data.ConversationPresentation.LiveStatus
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V05CompactionStatusTest {
    private fun event(method: String, params: String) = buildJsonObject {
        put("method", method); put("params", pocketJson.parseToJsonElement(params))
    }

    private fun start(state: NativeTimeline = NativeTimeline(activeTurn = "t"), turn: String = "t", item: String = "k") =
        NativeReducer.reduce(state, event("item/started", """{"turnId":"$turn","startedAtMs":100,"item":{"id":"$item","type":"contextCompaction"}}"""))

    @Test fun historicalSnapshotWithoutLifecycleEvidenceUsesGenericThinkingLabelState() {
        val state = NativeReducer.snapshot(pocketJson.parseToJsonElement("""{"turns":[{"id":"t","status":"inProgress","items":[{"id":"k","type":"contextCompaction"}]}]}""").jsonObject)
        assertFalse(state.items.values.single().liveStarted)
        assertEquals(LiveStatus.Thinking, ConversationPresentation.liveStatus(state))
    }

    @Test fun snapshotRetainsNativeItemCompletionEvenInsideAnActiveTurn() {
        val state = NativeReducer.snapshot(pocketJson.parseToJsonElement("""{"turns":[{"id":"t","status":"inProgress","items":[{"id":"k","type":"contextCompaction","startedAtMs":100,"completedAtMs":200},{"id":"c","type":"commandExecution","command":"check"}]}]}""").jsonObject)
        assertTrue(state.items.values.first().complete)
        assertEquals(LiveStatus.Working, ConversationPresentation.liveStatus(state))
    }

    @Test fun onlyCurrentObservedCompactionShowsCompactingAndNativeCompletionClearsIt() {
        val running = start()
        assertEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(running))
        val done = NativeReducer.reduce(running, event("item/completed", """{"turnId":"t","completedAtMs":200,"item":{"id":"k","type":"contextCompaction"}}"""))
        assertTrue(done.items.values.single().complete)
        assertEquals(LiveStatus.Thinking, ConversationPresentation.liveStatus(done))
        assertEquals(running.items.keys, done.items.keys)
    }

    @Test fun laterMeaningfulActionsClearCompactionWithoutInventingItsCompletion() {
        listOf("commandExecution", "agentMessage", "reasoning", "plan").forEach { type ->
            val state = NativeReducer.reduce(start(), event("item/started", """{"turnId":"t","startedAtMs":200,"item":{"id":"later","type":"$type"}}"""))
            val compaction = state.items.values.first()
            assertFalse("Stale $type compaction evidence", compaction.liveStarted)
            assertFalse(compaction.complete)
            assertEquals(if (type == "reasoning") LiveStatus.Thinking else LiveStatus.Working, ConversationPresentation.liveStatus(state))
        }
    }

    @Test fun laterDeltaOnAnEarlierToolClearsStaleCompactionWithoutReorderingTranscript() {
        val tool = TimelineItem("t", "tool", "commandExecution", liveStarted = true)
        val running = start(NativeTimeline(items = linkedMapOf(tool.key to tool), activeTurn = "t"))
        assertEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(running))
        val later = NativeReducer.reduce(running, event("item/commandExecution/outputDelta", """{"turnId":"t","itemId":"tool","delta":"done"}"""))
        assertEquals(LiveStatus.Working, ConversationPresentation.liveStatus(later))
        assertEquals(running.items.keys.toList(), later.items.keys.toList())
        assertFalse(later.items.values.last().liveStarted)
    }

    @Test fun deprecatedThreadCompactedCompletesOnlyItsNativeTurn() {
        val running = start(start(), "other", "other-k")
        val done = NativeReducer.reduce(running, event("thread/compacted", """{"threadId":"thread","turnId":"t"}"""))
        assertTrue(done.items.getValue(TimelineItem("t", "k", "contextCompaction").key).complete)
        assertFalse(done.items.getValue(TimelineItem("other", "other-k", "contextCompaction").key).complete)
        assertEquals(LiveStatus.Thinking, ConversationPresentation.liveStatus(done))
        assertEquals(running.items.keys, done.items.keys)
        assertEquals(done, NativeReducer.reduce(done, event("thread/compacted", """{"threadId":"thread"}""")))
    }

    @Test fun userSteerAndOtherTurnActionsDoNotClearCurrentCompaction() {
        var state = NativeReducer.reduce(start(), event("item/completed", """{"turnId":"t","item":{"id":"u","type":"userMessage","content":[{"type":"text","text":"extra"}]}}"""))
        state = NativeReducer.reduce(state, event("item/started", """{"turnId":"other","item":{"id":"c","type":"commandExecution"}}"""))
        assertEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(state))
        assertNull(ConversationPresentation.liveStatus(state.copy(activeTurn = null)))
    }
}
