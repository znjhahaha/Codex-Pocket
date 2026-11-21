package dev.codexpocket.wire

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Base64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

/** Owned by PocketClient's gate. Partial replies never escape as application events. */
internal class ReplyChunks {
    private class Assembly(val total: Int) {
        val blocks = ArrayDeque<ByteArray>()
        var offset = 0
        var sequence = 0
        private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        private val input = ByteBuffer.allocate(BLOCK + 4)
        private val chars = CharBuffer.allocate(1024)

        // kotlinx.serialization's stream reader replaces malformed UTF-8, so
        // validate before parsing, carrying incomplete code points between blocks.
        fun validate(block: ByteArray, last: Boolean) {
            input.put(block).flip()
            while (true) {
                chars.clear()
                val status = decoder.decode(input, chars, last)
                if (status.isError) status.throwException()
                if (status.isUnderflow) break
            }
            input.compact()
            if (last) { chars.clear(); decoder.flush(chars).let { if (it.isError) it.throwException() } }
        }
    }
    /** Consume original blocks; no joined byte array or whole-response String. */
    private class BlockStream(private val blocks: ArrayDeque<ByteArray>) : InputStream() {
        private var offset = 0
        override fun read(): Int {
            val block = blocks.peekFirst() ?: return -1
            val value = block[offset++].toInt() and 0xff
            if (offset == block.size) { blocks.removeFirst(); offset = 0 }
            return value
        }
        override fun read(target: ByteArray, start: Int, length: Int): Int {
            require(start >= 0 && length >= 0 && start <= target.size - length)
            if (length == 0) return 0
            val block = blocks.peekFirst() ?: return -1
            val count = minOf(length, block.size - offset)
            block.copyInto(target, start, offset, offset + count); offset += count
            if (offset == block.size) { blocks.removeFirst(); offset = 0 }
            return count
        }
        override fun close() { blocks.clear() }
    }
    private val partial = mutableMapOf<String, Assembly>()
    // Reserve declared totals even though storage grows only as blocks arrive.
    private var allocated = 0
    fun discard(id: String) { partial.remove(id)?.let { allocated -= it.total } }
    fun clear() { partial.clear(); allocated = 0 }
    @OptIn(ExperimentalSerializationApi::class)
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
            entry = Assembly(total); partial[id] = entry; allocated += total
        }
        if (entry.total != total || entry.sequence != sequence || block.size != minOf(BLOCK, total - entry.offset)) {
            discard(id); invalid()
        }
        try { entry.validate(block, entry.offset + block.size == total) }
        catch (_: Exception) { discard(id); invalid() }
        entry.blocks.addLast(block); entry.offset += block.size; entry.sequence++
        if (entry.offset != total) return null
        discard(id)
        val result = try {
            BlockStream(entry.blocks).use { Json.decodeFromStream(JsonObject.serializer(), it) }
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
