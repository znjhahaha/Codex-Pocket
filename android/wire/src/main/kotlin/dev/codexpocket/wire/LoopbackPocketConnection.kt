package dev.codexpocket.wire

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** A backend on this device. [prepare] makes sure it is running and returns its loopback port. */
class LoopbackPocketConnection(private val prepare: suspend () -> Int) : PocketConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(LinkState.OFFLINE)
    override val state = mutableState.asStateFlow()
    private val events = kotlinx.coroutines.channels.Channel<JsonObject>(64)
    override val notifications = events.receiveAsFlow()
    @Volatile private var delegate: PocketClient? = null
    @Volatile private var closed = false
    @Volatile private var preparationFailure: String? = null
    // The delegate publishes OFFLINE before its notification collector unwinds.
    // Expose the cause at that same instant, even if the owner closes this wrapper.
    override val failureCode: String? get() = preparationFailure ?: delegate?.failureCode
    override val lastApplicationActivityMillis: Long get() = delegate?.lastApplicationActivityMillis ?: 0

    override suspend fun connect(config: ConnectionConfig) {
        check(!closed && delegate == null)
        mutableState.value = LinkState.CONNECTING
        try {
            val port = prepare()
            require(port in 1..65535)
            if (closed) throw CancellationException("Connection closed")
            val connection = PocketClient(LanConnector("tls://127.0.0.1:$port"))
            delegate = connection
            scope.launch { connection.state.collect { mutableState.value = it } }
            scope.launch {
                // A transport that fails ends its notifications with that failure; it is this connection's, not the process's.
                var cause: Throwable? = null
                try { connection.notifications.collect { events.send(it) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { cause = failure; preparationFailure = failureCode ?: (failure as? WireException)?.code }
                finally { events.close(cause); close() }
            }
            connection.connect(config)
        } catch (cancelled: CancellationException) { close(); throw cancelled }
        catch (failure: Exception) { preparationFailure = (failure as? WireException)?.code ?: "local_runtime_unavailable"; close(); throw failure }
    }
    override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement =
        (delegate ?: throw WireException("offline", "Local backend is not ready")).request(method, params, commandId, timeoutMillis)
    override fun close() {
        closed = true
        delegate?.close(); events.close(); scope.cancel()
        mutableState.value = LinkState.OFFLINE
    }
}
