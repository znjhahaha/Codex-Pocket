package dev.codexpocket.wire

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class PocketClient internal constructor(private val connector: TransportConnector) : PocketConnection {
    constructor() : this(WssConnector())

    private val gate = Any()
    private val mutableState = MutableStateFlow(LinkState.OFFLINE)
    override val state: StateFlow<LinkState> = mutableState.asStateFlow()
    @Volatile override var failureCode: String? = null
        private set
    /** Fixed local source for diagnostics; error mapping still uses "backpressure". */
    @Volatile var backpressureSource: String? = null
        private set
    private val notices = Channel<JsonObject>(64)
    /** One ordered consumer; overflow disconnects and closes the flow with an error. */
    override val notifications: Flow<JsonObject> = notices.receiveAsFlow()
    private val events = Channel<Event>(128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    private var started = false
    private var terminated = false
    private var closing = false
    private var socket: BinaryTransport? = null
    private val pending = mutableMapOf<String, Pending>()
    private var queuedBytes = 0L
    /** Any relay bytes, including another reply's fragments, prove the tunnel is still moving. */
    @Volatile private var lastInboundNanos = System.nanoTime()

    private class Pending(val id: String, val commandId: String?) {
        val result = CompletableDeferred<JsonElement>()
        var sent = false
    }
    private class BackpressureFailure(val source: String) : Exception("Local queue stalled")
    private sealed interface Event {
        data class Start(val config: ConnectionConfig) : Event
        data object Open : Event
        data class Bytes(val bytes: ByteArray) : Event
        data class Send(val pending: Pending, val bytes: ByteArray) : Event
        data object Close : Event
    }

    init {
        scope.launch {
            var tls: TlsPump? = null
            val frames = FrameCodec()
            try {
                for (event in events) when (event) {
                    is Event.Start -> {
                        tls = TlsPump(pinnedEngine(event.config.certificateSha256), { bytes ->
                            val transport = synchronized(gate) { socket }
                            if (transport?.send(bytes) != true) throw BackpressureFailure("transport_send")
                        }, { bytes -> frames.feed(bytes).forEach(::receiveMessage) }, {
                            synchronized(gate) {
                                if (!terminated && !closing) { mutableState.value = LinkState.ONLINE; ready.complete(Unit) }
                            }
                        }, { terminate("disconnected", graceful = true) })
                        val transport = connector.open(event.config, object : TransportListener {
                            override fun onOpen() { offer(Event.Open) }
                            override fun onBinary(bytes: ByteArray) {
                                if (bytes.size > 65536) { terminate("invalid_transport"); return }
                                lastInboundNanos = System.nanoTime()
                                // OkHttp invokes this on its dedicated reader, not the UI or
                                // TLS owner. Let the bounded queue drain instead of treating
                                // an ordinary burst of history fragments as a lost connection.
                                if (!events.trySend(Event.Bytes(bytes)).isSuccess) {
                                    try { runBlocking { withTimeout(5_000) { events.send(Event.Bytes(bytes)) } } }
                                    catch (_: Exception) { terminate("backpressure", source = "transport_input") }
                                }
                            }
                            override fun onFailure() { terminate("disconnected") }
                        })
                        synchronized(gate) { if (terminated) transport.close(false) else socket = transport }
                    }
                    Event.Open -> tls!!.begin()
                    is Event.Bytes -> tls!!.receive(event.bytes)
                    is Event.Send -> {
                        val send = synchronized(gate) {
                            queuedBytes -= event.bytes.size
                            if (!terminated && pending[event.pending.id] === event.pending) { event.pending.sent = true; true } else false
                        }
                        if (send) tls!!.write(event.bytes)
                    }
                    Event.Close -> { tls?.close(); terminate("closed", graceful = true) }
                }
            } catch (failure: Exception) {
                if (failure is BackpressureFailure) terminate("backpressure", source = failure.source)
                else terminate((failure as? WireException)?.code?.takeIf { it in setOf("backpressure", "invalid_frame", "invalid_reply", "invalid_notification", "invalid_transport", "disconnected") } ?: "transport_failed")
            }
        }
    }

    override suspend fun connect(config: ConnectionConfig) {
        synchronized(gate) {
            if (started || terminated || closing) throw WireException("closed", "Create a new client to connect")
            started = true; mutableState.value = LinkState.CONNECTING
            if (!events.trySend(Event.Start(config)).isSuccess) throw WireException("closed", "Connection unavailable")
        }
        try { withTimeout(30000) { ready.await() } }
        catch (_: TimeoutCancellationException) { terminate("timeout"); throw WireException("timeout", "Connection timed out") }
        catch (e: CancellationException) { terminate("closed"); throw e }
    }

    override suspend fun request(method: String, params: JsonObject, commandId: String?,
                                 timeoutMillis: Long): JsonElement {
        require(timeoutMillis > 0) { "Invalid request timeout" }
        require(method.isNotBlank() && method.length <= 256) { "Invalid request method" }
        require(commandId == null || commandId.isNotBlank()) { "Invalid command ID" }
        val nativeMethod = (params["method"] as? JsonPrimitive)?.content
        if (commandId == null && (method in MUTATIONS || (method == "codex/request" && nativeMethod !in NATIVE_READS)))
            throw WireException("invalid_request", "Mutating requests require a command ID")
        val item = Pending(UUID.randomUUID().toString(), commandId)
        val bytes = FrameCodec.encode(buildJsonObject {
            put("id", item.id); put("method", method)
            put("params", if (commandId == null) params else JsonObject(params + ("commandId" to JsonPrimitive(commandId))))
        })
        synchronized(gate) {
            if (terminated || closing || mutableState.value != LinkState.ONLINE) throw WireException("offline", "Tunnel is offline")
            if (pending.size >= 32 || queuedBytes + bytes.size > 32L * 1024 * 1024)
                throw WireException("backpressure", "Request capacity exceeded")
            pending[item.id] = item; queuedBytes += bytes.size
            if (!events.trySend(Event.Send(item, bytes)).isSuccess) {
                pending.remove(item.id); queuedBytes -= bytes.size
                terminate("backpressure", source = "transport_requests")
                throw WireException("backpressure", "Transport capacity exceeded")
            }
        }
        try {
            // The window measures silence, not transfer time: a multi-MB history page over a slow
            // home uplink keeps arriving for longer than its window. A hard cap still bounds it.
            val started = System.nanoTime()
            val cap = timeoutMillis * QUIET_TIMEOUT_CAP
            var window = timeoutMillis
            while (true) {
                withTimeoutOrNull(window) { item.result.await() }?.let { return it }
                val now = System.nanoTime()
                val elapsed = (now - started) / 1_000_000
                val quiet = (now - lastInboundNanos) / 1_000_000
                if (quiet >= timeoutMillis || elapsed >= cap) throw failed(item, "timeout")
                window = minOf(timeoutMillis - quiet, cap - elapsed).coerceAtLeast(1)
            }
        }
        catch (e: CancellationException) {
            if (synchronized(gate) { item.sent && commandId != null }) throw UnknownOutcomeException(commandId!!)
            throw e
        } finally { synchronized(gate) { pending.remove(item.id) } }
    }

    private fun failed(item: Pending, code: String): Exception = synchronized(gate) {
        pending.remove(item.id)
        if (item.sent && item.commandId != null) UnknownOutcomeException(item.commandId)
        else WireException(code, "Request did not complete")
    }

    private fun receiveMessage(message: JsonObject) {
        val id = message["id"]
        if (id != null) {
            val primitive = id as? JsonPrimitive
            if (primitive == null || !primitive.isString) throw WireException("invalid_reply", "Invalid reply")
            val item = synchronized(gate) { pending.remove(primitive.content) } ?: return // late response, never replay
            val error = message["error"]
            if (error != null) {
                val raw = (error as? JsonObject)?.get("code") as? JsonPrimitive
                // Only known protocol codes are safe to expose; messages and arbitrary codes may contain secrets.
                val code = raw?.content?.takeIf { it in SAFE_ERROR_CODES } ?: "remote_error"
                // A generic broker error can accompany a durable UNKNOWN native outcome.
                // Only explicit rejection codes prove it is safe to stop reconciling a mutation.
                val failure = if (item.commandId != null && code !in DEFINITE_REJECTIONS)
                    UnknownOutcomeException(item.commandId) else WireException(code, "Remote request failed")
                item.result.completeExceptionally(failure)
            } else if (message.containsKey("result")) item.result.complete(message.getValue("result"))
            else {
                item.result.completeExceptionally(failed(item, "invalid_reply"))
                throw WireException("invalid_reply", "Invalid reply")
            }
        } else {
            val method = message["method"] as? JsonPrimitive
            if (method == null || !method.isString || message["params"] !is JsonObject)
                throw WireException("invalid_notification", "Invalid notification")
            if (!notices.trySend(message).isSuccess) {
                // The TLS owner holds no gate here. A short repository/Room lock stall must
                // propagate pressure to the reader, without dropping a cursor or growing queues.
                // A sync reply behind this burst also waits; the deadline breaks that cycle
                // by failing closed, rather than waiting indefinitely for the reply's consumer.
                try { runBlocking { withTimeout(5_000) { notices.send(message) } } }
                catch (_: TimeoutCancellationException) { throw BackpressureFailure("wire_notifications") }
            }
        }
    }

    private fun offer(event: Event) {
        if (!events.trySend(event).isSuccess) terminate("backpressure", source = "transport_events")
    }

    private fun terminate(code: String, graceful: Boolean = false, source: String? = null) {
        val transport = synchronized(gate) {
            if (terminated) return
            failureCode = code
            backpressureSource = source
            terminated = true; mutableState.value = LinkState.OFFLINE
            ready.completeExceptionally(WireException(code, "Tunnel unavailable"))
            pending.values.toList().forEach { it.result.completeExceptionally(failed(it, code)) }
            pending.clear()
            events.cancel(); notices.close(if (code == "closed") null else WireException(code, "Tunnel closed"))
            socket.also { socket = null }
        }
        transport?.close(graceful)
        scope.cancel()
    }

    override fun close() {
        synchronized(gate) {
            if (terminated || closing) return
            closing = true; mutableState.value = LinkState.OFFLINE
            if (!started || !events.trySend(Event.Close).isSuccess) terminate("closed")
        }
    }

    private companion object {
        /** An actively streaming reply may take this many quiet windows in total. */
        const val QUIET_TIMEOUT_CAP = 6L
        val MUTATIONS = setOf("approvals/respond", "attachment/upload", "devices/revoke", "push/register", "push/unregister", "extensions/update", "thread/edit/revert",
            "session/preferences/update", "runtime/ensure", "thread/access/update", "thread/access/cancel", "thread/permissions/update")
        val NATIVE_READS = setOf("thread/read", "thread/list", "thread/loaded/list", "thread/turns/list", "thread/queue/list", "thread/items/list", "thread/goal/get", "model/list", "collaborationMode/list", "skills/list", "plugin/list")
        val DEFINITE_REJECTIONS = setOf("rejected", "unauthorized", "forbidden", "invalid_request", "invalid_params", "unsupported",
            "revoked", "expired", "conflict", "busy", "offline", "protocol_mismatch", "already_paired", "device_revoked", "authentication_failed", "unsupported_method", "method_not_found", "-32601",
            "runtime_offline", "runtime_unavailable", "thread_owned_elsewhere", "version_conflict", "operation_in_progress")
        val SAFE_ERROR_CODES = DEFINITE_REJECTIONS + setOf("request_timeout", "payload_too_large", "unknown", "rejected", "not_found", "unauthorized", "forbidden", "invalid_request", "invalid_params", "unsupported",
            "revoked", "expired", "conflict", "busy", "offline", "internal_error", "protocol_mismatch", "already_paired", "device_revoked", "authentication_failed", "unsupported_method", "method_not_found", "-32601")
    }
}
