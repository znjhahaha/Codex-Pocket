package dev.codexpocket.wire

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** Authenticated application channel; implementations retain mutation outcome semantics. */
interface PocketConnection : AutoCloseable {
    val relayVersion: RelayVersion? get() = null
    val state: StateFlow<LinkState>
    val notifications: Flow<JsonObject>
    /** Fixed local transport failure code, never peer text or payload. */
    val failureCode: String? get() = null
    /** Only decoded TLS application traffic counts, never relay packets. Monotonic clock. */
    val lastApplicationActivityMillis: Long get() = 0
    suspend fun connect(config: ConnectionConfig)
    suspend fun request(method: String, params: JsonObject = buildJsonObject {}, commandId: String? = null,
                        timeoutMillis: Long = 30000): JsonElement
}
