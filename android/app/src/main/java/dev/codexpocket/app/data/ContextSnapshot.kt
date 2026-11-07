package dev.codexpocket.app.data

import kotlinx.serialization.json.*

data class ContextSnapshot(
    val usage: JsonObject? = null,
    val status: String = "unknown",
    val source: String = "",
    val updatedAt: Long = 0,
    val turnId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    fun json() = buildJsonObject {
        usage?.let { put("usage", it) }; put("status", status); put("source", source); put("updatedAt", updatedAt)
        turnId?.let { put("turnId", it) }
    }
    companion object {
        private fun usage(value: JsonObject?) = value?.let { raw -> buildJsonObject {
            for (part in listOf("last", "total")) (raw[part] as? JsonObject)?.let { counters -> put(part, buildJsonObject {
                for (key in listOf("inputTokens", "outputTokens", "totalTokens", "cachedInputTokens", "cacheWriteInputTokens", "reasoningOutputTokens"))
                    (counters[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let { put(key, it) }
            }) }
            (raw["modelContextWindow"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let { put("modelContextWindow", it) }
        } }
        fun from(value: JsonObject) = ContextSnapshot(usage(value["usage"] as? JsonObject), value.string("status", "unknown"),
            value.string("source"), value.number("updatedAt"), value.string("turnId").takeIf(String::isNotBlank))
    }
}
