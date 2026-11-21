package dev.codexpocket.wire

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.xfer.FileSystemFile
import java.io.File

fun sshKeyFingerprint(key: PublicKey): String = "SHA256:" + Base64.getEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(Buffer.PlainBuffer().putPublicKey(key).compactData))

/** Probe exposes the key for user confirmation; it never authenticates or runs a command. */
suspend fun probeSshHostKey(host: String, port: Int): String = withContext(Dispatchers.IO) {
    require(host.isNotBlank() && host.length <= 253 && port in 1..65535)
    var observed: String? = null
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableListOf<ServerSocket>()

    suspend fun upload(file: File, remotePath: String) = withContext(Dispatchers.IO) {
        require(remotePath.startsWith("/") && remotePath.none(Char::isISOControl))
        client.newSFTPClient().use { it.put(FileSystemFile(file), remotePath) }
    }

    suspend fun execute(script: String, timeoutSeconds: Long = 20): String = withContext(Dispatchers.IO) {
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
                    if (command.exitStatus != 0) throw WireException("ssh_command_failed", "Remote preparation failed")
                    output.toString(Charsets.UTF_8)
                }
            }
        }
    }

    fun forwardLoopback(remotePort: Int): Int {
        require(remotePort in 1..65535)
        val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8) }
        synchronized(listeners) { listeners += socket }
        val forwarder = client.newLocalPortForwarder(Parameters("127.0.0.1", socket.localPort, "127.0.0.1", remotePort), socket)
        scope.launch { try { forwarder.listen() } catch (_: Exception) { close() } }
        return socket.localPort
    }
    override fun close() {
        synchronized(listeners) { listeners.forEach { runCatching { it.close() } }; listeners.clear() }
        runCatching { client.close() }; scope.cancel()
    }

    companion object {
        suspend fun open(target: SshTarget): SshSession = withContext(Dispatchers.IO) {
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
