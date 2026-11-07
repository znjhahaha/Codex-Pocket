package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeReasoningSnapshotTest {
    private fun json(value: String) = pocketJson.parseToJsonElement(value).jsonObject
    private fun snapshot(items: String, status: String = "inProgress") = NativeReducer.snapshot(json("""
        {"id":"native-thread","status":{"type":"active"},"turns":[
          {"id":"native-turn","status":"$status","items":$items}]}
    """))

    @Test fun midTurnOpeningRetainsSummaryAfterItemCompletionAndLaterTool() {
        val state = snapshot("""[
          {"id":"native-reason","type":"reasoning","summary":["First paragraph","Second paragraph"],"content":["private"],"completedAtMs":200},
          {"id":"native-command","type":"commandExecution","command":"check","status":"inProgress"}]
        """)
        val item = state.items.values.first()
        assertFalse(item.liveStarted)
        assertTrue(item.complete)
        val summary = ConversationPresentation.liveReasoning(state)!!
        assertEquals(item.key, summary.key)
        assertEquals(listOf("First paragraph", "Second paragraph"), summary.summaryParts)
        assertEquals(ConversationPresentation.LiveStatus.Working, ConversationPresentation.liveStatus(state))
        assertFalse(ConversationPresentation.rows(state).flatMap { it.items }.any { it.type == "reasoning" })
        assertEquals(summary, ConversationPresentation.liveReasoning(snapshot("""[
          {"id":"native-reason","type":"reasoning","summary":["First paragraph","Second paragraph"],"content":["private"],"completedAtMs":200},
          {"id":"native-command","type":"commandExecution","command":"check","status":"inProgress"}]
        """)))
    }

    @Test fun snapshotSummaryStreamsIntoItsOriginalParagraphWithoutStartReplay() {
        val state = snapshot("""[{"id":"r","type":"reasoning","summary":["First","Second"]}]""")
        val next = NativeReducer.reduce(state, json("""{"method":"item/reasoning/summaryTextDelta","params":{"turnId":"native-turn","itemId":"r","summaryIndex":1,"delta":" paragraph"}}"""))
        assertEquals(listOf("First", "Second paragraph"), ConversationPresentation.liveReasoning(next)!!.summaryParts)
        assertEquals(state.items.keys, next.items.keys)
    }

    @Test fun summaryIsIndependentOfCompletionButNeverEscapesItsActiveNativeTurn() {
        val state = snapshot("""[{"id":"r","type":"reasoning","summary":["Observed"],"completedAtMs":200}]""")
        assertNotNull(ConversationPresentation.liveReasoning(state))
        assertNull(ConversationPresentation.liveReasoning(state.copy(activeTurn = "another-turn")))
        assertNull(ConversationPresentation.liveReasoning(state.copy(completedTurns = setOf("native-turn"))))
        assertNull(ConversationPresentation.liveReasoning(snapshot("""[{"id":"r","type":"reasoning","summary":["Observed"]}]""", "completed")))
    }

    @Test fun nativeSummaryIsStrictProseAndEmptySummaryCannotExposeOtherFields() {
        val item = TimelineItem.from("t", json("""{"id":"r","type":"reasoning","summary":[42,true,null,{"text":false},{"text":"Legacy paragraph"},"Native paragraph"],"content":["private"],"text":"private"}"""))
        assertEquals(listOf("Legacy paragraph", "Native paragraph"), item.summaryParts)
        assertEquals("Legacy paragraph\n\nNative paragraph", item.text)
        assertTrue(item.copy(text = "private", raw = json("""{"summary":[],"content":["private"]}""")).summaryParts.isEmpty())
        assertTrue(item.copy(text = "private", raw = json("""{"summary":{"text":"private"}}""")).summaryParts.isEmpty())
        assertTrue(item.copy(raw = json("""{"summary":[{"type":"reasoning_text","text":"private"}]}""")).summaryParts.isEmpty())
        assertEquals("", NativeActivityPresentation.describe(item.copy(text = "private", raw = json("""{"summary":[]}"""))).output.text)
        assertEquals("", ConversationPresentation.detail(item.copy(text = "private", raw = json("""{"summary":[]}"""))))
        assertTrue(snapshot("""[{"id":"r","type":"reasoning","content":["private"],"text":"private"}]""").items.values.single().summaryParts.isEmpty())
    }

    @Test fun legacyCachedSummaryTextKeepsParagraphBoundaries() {
        assertEquals(listOf("First paragraph", "Second paragraph"), TimelineItem("t", "r", "reasoning", "First paragraph\n\nSecond paragraph").summaryParts)
    }

    @Test fun lateSummaryLifecycleCannotReplaceATerminalTurn() {
        val state = snapshot("""[{"id":"r","type":"reasoning","summary":["Final summary"]}]""", "completed")
        listOf(
            """{"method":"turn/started","params":{"turn":{"id":"native-turn","status":"inProgress"}}}""",
            """{"method":"item/started","params":{"turnId":"native-turn","item":{"id":"r","type":"reasoning","summary":[]}}}""",
            """{"method":"item/reasoning/summaryTextDelta","params":{"turnId":"native-turn","itemId":"r","summaryIndex":0,"delta":"stale"}}"""
        ).forEach { assertEquals(state, NativeReducer.reduce(state, json(it))) }
    }
}
