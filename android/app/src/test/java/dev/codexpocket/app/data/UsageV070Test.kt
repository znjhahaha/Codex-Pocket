package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UsageV070Test {
    @Test fun defaultUsesShanghaiCodexProxyForSevenDays() {
        val f = UsageFilter.lastDays()
        assertEquals("Asia/Shanghai", f.zoneId)
        assertEquals("codex", f.appType)
        assertEquals(UsageDataSource.Proxy, f.dataSource)
        assertEquals(8, f.toQuery().bucketEdges.size)
    }
    @Test fun additiveResponseFieldsAreToleratedWithoutDefaultingRequiredValues() {
        val fixture = usageFixtureJson()
        val totals = fixture["data"]!!.jsonObject["totals"]!!.jsonObject
        val expanded = JsonObject(fixture + mapOf("future_capability" to JsonPrimitive(true),
            "source" to JsonObject(fixture["source"]!!.jsonObject + ("schema_version" to JsonPrimitive(26))),
            "data" to JsonObject(fixture["data"]!!.jsonObject + mapOf("future_chart" to JsonPrimitive("ok"),
                "totals" to JsonObject(totals + ("future_measure" to JsonPrimitive(1)))))))
        assertEquals(26L, UsageResponse.fromJson(expanded).source.schemaVersion)
        assertEquals(5uL, UsageResponse.fromJson(expanded).data!!.totals.requests)
        assertThrows(IllegalArgumentException::class.java) { UsageResponse.fromJson(JsonObject(expanded - "coverage")) }
    }
    @Test fun sessionPagesAndFiltersResetTogetherAndNeverMatchLegacyWhenSelected() {
        val f = UsageFilter.lastDays().copy(sessionPage = UsagePage(50), providerPage = UsagePage(50))
        assertEquals(0, f.withSession("session-id").sessionPage.offset)
        assertEquals(0, f.withDates(f.startDate, f.endDate).sessionPage.offset)
        assertFalse(usageQueryMatches(f.legacyJson(), f.withSession("session-id").toJson()))
        assertTrue(usageQueryMatches(UsageFilter.lastDays().legacyJson(), UsageFilter.lastDays().toJson()))
    }
    @Test fun detailsPreserveNullsAndUseOnlyReliableSessionTitles() {
        val response = UsageResponse.fromJson(usageV070Fixture())
        val s = response.data!!.sessionGroups!!.items.single()
        assertEquals("会话 abcdefgh…mnop", s.label)
        val row = response.data.recentRequests!!.items.single()
        assertNull(row.costUsd); assertNull(row.ttftMs); assertNull(row.latencyMs); assertNull(row.tokensPerSecond)
        assertEquals(s.label, row.sessionLabel)
    }
    @Test fun requestMergingDeduplicatesAndBoundsVisibleRows() {
        val page = UsageResponse.fromJson(usageV070Fixture()).data!!.recentRequests!!
        assertEquals(1, appendUsageRequests(page, page).items.size)
        val many = (0..999).map { page.items.single().copy(requestId = "id-$it") }
        val result = appendUsageRequests(page.copy(items = many), page)
        assertEquals(1000, result.items.size)
        assertFalse(result.hasMore); assertNull(result.nextCursor)
        assertThrows(IllegalArgumentException::class.java) { UsageRequestPage(201) }
    }
}

internal fun usageV070Fixture(): JsonObject {
    val fixture = usageFixtureJson()
    val data = fixture["data"]!!.jsonObject
    val totals = data["totals"]!!
    val session = buildJsonObject { put("session_id", "abcdefghijklmnop"); put("title", JsonNull); put("totals", totals) }
    val request = buildJsonObject {
        put("request_id", "request-1"); put("created_at", 1794268800); put("provider_id", "p1"); put("provider_name", "Fixture Provider")
        put("model", "m1"); put("session_id", "abcdefghijklmnop"); put("session_title", JsonNull); put("status_code", 200)
        put("tokens", totals.jsonObject["tokens"]!!); put("cost_usd", JsonNull); put("ttft_ms", JsonNull); put("latency_ms", JsonNull); put("tokens_per_second", JsonNull)
    }
    return JsonObject(fixture + ("data" to JsonObject(data + mapOf(
        "session_groups" to buildJsonObject { put("items", JsonArray(listOf(session))); put("offset", 0); put("limit", 50); put("total_groups", 1); put("has_more", false) },
        "recent_requests" to buildJsonObject { put("items", JsonArray(listOf(request))); put("limit", 50); put("has_more", true)
            put("next_cursor", buildJsonObject { put("created_at", 1794268800); put("request_id", "request-1") }) }))))
}
