package dev.codexpocket.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.net.InetAddress
import java.net.ServerSocket
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Runs under the application's UID, with an isolated project/home and no model credentials. */
class PhoneLinuxDeviceTest {
    @Test fun packagedToolsAndNativeCodexExecuteAsTheApplication() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir.canonicalFile,"linux-acceptance-${UUID.randomUUID()}")
        val layout=LocalRuntimeLayout(File(context.applicationInfo.nativeLibraryDir),root)
        assertTrue("Install the on-device APK for this test",layout.available)
        layout.prepare("")
        PhoneLinuxEnvironment(layout,context.assets) {}.prepare()
        val output=File(root,"tools.log")
        val script="set -eu; cd '${layout.projects.path}'; bash --version | head -1; git --version; python3 -c 'print(6*7)'; node -e 'console.log(6*7)'; npm --version; git init -q; printf pocket > result.txt; git status --porcelain"
        val process=ProcessBuilder(phoneLinuxCommand(layout,File("/bin/bash"),listOf("-lc",script)))
            .directory(layout.projects).apply { environment().putAll(layout.environment("http://127.0.0.1:1")) }
            .redirectErrorStream(true).redirectOutput(output).start()
        assertTrue("Tool process timed out",process.waitFor(90,TimeUnit.SECONDS))
        val report=output.readText()
        assertEquals(report,0,process.exitValue())
        assertEquals("pocket",File(layout.projects,"result.txt").readText())
        assertTrue(report.contains("git version") && report.lines().count { it=="42" }==2)
        val nativeLog=File(root,"codex.stderr")
        val codex=ProcessBuilder(phoneLinuxCommand(layout,layout.codex,listOf("app-server","--listen","stdio://")))
            .directory(layout.home).apply { environment().putAll(layout.environment("http://127.0.0.1:1")) }
            .redirectError(nativeLog).start()
        val reader=codex.inputStream.bufferedReader(); val writer=codex.outputStream.bufferedWriter()
        val reads=Executors.newSingleThreadExecutor()
        fun send(message: JsonObject) { writer.write(message.toString());writer.newLine();writer.flush() }
        fun reply(id:Int):JsonObject = reads.submit<JsonObject> {
            var result:JsonObject?=null
            while(result==null) {
                val next=reader.readLine() ?: error("Codex ended: ${nativeLog.readText().take(2000)}")
                val message=Json.parseToJsonElement(next).jsonObject
                if(message["id"]?.jsonPrimitive?.intOrNull==id) result=message
            }
            result
        }.get(30,TimeUnit.SECONDS)
        try {
            send(buildJsonObject { put("id",1);put("method","initialize");putJsonObject("params") {
                putJsonObject("clientInfo") { put("name","pocket-linux-acceptance");put("version","1") }
            } })
            assertNull(reply(1)["error"])
            send(buildJsonObject { put("method","initialized") })
            send(buildJsonObject { put("id",2);put("method","command/exec");putJsonObject("params") {
                putJsonArray("command") { add("/bin/bash");add("-lc");add("python3 -c 'print(21*2)' && node -e 'console.log(21*2)' && printf native > native.txt") }
                put("cwd",layout.projects.path);putJsonObject("sandboxPolicy") { put("type","dangerFullAccess") }
            } })
            val response=reply(2)
            assertNull(response.toString(),response["error"])
            assertEquals("native",File(layout.projects,"native.txt").readText())
            File(context.getExternalFilesDir(null),"phone-linux-acceptance.json").writeText(buildJsonObject {
                put("abi",android.os.Build.SUPPORTED_ABIS.first());put("uid",android.os.Process.myUid())
                put("tools",report);put("nativeCommand",response);put("workspace",root.path)
            }.toString())
        } finally { codex.destroy();reads.shutdownNow() }
        verifyPackagedAgent(context, layout)
    }

    /** Exercise the shipped agent -> runtime-host -> official Codex chain as the app UID. */
    private fun verifyPackagedAgent(context: android.content.Context, layout: LocalRuntimeLayout) = runBlocking {
        fun command(vararg args: String): ProcessBuilder = ProcessBuilder(phoneLinuxCommand(layout, layout.agent, args.toList()))
            .directory(layout.home).apply { environment().putAll(layout.environment("http://127.0.0.1:1")) }
        fun once(vararg args: String) {
            val output = File(layout.temporary, "agent-check.out")
            val process = command(*args, "--state", layout.state.path).redirectErrorStream(true).redirectOutput(output).start()
            assertTrue("Packaged agent command timed out", process.waitFor(30, TimeUnit.SECONDS))
            assertEquals("Packaged agent command failed; private diagnostics retained", 0, process.exitValue())
        }
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        once("init", "--lan-bind", "127.0.0.1:$port", "--policy", "lan", "--json")
        val agent = command("run", "--state", layout.state.path).redirectErrorStream(true)
            .redirectOutput(File(layout.temporary, "agent-chain.log")).start()
        val connection = RoutingPocketConnection()
        try {
            withTimeout(30_000) {
                while (true) {
                    check(agent.isAlive) { "Packaged agent exited before listening" }
                    if (runCatching { java.net.Socket("127.0.0.1", port).use {} }.isSuccess) break
                    delay(200)
                }
            }
            once("pair", "--json")
            val code = PairingCode.parse(File(layout.state, "pairing-qr.json").readText())
            connection.connect(code.connectionConfig())
            val pair = connection.request("pair", buildJsonObject {
                put("pairingToken", code.pairingToken); put("deviceId", UUID.randomUUID().toString()); put("deviceName", "Isolated phone chain test")
            }).asObject()
            connection.request("hello", buildJsonObject {
                put("protocol", 1); put("deviceId", pair.string("deviceId")); put("deviceSecret", pair.string("deviceSecret"))
            })
            val events = launch { connection.notifications.collect {} }
            try {
                val runtime = withTimeout(45_000) {
                    var ready: JsonObject? = null
                    while (ready == null) {
                        ready = connection.request("runtimes/list").asObject().array("data").map { it.asObject() }
                            .firstOrNull { it.flag("online") && it.string("source") == "cli" }
                        if (ready == null) delay(500)
                    }
                    ready
                }
                val permissions = connection.request("thread/permissions/read", buildJsonObject {
                    put("runtimeId", runtime.string("id")); put("threadId", "policy-probe")
                }).asObject()
                assertEquals("应用隔离 · 按命令审批", permissions.string("currentLabel"))
                File(context.getExternalFilesDir(null), "phone-agent-chain-acceptance.json").writeText(buildJsonObject {
                    put("runtimeOnline", true); put("source", runtime.string("source")); put("version", BuildConfig.VERSION_NAME)
                    put("uid", android.os.Process.myUid()); put("permissionLabel", permissions.string("currentLabel"))
                }.toString())
            } finally { events.cancelAndJoin() }
        } finally { connection.close(); agent.destroy() }
    }
}
