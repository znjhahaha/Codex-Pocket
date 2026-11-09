package dev.codexpocket.wire

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PocketClientTest {
    @Test fun nativeQueueReadDoesNotRequireMutationCommandId() = runBlocking {
        Relay().use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            client.request("codex/request", buildJsonObject { put("runtimeId", "runtime"); put("method", "thread/queue/list"); put("params", buildJsonObject { put("threadId", "t"); put("limit", 100) }) })
            assertFalse(relay.requests.single()["params"]!!.jsonObject.containsKey("commandId"))
        } }
    }
    @Test fun largeFragmentedHistoryRemainsConnectedAndCanPing() = runBlocking {
        Relay(replyText = "x".repeat(512 * 1024)).use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            val result = client.request("codex/request", buildJsonObject { put("method", "thread/read") }, timeoutMillis = 15_000)
            assertEquals(512 * 1024, result.jsonPrimitive.content.length)
            assertEquals(LinkState.ONLINE, client.state.value)
            client.request("ping", timeoutMillis = 15_000)
            assertEquals(LinkState.ONLINE, client.state.value)
        } }
    }
    @Test fun slowButActiveReplyOutlastsItsQuietTimeout() = runBlocking {
        // ~1.2s of steady delivery against a 400ms request window: a slow uplink, not a dead link.
        Relay(replyText = "x".repeat(320 * 1024), chunk = 4096, pacingMillis = 15).use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            val started = System.nanoTime()
            val result = client.request("codex/request", buildJsonObject { put("method", "thread/read") }, timeoutMillis = 400)
            assertEquals(320 * 1024, result.jsonPrimitive.content.length)
            assertTrue((System.nanoTime() - started) / 1_000_000 > 400)
            assertEquals(LinkState.ONLINE, client.state.value)
        } }
    }
    @Test fun silentReplyStillTimesOutOnSchedule() = runBlocking {
        Relay(respond = false).use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            val started = System.nanoTime()
            val error = runCatching { client.request("threads/list", timeoutMillis = 300) }.exceptionOrNull()
            assertEquals("timeout", (error as WireException).code)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500)
        } }
    }
    internal class Relay(val respond: Boolean = true, val disconnect: Boolean = false, val errorCode: String? = null, val replyText: String = "ok",
        val chunk: Int = 23, val pacingMillis: Long = 0,
        val notificationsBeforeReply: List<JsonObject> = emptyList()) : TransportConnector, AutoCloseable {
        val cert = certificate()
        val config = ConnectionConfig("wss://relay.example/prefix", "room", "secret", fingerprint(cert))
        val requests = CopyOnWriteArrayList<JsonObject>()
        val encrypted = CopyOnWriteArrayList<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val input = Channel<ByteArray>(256)
        val cancelled = AtomicBoolean()
        lateinit var listener: TransportListener
        lateinit var server: TlsPump
        override fun open(config: ConnectionConfig, listener: TransportListener): BinaryTransport {
            this.listener = listener
            scope.launch {
                val frames = FrameCodec()
                server = TlsPump(serverEngine(cert), { bytes ->
                    bytes.toList().chunked(chunk).forEach {
                        if (pacingMillis > 0) Thread.sleep(pacingMillis)
                        listener.onBinary(it.toByteArray())
                    }
                }, { bytes ->
                    frames.feed(bytes).forEach { request ->
                        requests += request
                        if (disconnect) listener.onFailure()
                        else if (respond) {
                            val reply = buildJsonObject {
                                put("id", request.getValue("id"))
                                if (errorCode != null || request["method"]?.jsonPrimitive?.content == "error") put("error", buildJsonObject {
                                    put("code", errorCode ?: "not_found"); put("message", "SECRET_PAYLOAD")
                                }) else put("result", JsonPrimitive(replyText))
                            }
                            scope.launch { input.send(byteArrayOf()) } // response is sent below on owner
                            replies.addAll(notificationsBeforeReply)
                            replies.add(reply)
                        }
                    }
                }, {}, {})
                server.begin()
                listener.onOpen()
                for (bytes in input) {
                    if (bytes.isNotEmpty()) server.receive(bytes)
                    while (replies.isNotEmpty()) server.write(FrameCodec.encode(replies.removeAt(0)))
                }
            }
            return object : BinaryTransport {
                override fun send(bytes: ByteArray): Boolean { encrypted += bytes; return input.trySend(bytes).isSuccess }
                override fun close(graceful: Boolean) { cancelled.set(true); scope.cancel() }
            }
        }
        private val replies = CopyOnWriteArrayList<JsonObject>()
        suspend fun enqueueNotification(message: JsonObject) { replies.add(message); input.send(byteArrayOf()) }
        suspend fun enqueueManyNotifications(count: Int) {
            repeat(count) {
                enqueueNotification(buildJsonObject { put("method", "event"); put("params", buildJsonObject { put("n", it) }) })
                delay(5) // isolate the notification queue from the separately bounded TLS input queue
            }
        }
        override fun close() { scope.cancel() }
    }

    @Test fun encryptedRequestsCommandIdsAndNotifications() = runBlocking {
        Relay().use { relay ->
            PocketClient(relay).use { client ->
                client.connect(relay.config)
                assertEquals(LinkState.ONLINE, client.state.value)
                val note = async(start = CoroutineStart.UNDISPATCHED) { client.notifications.first() }
                relay.listener.onBinary(byteArrayOf()) // arbitrary empty stream chunk is harmless
                assertEquals(JsonPrimitive("ok"), client.request("codex/request", buildJsonObject { put("method", "turn/start") }, "command-1"))
                assertEquals("command-1", relay.requests.single()["params"]!!.jsonObject["commandId"]!!.jsonPrimitive.content)
                assertTrue(relay.requests.single()["id"]!!.jsonPrimitive.content.matches(Regex("[a-f0-9-]{36}")))
                assertFalse(relay.encrypted.any { String(it).contains("turn/start") })
                // Inject notifications through the actual server engine, on its owner.
                relay.enqueueNotification(buildJsonObject { put("method", "codex/event"); put("params", buildJsonObject {}) })
                assertEquals("codex/event", withTimeout(5000) { note.await() }["method"]!!.jsonPrimitive.content)
            }
            withTimeout(5000) { while (!relay.cancelled.get()) delay(5) }
        }
    }

    @Test fun mutationTimeoutAndDisconnectAreUnknownAndNeverReplayed() = runBlocking {
        for (disconnect in listOf(false, true)) Relay(respond = false, disconnect = disconnect).use { relay ->
            PocketClient(relay).use { client ->
                client.connect(relay.config)
                val error = try { client.request("codex/request", commandId = "stable-command", timeoutMillis = 200); null } catch (e: Exception) { e }
                assertTrue(error is UnknownOutcomeException)
                assertEquals("stable-command", (error as UnknownOutcomeException).commandId)
                delay(100)
                assertEquals(1, relay.requests.size)
            }
        }
    }

    @Test fun nonmutationFailuresAndRemoteErrorsAreSanitized() = runBlocking {
        Relay(respond = false).use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            val error = try { client.request("threads/list", timeoutMillis = 100); null } catch (e: Exception) { e }
            assertTrue(error is WireException)
        } }
        Relay().use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            val error = try { client.request("error"); null } catch (e: Exception) { e }
            assertTrue(error is WireException)
            assertEquals("not_found", (error as WireException).code)
            assertFalse(error.toString().contains("SECRET_PAYLOAD"))
        } }
        PocketClient().use { client ->
            val error = try { client.request("threads/list"); null } catch (e: Exception) { e }
            assertTrue(error is WireException)
        }
    }

    @Test fun genericMutationErrorsRetainUnknownOutcomeAndNeverReplay() = runBlocking {
        for (code in listOf("unknown", "not_found", "request_failed", "internal_error", "future_error")) Relay(errorCode = code).use { relay ->
            PocketClient(relay).use { client ->
                client.connect(relay.config)
                val error = runCatching { client.request("codex/request", buildJsonObject { put("method", "turn/start") }, "stable-id") }.exceptionOrNull()
                assertTrue("$code is not proof of rejection: $error", error is UnknownOutcomeException)
                assertEquals("stable-id", (error as UnknownOutcomeException).commandId)
                assertFalse(error.toString().contains("SECRET_PAYLOAD"))
                assertEquals(1, relay.requests.size)
            }
        }
    }

    @Test fun definiteMutationRejectionRemainsRejectedAndSanitized() = runBlocking {
        for (code in listOf("invalid_params", "rejected", "unauthorized", "expired")) Relay(errorCode = code).use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            val error = runCatching { client.request("devices/revoke", commandId = "rejected-id") }.exceptionOrNull()
            assertTrue(error is WireException)
            assertEquals(code, (error as WireException).code)
            assertFalse(error.toString().contains("SECRET_PAYLOAD"))
        } }
    }

    @Test fun briefNotificationConsumerStallPreservesOrderAndCanPing() = runBlocking {
        Relay(chunk = 65536).use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val received = mutableListOf<Int>()
            val consumer = async(start = CoroutineStart.UNDISPATCHED) {
                client.notifications.take(96).collect {
                    if (received.isEmpty()) { entered.complete(Unit); release.await() }
                    received += it["params"]!!.jsonObject["n"]!!.jsonPrimitive.int
                }
            }
            try {
                relay.enqueueManyNotifications(96)
                withTimeout(5000) { entered.await() }
                // More than the fixed 64 slots arrived while the consumer could not proceed.
                assertEquals(LinkState.ONLINE, client.state.value)
                release.complete(Unit)
                withTimeout(5000) { consumer.await() }
                assertEquals((0 until 96).toList(), received)
                client.request("ping", timeoutMillis = 5000)
                assertEquals(LinkState.ONLINE, client.state.value)
            } finally { release.complete(Unit); consumer.cancel() }
        } }
    }

    @Test fun notificationOverflowFailsClosedAndRetainsQueuedNotifications() = runBlocking {
        Relay(respond = false).use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            val mutation = async {
                runCatching { client.request("devices/revoke", commandId = "blocked-command", timeoutMillis = 15000) }.exceptionOrNull()
            }
            withTimeout(5000) { while (relay.requests.isEmpty()) delay(5) }
            relay.enqueueManyNotifications(65)
            withTimeout(8000) { client.state.first { it == LinkState.OFFLINE } }
            var received = 0
            val failure = try { client.notifications.collect { received++ }; null } catch (e: Exception) { e }
            assertEquals(64, received)
            assertTrue(failure is WireException)
            assertEquals("backpressure", client.failureCode)
            assertEquals("wire_notifications", client.backpressureSource)
            assertEquals("backpressure", (failure as WireException).code)
            assertTrue(withTimeout(1000) { mutation.await() } is UnknownOutcomeException)
            assertEquals(1, relay.requests.size)
        } }
    }

    @Test fun mutationWithoutCommandIdFailsBeforeSend() = runBlocking {
        Relay().use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            val failure = try { client.request("codex/request", buildJsonObject { put("method", "turn/start") }); null } catch (e: Exception) { e }
            assertTrue(failure is WireException)
            assertTrue(relay.requests.isEmpty())
        } }
    }

    @Test fun closeFailsPendingMutationUnknownAndDoesNotAllowReconnect() = runBlocking {
        Relay(respond = false).use { relay ->
            val client = PocketClient(relay)
            client.connect(relay.config)
            val result = async { try { client.request("attachment/upload", commandId = "cmd"); null } catch (e: Exception) { e } }
            withTimeout(5000) { while (relay.requests.isEmpty()) delay(5) }
            client.close()
            assertTrue(withTimeout(5000) { result.await() } is UnknownOutcomeException)
            assertEquals(LinkState.OFFLINE, client.state.value)
            val reconnect = try { client.connect(relay.config); null } catch (e: Exception) { e }
            assertTrue(reconnect is WireException)
        }
    }
}
