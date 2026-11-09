package dev.codexpocket.wire

import java.util.concurrent.TimeUnit
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal interface BinaryTransport { fun send(bytes: ByteArray): Boolean; fun close(graceful: Boolean) }
internal interface TransportListener { fun onOpen(); fun onBinary(bytes: ByteArray); fun onFailure() }
internal fun interface TransportConnector { fun open(config: ConnectionConfig, listener: TransportListener): BinaryTransport }

/** Outer TLS uses platform trust and hostname verification, independently of the inner pin. */
internal class WssConnector(private val endpoint: String? = null) : TransportConnector {
    override fun open(config: ConnectionConfig, listener: TransportListener): BinaryTransport {
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(45, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(endpoint ?: config.phoneUrl()).header("Authorization", "Bearer ${config.phoneToken}").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { listener.onOpen() }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > 65536) { listener.onFailure(); webSocket.cancel() }
                else listener.onBinary(bytes.toByteArray())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { listener.onFailure(); webSocket.cancel() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { listener.onFailure() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { listener.onFailure(); release() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { listener.onFailure(); release() }
            private fun release() { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        })
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return object : BinaryTransport {
            override fun send(bytes: ByteArray): Boolean {
                if (bytes.size > 65536 || closed.get()) return false
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                // The OkHttp writer drains independently. Bound memory while allowing an 8 MiB upload.
                while (socket.queueSize() + bytes.size > 4L * 1024 * 1024) {
                    if (closed.get() || System.nanoTime() >= deadline) return false
                    Thread.sleep(4)
                }
                return !closed.get() && socket.send(bytes.toByteString())
            }
            override fun close(graceful: Boolean) {
                closed.set(true)
                if (graceful) { if (!socket.close(1000, "")) socket.cancel() } else socket.cancel()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
