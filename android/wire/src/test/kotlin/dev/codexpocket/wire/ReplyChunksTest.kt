package dev.codexpocket.wire

import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReplyChunksTest {
    private fun frames(id: String, reply: JsonObject): List<JsonObject> {
        val bytes = reply.toString().toByteArray()
        return bytes.asList().chunked(ReplyChunks.BLOCK).mapIndexed { sequence, block -> frame(id, sequence, bytes.size, block.toByteArray()) }
    }
    private fun frame(id: String, sequence: Int, total: Int, bytes: ByteArray) = buildJsonObject {
        put("method", ReplyChunks.METHOD); put("params", buildJsonObject {
            put("replyId", id); put("sequence", sequence); put("totalBytes", total); put("data", Base64.getEncoder().encodeToString(bytes))
        })
    }
    private fun rejects(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is WireException)
        assertEquals("invalid_reply", (failure as WireException).code)
    }
    @Test fun interleavedRepliesKeepIdentityAndUnicode() {
        val replies = listOf("a", "b").associateWith { buildJsonObject { put("id", it); put("result", "测试🚀".repeat(9000)) } }
        val chunks = replies.mapValues { frames(it.key, it.value) }
        val receiver = ReplyChunks(); val received = mutableMapOf<String, JsonObject>()
        for (i in chunks.getValue("a").indices) for (id in replies.keys) receiver.accept(chunks.getValue(id)[i], replies.keys)?.let { received[id] = it }
        assertEquals(replies, received)
    }
    @Test fun duplicateSkippedAndMismatchedChunksAreRejected() {
        val reply = buildJsonObject { put("id", "r"); put("result", "x".repeat(50_000)) }
        val chunks = frames("r", reply); val receiver = ReplyChunks()
        assertNull(receiver.accept(chunks[0], setOf("r")))
        rejects { receiver.accept(chunks[0], setOf("r")) }
        rejects { ReplyChunks().accept(chunks[1], setOf("r")) }
        rejects { ReplyChunks().accept(frame("r", 0, 32, byteArrayOf(1)), setOf("r")) }
    }
    @Test fun cancelledReplyCannotResurrectAndMemoryCanBeReleased() {
        val chunks = frames("r", buildJsonObject { put("id", "r"); put("result", "x".repeat(50_000)) })
        val receiver = ReplyChunks(); receiver.accept(chunks[0], setOf("r")); receiver.discard("r")
        assertNull(receiver.accept(chunks[1], emptySet())); receiver.clear()
        var reply: JsonObject? = null
        for (chunk in chunks) reply = receiver.accept(chunk, setOf("r"))
        assertNotNull(reply)
    }
    @Test fun allocationAndReassembledIdentityAreBounded() {
        rejects { ReplyChunks().accept(frame("r", 0, ReplyChunks.MAX_REPLY + 1, ByteArray(ReplyChunks.BLOCK)), setOf("r")) }
        val receiver = ReplyChunks()
        for (id in listOf("a", "b")) receiver.accept(frame(id, 0, ReplyChunks.MAX_REPLY, ByteArray(ReplyChunks.BLOCK)), setOf(id))
        rejects { receiver.accept(frame("c", 0, ReplyChunks.MAX_REPLY, ByteArray(ReplyChunks.BLOCK)), setOf("c")) }
        val wrongId = frames("r", buildJsonObject { put("id", "another"); put("result", "value") }).single()
        rejects { ReplyChunks().accept(wrongId, setOf("r")) }
    }
}
