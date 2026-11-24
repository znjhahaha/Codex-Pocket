package dev.codexpocket.app.data

import android.content.Context
import dev.codexpocket.wire.*
import java.util.UUID
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The two places where SSH leaves this process. */
internal interface SshGateway {
    suspend fun prepare(target: SshTarget, bundleFor: suspend (String) -> LinuxRuntimeBundle, progress: (String) -> Unit): SshPreparation
    fun open(target: SshTarget): PocketConnection
    object Network : SshGateway {
        override suspend fun prepare(target: SshTarget, bundleFor: suspend (String) -> LinuxRuntimeBundle, progress: (String) -> Unit) =
            prepareSshHost(target, bundleFor, progress)
        override fun open(target: SshTarget): PocketConnection = SshPocketConnection(target)
    }
}

/** The identity and data source displayed together, published only after activation is ready. */
internal data class ActiveExecutionLocation(val target: ExecutionTarget, val repository: PocketRepository)

/** One repository owns one location for its entire lifetime, including late replies. */
internal class ExecutionLocations(private val context: Context,
    private val store: ExecutionLocationStore = ExecutionLocationStore(context),
    private val db: PocketDatabase = PocketDatabase.create(context),
    private val ssh: SshGateway = SshGateway.Network) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repositories = linkedMapOf<String, PocketRepository>()
    private val selected = MutableStateFlow<PocketRepository?>(null)
    val active = selected.asStateFlow()
    private val activated = MutableStateFlow<ActiveExecutionLocation?>(null)
    val activeLocation = activated.asStateFlow()
    val directory = store.directoryChanges
    private val failure = MutableStateFlow<String?>(null)
    val error = failure.asStateFlow()
    private val preparation = MutableStateFlow<String?>(null)
    val progress = preparation.asStateFlow()
    /** What the user should know about a host that was added but is not all it could be. */
    private val advice = MutableStateFlow<String?>(null)
    val notice = advice.asStateFlow()
    @Volatile private var foreground = false
    val thisPhone by lazy { LocalRuntime(context) }
    private var recovered = false

    init {
        // The selected location is ready before the first frame, as the single repository always was.
        follow()
        scope.launch { directory.collect { follow() } }
    }
    /** A change only prompts a look: what is selected is read under the lock, never taken from an older emission. */
    @Synchronized private fun follow() {
        try { store.directory().let { activate(it.selectedId ?: it.targets.first().id) } }
        catch (_: Exception) { failure.value = "执行位置暂时无法读取，已有配对与会话数据已保留。" }
    }

    fun setForeground(value: Boolean) { foreground = value; selected.value?.setForeground(value) }
    fun select(id: String) = scope.launch {
        try {
            // Choosing this phone is the request to run Codex here again after it was stopped.
            if (store.directory().targets.firstOrNull { it.id == id }?.kind == ExecutionTargetKind.ThisPhone) thisPhone.resume()
            activate(id, dismissFailure = true)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure.value = "暂时无法切换执行位置，请重试。" }
    }
    @Synchronized private fun activate(id: String, dismissFailure: Boolean = false) {
        val catalog = store.directory()
        val target = catalog.targets.firstOrNull { it.id == id } ?: error("Unknown location")
        // A forgotten location's repository has already disconnected and reset itself.
        repositories.keys.retainAll(catalog.targets.mapTo(hashSetOf()) { it.id })
        val repository = repositories[id] ?: createRepository(target).also { repositories[id] = it }
        store.select(id)
        // A place added to pair another computer and left without pairing holds nothing to keep.
        catalog.selected?.takeIf { it.id != id && it.endpointId == null }?.let { store.remove(it.id) }
        val old = selected.value
        if (old !== repository) {
            old?.setForeground(false, retainConnection = false)
            // Appearance is the phone's, not a location's: carry it over rather than show what this one last saw.
            repository.reloadAppearance()
            selected.value = repository
            repository.setForeground(foreground)
        }
        // Directory selection is persisted earlier and can emit while activation is still busy.
        // UI consumers must take the target and repository from this one publication instead.
        activated.value = ActiveExecutionLocation(target, repository)
        // Directory emissions can race a failed user action; only an explicit success dismisses it.
        if (dismissFailure) failure.value = null
    }
    private fun createRepository(target: ExecutionTarget): PocketRepository {
        val connector: (() -> PocketConnection)? = when (target.kind) {
            ExecutionTargetKind.Computer -> null
            // Read when a connection is opened: preparing the host again may have saved other credentials.
            ExecutionTargetKind.LinuxSsh -> { { ssh.open(SshTarget.parse(checkNotNull(store.connector(target.id)))) } }
            ExecutionTargetKind.ThisPhone -> { { LoopbackPocketConnection { withContext(Dispatchers.IO) { thisPhone.start() } } } }
        }
        // Commands cut off by the last process end are marked once, by the first location to open.
        return PocketRepository(context, connectionFactory = connector, pairStore = store.pairing(target.id),
            db = db, recoverInterruptedCommands = !recovered,
            modelCatalogOverride = if (target.kind == ExecutionTargetKind.ThisPhone) thisPhone::modelCatalog else ({ null }))
            .also { recovered = true }
    }
    fun addComputer() = scope.launch {
        try {
            val target = ExecutionTarget(UUID.randomUUID().toString(), ExecutionTargetKind.Computer, "新电脑")
            store.add(target)
            activate(target.id, dismissFailure = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure.value = "新电脑未能保存，请重试。" }
    }
    fun dismissError() { failure.value = null }
    fun dismissNotice() { advice.value = null }

    fun connectSsh(label: String, config: SshTarget) = scope.launch {
        if (preparation.value != null) return@launch
        preparation.value = "正在连接"
        try {
            val prepared = ssh.prepare(config, { architecture -> linuxBundle(architecture) }) { preparation.value = it }
            val connected = SshTarget(config.host, config.port, config.username, config.hostKey, config.authentication, prepared.backendPort)
            val code = prepared.pairing
            val existing = store.directory().targets.firstOrNull { it.endpointId == code.computerId }
            require(existing == null || existing.kind == ExecutionTargetKind.LinuxSsh)
            val saved = existing?.let { store.pairing(it.id).load() }
            val connection = ssh.open(connected)
            val pair = try {
                connection.connect(code.connectionConfig())
                // A host accepts only a few devices: preparing it again keeps this phone's identity while it still signs in.
                val known = saved != null && try {
                    connection.request("hello", buildJsonObject {
                        put("protocol", 1); put("deviceId", saved.deviceId); put("deviceSecret", saved.deviceSecret)
                    }).asObject().string("computerId") == code.computerId
                } catch (_: WireException) { false }
                if (known) PairedComputer(code.connectionConfig(), saved!!.deviceId, saved.deviceSecret, saved.computerId, saved.computerName)
                else {
                    val response = connection.request("pair", buildJsonObject {
                        put("pairingToken", code.pairingToken); put("deviceId", UUID.randomUUID().toString())
                        put("deviceName", android.os.Build.MODEL.orEmpty().ifBlank { "Android" }.take(64))
                    }).asObject()
                    require(response.string("computerId") == code.computerId)
                    PairedComputer(code.connectionConfig(), response.string("deviceId"), response.string("deviceSecret"),
                        response.string("computerId"), response.string("computerName").ifBlank { label })
                }
            } finally { connection.close() }
            val target = ExecutionTarget(existing?.id ?: UUID.randomUUID().toString(), ExecutionTargetKind.LinuxSsh, label.ifBlank { config.host },
                existing?.endpointId)
            store.register(target, pair, connected.toJson())
            activate(target.id, dismissFailure = true)
            advice.value = listOfNotNull(
                "这台主机不允许后台在重启后自行启动。主机重启后，重新打开此位置即可恢复；在主机上执行一次 sudo loginctl enable-linger ${config.username} 可让后台随开机启动。"
                    .takeIf { !prepared.persistent },
                "这台主机上还没有找到 Codex。请先在主机上安装 Codex 命令行，再开始会话。".takeIf { !prepared.nativeInstalled },
                "主机上的 Codex 尚未登录。请在主机终端执行 codex login 后再开始会话；使用自有模型服务的主机可忽略。"
                    .takeIf { prepared.nativeInstalled && !prepared.nativeSignedIn },
            ).joinToString("\n\n").ifEmpty { null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure.value = when ((error as? WireException)?.code) {
            "ssh_host_key_changed" -> "主机密钥已变化，请核对服务器身份后重新连接。"
            "ssh_runtime_missing" -> "安装包尚未包含此架构的 Linux 运行环境。"
            "ssh_runtime_invalid" -> "安装包内的 Linux 运行环境未通过校验，未向主机安装任何内容。请重新安装 Codex Pocket。"
            "ssh_platform_unsupported" -> "目前仅支持 Linux SSH 主机。"
            "ssh_connection_failed" -> "SSH 登录失败，请检查地址、用户名和认证信息。"
            "ssh_upgrade_rolled_back" -> "新版本的远程后台未能启动，已恢复之前的版本，现有会话不受影响。"
            "ssh_host_start_failed" -> "远程后台未能启动，请稍后重试。"
            "ssh_upload_failed" -> "运行环境没有完整送达，请检查网络和主机磁盘空间后重试。"
            "ssh_remote_path_unsafe" -> "主机上的 Pocket 目录是符号链接，为安全起见没有继续安装。"
            else -> "远程环境准备未完成，请重试。已有远程会话与配对数据会保留。"
        } }
        finally { preparation.value = null }
    }

    /** Runs Codex on this phone and pairs this application with it over loopback. Sign-in is separate. */
    fun connectThisPhone() = scope.launch {
        if (preparation.value != null) return@launch
        preparation.value = "正在启动此手机上的运行环境"
        try {
            thisPhone.resume()
            val code = withContext(Dispatchers.IO) { thisPhone.pairing() }
            val port = withContext(Dispatchers.IO) { thisPhone.start() }
            val connection = LoopbackPocketConnection { port }
            val pair = try {
                connection.connect(code.connectionConfig())
                val response = connection.request("pair", kotlinx.serialization.json.buildJsonObject {
                    put("pairingToken", kotlinx.serialization.json.JsonPrimitive(code.pairingToken))
                    put("deviceId", kotlinx.serialization.json.JsonPrimitive(UUID.randomUUID().toString()))
                    put("deviceName", kotlinx.serialization.json.JsonPrimitive(android.os.Build.MODEL.take(80)))
                }).asObject()
                require(response.string("computerId") == code.computerId)
                PairedComputer(code.connectionConfig(), response.string("deviceId"), response.string("deviceSecret"),
                    response.string("computerId"), "此手机")
            } finally { connection.close() }
            val existing = store.directory().targets.firstOrNull { it.endpointId == pair.computerId }
            require(existing == null || existing.kind == ExecutionTargetKind.ThisPhone)
            val target = ExecutionTarget(existing?.id ?: UUID.randomUUID().toString(), ExecutionTargetKind.ThisPhone, "此手机")
            store.register(target, pair)
            activate(target.id, dismissFailure = true)
            thisPhone.refreshAccount()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure.value = when ((error as? WireException)?.code) {
            "local_runtime_missing" -> "这个安装包不包含手机本机运行环境。"
            else -> "此手机上的运行环境未能启动，请重试。已有本机会话与登录信息会保留。"
        } }
        finally { preparation.value = null }
    }
    /** Ends Codex on this phone until this location is selected again. Its sessions and sign-in stay. */
    fun stopThisPhone() = scope.launch { thisPhone.stop() }

    private suspend fun linuxBundle(architecture: String): LinuxRuntimeBundle = withContext(Dispatchers.IO) {
        require(architecture in setOf("x86_64", "aarch64"))
        val path = "linux-runtime/$architecture"
        val names = context.assets.list(path).orEmpty().toSet()
        if (!names.containsAll(listOf("runtime-manifest.json", "runtime-manifest.sig", "pocket-agent", "pocket-runtime-host")))
            throw WireException("ssh_runtime_missing", "Linux package is missing")
        val version = dev.codexpocket.app.BuildConfig.VERSION_NAME
        // Copies made by earlier versions of the app are of no further use.
        File(context.filesDir, "linux-runtime").listFiles()?.filter { it.name != version }?.forEach { it.deleteRecursively() }
        val directory = File(context.filesDir, "linux-runtime/$version/$architecture").apply { mkdirs() }
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
