package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

internal fun usageFixtureJson(): JsonObject {
    val cost = """{"known_usd":"0.1000000000000000000000000001","known_rows":2,"missing_rows":1,"invalid_rows":1,"zero_rows":1}"""
    val timing = """{"average_ms":150.0,"sum_ms":300,"sample_rows":2,"missing_rows":1,"invalid_rows":0,"excluded_rows":1}"""
    val totals = """{"requests":5,"success":4,"errors":1,
      "tokens":{"fresh_input":100,"cache_read":20,"cache_write":10,"output":200,"total":330,"invalid_rows":1},
      "costs":{"input":$cost,"output":$cost,"cache_read":$cost,"cache_write":$cost,"total":$cost},
      "performance":{"scope":"successful_proxy_observations","success_rows":4,"ttft":$timing,"duration":$timing,
        "output_speed":{"tokens_per_second":100.0,"sum_output_tokens":200,"sum_generation_ms":2000,"sample_rows":2,"missing_rows":1,"invalid_rows":0,"excluded_rows":1}}}"""
    return Json.parseToJsonElement("""{"availability":"ready",
      "coverage":{"scope":"retained_detail_only","complete":false,"matched_rows":5,"first_observed_at":1794268800,"last_observed_at":1794268810,
        "rollups_present":true,"rollups_included":false,"cost_known_rows":2,"cost_missing_rows":1,"cost_invalid_rows":1,"zero_cost_rows":1,"token_invalid_rows":1,
        "confirmed_free_rows":null,"zero_cost_interpretation":"stored_zero_free_status_unknown"},
      "source":{"kind":"cc_switch","table":"proxy_request_logs","app_type":"codex","data_source":"proxy","schema_version":24},"errors":[],
      "data":{"totals":$totals,"trend":[{"start_at":1794268800,"end_at":1794355200,"totals":$totals}],
        "provider_groups":{"items":[{"provider_id":"p1","app_type":"codex","name":null,"totals":$totals}],"offset":0,"limit":50,"total_groups":51,"has_more":true},
        "model_groups":{"items":[{"model":"m1","totals":$totals}],"offset":0,"limit":50,"total_groups":1,"has_more":false}}}""").jsonObject
}

class UsagePresentationTest {
    private val day = LocalDate.of(2026, 11, 10)
    private fun filter() = UsageFilter.lastDays(7, "Asia/Shanghai", day)

    @Test fun localDatesProduceExclusiveUtcEndAndDailyEdges() {
        val q = filter().toQuery()
        assertEquals(8, q.bucketEdges.size)
        assertEquals(day.minusDays(6).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond(), q.startAt)
        assertEquals(day.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond(), q.endAt)
        assertEquals("proxy", filter().toJson()["filters"]!!.jsonObject["data_source"]!!.jsonPrimitive.content)
        assertFalse(filter().toJson().containsKey("zone_id"))
    }

    @Test fun dstFallBackHas25HourNaturalDay() {
        val d = LocalDate.of(2026, 11, 1)
        val q = UsageFilter(d, d, "America/New_York").toQuery()
        assertEquals(25 * 3600L, q.endAt - q.startAt)
    }

    @Test fun dstSpringForwardHas23HourNaturalDay() {
        val d = LocalDate.of(2026, 3, 8)
        val q = UsageFilter(d, d, "America/New_York").toQuery()
        assertEquals(23 * 3600L, q.endAt - q.startAt)
    }

    @Test fun filterChangesResetBothPagesAndKeepSourcesExclusive() {
        val f = filter().copy(providerPage = UsagePage(100, 50), modelPage = UsagePage(50, 25))
        val session = f.withDataSource(UsageDataSource.CodexSession)
        assertEquals(0, session.providerPage.offset)
        assertEquals(0, session.modelPage.offset)
        assertEquals(25, session.modelPage.limit)
        assertEquals(UsageDataSource.Proxy, session.withAppType("claude").dataSource)
        assertEquals(0, f.withProvider("another").modelPage.offset)
        assertEquals(0, f.withModel("another").providerPage.offset)
        assertEquals(0, f.withStatus(UsageStatusFilter.Error).providerPage.offset)
    }

    @Test fun invalidRangesSourcesAndIdentitiesAreRejectedBeforeDispatch() {
        listOf<() -> Any>(
            { UsageFilter(day, day.minusDays(1), "UTC") },
            { UsageFilter(day.minusDays(31), day, "UTC") },
            { filter().copy(appType = "opencode") },
            { filter().copy(appType = "claude", dataSource = UsageDataSource.CodexSession) },
            { filter().copy(providerId = "\u0000") },
            { UsagePage(10001, 50) }, { UsagePage(0, 201) },
        ).forEach { action -> assertThrows(IllegalArgumentException::class.java) { action() } }
    }

    @Test fun strictDtoPreservesDecimalU64AndObservedPerformance() {
        val json = usageFixtureJson()
        val data = json["data"]!!.jsonObject
        val totals = data["totals"]!!.jsonObject
        val changed = JsonObject(json + ("data" to JsonObject(data + ("totals" to JsonObject(totals + ("requests" to JsonPrimitive(ULong.MAX_VALUE.toString().toBigInteger())))))))
        val result = UsageResponse.fromJson(changed)
        assertEquals(ULong.MAX_VALUE, result.data!!.totals.requests)
        assertEquals("USD 0.1000000000000000000000000001", result.data.totals.costs.total.display())
        assertEquals(150.0, result.data.totals.performance.ttft.averageMs!!, 0.0)
        assertEquals(100.0, result.data.totals.performance.outputSpeed.tokensPerSecond!!, 0.0)
        assertNull(result.coverage.confirmedFreeRows)
    }

    @Test fun malformedCountsDecimalsUnknownKeysAndMissingFieldsNeverBecomeZero() {
        val original = usageFixtureJson()
        assertThrows(IllegalArgumentException::class.java) { UsageResponse.fromJson(JsonObject(original + ("path" to JsonPrimitive("unexpected")))) }
        assertThrows(IllegalArgumentException::class.java) { UsageResponse.fromJson(JsonObject(original - "coverage")) }
        val data = original["data"]!!.jsonObject
        val totals = data["totals"]!!.jsonObject
        listOf(JsonPrimitive(-1), JsonPrimitive("2"), JsonPrimitive("18446744073709551616".toBigInteger())).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) {
                UsageResponse.fromJson(JsonObject(original + ("data" to JsonObject(data + ("totals" to JsonObject(totals + ("requests" to bad)))))))
            }
        }
        val cost = totals["costs"]!!.jsonObject["total"]!!.jsonObject
        val costs = totals["costs"]!!.jsonObject
        val invalidCosts = JsonObject(costs + ("total" to JsonObject(cost + ("known_usd" to JsonPrimitive("NaN")))))
        val invalidData = JsonObject(data + ("totals" to JsonObject(totals + ("costs" to invalidCosts))))
        assertThrows(IllegalArgumentException::class.java) {
            UsageResponse.fromJson(JsonObject(original + ("data" to invalidData)))
        }
    }

    @Test fun envelopePreservesStaleMetadataAndCurrentFailureAvailability() {
        val result = JsonObject(usageFixtureJson() + ("availability" to JsonPrimitive("locked")))
        val envelope = buildJsonObject {
            put("schema", 1); put("query", filter().toJson()); put("result", result)
            put("cache", buildJsonObject { put("state", "stale"); put("sampled_at", 1794268800); put("age_seconds", 70); put("ttl_seconds", 60); put("stale_reason", "locked") })
        }
        val response = UsageResponse.fromJson(envelope)
        assertEquals(UsagePhase.Locked, response.phase)
        assertEquals("stale", response.cache!!.state)
        assertEquals(1794268800L, response.cache.sampledAt)
        assertNotNull(response.data)
    }

    @Test fun onlyExactlyMatchingSnapshotRemainsVisibleAcrossErrors() {
        val f = filter()
        val snap = UsageSnapshot(f, UsageResponse.fromJson(usageFixtureJson()), 1794268800)
        val s = UsageScreenState(f, snap, UsagePhase.Failed, cached = true)
        assertSame(snap, s.visibleSnapshot)
        assertNull(s.copy(filter = f.withModel("m2")).visibleSnapshot)
        assertNull(s.copy(filter = f.withDataSource(UsageDataSource.CodexSession)).visibleSnapshot)
        assertNull(s.copy(filter = f.copy(providerPage = UsagePage(50))).visibleSnapshot)
        assertNull(s.copy(filter = f.withZone("UTC")).visibleSnapshot)
        val wrongSource = snap.response.copy(source = snap.response.source.copy(appType = "claude"))
        assertNull(s.copy(snapshot = snap.copy(response = wrongSource)).visibleSnapshot)
        val wrongQuery = snap.response.copy(query = f.withModel("m2").toJson())
        assertNull(s.copy(snapshot = snap.copy(response = wrongQuery)).visibleSnapshot)
    }

    @Test fun unavailableAndUnsupportedResponsesRemainSpecificWithoutInventingData() {
        listOf("missing" to UsagePhase.Missing, "locked" to UsagePhase.Locked,
            "unsupported_schema" to UsagePhase.UnsupportedSchema, "invalid_path" to UsagePhase.InvalidPath,
            "rejected" to UsagePhase.Rejected, "unavailable" to UsagePhase.Failed).forEach { (availability, phase) ->
            val json = JsonObject(usageFixtureJson() + mapOf("availability" to JsonPrimitive(availability), "data" to JsonNull))
            val r = UsageResponse.fromJson(json)
            assertEquals(phase, r.phase)
            assertNull(r.data)
        }
    }

    @Test fun zeroMissingInvalidAndAbsentSamplesHaveDistinctLabels() {
        assertEquals("未知", UsageCostAmount("0", 0u, 3u, 1u, 0u).display())
        assertEquals("USD 0", UsageCostAmount("0", 3u, 0u, 0u, 3u).display())
        assertEquals("未知", UsageTimingMetric().display())
        assertEquals("0 ms", UsageTimingMetric(averageMs = 0.0, sampleRows = 1u).display())
        assertEquals("未知", UsageSpeedMetric().display())
        assertTrue(usageFixtureJson().let(UsageResponse::fromJson).data!!.totals.costs.total.coverageLabel().contains("缺失 1"))
    }
}
