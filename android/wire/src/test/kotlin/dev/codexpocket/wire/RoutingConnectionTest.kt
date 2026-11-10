package dev.codexpocket.wire

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RoutingConnectionTest {
    private val lan = ConnectionRoute("lan", "lan", "tls://192.168.1.8:1234", 0)
    private val relay = ConnectionRoute("public", "relay", "wss://relay.example/v1/tunnel/room/phone", 5)
    private fun config() = ConnectionConfig("wss://relay.example", "room", "secret", "ab".repeat(32), listOf(lan, relay), "pc")
    private class Fake(var fails: Boolean = false, val identity: String = "pc") : PocketConnection {
        override val state = MutableStateFlow(LinkState.OFFLINE)
        private val events = Channel<JsonObject>(8)
        override val notifications = events.receiveAsFlow()
        val requests = mutableListOf<Pair<String,String?>>()
        var hold: CompletableDeferred<Unit>? = null
        var mutationUnknown = false
        suspend fun emit(value: JsonObject) { events.send(value) }
        override suspend fun connect(config: ConnectionConfig) {
            if (fails) throw WireException("offline", "offline")
            state.value = LinkState.ONLINE
        }
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            requests += method to commandId
            if (method == "hello") return buildJsonObject { put("protocol", 1); put("computerId", identity) }
            hold?.await()
            if (mutationUnknown) throw UnknownOutcomeException(commandId!!)
            return JsonNull
        }
        override fun close() { state.value = LinkState.OFFLINE; events.close() }
    }
    @Test fun fallbackKeepsFullEndpointAndNeverReplaysAnUnknownMutation() = runBlocking {
        val attempted = mutableListOf<ConnectionRoute>()
        val failed = Fake(true); val good = Fake().apply { mutationUnknown = true }
        val c = RoutingPocketConnection(RouteManager()) { route -> attempted += route; if (route.kind == "lan") failed else good }
        c.connect(config()); assertEquals(listOf(lan, relay), attempted); assertNull(c.activeRoute)
        c.request("hello"); assertEquals(relay, c.activeRoute)
        val error = runCatching { c.request("thread/access/update", commandId = "once") }.exceptionOrNull()
        assertTrue(error is UnknownOutcomeException)
        assertEquals(1, good.requests.count { it.second == "once" }); assertTrue(failed.requests.isEmpty())
        c.close()
    }
    @Test fun healthyRouteMovesOnlyAfterAuthenticationAndWithoutInflightRequests() = runBlocking {
        var now = 100_000L
        val manager = RouteManager({ now/1000 }, { now })
        val public = Fake(); val direct = Fake(); var first = true
        val c = RoutingPocketConnection(manager, upgradeStabilityMillis = 0) { route -> if (route.kind != "lan") public else if(first) { first=false; Fake(true) } else direct }
        c.connect(config()); c.request("hello")
        assertFalse(c.tryPreferredRoute {}) // public stable wait, no flapping
        now += 31_000
        public.hold = CompletableDeferred()
        val read = async { c.request("threads/list") }
        yield()
        assertFalse(c.tryPreferredRoute {})
        public.hold!!.complete(Unit); read.await()
        assertTrue(c.tryPreferredRoute {})
        assertEquals(lan, c.activeRoute); assertEquals(LinkState.OFFLINE, public.state.value)
        assertEquals(listOf("hello" to null), direct.requests)
        c.close()
    }
    @Test fun foreignCandidateCannotReplaceHealthyComputer() = runBlocking {
        var now = 100_000L
        val manager = RouteManager({now/1000},{now}); val public=Fake(); var first=true
        val c = RoutingPocketConnection(manager, upgradeStabilityMillis = 0) { if(it.kind != "lan") public else if(first){first=false;Fake(true)}else Fake(identity="foreign") }
        c.connect(config()); c.request("hello"); now += 31_000
        assertFalse(c.tryPreferredRoute {}); assertEquals(relay,c.activeRoute)
        assertEquals(LinkState.ONLINE,public.state.value)
        c.request("ping"); c.close()
    }
    @Test fun candidateDisconnectDuringPreparationKeepsHealthyDelegate() = runBlocking {
        var now = 100_000L
        val manager = RouteManager({ now / 1000 }, { now })
        val old = Fake(); val candidate = Fake(); var first = true
        val c = RoutingPocketConnection(manager, upgradeStabilityMillis = 0) {
            if (it.kind != "lan") old else if (first) { first = false; Fake(true) } else candidate
        }
        try {
            c.connectAuthenticated(config(), buildJsonObject {}); now += 31_000
            assertFalse(c.tryPreferredRoute { it.request("sync"); candidate.close() })
            assertEquals(relay, c.activeRoute)
            assertEquals(LinkState.ONLINE, old.state.value)
            c.request("devices/revoke", commandId = "original")
            assertEquals(1, old.requests.count { it.second == "original" })
            assertTrue(candidate.requests.none { it.second != null })
        } finally { c.close() }
    }
    @Test fun candidateEventsContinueDrainingDuringPreparationAndDeliverInOrder() = runBlocking {
        var now = 100_000L
        val manager = RouteManager({ now / 1000 }, { now })
        val old = Fake(); val candidate = Fake(); var first = true
        val c = RoutingPocketConnection(manager, upgradeStabilityMillis = 0) {
            if (it.kind != "lan") old else if (first) { first = false; Fake(true) } else candidate
        }
        val prepared = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<List<Int>>()
        val reader = launch { delivered.complete(c.notifications.take(129).map { it["sequence"]!!.jsonPrimitive.int }.toList()) }
        try {
            c.connectAuthenticated(config(), buildJsonObject {}); now += 31_000
            val migration = async { c.tryPreferredRoute { prepared.complete(Unit); release.await() } }
            try {
                withTimeout(2_000) { prepared.await() }
                assertTrue("The candidate reader must stay active while sync is suspended",
                    withTimeoutOrNull(2_000) {
                        repeat(128) { candidate.emit(buildJsonObject { put("sequence", it + 1) }) }; true
                    } == true)
                assertFalse(delivered.isCompleted)
                release.complete(Unit)
                assertTrue(migration.await())
                candidate.emit(buildJsonObject { put("sequence", 129) })
                assertEquals((1..129).toList(), withTimeout(2_000) { delivered.await() })
            } finally { release.complete(Unit); migration.cancelAndJoin() }
        } finally { reader.cancelAndJoin(); c.close() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun heartbeatDeadlineStartsAfterMigrationReleasesDispatch() = runTest {
        val manager = RouteManager({ testScheduler.currentTime / 1000 }, { testScheduler.currentTime })
        val old = Fake(); val candidate = Fake(); var first = true
        val c = RoutingPocketConnection(manager, upgradeStabilityMillis = 0, dispatcher = StandardTestDispatcher(testScheduler)) {
            if (it.kind != "lan") old else if (first) { first = false; Fake(true) } else candidate
        }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            c.connectAuthenticated(config(), buildJsonObject {}); advanceTimeBy(31_000); runCurrent()
            val migration = async { c.tryPreferredRoute { entered.complete(Unit); release.await() } }
            runCurrent(); entered.await()
            val heartbeat = async { c.heartbeat("ping", timeoutMillis = 2000) }
            runCurrent(); advanceTimeBy(20_000); runCurrent()
            assertFalse(heartbeat.isCompleted)
            assertEquals(LinkState.ONLINE, old.state.value)
            assertTrue(old.requests.none { it.first == "ping" })
            release.complete(Unit); runCurrent()
            assertTrue(migration.await()); heartbeat.await()
            assertEquals(1, candidate.requests.count { it.first == "ping" })
            candidate.hold = CompletableDeferred()
            val timed = async { runCatching { c.heartbeat("ping", timeoutMillis = 2000) }.exceptionOrNull() }
            runCurrent(); advanceTimeBy(2000); runCurrent()
            assertTrue(timed.await() is TimeoutCancellationException)
        } finally { release.complete(Unit); c.close() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun overflowingCandidateBufferFailsClosedWithoutReplacingOldRoute() = runTest {
        val manager = RouteManager({ testScheduler.currentTime / 1000 }, { testScheduler.currentTime })
        val old = Fake(); val candidate = Fake(); var first = true
        val c = RoutingPocketConnection(manager, upgradeStabilityMillis = 0, dispatcher = StandardTestDispatcher(testScheduler)) {
            if (it.kind != "lan") old else if (first) { first = false; Fake(true) } else candidate
        }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            c.connectAuthenticated(config(), buildJsonObject {}); advanceTimeBy(31_000); runCurrent()
            val migration = async { c.tryPreferredRoute { entered.complete(Unit); release.await() } }
            runCurrent(); entered.await()
            val burst = async { repeat(513) { candidate.emit(buildJsonObject { put("sequence", it + 1) }) } }
            runCurrent(); burst.await()
            assertEquals(LinkState.OFFLINE, candidate.state.value)
            release.complete(Unit); runCurrent()
            assertFalse(migration.await())
            assertEquals(relay, c.activeRoute)
            c.request("devices/revoke", commandId = "once")
            assertEquals(1, old.requests.count { it.second == "once" })
        } finally { release.complete(Unit); c.close() }
    }
    @Test fun deviceHelloFailureDoesNotDiscardTheOtherAuthenticatedRoute() = runBlocking {
        val wrong = Fake(identity = "foreign"); val good = Fake()
        val c = RoutingPocketConnection(RouteManager()) { if (it.kind == "lan") wrong else good }
        try {
            val hello = c.connectAuthenticated(config(), buildJsonObject { put("deviceId", "fixture"); put("deviceSecret", "fixture") })
            assertEquals("pc", hello["computerId"]!!.jsonPrimitive.content)
            assertEquals(relay, c.activeRoute)
            assertEquals(LinkState.OFFLINE, wrong.state.value)
            assertEquals(1, good.requests.count { it.first == "hello" })
        } finally { c.close() }
    }
    @Test fun failedCursorPreparationKeepsOldHealthyRouteAndWritesThere() = runBlocking {
        var now = 100_000L
        val manager = RouteManager({now/1000},{now}); val old = Fake(); val direct = Fake(); var first = true
        val c = RoutingPocketConnection(manager, upgradeStabilityMillis = 0) { if (it.kind != "lan") old else if(first){first=false;Fake(true)}else direct }
        try {
            c.connectAuthenticated(config(), buildJsonObject {}); now += 31_000
            assertFalse(c.tryPreferredRoute { throw WireException("timeout", "Sync failed") })
            assertEquals(relay, c.activeRoute)
            assertEquals(LinkState.ONLINE, old.state.value)
            c.request("devices/revoke", commandId = "once")
            assertEquals(1, old.requests.count { it.second == "once" })
            assertTrue(direct.requests.none { it.second != null })
        } finally { c.close() }
    }
    @Test fun explicitReconnectDoesNotWaitForRouteCooldownAndStartsRecentSuccess() = runBlocking {
        val manager = RouteManager({100},{100_000})
        manager.configure(config()); manager.authenticated(relay, "pc"); manager.failed(relay); manager.failed(lan)
        val attempts = mutableListOf<ConnectionRoute>()
        val c = RoutingPocketConnection(manager) { attempts += it; Fake() }
        try {
            c.connectAuthenticated(config(), buildJsonObject {})
            assertEquals(relay, attempts.first())
            assertEquals(relay, c.activeRoute)
        } finally { c.close() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun directCandidateMustSurviveThirtySecondsAndSyncBeforeWritingMoves() = runTest {
        val manager=RouteManager({testScheduler.currentTime/1000},{testScheduler.currentTime})
        val old=Fake();val direct=Fake();var first=true
        val c=RoutingPocketConnection(manager) { if(it.kind!="lan") old else if(first){first=false;Fake(true)}else direct }
        try {
            c.connectAuthenticated(config(),buildJsonObject {})
            advanceTimeBy(31_000);runCurrent()
            val preparing=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val change=async { c.tryPreferredRoute { preparing.complete(Unit); release.await() } }
            runCurrent();advanceTimeBy(29_999);runCurrent()
            assertFalse(preparing.isCompleted);assertEquals(relay,c.activeRoute)
            c.request("ping");assertEquals(LinkState.ONLINE,old.state.value)
            advanceTimeBy(1);runCurrent();assertTrue(preparing.isCompleted)
            val write=async { c.request("devices/revoke",commandId="after-switch") }
            runCurrent();assertFalse(write.isCompleted)
            release.complete(Unit);runCurrent();assertTrue(change.await());write.await()
            assertEquals(lan,c.activeRoute)
            assertTrue(old.requests.none { it.second=="after-switch" })
            assertEquals(1,direct.requests.count { it.second=="after-switch" })
        } finally {c.close()}
    }
}
