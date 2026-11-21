package dev.codexpocket.app.data

import kotlinx.serialization.json.*

data class UsageSessionGroup(val sessionId: String, val title: String?, val totals: UsageTotals) {
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: "会话 ${usageShortId(sessionId)}"
}
fun usageShortId(id: String): String = if (id.length <= 12) id else id.take(8) + "…" + id.takeLast(4)

data class UsageRequestCursor(val createdAt: Long, val requestId: String) {
    init { require(createdAt >= 0 && requestId.toByteArray().size in 1..256 && '\u0000' !in requestId) }
    fun toJson() = buildJsonObject { put("created_at", createdAt); put("request_id", requestId) }
}
data class UsageRequestPage(val limit: Int = 50, val cursor: UsageRequestCursor? = null) {
    init { require(limit in 1..200) }
    fun toJson() = buildJsonObject { put("limit", limit); put("cursor", cursor?.toJson() ?: JsonNull) }
}
data class UsageRequestDetail(
    val requestId: String, val createdAt: Long, val providerId: String, val providerName: String?, val model: String,
    val sessionId: String?, val sessionTitle: String?, val statusCode: Int, val tokens: UsageTokens?, val costUsd: String?,
    val ttftMs: ULong?, val latencyMs: ULong?, val tokensPerSecond: Double?,
) {
    val sessionLabel: String get() = sessionId?.let { sessionTitle?.takeIf(String::isNotBlank) ?: "会话 ${usageShortId(it)}" } ?: "会话未记录"
}
data class UsageRequestDetailsPage(val items: List<UsageRequestDetail>, val limit: Int, val hasMore: Boolean, val nextCursor: UsageRequestCursor?)
data class UsageRequestFeed(val filter: UsageFilter, val page: UsageRequestDetailsPage)
data class UsageRequestsResponse(val query: JsonObject, val source: UsageSource, val availability: String,
    val page: UsageRequestDetailsPage?, val cache: UsageCache)

/** Retain at most 1000 visible rows. Deduplicate retries and never reuse a feed for another filter. */
fun appendUsageRequests(first: UsageRequestDetailsPage, next: UsageRequestDetailsPage): UsageRequestDetailsPage {
    val rows = (first.items + next.items).distinctBy { it.requestId }.take(1000)
    return next.copy(items = rows, hasMore = next.hasMore && rows.size < 1000,
        nextCursor = next.nextCursor.takeIf { rows.size < 1000 })
}
