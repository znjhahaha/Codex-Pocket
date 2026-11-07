package dev.codexpocket.wire

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PairingAndFramesTest {
    private val pin = "ab".repeat(32)
    private fun qr(url: String = "wss://relay.example/pocket", room: String = "room_123", expiry: Long = 200) =
        buildJsonObject {
            put("v", 1); put("relayUrl", url); put("room", room); put("phoneToken", "PHONE_SECRET")
            put("certificateSha256", pin); put("pairingToken", "PAIR_SECRET"); put("expiresAt", expiry)
            put("computerName", "Desktop")
        }.toString()

    @Test fun pairingAndSavedConfigValidateAndRedact() {
        val pair = PairingCode.parse(qr(), 100)
        assertEquals("wss://relay.example/pocket/v1/tunnel/room_123/phone", ConnectionConfig(pair.relayUrl, pair.room, pair.phoneToken, pair.certificateSha256).phoneUrl())
        assertFalse(pair.toString().contains("SECRET"))
        assertFalse(ConnectionConfig(pair.relayUrl, pair.room, pair.phoneToken, pin).toString().contains("SECRET"))
        for (url in listOf("ws://relay.example", "wss://secret@relay.example", "wss://relay.example?secret=x", "wss://relay.example/#secret")) {
            assertThrows(IllegalArgumentException::class.java) { PairingCode.parse(qr(url), 100) }
            assertThrows(IllegalArgumentException::class.java) { ConnectionConfig(url, "room", "token", pin) }
        }
        for (room in listOf("..", ".", "a/b", "a%2fb", "", "a?b", "a\\b")) {
            assertThrows(IllegalArgumentException::class.java) { PairingCode.parse(qr(room = room), 100) }
        }
        assertThrows(IllegalArgumentException::class.java) { PairingCode.parse(qr(expiry = 100), 100) }
        assertThrows(IllegalArgumentException::class.java) { PairingCode.parse(qr().replace("\"v\":1", "\"v\":2"), 100) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionConfig("wss://relay.example", "room", "token", "0".repeat(63)) }
        val error = assertThrows(IllegalArgumentException::class.java) { PairingCode.parse("PAIR_SECRET", 100) }
        assertFalse(error.toString().contains("PAIR_SECRET"))
    }

    @Test fun fragmentedAndCoalescedFramesRoundTrip() {
        val expected = listOf(buildJsonObject { put("a", "你好") }, buildJsonObject { put("b", 2) })
        val bytes = expected.flatMap { FrameCodec.encode(it).toList() }.toByteArray()
        for (fragment in listOf(1, 2, 3, 4, 7, 65536)) {
            val decoder = FrameCodec(); val actual = mutableListOf<JsonObject>()
            bytes.toList().chunked(fragment).forEach { actual.addAll(decoder.feed(it.toByteArray())) }
            assertEquals(expected, actual)
        }
    }

    @Test fun unsignedLengthsAndMalformedFramesFailBeforeAllocation() {
        for (prefix in listOf(byteArrayOf(-1,-1,-1,-1), byteArrayOf(-128,0,0,0), byteArrayOf(1,0,0,1), byteArrayOf(0,0,0,0))) {
            assertThrows(WireException::class.java) { FrameCodec().feed(prefix) }
        }
        assertThrows(WireException::class.java) { FrameCodec().feed(byteArrayOf(0,0,0,1,0xff.toByte())) }
        assertThrows(WireException::class.java) { FrameCodec().feed(byteArrayOf(0,0,0,2,91,93)) }
    }

    @Test fun maximumFrameIsAcceptedIncrementallyAndLargerFrameIsRejected() {
        val text = "{\"x\":\"" + "a".repeat(FrameCodec.MAX_FRAME - 8) + "\"}"
        assertEquals(FrameCodec.MAX_FRAME, text.toByteArray().size)
        val framed = java.nio.ByteBuffer.allocate(text.length + 4).putInt(text.length).put(text.toByteArray()).array()
        val decoder = FrameCodec(); var result: JsonObject? = null
        var offset = 0
        while (offset < framed.size) {
            val end = minOf(offset + 65536, framed.size)
            decoder.feed(framed.copyOfRange(offset, end)).forEach { result = it }; offset = end
        }
        assertEquals(FrameCodec.MAX_FRAME - 8, result!!["x"]!!.jsonPrimitive.content.length)
        assertThrows(WireException::class.java) { FrameCodec.encode(buildJsonObject { put("x", "a".repeat(FrameCodec.MAX_FRAME)) }) }
    }
}
