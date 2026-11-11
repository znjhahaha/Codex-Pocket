package dev.codexpocket.wire

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

/** Signaling stays inside the already pinned, device-authenticated Pocket connection. */
internal interface DirectCandidateFactory {
    fun available(): Boolean
    suspend fun open(old: PocketConnection, config: ConnectionConfig, credentials: JsonObject,
                     generation: Long, useStun: Boolean): P2pCandidate.Opened
}
internal object P2pCandidate : DirectCandidateFactory {
    const val CAPABILITY = "p2p.v1"
    data class Opened(val client: PocketConnection, val route: ConnectionRoute, val hello: JsonObject)
    override fun available() = DataChannelTransport.available()
    override suspend fun open(old: PocketConnection, config: ConnectionConfig, credentials: JsonObject,
                     generation: Long, useStun: Boolean): Opened = coroutineScope {
        val id = UUID.randomUUID().toString()
        val nativeGeneration = generation + 1
        val envelope = buildJsonObject { put("negotiationId", id); put("networkGeneration", nativeGeneration) }
        val reply = old.request("p2p/start", JsonObject(envelope + ("useStun" to JsonPrimitive(useStun))), timeoutMillis = 5000).jsonObject
        checkEnvelope(reply, id, nativeGeneration)
        val stun = reply["stun"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        require(stun == null || (useStun && stun.length <= 256 && stun.matches(Regex("stun:[A-Za-z0-9.\\-:\\[\\]]+"))))
        val signals = Channel<DataChannelSignal>(256)
        val signalGate = NegotiationSignals(signals)
        val ready = CompletableDeferred<DataChannelTransport>()
        val client = PocketClient(TransportConnector { _, listener ->
            val peer = DataChannelTransport(nativeGeneration, listener,
                signal = { signalGate.send(it, listener::onFailure) },
                onWritable = listener::onWritable, stunUrl = stun)
            ready.complete(peer)
            if (!peer.start(false)) { peer.close(false); listener.onFailure() }
            peer
        })
        var accepted = false
        val sender = launch {
            for (signal in signals) {
                val wire = when (signal) {
                    is DataChannelSignal.Description -> buildJsonObject { put("kind", "description"); put("sdp", signal.sdp); put("type", signal.type) }
                    is DataChannelSignal.Candidate -> buildJsonObject { put("kind", "candidate"); put("candidate", signal.candidate); put("mid", signal.mid) }
                }
                old.request("p2p/signal", JsonObject(envelope + ("signals" to JsonArray(listOf(wire)))), timeoutMillis = 5000)
            }
        }
        val receiver = launch {
            val peer = ready.await()
            while (isActive) {
                val response = old.request("p2p/poll", envelope, timeoutMillis = 5000).jsonObject
                checkEnvelope(response, id, nativeGeneration)
                if (response["phase"]?.jsonPrimitive?.content == "ended") throw WireException("p2p_failed", "Direct candidate ended")
                val items = response["signals"]?.jsonArray ?: throw WireException("invalid_reply", "Missing signals")
                require(items.size <= 32)
                for (item in items) {
                    val value = item.jsonObject
                    val applied = when (value["kind"]?.jsonPrimitive?.content) {
                        "description" -> {
                            val sdp = value.getValue("sdp").jsonPrimitive.content
                            require(sdp.length in 1..65536 && value["type"]?.jsonPrimitive?.content == "offer")
                            peer.remoteDescription(nativeGeneration, sdp, "offer")
                        }
                        "candidate" -> {
                            val candidate = value.getValue("candidate").jsonPrimitive.content
                            val mid = value.getValue("mid").jsonPrimitive.content
                            require(candidate.length in 1..4096 && mid.length in 1..256)
                            peer.remoteCandidate(nativeGeneration, candidate, mid)
                        }
                        else -> false
                    }
                    if (!applied) throw WireException("p2p_failed", "Invalid signaling")
                }
            }
        }
        try {
            withTimeout(25_000) { client.connect(config) }
            accepted = true
            Opened(client, ConnectionRoute(id, "p2p", "p2p://$id", 1), JsonObject(credentials + mapOf(
                "p2pNegotiationId" to JsonPrimitive(id), "p2pNetworkGeneration" to JsonPrimitive(nativeGeneration))))
        } finally {
            signalGate.finish()
            sender.cancel(); receiver.cancel(); signals.cancel()
            if (!accepted) client.close()
        }
    }
    private fun checkEnvelope(value: JsonObject, id: String, generation: Long) {
        if (value["negotiationId"]?.jsonPrimitive?.content != id || value["networkGeneration"]?.jsonPrimitive?.longOrNull != generation)
            throw WireException("invalid_reply", "Stale signaling")
    }
}

/** Native ICE callbacks may outlive negotiation; only active overflow is fatal. */
internal class NegotiationSignals(private val channel: Channel<DataChannelSignal>) {
    private val gate = Any()
    private var active = true
    fun send(signal: DataChannelSignal, overflow: () -> Unit) = synchronized(gate) {
        if (active && !channel.trySend(signal).isSuccess) overflow()
    }
    fun finish() = synchronized(gate) { active = false }
}
