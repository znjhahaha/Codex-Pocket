package dev.codexpocket.wire

import java.io.File
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.*
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** Only a release signed by Pocket may supply remotely installed executables. */
class LinuxRuntimeBundle private constructor(val directory: File, val version: String, val architecture: String,
    val generation: String, val files: List<String>, internal val digests: Map<String, String>) {
    companion object {
        private val allowed = setOf("pocket-agent", "pocket-runtime-host")
        private const val PUBLIC_KEY = "BYQSPxB4SIYDiqclmPaERfItHjDK4FP63vncq8usSgQ="
        fun verified(directory: File): LinuxRuntimeBundle = verifiedAgainst(directory, Base64.getDecoder().decode(PUBLIC_KEY))

        /** The trust anchor is a parameter only so this module's tests can sign fixtures; the app cannot reach it. */
        internal fun verifiedAgainst(directory: File, trusted: ByteArray): LinuxRuntimeBundle =
            try { read(directory, trusted) }
            catch (_: Exception) { throw WireException("ssh_runtime_invalid", "Linux runtime could not be verified") }

        private fun read(directory: File, trusted: ByteArray): LinuxRuntimeBundle {
            val manifest = File(directory, "runtime-manifest.json")
            require(manifest.length() in 1..65536)
            val bytes = manifest.readBytes()
            val signatureFile = File(directory, "runtime-manifest.sig")
            require(signatureFile.length() in 1..256)
            val sealed = signatureFile.readBytes()
            val signature = Base64.getDecoder().decode(sealed.toString(Charsets.US_ASCII).trim())
            val provider = BouncyCastleProvider()
            val prefix = byteArrayOf(0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00)
            require(trusted.size == 32)
            val key = KeyFactory.getInstance("Ed25519", provider).generatePublic(X509EncodedKeySpec(prefix + trusted))
            require(Signature.getInstance("Ed25519", provider).run { initVerify(key); update(bytes); verify(signature) }) { "Linux runtime signature mismatch" }
            val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(value.integer("schema") == 1L && value.string("product") == "Codex Pocket")
            val version = value.string("version")
            val architecture = value.string("architecture")
            require(version.matches(Regex("[0-9A-Za-z._-]{1,96}")) && architecture in setOf("x86_64", "aarch64"))
            val entries = value.getValue("files").jsonArray.map { it.jsonObject }
            require(entries.size == allowed.size && entries.map { it.string("path") }.toSet() == allowed)
            val digests = linkedMapOf<String, String>()
            entries.forEach { entry ->
                val file = File(directory, entry.string("path"))
                require(file.canonicalFile.parentFile == directory.canonicalFile && file.isFile)
                require(file.length() == entry.integer("bytes") && file.length() in 1..128 * 1024 * 1024)
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) {
                    val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n)
                } }
                require(hex(hash.digest()) == entry.string("sha256")) { "Linux runtime file changed" }
                digests[entry.string("path")] = entry.string("sha256")
            }
            val generation = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
            digests["runtime-manifest.json"] = generation
            digests["runtime-manifest.sig"] = hex(MessageDigest.getInstance("SHA-256").digest(sealed))
            return LinuxRuntimeBundle(directory, version, architecture, generation, digests.keys.toList(), digests)
        }
        private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
