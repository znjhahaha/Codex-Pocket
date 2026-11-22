package dev.codexpocket.wire

import java.io.File
import java.security.Provider
import java.security.Security
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Explicit isolated-VM acceptance. Uses real SSH, TLS, broker and native app-server.
 * POCKET_LINUX_QA names the VM fixture; POCKET_LINUX_BUNDLE a release-signed bundle, or POCKET_LINUX_BIN the
 * cross-built executables, which are then signed by a key only these tests trust.
 */
class SshLinuxLiveTest {
    private suspend fun wireFailure(block: suspend () -> Unit): String =
        try { block(); "none" } catch (failure: WireException) { failure.code }

    @Test fun signedBootstrapPairsAndReadsTheOriginalNativeRuntime() = runBlocking {
        LinuxQa.assume()
        val target = LinuxQa.pocket
        assertEquals(LinuxQa.fingerprint, probeSshHostKey(target.host, target.port))
        val release = System.getenv("POCKET_LINUX_BUNDLE")?.let(::File)
        val prepared = prepareSshHost(target, { arch ->
            assertEquals(LinuxQa.architecture, arch)
            if (release != null) LinuxRuntimeBundle.verified(release) else TestRuntimeSigner.verified(LinuxQa.base)
        })
        assertTrue(prepared.nativeInstalled)
        assertTrue(prepared.persistent)
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
            println("LIVE_SSH_NATIVE_OK: pinned SSH, signed bootstrap, preserved endpoint, native CLI directory" +
                if (release != null) " (release-signed bundle)" else " (bundle signed by the test key)")
        } finally { connection.close() }
    }

    @Test fun passwordAndCommonPrivateKeyFormatsAuthenticateAndWrongSecretsDoNot() = runBlocking {
        LinuxQa.assume()
        suspend fun whoami(authentication: SshAuthentication) = LinuxQa.sh("id -un\n", LinuxQa.target("pocketqa2", authentication)).trim()
        // The PEM key of the fixture, then what ssh-keygen writes today, with and without a passphrase.
        assertEquals("pocket", LinuxQa.sh("id -un\n").trim())
        assertEquals("pocketqa2", whoami(SshAuthentication.PrivateKey(LinuxQa.ordinaryKey("ed25519", ""))))
        val passphrase = UUID.randomUUID().toString()
        for ((type, extra) in listOf("ed25519" to "", "rsa" to "-b 3072", "ecdsa" to "-b 256")) {
            val sealed = LinuxQa.ordinaryKey(type, passphrase, extra)
            assertTrue("$type key is encrypted", sealed.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
            assertEquals(type, "pocketqa2", whoami(SshAuthentication.PrivateKey(sealed, passphrase)))
            assertEquals(type, "ssh_connection_failed", wireFailure { whoami(SshAuthentication.PrivateKey(sealed, "not the passphrase")) })
            assertEquals(type, "ssh_connection_failed", wireFailure { whoami(SshAuthentication.PrivateKey(sealed)) })
        }
        val password = UUID.randomUUID().toString()
        try {
            LinuxQa.sh("""
                set -eu
                printf '%s:%s\n' pocketqa2 ${shellWord(password)} | sudo chpasswd
                printf 'Match User pocketqa2\n    PasswordAuthentication yes\n' | sudo tee /etc/ssh/sshd_config.d/95-pocket-qa-password.conf >/dev/null
                sudo sshd -t
                sudo systemctl reload ssh
            """.trimIndent())
            assertEquals("pocketqa2", whoami(SshAuthentication.Password(password)))
            assertEquals("ssh_connection_failed", wireFailure { whoami(SshAuthentication.Password("$password.")) })
            // The administrator's account never accepts a password.
            assertEquals("ssh_connection_failed", wireFailure { LinuxQa.sh("true\n", LinuxQa.target("pocket", SshAuthentication.Password(password))) })
        } finally {
            LinuxQa.sh("sudo passwd -l pocketqa2 >/dev/null; sudo rm -f /etc/ssh/sshd_config.d/95-pocket-qa-password.conf; sudo systemctl reload ssh\n")
        }
        assertEquals("ssh_connection_failed", wireFailure { whoami(SshAuthentication.Password(password)) })
    }

    @Test fun changedHostKeyIsRefusedBeforeAuthenticationUploadOrExecution() = runBlocking {
        LinuxQa.assume()
        val prepared = LinuxQa.prepare()
        val phone = LinuxQa.pair(LinuxQa.pocket, prepared)
        val before = LinuxQa.installation()
        // A pin that never was this host's key.
        val stranger = LinuxQa.target("pocket", LinuxQa.pocket.authentication, 43821, "SHA256:" + "A".repeat(43))
        var asked = false
        assertEquals("ssh_host_key_changed", wireFailure { prepareSshHost(stranger, { asked = true; TestRuntimeSigner.verified(LinuxQa.base) }) })
        assertFalse(asked)
        assertEquals("ssh_host_key_changed", wireFailure { SshPocketConnection(stranger).use { it.connect(phone.config) } })
        // The host really presents another key at the saved address; the old key is put back by the host itself.
        val replaced = SshSession.open(LinuxQa.pocket).use { admin ->
            try {
                admin.execute("""
                    set -eu
                    sudo rm -f /etc/ssh/pocket-qa-other-key /etc/ssh/pocket-qa-other-key.pub
                    sudo ssh-keygen -q -t ed25519 -N '' -f /etc/ssh/pocket-qa-other-key
                    sudo cp /etc/ssh/sshd_config.d/90-pocket-qa.conf /etc/ssh/pocket-qa-host-key.conf
                    sudo systemctl stop pocket-qa-restore-key.timer 2>/dev/null || true
                    sudo systemd-run --quiet --on-active=120 --unit=pocket-qa-restore-key sh -c 'cp /etc/ssh/pocket-qa-host-key.conf /etc/ssh/sshd_config.d/90-pocket-qa.conf; systemctl reload ssh'
                    printf 'HostKey /etc/ssh/pocket-qa-other-key\n' | sudo tee /etc/ssh/sshd_config.d/90-pocket-qa.conf >/dev/null
                    sudo sshd -t
                    sudo systemctl reload ssh
                """.trimIndent(), 60, emptyMap())
                val observed = probeSshHostKey(LinuxQa.host, LinuxQa.pocket.port)
                assertNotEquals(LinuxQa.fingerprint, observed)
                assertEquals("ssh_host_key_changed", wireFailure { phone.online { _, _ -> } })
                assertEquals("ssh_host_key_changed", wireFailure { LinuxQa.prepare() })
                observed
            } finally {
                admin.execute("""
                    sudo cp /etc/ssh/pocket-qa-host-key.conf /etc/ssh/sshd_config.d/90-pocket-qa.conf
                    sudo systemctl reload ssh
                    sudo systemctl stop pocket-qa-restore-key.timer 2>/dev/null
                    sudo rm -f /etc/ssh/pocket-qa-other-key /etc/ssh/pocket-qa-other-key.pub /etc/ssh/pocket-qa-host-key.conf
                """.trimIndent(), 60, emptyMap())
            }
        }
        assertTrue(replaced.startsWith("SHA256:"))
        assertEquals(LinuxQa.fingerprint, probeSshHostKey(LinuxQa.host, LinuxQa.pocket.port))
        assertEquals(before, LinuxQa.installation())
        assertEquals(prepared.pairing.computerId, phone.online { _, hello -> hello.string("computerId") })
    }

    @Test fun unverifiableBundlesAreRefusedBeforeTheHostIsTouched() = runBlocking {
        LinuxQa.assume()
        LinuxQa.prepare()
        val before = LinuxQa.installation()
        val changedExecutable = LinuxQa.bundle("0.0.1-qa").also { File(it, "pocket-agent").appendBytes(byteArrayOf(0)) }
        val changedManifest = LinuxQa.bundle("0.0.1-qa").also { File(it, "runtime-manifest.json").apply { writeText(readText().replace("0.0.1-qa", "0.0.2-qa")) } }
        val foreignSignature = LinuxQa.bundle("0.0.1-qa").also { File(it, "runtime-manifest.sig").writeText(File(LinuxQa.base, "runtime-manifest.sig").readText()) }
        for (directory in listOf(changedExecutable, changedManifest, foreignSignature))
            assertEquals("ssh_runtime_invalid", wireFailure { LinuxQa.prepare(directory = directory) })
        // Signed by the test key but offered to the shipped trust, as the application would check it.
        assertEquals("ssh_runtime_invalid", wireFailure { prepareSshHost(LinuxQa.pocket, { LinuxRuntimeBundle.verified(LinuxQa.bundle("0.0.1-qa")) }) })
        // Correctly signed for a processor this host does not have.
        assertEquals("ssh_runtime_invalid", wireFailure { LinuxQa.prepare(directory = LinuxQa.bundle("0.0.1-qa", LinuxQa.otherArchitecture)) })
        assertEquals("ssh_runtime_missing", wireFailure { prepareSshHost(LinuxQa.pocket, { throw WireException("ssh_runtime_missing", "No bundle") }) })
        assertEquals(before, LinuxQa.installation())
    }

    @Test fun preparingAPreparedHostChangesNothingAndKeepsThePairing() = runBlocking {
        LinuxQa.assume()
        val first = LinuxQa.prepare()
        val phone = LinuxQa.pair(LinuxQa.pocket, first)
        val before = LinuxQa.installation()
        val steps = mutableListOf<String>()
        val second = LinuxQa.prepare(progress = { steps += it })
        assertFalse("nothing is uploaded again", steps.any { "上传" in it })
        assertEquals(before, LinuxQa.installation())
        assertEquals(first.pairing.computerId, second.pairing.computerId)
        assertEquals(first.pairing.certificateSha256, second.pairing.certificateSha256)
        assertEquals(first.backendPort, second.backendPort)
        assertEquals("1", LinuxQa.sh("systemctl --user list-unit-files --no-legend 'codex-pocket*' | wc -l\n").trim())
        assertEquals(1, before.lines().count { it.startsWith("host ") })
        // The identity saved before the second preparation still signs in, and so does a newly paired one.
        assertEquals(first.pairing.computerId, phone.online { _, hello -> hello.string("computerId") })
        assertEquals(first.pairing.computerId, LinuxQa.pair(LinuxQa.pocket, second).online { _, hello -> hello.string("computerId") })
    }

    /** Everything a phone can do with native Codex on a host where nobody has signed in to Codex, with the VM cut off from the model. */
    @Test fun nativeSessionWithoutACodexLoginIsCreatedReadStartedAndStoppedAndThePhoneIsToldWhy() = runBlocking {
        LinuxQa.assume()
        val prepared = LinuxQa.prepare()
        assertTrue(prepared.nativeInstalled)
        assertFalse("the bootstrap reports that Codex on the host has no login", prepared.nativeSignedIn)
        LinuxQa.sh("mkdir -p \"${'$'}HOME/qa-project\"")
        LinuxQa.pair(LinuxQa.pocket, prepared).online { connection, _ ->
            val events = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
            val collector = launch { connection.notifications.collect { if (it["method"] == JsonPrimitive("codex/event")) events += it.getValue("params").jsonObject.getValue("event").jsonObject } }
            suspend fun event(method: String, matches: (JsonObject) -> Boolean = { true }) = withTimeout(40_000) {
                var found: JsonObject? = null
                while (found == null) { found = events.firstOrNull { it["method"] == JsonPrimitive(method) && matches(it.getValue("params").jsonObject) }; if (found == null) delay(200) }
                found!!.getValue("params").jsonObject
            }
            val native = awaitNative(connection)
            assertTrue(connection.native(native, "thread/list") { put("limit", 10) }["data"] is JsonArray)
            assertTrue(connection.native(native, "model/list").getValue("data").jsonArray.isNotEmpty())
            // Signing in or out of Codex is not something a phone may ask the host to do.
            for (method in listOf("account/read", "account/login/start", "account/logout"))
                assertEquals(method, "unsupported", wireFailure { connection.native(native, method, mutation = true) })
            val thread = connection.native(native, "thread/start", mutation = true) { put("cwd", "/home/pocket/qa-project") }.getValue("thread").jsonObject
            assertEquals("/home/pocket/qa-project", thread.string("cwd"))
            val id = thread.string("id")
            assertEquals("awaiting_first_message", connection.native(native, "thread/read") { put("threadId", id); put("includeTurns", true) }.string("pocketHistoryState"))
            val turn = connection.native(native, "turn/start", mutation = true) {
                put("threadId", id); put("clientUserMessageId", UUID.randomUUID().toString())
                put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "Say hi") }) })
            }.getValue("turn").jsonObject
            assertEquals("inProgress", turn.string("status"))
            event("turn/started") { it["threadId"] == JsonPrimitive(id) }
            // The model cannot be reached from the isolated VM: the phone is told while native Codex retries, it is not left waiting.
            val trouble = event("error") { it["turnId"] == JsonPrimitive(turn.string("id")) }
            assertEquals(JsonPrimitive(true), trouble["willRetry"])
            assertTrue(trouble.getValue("error").jsonObject.string("message").isNotBlank())
            connection.native(native, "turn/interrupt", mutation = true) { put("threadId", id); put("turnId", turn.string("id")) }
            val ended = event("turn/completed") { it["threadId"] == JsonPrimitive(id) }.getValue("turn").jsonObject
            assertEquals("interrupted", ended.string("status"))
            val read = connection.native(native, "thread/read") { put("threadId", id); put("includeTurns", true) }.getValue("thread").jsonObject
            assertEquals("interrupted", read.getValue("turns").jsonArray.single().jsonObject.string("status"))
            assertTrue(connection.native(native, "thread/list") { put("limit", 10) }.getValue("data").jsonArray.any { it.jsonObject["id"] == JsonPrimitive(id) })
            connection.native(native, "thread/archive", mutation = true) { put("threadId", id) }
            collector.cancel()
        }
    }

    /** Android answers to the provider name SSHJ asks for with a provider that has no algorithms left. */
    @Test fun aPlatformProviderWithoutAlgorithmsUnderTheSameNameDoesNotBreakSsh() = runBlocking {
        LinuxQa.assume()
        Security.removeProvider("BC")
        Security.addProvider(object : Provider("BC", "1.0", "stand-in for the platform's cut-down provider") {})
        assertEquals(LinuxQa.fingerprint, probeSshHostKey(LinuxQa.host, LinuxQa.pocket.port))
        Security.removeProvider("BC")
        Security.addProvider(object : Provider("BC", "1.0", "stand-in for the platform's cut-down provider") {})
        assertEquals("pocket", LinuxQa.sh("id -un\n").trim())
    }
}
