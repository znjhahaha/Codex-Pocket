package dev.codexpocket.wire

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReplyChunksMemoryTest {
    @Test fun largeHistoryReplyFits192MiBWithExistingAppState() {
        val classpath = listOf(ReplyChunksMemoryProbe::class.java, ReplyChunks::class.java,
            Unit::class.java, Json::class.java, KSerializer::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct()
            .joinToString(File.pathSeparator)
        val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx192m", "-XX:+UseSerialGC", "-XX:-CompactStrings", "-cp", classpath,
            ReplyChunksMemoryProbe::class.java.name).redirectErrorStream(true).start()
        try {
            assertTrue("bounded history probe timed out", child.waitFor(45, TimeUnit.SECONDS))
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(output, 0, child.exitValue())
            assertTrue(output, output.contains("items=4096"))
            println(output.trim())
        } finally { if (child.isAlive) child.destroyForcibly() }
    }
}

/** Generates the wire blocks directly; the fixture never keeps a full JSON text or byte array. */
object ReplyChunksMemoryProbe {
    private var existingAppState: List<ByteArray> = emptyList()
    @JvmStatic fun main(args: Array<String>) {
        existingAppState = List(166) { ByteArray(1024 * 1024) { 1 } }
        val receiver = ReplyChunks()
        repeat(3) { decodeHistory(receiver) }
        check(existingAppState.sumOf { it[0].toLong() } == 166L)
    }

    private fun decodeHistory(receiver: ReplyChunks) {
        val prefix = "{\"id\":\"history\",\"result\":{\"items\":[".toByteArray()
        val row = ("{\"text\":\"" + "测试🚀".repeat(210) + "\",\"status\":\"inProgress\"}").toByteArray()
        val suffix = "]}}".toByteArray()
        val count = 4096
        val total = prefix.size + row.size * count + count - 1 + suffix.size
        check(total in 8_000_000..ReplyChunks.MAX_REPLY)
        var block = ByteArray(ReplyChunks.BLOCK)
        var used = 0
        var sequence = 0
        var reply: JsonObject? = null
        fun send() {
            val frame = buildJsonObject { put("params", buildJsonObject {
                put("replyId", "history"); put("sequence", sequence++); put("totalBytes", total)
                put("data", Base64.getEncoder().encodeToString(if (used == block.size) block else block.copyOf(used)))
            }) }
            reply = receiver.accept(frame, setOf("history"))
            used = 0
        }
        fun append(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val n = minOf(block.size - used, bytes.size - offset)
                bytes.copyInto(block, used, offset, offset + n); used += n; offset += n
                if (used == block.size) send()
            }
        }
        append(prefix)
        repeat(count) { index -> if (index > 0) append(byteArrayOf(','.code.toByte())); append(row) }
        append(suffix)
        if (used > 0) send()
        val items = checkNotNull(reply)["result"]!!.jsonObject["items"]!!.jsonArray
        check(items.size == count)
        for (item in items) {
            check(item.jsonObject["text"]!!.jsonPrimitive.content == "测试🚀".repeat(210))
            check(item.jsonObject["status"]!!.jsonPrimitive.content == "inProgress")
        }
        println("replyBytes=$total items=${items.size} heapLimitMiB=${Runtime.getRuntime().maxMemory() / 1024 / 1024} retainedAppMiB=166")
    }
}
