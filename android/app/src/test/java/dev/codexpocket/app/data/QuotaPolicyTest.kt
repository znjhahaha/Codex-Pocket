package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuotaPolicyTest {
    private fun json(value: String) = pocketJson.parseToJsonElement(value).jsonObject
    private val source = json("""{"id":"a","associated":true,"autoRefreshSeconds":120,"manualCooldownSeconds":15}""")
    private val good = json("""{"runtimeId":"runtime","sourceId":"a","status":"ok","items":[{"remaining":12.5}],"updatedAt":1794000000000,"stale":false}""")
    private fun cached() = QuotaState(runtimeId = "runtime", computerId = "computer", sources = listOf(source), selectedSourceId = "a", result = good, status = "ok")

    @Test fun rediscoveryPreservesExplicitSourceAndItsLastGoodResult() {
        val other = json("""{"id":"b","associated":true}""")
        val updated = cached().withSources("computer", "runtime", listOf(source, other), "b", "ok")
        assertEquals("a", updated.selectedSourceId)
        assertEquals(good, updated.result)
        assertEquals("ok", updated.status)
    }

    @Test fun cachedResultCannotCrossComputerRuntimeOrSourceIdentity() {
        assertNull(cached().forScope("other", "runtime").result)
        assertNull(cached().forScope("computer", "other").result)
        val replaced = cached().withSources("computer", "runtime", listOf(source + ("id" to JsonPrimitive("new"))).map(::JsonObject), "new", "ok")
        assertNull(replaced.result)
        assertFalse(replaced.resultsBySource.containsKey("a"))
    }

    @Test fun unavailableCatalogKeepsPreviouslyDiscoveredSourceAndLastGoodItems() {
        val state = cached().withSources("computer", "runtime", emptyList(), null, "unavailable")
        assertEquals(listOf(source), state.sources)
        assertEquals("a", state.selectedSourceId)
        assertEquals(good["items"], state.result?.get("items"))
        assertEquals(good["updatedAt"], state.result?.get("updatedAt"))
        assertTrue(state.result!!.flag("stale"))
        assertEquals("unavailable", state.status)
    }

    @Test fun unmatchedProviderIsNeverChosenFromListPositionOrUnverifiedHint() {
        val unmatched = json("""{"id":"a","associated":false}""")
        val state = QuotaState().withSources("computer", "runtime", listOf(unmatched), "a", "unmatched")
        assertNull(state.selectedSourceId)
        assertEquals("unmatched", state.status)
    }

    @Test fun failureRetainsLastGoodMillisecondsAndMarksThemStale() {
        val failed = cached().withResult(json("""{"runtimeId":"runtime","sourceId":"a","status":"timeout","items":[],"updatedAt":null,"stale":false}"""))
        assertEquals(good["items"], failed.result?.get("items"))
        assertEquals(good["updatedAt"], failed.result?.get("updatedAt"))
        assertTrue(failed.result!!.flag("stale"))
        assertEquals("timeout", failed.status)
    }

    @Test fun staleOlderSuccessCannotReplaceNewerCachedAmounts() {
        val updated = cached().withResult(json("""{"runtimeId":"runtime","sourceId":"a","status":"ok","items":[],"updatedAt":1793999999000,"stale":true}"""))
        assertEquals(good["items"], updated.result?.get("items"))
        assertEquals(good["updatedAt"], updated.result?.get("updatedAt"))
    }

    @Test fun changingSourcesRestoresOnlyThatSourcesCache() {
        val b = json("""{"id":"b","autoRefreshSeconds":60}""")
        val state = cached().copy(sources = listOf(source, b)).selectSource("b")
        assertNull(state.result)
        assertEquals(good, state.selectSource("a").result)
        assertSame(state, state.withResult(good))
    }

    @Test fun olderNotificationsAreIgnoredWithoutChangingFreshnessOrStatus() {
        val state = cached()
        val older = JsonObject(good + mapOf("updatedAt" to JsonPrimitive(1793999999000), "items" to JsonArray(emptyList())))
        assertSame(state, state.withNotification(older))
        assertSame(state, state.withNotification(JsonObject(good + ("runtimeId" to JsonPrimitive("other")))))
        assertSame(state, state.withNotification(JsonObject(good + ("sourceId" to JsonPrimitive("other")))))
        assertSame(state, state.withNotification(JsonObject(good + ("items" to JsonPrimitive("invalid")))))
    }

    @Test fun notificationWinsOverPendingReadAndFailureKeepsLastGood() {
        val fresh = JsonObject(good + mapOf("updatedAt" to JsonPrimitive(1794000001000), "items" to JsonArray(listOf(json("""{"remaining":10}""")))))
        val updated = cached().copy(loading = true).withNotification(fresh)
        assertEquals(fresh, updated.result)
        assertFalse(updated.loading)
        val failure = updated.withFailure("http_error")
        assertTrue(failure.result!!.flag("stale"))
        assertEquals("http_error", failure.result.string("status"))
        assertEquals(fresh["updatedAt"], failure.result["updatedAt"])
        assertEquals("http_error", failure.withSources("computer", "runtime", listOf(source), "a", "ok").status)
    }

    @Test fun requestTicketsRejectReplacementClientsComputersRuntimesAndGenerations() {
        val client = Any()
        val scope = QuotaRequestScope(client, "computer", "runtime", 2, 3)
        val ticket = QuotaRequestTicket(scope, 4, 5)
        assertTrue(ticket.isCurrent(scope, 4, 5))
        listOf(scope.copy(client = Any()), scope.copy(computerId = "other"), scope.copy(runtimeId = "other"),
            scope.copy(connectionGeneration = 3), scope.copy(selectionGeneration = 5)).forEach {
            assertFalse(ticket.isCurrent(it, 4, 5))
        }
        assertFalse(ticket.isCurrent(scope, 5, 5))
        assertFalse(ticket.isCurrent(scope, 4, 6))
    }

    @Test fun automaticReadsRespectConfiguredFloorDisableAndServerRetry() {
        assertEquals(0L, quotaRefreshDelayMillis(source, QuotaReadTiming(), false, 1000))
        assertEquals(119000L, quotaRefreshDelayMillis(source, QuotaReadTiming(lastAttemptAt = 1000), false, 2000))
        val short = JsonObject(source + ("autoRefreshSeconds" to JsonPrimitive(2)))
        assertEquals(59000L, quotaRefreshDelayMillis(short, QuotaReadTiming(lastAttemptAt = 1000), false, 2000))
        val disabled = JsonObject(source + ("autoRefreshSeconds" to JsonPrimitive(0)))
        assertNull(quotaRefreshDelayMillis(disabled, QuotaReadTiming(), false, 1000))
        assertEquals(0L, quotaRefreshDelayMillis(disabled, QuotaReadTiming(), true, 1000))
        assertEquals(298000L, quotaRefreshDelayMillis(source, QuotaReadTiming(1000, automaticRetryAt = 300000), false, 2000))
    }

    @Test fun manualCooldownUsesAnyPreviousAttemptAndSurvivesClockRollback() {
        assertEquals(14000L, quotaRefreshDelayMillis(source, QuotaReadTiming(lastAttemptAt = 1000), true, 2000))
        assertEquals(15000L, quotaRefreshDelayMillis(source, QuotaReadTiming(lastAttemptAt = 1000), true, 500))
        assertEquals(0L, quotaRefreshDelayMillis(source, QuotaReadTiming(lastAttemptAt = 1000), true, 16000))
    }

    @Test fun allContractStatusesHaveFixedExplanations() {
        listOf("unknown", "unconfigured", "auto_disabled", "invalid", "unavailable", "script_error", "timeout", "http_error", "limit_exceeded", "unmatched", "unsupported", "offline", "failed", "cooldown").forEach {
            assertNotNull(it, quotaStatusMessage(it))
            assertNotEquals(it, "等待额度数据", quotaStatusMessage(it))
        }
        assertNull(quotaStatusMessage("ok"))
        assertNotNull(quotaStatusMessage("ok", stale = true))
        assertEquals("unsupported", quotaFailureStatus("method_not_found"))
        assertEquals("unavailable", quotaFailureStatus("not_found"))
        assertEquals("invalid", quotaFailureStatus("invalid_params"))
        assertEquals("failed", quotaFailureStatus("private error text"))
    }
}
