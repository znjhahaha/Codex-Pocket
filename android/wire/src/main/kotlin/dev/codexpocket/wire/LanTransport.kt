package dev.codexpocket.wire

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Carries ciphertext only; PocketClient still performs the same pinned inner TLS handshake. */
internal class LanConnector(private val endpoint: String) : TransportConnector {
    override fun open(config: ConnectionConfig, listener: TransportListener): BinaryTransport {
        ConnectionRoute("lan", "lan", endpoint, 0) // literal private-IP validation before Socket
        val uri = URI(endpoint)
        val socket = Socket()
        val closed = AtomicBoolean(false)
        val queue = ArrayBlockingQueue<ByteArray>(64)
        fun fail() {
            if (closed.compareAndSet(false, true)) {
                runCatching { socket.close() }
                listener.onFailure()
            }
        }
        Thread({
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(uri.host.removeSurrounding("[", "]"), uri.port), 5000)
                if (closed.get()) return@Thread
                Thread({
                    try {
                        val out = socket.getOutputStream()
                        while (!closed.get()) {
                            val bytes = queue.poll(1, TimeUnit.SECONDS) ?: continue
                            out.write(bytes); out.flush()
                        }
                    } catch (_: Exception) { fail() }
                }, "pocket-lan-write").apply { isDaemon = true; start() }
                listener.onOpen()
                val input = socket.getInputStream()
                val buffer = ByteArray(16384)
                while (!closed.get()) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) listener.onBinary(buffer.copyOf(count))
                }
                fail()
            } catch (_: Exception) { fail() }
        }, "pocket-lan-read").apply { isDaemon = true; start() }
        return object : BinaryTransport {
            override fun send(bytes: ByteArray): Boolean = bytes.size <= 65536 && !closed.get() &&
                queue.offer(bytes.copyOf(), 5, TimeUnit.SECONDS) && !closed.get()
            override fun close(graceful: Boolean) {
                closed.set(true); queue.clear(); runCatching { socket.close() }
            }
        }
    }
}
