package dev.codexpocket.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.wire.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class P2pProductDeviceTest {
    @Test fun realJniTlsDeviceHelloAndProductSwitch() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("explicit local fixture required", args.getString("p2pProduct") == "true")
        val input = Json.parseToJsonElement(File("/data/local/tmp/pocket-p2p-fixture.json").readText()).jsonObject
        val config = ConnectionConfig("wss://fixture.invalid", "fixture", "fixture-token", input.getValue("certificateSha256").jsonPrimitive.content,
            listOf(ConnectionRoute("fixture", "relay", "wss://fixture.invalid/phone", 9)), input.getValue("computerId").jsonPrimitive.content)
        val connectorType = Class.forName("dev.codexpocket.wire.TransportConnector")
        val factoryType = Class.forName("dev.codexpocket.wire.DirectCandidateFactory")
        val lan = Class.forName("dev.codexpocket.wire.LanConnector").getDeclaredConstructor(String::class.java)
            .newInstance("tls://127.0.0.1:19111")
        val nativeFactory = Class.forName("dev.codexpocket.wire.P2pCandidate").getField("INSTANCE").get(null)
        // Only the bootstrap is injected: it uses a real pinned TLS LAN stream over adb reverse.
        // Direct negotiation, JNI, original TLS, hello, 30s stability and switch are product code.
        val create: (ConnectionRoute) -> PocketConnection = {
            PocketClient::class.java.getDeclaredConstructor(connectorType, CoroutineDispatcher::class.java)
                .newInstance(lan, Dispatchers.IO) as PocketConnection
        }
        val routing = RoutingPocketConnection::class.java.getDeclaredConstructor(RouteManager::class.java,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, CoroutineDispatcher::class.java,
            factoryType, kotlin.jvm.functions.Function1::class.java)
            .newInstance(RouteManager(), 20000L, 30000L, Dispatchers.IO, nativeFactory, create) as RoutingPocketConnection
        routing.usePublicStun = false
        try {
            val hello = routing.connectAuthenticated(config, buildJsonObject {
                put("protocol",1); put("deviceId",input.getValue("deviceId"));put("deviceSecret",input.getValue("deviceSecret"))
            })
            assertEquals(config.computerId, hello["computerId"]?.jsonPrimitive?.content)
            assertTrue(hello["capabilities"]!!.jsonArray.any { it.jsonPrimitive.content == "p2p.v1" })
            assertEquals("relay",routing.activeRoute?.kind)
            val began = System.nanoTime()
            val switched = withTimeout(65_000) { routing.tryPublicDirect { candidate ->
                candidate.request("sync", buildJsonObject { put("cursors", buildJsonObject {}) })
            } }
            assertTrue("direct failure=${routing.directFailure}",switched)
            assertEquals("p2p",routing.activeRoute?.kind)
            assertNull(routing.relayVersion)
            assertNotNull(routing.request("ping"))
            assertNotNull(routing.request("sync", buildJsonObject { put("cursors", buildJsonObject {}) }))
            println("POCKET_P2P_DEVICE verified=true publicNAT=false publicSTUN=false stabilizationMillis=30000 elapsedMillis=${(System.nanoTime()-began)/1_000_000}")
        } finally { routing.close() }
    }
}
