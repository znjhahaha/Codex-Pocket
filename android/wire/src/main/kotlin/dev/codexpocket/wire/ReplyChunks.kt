package dev.codexpocket.wire

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.*

/** Owned by PocketClient's gate. Partial replies never escape as application events. */
internal class ReplyChunks {
    private data class Assembly(val bytes: ByteArray, var offset: Int = 0, var sequence: Int = 0)
    private val partial = mutableMapOf<String, Assembly>()
    private var allocated = 0
    fun discard(id: String) { partial.remove(id)?.let { allocated -= it.bytes.size } }
    fun clear() { partial.clear(); allocated = 0 }
    fun accept(message: JsonObject, expected: Set<String>): JsonObject? {
        fun invalid(): Nothing = throw WireException("invalid_reply", "Invalid reply chunk")
        val p = message["params"] as? JsonObject ?: invalid()
        val id = (p["replyId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
        if (id.isBlank() || id.length > 128) invalid()
        if (id !in expected) { discard(id); return null } // timed out/cancelled reply; never resurrect it
        val sequence = (p["sequence"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: invalid()
        val total = (p["totalBytes"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: invalid()
        if (total !in 1..MAX_REPLY || sequence !in 0..(MAX_REPLY / BLOCK)) invalid()
        val encoded = (p["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
        if (encoded.length > ((BLOCK + 2) / 3) * 4) invalid()
        val block = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { invalid() }
        var entry = partial[id]
        if (entry == null) {
            if (sequence != 0 || allocated.toLong() + total > MAX_BUFFERED || partial.size >= 16) invalid()
            entry = Assembly(ByteArray(total)); partial[id] = entry; allocated += total
        }
        if (entry.bytes.size != total || entry.sequence != sequence || block.size != minOf(BLOCK, total - entry.offset)) invalid()
        block.copyInto(entry.bytes, entry.offset); entry.offset += block.size; entry.sequence++
        if (entry.offset != total) return null
        discard(id)
        val result = try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(entry.bytes)).toString()
            Json.parseToJsonElement(text) as? JsonObject ?: invalid()
        } catch (_: Exception) { invalid() }
        if ((result["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content != id) invalid()
        if (result.containsKey("method") || result.containsKey("result") == result.containsKey("error")) invalid()
        return result
    }
    companion object {
        const val CAPABILITY = "transport.chunkedReplies.v1"
        const val METHOD = "pocket/replyChunk"
        const val BLOCK = 16 * 1024
        const val MAX_REPLY = 16 * 1024 * 1024
        const val MAX_BUFFERED = 32 * 1024 * 1024
    }
}
