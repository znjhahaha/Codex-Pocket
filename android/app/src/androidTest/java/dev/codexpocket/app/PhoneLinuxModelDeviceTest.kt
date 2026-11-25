package dev.codexpocket.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Uses the encrypted provider store, or an explicitly selected local CCS route over ADB reverse. */
class PhoneLinuxModelDeviceTest {
    @Test fun modelCallsToolsAndHonorsAnApprovalRejection() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val providers=LocalProviderStore(EncryptedLocationBlob(context,"local_providers",
            "codex-pocket-local-providers-v1","dev.codexpocket.app/local-providers/v1"))
        val localModel=InstrumentationRegistry.getArguments().getString("ccsModel")
        val provider=if(localModel!=null) {
            require(context.packageName.contains(".qa") && localModel.startsWith("ccs-"))
            // The loopback CCS proxy supplies its own upstream credential. This marker is not a key.
            LocalProvider("ccs-acceptance","CCS acceptance","http://127.0.0.1:15721/v1","cc-switch-local",localModel)
        } else providers.active() ?: error("No third-party model service configured in this QA application")
        val root=context.cacheDir.listFiles()?.firstOrNull { it.name.startsWith("linux-acceptance-") && File(it,"linux/rootfs/.pocket-environment.json").isFile }
            ?: File(context.cacheDir.canonicalFile,"linux-acceptance-${UUID.randomUUID()}")
        val layout=LocalRuntimeLayout(File(context.applicationInfo.nativeLibraryDir),root)
        val certificates=KeyStore.getInstance("AndroidCAStore").apply { load(null) }.let { store ->
            store.aliases().toList().mapNotNull { store.getCertificate(it) as? X509Certificate }
        }
        layout.prepare(LocalRuntimeLayout.pem(certificates),provider)
        PhoneLinuxEnvironment(layout,context.assets) {}.prepare()
        val project=File(layout.projects,"model-${UUID.randomUUID()}").apply { mkdirs() }
        val proxy=LoopbackConnectProxy()
        val stderr=File(root,"model.stderr")
        val codex=ProcessBuilder(phoneLinuxCommand(layout,layout.codex,listOf("app-server","--listen","stdio://")))
            .directory(layout.home).apply { environment().putAll(layout.environment(proxy.url,provider)) }
            .redirectError(stderr).start()
        val writer=codex.outputStream.bufferedWriter(); val reader=codex.inputStream.bufferedReader()
        val executor=Executors.newSingleThreadExecutor(); var approved=0; var declined=0; var completedTools=0
        var successfulTools=0; var answers42=0; var turnStatus:String?=null
        fun send(message:JsonObject) { writer.write(message.toString());writer.newLine();writer.flush() }
        fun next():JsonObject=executor.submit<JsonObject> { Json.parseToJsonElement(reader.readLine() ?: error("Native model process ended")).jsonObject }.get(120,TimeUnit.SECONDS)
        fun reply(id:Int):JsonObject { while(true) { val value=next();if(value["id"]?.jsonPrimitive?.intOrNull==id) { assertNull(value.toString(),value["error"]);return value.getValue("result").jsonObject } } }
        try {
            send(buildJsonObject { put("id",1);put("method","initialize");putJsonObject("params") {
                putJsonObject("clientInfo") { put("name","pocket-model-acceptance");put("version","1") }
                putJsonObject("capabilities") { put("experimentalApi",true) }
            } });reply(1);send(buildJsonObject { put("method","initialized") })
            send(buildJsonObject { put("id",2);put("method","thread/start");putJsonObject("params") {
                put("cwd",project.path);put("model",provider.model);put("sandbox","danger-full-access");put("approvalPolicy","untrusted")
            } })
            val thread=reply(2).getValue("thread").jsonObject.getValue("id").jsonPrimitive.content
            send(buildJsonObject { put("id",3);put("method","turn/start");putJsonObject("params") {
                put("threadId",thread);put("approvalPolicy","untrusted");putJsonObject("sandboxPolicy") { put("type","dangerFullAccess") }
                putJsonArray("input") { add(buildJsonObject {
                    put("type","text");put("text","In this temporary test project, use shell tools, not file-edit tools. First run: python3 -c 'print(6*7)' && node -e 'console.log(6*7)' && git init -q && printf pocket > model.txt . Then separately run: printf forbidden > rejected.txt . A user may decline that second command: do not retry it or write rejected.txt by another method. Finally report briefly.")
                    putJsonArray("text_elements") {}
                }) }
            } })
            val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4)
            var complete=false
            while(!complete && System.nanoTime()<deadline) {
                val event=next(); val method=event["method"]?.jsonPrimitive?.contentOrNull
                if(method=="item/commandExecution/requestApproval") {
                    val params=event.getValue("params").jsonObject
                    val command=params["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val cwd=params["cwd"]?.jsonPrimitive?.contentOrNull
                    val accept=cwd==project.path && !command.contains("rejected.txt")
                    if(accept) approved++ else declined++
                    send(buildJsonObject { put("id",event.getValue("id"));putJsonObject("result") { put("decision",if(accept) "accept" else "decline") } })
                } else if(method=="item/completed") {
                    val item=event["params"]?.jsonObject?.get("item")?.jsonObject
                    if(item?.get("type")?.jsonPrimitive?.content=="commandExecution") {
                        completedTools++
                        if(item["exitCode"]?.jsonPrimitive?.intOrNull==0) {
                            successfulTools++
                            answers42+=item["aggregatedOutput"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                .lineSequence().count { it.trim()=="42" }
                        }
                    }
                } else if(method=="turn/completed") {
                    complete=true
                    turnStatus=event["params"]?.jsonObject?.get("turn")?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
                }
                else if(event["id"]==JsonPrimitive(3)) assertNull(event.toString(),event["error"])
            }
            assertTrue("Native model turn did not finish",complete)
            assertEquals("Native model turn must finish successfully","completed",turnStatus)
            assertTrue("No shell tool succeeded",successfulTools>0)
            assertTrue("Python and Node must each return 42",answers42>=2)
            assertEquals("pocket",File(project,"model.txt").readText())
            assertTrue("Git did not initialize the test project",File(project,".git").isDirectory)
            assertFalse("Rejected write must not execute",File(project,"rejected.txt").exists())
            assertTrue("Expected approval and rejection round trips",approved>0 && declined>0)
            File(context.getExternalFilesDir(null),"phone-linux-model-acceptance.json").writeText(buildJsonObject {
                put("model",provider.model);put("approved",approved);put("declined",declined);put("completedTools",completedTools)
                put("successfulTools",successfulTools);put("answers42",answers42);put("turnStatus",turnStatus);put("uid",android.os.Process.myUid())
            }.toString())
        } finally { codex.destroy();executor.shutdownNow();proxy.close() }
    }
}
