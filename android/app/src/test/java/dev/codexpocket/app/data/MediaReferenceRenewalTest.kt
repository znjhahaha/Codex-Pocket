package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MediaReferenceRenewalTest {
    private fun item(first: String, status: String = "ready", second: String = "c", runtime: String = "r") =
        TimelineItem.from("turn", buildJsonObject {
            put("id", "user"); put("type", "userMessage")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "Keep my message") })
                listOf(first to status, second to "ready").forEachIndexed { n, (id, state) -> add(buildJsonObject {
                    put("type", "image"); put("contentPart", n + 1); put("mediaId", id.repeat(32)); put("status", state)
                    put("runtimeId", runtime); put("threadId", "thread"); put("turnId", "turn"); put("itemId", "user")
                    put("mimeType", "image/png"); put("byteLength", 10)
                }) }
            })
        })

    @Test fun readableSiblingCannotConfirmTheExpiredTargetsRecovery() {
        val old = item("a", "expired")
        assertNull(renewedMediaItem(old, item("a", "expired", second = "d"), itemMedia(old).first()))
    }

    @Test fun onlyTheRequestedSlotChangesAndLiveFieldsRemainIntact() {
        val old = item("a", "expired").copy(text = "live text", status = "completed", complete = true)
        val result = renewedMediaItem(old, item("b", second = "d"), itemMedia(old).first())!!
        assertEquals(listOf("b".repeat(32), "c".repeat(32)), itemMedia(result).map { it.mediaId })
        assertEquals(old.text, result.text); assertEquals(old.status, result.status); assertEquals(old.complete, result.complete)
        assertEquals(old.raw.array("content").first(), result.raw.array("content").first())
    }

    @Test fun anotherRuntimeAndAnAlreadyReplacedReferenceAreRejected() {
        val old = item("a", "expired")
        val ref = itemMedia(old).first()
        assertNull(renewedMediaItem(old, item("b", runtime = "other"), ref))
        assertNull(renewedMediaItem(item("c"), item("b"), ref))
    }
}
