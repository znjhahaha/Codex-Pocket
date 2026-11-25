package dev.codexpocket.app.data

import android.content.Context
import dev.codexpocket.app.LocalRuntimeService
import dev.codexpocket.wire.PairingCode
import dev.codexpocket.wire.WireException
import java.io.BufferedReader
import java.io.File
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Project directories in this phone's workspace, and what the last import or export came to. */
internal data class LocalProjects(val names: List<String> = emptyList(), val busy: Boolean = false, val notice: String? = null)

internal sealed interface LocalAccount {
    data object Unknown : LocalAccount
    data object Checking : LocalAccount
    data object SignedOut : LocalAccount
    /** The person enters [code] at [url] in a browser; nothing is sent from here. */
    data class Code(val url: String, val code: String) : LocalAccount
    data class SignedIn(val label: String) : LocalAccount
    data class Failed(val reason: String) : LocalAccount
}

/** Where the packaged executables are and where this phone's own Codex data lives. */
internal class LocalRuntimeLayout(val libraries: File, val root: File) {
    var runtime: File = libraries
        private set
    fun useRuntime(directory: File) { runtime = directory }
    val agent get() = File(runtime, "libpocket-agent.so")
    val codex get() = File(runtime, "libcodex.so")
    val home = File(root, "home")
    val codexHome = File(home, ".codex")
    val projects = File(home, "projects")
    val state = File(root, "agent")
    val temporary = File(root, "tmp")
    val trustRoots = File(root, "trust-roots.pem")
    val backendPort = File(root, "backend-port")
    val available: Boolean get() = REQUIRED.all { File(libraries, it).isFile }
    val bootstrapAvailable: Boolean get() = BOOTSTRAP_FILES.all { File(libraries, it).isFile }

    /** The static executables read none of Android's own configuration, so everything they need is named here. */
    fun environment(proxyUrl: String, provider: LocalProvider? = null): Map<String, String> = mapOf(
        "HOME" to home.path, "CODEX_HOME" to codexHome.path, "TMPDIR" to temporary.path,
        "CODEX_CLI_PATH" to codex.path, "SHELL" to "/bin/bash",
        "PATH" to "/opt/python/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin",
        "PROOT_TMP_DIR" to temporary.path, "PROOT_LOADER" to File(libraries, "libproot-loader.so").path,
        "LD_LIBRARY_PATH" to libraries.path, "LANG" to "C.UTF-8", "TERM" to "xterm-256color",
        // The agent names itself from this when it is first set up.
        "COMPUTERNAME" to "此手机",
        "SSL_CERT_FILE" to trustRoots.path, "GIT_SSL_CAINFO" to trustRoots.path, "NODE_EXTRA_CA_CERTS" to trustRoots.path,
        "HTTPS_PROXY" to proxyUrl, "https_proxy" to proxyUrl, "HTTP_PROXY" to proxyUrl, "http_proxy" to proxyUrl,
        "NO_PROXY" to "127.0.0.1,localhost,::1", "no_proxy" to "127.0.0.1,localhost,::1") +
        // Codex reads the key of the service in use from here; it is in no file Codex or its commands can open.
        (provider?.let { mapOf(LocalCodexConfig.KEY_VARIABLE to it.apiKey) } ?: emptyMap())

    fun prepare(trustRootsPem: String, provider: LocalProvider? = null) {
        for (directory in listOf(root, home, codexHome, projects, state, temporary)) {
            directory.mkdirs()
            check(directory.isDirectory) { "Local runtime storage is unavailable" }
        }
        val config = File(codexHome, "config.toml")
        // Codex and the person may change the rest; only the lines that name the service in use are Pocket's.
        val current = if (config.exists()) config.readText() else null
        val wanted = LocalCodexConfig.apply(current ?: DEFAULT_CONFIG, provider)
        if (wanted != current) {
            val next = File(codexHome, "config.toml.new")
            next.writeText(wanted)
            java.nio.file.Files.move(next.toPath(), config.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val pending = File(root, "trust-roots.pem.new")
        pending.writeText(trustRootsPem)
        java.nio.file.Files.move(pending.toPath(), trustRoots.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        val RUNTIME_FILES = listOf("libpocket-agent.so", "libpocket-runtime-host.so", "libpocket-runtime-manifest.so", "libcodex.so", "libcodex-code-mode-host.so")
        val BOOTSTRAP_FILES = listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so")
        val REQUIRED = RUNTIME_FILES + BOOTSTRAP_FILES
        /** New installations request a workspace sandbox. Unsupported devices require an explicit permission choice. */
        const val DEFAULT_CONFIG = "sandbox_mode = \"workspace-write\"\napproval_policy = \"on-request\"\n"
        /** The backend answers health before its listener is bound, and a connection made then is refused. */
        fun listening(health: String): Boolean = runCatching {
            val report = Json.parseToJsonElement(health.lineSequence().last { it.startsWith("{") }).jsonObject
            report.getValue("routeStatus").jsonArray.any { route ->
                route.jsonObject["kind"]?.jsonPrimitive?.contentOrNull == "lan" && route.jsonObject["listenerReady"]?.jsonPrimitive?.booleanOrNull == true
            }
        }.getOrDefault(false)
        fun pem(certificates: Iterable<X509Certificate>): String = buildString {
            val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
            for (certificate in certificates) {
                append("-----BEGIN CERTIFICATE-----\n").append(encoder.encodeToString(certificate.encoded)).append("\n-----END CERTIFICATE-----\n")
            }
        }
    }
}

/** Runs the packaged agent, its runtime host and Codex as this application, on this phone only. */
internal class LocalRuntime(private val context: Context,
    val layout: LocalRuntimeLayout = LocalRuntimeLayout(File(context.applicationInfo.nativeLibraryDir.orEmpty()),
        // The agent refuses a state path that passes through a link, and /data/user/0 is one.
        File(context.filesDir.canonicalFile, "this-phone")),
    private val providers: LocalProviderStore = LocalProviderStore(EncryptedLocationBlob(context, "local_providers",
        "codex-pocket-local-providers-v1", "dev.codexpocket.app/local-providers/v1"))) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxy: LoopbackConnectProxy? = null
    private var agent: Process? = null
    private var sandboxVerified = false
    private var loginJob: Job? = null
    private var restartJob: Job? = null
    /** Set when the person stops Codex on this phone; nothing restarts it until they ask. */
    @Volatile private var stopped = false
    /** Only the person's explicit phone-location action may start or update a download. */
    @Volatile private var downloadRequested = false
    @Volatile private var signInCodex: Process? = null
    private val accountState = MutableStateFlow<LocalAccount>(LocalAccount.Unknown)
    val account = accountState.asStateFlow()
    private val projectState = MutableStateFlow(LocalProjects())
    val projects = projectState.asStateFlow()
    private val linux: PhoneLinuxEnvironment by lazy { PhoneLinuxEnvironment(layout, context.assets) { environmentProgress.value = it } }
    private val environmentProgress: MutableStateFlow<PhoneLinuxProgress?> = MutableStateFlow(linux.preview())
    val preparation = environmentProgress.asStateFlow()
    val available: Boolean get() = layout.available || (layout.bootstrapAvailable && linux.downloadable)
    /** The saved model services and which one is in use; none in use means OpenAI's own sign-in. */
    val access = providers.access
    private val modelDiscovery by lazy { LocalProviderModelDiscovery(providers) }
    suspend fun discoverProviderModels(request: LocalProviderModelRequest): LocalProviderModelResult = modelDiscovery.fetch(request)
    internal fun modelCatalog(): ModelCatalogRequest? {
        val captured = providers.active() ?: return null
        var current = captured
        return ModelCatalogRequest(isCurrent = { providers.active()?.configuration === captured.configuration },
            saved = captured.enabledModels.map { it.native(captured.model) }, load = {
            when (val result = modelDiscovery.fetch(LocalProviderModelRequest(captured.id, captured.baseUrl, captured.apiKey))) {
                is LocalProviderModelResult.Models -> {
                    current = providers.refreshCapabilities(captured, result.capabilities) ?: captured
                    current.enabledModels.map { it.native(current.model) }
                }
                is LocalProviderModelResult.Failed -> captured.enabledModels.map { it.native(captured.model) }
            }
        })
    }
    private val applyingState = MutableStateFlow(false)
    /** True while Codex here is being started again on another service. */
    val applying = applyingState.asStateFlow()
    /** Whether Codex here has anything to reach a model with, without starting it to ask. */
    val hasAccess: Boolean get() = providers.active() != null || File(layout.codexHome, "auth.json").isFile

    private fun environment(): Map<String, String> {
        val tunnel = proxy ?: LoopbackConnectProxy().also { proxy = it }
        return layout.environment(tunnel.url, providers.active()) +
            ("POCKET_PHONE_SANDBOX" to if (sandboxVerified) "verified" else "unavailable")
    }
    private fun command(executable: File, vararg arguments: String) = ProcessBuilder(phoneLinuxCommand(layout, executable, arguments.toList()))
        .directory(layout.home).apply { environment().putAll(this@LocalRuntime.environment()) }
    private fun prepareEnvironment(allowDownload: Boolean = false) {
        try {
            linux.prepare(allowDownload)
            layout.prepare(LocalRuntimeLayout.pem(systemTrustRoots()), providers.active())
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val message = when ((error as? WireException)?.code) {
                "local_environment_space" -> "手机空间不足，请腾出空间后重试"
                "local_environment_missing" -> "安装包缺少此手机的 Linux 工具环境"
                "local_environment_invalid" -> "Linux 环境未完成安装，原有目录已保留"
                "local_environment_download_required" -> "请点击在此手机上运行，下载手机环境"
                else -> "Linux 工具环境准备失败，请重试"
            }
            if (environmentProgress.value?.failed != true) environmentProgress.value = PhoneLinuxProgress(message, failed = true)
            throw error
        }
    }
    private fun agentCommand(vararg arguments: String, seconds: Long = 30): Pair<Int, String> {
        // To a file, so a full pipe can never stall the wait.
        val output = File.createTempFile("agent-", ".out", layout.temporary)
        try {
            val process = command(layout.agent, *arguments, "--state", layout.state.path).redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) { process.destroyForcibly(); throw WireException("local_runtime_unavailable", "Local backend did not answer") }
            return process.exitValue() to output.readText().take(65536)
        } finally { output.delete() }
    }
    private fun healthy() = runCatching {
        agentCommand("health", "--json", seconds = 10).let { (code, report) -> code == 0 && LocalRuntimeLayout.listening(report) }
    }.getOrDefault(false)
    private fun listening(port: Int) = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 150) }
    }.isSuccess

    /** Idempotent. Returns the loopback port the backend listens on. */
    @Synchronized fun start(): Int {
        if (!available) throw WireException("local_runtime_missing", "This package does not include the on-device runtime")
        if (stopped) throw WireException("local_runtime_stopped", "Codex on this phone was stopped")
        val download = downloadRequested
        downloadRequested = false
        val savedPort = layout.backendPort.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
        // Reentry reuses this owned process; the normal authenticated connection
        // still verifies protocol readiness. Do not spawn a health CLI per reconnect.
        if (agent?.isAlive == true && savedPort != null && listening(savedPort)) return savedPort
        prepareEnvironment(download)
        sandboxVerified = PhoneSandbox.verify(layout)
        if (!File(layout.state, "config.json").isFile) {
            val port = ServerSocket(0, 1, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
            val (code, _) = agentCommand("init", "--lan-bind", "127.0.0.1:$port", "--policy", "lan", "--json")
            if (code != 0) throw WireException("local_runtime_unavailable", "Local backend could not be initialised")
            layout.backendPort.writeText(port.toString())
        }
        val port = layout.backendPort.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw WireException("local_runtime_unavailable", "Local backend port is unknown")
        if (agent?.isAlive != true && !healthy()) {
            agent = command(layout.agent, "run", "--state", layout.state.path)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(File(layout.state, "host.log")))
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
            LocalRuntimeService.start(context)
        }
        repeat(150) {
            if (listening(port)) return port
            if (agent?.isAlive == false) throw WireException("local_runtime_unavailable", "Local backend exited before listening")
            Thread.sleep(200)
        }
        throw WireException("local_runtime_unavailable", "Local backend did not start")
    }

    /** A one-time code for this application to pair with its own backend. */
    @Synchronized fun pairing(): PairingCode {
        // Only connectThisPhone's explicit start/retry action calls pairing; selecting a saved location does not.
        downloadRequested = true
        start()
        if (agentCommand("pair", "--json").first != 0) throw WireException("local_runtime_unavailable", "Local pairing was refused")
        return PairingCode.parse(File(layout.state, "pairing-qr.json").readText())
    }

    /** Ends every process started from this package's executables; tasks they were running end with them. */
    @Synchronized fun resume() { stopped = false }
    @Synchronized fun stop() {
        stopped = true
        cancelSignIn(); cancelRestart()
        endProcesses()
        LocalRuntimeService.stop(context)
    }
    private fun cancelSignIn() { loginJob?.cancel(); signInCodex?.destroy() }
    private fun cancelRestart() { restartJob?.cancel(); restartJob = null; applyingState.value = false }
    private fun endProcesses() {
        agent?.destroy(); agent = null
        val prefixes = listOf(layout.libraries.path + "/", File(layout.root, "linux/runtimes").path + "/")
        File("/proc").listFiles { file -> file.name.all(Char::isDigit) }.orEmpty().forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val executable = runCatching { File(entry, "cmdline").readText().substringBefore('\u0000') }.getOrDefault("")
            if (pid != android.os.Process.myPid() && prefixes.any(executable::startsWith)) android.os.Process.killProcess(pid)
        }
        proxy?.close(); proxy = null
    }

    fun refreshProjects() { projectState.value = projectState.value.copy(names = LocalWorkspace.projects(layout.projects)) }
    /** Copies an archive the person picked into a new project directory here. */
    fun importProject(source: android.net.Uri) = transfer("导入") {
        val resolver = context.contentResolver
        val shown = resolver.query(source, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null }
        val name = LocalWorkspace.projectName(shown.orEmpty()) ?: throw IllegalArgumentException("Invalid project name")
        layout.projects.mkdirs()
        (resolver.openInputStream(source) ?: error("Archive could not be opened")).use { LocalWorkspace.importZip(it, layout.projects, name) }
        "已导入到 ${File(layout.projects, name).path}，新建项目时选择这个目录即可。"
    }
    /** Writes one project as an archive to the document the person picked. */
    fun exportProject(name: String, destination: android.net.Uri) = transfer("导出") {
        val project = File(layout.projects, requireNotNull(LocalWorkspace.projectName(name)))
        (context.contentResolver.openOutputStream(destination, "wt") ?: error("Document could not be opened")).use { LocalWorkspace.exportZip(project, it) }
        "已导出 $name。"
    }
    private fun transfer(action: String, work: () -> String) {
        if (projectState.value.busy) return
        projectState.value = projectState.value.copy(busy = true, notice = null)
        scope.launch {
            val notice = try { work() } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: IllegalArgumentException) { when (error.message) {
                    "A project with this name already exists" -> "已有同名项目，请先改名压缩包再导入。"
                    "Archive is too large", "Archive has too many files" -> "压缩包超过 512 MB 或 2 万个文件的上限。"
                    else -> "${action}未完成：压缩包或项目名称无效。"
                } }
                catch (_: Exception) { "${action}未完成，请重试。" }
            projectState.value = LocalProjects(LocalWorkspace.projects(layout.projects), false, notice)
        }
    }

    /** With a service of the person's own in use there is no OpenAI account to ask about. */
    fun refreshAccount() { if (providers.active() == null) signIn(null) else { cancelSignIn(); accountState.value = LocalAccount.Unknown } }
    fun signInWithChatGpt() = signIn(buildJsonObject { put("type", "chatgptDeviceCode") })
    fun signOut() = signIn(null, leaving = true)

    /** Saves a service and puts it in use. False when it could not be saved as typed. */
    fun saveProvider(draft: LocalProviderDraft): Boolean {
        if (runCatching { providers.save(draft) }.getOrNull() == null) return false
        restart(); return true
    }
    /** Null goes back to OpenAI's own sign-in. */
    fun useProvider(id: String?) { if (access.value.activeId != id && runCatching { providers.use(id) }.getOrDefault(false)) restart() }
    fun removeProvider(id: String) {
        val inUse = access.value.activeId == id
        if (runCatching { providers.remove(id) }.isSuccess && inUse) restart()
    }
    /** Codex reads its service and key when it starts, so a change ends what is running here and starts it again. */
    private fun restart() {
        cancelSignIn()
        accountState.value = LocalAccount.Unknown
        applyingState.value = true
        restartJob = scope.launch {
            try { synchronized(this@LocalRuntime) { endProcesses(); if (!stopped && File(layout.state, "config.json").isFile) start() } }
            catch (cancelled: CancellationException) { throw cancelled }
            // Left stopped, the location says it is offline and starts again when it is next opened.
            catch (_: Exception) { }
            if (restartJob === coroutineContext[Job]) { applyingState.value = false; refreshAccount() }
        }
    }

    /** Talks to a short-lived Codex of its own; credentials are written by Codex into its private home and never pass through Pocket. */
    private fun signIn(login: JsonObject?, leaving: Boolean = false) {
        cancelSignIn()
        accountState.value = LocalAccount.Checking
        loginJob = scope.launch {
            var codex: Process? = null
            try {
                synchronized(this@LocalRuntime) { prepareEnvironment() }
                codex = command(layout.codex, "app-server", "--listen", "stdio://").redirectError(ProcessBuilder.Redirect.appendTo(File(layout.temporary, "sign-in.log"))).start()
                signInCodex = codex
                val writer = codex.outputStream.bufferedWriter()
                val reader = codex.inputStream.bufferedReader()
                fun send(value: JsonObject) { writer.write(value.toString()); writer.write("\n"); writer.flush() }
                send(buildJsonObject { put("id", 1); put("method", "initialize")
                    putJsonObject("params") { putJsonObject("clientInfo") { put("name", "codex-pocket"); put("title", "Codex Pocket"); put("version", dev.codexpocket.app.BuildConfig.VERSION_NAME) } } })
                reply(reader, 1)
                send(buildJsonObject { put("method", "initialized") })
                if (login != null) {
                    send(buildJsonObject { put("id", 2); put("method", "account/login/start"); put("params", login) })
                    val started = reply(reader, 2)
                    if (started["type"]?.jsonPrimitive?.content == "chatgptDeviceCode") {
                        accountState.value = LocalAccount.Code(started.getValue("verificationUrl").jsonPrimitive.content, started.getValue("userCode").jsonPrimitive.content)
                        val completed = notification(reader, "account/login/completed")
                        if (completed["success"]?.jsonPrimitive?.booleanOrNull != true) throw WireException("local_sign_in_failed", "Sign-in was not completed")
                    }
                }
                if (leaving) { send(buildJsonObject { put("id", 4); put("method", "account/logout") }); reply(reader, 4) }
                send(buildJsonObject { put("id", 3); put("method", "account/read"); putJsonObject("params") { put("refreshToken", false) } })
                val current = reply(reader, 3)["account"] as? JsonObject
                accountState.value = when (current?.get("type")?.jsonPrimitive?.content) {
                    "chatgpt" -> LocalAccount.SignedIn(current["email"]?.jsonPrimitive?.contentOrNull ?: "ChatGPT")
                    "apiKey" -> LocalAccount.SignedIn("OpenAI API Key")
                    else -> LocalAccount.SignedOut
                }
                // The backend's Codex read its credentials when it started.
                if (leaving || (login != null && accountState.value is LocalAccount.SignedIn)) synchronized(this@LocalRuntime) {
                    codex.destroy(); endProcesses()
                    if (!stopped) runCatching { start() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isActive) accountState.value = LocalAccount.Failed(
                if (leaving) "未能退出登录，请重试" else if (login == null) "暂时无法读取登录状态" else "登录未完成，请重试") }
            finally { codex?.destroy() }
        }
    }
    private fun reply(reader: BufferedReader, id: Int): JsonObject {
        while (true) {
            val message = Json.parseToJsonElement(reader.readLine() ?: throw WireException("local_runtime_unavailable", "Codex ended")).jsonObject
            if (message["id"]?.jsonPrimitive?.intOrNull != id) continue
            if (message["error"] != null) throw WireException("local_sign_in_failed", "Codex refused the request")
            return message.getValue("result").jsonObject
        }
    }
    private fun notification(reader: BufferedReader, method: String): JsonObject {
        while (true) {
            val message = Json.parseToJsonElement(reader.readLine() ?: throw WireException("local_runtime_unavailable", "Codex ended")).jsonObject
            if (message["method"]?.jsonPrimitive?.contentOrNull == method) return message["params"] as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    private fun systemTrustRoots(): List<X509Certificate> {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        return store.aliases().toList().mapNotNull { store.getCertificate(it) as? X509Certificate }
    }
}
