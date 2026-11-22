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
    val agent = File(libraries, "libpocket-agent.so")
    val codex = File(libraries, "libcodex.so")
    val home = File(root, "home")
    val codexHome = File(home, ".codex")
    val projects = File(home, "projects")
    val state = File(root, "agent")
    val temporary = File(root, "tmp")
    val trustRoots = File(root, "trust-roots.pem")
    val backendPort = File(root, "backend-port")
    val available: Boolean get() = REQUIRED.all { File(libraries, it).isFile }

    /** The static executables read none of Android's own configuration, so everything they need is named here. */
    fun environment(proxyUrl: String): Map<String, String> = mapOf(
        "HOME" to home.path, "CODEX_HOME" to codexHome.path, "TMPDIR" to temporary.path,
        "CODEX_CLI_PATH" to codex.path, "SHELL" to "/system/bin/sh", "PATH" to "/system/bin",
        // The agent names itself from this when it is first set up.
        "COMPUTERNAME" to "此手机",
        "SSL_CERT_FILE" to trustRoots.path, "HTTPS_PROXY" to proxyUrl, "https_proxy" to proxyUrl,
        "NO_PROXY" to "127.0.0.1,localhost,::1", "no_proxy" to "127.0.0.1,localhost,::1")

    fun prepare(trustRootsPem: String) {
        for (directory in listOf(root, home, codexHome, projects, state, temporary)) {
            directory.mkdirs()
            check(directory.isDirectory) { "Local runtime storage is unavailable" }
        }
        val config = File(codexHome, "config.toml")
        // Written once: Codex and the person may change it afterwards.
        if (!config.exists()) config.writeText(DEFAULT_CONFIG)
        val pending = File(root, "trust-roots.pem.new")
        pending.writeText(trustRootsPem)
        java.nio.file.Files.move(pending.toPath(), trustRoots.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        val REQUIRED = listOf("libpocket-agent.so", "libpocket-runtime-host.so", "libpocket-runtime-manifest.so", "libcodex.so")
        /** Codex's own command sandbox cannot be created inside an Android application, whose sandbox is the boundary here. The packaged agent makes each session ask before commands. */
        const val DEFAULT_CONFIG = "sandbox_mode = \"danger-full-access\"\n"
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
        File(context.filesDir.canonicalFile, "this-phone"))) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxy: LoopbackConnectProxy? = null
    private var agent: Process? = null
    private var loginJob: Job? = null
    /** Set when the person stops Codex on this phone; nothing restarts it until they ask. */
    @Volatile private var stopped = false
    @Volatile private var signInCodex: Process? = null
    private val accountState = MutableStateFlow<LocalAccount>(LocalAccount.Unknown)
    val account = accountState.asStateFlow()
    private val projectState = MutableStateFlow(LocalProjects())
    val projects = projectState.asStateFlow()
    val available: Boolean get() = layout.available

    private fun environment(): Map<String, String> {
        val tunnel = proxy ?: LoopbackConnectProxy().also { proxy = it }
        return layout.environment(tunnel.url)
    }
    private fun command(executable: File, vararg arguments: String) = ProcessBuilder(listOf(executable.path) + arguments)
        .directory(layout.home).apply { environment().putAll(this@LocalRuntime.environment()) }
    private fun agentCommand(vararg arguments: String, seconds: Long = 30): Pair<Int, String> {
        // To a file, so a full pipe can never stall the wait.
        val output = File.createTempFile("agent-", ".out", layout.temporary)
        try {
            val process = command(layout.agent, *arguments, "--state", layout.state.path).redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) { process.destroyForcibly(); throw WireException("local_runtime_unavailable", "Local backend did not answer") }
            return process.exitValue() to output.readText().take(65536)
        } finally { output.delete() }
    }
    private fun healthy() = runCatching { agentCommand("health", "--json", seconds = 10).first == 0 }.getOrDefault(false)

    /** Idempotent. Returns the loopback port the backend listens on. */
    @Synchronized fun start(): Int {
        if (!available) throw WireException("local_runtime_missing", "This package does not include the on-device runtime")
        if (stopped) throw WireException("local_runtime_stopped", "Codex on this phone was stopped")
        layout.prepare(LocalRuntimeLayout.pem(systemTrustRoots()))
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
        repeat(30) { if (healthy()) return port; Thread.sleep(1000) }
        throw WireException("local_runtime_unavailable", "Local backend did not start")
    }

    /** A one-time code for this application to pair with its own backend. */
    @Synchronized fun pairing(): PairingCode {
        start()
        if (agentCommand("pair", "--json").first != 0) throw WireException("local_runtime_unavailable", "Local pairing was refused")
        return PairingCode.parse(File(layout.state, "pairing-qr.json").readText())
    }

    /** Ends every process started from this package's executables; tasks they were running end with them. */
    @Synchronized fun resume() { stopped = false }
    @Synchronized fun stop() {
        stopped = true
        cancelSignIn()
        endProcesses()
        LocalRuntimeService.stop(context)
    }
    private fun cancelSignIn() { loginJob?.cancel(); signInCodex?.destroy() }
    private fun endProcesses() {
        agent?.destroy(); agent = null
        val prefix = layout.libraries.path + "/"
        File("/proc").listFiles { file -> file.name.all(Char::isDigit) }.orEmpty().forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val executable = runCatching { File(entry, "cmdline").readText().substringBefore('\u0000') }.getOrDefault("")
            if (pid != android.os.Process.myPid() && executable.startsWith(prefix)) android.os.Process.killProcess(pid)
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

    fun refreshAccount() = signIn(null)
    fun signInWithChatGpt() = signIn(buildJsonObject { put("type", "chatgptDeviceCode") })
    fun signInWithApiKey(key: String) = signIn(buildJsonObject { put("type", "apiKey"); put("apiKey", key) })

    /** Talks to a short-lived Codex of its own; credentials are written by Codex into its private home and never pass through Pocket. */
    private fun signIn(login: JsonObject?) {
        cancelSignIn()
        accountState.value = LocalAccount.Checking
        loginJob = scope.launch {
            var codex: Process? = null
            try {
                synchronized(this@LocalRuntime) { layout.prepare(LocalRuntimeLayout.pem(systemTrustRoots())) }
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
                send(buildJsonObject { put("id", 3); put("method", "account/read"); putJsonObject("params") { put("refreshToken", false) } })
                val current = reply(reader, 3)["account"] as? JsonObject
                accountState.value = when (current?.get("type")?.jsonPrimitive?.content) {
                    "chatgpt" -> LocalAccount.SignedIn(current["email"]?.jsonPrimitive?.contentOrNull ?: "ChatGPT")
                    "apiKey" -> LocalAccount.SignedIn("API Key")
                    else -> LocalAccount.SignedOut
                }
                // The backend's Codex read its credentials when it started.
                if (login != null && accountState.value is LocalAccount.SignedIn) synchronized(this@LocalRuntime) { codex.destroy(); endProcesses(); start() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isActive) accountState.value = LocalAccount.Failed(if (login == null) "暂时无法读取登录状态" else "登录未完成，请重试") }
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
