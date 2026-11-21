package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReasoningLifecycleTest {
    private fun json(value: String) = pocketJson.parseToJsonElement(value).jsonObject
    private fun event(state: NativeTimeline, method: String, params: String) = NativeReducer.reduce(state,
        buildJsonObject { put("method", method); put("params", json(params)) })
    private fun snapshot(items: String, status: String = "inProgress") = NativeReducer.snapshot(json("""
        {"status":{"type":"active"},"turns":[{"id":"t","status":"$status","items":$items}]}
    """))
    private fun started() = event(NativeTimeline(), "turn/started", """{"turn":{"id":"t","status":"inProgress"}}""")

    @Test fun finishedToolsReturnToThinkingInLiveHistoryAndReconnectedSnapshots() {
        var live = started()
        for (n in 1..5) {
            live = event(live, "item/started", """{"turnId":"t","item":{"id":"c$n","type":"commandExecution","status":"inProgress"}}""")
            assertEquals(ConversationPresentation.LiveStatus.Working, ConversationPresentation.liveStatus(live))
            live = event(live, "item/completed", """{"turnId":"t","item":{"id":"c$n","type":"commandExecution","status":"completed","exitCode":0}}""")
        }
        val items = JsonArray(live.items.values.map { it.raw }).toString()
        for (timeline in listOf(live, snapshot(items), snapshot(items).withObservedTurns(live))) {
            assertEquals(ConversationPresentation.LiveStatus.Thinking, ConversationPresentation.liveStatus(timeline))
            assertNull(ConversationPresentation.liveReasoning(timeline)) // No fabricated summary.
            assertEquals(5, ConversationPresentation.rows(timeline).flatMap { it.items }.count { it.type == "commandExecution" })
        }
    }

    @Test fun parallelRunningOrWaitingToolTakesPrecedenceOverLaterReasoning() {
        for (status in listOf("inProgress", "waitingOnApproval", "unknown")) {
            val state = snapshot("""[
              {"id":"tool","type":"commandExecution","status":"$status"},
              {"id":"reason","type":"reasoning","summary":["Native summary"]}]
            """)
            assertEquals(ConversationPresentation.LiveStatus.Working, ConversationPresentation.liveStatus(state))
            assertEquals(listOf("Native summary"), ConversationPresentation.liveReasoning(state)!!.summaryParts)
        }
    }

    @Test fun summaryCompletionWithoutSummaryFieldRetainsOnlyPreviouslyProvidedProse() {
        var state = event(started(), "item/reasoning/summaryTextDelta", """{"turnId":"t","itemId":"r","summaryIndex":0,"delta":"Native summary"}""")
        state = event(state, "item/completed", """{"turnId":"t","item":{"id":"r","type":"reasoning","content":["private"]}}""")
        assertEquals(listOf("Native summary"), state.items.values.single().summaryParts)
        assertTrue(state.items.values.single().complete)
        assertFalse(ConversationPresentation.detail(state.items.values.single()).contains("private"))
        state = event(state, "item/completed", """{"turnId":"t","item":{"id":"r","type":"reasoning","summary":[],"content":["private"]}}""")
        assertTrue(state.items.values.single().summaryParts.isEmpty()) // Explicit native replacement wins.
    }

    @Test fun terminalTurnRejectsLateReasoningAndKeepsSummaryInDataOnly() {
        for (status in listOf("completed", "failed", "interrupted")) {
            val running = snapshot("""[{"id":"r","type":"reasoning","summary":["Saved native summary"]}]""")
            val closed = event(running, "turn/completed", """{"turn":{"id":"t","status":"$status"}}""")
            assertNull(ConversationPresentation.liveStatus(closed))
            assertNull(ConversationPresentation.liveReasoning(closed))
            val late = event(closed, "item/reasoning/summaryTextDelta", """{"turnId":"t","itemId":"r","summaryIndex":0,"delta":" stale"}""")
            assertEquals(closed, late)
            val cache = NativeTimeline(items = closed.items.mapValues { (_, item) ->
                ItemEntity.from(ThreadKey("pc", "store", "thread"), item.withTurnCache(closed.turns[item.turnId]), 0).item()
            }).withCachedTurns()
            for (history in listOf(closed, cache, snapshot("""[{"id":"r","type":"reasoning","summary":["Saved native summary"]}]""", status))) {
                assertTrue(ConversationPresentation.rows(history).flatMap { it.items }.none { it.type == "reasoning" })
                assertEquals(listOf("Saved native summary"), history.items.values.single { it.type == "reasoning" }.summaryParts)
                assertNull(ConversationPresentation.liveStatus(history))
            }
        }
    }

    @Test fun newEmptyReasoningDoesNotMasqueradeAsAnOlderSummaryOrEraseIt() {
        val state = snapshot("""[
          {"id":"old","type":"reasoning","summary":["Earlier native summary"],"completedAtMs":1},
          {"id":"new","type":"reasoning","summary":[],"content":["private"]}]
        """)
        assertNull(ConversationPresentation.liveReasoning(state))
        assertEquals(ConversationPresentation.LiveStatus.Thinking, ConversationPresentation.liveStatus(state))
        assertTrue(ConversationPresentation.rows(state).flatMap { it.items }.none { it.type == "reasoning" })
        assertEquals(listOf("Earlier native summary"), state.items.values.first().summaryParts)
    }

    @Test fun staleActivePointerAndApprovalWaitNeverClaimThinking() {
        val running = started()
        for (status in listOf("idle", "notLoaded", "systemError"))
            assertNull(ConversationPresentation.liveStatus(running.copy(status = status)))
        for (flag in listOf("waitingOnApproval", "waitingOnUserInput"))
            assertEquals(ConversationPresentation.LiveStatus.Working, ConversationPresentation.liveStatus(running.copy(activeFlags = setOf(flag))))
    }
}
