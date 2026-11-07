package dev.codexpocket.wire

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

internal class FrameCodec {
    private val header = ByteArray(4)
    private var headerSize = 0
    private var body: ByteArray? = null
    private var bodySize = 0
    fun feed(bytes: ByteArray): List<JsonObject> {
        val messages = mutableListOf<JsonObject>()
        var offset = 0
        while (offset < bytes.size) {
            if (body == null) {
                val n = minOf(4 - headerSize, bytes.size - offset)
                bytes.copyInto(header, headerSize, offset, offset + n); offset += n; headerSize += n
                if (headerSize < 4) continue
                val length = ByteBuffer.wrap(header).int.toLong() and 0xffffffffL
                if (length !in 1..MAX_FRAME.toLong()) throw WireException("invalid_frame", "Invalid frame length")
                body = ByteArray(length.toInt()); bodySize = 0
            }
            val target = body!!
            val n = minOf(target.size - bodySize, bytes.size - offset)
            bytes.copyInto(target, bodySize, offset, offset + n); offset += n; bodySize += n
            if (bodySize == target.size) {
                try {
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(target)).toString()
                    messages += Json.parseToJsonElement(text).jsonObject
                } catch (_: Exception) { throw WireException("invalid_frame", "Invalid JSON frame") }
                body = null; headerSize = 0; bodySize = 0
            }
        }
        return messages
    }
    companion object {
        const val MAX_FRAME = 16 * 1024 * 1024
        fun encode(message: JsonObject): ByteArray {
            val bytes = message.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size !in 1..MAX_FRAME) throw WireException("invalid_frame", "Invalid frame length")
            return ByteBuffer.allocate(bytes.size + 4).putInt(bytes.size).put(bytes).array()
        }
    }
}
