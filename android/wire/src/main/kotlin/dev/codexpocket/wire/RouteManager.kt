package dev.codexpocket.wire

/** Thread-free route selection memory. Reuse for the same computer across repository reconnects. */
class RouteManager internal constructor(private val nowSeconds: () -> Long, private val nowMillis: () -> Long) {
    constructor() : this({ System.currentTimeMillis() / 1000 }, { System.nanoTime() / 1_000_000 })

    private var config: ConnectionConfig? = null
    private var configured = emptyList<ConnectionRoute>()
    private var discovered = emptyList<ConnectionRoute>()
    private var selectedPolicy = ConnectionPolicy.AUTO
    private var identity: String? = null
    private var generation = 0L
    private data class Failure(val count: Int, val until: Long)
    private val failures = mutableMapOf<String, Failure>()
    private var sticky: ConnectionRoute? = null
    private var recent: ConnectionRoute? = null
    private var stickyUntil = 0L

    val networkGeneration: Long @Synchronized get() = generation
    val routes: List<ConnectionRoute> @Synchronized get() = configured.toList()
    val policy: ConnectionPolicy @Synchronized get() = selectedPolicy
    val computerId: String? @Synchronized get() = identity

    @Synchronized internal fun configure(value: ConnectionConfig) {
        if (config == value) return
        if (config?.let { it.certificateSha256 != value.certificateSha256 || it.room != value.room || it.computerId != value.computerId } == true) {
            discovered = emptyList(); failures.clear(); sticky = null; recent = null
        }
        config = value; configured = value.routes.toList(); selectedPolicy = value.policy; identity = value.computerId
    }

    /** Trusted saved/pairing or authenticated directory. Does not itself assert route readiness. */
    @Synchronized fun updateRoutes(routes: List<ConnectionRoute>, policy: ConnectionPolicy? = null,
                                    networkGeneration: Long = this.networkGeneration): Boolean {
        if (networkGeneration != generation) return false
        validateRoutes(routes)
        configured = routes.toList(); policy?.let { selectedPolicy = it }
        trimFailures()
        return true
    }

    /** NSD supplies candidate endpoints only. Certificate and device credentials stay in config. */
    @Synchronized fun updateDiscoveredRoutes(routes: List<ConnectionRoute>, networkGeneration: Long): Boolean {
        if (networkGeneration != generation) return false
        validateRoutes(routes); require(routes.all { it.kind == "lan" }) { "Discovery must contain LAN routes only" }
        discovered = routes.toList(); trimFailures()
        return true
    }

    @Synchronized fun updatePolicy(policy: ConnectionPolicy) { selectedPolicy = policy }

    /** Monotonically increasing network IDs; callbacks from previous networks cannot update this manager. */
    @Synchronized fun networkGenerationChanged(networkGeneration: Long): Boolean {
        require(networkGeneration >= 0) { "Invalid network generation" }
        if (networkGeneration <= generation) return false
        generation = networkGeneration; discovered = emptyList(); sticky = null; failures.clear()
        return true
    }

    @Synchronized internal fun candidates(): List<ConnectionRoute> {
        val now = nowMillis()
        return directory().filter { eligible(it) && (failures[key(it)]?.until ?: 0) <= now }
            .sortedWith(compareBy<ConnectionRoute>(
                { if (stickyUntil > now && sticky?.let { s -> key(s) == key(it) } == true) 0 else 1 },
                { if (it.kind == "lan") 0 else 1 }, { it.priority }, { it.id }))
    }

    /** Reconnection has one owner (the repository). A previous route cooldown
     * must not add a second delay after its bounded retry wait or manual wakeup. */
    @Synchronized internal fun connectionCandidates(): List<ConnectionRoute> =
        directory().filter(::eligible).sortedWith(compareBy<ConnectionRoute>(
            { if (recent?.let { previous -> key(previous) == key(it) } == true) 0 else 1 },
            { if (it.kind == "lan") 0 else 1 }, { it.priority }, { it.id }))

    @Synchronized internal fun hasRecentRoute(): Boolean = recent?.let(::eligible) == true

    @Synchronized internal fun eligible(route: ConnectionRoute): Boolean =
        (route.expiresAt == null || route.expiresAt > nowSeconds()) &&
            (selectedPolicy != ConnectionPolicy.LAN_ONLY || route.kind == "lan") &&
            (selectedPolicy != ConnectionPolicy.PUBLIC_ONLY || route.kind != "lan") &&
            directory().any { it == route }

    @Synchronized internal fun failed(route: ConnectionRoute) {
        val count = ((failures[key(route)]?.count ?: 0) + 1).coerceAtMost(6)
        failures[key(route)] = Failure(count, nowMillis() + (1000L shl (count - 1)).coerceAtMost(30_000))
        if (sticky?.let { key(it) == key(route) } == true) sticky = null
    }

    @Synchronized internal fun authenticated(route: ConnectionRoute, computerId: String) {
        require(computerId.isNotBlank() && computerId.length <= 256) { "Invalid computer identity" }
        if (identity != null && identity != computerId) throw WireException("identity_mismatch", "Computer identity differs")
        identity = computerId; failures.remove(key(route)); sticky = route; recent = route; stickyUntil = nowMillis() + 30_000
    }

    @Synchronized internal fun checkIdentity(computerId: String) {
        if (identity != null && identity != computerId) throw WireException("identity_mismatch", "Computer identity differs")
    }

    private fun key(route: ConnectionRoute) = route.kind + ":" + route.url
    private fun directory(): List<ConnectionRoute> {
        val all = configured + discovered.filter { candidate -> configured.none { it.id == candidate.id } }
        val base = config ?: return all
        if (base.relayUrl.isEmpty() || all.any { it.kind != "lan" && it.url == base.phoneUrl() }) return all
        var id = "legacy-relay"
        while (all.any { it.id == id }) id += "_"
        return all + ConnectionRoute(id, "relay", base.phoneUrl(), Int.MAX_VALUE)
    }
    private fun trimFailures() {
        val keys = directory().map(::key).toSet()
        failures.keys.retainAll(keys)
    }
}
