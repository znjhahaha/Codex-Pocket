package dev.codexpocket.wire

import java.util.ArrayDeque
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.net.ssl.*
import org.junit.Assert.*
import org.junit.Test

class TlsBackpressureTest {
    @Test fun unacceptedClientHelloMustRemainAvailableForRetry() {
        val cert = certificate()
        val toServer = ArrayDeque<ByteArray>()
        val toClient = ArrayDeque<ByteArray>()
        var blocked = true
        var clientReady = false
        val client = TlsPump(pinnedEngine(fingerprint(cert)), {
            if (blocked) TransportSendResult.WOULD_BLOCK else { toServer.add(it); TransportSendResult.ACCEPTED }
        },
            {}, { clientReady = true }, {})
        val server = TlsPump(serverEngine(cert), { toClient.add(it); TransportSendResult.ACCEPTED }, {}, {}, {})
        server.begin(); client.begin()
        blocked = false
        client.onWritable()
        repeat(10) {
            while (toServer.isNotEmpty()) server.receive(toServer.removeFirst())
            while (toClient.isNotEmpty()) client.receive(toClient.removeFirst())
        }
        assertTrue("Unaccepted ClientHello was lost instead of retained for writable retry", clientReady)
    }

    private class Pair(protocol: String) {
        val cert = certificate()
        val toServer = ArrayDeque<ByteArray>()
        val toClient = ArrayDeque<ByteArray>()
        val received = ByteArrayOutputStream()
        val attempts = mutableListOf<ByteArray>()
        var credits = Int.MAX_VALUE
        var ready = false
        val client = TlsPump(pinnedEngine(fingerprint(cert)), {
            attempts.add(it.copyOf())
            if (credits == 0) TransportSendResult.WOULD_BLOCK else {
                credits--; toServer.add(it.copyOf()); TransportSendResult.ACCEPTED
            }
        }, {}, { ready = true }, {})
        val server = TlsPump(serverEngine(cert, protocol), {
            toClient.add(it); TransportSendResult.ACCEPTED
        }, { received.write(it) }, {}, {})
        fun drain(fragment: Int = 7) {
            var rounds = 0
            while (toServer.isNotEmpty() || toClient.isNotEmpty()) {
                check(++rounds < 10000)
                while (toServer.isNotEmpty()) fragments(toServer.removeFirst(), fragment, server::receive)
                while (toClient.isNotEmpty()) fragments(toClient.removeFirst(), fragment, client::receive)
            }
        }
        fun handshake() { server.begin(); client.begin(); drain(); assertTrue(ready) }
    }

    @Test fun tls12And13ResumeOnlyRejectedRecordsAcrossPartialInputFrames() {
        for (protocol in listOf("TLSv1.2", "TLSv1.3")) {
            val pair = Pair(protocol)
            pair.handshake()
            pair.credits = 1
            val body = ByteArray(230000) { (it % 251).toByte() }
            pair.client.write(body)
            val rejected = pair.attempts.last().copyOf()
            val attempts = pair.attempts.size
            repeat(10) { pair.client.receive(byteArrayOf()); pair.client.write(byteArrayOf()) }
            pair.drain(31)
            assertEquals("Only writable may retry", attempts, pair.attempts.size)
            pair.credits = 1
            pair.client.onWritable()
            assertArrayEquals(rejected, pair.attempts[attempts])
            pair.drain(3)
            pair.credits = Int.MAX_VALUE
            pair.client.onWritable(); pair.drain(17)
            assertArrayEquals(body, pair.received.toByteArray())
            val finishedAttempts = pair.attempts.size
            repeat(100) { pair.client.onWritable() }
            assertEquals(finishedAttempts, pair.attempts.size)
        }
    }

    @Test fun acceptedPrefixOfOneLargeWrapIsNeverDuplicated() {
        val accepted = ByteArrayOutputStream()
        val attempts = mutableListOf<ByteArray>()
        var credits = 1
        val pump = TlsPump(LargeRecordEngine(), {
            attempts += it.copyOf()
            if (credits == 0) TransportSendResult.WOULD_BLOCK else {
                credits--; accepted.write(it); TransportSendResult.ACCEPTED
            }
        }, {}, {}, {})
        pump.begin()
        val body = ByteArray(140003) { (it % 239).toByte() }
        pump.write(body)
        assertEquals(65536, accepted.size())
        assertEquals(2, attempts.size)
        val rejected = attempts.last()
        credits = 1; pump.onWritable()
        assertArrayEquals(rejected, attempts[2])
        assertEquals(131072, accepted.size())
        credits = 1; pump.onWritable()
        assertArrayEquals(body, accepted.toByteArray())
        assertEquals(listOf(65536, 65536, 65536, 8931, 8931), attempts.map { it.size })
    }

    @Test fun localCloseAndTransportCloseCannotReplayPendingCiphertext() {
        for (remoteClosed in listOf(false, true)) {
            var calls = 0
            var closed = false
            var ended = 0
            val pump = TlsPump(LargeRecordEngine(), {
                calls++
                if (closed) TransportSendResult.CLOSED else TransportSendResult.WOULD_BLOCK
            }, {}, {}, { ended++ })
            pump.begin(); pump.write(ByteArray(140003))
            assertEquals(1, calls)
            if (remoteClosed) {
                closed = true
                assertEquals("disconnected", assertThrows(WireException::class.java) { pump.onWritable() }.code)
            } else { pump.close(); assertEquals(1, ended); pump.close(); assertEquals(1, ended) }
            val stopped = calls
            repeat(100) { pump.onWritable(); pump.receive(byteArrayOf()) }
            assertEquals(stopped, calls)
            assertThrows(IllegalStateException::class.java) { pump.write(byteArrayOf(1)) }
        }
    }

    @Test fun queuedPlaintextHasByteAndItemBoundsAndOwnsCallerArrays() {
        var writable = false
        val accepted = ByteArrayOutputStream()
        val pump = TlsPump(LargeRecordEngine(), {
            if (!writable) TransportSendResult.WOULD_BLOCK else { accepted.write(it); TransportSendResult.ACCEPTED }
        }, {}, {}, {})
        pump.begin(); pump.write(byteArrayOf(7))
        repeat(1024) {
            val source = byteArrayOf((it % 251).toByte())
            pump.write(source); source[0] = -1
        }
        assertEquals("backpressure", assertThrows(WireException::class.java) { pump.write(byteArrayOf(1)) }.code)
        writable = true; pump.onWritable()
        assertArrayEquals(byteArrayOf(7) + ByteArray(1024) { (it % 251).toByte() }, accepted.toByteArray())
        pump.abort()

        val byteBound = TlsPump(LargeRecordEngine(), { TransportSendResult.WOULD_BLOCK }, {}, {}, {})
        byteBound.begin(); byteBound.write(byteArrayOf(1))
        repeat(32) { byteBound.write(ByteArray(1024 * 1024)) }
        assertEquals("backpressure", assertThrows(WireException::class.java) { byteBound.write(byteArrayOf(1)) }.code)
        byteBound.abort()
    }

    @Test fun blockedInputBufferRemainsBounded() {
        val pump = TlsPump(LargeRecordEngine(), { TransportSendResult.WOULD_BLOCK }, {}, {}, {})
        pump.begin(); pump.write(byteArrayOf(1))
        repeat(4) { pump.receive(ByteArray(65536)) }
        assertThrows(SSLException::class.java) { pump.receive(byteArrayOf(1)) }
        pump.abort()
    }

    /** Scripted record provider to force >64 KiB output and buffer growth, without random TLS. */
    private class LargeRecordEngine : SSLEngine() {
        private val delegate = SSLContext.getDefault().createSSLEngine()
        private var closed = false
        override fun wrap(srcs: Array<out ByteBuffer>, offset: Int, length: Int, dst: ByteBuffer): SSLEngineResult {
            val total = minOf((offset until offset + length).sumOf { srcs[it].remaining() }, 200000)
            if (dst.remaining() < total) return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0)
            var remaining = total
            for (index in offset until offset + length) {
                val source = srcs[index]
                val count = minOf(source.remaining(), remaining)
                val part = source.duplicate().apply { limit(position() + count) }
                dst.put(part); source.position(source.position() + count); remaining -= count
            }
            return result(if (closed) SSLEngineResult.Status.CLOSED else SSLEngineResult.Status.OK, total)
        }
        private fun result(status: SSLEngineResult.Status, count: Int) = SSLEngineResult(status,
            SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, count, count)
        override fun unwrap(src: ByteBuffer, dsts: Array<out ByteBuffer>, offset: Int, length: Int) = result(SSLEngineResult.Status.BUFFER_UNDERFLOW, 0)
        override fun beginHandshake() {}
        override fun getHandshakeStatus() = SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        override fun getDelegatedTask(): Runnable? = null
        override fun closeInbound() { closed = true }
        override fun closeOutbound() { closed = true }
        override fun isInboundDone() = closed
        override fun isOutboundDone() = closed
        override fun getSession(): SSLSession = delegate.session
        override fun getSupportedCipherSuites() = delegate.supportedCipherSuites
        override fun getEnabledCipherSuites() = delegate.enabledCipherSuites
        override fun setEnabledCipherSuites(suites: Array<out String>) { delegate.enabledCipherSuites = suites }
        override fun getSupportedProtocols() = delegate.supportedProtocols
        override fun getEnabledProtocols() = delegate.enabledProtocols
        override fun setEnabledProtocols(protocols: Array<out String>) { delegate.enabledProtocols = protocols }
        override fun setUseClientMode(mode: Boolean) { delegate.useClientMode = mode }
        override fun getUseClientMode() = delegate.useClientMode
        override fun setNeedClientAuth(need: Boolean) { delegate.needClientAuth = need }
        override fun getNeedClientAuth() = delegate.needClientAuth
        override fun setWantClientAuth(want: Boolean) { delegate.wantClientAuth = want }
        override fun getWantClientAuth() = delegate.wantClientAuth
        override fun setEnableSessionCreation(flag: Boolean) { delegate.enableSessionCreation = flag }
        override fun getEnableSessionCreation() = delegate.enableSessionCreation
    }

    companion object {
        internal fun fragments(bytes: ByteArray, size: Int, consume: (ByteArray) -> Unit) {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + size, bytes.size)
                consume(bytes.copyOfRange(offset, end)); offset = end
            }
        }
    }
}
