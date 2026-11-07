package dev.codexpocket.wire

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** Authenticated application channel; implementations retain mutation outcome semantics. */
interface PocketConnection : AutoCloseable {
    val state: StateFlow<LinkState>
    val notifications: Flow<JsonObject>
    /** Fixed local transport failure code, never peer text or payload. */
    val failureCode: String? get() = null
    suspend fun connect(config: ConnectionConfig)
    suspend fun request(method: String, params: JsonObject = buildJsonObject {}, commandId: String? = null,
                        timeoutMillis: Long = 30000): JsonElement
}
