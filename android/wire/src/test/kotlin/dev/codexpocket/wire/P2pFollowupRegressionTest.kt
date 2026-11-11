package dev.codexpocket.wire

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real routing and compiled P2pCandidate callback; no JNI, socket, STUN or production peer. */
@OptIn(ExperimentalCoroutinesApi::class)
class P2pFollowupRegressionTest {
    private val relay = ConnectionRoute("relay", "relay", "wss://example.invalid/phone", 9)
    private val direct = ConnectionRoute("bcfe7126-c3aa-4572-a13d-437afc86a0e1", "p2p", "p2p://bcfe7126-c3aa-4572-a13d-437afc86a0e1", 1)
    private val config = ConnectionConfig("wss://example.invalid", "room", "secret", "ab".repeat(32), listOf(relay), "pc")
    private class Fake : PocketConnection {
        override val state = MutableStateFlow(LinkState.OFFLINE)
        val events = Channel<JsonObject>(64)
        var readers = 0
        var closes = 0
        override val notifications = events.receiveAsFlow().onStart { readers++ }.onCompletion { readers-- }
        val calls = mutableListOf<Pair<String, String?>>()
        var helloGate: CompletableDeferred<Unit>? = null
        var helloCancelled = false
        override suspend fun connect(config: ConnectionConfig) { state.value = LinkState.ONLINE }
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            calls += method to commandId
            if (method == "hello") {
                if (helloGate != null) try { helloGate!!.await() } catch (e: CancellationException) { helloCancelled = true; throw e }
                return buildJsonObject { put("computerId", "pc"); put("protocol", 1); put("capabilities", buildJsonArray { add("p2p.v1") }) }
            }
            return JsonObject(emptyMap())
        }
        override fun close() { if (state.value != LinkState.OFFLINE) closes++; state.value = LinkState.OFFLINE; events.close() }
    }
    private fun factory(peer: Fake) = object : DirectCandidateFactory {
        override fun available() = true
        override suspend fun open(old: PocketConnection, config: ConnectionConfig, credentials: JsonObject, generation: Long, useStun: Boolean): P2pCandidate.Opened {
            peer.connect(config); return P2pCandidate.Opened(peer, direct, credentials)
        }
    }
    private fun routing(manager: RouteManager, scheduler: TestCoroutineScheduler, old: Fake, peer: Fake) =
        RoutingPocketConnection(manager, upgradeStabilityMillis = 0, dispatcher = StandardTestDispatcher(scheduler), directFactory = factory(peer)) { old }

    @Test fun preferenceRevokedAtPrepareCompletionMustNotCommitBeforeNextMonitorTick() = runTest {
        val old = Fake(); val peer = Fake(); val manager = RouteManager(); val route = routing(manager, testScheduler, old, peer)
        try {
            route.connectAuthenticated(config, JsonObject(emptyMap()))
            // Let both monitors observe the original preference at t=0 and sleep until t=50.
            // Complete prepare at t=1, so this checks the commit between monitor ticks.
            val migration = async { route.tryPublicDirect { delay(1); route.usePublicStun = false } }
            runCurrent(); advanceTimeBy(1); runCurrent()
            val adopted = migration.await()
            assertFalse("The final commit must recheck the preference version, not wait for the next 50ms monitor tick", adopted)
            assertEquals(relay, route.activeRoute)
            assertEquals(LinkState.ONLINE, old.state.value)
        } finally { route.close() }
    }

    @Test fun finalPrepareHasFiveSecondHardDeadlineAndCancelsWork() = runTest {
        val old = Fake(); val peer = Fake(); val manager = RouteManager(); val route = routing(manager, testScheduler, old, peer)
        var cancelled = false
        try {
            route.connectAuthenticated(config, JsonObject(emptyMap()))
            val entered = CompletableDeferred<Unit>()
            val migration = async { route.tryPublicDirect { entered.complete(Unit); try { awaitCancellation() } finally { cancelled = true } } }
            runCurrent(); entered.await()
            val writing = async { route.request("fixture/write", commandId = "once") }
            runCurrent(); advanceTimeBy(4999); runCurrent(); assertFalse(migration.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertFalse(migration.await()); writing.await()
            assertTrue(cancelled); assertEquals(0, peer.readers); assertEquals(1, peer.closes)
            assertEquals(LinkState.ONLINE, old.state.value); assertEquals(1, old.calls.count { it.second == "once" })
        } finally { route.close() }
    }

    @Test fun preferenceMonitorCancelsNegotiationAndJoinsItsChildTasks() = runTest {
        val old = Fake(); val peer = Fake(); var openCancelled = false; var childCancelled = false
        val entered = CompletableDeferred<Unit>()
        val directFactory = object : DirectCandidateFactory {
            override fun available() = true
            override suspend fun open(old: PocketConnection, config: ConnectionConfig, credentials: JsonObject, generation: Long, useStun: Boolean): P2pCandidate.Opened = coroutineScope {
                launch { try { awaitCancellation() } finally { childCancelled = true } }
                entered.complete(Unit)
                try { awaitCancellation() } finally { openCancelled = true }
            }
        }
        val route = RoutingPocketConnection(RouteManager(), dispatcher = StandardTestDispatcher(testScheduler), directFactory = directFactory) { old }
        try {
            route.connectAuthenticated(config, JsonObject(emptyMap()))
            val migration = async { route.tryPublicDirect {} }
            runCurrent(); entered.await(); route.usePublicStun = false
            advanceTimeBy(50); runCurrent()
            assertFalse(migration.await()); assertTrue(openCancelled); assertTrue(childCancelled)
            assertEquals(relay, route.activeRoute); assertEquals(LinkState.ONLINE, old.state.value)
            route.request("fixture/write", commandId = "once"); assertEquals(1, old.calls.count { it.second == "once" })
        } finally { route.close(); peer.close() }
    }

    @Test fun networkMonitorCancelsDeviceHelloAndClosesOnlyCandidate() = runTest {
        val old = Fake(); val peer = Fake().apply { helloGate = CompletableDeferred() }; val manager = RouteManager()
        val route = routing(manager, testScheduler, old, peer)
        try {
            route.connectAuthenticated(config, JsonObject(emptyMap()))
            val migration = async { route.tryPublicDirect {} }
            runCurrent(); assertTrue(peer.calls.any { it.first == "hello" }); manager.networkGenerationChanged(1)
            advanceTimeBy(50); runCurrent()
            assertFalse(migration.await()); assertTrue(peer.helloCancelled)
            assertEquals(0, peer.readers); assertEquals(1, peer.closes)
            assertEquals(relay, route.activeRoute); assertEquals(LinkState.ONLINE, old.state.value)
        } finally { route.close() }
    }

    @Test fun candidateQueueAndByteLimitAbortPrepareWithoutFailingOldLine() = runTest {
        for (byteLimit in listOf(false, true)) {
            val old = Fake(); val peer = Fake(); val manager = RouteManager(); val route = routing(manager, testScheduler, old, peer)
            var cancelled = false
            try {
                route.connectAuthenticated(config, JsonObject(emptyMap()))
                val entered = CompletableDeferred<Unit>()
                val migration = async { route.tryPublicDirect { entered.complete(Unit); try { awaitCancellation() } finally { cancelled = true } } }
                runCurrent(); entered.await()
                val payload = if (byteLimit) "x".repeat(1024 * 1024) else "x"
                val burst = async {
                    repeat(if (byteLimit) 9 else 513) { index ->
                        peer.events.send(buildJsonObject { put("method", "fixture/event"); put("params", buildJsonObject { put("index", index); put("data", payload) }) })
                    }
                }
                runCurrent(); advanceTimeBy(50); runCurrent()
                burst.await(); assertFalse(migration.await()); assertTrue(cancelled)
                assertEquals(0, peer.readers); assertEquals(1, peer.closes)
                assertEquals(relay, route.activeRoute); assertEquals(LinkState.ONLINE, old.state.value)
                route.request("fixture/write", commandId = "once"); assertEquals(1, old.calls.count { it.second == "once" })
            } finally { route.close() }
        }
    }

    @Test fun lateIceAfterSignalingCleanupKeepsAcceptedPeerAlive() {
        val queue=Channel<DataChannelSignal>(256); val signals=NegotiationSignals(queue); var failures=0
        signals.finish(); queue.cancel()
        signals.send(DataChannelSignal.Candidate(1,"late","0")){failures++}
        assertEquals(0,failures)
    }
    @Test fun activeNegotiationOverflowStillFailsClosed() {
        val queue=Channel<DataChannelSignal>(256); val signals=NegotiationSignals(queue);var failures=0
        repeat(256){signals.send(DataChannelSignal.Candidate(1,"candidate-$it","0")){failures++}}
        assertEquals(0,failures)
        signals.send(DataChannelSignal.Candidate(1,"overflow","0")){failures++}
        assertEquals(1,failures)
        signals.finish();queue.cancel()
        signals.send(DataChannelSignal.Candidate(1,"late","0")){failures++}
        assertEquals(1,failures)
    }
}
