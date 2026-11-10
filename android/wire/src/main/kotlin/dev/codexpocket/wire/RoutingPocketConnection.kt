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
    private val upgradeStabilityMillis: Long = 30000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val create: (ConnectionRoute) -> PocketConnection,
) : PocketConnection {
    constructor(manager: RouteManager = RouteManager()) : this(manager, 20000, 30000, Dispatchers.IO, { route ->
        PocketClient(if (route.kind == "lan") LanConnector(route.url) else WssConnector(route.url))
    })
    private val gate = Any()
    private val switching = Mutex()
    private val dispatch = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val notices = Channel<JsonObject>(64)
    private val link = MutableStateFlow(LinkState.OFFLINE)
    override val state: StateFlow<LinkState> = link.asStateFlow()
    override val notifications: Flow<JsonObject> = notices.receiveAsFlow()
    override val lastApplicationActivityMillis: Long get() = synchronized(gate) { delegate?.lastApplicationActivityMillis ?: 0 }
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
    private data class ConnectResult(val route: ConnectionRoute, val client: PocketConnection, val failure: Exception? = null, val hello: JsonObject? = null)

    override suspend fun connect(config: ConnectionConfig) { connectRace(config, null) }

    /** A paired reconnect races the complete pinned TLS + device hello path. */
    suspend fun connectAuthenticated(config: ConnectionConfig, credentials: JsonObject): JsonObject =
        connectRace(config, credentials) ?: throw WireException("authentication_failed", "Missing hello")

    private suspend fun connectRace(config: ConnectionConfig, credentials: JsonObject?): JsonObject? = switching.withLock {
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
                val pending = manager.connectionCandidates().toMutableList()
                val warm = manager.hasRecentRoute()
                val results = Channel<ConnectResult?>(33) // bounded candidate results plus the stagger signal
                val attempts = mutableListOf<PocketConnection>()
                val jobs = mutableListOf<Job>()
                var winner: PocketConnection? = null
                var running = 0
                var parallel = false
                var preferPublic = pending.firstOrNull()?.kind == "lan"
                var lastFailure: Exception? = null
                fun checkRace() {
                    raceContext.ensureActive()
                    synchronized(gate) { if (closed) throw WireException("closed", "Closed") }
                    if (manager.networkGeneration != generation) throw WireException("network_changed", "Network changed")
                }
                fun startNext() {
                    checkRace()
                    val index = if (attempts.size == 1 && warm && !preferPublic) {
                        pending.indexOfFirst { it.kind == "lan" }.coerceAtLeast(0)
                    } else if (attempts.isNotEmpty() && preferPublic) {
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
                            val authenticated = withTimeout(minOf(attemptTimeoutMillis, if (route.kind == "lan") 7000 else 20000)) {
                                candidate.connect(config)
                                credentials?.let { params ->
                                    candidate.request("hello", params, timeoutMillis = 5000).jsonObject.also(::validateHello)
                                }
                            }
                            ensureActive()
                            verified = true
                            results.trySend(ConnectResult(route, candidate, hello = authenticated))
                        } catch (e: Exception) {
                            if (e is CancellationException && !isActive) throw e
                            closeConnecting(candidate)
                            results.trySend(ConnectResult(route, candidate, e))
                        } finally { if (!verified) closeConnecting(candidate) }
                    }
                }
                val stagger = launch { if (!warm) delay(250); results.send(null) }
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
                            lastFailure = result.failure
                            manager.failed(result.route)
                        }
                        while (running < (if (parallel) 2 else 1) && pending.isNotEmpty()) startNext()
                    }
                    throw (lastFailure as? WireException)?.takeIf { it.code in setOf("unauthorized", "revoked", "device_revoked", "authentication_failed") }
                        ?: WireException("routes_unavailable", "No configured route is available")
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
                if (selected.client.state.value != LinkState.ONLINE) throw WireException("disconnected", "Selected connection closed")
                if (manager.networkGeneration != generation) throw WireException("network_changed", "Network changed")
                if (!manager.eligible(selected.route)) throw WireException("routes_unavailable", "Selected route is no longer eligible")
                delegate = selected.client; candidateRoute = selected.route; link.value = LinkState.ONLINE
                if (credentials != null) {
                    acceptHello(selected.hello!!, credentials, selected.route)
                    activeRoute = selected.route
                }
                connecting -= selected.client
                observe(selected.client)
            }
            selected.hello
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
        return dispatchRequest(method, params, commandId, timeoutMillis, heartbeat = false)
    }

    /** A heartbeat deadline measures its response, excluding time queued behind a route swap. */
    suspend fun heartbeat(method: String, timeoutMillis: Long = 2000): JsonElement =
        dispatchRequest(method, buildJsonObject {}, null, timeoutMillis, heartbeat = true)

    private suspend fun dispatchRequest(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long, heartbeat: Boolean): JsonElement {
        val current = dispatch.withLock { synchronized(gate) {
            if (closed) throw WireException("closed", "Closed")
            val current = delegate ?: throw WireException("offline", "Offline")
            inFlight++; current
        } }
        try {
            val result = if (heartbeat) withTimeout(timeoutMillis) { current.request(method, params, commandId, timeoutMillis) }
                else current.request(method, params, commandId, timeoutMillis)
            if (method == "hello") { acceptHello(result.jsonObject, params, candidateRoute!!); activeRoute = candidateRoute }
            return result
        } catch (e: Exception) {
            if (method == "hello" && e is WireException && e.code in setOf("identity_mismatch", "protocol_mismatch")) current.close()
            if (current.state.value == LinkState.OFFLINE) { candidateRoute?.let(manager::failed); activeRoute = null }
            throw e
        } finally { synchronized(gate) { inFlight-- } }
    }

    private fun validateHello(value: JsonObject) {
        val id = value["computerId"]?.jsonPrimitive?.content ?: throw WireException("identity_mismatch", "Missing identity")
        if (value["protocol"]?.jsonPrimitive?.intOrNull != 1) throw WireException("protocol_mismatch", "Protocol differs")
        manager.checkIdentity(id)
    }
    private fun acceptHello(value: JsonObject, params: JsonObject, route: ConnectionRoute) {
        validateHello(value)
        val id = value.getValue("computerId").jsonPrimitive.content
        manager.authenticated(route, id)
        if (value.containsKey("routes")) manager.updateRoutes(value.routes(),
            value["routePolicy"]?.jsonPrimitive?.content?.let(ConnectionPolicy::parse))
        hello = params
    }

    /** One continuous reader: discard the stabilization prefix, retain everything from before sync. */
    private class CandidateEvents(private val client: PocketConnection, scope: CoroutineScope) {
        private data class Buffered(val value: JsonObject, val bytes: Int)
        private val gate = Any()
        private val queue = Channel<Buffered>(512)
        private var retaining = false
        private var bytes = 0L
        @Volatile var failed = false
            private set
        val notifications = queue.receiveAsFlow().map { buffered ->
            synchronized(gate) { bytes -= buffered.bytes }
            buffered.value
        }
        private val reader = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                client.notifications.collect { value ->
                    if (!synchronized(gate) { retaining }) return@collect
                    val size = value.toString().toByteArray(Charsets.UTF_8).size
                    val retained = synchronized(gate) {
                        if (failed || bytes + size > 8L * 1024 * 1024) false
                        else queue.trySend(Buffered(value, size)).isSuccess.also { if (it) bytes += size }
                    }
                    if (!retained) throw WireException("backpressure", "Candidate event capacity exceeded")
                }
                // A normally completed event stream cannot be adopted either.
                failed = true
                client.close()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; client.close() }
            finally { queue.close() }
        }
        fun retain() { synchronized(gate) { retaining = true } }
        fun close() { reader.cancel(); queue.cancel() }
    }

    /** The old connection continues serving while the candidate stabilizes.
     * Final synchronization runs behind the dispatch gate, with no in-flight request.
     * A failed prepare keeps the old connection; no request is moved or resent. */
    suspend fun tryPreferredRoute(prepare: suspend (PocketConnection) -> Unit): Boolean = switching.withLock {
        val saved = config ?: return@withLock false
        val credentials = hello ?: return@withLock false
        val old = synchronized(gate) { if (closed || inFlight != 0) null else delegate } ?: return@withLock false
        val route = manager.candidates().firstOrNull() ?: return@withLock false
        if (activeRoute?.let { it.url == route.url && it.kind == route.kind } == true) return@withLock false
        val generation = manager.networkGeneration
        val candidate = create(route)
        var adopted = false
        var events: CandidateEvents? = null
        synchronized(gate) {
            if (closed) { candidate.close(); return@withLock false }
            connecting += candidate
        }
        try {
            withTimeout(12000) { candidate.connect(saved) }
            val candidateEvents = CandidateEvents(candidate, scope)
            events = candidateEvents
            val result = candidate.request("hello", credentials, timeoutMillis = 5000).jsonObject
            validateHello(result)
            var stable = 0L
            while (stable < upgradeStabilityMillis) {
                val interval = minOf(5000, upgradeStabilityMillis - stable)
                delay(interval); stable += interval
                if (generation != manager.networkGeneration || closed || !manager.eligible(route) || candidateEvents.failed || candidate.state.value != LinkState.ONLINE) return@withLock false
                withTimeout(2000) { candidate.request("ping", timeoutMillis = 2000) }
            }
            dispatch.withLock {
                val canPrepare = synchronized(gate) {
                    !closed && inFlight == 0 && delegate === old && generation == manager.networkGeneration && manager.eligible(route) &&
                        !candidateEvents.failed && candidate.state.value == LinkState.ONLINE
                }
                if (canPrepare) {
                    candidateEvents.retain()
                    prepare(candidate)
                    currentCoroutineContext().ensureActive()
                    synchronized(gate) {
                        if (!closed && inFlight == 0 && delegate === old && generation == manager.networkGeneration && manager.eligible(route) &&
                            !candidateEvents.failed && candidate.state.value == LinkState.ONLINE) {
                            acceptHello(result, credentials, route)
                            collectors?.cancel()
                            delegate = candidate; candidateRoute = route; activeRoute = route; adopted = true
                            connecting -= candidate
                            link.value = LinkState.ONLINE; failureCode = null
                            observe(candidate, candidateEvents)
                        }
                    }
                }
            }
            if (adopted) old.close()
            adopted
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            manager.failed(route); false
        } catch (_: Exception) { manager.failed(route); false }
        finally {
            synchronized(gate) { connecting -= candidate }
            if (!adopted) { events?.close(); candidate.close() }
        }
    }

    private fun observe(current: PocketConnection, buffered: CandidateEvents? = null) {
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
                (buffered?.notifications ?: current.notifications).collect { value ->
                    if (synchronized(gate) { delegate === current && !closed }) withTimeout(5000) { notices.send(value) }
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) current.close() else throw e
            } catch (_: Exception) { current.close() }
            finally { buffered?.close() }
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
