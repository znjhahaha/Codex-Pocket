package dev.codexpocket.wire

/** Atomic ownership transfer: WOULD_BLOCK and CLOSED accept no bytes. Never retry ACCEPTED. */
internal enum class TransportSendResult { ACCEPTED, WOULD_BLOCK, CLOSED }

/** Opt-in for transports with a nonblocking send and a writable notification. */
internal interface WritableBinaryTransport : BinaryTransport {
    fun trySend(bytes: ByteArray): TransportSendResult
    override fun send(bytes: ByteArray): Boolean = trySend(bytes) == TransportSendResult.ACCEPTED
}

/**
 * Adapts an atomic Boolean transport such as DataChannelTransport without changing its ABI.
 * The factory must forward its onWritable callback to the supplied listener.onWritable,
 * and terminal errors to listener.onFailure. Use a new connector/peer for each generation.
 * Do not wrap a blocking legacy WSS/LAN transport: those retain their existing send contract.
 */
internal class BackpressureConnector(private val delegate: TransportConnector) : TransportConnector {
    override fun open(config: ConnectionConfig, listener: TransportListener): BinaryTransport {
        val gate = Any()
        var closed = false
        val transport = delegate.open(config, object : TransportListener {
            override fun onOpen() { if (synchronized(gate) { !closed }) listener.onOpen() }
            override fun onBinary(bytes: ByteArray) { if (synchronized(gate) { !closed }) listener.onBinary(bytes) }
            override fun onWritable() { if (synchronized(gate) { !closed }) listener.onWritable() }
            override fun onFailure() {
                val notify = synchronized(gate) { if (closed) false else { closed = true; true } }
                if (notify) listener.onFailure()
            }
        })
        return object : WritableBinaryTransport {
            override fun trySend(bytes: ByteArray): TransportSendResult = synchronized(gate) {
                when {
                    closed || bytes.size > 65536 -> TransportSendResult.CLOSED
                    transport.send(bytes) -> TransportSendResult.ACCEPTED
                    closed -> TransportSendResult.CLOSED
                    else -> TransportSendResult.WOULD_BLOCK
                }
            }
            override fun close(graceful: Boolean) {
                synchronized(gate) { closed = true }
                transport.close(graceful)
            }
        }
    }
}
