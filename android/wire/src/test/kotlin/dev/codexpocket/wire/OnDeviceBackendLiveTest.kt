package dev.codexpocket.wire

import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The backend packaged for a phone, reached over a forwarded loopback port. Run only when
 * POCKET_DEVICE_QA names a directory holding the backend's `pairing-qr.json`. The backend's Codex
 * is expected to use a model service that answers a message containing RUNCMD with one shell
 * command that is not on Codex's safe list, and anything else with text.
 */
class OnDeviceBackendLiveTest {
    private val home = System.getenv("POCKET_DEVICE_QA")?.let(::File)
    private suspend fun wireFailure(block: suspend () -> Unit): String =
        try { block(); "none" } catch (failure: WireException) { failure.code }

    private class Session(val connection: PocketClient, val hello: JsonObject, private val events: List<JsonObject>) {
        /** Names the request that was refused; a bare code does not say which one it was. */
        suspend fun <T> step(name: String, block: suspend () -> T): T =
            try { block() } catch (failure: WireException) { throw AssertionError("$name: ${failure.code}", failure) }
        suspend fun event(method: String, matches: (JsonObject) -> Boolean = { true }): JsonObject = withTimeoutOrNull(60_000) {
            var found: JsonObject? = null
            while (found == null) { found = events.firstOrNull { it["method"] == JsonPrimitive(method) && matches(it.getValue("params").jsonObject) }; if (found == null) delay(200) }
            found
        } ?: throw AssertionError("No $method; saw ${events.map { it["method"] to it["params"].toString().take(240) }}")
        suspend fun say(native: JsonObject, thread: String, text: String): JsonObject = connection.native(native, "turn/start", mutation = true) {
            put("threadId", thread); put("clientUserMessageId", UUID.randomUUID().toString())
            put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
        }.getValue("turn").jsonObject
        suspend fun items(native: JsonObject, thread: String): List<JsonObject> =
            connection.native(native, "thread/read") { put("threadId", thread); put("includeTurns", true) }.getValue("thread").jsonObject
                .getValue("turns").jsonArray.last().jsonObject.getValue("items").jsonArray.map { it.jsonObject }
    }

    /** Pairs as a new phone, as the application does when "此手机" is added, then signs in with what pairing returned. */
    private fun paired(block: suspend CoroutineScope.(Session) -> Unit) = runBlocking {
        assumeTrue("POCKET_DEVICE_QA is not set", home != null)
        val code = PairingCode.parse(File(home!!, "pairing-qr.json").readText())
        val url = code.connectionConfig().routes.first { it.url.startsWith("tls://127.0.0.1:") }.url
        val saved = File(home, "paired.json")
        if (!saved.isFile) {
            val pairing = PocketClient(LanConnector(url))
            try {
                pairing.connect(code.connectionConfig())
                saved.writeText(pairing.request("pair", buildJsonObject {
                    put("pairingToken", code.pairingToken); put("deviceId", UUID.randomUUID().toString()); put("deviceName", "live test")
                }).toString())
            } finally { pairing.close() }
        }
        val identity = Json.parseToJsonElement(saved.readText()).jsonObject
        val connection = PocketClient(LanConnector(url))
        try {
            connection.connect(code.connectionConfig())
            val hello = connection.request("hello", buildJsonObject {
                put("protocol", 1); put("deviceId", identity.string("deviceId")); put("deviceSecret", identity.string("deviceSecret"))
            }).jsonObject
            val events = CopyOnWriteArrayList<JsonObject>()
            val collector = launch { connection.notifications.collect { if (it["method"] == JsonPrimitive("codex/event")) events += it.getValue("params").jsonObject.getValue("event").jsonObject } }
            block(Session(connection, hello, events))
            collector.cancel()
        } catch (failure: WireException) { throw AssertionError("${failure.code}: ${failure.message}", failure) }
        finally { connection.close() }
    }

    @Test fun pairedPhoneStartsASessionOnTheHostedRuntimeAndReadsTheModelsReply() = paired { session ->
        val connection = session.connection
        val capabilities = session.hello.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
        // A phone has no desktop Codex to follow, and says so, so the application never offers it.
        assertTrue("runtime.desktop.unavailable" in capabilities && "runtime.ensure.cli" in capabilities)
        assertEquals("cli", connection.request("session/preferences/read").jsonObject.string("defaultMode"))
        // The assistant is withheld, with its reason, while ordinary sessions run.
        val assistant = connection.request("assistant/read").jsonObject
        assertEquals(JsonPrimitive(false), assistant["ready"])
        assertEquals("assistant_unavailable_on_device", assistant.string("error"))
        assertEquals("runtime_unavailable", wireFailure {
            connection.request("assistant/settings/update", buildJsonObject { put("runtimeId", "x"); put("settings", buildJsonObject {}) }, UUID.randomUUID().toString())
        })
        // What carries the session is shown to the phone as the CLI runtime it knows how to use.
        val native = awaitNative(connection)
        assertTrue(connection.native(native, "model/list").getValue("data").jsonArray.isNotEmpty())
        val id = connection.native(native, "thread/start", mutation = true) {}.getValue("thread").jsonObject.string("id")
        val turn = session.say(native, id, "Say hi")
        session.event("turn/started") { it["threadId"] == JsonPrimitive(id) }
        val ended = session.event("turn/completed") { it["threadId"] == JsonPrimitive(id) }.getValue("params").jsonObject.getValue("turn").jsonObject
        assertEquals(turn.string("id"), ended.string("id"))
        assertEquals("completed", ended.string("status"))
        val reply = session.items(native, id).last { it.string("type") == "agentMessage" }.string("text")
        assertTrue(reply, reply.isNotBlank())
        println("on-device reply: $reply")
    }

    @Test fun aCommandOffTheSafeListWaitsForThePhoneAndRunsOnlyWhenAllowed() = paired { session ->
        val connection = session.connection
        val native = awaitNative(connection)
        suspend fun ask(decision: String): JsonObject {
            val id = session.step("thread/start") { connection.native(native, "thread/start", mutation = true) {} }.getValue("thread").jsonObject.string("id")
            // As the application does before anything can be typed.
            session.step("thread/read") { connection.native(native, "thread/read") { put("threadId", id); put("includeTurns", false) } }
            session.step("turn/start") { session.say(native, id, "RUNCMD") }
            // Codex's own sandbox does not exist on a phone; asking first is what stands in for it.
            val request = session.event("item/commandExecution/requestApproval") { it["threadId"] == JsonPrimitive(id) }
            assertTrue(JsonPrimitive(decision) in request.getValue("params").jsonObject.getValue("availableDecisions").jsonArray)
            session.step("respond $decision") { connection.request("approvals/respond", buildJsonObject {
                put("runtimeId", native.string("id")); put("requestId", request.getValue("id")); put("response", buildJsonObject { put("decision", decision) })
            }, UUID.randomUUID().toString()) }
            val command = session.event("item/completed") { it["threadId"] == JsonPrimitive(id) && it["item"]?.jsonObject?.string("type") == "commandExecution" }
                .getValue("params").jsonObject.getValue("item").jsonObject
            session.event("turn/completed") { it["threadId"] == JsonPrimitive(id) }
            return command
        }
        // Refusing ends the turn without the command having run.
        val refused = ask("cancel")
        assertEquals(refused.toString(), "declined", refused.string("status"))
        val allowed = ask("accept")
        assertEquals(allowed.toString(), "completed", allowed.string("status"))
        assertEquals(JsonPrimitive(0), allowed["exitCode"])
        println("on-device command: ${allowed.string("command")}")
    }
}
