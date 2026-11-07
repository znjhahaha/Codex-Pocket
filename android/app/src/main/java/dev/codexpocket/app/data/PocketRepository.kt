package dev.codexpocket.app.data

import android.content.Context
import dev.codexpocket.app.R
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.room.withTransaction
import dev.codexpocket.wire.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

data class LocalAttachment(val id: String, val name: String, val mime: String) {
    fun json() = buildJsonObject { put("id", id); put("name", name); put("mime", mime) }
    companion object { fun from(o: JsonObject) = LocalAttachment(o.string("id"), o.string("name"), o.string("mime")) }
}
data class PocketState(
    val initialized: Boolean = false, val paired: Boolean = false, val online: Boolean = false,
    val connection: String = "Offline", val computerName: String = "", val computerId: String = "",
    val runtimes: List<RuntimeInfo> = emptyList(), val chats: List<ChatEntity> = emptyList(),
    val selected: ChatEntity? = null, val timeline: NativeTimeline = NativeTimeline(),
    val draft: DraftEntity? = null, val attachments: List<LocalAttachment> = emptyList(),
    val sharedAttachments: List<LocalAttachment> = emptyList(),
    val approvals: List<PendingApproval> = emptyList(), val commands: List<CommandEntity> = emptyList(),
    val approvalsFresh: Boolean = false,
    val models: List<NativeModel> = emptyList(), val model: String? = null, val effort: String? = null,
    val modelCatalogState: ModelCatalogState = ModelCatalogState.Idle, val modelCatalogError: String? = null,
    val pairingPhase: PairingPhase = PairingPhase.Idle,
    val modes: List<NativeMode> = emptyList(), val mode: String? = null,
    val devices: List<JsonObject> = emptyList(), val theme: String = "system", val watch: Boolean = false,
    val glassEffects: Boolean = true,
    val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val nextThreads: JsonElement? = null, val nextTurns: JsonElement? = null,
    val disabledMethods: Set<String> = emptySet(), val capabilities: Set<String> = emptySet(),
    val historyExhausted: Boolean = false, val historyLoading: Boolean = false,
    val conversationLoadState: ConversationLoadState = ConversationLoadState.Idle,
    val conversationLoadError: String? = null,
    val unknownHistoryInspected: Boolean = false, val stopping: Boolean = false,
    val sendBusy: Boolean = false, val submittingApprovalKeys: Set<String> = emptySet(),
    val nativeQueue: List<JsonObject> = emptyList(), val queueSupported: Boolean? = null,
    val connectionPhase: String = "offline", val connectionDetail: String = "",
    val lastSyncAt: Long = 0, val unreadKeys: Set<String> = emptySet(),
    val pendingQuestions: Map<String, List<JsonObject>> = emptyMap(),
    val advanced: AdvancedConversationState = AdvancedConversationState(),
    val pushConfigured: Boolean = dev.codexpocket.app.push.PushRegistration.configured, val pushStatus: String = "unconfigured",
) {
    val activeRuntime get() = runtimes.firstOrNull { it.id == selected?.runtimeId }
    val currentModel get() = if (model != null) models.firstOrNull { it.model == model } else models.firstOrNull { it.isDefault }
    fun supports(method: String, runtime: RuntimeInfo? = activeRuntime): Boolean {
        if (runtime == null || !runtime.online || "${runtime.id}:$method" in disabledMethods) return false
        val advertised = runtime.capabilities ?: capabilities
        return method in advertised || "native:$method" in advertised
    }
    val unresolvedCommands get() = commands.filter { (it.chatKey == selected?.key || it.chatKey == null) && CommandPolicy.blocksNewSend(it.status) }
    val uncertainSend get() = unresolvedCommands.isNotEmpty()
}

/** Turns per history page. Busy agent turns carry hundreds of commands; at 20 one page measured 7.6MB. */
internal const val HISTORY_FIRST_PAGE = 8
internal const val HISTORY_OLDER_PAGE = 10

class PocketRepository(
    private val context: Context,
    private val connectionFactory: () -> PocketConnection = { PocketClient() },
    private val pairStore: PairingStorage = PairStore(context),
    private val db: PocketDatabase = PocketDatabase.create(context),
    private val networkAvailable: Flow<Unit> = networkAvailability(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = db.dao()
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(PocketState(theme = preferences.getString("theme", "system") ?: "system", glassEffects = preferences.getBoolean("glassEffects", true)))
    val state: StateFlow<PocketState> = _state.asStateFlow()
    val alerts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val conversationAlerts = MutableSharedFlow<ConversationAlert>(extraBufferCapacity = 32)
    private var pair: PairedComputer? = null
    @Volatile private var client: PocketConnection? = null
    private var connectionJob: Job? = null
    private var foreground = false
    private var networkProbe: Job? = null
    @Volatile private var pendingNotification: String? = null
    private val eventLock = Mutex()
    private val actionLock = Mutex()
    private val draftLock = Mutex()
    private val draftGate = Any()
    private val unreadGate = Any()
    private val drafts = ConcurrentHashMap<ThreadKey, DraftEntity>()
    private val activeActions = AtomicInteger()
    private val selectionGeneration = AtomicLong()
    private val catalogGeneration = AtomicLong()
    private val fileListingGeneration = AtomicLong()
    private val fileReadGeneration = AtomicLong()
    private val connectionGeneration = AtomicLong()
    private val connectionDiagnostics = ConnectionDiagnostics()
    fun connectionDiagnostics(): List<ConnectionDiagnostic> = connectionDiagnostics.snapshot()
    private class HistoryCapture(val runtime: String) {
        val events = mutableListOf<JsonObject>()
        var characters = 0
        var overflow = false
        fun add(envelope: JsonObject) {
            if (overflow) return
            characters += envelope.toString().length
            if (events.size >= 2048 || characters > 1_000_000) { overflow = true; events.clear() }
            else events += envelope
        }
    }
    // Access only while holding eventLock. Captures do not block normal live reduction.
    private val historyCaptures = mutableMapOf<ThreadKey, HistoryCapture>()
    private val reconnectWakeup = Channel<Unit>(Channel.CONFLATED)
    private val directoryRefresh = Channel<Unit>(Channel.CONFLATED)
    private val directoryLock = Mutex()
    private data class DirectoryQuery(val search: String = "", val archived: Boolean = false)
    @Volatile private var directoryQuery = DirectoryQuery()
    private val queueRefresh = Channel<Unit>(Channel.CONFLATED)
    private val queueLock = Mutex()
    private val stoppingTurns = ConcurrentHashMap.newKeySet<String>()
    private val responding = ConcurrentHashMap.newKeySet<String>()
    private val approvalGate = Any()
    private val approvalGenerations = ConcurrentHashMap<String, Long>()
    private val approvalCommandGenerations = ConcurrentHashMap<String, Pair<String, Long>>()
    private val rejectedApprovalRefresh = ConcurrentHashMap<String, Long>()
    private val resolvedApprovalKeys = ConcurrentHashMap.newKeySet<String>()
    private val sending = Mutex()
    private val loadingHistory = ConcurrentHashMap.newKeySet<ThreadKey>()
    private val reconciliationLock = Mutex()
    private var inspectedChat: ThreadKey? = null
    private var inspectedCommands: Set<String> = emptySet()
    private var inspectedGeneration: Long? = null
    private var inspectedConnectionGeneration: Long? = null
    private val timelines = ConcurrentHashMap<ThreadKey, NativeTimeline>()
    private val cursors = ConcurrentHashMap<String, Cursor>()
    private val snapshotCursors = ConcurrentHashMap<ThreadKey, Cursor>()
    private val files = File(context.filesDir, "attachments").apply { mkdirs() }

    init {
        scope.launch {
            for (ignored in directoryRefresh) {
                delay(100)
                while (directoryRefresh.tryReceive().isSuccess) { }
                if (_state.value.online) runCatching {
                    refreshRuntimes()
                    // Refresh both archive states so moves and ownership changes update cached rows.
                    refreshDirectory()
                }
            }
        }
        scope.launch {
            for (ignored in queueRefresh) {
                if (_state.value.online) _state.value.selected?.let { chat ->
                    runCatching { loadQueue(chat, selectionGeneration.get()) }
                }
            }
        }
        scope.launch {
            networkAvailable.catch { /* Keep manual/timed reconnect available if platform observation fails. */ }.collect {
                reconnectWakeup.trySend(Unit)
                if (foreground || _state.value.watch) {
                    // Availability/validation callbacks do not imply a broken socket.
                    // Probe the current generation; a late failed probe cannot close its replacement.
                    if (_state.value.online) {
                        if (networkProbe?.isActive != true) networkProbe = scope.launch {
                            val current = client ?: return@launch
                            try { current.request("runtimes/list", timeoutMillis = 1500) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { if (client === current) current.close() }
                        }
                    } else reconnect()
                }
            }
        }
        scope.launch {
            dao.markInterruptedUnknown()
            try {
                pair = pairStore.load()
                val cachedChats = dao.allChats().filter { it.key.computerId == pair?.computerId }
                val cachedCommands = pair?.let { dao.uncertain(it.computerId) }.orEmpty()
                val unread = preferences.getStringSet("unread", emptySet()).orEmpty().filterTo(mutableSetOf()) { runCatching { ThreadKey.decode(it).computerId == pair?.computerId }.getOrDefault(false) }
                val cachedQuestions = cachedChats.mapNotNull { chat ->
                    val cached = NativeTimeline(items = dao.items(chat.key).associateTo(linkedMapOf()) { it.itemKey to it.item() })
                    AsyncQuestions.pending(cached, submittedQuestions(chat.key)).takeIf { it.isNotEmpty() }?.let { chat.key.encoded to it }
                }.toMap()
                _state.update { it.copy(initialized = true, paired = pair != null, computerName = pair?.computerName.orEmpty(), computerId = pair?.computerId.orEmpty(), chats = cachedChats, commands = cachedCommands, unreadKeys = unread, pendingQuestions = cachedQuestions) }
                pair?.let { p -> dao.cursors(p.computerId).forEach { cursors[it.runtimeId] = Cursor(it.epoch, it.sequence) } }
            } catch (_: Exception) {
                _state.update { it.copy(initialized = true, error = context.getString(R.string.pair_unlock_error)) }
            }
            if (foreground) reconnect()
        }
        scope.launch { dao.chats().collect { list ->
            val before = _state.value.selected
            val after = list.firstOrNull { it.key == before?.key }
            val ownerChanged = before != null && after != null && (before.runtimeId != after.runtimeId || before.conflict != after.conflict)
            if (ownerChanged) selectionGeneration.incrementAndGet()
            _state.update { s -> s.copy(chats = list.filter { it.key.computerId == s.computerId }, selected = s.selected?.let { c -> list.firstOrNull { it.key == c.key } ?: c },
                nativeQueue = if (ownerChanged) emptyList() else s.nativeQueue,
                queueSupported = if (ownerChanged) null else s.queueSupported,
                nextTurns = if (ownerChanged) null else s.nextTurns,
                historyExhausted = if (ownerChanged) false else s.historyExhausted,
                unknownHistoryInspected = if (ownerChanged) false else s.unknownHistoryInspected) }
            if (ownerChanged && _state.value.online) action(lock = null) {
                hydrate(after); loadModels(after.runtimeId); loadQueue(after, selectionGeneration.get())
            }
        } }
        scope.launch { dao.uncertainCommands().collect { commands -> _state.update { s ->
            val current = commands.filter { c -> c.computerId == s.computerId }
            val selected = current.filter { it.chatKey == s.selected?.key || it.chatKey == null }
            s.copy(commands = current, unknownHistoryInspected = s.unknownHistoryInspected &&
                s.selected?.key == inspectedChat && selected.isNotEmpty() &&
                selected.all { it.status in setOf("unknown", "not_found") } && selected.map { it.id }.toSet() == inspectedCommands)
        } } }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (_state.value.online) scope.launch { runCatching { sendPresence() } }
        if (value) reconnect() else if (!_state.value.watch) disconnect()
    }
    fun setWatch(value: Boolean) { _state.update { it.copy(watch = value) }; if (value) reconnect() else if (!foreground) disconnect() }
    fun theme(value: String) { require(value in setOf("system", "light", "dark")); preferences.edit().putString("theme", value).apply(); _state.update { it.copy(theme = value) } }
    fun glassEffects(value: Boolean) { preferences.edit().putBoolean("glassEffects", value).apply(); _state.update { it.copy(glassEffects = value) } }
    fun clearError() = _state.update { it.copy(error = null) }
    fun reportError(message: String) = _state.update { it.copy(error = message) }

    fun reconnect() {
        if (!_state.value.initialized || pair == null) return
        if (connectionJob?.isActive == true) { reconnectWakeup.trySend(Unit); return }
        connectionJob = scope.launch {
            var attempt = 0
            while (isActive && (foreground || _state.value.watch)) {
                while (reconnectWakeup.tryReceive().isSuccess) { /* Coalesce network/manual wakeups. */ }
                val currentPair = pair ?: break
                val transport = connectionFactory()
                val generation = connectionGeneration.incrementAndGet()
                var stage = "transport"
                connectionDiagnostics.record(generation, stage, "started")
                client = transport
                try {
                    _state.update { it.copy(connection = if (attempt == 0) "Connecting" else "Reconnecting", connectionPhase = "connecting", connectionDetail = "正在建立加密连接", online = false, approvalsFresh = false) }
                    transport.connect(currentPair.config)
                    stage = "authentication"
                    _state.update { it.copy(connectionPhase = "authenticating", connectionDetail = "正在验证电脑身份") }
                    val hello = transport.request("hello", buildJsonObject { put("protocol", 1); put("deviceId", currentPair.deviceId); put("deviceSecret", currentPair.deviceSecret) }).asObject()
                    require(hello.number("protocol") == 1L && hello.string("computerId") == currentPair.computerId) { "Protocol or computer identity mismatch" }
                    val runtimeList = hello.array("runtimes").map { RuntimeInfo.from(it.asObject()) }
                    _state.update { it.copy(runtimes = runtimeList, capabilities = hello.array("capabilities").mapNotNull { v -> (v as? JsonPrimitive)?.contentOrNull }.toSet(), disabledMethods = emptySet(), computerName = hello.string("computerName", currentPair.computerName)) }
                    if (runtimeList.isEmpty()) refreshRuntimes()
                    resolvedApprovalKeys.clear()
                    resyncRequested = false; resyncCount = 0
                    coroutineScope {
                        val buffered = Channel<JsonObject>(512)
                        launch {
                            try { transport.notifications.collect { if (!buffered.trySend(it).isSuccess) throw WireException("backpressure", "Startup event buffer exhausted") } }
                            finally { buffered.close() }
                        }
                        _state.update { it.copy(connectionPhase = "syncing", connectionDetail = "正在恢复对话与待处理事项") }
                        stage = "synchronization"
                        sync()
                        _state.update { it.copy(online = true, connection = "Connected", connectionPhase = "ready", connectionDetail = "", lastSyncAt = System.currentTimeMillis(), error = null, pairingPhase = if (it.pairingPhase == PairingPhase.Synchronizing) PairingPhase.Paired else it.pairingPhase) }
                        stage = "connected"
                        connectionDiagnostics.record(generation, stage, "ready")
                        registerPush()
                        attempt = 0
                        launch {
                            for (notification in buffered) {
                                val batch = mutableListOf(notification)
                                // One frame's work is shared by streaming tokens; control replies remain independent.
                                if (notification.obj("params").obj("event").string("method").endsWith("/delta") || notification.obj("params").obj("event").string("method").endsWith("Delta")) delay(16)
                                while (batch.size < 128) { val next = buffered.tryReceive().getOrNull() ?: break; batch += next }
                                receiveBatch(batch)
                                // Outside eventLock: sync() takes it while replaying.
                                if (resyncRequested) resyncInBand(transport)
                            }
                        }
                        launch { reconcileCommands() }
                        launch {
                            try {
                                refreshChats()
                                pendingNotification?.let { openNotification(it) }
                                _state.value.selected?.let { chat ->
                                    coroutineScope {
                                        launch { loadConversation(chat) }
                                        launch { loadModels(chat.runtimeId) }
                                        launch { loadQueue(chat, selectionGeneration.get()) }
                                    }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { if (client === transport) _state.update { it.copy(notice = "部分资料尚未更新，当前对话仍可继续") } }
                        }
                        launch {
                            while (isActive) {
                                delay(15_000)
                                transport.request(if ("ping" in _state.value.capabilities) "ping" else "runtimes/list", timeoutMillis = 5_000)
                                if (client === transport) _state.update { it.copy(lastSyncAt = System.currentTimeMillis()) }
                                sendPresence()
                            }
                        }
                        transport.state.first { it == LinkState.OFFLINE }
                        throw WireException(transport.failureCode ?: "disconnected", "Disconnected")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    connectionDiagnostics.record(generation, stage, transport.failureCode ?: (e as? WireException)?.code ?: "unknown")
                    val authorizationRequired = e is WireException && e.code in setOf("revoked", "unauthorized", "device_revoked", "authentication_failed")
                    if (client === transport) _state.update { it.copy(online = false, connection = "Offline", connectionPhase = if (authorizationRequired) "authorization_required" else "recovering", connectionDetail = safeError(e), error = null,
                        pairingPhase = if (it.pairingPhase == PairingPhase.Synchronizing && authorizationRequired) PairingPhase.Failed else it.pairingPhase) }
                    if (authorizationRequired) break
                } finally {
                    transport.close()
                    if (client === transport) {
                        client = null
                        _state.update { it.copy(online = false, connection = "Offline", modelCatalogState = ModelCatalogState.Offline, approvalsFresh = false, unknownHistoryInspected = false, nativeQueue = emptyList(), queueSupported = null) }
                    }
                }
                val backoff = minOf(30_000L, 1_000L shl attempt.coerceAtMost(5))
                val awakened = withTimeoutOrNull(backoff + kotlin.random.Random.nextLong(0, maxOf(1, backoff / 4))) { reconnectWakeup.receive(); true } == true
                if (awakened) attempt = 0 else attempt++
            }
        }
    }

    private fun disconnect() { connectionJob?.cancel(); connectionJob = null; client?.close(); client = null; _state.update { it.copy(online = false, connection = "Offline", modelCatalogState = ModelCatalogState.Offline, approvalsFresh = false, unknownHistoryInspected = false, nativeQueue = emptyList(), queueSupported = null) } }

    fun reloadConversation(): Job = _state.value.selected?.let(::select) ?: scope.launch {}

    fun preparePairing() { _state.update { it.copy(pairingPhase = PairingPhase.Idle, error = null) } }

    fun pair(text: String, deviceName: String) = action {
        _state.update { it.copy(pairingPhase = PairingPhase.Validating) }
        val code = try { PairingCode.parse(text) } catch (error: Exception) {
            _state.update { it.copy(pairingPhase = PairingPhase.Failed) }; throw error
        }
        disconnect()
        val transport = connectionFactory()
        try {
            val config = ConnectionConfig(code.relayUrl, code.room, code.phoneToken, code.certificateSha256)
            _state.update { it.copy(pairingPhase = PairingPhase.Connecting) }
            transport.connect(config)
            _state.update { it.copy(pairingPhase = PairingPhase.Authenticating) }
            val deviceId = UUID.randomUUID().toString()
            val result = transport.request("pair", buildJsonObject { put("pairingToken", code.pairingToken); put("deviceId", deviceId); put("deviceName", deviceName.ifBlank { Build.MODEL }.take(80)) }).asObject()
            require(result.string("deviceId").isNotBlank() && result.string("deviceSecret").isNotBlank() && result.string("computerId").isNotBlank())
            val saved = PairedComputer(config, result.string("deviceId"), result.string("deviceSecret"), result.string("computerId"), result.string("computerName", code.computerName))
            pairStore.save(saved)
            pair = saved
            cursors.clear(); timelines.clear(); snapshotCursors.clear()
            dao.cursors(saved.computerId).forEach { cursors[it.runtimeId] = Cursor(it.epoch, it.sequence) }
            val cachedChats = dao.allChats().filter { it.key.computerId == saved.computerId }
            val cachedCommands = dao.uncertain(saved.computerId)
            _state.update { it.copy(paired = true, pairingPhase = PairingPhase.Synchronizing, computerId = saved.computerId, computerName = saved.computerName, selected = null, timeline = NativeTimeline(), chats = cachedChats, commands = cachedCommands, approvals = emptyList(), models = emptyList(), modelCatalogState = if (it.online) ModelCatalogState.Idle else ModelCatalogState.Offline, modelCatalogError = null, model = null, effort = null) }
        } catch (cancelled: CancellationException) {
            _state.update { it.copy(pairingPhase = PairingPhase.Failed) }; throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(pairingPhase = PairingPhase.Failed) }; throw error
        } finally { transport.close() }
        reconnect()
    }

    fun forget() = action {
        disconnect(); pairStore.forget(); pair = null; cursors.clear(); timelines.clear(); snapshotCursors.clear()
        _state.update { PocketState(initialized = true, theme = it.theme) }
    }

    private suspend fun request(method: String, params: JsonObject = buildJsonObject {}) = (client ?: throw WireException("offline", "Offline")).request(method, params)
    private suspend fun native(runtime: String, method: String, params: JsonObject, mutation: Boolean = false, chat: ThreadKey? = null, draft: String = ""): JsonElement {
        val body = buildJsonObject { put("runtimeId", runtime); put("method", method); put("params", params) }
        try { return if (mutation) mutate("codex/request", body, chat, method, draft) else request("codex/request", body) }
        catch (e: WireException) {
            if (e.code in setOf("unsupported", "method_not_found", "unsupported_method", "-32601")) _state.update { it.copy(disabledMethods = it.disabledMethods + "$runtime:$method") }
            throw e
        }
    }
    private suspend fun mutate(method: String, params: JsonObject, chat: ThreadKey? = null, nativeMethod: String = method, draft: String = ""): JsonElement {
        val p = pair ?: throw WireException("offline", "Offline")
        val transport = client ?: throw WireException("offline", "Offline")
        val id = UUID.randomUUID().toString()
        dao.putCommand(CommandEntity(id, p.computerId, chat, nativeMethod, "submitted", System.currentTimeMillis(), draft))
        if (nativeMethod == "approvals/respond") synchronized(approvalGate) {
            approvalCommandGenerations[id] = draft to (approvalGenerations[draft] ?: 0L)
        }
        try {
            val result = transport.request(method, params, commandId = id)
            val status = (result as? JsonObject)?.string("status").orEmpty()
            dao.commandStatus(id, if (status in setOf("unknown", "submitted")) status else "accepted")
            if (status == "unknown" || (status == "submitted" && nativeMethod != "approvals/respond")) throw UnknownOutcomeException(id)
            return result
        } catch (e: WireException) { dao.commandStatus(id, "rejected"); throw e }
        catch (e: Exception) { withContext(NonCancellable) { dao.commandStatus(id, "unknown") }; throw e }
    }

    private suspend fun refreshRuntimes() {
        val runtimes = request("runtimes/list").asObject().array("data").map { RuntimeInfo.from(it.asObject()) }
        _state.update { it.copy(runtimes = runtimes) }
    }
    fun refresh(search: String = "", archived: Boolean = false, more: Boolean = false): Job {
        val query = DirectoryQuery(search, archived)
        if (directoryQuery != query) { directoryQuery = query; _state.update { it.copy(nextThreads = null) } }
        return action(lock = null) { refreshRuntimes(); refreshChats(search, archived, more) }
    }
    private suspend fun refreshChats(search: String = "", archived: Boolean = false, more: Boolean = false) {
        directoryLock.withLock {
            val query = DirectoryQuery(search, archived)
            if (query != directoryQuery) return@withLock
            val cursor = _state.value.nextThreads
            if (more && cursor == null) return@withLock
            val response = readChats(query, if (more) cursor else null)
            if (query == directoryQuery) _state.update { it.copy(nextThreads = response["nextCursor"]?.takeUnless { c -> c is JsonNull }) }
        }
    }
    private suspend fun refreshDirectory() = directoryLock.withLock {
        for (archived in listOf(false, true)) {
            var cursor: JsonElement? = null
            val seen = mutableSetOf<JsonElement>()
            do {
                val response = readChats(DirectoryQuery(archived = archived), cursor)
                cursor = response["nextCursor"]?.takeUnless { it is JsonNull }
            } while (cursor != null && seen.add(cursor))
        }
        // Previous cursors may describe an obsolete directory order.
        _state.update { it.copy(nextThreads = null) }
    }
    private suspend fun readChats(query: DirectoryQuery, cursor: JsonElement?): JsonObject {
        val response = request("threads/list", buildJsonObject { put("limit", 60); put("search", query.search); put("archived", query.archived); cursor?.let { put("cursor", it) } }).asObject()
        val p = pair ?: return response
        val chats = response.array("data").mapNotNull { value ->
            val o = value.asObject()
            val r = _state.value.runtimes.firstOrNull { it.id == o.string("runtimeId") } ?: return@mapNotNull null
            val key = ThreadKey(p.computerId, o.string("configStoreId", r.configStoreId), o.string("id"))
            ChatEntity(key, r.id, o.string("source", r.source), o.string("name", context.getString(R.string.untitled_chat)), o.string("preview"), o.string("cwd"), o.number("updatedAt"), o.string("status", o.obj("status").string("type")), o.flag("conflict"), query.archived, dao.chat(key)?.pinned ?: false)
        }.distinctBy { it.key }
        dao.putChats(chats)
        return response
    }

    fun select(chat: ChatEntity): Job {
        val generation = selectionGeneration.incrementAndGet()
        return action(lock = null) {
        val savedDraft = drafts[chat.key] ?: dao.draft(chat.key) ?: DraftEntity(chat.key)
        val draft = synchronized(draftGate) { drafts.getOrPut(chat.key) { savedDraft } }
        val timeline = timelines[chat.key] ?: NativeTimeline(items = dao.items(chat.key).associateTo(linkedMapOf()) { it.itemKey to it.item() }).also { timelines[chat.key] = it }
        if (generation != selectionGeneration.get()) return@action
        _state.update { it.copy(selected = chat, timeline = timeline, draft = draft, attachments = parseAttachments(draft.attachments), models = emptyList(), modelCatalogState = if (it.online) ModelCatalogState.Idle else ModelCatalogState.Offline, modelCatalogError = null, model = null, effort = null, modes = emptyList(), mode = null, nextTurns = null, historyExhausted = false, historyLoading = chat.key in loadingHistory, unknownHistoryInspected = false, stopping = stopKey(chat.key, timeline.activeTurn) in stoppingTurns, nativeQueue = emptyList(), queueSupported = null, advanced = AdvancedConversationState(asyncQuestions = AsyncQuestions.pending(timeline, submittedQuestions(chat.key)))) }
        _state.update { it.copy(conversationLoadState = ConversationLoadState.Idle, conversationLoadError = null) }
        markRead(chat)
        if (_state.value.online) scope.launch { runCatching { sendPresence() } }
        preferences.edit().putString("selected", chat.key.encoded).apply()
        transferSharedAttachments(chat.key)
        if (_state.value.online) {
            loadConversation(chat)
            if (isSelected(chat, generation)) supervisorScope {
                launch { loadModels(chat.runtimeId) }
                launch { runCatching { loadQueue(chat, generation) } }
                launch { runCatching { loadGoal(chat) } }
            }
        }
        }
    }

    private suspend fun loadConversation(chat: ChatEntity) {
        try { hydrate(chat) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* hydrate owns its scoped failure; authentication remains usable. */ }
    }

    private suspend fun hydrate(chat: ChatEntity): Boolean {
        val transport = client ?: throw WireException("offline", "Offline")
        val generation = selectionGeneration.get()
        val linkGeneration = connectionGeneration.get()
        val capture = HistoryCapture(chat.runtimeId)
        fun current() = client === transport && connectionGeneration.get() == linkGeneration && isSelected(chat, generation)
        if (!current()) return false
        _state.update { if (current()) it.copy(conversationLoadState = ConversationLoadState.Loading, conversationLoadError = null) else it }
        connectionDiagnostics.record(linkGeneration, "history", "started")
        eventLock.withLock { historyCaptures[chat.key] = capture }
        try {
            suspend fun read(method: String, params: JsonObject): JsonObject = transport.request("codex/request", buildJsonObject {
                put("runtimeId", chat.runtimeId); put("method", method); put("params", params)
            }).asObject()
            val paged = _state.value.supports("thread/turns/list", _state.value.runtimes.firstOrNull { it.id == chat.runtimeId })
            var metadata = read("thread/read", buildJsonObject { put("threadId", chat.key.nativeId); put("includeTurns", !paged) })
            if (!current()) return false
            // Older peers may still return turns despite includeTurns=false; their bounded snapshot remains valid.
            var page = if (paged && metadata.obj("thread").array("turns").isEmpty()) read("thread/turns/list", buildJsonObject {
                put("threadId", chat.key.nativeId); put("limit", HISTORY_FIRST_PAGE); put("sortDirection", "desc"); put("itemsView", "full")
            }) else null
            if (page != null && page["pocketCursor"] !is JsonObject) {
                // Old helpers cannot fence a paginated snapshot. Do not guess its event watermark.
                metadata = read("thread/read", buildJsonObject { put("threadId", chat.key.nativeId); put("includeTurns", true) })
                page = null
            }
            if (!current()) return false
            val thread = if (page != null) JsonObject(metadata.obj("thread") + ("turns" to JsonArray(page.array("data").reversed()))) else metadata.obj("thread")
            val parsed = withContext(Dispatchers.Default) { NativeReducer.snapshot(thread) }
            val cursorSource = page?.get("pocketCursor") ?: metadata["pocketCursor"]
            val watermark = cursorSource?.asObject()?.let { o -> o.string("epoch").takeIf(String::isNotBlank)?.let { Cursor(it, o.number("sequence")) } }
            var applied = false
            eventLock.withLock {
                if (!current() || historyCaptures[chat.key] !== capture || dao.chat(chat.key)?.runtimeId?.let { it != chat.runtimeId } == true) return@withLock
                if (capture.overflow) throw WireException("history_overflow", "History changed too quickly")
                val cursor = cursors[chat.runtimeId]
                if (watermark != null && cursor != null && watermark.epoch != cursor.epoch) throw WireException("stale", "History epoch changed")
                if (watermark == null && capture.events.isNotEmpty()) throw WireException("stale", "History needs a cursor")
                var timeline = parsed
                var goal = thread["goal"] as? JsonObject
                var usage = thread["tokenUsage"] as? JsonObject
                var fast = thread.string("serviceTier") == "fast"
                val metadataCursor = metadata.obj("pocketCursor").let { o -> o.string("epoch").takeIf(String::isNotBlank)?.let { Cursor(it, o.number("sequence")) } }
                for (envelope in capture.events) {
                    val eventCursor = envelope.obj("cursor").let { Cursor(it.string("epoch"), it.number("sequence")) }
                    val event = envelope.obj("event")
                    val params = event.obj("params")
                    if (metadataCursor == null || !metadataCursor.covers(eventCursor)) when (event.string("method")) {
                        "thread/tokenUsage/updated" -> usage = params["tokenUsage"] as? JsonObject
                        "thread/goal/updated" -> goal = params["goal"] as? JsonObject
                        "thread/goal/cleared" -> goal = null
                        "thread/settings/updated" -> fast = params.obj("settings").string("serviceTier", params.string("serviceTier")) == "fast"
                    }
                    if (watermark != null && eventCursor.epoch == watermark.epoch && eventCursor.sequence > watermark.sequence) {
                        if (event["id"] == null) timeline = NativeReducer.reduce(timeline, event, "${eventCursor.epoch}:${eventCursor.sequence}")
                    }
                }
                persistTimeline(chat.key, timeline, replace = true)
                if (watermark != null) {
                    db.withTransaction { dao.putSnapshot(SnapshotEntity(chat.key, watermark.epoch, watermark.sequence)) }
                    snapshotCursors[chat.key] = watermark
                }
                val next = page?.get("nextCursor")?.takeUnless { it is JsonNull }
                _state.update { if (current()) it.copy(timeline = timeline, nextTurns = next, historyExhausted = page != null && next == null,
                    conversationLoadState = ConversationLoadState.Ready, conversationLoadError = null,
                    stopping = stopKey(chat.key, timeline.activeTurn) in stoppingTurns,
                    advanced = it.advanced.copy(goal = goal, usage = usage,
                        fastMode = fast, asyncQuestions = AsyncQuestions.pending(timeline, submittedQuestions(chat.key)))) else it }
                updateAsyncState(chat.key, timeline)
                applied = true
            }
            connectionDiagnostics.record(linkGeneration, "history", if (applied) "completed" else "stale")
            return applied
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val code = (failure as? WireException)?.code ?: "unknown"
            connectionDiagnostics.record(linkGeneration, "history", code)
            val message = when (code) {
                "payload_too_large" -> "这段历史超过读取上限，请尝试较短的会话或在电脑端查看。"
                "history_overflow", "stale" -> "会话在读取期间发生变化，请重新加载。"
                "timeout" -> "会话历史读取超时，可重新加载；电脑连接状态单独显示。"
                "offline", "disconnected" -> "连接恢复后可重新加载会话，缓存与草稿已保留。"
                else -> "无法读取此会话历史，缓存与草稿已保留。请重新加载或在电脑端检查该会话。"
            }
            _state.update { if (current()) it.copy(conversationLoadState = ConversationLoadState.Failed, conversationLoadError = message) else it }
            throw failure
        } finally {
            withContext(NonCancellable) { eventLock.withLock { if (historyCaptures[chat.key] === capture) historyCaptures.remove(chat.key) } }
        }
    }

    private fun isSelected(chat: ChatEntity, generation: Long? = null): Boolean = _state.value.selected?.let {
        it.key == chat.key && it.runtimeId == chat.runtimeId && it.conflict == chat.conflict &&
            (generation == null || generation == selectionGeneration.get())
    } == true

    private suspend fun loadQueue(chat: ChatEntity, generation: Long) = queueLock.withLock {
        if (!isSelected(chat, generation)) return@withLock
        val transport = client
        val runtime = _state.value.runtimes.firstOrNull { it.id == chat.runtimeId }
        if (runtime == null || !runtime.online || chat.conflict) {
            _state.update { if (isSelected(chat, generation)) it.copy(nativeQueue = emptyList(), queueSupported = null) else it }
            return@withLock
        }
        if (!_state.value.supports("thread/queue/list", runtime)) {
            _state.update { if (isSelected(chat, generation)) it.copy(nativeQueue = emptyList(), queueSupported = false) else it }
            return@withLock
        }
        val queue = linkedMapOf<String, JsonObject>()
        val seen = mutableSetOf<JsonElement>()
        var cursor: JsonElement? = null
        try {
            do {
                val response = native(chat.runtimeId, "thread/queue/list", buildJsonObject {
                    put("threadId", chat.key.nativeId); put("limit", 100); cursor?.let { put("cursor", it) }
                }).asObject()
                if (!isSelected(chat, generation) || client !== transport || _state.value.runtimes.none { it.id == chat.runtimeId && it.online && it.configStoreId == chat.key.configStoreId }) return@withLock
                response.array("data").forEach { item ->
                    val entry = item.asObject()
                    require(entry.string("id").isNotBlank() && entry["input"] is JsonArray)
                    queue[entry.string("id")] = entry
                }
                cursor = response["nextCursor"]?.takeUnless { it is JsonNull }
                require(cursor == null || seen.add(cursor)) { "Repeated queue cursor" }
            } while (cursor != null)
            _state.update { if (isSelected(chat, generation)) it.copy(nativeQueue = queue.values.toList(), queueSupported = true) else it }
        } catch (e: WireException) {
            if (isSelected(chat, generation)) _state.update { it.copy(nativeQueue = emptyList(), queueSupported =
                if (e.code in setOf("unsupported", "method_not_found", "unsupported_method", "-32601")) false else null) }
            if (e.code !in setOf("unsupported", "method_not_found", "unsupported_method", "-32601")) throw e
        }
    }

    fun refreshModels(): Job = scope.launch {
        val runtime = _state.value.selected?.runtimeId ?: return@launch
        loadModels(runtime)
    }

    private suspend fun loadModels(runtime: String) {
        val selected = _state.value.selected ?: return
        if (selected.runtimeId != runtime) return
        val selectedGeneration = selectionGeneration.get()
        val requestGeneration = catalogGeneration.incrementAndGet()
        val transport = client
        fun current(s: PocketState = _state.value) = requestGeneration == catalogGeneration.get() && client === transport &&
            selectedGeneration == selectionGeneration.get() && s.selected?.key == selected.key && s.selected.runtimeId == runtime
        fun publish(transform: (PocketState) -> PocketState) { _state.update { if (current(it)) transform(it) else it } }
        suspend fun catalog(method: String, params: JsonObject = buildJsonObject {}): JsonObject {
            if (!current()) throw CancellationException("Superseded model catalog")
            return (transport ?: throw WireException("offline", "Offline")).request("codex/request", buildJsonObject {
                put("runtimeId", runtime); put("method", method); put("params", params)
            }).asObject()
        }
        val state = _state.value
        if (!state.online || state.runtimes.firstOrNull { it.id == runtime }?.online != true) {
            publish { it.copy(modelCatalogState = ModelCatalogState.Offline, modelCatalogError = null) }
            return
        }
        if (!state.supports("model/list", state.runtimes.firstOrNull { it.id == runtime })) {
            publish { it.copy(modelCatalogState = ModelCatalogState.Unsupported, modelCatalogError = null) }
            return
        }
        publish { it.copy(modelCatalogState = ModelCatalogState.Loading, modelCatalogError = null) }
        try {
            val found = linkedMapOf<String, NativeModel>()
            val seen = mutableSetOf<JsonElement>()
            var cursor: JsonElement? = null
            do {
                val response = catalog("model/list", buildJsonObject { put("limit", 100); cursor?.let { put("cursor", it) } })
                response.array("data").map { it.asObject() }.filterNot { it.flag("hidden") }.map(NativeModel::from).forEach { found[it.model] = it }
                cursor = response["nextCursor"]?.takeUnless { it is JsonNull }
                if (cursor != null && !seen.add(cursor)) throw WireException("invalid_response", "Model catalog cursor repeated")
            } while (cursor != null && found.size < 1000)
            publish { it.copy(models = found.values.toList(), modelCatalogState = if (found.isEmpty()) ModelCatalogState.Empty else ModelCatalogState.Ready, modelCatalogError = null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            publish {
                val unsupported = error is WireException && error.code in setOf("unsupported", "method_not_found", "unsupported_method", "-32601")
                it.copy(modelCatalogState = if (!it.online) ModelCatalogState.Offline else if (unsupported) ModelCatalogState.Unsupported else ModelCatalogState.Failed,
                    disabledMethods = if (unsupported && it.online) it.disabledMethods + "$runtime:model/list" else it.disabledMethods,
                    modelCatalogError = if (unsupported || !it.online) null else "模型列表未能更新，请重试。已选择的模型保持不变。")
            }
        }
        if (!current()) return
        val r = _state.value.runtimes.firstOrNull { it.id == runtime }
        if (_state.value.supports("collaborationMode/list", r)) {
            try {
                val modes = catalog("collaborationMode/list").array("data").mapNotNull { NativeMode.from(it.asObject()) }
                publish { it.copy(modes = modes) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional mode catalog cannot invalidate a successful model catalog. */ }
        }
    }
    fun chooseModel(model: NativeModel, effort: String?) { require(effort == null || effort in model.efforts); _state.update { it.copy(model = model.model, effort = effort) } }
    fun chooseMode(mode: String?) { require(mode == null || _state.value.modes.any { it.mode == mode }); _state.update { it.copy(mode = mode) } }

    fun create(runtime: RuntimeInfo, cwd: String) = action {
        reconcileCommands()
        dao.uncertain(_state.value.computerId).firstOrNull { it.chatKey == null }?.let { throw UnknownOutcomeException(it.id) }
        val response = native(runtime.id, "thread/start", buildJsonObject { if (cwd.isNotBlank()) put("cwd", cwd) }, mutation = true).asObject().obj("thread")
        val p = pair ?: return@action
        val chat = ChatEntity(ThreadKey(p.computerId, runtime.configStoreId, response.string("id")), runtime.id, runtime.source, response.string("name", context.getString(R.string.new_chat)), response.string("preview"), response.string("cwd", cwd), response.number("updatedAt", System.currentTimeMillis() / 1000), "idle", false)
        require(chat.key.nativeId.isNotBlank())
        dao.putChat(chat)
        val generation = selectionGeneration.incrementAndGet()
        val draft = DraftEntity(chat.key)
        drafts[chat.key] = draft
        val timeline = NativeReducer.snapshot(response)
        timelines[chat.key] = timeline
        _state.update { it.copy(selected = chat, timeline = timeline, draft = draft, attachments = emptyList(), models = emptyList(), modelCatalogState = if (it.online) ModelCatalogState.Idle else ModelCatalogState.Offline, modelCatalogError = null, model = null, effort = null, modes = emptyList(), mode = null, nextTurns = null, historyExhausted = false, historyLoading = false, unknownHistoryInspected = false, stopping = false, nativeQueue = emptyList(), queueSupported = null, advanced = AdvancedConversationState()) }
        transferSharedAttachments(chat.key)
        refreshRuntimes()
        loadModels(runtime.id)
        loadQueue(chat, generation)
    }
    fun rename(name: String) = action {
        val chat = selected()
        native(chat.runtimeId, "thread/name/set", buildJsonObject { put("threadId", chat.key.nativeId); put("name", name.trim()) }, true, chat.key)
        dao.putChat(chat.copy(title = name.trim()))
    }
    fun pin(chat: ChatEntity) = action { dao.putChat(chat.copy(pinned = !chat.pinned)) }
    fun archive(chat: ChatEntity) = action {
        native(chat.runtimeId, if (chat.archived) "thread/unarchive" else "thread/archive", buildJsonObject { put("threadId", chat.key.nativeId) }, true, chat.key)
        dao.putChat(chat.copy(archived = !chat.archived))
    }
    fun resume() = action {
        val chat = selected()
        native(chat.runtimeId, "thread/resume", buildJsonObject { put("threadId", chat.key.nativeId) }, true, chat.key)
        hydrate(chat); refreshRuntimes()
    }

    fun editDraft(text: String) {
        updateDraft { it.copy(text = text) }
    }
    fun scroll(index: Int, offset: Int, followEnd: Boolean, itemKey: String? = null) {
        updateDraft { it.copy(scrollIndex = index, scrollOffset = offset, followEnd = followEnd, scrollItemKey = itemKey) }
    }
    fun toggleExpansion(itemKey: String) {
        updateDraft { draft ->
            val expansions = runCatching { pocketJson.parseToJsonElement(draft.expansions).jsonArray.map { it.jsonPrimitive.content }.toSet() }.getOrDefault(emptySet())
            val next = if (itemKey in expansions) expansions - itemKey else expansions + itemKey
            draft.copy(expansions = JsonArray(next.map(::JsonPrimitive)).toString())
        }
    }
    private fun updateDraft(key: ThreadKey? = _state.value.draft?.chatKey, transform: (DraftEntity) -> DraftEntity) {
        if (key == null) return
        synchronized(draftGate) {
            val current = drafts[key] ?: _state.value.draft?.takeIf { it.chatKey == key } ?: return
            val next = transform(current)
            drafts[key] = next
            _state.update { if (it.draft?.chatKey == key) it.copy(draft = next, attachments = parseAttachments(next.attachments)) else it }
        }
        scope.launch { persistDraft(key) }
    }
    private suspend fun persistDraft(key: ThreadKey) = draftLock.withLock { drafts[key]?.let { dao.putDraft(it) } }
    private suspend fun saveDraft(attachments: List<LocalAttachment>? = null, expectedKey: ThreadKey? = null) {
        val key = expectedKey ?: _state.value.draft?.chatKey ?: return
        if (attachments != null) updateDraft(key) { it.copy(attachments = JsonArray(attachments.map { a -> a.json() }).toString()) }
        persistDraft(key)
    }
    private suspend fun transferSharedAttachments(key: ThreadKey) {
        val current = drafts[key] ?: dao.draft(key) ?: DraftEntity(key)
        synchronized(draftGate) {
            val shared = _state.value.sharedAttachments
            val sharedText = preferences.getString("sharedText", "").orEmpty()
            if (shared.isEmpty() && sharedText.isBlank()) return
            val latest = drafts[key] ?: current
            val combined = (parseAttachments(latest.attachments) + shared).distinctBy { it.id }.take(4)
            val updated = latest.copy(attachments = JsonArray(combined.map { it.json() }).toString(), text = listOf(latest.text, sharedText).filter(String::isNotBlank).joinToString("\n\n"))
            drafts[key] = updated
            preferences.edit().remove("sharedText").apply()
            val transferred = combined.map { it.id }.toSet()
            _state.update { it.copy(sharedAttachments = it.sharedAttachments.filterNot { a -> a.id in transferred },
                draft = if (it.selected?.key == key) updated else it.draft,
                attachments = if (it.selected?.key == key) combined else it.attachments) }
        }
        persistDraft(key)
    }
    fun receiveSharedText(text: String) {
        if (_state.value.selected != null) editDraft(listOf(_state.value.draft?.text.orEmpty(), text).filter(String::isNotBlank).joinToString("\n\n"))
        else preferences.edit().putString("sharedText", text.take(1_000_000)).apply()
    }
    private fun parseAttachments(text: String) = runCatching { pocketJson.parseToJsonElement(text).jsonArray.map { LocalAttachment.from(it.asObject()) } }.getOrDefault(emptyList())

    fun attach(uris: List<Uri>): Job {
        val key = _state.value.selected?.key
        return action {
        val added = uris.take(4).map { uri ->
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 8 * 1024 * 1024) { val read = input.read(buffer, 0, minOf(buffer.size, 8 * 1024 * 1024 + 1 - output.size())); if (read < 0) break; output.write(buffer, 0, read) }
                output.toByteArray()
            } ?: error("Could not read image")
            require(bytes.size <= 8 * 1024 * 1024) { "Images must be 8 MiB or smaller" }
            val originalName = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()?.replace(Regex("[\\r\\n\\u0000-\\u001f]"), " ")?.take(128)
            val mime = when {
                bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
                bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)) -> "image/png"
                bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
                else -> {
                    require(bytes.size <= 256 * 1024) { "Text files must be at most 256 KiB" }
                    val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    require(decoded.none { it == '\u0000' || it.code in 1..8 || it.code in 14..31 }) { "This file is not UTF-8 text" }
                    "text/plain"
                }
            }
            val id = UUID.randomUUID().toString()
            File(files, id).writeBytes(bytes)
            LocalAttachment(id, originalName ?: if (mime == "text/plain") "附件.txt" else context.getString(R.string.image_name, mime.substringAfter('/').replace("jpeg", "jpg")), mime)
        }
        if (key == null) _state.update { it.copy(sharedAttachments = (it.sharedAttachments + added).take(4), notice = context.getString(R.string.shared_choose_chat)) }
        else saveDraft(attachments = (parseAttachments(drafts[key]?.attachments ?: "[]") + added).take(4), expectedKey = key)
        }
    }
    fun removeAttachment(attachment: LocalAttachment) = action { saveDraft(attachments = _state.value.attachments.filterNot { it.id == attachment.id }); attachmentFile(attachment).delete() }
    /** App-private attachment path for read-only UI preview. */
    fun attachmentFile(a: LocalAttachment): File { require(runCatching { UUID.fromString(a.id) }.isSuccess); return File(files, a.id) }

    fun send(): Job = send(false)
    fun send(steer: Boolean): Job {
        val s = _state.value
        val chat = s.selected ?: return scope.launch {}
        if (!sending.tryLock()) return scope.launch {}
        _state.update { it.copy(sendBusy = true) }
        return action(lock = null) {
        try {
        require(s.online && !chat.conflict)
        require(dao.uncertain(chat.key.computerId).none { it.chatKey == chat.key || it.chatKey == null }) { "Check the previous command outcome before sending again" }
        val text = s.draft?.text.orEmpty()
        val attachments = s.attachments
        require(text.isNotBlank() || attachments.isNotEmpty())
        require(attachments.none { it.mime.startsWith("image/") } || s.currentModel?.images == true) { "The selected model has not advertised image input" }
        if (s.activeRuntime?.loadedThreadIds?.contains(chat.key.nativeId) != true) {
            require(s.supports("thread/resume"))
            native(chat.runtimeId, "thread/resume", buildJsonObject { put("threadId", chat.key.nativeId) }, true, chat.key)
            refreshRuntimes()
            hydrate(chat)
        }
        val input = mutableListOf<JsonElement>()
        if (text.isNotBlank()) input += buildJsonObject { put("type", "text"); put("text", text) }
        runCatching { pocketJson.parseToJsonElement(s.draft?.references ?: "[]").jsonArray }.getOrDefault(JsonArray(emptyList())).forEach { value ->
            val reference = value.asObject()
            if (reference.string("token").isNotBlank() && reference.string("token") in text && reference.string("type") in setOf("skill", "mention")) {
                input += buildJsonObject { put("type", reference.string("type")); put("name", reference.string("name")); put("path", reference.string("path")) }
            }
        }
        for (attachment in attachments) {
            if (attachment.mime == "text/plain") {
                val content = attachmentFile(attachment).readText(Charsets.UTF_8)
                input += buildJsonObject { put("type", "text"); put("text", "附件：${attachment.name}\n\n$content") }
                continue
            }
            _state.update { it.copy(advanced = it.advanced.copy(uploadProgress = it.advanced.uploadProgress + (attachment.id to 0f))) }
            val result = mutate("attachment/upload", buildJsonObject { put("name", attachment.name); put("mime", attachment.mime); put("base64", Base64.encodeToString(attachmentFile(attachment).readBytes(), Base64.NO_WRAP)) }, chat.key).asObject()
            _state.update { it.copy(advanced = it.advanced.copy(uploadProgress = it.advanced.uploadProgress + (attachment.id to 1f))) }
            val id = result.string("attachmentId").also { require(it.isNotBlank()) }
            input += buildJsonObject { put("type", "pocketImage"); put("attachmentId", id) }
        }
        val currentChat = dao.chat(chat.key) ?: chat
        require(currentChat.runtimeId == chat.runtimeId && !currentChat.conflict && pair?.computerId == chat.key.computerId) { "Chat ownership changed" }
        val running = (timelines[chat.key] ?: s.timeline).activeTurn
        require(!steer || running != null) { "The turn has finished; send a new message instead" }
        val method = when { steer -> "turn/steer"; running != null -> "thread/queue/add"; else -> "turn/start" }
        require(s.supports(method)) { "This runtime does not support this action" }
        val result = native(chat.runtimeId, method, buildJsonObject {
            put("threadId", chat.key.nativeId); put("input", JsonArray(input))
            put("clientUserMessageId", UUID.randomUUID().toString())
            if (steer) put("expectedTurnId", running) else if (method == "turn/start") {
                s.model?.let { put("model", it) }; s.effort?.let { put("effort", it) }
                s.modes.firstOrNull { it.mode == s.mode }?.let { mode ->
                    val model = s.model ?: mode.model ?: s.currentModel?.model ?: error("Select a model for this mode")
                    put("collaborationMode", mode.payload(model, s.effort ?: mode.effort ?: s.currentModel?.defaultEffort))
                }
            }
        }, true, chat.key, buildJsonObject {
            put("pocketDraft", 1); put("text", text); put("attachments", JsonArray(attachments.map { it.json() }))
        }.toString()).asObject()
        clearAcceptedDraft(chat.key, text, attachments)
        if (method == "thread/queue/add") loadQueue(chat, selectionGeneration.get())
        val turnId = result.obj("turn").string("id").ifBlank { if (steer) running.orEmpty() else "" }
        if (turnId.isNotBlank()) eventLock.withLock {
            val old = timelines[chat.key] ?: NativeTimeline()
            val next = if (turnId in old.completedTurns || old.activeTurn != null) old else old.copy(activeTurn = turnId, status = "inProgress")
            timelines[chat.key] = next
            if (_state.value.selected?.key == chat.key) _state.update { it.copy(timeline = next) }
        }
        } finally { sending.unlock(); _state.update { it.copy(sendBusy = false, advanced = it.advanced.copy(uploadProgress = emptyMap())) } }
        }
    }

    private suspend fun clearAcceptedDraft(key: ThreadKey, text: String, attachments: List<LocalAttachment>) {
        val saved = drafts[key] ?: dao.draft(key)
        if (saved != null) {
            drafts.putIfAbsent(key, saved)
            val ids = attachments.map { it.id }.toSet()
            updateDraft(key) { current -> current.copy(text = if (current.text == text) "" else current.text,
                references = if (current.text == text) "[]" else current.references,
                attachments = JsonArray(parseAttachments(current.attachments).filterNot { it.id in ids }.map { it.json() }).toString()) }
            persistDraft(key)
            attachments.forEach { attachmentFile(it).delete() }
        }
    }
    private fun stopKey(key: ThreadKey, turn: String?) = "${key.encoded}:$turn"
    fun stop(): Job {
        val current = _state.value
        val chat = current.selected ?: return scope.launch {}
        val turn = current.timeline.activeTurn ?: return scope.launch {}
        val target = stopKey(chat.key, turn)
        if (!stoppingTurns.add(target)) return scope.launch {}
        _state.update { it.copy(stopping = true) }
        return action(lock = null) {
            try {
                require(_state.value.online && !chat.conflict && current.supports("turn/interrupt"))
                native(chat.runtimeId, "turn/interrupt", buildJsonObject { put("threadId", chat.key.nativeId); put("turnId", turn) }, true, chat.key)
            } finally {
                stoppingTurns.remove(target)
                _state.update { it.copy(stopping = it.selected?.let { c -> stopKey(c.key, it.timeline.activeTurn) in stoppingTurns } == true) }
            }
        }
    }

    fun respond(approval: PendingApproval, response: JsonObject): Job {
        if (!responding.add(approval.key)) return scope.launch {}
        _state.update { it.copy(submittingApprovalKeys = responding.toSet()) }
        return action(lock = null) {
            try {
                require(_state.value.online && _state.value.approvalsFresh) { "Reconnect to verify this request before responding" }
                require(_state.value.approvals.any { it.key == approval.key && !it.submitted }) { "This request is no longer pending" }
                val runtime = _state.value.runtimes.firstOrNull { it.id == approval.runtimeId } ?: error("Approval runtime unavailable")
                val chat = approval.threadId.takeIf(String::isNotBlank)?.let { ThreadKey(pair!!.computerId, runtime.configStoreId, it) }
                val generation = synchronized(approvalGate) {
                    require(_state.value.approvals.any { it.key == approval.key && !it.submitted }) { "This request is no longer pending" }
                    val next = (approvalGenerations[approval.key] ?: 0L) + 1
                    approvalGenerations[approval.key] = next
                    rejectedApprovalRefresh.remove(approval.key)
                    _state.update { it.copy(approvals = it.approvals.map { a -> if (a.key == approval.key) a.copy(submitted = true) else a }) }
                    next
                }
                try {
                    mutate("approvals/respond", buildJsonObject { put("runtimeId", approval.runtimeId); put("requestId", approval.requestId); put("response", response) }, chat, draft = approval.key)
                } catch (e: WireException) {
                    // Only the mutation's definite refusal permits reopening this request.
                    // If sync fails, retain the guard and retry the read on the next Check.
                    synchronized(approvalGate) {
                        if (approvalGenerations[approval.key] == generation) rejectedApprovalRefresh[approval.key] = generation
                    }
                    try { sync() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep the original sanitized mutation error. */ }
                    throw e
                }
                reconcileCommands()
            } finally {
                responding.remove(approval.key)
                _state.update { it.copy(submittingApprovalKeys = responding.toSet()) }
            }
        }
    }
    fun devices() = action(lock = null) { val devices = request("devices/list").asObject().array("data").map(JsonElement::asObject); _state.update { it.copy(devices = devices) } }
    fun revoke(id: String) = action {
        mutate("devices/revoke", buildJsonObject { put("deviceId", id) })
        if (id == pair?.deviceId) { disconnect(); pairStore.forget(); pair = null; _state.update { PocketState(initialized = true, theme = it.theme) } }
        else { val devices = request("devices/list").asObject().array("data").map(JsonElement::asObject); _state.update { it.copy(devices = devices) } }
    }
    fun clearCache() = action {
        db.withTransaction { dao.clearHistory(); dao.clearCursors(); dao.clearSnapshots() }
        eventLock.withLock { timelines.clear(); cursors.clear(); snapshotCursors.clear(); _state.update { it.copy(timeline = NativeTimeline(), notice = context.getString(R.string.cache_cleared)) } }
        disconnect(); reconnect()
    }
    fun checkCommands() = action(lock = null) { reconcileCommands() }
    fun inspectUnknownHistory() = action(lock = null) {
        val chat = _state.value.selected
        val generation = selectionGeneration.get()
        val linkGeneration = connectionGeneration.get()
        _state.update { it.copy(unknownHistoryInspected = false) }
        reconcileCommands()
        require(_state.value.online)
        val computer = _state.value.computerId
        val before = dao.uncertain(computer).filter { it.chatKey == chat?.key || it.chatKey == null }
        if (before.any { it.chatKey == null }) refreshDirectory()
        if (before.any { it.method == "devices/revoke" }) {
            val devices = request("devices/list").asObject().array("data").map(JsonElement::asObject)
            _state.update { it.copy(devices = devices) }
        }
        if (before.any { it.method == "approvals/respond" }) sync()
        if (chat != null) require(hydrate(chat)) { "History changed during inspection" }
        val unresolved = dao.uncertain(computer).filter { it.chatKey == chat?.key || it.chatKey == null }
        if (_state.value.online && linkGeneration == connectionGeneration.get() && _state.value.selected?.key == chat?.key && generation == selectionGeneration.get() && unresolved.isNotEmpty() &&
            unresolved.map { it.id }.toSet() == before.map { it.id }.toSet() && unresolved.all { it.status in setOf("unknown", "not_found") }) {
            inspectedChat = chat?.key
            inspectedCommands = unresolved.map { it.id }.toSet()
            inspectedGeneration = generation
            inspectedConnectionGeneration = linkGeneration
            _state.update { it.copy(unknownHistoryInspected = true) }
        }
    }
    fun resolveUnknownAfterInspection() = action {
        reconciliationLock.withLock {
            val chat = _state.value.selected
            db.withTransaction {
                val current = dao.uncertain(_state.value.computerId).filter { it.chatKey == chat?.key || it.chatKey == null }
                require(_state.value.online && _state.value.unknownHistoryInspected && inspectedChat == chat?.key && inspectedGeneration == selectionGeneration.get() && inspectedConnectionGeneration == connectionGeneration.get() &&
                    current.isNotEmpty() && current.all { it.status in setOf("unknown", "not_found") } &&
                    current.map { it.id }.toSet() == inspectedCommands) { "Inspect the current unresolved commands first" }
                current.forEach { dao.commandStatus(it.id, "reconciled") }
            }
            _state.update { it.copy(unknownHistoryInspected = false, commands = it.commands.filterNot { c -> c.id in inspectedCommands }) }
            inspectedCommands = emptySet(); inspectedChat = null; inspectedGeneration = null; inspectedConnectionGeneration = null
        }
    }
    private suspend fun reconcileCommands() {
        reconciliationLock.withLock {
        val p = pair ?: return@withLock
        for (command in dao.uncertain(p.computerId)) {
            val response = try { request("commands/status", buildJsonObject { put("commandId", command.id) }).asObject() }
            catch (e: WireException) { when (e.code) {
                "not_found" -> buildJsonObject { put("status", "not_found") }
                "remote_error", "unknown" -> buildJsonObject { put("status", "unknown") }
                else -> throw e
            } }
            val status = response.string("status").takeIf { it in setOf("accepted", "rejected", "submitted", "unknown", "not_found") } ?: "unknown"
            dao.commandStatus(command.id, status)
            if (status == "rejected" && command.method == "approvals/respond" && command.draftText.isNotBlank()) {
                synchronized(approvalGate) {
                    val generation = approvalCommandGenerations[command.id]?.second ?: (approvalGenerations[command.draftText] ?: 0L)
                    if ((approvalGenerations[command.draftText] ?: 0L) == generation) rejectedApprovalRefresh[command.draftText] = generation
                }
            }
            if (status == "accepted" && command.method in setOf("turn/start", "turn/steer", "thread/queue/add")) {
                command.chatKey?.let { key ->
                    val snapshot = runCatching { pocketJson.parseToJsonElement(command.draftText).jsonObject }.getOrNull()?.takeIf { it.number("pocketDraft") == 1L }
                    clearAcceptedDraft(key, snapshot?.string("text") ?: command.draftText,
                        snapshot?.array("attachments")?.map { LocalAttachment.from(it.asObject()) }.orEmpty())
                }
            }
        }
        }
        // Pending-request sync can consume resolution events; do not hold the ledger lock.
        if (rejectedApprovalRefresh.isNotEmpty()) sync()
    }

    /** Returns the runtimes whose journal no longer covers our cursor and need a fresh snapshot. */
    private suspend fun sync(): List<String> {
        val transport = client ?: throw WireException("offline", "Offline")
        val refreshingRejected = synchronized(approvalGate) { rejectedApprovalRefresh.toMap() }
        val requestedCursors = eventLock.withLock { cursors.toMap() }
        val response = transport.request("sync", buildJsonObject { put("cursors", buildJsonObject { requestedCursors.forEach { (id, c) -> put(id, c.json()) } }) }).asObject()
        if (client !== transport) return emptyList()
        // This is an authoritative set, independent of journal replay and cursor age.
        val required = response.array("snapshotRequired").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (required.isNotEmpty()) {
            _state.update { it.copy(notice = if (response["pendingRequests"] is JsonArray) context.getString(R.string.history_refreshed) else context.getString(R.string.history_no_approvals)) }
        }
        val computerId = pair?.computerId ?: return required
        // Events received after this sync response are buffered; baselines discard the replay prefix.
        eventLock.withLock {
            if (client !== transport || transport.state.value != LinkState.ONLINE) return@withLock
            val responseCursors = response.obj("cursors").mapNotNull { (runtime, value) ->
                val o = value.asObject()
                o.string("epoch").takeIf(String::isNotBlank)?.let { runtime to Cursor(it, o.number("sequence")) }
            }.toMap()
            val supersededEpochs = responseCursors.filter { (runtime, incoming) ->
                val current = cursors[runtime]
                current != null && current.epoch != incoming.epoch && current.epoch != requestedCursors[runtime]?.epoch
            }.keys
            for (replay in response.array("events")) {
                val envelope = replay.asObject()
                if (envelope.string("runtimeId") !in supersededEpochs) consumeEventLocked(envelope)
            }
            if (client !== transport || transport.state.value != LinkState.ONLINE) return@withLock
            val staleRuntimes = responseCursors.filter { (runtime, incoming) ->
                val current = cursors[runtime]
                current != null && if (current.epoch == incoming.epoch) current.sequence > incoming.sequence
                    else current.epoch != requestedCursors[runtime]?.epoch
            }.keys
            if (response["pendingRequests"] is JsonArray) {
                val uncertainApprovals = dao.uncertain(computerId).filter { it.method == "approvals/respond" }
                val pending = response.array("pendingRequests").mapNotNull { value ->
                    val wrapper = value.asObject(); val event = wrapper.obj("event")
                    if (wrapper.string("runtimeId") in staleRuntimes) return@mapNotNull null
                    val id = event["id"]?.takeUnless { it is JsonNull } ?: return@mapNotNull null
                    val approval = PendingApproval(wrapper.string("runtimeId"), id, event.string("method"), event.obj("params"))
                    if (approval.key in resolvedApprovalKeys) return@mapNotNull null
                    val runtime = _state.value.runtimes.firstOrNull { it.id == approval.runtimeId }
                    val chatKey = runtime?.let { ThreadKey(computerId, it.configStoreId, approval.threadId) }
                    approval.copy(submitted = (approval.key in responding && approval.key !in refreshingRejected.keys) ||
                        uncertainApprovals.any { it.draftText == approval.key || (it.draftText.isEmpty() && it.chatKey != null && it.chatKey == chatKey) })
                }.distinctBy { it.key }
                synchronized(approvalGate) {
                val appliedRejected = refreshingRejected.filter { (key, generation) ->
                    val runtime = runCatching { pocketJson.parseToJsonElement(key).jsonArray.first().jsonPrimitive.content }.getOrNull()
                    runtime != null && runtime !in staleRuntimes && (approvalGenerations[key] ?: 0L) == generation &&
                        rejectedApprovalRefresh[key] == generation
                }
                _state.update { s ->
                    val preserved = s.approvals.filter { it.runtimeId in staleRuntimes }
                    val refreshed = pending.map { approval ->
                        val wasSubmitted = s.approvals.any { it.key == approval.key && it.submitted }
                        approval.copy(submitted = approval.submitted || (wasSubmitted && approval.key !in appliedRejected.keys) ||
                            (approval.key in responding && approval.key !in appliedRejected.keys))
                    }
                    s.copy(approvals = preserved + refreshed, approvalsFresh = true)
                }
                appliedRejected.forEach { (key, generation) -> rejectedApprovalRefresh.remove(key, generation) }
                }
            }
            db.withTransaction {
                responseCursors.forEach { (runtime, incoming) ->
                    val current = cursors[runtime]
                    val chosen = when {
                        current?.epoch == incoming.epoch && current.sequence > incoming.sequence -> current
                        runtime in staleRuntimes -> current ?: incoming
                        else -> incoming // A new authoritative epoch establishes its own sequence baseline.
                    }
                    cursors[runtime] = chosen
                    dao.putCursor(CursorEntity(computerId, runtime, chosen.epoch, chosen.sequence))
                }
            }
        }
        return required
    }

    /**
     * A same-epoch sequence gap or a helper `pocket/resync` means notifications were skipped
     * while the link stayed healthy. Replaying from the journal over the same tunnel keeps the
     * conversation open; reconnecting here was the visible "entered a chat, then disconnected".
     */
    @Volatile private var resyncRequested = false
    private var resyncWindowStart = 0L
    private var resyncCount = 0
    private suspend fun resyncInBand(transport: PocketConnection) {
        if (!resyncRequested || client !== transport) return
        resyncRequested = false
        val now = System.currentTimeMillis()
        if (now - resyncWindowStart > 60_000) { resyncWindowStart = now; resyncCount = 0 }
        // A link that keeps losing events every few seconds is genuinely unhealthy.
        if (++resyncCount > 6) { transport.close(); return }
        connectionDiagnostics.record(connectionGeneration.get(), "resync", "started")
        val required = try { sync() } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { connectionDiagnostics.record(connectionGeneration.get(), "resync", (e as? WireException)?.code ?: "unknown"); transport.close(); return }
        if (client !== transport) return
        connectionDiagnostics.record(connectionGeneration.get(), "resync", "completed")
        runCatching { refreshRuntimes() }
        val selected = _state.value.selected
        if (selected != null && selected.runtimeId in required) scope.launch {
            try { loadConversation(selected) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
        }
    }

    private suspend fun receive(notification: JsonObject) {
        when (notification.string("method")) {
            "codex/event" -> consumeEvent(notification.obj("params"))
            "pocket/resync" -> resyncRequested = true
            "runtimes/changed" -> {
                _state.update { it.copy(runtimes = notification.obj("params").array("data").map { r -> RuntimeInfo.from(r.asObject()) }) }
                directoryRefresh.trySend(Unit)
            }
        }
    }
    private suspend fun receiveBatch(notifications: List<JsonObject>) {
        var index = 0
        while (index < notifications.size) {
            if (notifications[index].string("method") != "codex/event") { receive(notifications[index++]); continue }
            val events = mutableListOf<JsonObject>()
            while (index < notifications.size && notifications[index].string("method") == "codex/event") events += notifications[index++].obj("params")
            eventLock.withLock {
                val runtimes = _state.value.runtimes
                val snapshots = snapshotCursors.filterKeys { it.computerId == pair?.computerId }.flatMap { (key, cursor) -> runtimes.filter { it.configStoreId == key.configStoreId }.map { (it.id to key.nativeId) to cursor } }.toMap()
                // Unmerged events retain the precise snapshot boundary while history is in flight.
                val batches = if (historyCaptures.isEmpty()) EventBatcher.coalesce(events, cursors.toMap(), snapshots)
                    else events.map { EventBatch(it, it.obj("cursor").number("sequence")) }
                batches.forEach { consumeEventLocked(it.envelope, it.firstSequence) }
            }
        }
    }
    private suspend fun consumeEvent(envelope: JsonObject) = eventLock.withLock { consumeEventLocked(envelope) }
    /** Caller holds eventLock so replay epoch validation and consumption cannot interleave with another sync. */
    private suspend fun consumeEventLocked(envelope: JsonObject, firstSequence: Long = envelope.obj("cursor").number("sequence")) {
        val runtime = envelope.string("runtimeId")
        val cur = envelope.obj("cursor")
        val next = Cursor(cur.string("epoch"), cur.number("sequence"))
        val decision = cursors[runtime]?.compare(Cursor(next.epoch, firstSequence))
        if (decision == CursorDecision.DUPLICATE) return
        if (decision == CursorDecision.SNAPSHOT) {
            if (cursors[runtime]?.epoch == next.epoch) {
                // Skipped events in the same journal epoch: keep our cursor and replay in-band.
                // Later events are dropped until the replay restores contiguous order.
                if (!resyncRequested) connectionDiagnostics.record(connectionGeneration.get(), "resync", "cursor_gap")
                resyncRequested = true
                return
            }
            _state.update { it.copy(notice = context.getString(R.string.stream_gap)) }
            cursors.remove(runtime)
            client?.close()
            return
        }
        val event = envelope.obj("event")
        val method = event.string("method")
        val params = event.obj("params")
        val requestId = event["id"]
        if (method == "serverRequest/resolved" || method == "pocket/approvalResolved") {
            val id = params["requestId"] ?: params["id"]
            if (id != null) {
                resolvedApprovalKeys.add(JsonArray(listOf(JsonPrimitive(runtime), id)).toString())
                _state.update { it.copy(approvals = it.approvals.filterNot { a -> a.matches(runtime, id) }) }
            }
            action(lock = null) { reconcileCommands() }
        } else if (requestId != null && requestId !is JsonNull) {
            val pending = PendingApproval(runtime, requestId, method, params)
            if (pending.key !in resolvedApprovalKeys) {
                _state.update { s ->
                    val guarded = s.approvals.any { it.key == pending.key && it.submitted } || pending.key in responding
                    s.copy(approvals = s.approvals.filterNot { a -> a.key == pending.key } + pending.copy(submitted = guarded))
                }
                alerts.tryEmit(context.getString(R.string.alert_input))
            }
        }
        val threadId = params.string("threadId", params.obj("thread").string("id"))
        val info = _state.value.runtimes.firstOrNull { it.id == runtime }
        val p = pair ?: return
        val key = info?.let { ThreadKey(p.computerId, it.configStoreId, threadId) }
        key?.let { historyCaptures[it]?.takeIf { capture -> capture.runtime == runtime }?.add(envelope) }
        if (key != null && threadId.isNotBlank() && (_state.value.selected?.key != key || !foreground)) {
            val title = when {
                requestId != null && requestId !is JsonNull -> context.getString(R.string.alert_input)
                method == "turn/completed" -> context.getString(if (params.obj("turn").string("status") == "failed") R.string.alert_failed else R.string.alert_completed)
                method == "item/completed" && params.obj("item").string("delivery") == "async" -> context.getString(R.string.alert_input)
                else -> null
            }
            if (title != null) conversationAlerts.tryEmit(ConversationAlert(key, title, if (method == "turn/completed") "result" else "attention"))
        }
        if (key != null && _state.value.selected?.key == key) when (method) {
            "thread/tokenUsage/updated" -> _state.update { it.copy(advanced = it.advanced.copy(usage = params["tokenUsage"] as? JsonObject)) }
            "thread/goal/updated" -> _state.update { it.copy(advanced = it.advanced.copy(goal = params["goal"] as? JsonObject)) }
            "thread/goal/cleared" -> _state.update { it.copy(advanced = it.advanced.copy(goal = null)) }
            "thread/settings/updated" -> _state.update { it.copy(advanced = it.advanced.copy(fastMode = params.obj("settings").string("serviceTier", params.string("serviceTier")) == "fast")) }
        }
        db.withTransaction {
            val watermark = key?.let { snapshotCursors[it] ?: dao.snapshot(it)?.let { row -> Cursor(row.epoch, row.sequence).also { c -> snapshotCursors[key] = c } } }
            val owner = key?.let { dao.chat(it) }
            if (key != null && (owner == null || owner.runtimeId == runtime) && threadId.isNotBlank() && requestId == null && watermark?.covers(next) != true && method !in setOf("serverRequest/resolved", "pocket/approvalResolved")) {
                val old = timelines[key] ?: NativeTimeline(items = dao.items(key).associateTo(linkedMapOf()) { it.itemKey to it.item() })
                val updated = NativeReducer.reduce(old, event, "${next.epoch}:${next.sequence}")
                val changed = updated.items.values.filter { old.items[it.key] !== it }
                val order = updated.items.keys.withIndex().associate { it.value to it.index.toLong() }
                dao.putItems(changed.map { ItemEntity.from(key, it, order.getValue(it.key)) })
                timelines[key] = updated
                if (_state.value.selected?.key == key && _state.value.selected?.runtimeId == runtime) _state.update { it.copy(timeline = updated, stopping = stopKey(key, updated.activeTurn) in stoppingTurns) }
                if (method in setOf("item/started", "item/completed")) updateAsyncState(key, updated)
            }
            if (key != null) dao.chat(key)?.let { chat ->
                if (chat.runtimeId == runtime) {
                    val status = when (method) {
                        "turn/started" -> "active"
                        "turn/completed" -> params.obj("turn").string("status", "completed")
                        "thread/status/changed" -> params.obj("status").string("type", chat.status)
                        else -> chat.status
                    }
                    if (status != chat.status) dao.putChat(chat.copy(status = status))
                }
            }
            dao.putCursor(CursorEntity(p.computerId, runtime, next.epoch, next.sequence))
        }
        cursors[runtime] = next
        _state.update { it.copy(lastSyncAt = System.currentTimeMillis()) }
        val visibleCompletion = method == "turn/completed" || (method == "item/completed" && params.obj("item").string("type") == "agentMessage")
        if (key != null && threadId.isNotBlank() && (_state.value.selected?.key != key || !foreground) && visibleCompletion) setUnread(key, true)
        if (method in setOf("thread/started", "thread/archived", "thread/unarchived", "thread/name/updated", "thread/name/changed", "thread/renamed", "thread/closed", "thread/unloaded")) directoryRefresh.trySend(Unit)
        if (method == "thread/queue/changed" && _state.value.selected?.let { it.key == key && it.runtimeId == runtime } == true) queueRefresh.trySend(Unit)
        if (method == "turn/completed") alerts.tryEmit(if (params.obj("turn").string("status") == "failed") context.getString(R.string.alert_failed) else context.getString(R.string.alert_completed))
    }

    private suspend fun persistTimeline(key: ThreadKey, timeline: NativeTimeline, replace: Boolean) {
        db.withTransaction { if (replace) dao.clearItems(key); dao.putItems(timeline.items.values.mapIndexed { index, i -> ItemEntity.from(key, i, index.toLong()) }) }
        timelines[key] = timeline
    }
    fun older(): Job {
        val s = _state.value
        val chat = s.selected ?: return scope.launch {}
        val generation = selectionGeneration.get()
        if (s.historyExhausted || !loadingHistory.add(chat.key)) return scope.launch {}
        _state.update { it.copy(historyLoading = true) }
        return action(lock = null) {
        try {
        val response = native(chat.runtimeId, "thread/turns/list", buildJsonObject { put("threadId", chat.key.nativeId); put("limit", HISTORY_OLDER_PAGE); put("sortDirection", "desc"); put("itemsView", "full"); s.nextTurns?.let { put("cursor", it) } }).asObject()
        val turns = response.array("data").reversed()
        val old = NativeReducer.snapshot(buildJsonObject { put("turns", JsonArray(turns)) })
        eventLock.withLock {
            if (!isSelected(chat, generation)) return@withLock
            val current = timelines[chat.key] ?: NativeTimeline()
            val merged = current.copy(items = LinkedHashMap(old.items).apply { putAll(current.items) })
            persistTimeline(chat.key, merged, true)
            val next = response["nextCursor"]?.takeUnless { v -> v is JsonNull }
            _state.update { it.copy(timeline = merged, nextTurns = next, historyExhausted = next == null || next == s.nextTurns) }
        }
        } finally { loadingHistory.remove(chat.key); _state.update { it.copy(historyLoading = it.selected?.key?.let { key -> key in loadingHistory } == true) } }
        }
    }
    private fun selected() = _state.value.selected ?: throw IllegalStateException("Choose a chat first")

    private suspend fun checkedMutation(chat: ChatEntity, method: String, params: JsonObject): JsonElement {
        validateMutation(chat, method)
        return native(chat.runtimeId, method, JsonObject(params + ("threadId" to JsonPrimitive(chat.key.nativeId))), true, chat.key)
    }
    private suspend fun validateMutation(chat: ChatEntity, method: String) {
        val state = _state.value
        val current = dao.chat(chat.key) ?: error("Conversation is unavailable")
        require(state.online && !current.conflict && current.runtimeId == chat.runtimeId && pair?.computerId == chat.key.computerId)
        require(state.supports(method, state.runtimes.firstOrNull { it.id == chat.runtimeId }))
        require(dao.uncertain(chat.key.computerId).none { it.chatKey == chat.key || it.chatKey == null })
    }

    fun queueEdit(id: String, text: String): Job {
        val chat = selected(); val generation = selectionGeneration.get()
        return action(lock = null) {
            require(text.isNotBlank() && text.length <= 1_000_000)
            val entry = _state.value.nativeQueue.firstOrNull { it.string("id") == id } ?: error("Queued message changed")
            // Preserve attachments and structured references when editing its text.
            val input = listOf(buildJsonObject { put("type", "text"); put("text", text) }) + entry.array("input").filter { it.asObject().string("type") != "text" }
            checkedMutation(chat, "thread/queue/update", buildJsonObject { put("queuedSubmissionId", id); put("input", JsonArray(input)) })
            loadQueue(chat, generation)
        }
    }
    fun queueDelete(id: String): Job {
        val chat = selected(); val generation = selectionGeneration.get()
        return action(lock = null) {
            require(id.isNotBlank())
            checkedMutation(chat, "thread/queue/delete", buildJsonObject { put("queuedSubmissionId", id) })
            loadQueue(chat, generation)
        }
    }
    /** "引导" on a queued row: steer the running turn with that message now, then drop it from the queue. */
    fun queueSteer(id: String): Job {
        val chat = selected(); val generation = selectionGeneration.get()
        return action(lock = null) {
            val entry = _state.value.nativeQueue.firstOrNull { it.string("id") == id } ?: error("Queued message changed")
            val running = (timelines[chat.key] ?: _state.value.timeline).activeTurn ?: error("The turn has finished; the queued message will run next")
            val input = entry.array("input")
            require(input.isNotEmpty())
            // Steer first: if deleting fails afterwards the message is duplicated, never lost.
            checkedMutation(chat, "turn/steer", buildJsonObject {
                put("input", input); put("clientUserMessageId", UUID.randomUUID().toString()); put("expectedTurnId", running)
            })
            if (_state.value.supports("thread/queue/delete")) checkedMutation(chat, "thread/queue/delete", buildJsonObject { put("queuedSubmissionId", id) })
            loadQueue(chat, generation)
        }
    }
    fun queueMove(id: String, beforeId: String?): Job {
        val chat = selected(); val generation = selectionGeneration.get()
        val order = _state.value.nativeQueue.map { it.string("id") }.toMutableList()
        return action(lock = null) {
            require(order.remove(id) && (beforeId == null || beforeId in order))
            order.add(if (beforeId == null) order.size else order.indexOf(beforeId), id)
            checkedMutation(chat, "thread/queue/reorder", buildJsonObject { put("queuedSubmissionIds", JsonArray(order.map(::JsonPrimitive))) })
            loadQueue(chat, generation)
        }
    }
    fun fork(sideChat: Boolean = false, beforeTurnId: String? = null, prompt: String? = null): Job {
        val source = selected(); val generation = selectionGeneration.get()
        return action(lock = null) {
            val response = checkedMutation(source, "thread/fork", buildJsonObject {
                put("attachToSource", sideChat); beforeTurnId?.let { put("beforeTurnId", it) }; put("excludeTurns", true)
            }).asObject().obj("thread")
            val id = response.string("id"); require(id.isNotBlank() && id != source.key.nativeId)
            // The native fork inherits the source name; prefix it so the two chats are never mistaken for one.
            val prefix = if (sideChat) "旁支 · " else "分支 · "
            val chat = source.copy(key = source.key.copy(nativeId = id), title = prefix + response.string("name").ifBlank { source.title }.removePrefix(prefix),
                preview = response.string("preview"), updatedAt = response.number("updatedAt", System.currentTimeMillis() / 1000), status = "idle", archived = false, pinned = false)
            dao.putChat(chat); refreshRuntimes()
            // Same name on the desktop too, so the fork is recognisable there. Best effort.
            if (_state.value.supports("thread/name/set")) try {
                native(chat.runtimeId, "thread/name/set", buildJsonObject { put("threadId", id); put("name", chat.title) }, true, chat.key)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            if (isSelected(source, generation)) {
                select(chat).join(); if (!prompt.isNullOrBlank()) editDraft(prompt)
                alerts.tryEmit("已新建${if (sideChat) "旁支" else "分支"}会话，电脑上会显示为单独的对话；原会话不受影响")
            }
        }
    }
    fun compact(): Job {
        val chat = selected()
        return action(lock = null) { checkedMutation(chat, "thread/compact/start", buildJsonObject {}); if (isSelected(chat)) _state.update { it.copy(notice = "正在压缩上下文") } }
    }
    fun setGoal(objective: String?, paused: Boolean = false): Job {
        val chat = selected()
        return action(lock = null) {
            val method = if (objective == null && !paused) "thread/goal/clear" else "thread/goal/set"
            checkedMutation(chat, method, buildJsonObject {
                put("origin", "user")
                if (method.endsWith("set")) { objective?.trim()?.let { require(it.isNotBlank()); put("objective", it) }; put("status", if (paused) "paused" else "active") }
            })
            loadGoal(chat)
        }
    }
    private suspend fun loadGoal(chat: ChatEntity) {
        if (!_state.value.supports("thread/goal/get", _state.value.runtimes.firstOrNull { it.id == chat.runtimeId })) return
        val result = native(chat.runtimeId, "thread/goal/get", buildJsonObject { put("threadId", chat.key.nativeId) }).asObject()
        if (isSelected(chat)) _state.update { it.copy(advanced = it.advanced.copy(goal = result["goal"] as? JsonObject)) }
    }
    fun setFastMode(enabled: Boolean): Job {
        val chat = selected()
        return action(lock = null) {
            checkedMutation(chat, "thread/settings/update", buildJsonObject { put("serviceTier", if (enabled) JsonPrimitive("fast") else JsonNull) })
            if (isSelected(chat)) _state.update { it.copy(advanced = it.advanced.copy(fastMode = enabled)) }
        }
    }
    fun answerAsync(id: String, answers: JsonObject): Job {
        val chat = selected()
        return action {
            val timeline = timelines[chat.key] ?: error("Conversation is unavailable")
            val pending = AsyncQuestions.pending(timeline, submittedQuestions(chat.key)).firstOrNull { it.string("id") == id } ?: error("Question already answered")
            require(!pending.flag("submitted"))
            val answer = answers.string("answer").ifBlank {
                val entry = answers[id]
                val list = if (entry is JsonArray) entry else (entry as? JsonObject)?.get("answers") as? JsonArray
                list?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString("\n").orEmpty()
            }
            val text = AsyncQuestions.reply(pending, answer)
            val running = timeline.activeTurn
            val method = if (running == null) "turn/start" else "turn/steer"
            validateMutation(chat, method)
            val submitted = submittedQuestions(chat.key) + id
            preferences.edit().putStringSet("questions:${chat.key.encoded}", submitted).commit()
            updateAsyncState(chat.key, timeline)
            try {
                native(chat.runtimeId, method, buildJsonObject {
                    put("threadId", chat.key.nativeId)
                    put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                    put("clientUserMessageId", UUID.randomUUID().toString())
                    running?.let { put("expectedTurnId", it) }
                }, true, chat.key)
            } catch (e: WireException) {
                preferences.edit().putStringSet("questions:${chat.key.encoded}", submitted - id).commit()
                updateAsyncState(chat.key, timelines[chat.key] ?: timeline); throw e
            }
        }
    }
    private fun submittedQuestions(key: ThreadKey) = preferences.getStringSet("questions:${key.encoded}", emptySet()).orEmpty().toSet()
    private fun updateAsyncState(key: ThreadKey, timeline: NativeTimeline) {
        val questions = AsyncQuestions.pending(timeline, submittedQuestions(key))
        _state.update { it.copy(pendingQuestions = if (questions.isEmpty()) it.pendingQuestions - key.encoded else it.pendingQuestions + (key.encoded to questions),
            advanced = if (it.selected?.key == key) it.advanced.copy(asyncQuestions = questions) else it.advanced) }
    }
    fun loadReferences(query: String = ""): Job {
        val chat = selected(); val generation = selectionGeneration.get()
        return action(lock = null) {
            val result = request("references/list", buildJsonObject { put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId); put("query", query.take(256)) }).asObject()
            if (isSelected(chat, generation)) _state.update { it.copy(advanced = it.advanced.copy(references = result.array("data").map(JsonElement::asObject))) }
        }
    }
    fun insertReference(value: JsonObject) {
        require(value in _state.value.advanced.references)
        val prefix = if (value.string("type") == "skill") "$" else "@"
        val token = prefix + (if (value.string("type") == "file") value.string("path") else value.string("name").ifBlank { value.string("path") })
        updateDraft { draft ->
            val refs = runCatching { pocketJson.parseToJsonElement(draft.references).jsonArray }.getOrDefault(JsonArray(emptyList()))
            val updated = JsonObject(value + ("token" to JsonPrimitive(token)))
            draft.copy(text = draft.text + " " + token + " ", references = JsonArray((refs + updated).distinct().takeLast(12)).toString())
        }
    }
    fun browseWorkspace(path: String? = null): Job {
        return browseProjectFiles(path.orEmpty())
    }
    fun browseProjectFiles(path: String = ""): Job {
        val chat = selected(); val generation = selectionGeneration.get()
        val requestGeneration = fileListingGeneration.incrementAndGet()
        val transport = client
        fun current() = isSelected(chat, generation) && client === transport && fileListingGeneration.get() == requestGeneration
        _state.update { it.copy(advanced = it.advanced.copy(projectFilesLoading = true, projectFilesError = null)) }
        return action(lock = null) {
            try {
                require(_state.value.online && _state.value.supports("workspace/list"))
                val result = (transport ?: throw WireException("offline", "Offline")).request("workspace/list", buildJsonObject { put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId); if(path.isNotBlank()) put("path", path) }).asObject()
                val listing = projectFileListing(result)
                _state.update { if (current()) it.copy(advanced = it.advanced.copy(projectFiles = listing, projectFilesLoading = false, projectFilesError = null, workspaceEntries = result.array("entries").map(JsonElement::asObject))) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _state.update { if (current()) it.copy(advanced = it.advanced.copy(projectFilesLoading = false, projectFilesError = safeError(failure))) else it }
            }
        }
    }
    fun readSource(path: String): Job {
        val chat = selected(); val generation = selectionGeneration.get()
        val requestGeneration = fileReadGeneration.incrementAndGet()
        val transport = client
        fun current() = isSelected(chat, generation) && client === transport && fileReadGeneration.get() == requestGeneration
        return action(lock = null) {
            require(_state.value.online && _state.value.supports("workspace/read"))
            val result = (transport ?: throw WireException("offline", "Offline")).request("workspace/read", buildJsonObject { put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId); put("path", path) }).asObject()
            val actualPath = result.string("path", path)
            // Source cwd comes from the native thread; the helper independently checks canonical bounds.
            val roots = (_state.value.advanced.projectFiles?.roots.orEmpty() + chat.cwd).filter { it.isNotBlank() }
            val normalized = actualPath.replace('\\', '/')
            val root = roots.filter { normalized.startsWith(it.replace('\\', '/').trimEnd('/') + "/", ignoreCase = true) }.maxByOrNull { it.length }
            _state.update { if (current()) it.copy(advanced = it.advanced.copy(sourcePreview = SourcePreview(actualPath, result.string("text"), result.flag("truncated"), root))) else it }
        }
    }
    fun clearSourcePreview() { fileReadGeneration.incrementAndGet(); _state.update { it.copy(advanced = it.advanced.copy(sourcePreview = null)) } }

    private val imageCache = File(context.cacheDir, "desktop-images").apply { mkdirs() }
    private val imageFetches = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<File?>>()
    private val imageFailures = ConcurrentHashMap<String, Long>()
    private val imageGate = kotlinx.coroutines.sync.Semaphore(2)
    fun canFetchDesktopImages(): Boolean = _state.value.let { it.online && it.selected != null && it.supports("workspace/image") }

    /**
     * Desktop image bytes over the bounded `workspace/image` chunk API, cached on disk by path.
     * Null when the helper predates the API, the file is gone, or it is not a real image.
     */
    suspend fun desktopImage(path: String): File? {
        val chat = _state.value.selected ?: return null
        val name = java.security.MessageDigest.getInstance("SHA-256").digest("${chat.key.computerId}\u0000$path".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(imageCache, name)
        if (target.isFile && target.length() > 0) return target.also { it.setLastModified(System.currentTimeMillis()) }
        if (!canFetchDesktopImages()) return null
        imageFailures[name]?.let { if (System.currentTimeMillis() - it < 60_000) return null }
        val job = imageFetches.computeIfAbsent(name) { scope.async {
            try {
                imageGate.withPermit {
                    val partial = File(imageCache, "$name.part")
                    partial.outputStream().use { out ->
                        var offset = 0L
                        while (true) {
                            val chunk = request("workspace/image", buildJsonObject {
                                put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId); put("path", path); put("offset", offset)
                            }).asObject()
                            val bytes = android.util.Base64.decode(chunk.string("data"), android.util.Base64.DEFAULT)
                            require(bytes.isNotEmpty() || chunk.flag("eof"))
                            out.write(bytes); offset += bytes.size
                            require(offset <= 24L * 1024 * 1024)
                            if (chunk.flag("eof")) break
                        }
                    }
                    check(partial.renameTo(target))
                    trimImageCache()
                    target
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { imageFailures[name] = System.currentTimeMillis(); File(imageCache, "$name.part").delete(); null }
            finally { imageFetches.remove(name) }
        } }
        return job.await()
    }

    private fun trimImageCache(limit: Long = 96L * 1024 * 1024) {
        val cached = imageCache.listFiles().orEmpty().filter { !it.name.endsWith(".part") }.sortedByDescending { it.lastModified() }
        var total = 0L
        cached.forEach { file -> total += file.length(); if (total > limit) file.delete() }
    }
    fun commentOnFile(path: String, line: Int, comment: String) {
        require(line > 0 && comment.isNotBlank() && path.isNotBlank())
        editDraft(_state.value.draft?.text.orEmpty() + "\n\n关于 $path:$line\n${comment.trim()}")
    }
    fun commentOnFile(comment: FileCommentDraft) {
        require(comment.line > 0 && comment.path.isNotBlank() && comment.text.isNotBlank())
        val side = if (comment.side == ReviewSide.OLD) "旧侧" else "新侧"
        editDraft(_state.value.draft?.text.orEmpty() + "\n\n关于 ${comment.path}:${comment.line}（$side）\n${comment.text.trim()}")
    }
    fun markRead(chat: ChatEntity) {
        preferences.edit().putLong("read:${chat.key.encoded}", System.currentTimeMillis()).apply()
        setUnread(chat.key, false)
    }
    private fun setUnread(key: ThreadKey, unread: Boolean) = synchronized(unreadGate) {
        val keys = preferences.getStringSet("unread", emptySet()).orEmpty().toMutableSet()
        if (unread) keys.add(key.encoded) else keys.remove(key.encoded)
        preferences.edit().putStringSet("unread", keys).apply()
        _state.update { state -> state.copy(unreadKeys = keys.filterTo(mutableSetOf()) { runCatching { ThreadKey.decode(it).computerId == state.computerId }.getOrDefault(false) }) }
    }
    fun inspectConnection() = action(lock = null) {
        val status = request("diagnostics").asObject()
        _state.update { it.copy(connectionDetail = if (status.flag("connected")) "电脑助手已连接 · ${status.array("runtimes").size} 个运行时" else "电脑助手正在恢复连接") }
    }
    fun registerPush(): Job = scope.launch {
        if (!_state.value.pushConfigured) { _state.update { it.copy(pushStatus = "未配置华为推送") }; return@launch }
        if (!_state.value.online) return@launch
        try {
            val status = request("push/status").asObject()
            if (!status.flag("configured")) { _state.update { it.copy(pushStatus = "电脑尚未配置推送服务") }; return@launch }
            val token = dev.codexpocket.app.push.PushRegistration.token(context)
            if (token.isNullOrBlank()) { _state.update { it.copy(pushStatus = "等待华为设备令牌") }; return@launch }
            mutate("push/register", buildJsonObject { put("token", token) })
            _state.update { it.copy(pushStatus = "已启用 · 等待真机送达验证") }
            sendPresence()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.update { it.copy(pushStatus = "注册未完成，可重新尝试") } }
    }
    private suspend fun sendPresence() {
        if ("push/presence" !in _state.value.capabilities) return
        request("push/presence", buildJsonObject { put("foreground", foreground); if (foreground) _state.value.selected?.key?.nativeId?.let { put("threadId", it) } })
    }
    fun openNotification(eventId: String) {
        if (runCatching { UUID.fromString(eventId) }.isFailure) return
        pendingNotification = eventId
        if (!_state.value.online) { reconnect(); return }
        scope.launch {
            try {
                val route = request("push/resolve", buildJsonObject { put("eventId", eventId) }).asObject()
                val threadId = route.string("threadId"); val runtimeId = route.string("runtimeId")
                refreshDirectory()
                val chat = dao.allChats().firstOrNull { it.key.computerId == pair?.computerId && it.key.nativeId == threadId && it.runtimeId == runtimeId }
                if (chat != null && pendingNotification == eventId) { select(chat).join(); pendingNotification = null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(notice = "通知暂不可用，请在会话列表查看") } }
        }
    }
    fun openChat(key: ThreadKey) = scope.launch {
        state.first { it.initialized }
        if (key.computerId != pair?.computerId) return@launch
        dao.chat(key)?.let { select(it).join() }
    }
    private fun action(lock: Mutex? = actionLock, block: suspend () -> Unit): Job = scope.launch {
        suspend fun execute() {
            activeActions.incrementAndGet()
            _state.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { _state.update { it.copy(error = safeError(e)) } }
            finally { activeActions.decrementAndGet(); _state.update { it.copy(busy = activeActions.get() > 0) } }
        }
        if (lock == null) execute() else lock.withLock { execute() }
    }
    private fun safeError(e: Exception): String = when (e) {
        is UnknownOutcomeException -> context.getString(R.string.unknown_error)
        is WireException -> when (e.code) {
            "offline", "disconnected" -> context.getString(R.string.offline_error)
            "revoked", "device_revoked", "unauthorized", "authentication_failed" -> context.getString(R.string.revoked_error)
            "unsupported", "unsupported_method", "method_not_found", "-32601" -> context.getString(R.string.unsupported_error)
            else -> context.getString(R.string.secure_request_error)
        }
        is IllegalArgumentException -> context.getString(R.string.invalid_input_error)
        else -> context.getString(R.string.action_error)
    }
}

