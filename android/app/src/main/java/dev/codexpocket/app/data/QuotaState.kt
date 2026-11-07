package dev.codexpocket.app.data

import kotlinx.serialization.json.*

data class QuotaState(val runtimeId: String? = null, val sources: List<JsonObject> = emptyList(),
    val selectedSourceId: String? = null, val result: JsonObject? = null, val loading: Boolean = false,
    val status: String = "idle", val computerId: String? = null,
    val resultsBySource: Map<String, JsonObject> = emptyMap(),
    val readTimings: Map<String, QuotaReadTiming> = emptyMap()) {
    fun matches(computer: String, runtime: String?) = computerId == computer && runtimeId == runtime

    fun forScope(computer: String, runtime: String) =
        if (matches(computer, runtime)) this else QuotaState(runtimeId = runtime, computerId = computer)

    private fun cachedResults(): Map<String, JsonObject> = resultsBySource +
        (result?.takeIf { it.string("runtimeId") == runtimeId && it.string("sourceId") == selectedSourceId }
            ?.let { mapOf(it.string("sourceId") to it) } ?: emptyMap())

    fun withSources(computer: String, runtime: String, values: List<JsonObject>, associatedId: String?, sourceStatus: String): QuotaState {
        val previous = forScope(computer, runtime)
        // A failed discovery is not evidence that the previously discovered source disappeared.
        if (sourceStatus in setOf("unavailable", "unsupported")) return previous.withFailure(sourceStatus)
        val ids = values.map { it.string("id") }.filter(String::isNotBlank).toSet()
        // A retained explicit choice is valid only while its opaque ID still exists.
        // New choices require the helper's verifiable association, never list position.
        val id = previous.selectedSourceId?.takeIf { it in ids }
            ?: associatedId?.takeIf { candidate -> values.any { it.string("id") == candidate && it.flag("associated") } }
        val cache = previous.cachedResults().filterKeys { it in ids }
        val retained = cache[id]
        val status = when {
            sourceStatus in setOf("unavailable", "unconfigured", "unsupported") -> sourceStatus
            retained != null -> retained.string("status", "unknown")
            id != null -> if (values.firstOrNull { it.string("id") == id }?.string("status") == "unconfigured") "unconfigured" else "ready"
            sourceStatus == "ok" -> "unmatched"
            else -> sourceStatus
        }
        return previous.copy(sources = values, selectedSourceId = id, result = retained,
            resultsBySource = cache, readTimings = previous.readTimings.filterKeys { it in ids }, loading = false, status = status)
    }

    fun selectSource(id: String): QuotaState {
        if (sources.none { it.string("id") == id }) return this
        val cache = cachedResults()
        val retained = cache[id]
        return copy(selectedSourceId = id, result = retained, resultsBySource = cache,
            status = retained?.string("status", "unknown") ?: "unknown")
    }

    fun withResult(value: JsonObject): QuotaState {
        if (value.string("runtimeId") != runtimeId || value.string("sourceId") != selectedSourceId) return this
        val old = result
        val oldAt = old?.number("updatedAt") ?: 0
        val incomingAt = value.number("updatedAt")
        val status = value.string("status", "unknown")
        val merged = if (old != null && oldAt > 0 && incomingAt < oldAt) {
            JsonObject(value + mapOf("items" to (old["items"] ?: JsonArray(emptyList())),
                "updatedAt" to (old["updatedAt"] ?: JsonNull), "stale" to JsonPrimitive(true)))
        } else if (status !in setOf("ok", "ready")) JsonObject(value + ("stale" to JsonPrimitive(true))) else value
        return copy(result = merged, resultsBySource = cachedResults() + (value.string("sourceId") to merged), loading = false, status = status)
    }

    fun withFailure(failureStatus: String): QuotaState {
        val retained = result?.let { JsonObject(it + mapOf("status" to JsonPrimitive(failureStatus), "stale" to JsonPrimitive(true))) }
        return copy(result = retained, resultsBySource = cachedResults() +
            (retained?.let { mapOf(it.string("sourceId") to it) } ?: emptyMap()), loading = false, status = failureStatus)
    }

    fun withNotification(value: JsonObject): QuotaState {
        if (value.string("runtimeId") != runtimeId || value.string("sourceId") != selectedSourceId ||
            sources.none { it.string("id") == selectedSourceId }) return this
        val items = value["items"] as? JsonArray ?: return this
        if (items.any { it !is JsonObject } || value.string("status") !in quotaResultStatuses) return this
        val timestamp = value["updatedAt"]
        val updatedAt = (timestamp as? JsonPrimitive)?.longOrNull
        if (timestamp != JsonNull && (updatedAt == null || updatedAt <= 0)) return this
        if (value.string("status") == "ok" && updatedAt == null) return this
        if (updatedAt != null && updatedAt < (result?.number("updatedAt") ?: 0)) return this
        return withResult(value)
    }
}

private val quotaResultStatuses = setOf("ok", "unknown", "unconfigured", "auto_disabled", "invalid", "unavailable",
    "script_error", "timeout", "http_error", "limit_exceeded")

/** Request scopes include generations so switching away and back cannot revive an old reply. */
internal data class QuotaRequestScope(val client: Any?, val computerId: String, val runtimeId: String,
    val connectionGeneration: Long, val selectionGeneration: Long) {
    fun sameAs(other: QuotaRequestScope) = client === other.client && computerId == other.computerId &&
        runtimeId == other.runtimeId && connectionGeneration == other.connectionGeneration && selectionGeneration == other.selectionGeneration
}

internal data class QuotaRequestTicket(val scope: QuotaRequestScope, val sourcesGeneration: Long, val readGeneration: Long? = null) {
    fun isCurrent(current: QuotaRequestScope, sources: Long, read: Long) = scope.sameAs(current) &&
        sourcesGeneration == sources && (readGeneration == null || readGeneration == read)
}

data class QuotaReadTiming(val lastAttemptAt: Long? = null, val automaticRetryAt: Long = 0, val manualRetryAt: Long = 0)

private fun quotaMillis(seconds: Long) = seconds.coerceIn(0, Long.MAX_VALUE / 1000) * 1000
private fun quotaDeadline(now: Long, delay: Long) = now.coerceAtLeast(0).let { it + delay.coerceAtMost(Long.MAX_VALUE - it) }

/** null disables automatic requests; all local scheduling and server timestamps use milliseconds. */
fun quotaRefreshDelayMillis(source: JsonObject, timing: QuotaReadTiming, manual: Boolean, now: Long): Long? {
    val automatic = source.number("autoRefreshSeconds", 60)
    if (!manual && automatic <= 0) return null
    val interval = quotaMillis(if (manual) source.number("manualCooldownSeconds", 15).coerceAtLeast(15) else automatic.coerceAtLeast(60))
    val elapsed = timing.lastAttemptAt?.let { (now.coerceAtLeast(it) - it).coerceAtLeast(0) }
    val local = elapsed?.let { (interval - it).coerceAtLeast(0) } ?: 0
    val retry = if (manual) timing.manualRetryAt else timing.automaticRetryAt
    return maxOf(local, (retry - now.coerceAtLeast(0)).coerceAtLeast(0))
}

fun QuotaReadTiming.withRetry(seconds: Long, manual: Boolean, now: Long): QuotaReadTiming {
    val deadline = quotaDeadline(now, quotaMillis(seconds))
    return if (manual) copy(manualRetryAt = deadline) else copy(automaticRetryAt = deadline)
}

fun quotaFailureStatus(code: String?): String = when (code) {
    "unsupported", "method_not_found", "unsupported_method", "-32601" -> "unsupported"
    "offline", "disconnected" -> "offline"
    "not_found", "unauthorized", "revoked" -> "unavailable"
    "invalid_params", "-32602" -> "invalid"
    "timeout" -> "timeout"
    "rate_limited", "cooldown" -> "cooldown"
    else -> "failed"
}

fun quotaStatusMessage(status: String, stale: Boolean = false): String? = when (status) {
    "ok", "ready" -> if (stale) "最近结果已过期，等待刷新" else null
    "unconfigured" -> "此来源尚未配置额度查询"
    "unsupported" -> "当前助手或运行时不支持额度查询"
    "unmatched" -> "尚未关联额度来源，可选择已配置账户查看"
    "unavailable" -> "额度来源暂不可用，请刷新来源后重试"
    "auto_disabled" -> "此来源已关闭自动查询，可手动刷新"
    "unknown" -> "此来源暂未提供额度数据"
    "invalid" -> "额度查询配置或结果无效"
    "script_error" -> "额度查询脚本执行失败"
    "timeout" -> "额度查询超时，可稍后重试"
    "http_error" -> "额度服务请求失败，可稍后重试"
    "limit_exceeded" -> "额度查询超过助手限制"
    "offline" -> "电脑已离线，显示最近获取的结果"
    "failed", "error" -> "查询失败，可稍后重试"
    "rate_limited", "cooldown" -> "刷新间隔内，显示上次结果"
    else -> "等待额度数据"
}

data class ContextUsage(val used: Long?, val capacity: Long?, val cumulative: Long?) {
    val fraction: Float? get() = if (used != null && capacity != null && used >= 0 && capacity > 0)
        (used.toDouble() / capacity).coerceIn(0.0, 1.0).toFloat() else null
    companion object {
        fun from(usage: JsonObject?): ContextUsage {
            fun JsonObject.amount(key: String) = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
            // The latest native round includes output; lifetime totals are a separate counter.
            return ContextUsage(usage?.obj("last")?.amount("totalTokens"), usage?.amount("modelContextWindow"), usage?.obj("total")?.amount("totalTokens"))
        }
    }
}
