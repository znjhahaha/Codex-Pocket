package dev.codexpocket.wire

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
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
        val c = RoutingPocketConnection(manager) { route -> if (route.kind != "lan") public else if(first) { first=false; Fake(true) } else direct }
        c.connect(config()); c.request("hello")
        assertFalse(c.tryPreferredRoute()) // public stable wait, no flapping
        now += 31_000
        public.hold = CompletableDeferred()
        val read = async { c.request("threads/list") }
        yield()
        assertFalse(c.tryPreferredRoute())
        public.hold!!.complete(Unit); read.await()
        assertTrue(c.tryPreferredRoute())
        assertEquals(lan, c.activeRoute); assertEquals(LinkState.OFFLINE, public.state.value)
        assertEquals(listOf("hello" to null), direct.requests)
        c.close()
    }
    @Test fun foreignCandidateCannotReplaceHealthyComputer() = runBlocking {
        var now = 100_000L
        val manager = RouteManager({now/1000},{now}); val public=Fake(); var first=true
        val c = RoutingPocketConnection(manager) { if(it.kind != "lan") public else if(first){first=false;Fake(true)}else Fake(identity="foreign") }
        c.connect(config()); c.request("hello"); now += 31_000
        assertFalse(c.tryPreferredRoute()); assertEquals(relay,c.activeRoute)
        assertEquals(LinkState.ONLINE,public.state.value)
        c.request("ping"); c.close()
    }
}
