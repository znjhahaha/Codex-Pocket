package dev.codexpocket.app.data

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** A server path never becomes a client path. Only a verified immutable version is cached. */
internal suspend fun downloadAssistantArtifact(directory: File, computer: String, artifact: AssistantArtifact,
    current: () -> Boolean, read: suspend (Long) -> JsonObject): File = withContext(Dispatchers.IO) {
    val size = requireNotNull(artifact.sizeBytes)
    val digest = requireNotNull(artifact.sha256)
    require(artifact.kind == "file" && artifact.availability == "available" && size in 0..24L*1024*1024 &&
        Regex("[a-f0-9]{64}").matches(digest) && artifact.version.isNotBlank())
    fun hash(file: File): String = file.inputStream().use { stream ->
        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val n = stream.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    check(current()) { "Artifact owner changed" }
    check(directory.mkdirs() || directory.isDirectory)
    val key = MessageDigest.getInstance("SHA-256").digest("$computer:${artifact.id}:${artifact.version}:$digest".toByteArray())
        .joinToString("") { "%02x".format(it) }
    val extension = artifact.name.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
    val output = File(directory, key + (extension?.let { ".$it" } ?: ".bin"))
    if (output.isFile && output.length() == size && hash(output) == digest) {
        check(current()); return@withContext output
    }
    val temporary = File.createTempFile("artifact-", ".part", directory)
    try {
        temporary.outputStream().use { stream ->
            var offset = 0L
            do {
                check(current())
                val chunk = read(offset)
                check(current())
                fun text(name: String) = (chunk[name] as? JsonPrimitive)?.content
                fun number(name: String) = (chunk[name] as? JsonPrimitive)?.longOrNull
                require(text("artifactId") == artifact.id && text("version") == artifact.version &&
                    text("sha256") == digest && number("sizeBytes") == size && number("offset") == offset)
                val encoded = requireNotNull(text("data")); require(encoded.length <= 262144)
                val bytes = Base64.getDecoder().decode(encoded)
                require(bytes.size <= 192*1024 && offset + bytes.size <= size)
                val eof = (chunk["eof"] as? JsonPrimitive)?.booleanOrNull
                require(eof != null && eof == (offset + bytes.size == size) && (bytes.isNotEmpty() || eof))
                stream.write(bytes); offset += bytes.size
            } while (!eof)
            stream.flush()
        }
        require(hash(temporary) == digest) { "Artifact digest mismatch" }
        check(current())
        if (!temporary.renameTo(output)) {
            check(output.isFile && output.length() == size && hash(output) == digest) { "Artifact cache promotion failed" }
        }
        output
    } finally { temporary.delete() }
}
