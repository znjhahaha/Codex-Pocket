package dev.codexpocket.wire

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantReadClassificationTest {
    @Test fun artifactReadIsReadOnlyAndTakeoverRequiresDurableCommand() = runBlocking {
        PocketClientTest.Relay().use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            assertEquals("ok", client.request("assistant/artifact/read",buildJsonObject {put("artifactId","a");put("offset",0)}).jsonPrimitive.content)
            assertFalse(relay.requests.single()["params"]!!.jsonObject.containsKey("commandId"))
            assertTrue(runCatching {client.request("threads/takeover")}.exceptionOrNull() is WireException)
            assertEquals(1,relay.requests.size)
        } }
    }
    @Test fun projectPermissionReadTraversesPinnedTlsWithoutMutationCommandId() = runBlocking {
        PocketClientTest.Relay().use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            val selector = buildJsonObject {
                put("runtimeId", "assistant-runtime")
                put("sourceRuntimeId", "desktop-runtime")
                put("sourceConfigStoreId", "source-store")
                put("threadId", "source-thread")
            }
            assertEquals("ok", client.request("assistant/project/read", selector).jsonPrimitive.content)
            val request = relay.requests.single()
            assertEquals("assistant/project/read", request["method"]!!.jsonPrimitive.content)
            assertEquals(selector, request["params"])
            assertFalse(request["params"]!!.jsonObject.containsKey("commandId"))
            assertEquals(LinkState.ONLINE, client.state.value)
        } }
    }

    @Test fun assistantMutationsAndUnrecognizedReadNamesStillRequireCommandId() = runBlocking {
        PocketClientTest.Relay().use { relay -> PocketClient(relay).use { client ->
            client.connect(relay.config)
            for (method in listOf("assistant/send", "assistant/respond", "assistant/schedule/create",
                "assistant/project/update", "assistant/projects/read", "assistant/project/read/extra")) {
                val error = runCatching { client.request(method) }.exceptionOrNull()
                assertTrue(method, error is WireException)
                assertEquals(method, "invalid_request", (error as WireException).code)
            }
            assertTrue(relay.requests.isEmpty())
            assertEquals("ok", client.request("assistant/send", commandId="send-once").jsonPrimitive.content)
            assertEquals("send-once", relay.requests.single()["params"]!!.jsonObject["commandId"]!!.jsonPrimitive.content)
        } }
    }
}
