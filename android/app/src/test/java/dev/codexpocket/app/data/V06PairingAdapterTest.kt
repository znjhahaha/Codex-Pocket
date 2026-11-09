package dev.codexpocket.app.data

import dev.codexpocket.wire.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V06PairingAdapterTest {
    private fun obj(text: String) = pocketJson.parseToJsonElement(text).jsonObject

    @Test fun legacyEncryptedShapeKeepsPairingIdentityAndGetsAutomaticPolicy() {
        val old = obj("""{"relayUrl":"wss://fixture.invalid/base","room":"room","phoneToken":"token","certificateSha256":"${"00".repeat(32)}",
            "deviceId":"phone","deviceSecret":"secret","computerId":"pc","computerName":"Fixture"}""")
        val pair = restoredPair(old)
        assertEquals("phone", pair.deviceId)
        assertEquals("secret", pair.deviceSecret)
        assertEquals("pc", pair.computerId)
        assertEquals(ConnectionPolicy.AUTO, pair.config.policy)
        assertEquals("wss://fixture.invalid/base/v1/tunnel/room/phone", pair.config.phoneUrl())
    }

    @Test fun newPairingRoutesRetainCompleteEndpointAndPublicPolicy() {
        val endpoint = "wss://fixture.invalid/prefix/v1/tunnel/room/phone"
        val code = PairingCode.parse("""{"v":1,"relayUrl":"wss://fixture.invalid/base","room":"room","phoneToken":"token",
            "certificateSha256":"${"00".repeat(32)}","pairingToken":"pair","expiresAt":5000000000,"computerName":"Fixture","computerId":"pc",
            "policy":"public","routes":[{"id":"public","kind":"cloudflare","url":"$endpoint","priority":1}]}""", nowSeconds = 1)
        val saved = PairedComputer(code.connectionConfig(), "phone", "secret", "pc", "Fixture")
        val restored = restoredPair(saved.persisted())
        assertEquals(endpoint, restored.config.routes.single().url)
        assertEquals("public", restored.config.policy.wireValue)
        assertEquals("pc", restored.config.computerId)
        assertEquals("phone", restored.deviceId)
        assertEquals("secret", restored.deviceSecret)
        assertEquals("token", restored.config.phoneToken)
    }
}
