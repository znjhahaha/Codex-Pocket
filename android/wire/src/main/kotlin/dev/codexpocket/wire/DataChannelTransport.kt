package dev.codexpocket.wire

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Signaling is routed and authenticated by the controller; generation is never inferred from SDP. */
internal sealed interface DataChannelSignal {
    val generation: Long
    data class Description(override val generation: Long, val sdp: String, val type: String) : DataChannelSignal
    data class Candidate(override val generation: Long, val candidate: String, val mid: String) : DataChannelSignal
}

/**
 * Carries Pocket TLS ciphertext only. Use with the existing PocketClient/inner TLS engine.
 * Native reliable ordered protocol pocket.tls.bytes.v1; no credentials, model or relay here.
 * Controller starts once, verifies current generation and signaling authentication, then applies
 * remote signals. Native queues are bounded: TX including RTC buffer 1 MiB/256 KiB hysteresis,
 * RX 1 MiB; overflow fails the transport. Listener callbacks use a dedicated delivery thread;
 * signaling runs on the native worker and must enqueue to the controller promptly.
 * send is nonblocking and atomic: false means no bytes accepted. Controller retries after
 * onWritable, or lets Pocket's existing send(false) path terminate on backpressure.
 */
internal class DataChannelTransport(
    val generation: Long,
    private val listener: TransportListener,
    private val signal: (DataChannelSignal) -> Unit,
    private val onWritable: () -> Unit = {},
    bindAddress: String? = null,
    stunUrl: String? = null,
) : WritableBinaryTransport {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val gate = java.lang.Object()
    private var handle = 0L
    private var closing = false
    private val ended = AtomicBoolean(false)
    private val released = CountDownLatch(1)
    private var openPending = false
    private var readPending = false
    private var writePending = false
    private var terminalPending = false
    // No second receive byte queue; only the delivery thread pulls native queued bytes.
    private val readBuffer = ByteArray(16384)

    init {
        require(generation > 0)
        // Explicit construction loads the library; importing the wire module does not.
        loadNative()
        synchronized(gate) {
            handle = nativeCreate(generation, bindAddress, stunUrl)
            check(handle != 0L) { "DataChannel peer creation failed" }
        }
        Thread(::deliver, "pocket-dc-delivery").apply { isDaemon = true; start() }
    }

    fun start(offerer: Boolean): Boolean = synchronized(gate) {
        !closing && handle != 0L && nativeStart(handle, generation, offerer) == 0
    }

    /** Caller must validate this envelope against the authenticated, active negotiation first. */
    fun remoteDescription(generation: Long, sdp: String, type: String): Boolean = synchronized(gate) {
        generation == this.generation && !closing && handle != 0L &&
            nativeDescription(handle, generation, sdp, type) == 0
    }

    fun remoteCandidate(generation: Long, candidate: String, mid: String): Boolean = synchronized(gate) {
        generation == this.generation && !closing && handle != 0L &&
            nativeCandidate(handle, generation, candidate, mid) == 0
    }

    override fun trySend(bytes: ByteArray): TransportSendResult = synchronized(gate) {
        if (bytes.size > 65536 || closing || handle == 0L) TransportSendResult.CLOSED
        else when (nativeWrite(handle, bytes)) {
            bytes.size -> TransportSendResult.ACCEPTED
            -1 -> TransportSendResult.WOULD_BLOCK
            else -> TransportSendResult.CLOSED
        }
    }

    override fun close(graceful: Boolean) {
        synchronized(gate) {
            closing = true
            if (handle != 0L) nativeClose(handle, graceful)
        }
    }

    /** Optional controller teardown wait. Call on a background thread, never a listener callback. */
    fun awaitReleased(timeoutMillis: Long): Boolean = released.await(timeoutMillis, TimeUnit.MILLISECONDS)

    // JNI resolves this exact private signature; packaging must keep it and native methods.
    @Suppress("unused")
    private fun onNativeEvent(nativeHandle: Long, generation: Long, event: Int, first: String, second: String) {
        if (generation != this.generation || ended.get()) return
        try {
            when (event) {
                6 -> if (!isClosing()) signal(DataChannelSignal.Description(generation, first, second))
                7 -> if (!isClosing()) signal(DataChannelSignal.Candidate(generation, first, second))
                else -> synchronized(gate) {
                    if (handle != nativeHandle) return
                    when (event) {
                        1 -> openPending = true
                        2 -> readPending = true
                        3 -> writePending = true
                        4, 5 -> terminalPending = true
                    }
                    gate.notifyAll()
                }
            }
        } catch (_: Exception) {
            nativeClose(nativeHandle, false)
        }
    }

    private data class Delivery(val handle: Long, val open: Boolean, val read: Boolean, val writable: Boolean, val terminal: Boolean)

    private fun deliver() {
        while (!ended.get()) {
            val work = synchronized(gate) {
                while (!ended.get() && !openPending && !readPending && !writePending && !terminalPending) gate.wait()
                if (ended.get()) return
                Delivery(handle, openPending, readPending, writePending, terminalPending).also {
                    openPending = false; readPending = false; writePending = false; terminalPending = false
                }
            }
            try {
                if (work.open && !work.terminal && !isClosing()) listener.onOpen()
                if (work.read || work.terminal) {
                    while (!isClosing()) {
                        val count = nativeRead(work.handle, readBuffer)
                        if (count == -1 || count == 0) break
                        if (count < 0) { finish(work.handle); return }
                        listener.onBinary(readBuffer.copyOf(count))
                    }
                }
                if (work.writable && !work.terminal && !isClosing()) onWritable()
                if (work.terminal) { finish(work.handle); return }
            } catch (_: Exception) { finish(work.handle); return }
        }
    }

    private fun isClosing(): Boolean = synchronized(gate) { closing }

    private fun finish(nativeHandle: Long) {
        if (!ended.compareAndSet(false, true)) return
        val localClose = synchronized(gate) {
            val wasClosing = closing
            closing = true
            handle = 0L
            gate.notifyAll()
            wasClosing
        }
        try { if (!localClose) listener.onFailure() }
        catch (_: Exception) { }
        finally {
            nativeClose(nativeHandle, false)
            // destroy joins the callback worker, so it must execute on another thread.
            Thread({ try { nativeDestroy(nativeHandle) } finally { released.countDown() } }, "pocket-dc-release").start()
        }
    }

    private external fun nativeCreate(generation: Long, bindAddress: String?, stunUrl: String?): Long
    private external fun nativeStart(handle: Long, generation: Long, offerer: Boolean): Int
    private external fun nativeDescription(handle: Long, generation: Long, sdp: String, type: String): Int
    private external fun nativeCandidate(handle: Long, generation: Long, candidate: String, mid: String): Int
    private external fun nativeWrite(handle: Long, bytes: ByteArray): Int
    private external fun nativeRead(handle: Long, bytes: ByteArray): Int
    private external fun nativeClose(handle: Long, graceful: Boolean)
    private external fun nativeDestroy(handle: Long)

    companion object {
        private val loadGate = Any()
        private var loaded = false
        fun available(): Boolean = try { loadNative(); true } catch (_: UnsatisfiedLinkError) { false }
        private fun loadNative() = synchronized(loadGate) {
            if (!loaded) { System.loadLibrary("pocket-p2p-jni"); loaded = true }
        }
    }
}
