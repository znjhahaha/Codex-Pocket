package dev.codexpocket.wire

import java.io.File
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit isolated-VM acceptance. Uses real SSH, TLS, broker and native app-server. */
class SshLinuxLiveTest {
    @Test fun signedBootstrapPairsAndReadsTheOriginalNativeRuntime() = runBlocking {
        val fixture = System.getenv("POCKET_LINUX_QA")
        assumeTrue("Run only against the prepared disposable Linux VM", fixture != null)
        val root = File(fixture!!)
        val known = File(root, "known_hosts").readText().trim().split(' ')[2]
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(known)))
        val target = SshTarget("127.0.0.1", 22247, "pocket", fingerprint,
            SshAuthentication.PrivateKey(File(root, "test-key").readText()), 43821)
        assertEquals(fingerprint, probeSshHostKey(target.host, target.port))
        val bundle = File(System.getenv("POCKET_LINUX_BUNDLE") ?: error("Signed bundle required"))
        val prepared = prepareSshHost(target, { arch ->
            assertEquals("x86_64", arch); LinuxRuntimeBundle.verified(bundle)
        })
        assertTrue(prepared.nativeInstalled)
        val connection = SshPocketConnection(target)
        try {
            connection.connect(prepared.pairing.connectionConfig())
            val identity = connection.request("pair", buildJsonObject {
                put("pairingToken", prepared.pairing.pairingToken); put("deviceId", UUID.randomUUID().toString()); put("deviceName", "Pocket SSH acceptance")
            }).jsonObject
            val hello = connection.request("hello", buildJsonObject {
                put("protocol", 1); put("deviceId", identity.getValue("deviceId")); put("deviceSecret", identity.getValue("deviceSecret"))
            }).jsonObject
            assertEquals(prepared.pairing.computerId, hello.string("computerId"))
            assertTrue(hello.getValue("capabilities").jsonArray.any { it.jsonPrimitive.content == "runtime.ensure.cli" })
            val ensured = connection.request("runtime/ensure", buildJsonObject { put("source", "cli") }, UUID.randomUUID().toString(), 30_000).jsonObject
            assertTrue(ensured.string("phase") in setOf("starting_cli", "ready"))
            var native: JsonObject? = null
            withTimeout(45_000) {
                while (native == null) {
                    val directory = connection.request("runtimes/list").jsonObject
                    native = directory.getValue("data").jsonArray.map { it.jsonObject }.firstOrNull {
                        it["online"] == JsonPrimitive(true) && it["source"] == JsonPrimitive("cli") &&
                            it["role"]?.jsonObject?.get("kind") == JsonPrimitive("cliDaemon")
                    }
                    if (native == null) delay(500)
                }
            }
            assertNotNull(native)
            val history = connection.request("codex/request", buildJsonObject {
                put("runtimeId", native!!.string("id")); put("method", "thread/list"); put("params", buildJsonObject { put("limit", 10) })
            }).jsonObject
            assertTrue(history["data"] is JsonArray)
            println("LIVE_SSH_NATIVE_OK: pinned SSH, signed bootstrap, preserved endpoint, native CLI directory")
        } finally { connection.close() }
    }
}
