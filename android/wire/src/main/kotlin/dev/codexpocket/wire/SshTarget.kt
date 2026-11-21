package dev.codexpocket.wire

import kotlinx.serialization.json.*

/** Stored only in the application's encrypted location record. Never log this value. */
class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
    val hostKey: String,
    val authentication: SshAuthentication,
    val backendPort: Int,
) {
    init {
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it.isISOControl() || it in "/\\@" })
        require(port in 1..65535 && backendPort in 1..65535)
        require(username.isNotBlank() && username.length <= 128 && username.none { it.isISOControl() || it in "\r\n" })
        require(hostKey.matches(Regex("SHA256:[A-Za-z0-9+/]{43}")))
    }
    override fun toString() = "SshTarget([redacted])"
    fun toJson() = buildJsonObject {
        put("version", 1); put("host", host); put("port", port); put("username", username)
        put("hostKey", hostKey); put("authentication", authentication.toJson()); put("backendPort", backendPort)
    }
    companion object {
        fun parse(value: JsonObject): SshTarget {
            require(value.integer("version") == 1L)
            return SshTarget(value.string("host"), value.integer("port").toInt(), value.string("username"),
                value.string("hostKey"), SshAuthentication.parse(value.getValue("authentication").jsonObject), value.integer("backendPort").toInt())
        }
    }
}

sealed class SshAuthentication {
    class Password(val value: String) : SshAuthentication() {
        init { require(value.isNotEmpty() && value.length <= 16384 && '\u0000' !in value) }
    }
    class PrivateKey(val pem: String, val passphrase: String = "") : SshAuthentication() {
        init { require(pem.length in 32..65536 && pem.startsWith("-----BEGIN ") && passphrase.length <= 16384) }
    }
    final override fun toString() = "SshAuthentication([redacted])"
    fun toJson(): JsonObject = when (this) {
        is Password -> buildJsonObject { put("kind", "password"); put("value", value) }
        is PrivateKey -> buildJsonObject { put("kind", "private_key"); put("pem", pem); put("passphrase", passphrase) }
    }
    companion object {
        fun parse(value: JsonObject): SshAuthentication = when (value.string("kind")) {
            "password" -> Password(value.string("value"))
            "private_key" -> PrivateKey(value.string("pem"), value.string("passphrase"))
            else -> throw IllegalArgumentException("Unsupported SSH authentication")
        }
    }
}
