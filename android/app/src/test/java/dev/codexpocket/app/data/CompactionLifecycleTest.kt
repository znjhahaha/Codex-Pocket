package dev.codexpocket.app.data

import dev.codexpocket.app.data.ConversationPresentation.LiveStatus
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Native event fixtures only: no compact request or model invocation. */
class CompactionLifecycleTest {
    private fun json(value: String) = pocketJson.parseToJsonElement(value).jsonObject
    private fun event(state: NativeTimeline, method: String, params: String) =
        NativeReducer.reduce(state, buildJsonObject { put("method", method); put("params", json(params)) })
    private fun started(state: NativeTimeline = NativeTimeline()) = event(
        event(state, "turn/started", """{"threadId":"thread","turn":{"id":"turn","status":"inProgress"}}"""),
        "item/started", """{"threadId":"thread","turnId":"turn","item":{"id":"compact","type":"contextCompaction","startedAtMs":100}}""")
    private fun compact(state: NativeTimeline) = state.items.values.single { it.type == "contextCompaction" }
    private fun compactRows(state: NativeTimeline) = ConversationPresentation.rows(state).filter { row -> row.items.any { it.type == "contextCompaction" } }

    @Test fun automaticAndManualCompactionCompleteWithoutAnAssistantAnswer() {
        // Automatic compaction occurs inside a conversation; manual compaction has no user item.
        listOf(NativeTimeline(), NativeTimeline(items = mapOf(TimelineItem("turn", "user", "userMessage", "hello").let { it.key to it }))).forEach { initial ->
            val running = started(initial)
            assertEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(running))
            val done = event(running, "item/completed", """{"threadId":"thread","turnId":"turn","item":{"id":"compact","type":"contextCompaction","completedAtMs":200}}""")
            assertEquals(running.items.keys, done.items.keys)
            assertEquals("compact", compactRows(done).single().items.single().itemId)
            assertTrue(compact(done).complete)
        }
    }

    @Test fun historyShowsCompletedCompactionAsItsOwnBoundaryBetweenTools() {
        val state = NativeReducer.snapshot(json("""{"turns":[{"id":"turn","status":"completed","items":[{"id":"a","type":"commandExecution"},{"id":"compact","type":"contextCompaction","completedAtMs":200},{"id":"b","type":"commandExecution"}]}]}"""))
        val rows = ConversationPresentation.rows(state)
        assertEquals(listOf(listOf("a"), listOf("compact"), listOf("b")), rows.map { row -> row.items.map { it.itemId } })
        assertTrue(compact(state).complete)
        assertNull(ConversationPresentation.liveStatus(state))
    }

    @Test fun retryingNativeErrorPreservesActiveCompactionAndOriginalError() {
        val running = started()
        val params = """{"threadId":"thread","turnId":"turn","error":{"message":"HTTP 429 Reconnecting 1/5"},"willRetry":true}"""
        val retrying = event(running, "error", params)
        assertEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(retrying))
        assertFalse(compact(retrying).complete)
        val error = retrying.items.values.single { it.type == "error" }
        assertEquals(json(params), error.raw.obj("params"))
        assertTrue(ConversationErrorPresentation.from(error, retrying).retrying)
        val done = event(retrying, "item/completed", """{"turnId":"turn","item":{"id":"compact","type":"contextCompaction"}}""")
        assertNotEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(done))
        assertEquals(1, compactRows(done).size)
    }

    @Test fun terminalErrorAndInterruptedTurnDoNotLeaveLiveCompaction() {
        listOf("failed", "interrupted").forEach { status ->
            val running = started()
            val stopped = event(running, "turn/completed", """{"threadId":"thread","turn":{"id":"turn","status":"$status"}}""")
            assertFalse(compact(stopped).liveStarted)
            assertFalse(compact(stopped).complete)
            assertNull(ConversationPresentation.liveStatus(stopped))
            assertEquals(1, compactRows(stopped).size)
            val lateStart = event(stopped, "item/started", """{"turnId":"turn","item":{"id":"compact","type":"contextCompaction"}}""")
            assertFalse(compact(lateStart).liveStarted)
        }
        val failed = event(started(), "error", """{"threadId":"thread","turnId":"turn","error":{"message":"HTTP 429"},"willRetry":false}""")
        assertNotEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(failed))
        assertEquals("failed", compact(failed).status)
        assertEquals(1, compactRows(failed).size)
    }

    @Test fun reentryRestoresOnlyNativeStartedTailOfTheActiveTurn() {
        val state = NativeReducer.snapshot(json("""{"status":{"type":"active","activeFlags":[]},"turns":[{"id":"old","status":"completed","items":[{"id":"old-compact","type":"contextCompaction","startedAtMs":10,"completedAtMs":30}]},{"id":"turn","status":"inProgress","items":[{"id":"reasoning","type":"reasoning","summary":["Earlier summary"]},{"id":"compact","type":"contextCompaction","startedAtMs":100,"completedAtMs":null}]}]}"""))
        assertEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(state))
        assertTrue(compactRows(state).single().items.single().complete)
        val snapshot = json("""{"turns":[{"id":"turn","status":"inProgress","items":[{"id":"compact","type":"contextCompaction","startedAtMs":100},{"id":"later","type":"commandExecution"}]}]}""")
        val later = NativeReducer.snapshot(snapshot)
        assertFalse(compact(later).liveStarted)
        assertNotEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(later))
        assertFalse(compact(later).complete)
        assertEquals(1, compactRows(later).size)
        val cached = NativeTimeline(items = state.items.mapValues { (_, item) -> item.copy(liveStarted = false) }).withCachedTurns()
        assertNull(ConversationPresentation.liveStatus(cached))
    }

    @Test fun failedHistoryWithoutItemCompletionIsNotSuccessfulCompaction() {
        listOf("failed", "interrupted").forEach { status ->
            val history = NativeReducer.snapshot(json("""{"turns":[{"id":"turn","status":"$status","items":[{"id":"compact","type":"contextCompaction","startedAtMs":100}]}]}"""))
            assertFalse(compact(history).complete)
            assertFalse(compact(history).liveStarted)
            assertEquals(1, compactRows(history).size)
            assertNull(ConversationPresentation.liveStatus(history))
        }
    }

    @Test fun idleOrUnloadedNativeThreadClearsActivityWithoutInventingCompletion() {
        listOf("idle", "notLoaded", "systemError").forEach { status ->
            val running = started()
            val stopped = event(running, "thread/status/changed", """{"threadId":"thread","status":{"type":"$status"}}""")
            assertFalse(compact(stopped).liveStarted)
            assertFalse(compact(stopped).complete)
            assertNotEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(stopped))
            assertEquals("上下文压缩状态未知", ConversationPresentation.compactionLabel(compact(stopped), stopped))
            assertEquals(running.items.keys, stopped.items.keys)
        }
    }

    @Test fun realCacheRoundTripDoesNotPersistLiveCompactionEvidence() {
        val running = started()
        val key = ThreadKey("computer", "store", "thread")
        val item = compact(running).withTurnCache(running.turns["turn"])
        val cached = ItemEntity.from(key, item, 0).item()
        val restored = NativeTimeline(items = mapOf(cached.key to cached)).withCachedTurns()
        assertFalse(cached.liveStarted)
        assertEquals(item.raw, cached.raw)
        assertNull(ConversationPresentation.liveStatus(restored))
        assertEquals("上下文压缩状态未知", ConversationPresentation.compactionLabel(cached, restored))
    }

    @Test fun snapshotUnknownLaterActionOrIdleStatusCannotReviveHistoricalCompaction() {
        val later = NativeReducer.snapshot(json("""{"turns":[{"id":"turn","status":"inProgress","items":[{"id":"compact","type":"contextCompaction","startedAtMs":100},{"id":"future","type":"futureNativeAction"}]}]}"""))
        assertFalse(compact(later).liveStarted)
        assertNotEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(later))
        val idle = NativeReducer.snapshot(json("""{"status":{"type":"idle"},"turns":[{"id":"turn","status":"inProgress","items":[{"id":"compact","type":"contextCompaction","startedAtMs":100}]}]}"""))
        assertFalse(compact(idle).liveStarted)
        assertNotEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(idle))
    }

    @Test fun completedItemSurvivesLaterFailureWhileMissingCompletionStaysUnknown() {
        val running = started()
        val completed = event(running, "item/completed", """{"turnId":"turn","item":{"id":"compact","type":"contextCompaction"}}""")
        val failedLater = event(completed, "turn/completed", """{"turn":{"id":"turn","status":"failed","error":{"message":"Later tool failed"}}}""")
        assertEquals("上下文已压缩", ConversationPresentation.compactionLabel(compact(failedLater), failedLater))
        val unknown = event(running, "turn/completed", """{"turn":{"id":"turn","status":"completed"}}""")
        assertFalse(compact(unknown).complete)
        assertEquals("上下文压缩状态未知", ConversationPresentation.compactionLabel(compact(unknown), unknown))
    }

    @Test fun compatibleFailedOrInterruptedItemCompletionNeverReportsSuccess() {
        // The current schema has no compaction status; tolerate extensions without ignoring explicit failure.
        mapOf("failed" to "上下文压缩失败", "interrupted" to "上下文压缩已中断", "cancelled" to "上下文压缩已中断").forEach { (status, label) ->
            val payload = """{"turnId":"turn","item":{"id":"compact","type":"contextCompaction","status":"$status","completedAtMs":200}}"""
            val ended = event(started(), "item/completed", payload)
            val item = compact(ended)
            assertTrue(item.complete) // Lifecycle ended; this flag alone is not success.
            assertEquals(json(payload).obj("item"), item.raw)
            assertEquals(label, ConversationPresentation.compactionLabel(item, ended))
            assertNotEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(ended))
            val snapshot = NativeReducer.snapshot(json("""{"turns":[{"id":"turn","status":"completed","items":[${item.raw}]}]}"""))
            assertEquals(label, ConversationPresentation.compactionLabel(compact(snapshot), snapshot))
            val cached = ItemEntity.from(ThreadKey("computer", "store", "thread"), item, 0).item()
            assertEquals(label, ConversationPresentation.compactionLabel(cached))
        }
    }

    @Test fun snapshotAndLiveTurnCompletionBothLeaveUntimedCompactionUnknown() {
        val live = event(started(), "turn/completed", """{"turn":{"id":"turn","status":"completed"}}""")
        listOf("", ",\"completedAtMs\":null", ",\"completedAtMs\":-1").forEach { timing ->
            val history = NativeReducer.snapshot(json("""{"turns":[{"id":"turn","status":"completed","items":[{"id":"compact","type":"contextCompaction","startedAtMs":100$timing}]}]}"""))
            assertFalse(compact(history).complete)
            assertEquals(ConversationPresentation.compactionLabel(compact(live), live),
                ConversationPresentation.compactionLabel(compact(history), history))
            assertEquals("上下文压缩状态未知", ConversationPresentation.compactionLabel(compact(history), history))
        }
    }

    @Test fun nativeItemCompletionEvidenceIsIndependentOfContainingTurnOutcome() {
        listOf("inProgress", "completed", "failed", "interrupted").forEach { status ->
            val history = NativeReducer.snapshot(json("""{"turns":[{"id":"turn","status":"$status","items":[{"id":"compact","type":"contextCompaction","startedAtMs":100,"completedAtMs":200}]}]}"""))
            assertTrue(compact(history).complete)
            assertEquals("上下文已压缩", ConversationPresentation.compactionLabel(compact(history), history))
        }
    }
}
