package dev.codexpocket.wire

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingConnectionRaceTest {
    private val lan = ConnectionRoute("lan", "lan", "tls://192.168.1.8:1234", 0)
    private val lan2 = ConnectionRoute("lan2", "lan", "tls://192.168.1.9:1234", 1)
    private val relay = ConnectionRoute("public", "relay", "wss://relay.example/v1/tunnel/room/phone", 5)
    private val relay2 = ConnectionRoute("public2", "relay", "wss://other.example/v1/tunnel/room/phone", 6)

    private class Fake(val route: ConnectionRoute, private val owner: Fixture) : PocketConnection {
        override val state = MutableStateFlow(LinkState.OFFLINE)
        override val notifications: Flow<JsonObject> = emptyFlow()
        val verified = CompletableDeferred<Unit>()
        val requests = mutableListOf<Pair<String, String?>>()
        var closed = false
        var earlyOnline = false
        var completeOnClose = false
        var mutationUnknown = false
        var onClose: (() -> Unit)? = null
        override suspend fun connect(config: ConnectionConfig) {
            owner.starts += route.id to owner.scheduler.currentTime
            owner.live += this
            owner.peak = maxOf(owner.peak, owner.live.size)
            state.value = if (earlyOnline) LinkState.ONLINE else LinkState.CONNECTING
            if (completeOnClose) withContext(NonCancellable) { verified.await() } else verified.await()
            currentCoroutineContext().ensureActive()
            check(!closed)
            state.value = LinkState.ONLINE
        }
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            check(!closed)
            requests += method to commandId
            if (method == "hello") return buildJsonObject { put("protocol", 1); put("computerId", "pc") }
            if (mutationUnknown) throw UnknownOutcomeException(commandId!!)
            return JsonNull
        }
        override fun close() {
            if (closed) return
            closed = true
            owner.live -= this
            state.value = LinkState.OFFLINE
            onClose?.invoke()
            if (completeOnClose) verified.complete(Unit)
        }
    }

    private inner class Fixture(val scheduler: TestCoroutineScheduler, routes: List<ConnectionRoute> = listOf(lan, relay)) {
        val starts = mutableListOf<Pair<String, Long>>()
        val live = mutableSetOf<Fake>()
        var peak = 0
        val peers = routes.associateWith { Fake(it, this) }
        val manager = RouteManager({ scheduler.currentTime / 1000 }, { scheduler.currentTime })
        val config = ConnectionConfig("wss://relay.example", "room", "secret", "ab".repeat(32), routes, "pc")
        val client = RoutingPocketConnection(manager) { peers.getValue(it) }
    }

    @Test fun publicStartsAt250msAndOnlyVerifiedWinnerReceivesHelloAndMutation() = runTest {
        val f = Fixture(testScheduler)
        try {
            f.peers.getValue(lan).earlyOnline = true // transport state alone is not certificate verification
            val connected = async { f.client.connect(f.config) }
            runCurrent()
            assertEquals(listOf("lan" to 0L), f.starts)
            assertFalse(connected.isCompleted)
            advanceTimeBy(249); runCurrent()
            assertEquals(1, f.starts.size)
            advanceTimeBy(1); runCurrent()
            assertEquals(listOf("lan" to 0L, "public" to 250L), f.starts)
            assertEquals(2, f.peak)
            val winner = f.peers.getValue(relay)
            winner.verified.complete(Unit); runCurrent(); connected.await()
            assertTrue(f.peers.getValue(lan).closed)
            assertNull(f.client.activeRoute)
            assertTrue(f.peers.values.all { it.requests.isEmpty() })
            f.client.request("hello")
            assertEquals(relay, f.client.activeRoute)
            winner.mutationUnknown = true
            assertTrue(runCatching { f.client.request("devices/revoke", commandId = "once") }.exceptionOrNull() is UnknownOutcomeException)
            assertEquals(listOf("hello" to null, "devices/revoke" to "once"), winner.requests)
            assertTrue(f.peers.getValue(lan).requests.isEmpty())
        } finally { f.client.close() }
    }

    @Test fun fastLanCancelsStaggerWithoutOpeningPublic() = runTest {
        val f = Fixture(testScheduler)
        try {
            val connected = async { f.client.connect(f.config) }
            runCurrent(); advanceTimeBy(100)
            f.peers.getValue(lan).verified.complete(Unit)
            runCurrent(); connected.await()
            advanceTimeBy(1000); runCurrent()
            assertEquals(listOf("lan" to 0L), f.starts)
            assertFalse(f.peers.getValue(lan).closed)
            assertEquals(1, f.peak)
        } finally { f.client.close() }
    }

    @Test fun certificateFailureImmediatelyFreesSlotWithoutWaitingForStagger() = runTest {
        val f = Fixture(testScheduler)
        try {
            val connected = async { f.client.connect(f.config) }
            runCurrent(); advanceTimeBy(100)
            f.peers.getValue(lan).verified.completeExceptionally(WireException("certificate_mismatch", "Rejected pin"))
            runCurrent()
            assertTrue(f.peers.getValue(lan).closed)
            assertEquals(listOf("lan" to 0L, "public" to 100L), f.starts)
            f.peers.getValue(relay).verified.complete(Unit)
            runCurrent(); connected.await()
            assertEquals(1, f.peak)
        } finally { f.client.close() }
    }

    @Test fun publicSkipsSecondLanAndFailuresRefillOnlyAfterClosingWithAtMostTwoLive() = runTest {
        val f = Fixture(testScheduler, listOf(lan, lan2, relay, relay2))
        try {
            val connected = async { f.client.connect(f.config) }
            runCurrent(); advanceTimeBy(250); runCurrent()
            assertEquals(listOf("lan" to 0L, "public" to 250L), f.starts)
            f.peers.getValue(relay).verified.completeExceptionally(WireException("offline", "Failed"))
            runCurrent()
            assertTrue(f.peers.getValue(relay).closed)
            assertEquals("lan2" to 250L, f.starts.last())
            f.peers.getValue(lan).verified.completeExceptionally(WireException("offline", "Failed"))
            runCurrent()
            assertTrue(f.peers.getValue(lan).closed)
            assertEquals("public2" to 250L, f.starts.last())
            assertEquals(2, f.peak)
            f.peers.getValue(lan2).verified.complete(Unit)
            runCurrent(); connected.await()
            assertTrue(f.peers.getValue(relay2).closed)
            assertEquals(setOf(f.peers.getValue(lan2)), f.live)
        } finally { f.client.close() }
    }

    @Test fun simultaneousVerifiedCompletionsKeepExactlyOneConnection() = runTest {
        val f = Fixture(testScheduler)
        try {
            val connected = async { f.client.connect(f.config) }
            runCurrent(); advanceTimeBy(250); runCurrent()
            f.peers.values.forEach { it.verified.complete(Unit) }
            runCurrent(); connected.await()
            assertEquals(1, f.live.size)
            assertEquals(1, f.peers.values.count { it.closed })
            f.client.request("hello")
            f.client.request("devices/revoke", commandId = "once")
            assertEquals(1, f.peers.values.sumOf { it.requests.count { request -> request.first == "hello" } })
            assertEquals(1, f.peers.values.sumOf { it.requests.count { request -> request.second == "once" } })
            assertTrue(f.peers.values.filter { it.closed }.all { it.requests.isEmpty() })
        } finally { f.client.close() }
    }

    @Test fun timeoutBudgetsRemainSevenSecondsForLanAndTwentyForPublic() = runTest {
        val f = Fixture(testScheduler)
        try {
            val connected = async { runCatching { f.client.connect(f.config) }.exceptionOrNull() }
            runCurrent(); advanceTimeBy(6999); runCurrent()
            assertFalse(f.peers.getValue(lan).closed)
            advanceTimeBy(1); runCurrent()
            assertTrue(f.peers.getValue(lan).closed)
            assertFalse(connected.isCompleted)
            advanceTimeBy(13249); runCurrent()
            assertFalse(f.peers.getValue(relay).closed)
            advanceTimeBy(1); runCurrent()
            assertEquals("routes_unavailable", (connected.await() as WireException).code)
            assertTrue(f.live.isEmpty())
            assertEquals(LinkState.OFFLINE, f.client.state.value)
        } finally { f.client.close() }
    }

    @Test fun cancellationCloseAndNetworkChangeCloseEveryAttemptBeforeAndAfterStagger() = runTest {
        for (afterStagger in listOf(false, true)) for (abort in listOf("cancel", "close", "network")) {
            val f = Fixture(testScheduler, listOf(lan, relay, relay2))
            try {
                // Exercise a connect which ignores cancellation until its underlying transport closes.
                f.peers.values.forEach { it.completeOnClose = true }
                val connected = async { runCatching { f.client.connect(f.config) }.exceptionOrNull() }
                runCurrent()
                if (afterStagger) { advanceTimeBy(250); runCurrent() }
                assertEquals(if (afterStagger) 2 else 1, f.starts.size)
                assertEquals(if (afterStagger) 2 else 1, f.peak)
                when (abort) {
                    "cancel" -> connected.cancel()
                    "close" -> f.client.close()
                    else -> f.manager.networkGenerationChanged(1)
                }
                advanceTimeBy(50); runCurrent()
                assertTrue(connected.isCompleted)
                if (abort == "network") assertEquals("network_changed", (connected.await() as WireException).code)
                assertTrue(f.live.isEmpty())
                assertEquals(LinkState.OFFLINE, f.client.state.value)
                assertNull(f.client.activeRoute)
                val count = f.starts.size
                advanceTimeBy(1000); runCurrent()
                assertEquals(count, f.starts.size)
                assertTrue(f.peak <= 2)
                assertTrue(f.peers.values.all { it.requests.isEmpty() })
            } finally { f.client.close() }
        }
    }

    @Test fun routePolicyStillFiltersBeforeRacing() = runTest {
        for (policy in listOf(ConnectionPolicy.LAN_ONLY, ConnectionPolicy.PUBLIC_ONLY)) {
            val f = Fixture(testScheduler)
            try {
                val connected = async { f.client.connect(f.config.copy(policy = policy)) }
                runCurrent(); advanceTimeBy(300); runCurrent()
                val route = if (policy == ConnectionPolicy.LAN_ONLY) lan else relay
                assertEquals(listOf(route.id), f.starts.map { it.first })
                f.peers.getValue(route).verified.complete(Unit)
                runCurrent(); connected.await()
                assertEquals(1, f.peak)
            } finally { f.client.close() }
        }
    }

    @Test fun policyOrCatalogChangeBeforeAdoptionClosesVerifiedWinnerInSameGeneration() = runTest {
        for (changePolicy in listOf(false, true)) {
            val f = Fixture(testScheduler)
            try {
                val connected = async { runCatching { f.client.connect(f.config) }.exceptionOrNull() }
                runCurrent(); advanceTimeBy(250); runCurrent()
                // Closing the loser occurs after winner selection, before the adoption gate.
                f.peers.getValue(relay).onClose = {
                    if (changePolicy) f.manager.updatePolicy(ConnectionPolicy.PUBLIC_ONLY)
                    else f.manager.updateRoutes(listOf(relay))
                }
                f.peers.getValue(lan).verified.complete(Unit)
                runCurrent()
                assertEquals("routes_unavailable", (connected.await() as WireException).code)
                assertEquals(0L, f.manager.networkGeneration)
                assertTrue(f.peers.values.all { it.closed })
                assertTrue(f.live.isEmpty())
                assertEquals(LinkState.OFFLINE, f.client.state.value)
                assertNull(f.client.activeRoute)
                assertEquals(2, f.peak)
                assertTrue(f.peers.values.all { it.requests.isEmpty() })
            } finally { f.client.close() }
        }
    }
}
