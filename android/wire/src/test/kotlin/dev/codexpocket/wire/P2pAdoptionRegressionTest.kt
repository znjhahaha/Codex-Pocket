package dev.codexpocket.wire

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Observed-behavior probes. Passing demonstrates the reported defect, not acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
class P2pAdoptionRegressionTest {
    private val relay = ConnectionRoute("relay", "relay", "wss://example.invalid/phone", 9)
    private val peerRoute = ConnectionRoute("bcfe7126-c3aa-4572-a13d-437afc86a0e1", "p2p", "p2p://bcfe7126-c3aa-4572-a13d-437afc86a0e1", 1)
    private val config = ConnectionConfig("wss://example.invalid", "room", "secret", "ab".repeat(32), listOf(relay), "pc")
    private class Fake(private val extraHello: JsonObject = JsonObject(emptyMap())) : PocketConnection {
        override val state = MutableStateFlow(LinkState.OFFLINE)
        private val events = Channel<JsonObject>(64)
        override val notifications = events.receiveAsFlow()
        val calls = mutableListOf<Pair<String, String?>>()
        override suspend fun connect(config: ConnectionConfig) { state.value = LinkState.ONLINE }
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            calls += method to commandId
            return if (method == "hello") JsonObject(buildJsonObject {
                put("computerId", "pc"); put("protocol", 1); put("capabilities", buildJsonArray { add("p2p.v1") })
            } + extraHello) else JsonObject(emptyMap())
        }
        override fun close() { state.value = LinkState.OFFLINE; events.close() }
    }
    private fun factory(peer: Fake, available: Boolean = true) = object : DirectCandidateFactory {
        override fun available() = available
        override suspend fun open(old: PocketConnection, config: ConnectionConfig, credentials: JsonObject, generation: Long, useStun: Boolean): P2pCandidate.Opened {
            peer.connect(config)
            return P2pCandidate.Opened(peer, peerRoute, credentials)
        }
    }

    @Test fun observed_disableDuringStabilizationStillAdoptsDirectAndClosesHealthyRelay() = runTest {
        val old = Fake(); val peer = Fake(); val manager = RouteManager()
        val routing = RoutingPocketConnection(manager, dispatcher = StandardTestDispatcher(testScheduler), directFactory = factory(peer)) { old }
        try {
            routing.connectAuthenticated(config, JsonObject(emptyMap()))
            val migration = async { routing.tryPublicDirect { it.request("sync") } }
            runCurrent(); advanceTimeBy(10_000); runCurrent()
            routing.p2pEnabled = false; routing.usePublicStun = false
            advanceTimeBy(20_000); runCurrent()
            assertFalse(migration.await())
            assertEquals(relay, routing.activeRoute)
            assertEquals(LinkState.ONLINE, old.state.value)
            assertFalse(routing.p2pEnabled)
        } finally { routing.close() }
    }

    @Test fun candidateHelloLanOnlyDoesNotAdoptP2p() = runTest {
        val old = Fake()
        val peer = Fake(buildJsonObject { put("routes", JsonArray(listOf(relay.toJson()))); put("routePolicy", "lan") })
        val manager = RouteManager()
        val routing = RoutingPocketConnection(manager, upgradeStabilityMillis = 0, dispatcher = StandardTestDispatcher(testScheduler), directFactory = factory(peer)) { old }
        try {
            routing.connectAuthenticated(config, JsonObject(emptyMap()))
            assertFalse(routing.tryPublicDirect { it.request("sync") })
            assertEquals(ConnectionPolicy.AUTO, manager.policy)
            assertFalse(manager.eligible(peerRoute))
            assertEquals(relay, routing.activeRoute)
            assertEquals(LinkState.ONLINE, old.state.value)
        } finally { routing.close() }
    }

    @Test fun networkChangeCancelsFinalSyncAndUnblocksWrites() = runTest {
        val old = Fake(); val peer = Fake(); val manager = RouteManager()
        val routing = RoutingPocketConnection(manager, upgradeStabilityMillis = 0, dispatcher = StandardTestDispatcher(testScheduler), directFactory = factory(peer)) { old }
        val syncEntered = CompletableDeferred<Unit>(); val releaseSync = CompletableDeferred<Unit>()
        try {
            routing.connectAuthenticated(config, JsonObject(emptyMap()))
            val migration = async { routing.tryPublicDirect { syncEntered.complete(Unit); releaseSync.await() } }
            runCurrent(); syncEntered.await()
            val writing = async { routing.request("fixture/write", commandId = "single-write", timeoutMillis = 1500) }
            runCurrent(); manager.networkGenerationChanged(1)
            advanceTimeBy(100); runCurrent()
            assertTrue(migration.isCompleted)
            assertTrue(writing.isCompleted)
            assertEquals(LinkState.ONLINE, old.state.value)
            assertEquals(1, old.calls.count { it.second == "single-write" })
            releaseSync.complete(Unit); runCurrent()
            assertFalse(migration.await()); writing.await()
            assertEquals(1, old.calls.count { it.second == "single-write" })
        } finally { releaseSync.complete(Unit); routing.close() }
    }

    @Test fun unavailableAdapterDoesNotAttemptCandidateAndKeepsOldBusinessEntry() = runTest {
        val old = Fake(); val peer = Fake()
        val routing = RoutingPocketConnection(RouteManager(), dispatcher = StandardTestDispatcher(testScheduler), directFactory = factory(peer, false)) { old }
        try {
            routing.connectAuthenticated(config, JsonObject(emptyMap()))
            assertFalse(routing.tryPublicDirect {})
            routing.request("fixture/write", commandId = "single-write")
            assertEquals(1, old.calls.count { it.second == "single-write" })
            assertTrue(peer.calls.isEmpty())
            assertEquals(LinkState.ONLINE, old.state.value)
        } finally { routing.close() }
    }
}
