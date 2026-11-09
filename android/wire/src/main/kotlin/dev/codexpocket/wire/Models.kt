package dev.codexpocket.wire

import kotlinx.serialization.json.*

enum class LinkState { OFFLINE, CONNECTING, ONLINE }
class WireException(val code: String, message: String) : Exception(message)
class UnknownOutcomeException(val commandId: String) : Exception("Command outcome is unknown; reconcile before retrying.")

data class ConnectionConfig(val relayUrl: String, val room: String, val phoneToken: String, val certificateSha256: String,
                            val routes: List<ConnectionRoute> = emptyList(), val computerId: String? = null,
                            val policy: ConnectionPolicy = ConnectionPolicy.AUTO) {
    init { validateConnection(relayUrl, room, phoneToken, certificateSha256, routes, computerId) }
    fun phoneUrl(): String = relayUrl.trimEnd('/') + "/v1/tunnel/$room/phone"
    fun toJson(): JsonObject = buildJsonObject {
        put("relayUrl", relayUrl); put("room", room); put("phoneToken", phoneToken); put("certificateSha256", certificateSha256)
        if (routes.isNotEmpty()) put("routes", JsonArray(routes.map { it.toJson() }))
        computerId?.let { put("computerId", it) }
        put("policy", policy.wireValue)
    }
    override fun toString() = "ConnectionConfig([redacted])"
    companion object {
        fun parse(obj: JsonObject): ConnectionConfig {
            try {
                return ConnectionConfig(obj.string("relayUrl"), obj.string("room"), obj.string("phoneToken"),
                    obj.string("certificateSha256"), obj.routes(), obj["computerId"]?.takeUnless { it == JsonNull }?.let { obj.string("computerId") },
                    obj["policy"]?.let { ConnectionPolicy.parse(obj.string("policy")) } ?: ConnectionPolicy.AUTO)
            } catch (_: Exception) { throw IllegalArgumentException("Invalid connection configuration") }
        }
    }
}

data class PairingCode(val relayUrl: String, val room: String, val phoneToken: String, val certificateSha256: String,
                       val pairingToken: String, val expiresAt: Long, val computerName: String,
                       val routes: List<ConnectionRoute> = emptyList(), val computerId: String? = null,
                       val policy: ConnectionPolicy = ConnectionPolicy.AUTO) {
    init {
        validateConnection(relayUrl, room, phoneToken, certificateSha256, routes, computerId)
        require(pairingToken.isNotBlank() && pairingToken.length <= 4096) { "Invalid pairing code" }
    }
    override fun toString() = "PairingCode([redacted])"
    fun connectionConfig(): ConnectionConfig = ConnectionConfig(relayUrl, room, phoneToken, certificateSha256, routes, computerId, policy)
    companion object {
        fun parse(text: String, nowSeconds: Long = System.currentTimeMillis() / 1000): PairingCode {
            try {
                require(text.length <= 65536)
                val obj = Json.parseToJsonElement(text).jsonObject
                fun string(key: String): String { val v = obj.getValue(key).jsonPrimitive; require(v.isString); return v.content }
                val version = obj.getValue("v").jsonPrimitive
                val expires = obj.getValue("expiresAt").jsonPrimitive
                require(!version.isString && version.int == 1 && !expires.isString && expires.long > nowSeconds)
                return PairingCode(string("relayUrl"), string("room"), string("phoneToken"), string("certificateSha256"),
                    string("pairingToken"), expires.long, string("computerName"), obj.routes(),
                    obj["computerId"]?.takeUnless { it == JsonNull }?.let { string("computerId") },
                    obj["policy"]?.let { ConnectionPolicy.parse(string("policy")) } ?: ConnectionPolicy.AUTO)
            } catch (_: Exception) { throw IllegalArgumentException("Invalid or expired pairing code") }
        }
    }
}

private fun validateConnection(url: String, room: String, token: String, pin: String,
                               routes: List<ConnectionRoute>, computerId: String?) {
    try {
        if (url.isNotEmpty()) validateWssUrl(url) else require(routes.isNotEmpty())
        validateRoutes(routes)
        require(computerId == null || computerId.isNotBlank() && computerId.length <= 256)
        require(room.matches(Regex("[A-Za-z0-9_-]{1,256}")))
        require(token.isNotEmpty() && token.length <= 4096 && token.all { it.code in 33..126 })
        require(pin.matches(Regex("[a-fA-F0-9]{64}")))
    } catch (_: Exception) { throw IllegalArgumentException("Invalid connection configuration") }
}
