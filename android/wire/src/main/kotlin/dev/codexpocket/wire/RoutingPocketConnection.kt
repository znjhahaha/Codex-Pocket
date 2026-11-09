package dev.codexpocket.wire

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** A request is dispatched once to one authenticated connection. Failover never replays requests. */
class RoutingPocketConnection internal constructor(
    private val manager: RouteManager,
    private val attemptTimeoutMillis: Long = 20000,
    private val create: (ConnectionRoute) -> PocketConnection,
) : PocketConnection {
    constructor(manager: RouteManager = RouteManager()) : this(manager, 20000, { route ->
        PocketClient(if (route.kind == "lan") LanConnector(route.url) else WssConnector(route.url))
    })
    private val gate = Any()
    private val switching = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notices = Channel<JsonObject>(64)
    private val link = MutableStateFlow(LinkState.OFFLINE)
    override val state: StateFlow<LinkState> = link.asStateFlow()
    override val notifications: Flow<JsonObject> = notices.receiveAsFlow()
    @Volatile override var failureCode: String? = null
        private set
    @Volatile var activeRoute: ConnectionRoute? = null
        private set
    private var delegate: PocketConnection? = null
    private var candidateRoute: ConnectionRoute? = null
    private var config: ConnectionConfig? = null
    private var hello: JsonObject? = null
    private var inFlight = 0
    private var closed = false
    private var collectors: Job? = null

    override suspend fun connect(config: ConnectionConfig) = switching.withLock {
        synchronized(gate) {
            check(this.config == null && !closed)
            this.config = config; link.value = LinkState.CONNECTING
        }
        manager.configure(config)
        val generation = manager.networkGeneration
        for (route in manager.candidates()) {
            val candidate = create(route)
            try {
                withTimeout(minOf(attemptTimeoutMillis, if (route.kind == "lan") 7000 else 20000)) { candidate.connect(config) }
                if (manager.networkGeneration != generation) throw WireException("network_changed", "Network changed")
                synchronized(gate) {
                    if (closed) throw WireException("closed", "Closed")
                    delegate = candidate; candidateRoute = route; link.value = LinkState.ONLINE
                }
                observe(candidate)
                return@withLock
            } catch (e: CancellationException) {
                candidate.close()
                if (e !is TimeoutCancellationException) {
                    failureCode = "closed"; link.value = LinkState.OFFLINE
                    throw e
                }
                manager.failed(route)
            } catch (_: Exception) {
                candidate.close(); manager.failed(route)
            }
            if (manager.networkGeneration != generation) {
                failureCode = "network_changed"; link.value = LinkState.OFFLINE
                throw WireException("network_changed", "Network changed")
            }
        }
        failureCode = "routes_unavailable"; link.value = LinkState.OFFLINE
        throw WireException("routes_unavailable", "No configured route is available")
    }

    override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
        val current = synchronized(gate) {
            if (closed) throw WireException("closed", "Closed")
            val current = delegate ?: throw WireException("offline", "Offline")
            inFlight++; current
        }
        try {
            val result = current.request(method, params, commandId, timeoutMillis)
            if (method == "hello") { acceptHello(result.jsonObject, params, candidateRoute!!); activeRoute = candidateRoute }
            return result
        } catch (e: Exception) {
            if (method == "hello" && e is WireException && e.code in setOf("identity_mismatch", "protocol_mismatch")) current.close()
            if (current.state.value == LinkState.OFFLINE) { candidateRoute?.let(manager::failed); activeRoute = null }
            throw e
        } finally { synchronized(gate) { inFlight-- } }
    }

    private fun acceptHello(value: JsonObject, params: JsonObject, route: ConnectionRoute) {
        val id = value["computerId"]?.jsonPrimitive?.content ?: throw WireException("identity_mismatch", "Missing identity")
        if (value["protocol"]?.jsonPrimitive?.intOrNull != 1) throw WireException("protocol_mismatch", "Protocol differs")
        manager.checkIdentity(id)
        manager.authenticated(route, id)
        if (value.containsKey("routes")) manager.updateRoutes(value.routes(),
            value["routePolicy"]?.jsonPrimitive?.content?.let(ConnectionPolicy::parse))
        hello = params
    }

    /** Keeps the old healthy channel until the preferred candidate has authenticated.
     * Caller must sync native cursors after true. No in-flight request is moved or resent. */
    suspend fun tryPreferredRoute(): Boolean = switching.withLock {
        val saved = config ?: return@withLock false
        val credentials = hello ?: return@withLock false
        val old = synchronized(gate) { if (closed || inFlight != 0) null else delegate } ?: return@withLock false
        val route = manager.candidates().firstOrNull() ?: return@withLock false
        if (activeRoute?.let { it.url == route.url && it.kind == route.kind } == true) return@withLock false
        val generation = manager.networkGeneration
        val candidate = create(route)
        var adopted = false
        try {
            withTimeout(12000) { candidate.connect(saved) }
            val result = candidate.request("hello", credentials, timeoutMillis = 5000).jsonObject
            manager.checkIdentity(result["computerId"]?.jsonPrimitive?.content ?: "")
            if (result["protocol"]?.jsonPrimitive?.intOrNull != 1) return@withLock false
            synchronized(gate) {
                if (!closed && inFlight == 0 && delegate === old && generation == manager.networkGeneration && manager.eligible(route)) {
                    acceptHello(result, credentials, route)
                    collectors?.cancel()
                    delegate = candidate; candidateRoute = route; activeRoute = route; adopted = true
                    link.value = LinkState.ONLINE; failureCode = null
                }
            }
            if (adopted) { observe(candidate); old.close() }
            adopted
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            manager.failed(route); false
        } catch (_: Exception) { manager.failed(route); false }
        finally { if (!adopted) candidate.close() }
    }

    private fun observe(current: PocketConnection) {
        collectors = scope.launch {
            launch {
                current.state.first { it == LinkState.OFFLINE }
                synchronized(gate) {
                    if (delegate === current && !closed) {
                        failureCode = current.failureCode ?: "disconnected"
                        candidateRoute?.let(manager::failed); activeRoute = null; link.value = LinkState.OFFLINE
                    }
                }
            }
            try {
                current.notifications.collect { value ->
                    if (synchronized(gate) { delegate === current && !closed }) withTimeout(5000) { notices.send(value) }
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) current.close() else throw e
            } catch (_: Exception) { current.close() }
        }
    }
    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true; delegate?.close(); delegate = null; hello = null; activeRoute = null; link.value = LinkState.OFFLINE
        }
        collectors?.cancel(); notices.close(); scope.cancel()
    }
}
