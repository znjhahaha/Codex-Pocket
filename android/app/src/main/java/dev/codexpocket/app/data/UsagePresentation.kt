package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class UsageDataSource(val wire: String, val label: String) {
    Proxy("proxy", "代理请求"), CodexSession("codex_session", "Codex 会话导入"),
}
/** Whose record the figures are read from. The two are separate ledgers and are never added together. */
enum class UsageSourceKind(val wire: String, val label: String) {
    CcSwitch("cc_switch", "CC Switch"), NativeCodex("native_codex", "原生 Codex"),
}
/** What a source records at all. Absent means the v1 CC Switch contract, which records everything. */
data class UsageCapabilities(val cost: Boolean = true, val performance: Boolean = true, val status: Boolean = true)
enum class UsageStatusFilter(val wire: String, val label: String) {
    All("all", "全部状态"), Success("success", "成功"), Error("error", "失败"),
}
data class UsagePage(val offset: Int = 0, val limit: Int = 50) {
    init { require(offset in 0..10000 && limit in 1..200) { "Invalid usage page" } }
    fun toJson() = buildJsonObject { put("offset", offset); put("limit", limit) }
}

/** Inclusive local dates become explicit UTC edges, including 23/25-hour DST days. */
data class UsageFilter(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val zoneId: String,
    val appType: String = "codex",
    val dataSource: UsageDataSource = UsageDataSource.Proxy,
    val status: UsageStatusFilter = UsageStatusFilter.All,
    val providerId: String? = null,
    val model: String? = null,
    val providerPage: UsagePage = UsagePage(),
    val modelPage: UsagePage = UsagePage(),
    val sessionId: String? = null,
    val sessionPage: UsagePage = UsagePage(),
    val source: UsageSourceKind = UsageSourceKind.CcSwitch,
) {
    init {
        require(ChronoUnit.DAYS.between(startDate, endDate) in 0..30) { "Choose 1 to 31 days" }
        ZoneId.of(zoneId)
        require(appType in apps) { "Unsupported usage app" }
        // The native ledger is Codex only and has neither an import source nor a per-request status.
        require(source != UsageSourceKind.NativeCodex || (appType == "codex" && dataSource == UsageDataSource.Proxy && status == UsageStatusFilter.All)) { "Filter does not apply to native usage" }
        require(dataSource != UsageDataSource.CodexSession || appType == "codex") { "Session source requires Codex" }
        listOf(providerId, model, sessionId).forEach { require(it == null || (it.toByteArray(Charsets.UTF_8).size in 1..256 && '\u0000' !in it)) { "Invalid usage identity" } }
    }
    fun toQuery(): UsageQuery {
        val zone = ZoneId.of(zoneId)
        val count = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
        val edges = (0..count).map { startDate.plusDays(it.toLong()).atStartOfDay(zone).toEpochSecond() }
        require(edges.first() >= 0 && edges.last() <= 253402300799L)
        require(edges.zipWithNext().all { (a, b) -> b > a && b - a <= 27 * 3600 })
        require(edges.last() - edges.first() <= 32 * 86400)
        return UsageQuery(edges.first(), edges.last(), edges, UsageQueryFilters(appType, dataSource, providerId, model, status, sessionId), providerPage, modelPage, sessionPage)
    }
    fun toJson() = toQuery().toJson()
    private fun reset() = copy(providerPage = providerPage.copy(offset = 0), modelPage = modelPage.copy(offset = 0), sessionPage = sessionPage.copy(offset = 0))
    fun withDates(start: LocalDate, end: LocalDate) = copy(startDate = start, endDate = end).reset()
    fun withZone(zone: String) = copy(zoneId = zone).reset()
    /** Providers, models and sessions are named differently by each source, so those choices do not carry over. */
    fun withSource(value: UsageSourceKind) = if (value == source) this else if (value == UsageSourceKind.NativeCodex)
        copy(source = value, appType = "codex", dataSource = UsageDataSource.Proxy, status = UsageStatusFilter.All, providerId = null, model = null, sessionId = null).reset()
        else copy(source = value, providerId = null, model = null, sessionId = null).reset()
    fun withAppType(app: String) = copy(appType = app,
        dataSource = if (app == "codex") dataSource else UsageDataSource.Proxy, providerId = null, model = null, sessionId = null).reset()
    fun withDataSource(source: UsageDataSource) = copy(dataSource = source, providerId = null, model = null, sessionId = null).reset()
    fun withStatus(value: UsageStatusFilter) = copy(status = value).reset()
    fun withProvider(value: String?) = copy(providerId = value).reset()
    fun withModel(value: String?) = copy(model = value).reset()
    fun withSession(value: String?) = copy(sessionId = value).reset()
    companion object {
        val apps = listOf("codex", "claude", "gemini", "grokbuild")
        fun lastDays(days: Int = 7, zoneId: String = "Asia/Shanghai",
            today: LocalDate = LocalDate.now(ZoneId.of(zoneId))): UsageFilter {
            require(days in 1..31)
            return UsageFilter(today.minusDays(days - 1L), today, zoneId)
        }
    }
}
data class UsageQueryFilters(val appType: String, val dataSource: UsageDataSource, val providerId: String?, val model: String?, val status: UsageStatusFilter, val sessionId: String? = null) {
    fun toJson() = buildJsonObject {
        put("app_type", appType); put("data_source", dataSource.wire)
        put("provider_id", providerId?.let(::JsonPrimitive) ?: JsonNull)
        put("model", model?.let(::JsonPrimitive) ?: JsonNull); put("status", status.wire)
        put("session_id", sessionId?.let(::JsonPrimitive) ?: JsonNull)
    }
}
data class UsageQuery(val startAt: Long, val endAt: Long, val bucketEdges: List<Long>, val filters: UsageQueryFilters,
    val providerPage: UsagePage, val modelPage: UsagePage, val sessionPage: UsagePage = UsagePage()) {
    fun toJson() = buildJsonObject {
        put("start_at", startAt); put("end_at", endAt)
        put("bucket_edges", JsonArray(bucketEdges.map(::JsonPrimitive)))
        put("filters", filters.toJson()); put("provider_page", providerPage.toJson()); put("model_page", modelPage.toJson()); put("session_page", sessionPage.toJson())
        put("request_page", UsageRequestPage().toJson())
    }
}

enum class UsagePhase {
    NotConnected, Idle, Loading, Ready, Offline, Missing, Locked, UnsupportedSchema, UnsupportedHelper, InvalidPath, Rejected, Failed, Corrupt,
}
data class UsageSnapshot(val filter: UsageFilter, val response: UsageResponse, val updatedAt: Long)
data class UsageScreenState(
    val filter: UsageFilter = UsageFilter.lastDays(),
    val snapshot: UsageSnapshot? = null,
    val phase: UsagePhase = UsagePhase.NotConnected,
    val refreshing: Boolean = false,
    val canQuery: Boolean = false,
    val cached: Boolean = false,
    val canPageRequests: Boolean = false,
    val requestFeed: UsageRequestFeed? = null,
    val requestLoading: Boolean = false,
    val requestError: String? = null,
    val observedSchemaVersion: Long? = null,
    val failureCode: String? = null,
    /** Sources this computer can be asked for; more than one only when it announces usage v2. */
    val sources: List<UsageSourceKind> = listOf(UsageSourceKind.CcSwitch),
) {
    val visibleSnapshot: UsageSnapshot? get() = snapshot?.takeIf {
        it.filter == filter && it.response.source.kind == filter.source.wire && it.response.source.appType == filter.appType && it.response.source.dataSource == filter.dataSource &&
            (it.response.query == null || usageQueryMatches(it.response.query, filter.toJson()))
    }
    val visibleRequests: UsageRequestDetailsPage? get() = visibleSnapshot?.response?.data?.recentRequests?.let { first ->
        requestFeed?.takeIf { it.filter == filter }?.page ?: first
    }
}

data class UsageCoverage(val scope: String, val complete: Boolean, val matchedRows: ULong, val firstObservedAt: Long?, val lastObservedAt: Long?,
    val rollupsPresent: Boolean, val rollupsIncluded: Boolean, val costKnownRows: ULong, val costMissingRows: ULong, val costInvalidRows: ULong,
    val zeroCostRows: ULong, val tokenInvalidRows: ULong, val confirmedFreeRows: ULong?, val zeroCostInterpretation: String)
data class UsageSource(val kind: String, val table: String, val appType: String, val dataSource: UsageDataSource, val schemaVersion: Long?)
data class UsageError(val code: String, val message: String)
data class UsageCache(val state: String, val sampledAt: Long?, val ageSeconds: ULong?, val ttlSeconds: ULong, val staleReason: String?)
data class UsageTokens(val freshInput: ULong, val cacheRead: ULong, val cacheWrite: ULong, val output: ULong, val total: ULong, val invalidRows: ULong)
data class UsageCostAmount(val knownUsd: String, val knownRows: ULong, val missingRows: ULong, val invalidRows: ULong, val zeroRows: ULong) {
    fun display(): String = if (knownRows == 0uL) "未知" else "USD " + decimal(knownUsd).stripTrailingZeros().toPlainString()
    fun coverageLabel() = "已知 ${usageCount(knownRows)} · 缺失 ${usageCount(missingRows)} · 无效 ${usageCount(invalidRows)} · 零值 ${usageCount(zeroRows)}"
}
data class UsageCosts(val input: UsageCostAmount, val output: UsageCostAmount, val cacheRead: UsageCostAmount, val cacheWrite: UsageCostAmount, val total: UsageCostAmount)
data class UsageTimingMetric(val averageMs: Double? = null, val sumMs: ULong = 0u, val sampleRows: ULong = 0u,
    val missingRows: ULong = 0u, val invalidRows: ULong = 0u, val excludedRows: ULong = 0u) {
    fun display() = if (sampleRows == 0uL || averageMs == null) "未知" else usageDecimal(averageMs) + " ms"
    fun coverageLabel() = performanceCoverage(sampleRows, missingRows, invalidRows, excludedRows)
}
data class UsageSpeedMetric(val tokensPerSecond: Double? = null, val sumOutputTokens: ULong = 0u, val sumGenerationMs: ULong = 0u,
    val sampleRows: ULong = 0u, val missingRows: ULong = 0u, val invalidRows: ULong = 0u, val excludedRows: ULong = 0u) {
    fun display() = if (sampleRows == 0uL || tokensPerSecond == null) "未知" else usageDecimal(tokensPerSecond) + " token/s"
    fun coverageLabel() = performanceCoverage(sampleRows, missingRows, invalidRows, excludedRows)
}
data class UsagePerformance(val scope: String, val successRows: ULong, val ttft: UsageTimingMetric, val duration: UsageTimingMetric, val outputSpeed: UsageSpeedMetric)
data class UsageTotals(val requests: ULong, val success: ULong, val errors: ULong, val tokens: UsageTokens, val costs: UsageCosts, val performance: UsagePerformance)
data class UsageTrendBucket(val startAt: Long, val endAt: Long, val totals: UsageTotals)
data class UsageGroupPage<T>(val items: List<T>, val offset: Int, val limit: Int, val totalGroups: Int, val hasMore: Boolean)
data class UsageProviderGroup(val providerId: String, val appType: String, val name: String?, val totals: UsageTotals)
data class UsageModelGroup(val model: String, val totals: UsageTotals)
data class UsageData(val totals: UsageTotals, val trend: List<UsageTrendBucket>, val providerGroups: UsageGroupPage<UsageProviderGroup>, val modelGroups: UsageGroupPage<UsageModelGroup>,
    val sessionGroups: UsageGroupPage<UsageSessionGroup>? = null, val recentRequests: UsageRequestDetailsPage? = null)

data class UsageResponse(val availability: String, val coverage: UsageCoverage, val source: UsageSource, val errors: List<UsageError>,
    val data: UsageData?, val cache: UsageCache? = null, val query: JsonObject? = null, val capabilities: UsageCapabilities = UsageCapabilities()) {
    val phase: UsagePhase get() = when (availability) {
        "ready" -> UsagePhase.Ready; "missing" -> UsagePhase.Missing; "locked" -> UsagePhase.Locked
        "unsupported_schema" -> UsagePhase.UnsupportedSchema; "invalid_path" -> UsagePhase.InvalidPath
        "rejected" -> UsagePhase.Rejected
        else -> if (errors.any { it.code == "database_corrupt" }) UsagePhase.Corrupt else UsagePhase.Failed
    }
    companion object {
        /** Accepts the statistics result, the schema=1 RPC envelope, or the source-naming schema=2 one. No lossy defaults. */
        fun fromJson(json: JsonObject): UsageResponse {
            if ("result" in json) {
                json.keysRequired("schema query result cache")
                val schema = json.long("schema")
                require(schema == 1L || schema == 2L) { "Unsupported usage RPC schema" }
                val q = json.usageObject("query").also(::validateQuery)
                val response = parseResponse(json.usageObject("result")).copy(cache = parseCache(json.usageObject("cache")), query = q)
                if (schema == 2L) require(json.string("source") == response.source.kind) { "Usage source mismatch" }
                else require(response.source.kind == UsageSourceKind.CcSwitch.wire) { "Usage source mismatch" }
                return response
            }
            return parseResponse(json)
        }
    }
}

fun usageCount(value: ULong): String = value.toString().reversed().chunked(3).joinToString(",").reversed()
fun usageDecimal(value: Double): String = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
fun usageTime(seconds: Long, zone: String): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(Instant.ofEpochSecond(seconds).atZone(ZoneId.of(zone)))
private fun performanceCoverage(sample: ULong, missing: ULong, invalid: ULong, excluded: ULong) =
    "样本 ${usageCount(sample)} · 缺失 ${usageCount(missing)} · 无效 ${usageCount(invalid)} · 排除 ${usageCount(excluded)}"

private fun JsonObject.keysRequired(keys: String) { require(this.keys.containsAll(keys.split(' ').toSet())) { "Unexpected usage fields" } }
private fun JsonObject.field(key: String): JsonElement = requireNotNull(this[key]) { "Missing usage field: $key" }
private fun JsonObject.usageObject(key: String): JsonObject = field(key) as? JsonObject ?: throw IllegalArgumentException("Expected usage object: $key")
private fun JsonObject.usageArray(key: String): JsonArray = field(key) as? JsonArray ?: throw IllegalArgumentException("Expected usage array: $key")
private fun JsonElement.asUsageObject(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("Expected usage object")
private fun JsonObject.string(key: String): String = (field(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Expected usage string: $key")
private fun JsonObject.nullString(key: String): String? = if (field(key) == JsonNull) null else string(key)
private fun JsonObject.bool(key: String): Boolean = (field(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw IllegalArgumentException("Expected usage boolean: $key")
private fun JsonObject.unsigned(key: String): ULong {
    val p = field(key) as? JsonPrimitive
    require(p != null && !p.isString && p.content.matches(Regex("0|[1-9][0-9]*"))) { "Expected usage u64: $key" }
    return p.content.toULongOrNull() ?: throw IllegalArgumentException("Usage u64 overflow: $key")
}
private fun JsonObject.nullUnsigned(key: String): ULong? = if (field(key) == JsonNull) null else unsigned(key)
private fun JsonObject.long(key: String): Long {
    val p = field(key) as? JsonPrimitive
    require(p != null && !p.isString && p.content.matches(Regex("0|[1-9][0-9]*"))) { "Expected usage timestamp: $key" }
    return p.content.toLongOrNull() ?: throw IllegalArgumentException("Usage timestamp overflow")
}
private fun JsonObject.nullLong(key: String): Long? = if (field(key) == JsonNull) null else long(key)
private fun JsonObject.int(key: String): Int = long(key).also { require(it <= Int.MAX_VALUE) }.toInt()
private fun JsonObject.nullDouble(key: String): Double? {
    if (field(key) == JsonNull) return null
    val p = field(key) as? JsonPrimitive
    val n = p?.takeUnless { it.isString }?.doubleOrNull
    require(n != null && n.isFinite() && n >= 0) { "Invalid usage measurement" }
    return n
}
private fun decimal(text: String): BigDecimal {
    require(text.length <= 70 && text.matches(Regex("[0-9]+(?:\\.[0-9]{1,28})?"))) { "Invalid usage decimal" }
    val d = BigDecimal(text)
    require(d.unscaledValue() <= BigInteger("170141183460469231731687303715884105727")) { "Usage decimal overflow" }
    return d
}
private fun source(text: String): UsageDataSource = UsageDataSource.entries.singleOrNull { it.wire == text } ?: throw IllegalArgumentException("Unsupported usage source")
private fun parseResponse(j: JsonObject): UsageResponse {
    j.keysRequired("availability coverage source errors data")
    val availability = j.string("availability")
    require(availability in setOf("ready", "missing", "locked", "unsupported_schema", "invalid_path", "rejected", "unavailable"))
    val s = j.usageObject("source")
    s.keysRequired("kind table app_type data_source schema_version")
    val native = s.string("kind") == UsageSourceKind.NativeCodex.wire
    val c = j.usageObject("coverage")
    c.keysRequired("scope complete matched_rows first_observed_at last_observed_at rollups_present rollups_included cost_known_rows cost_missing_rows cost_invalid_rows zero_cost_rows token_invalid_rows confirmed_free_rows zero_cost_interpretation")
    require(c.string("scope") == (if (native) "native_ledger" else "retained_detail_only") && !c.bool("complete") && !c.bool("rollups_included"))
    require(c.string("zero_cost_interpretation") == (if (native) "cost_not_provided" else "stored_zero_free_status_unknown") && c.field("confirmed_free_rows") == JsonNull)
    val coverage = UsageCoverage(c.string("scope"), c.bool("complete"), c.unsigned("matched_rows"), c.nullLong("first_observed_at"), c.nullLong("last_observed_at"),
        c.bool("rollups_present"), c.bool("rollups_included"), c.unsigned("cost_known_rows"), c.unsigned("cost_missing_rows"), c.unsigned("cost_invalid_rows"),
        c.unsigned("zero_cost_rows"), c.unsigned("token_invalid_rows"), c.nullUnsigned("confirmed_free_rows"), c.string("zero_cost_interpretation"))
    val info = parseSource(s)
    val errors = j.usageArray("errors").map { e -> e.asUsageObject().let { it.keysRequired("code message"); UsageError(it.string("code"), it.string("message")) } }
    val capabilities = parseCapabilities(j, native)
    // A source that says it records no amounts must also say so where its figures are read.
    require(capabilities.cost || availability != "ready" || errors.any { it.code == "cost_not_provided" }) { "Unpriced usage lacks its notice" }
    val data = if (j.field("data") == JsonNull) null else parseData(j.usageObject("data"), capabilities)
    require(availability != "ready" || data != null) { "Ready usage lacks data" }
    return UsageResponse(availability, coverage, info, errors, data, capabilities = capabilities)
}
private fun parseSource(s: JsonObject): UsageSource {
    s.keysRequired("kind table app_type data_source schema_version")
    if (s.string("kind") == UsageSourceKind.NativeCodex.wire) {
        // The native ledger has no import dimension; its null is not one of CC Switch's sources.
        require(s.string("table") == "native_usage_events" && s.string("app_type") == "codex" && s.field("data_source") == JsonNull)
        return UsageSource(s.string("kind"), s.string("table"), "codex", UsageDataSource.Proxy, s.nullLong("schema_version"))
    }
    require(s.string("kind") == "cc_switch" && s.string("table") == "proxy_request_logs" && s.string("app_type") in UsageFilter.apps)
    return UsageSource(s.string("kind"), s.string("table"), s.string("app_type"), source(s.string("data_source")), s.nullLong("schema_version")).also {
        require(it.dataSource != UsageDataSource.CodexSession || it.appType == "codex")
    }
}
private fun parseCapabilities(result: JsonObject, native: Boolean): UsageCapabilities {
    val c = result["capabilities"]?.takeUnless { it == JsonNull }?.asUsageObject()
    require(c != null || !native) { "Native usage must state what it records" }
    if (c == null) return UsageCapabilities()
    c.keysRequired("cost performance status")
    return UsageCapabilities(c.bool("cost"), c.bool("performance"), c.bool("status"))
}
private fun parseCache(j: JsonObject): UsageCache {
    j.keysRequired("state sampled_at age_seconds ttl_seconds stale_reason")
    val state = j.string("state").also { require(it in setOf("fresh", "stale", "unavailable")) }
    return UsageCache(state, j.nullLong("sampled_at"), j.nullUnsigned("age_seconds"), j.unsigned("ttl_seconds"), j.nullString("stale_reason"))
}
private fun parseCost(j: JsonObject): UsageCostAmount {
    j.keysRequired("known_usd known_rows missing_rows invalid_rows zero_rows")
    val text = j.string("known_usd").also { decimal(it) }
    return UsageCostAmount(text, j.unsigned("known_rows"), j.unsigned("missing_rows"), j.unsigned("invalid_rows"), j.unsigned("zero_rows")).also { require(it.zeroRows <= it.knownRows) }
}
private fun parseTiming(j: JsonObject): UsageTimingMetric {
    j.keysRequired("average_ms sum_ms sample_rows missing_rows invalid_rows excluded_rows")
    return UsageTimingMetric(j.nullDouble("average_ms"), j.unsigned("sum_ms"), j.unsigned("sample_rows"), j.unsigned("missing_rows"), j.unsigned("invalid_rows"), j.unsigned("excluded_rows")).also {
        require((it.sampleRows == 0uL) == (it.averageMs == null))
    }
}
private fun parseSpeed(j: JsonObject): UsageSpeedMetric {
    j.keysRequired("tokens_per_second sum_output_tokens sum_generation_ms sample_rows missing_rows invalid_rows excluded_rows")
    return UsageSpeedMetric(j.nullDouble("tokens_per_second"), j.unsigned("sum_output_tokens"), j.unsigned("sum_generation_ms"), j.unsigned("sample_rows"), j.unsigned("missing_rows"), j.unsigned("invalid_rows"), j.unsigned("excluded_rows")).also {
        require((it.sampleRows == 0uL) == (it.tokensPerSecond == null))
    }
}
/** What a source does not record arrives as null and stays "not recorded": zero known rows, never a zero amount. */
private fun parseTotals(j: JsonObject, can: UsageCapabilities): UsageTotals {
    j.keysRequired("requests success errors tokens costs performance")
    val t = j.usageObject("tokens"); t.keysRequired("fresh_input cache_read cache_write output total invalid_rows")
    val tokens = UsageTokens(t.unsigned("fresh_input"), t.unsigned("cache_read"), t.unsigned("cache_write"), t.unsigned("output"), t.unsigned("total"), t.unsigned("invalid_rows"))
    val requests = j.unsigned("requests")
    if (!can.status) require(j.field("success") == JsonNull && j.field("errors") == JsonNull) { "Unrecorded status has figures" }
    val costs = if (!can.cost) {
        require(j.field("costs") == JsonNull) { "Unrecorded cost has figures" }
        UsageCostAmount("0", 0u, requests, 0u, 0u).let { UsageCosts(it, it, it, it, it) }
    } else j.usageObject("costs").let { c ->
        c.keysRequired("input output cache_read cache_write total")
        UsageCosts(parseCost(c.usageObject("input")), parseCost(c.usageObject("output")), parseCost(c.usageObject("cache_read")), parseCost(c.usageObject("cache_write")), parseCost(c.usageObject("total")))
    }
    if (!can.performance) {
        require(j.field("performance") == JsonNull) { "Unrecorded performance has figures" }
        return UsageTotals(requests, if (can.status) j.unsigned("success") else 0u, if (can.status) j.unsigned("errors") else 0u, tokens, costs,
            UsagePerformance("not_recorded", 0u, UsageTimingMetric(), UsageTimingMetric(), UsageSpeedMetric()))
    }
    val p = j.usageObject("performance"); p.keysRequired("scope success_rows ttft duration output_speed")
    require(p.string("scope") == "successful_proxy_observations")
    val performance = UsagePerformance(p.string("scope"), p.unsigned("success_rows"), parseTiming(p.usageObject("ttft")), parseTiming(p.usageObject("duration")), parseSpeed(p.usageObject("output_speed")))
    val metrics = listOf(performance.ttft.let { listOf(it.sampleRows, it.missingRows, it.invalidRows, it.excludedRows) },
        performance.duration.let { listOf(it.sampleRows, it.missingRows, it.invalidRows, it.excludedRows) },
        performance.outputSpeed.let { listOf(it.sampleRows, it.missingRows, it.invalidRows, it.excludedRows) })
    require(metrics.all { rows -> rows.map { BigInteger(it.toString()) }.fold(BigInteger.ZERO, BigInteger::add) == BigInteger(performance.successRows.toString()) })
    return UsageTotals(requests, if (can.status) j.unsigned("success") else 0u, if (can.status) j.unsigned("errors") else 0u, tokens, costs, performance)
}
private fun <T> parsePage(j: JsonObject, item: (JsonObject) -> T): UsageGroupPage<T> {
    j.keysRequired("items offset limit total_groups has_more")
    val offset = j.int("offset"); val limit = j.int("limit"); UsagePage(offset, limit)
    val items = j.usageArray("items").map { item(it.asUsageObject()) }
    require(items.size <= limit && j.int("total_groups") <= 10000)
    return UsageGroupPage(items, offset, limit, j.int("total_groups"), j.bool("has_more"))
}
private fun parseData(j: JsonObject, can: UsageCapabilities): UsageData {
    j.keysRequired("totals trend provider_groups model_groups")
    fun parseTotals(totals: JsonObject) = parseTotals(totals, can)
    val trend = j.usageArray("trend").map { e -> e.asUsageObject().let {
        it.keysRequired("start_at end_at totals")
        UsageTrendBucket(it.long("start_at"), it.long("end_at"), parseTotals(it.usageObject("totals"))).also { b -> require(b.endAt > b.startAt) }
    } }
    require(trend.size <= 744 && trend.zipWithNext().all { (a, b) -> a.endAt == b.startAt })
    return UsageData(parseTotals(j.usageObject("totals")), trend,
        parsePage(j.usageObject("provider_groups")) { it.keysRequired("provider_id app_type name totals"); UsageProviderGroup(it.string("provider_id"), it.string("app_type"), it.nullString("name"), parseTotals(it.usageObject("totals"))) },
        parsePage(j.usageObject("model_groups")) { it.keysRequired("model totals"); UsageModelGroup(it.string("model"), parseTotals(it.usageObject("totals"))) },
        j["session_groups"]?.takeUnless { it == JsonNull }?.let { parsePage(it.asUsageObject()) { g ->
            g.keysRequired("session_id title totals"); UsageSessionGroup(g.string("session_id"), g.nullString("title"), parseTotals(g.usageObject("totals"))) } },
        j["recent_requests"]?.takeUnless { it == JsonNull }?.let { parseRequests(it.asUsageObject()) }?.also { page ->
            require(!can.status || page.items.all { it.statusCode != null }) { "Request lacks its status" }
        })
}
private fun validateQuery(j: JsonObject) {
    j.keysRequired("start_at end_at bucket_edges filters provider_page model_page")
    val start = j.long("start_at"); val end = j.long("end_at")
    val edges = j.usageArray("bucket_edges").map { buildJsonObject { put("edge", it) }.long("edge") }
    require(end > start && end <= 253402300799L && end - start <= 32 * 86400 && edges.size in 2..745 && edges.first() == start && edges.last() == end)
    require(edges.zipWithNext().all { (a, b) -> b > a && b - a <= 27 * 3600 })
    require(edges.zipWithNext().all { (a, b) -> b - a <= 3600 } || edges.size <= 32)
    val f = j.usageObject("filters"); f.keysRequired("app_type data_source provider_id model status")
    require(f.string("app_type") in UsageFilter.apps)
    require(source(f.string("data_source")) != UsageDataSource.CodexSession || f.string("app_type") == "codex")
    require(UsageStatusFilter.entries.any { it.wire == f.string("status") })
    listOf("provider_id", "model", "session_id").filter { it in f }.forEach { k -> f.nullString(k)?.let { require(it.toByteArray(Charsets.UTF_8).size in 1..256 && '\u0000' !in it) } }
    listOf("provider_page", "model_page", "session_page").filter { it in j }.forEach { k -> j.usageObject(k).let { it.keysRequired("offset limit"); UsagePage(it.int("offset"), it.int("limit")) } }
}

/** Compare declared query fields, accepting legacy omitted defaults and future additive metadata. */
fun usageQueryMatches(actual: JsonObject, expected: JsonObject): Boolean {
    fun subset(a: JsonObject, e: JsonObject): Boolean = e.all { (key, value) ->
        when {
            a[key] is JsonObject && value is JsonObject -> subset(a[key]!!.jsonObject, value)
            key !in a && (key == "session_id" && value == JsonNull || key == "session_page" && value == UsagePage().toJson() || key == "request_page" && value == UsageRequestPage().toJson()) -> true
            else -> a[key] == value
        }
    }
    return subset(actual, expected)
}
fun UsageFilter.legacyJson(): JsonObject {
    val query = toJson()
    return JsonObject(query - "session_page" - "request_page" + ("filters" to JsonObject(query.getValue("filters").jsonObject - "session_id")))
}
private fun parseRequestTokens(j: JsonObject): UsageTokens {
    j.keysRequired("fresh_input cache_read cache_write output total invalid_rows")
    return UsageTokens(j.unsigned("fresh_input"), j.unsigned("cache_read"), j.unsigned("cache_write"), j.unsigned("output"), j.unsigned("total"), j.unsigned("invalid_rows"))
}
private fun parseRequests(j: JsonObject): UsageRequestDetailsPage {
    j.keysRequired("items limit has_more next_cursor")
    val limit = j.int("limit"); require(limit in 1..200)
    val items = j.usageArray("items").map { e -> e.asUsageObject().let { r ->
        r.keysRequired("request_id created_at provider_id provider_name model session_id session_title status_code tokens cost_usd ttft_ms latency_ms tokens_per_second")
        UsageRequestDetail(r.string("request_id"), r.long("created_at"), r.string("provider_id"), r.nullString("provider_name"), r.string("model"),
            r.nullString("session_id"), r.nullString("session_title"), if (r.field("status_code") == JsonNull) null else r.int("status_code"),
            r["tokens"]?.takeUnless { it == JsonNull }?.let { parseRequestTokens(it.asUsageObject()) },
            r.nullString("cost_usd")?.also { decimal(it) }, r.nullUnsigned("ttft_ms"), r.nullUnsigned("latency_ms"), r.nullDouble("tokens_per_second"))
    } }
    require(items.size <= limit)
    val cursor = j["next_cursor"]?.takeUnless { it == JsonNull }?.asUsageObject()?.let {
        it.keysRequired("created_at request_id"); UsageRequestCursor(it.long("created_at"), it.string("request_id"))
    }
    val hasMore = j.bool("has_more")
    require(hasMore == (cursor != null) && (!hasMore || items.isNotEmpty()))
    if (cursor != null) require(items.last().let { it.createdAt == cursor.createdAt && it.requestId == cursor.requestId })
    return UsageRequestDetailsPage(items, limit, hasMore, cursor)
}
fun parseUsageRequests(json: JsonObject): UsageRequestsResponse {
    json.keysRequired("schema query result cache")
    val schema = json.long("schema"); require(schema == 1L || schema == 2L)
    val q = json.usageObject("query").also(::validateQuery)
    val result = json.usageObject("result"); result.keysRequired("availability source errors data")
    val info = parseSource(result.usageObject("source"))
    require(if (schema == 2L) json.string("source") == info.kind else info.kind == UsageSourceKind.CcSwitch.wire) { "Usage source mismatch" }
    val can = parseCapabilities(result, info.kind == UsageSourceKind.NativeCodex.wire)
    val data = result["data"]?.takeUnless { it == JsonNull }?.asUsageObject()?.let { parseRequests(it.usageObject("requests")) }
    val availability = result.string("availability")
    require(availability != "ready" || data != null)
    // Only a source without a status dimension may leave a request's status unstated.
    require(!can.status || data == null || data.items.all { it.statusCode != null }) { "Request lacks its status" }
    return UsageRequestsResponse(q, info, availability, data, parseCache(json.usageObject("cache")))
}
