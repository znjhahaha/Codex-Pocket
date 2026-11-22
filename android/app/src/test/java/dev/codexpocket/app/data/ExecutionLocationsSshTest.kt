package dev.codexpocket.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.wire.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Adding a Linux host, with the host itself replaced by a stand-in that pairs and signs devices in as the real one does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExecutionLocationsSshTest {
    private class Blob : LocationBlob {
        var text: String? = null
        override fun read() = text
        override fun write(value: String) { text = value }
    }
    private object NoLegacy : PairingStorage {
        override fun load(): PairedComputer? = null
        override fun save(pair: PairedComputer) = Unit
        override fun forget() = Unit
    }
    private class Link(private val reply: (String, JsonObject) -> JsonElement) : PocketConnection {
        override val state = MutableStateFlow(LinkState.OFFLINE)
        override val notifications = emptyFlow<JsonObject>()
        val methods = mutableListOf<String>()
        var closed = false
        override suspend fun connect(config: ConnectionConfig) { state.value = LinkState.ONLINE }
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            methods += method
            return reply(method, params)
        }
        override fun close() { closed = true; state.value = LinkState.OFFLINE }
    }
    private class Host(val computer: String = "linux-1") : SshGateway {
        var failure: Exception? = null
        var persistent = true; var installed = true; var signedIn = true
        var askForBundle = false
        val devices = mutableMapOf<String, String>()
        val links = mutableListOf<Link>()
        val opened = mutableListOf<SshTarget>()
        var prepared = 0
        override suspend fun prepare(target: SshTarget, bundleFor: suspend (String) -> LinuxRuntimeBundle, progress: (String) -> Unit): SshPreparation {
            progress("正在准备远程运行环境")
            failure?.let { throw it }
            if (askForBundle) bundleFor("x86_64")
            prepared++
            val code = PairingCode("", "room", "token", "ab".repeat(32), "invite-$prepared", System.currentTimeMillis() / 1000 + 600, "devbox",
                listOf(ConnectionRoute("lan", "lan", "tls://127.0.0.1:43999", 0)), computer, ConnectionPolicy.LAN_ONLY)
            return SshPreparation(code, 43999, installed, persistent, signedIn)
        }
        override fun open(target: SshTarget): PocketConnection = Link { method, params ->
            fun text(key: String) = (params[key] as JsonPrimitive).content
            when (method) {
                "hello" -> if (devices[text("deviceId")] == text("deviceSecret")) buildJsonObject { put("computerId", computer) }
                    else throw WireException("authentication_failed", "Unknown device")
                "pair" -> {
                    check(text("pairingToken") == "invite-$prepared" && text("deviceName").length in 1..64)
                    devices[text("deviceId")] = "secret-${devices.size + 1}"
                    buildJsonObject { put("deviceId", text("deviceId")); put("deviceSecret", devices.getValue(text("deviceId"))); put("computerId", computer); put("computerName", "devbox") }
                }
                else -> throw WireException("unsupported", method)
            }
        }.also { links += it; opened += target }
    }

    private lateinit var app: Application
    private lateinit var db: PocketDatabase
    private val owners = mutableListOf<Any>()
    private val store = ExecutionLocationStore(Blob(), NoLegacy)
    private val target = SshTarget("host.example", 2222, "dev", "SHA256:" + "A".repeat(43), SshAuthentication.Password("first-password"), 43821)
    private fun locations(host: Host) = ExecutionLocations(app, store, db, host).also(owners::add)

    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
    }
    @After fun release() = runBlocking<Unit> {
        (owners + owners.filterIsInstance<ExecutionLocations>().mapNotNull { it.active.value }).distinct().forEach { owner ->
            val scope = owner.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(owner) as CoroutineScope
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
        db.close()
    }

    @Test fun addingAHostPairsThisPhoneSavesHowToReachItAndSelectsIt() = runBlocking<Unit> {
        val host = Host()
        val locations = locations(host)
        locations.connectSsh("开发机", target).join()
        assertNull(locations.error.value)
        assertNull(locations.notice.value)
        assertNull(locations.progress.value)
        val added = store.directory().selected!!
        assertEquals(ExecutionTargetKind.LinuxSsh, added.kind)
        assertEquals("开发机", added.label)
        assertEquals("linux-1", added.endpointId)
        val saved = SshTarget.parse(store.connector(added.id)!!)
        assertEquals("the port the host really listens on, not the one asked for", 43999, saved.backendPort)
        assertEquals("host.example" to 2222, saved.host to saved.port)
        assertEquals(target.hostKey, saved.hostKey)
        val pair = store.pairing(added.id).load()!!
        assertEquals(host.devices[pair.deviceId], pair.deviceSecret)
        assertEquals(listOf("pair"), host.links.single().methods)
        assertTrue(host.links.single().closed)
        assertEquals(43999, host.opened.single().backendPort)
    }

    @Test fun preparingTheSameHostAgainKeepsThisPhonesIdentityUntilTheHostForgetsIt() = runBlocking<Unit> {
        val host = Host()
        val locations = locations(host)
        locations.connectSsh("开发机", target).join()
        val id = store.directory().selected!!.id
        val first = store.pairing(id).load()!!
        val changed = SshTarget(target.host, target.port, target.username, target.hostKey, SshAuthentication.Password("second-password"), 43821)
        locations.connectSsh("开发机 2", changed).join()
        assertNull(locations.error.value)
        assertEquals("one location for one computer", listOf(id), store.directory().targets.filter { it.kind == ExecutionTargetKind.LinuxSsh }.map { it.id })
        assertEquals("开发机 2", store.directory().selected!!.label)
        assertEquals(1, host.devices.size)
        assertEquals(listOf("hello"), host.links.last().methods)
        assertEquals(first.deviceId to first.deviceSecret, store.pairing(id).load()!!.let { it.deviceId to it.deviceSecret })
        assertEquals("second-password", (SshTarget.parse(store.connector(id)!!).authentication as SshAuthentication.Password).value)
        // Revoked on the host: the saved identity no longer signs in, so the phone pairs anew on the same connection.
        host.devices.clear()
        locations.connectSsh("开发机 2", changed).join()
        assertNull(locations.error.value)
        assertEquals(listOf("hello", "pair"), host.links.last().methods)
        assertNotEquals(first.deviceId, store.pairing(id).load()!!.deviceId)
        assertEquals(1, host.devices.size)
    }

    @Test fun everyKnownFailureIsExplainedAndNothingIsSaved() = runBlocking<Unit> {
        val host = Host()
        val locations = locations(host)
        val generic = "远程环境准备未完成，请重试。已有远程会话与配对数据会保留。"
        val expected = mapOf(
            "ssh_host_key_changed" to "主机密钥已变化，请核对服务器身份后重新连接。",
            "ssh_runtime_missing" to "安装包尚未包含此架构的 Linux 运行环境。",
            "ssh_runtime_invalid" to "安装包内的 Linux 运行环境未通过校验，未向主机安装任何内容。请重新安装 Codex Pocket。",
            "ssh_platform_unsupported" to "目前仅支持 Linux SSH 主机。",
            "ssh_connection_failed" to "SSH 登录失败，请检查地址、用户名和认证信息。",
            "ssh_upgrade_rolled_back" to "新版本的远程后台未能启动，已恢复之前的版本，现有会话不受影响。",
            "ssh_host_start_failed" to "远程后台未能启动，请稍后重试。",
            "ssh_upload_failed" to "运行环境没有完整送达，请检查网络和主机磁盘空间后重试。",
            "ssh_remote_path_unsafe" to "主机上的 Pocket 目录是符号链接，为安全起见没有继续安装。",
            "ssh_command_failed" to generic, "ssh_timeout" to generic, "something_new" to generic)
        for ((code, message) in expected) {
            host.failure = WireException(code, "first-password must never be shown")
            locations.connectSsh("开发机", target).join()
            assertEquals(code, message, locations.error.value)
            assertNull(locations.progress.value)
            locations.dismissError()
        }
        host.failure = java.io.IOException("connection reset while uploading")
        locations.connectSsh("开发机", target).join()
        assertEquals(generic, locations.error.value)
        assertEquals(0, host.prepared)
        assertTrue(host.links.isEmpty())
        assertTrue(store.directory().targets.none { it.kind == ExecutionTargetKind.LinuxSsh })
    }

    @Test fun aPackageBuiltWithoutTheLinuxRuntimeSaysSoInsteadOfInstallingAnything() = runBlocking<Unit> {
        assumeTrue("this build carries a Linux runtime", !File("../../.local/execution-runtime/assets/linux-runtime/x86_64").exists())
        val host = Host().apply { askForBundle = true }
        val locations = locations(host)
        locations.connectSsh("开发机", target).join()
        assertEquals("安装包尚未包含此架构的 Linux 运行环境。", locations.error.value)
        assertEquals(0, host.prepared)
        assertTrue(store.directory().targets.none { it.kind == ExecutionTargetKind.LinuxSsh })
    }

    @Test fun anAddedHostThatLacksSomethingSaysWhatToDoOnTheHost() = runBlocking<Unit> {
        val host = Host().apply { persistent = false; installed = false; signedIn = false }
        val locations = locations(host)
        locations.connectSsh("开发机", target).join()
        assertNull(locations.error.value)
        assertEquals(ExecutionTargetKind.LinuxSsh, store.directory().selected!!.kind)
        val notice = locations.notice.value!!
        assertTrue(notice, "sudo loginctl enable-linger dev" in notice && "还没有找到 Codex" in notice && "codex login" !in notice)
        locations.dismissNotice()
        assertNull(locations.notice.value)
        host.persistent = true; host.installed = true
        locations.connectSsh("开发机", target).join()
        assertEquals("主机上的 Codex 尚未登录。请在主机终端执行 codex login 后再开始会话；使用自有模型服务的主机可忽略。", locations.notice.value)
        host.signedIn = true
        locations.connectSsh("开发机", target).join()
        assertNull(locations.notice.value)
    }
}
