package dev.codexpocket.wire

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PublicDirectRoutingTest {
    private val relay = ConnectionRoute("relay", "relay", "wss://example.invalid/phone", 9)
    private val direct = ConnectionRoute("bcfe7126-c3aa-4572-a13d-437afc86a0e1", "p2p", "p2p://bcfe7126-c3aa-4572-a13d-437afc86a0e1", 1)
    private val config = ConnectionConfig("wss://example.invalid", "room", "secret", "ab".repeat(32), listOf(relay), "pc")
    private class Fake(val identity: String = "pc") : PocketConnection {
        override val state = MutableStateFlow(LinkState.OFFLINE)
        val events = Channel<JsonObject>(64)
        override val notifications = events.receiveAsFlow()
        val calls = mutableListOf<String>()
        var held: CompletableDeferred<Unit>? = null
        override suspend fun connect(config: ConnectionConfig) { state.value = LinkState.ONLINE }
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            calls += method
            if (method == "hello") return buildJsonObject { put("computerId", identity); put("protocol", 1); put("capabilities", buildJsonArray { add("p2p.v1") }) }
            if (method == "write") { held?.await(); throw UnknownOutcomeException(commandId!!) }
            return JsonObject(emptyMap())
        }
        override fun close() { state.value = LinkState.OFFLINE; events.close() }
    }
    private fun factory(candidate: Fake) = object : DirectCandidateFactory {
        override fun available() = true
        override suspend fun open(old: PocketConnection, config: ConnectionConfig, credentials: JsonObject, generation: Long, useStun: Boolean): P2pCandidate.Opened {
            candidate.connect(config)
            return P2pCandidate.Opened(candidate, direct, credentials)
        }
    }
    @Test fun authenticatedStablePeerReplacesRelayOnlyAfterSync() = runTest {
        val old = Fake(); val peer = Fake(); val dispatcher = StandardTestDispatcher(testScheduler)
        val routing = RoutingPocketConnection(RouteManager(), upgradeStabilityMillis = 30_000, dispatcher = dispatcher, directFactory = factory(peer)) { old }
        routing.connectAuthenticated(config, JsonObject(emptyMap()))
        val swap = async { routing.tryPublicDirect { assertEquals(relay, routing.activeRoute); it.request("sync") } }
        runCurrent(); advanceTimeBy(29_999); runCurrent()
        assertEquals(relay, routing.activeRoute); assertFalse(swap.isCompleted)
        advanceTimeBy(1); runCurrent(); assertTrue(swap.await())
        assertEquals(direct, routing.activeRoute); assertEquals(LinkState.OFFLINE, old.state.value)
        assertTrue("sync" in peer.calls); assertNull(routing.relayVersion)
        routing.close()
    }
    @Test fun foreignPeerAndSyncFailureKeepHealthyRelay() = runTest {
        for (foreign in listOf(true, false)) {
            val old = Fake(); val peer = Fake(if (foreign) "foreign" else "pc")
            val routing = RoutingPocketConnection(RouteManager(), upgradeStabilityMillis = 0, dispatcher = StandardTestDispatcher(testScheduler), directFactory = factory(peer)) { old }
            routing.connectAuthenticated(config, JsonObject(emptyMap()))
            assertFalse(routing.tryPublicDirect { error("sync failed") })
            assertEquals(relay, routing.activeRoute); assertEquals(LinkState.ONLINE, old.state.value)
            assertEquals(LinkState.OFFLINE, peer.state.value); routing.request("ping"); routing.close()
        }
    }
    @Test fun inFlightUnknownWriteNeverMovesOrResends() = runTest {
        val old = Fake().apply { held = CompletableDeferred() }; val peer = Fake()
        val routing = RoutingPocketConnection(RouteManager(), upgradeStabilityMillis = 0, dispatcher = StandardTestDispatcher(testScheduler), directFactory = factory(peer)) { old }
        routing.connectAuthenticated(config, JsonObject(emptyMap()))
        val writing = async { runCatching { routing.request("write", commandId = "once") } }
        runCurrent(); assertFalse(routing.tryPublicDirect {})
        assertEquals(relay, routing.activeRoute); assertFalse("write" in peer.calls)
        old.held!!.complete(Unit); assertTrue(writing.await().exceptionOrNull() is UnknownOutcomeException)
        assertEquals(1, old.calls.count { it == "write" }); routing.close()
    }
    @Test fun negotiatedRouteIsNeverPersistedOrAcceptedFromDirectory() {
        assertTrue(runCatching { direct.toJson() }.isFailure)
        assertTrue(runCatching { ConnectionRoute.parse(buildJsonObject { put("id",direct.id);put("kind","p2p");put("url",direct.url);put("priority",1) }) }.isFailure)
    }
}
