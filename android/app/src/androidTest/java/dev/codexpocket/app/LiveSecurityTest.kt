package dev.codexpocket.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses and revokes one disposable test identity. Never revokes the installed phone. */
@RunWith(AndroidJUnit4::class)
class LiveSecurityTest {
    @Test fun pinOneTimePairingAndRevocation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSecurity") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "integration/pairing.json")
        val owner = PocketClient()
        val spare = PocketClient()
        val wrongPin = PocketClient()
        val revoked = PocketClient()
        val repeat = PocketClient()
        var stage = "private pairing input"
        var disposableId: String? = null
        var revokedDone = false
        try {
            require(input.isFile && input.length() in 1..65_536)
            val code = PairingCode.parse(input.readText())
            require(input.delete())
            val installed = PairStore(context).load() ?: error("Installed paired identity required")
            runBlocking {
                withTimeout(120_000) {
                    stage = "valid owner authentication"
                    owner.connect(installed.config)
                    owner.request("hello", buildJsonObject { put("protocol", 1); put("deviceId", installed.deviceId); put("deviceSecret", installed.deviceSecret) })
                    stage = "wrong certificate pin"
                    check(runCatching { wrongPin.connect(installed.config.copy(certificateSha256 = "0".repeat(64))) }.isFailure)
                    stage = "disposable pairing"
                    val id = "mumu-security-" + UUID.randomUUID()
                    spare.connect(installed.config)
                    val paired = spare.request("pair", buildJsonObject { put("pairingToken", code.pairingToken); put("deviceId", id); put("deviceName", "MuMu security probe") }).asObject()
                    disposableId = id
                    val secret = paired.string("deviceSecret")
                    check(secret.isNotBlank())
                    spare.request("hello", buildJsonObject { put("protocol", 1); put("deviceId", id); put("deviceSecret", secret) })
                    stage = "one-time invitation reuse"
                    repeat.connect(installed.config)
                    val reuse = runCatching { repeat.request("pair", buildJsonObject { put("pairingToken", code.pairingToken); put("deviceId", "mumu-reuse-" + UUID.randomUUID()); put("deviceName", "Rejected reuse probe") }) }.exceptionOrNull()
                    check(reuse is WireException && reuse.code == "expired")
                    stage = "device revocation"
                    owner.request("devices/revoke", buildJsonObject { put("deviceId", id) }, commandId = UUID.randomUUID().toString())
                    revokedDone = true
                    revoked.connect(installed.config)
                    val refused = runCatching { revoked.request("hello", buildJsonObject { put("protocol", 1); put("deviceId", id); put("deviceSecret", secret) }) }.exceptionOrNull()
                    check(refused is WireException && refused.code in setOf("revoked", "unauthorized"))
                    stage = "owner remains valid"
                    check(owner.request("runtimes/list").asObject().array("data").isNotEmpty())
                    writeLiveReport("security-report.json", buildJsonObject {
                        put("passed", true); put("wrongPinRejected", true); put("reusedInvitationRejected", true)
                        put("revokedIdentityRejected", true); put("installedPhonePreserved", true)
                    })
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Live security failed at $stage (${failure.javaClass.simpleName}); sensitive diagnostics omitted")
        } finally {
            if (!revokedDone && disposableId != null) runBlocking {
                runCatching { withTimeout(10_000) { owner.request("devices/revoke", buildJsonObject { put("deviceId", disposableId) }, commandId = UUID.randomUUID().toString()) } }
            }
            input.delete()
            listOf(owner, spare, wrongPin, revoked, repeat).forEach { it.close() }
        }
    }
}
