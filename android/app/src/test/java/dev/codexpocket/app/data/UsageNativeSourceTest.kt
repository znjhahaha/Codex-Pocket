package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** The `usage/v2/read` reply for the native ledger, field for field as the desktop writes it. */
internal fun usageNativeFixtureJson(filter: UsageFilter, requests: Int = 3): JsonObject {
    val totals = """{"requests":$requests,"success":null,"errors":null,
      "tokens":{"fresh_input":1200,"cache_read":8000,"cache_write":0,"output":640,"total":9840,"invalid_rows":0,"reasoning_output":210},
      "costs":null,"performance":null}"""
    val edges = filter.toQuery().bucketEdges
    val trend = edges.zipWithNext().joinToString(",") { (a, b) -> """{"start_at":$a,"end_at":$b,"totals":$totals}""" }
    val request = """{"request_id":"41","created_at":${edges.first() + 60},"provider_id":"openai","provider_name":null,"model":"gpt-5.2-codex",
      "session_id":"01a1e7c0-9589-72b0-b1d0-0d58957e18f9","session_title":null,"turn_id":"turn-7","observed_by":"live","status_code":null,
      "tokens":{"fresh_input":400,"cache_read":2600,"cache_write":0,"output":210,"total":3210,"invalid_rows":0,"reasoning_output":70},
      "cost_usd":null,"ttft_ms":null,"latency_ms":null,"tokens_per_second":null}"""
    return Json.parseToJsonElement("""{"schema":2,"source":"native_codex","query":${filter.toJson()},
      "result":{"availability":"ready",
        "coverage":{"scope":"native_ledger","complete":false,"matched_rows":$requests,"first_observed_at":${edges.first() + 60},"last_observed_at":${edges.first() + 90},
          "rollups_present":false,"rollups_included":false,"cost_known_rows":0,"cost_missing_rows":$requests,"cost_invalid_rows":0,"zero_cost_rows":0,
          "token_invalid_rows":0,"confirmed_free_rows":null,"zero_cost_interpretation":"cost_not_provided","sessions":1,"unanchored_rows":0,"inherited_rows":2,
          "history_import":{"state":"complete","complete":true}},
        "source":{"kind":"native_codex","table":"native_usage_events","app_type":"codex","data_source":null,"schema_version":1},
        "capabilities":{"cost":false,"performance":false,"status":false,"providers":true,"models":true,"sessions":true,"requests":true},
        "errors":[{"code":"cost_not_provided","message":"Native Codex usage carries no cost evidence; cost amounts are not provided."},
          {"code":"incomplete_coverage","message":"Only usage the agent observed live or found in retained native history is counted."}],
        "data":{"totals":$totals,"trend":[$trend],
          "provider_groups":{"items":[{"provider_id":"openai","app_type":"codex","name":null,"totals":$totals}],"offset":0,"limit":50,"total_groups":1,"has_more":false},
          "model_groups":{"items":[{"model":"gpt-5.2-codex","totals":$totals}],"offset":0,"limit":50,"total_groups":1,"has_more":false},
          "session_groups":{"items":[{"session_id":"01a1e7c0-9589-72b0-b1d0-0d58957e18f9","title":null,"totals":$totals}],"offset":0,"limit":50,"total_groups":1,"has_more":false},
          "recent_requests":{"items":[$request],"limit":50,"has_more":false,"next_cursor":null}}},
      "cache":{"state":"fresh","sampled_at":${edges.first() + 120},"age_seconds":0,"ttl_seconds":60,"stale_reason":null}}""").jsonObject
}

class UsageNativeSourceTest {
    private val native = UsageFilter.lastDays(7, "UTC", LocalDate.of(2026, 11, 22)).withSource(UsageSourceKind.NativeCodex)
    private fun JsonObject.edit(path: List<String>, value: JsonElement): JsonObject =
        if (path.size == 1) JsonObject(this + (path[0] to value))
        else JsonObject(this + (path[0] to getValue(path[0]).jsonObject.edit(path.drop(1), value)))

    @Test fun nativeFiguresKeepTheirTokensAndLeaveCostStatusAndTimingUnrecordedRatherThanZero() {
        val response = UsageResponse.fromJson(usageNativeFixtureJson(native))
        assertEquals("native_codex", response.source.kind)
        assertEquals(UsageCapabilities(cost = false, performance = false, status = false), response.capabilities)
        val totals = response.data!!.totals
        assertEquals(3uL, totals.requests)
        assertEquals(9840uL, totals.tokens.total)
        assertEquals(8000uL, totals.tokens.cacheRead)
        // No amount is known for any row: the display is "unknown", never USD 0.
        assertEquals(0uL, totals.costs.total.knownRows)
        assertEquals(3uL, totals.costs.total.missingRows)
        assertEquals("未知", totals.costs.total.display())
        assertNull(totals.performance.ttft.averageMs)
        assertEquals(0uL, totals.performance.outputSpeed.sampleRows)
        val request = response.data!!.recentRequests!!.items.single()
        assertNull(request.statusCode)
        assertNull(request.costUsd)
        assertEquals(3210uL, request.tokens!!.total)
        assertTrue(response.errors.any { it.code == "cost_not_provided" })
        assertEquals(UsagePhase.Ready, response.phase)
    }

    @Test fun aNativeReplyThatInventsOrMislabelsWhatItDoesNotRecordIsRefused() {
        val good = usageNativeFixtureJson(native)
        val zeroCost = Json.parseToJsonElement("""{"known_usd":"0","known_rows":0,"missing_rows":3,"invalid_rows":0,"zero_rows":0}""")
        val costs = JsonObject(listOf("input", "output", "cache_read", "cache_write", "total").associateWith { zeroCost })
        listOf(
            good.edit(listOf("result", "data", "totals", "costs"), costs),
            good.edit(listOf("result", "data", "totals", "success"), JsonPrimitive(3)),
            good.edit(listOf("result", "capabilities"), JsonNull),
            good.edit(listOf("result", "errors"), JsonArray(emptyList())),
            good.edit(listOf("result", "source", "data_source"), JsonPrimitive("codex_session")),
            good.edit(listOf("result", "source", "table"), JsonPrimitive("proxy_request_logs")),
            good.edit(listOf("result", "coverage", "scope"), JsonPrimitive("retained_detail_only")),
            good.edit(listOf("source"), JsonPrimitive("cc_switch")),
            JsonObject(good + ("schema" to JsonPrimitive(3))),
        ).forEachIndexed { index, json -> assertThrows("case $index", IllegalArgumentException::class.java) { UsageResponse.fromJson(json) } }
        // A CC Switch reply may never arrive under the native name, nor leave a request's status out.
        val ccSwitch = buildJsonObject { put("schema", 2); put("source", "native_codex"); put("query", native.toJson()); put("result", usageFixtureJson())
            put("cache", good.getValue("cache")) }
        assertThrows(IllegalArgumentException::class.java) { UsageResponse.fromJson(ccSwitch) }
    }

    @Test fun ccSwitchRepliesReadTheSameUnderEitherEnvelopeAndStillRequireEveryFigure() {
        val filter = UsageFilter.lastDays(7, "UTC", LocalDate.of(2026, 11, 22))
        val cache = usageNativeFixtureJson(native).getValue("cache")
        val v1 = buildJsonObject { put("schema", 1); put("query", filter.toJson()); put("result", usageFixtureJson()); put("cache", cache) }
        val v2 = JsonObject(v1 + ("schema" to JsonPrimitive(2)) + ("source" to JsonPrimitive("cc_switch")))
        assertEquals(UsageResponse.fromJson(v1), UsageResponse.fromJson(v2))
        assertEquals(UsageCapabilities(), UsageResponse.fromJson(v2).capabilities)
        val missing = v2.edit(listOf("result", "data", "totals", "costs"), JsonNull)
        assertThrows(IllegalArgumentException::class.java) { UsageResponse.fromJson(missing) }
    }

    @Test fun choosingASourceDropsWhatOnlyTheOtherUnderstandsAndASnapshotOfOneNeverShowsUnderTheOther() {
        val day = LocalDate.of(2026, 11, 22)
        val narrowed = UsageFilter.lastDays(7, "UTC", day).withAppType("claude").withStatus(UsageStatusFilter.Error).withProvider("p1").copy(providerPage = UsagePage(50))
        val switched = narrowed.withSource(UsageSourceKind.NativeCodex)
        assertEquals("codex", switched.appType)
        assertEquals(UsageStatusFilter.All, switched.status)
        assertEquals(UsageDataSource.Proxy, switched.dataSource)
        assertNull(switched.providerId)
        assertEquals(0, switched.providerPage.offset)
        assertEquals(narrowed.startDate to narrowed.endDate, switched.startDate to switched.endDate)
        assertSame(switched, switched.withSource(UsageSourceKind.NativeCodex))
        assertThrows(IllegalArgumentException::class.java) { switched.copy(status = UsageStatusFilter.Error) }
        assertThrows(IllegalArgumentException::class.java) { switched.copy(dataSource = UsageDataSource.CodexSession) }
        assertThrows(IllegalArgumentException::class.java) { switched.copy(appType = "claude") }
        // Same dates and query, different ledger: the figures of one are not shown as the other's.
        val response = UsageResponse.fromJson(usageNativeFixtureJson(native))
        val shown = UsageScreenState(native, UsageSnapshot(native, response, 1), UsagePhase.Ready)
        assertNotNull(shown.visibleSnapshot)
        val other = native.withSource(UsageSourceKind.CcSwitch)
        assertNull(UsageScreenState(other, UsageSnapshot(other, response, 1), UsagePhase.Ready).visibleSnapshot)
    }

    @Test fun nativeRequestPagesParseAndCcSwitchPagesMustStillStateEachStatus() {
        val reply = usageNativeFixtureJson(native)
        val page = buildJsonObject {
            put("schema", 2); put("source", "native_codex"); put("query", native.toJson()); put("cache", reply.getValue("cache"))
            put("result", buildJsonObject {
                val result = reply.getValue("result").jsonObject
                put("availability", "ready"); put("source", result.getValue("source")); put("capabilities", result.getValue("capabilities"))
                put("errors", result.getValue("errors"))
                put("data", buildJsonObject { put("requests", result.getValue("data").jsonObject.getValue("recent_requests")) })
            })
        }
        val parsed = parseUsageRequests(page)
        assertEquals("native_codex", parsed.source.kind)
        assertNull(parsed.page!!.items.single().statusCode)
        val asCcSwitch = page.edit(listOf("result", "source"), usageFixtureJson().getValue("source")).edit(listOf("source"), JsonPrimitive("cc_switch"))
            .edit(listOf("result", "capabilities"), JsonNull)
        assertThrows(IllegalArgumentException::class.java) { parseUsageRequests(asCcSwitch) }
    }
}
