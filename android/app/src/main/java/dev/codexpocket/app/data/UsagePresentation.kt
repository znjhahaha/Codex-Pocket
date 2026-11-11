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
) {
    init {
        require(ChronoUnit.DAYS.between(startDate, endDate) in 0..30) { "Choose 1 to 31 days" }
        ZoneId.of(zoneId)
        require(appType in apps) { "Unsupported usage app" }
        require(dataSource != UsageDataSource.CodexSession || appType == "codex") { "Session source requires Codex" }
        listOf(providerId, model).forEach { require(it == null || (it.toByteArray(Charsets.UTF_8).size in 1..256 && '\u0000' !in it)) { "Invalid usage identity" } }
    }
    fun toQuery(): UsageQuery {
        val zone = ZoneId.of(zoneId)
        val count = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
        val edges = (0..count).map { startDate.plusDays(it.toLong()).atStartOfDay(zone).toEpochSecond() }
        require(edges.first() >= 0 && edges.last() <= 253402300799L)
        require(edges.zipWithNext().all { (a, b) -> b > a && b - a <= 27 * 3600 })
        require(edges.last() - edges.first() <= 32 * 86400)
        return UsageQuery(edges.first(), edges.last(), edges, UsageQueryFilters(appType, dataSource, providerId, model, status), providerPage, modelPage)
    }
    fun toJson() = toQuery().toJson()
    private fun reset() = copy(providerPage = providerPage.copy(offset = 0), modelPage = modelPage.copy(offset = 0))
    fun withDates(start: LocalDate, end: LocalDate) = copy(startDate = start, endDate = end).reset()
    fun withZone(zone: String) = copy(zoneId = zone).reset()
    fun withAppType(app: String) = copy(appType = app,
        dataSource = if (app == "codex") dataSource else UsageDataSource.Proxy, providerId = null, model = null).reset()
    fun withDataSource(source: UsageDataSource) = copy(dataSource = source, providerId = null, model = null).reset()
    fun withStatus(value: UsageStatusFilter) = copy(status = value).reset()
    fun withProvider(value: String?) = copy(providerId = value).reset()
    fun withModel(value: String?) = copy(model = value).reset()
    companion object {
        val apps = listOf("codex", "claude", "gemini", "grokbuild")
        fun lastDays(days: Int = 7, zoneId: String = ZoneId.systemDefault().id,
            today: LocalDate = LocalDate.now(ZoneId.of(zoneId))): UsageFilter {
            require(days in 1..31)
            return UsageFilter(today.minusDays(days - 1L), today, zoneId)
        }
    }
}
data class UsageQueryFilters(val appType: String, val dataSource: UsageDataSource, val providerId: String?, val model: String?, val status: UsageStatusFilter) {
    fun toJson() = buildJsonObject {
        put("app_type", appType); put("data_source", dataSource.wire)
        put("provider_id", providerId?.let(::JsonPrimitive) ?: JsonNull)
        put("model", model?.let(::JsonPrimitive) ?: JsonNull); put("status", status.wire)
    }
}
data class UsageQuery(val startAt: Long, val endAt: Long, val bucketEdges: List<Long>, val filters: UsageQueryFilters,
    val providerPage: UsagePage, val modelPage: UsagePage) {
    fun toJson() = buildJsonObject {
        put("start_at", startAt); put("end_at", endAt)
        put("bucket_edges", JsonArray(bucketEdges.map(::JsonPrimitive)))
        put("filters", filters.toJson()); put("provider_page", providerPage.toJson()); put("model_page", modelPage.toJson())
    }
}

enum class UsagePhase {
    NotConnected, Idle, Loading, Ready, Offline, Missing, Locked, UnsupportedSchema, UnsupportedHelper, InvalidPath, Rejected, Failed,
}
data class UsageSnapshot(val filter: UsageFilter, val response: UsageResponse, val updatedAt: Long)
data class UsageScreenState(
    val filter: UsageFilter = UsageFilter.lastDays(),
    val snapshot: UsageSnapshot? = null,
    val phase: UsagePhase = UsagePhase.NotConnected,
    val refreshing: Boolean = false,
    val canQuery: Boolean = false,
    val cached: Boolean = false,
) {
    val visibleSnapshot: UsageSnapshot? get() = snapshot?.takeIf {
        it.filter == filter && it.response.source.appType == filter.appType && it.response.source.dataSource == filter.dataSource &&
            (it.response.query == null || it.response.query == filter.toJson())
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
data class UsageData(val totals: UsageTotals, val trend: List<UsageTrendBucket>, val providerGroups: UsageGroupPage<UsageProviderGroup>, val modelGroups: UsageGroupPage<UsageModelGroup>)

data class UsageResponse(val availability: String, val coverage: UsageCoverage, val source: UsageSource, val errors: List<UsageError>,
    val data: UsageData?, val cache: UsageCache? = null, val query: JsonObject? = null) {
    val phase: UsagePhase get() = when (availability) {
        "ready" -> UsagePhase.Ready; "missing" -> UsagePhase.Missing; "locked" -> UsagePhase.Locked
        "unsupported_schema" -> UsagePhase.UnsupportedSchema; "invalid_path" -> UsagePhase.InvalidPath
        "rejected" -> UsagePhase.Rejected; else -> UsagePhase.Failed
    }
    companion object {
        /** Accepts the statistics result or the finalized schema=1 RPC envelope. No lossy defaults. */
        fun fromJson(json: JsonObject): UsageResponse {
            if ("result" in json) {
                json.keysExactly("schema query result cache")
                require(json.long("schema") == 1L) { "Unsupported usage RPC schema" }
                val q = json.usageObject("query").also(::validateQuery)
                return parseResponse(json.usageObject("result")).copy(cache = parseCache(json.usageObject("cache")), query = q)
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

private fun JsonObject.keysExactly(keys: String) { require(this.keys == keys.split(' ').toSet()) { "Unexpected usage fields" } }
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
    j.keysExactly("availability coverage source errors data")
    val availability = j.string("availability")
    require(availability in setOf("ready", "missing", "locked", "unsupported_schema", "invalid_path", "rejected", "unavailable"))
    val c = j.usageObject("coverage")
    c.keysExactly("scope complete matched_rows first_observed_at last_observed_at rollups_present rollups_included cost_known_rows cost_missing_rows cost_invalid_rows zero_cost_rows token_invalid_rows confirmed_free_rows zero_cost_interpretation")
    require(c.string("scope") == "retained_detail_only" && !c.bool("complete") && !c.bool("rollups_included"))
    require(c.string("zero_cost_interpretation") == "stored_zero_free_status_unknown" && c.field("confirmed_free_rows") == JsonNull)
    val coverage = UsageCoverage(c.string("scope"), c.bool("complete"), c.unsigned("matched_rows"), c.nullLong("first_observed_at"), c.nullLong("last_observed_at"),
        c.bool("rollups_present"), c.bool("rollups_included"), c.unsigned("cost_known_rows"), c.unsigned("cost_missing_rows"), c.unsigned("cost_invalid_rows"),
        c.unsigned("zero_cost_rows"), c.unsigned("token_invalid_rows"), c.nullUnsigned("confirmed_free_rows"), c.string("zero_cost_interpretation"))
    val s = j.usageObject("source")
    s.keysExactly("kind table app_type data_source schema_version")
    require(s.string("kind") == "cc_switch" && s.string("table") == "proxy_request_logs" && s.string("app_type") in UsageFilter.apps)
    val info = UsageSource(s.string("kind"), s.string("table"), s.string("app_type"), source(s.string("data_source")), s.nullLong("schema_version"))
    require(info.dataSource != UsageDataSource.CodexSession || info.appType == "codex")
    val errors = j.usageArray("errors").map { e -> e.asUsageObject().let { it.keysExactly("code message"); UsageError(it.string("code"), it.string("message")) } }
    val data = if (j.field("data") == JsonNull) null else parseData(j.usageObject("data"))
    require(availability != "ready" || data != null) { "Ready usage lacks data" }
    return UsageResponse(availability, coverage, info, errors, data)
}
private fun parseCache(j: JsonObject): UsageCache {
    j.keysExactly("state sampled_at age_seconds ttl_seconds stale_reason")
    val state = j.string("state").also { require(it in setOf("fresh", "stale", "unavailable")) }
    return UsageCache(state, j.nullLong("sampled_at"), j.nullUnsigned("age_seconds"), j.unsigned("ttl_seconds"), j.nullString("stale_reason"))
}
private fun parseCost(j: JsonObject): UsageCostAmount {
    j.keysExactly("known_usd known_rows missing_rows invalid_rows zero_rows")
    val text = j.string("known_usd").also { decimal(it) }
    return UsageCostAmount(text, j.unsigned("known_rows"), j.unsigned("missing_rows"), j.unsigned("invalid_rows"), j.unsigned("zero_rows")).also { require(it.zeroRows <= it.knownRows) }
}
private fun parseTiming(j: JsonObject): UsageTimingMetric {
    j.keysExactly("average_ms sum_ms sample_rows missing_rows invalid_rows excluded_rows")
    return UsageTimingMetric(j.nullDouble("average_ms"), j.unsigned("sum_ms"), j.unsigned("sample_rows"), j.unsigned("missing_rows"), j.unsigned("invalid_rows"), j.unsigned("excluded_rows")).also {
        require((it.sampleRows == 0uL) == (it.averageMs == null))
    }
}
private fun parseSpeed(j: JsonObject): UsageSpeedMetric {
    j.keysExactly("tokens_per_second sum_output_tokens sum_generation_ms sample_rows missing_rows invalid_rows excluded_rows")
    return UsageSpeedMetric(j.nullDouble("tokens_per_second"), j.unsigned("sum_output_tokens"), j.unsigned("sum_generation_ms"), j.unsigned("sample_rows"), j.unsigned("missing_rows"), j.unsigned("invalid_rows"), j.unsigned("excluded_rows")).also {
        require((it.sampleRows == 0uL) == (it.tokensPerSecond == null))
    }
}
private fun parseTotals(j: JsonObject): UsageTotals {
    j.keysExactly("requests success errors tokens costs performance")
    val t = j.usageObject("tokens"); t.keysExactly("fresh_input cache_read cache_write output total invalid_rows")
    val tokens = UsageTokens(t.unsigned("fresh_input"), t.unsigned("cache_read"), t.unsigned("cache_write"), t.unsigned("output"), t.unsigned("total"), t.unsigned("invalid_rows"))
    val c = j.usageObject("costs"); c.keysExactly("input output cache_read cache_write total")
    val costs = UsageCosts(parseCost(c.usageObject("input")), parseCost(c.usageObject("output")), parseCost(c.usageObject("cache_read")), parseCost(c.usageObject("cache_write")), parseCost(c.usageObject("total")))
    val p = j.usageObject("performance"); p.keysExactly("scope success_rows ttft duration output_speed")
    require(p.string("scope") == "successful_proxy_observations")
    val performance = UsagePerformance(p.string("scope"), p.unsigned("success_rows"), parseTiming(p.usageObject("ttft")), parseTiming(p.usageObject("duration")), parseSpeed(p.usageObject("output_speed")))
    val metrics = listOf(performance.ttft.let { listOf(it.sampleRows, it.missingRows, it.invalidRows, it.excludedRows) },
        performance.duration.let { listOf(it.sampleRows, it.missingRows, it.invalidRows, it.excludedRows) },
        performance.outputSpeed.let { listOf(it.sampleRows, it.missingRows, it.invalidRows, it.excludedRows) })
    require(metrics.all { rows -> rows.map { BigInteger(it.toString()) }.fold(BigInteger.ZERO, BigInteger::add) == BigInteger(performance.successRows.toString()) })
    return UsageTotals(j.unsigned("requests"), j.unsigned("success"), j.unsigned("errors"), tokens, costs, performance)
}
private fun <T> parsePage(j: JsonObject, item: (JsonObject) -> T): UsageGroupPage<T> {
    j.keysExactly("items offset limit total_groups has_more")
    val offset = j.int("offset"); val limit = j.int("limit"); UsagePage(offset, limit)
    val items = j.usageArray("items").map { item(it.asUsageObject()) }
    require(items.size <= limit && j.int("total_groups") <= 10000)
    return UsageGroupPage(items, offset, limit, j.int("total_groups"), j.bool("has_more"))
}
private fun parseData(j: JsonObject): UsageData {
    j.keysExactly("totals trend provider_groups model_groups")
    val trend = j.usageArray("trend").map { e -> e.asUsageObject().let {
        it.keysExactly("start_at end_at totals")
        UsageTrendBucket(it.long("start_at"), it.long("end_at"), parseTotals(it.usageObject("totals"))).also { b -> require(b.endAt > b.startAt) }
    } }
    require(trend.size <= 744 && trend.zipWithNext().all { (a, b) -> a.endAt == b.startAt })
    return UsageData(parseTotals(j.usageObject("totals")), trend,
        parsePage(j.usageObject("provider_groups")) { it.keysExactly("provider_id app_type name totals"); UsageProviderGroup(it.string("provider_id"), it.string("app_type"), it.nullString("name"), parseTotals(it.usageObject("totals"))) },
        parsePage(j.usageObject("model_groups")) { it.keysExactly("model totals"); UsageModelGroup(it.string("model"), parseTotals(it.usageObject("totals"))) })
}
private fun validateQuery(j: JsonObject) {
    j.keysExactly("start_at end_at bucket_edges filters provider_page model_page")
    val start = j.long("start_at"); val end = j.long("end_at")
    val edges = j.usageArray("bucket_edges").map { buildJsonObject { put("edge", it) }.long("edge") }
    require(end > start && end <= 253402300799L && end - start <= 32 * 86400 && edges.size in 2..745 && edges.first() == start && edges.last() == end)
    require(edges.zipWithNext().all { (a, b) -> b > a && b - a <= 27 * 3600 })
    require(edges.zipWithNext().all { (a, b) -> b - a <= 3600 } || edges.size <= 32)
    val f = j.usageObject("filters"); f.keysExactly("app_type data_source provider_id model status")
    require(f.string("app_type") in UsageFilter.apps)
    require(source(f.string("data_source")) != UsageDataSource.CodexSession || f.string("app_type") == "codex")
    require(UsageStatusFilter.entries.any { it.wire == f.string("status") })
    listOf("provider_id", "model").forEach { k -> f.nullString(k)?.let { require(it.toByteArray(Charsets.UTF_8).size in 1..256 && '\u0000' !in it) } }
    listOf("provider_page", "model_page").forEach { k -> j.usageObject(k).let { it.keysExactly("offset limit"); UsagePage(it.int("offset"), it.int("limit")) } }
}
