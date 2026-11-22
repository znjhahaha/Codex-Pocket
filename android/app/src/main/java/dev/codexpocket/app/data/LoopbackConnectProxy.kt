package dev.codexpocket.app.data

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The published Codex executable is statically linked and looks for /etc/resolv.conf, which
 * Android does not have. It reaches the network through this tunnel instead, so names are
 * resolved by Android. Only CONNECT is served, only on loopback, only with this process's token.
 */
internal class LoopbackConnectProxy(
    private val resolve: (String) -> InetAddress = InetAddress::getByName,
    private val workers: ExecutorService = Executors.newCachedThreadPool { Thread(it, "pocket-local-proxy").apply { isDaemon = true } },
) : Closeable {
    private val server = ServerSocket(0, 64, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val expected = "Basic " + Base64.getEncoder().encodeToString("pocket:$token".toByteArray())
    val port: Int get() = server.localPort
    /** Carries the credential; give it to the child process only. */
    val url: String get() = "http://pocket:$token@127.0.0.1:$port"

    init { workers.execute(::accept) }

    private fun accept() {
        while (!server.isClosed) {
            val client = try { server.accept() } catch (_: Exception) { break }
            workers.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 15000
            val head = readHead(client.getInputStream()) ?: return refuse(client, "400 Bad Request")
            val lines = head.split("\r\n")
            val request = lines.first().split(' ')
            val authorization = lines.drop(1).firstOrNull { it.startsWith("proxy-authorization:", ignoreCase = true) }
                ?.substringAfter(':')?.trim().orEmpty()
            if (!MessageDigest.isEqual(authorization.toByteArray(), expected.toByteArray()))
                return refuse(client, "407 Proxy Authentication Required")
            if (request.size != 3 || request[0] != "CONNECT") return refuse(client, "405 Method Not Allowed")
            val host = request[1].substringBeforeLast(':').removeSurrounding("[", "]")
            val port = request[1].substringAfterLast(':', "").toIntOrNull()
            if (host.isEmpty() || host.length > 253 || port == null || port !in 1..65535) return refuse(client, "400 Bad Request")
            upstream = try { Socket().apply { connect(InetSocketAddress(resolve(host), port), 15000) } }
                catch (_: Exception) { return refuse(client, "502 Bad Gateway") }
            client.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
            client.soTimeout = 0
            val target = upstream
            workers.execute { pump(target, client) }
            pump(client, target)
        } catch (_: Exception) {
        } finally { runCatching { client.close() }; runCatching { upstream?.close() } }
    }

    /** Request head only, byte by byte so nothing of the tunnel is consumed. */
    private fun readHead(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val next = input.read()
            if (next < 0) return null
            bytes.write(next)
            val size = bytes.size()
            if (size >= 4 && next == '\n'.code) {
                val data = bytes.toByteArray()
                if (data[size - 4] == '\r'.code.toByte() && data[size - 3] == '\n'.code.toByte() && data[size - 2] == '\r'.code.toByte())
                    return String(data, 0, size - 4, Charsets.ISO_8859_1)
            }
        }
        return null
    }

    private fun refuse(client: Socket, status: String) {
        runCatching { client.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() } }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val buffer = ByteArray(16384)
            val input = from.getInputStream(); val output = to.getOutputStream()
            while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); output.flush() }
        } catch (_: Exception) {
        } finally { runCatching { to.shutdownOutput() } }
    }

    override fun close() { runCatching { server.close() }; workers.shutdownNow() }
}
