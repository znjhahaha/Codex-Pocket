package dev.codexpocket.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.codexpocket.wire.ConnectionRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Discovery is only a hint. The wire layer still requires the paired certificate and device hello. */
@Suppress("DEPRECATION")
internal fun lanDiscovery(context: Context, computerId: String): Flow<List<ConnectionRoute>> = callbackFlow {
    val manager = context.getSystemService(NsdManager::class.java)
    if (manager == null || computerId.isBlank()) { close(); return@callbackFlow }
    val pending = Channel<NsdServiceInfo>(8)
    val routes = linkedMapOf<String, ConnectionRoute>()
    val gate = Any()
    val worker = launch {
        for (service in pending) {
            val resolved = withTimeoutOrNull(5000) { suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onServiceResolved(info: NsdServiceInfo) { if (cont.isActive) cont.resume(info) }
                }
                runCatching { manager.resolveService(service, listener) }.onFailure { if (cont.isActive) cont.resume(null) }
            } } ?: continue
            val id = resolved.attributes["computerId"]?.toString(Charsets.UTF_8) ?: continue
            if (id != computerId || resolved.attributes["protocol"]?.toString(Charsets.UTF_8) != "1") continue
            val address = resolved.host?.hostAddress ?: continue
            val literal = if (address.contains(':')) "[$address]" else address
            val route = runCatching { ConnectionRoute("nsd-${resolved.serviceName.hashCode().toUInt()}", "lan", "tls://$literal:${resolved.port}", 0) }.getOrNull() ?: continue
            synchronized(gate) {
                if (routes.size < 8 || routes.containsKey(resolved.serviceName)) routes[resolved.serviceName] = route
                trySend(routes.values.toList())
            }
        }
    }
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) { close() }
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) { if (info.serviceType.contains("_codex-pocket._tcp")) pending.trySend(info) }
        override fun onServiceLost(info: NsdServiceInfo) { synchronized(gate) { routes.remove(info.serviceName); trySend(routes.values.toList()) } }
    }
    runCatching { manager.discoverServices("_codex-pocket._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { close() }
    awaitClose { pending.close(); worker.cancel(); runCatching { manager.stopServiceDiscovery(listener) } }
}.conflate()
