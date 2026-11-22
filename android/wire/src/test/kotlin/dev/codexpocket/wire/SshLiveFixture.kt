package dev.codexpocket.wire

import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

/** Signs fixture bundles with a key that exists only in this JVM; the shipped pin never trusts them. */
internal object TestRuntimeSigner {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val trust: ByteArray = pair.public.encoded.takeLast(32).toByteArray()
    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun sign(directory: File, version: String, architecture: String) {
        val manifest = buildJsonObject {
            put("schema", 1); put("product", "Codex Pocket"); put("version", version); put("architecture", architecture)
            put("files", buildJsonArray { for (name in listOf("pocket-agent", "pocket-runtime-host")) add(buildJsonObject {
                val file = File(directory, name)
                put("path", name); put("bytes", file.length()); put("sha256", sha256(file))
            }) })
        }.toString().toByteArray()
        File(directory, "runtime-manifest.json").writeBytes(manifest)
        File(directory, "runtime-manifest.sig").writeBytes(Base64.getEncoder().encode(
            Signature.getInstance("Ed25519").run { initSign(pair.private); update(manifest); sign() }))
    }
    fun verified(directory: File) = LinuxRuntimeBundle.verifiedAgainst(directory, trust)
}

/** A paired phone as the app saves it: connection settings and device identity, no pairing token. */
internal class SavedPhone(val target: SshTarget, val config: ConnectionConfig, val deviceId: String, val deviceSecret: String) {
    suspend fun hello(connection: PocketConnection): JsonObject = connection.request("hello", buildJsonObject {
        put("protocol", 1); put("deviceId", deviceId); put("deviceSecret", deviceSecret)
    }).jsonObject
    suspend fun <T> online(block: suspend (SshPocketConnection, JsonObject) -> T): T {
        val connection = SshPocketConnection(target)
        try { connection.connect(config); return block(connection, hello(connection)) } finally { connection.close() }
    }
}

/**
 * The disposable VM: `pocket` administers it (key login, passwordless sudo, lingering, native Codex on PATH);
 * `pocketqa2` is an ordinary account with neither sudo, lingering nor Codex.
 */
internal object LinuxQa {
    private val home = System.getenv("POCKET_LINUX_QA")?.let(::File)
    private val port = (System.getenv("POCKET_LINUX_QA_PORT") ?: "22247").toInt()
    val fingerprint by lazy {
        val known = File(home!!, "known_hosts").readText().trim().split(' ')[2]
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(known)))
    }
    val pocket by lazy { SshTarget("127.0.0.1", port, "pocket", fingerprint, SshAuthentication.PrivateKey(File(home!!, "test-key").readText()), 43821) }
    fun target(user: String, authentication: SshAuthentication, backend: Int = 43822, hostKey: String = fingerprint) =
        SshTarget("127.0.0.1", port, user, hostKey, authentication, backend)

    private val scratch by lazy {
        File("build/linux-qa").apply { deleteRecursively(); mkdirs() }.also { Runtime.getRuntime().addShutdownHook(Thread { it.deleteRecursively() }) }
    }
    private val binaries by lazy { File(System.getenv("POCKET_LINUX_BIN") ?: error("POCKET_LINUX_BIN must hold the cross-built pocket-agent and pocket-runtime-host")) }
    /** [agent] replaces the host executable before signing: a validly signed version that cannot run. */
    fun bundle(version: String = "0.0.0-qa", architecture: String = "x86_64", agent: ByteArray? = null): File {
        val directory = File(scratch, UUID.randomUUID().toString()).apply { mkdirs() }
        for (name in listOf("pocket-agent", "pocket-runtime-host")) {
            val copy = File(directory, name)
            if (name == "pocket-agent" && agent != null) copy.writeBytes(agent)
            else Files.copy(File(binaries, name).toPath(), copy.toPath())
        }
        TestRuntimeSigner.sign(directory, version, architecture)
        return directory
    }
    val base by lazy { bundle() }
    suspend fun prepare(target: SshTarget = pocket, directory: File = base, progress: (String) -> Unit = {}) =
        prepareSshHost(target, { TestRuntimeSigner.verified(directory) }, progress)

    suspend fun sh(script: String, target: SshTarget = pocket, timeoutSeconds: Long = 60): String =
        SshSession.open(target).use { it.execute(script, timeoutSeconds, emptyMap()) }

    private val provisioned by lazy { runBlocking {
        sh("""
            set -eu
            sudo loginctl enable-linger pocket
            mkdir -p "${'$'}HOME/.local/bin"
            [ -x "${'$'}HOME/.local/bin/codex" ] || ln -sfn "${'$'}HOME/codex-native/bin/codex" "${'$'}HOME/.local/bin/codex"
            id pocketqa2 >/dev/null 2>&1 || sudo useradd -m -s /bin/bash pocketqa2
            sudo passwd -l pocketqa2 >/dev/null
        """.trimIndent())
    } }
    /** A host accepts eight devices; every test starts without the ones earlier tests paired. */
    suspend fun forgetAcceptanceDevices(target: SshTarget = pocket) = sh("""
        root="${'$'}HOME/.local/share/codex-pocket"; state="${'$'}HOME/.local/state/codex-pocket/agent"
        [ -f "${'$'}root/active" ] || exit 0
        agent="${'$'}root/versions/${'$'}(cat "${'$'}root/active")/pocket-agent"
        "${'$'}agent" devices --state "${'$'}state" --json </dev/null | tr '{' '\n' | sed -n 's/.*"id":"\([^"]*\)","name":"Pocket SSH acceptance.*/\1/p' |
          while read -r id; do "${'$'}agent" revoke --state "${'$'}state" --device-id "${'$'}id" --json </dev/null >/dev/null; done
    """.trimIndent(), target)
    fun assume() {
        assumeTrue("Run only against the prepared disposable Linux VM", home != null)
        provisioned
        runBlocking { forgetAcceptanceDevices() }
    }

    /** A fresh key for the ordinary account, made on the VM and held only in this JVM. */
    val ordinary by lazy { runBlocking { target("pocketqa2", SshAuthentication.PrivateKey(ordinaryKey("ed25519", ""))).also { forgetAcceptanceDevices(it) } } }
    suspend fun ordinaryKey(type: String, passphrase: String, extra: String = ""): String = sh("""
        set -eu
        umask 077
        key=${'$'}(mktemp -u /tmp/pocket-qa-key.XXXXXX)
        ssh-keygen -q -t $type $extra -N ${if (passphrase.isEmpty()) "''" else shellWord(passphrase)} -f "${'$'}key"
        sudo install -d -m 700 -o pocketqa2 -g pocketqa2 /home/pocketqa2/.ssh
        sudo sh -c 'cat "${'$'}1" >> /home/pocketqa2/.ssh/authorized_keys; chown pocketqa2:pocketqa2 /home/pocketqa2/.ssh/authorized_keys; chmod 600 /home/pocketqa2/.ssh/authorized_keys' sh "${'$'}key.pub"
        cat "${'$'}key"
        rm -f "${'$'}key" "${'$'}key.pub"
    """.trimIndent())

    suspend fun pair(target: SshTarget, prepared: SshPreparation, name: String = "Pocket SSH acceptance"): SavedPhone {
        val saved = SshTarget(target.host, target.port, target.username, target.hostKey, target.authentication, prepared.backendPort)
        val connection = SshPocketConnection(saved)
        try {
            connection.connect(prepared.pairing.connectionConfig())
            val identity = connection.request("pair", buildJsonObject {
                put("pairingToken", prepared.pairing.pairingToken); put("deviceId", UUID.randomUUID().toString()); put("deviceName", name)
            }).jsonObject
            return SavedPhone(saved, prepared.pairing.connectionConfig(), identity.string("deviceId"), identity.string("deviceSecret"))
        } finally { connection.close() }
    }
    /** What an installation consists of on disk and which process serves it. */
    suspend fun installation(target: SshTarget = pocket): String = sh("""
        cd "${'$'}HOME"
        find .local/share/codex-pocket .config/systemd/user -printf '%p %s %T@\n' 2>/dev/null | sort
        cat .local/share/codex-pocket/active 2>/dev/null
        for entry in /proc/[0-9]*; do
          case "${'$'}(readlink "${'$'}entry/exe" 2>/dev/null)" in */pocket-agent) printf 'host %s %s\n' "${'$'}{entry#/proc/}" "${'$'}(cut -d' ' -f22 "${'$'}entry/stat")" ;; esac
        done
    """.trimIndent(), target)
}
