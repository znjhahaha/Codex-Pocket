package dev.codexpocket.app

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeReducerTest {
    private val key = ThreadKey("computer", "store", "thread")
    private fun event(method: String, params: String) = Json.parseToJsonElement("""{"method":"$method","params":$params}""").jsonObject

    @Test fun deltasOnlyReplaceTheChangedItemAndCompletionIsAuthoritative() {
        val a = TimelineItem("turn", "a", "agentMessage", "one")
        val b = TimelineItem("turn", "b", "agentMessage", "two")
        val start = NativeTimeline(items = linkedMapOf(a.key to a, b.key to b))
        val delta = NativeReducer.reduce(start, event("item/agentMessage/delta", """{"threadId":"thread","turnId":"turn","itemId":"b","delta":"!"}"""))
        assertSame(a, delta.items[a.key])
        assertEquals("two!", delta.items[b.key]?.text)
        val done = NativeReducer.reduce(delta, event("item/completed", """{"threadId":"thread","turnId":"turn","item":{"id":"b","type":"agentMessage","text":"final"}}"""))
        assertEquals("final", done.items[b.key]?.text)
        assertEquals(listOf(a.key, b.key), done.items.keys.toList())
    }

    @Test fun runtimeAndNativeIdentitySurviveDatabaseConvertersWithoutDelimiterCollisions() {
        val converter = RoomConverters()
        val odd = ThreadKey("computer:part", "store/\"x", "native:42")
        assertEquals(odd, converter.threadKey(converter.threadKeyString(odd)))
        assertNotEquals(ThreadKey("a:b", "c", "d").encoded, ThreadKey("a", "b:c", "d").encoded)
        val opaque = Json.parseToJsonElement("""{"generation":"g","id":19}""")
        assertEquals(opaque, converter.json(converter.jsonString(opaque)))
    }

    @Test fun cursorRejectsDuplicateAndDetectsEpochOrSequenceGaps() {
        assertEquals(CursorDecision.DUPLICATE, Cursor("e", 9).compare(Cursor("e", 9)))
        assertEquals(CursorDecision.NEXT, Cursor("e", 9).compare(Cursor("e", 10)))
        assertEquals(CursorDecision.SNAPSHOT, Cursor("e", 9).compare(Cursor("e", 11)))
        assertEquals(CursorDecision.SNAPSHOT, Cursor("e", 9).compare(Cursor("new", 1)))
    }

    @Test fun snapshotWatermarkDiscardsOnlySameEpochOlderDeltas() {
        val watermark = Cursor("epoch", 15)
        assertTrue(watermark.covers(Cursor("epoch", 14)))
        assertTrue(watermark.covers(Cursor("epoch", 15)))
        assertFalse(watermark.covers(Cursor("epoch", 16)))
        assertFalse(watermark.covers(Cursor("new-epoch", 1)))
    }

    @Test fun nativeModePayloadUsesExactExperimentalSchemaWithoutPermissionOverrides() {
        val mode = NativeMode.from(pocketJson.parseToJsonElement("""{"mode":"plan","name":"Plan","model":null,"reasoning_effort":null}""").jsonObject)!!
        val payload = mode.payload("catalog-model", "high")
        assertEquals("plan", payload.string("mode"))
        assertEquals(setOf("model", "reasoning_effort", "developer_instructions"), payload.obj("settings").keys)
        assertEquals(JsonNull, payload.obj("settings")["developer_instructions"])
        assertNull(NativeMode.from(buildJsonObject { put("name", "Unsupported"); put("mode", "future") }))
    }

    @Test fun absentCapabilitiesDoNotEnableUnsupportedControls() {
        val runtime = RuntimeInfo("r", "desktop", "v", "store", true, emptySet(), null)
        val empty = PocketState(runtimes = listOf(runtime))
        assertFalse(empty.supports("turn/start", runtime))
        val supported = empty.copy(capabilities = setOf("turn/start"))
        assertTrue(supported.supports("turn/start", runtime))
        assertFalse(supported.supports("collaborationMode/list", runtime))
        assertFalse(supported.supports("turn/start", runtime.copy(online = false)))
    }

    @Test fun opaqueRequestResolutionMatchesOnlyItsRuntimeAndFullIdentifier() {
        val id = Json.parseToJsonElement("""{"generation":"g1","id":3}""")
        val pending = PendingApproval("r1", id, "item/commandExecution/requestApproval", buildJsonObject { put("threadId", "thread") })
        assertFalse(pending.matches("r2", id))
        assertFalse(pending.matches("r1", JsonPrimitive(3)))
        assertTrue(pending.matches("r1", id))
    }

    @Test fun unknownSendIsNeverEligibleForAutomaticReplayEvenWhenNotFound() {
        assertFalse(CommandPolicy.canAutomaticallyReplay("unknown"))
        assertFalse(CommandPolicy.canAutomaticallyReplay("not_found"))
        assertFalse(CommandPolicy.canAutomaticallyReplay("submitted"))
        assertTrue(CommandPolicy.blocksNewSend("unknown"))
        assertFalse(CommandPolicy.blocksNewSend("accepted"))
    }

    @Test fun unknownTelemetryDoesNotEnterTranscriptAndTurnCompletionClearsRunningTurn() {
        val unknown = NativeReducer.reduce(NativeTimeline(), event("future/something", """{"threadId":"thread","value":1}"""), "e:7")
        assertTrue(unknown.items.isEmpty())
        val running = NativeReducer.reduce(unknown, event("turn/started", """{"threadId":"thread","turn":{"id":"t","status":"inProgress"}}"""))
        assertEquals("t", running.activeTurn)
        val finished = NativeReducer.reduce(running, event("turn/completed", """{"threadId":"thread","turn":{"id":"t","status":"completed"}}"""))
        assertNull(finished.activeTurn)
    }
}
