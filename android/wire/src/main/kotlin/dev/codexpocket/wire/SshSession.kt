package dev.codexpocket.wire

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.xfer.FileSystemFile
import java.io.File
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** SSHJ asks for its algorithms from "BC" by name, and Android answers to that name with a provider that has none of them. */
private fun requireFullBouncyCastle() = synchronized(Security::class.java) {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) !is BouncyCastleProvider) {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }
}

fun sshKeyFingerprint(key: PublicKey): String = "SHA256:" + Base64.getEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(Buffer.PlainBuffer().putPublicKey(key).compactData))

/** Probe exposes the key for user confirmation; it never authenticates or runs a command. */
suspend fun probeSshHostKey(host: String, port: Int): String = withContext(Dispatchers.IO) {
    require(host.isNotBlank() && host.length <= 253 && port in 1..65535)
    var observed: String? = null
    requireFullBouncyCastle()
    SSHClient().use { ssh ->
        ssh.connectTimeout = 10_000; ssh.timeout = 15_000
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, remotePort: Int, key: PublicKey): Boolean {
                observed = sshKeyFingerprint(key)
                return false // No unverified connection continues beyond key exchange.
            }
            override fun findExistingAlgorithms(hostname: String, remotePort: Int): List<String> = emptyList()
        })
        try { ssh.connect(host, port) } catch (_: Exception) { }
    }
    observed ?: throw WireException("ssh_unreachable", "SSH host key could not be read")
}

internal class SshSession private constructor(private val client: SSHClient) : AutoCloseable {
    suspend fun upload(file: File, remotePath: String) = withContext(Dispatchers.IO) {
        require(remotePath.startsWith("/") && remotePath.none(Char::isISOControl))
        client.newSFTPClient().use { it.put(FileSystemFile(file), remotePath) }
    }

    suspend fun execute(script: String, timeoutSeconds: Long = 20, failures: Map<Int, String> = sshRemoteFailures): String = withContext(Dispatchers.IO) {
        require(script.length <= 256 * 1024 && timeoutSeconds in 1..180)
        client.startSession().use { session ->
            session.exec("sh -s").use { command ->
                coroutineScope {
                    val stdout = async { bounded(command.inputStream, 512 * 1024) }
                    val stderr = async { bounded(command.errorStream, 64 * 1024) }
                    command.outputStream.use { it.write(script.toByteArray()); it.flush() }
                    command.join(timeoutSeconds, TimeUnit.SECONDS)
                    if (!command.isEOF || command.exitStatus == null) { command.close(); throw WireException("ssh_timeout", "Remote command did not finish") }
                    val output = stdout.await()
                    stderr.await() // Drain diagnostic output without exposing paths or credentials.
                    if (command.exitStatus != 0) throw WireException(failures[command.exitStatus] ?: "ssh_command_failed", "Remote preparation failed")
                    output.toString(Charsets.UTF_8)
                }
            }
        }
    }

    /** The host's loopback port as a transport of its own: nothing listens on the phone, and a lost SSH link fails it at once. */
    fun loopback(remotePort: Int) = TransportConnector { _, listener ->
        require(remotePort in 1..65535)
        val closed = AtomicBoolean(false)
        val queue = ArrayBlockingQueue<ByteArray>(64)
        val channel = AtomicReference<DirectConnection?>()
        fun end(failed: Boolean) {
            if (closed.compareAndSet(false, true)) { queue.clear(); runCatching { channel.get()?.close() }; if (failed) listener.onFailure() }
        }
        Thread({
            try {
                val direct = client.newDirectConnection("127.0.0.1", remotePort)
                channel.set(direct)
                if (closed.get()) { direct.close(); return@Thread }
                Thread({
                    try {
                        val out = direct.outputStream
                        while (!closed.get()) {
                            val bytes = queue.poll(1, TimeUnit.SECONDS) ?: continue
                            out.write(bytes); out.flush()
                        }
                    } catch (_: Exception) { end(true) }
                }, "pocket-ssh-write").apply { isDaemon = true; start() }
                listener.onOpen()
                val input = direct.inputStream
                val buffer = ByteArray(16384)
                while (!closed.get()) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) listener.onBinary(buffer.copyOf(count))
                }
                end(true)
            } catch (_: Exception) { end(true) }
        }, "pocket-ssh-read").apply { isDaemon = true; start() }
        object : BinaryTransport {
            override fun send(bytes: ByteArray): Boolean = bytes.size <= 65536 && !closed.get() &&
                queue.offer(bytes.copyOf(), 5, TimeUnit.SECONDS) && !closed.get()
            override fun close(graceful: Boolean) = end(false)
        }
    }
    override fun close() { runCatching { client.close() } }

    companion object {
        suspend fun open(target: SshTarget): SshSession = withContext(Dispatchers.IO) {
            requireFullBouncyCastle()
            val client = SSHClient()
            var mismatch = false
            try {
                client.connectTimeout = 10_000; client.timeout = 30_000
                client.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
                        MessageDigest.isEqual(target.hostKey.toByteArray(), sshKeyFingerprint(key).toByteArray()).also { mismatch = !it }
                    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
                })
                client.connect(target.host, target.port)
                when (val auth = target.authentication) {
                    is SshAuthentication.Password -> client.authPassword(target.username, auth.value.toCharArray())
                    is SshAuthentication.PrivateKey -> client.authPublickey(target.username,
                        client.loadKeys(auth.pem, null, PasswordUtils.createOneOff(auth.passphrase.toCharArray())))
                }
                SshSession(client)
            } catch (cancelled: CancellationException) { runCatching { client.close() }; throw cancelled }
            catch (_: Exception) {
                runCatching { client.close() }
                throw WireException(if (mismatch) "ssh_host_key_changed" else "ssh_connection_failed", "SSH connection could not be authenticated")
            }
        }
        private fun bounded(input: InputStream, maximum: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(output.size() + size <= maximum) { "SSH output exceeded its limit" }
                output.write(buffer, 0, size)
            }
            return output.toByteArray()
        }
    }
}
