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
import java.security.MessageDigest

/** Read-only authentication probe. Report only fingerprints and fixed protocol error codes. */
@RunWith(AndroidJUnit4::class)
class LiveConnectionTest {
    @Test fun installedIdentityAndAuthentication() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveConnection")=="true")
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        fun hash(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString(""){"%02x".format(it)}
        val facts=linkedMapOf<String,JsonElement>()
        var stage="stored_identity"
        val client=PocketClient()
        try {
            val saved=PairStore(ctx).load()?:error("Not paired")
            facts["computerId"]=JsonPrimitive(saved.computerId)
            facts["deviceFingerprint"]=JsonPrimitive(hash(saved.deviceId+"|"+hash(saved.deviceSecret)))
            facts["connectionFingerprint"]=JsonPrimitive(hash(saved.config.room+"|"+saved.config.certificateSha256.lowercase()+"|"+saved.computerId))
            runBlocking { withTimeout(60_000) {
                stage="encrypted_transport"
                client.connect(saved.config)
                facts["encryptedTransport"]=JsonPrimitive(true)
                stage="device_authentication"
                val hello=client.request("hello",buildJsonObject{put("protocol",1);put("deviceId",saved.deviceId);put("deviceSecret",saved.deviceSecret)}).asObject()
                check(hello.string("computerId")==saved.computerId)
                facts["authenticated"]=JsonPrimitive(true)
                facts["runtimeCount"]=JsonPrimitive(hello.array("runtimes").size)
                stage="synchronization"
                client.request("sync",buildJsonObject{put("cursors",buildJsonObject{})})
                facts["synchronized"]=JsonPrimitive(true)
                stage="complete"
            } }
        }catch(e:Exception){
            val code=(e as? WireException)?.code
            facts["errorCode"]=JsonPrimitive(if(code in setOf("unauthorized","revoked","device_revoked","authentication_failed","disconnected","transport_failed","timeout","invalid_params"))code else e.javaClass.simpleName)
        }finally{
            client.close()
            facts["stage"]=JsonPrimitive(stage)
            writeLiveReport("connection-report.json",JsonObject(facts))
        }
    }
}
