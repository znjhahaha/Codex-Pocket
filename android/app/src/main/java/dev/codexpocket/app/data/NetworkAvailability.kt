package dev.codexpocket.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Wake reconnect on a replacement default network or newly validated internet access. */
internal fun networkAvailability(context: Context): Flow<Unit> = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    var current: Network? = null
    var validated = false
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            current = network; validated = false; trySend(Unit)
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != current) return
            val now = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (now && !validated) trySend(Unit)
            validated = now
        }
        override fun onLost(network: Network) { if (network == current) { current = null; validated = false } }
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
}.conflate()
