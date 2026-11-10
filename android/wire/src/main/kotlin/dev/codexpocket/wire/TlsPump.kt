package dev.codexpocket.wire

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

internal fun pinnedEngine(pin: String): SSLEngine {
    require(pin.matches(Regex("[A-Fa-f0-9]{64}"))) { "Invalid certificate pin" }
    val expected = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("Client certificates unsupported") }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("Missing pinned certificate")
            if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(cert.encoded)))
                throw CertificateException("Certificate pin mismatch")
            cert.checkValidity()
            val dns = cert.subjectAlternativeNames?.any { it.size >= 2 && it[0] == 2 && (it[1] as? String).equals("pocket.local", ignoreCase = true) } == true
            if (!dns) throw CertificateException("Invalid peer name")
        }
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return context.createSSLEngine("pocket.local", 443).apply {
        useClientMode = true
        enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2").filter { it in supportedProtocols }.toTypedArray()
        sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    }
}

/** All methods run on one owner. Transport chunks are unrelated to TLS record boundaries. */
internal class TlsPump(
    private val engine: SSLEngine,
    private val emit: (ByteArray) -> TransportSendResult,
    private val plaintext: (ByteArray) -> Unit,
    private val ready: () -> Unit,
    private val ended: () -> Unit
) {
    private var input = ByteBuffer.allocate(32768)
    private var output = ByteBuffer.allocate(32768).apply { limit(0) }
    private var application = ByteBuffer.allocate(32768)
    private var started = false
    private var established = false
    private var endedOnce = false
    private var peerClosed = false
    private var writeBlocked = false
    private var unaccepted: ByteArray? = null
    private val outgoing = java.util.ArrayDeque<ByteBuffer>()
    private var outgoingBytes = 0L

    fun begin() { check(!started); started = true; engine.beginHandshake(); drive() }

    fun receive(bytes: ByteArray) {
        require(bytes.size <= 65536) { "TLS chunk too large" }
        check(started)
        if (endedOnce) return
        while (input.remaining() < bytes.size) input = grow(input)
        input.put(bytes)
        drive()
    }

    fun write(bytes: ByteArray) {
        check(established && !endedOnce && !engine.isOutboundDone) { "TLS is not ready" }
        if (outgoingBytes + bytes.size > 32L * 1024 * 1024 || outgoing.size >= 1024)
            throw WireException("backpressure", "TLS outbound capacity exceeded")
        if (bytes.isEmpty()) return
        outgoing.add(ByteBuffer.wrap(bytes.copyOf())); outgoingBytes += bytes.size
        drive()
    }

    /** Owner-only. No timer/polling, and receive/write cannot retry a blocked ciphertext chunk. */
    fun onWritable() {
        if (endedOnce || !writeBlocked) return
        writeBlocked = false
        drive()
    }

    fun close() {
        if (endedOnce) return
        // Local cancellation cannot wait indefinitely for a writable event. Already accepted
        // bytes belong to the transport; discard all unaccepted bytes and never replay them.
        try {
            engine.closeOutbound()
            if (!writeBlocked) drive()
        } finally { finish() }
    }

    fun abort() {
        endedOnce = true
        outgoing.clear(); outgoingBytes = 0
        input.clear(); output.clear(); output.limit(0); application.clear()
        unaccepted = null; writeBlocked = false
    }

    private fun finish() { if (!endedOnce) { abort(); ended() } }

    private fun drive() {
        if (writeBlocked) return
        var steps = 0
        while (!endedOnce) {
            if (++steps > 10000) throw SSLException("TLS progress limit")
            // Wrapping consumes plaintext exactly once. Flush its retained ciphertext before
            // another engine operation, advancing only after atomic transport acceptance.
            if (!flushCiphertext()) return
            // Names avoid linking NEED_UNWRAP_AGAIN on older Android providers.
            when (engine.handshakeStatus.name) {
                "NEED_TASK" -> {
                    var task = engine.delegatedTask ?: throw SSLException("Missing TLS task")
                    while (true) { task.run(); task = engine.delegatedTask ?: break }
                }
                "NEED_WRAP" -> {
                    val r = wrap(ByteBuffer.allocate(0))
                    if (r.bytesProduced() == 0 && r.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP)
                        throw SSLException("TLS handshake stalled")
                }
                "NEED_UNWRAP", "NEED_UNWRAP_AGAIN" -> {
                    val again = engine.handshakeStatus.name == "NEED_UNWRAP_AGAIN"
                    if (input.position() == 0 && !again) return
                    if (!unwrap()) return
                }
                "FINISHED", "NOT_HANDSHAKING" -> {
                    if (!established && !engine.isOutboundDone && !peerClosed) { established = true; ready() }
                    if (peerClosed && engine.isOutboundDone) { finish(); return }
                    if (input.position() > 0) { if (!unwrap()) return }
                    else if (outgoing.isNotEmpty() && !engine.isOutboundDone) {
                        val source = outgoing.first
                        val r = wrap(source)
                        outgoingBytes -= r.bytesConsumed()
                        if (!source.hasRemaining()) outgoing.removeFirst()
                        if (r.status == SSLEngineResult.Status.CLOSED) throw SSLException("TLS closed")
                        if (r.bytesConsumed() == 0 && r.bytesProduced() == 0 && r.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) throw SSLException("TLS write stalled")
                    } else return
                }
                else -> throw SSLException("Unknown TLS handshake status")
            }
        }
    }

    private fun wrap(source: ByteBuffer): SSLEngineResult {
        while (true) {
            output.clear()
            val r = engine.wrap(source, output)
            output.flip()
            if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                if (r.bytesConsumed() != 0 || r.bytesProduced() != 0) throw SSLException("Invalid TLS wrap overflow")
                output.clear(); output = grow(output); continue
            }
            if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) throw SSLException("Invalid TLS wrap")
            return r
        }
    }

    private fun flushCiphertext(): Boolean {
        while (output.hasRemaining()) {
            val bytes = unaccepted ?: ByteArray(minOf(output.remaining(), 65536)).also {
                output.duplicate().get(it); unaccepted = it
            }
            when (emit(bytes)) {
                TransportSendResult.ACCEPTED -> { output.position(output.position() + bytes.size); unaccepted = null }
                TransportSendResult.WOULD_BLOCK -> { writeBlocked = true; return false }
                TransportSendResult.CLOSED -> { abort(); throw WireException("disconnected", "Transport closed") }
            }
        }
        return true
    }

    private fun unwrap(): Boolean {
        input.flip()
        val r = try { engine.unwrap(input, application) } finally { input.compact() }
        application.flip()
        if (application.hasRemaining()) {
            val bytes = ByteArray(application.remaining()); application.get(bytes); plaintext(bytes)
        }
        application.clear()
        when (r.status) {
            SSLEngineResult.Status.BUFFER_OVERFLOW -> { application = grow(application); return true }
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                if (!input.hasRemaining()) input = grow(input)
                return false
            }
            SSLEngineResult.Status.CLOSED -> { peerClosed = true; engine.closeOutbound(); return true }
            SSLEngineResult.Status.OK -> return r.bytesConsumed() != 0 || r.bytesProduced() != 0 ||
                r.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
            else -> throw SSLException("Unknown TLS result")
        }
    }

    private fun grow(buffer: ByteBuffer): ByteBuffer {
        if (buffer.capacity() >= 262144) throw SSLException("TLS buffer limit")
        val larger = ByteBuffer.allocate(minOf(buffer.capacity() * 2, 262144))
        buffer.flip(); larger.put(buffer); return larger
    }
}
