package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MessageTimestampTest {
    private fun item(id: String = "u", raw: String = "{}") = TimelineItem("t", id, "userMessage", raw = pocketJson.parseToJsonElement(raw).jsonObject)
    @Test fun nativeSecondsMillisecondsAndIsoPreserveTheSameInstant() {
        val expected = 1_794_140_000_000L
        for (raw in listOf("""{"createdAt":1794140000}""", """{"sentAtMs":1794140000000}""",
            """{"timestamp":"${java.time.Instant.ofEpochMilli(expected)}"}""")) {
            assertEquals(MessageTimestamp(expected), messageTimestamp(item(raw = raw), null))
        }
    }
    @Test fun missingTimesNeverUseRefreshOrElapsedDuration() {
        assertNull(messageTimestamp(item(raw = """{"updatedAt":1794140000,"createdAt":-1}"""), null))
        assertNull(messageTimestamp(item(raw = """{"createdAt":"invalid"}"""), null))
    }
    @Test fun nativeTurnStartBelongsOnlyToItsInitialUserMessage() {
        val first = item(); val inserted = item("inserted")
        val timeline = NativeTimeline(items = linkedMapOf(first.key to first, inserted.key to inserted),
            turns = mapOf("t" to NativeTurnInfo("t", startedAtMs = 1_794_140_000_000)))
        assertEquals(MessageTimestamp(1_794_140_000_000, true), messageTimestamp(first, timeline))
        assertNull(messageTimestamp(inserted, timeline))
        assertNull(messageTimestamp(first, timeline.copy(turns = mapOf("t" to NativeTurnInfo("t", observedStartNanos = 100)))))
    }

    @Test fun insertedNativeUserItemUsesItsOwnStartNotTheTurnStart() {
        // Sanitized native thread/turns/list field shape from the reported mid-task message.
        val first = item()
        val inserted = item("inserted", """{"startedAtMs":1794145742534,"completedAtMs":1794145742534}""")
        val timeline = NativeTimeline(items = linkedMapOf(first.key to first, inserted.key to inserted),
            turns = mapOf("t" to NativeTurnInfo("t", startedAtMs = 1_794_141_843_897)))
        assertEquals(MessageTimestamp(1_794_145_742_534), messageTimestamp(inserted, timeline))
        assertEquals("21:49", java.time.Instant.ofEpochMilli(messageTimestamp(inserted, timeline)!!.epochMillis)
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
    }

    @Test fun sameTextInsertedTwiceKeepsSeparateItemTimesAcrossSnapshotAndEvents() {
        val snapshot = pocketJson.parseToJsonElement("""{"turns":[{"id":"t","status":"inProgress","items":[
            {"id":"a","type":"userMessage","startedAtMs":1794145742534,"content":[{"type":"text","text":"继续"}]},
            {"id":"b","type":"userMessage","startedAtMs":1794145898995,"content":[{"type":"text","text":"继续"}]}]}]}""").jsonObject
        val history = NativeReducer.snapshot(snapshot)
        assertEquals(listOf(1_794_145_742_534L, 1_794_145_898_995L), history.items.values.map { messageTimestamp(it, history)!!.epochMillis })
        // Cache round-trip preserves the native data rather than using the reload clock.
        for (entry in history.items.values) {
            val cached = entry.copy(raw = pocketJson.parseToJsonElement(entry.raw.toString()).jsonObject)
            assertEquals(messageTimestamp(entry, history), messageTimestamp(cached, null))
            var live = NativeTimeline()
            for (method in listOf("item/started", "item/completed")) {
                live = NativeReducer.reduce(live, buildJsonObject {
                    put("method", method); put("params", buildJsonObject { put("turnId", "t"); put("item", entry.raw) })
                })
                assertEquals(messageTimestamp(entry, history), messageTimestamp(live.items.getValue(entry.key), live))
            }
        }
    }

    @Test fun invalidNativeItemStartDoesNotUseItsCompletionOrSyncTime() {
        assertNull(messageTimestamp(item(raw = """{"startedAtMs":-1,"completedAtMs":1794145742534,"updatedAt":1794145898995}"""), null))
    }
}
