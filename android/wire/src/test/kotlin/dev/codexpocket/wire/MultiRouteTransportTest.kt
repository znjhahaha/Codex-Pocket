package dev.codexpocket.wire

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MultiRouteTransportTest {
    /** Real TCP, real inner TLS, no relay HTTP headers or application credentials outside TLS. */
    private class LanPeer(val disconnectMutation: Boolean = false, val wrongComputer: Boolean = false) : AutoCloseable {
        val cert = certificate()
        val listener = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val route = ConnectionRoute("lan", "lan", "tls://127.0.0.1:${listener.localPort}", 0)
        val config = ConnectionConfig("", "room", "outer-secret", fingerprint(cert), listOf(route), "computer")
        val requests = CopyOnWriteArrayList<JsonObject>()
        val firstBytes = CompletableDeferred<ByteArray>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile var socket: Socket? = null
        init {
            scope.launch {
                try {
                    val connection = listener.accept().also { socket = it }
                    val input = connection.getInputStream(); val output = connection.getOutputStream()
                    val frames = FrameCodec(); val replies = ArrayDeque<JsonObject>()
                    val tls = TlsPump(serverEngine(cert), { output.write(it); output.flush() }, { bytes ->
                        frames.feed(bytes).forEach { request ->
                            requests += request
                            val method = request.string("method")
                            if (disconnectMutation && method == "devices/revoke") connection.close()
                            else replies.add(buildJsonObject {
                                put("id", request.getValue("id"))
                                if (method == "hello" || method == "pair") put("result", buildJsonObject {
                                    put("protocol", 1); put("computerId", if (wrongComputer) "other-computer" else "computer")
                                    put("routes", JsonArray(listOf(route.toJson()))); put("routePolicy", "automatic")
                                }) else put("result", "ok")
                            })
                        }
                    }, {}, {})
                    tls.begin()
                    val buffer = ByteArray(65536)
                    while (!connection.isClosed) {
                        val length = input.read(buffer); if (length < 0) break
                        val bytes = buffer.copyOf(length); firstBytes.complete(bytes)
                        tls.receive(bytes)
                        while (replies.isNotEmpty()) tls.write(FrameCodec.encode(replies.removeFirst()))
                    }
                } catch (_: Exception) { /* cancelling fixtures closes blocking IO */ }
            }
        }
        override fun close() { socket?.close(); listener.close(); scope.cancel() }
    }

    @Test fun lanCarriesPinnedTlsAndPublishesRouteOnlyAfterHello() = runBlocking {
        LanPeer().use { peer -> RoutingPocketConnection().use { client ->
            client.connect(peer.config)
            assertEquals(LinkState.ONLINE, client.state.value)
            assertNull(client.activeRoute)
            assertEquals(22, withTimeout(2000) { peer.firstBytes.await() }[0].toInt())
            assertFalse(String(peer.firstBytes.await()).contains("outer-secret"))
            client.request("hello", buildJsonObject { put("protocol", 1); put("deviceId", "device"); put("deviceSecret", "inner-secret") })
            assertEquals(peer.route, client.activeRoute)
            assertEquals("ok", client.request("ping").jsonPrimitive.content)
            assertEquals("inner-secret", peer.requests.first()["params"]!!.jsonObject.string("deviceSecret"))
        } }
    }

    @Test fun fingerprintAndComputerIdentityCannotBeChangedByDiscoveryOrHello() = runBlocking {
        LanPeer().use { peer -> RoutingPocketConnection().use { client ->
            assertTrue(runCatching { client.connect(peer.config.copy(certificateSha256 = "00".repeat(32))) }.exceptionOrNull() is WireException)
            assertEquals(LinkState.OFFLINE, client.state.value); assertNull(client.activeRoute)
            assertTrue(peer.requests.isEmpty())
        } }
        LanPeer(wrongComputer = true).use { peer -> RoutingPocketConnection().use { client ->
            client.connect(peer.config)
            val error = runCatching { client.request("hello", buildJsonObject { put("protocol", 1) }) }.exceptionOrNull()
            assertTrue(error is WireException); assertNull(client.activeRoute)
        } }
    }

    @Test fun sentMutationOnLanDisconnectRemainsUnknownAndIsNotReplayed() = runBlocking {
        LanPeer(disconnectMutation = true).use { peer -> RoutingPocketConnection().use { client ->
            client.connect(peer.config)
            client.request("hello", buildJsonObject { put("protocol", 1) })
            val error = runCatching { client.request("devices/revoke", commandId = "stable-command") }.exceptionOrNull()
            assertTrue(error is UnknownOutcomeException)
            assertEquals("stable-command", (error as UnknownOutcomeException).commandId)
            assertEquals(1, peer.requests.count { it.string("method") == "devices/revoke" })
            assertNull(client.activeRoute)
        } }
    }

    @Test fun lanFailureAndHandshakeTimeoutFailOverToConfiguredPublicAndIgnoreOldCallbacks() = runBlocking {
        for (stall in listOf(false, true)) PocketClientTest.Relay().use { relay ->
            val routes = listOf(ConnectionRoute("lan", "lan", "tls://127.0.0.1:1", 0),
                ConnectionRoute("public", "relay", relay.config.phoneUrl(), 0))
            val opened = CopyOnWriteArrayList<String>()
            val cancelled = AtomicBoolean()
            lateinit var old: TransportListener
            val factory: (ConnectionRoute) -> PocketConnection = { route ->
                opened += route.id
                if (route.kind != "lan") PocketClient(relay) else PocketClient(TransportConnector { _, listener ->
                    old = listener
                    if (stall) listener.onOpen() else listener.onFailure()
                    object : BinaryTransport {
                        override fun send(bytes: ByteArray) = true
                        override fun close(graceful: Boolean) { cancelled.set(true) }
                    }
                })
            }
            RoutingPocketConnection(RouteManager(), 150, create = factory).use { client ->
                client.connect(relay.config.copy(routes = routes))
                assertEquals(listOf("lan", "public"), opened)
                assertTrue(cancelled.get()); assertNull(client.activeRoute)
                old.onOpen(); old.onBinary(byteArrayOf(1, 2, 3)); old.onFailure()
                assertEquals("ok", client.request("ping").jsonPrimitive.content)
                assertEquals(LinkState.ONLINE, client.state.value)
                assertEquals(1, relay.requests.size)
            }
        }
    }

    @Test fun networkGenerationAndConnectCancellationCloseAttemptsWithoutFailover() = runBlocking {
        for (networkChange in listOf(false, true)) {
            val opened = CompletableDeferred<Unit>(); val closed = AtomicBoolean(); var attempts = 0
            lateinit var listener: TransportListener
            val connector = TransportConnector { _, callback ->
                attempts++; listener = callback; callback.onOpen(); opened.complete(Unit)
                object : BinaryTransport {
                    override fun send(bytes: ByteArray) = true
                    override fun close(graceful: Boolean) { closed.set(true) }
                }
            }
            val manager = RouteManager()
            RoutingPocketConnection(manager, 100) { PocketClient(connector) }.use { client ->
                val config = ConnectionConfig("wss://relay.example", "room", "secret", "ab".repeat(32))
                val connect = async { runCatching { client.connect(config) }.exceptionOrNull() }
                opened.await()
                if (networkChange) manager.networkGenerationChanged(1) else connect.cancel()
                withTimeout(2000) { while (!closed.get()) delay(5) }
                listener.onOpen(); listener.onBinary(byteArrayOf(1)); listener.onFailure()
                assertEquals(LinkState.OFFLINE, client.state.value); assertNull(client.activeRoute)
                assertEquals(1, attempts)
                connect.cancelAndJoin()
            }
        }
    }
}
