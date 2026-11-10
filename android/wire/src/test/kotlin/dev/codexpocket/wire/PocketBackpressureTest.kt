package dev.codexpocket.wire

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PocketBackpressureTest {
    @Test fun legacyBooleanFailureRetainsExistingBackpressureDiagnosis() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var sends = 0
        var closes = 0
        val connector = TransportConnector { _, listener ->
            listener.onOpen()
            object : BinaryTransport {
                override fun send(bytes: ByteArray): Boolean { sends++; return false }
                override fun close(graceful: Boolean) { closes++ }
            }
        }
        PocketClient(connector, dispatcher).use { client ->
            val config = ConnectionConfig("wss://fixture.invalid", "fixture", "fixture", "00".repeat(32))
            val connecting = async { runCatching { client.connect(config) }.exceptionOrNull() }
            runCurrent()
            assertEquals("backpressure", (connecting.await() as WireException).code)
            assertEquals("transport_send", client.backpressureSource)
            assertEquals(1, sends); assertEquals(1, closes)
        }
    }

    @Test fun existingWebSocketConnectorStillCarriesPinnedTlsBinaryFrames() = runBlocking {
        // Loopback WS exercises the unchanged WebSocket framing/send implementation. Production
        // WSS platform trust is unchanged; this fixture does not claim outer WSS trust validation.
        val cert = certificate()
        val requests = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        MockWebServer().use { relay ->
            relay.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                lateinit var tls: TlsPump
                val frames = FrameCodec()
                val replies = ArrayDeque<JsonObject>()
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    tls = TlsPump(serverEngine(cert), {
                        if (webSocket.send(it.toByteString())) TransportSendResult.ACCEPTED else TransportSendResult.CLOSED
                    }, { bytes -> frames.feed(bytes).forEach { request ->
                        requests += request
                        replies.add(buildJsonObject { put("id", request.getValue("id")); put("result", "ok") })
                    } }, {}, {})
                    tls.begin()
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    tls.receive(bytes.toByteArray())
                    while (replies.isNotEmpty()) tls.write(FrameCodec.encode(replies.removeFirst()))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
            }))
            val endpoint = relay.url("/fixture").toString().replaceFirst("http://", "ws://")
            PocketClient(WssConnector(endpoint)).use { client ->
                client.connect(ConnectionConfig("wss://fixture.invalid", "fixture", "fixture", fingerprint(cert)))
                assertEquals("ok", client.request("ping", timeoutMillis = 5000).jsonPrimitive.content)
                assertEquals(LinkState.ONLINE, client.state.value)
                assertEquals(1, requests.size)
            }
        }
    }

    /** All scheduling and writable events are controlled by the test; no sockets/JNI/models. */
    private class Relay(dispatcher: CoroutineDispatcher, val respond: Boolean = true) : AutoCloseable {
        val cert = certificate()
        val config = ConnectionConfig("wss://fixture.invalid", "fixture", "fixture", fingerprint(cert))
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val input = Channel<ByteArray>(128)
        val requests = mutableListOf<JsonObject>()
        val attempts = mutableListOf<ByteArray>()
        var credits = Int.MAX_VALUE
        var closes = 0
        lateinit var listener: TransportListener
        val connector = BackpressureConnector(TransportConnector { _, callbacks ->
            listener = callbacks
            scope.launch {
                val frames = FrameCodec()
                val replies = ArrayDeque<JsonObject>()
                val server = TlsPump(serverEngine(cert), { bytes ->
                    TlsBackpressureTest.fragments(bytes, 7, callbacks::onBinary)
                    TransportSendResult.ACCEPTED
                }, { bytes ->
                    frames.feed(bytes).forEach { request ->
                        requests += request
                        if (respond) replies.add(buildJsonObject { put("id", request.getValue("id")); put("result", "ok") })
                    }
                }, {}, {})
                try {
                    server.begin(); callbacks.onOpen()
                    for (bytes in input) {
                        TlsBackpressureTest.fragments(bytes, 13, server::receive)
                        while (replies.isNotEmpty()) server.write(FrameCodec.encode(replies.removeFirst()))
                    }
                } finally { server.abort() }
            }
            object : BinaryTransport {
                override fun send(bytes: ByteArray): Boolean {
                    attempts += bytes.copyOf()
                    if (credits == 0) return false
                    credits--
                    check(input.trySend(bytes.copyOf()).isSuccess)
                    return true
                }
                override fun close(graceful: Boolean) { closes++; scope.cancel(); input.cancel() }
            }
        })
        fun writable(count: Int) { credits = count; listener.onWritable() }
        override fun close() { scope.cancel(); input.cancel() }
    }

    @Test fun handshakeWaitsWithoutPollingAndWritableStormIsCoalesced() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Relay(dispatcher).use { relay -> PocketClient(relay.connector, dispatcher).use { client ->
            relay.credits = 0
            val connecting = async { client.connect(relay.config) }
            runCurrent()
            assertEquals(LinkState.CONNECTING, client.state.value)
            assertFalse(connecting.isCompleted)
            assertEquals(1, relay.attempts.size)
            val clientHello = relay.attempts.single().copyOf()
            advanceTimeBy(1000); runCurrent()
            assertEquals("No writable event means no retry timer", 1, relay.attempts.size)
            repeat(10000) { relay.listener.onWritable() }
            assertEquals("Callback only schedules owner work", 1, relay.attempts.size)
            runCurrent()
            assertEquals("Storm must make just one retry", 2, relay.attempts.size)
            assertArrayEquals(clientHello, relay.attempts[1])
            relay.writable(Int.MAX_VALUE); runCurrent(); connecting.await()
            assertEquals(LinkState.ONLINE, client.state.value)
            assertArrayEquals(clientHello, relay.attempts[2])
        } }
    }

    @Test fun partialRequestFrameResumesWithoutDuplicateCiphertextOrBusinessReplay() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Relay(dispatcher).use { relay -> PocketClient(relay.connector, dispatcher).use { client ->
            val connecting = async { client.connect(relay.config) }; runCurrent(); connecting.await()
            relay.credits = 1
            val body = "x".repeat(230000)
            val request = async { client.request("ping", buildJsonObject { put("body", body) }) }
            runCurrent()
            assertTrue(relay.requests.isEmpty())
            var rounds = 0
            while (!request.isCompleted) {
                assertTrue(++rounds < 32)
                val rejected = relay.attempts.last().copyOf()
                val before = relay.attempts.size
                relay.listener.onBinary(byteArrayOf()); runCurrent()
                assertEquals(before, relay.attempts.size)
                relay.writable(1); runCurrent()
                assertArrayEquals(rejected, relay.attempts[before])
            }
            assertEquals("ok", request.await().jsonPrimitive.content)
            assertEquals(1, relay.requests.size)
            assertEquals(body, relay.requests.single()["params"]!!.jsonObject["body"]!!.jsonPrimitive.content)
            assertEquals(LinkState.ONLINE, client.state.value)
        } }
    }

    @Test fun localAndRemoteCloseDuringBackpressureCompletePendingMutationUnknown() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (remote in listOf(false, true)) Relay(dispatcher).use { relay -> PocketClient(relay.connector, dispatcher).use { client ->
            val connecting = async { client.connect(relay.config) }; runCurrent(); connecting.await()
            relay.credits = 0
            val request = async { runCatching { client.request("devices/revoke", commandId = "fixture-command") }.exceptionOrNull() }
            runCurrent()
            val before = relay.attempts.size
            if (remote) relay.listener.onFailure() else client.close()
            runCurrent()
            assertTrue(request.await() is UnknownOutcomeException)
            assertEquals(LinkState.OFFLINE, client.state.value)
            assertEquals(if (remote) "disconnected" else "closed", client.failureCode)
            assertEquals(1, relay.closes)
            repeat(1000) { relay.writable(Int.MAX_VALUE); relay.listener.onOpen(); relay.listener.onBinary(byteArrayOf(1)) }
            runCurrent()
            assertEquals(before, relay.attempts.size)
            assertTrue(relay.requests.isEmpty())
        } }
    }

    @Test fun connectCancellationAndOldGenerationCallbacksCannotWakeNewClient() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Relay(dispatcher).use { old -> Relay(dispatcher).use { current ->
            val previous = PocketClient(old.connector, dispatcher)
            old.credits = 0
            val connecting = async { previous.connect(old.config) }
            runCurrent(); connecting.cancelAndJoin(); runCurrent()
            assertEquals(1, old.closes)
            val before = old.attempts.size
            PocketClient(current.connector, dispatcher).use { client ->
                val ready = async { client.connect(current.config) }; runCurrent(); ready.await()
                repeat(1000) { old.writable(Int.MAX_VALUE); old.listener.onFailure(); old.listener.onBinary(byteArrayOf(1)); old.listener.onOpen() }
                runCurrent()
                val ping = async { client.request("ping") }; runCurrent()
                assertEquals("ok", ping.await().jsonPrimitive.content)
                assertEquals(LinkState.ONLINE, client.state.value)
                assertEquals(before, old.attempts.size)
                assertEquals(1, current.requests.size)
            }
            previous.close()
        } }
    }

    @Test fun cancellingBlockedRequestDiscardsRemainderAndRequestsBehindIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Relay(dispatcher).use { relay -> PocketClient(relay.connector, dispatcher).use { client ->
            val ready = async { client.connect(relay.config) }; runCurrent(); ready.await()
            relay.credits = 1
            val result = CompletableDeferred<Throwable?>()
            val cancelled = launch {
                result.complete(runCatching {
                    client.request("attachment/upload", buildJsonObject { put("body", "x".repeat(120000)) }, "fixture-command")
                }.exceptionOrNull())
            }
            runCurrent()
            val following = async { runCatching { client.request("ping") }.exceptionOrNull() }
            runCurrent()
            val before = relay.attempts.size
            cancelled.cancelAndJoin(); runCurrent()
            assertTrue(result.await() is UnknownOutcomeException)
            assertTrue(following.await() is WireException)
            assertEquals(LinkState.OFFLINE, client.state.value)
            assertEquals(1, relay.closes)
            relay.writable(Int.MAX_VALUE); runCurrent()
            assertEquals(before, relay.attempts.size)
            assertTrue(relay.requests.isEmpty())
        } }
    }

    @Test fun admittedRequestCancellationClosesOptInCandidateEvenWhenCurrentlyWritable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Relay(dispatcher, respond = false).use { relay -> PocketClient(relay.connector, dispatcher).use { client ->
            val ready = async { client.connect(relay.config) }; runCurrent(); ready.await()
            val result = CompletableDeferred<Throwable?>()
            val request = launch {
                result.complete(runCatching { client.request("devices/revoke", commandId = "fixture-command") }.exceptionOrNull())
            }
            runCurrent()
            assertEquals(1, relay.requests.size)
            request.cancelAndJoin(); runCurrent()
            assertTrue(result.await() is UnknownOutcomeException)
            assertEquals(LinkState.OFFLINE, client.state.value)
            assertEquals(1, relay.closes)
            val before = relay.attempts.size
            relay.writable(Int.MAX_VALUE); runCurrent()
            assertEquals(before, relay.attempts.size)
            assertEquals(1, relay.requests.size)
        } }
    }

    @Test fun wrapperDistinguishesAtomicAcceptanceBlockedAndTerminalState() {
        var writable = false
        var closes = 0
        var terminal = 0
        lateinit var callback: TransportListener
        val connector = BackpressureConnector(TransportConnector { _, listener ->
            callback = listener
            object : BinaryTransport {
                override fun send(bytes: ByteArray) = writable
                override fun close(graceful: Boolean) { closes++ }
            }
        })
        val config = ConnectionConfig("wss://fixture.invalid", "fixture", "fixture", "00".repeat(32))
        val transport = connector.open(config, object : TransportListener {
            override fun onOpen() {}
            override fun onBinary(bytes: ByteArray) {}
            override fun onFailure() { terminal++ }
        }) as WritableBinaryTransport
        assertEquals(TransportSendResult.WOULD_BLOCK, transport.trySend(byteArrayOf(1)))
        writable = true
        assertEquals(TransportSendResult.ACCEPTED, transport.trySend(byteArrayOf(1)))
        callback.onFailure(); callback.onFailure()
        assertEquals(1, terminal)
        assertEquals(TransportSendResult.CLOSED, transport.trySend(byteArrayOf(1)))
        transport.close(false)
        assertEquals(1, closes)
        assertEquals(TransportSendResult.CLOSED, transport.trySend(byteArrayOf(1)))
    }
}
