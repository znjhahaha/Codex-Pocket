package dev.codexpocket.wire

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RoutesTest {
    private val pin = "ab".repeat(32)
    private val public = ConnectionRoute("public", "relay", "wss://relay.example/prefix/v1/tunnel/room/phone", 5)
    private val lan = ConnectionRoute("lan", "lan", "tls://192.168.1.8:1234", 50)
    private fun config(routes: List<ConnectionRoute> = listOf(public, lan)) =
        ConnectionConfig("wss://relay.example/prefix", "room", "secret", pin, routes, "computer")

    @Test fun legacyAndExtendedSavedConfigRoundTripWithoutAppendingRoutePaths() {
        val old = ConnectionConfig.parse(buildJsonObject {
            put("relayUrl", "wss://relay.example/prefix"); put("room", "room"); put("phoneToken", "secret"); put("certificateSha256", pin)
        })
        assertTrue(old.routes.isEmpty()); assertEquals(ConnectionPolicy.AUTO, old.policy)
        assertEquals(public.url, old.phoneUrl())
        val extended = config().copy(policy = ConnectionPolicy.PUBLIC_ONLY)
        assertEquals(extended, ConnectionConfig.parse(extended.toJson()))
        assertEquals(public.url, ConnectionConfig.parse(extended.toJson()).routes.first().url)
    }

    @Test fun extendedPairingRetainsRoutesPolicyAndComputerIdentity() {
        val qr = JsonObject(config().toJson() + mapOf("v" to JsonPrimitive(1), "pairingToken" to JsonPrimitive("one-time"),
            "expiresAt" to JsonPrimitive(200), "computerName" to JsonPrimitive("Computer")))
        assertEquals(config(), PairingCode.parse(qr.toString(), 100).connectionConfig())
        for (value in listOf("auto", "lan_only", "PUBLIC", "", "arbitrary")) {
            assertThrows(IllegalArgumentException::class.java) { ConnectionConfig.parse(JsonObject(config().toJson() + ("policy" to JsonPrimitive(value)))) }
        }
    }

    @Test fun onlyPrivateLiteralLanEndpointsAndWssAreAccepted() {
        for (url in listOf("tls://10.0.0.8:443", "tls://172.16.0.1:12", "tls://192.168.3.8:20", "tls://127.0.0.1:1", "tls://[::1]:12", "tls://[fd12::1]:12")) {
            ConnectionRoute("lan", "lan", url, 0)
        }
        for (url in listOf("tls://8.8.8.8:12", "tls://0.0.0.0:12", "tls://localhost:12", "tls://192.168.01.1:12", "tls://127.1:12", "tls://[::]:12", "tls://[::ffff:8.8.8.8]:12", "tls://[ff02::1]:12", "tls://192.168.1.2", "tls://192.168.1.2:0", "tls://192.168.1.2:12/", "tls://192.168.1.2:12?token=secret", "tls://secret@192.168.1.2:12")) {
            assertThrows(url, IllegalArgumentException::class.java) { ConnectionRoute("lan", "lan", url, 0) }
        }
        for (url in listOf("ws://public.example", "wss://secret@public.example", "wss://public.example?token=secret")) {
            assertThrows(IllegalArgumentException::class.java) { ConnectionRoute("public", "relay", url, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { config(listOf(lan, lan)) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionRoute("bad", "lan", lan.url, -1) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionRoute("bad", "lan", lan.url, 0, -1) }
    }

    @Test fun expiryBackoffHysteresisAndPoliciesApplyAcrossClientReplacements() {
        var time = 100_000L
        val manager = RouteManager({ time / 1000 }, { time })
        manager.configure(config(listOf(public, lan.copy(expiresAt = 100))))
        assertEquals(listOf(public), manager.candidates())
        manager.updateRoutes(listOf(public, lan), networkGeneration = 0)
        assertEquals(lan, manager.candidates().first())
        manager.failed(lan)
        assertEquals(public, manager.candidates().first())
        manager.authenticated(public, "computer")
        time += 2000
        assertEquals(public, manager.candidates().first()) // public remains sticky despite LAN backoff expiring
        time += 30_000
        assertEquals(lan, manager.candidates().first())
        manager.updatePolicy(ConnectionPolicy.PUBLIC_ONLY)
        assertEquals(listOf(public), manager.candidates())
        manager.updatePolicy(ConnectionPolicy.LAN_ONLY)
        assertEquals(listOf(lan), manager.candidates())
    }

    @Test fun expiredExplicitPublicIsNotResurrectedAsLegacyFallback() {
        val manager = RouteManager({ 100 }, { 100_000 })
        manager.configure(config(listOf(public.copy(expiresAt = 100))))
        assertTrue(manager.candidates().isEmpty())
    }

    @Test fun discoveryIsLanOnlyAndStaleGenerationsCannotChangeSelection() {
        val manager = RouteManager()
        manager.configure(config(listOf(public)))
        assertTrue(manager.networkGenerationChanged(4))
        assertFalse(manager.updateDiscoveredRoutes(listOf(lan), 3))
        assertEquals(listOf(public), manager.candidates())
        assertTrue(manager.updateDiscoveredRoutes(listOf(lan), 4))
        assertEquals(lan, manager.candidates().first())
        assertThrows(IllegalArgumentException::class.java) { manager.updateDiscoveredRoutes(listOf(public), 4) }
        assertFalse(manager.networkGenerationChanged(2))
        assertFalse(manager.updateRoutes(listOf(public), networkGeneration = 3))
        manager.networkGenerationChanged(5)
        assertEquals(listOf(public), manager.candidates()) // old-network NSD candidates removed
    }
}
