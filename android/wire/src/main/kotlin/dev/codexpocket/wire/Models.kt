package dev.codexpocket.wire

import java.net.URI
import kotlinx.serialization.json.*

enum class LinkState { OFFLINE, CONNECTING, ONLINE }
class WireException(val code: String, message: String) : Exception(message)
class UnknownOutcomeException(val commandId: String) : Exception("Command outcome is unknown; reconcile before retrying.")

data class ConnectionConfig(val relayUrl: String, val room: String, val phoneToken: String, val certificateSha256: String) {
    init { validateConnection(relayUrl, room, phoneToken, certificateSha256) }
    fun phoneUrl(): String = relayUrl.trimEnd('/') + "/v1/tunnel/$room/phone"
    override fun toString() = "ConnectionConfig([redacted])"
}

data class PairingCode(val relayUrl: String, val room: String, val phoneToken: String, val certificateSha256: String,
                       val pairingToken: String, val expiresAt: Long, val computerName: String) {
    init {
        validateConnection(relayUrl, room, phoneToken, certificateSha256)
        require(pairingToken.isNotBlank() && pairingToken.length <= 4096) { "Invalid pairing code" }
    }
    override fun toString() = "PairingCode([redacted])"
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
                    string("pairingToken"), expires.long, string("computerName"))
            } catch (_: Exception) { throw IllegalArgumentException("Invalid or expired pairing code") }
        }
    }
}

private fun validateConnection(url: String, room: String, token: String, pin: String) {
    try {
        require(url.length <= 8192)
        val uri = URI(url)
        require(uri.scheme == "wss" && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        require(uri.normalize().rawPath == uri.rawPath)
        require(room.matches(Regex("[A-Za-z0-9_-]{1,256}")))
        require(token.isNotEmpty() && token.length <= 4096 && token.all { it.code in 33..126 })
        require(pin.matches(Regex("[a-fA-F0-9]{64}")))
    } catch (_: Exception) { throw IllegalArgumentException("Invalid connection configuration") }
}
