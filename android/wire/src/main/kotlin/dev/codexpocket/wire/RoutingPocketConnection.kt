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
    private var connectingJob: Job? = null
    private val connecting = mutableSetOf<PocketConnection>()
    private data class ConnectResult(val route: ConnectionRoute, val client: PocketConnection, val failure: Exception? = null)

    override suspend fun connect(config: ConnectionConfig) = switching.withLock {
        synchronized(gate) {
            check(this.config == null && !closed)
            this.config = config; link.value = LinkState.CONNECTING
        }
        manager.configure(config)
        val generation = manager.networkGeneration
        try {
            val selected = coroutineScope {
                val raceContext = currentCoroutineContext()
                synchronized(gate) {
                    if (closed) throw WireException("closed", "Closed")
                    connectingJob = raceContext[Job]
                }
                val pending = manager.candidates().toMutableList()
                // Keep policy/backoff filtering, but give the first eligible LAN a head start.
                val firstLan = pending.indexOfFirst { it.kind == "lan" }
                if (firstLan > 0) pending.add(0, pending.removeAt(firstLan))
                val results = Channel<ConnectResult?>(33) // bounded candidate results plus the stagger signal
                val attempts = mutableListOf<PocketConnection>()
                val jobs = mutableListOf<Job>()
                var winner: PocketConnection? = null
                var running = 0
                var parallel = false
                var preferPublic = pending.firstOrNull()?.kind == "lan"
                fun checkRace() {
                    raceContext.ensureActive()
                    synchronized(gate) { if (closed) throw WireException("closed", "Closed") }
                    if (manager.networkGeneration != generation) throw WireException("network_changed", "Network changed")
                }
                fun startNext() {
                    checkRace()
                    val index = if (attempts.isNotEmpty() && preferPublic) {
                        preferPublic = false
                        pending.indexOfFirst { it.kind != "lan" }.coerceAtLeast(0)
                    } else 0
                    val route = pending.removeAt(index)
                    val candidate = create(route)
                    synchronized(gate) {
                        if (closed) { candidate.close(); throw WireException("closed", "Closed") }
                        connecting += candidate
                    }
                    attempts += candidate
                    running++
                    jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                        var verified = false
                        try {
                            // PocketClient.connect awaits the original pinned TLS handshake.
                            withTimeout(minOf(attemptTimeoutMillis, if (route.kind == "lan") 7000 else 20000)) {
                                candidate.connect(config)
                            }
                            ensureActive()
                            verified = true
                            results.trySend(ConnectResult(route, candidate))
                        } catch (e: Exception) {
                            if (e is CancellationException && !isActive) throw e
                            closeConnecting(candidate)
                            results.trySend(ConnectResult(route, candidate, e))
                        } finally { if (!verified) closeConnecting(candidate) }
                    }
                }
                val stagger = launch { delay(250); results.send(null) }
                // RouteManager has no change flow; bound stale-network attempts to one poll interval.
                val network = launch {
                    while (isActive) {
                        if (manager.networkGeneration != generation) throw WireException("network_changed", "Network changed")
                        delay(50)
                    }
                }
                try {
                    if (pending.isNotEmpty()) startNext()
                    while (running > 0) {
                        val result = results.receive()
                        checkRace()
                        if (result == null) parallel = true
                        else {
                            running--
                            if (result.failure == null) {
                                winner = result.client
                                return@coroutineScope result
                            }
                            if (result.failure is CancellationException && result.failure !is TimeoutCancellationException) throw result.failure
                            manager.failed(result.route)
                        }
                        while (running < (if (parallel) 2 else 1) && pending.isNotEmpty()) startNext()
                    }
                    throw WireException("routes_unavailable", "No configured route is available")
                } finally {
                    // Close even already-verified losers before exposing a single request delegate.
                    attempts.filter { it !== winner }.forEach(::closeConnecting)
                    jobs.forEach { it.cancel() }
                    stagger.cancel(); network.cancel(); results.close()
                }
            }
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                if (closed) throw WireException("closed", "Closed")
                if (manager.networkGeneration != generation) throw WireException("network_changed", "Network changed")
                if (!manager.eligible(selected.route)) throw WireException("routes_unavailable", "Selected route is no longer eligible")
                delegate = selected.client; candidateRoute = selected.route; link.value = LinkState.ONLINE
                connecting -= selected.client
                observe(selected.client)
            }
        } catch (e: CancellationException) {
            failureCode = "closed"; link.value = LinkState.OFFLINE
            throw e
        } catch (e: Exception) {
            failureCode = (e as? WireException)?.code ?: "routes_unavailable"; link.value = LinkState.OFFLINE
            throw e
        } finally {
            val abandoned = synchronized(gate) {
                connectingJob = null
                connecting.toList().also { connecting.clear() }
            }
            abandoned.forEach { it.close() }
        }
    }

    private fun closeConnecting(candidate: PocketConnection) {
        if (synchronized(gate) { connecting.remove(candidate) }) candidate.close()
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
        val (attempts, job) = synchronized(gate) {
            if (closed) return
            closed = true
            val attempts = (connecting + listOfNotNull(delegate)).toList()
            connecting.clear()
            delegate = null; hello = null; activeRoute = null; link.value = LinkState.OFFLINE
            attempts to connectingJob
        }
        attempts.forEach { it.close() }; job?.cancel()
        collectors?.cancel(); notices.close(); scope.cancel()
    }
}
