package dev.codexpocket.app.data

import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

class AssistantArtifactDownloadTest {
    private val bytes = "成果内容".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val artifact = AssistantArtifact("a", "t", "op", "../报告.md", "file", null, null, "v1", 1, "available", sizeBytes = bytes.size.toLong(), sha256 = hash)
    private fun chunk(offset: Long, digest: String = hash) = buildJsonObject {
        put("artifactId", "a"); put("version", "v1"); put("sha256", digest); put("sizeBytes", bytes.size); put("offset", offset)
        put("data", Base64.getEncoder().encodeToString(bytes)); put("eof", true)
    }
    @Test fun verifiesVersionAndDigestAndReusesOnlyImmutableContent() = runBlocking {
        val dir = Files.createTempDirectory("assistant-artifact").toFile()
        try {
            val file = downloadAssistantArtifact(dir, "computer", artifact, {true}) { chunk(it) }
            assertEquals(dir.canonicalFile, file.parentFile.canonicalFile)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(file, downloadAssistantArtifact(dir, "computer", artifact, {true}) { error("Should use verified cache") })
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsChangedOwnerOffsetVersionAndCorruptionWithoutPublishing() = runBlocking {
        for (mode in 0..3) {
            val dir = Files.createTempDirectory("assistant-reject").toFile()
            var owner = true
            try {
                val result = runCatching { downloadAssistantArtifact(dir, "computer", artifact, {owner}) {
                    when(mode) {
                        0 -> { owner=false; chunk(it) }
                        1 -> chunk(it+1)
                        2 -> JsonObject(chunk(it)+( "version" to JsonPrimitive("other")))
                        else -> JsonObject(chunk(it)+( "data" to JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(bytes.size)))))
                    }
                } }
                assertTrue(result.isFailure); assertTrue(dir.listFiles().orEmpty().isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }
}
