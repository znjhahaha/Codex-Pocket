package dev.codexpocket.wire

import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.json.*

enum class ConnectionPolicy(val wireValue: String) {
    AUTO("automatic"), LAN_ONLY("lan"), PUBLIC_ONLY("public");

    companion object {
        fun parse(value: String): ConnectionPolicy = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Invalid route policy")
    }
}

/** Public URLs are complete phone endpoints. LAN endpoints carry inner TLS directly. */
data class ConnectionRoute(val id: String, val kind: String, val url: String, val priority: Int,
                           val expiresAt: Long? = null) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid route ID" }
        require(kind in setOf("lan", "cloudflare", "relay") && priority >= 0) { "Invalid route" }
        require(expiresAt == null || expiresAt >= 0) { "Invalid route expiry" }
        validateRouteUrl(url, kind)
    }
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("kind", kind); put("url", url); put("priority", priority)
        expiresAt?.let { put("expiresAt", it) }
    }
    override fun toString(): String = "ConnectionRoute(id=$id, kind=$kind)"
    companion object {
        fun parse(obj: JsonObject): ConnectionRoute = ConnectionRoute(obj.string("id"), obj.string("kind"),
            obj.string("url"), obj.integer("priority").also { require(it <= Int.MAX_VALUE) }.toInt(),
            obj["expiresAt"]?.takeUnless { it == JsonNull }?.let { obj.integer("expiresAt") })
    }
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
    require(it.isString); it.content
}
internal fun JsonObject.integer(key: String): Long = getValue(key).jsonPrimitive.let {
    require(!it.isString); it.long.also { value -> require(value >= 0) }
}
internal fun JsonObject.routes(): List<ConnectionRoute> = this["routes"]?.let { value ->
    value.jsonArray.also { require(it.size <= 32) }.map { ConnectionRoute.parse(it.jsonObject) }
} ?: emptyList()
internal fun validateRoutes(routes: List<ConnectionRoute>) {
    require(routes.size <= 32 && routes.map { it.id }.distinct().size == routes.size) { "Invalid route directory" }
}

internal fun validateWssUrl(url: String): URI {
    require(url.length in 1..8192) { "Invalid public endpoint" }
    val uri = URI(url)
    require(uri.scheme == "wss" && !uri.host.isNullOrBlank())
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.port == -1 || uri.port in 1..65535)
    require(uri.normalize().rawPath == uri.rawPath)
    return uri
}

private fun validateRouteUrl(url: String, kind: String) {
    try {
        if (kind != "lan") { validateWssUrl(url); return }
        require(url.length in 1..8192)
        val uri = URI(url)
        require(uri.scheme == "tls" && uri.port in 1..65535 && !uri.host.isNullOrEmpty())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty())
        val host = uri.host.removePrefix("[").removeSuffix("]")
        require(isPrivateLiteral(host))
    } catch (_: Exception) { throw IllegalArgumentException("Invalid route endpoint") }
}

/** Literal parsing only: never resolve an NSD-supplied hostname or follow DNS rebinding. */
internal fun isPrivateLiteral(host: String): Boolean {
    if (host.contains(':')) {
        if (!host.matches(Regex("[0-9A-Fa-f:]+"))) return false
        val address = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return false
        if (address.size != 16) return false // reject IPv4-mapped spellings
        val first = address[0].toInt() and 255
        return (address.take(15).all { it == 0.toByte() } && address[15] == 1.toByte()) || first and 254 == 252
    }
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
    val octets = parts.map { it.toInt() }
    if (octets.any { it > 255 }) return false
    return octets[0] == 10 || octets[0] == 127 ||
        (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 169 && octets[1] == 254 && octets[2] in 1..254)
}
