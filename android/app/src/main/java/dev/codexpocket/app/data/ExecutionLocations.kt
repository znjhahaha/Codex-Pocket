package dev.codexpocket.app.data

import android.content.Context
import dev.codexpocket.wire.*
import java.util.UUID
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One repository owns one location for its entire lifetime, including late replies. */
internal class ExecutionLocations(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ExecutionLocationStore(context)
    private val db = PocketDatabase.create(context)
    private val gate = Mutex()
    private val repositories = linkedMapOf<String, PocketRepository>()
    private val selected = MutableStateFlow<PocketRepository?>(null)
    val active = selected.asStateFlow()
    val directory = store.directoryChanges
    private val failure = MutableStateFlow<String?>(null)
    val error = failure.asStateFlow()
    private val preparation = MutableStateFlow<String?>(null)
    val progress = preparation.asStateFlow()
    @Volatile private var foreground = false

    init {
        scope.launch {
            try {
                // Process-recovery pass occurs once, never when another live target opens.
                db.dao().markInterruptedUnknown()
                var catalog = store.directory()
                if (catalog.targets.isEmpty()) {
                    store.add(ExecutionTarget(UUID.randomUUID().toString(), ExecutionTargetKind.Computer, "电脑"))
                    catalog = store.directory()
                }
                activate(catalog.selectedId ?: catalog.targets.first().id)
                directory.collect { latest ->
                    if (latest.targets.isEmpty()) {
                        store.add(ExecutionTarget(UUID.randomUUID().toString(), ExecutionTargetKind.Computer, "电脑"))
                    } else if (latest.selectedId != null && repositories[latest.selectedId] !== selected.value) {
                        activate(checkNotNull(latest.selectedId))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure.value = "执行位置暂时无法读取，已有配对与会话数据已保留。" }
        }
    }

    fun setForeground(value: Boolean) { foreground = value; selected.value?.setForeground(value) }
    fun select(id: String) = scope.launch {
        try { activate(id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure.value = "暂时无法切换执行位置，请重试。" }
    }
    private suspend fun activate(id: String) = gate.withLock {
        val target = store.directory().targets.firstOrNull { it.id == id } ?: error("Unknown location")
        val repository = repositories[id] ?: createRepository(target).also { repositories[id] = it }
        store.select(id)
        val old = selected.value
        if (old !== repository) {
            old?.setForeground(false)
            selected.value = repository
            repository.setForeground(foreground)
        }
        failure.value = null
    }
    private fun createRepository(target: ExecutionTarget): PocketRepository {
        val connector: (() -> PocketConnection)? = when (target.kind) {
            ExecutionTargetKind.Computer -> null
            ExecutionTargetKind.LinuxSsh -> {
                val config = SshTarget.parse(checkNotNull(store.connector(target.id)));
                { SshPocketConnection(config) }
            }
            else -> error("Execution location runtime has not been prepared")
        }
        return PocketRepository(context, connectionFactory = connector, pairStore = store.pairing(target.id),
            db = db, recoverInterruptedCommands = false)
    }
    fun addComputer() = scope.launch {
        try {
            val target = ExecutionTarget(UUID.randomUUID().toString(), ExecutionTargetKind.Computer, "新电脑")
            store.add(target)
            activate(target.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure.value = "新电脑未能保存，请重试。" }
    }
    fun dismissError() { failure.value = null }

    fun connectSsh(label: String, config: SshTarget) = scope.launch {
        if (preparation.value != null) return@launch
        preparation.value = "正在连接"
        try {
            val prepared = prepareSshHost(config, { architecture -> linuxBundle(architecture) }) { preparation.value = it }
            val connected = SshTarget(config.host, config.port, config.username, config.hostKey, config.authentication, prepared.backendPort)
            val code = prepared.pairing
            val connection = SshPocketConnection(connected)
            val pair = try {
                connection.connect(code.connectionConfig())
                val response = connection.request("pair", kotlinx.serialization.json.buildJsonObject {
                    put("pairingToken", kotlinx.serialization.json.JsonPrimitive(code.pairingToken))
                    put("deviceId", kotlinx.serialization.json.JsonPrimitive(UUID.randomUUID().toString()))
                    put("deviceName", kotlinx.serialization.json.JsonPrimitive(android.os.Build.MODEL.take(80)))
                }).asObject()
                require(response.string("computerId") == code.computerId)
                PairedComputer(code.connectionConfig(), response.string("deviceId"), response.string("deviceSecret"),
                    response.string("computerId"), response.string("computerName").ifBlank { label })
            } finally { connection.close() }
            val existing = store.directory().targets.firstOrNull { it.endpointId == pair.computerId }
            require(existing == null || existing.kind == ExecutionTargetKind.LinuxSsh)
            val target = ExecutionTarget(existing?.id ?: UUID.randomUUID().toString(), ExecutionTargetKind.LinuxSsh, label.ifBlank { config.host })
            store.register(target, pair, connected.toJson())
            activate(target.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure.value = when ((error as? WireException)?.code) {
            "ssh_host_key_changed" -> "主机密钥已变化，请核对服务器身份后重新连接。"
            "ssh_runtime_missing" -> "安装包尚未包含此架构的 Linux 运行环境。"
            "ssh_platform_unsupported" -> "目前仅支持 Linux SSH 主机。"
            "ssh_connection_failed" -> "SSH 登录失败，请检查地址、用户名和认证信息。"
            else -> "远程环境准备未完成，请重试。已有远程会话与配对数据会保留。"
        } }
        finally { preparation.value = null }
    }

    private suspend fun linuxBundle(architecture: String): LinuxRuntimeBundle = withContext(Dispatchers.IO) {
        require(architecture in setOf("x86_64", "aarch64"))
        val path = "linux-runtime/$architecture"
        val names = context.assets.list(path).orEmpty().toSet()
        if (!names.containsAll(listOf("runtime-manifest.json", "runtime-manifest.sig", "pocket-agent", "pocket-runtime-host")))
            throw WireException("ssh_runtime_missing", "Linux package is missing")
        val directory = File(context.filesDir, "linux-runtime/${dev.codexpocket.app.BuildConfig.VERSION_NAME}/$architecture").apply { mkdirs() }
        for (name in listOf("runtime-manifest.json", "runtime-manifest.sig", "pocket-agent", "pocket-runtime-host")) {
            context.assets.open("$path/$name").use { input -> File(directory, name).outputStream().use { output -> input.copyTo(output) } }
        }
        LinuxRuntimeBundle.verified(directory)
    }

    suspend fun openChat(key: ThreadKey) {
        val target = store.directory().targets.firstOrNull { it.endpointId == key.computerId } ?: return
        activate(target.id)
        selected.value?.openChat(key)?.join()
    }
}
