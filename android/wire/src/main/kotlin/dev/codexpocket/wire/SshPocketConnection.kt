package dev.codexpocket.wire

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** SSH owns transport only. PocketClient retains authentication and dispatch-once receipts. */
class SshPocketConnection(private val target: SshTarget) : PocketConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(LinkState.OFFLINE)
    override val state = mutableState.asStateFlow()
    private val events = kotlinx.coroutines.channels.Channel<JsonObject>(64)
    override val notifications = events.receiveAsFlow()
    @Volatile private var session: SshSession? = null
    @Volatile private var delegate: PocketClient? = null
    @Volatile private var closed = false
    @Volatile override var failureCode: String? = null
        private set
    override val lastApplicationActivityMillis: Long get() = delegate?.lastApplicationActivityMillis ?: 0

    override suspend fun connect(config: ConnectionConfig) {
        check(!closed && session == null)
        mutableState.value = LinkState.CONNECTING
        try {
            val ssh = SshSession.open(target)
            session = ssh
            if (closed) { ssh.close(); throw CancellationException("Connection closed") }
            val operatingSystem = ssh.execute("uname -s\n").trim()
            if (operatingSystem != "Linux") throw WireException("ssh_platform_unsupported", "This SSH host is not Linux")
            val port = ssh.forwardLoopback(target.backendPort)
            val connection = PocketClient(LanConnector("tls://127.0.0.1:$port"))
            delegate = connection
            scope.launch { connection.state.collect { mutableState.value = it } }
            scope.launch { try { connection.notifications.collect { events.send(it) } } finally { close() } }
            connection.connect(config)
        } catch (cancelled: CancellationException) { close(); throw cancelled }
        catch (failure: Exception) { failureCode = (failure as? WireException)?.code ?: "ssh_connection_failed"; close(); throw failure }
    }
    override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement =
        (delegate ?: throw WireException("offline", "SSH connection is not ready")).request(method, params, commandId, timeoutMillis)
    override fun close() {
        closed = true
        delegate?.close(); session?.close(); events.close(); scope.cancel()
        mutableState.value = LinkState.OFFLINE
    }
}
