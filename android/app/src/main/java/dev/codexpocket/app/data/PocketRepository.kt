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
    val mediaConnectionEpoch: Long = 0,
    val connection: String = "Offline", val computerName: String = "", val computerId: String = "",
    val runtimes: List<RuntimeInfo> = emptyList(), val chats: List<ChatEntity> = emptyList(),
    val selected: ChatEntity? = null, val timeline: NativeTimeline = NativeTimeline(),
    val draft: DraftEntity? = null, val attachments: List<LocalAttachment> = emptyList(),
    val sharedAttachments: List<LocalAttachment> = emptyList(),
    val approvals: List<PendingApproval> = emptyList(), val commands: List<CommandEntity> = emptyList(),
    val questionDeliveries: List<QuestionReceipt> = emptyList(),
    val approvalsFresh: Boolean = false,
    val models: List<NativeModel> = emptyList(), val model: String? = null, val effort: String? = null,
    val nativeSettings: NativeSettings = NativeSettings(), val settingsFresh: Boolean = false,
    val pendingSettings: Set<NativeSettingsField> = emptySet(),
    val modelCatalogState: ModelCatalogState = ModelCatalogState.Idle, val modelCatalogError: String? = null,
    val pairingPhase: PairingPhase = PairingPhase.Idle,
    val modes: List<NativeMode> = emptyList(), val mode: String? = null,
    val devices: List<JsonObject> = emptyList(), val theme: String = "system", val watch: Boolean = false,
    val glassEffects: Boolean = true,
    val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val directoryLoading: Boolean = false, val actionStatus: Map<String, ActionStatus> = emptyMap(),
    val directoryRefreshing: Boolean = false, val directoryError: String? = null, val directoryUpdatedAt: Long = 0,
    val sessionPreferences: SessionPreferences = SessionPreferences(), val preferencesFresh: Boolean = false,
    val threadAccess: Map<ThreadKey, ThreadAccess> = emptyMap(), val accessFresh: Set<ThreadKey> = emptySet(),
    val threadOwnershipReasons: Map<ThreadKey, OwnershipReason> = emptyMap(),
    val ownershipChecks: Map<ThreadKey, OwnershipCheck> = emptyMap(),
    val nextThreads: JsonElement? = null, val nextTurns: JsonElement? = null,
    val disabledMethods: Set<String> = emptySet(), val capabilities: Set<String> = emptySet(),
    val historyExhausted: Boolean = false, val historyLoading: Boolean = false,
    val conversationLoadState: ConversationLoadState = ConversationLoadState.Idle,
    val conversationLoadError: String? = null,
    val conversationCached: Boolean = false,
    val unknownHistoryInspected: Boolean = false, val stopping: Boolean = false,
    val sendBusy: Boolean = false, val submittingApprovalKeys: Set<String> = emptySet(),
    val nativeQueue: List<JsonObject> = emptyList(), val queueSupported: Boolean? = null,
    val connectionPhase: String = "offline", val connectionDetail: String = "",
    val activeRouteKind: String? = null, val routePhase: String = "offline", val routeFallback: String? = null,
    val relayVersion: RelayVersion? = null, val upstreamVersions: JsonObject? = null,
    val versionsObservedAt: Long = 0, val p2pEnabled: Boolean = true, val publicStunEnabled: Boolean = true,
    val lastSyncAt: Long = 0, val unreadKeys: Set<String> = emptySet(),
    val pendingQuestions: Map<String, List<JsonObject>> = emptyMap(),
    val advanced: AdvancedConversationState = AdvancedConversationState(),
    val quota: QuotaState = QuotaState(),
    val usage: UsageScreenState = UsageScreenState(),
    val assistant: AssistantUiState = AssistantUiState(), val assistantModels: List<NativeModel> = emptyList(),
    val agentChats: List<ChatEntity> = emptyList(),
    val agentStatuses: Map<ThreadKey, AgentLiveStatus> = emptyMap(),
    val resolvedRequests: Map<String, Set<String>> = emptyMap(),
    val agentReader: AgentReaderState? = null,
    val contextSnapshot: ContextSnapshot = ContextSnapshot(),
    val permissions: PermissionState = PermissionState(),
    val resolvedPlans: Set<String> = emptySet(),
    val dismissedPlans: Set<String> = emptySet(),
    val planFeedback: PlanFeedbackDraft? = null,
    val fastPending: Set<ThreadKey> = emptySet(),
    val extensions: ExtensionManagementState = ExtensionManagementState(),
    val messageEdit: MessageEditState? = null,
    val pushConfigured: Boolean = dev.codexpocket.app.push.PushRegistration.configured, val pushStatus: String = "unconfigured",
) {
    val activeRuntime get() = runtimes.firstOrNull { it.id == selected?.runtimeId }
    val currentModel get() = model?.let { selected -> models.firstOrNull { it.model == selected } }
    val modelSelectionLabel get() = currentModel?.name?.takeIf(String::isNotBlank) ?: model ?: "模型未同步"
    val effortSelectionLabel get() = effort ?: ""
    val modeSelectionLabel get() = mode ?: ""
    fun supports(method: String, runtime: RuntimeInfo? = activeRuntime): Boolean {
        if (runtime == null || !runtime.online || "${runtime.id}:$method" in disabledMethods) return false
        val advertised = runtime.capabilities ?: capabilities
        return method in advertised || "native:$method" in advertised
    }
    val unresolvedCommands get() = commands.filter { !it.method.startsWith("assistant/") && (it.chatKey == selected?.key || it.chatKey == null) && CommandPolicy.blocksNewSend(it.status) }
    val uncertainSend get() = unresolvedCommands.any { it.method != "approvals/respond" }
    fun currentAgents(): List<AgentRosterEntry> {
        val parent = selected ?: return emptyList()
        val states = agentStatuses.filterKeys { it.computerId == parent.key.computerId && it.configStoreId == parent.key.configStoreId }
            .mapKeys { it.key.nativeId }
        return agentRoster(timeline, states)
    }
}

/** Turns per history page. Busy agent turns carry hundreds of commands; at 20 one page measured 7.6MB. */
internal const val HISTORY_FIRST_PAGE = 8
internal const val HISTORY_OLDER_PAGE = 10

class PocketRepository(
    private val context: Context,
    private val connectionFactory: (() -> PocketConnection)? = null,
    private val pairStore: PairingStorage = PairStore(context),
    private val db: PocketDatabase = PocketDatabase.create(context),
    private val networkAvailable: Flow<Unit> = networkAvailability(context),
    historyCacheBudget: Long = 32L * 1024 * 1024,
    private val directoryTimeoutMillis: Long = 15_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = db.dao()
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(PocketState(theme = preferences.getString("theme", "system") ?: "system", glassEffects = preferences.getBoolean("glassEffects", true)))
    val state: StateFlow<PocketState> = _state.asStateFlow()
    val alerts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val conversationAlerts = MutableSharedFlow<ConversationAlert>(extraBufferCapacity = 32)
    val threadLinkOpened = MutableSharedFlow<ThreadKey>(extraBufferCapacity = 1)
    private var pair: PairedComputer? = null
    @Volatile private var client: PocketConnection? = null
    private val connectionGate = Any()
    private var connectionJob: Job? = null
    @Volatile private var foreground = false
    private var networkProbe: Job? = null
    private val routeManager = RouteManager()
    private var routeDiscovery: Job? = null
    private var discoveryComputer: String? = null
    private var usageJob: Job? = null
    private var usageComputer: String? = null
    private val assistantRead = Mutex()
    private var assistantComputer: String? = null
    private var assistantModelRuntime: String? = null
    fun loadAssistant(params: JsonObject = buildJsonObject {put("after",0);put("limit",50)}, automaticPrepare: Boolean = true, explicitRead: Boolean = false): Job = scope.launch {
        val requestedComputer=pair?.computerId
        val requestedTransport=client
        if(explicitRead || params.containsKey("before") || params.containsKey("taskId")) assistantRead.lock()
        else if (!assistantRead.tryLock()) return@launch
        val computer = pair?.computerId
        val transport = client
        try {
            if(requestedComputer!=computer || requestedTransport!==transport) return@launch
            if (assistantComputer != computer) {
                assistantComputer = computer; assistantModelRuntime = null
                _state.update { it.copy(assistant = AssistantUiState(), assistantModels = emptyList()) }
            }
            if (!_state.value.online || transport == null) {
                _state.update { it.copy(assistant = it.assistant.copy(loading = false, error = "连接电脑后可继续使用助理")) }; return@launch
            }
            if ("assistant.v1" !in _state.value.capabilities) {
                _state.update { it.copy(assistant = it.assistant.copy(loading = false, error = "电脑助手尚不支持此功能，请更新 Windows 端")) }; return@launch
            }
            if (_state.value.assistant.snapshot == null) _state.update { it.copy(assistant = it.assistant.copy(loading = true)) }
            var raw = transport.request("assistant/read", params, timeoutMillis = 8000).asObject()
            if (client !== transport || pair?.computerId != computer) return@launch
            if (automaticPrepare && !_state.value.commands.any {it.method == "assistant/prepare" && CommandPolicy.blocksNewSend(it.status)} && (raw["enabled"] == JsonPrimitive(false) || raw["ready"] == JsonPrimitive(false))) {
                _state.update {it.copy(assistant = it.assistant.copy(pendingActions = it.assistant.pendingActions + "assistant/prepare"))}
                try {
                    val prepared = mutate("assistant/prepare", buildJsonObject {}).asObject()
                    if (client !== transport || pair?.computerId != computer) return@launch
                    raw = (prepared["snapshot"] as? JsonObject) ?: prepared
                    // Preparation returns the latest snapshot, not the requested history/task page.
                    if (!raw.containsKey("enabled") || params.number("after") > 0 || params.containsKey("before") || params.containsKey("taskId"))
                        raw = transport.request("assistant/read", params, timeoutMillis = 8000).asObject()
                } catch (cancelled: CancellationException) {throw cancelled}
                catch (_: Exception) { /* Retain the readable snapshot and draft while preparation can be retried. */ }
                finally {
                    _state.update {it.copy(assistant = it.assistant.copy(pendingActions = it.assistant.pendingActions - "assistant/prepare"))}
                }
            }
            if (client !== transport || pair?.computerId != computer) return@launch
            val parsed = AssistantSnapshot.from(raw)
            val snapshot = if(params.number("after") > 0 || params.containsKey("before")) parsed.copy(events=(_state.value.assistant.snapshot?.events.orEmpty()+parsed.events).distinctBy {it.id}) else parsed
            val page = raw["conversationPage"] as? JsonObject
            val messages = (page?.array("messages") ?: raw.array("messages")).mapNotNull {item ->
                AssistantChatMessage.from(item.asObject())?.let {message ->
                    if(message.authorName == null && message.memberId != null) message.copy(authorName=snapshot.members.firstOrNull {it.id == message.memberId}?.name) else message
                }
            }
            _state.update { current ->
                val previous=current.assistant
                val sameConversation=snapshot.coordinatorThreadId!=null && previous.snapshot?.coordinatorThreadId==snapshot.coordinatorThreadId
                val older=params.containsKey("before")
                val cursor=if(page!=null) (page["nextBefore"] as? JsonPrimitive)?.longOrNull else (raw["nextBefore"] as? JsonPrimitive)?.longOrNull
                current.copy(assistant = previous.copy(snapshot = snapshot,
                    messages = if(older || params.containsKey("taskId") || sameConversation) (messages + previous.messages).distinctBy {it.id} else messages,
                    nextBefore = if(!older && sameConversation && previous.historyPaged) previous.nextBefore else cursor,
                    historyPaged = older || (sameConversation && previous.historyPaged), loading = false,
                    error = snapshot.preparationIssue))
            }
            val runtime = snapshot.runtimeId
            if (snapshot.prepared && runtime != null && runtime != assistantModelRuntime) {
                val found = linkedMapOf<String, NativeModel>(); var cursor: JsonElement? = null
                val seen = mutableSetOf<JsonElement>()
                do {
                    val response = transport.request("codex/request", buildJsonObject { put("runtimeId", runtime); put("method", "model/list");
                        put("params", buildJsonObject { put("limit", 100); cursor?.let { put("cursor", it) } }) }, timeoutMillis = 8000).asObject()
                    response.array("data").map { it.asObject() }.filterNot { it.flag("hidden") }.map(NativeModel::from).forEach { found[it.model] = it }
                    cursor = response["nextCursor"]?.takeUnless { it == JsonNull }
                    if (cursor != null && !seen.add(cursor!!)) throw WireException("invalid_reply", "Model cursor repeated")
                } while (cursor != null && found.size < 1000)
                if (client === transport && pair?.computerId == computer) {
                    assistantModelRuntime = runtime; _state.update { it.copy(assistantModels = found.values.toList()) }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (client === transport && pair?.computerId == computer) _state.update { it.copy(assistant = it.assistant.copy(loading = false, error = "助理状态暂未读取，可重试；任务不会重复提交")) } }
        finally { assistantRead.unlock() }
    }
    fun assistantAction(action: AssistantAction): Job = scope.launch {
        if (action.method == "assistant/read") {
            _state.update {it.copy(assistant=it.assistant.copy(pendingActions=it.assistant.pendingActions+"assistant/read"))}
            try {loadAssistant(action.params,explicitRead=true).join()}
            finally {_state.update {it.copy(assistant=it.assistant.copy(pendingActions=it.assistant.pendingActions-"assistant/read"))}}
            return@launch
        }
        if (!_state.value.online || client == null) {
            _state.update { it.copy(assistant = it.assistant.copy(error = "连接电脑后可继续使用助理")) }
            return@launch
        }
        if(action.method=="assistant/send" && _state.value.assistant.snapshot?.prepared!=true) {
            _state.update {it.copy(assistant=it.assistant.copy(error=it.assistant.snapshot?.preparationIssue ?: "助理尚未准备就绪，请重试准备"))}
            return@launch
        }
        if (_state.value.assistant.pendingActions.contains(action.method)) return@launch
        if (_state.value.commands.any { it.method == action.method && CommandPolicy.blocksNewSend(it.status) }) {
            _state.update { it.copy(assistant = it.assistant.copy(error = "这项操作正在自动核对，请稍候")) }; checkCommands(); return@launch
        }
        _state.update { it.copy(assistant = it.assistant.copy(pendingActions = it.assistant.pendingActions + action.method, error = null)) }
        try {
            mutate(action.method, action.params)
            _state.update { it.copy(assistant = it.assistant.copy(sendAcknowledgement = it.assistant.sendAcknowledgement + if (action.method == "assistant/send") 1 else 0)) }
            loadAssistant(automaticPrepare = action.method != "assistant/prepare").join()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { _state.update { it.copy(assistant = it.assistant.copy(error = if (error is UnknownOutcomeException) "结果正在同步，已保留内容，不会重复提交" else safeError(error))) } }
        finally { _state.update { it.copy(assistant = it.assistant.copy(pendingActions = it.assistant.pendingActions - action.method)) } }
    }
    fun openAssistantThread(runtimeId: String, threadId: String): Job = scope.launch {
        refreshDirectory()
        val chat = dao.allChats().firstOrNull { it.key.computerId == pair?.computerId && it.key.nativeId == threadId && it.runtimeId == runtimeId }
            ?: dao.allChats().firstOrNull { it.key.computerId == pair?.computerId && it.key.nativeId == threadId }
        if (chat != null) select(chat).join() else _state.update { it.copy(assistant = it.assistant.copy(error = "原会话尚未同步，请稍后重试")) }
    }
    /** Read-only link routing. The URI cannot supply a runtime, profile, method or command. */
    fun openThreadLink(url: String, origin: ThreadKey? = _state.value.selected?.key): Job {
        val selected = _state.value.selected?.key
        val selection = selectionGeneration.get()
        val connection = connectionGeneration.get()
        val transport = client
        val computer = pair?.computerId
        fun current() = selected == _state.value.selected?.key && selection == selectionGeneration.get() &&
            connection == connectionGeneration.get() && transport === client && computer == pair?.computerId
        return scope.launch {
            fun reject(message: String) { _state.update { if (current()) it.copy(error = message) else it } }
            val parsed = ThreadDeepLink.parse(url)
            if (parsed == null) { reject("会话链接格式无效，仅支持 codex://threads/原生会话ID"); return@launch }
            if (computer.isNullOrBlank() || !_state.value.paired) { reject("请先配对链接所属的电脑"); return@launch }
            if (origin?.computerId?.let { it != computer } == true) { reject("此链接的来源电脑与当前配对不一致，请切回原电脑"); return@launch }
            if (!_state.value.online || transport == null) { reject("电脑离线；会话链接可复制，连接后再打开"); return@launch }
            try {
                var chats = dao.allChats()
                if (!current()) return@launch
                var resolution = resolveThreadDeepLink(parsed.nativeId, computer, origin, chats, _state.value.runtimes)
                if (resolution is ThreadLinkResolution.Rejected && resolution.missing) {
                    // Query the authenticated directory only. Never try arbitrary IDs against every native runtime.
                    val found = mutableListOf<ChatEntity>()
                    for (archived in listOf(false, true)) {
                        var cursor: JsonElement? = null
                        val seen = mutableSetOf<JsonElement>()
                        for (page in 0 until 4) {
                            if (!current()) return@launch
                            val response = transport.request("threads/list", buildJsonObject {
                                put("limit", 100); put("archived", archived); put("search", "")
                                cursor?.let { put("cursor", it) }
                            }, timeoutMillis = directoryTimeoutMillis).asObject()
                            if (!current()) return@launch
                            for (value in response.array("data")) {
                                val row = value.asObject()
                                if (row.string("id") != parsed.nativeId) continue
                                val runtime = _state.value.runtimes.firstOrNull { it.id == row.string("runtimeId") } ?: continue
                                val store = row.string("configStoreId", runtime.configStoreId)
                                if (store != runtime.configStoreId || row.string("source", runtime.source) != runtime.source) continue
                                val parent = row.string("parentThreadId").takeIf(String::isNotBlank)
                                found += ChatEntity(ThreadKey(computer, store, parsed.nativeId), runtime.id, runtime.source,
                                    row.string("name").ifBlank { "会话" }, row.string("preview"), row.string("cwd"), row.number("updatedAt"),
                                    row.string("status"), row.flag("conflict"), archived, isSubagent = row.flag("isSubagent") || parent != null,
                                    parentThreadId = parent, relationSource = if (parent != null || row.flag("isSubagent")) "native" else null)
                            }
                            cursor = response["nextCursor"]?.takeUnless { it is JsonNull }
                            if (cursor == null || !seen.add(cursor)) break
                        }
                    }
                    chats = chats + found
                    resolution = resolveThreadDeepLink(parsed.nativeId, computer, origin, chats, _state.value.runtimes)
                }
                if (!current()) return@launch
                if (resolution is ThreadLinkResolution.Rejected) { reject(resolution.message); return@launch }
                val chat = (resolution as ThreadLinkResolution.Open).chat
                if (!_state.value.supports("thread/read", _state.value.runtimes.firstOrNull { it.id == chat.runtimeId })) {
                    reject("此会话的运行入口不支持读取，请更新电脑助手"); return@launch
                }
                val proof = transport.request("codex/request", buildJsonObject {
                    put("runtimeId", chat.runtimeId); put("method", "thread/read")
                    put("params", buildJsonObject { put("threadId", parsed.nativeId); put("includeTurns", false) })
                }, timeoutMillis = directoryTimeoutMillis).asObject().obj("thread")
                if (!current()) return@launch
                if (proof.string("id") != parsed.nativeId) { reject("电脑返回的会话身份不一致，未打开目标"); return@launch }
                val latest = resolveThreadDeepLink(parsed.nativeId, computer, origin, dao.allChats() + chat, _state.value.runtimes)
                if (!current()) return@launch
                if (latest !is ThreadLinkResolution.Open || latest.chat.runtimeId != chat.runtimeId) {
                    reject("会话归属已变化，请刷新列表后重试"); return@launch
                }
                val verified = chat.copy(cwd = proof.string("cwd", chat.cwd),
                    title = proof.string("agentNickname").ifBlank { proof.string("name").ifBlank { chat.title } })
                dao.putChat(verified)
                if (!current()) return@launch
                select(verified).join()
                if (_state.value.selected?.key == verified.key && computer == pair?.computerId && connection == connectionGeneration.get())
                    threadLinkOpened.emit(verified.key)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reject("此会话暂不可读取，请确认当前电脑的原运行入口和配置档案后重试") }
        }
    }
    private val usageRevision = AtomicLong(0)
    private val usageCache = object : LinkedHashMap<UsageFilter, UsageSnapshot>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UsageFilter, UsageSnapshot>?): Boolean = size > 16
    }
    fun loadUsage(filter: UsageFilter = _state.value.usage.filter): Job {
        val selected = pair?.computerId
        val transport = client
        val revision = usageRevision.incrementAndGet()
        usageJob?.cancel()
        val cached = synchronized(usageCache) {
            if (usageComputer != selected) { usageCache.clear(); usageComputer = selected }
            usageCache[filter]
        }
        val canQuery = selected != null && _state.value.online && "usage/read" in _state.value.capabilities
        _state.update { it.copy(usage = UsageScreenState(filter, cached,
            phase = when { selected == null -> UsagePhase.NotConnected; !it.online -> UsagePhase.Offline;
                !canQuery -> UsagePhase.UnsupportedHelper; cached == null -> UsagePhase.Loading; else -> UsagePhase.Ready },
            refreshing = canQuery, canQuery = canQuery, cached = cached != null)) }
        return scope.launch {
            if (!canQuery || transport == null) return@launch
            try {
                val raw = transport.request("usage/read", buildJsonObject { put("source_id", "cc_switch"); put("query", filter.toJson()) }, timeoutMillis = 8000).asObject()
                val response = UsageResponse.fromJson(raw)
                if (client !== transport || pair?.computerId != selected || revision != usageRevision.get()) return@launch
                val sampled = response.cache?.sampledAt ?: (System.currentTimeMillis() / 1000)
                val snapshot = response.data?.let { UsageSnapshot(filter, response, sampled) }
                if (snapshot != null) synchronized(usageCache) { usageCache[filter] = snapshot }
                _state.update { it.copy(usage = UsageScreenState(filter, snapshot ?: cached, response.phase,
                    refreshing = false, canQuery = true, cached = response.cache?.state == "stale" || snapshot == null && cached != null)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (client === transport && pair?.computerId == selected && revision == usageRevision.get())
                    _state.update { it.copy(usage = it.usage.copy(refreshing = false,
                        phase = if (!it.online) UsagePhase.Offline else if ((error as? WireException)?.code == "unsupported") UsagePhase.UnsupportedHelper else UsagePhase.Failed,
                        cached = cached != null)) }
            }
        }.also { usageJob = it }
    }
    private fun newConnection(): PocketConnection = connectionFactory?.invoke() ?: RoutingPocketConnection(routeManager).apply {
        p2pEnabled = preferences.getBoolean("p2pEnabled", true)
        usePublicStun = preferences.getBoolean("publicStunEnabled", true)
    }
    private fun startRouteDiscovery(restart: Boolean = false) {
        val id = pair?.computerId
        if (connectionFactory != null || id == null || (!foreground && !_state.value.watch)) return
        if (!restart && discoveryComputer == id && routeDiscovery?.isActive == true) return
        routeDiscovery?.cancel(); discoveryComputer = id
        val generation = routeManager.networkGeneration
        routeDiscovery = scope.launch {
            lanDiscovery(context, id).catch { }.collect { routes ->
                if (pair?.computerId == id && routeManager.updateDiscoveredRoutes(routes, generation)) reconnectWakeup.trySend(Unit)
            }
        }
    }
    private fun persistRouteDirectory(hello: JsonObject, current: PairedComputer) {
        if (!hello.containsKey("routes") || pair !== current) return
        runCatching {
            val routes = hello.array("routes").map { ConnectionRoute.parse(it.asObject()) }
            // An empty transient directory must not erase the last usable pairing endpoint.
            val updated = current.config.copy(routes = routes.ifEmpty { current.config.routes }, computerId = current.computerId,
                policy = hello.string("routePolicy").takeIf(String::isNotBlank)?.let(ConnectionPolicy::parse) ?: current.config.policy)
            val next = PairedComputer(updated, current.deviceId, current.deviceSecret, current.computerId, current.computerName)
            pairStore.save(next); pair = next
        }
    }
    @Volatile private var pendingNotification: String? = null
    private val eventLock = Mutex()
    private val actionLock = Mutex()
    private val draftLock = Mutex()
    private val draftGate = Any()
    private val unreadGate = Any()
    private val planResolutionGate = Any()
    private val fastUpdates = ConcurrentHashMap.newKeySet<ThreadKey>()
    private val verifiedHistoryLinks = BoundedCache<ThreadKey, Long>(24)
    private val historyVerificationEpoch = AtomicLong()
    private val drafts = ConcurrentHashMap<ThreadKey, DraftEntity>()
    private val activeActions = AtomicInteger()
    private val directoryGate = Any()
    private var directoryActions = 0
    private val preferencesGeneration = AtomicLong()
    private val accessGenerations = ConcurrentHashMap<ThreadKey, AtomicLong>()
    private val accessUpdates = ConcurrentHashMap.newKeySet<ThreadKey>()
    private val ownershipRechecks = ConcurrentHashMap.newKeySet<ThreadKey>()
    private val selectionGeneration = AtomicLong()
    private val catalogGeneration = AtomicLong()
    private val settingsGate = Any()
    private val settingsReadGeneration = AtomicLong()
    private data class CachedSettings(val runtimeId: String, val record: NativeSettingsRecord, val verifiedLink: Long? = null)
    // Access only under settingsGate; cache is scoped to computer/store/thread and current owner.
    private val settingsByChat = mutableMapOf<ThreadKey, CachedSettings>()
    private val fileListingGeneration = AtomicLong()
    private val fileReadGeneration = AtomicLong()
    private val connectionGeneration = AtomicLong()
    private val agentReadGeneration = AtomicLong()
    private val contextReadGeneration = AtomicLong()
    private val permissionReadGeneration = AtomicLong()
    private val contextRevisions = ConcurrentHashMap<ThreadKey, Long>()
    private val permissionUpdates = ConcurrentHashMap.newKeySet<ThreadKey>()
    private val extensionGeneration = AtomicLong()
    private val extensionGate = Any()
    private val extensionCache = BoundedCache<ExtensionCacheKey, CachedExtensions>(12)
    private val extensionFlights = mutableMapOf<ExtensionCacheKey, Pair<Long, Job>>()
    private val extensionReadTickets = mutableMapOf<ExtensionCacheKey, Long>()
    private val extensionMutationVersions = BoundedCache<ExtensionCacheKey, Long>(24)
    private val extensionUpdates = ConcurrentHashMap.newKeySet<String>()
    private val messageEditGeneration = AtomicLong()
    private val messageEditGate = Any()
    private val messageEdits = ConcurrentHashMap<ThreadKey, MessageEditState>()
    // Quota generation/flight state is independent of conversation and transport locks.
    private val quotaGenerationGate = Any()
    private val quotaSourcesGeneration = AtomicLong()
    private val quotaReadGeneration = AtomicLong()
    private var quotaSourcesFlight: Pair<QuotaRequestTicket, Job>? = null
    private var quotaReadFlight: Triple<QuotaRequestTicket, String, Job>? = null
    private var quotaSourceScope: QuotaRequestScope? = null
    // End quota generation/flight state.
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
    private data class DirectoryRequest(val query: DirectoryQuery, val link: Long, val more: Boolean)
    private data class DirectoryStamp(val link: Long, val nanos: Long, val next: JsonElement?)
    private val directoryJobs = mutableMapOf<DirectoryRequest, Job>()
    private val directoryStamps = mutableMapOf<DirectoryQuery, DirectoryStamp>()
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
    private val completionReads = ConcurrentHashMap.newKeySet<String>()
    private val reconciliationLock = Mutex()
    private val questionDeliveryLock = Mutex()
    private var inspectedChat: ThreadKey? = null
    private var inspectedCommands: Set<String> = emptySet()
    private var inspectedGeneration: Long? = null
    private var inspectedConnectionGeneration: Long? = null
    private val timelines = TimelineCache(historyCacheBudget)
    private val conversationWindows = BoundedCache<ThreadKey, ConversationWindow>(24)
    private val cursors = ConcurrentHashMap<String, Cursor>()
    private val snapshotCursors = ConcurrentHashMap<ThreadKey, Cursor>()
    private val files = File(context.filesDir, "attachments").apply { mkdirs() }

    init {
        scope.launch {
            for (ignored in directoryRefresh) {
                delay(350)
                while (directoryRefresh.tryReceive().isSuccess) { }
                if (_state.value.online) try {
                    synchronized(directoryGate) { directoryStamps.clear() }
                    // Bound a complete pass, including runtime reads and lock contention.
                    withTimeout(directoryTimeoutMillis) { refreshRuntimes(); refreshDirectory() }
                } catch (_: TimeoutCancellationException) {
                    _state.update { it.copy(directoryError = "会话列表更新超时，已保留缓存，可重试") }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { _state.update { it.copy(directoryError = "会话列表暂未更新，已保留缓存，可重试") } }
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
                routeManager.networkGenerationChanged(routeManager.networkGeneration + 1)
                startRouteDiscovery(restart = true)
                reconnectWakeup.trySend(Unit)
                if (foreground || _state.value.watch) {
                    // Availability/validation callbacks do not imply a broken socket.
                    // Probe the current generation; a late failed probe cannot close its replacement.
                    if (_state.value.online) {
                        if (networkProbe?.isActive != true) networkProbe = scope.launch {
                            val current = client ?: return@launch
                            try {
                                current.request("runtimes/list", timeoutMillis = 1500)
                                if (current is RoutingPocketConnection) tryRouteChange(current)
                            }
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
                // Pair identity must not depend on restoring every cached conversation.
                // One legacy/corrupt history row must not hide a healthy authenticated directory.
                _state.update { it.copy(paired = pair != null, computerName = pair?.computerName.orEmpty(), computerId = pair?.computerId.orEmpty()) }
                var cachedChats = dao.allChats().filter { it.key.computerId == pair?.computerId }
                val cachedCommands = pair?.let { dao.uncertain(it.computerId) }.orEmpty()
                val unread = preferences.getStringSet("unread", emptySet()).orEmpty().filterTo(mutableSetOf()) { runCatching { ThreadKey.decode(it).computerId == pair?.computerId }.getOrDefault(false) }
                val cachedQuestions = cachedChats.mapNotNull { chat ->
                    try {
                        val cached = NativeTimeline(items = db.withTransaction { dao.cachedItems(chat.key) }.associateTo(linkedMapOf()) { it.itemKey to it.item() }).withCachedTurns()
                        classifyAgentChildren(chat, cached)
                        AsyncQuestions.pending(cached, submittedQuestions(chat.key)).takeIf { it.isNotEmpty() }?.let { chat.key.encoded to it }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        connectionDiagnostics.record(0, "cached_history", "unknown", failure)
                        null // Preserve raw cache and drafts; this conversation can reload from native history.
                    }
                }.toMap()
                cachedChats = dao.allChats().filter { it.key.computerId == pair?.computerId }
                pair?.let { p -> dao.cursors(p.computerId).forEach { cursors[it.runtimeId] = Cursor(it.epoch, it.sequence) } }
                _state.update { it.copy(initialized = true, paired = pair != null, computerName = pair?.computerName.orEmpty(), computerId = pair?.computerId.orEmpty(), chats = cachedChats.filterNot { c -> c.isSubagent }, agentChats = cachedChats.filter { c -> c.isSubagent }, commands = cachedCommands, unreadKeys = unread, pendingQuestions = cachedQuestions) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                connectionDiagnostics.record(0, "initialization", "unknown", failure)
                _state.update { it.copy(initialized = true, error = context.getString(R.string.pair_unlock_error)) }
            }
            if (foreground) startConnection(restartPending = false)
        }
        scope.launch { dao.chats().collect { list ->
            val before = _state.value.selected
            val after = list.firstOrNull { it.key == before?.key }
            val ownerChanged = before != null && after != null && (before.runtimeId != after.runtimeId || before.conflict != after.conflict)
            if (ownerChanged) {
                selectionGeneration.incrementAndGet(); invalidateHistoryVerification()
                val previous = before!!
                _state.update { s -> s.copy(agentStatuses = s.agentStatuses.filterKeys {
                    it.computerId != previous.key.computerId || it.configStoreId != previous.key.configStoreId
                }) }
            }
            _state.update { s -> s.copy(chats = list.filter { it.key.computerId == s.computerId && !it.isSubagent }, agentChats = list.filter { it.key.computerId == s.computerId && it.isSubagent }, selected = s.selected?.let { c -> list.firstOrNull { it.key == c.key } ?: c },
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
        if (value) startRouteDiscovery() else if (!_state.value.watch) { routeDiscovery?.cancel(); routeDiscovery = null }
        if (_state.value.online) scope.launch { runCatching { sendPresence() } }
        if (value) reconnect() else if (!_state.value.watch) disconnect()
    }
    fun setWatch(value: Boolean) { _state.update { it.copy(watch = value) }; if (value) reconnect() else if (!foreground) disconnect() }
    fun theme(value: String) { require(value in setOf("system", "light", "dark")); preferences.edit().putString("theme", value).apply(); _state.update { it.copy(theme = value) } }
    fun glassEffects(value: Boolean) { preferences.edit().putBoolean("glassEffects", value).apply(); _state.update { it.copy(glassEffects = value) } }
    fun clearError() = _state.update { it.copy(error = null) }
    fun reportError(message: String) = _state.update { it.copy(error = message) }

    fun reconnect() { startConnection(restartPending = true) }

    private fun startConnection(restartPending: Boolean) = synchronized(connectionGate) {
        if (!_state.value.initialized || pair == null) return@synchronized
        startRouteDiscovery()
        if (connectionJob?.isActive == true) {
            if (!restartPending || _state.value.online) { reconnectWakeup.trySend(Unit); return@synchronized }
            connectionJob?.cancel(); client?.close(); connectionJob = null
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ownerContext = currentCoroutineContext()
            var attempt = 0
            while (isActive && (foreground || _state.value.watch)) {
                while (reconnectWakeup.tryReceive().isSuccess) { /* Coalesce network/manual wakeups. */ }
                val currentPair = pair ?: break
                val transport = newConnection()
                var generation = 0L
                var stage = "transport"
                try {
                    // A cancelled factory/connector may return after its replacement is live.
                    // Claim ownership before changing the client, generation or visible state.
                    synchronized(connectionGate) {
                        ownerContext.ensureActive()
                        if (connectionJob !== ownerContext[Job]) throw CancellationException("Connection superseded")
                        generation = connectionGeneration.incrementAndGet()
                        client = transport
                    }
                    fun requireCurrent() {
                        ownerContext.ensureActive()
                        if (client !== transport || connectionGeneration.get() != generation) throw CancellationException("Connection superseded")
                    }
                    connectionDiagnostics.record(generation, stage, "started")
                    _state.update { it.copy(connection = if (attempt == 0) "Connecting" else "Reconnecting", connectionPhase = "connecting", connectionDetail = "正在建立加密连接", online = false, approvalsFresh = false, settingsFresh = false, accessFresh = emptySet(), threadOwnershipReasons = emptyMap(), ownershipChecks = emptyMap(), preferencesFresh = false) }
                    val credentials = buildJsonObject { put("protocol", 1); put("deviceId", currentPair.deviceId); put("deviceSecret", currentPair.deviceSecret) }
                    val authenticated = if (transport is RoutingPocketConnection) transport.connectAuthenticated(currentPair.config, credentials)
                        else { transport.connect(currentPair.config); null }
                    requireCurrent()
                    stage = "authentication"
                    _state.update { it.copy(connectionPhase = "authenticating", connectionDetail = "正在验证电脑身份") }
                    val hello = authenticated ?: transport.request("hello", credentials).asObject()
                    requireCurrent()
                    require(hello.number("protocol") == 1L && hello.string("computerId") == currentPair.computerId) { "Protocol or computer identity mismatch" }
                    persistRouteDirectory(hello, currentPair)
                    val runtimeList = hello.array("runtimes").map { RuntimeInfo.from(it.asObject()) }
                    _state.update { it.copy(runtimes = runtimeList, capabilities = hello.array("capabilities").mapNotNull { v -> (v as? JsonPrimitive)?.contentOrNull }.toSet(), disabledMethods = emptySet(), computerName = hello.string("computerName", currentPair.computerName)) }
                    resolvedApprovalKeys.clear()
                    resyncRequested = false; resyncCount = 0
                    _state.update { it.copy(agentStatuses = emptyMap(), resolvedRequests = emptyMap()) }
                    coroutineScope {
                        val buffered = Channel<JsonObject>(512)
                        launch {
                            try {
                                transport.notifications.collect { notification ->
                                    // Keep the cursor-bearing notification until the ordered
                                    // consumer releases eventLock; sustained stalls still fail closed.
                                    if (!buffered.trySend(notification).isSuccess &&
                                        withTimeoutOrNull(5_000) { buffered.send(notification); true } != true) {
                                        connectionDiagnostics.record(generation, "repository_notifications", "backpressure")
                                        throw WireException("backpressure", "Event consumer stalled")
                                    }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                // close() can race scope cancellation and close the flow with
                                // its transport error. Cancellation remains cancellation.
                                currentCoroutineContext().ensureActive()
                                throw failure
                            }
                            finally { buffered.close() }
                        }
                        _state.update { it.copy(connectionPhase = "syncing", connectionDetail = "正在恢复对话与待处理事项") }
                        stage = "synchronization"
                        sync()
                        requireCurrent()
                        val restoredSelection = _state.value.selected
                        val restoredSelectionGeneration = selectionGeneration.get()
                        _state.update { it.copy(online = true, mediaConnectionEpoch = generation, connection = "Connected", connectionPhase = "ready", connectionDetail = "", lastSyncAt = System.currentTimeMillis(), error = null, pairingPhase = if (it.pairingPhase == PairingPhase.Synchronizing) PairingPhase.Paired else it.pairingPhase) }
                        updateRouteEvidence(transport)
                        inspectConnection()
                        stage = "connected"
                        connectionDiagnostics.record(generation, stage, "ready")
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
                        launch { runCatching { reconcileCommands() } }
                        // Metadata failures cannot hold event delivery or reconnect hostage.
                        launch { runCatching { refreshSessionPreferences(); refreshThreadAccess() } }
                        launch { runCatching { registerPush() } }
                        launch { runCatching { if (runtimeList.isEmpty()) refreshRuntimes(); refreshChats(); pendingNotification?.let { openNotification(it) } } }
                        launch {
                            try {
                                restoredSelection?.takeIf { isSelected(it, restoredSelectionGeneration) }?.let { chat ->
                                    coroutineScope {
                                        launch { loadConversation(chat, restoredSelectionGeneration) }
                                        launch { runCatching { refreshContext() } }
                                        launch { runCatching { refreshPermissions() } }
                                        launch { runCatching { loadModels(chat.runtimeId) } }
                                        launch { runCatching { loadQueue(chat, restoredSelectionGeneration) } }
                                    }
                                    _state.value.agentReader?.child?.key?.nativeId?.let { openAgentReader(it) }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { if (client === transport) _state.update { it.copy(notice = "部分资料尚未更新，当前对话仍可继续") } }
                        }
                        launch {
                            var missed = 0
                            while (isActive) {
                                delay(if (foreground) 5_000 else 15_000)
                                val quiet = System.nanoTime() / 1_000_000 - transport.lastApplicationActivityMillis
                                if (transport.lastApplicationActivityMillis == 0L || quiet >= 5_000) {
                                    try {
                                        val method = if ("ping" in _state.value.capabilities) "ping" else "runtimes/list"
                                        if (transport is RoutingPocketConnection) transport.heartbeat(method, timeoutMillis = 2_000)
                                        else withTimeout(2_000) { transport.request(method, timeoutMillis = 2_000) }
                                        missed = 0
                                    } catch (failure: TimeoutCancellationException) {
                                        if (++missed >= 2) throw WireException("timeout", "Heartbeat timeout")
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { if (++missed >= 2) throw failure }
                                } else {
                                    missed = 0
                                }
                                if (client === transport) { _state.update { it.copy(lastSyncAt = System.currentTimeMillis()) }; updateRouteEvidence(transport) }
                            }
                        }
                        launch {
                            while (isActive) {
                                delay(15_000)
                                runCatching { sendPresence() }
                                if (transport is RoutingPocketConnection) {
                                    refreshRoutes(transport)
                                    tryRouteChange(transport)
                                }
                            }
                        }
                        transport.state.first { it == LinkState.OFFLINE }
                        throw WireException(transport.failureCode ?: "disconnected", "Disconnected")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    ownerContext.ensureActive()
                    val failureStage = (transport as? PocketClient)?.backpressureSource ?: stage
                    connectionDiagnostics.record(generation, failureStage, transport.failureCode ?: (e as? WireException)?.code ?: "unknown", e)
                    val authorizationRequired = e is WireException && e.code in setOf("revoked", "unauthorized", "device_revoked", "authentication_failed")
                    if (client === transport) _state.update { it.copy(online = false, settingsFresh = false, connection = "Offline", connectionPhase = if (authorizationRequired) "authorization_required" else "recovering", connectionDetail = safeError(e), error = null,
                        pairingPhase = if (it.pairingPhase == PairingPhase.Synchronizing && authorizationRequired) PairingPhase.Failed else it.pairingPhase) }
                    if (authorizationRequired) break
                } finally {
                    transport.close()
                    synchronized(connectionGate) { if (client === transport) {
                        client = null
                        _state.update { it.copy(online = false, settingsFresh = false, conversationLoadState = ConversationLoadState.Idle, connection = "Offline", modelCatalogState = ModelCatalogState.Offline, approvalsFresh = false, unknownHistoryInspected = false, nativeQueue = emptyList(), queueSupported = null) }
                    } }
                }
                val backoff = minOf(30_000L, 1_000L shl attempt.coerceAtMost(5))
                val awakened = withTimeoutOrNull(backoff + kotlin.random.Random.nextLong(0, maxOf(1, backoff / 4))) { reconnectWakeup.receive(); true } == true
                if (awakened) attempt = 0 else attempt++
            }
        }
        connectionJob = job
        job.start()
    }

    private fun disconnect() = synchronized(connectionGate) { connectionJob?.cancel(); connectionJob = null; client?.close(); client = null; _state.update { it.copy(online = false, settingsFresh = false, conversationLoadState = ConversationLoadState.Idle, connection = "Offline", connectionPhase = "offline", activeRouteKind = null, relayVersion = null, modelCatalogState = ModelCatalogState.Offline, approvalsFresh = false, unknownHistoryInspected = false, nativeQueue = emptyList(), queueSupported = null) } }

    fun reloadConversation(): Job = _state.value.selected?.let { select(it, forceRefresh = true) } ?: scope.launch {}

    fun preparePairing() { _state.update { it.copy(pairingPhase = PairingPhase.Idle, error = null) } }

    fun pair(text: String, deviceName: String) = action {
        _state.update { it.copy(pairingPhase = PairingPhase.Validating) }
        val code = try { PairingCode.parse(text) } catch (error: Exception) {
            _state.update { it.copy(pairingPhase = PairingPhase.Failed) }; throw error
        }
        disconnect()
        val transport = newConnection()
        try {
            val config = code.connectionConfig()
            _state.update { it.copy(pairingPhase = PairingPhase.Connecting) }
            transport.connect(config)
            _state.update { it.copy(pairingPhase = PairingPhase.Authenticating) }
            val deviceId = UUID.randomUUID().toString()
            val result = transport.request("pair", buildJsonObject { put("pairingToken", code.pairingToken); put("deviceId", deviceId); put("deviceName", deviceName.ifBlank { Build.MODEL }.take(80)) }).asObject()
            require(result.string("deviceId").isNotBlank() && result.string("deviceSecret").isNotBlank() && result.string("computerId").isNotBlank())
            val saved = PairedComputer(config, result.string("deviceId"), result.string("deviceSecret"), result.string("computerId"), result.string("computerName", code.computerName))
            pairStore.save(saved)
            pair = saved
            cursors.clear(); timelines.clear(); conversationWindows.clear(); snapshotCursors.clear()
            dao.cursors(saved.computerId).forEach { cursors[it.runtimeId] = Cursor(it.epoch, it.sequence) }
            val cachedChats = dao.allChats().filter { it.key.computerId == saved.computerId }
            val cachedCommands = dao.uncertain(saved.computerId)
            _state.update { it.copy(paired = true, pairingPhase = PairingPhase.Synchronizing, usage = UsageScreenState(), assistant = AssistantUiState(), assistantModels = emptyList(), upstreamVersions = null, versionsObservedAt = 0, relayVersion = null, capabilities = emptySet(), runtimes = emptyList(), computerId = saved.computerId, computerName = saved.computerName, selected = null, timeline = NativeTimeline(), chats = cachedChats.filterNot { c -> c.isSubagent }, agentChats = cachedChats.filter { c -> c.isSubagent }, agentReader = null, contextSnapshot = ContextSnapshot(), permissions = PermissionState(), commands = cachedCommands, approvals = emptyList(), models = emptyList(), modelCatalogState = if (it.online) ModelCatalogState.Idle else ModelCatalogState.Offline, modelCatalogError = null, model = null, effort = null, mode = null,
                nativeSettings = NativeSettings(), settingsFresh = false, pendingSettings = emptySet()) }
        } catch (cancelled: CancellationException) {
            _state.update { it.copy(pairingPhase = PairingPhase.Failed) }; throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(pairingPhase = PairingPhase.Failed) }; throw error
        } finally { transport.close() }
        reconnect()
    }

    fun forget() = action {
        routeDiscovery?.cancel(); routeDiscovery = null; discoveryComputer = null
        disconnect(); pairStore.forget(); pair = null; cursors.clear(); timelines.clear(); conversationWindows.clear(); snapshotCursors.clear()
        _state.update { PocketState(initialized = true, theme = it.theme) }
    }

    private suspend fun refreshRoutes(transport: PocketConnection) {
        if ("routes/list" !in _state.value.capabilities) return
        try {
            val current = pair ?: return
            val directory = transport.request("routes/list", timeoutMillis = 5000).asObject()
            if (client !== transport || pair !== current || directory.string("computerId") != current.computerId) return
            val routes = directory.array("routes").map { ConnectionRoute.parse(it.asObject()) }
            val policy = ConnectionPolicy.parse(directory.string("policy", "automatic"))
            routeManager.updateRoutes(routes.ifEmpty { current.config.routes }, policy)
            persistRouteDirectory(JsonObject(directory + ("routePolicy" to JsonPrimitive(policy.wireValue))), current)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A directory refresh is not a connection failure. */ }
    }
    private suspend fun tryRouteChange(transport: RoutingPocketConnection) {
        val changed = transport.tryPreferredRoute { candidate -> sync(candidate, transport) } ||
            transport.tryPublicDirect { candidate -> sync(candidate, transport) }
        if (changed) restoreAfterRouteChange(transport)
        // A candidate needing snapshots cannot advance our cursor. Recover through
        // the retained healthy delegate, outside the route's dispatch mutex.
        else if (client === transport && resyncRequested) resyncInBand(transport)
    }

    private suspend fun restoreAfterRouteChange(transport: PocketConnection) {
        if (client !== transport) return
        resyncCount = 0 // A deliberate authenticated migration is not backlog loss.
        connectionDiagnostics.record(connectionGeneration.get(), "route_switch", "completed")
        refreshRoutes(transport)
        updateRouteEvidence(transport)
    }

    private suspend fun request(method: String, params: JsonObject = buildJsonObject {}) = (client ?: throw WireException("offline", "Offline")).request(method, params)
    private suspend fun native(runtime: String, method: String, params: JsonObject, mutation: Boolean = false, chat: ThreadKey? = null, draft: String = ""): JsonElement {
        if (mutation && chat != null && method != "turn/interrupt") {
            val owned = dao.chat(chat) ?: throw PocketOperationException(PocketFailure.Ownership)
            ensureThreadAccess(owned)
            if ((dao.chat(chat) ?: owned).runtimeId != runtime) throw PocketOperationException(PocketFailure.Ownership)
        }
        val body = buildJsonObject { put("runtimeId", runtime); put("method", method); put("params", params) }
        try { return if (mutation) mutate("codex/request", body, chat, method, draft) else request("codex/request", body) }
        catch (e: WireException) {
            if (e.code in setOf("unsupported", "method_not_found", "unsupported_method", "-32601")) _state.update { it.copy(disabledMethods = it.disabledMethods + "$runtime:$method") }
            throw e
        }
    }
    private suspend fun mutate(method: String, params: JsonObject, chat: ThreadKey? = null, nativeMethod: String = method, draft: String = ""): JsonElement {
        if (chat != null && dao.chat(chat)?.isSubagent == true) throw WireException("read_only", "子代理为只读会话")
        val p = pair ?: throw WireException("offline", "Offline")
        val transport = client ?: throw WireException("offline", "Offline")
        val id = UUID.randomUUID().toString()
        val questionStore = QuestionAnswerStore(context)
        var questionReceipt = if (nativeMethod == "approvals/respond" && chat != null) {
            _state.value.approvals.firstOrNull { it.key == draft && it.method == "item/tool/requestUserInput" }
                ?.let { QuestionReceipt.capture(id, chat, it, params.obj("response")) }
        } else null
        dao.putCommand(CommandEntity(id, p.computerId, chat, nativeMethod, "submitted", System.currentTimeMillis(), draft))
        val editMarker = runCatching { pocketJson.parseToJsonElement(draft).jsonObject.string("pocketEdit") }.getOrNull()
        if (chat != null && editMarker in setOf("revert", "send")) messageEdits[chat]?.let { putMessageEdit(it.copy(commandId = id)) }
        val planRevision = runCatching { pocketJson.parseToJsonElement(draft).jsonObject.string("pocketPlan") }.getOrNull()?.takeIf(String::isNotBlank)
        if (nativeMethod == "approvals/respond") synchronized(approvalGate) {
            approvalCommandGenerations[id] = draft to (approvalGenerations[draft] ?: 0L)
        }
        try {
            questionReceipt = questionReceipt?.let { retainQuestionReceipt(questionStore, it, p.computerId) }
            if (chat != null && planRevision != null) {
                val epoch = historyVerificationEpoch.get()
                val selection = selectionGeneration.get()
                fun verifyPlan() {
                    val current = _state.value
                    if (client !== transport || current.selected?.key != chat || current.selected.runtimeId != params.string("runtimeId") ||
                        current.selected.conflict || historyVerificationEpoch.get() != epoch || selectionGeneration.get() != selection ||
                        verifiedHistoryLinks[chat] != connectionGeneration.get() || current.conversationLoadState != ConversationLoadState.Ready ||
                        PlanPresentation.latest(current.timeline)?.revision != planRevision) {
                        throw WireException("stale_plan", "请重新读取当前方案后再实施")
                    }
                }
                verifyPlan()
                resolvePlan(chat, planRevision, true, id, claim = true)
                // The durable guard can block while another coroutine invalidates history.
                verifyPlan()
            }
            val brokerParams = if (method in BrokerCapability.entries.map { it.method }) JsonObject(params + ("commandId" to JsonPrimitive(id))) else params
            val result = transport.request(method, brokerParams, commandId = id)
            val status = (result as? JsonObject)?.string("status").orEmpty()
            questionReceipt = questionReceipt?.let { receipt ->
                (result as? JsonObject)?.get("delivery")?.let { receipt.withDelivery(it.asObject()) }
                    ?: receipt.awaitingConfirmation()
            }
            questionReceipt = questionReceipt?.let { retainQuestionReceipt(questionStore, it, p.computerId) }
            if (chat != null && planRevision != null && status !in setOf("unknown", "submitted")) acknowledgePlanSettings(chat, draft)
            dao.commandStatus(id, questionReceipt?.commandStatus(status) ?: if (status in setOf("unknown", "submitted")) status else "accepted")
            if (status == "unknown" || (status == "submitted" && nativeMethod != "approvals/respond")) throw UnknownOutcomeException(id)
            return result
        } catch (e: QuestionRetentionCapacityException) {
            dao.commandStatus(id, "rejected")
            throw WireException("question_capacity", "Local question answer retention is full")
        } catch (e: WireException) {
            if (chat != null && planRevision != null) resolvePlan(chat, planRevision, false, id)
            questionReceipt?.let { receipt ->
                questionDeliveryLock.withLock {
                    val terminal = questionStore.removeRejected(receipt.commandId) ?: _state.value.questionDeliveries
                        .firstOrNull { it.commandId == receipt.commandId && it.phase.terminal }
                    _state.update { it.copy(questionDeliveries = it.questionDeliveries.filterNot { old -> old.commandId == receipt.commandId } + listOfNotNull(terminal)) }
                }
            }
            dao.commandStatus(id, "rejected"); throw e
        }
        catch (e: Exception) {
            withContext(NonCancellable) {
                dao.commandStatus(id, "unknown")
                questionReceipt?.awaitingConfirmation()?.let { receipt ->
                    retainQuestionReceipt(questionStore, receipt, p.computerId)
                }
            }
            throw e
        }
    }

    private suspend fun retainQuestionReceipt(store: QuestionAnswerStore, receipt: QuestionReceipt, computer: String): QuestionReceipt =
        questionDeliveryLock.withLock {
            val current = store.command(receipt.commandId) ?: _state.value.questionDeliveries.firstOrNull { it.commandId == receipt.commandId }
            // Terminal Room rows are deliberately excluded from reconciliation. Do
            // not resurrect their receipts, including one already aged out of storage.
            if (dao.uncertain(computer).none { it.id == receipt.commandId }) return@withLock current ?: receipt
            val retained = store.save(current?.takeIf { it.phase.terminal } ?: receipt)
            _state.update { it.copy(questionDeliveries = it.questionDeliveries.filterNot { old -> old.commandId == retained.commandId } + retained) }
            retained
        }

    private suspend fun refreshRuntimes() {
        val runtimes = request("runtimes/list").asObject().array("data").map { RuntimeInfo.from(it.asObject()) }
        _state.update { it.copy(runtimes = runtimes) }
    }
    /** Returning to a cached home is a freshness check, not a forced network reload. */
    fun ensureDirectory(search: String = "", archived: Boolean = false): Job = refreshDirectoryRequest(search, archived, false, false)
    fun refresh(search: String = "", archived: Boolean = false, more: Boolean = false): Job = refreshDirectoryRequest(search, archived, more, true)
    private fun refreshDirectoryRequest(search: String, archived: Boolean, more: Boolean, force: Boolean): Job = synchronized(directoryGate) {
        val query = DirectoryQuery(search, archived)
        val link = connectionGeneration.get()
        if (directoryQuery != query) {
            directoryQuery = query
            val next = directoryStamps[query]?.takeIf { it.link == link }?.next
            _state.update { it.copy(nextThreads = next) }
        }
        val key = DirectoryRequest(query, link, more)
        directoryJobs[key]?.takeIf { !it.isCompleted }?.let { return@synchronized it }
        val stamp = directoryStamps[query]
        if (!_state.value.online || (!force && !more && stamp != null && stamp.link == link && System.nanoTime() - stamp.nanos < 30_000_000_000L)) {
            return@synchronized Job().apply { complete() }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(directoryTimeoutMillis) {
                    // hello already contains runtime identity. Do not delay cached list reads on unrelated metadata.
                    if (force || _state.value.runtimes.isEmpty()) refreshRuntimes()
                    refreshChats(search, archived, more)
                }
            } catch (_: TimeoutCancellationException) {
                if (connectionGeneration.get() == link && directoryQuery == query) _state.update { it.copy(directoryError = "会话列表更新超时，已保留缓存，可重试") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (connectionGeneration.get() == link && directoryQuery == query) _state.update { it.copy(directoryError = "会话列表暂未更新，已保留缓存，可重试") }
            }
        }
        directoryJobs[key] = job
        job.invokeOnCompletion { synchronized(directoryGate) { if (directoryJobs[key] === job) directoryJobs.remove(key) } }
        job.start(); job
    }
    private suspend fun <T> directoryScope(block: suspend () -> T): T {
        synchronized(directoryGate) {
            directoryActions++
            _state.update { it.copy(directoryLoading = it.chats.none { chat -> chat.key.computerId == it.computerId }, directoryRefreshing = true, directoryError = null) }
        }
        try { return withTimeout(directoryTimeoutMillis) { block() } }
        finally { synchronized(directoryGate) {
            directoryActions--
            _state.update { it.copy(directoryLoading = directoryActions > 0 && it.chats.none { chat -> chat.key.computerId == it.computerId }, directoryRefreshing = directoryActions > 0) }
        } }
    }
    private suspend fun refreshChats(search: String = "", archived: Boolean = false, more: Boolean = false) {
        directoryScope { directoryLock.withLock {
            val query = DirectoryQuery(search, archived)
            if (query != directoryQuery) return@withLock
            val cursor = _state.value.nextThreads
            if (more && cursor == null) return@withLock
            val response = readChats(query, if (more) cursor else null)
            if (query == directoryQuery) _state.update { it.copy(nextThreads = response["nextCursor"]?.takeUnless { c -> c is JsonNull }) }
        } }
    }
    private suspend fun refreshDirectory() = directoryScope { directoryLock.withLock {
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
    } }
    private suspend fun readChats(query: DirectoryQuery, cursor: JsonElement?): JsonObject {
        val transport = client ?: throw WireException("offline", "Offline")
        val link = connectionGeneration.get()
        val p = pair ?: throw WireException("offline", "Offline")
        val response = withTimeout(directoryTimeoutMillis) { transport.request("threads/list", buildJsonObject { put("limit", 60); put("search", query.search); put("archived", query.archived); cursor?.let { put("cursor", it) } }, timeoutMillis = directoryTimeoutMillis).asObject() }
        if (client !== transport || connectionGeneration.get() != link || pair?.computerId != p.computerId) throw CancellationException("Directory connection changed")
        val reasons = mutableMapOf<ThreadKey, OwnershipReason>()
        val chats = response.array("data").mapNotNull { value ->
            val o = value.asObject()
            val r = _state.value.runtimes.firstOrNull { it.id == o.string("runtimeId") } ?: return@mapNotNull null
            val key = ThreadKey(p.computerId, o.string("configStoreId", r.configStoreId), o.string("id"))
            if (o.flag("conflict")) OwnershipReason.from(o.string("ownershipReason"))?.let { reasons[key] = it }
            val old = dao.chat(key)
            val parent = o.string("parentThreadId").takeIf(String::isNotBlank) ?: old?.parentThreadId
            ChatEntity(key, r.id, o.string("source", r.source), o.string("name", context.getString(R.string.untitled_chat)), o.string("preview"), o.string("cwd"), o.number("updatedAt"), o.string("status", o.obj("status").string("type")), o.flag("conflict"), query.archived, old?.pinned ?: false,
                isSubagent = o.flag("isSubagent") || parent != null || old?.isSubagent == true, parentThreadId = parent,
                relationSource = if (o.flag("isSubagent") || o.string("parentThreadId").isNotBlank()) "native" else old?.relationSource)
        }.distinctBy { it.key }
        dao.putChats(chats)
        synchronized(directoryGate) {
            if (cursor == null) {
                if (directoryStamps.size >= 32) directoryStamps.remove(directoryStamps.keys.first())
                directoryStamps[query] = DirectoryStamp(link, System.nanoTime(), response["nextCursor"]?.takeUnless { it is JsonNull })
            }
        }
        _state.update { it.copy(directoryLoading = false, directoryUpdatedAt = System.currentTimeMillis(), directoryError = null,
            threadOwnershipReasons = (it.threadOwnershipReasons - chats.map { chat -> chat.key }.toSet()) + reasons) }
        return response
    }

    fun select(chat: ChatEntity, forceRefresh: Boolean = false): Job {
        val outgoing = _state.value
        outgoing.selected?.let { previous -> ConversationWindow.from(outgoing)?.let { conversationWindows[previous.key] = it } }
        val generation = selectionGeneration.incrementAndGet()
        timelines.select(chat.key)
        closeAgentReader()
        return action(lock = null, statusKey = "conversation:${chat.key.encoded}", globalBusy = false) {
        val savedDraft = drafts[chat.key] ?: dao.draft(chat.key) ?: DraftEntity(chat.key)
        val draft = synchronized(draftGate) {
            val latest = drafts[chat.key] ?: savedDraft
            val restored = restoreReferenceDraft(latest.text, latest.references)
            latest.copy(text = restored.text, references = encodeReferenceSpans(restored.spans)).also { drafts[chat.key] = it }
        }
        if (draft != savedDraft) scope.launch { persistDraft(chat.key) }
        val inMemory = timelines[chat.key]
        val timeline = inMemory ?: try {
            NativeTimeline(items = db.withTransaction { dao.cachedItems(chat.key) }.associateTo(linkedMapOf()) { it.itemKey to it.item() }).withCachedTurns().also { timelines[chat.key] = it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            connectionDiagnostics.record(connectionGeneration.get(), "cached_history", "unknown", failure)
            NativeTimeline() // Native hydration below can repair the view without deleting any draft/cache.
        }
        if (generation != selectionGeneration.get()) return@action
        val window = conversationWindows[chat.key]?.takeIf { it.runtimeId == chat.runtimeId && it.conflict == chat.conflict }
        val reusable = !forceRefresh && window?.reusable(chat, _state.value.online,
            verifiedHistoryLinks[chat.key], connectionGeneration.get(), inMemory != null) == true
        val cachedContext = dao.contextUsage(chat.key)?.let { runCatching { ContextSnapshot.from(pocketJson.parseToJsonElement(it.snapshot).jsonObject).copy(status = "stale") }.getOrNull() } ?: ContextSnapshot()
        _state.update { it.copy(selected = chat, messageEdit = readMessageEdit(chat), timeline = timeline, draft = draft, attachments = parseAttachments(draft.attachments), models = emptyList(), modelCatalogState = if (it.online) ModelCatalogState.Idle else ModelCatalogState.Offline, modelCatalogError = null, model = null, effort = null, modes = emptyList(), mode = null, nextTurns = window?.nextTurns, historyExhausted = window?.exhausted ?: false, historyLoading = chat.key in loadingHistory, unknownHistoryInspected = false, stopping = stopKey(chat.key, timeline.activeTurn) in stoppingTurns, nativeQueue = emptyList(), queueSupported = null, advanced = AdvancedConversationState(asyncQuestions = AsyncQuestions.pending(timeline, submittedQuestions(chat.key)))) }
        _state.update { it.copy(conversationLoadState = if (reusable) ConversationLoadState.Ready else if (it.online) ConversationLoadState.Loading else window?.loadState?.takeIf { load -> load == ConversationLoadState.Failed } ?: ConversationLoadState.Idle, conversationLoadError = window?.error,
            conversationCached = window != null || timeline.items.isNotEmpty(),
            nativeSettings = NativeSettings(), settingsFresh = false, pendingSettings = emptySet(), contextSnapshot = cachedContext,
            permissions = PermissionState(), resolvedPlans = preferences.getStringSet("resolvedPlans:${chat.key.encoded}", emptySet()).orEmpty().toSet(),
            dismissedPlans = preferences.getStringSet("dismissedPlans:${chat.key.encoded}", emptySet()).orEmpty().toSet(),
            planFeedback = readPlanFeedback(chat),
            advanced = it.advanced.copy(usage = cachedContext.usage)) }
        restoreSelectedSettings(chat)
        markRead(chat)
        if (_state.value.online) scope.launch { runCatching { sendPresence() } }
        preferences.edit().putString("selected", chat.key.encoded).apply()
        transferSharedAttachments(chat.key)
        if (_state.value.online) {
            try { loadThreadAccess(chat) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.update { it.copy(accessFresh = it.accessFresh - chat.key,
                actionStatus = it.actionStatus + ("access:${chat.key.encoded}" to ActionStatus(error = safeError(failure)))) } }
            if (!isSelected(chat, generation)) return@action
            refreshContext()
            refreshPermissions()
            if (!reusable || verifiedHistoryLinks[chat.key] != connectionGeneration.get()) loadConversation(chat, generation)
            else scope.launch { loadNativeSettings(chat, generation) }
            if (isSelected(chat, generation)) supervisorScope {
                launch { loadModels(chat.runtimeId) }
                launch { runCatching { loadQueue(chat, generation) } }
                launch { runCatching { loadGoal(chat) } }
            }
        }
        }
    }

    private suspend fun loadConversation(chat: ChatEntity, generation: Long = selectionGeneration.get()) {
        try { hydrate(chat, generation) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* hydrate owns its scoped failure; authentication remains usable. */ }
    }

    private suspend fun hydrate(chat: ChatEntity, generation: Long = selectionGeneration.get()): Boolean {
        val transport = client ?: throw WireException("offline", "Offline")
        val linkGeneration = connectionGeneration.get()
        val capture = HistoryCapture(chat.runtimeId)
        val verificationEpoch = historyVerificationEpoch.get()
        fun current() = client === transport && connectionGeneration.get() == linkGeneration &&
            historyVerificationEpoch.get() == verificationEpoch && isSelected(chat, generation)
        if (!current()) return false
        verifiedHistoryLinks.remove(chat.key)
        restoreSelectedSettings(chat)
        val settingsTicket = synchronized(settingsGate) { settingsEntry(chat).record.revisions }
        // Optional helper cache read never starts/resumes a thread and does not block history.
        scope.launch { loadNativeSettings(chat, generation) }
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
                var timeline = parsed.withObservedTurns(timelineBase(chat.key))
                var goal = thread["goal"] as? JsonObject
                var usage = thread["tokenUsage"] as? JsonObject ?: _state.value.contextSnapshot.usage
                val metadataCursor = metadata.obj("pocketCursor").let { o -> o.string("epoch").takeIf(String::isNotBlank)?.let { Cursor(it, o.number("sequence")) } }
                for (envelope in capture.events) {
                    val eventCursor = envelope.obj("cursor").let { Cursor(it.string("epoch"), it.number("sequence")) }
                    val event = envelope.obj("event")
                    val params = event.obj("params")
                    if (metadataCursor == null || !metadataCursor.covers(eventCursor)) when (event.string("method")) {
                        "thread/tokenUsage/updated" -> usage = params["tokenUsage"] as? JsonObject
                        "thread/goal/updated" -> goal = params["goal"] as? JsonObject
                        "thread/goal/cleared" -> goal = null
                    }
                    if (watermark != null && eventCursor.epoch == watermark.epoch && eventCursor.sequence > watermark.sequence) {
                        if (event["id"] == null) timeline = NativeReducer.reduce(timeline, event, "${eventCursor.epoch}:${eventCursor.sequence}")
                    }
                }
                persistTimeline(chat.key, timeline, replace = true)
                classifyAgentChildren(chat, timeline)
                if (watermark != null) {
                    db.withTransaction { dao.putSnapshot(SnapshotEntity(chat.key, watermark.epoch, watermark.sequence)) }
                    snapshotCursors[chat.key] = watermark
                }
                val next = page?.get("nextCursor")?.takeUnless { it is JsonNull }
                // Settings events were applied immediately, once, by consumeEventLocked. Replaying
                // them here would overwrite a phone edit made after that live notification.
                applySettingsSnapshot(chat, NativeSettings.snapshot(metadata), settingsTicket, linkGeneration)
                if (!current()) return@withLock
                verifiedHistoryLinks[chat.key] = linkGeneration
                _state.update { if (current()) it.copy(timeline = timeline, nextTurns = next, historyExhausted = page != null && next == null,
                    conversationLoadState = ConversationLoadState.Ready, conversationLoadError = null, conversationCached = true,
                    stopping = stopKey(chat.key, timeline.activeTurn) in stoppingTurns,
                    advanced = it.advanced.copy(goal = goal, usage = usage,
                        asyncQuestions = AsyncQuestions.pending(timeline, submittedQuestions(chat.key)))) else it }
                // The selected state may already belong to another thread after publication.
                // Bind this window to the identity and pagination result of this read.
                conversationWindows[chat.key] = ConversationWindow(chat.runtimeId, chat.conflict, next,
                    page != null && next == null, ConversationLoadState.Ready)
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
                "native_history_unavailable" -> "当前电脑 Codex 暂不支持此会话的历史读取；缓存与草稿已保留，可在电脑端查看。"
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
    fun chooseModel(model: NativeModel, effort: String?) {
        require(effort == null || effort in model.efforts)
        editSettings(NativeSettings(model = NativeSetting(true, model.model), effort = NativeSetting(true, effort)))
    }
    fun chooseMode(mode: String?) {
        require(mode == null || _state.value.modes.any { it.mode == mode })
        editSettings(NativeSettings(mode = NativeSetting(true, mode)))
    }

    private fun settingsEntry(chat: ChatEntity): CachedSettings {
        settingsByChat[chat.key]?.takeIf { it.runtimeId == chat.runtimeId }?.let { return it }
        val saved = preferences.getString("nativeSettings:${chat.key.encoded}", null)?.let { value ->
            runCatching { NativeSettingsRecord.restore(pocketJson.parseToJsonElement(value).asObject(), chat.runtimeId) }.getOrNull()
        }
        return CachedSettings(chat.runtimeId, saved ?: NativeSettingsRecord()).also { settingsByChat[chat.key] = it }
    }

    private fun publishSettings(chat: ChatEntity, entry: CachedSettings) {
        val settings = entry.record.settings
        _state.update { s -> if (isSelected(chat)) s.copy(
            nativeSettings = settings, pendingSettings = entry.record.pending,
            settingsFresh = entry.verifiedLink == connectionGeneration.get() && s.online,
            model = settings.model.value, effort = settings.effort.value, mode = settings.mode.value,
            advanced = s.advanced.copy(fastMode = settings.serviceTier.value == "fast"),
        ) else s }
    }

    private fun saveSettings(chat: ChatEntity, entry: CachedSettings, durable: Boolean = false) {
        val writer = preferences.edit().putString("nativeSettings:${chat.key.encoded}", entry.record.persisted(chat.runtimeId).toString())
        if (durable) check(writer.commit()) { "Unable to retain confirmed settings" } else writer.apply()
        settingsByChat[chat.key] = entry
        publishSettings(chat, entry)
    }

    private fun restoreSelectedSettings(chat: ChatEntity) = synchronized(settingsGate) {
        if (isSelected(chat)) publishSettings(chat, settingsEntry(chat))
    }

    private fun editSettings(patch: NativeSettings) = synchronized(settingsGate) {
        val chat = _state.value.selected ?: return@synchronized
        val entry = settingsEntry(chat)
        saveSettings(chat, entry.copy(record = entry.record.edit(patch)))
    }

    private fun applySettingsEvent(chat: ChatEntity, patch: NativeSettings) = synchronized(settingsGate) {
        if (!patch.anyKnown) return@synchronized
        val entry = settingsEntry(chat)
        saveSettings(chat, entry.copy(record = entry.record.event(patch), verifiedLink = connectionGeneration.get()))
    }

    private fun applySettingsSnapshot(chat: ChatEntity, patch: NativeSettings,
        ticket: Map<NativeSettingsField, Long>, linkGeneration: Long) = synchronized(settingsGate) {
        if (!patch.anyKnown || !isSelected(chat) || connectionGeneration.get() != linkGeneration) return@synchronized
        val entry = settingsEntry(chat)
        val updated = entry.record.snapshot(patch, ticket)
        saveSettings(chat, entry.copy(record = updated,
            verifiedLink = if (updated.revisions != entry.record.revisions) linkGeneration else entry.verifiedLink))
    }

    /** Sanitized helper RPC, deliberately outside codex/request (it is not a native method). */
    fun refreshNativeSettings(): Job = scope.launch {
        val chat = _state.value.selected ?: return@launch
        loadNativeSettings(chat, selectionGeneration.get())
    }

    private suspend fun loadNativeSettings(chat: ChatEntity, selection: Long) {
        val transport = client ?: return
        val linkGeneration = connectionGeneration.get()
        fun viewCurrent() = client === transport && connectionGeneration.get() == linkGeneration && isSelected(chat, selection) &&
            pair?.computerId == chat.key.computerId && _state.value.runtimes.any {
                it.id == chat.runtimeId && it.configStoreId == chat.key.configStoreId && it.online
            } && !chat.conflict
        // A delayed job for a previous selection must not invalidate the current
        // selection's in-flight metadata read and then return without replacing it.
        if (!viewCurrent()) return
        val readGeneration = settingsReadGeneration.incrementAndGet()
        fun current() = viewCurrent() && settingsReadGeneration.get() == readGeneration
        if (!current()) return
        val ticket = synchronized(settingsGate) { settingsEntry(chat).record.revisions }
        try {
            val response = transport.request("thread/settings/read", buildJsonObject {
                put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId)
            }).asObject()
            if (!current()) return
            val patch = NativeSettings.readResponse(response, chat.runtimeId, chat.key.nativeId) ?: return
            synchronized(settingsGate) {
                if (current()) applySettingsSnapshot(chat, patch, ticket, linkGeneration)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Older helpers or unknown settings retain honest cache/unknown state. */ }
    }

    fun refreshSessionPreferences(): Job = action(lock = null, statusKey = "preferences", globalBusy = false) { readSessionPreferences() }

    private suspend fun readSessionPreferences() {
        if (!_state.value.supports(BrokerCapability.PreferencesRead)) return
        val transport = client ?: throw WireException("offline", "Offline")
        val link = connectionGeneration.get(); val generation = preferencesGeneration.incrementAndGet()
        val value = SessionPreferences.from(transport.request(BrokerCapability.PreferencesRead.method, buildJsonObject {}).asObject())
        if (client === transport && link == connectionGeneration.get() && generation == preferencesGeneration.get())
            _state.update { it.copy(sessionPreferences = value, preferencesFresh = true) }
    }

    fun setDefaultSessionMode(mode: SessionMode): Job = action(lock = null, statusKey = "preferences", globalBusy = false) {
        val initial = _state.value
        if (!initial.supports(BrokerCapability.PreferencesUpdate)) throw PocketOperationException(PocketFailure.Unsupported)
        if (!initial.preferencesFresh) readSessionPreferences()
        val revision = _state.value.sessionPreferences.revision ?: throw PocketOperationException(PocketFailure.StaleRevision)
        preferencesGeneration.incrementAndGet()
        try {
            mutate(BrokerCapability.PreferencesUpdate.method, buildJsonObject { put("mode", mode.wire); put("knownRevision", revision) })
        } finally {
            _state.update { it.copy(preferencesFresh = false) }
            readSessionPreferences()
        }
    }

    fun refreshThreadAccess(): Job {
        val chat = _state.value.selected ?: return scope.launch {}
        return action(lock = null, statusKey = "access:${chat.key.encoded}", globalBusy = false) { loadThreadAccess(chat) }
    }

    /** Explicit, read-only inventory recheck. It must never ensure a runtime or hand off a thread. */
    fun recheckThreadOwnership(): Job {
        val chat = _state.value.selected ?: return scope.launch {}
        if (!ownershipRechecks.add(chat.key)) return scope.launch {}
        val transport = client
        val link = connectionGeneration.get()
        return scope.launch {
            fun current() = transport != null && client === transport && connectionGeneration.get() == link &&
                _state.value.online && _state.value.computerId == chat.key.computerId
            fun requireCurrent() { if (!current()) throw CancellationException("Ownership connection changed") }
            _state.update { it.copy(ownershipChecks = it.ownershipChecks + (chat.key to OwnershipCheck.Checking), accessFresh = it.accessFresh - chat.key) }
            try {
                requireCurrent()
                if (!_state.value.supports(BrokerCapability.AccessRead)) throw PocketOperationException(PocketFailure.Unsupported)
                withTimeout(directoryTimeoutMillis) {
                    val runtimes = transport!!.request("runtimes/list").asObject().array("data").map { RuntimeInfo.from(it.asObject()) }
                    requireCurrent()
                    _state.update { it.copy(runtimes = runtimes) }
                    // threads/list refreshes the broker's complete loaded inventory even for one row.
                    // Do not replace cached chat ownership with these rows before access/read confirms it.
                    transport.request("threads/list", buildJsonObject { put("limit", 1) })
                    requireCurrent()
                    loadThreadAccess(dao.chat(chat.key) ?: chat, requireFresh = true)
                        ?: throw PocketOperationException(PocketFailure.InvalidSnapshot)
                    requireCurrent()
                    val known = dao.chat(chat.key)
                    val recovered = known?.conflict == false && _state.value.ownershipVerifiedFor(known)
                    _state.update { it.copy(ownershipChecks = it.ownershipChecks +
                        (chat.key to if (recovered) OwnershipCheck.Recovered else OwnershipCheck.Blocked)) }
                }
            } catch (_: TimeoutCancellationException) {
                if (current()) _state.update { it.copy(ownershipChecks = it.ownershipChecks + (chat.key to OwnershipCheck.Failed)) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(ownershipChecks = it.ownershipChecks - chat.key) }
                throw cancelled
            } catch (_: Exception) {
                if (current()) _state.update { it.copy(ownershipChecks = it.ownershipChecks + (chat.key to OwnershipCheck.Failed)) }
            } finally { ownershipRechecks.remove(chat.key) }
        }
    }

    fun dismissOwnershipCheck() {
        val key = _state.value.selected?.key ?: return
        _state.update { it.copy(ownershipChecks = it.ownershipChecks - key) }
    }

    private suspend fun loadThreadAccess(chat: ChatEntity, requireFresh: Boolean = false): ThreadAccess? {
        if (!_state.value.supports(BrokerCapability.AccessRead)) return null
        if (chat.key in accessUpdates) return if (requireFresh) null else _state.value.threadAccess[chat.key]
        val transport = client ?: throw WireException("offline", "Offline")
        val link = connectionGeneration.get()
        val counter = accessGenerations.computeIfAbsent(chat.key) { AtomicLong() }; val generation = counter.incrementAndGet()
        val access = ThreadAccess.from(transport.request(BrokerCapability.AccessRead.method, buildJsonObject {
            put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId)
        }).asObject())
        if (client === transport && link == connectionGeneration.get() && counter.get() == generation) publishAccess(chat, access)
        else if (requireFresh) return null
        return access
    }

    private suspend fun publishAccess(chat: ChatEntity, access: ThreadAccess) {
        _state.update { it.copy(threadAccess = it.threadAccess + (chat.key to access), accessFresh = it.accessFresh + chat.key) }
        val runtime = _state.value.runtimes.firstOrNull { it.id == access.ownerRuntimeId && it.online &&
            it.configStoreId == chat.key.configStoreId && it.source == access.mode.wire }
        if (runtime != null && access.canSendFrom(runtime.id)) {
            val known = dao.chat(chat.key) ?: chat
            if (known.conflict || known.runtimeId != runtime.id) {
                val owned = known.copy(runtimeId = runtime.id, conflict = false)
                dao.putChat(owned)
                if (_state.value.selected?.key == chat.key && known.runtimeId != runtime.id) select(owned)
            }
        }
    }

    fun setThreadSessionMode(mode: SessionMode): Job {
        val chat = _state.value.selected ?: return scope.launch {}
        if (!accessUpdates.add(chat.key)) return scope.launch {}
        accessGenerations.computeIfAbsent(chat.key) { AtomicLong() }.incrementAndGet()
        return action(lock = null, statusKey = "access:${chat.key.encoded}", globalBusy = false) {
            try {
            if (!_state.value.supports(BrokerCapability.AccessUpdate) || chat.isSubagent || chat.archived)
                throw PocketOperationException(PocketFailure.Unsupported)
            _state.update { it.copy(accessFresh = it.accessFresh - chat.key) }
            val transport = client; val link = connectionGeneration.get()
            val result = mutate(BrokerCapability.AccessUpdate.method, buildJsonObject {
                put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId); put("mode", mode.wire)
            }, chat.key).asObject()
            if (client !== transport || link != connectionGeneration.get()) return@action
            publishAccess(chat, ThreadAccess.from(result))
            refreshRuntimes()
            } finally { accessUpdates.remove(chat.key) }
            loadThreadAccess(dao.chat(chat.key) ?: chat)
        }
    }

    fun cancelThreadHandoff(): Job {
        val chat = _state.value.selected ?: return scope.launch {}
        return action(lock = null, statusKey = "access:${chat.key.encoded}", globalBusy = false) {
            if (!_state.value.supports(BrokerCapability.AccessCancel)) throw PocketOperationException(PocketFailure.Unsupported)
            mutate(BrokerCapability.AccessCancel.method, buildJsonObject { put("threadId", chat.key.nativeId) }, chat.key)
            loadThreadAccess(chat)
        }
    }

    private suspend fun ensureThreadAccess(chat: ChatEntity) {
        if (!_state.value.supports(BrokerCapability.AccessRead)) return
        if (chat.key in accessUpdates) throw PocketOperationException(PocketFailure.Handoff)
        val access = loadThreadAccess(chat) ?: throw PocketOperationException(PocketFailure.Ownership)
        if (!access.canSendFrom(chat.runtimeId) || _state.value.runtimes.firstOrNull { it.id == chat.runtimeId }?.source != access.mode.wire) throw PocketOperationException(
            if (access.phase.inProgress) PocketFailure.Handoff else PocketFailure.Ownership)
    }

    /** Sending may prepare an idle/unloaded thread once, using its recorded mode. Native calls only validate. */
    internal suspend fun prepareThreadForSend(chat: ChatEntity): ChatEntity {
        if (!_state.value.supports(BrokerCapability.AccessRead)) return chat
        var access = loadThreadAccess(chat) ?: throw PocketOperationException(PocketFailure.Ownership)
        if (access.canSendFrom(chat.runtimeId) && _state.value.runtimes.firstOrNull { it.id == chat.runtimeId }?.source == access.mode.wire) return chat
        if (access.phase != AccessPhase.Ready || !_state.value.supports(BrokerCapability.AccessUpdate))
            throw PocketOperationException(if (access.phase.inProgress) PocketFailure.Handoff else PocketFailure.Ownership)
        val transport = client; val link = connectionGeneration.get()
        val response = mutate(BrokerCapability.AccessUpdate.method, buildJsonObject {
            put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId); put("mode", access.mode.wire)
        }, chat.key).asObject()
        access = ThreadAccess.from(response)
        publishAccess(chat, access)
        val owned = withTimeoutOrNull(20_000) {
            while (true) {
                if (client !== transport || link != connectionGeneration.get() || _state.value.selected?.key != chat.key)
                    throw PocketOperationException(PocketFailure.Ownership)
                refreshRuntimes()
                access = loadThreadAccess(dao.chat(chat.key) ?: chat) ?: throw PocketOperationException(PocketFailure.Ownership)
                val runtime = _state.value.runtimes.firstOrNull { it.id == access.ownerRuntimeId && it.online && it.source == access.mode.wire && it.configStoreId == chat.key.configStoreId }
                if (runtime != null && access.canSendFrom(runtime.id)) return@withTimeoutOrNull (dao.chat(chat.key) ?: chat).copy(runtimeId = runtime.id)
                if (access.phase in setOf(AccessPhase.Blocked, AccessPhase.Failed, AccessPhase.Cancelled)) throw PocketOperationException(PocketFailure.Handoff)
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: throw PocketOperationException(PocketFailure.Runtime)
        if (owned.runtimeId != chat.runtimeId) {
            dao.putChat(owned)
            select(owned).join()
        }
        return owned
    }

    /** Follow an accepted asynchronous ensure through inventory; do not replay its mutation. */
    private suspend fun prepareRuntime(mode: SessionMode, preferredId: String?): RuntimeInfo {
        val initial = _state.value
        sessionRuntimes(initial.runtimes, mode).firstOrNull { (preferredId == null || it.id == preferredId) && initial.supports("thread/start", it) }?.let { return it }
        if (mode != SessionMode.Desktop || !initial.supports(BrokerCapability.EnsureRuntime)) throw PocketOperationException(PocketFailure.Runtime)
        val transport = client; val link = connectionGeneration.get()
        _state.update { it.copy(actionStatus = it.actionStatus + ("create" to ActionStatus(true, AccessPhase.StartingDesktop.label))) }
        val response = mutate(BrokerCapability.EnsureRuntime.method, buildJsonObject { put("source", "desktop") }).asObject()
        val acceptedId = response.string("runtimeId").takeIf { it.isNotBlank() && it != "null" }
        return withTimeoutOrNull(20_000) { while (true) {
            if (client !== transport || connectionGeneration.get() != link) throw WireException("disconnected", "Offline")
            refreshRuntimes()
            sessionRuntimes(_state.value.runtimes, mode).firstOrNull { runtime ->
                (acceptedId == null || runtime.id == acceptedId) && _state.value.supports("thread/start", runtime)
            }?.let { return@withTimeoutOrNull it }
            delay(500)
        }
            @Suppress("UNREACHABLE_CODE") null
        } ?: throw PocketOperationException(PocketFailure.Runtime)
    }

    fun create(runtime: RuntimeInfo, cwd: String) = create(SessionMode.from(runtime.source) ?: SessionMode.Desktop, cwd, runtime.id)

    fun create(mode: SessionMode, cwd: String, runtimeId: String? = null) = action(statusKey = "create", globalBusy = false) {
        val runtime = prepareRuntime(mode, runtimeId)
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
        timelines.select(chat.key)
        timelines[chat.key] = timeline
        verifiedHistoryLinks[chat.key] = connectionGeneration.get()
        conversationWindows[chat.key] = ConversationWindow(chat.runtimeId, chat.conflict, null, true, ConversationLoadState.Ready)
        _state.update { it.copy(conversationLoadState = ConversationLoadState.Ready, conversationLoadError = null, conversationCached = true,
            selected = chat, messageEdit = null, timeline = timeline, draft = draft, attachments = emptyList(), models = emptyList(), modelCatalogState = if (it.online) ModelCatalogState.Idle else ModelCatalogState.Offline, modelCatalogError = null, model = null, effort = null, modes = emptyList(), mode = null, nextTurns = null, historyExhausted = true, historyLoading = false, unknownHistoryInspected = false, stopping = false, nativeQueue = emptyList(), queueSupported = null, resolvedPlans = emptySet(), permissions = PermissionState(), contextSnapshot = ContextSnapshot(), advanced = AdvancedConversationState()) }
        restoreSelectedSettings(chat)
        loadThreadAccess(chat)
        scope.launch { loadNativeSettings(chat, generation) }
        transferSharedAttachments(chat.key)
        refreshRuntimes()
        loadModels(runtime.id)
        loadQueue(chat, generation)
    }
    private suspend fun classifyAgentChildren(parent: ChatEntity, timeline: NativeTimeline) {
        agentRoster(timeline).forEach { child ->
            if (child.threadId != parent.key.nativeId) dao.markSubagent(parent.key.copy(nativeId = child.threadId), parent.key.nativeId, "collaboration")
        }
    }

    fun showAgentReader() {
        val parent = _state.value.selected ?: return
        _state.update { it.copy(agentReader = AgentReaderState(parent.key)) }
        refreshAgentNames()
    }
    fun closeAgentReader() { agentReadGeneration.incrementAndGet(); _state.update { it.copy(agentReader = null) } }
    fun agentReaderList() { agentReadGeneration.incrementAndGet(); _state.update { it.copy(agentReader = it.agentReader?.let { r -> AgentReaderState(r.parent) }) } }
    fun agentReaderFullscreen(value: Boolean) { _state.update { it.copy(agentReader = it.agentReader?.copy(fullScreen = value)) } }

    /** Runtime IDs change on restart; a cached child may move only after its old owner is offline. */
    private fun canReadChildFrom(known: ChatEntity?, parent: ChatEntity): Boolean {
        if (known == null) return true
        if (known.conflict || known.key.computerId != parent.key.computerId || known.key.configStoreId != parent.key.configStoreId) return false
        if (known.runtimeId == parent.runtimeId) return true
        val runtimes = _state.value.runtimes
        return runtimes.none { it.id == known.runtimeId && it.online } &&
            runtimes.any { it.id == parent.runtimeId && it.online && it.configStoreId == parent.key.configStoreId }
    }

    fun openAgentReader(id: String, older: Boolean = false): Job {
        val parent = _state.value.selected ?: return scope.launch {}
        val agent = agentRoster(_state.value.timeline).firstOrNull { it.threadId == id } ?: return scope.launch {}
        if (id == parent.key.nativeId) return scope.launch {}
        val before = _state.value.agentReader
        if (older && (before?.nextTurns == null || before.loading)) return scope.launch {}
        val key = parent.key.copy(nativeId = id)
        val ticket = agentReadGeneration.incrementAndGet()
        val selection = selectionGeneration.get()
        val connection = client
        val link = connectionGeneration.get()
        val capture = HistoryCapture(parent.runtimeId)
        fun current() = ticket == agentReadGeneration.get() && isSelected(parent, selection) && client === connection && link == connectionGeneration.get()
        return scope.launch {
            val known = dao.chat(key)
            if (!canReadChildFrom(known, parent)) {
                _state.update { if (current()) it.copy(agentReader = AgentReaderState(parent.key, known,
                    error = "子会话归属冲突，请刷新后重试")) else it }
                return@launch
            }
            var child = known
                ?: ChatEntity(key, parent.runtimeId, parent.source, agent.name.ifBlank { "子代理" }, agent.taskPreview, parent.cwd, 0, "", false,
                    isSubagent = true, parentThreadId = parent.key.nativeId, relationSource = "collaboration")
            child = child.copy(runtimeId = parent.runtimeId, isSubagent = true, parentThreadId = parent.key.nativeId, relationSource = "collaboration")
            if (!current()) return@launch
            // Rebinding is committed below only after the current native runtime confirms the ID.
            if (known == null || known.runtimeId == parent.runtimeId) dao.putChat(child)
            val cached = timelineBase(key)
            _state.update { if (current()) it.copy(agentReader = AgentReaderState(parent.key, child, cached, connection != null,
                fullScreen = before?.fullScreen == true, updatedAt = before?.takeIf { r -> r.child?.key == key }?.updatedAt ?: 0,
                nextTurns = before?.takeIf { r -> r.child?.key == key }?.nextTurns)) else it }
            if (connection == null || !_state.value.online) return@launch
            eventLock.withLock { if (current()) historyCaptures[key] = capture }
            try {
                suspend fun read(method: String, params: JsonObject) = connection.request("codex/request", buildJsonObject {
                    put("runtimeId", parent.runtimeId); put("method", method); put("params", params)
                }).asObject()
                val paged = _state.value.supports("thread/turns/list")
                var metadata = read("thread/read", buildJsonObject { put("threadId", id); put("includeTurns", !paged) })
                require(metadata.obj("thread").string("id") == id)
                var page = if (paged) read("thread/turns/list", buildJsonObject {
                    put("threadId", id); put("limit", HISTORY_FIRST_PAGE); put("sortDirection", "desc"); put("itemsView", "full")
                    if (older) before?.nextTurns?.let { put("cursor", it) }
                }) else null
                if (page != null && page["pocketCursor"] !is JsonObject) {
                    metadata = read("thread/read", buildJsonObject { put("threadId", id); put("includeTurns", true) }); page = null
                }
                val thread = metadata.obj("thread")
                val snapshot = if (page == null) thread else JsonObject(thread + ("turns" to JsonArray(page.array("data").reversed())))
                val parsed = withContext(Dispatchers.Default) { NativeReducer.snapshot(snapshot) }
                val watermark = (page?.get("pocketCursor") ?: metadata["pocketCursor"])?.asObject()?.let {
                    it.string("epoch").takeIf(String::isNotBlank)?.let { epoch -> Cursor(epoch, it.number("sequence")) }
                }
                eventLock.withLock {
                    if (!current() || historyCaptures[key] !== capture) return@withLock
                    check(canReadChildFrom(dao.chat(key), parent)) { "Child ownership changed during read" }
                    if (capture.overflow || (watermark == null && capture.events.isNotEmpty())) throw WireException("history_overflow", "Snapshot unavailable")
                    if (watermark != null && cursors[parent.runtimeId]?.epoch?.let { it != watermark.epoch } == true) throw WireException("stale", "Epoch changed")
                    // Events received during the read may have evicted this unpinned child.
                    // Resolve its latest base once under the event lock, including reader/Room.
                    val base = timelineBase(key)
                    if (!current() || historyCaptures[key] !== capture) return@withLock
                    var result = parsed.withObservedTurns(base)
                    if (older) result = result.copy(items = LinkedHashMap(result.items + base.items), turns = result.turns + base.turns,
                        activeTurn = base.activeTurn, completedTurns = result.completedTurns + base.completedTurns)
                    capture.events.forEach { envelope ->
                        val c = envelope.obj("cursor").let { Cursor(it.string("epoch"), it.number("sequence")) }
                        if (watermark != null && c.epoch == watermark.epoch && c.sequence > watermark.sequence && envelope.obj("event")["id"] == null)
                            result = NativeReducer.reduce(result, envelope.obj("event"), "${c.epoch}:${c.sequence}")
                    }
                    child = child.copy(title = thread.string("agentNickname").ifBlank { thread.string("name").ifBlank { child.title } })
                    dao.putChat(child)
                    persistTimeline(key, result, replace = true)
                    AgentLiveStatus.fromTimeline(result, watermark)?.let { publishAgentStatus(key, it) }
                    if (watermark != null) { snapshotCursors[key] = watermark; dao.putSnapshot(SnapshotEntity(key, watermark.epoch, watermark.sequence)) }
                    _state.update { if (current()) it.copy(agentReader = it.agentReader?.copy(child = child, timeline = result, loading = false,
                        error = null, updatedAt = System.currentTimeMillis(), nextTurns = page?.get("nextCursor")?.takeUnless { c -> c is JsonNull })) else it }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.update { if (current()) it.copy(agentReader = it.agentReader?.copy(loading = false,
                error = "子会话读取失败，可重新加载（${(failure as? WireException)?.code ?: "read_failed"}）")) else it } }
            finally { eventLock.withLock { if (historyCaptures[key] === capture) historyCaptures.remove(key) } }
        }
    }
    fun olderAgent() = _state.value.agentReader?.child?.key?.nativeId?.let { openAgentReader(it, older = true) }
    fun retryAgent() = _state.value.agentReader?.child?.key?.nativeId?.let { openAgentReader(it) }

    fun refreshContext(): Job {
        val chat = _state.value.selected ?: return scope.launch {}
        val selection = selectionGeneration.get()
        val ticket = contextReadGeneration.incrementAndGet()
        val observed = contextRevisions[chat.key] ?: 0
        val connection = client
        fun current() = ticket == contextReadGeneration.get() && isSelected(chat, selection) && client === connection
        return scope.launch {
            if (!current() || connection == null || !_state.value.online) return@launch
            _state.update { if (current()) it.copy(contextSnapshot = it.contextSnapshot.copy(loading = true, error = null)) else it }
            try {
                val response = connection.request("thread/context/read", buildJsonObject { put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId) }).asObject()
                require(response.string("threadId") == chat.key.nativeId && response.string("runtimeId") == chat.runtimeId)
                eventLock.withLock {
                    if (!current() || (contextRevisions[chat.key] ?: 0) != observed) return@withLock
                    val read = ContextSnapshot.from(response)
                    val value = if (read.usage == null) _state.value.contextSnapshot.copy(status = read.status, loading = false) else read
                    if (value.usage != null) dao.putContextUsage(ContextEntity(chat.key, value.json().toString()))
                    _state.update { if (current()) it.copy(contextSnapshot = value, advanced = it.advanced.copy(usage = value.usage)) else it }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.update { if (current()) it.copy(contextSnapshot = it.contextSnapshot.copy(loading = false,
                status = if ((failure as? WireException)?.code in setOf("unsupported", "method_not_found", "-32601")) "unsupported" else "failed", error = "上下文读取未完成")) else it } }
            finally { _state.update { if (current()) it.copy(contextSnapshot = it.contextSnapshot.copy(loading = false)) else it } }
        }
    }

    fun refreshPermissions(): Job {
        val chat = _state.value.selected ?: return scope.launch {}
        val selection = selectionGeneration.get()
        val ticket = permissionReadGeneration.incrementAndGet()
        val connection = client
        fun current() = ticket == permissionReadGeneration.get() && isSelected(chat, selection) && client === connection
        return scope.launch {
            if (!current() || connection == null || !_state.value.online) return@launch
            _state.update { if (current()) it.copy(permissions = it.permissions.copy(loading = true, error = null)) else it }
            try {
                val response = connection.request("thread/permissions/read", buildJsonObject { put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId) }).asObject()
                require(response.string("threadId") == chat.key.nativeId && response.string("runtimeId") == chat.runtimeId)
                _state.update { if (current()) it.copy(permissions = PermissionState.from(response).copy(updating = chat.key in permissionUpdates)) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.update { if (current()) it.copy(permissions = it.permissions.copy(loading = false,
                status = if ((failure as? WireException)?.code in setOf("unsupported", "method_not_found", "-32601")) "unsupported" else "failed", error = "无法读取会话权限")) else it } }
        }
    }
    fun choosePermission(presetId: String): Job {
        val s = _state.value
        val chat = s.selected ?: return scope.launch {}
        val revision = s.permissions.revision ?: return scope.launch {}
        if (!s.online || chat.conflict || chat.isSubagent || s.permissions.status != "ready" || s.permissions.loading || s.permissions.presets.none { it.id == presetId && it.enabled } || !permissionUpdates.add(chat.key)) return scope.launch {}
        val generation = selectionGeneration.get()
        return scope.launch {
            try {
                _state.update { if (isSelected(chat, generation)) it.copy(permissions = it.permissions.copy(updating = true, error = null)) else it }
                mutate("thread/permissions/update", buildJsonObject { put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId)
                    put("presetId", presetId); put("expectedRevision", revision) }, chat.key)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { if (isSelected(chat, generation)) it.copy(permissions = it.permissions.copy(error = "权限更改未确认，正在核对当前设置")) else it } }
            finally { permissionUpdates.remove(chat.key)
                if (isSelected(chat, generation)) { _state.update { it.copy(permissions = it.permissions.copy(updating = false)) }; refreshPermissions() }
            }
        }
    }

    /** Legacy explicit navigation API; production UI uses the independent read-only reader above. */
    fun openAgent(id: String): Job {
        val source = _state.value.selected ?: return scope.launch {}
        val generation = selectionGeneration.get()
        val connection = client
        val timeline = cachedTimelineBase(source.key) ?: return scope.launch {}
        val agent = timeline.items.values.asSequence().filter { it.type in setOf("collabAgentToolCall", "subAgentActivity") }
            .flatMap { NativeActivityPresentation.describe(it).agents.asSequence() }.lastOrNull { it.threadId == id }
            ?: return scope.launch {}
        if (id == source.key.nativeId) return scope.launch {}
        return action(lock = null) {
            require(isSelected(source, generation) && client === connection && pair?.computerId == source.key.computerId)
            val childKey = source.key.copy(nativeId = id)
            var child = dao.chat(childKey)?.takeIf { it.runtimeId == source.runtimeId && !it.conflict }
            if (child == null) {
                val response = native(source.runtimeId, "thread/read", buildJsonObject { put("threadId", id); put("includeTurns", false) }).asObject().obj("thread")
                require(response.string("id") == id) { "Child conversation identity mismatch" }
                if (!isSelected(source, generation) || client !== connection || pair?.computerId != source.key.computerId) return@action
                child = ChatEntity(childKey, source.runtimeId, source.source,
                    response.string("name").ifBlank { response.string("agentNickname").ifBlank { agent.name.ifBlank { "子代理 ${id.take(8)}" } } },
                    response.string("preview"), response.string("cwd", source.cwd), response.number("updatedAt"),
                    response.obj("status").string("type", "idle"), false)
                dao.putChat(child)
            }
            if (isSelected(source, generation) && client === connection) select(child).join()
        }
    }
    fun returnFromAgent(key: ThreadKey): Job = scope.launch {
        if (pair?.computerId != key.computerId) return@launch
        dao.chat(key)?.let { select(it).join() }
    }
    /** Small bounded metadata refresh when opening the roster; it never reads or starts child turns. */
    private val agentMetadataFlight = ConcurrentHashMap.newKeySet<ThreadKey>()
    private val agentMetadataAt = ConcurrentHashMap<ThreadKey, Long>()
    private fun publishAgentStatus(key: ThreadKey, value: AgentLiveStatus) {
        _state.update { s -> if (s.computerId != key.computerId) s else {
            val next = mergeAgentLiveStatus(s.agentStatuses[key], value)
            if (next == s.agentStatuses[key]) s else s.copy(agentStatuses = s.agentStatuses + (key to next),
                agentReader = s.agentReader?.let { reader ->
                    if (reader.parent.computerId == key.computerId && reader.parent.configStoreId == key.configStoreId)
                        reader.withLiveStatus(key.nativeId, value) else reader
                })
        } }
    }
    fun refreshAgentNames(): Job {
        val source = _state.value.selected ?: return scope.launch {}
        val generation = selectionGeneration.get()
        val connection = client ?: return scope.launch {}
        val known = _state.value.agentChats.filter { it.runtimeId == source.runtimeId && it.title.isNotBlank() && !it.title.startsWith("子代理") }.map { it.key.nativeId }.toSet()
        val children = agentRoster(cachedTimelineBase(source.key) ?: return scope.launch {}).filter { it.threadId !in known || it.isCurrent }.sortedByDescending { it.isCurrent }.take(16)
        return scope.launch {
            for (batch in children.chunked(4)) coroutineScope {
                batch.map { agent -> async {
                    val childKey = source.key.copy(nativeId = agent.threadId)
                    if (System.currentTimeMillis() - (agentMetadataAt[childKey] ?: 0) < 2_000 || !agentMetadataFlight.add(childKey)) return@async
                    try {
                        if (!isSelected(source, generation) || client !== connection) return@async
                        val responseEnvelope = withTimeout(5_000) { connection.request("codex/request", buildJsonObject {
                            put("runtimeId", source.runtimeId); put("method", "thread/read")
                            put("params", buildJsonObject { put("threadId", agent.threadId); put("includeTurns", false) })
                        }).asObject() }
                        val response = responseEnvelope.obj("thread")
                        if (response.string("id") != agent.threadId || !isSelected(source, generation) || client !== connection || pair?.computerId != source.key.computerId) return@async
                        val old = dao.chat(childKey)
                        if (!canReadChildFrom(old, source)) return@async
                        val cursor = responseEnvelope.obj("pocketCursor").let { c -> c.string("epoch").takeIf(String::isNotBlank)?.let { Cursor(it, c.number("sequence")) } }
                        eventLock.withLock {
                            if (!isSelected(source, generation) || client !== connection ||
                                (cursor != null && cursors[source.runtimeId]?.epoch?.let { it != cursor.epoch } == true)) return@withLock
                            val latest = dao.chat(childKey)
                            if (!canReadChildFrom(latest, source)) return@withLock
                            AgentLiveStatus.fromNativeStatus(response.obj("status"), cursor = cursor)?.let { publishAgentStatus(childKey, it) }
                            agentMetadataAt[childKey] = System.currentTimeMillis()
                            val name = response.string("agentNickname").ifBlank { response.string("name") }.ifBlank { latest?.title.orEmpty() }
                            if (name.isBlank()) return@withLock
                            dao.putChat(latest?.copy(runtimeId = source.runtimeId, title = name, status = response.obj("status").string("type", latest.status), isSubagent = true, parentThreadId = source.key.nativeId, relationSource = "collaboration") ?: ChatEntity(childKey, source.runtimeId, source.source, name,
                            response.string("preview"), response.string("cwd", source.cwd), response.number("updatedAt"), response.obj("status").string("type", "idle"), false,
                            isSubagent = true, parentThreadId = source.key.nativeId, relationSource = "collaboration"))
                        }
                    } catch (_: TimeoutCancellationException) { /* One unavailable child cannot block the roster. */ }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep the native identity and recorded status. */ }
                    finally { agentMetadataFlight.remove(childKey) }
                } }.awaitAll()
            }
        }
    }
    private fun publishExtensions(key: ExtensionCacheKey, value: CachedExtensions) {
        extensionCache[key] = value
        _state.update { s -> if (s.computerId == key.computerId && s.extensions.runtimeId == key.runtimeId)
            s.copy(extensions = if (value.link == connectionGeneration.get() && s.online) value.state
                else value.state.copy(phase = ExtensionPhase.Offline, refreshing = false)) else s }
    }

    fun loadExtensions(runtimeId: String, forceRefresh: Boolean = false): Job = synchronized(extensionGate) {
        val snapshot = _state.value
        val runtime = snapshot.runtimes.firstOrNull { it.id == runtimeId } ?: return@synchronized scope.launch {}
        val key = ExtensionCacheKey(snapshot.computerId, runtimeId)
        val link = connectionGeneration.get()
        val transport = client
        val cached = extensionCache[key]
        val previous = cached?.state ?: snapshot.extensions.takeIf { it.runtimeId == runtimeId && it.computerId == snapshot.computerId }
            ?: ExtensionManagementState(snapshot.computerId, runtimeId)
        val online = snapshot.online && runtime.online && transport != null
        val flight = extensionFlights[key]?.takeIf { it.first == link && it.second.isActive }?.second
        val fresh = online && flight == null && !forceRefresh && cached?.fresh(link, android.os.SystemClock.elapsedRealtime()) == true
        _state.update { it.copy(extensions = previous.copy(
            phase = when { !online -> ExtensionPhase.Offline; fresh -> previous.phase; previous.entries.isNotEmpty() -> previous.phase; else -> ExtensionPhase.Loading },
            refreshing = online && !fresh, error = if (fresh || !online) previous.error else null)) }
        if (!online || fresh) return@synchronized scope.launch {}
        if (flight != null) return@synchronized flight
        val generation = extensionGeneration.incrementAndGet()
        val mutationVersion = extensionMutationVersions[key] ?: 0L
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // The per-runtime flight token allows another runtime to refresh independently.
            fun valid() = connectionGeneration.get() == link && client === transport && _state.value.computerId == key.computerId &&
                synchronized(extensionGate) { extensionReadTickets[key] == generation }
            fun updateData(transform: (ExtensionManagementState) -> ExtensionManagementState) = synchronized(extensionGate) {
                if (valid()) {
                    val existing = extensionCache[key] ?: CachedExtensions(previous, link, 0)
                    publishExtensions(key, existing.copy(state = transform(existing.state)))
                }
            }
            try {
                if (!snapshot.supports("extensions/list", runtime)) {
                    updateData { it.copy(phase = ExtensionPhase.Unsupported, refreshing = false, status = "helper_update_required", error = "此助手尚未提供扩展管理，请更新电脑助手") }
                    return@launch
                }
                val result = transport!!.request("extensions/list", buildJsonObject { put("runtimeId", runtimeId) }).asObject()
                val ready = result.string("status") == "ready"
                val phase = if (ready) ExtensionPhase.Ready else if (result.string("status") == "initializing") ExtensionPhase.Idle else ExtensionPhase.Unsupported
                val reason = if (ready) null else when (result.string("status")) {
                    "initializing" -> "原生能力正在初始化，请稍后刷新"
                    "bridge_missing", "update_required" -> "运行中的电脑桥接需要更新，完成电脑任务后重新打开 Codex"
                    else -> "当前运行时未提供扩展管理能力"
                }
                synchronized(extensionGate) {
                    if (valid()) {
                        val state = extensionCache[key]?.state ?: previous
                        val entries = managedExtensions(result)
                        val confirmed = if (ready && (extensionMutationVersions[key] ?: 0L) == mutationVersion)
                            entries.filter { it.enabled != null && it.id !in state.pendingIds }.mapTo(hashSetOf()) { it.id } else emptySet()
                        val now = System.currentTimeMillis()
                        publishExtensions(key, CachedExtensions(ExtensionManagementState(snapshot.computerId, runtimeId,
                            phase, entries, result.string("version"), now, error = reason,
                            pendingIds = state.pendingIds, uncertainIds = state.uncertainIds - confirmed,
                            scope = result.string("scope"), reloadPending = result.flag("reloadPending"), status = result.string("status"),
                            appInventoryStatus = result.obj("appInventory").string("status"),
                            categoryStatuses = result.obj("categories").mapValues { (_, value) -> value.asObject().string("status") }), link, android.os.SystemClock.elapsedRealtime()))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                updateData { it.copy(phase = if (_state.value.online) ExtensionPhase.Failed else ExtensionPhase.Offline,
                    refreshing = false, error = "扩展读取失败，可刷新重试") }
            } finally {
                synchronized(extensionGate) { if (extensionReadTickets[key] == generation) { extensionFlights.remove(key); extensionReadTickets.remove(key) } }
            }
        }
        extensionReadTickets[key] = generation
        extensionFlights[key] = link to job
        job.start()
        job
    }

    fun refreshExtensions(): Job = loadExtensions(_state.value.extensions.runtimeId, forceRefresh = true)

    fun setExtensionEnabled(id: String, enabled: Boolean): Job {
        val s = _state.value
        val state = s.extensions
        val entry = state.entries.firstOrNull { it.id == id } ?: return scope.launch {}
        val runtime = s.runtimes.firstOrNull { it.id == state.runtimeId } ?: return scope.launch {}
        val key = ExtensionCacheKey(state.computerId, state.runtimeId)
        val updateKey = "${state.computerId.length}:${state.computerId}${state.runtimeId.length}:${state.runtimeId}$id"
        if (!s.online || !runtime.online || extensionCache[key]?.link != connectionGeneration.get() || !entry.canToggle || entry.enabled == enabled || id in state.uncertainIds || id in state.pendingIds || state.refreshing ||
            state.computerId != s.computerId || state.phase != ExtensionPhase.Ready || !extensionUpdates.add(updateKey)) return scope.launch {}
        val link = connectionGeneration.get()
        fun updateData(transform: (ExtensionManagementState) -> ExtensionManagementState) = synchronized(extensionGate) {
            val existing = extensionCache[key] ?: CachedExtensions(state, link, 0)
            publishExtensions(key, existing.copy(state = transform(existing.state)))
        }
        synchronized(extensionGate) { extensionMutationVersions[key] = (extensionMutationVersions[key] ?: 0L) + 1 }
        updateData { it.copy(pendingIds = it.pendingIds + id, error = null) }
        return scope.launch {
            try {
                val result = mutate("extensions/update", buildJsonObject {
                    put("runtimeId", state.runtimeId); put("id", id); put("enabled", enabled); put("expectedVersion", entry.version.ifBlank { state.version })
                }).asObject()
                synchronized(extensionGate) { extensionMutationVersions[key] = (extensionMutationVersions[key] ?: 0L) + 1 }
                updateData { it.copy(pendingIds = it.pendingIds - id) }
                if (extensionUpdateUncertain(result.string("status"))) {
                    updateData { it.copy(uncertainIds = it.uncertainIds + id, error = "更新结果待核对，请刷新；不会自动重试") }
                } else if (_state.value.computerId == state.computerId && connectionGeneration.get() == link && _state.value.extensions.runtimeId == state.runtimeId) {
                    // Discard any read that started before the acknowledgement.
                    synchronized(extensionGate) { extensionFlights.remove(key)?.second?.cancel(); extensionReadTickets.remove(key) }
                    loadExtensions(state.runtimeId, forceRefresh = true).join()
                } else {
                    updateData { it.copy(uncertainIds = it.uncertainIds + id) }
                }
            } catch (cancelled: CancellationException) {
                synchronized(extensionGate) { extensionMutationVersions[key] = (extensionMutationVersions[key] ?: 0L) + 1 }
                updateData { it.copy(uncertainIds = it.uncertainIds + id) }
                throw cancelled
            }
            catch (error: Exception) {
                synchronized(extensionGate) { extensionMutationVersions[key] = (extensionMutationVersions[key] ?: 0L) + 1 }
                val unknown = extensionUpdateUncertain((error as? WireException)?.code)
                updateData { it.copy(
                    error = if (unknown) "更新结果待核对，请刷新；不会自动重试" else "更新未完成，原选择已保留，请刷新后重试",
                    uncertainIds = if (unknown) it.uncertainIds + id else it.uncertainIds) }
            } finally {
                extensionUpdates.remove(updateKey)
                updateData { it.copy(pendingIds = it.pendingIds - id) }
            }
        }
    }

    private fun putMessageEdit(edit: MessageEditState) = synchronized(messageEditGate) {
        messageEdits[edit.key] = edit
        preferences.edit().putString("messageEdit:${edit.key.encoded}", buildJsonObject {
            put("runtimeId", edit.runtimeId); put("turnId", edit.turnId); put("itemId", edit.itemId)
            put("text", edit.text); put("originalText", edit.originalText); put("phase", edit.phase.name)
            put("version", edit.version); put("token", edit.token); put("retainedItems", edit.retainedItems)
            put("reverted", edit.reverted); edit.commandId?.let { put("commandId", it) }
        }.toString()).apply()
        _state.update { if (it.selected?.key == edit.key && it.selected.runtimeId == edit.runtimeId) it.copy(messageEdit = edit) else it }
    }

    private fun readMessageEdit(chat: ChatEntity): MessageEditState? = messageEdits[chat.key]?.takeIf { it.runtimeId == chat.runtimeId }
        ?: runCatching {
            val saved = preferences.getString("messageEdit:${chat.key.encoded}", null) ?: return@runCatching null
            val o = pocketJson.parseToJsonElement(saved).jsonObject
            val phase = runCatching { MessageEditPhase.valueOf(o.string("phase")) }.getOrDefault(MessageEditPhase.Unavailable)
            val restored = when (phase) {
                MessageEditPhase.Reverting -> MessageEditPhase.UnknownRevert
                MessageEditPhase.Sending -> MessageEditPhase.UnknownSend
                MessageEditPhase.Preparing -> MessageEditPhase.Unavailable
                else -> phase
            }
            MessageEditState(chat.key, chat.runtimeId, o.string("turnId"), o.string("itemId"), o.string("text"), o.string("originalText"),
                if (o.string("runtimeId") == chat.runtimeId) restored else MessageEditPhase.Unavailable,
                o.string("version"), o.string("token"), "编辑稿已恢复，请核对原生记录后继续", o.number("retainedItems").toInt(),
                o.flag("reverted"), o.string("commandId").takeIf(String::isNotBlank)).also { messageEdits[chat.key] = it }
        }.getOrNull()

    fun beginMessageEdit(item: TimelineItem): Job {
        val s = _state.value
        val chat = s.selected ?: return scope.launch {}
        val retained = s.messageEdit ?: readMessageEdit(chat)
        if (retained != null && (retained.busy || retained.uncertain)) {
            // Closing an uncertain editor only hides it; another message cannot replace its ledger.
            putMessageEdit(retained)
            return scope.launch {}
        }
        val issue = messageEditUnavailable(s, item)
        val original = UserMessageParsing.parse(item.text).request
        val previous = retained?.takeIf { it.turnId == item.turnId && it.itemId == item.itemId && it.key == chat.key }
        val edit = MessageEditState(chat.key, chat.runtimeId, item.turnId, item.itemId, previous?.text ?: original, original,
            if (issue == null) MessageEditPhase.Preparing else MessageEditPhase.Unavailable, reason = issue)
        putMessageEdit(edit)
        if (issue != null) return scope.launch {}
        val generation = messageEditGeneration.incrementAndGet()
        val selectedGeneration = selectionGeneration.get()
        val transport = client
        val link = connectionGeneration.get()
        return scope.launch {
            fun current() = generation == messageEditGeneration.get() && selectedGeneration == selectionGeneration.get() &&
                _state.value.selected?.key == chat.key && client === transport && connectionGeneration.get() == link
            try {
                val result = request("thread/edit/prepare", buildJsonObject {
                    put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId); put("turnId", item.turnId); put("itemId", item.itemId)
                }).asObject()
                if (!current()) return@launch
                require(result.string("threadId") == chat.key.nativeId && result.string("turnId") == item.turnId && result.string("itemId") == item.itemId)
                if (!result.flag("allowed")) putMessageEdit(edit.copy(phase = MessageEditPhase.Unavailable, reason = editFailureMessage(result.string("reason"))))
                else {
                    val text = result.string("text")
                    putMessageEdit(edit.copy(text = previous?.text ?: text, originalText = text, phase = MessageEditPhase.Ready,
                        token = result.string("editToken"), version = result.string("version"),
                        retainedItems = result.array("originalInput").count { it.asObject().string("type") == "retained" }, reason = null))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) putMessageEdit(edit.copy(phase = MessageEditPhase.Unavailable, reason = "原消息读取失败，请重试")) }
            finally {
                // A late prepare belongs to the old view. Keep its draft, but never leave it busy forever.
                if (!current()) messageEdits[edit.key]?.takeIf { it.phase == MessageEditPhase.Preparing && it.itemId == edit.itemId }?.let {
                    putMessageEdit(it.copy(phase = MessageEditPhase.Unavailable, reason = "会话已切换，请重新读取原消息"))
                }
            }
        }
    }

    fun changeMessageEdit(text: String) = synchronized(messageEditGate) {
        val current = _state.value.messageEdit ?: return@synchronized
        if (!current.busy && !current.uncertain) putMessageEdit(current.copy(text = text))
    }
    fun cancelMessageEdit() = synchronized(messageEditGate) {
        val edit = _state.value.messageEdit ?: return@synchronized
        if (edit.busy) return@synchronized
        messageEditGeneration.incrementAndGet()
        // Uncertain text stays recoverable until its ledger is reconciled.
        if (!edit.uncertain) {
            messageEdits.remove(edit.key)
            preferences.edit().remove("messageEdit:${edit.key.encoded}").apply()
        }
        _state.update { it.copy(messageEdit = null) }
    }
    fun prepareMessageEditAgain(): Job {
        val s = _state.value
        val edit = s.messageEdit ?: return scope.launch {}
        val item = s.timeline.items.values.firstOrNull { it.turnId == edit.turnId && it.itemId == edit.itemId }
        return if (item != null && !edit.reverted) beginMessageEdit(item) else checkMessageEdit()
    }

    fun submitMessageEdit(): Job {
        val s = _state.value
        val edit = s.messageEdit ?: return scope.launch {}
        val chat = s.selected ?: return scope.launch {}
        if (!edit.canSubmit || edit.key != chat.key || edit.runtimeId != chat.runtimeId || !s.online ||
            s.uncertainSend || s.timeline.activeTurn != null || !sending.tryLock()) return scope.launch {}
        val generation = selectionGeneration.get()
        val link = connectionGeneration.get()
        val transport = client
        _state.update { it.copy(sendBusy = true) }
        putMessageEdit(edit.copy(phase = if (edit.reverted) MessageEditPhase.Sending else MessageEditPhase.Reverting, reason = null))
        return scope.launch {
            var current = edit
            var dispatched: String? = null
            fun verify() {
                require(_state.value.selected?.key == chat.key && _state.value.selected?.runtimeId == chat.runtimeId &&
                    selectionGeneration.get() == generation && connectionGeneration.get() == link && client === transport &&
                    _state.value.online && _state.value.timeline.activeTurn == null) { "Edit identity changed" }
            }
            try {
                verify()
                if (!current.reverted) {
                    dispatched = "revert"
                    val result = mutate("thread/edit/revert", buildJsonObject {
                        put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId); put("turnId", edit.turnId)
                        put("itemId", edit.itemId); put("version", edit.version); put("editToken", edit.token)
                    }, chat.key, draft = buildJsonObject { put("pocketEdit", "revert"); put("text", edit.text); put("token", edit.token) }.toString()).asObject()
                    require(result.string("status") == "reverted" && result.string("threadId") == chat.key.nativeId)
                    dispatched = null
                    current = (messageEdits[edit.key] ?: current).copy(reverted = true, version = result.string("version"), phase = MessageEditPhase.Reverted)
                    putMessageEdit(current)
                    hydrate(chat)
                }
                verify()
                putMessageEdit(current.copy(phase = MessageEditPhase.Sending))
                val params = buildJsonObject {
                    put("runtimeId", chat.runtimeId); put("method", "turn/start"); put("preparedInput", edit.token)
                    put("params", buildJsonObject {
                        put("threadId", chat.key.nativeId); put("clientUserMessageId", UUID.randomUUID().toString())
                        put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", edit.text) }) })
                        s.nativeSettings.turnSelection(s.model, s.effort, s.mode, s.pendingSettings).forEach { (key, value) -> put(key, value) }
                    })
                }
                dispatched = "send"
                val result = mutate("codex/request", params, chat.key, "turn/start", buildJsonObject {
                    put("pocketEdit", "send"); put("text", edit.text); put("token", edit.token)
                }.toString()).asObject()
                val turn = result.obj("turn").string("id").ifBlank { result.string("turnId") }
                require(turn.isNotBlank())
                dispatched = null
                finishMessageEdit(edit.key)
                if (_state.value.selected?.key == chat.key) scope.launch { runCatching { hydrate(chat) } }
            } catch (cancelled: CancellationException) {
                if (dispatched != null) withContext(NonCancellable) {
                    val latest = messageEdits[edit.key] ?: current
                    putMessageEdit(latest.copy(phase = if (dispatched == "send") MessageEditPhase.UnknownSend else MessageEditPhase.UnknownRevert,
                        reason = "操作结果待核对，不会自动重发"))
                }
                throw cancelled
            }
            catch (failure: Exception) {
                val latest = messageEdits[edit.key] ?: current
                val definite = failure is WireException || dispatched == null
                if (!definite) latest.commandId?.let { dao.commandStatus(it, "unknown") }
                putMessageEdit(latest.copy(phase = if (!definite) {
                    if (dispatched == "send") MessageEditPhase.UnknownSend else MessageEditPhase.UnknownRevert
                } else if (latest.reverted) MessageEditPhase.Reverted else MessageEditPhase.Failed,
                    reason = if (definite) "操作未完成，编辑稿与原普通草稿已保留" else "操作结果待核对，不会自动重发"))
            } finally { sending.unlock(); _state.update { it.copy(sendBusy = false) } }
        }
    }

    private fun finishMessageEdit(key: ThreadKey) = synchronized(messageEditGate) {
        messageEdits.remove(key)
        preferences.edit().remove("messageEdit:${key.encoded}").apply()
        _state.update { if (it.messageEdit?.key == key) it.copy(messageEdit = null) else it }
    }

    fun checkMessageEdit(): Job = scope.launch {
        val edit = _state.value.messageEdit ?: return@launch
        val chat = _state.value.selected?.takeIf { it.key == edit.key && it.runtimeId == edit.runtimeId } ?: return@launch
        if (!_state.value.online || edit.busy) return@launch
        val generation = messageEditGeneration.get()
        val selectedGeneration = selectionGeneration.get()
        val link = connectionGeneration.get()
        val transport = client
        fun currentEdit(): MessageEditState? {
            val state = _state.value
            if (messageEditGeneration.get() != generation || selectionGeneration.get() != selectedGeneration ||
                connectionGeneration.get() != link || client !== transport ||
                state.selected?.key != edit.key || state.selected?.runtimeId != edit.runtimeId) return null
            return messageEdits[edit.key]?.takeIf { it.runtimeId == edit.runtimeId && it.turnId == edit.turnId &&
                it.itemId == edit.itemId && it.token == edit.token && it.commandId == edit.commandId && !it.busy }
        }
        try {
            reconcileCommands()
            if (currentEdit() == null) return@launch
            val result = request("thread/edit/status", buildJsonObject {
                put("runtimeId", edit.runtimeId); put("threadId", edit.key.nativeId); put("editToken", edit.token)
            }).asObject()
            synchronized(messageEditGate) {
                // Read after the RPC and serialize with typing/closing, retaining the latest text.
                val saved = currentEdit() ?: return@launch
                require(result.string("threadId", edit.key.nativeId) == edit.key.nativeId)
                when (result.string("status")) {
                    "sent" -> finishMessageEdit(edit.key)
                    "reverted" -> if (saved.phase != MessageEditPhase.UnknownSend) putMessageEdit(saved.copy(reverted = true,
                        phase = MessageEditPhase.Reverted, version = result.string("version", saved.version), reason = "已核对撤回成功；可主动发送编辑稿"))
                    "prepared" -> if (saved.phase != MessageEditPhase.UnknownSend) putMessageEdit(saved.copy(phase = MessageEditPhase.Ready, reason = null))
                    "expired" -> putMessageEdit(saved.copy(phase = if (saved.uncertain) saved.phase else MessageEditPhase.Unavailable,
                        token = "", reason = "原编辑凭据已过期，编辑稿已保留；请在电脑端核对记录"))
                    else -> putMessageEdit(saved.copy(reason = "暂时无法确认操作结果，编辑稿已保留；请在电脑端核对"))
                }
            }
            if (_state.value.selected?.key == chat.key) hydrate(chat)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            synchronized(messageEditGate) {
                currentEdit()?.let { putMessageEdit(it.copy(reason = "核对失败，未重发任何请求；编辑稿已保留")) }
            }
        }
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
        updateDraft { draft ->
            val spans = restoreReferenceDraft(draft.text, draft.references).spans
            draft.copy(text = text, references = encodeReferenceSpans(rebaseReferenceSpans(draft.text, text, spans)))
        }
    }
    /** Bind native editor replacements to the draft and exact text the editor actually observed. */
    fun editDraft(before: String, after: String, edit: ReferenceTextEdit?, expectedKey: ThreadKey?) {
        if (expectedKey == null || _state.value.selected?.key != expectedKey) return
        updateDraft(expectedKey) { draft ->
            if (draft.text != before) draft
            else {
                val spans = restoreReferenceDraft(draft.text, draft.references).spans
                draft.copy(text = after, references = encodeReferenceSpans(rebaseReferenceSpans(before, after, spans, edit)))
            }
        }
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
    fun expandProcess(itemKey: String) {
        updateDraft { draft ->
            val current = runCatching { pocketJson.parseToJsonElement(draft.expansions).jsonArray.map { it.jsonPrimitive.content }.toSet() }.getOrDefault(emptySet())
            draft.copy(expansions = JsonArray((current + itemKey).map(::JsonPrimitive)).toString())
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

    private fun resolvePlan(key: ThreadKey, revision: String, resolved: Boolean, commandId: String, claim: Boolean = false) = synchronized(planResolutionGate) {
        val name = "resolvedPlans:${key.encoded}"
        val attempt = "planAttempt:${key.encoded}:$revision"
        val owner = preferences.getString(attempt, null)
        if (!claim && owner != null && owner != commandId) return@synchronized
        val values = preferences.getStringSet(name, emptySet()).orEmpty().toMutableSet()
        if (claim && revision in values) throw WireException("stale_plan", "此方案已经提交，请先核对结果")
        if (resolved) values.add(revision) else values.remove(revision)
        check(preferences.edit().putStringSet(name, values).putString(attempt, commandId).commit()) { "Unable to retain plan submission state" }
        _state.update { if (it.selected?.key == key) it.copy(resolvedPlans = values.toSet()) else it }
    }

    private suspend fun acknowledgePlanSettings(key: ThreadKey, journal: String) {
        val saved = runCatching { pocketJson.parseToJsonElement(journal).asObject() }.getOrNull() ?: return
        val revision = (saved["pocketPlanModeRevision"] as? JsonPrimitive)?.longOrNull ?: return
        val chat = dao.chat(key)?.takeIf { it.runtimeId == saved.string("pocketPlanRuntime") && !it.conflict } ?: return
        synchronized(settingsGate) {
            val entry = settingsEntry(chat)
            val mode = if (saved.string("pocketPlanAction") == "feedback") "plan" else "default"
            val record = entry.record.submitted(NativeSettings(mode = NativeSetting(true, mode)), mapOf(NativeSettingsField.Mode to revision))
            saveSettings(chat, entry.copy(record = record), durable = true)
        }
        synchronized(planResolutionGate) {
            val feedback = readPlanFeedback(chat)
            if (feedback?.revision == saved.string("pocketPlan") &&
                (saved.string("pocketPlanAction") != "feedback" || feedback.text == saved.string("text"))) {
                savePlanFeedback(chat, null)
            }
        }
    }

    private fun readPlanFeedback(chat: ChatEntity): PlanFeedbackDraft? = preferences.getString("planFeedback:${chat.key.encoded}", null)?.let {
        runCatching { PlanFeedbackDraft.restore(pocketJson.parseToJsonElement(it).jsonObject, chat.key, chat.runtimeId) }.getOrNull()
    }

    private fun savePlanFeedback(chat: ChatEntity, feedback: PlanFeedbackDraft?) {
        preferences.edit().apply {
            if (feedback == null) remove("planFeedback:${chat.key.encoded}")
            else putString("planFeedback:${chat.key.encoded}", feedback.persisted().toString())
        }.apply()
        _state.update { if (it.selected?.key == chat.key && it.selected.runtimeId == chat.runtimeId) it.copy(planFeedback = feedback) else it }
    }

    fun dismissPlan(plan: ProposedPlan) = synchronized(planResolutionGate) {
        val s = _state.value
        val chat = s.selected ?: return@synchronized
        if (chat.isSubagent || PlanPresentation.latest(s.timeline)?.revision != plan.revision) return@synchronized
        cancelPlanFeedback(plan)
        val values = preferences.getStringSet("dismissedPlans:${chat.key.encoded}", emptySet()).orEmpty() + plan.revision
        preferences.edit().putStringSet("dismissedPlans:${chat.key.encoded}", values).apply()
        _state.update { if (it.selected?.key == chat.key) it.copy(dismissedPlans = values) else it }
    }

    fun reopenPlan(plan: ProposedPlan): Boolean = synchronized(planResolutionGate) {
        val s = _state.value
        val chat = s.selected ?: return@synchronized false
        if (PlanPresentation.unavailable(s, plan) != null) return@synchronized false
        val values = preferences.getStringSet("dismissedPlans:${chat.key.encoded}", emptySet()).orEmpty() - plan.revision
        preferences.edit().putStringSet("dismissedPlans:${chat.key.encoded}", values).apply()
        _state.update { if (it.selected?.key == chat.key) it.copy(dismissedPlans = values) else it }
        true
    }

    /** A dedicated editor never consumes the ordinary draft, references or attachments. */
    fun beginPlanFeedback(plan: ProposedPlan): Boolean = synchronized(planResolutionGate) {
        val s = _state.value
        val chat = s.selected ?: return@synchronized false
        if (PlanPresentation.unavailable(s, plan) != null) return@synchronized false
        if (s.planFeedback?.matches(chat.key, chat.runtimeId, plan) == true) return@synchronized true
        synchronized(settingsGate) {
            val entry = settingsEntry(chat)
            val edited = entry.record.edit(NativeSettings(mode = NativeSetting(true, "plan")))
            savePlanFeedback(chat, PlanFeedbackDraft(chat.key, chat.runtimeId, plan.revision,
                previousMode = entry.record.settings.mode, previousModePending = NativeSettingsField.Mode in entry.record.pending,
                modeRevision = edited.revisions[NativeSettingsField.Mode] ?: 0L))
            saveSettings(chat, entry.copy(record = edited))
        }
        true
    }

    fun editPlanFeedback(plan: ProposedPlan, text: String) = synchronized(planResolutionGate) {
        val s = _state.value
        val chat = s.selected ?: return@synchronized
        val feedback = s.planFeedback?.takeIf { it.matches(chat.key, chat.runtimeId, plan) } ?: return@synchronized
        if (s.sendBusy || plan.revision in s.resolvedPlans || PlanPresentation.latest(s.timeline)?.revision != plan.revision) return@synchronized
        savePlanFeedback(chat, feedback.copy(text = text))
    }

    fun cancelPlanFeedback(plan: ProposedPlan) = synchronized(planResolutionGate) {
        val s = _state.value
        val chat = s.selected ?: return@synchronized
        val feedback = s.planFeedback?.takeIf { it.matches(chat.key, chat.runtimeId, plan) } ?: return@synchronized
        if (s.sendBusy || plan.revision in s.resolvedPlans) return@synchronized
        synchronized(settingsGate) {
            val entry = settingsEntry(chat)
            if (entry.record.revisions[NativeSettingsField.Mode] == feedback.modeRevision) {
                val pending = if (feedback.previousModePending) entry.record.pending + NativeSettingsField.Mode else entry.record.pending - NativeSettingsField.Mode
                val restored = entry.record.copy(settings = entry.record.settings.copy(mode = feedback.previousMode), pending = pending,
                    revisions = entry.record.revisions + (NativeSettingsField.Mode to (feedback.modeRevision + 1)))
                saveSettings(chat, entry.copy(record = restored))
            }
        }
        savePlanFeedback(chat, null)
    }

    fun submitPlanFeedback(plan: ProposedPlan): Job {
        val s = _state.value
        val feedback = s.planFeedback?.takeIf { it.matches(s.selected?.key, s.selected?.runtimeId, plan) && it.text.isNotBlank() }
            ?: return scope.launch {}
        return submitPlan(plan, feedback.text)
    }

    fun implementPlan(plan: ProposedPlan): Job = submitPlan(plan, null)

    private fun submitPlan(plan: ProposedPlan, feedback: String?): Job {
        val initial = _state.value
        val chat = initial.selected ?: return scope.launch {}
        if (PlanPresentation.unavailable(initial, plan) != null || !sending.tryLock()) return scope.launch {}
        val selection = selectionGeneration.get()
        val link = client
        _state.update { it.copy(sendBusy = true) }
        return action(lock = null) {
            try {
                require(isSelected(chat, selection) && client === link && link != null)
                require(verifiedHistoryLinks[chat.key] == connectionGeneration.get()) { "Verify current connection history before implementing a plan" }
                require(dao.uncertain(chat.key.computerId).none { it.method != "approvals/respond" && (it.chatKey == chat.key || it.chatKey == null) })
                if (initial.activeRuntime?.loadedThreadIds?.contains(chat.key.nativeId) != true) {
                    native(chat.runtimeId, "thread/resume", buildJsonObject { put("threadId", chat.key.nativeId) }, true, chat.key)
                    refreshRuntimes()
                    hydrate(chat)
                }
                val now = _state.value
                require(isSelected(chat, selection) && client === link)
                require(PlanPresentation.unavailable(now.copy(sendBusy = false), plan) == null)
                if (feedback != null) require(now.planFeedback?.let { it.matches(chat.key, chat.runtimeId, plan) && it.text == feedback } == true)
                require(PlanPresentation.latest(timelineBase(chat.key))?.revision == plan.revision)
                val owned = dao.chat(chat.key)
                require(owned?.runtimeId == chat.runtimeId && !owned.conflict && !owned.isSubagent)
                val settingsTicket = synchronized(settingsGate) { settingsEntry(chat).record.revisions }
                val params = buildJsonObject {
                    put("threadId", chat.key.nativeId)
                    put("clientUserMessageId", UUID.randomUUID().toString())
                    put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", feedback ?: plan.prompt) }) })
                    now.nativeSettings.turnSelection(now.model, now.effort, if (feedback != null) "plan" else "default", now.pendingSettings).forEach { (k, v) -> put(k, v) }
                }
                // mutate journals the command and its proposal guard before dispatch.
                val result = native(chat.runtimeId, "turn/start", params, true, chat.key,
                    buildJsonObject { put("pocketPlan", plan.revision); put("text", feedback ?: plan.prompt)
                        put("pocketPlanAction", if (feedback != null) "feedback" else "implement")
                        put("pocketPlanRuntime", chat.runtimeId); put("pocketPlanModeRevision", settingsTicket[NativeSettingsField.Mode] ?: 0L) }.toString()).asObject()
                if (isSelected(chat, selection)) refreshNativeSettings()
                val turnId = result.obj("turn").string("id").ifBlank { result.string("turnId") }
                if (turnId.isNotBlank()) eventLock.withLock {
                    val old = timelineBase(chat.key)
                    val next = if (turnId in old.completedTurns || old.activeTurn != null) old else old.copy(activeTurn = turnId, status = "inProgress")
                    persistTimeline(chat.key, next, replace = false)
                    _state.update { if (it.selected?.key == chat.key) it.copy(timeline = next) else it }
                }
            } finally { sending.unlock(); _state.update { it.copy(sendBusy = false) } }
        }
    }

    fun send(): Job = send(false)
    fun send(steer: Boolean): Job {
        var s = _state.value
        var chat = s.selected ?: return scope.launch {}
        if (!sending.tryLock()) return scope.launch {}
        _state.update { it.copy(sendBusy = true) }
        return action(lock = null, statusKey = "send:${chat.key.encoded}", globalBusy = false) {
        try {
        require(dao.uncertain(chat.key.computerId).none { it.method != "approvals/respond" && (it.chatKey == chat.key || it.chatKey == null) }) { "Check the previous command outcome before sending again" }
        chat = prepareThreadForSend(chat)
        s = _state.value.takeIf { it.selected?.key == chat.key && it.selected.runtimeId == chat.runtimeId }
            ?: throw PocketOperationException(PocketFailure.Ownership)
        ensureThreadAccess(chat)
        require(s.online && !chat.conflict)
        require(dao.uncertain(chat.key.computerId).none { it.method != "approvals/respond" && (it.chatKey == chat.key || it.chatKey == null) }) { "Check the previous command outcome before sending again" }
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
        val references = restoreReferenceDraft(text, s.draft?.references ?: "[]")
        // The helper revalidates each typed reference against this runtime's current enabled catalog.
        val prepared = prepareReferenceInputs(references.text, references.spans)
        if (prepared.text.isNotBlank()) input += buildJsonObject { put("type", "text"); put("text", prepared.text) }
        input += prepared.references
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
        val running = timelineBase(chat.key).activeTurn
        require(!steer || running != null) { "The turn has finished; send a new message instead" }
        val method = when { steer -> "turn/steer"; running != null -> "thread/queue/add"; else -> "turn/start" }
        require(s.supports(method)) { "This runtime does not support this action" }
        val result = native(chat.runtimeId, method, buildJsonObject {
            put("threadId", chat.key.nativeId); put("input", JsonArray(input))
            put("clientUserMessageId", UUID.randomUUID().toString())
            if (steer) put("expectedTurnId", running) else if (method == "turn/start") {
                s.nativeSettings.turnSelection(s.model, s.effort, s.mode, s.pendingSettings).forEach { (key, value) -> put(key, value) }
            }
        }, true, chat.key, buildJsonObject {
            put("pocketDraft", 1); put("text", text); put("attachments", JsonArray(attachments.map { it.json() }))
        }.toString()).asObject()
        clearAcceptedDraft(chat.key, text, attachments)
        if (method == "thread/queue/add") loadQueue(chat, selectionGeneration.get())
        val turnId = result.obj("turn").string("id").ifBlank { result.string("turnId") }.ifBlank { if (steer) running.orEmpty() else "" }
        if (turnId.isNotBlank()) eventLock.withLock {
            val old = timelineBase(chat.key)
            val related = if (steer && running != null) old.withConfirmedSteer(running, turnId) else old
            val next = if (turnId in related.completedTurns || related.activeTurn != null) related else related.copy(activeTurn = turnId, status = "inProgress")
            persistTimeline(chat.key, next, replace = false)
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
                val unanswered = dao.uncertain(_state.value.computerId)
                require(unanswered.none { it.method == "approvals/respond" && it.draftText == approval.key }) {
                    "This request already has a submitted response awaiting confirmation"
                }
                val runtime = _state.value.runtimes.firstOrNull { it.id == approval.runtimeId } ?: error("Approval runtime unavailable")
                val chat = approval.threadId.takeIf(String::isNotBlank)?.let { ThreadKey(pair!!.computerId, runtime.configStoreId, it) }
                require(chat == null || QuestionAnswerStore(context).all().none { receipt ->
                    !receipt.phase.terminal && receipt.matchesNative(chat, approval) && unanswered.any { it.id == receipt.commandId }
                }) { "This native question already has a response awaiting confirmation" }
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
                } catch (_: UnknownOutcomeException) {
                    // The exact request's dock retains its guard and answer; reads never replay the response.
                    // Another rejected request may already hold a pending sync.
                    // Do not make this submission's completion wait for that unrelated read.
                    scope.launch { runCatching { reconcileCommands() } }
                    return@action
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
        eventLock.withLock { timelines.clear(); timelines.select(_state.value.selected?.key); conversationWindows.clear(); cursors.clear(); snapshotCursors.clear(); invalidateHistoryVerification(); _state.update { it.copy(timeline = NativeTimeline(), conversationCached = false, notice = context.getString(R.string.cache_cleared)) } }
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
        // This optional endpoint is caller-scoped. Match every identity again before adopting evidence.
        val listedReceipts = if ("questions/list" in _state.value.capabilities) {
            try { request("questions/list").asObject().array("data").map(JsonElement::asObject) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: WireException) { emptyList() }
        } else emptyList()
        for (command in dao.uncertain(p.computerId)) {
            val response = try { request("commands/status", buildJsonObject { put("commandId", command.id) }).asObject() }
            catch (e: WireException) { when (e.code) {
                "not_found" -> buildJsonObject { put("status", "not_found") }
                "remote_error", "unknown" -> buildJsonObject { put("status", "unknown") }
                else -> throw e
            } }
            val brokerStatus = response.string("status").takeIf { it in setOf("accepted", "rejected", "submitted", "unknown", "not_found") } ?: "unknown"
            val questionStore = QuestionAnswerStore(context)
            val receipt = questionStore.command(command.id)
            val updated = receipt?.let { current -> current.withDelivery(response.obj("delivery")) ?:
                listedReceipts.firstNotNullOfOrNull(current::withDelivery) ?: current.awaitingConfirmation() }
                ?.let { retainQuestionReceipt(questionStore, it, p.computerId) }
            val status = updated?.commandStatus(brokerStatus) ?: brokerStatus
            val planRevision = runCatching { pocketJson.parseToJsonElement(command.draftText).jsonObject.string("pocketPlan") }.getOrNull()?.takeIf(String::isNotBlank)
            if (planRevision != null && command.chatKey != null && status in setOf("accepted", "rejected")) {
                resolvePlan(command.chatKey, planRevision, status == "accepted", command.id)
                if (status == "accepted") acknowledgePlanSettings(command.chatKey, command.draftText)
            }
            dao.commandStatus(command.id, status)
            val editMarker = runCatching { pocketJson.parseToJsonElement(command.draftText).jsonObject.string("pocketEdit") }.getOrNull()
            if (editMarker in setOf("revert", "send") && command.chatKey != null && status in setOf("accepted", "rejected")) {
                messageEdits[command.chatKey]?.takeIf { it.commandId == command.id }?.let { edit ->
                    when {
                        editMarker == "send" && status == "accepted" -> finishMessageEdit(command.chatKey)
                        editMarker == "revert" && status == "accepted" -> putMessageEdit(edit.copy(reverted = true,
                            phase = MessageEditPhase.Reverted, reason = "已核对撤回成功，请确认后再发送"))
                        else -> putMessageEdit(edit.copy(phase = if (edit.reverted) MessageEditPhase.Reverted else MessageEditPhase.Failed,
                            reason = "原生已确认操作未执行，编辑稿已保留"))
                    }
                }
            }
            if (status == "rejected" && command.method == "approvals/respond" && command.draftText.isNotBlank()) {
                synchronized(approvalGate) {
                    val generation = approvalCommandGenerations[command.id]?.second ?: (approvalGenerations[command.draftText] ?: 0L)
                    if ((approvalGenerations[command.draftText] ?: 0L) == generation) rejectedApprovalRefresh[command.draftText] = generation
                }
            }
            if (status == "accepted" && planRevision == null && editMarker.isNullOrBlank() && command.method in setOf("turn/start", "turn/steer", "thread/queue/add")) {
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
    private suspend fun sync(source: PocketConnection? = null, owner: PocketConnection? = client): List<String> {
        val transport = source ?: owner ?: throw WireException("offline", "Offline")
        val refreshingRejected = synchronized(approvalGate) { rejectedApprovalRefresh.toMap() }
        val requestedCursors = eventLock.withLock { cursors.toMap() }
        val response = transport.request("sync", buildJsonObject { put("cursors", buildJsonObject { requestedCursors.forEach { (id, c) -> put(id, c.json()) } }) }).asObject()
        if (client !== owner) {
            if (source != null) throw WireException("disconnected", "Route preparation owner changed")
            return emptyList()
        }
        // This is an authoritative set, independent of journal replay and cursor age.
        val required = response.array("snapshotRequired").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val computerId = pair?.computerId ?: return required
        // Events received after this sync response are buffered; baselines discard the replay prefix.
        eventLock.withLock {
            if (client !== owner || transport.state.value != LinkState.ONLINE) {
                if (source != null) throw WireException("disconnected", "Route preparation connection closed")
                return@withLock
            }
            if (required.isNotEmpty()) {
                invalidateHistoryVerification()
                _state.update { it.copy(notice = if (response["pendingRequests"] is JsonArray) context.getString(R.string.history_refreshed) else context.getString(R.string.history_no_approvals)) }
                if (source != null) {
                    resyncRequested = true
                    throw WireException("snapshot_required", "Route preparation requires authoritative history")
                }
            }
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
            if (client !== owner || transport.state.value != LinkState.ONLINE) {
                if (source != null) throw WireException("disconnected", "Route preparation connection closed")
                return@withLock
            }
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
                    approval.copy(submitted = wrapper.flag("deliveryPending") || (approval.key in responding && approval.key !in refreshingRejected.keys) ||
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
                    val vanished = s.approvals.filter { it.runtimeId !in staleRuntimes && pending.none { next -> next.key == it.key } }
                    val resolved = s.resolvedRequests.toMutableMap()
                    vanished.forEach { a -> resolved[a.runtimeId] = resolved[a.runtimeId].orEmpty() +
                        ((a.requestId as? JsonPrimitive)?.content ?: a.requestId.toString()) }
                    s.copy(approvals = preserved + refreshed, approvalsFresh = true, resolvedRequests = resolved)
                }
                appliedRejected.forEach { (key, generation) -> rejectedApprovalRefresh.remove(key, generation) }
                }
            }
            db.withTransaction {
                responseCursors.forEach { (runtime, incoming) ->
                    val current = cursors[runtime]
                    if (current != null && current.epoch != incoming.epoch) {
                        val store = _state.value.runtimes.firstOrNull { it.id == runtime }?.configStoreId
                        _state.update { s -> s.copy(agentStatuses = s.agentStatuses.filterKeys { it.computerId != computerId || it.configStoreId != store },
                            resolvedRequests = s.resolvedRequests - runtime) }
                    }
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
    private fun invalidateHistoryVerification() {
        historyVerificationEpoch.incrementAndGet()
        verifiedHistoryLinks.clear()
        _state.update { it.copy(conversationLoadState = ConversationLoadState.Idle) }
    }
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
        if (selected != null && (selected.runtimeId in required || verifiedHistoryLinks[selected.key] != connectionGeneration.get())) scope.launch {
            try { loadConversation(selected) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
        }
    }

    private suspend fun receive(notification: JsonObject) {
        when (notification.string("method")) {
            "quota/updated" -> {
                // The receiver must still belong to the active connection job. A cancelled old
                // pipeline cannot adopt the new client just because the notification has no sender field.
                val receiver = currentCoroutineContext()[Job] ?: return
                val owner = connectionJob ?: return
                fun ownsReceiver(job: Job): Boolean = job === receiver || job.children.any { ownsReceiver(it) }
                if (!receiver.isActive || !owner.isActive || !ownsReceiver(owner)) return
                val connection = client ?: return
                val computer = pair?.computerId ?: return
                val value = notification["params"] as? JsonObject ?: return
                val runtime = value.string("runtimeId")
                val linkGeneration = connectionGeneration.get()
                val selectedGeneration = selectionGeneration.get()
                synchronized(quotaGenerationGate) {
                    val discovered = quotaSourceScope
                    if (discovered?.client !== connection || discovered.computerId != computer ||
                        discovered.runtimeId != runtime || discovered.connectionGeneration != linkGeneration) return@synchronized
                    fun current(s: PocketState) = receiver.isActive && connectionJob === owner && client === connection &&
                        pair?.computerId == computer && s.computerId == computer && s.selected?.key?.computerId == computer &&
                        s.selected.runtimeId == runtime && s.quota.matches(computer, runtime) &&
                        connectionGeneration.get() == linkGeneration && selectionGeneration.get() == selectedGeneration
                    val before = _state.value
                    if (!current(before) || before.quota.withNotification(value) === before.quota) return@synchronized
                    // A native cache update wins over an older read already in flight.
                    quotaReadGeneration.incrementAndGet()
                    _state.update { if (current(it)) it.copy(quota = it.quota.withNotification(value)) else it }
                }
            }
            "codex/event" -> consumeEvent(notification.obj("params"))
            "assistant/changed" -> loadAssistant()
            "pocket/resync" -> { invalidateHistoryVerification(); resyncRequested = true }
            "runtimes/changed" -> {
                _state.update { it.copy(runtimes = notification.obj("params").array("data").map { r -> RuntimeInfo.from(r.asObject()) }) }
                refreshThreadAccess()
                directoryRefresh.trySend(Unit)
            }
            "thread/access/changed" -> {
                val thread = notification.obj("params").string("threadId")
                val chat = _state.value.selected
                if (chat?.key?.nativeId == thread) {
                    _state.update { it.copy(accessFresh = it.accessFresh - chat.key) }
                    refreshThreadAccess()
                }
            }
            "session/preferences/changed" -> refreshSessionPreferences()
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
            invalidateHistoryVerification()
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
        if (method == "item/completed" && NativeQuestionHistory.isRequest(TimelineItem.from(params.string("turnId"), params.obj("item")))) {
            // Read the backend receipt after native result evidence; never infer acceptance here.
            action(lock = null) { reconcileCommands() }
        }
        if (method == "serverRequest/resolved" || method == "pocket/approvalResolved") {
            val id = params["requestId"] ?: params["id"]
            if (id != null) {
                resolvedApprovalKeys.add(JsonArray(listOf(JsonPrimitive(runtime), id)).toString())
                _state.update { it.copy(approvals = it.approvals.filterNot { a -> a.matches(runtime, id) },
                    resolvedRequests = it.resolvedRequests + (runtime to (it.resolvedRequests[runtime].orEmpty() +
                        ((id as? JsonPrimitive)?.content ?: id.toString())))) }
            }
            action(lock = null) { reconcileCommands() }
        } else if (requestId != null && requestId !is JsonNull && method != "item/completed") {
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
        if (key != null && threadId.isNotBlank() && requestId == null && method == "thread/settings/updated") {
            val owner = dao.chat(key)
            if (owner == null || owner.runtimeId == runtime) {
                val chat = owner ?: _state.value.selected?.takeIf { it.key == key && it.runtimeId == runtime }
                    ?: ChatEntity(key, runtime, info?.source.orEmpty(), "", "", "", 0, "", false)
                applySettingsEvent(chat, NativeSettings.notification(params))
                if (_state.value.selected?.key == key) refreshPermissions()
            }
        }
        if (key != null && method == "thread/tokenUsage/updated" && params["tokenUsage"] is JsonObject && dao.chat(key)?.runtimeId?.let { it == runtime } != false) {
            val usage = ContextSnapshot.from(buildJsonObject { put("usage", params.getValue("tokenUsage")); put("status", "ok"); put("source", "live")
                put("updatedAt", System.currentTimeMillis()); put("turnId", params.string("turnId")) })
            contextRevisions[key] = (contextRevisions[key] ?: 0) + 1
            dao.putContextUsage(ContextEntity(key, usage.json().toString()))
            if (_state.value.selected?.let { it.key == key && it.runtimeId == runtime } == true) _state.update { it.copy(contextSnapshot = usage, advanced = it.advanced.copy(usage = usage.usage)) }
        }
        if (key != null && _state.value.selected?.key == key) when (method) {
            "thread/goal/updated" -> _state.update { it.copy(advanced = it.advanced.copy(goal = params["goal"] as? JsonObject)) }
            "thread/goal/cleared" -> _state.update { it.copy(advanced = it.advanced.copy(goal = null)) }
        }
        db.withTransaction {
            val watermark = key?.let { snapshotCursors[it] ?: dao.snapshot(it)?.let { row -> Cursor(row.epoch, row.sequence).also { c -> snapshotCursors[key] = c } } }
            val owner = key?.let { dao.chat(it) }
            if (key != null && (owner == null || owner.runtimeId == runtime) && threadId.isNotBlank() && requestId == null && watermark?.covers(next) != true && method !in setOf("serverRequest/resolved", "pocket/approvalResolved")) {
                val old = timelineBase(key)
                val updated = NativeReducer.reduce(old, event, "${next.epoch}:${next.sequence}")
                val changed = updated.items.values.filter { old.items[it.key] !== it || old.turns[it.turnId] != updated.turns[it.turnId] }
                val order = updated.items.keys.withIndex().associate { it.value to it.index.toLong() }
                dao.putItems(changed.map { ItemEntity.from(key, it.withTurnCache(updated.turns[it.turnId]), order.getValue(it.key)) })
                timelines.update(key, updated, changed)
                if (_state.value.selected?.key == key && _state.value.selected?.runtimeId == runtime) _state.update { it.copy(timeline = updated, stopping = stopKey(key, updated.activeTurn) in stoppingTurns) }
                _state.update { s -> if (s.agentReader?.child?.key == key && s.agentReader.child.runtimeId == runtime)
                    s.copy(agentReader = s.agentReader.copy(timeline = updated, updatedAt = System.currentTimeMillis())) else s }
                if (method in setOf("item/started", "item/completed")) owner?.let { classifyAgentChildren(it, updated) }
                if (method in setOf("item/started", "item/completed")) updateAsyncState(key, updated)
                if (method == "item/completed" && params.string("turnId").isNotBlank()) {
                    agentResumeEvidence(TimelineItem.from(params.string("turnId"), params.obj("item"), complete = true), next)
                        .forEach { (childId, status) -> publishAgentStatus(key.copy(nativeId = childId), status) }
                }
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
                    if (chat.isSubagent && method in setOf("turn/started", "turn/completed", "thread/status/changed")) {
                        val observed = if (method == "thread/status/changed") AgentLiveStatus.fromNativeStatus(params.obj("status"), cursor = next)
                            else AgentLiveStatus(params.obj("turn").string("status", if (method == "turn/started") "inProgress" else "completed"),
                                params.obj("turn").string("id").takeIf(String::isNotBlank), next)
                        observed?.let { publishAgentStatus(key, it) }
                    }
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
        if (method == "turn/completed") {
            val chat = _state.value.selected?.takeIf { it.key == key && it.runtimeId == runtime }
            val completedId = params.obj("turn").string("id", params.string("turnId"))
            val generation = connectionGeneration.get()
            val selectedGeneration = selectionGeneration.get()
            val readKey = "$generation:$selectedGeneration:$completedId"
            if (chat != null && ConversationPresentation.needsCompletionRead(_state.value.timeline, completedId) && completionReads.add(readKey)) {
                // Never await network while holding the event mutex. A late final cancels the need.
                scope.launch {
                    delay(350)
                    if (generation == connectionGeneration.get() && isSelected(chat, selectedGeneration) &&
                        ConversationPresentation.needsCompletionRead(_state.value.timeline, completedId)) {
                        try { hydrate(chat) } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Cached content remains readable; explicit reload is available. */ }
                    }
                    if (completionReads.size > 128) completionReads.removeIf { !it.startsWith("${connectionGeneration.get()}:") }
                }
            }
        }
        if (key == _state.value.selected?.key && method == "item/completed" && params.obj("item").string("type") in setOf("collabAgentToolCall", "subAgentActivity")) refreshAgentNames()
        if (key == _state.value.selected?.key && (method == "thread/compacted" ||
                (method == "item/completed" && params.obj("item").string("type") == "contextCompaction"))) {
            _state.update { it.copy(contextSnapshot = it.contextSnapshot.copy(status = "stale")) }
            refreshContext()
        }
    }

    /** A miss is never an empty-history signal, including delayed writes after selection changes. */
    private fun cachedTimelineBase(key: ThreadKey): NativeTimeline? {
        timelines[key]?.let { return it }
        val current = _state.value
        if (current.selected?.key == key) return current.timeline
        current.agentReader?.takeIf { it.child?.key == key }?.let { return it.timeline }
        return null
    }
    private suspend fun timelineBase(key: ThreadKey): NativeTimeline {
        cachedTimelineBase(key)?.let { return it }
        return NativeTimeline(items = db.withTransaction { dao.cachedItems(key) }
            .associateTo(linkedMapOf()) { it.itemKey to it.item() }).withCachedTurns()
    }

    private suspend fun persistTimeline(key: ThreadKey, timeline: NativeTimeline, replace: Boolean) {
        db.withTransaction {
            val next = timeline.items.values.mapIndexed { index, i -> ItemEntity.from(key, i.withTurnCache(timeline.turns[i.turnId]), index.toLong()) }
            val diff = itemPersistenceDiff(dao.cachedItems(key), next, replace)
            diff.removed.chunked(400).forEach { dao.removeItems(key, it) }
            if (diff.upserts.isNotEmpty()) dao.putItems(diff.upserts)
        }
        timelines[key] = timeline
    }
    fun older(): Job {
        val s = _state.value
        val chat = s.selected ?: return scope.launch {}
        val generation = selectionGeneration.get()
        val link = connectionGeneration.get()
        val epoch = historyVerificationEpoch.get()
        if (s.conversationLoadState != ConversationLoadState.Ready || verifiedHistoryLinks[chat.key] != link) return scope.launch {}
        if (s.historyExhausted || !loadingHistory.add(chat.key)) return scope.launch {}
        _state.update { it.copy(historyLoading = true) }
        return action(lock = null) {
        try {
        val response = native(chat.runtimeId, "thread/turns/list", buildJsonObject { put("threadId", chat.key.nativeId); put("limit", HISTORY_OLDER_PAGE); put("sortDirection", "desc"); put("itemsView", "full"); s.nextTurns?.let { put("cursor", it) } }).asObject()
        val turns = response.array("data").reversed()
        val old = NativeReducer.snapshot(buildJsonObject { put("turns", JsonArray(turns)) })
        eventLock.withLock {
            if (!isSelected(chat, generation) || connectionGeneration.get() != link || historyVerificationEpoch.get() != epoch) return@withLock
            val current = timelineBase(chat.key)
            val merged = current.copy(items = LinkedHashMap(old.items).apply { putAll(current.items) }, turns = old.turns + current.turns, completedTurns = old.completedTurns + current.completedTurns)
            persistTimeline(chat.key, merged, true)
            val next = response["nextCursor"]?.takeUnless { v -> v is JsonNull }
            val exhausted = next == null || next == s.nextTurns
            _state.update { if (isSelected(chat, generation) && connectionGeneration.get() == link && historyVerificationEpoch.get() == epoch)
                it.copy(timeline = merged, nextTurns = next, historyExhausted = exhausted) else it }
            conversationWindows[chat.key] = ConversationWindow(chat.runtimeId, chat.conflict, next, exhausted,
                s.conversationLoadState, s.conversationLoadError)
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
        require(dao.uncertain(chat.key.computerId).none { it.method != "approvals/respond" && (it.chatKey == chat.key || it.chatKey == null) })
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
            val running = timelineBase(chat.key).activeTurn ?: error("The turn has finished; the queued message will run next")
            val input = entry.array("input")
            require(input.isNotEmpty())
            // Steer first: if deleting fails afterwards the message is duplicated, never lost.
            val response = checkedMutation(chat, "turn/steer", buildJsonObject {
                put("input", input); put("clientUserMessageId", UUID.randomUUID().toString()); put("expectedTurnId", running)
            }).asObject()
            recordSteerRelation(chat, running, response)
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
    private suspend fun recordSteerRelation(chat: ChatEntity, running: String, response: JsonObject) {
        val turnId = response.obj("turn").string("id").ifBlank { response.string("turnId") }
        if (turnId.isBlank() || turnId == running) return
        eventLock.withLock {
            val previous = timelineBase(chat.key)
            val updated = previous.withConfirmedSteer(running, turnId)
            persistTimeline(chat.key, updated, replace = false)
            _state.update { if (it.selected?.key == chat.key && it.selected.runtimeId == chat.runtimeId) it.copy(timeline = updated) else it }
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
        val initial = _state.value
        val chat = initial.selected ?: return scope.launch {}
        initial.fastCapabilityFailure(enabled)?.let { failure ->
            _state.update { it.copy(error = failure.label) }; return scope.launch {}
        }
        if (!initial.accessAllowsSending() || !initial.paired || !initial.online || initial.busy || initial.sendBusy || initial.uncertainSend ||
            initial.timeline.activeTurn != null || chat.isSubagent || chat.archived || chat.conflict ||
            !initial.supports("thread/settings/update") || !fastUpdates.add(chat.key)) return scope.launch {}
        _state.update { it.copy(fastPending = it.fastPending + chat.key) }
        val linkGeneration = connectionGeneration.get()
        val selection = selectionGeneration.get()
        return action(lock = null, statusKey = "fast:${chat.key.encoded}", globalBusy = false) {
            try {
            require(isSelected(chat, selection) && connectionGeneration.get() == linkGeneration && _state.value.timeline.activeTurn == null)
            checkedMutation(chat, "thread/settings/update", buildJsonObject { put("serviceTier", if (enabled) JsonPrimitive("fast") else JsonNull) })
            // Settings read/event is the authority, including native normalization of the tier.
            loadNativeSettings(chat, selection)
            } finally {
                fastUpdates.remove(chat.key)
                _state.update { it.copy(fastPending = it.fastPending - chat.key) }
            }
        }
    }
    /** Quota reads never take the event/action lock or change transport state. */
    fun refreshQuotaSources(): Job {
        return synchronized(quotaGenerationGate) {
            val s = _state.value
            val selected = s.selected ?: return@synchronized scope.launch {}
            val connection = client
            val computer = s.computerId
            if (computer != pair?.computerId || selected.key.computerId != computer) return@synchronized scope.launch {}
            val captured = QuotaRequestScope(connection, computer, selected.runtimeId, connectionGeneration.get(), selectionGeneration.get())
            quotaSourcesFlight?.takeIf { (ticket, job) -> !job.isCompleted &&
                ticket.isCurrent(captured, quotaSourcesGeneration.get(), quotaReadGeneration.get()) }?.let { return@synchronized it.second }
            val ticket = QuotaRequestTicket(captured, quotaSourcesGeneration.incrementAndGet())
            fun current(value: PocketState) = pair?.computerId == computer && value.selected?.key?.computerId == computer &&
                ticket.isCurrent(QuotaRequestScope(client, value.computerId, value.selected?.runtimeId.orEmpty(),
                    connectionGeneration.get(), selectionGeneration.get()), quotaSourcesGeneration.get(), quotaReadGeneration.get())
            fun publish(transform: (QuotaState) -> QuotaState) = synchronized(quotaGenerationGate) {
                _state.update { if (current(it)) it.copy(quota = transform(it.quota.forScope(computer, selected.runtimeId))) else it }
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                if (!s.online || connection == null) { publish { it.copy(loading = false, status = "offline") }; return@launch }
                if (!s.supports("quota/sources")) { publish { it.copy(loading = false, status = "unsupported") }; return@launch }
                publish { it.copy(loading = true) }
                var readSource: String? = null
                try {
                    if (!current(_state.value)) return@launch
                    // Use the captured client; the shared request() may already point at a replacement.
                    val value = connection.request("quota/sources", buildJsonObject { put("runtimeId", selected.runtimeId) }) as? JsonObject
                        ?: throw WireException("invalid_params", "Invalid quota sources")
                    require(value.string("runtimeId") == selected.runtimeId)
                    val sources = (value["sources"] as? JsonArray)?.map { it as? JsonObject ?: error("Invalid quota source") }
                        ?: error("Missing quota sources")
                    require(sources.all { it.string("id").isNotBlank() } && sources.map { it.string("id") }.distinct().size == sources.size)
                    synchronized(quotaGenerationGate) {
                        if (current(_state.value)) {
                            quotaSourceScope = captured
                            publish { it.withSources(computer, selected.runtimeId, sources,
                                value.string("selectedSourceId").takeIf(String::isNotBlank), value.string("status", "unknown")) }
                        }
                    }
                    if (current(_state.value) && value.string("status") !in setOf("unavailable", "unsupported"))
                        readSource = _state.value.quota.selectedSourceId
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { publish { it.withFailure(quotaFailureStatus((failure as? WireException)?.code)) } }
                finally {
                    publish { it.copy(loading = false) }
                    synchronized(quotaGenerationGate) { if (quotaSourcesFlight?.first == ticket) quotaSourcesFlight = null }
                }
                // Rediscovery keeps the last good result; the demand read obeys the same cooldown as polling.
                if (readSource != null && current(_state.value)) refreshQuota(readSource!!).join()
            }
            quotaSourcesFlight = ticket to job
            job.start()
            job
        }
    }
    fun refreshQuota(sourceId: String, refresh: Boolean = false): Job {
        return synchronized(quotaGenerationGate) {
            val s = _state.value
            val selected = s.selected ?: return@synchronized scope.launch {}
            val runtime = selected.runtimeId
            val computer = s.computerId
            val connection = client
            val source = s.quota.sources.firstOrNull { it.string("id") == sourceId }
            if (computer != pair?.computerId || selected.key.computerId != computer ||
                !s.quota.matches(computer, runtime) || source == null) return@synchronized scope.launch {}
            val captured = QuotaRequestScope(connection, computer, runtime, connectionGeneration.get(), selectionGeneration.get())
            quotaReadFlight?.takeIf { (ticket, id, job) -> id == sourceId && !job.isCompleted &&
                ticket.isCurrent(captured, quotaSourcesGeneration.get(), quotaReadGeneration.get()) }?.let { return@synchronized it.third }
            val ticket = QuotaRequestTicket(captured, quotaSourcesGeneration.get(), quotaReadGeneration.incrementAndGet())
            fun current(value: PocketState) = pair?.computerId == computer && value.selected?.key?.computerId == computer &&
                value.quota.matches(computer, runtime) && value.quota.selectedSourceId == sourceId &&
                value.quota.sources.any { it.string("id") == sourceId } &&
                ticket.isCurrent(QuotaRequestScope(client, value.computerId, value.selected?.runtimeId.orEmpty(),
                    connectionGeneration.get(), selectionGeneration.get()), quotaSourcesGeneration.get(), quotaReadGeneration.get())
            fun publish(transform: (QuotaState) -> QuotaState) = synchronized(quotaGenerationGate) {
                _state.update { if (current(it)) it.copy(quota = transform(it.quota)) else it }
            }
            val discovering = quotaSourcesFlight?.second?.isCompleted == false
            _state.update { if (pair?.computerId == computer && it.selected?.key?.computerId == computer && it.quota.matches(computer, runtime) &&
                ticket.isCurrent(QuotaRequestScope(client, it.computerId, it.selected?.runtimeId.orEmpty(),
                    connectionGeneration.get(), selectionGeneration.get()), quotaSourcesGeneration.get(), quotaReadGeneration.get()))
                it.copy(quota = it.quota.selectSource(sourceId).copy(loading = discovering)) else it }
            // A source choice during rediscovery is kept and read after the catalog arrives.
            if (discovering) return@synchronized scope.launch {}
            val timing = s.quota.readTimings[sourceId] ?: QuotaReadTiming()
            val now = System.currentTimeMillis()
            if (!s.online || connection == null) { publish { it.copy(status = "offline") }; return@synchronized scope.launch {} }
            if (!s.supports("quota/read")) { publish { it.copy(status = "unsupported") }; return@synchronized scope.launch {} }
            val wait = quotaRefreshDelayMillis(source, timing, refresh, now)
            if (wait == null || wait > 0) {
                publish { it.copy(status = if (wait == null && it.result == null) "auto_disabled" else if (refresh) "cooldown" else it.status) }
                return@synchronized scope.launch {}
            }
            publish { it.copy(loading = true, readTimings = it.readTimings + (sourceId to timing.copy(lastAttemptAt = now))) }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (!current(_state.value)) return@launch
                    val result = connection.request("quota/read", buildJsonObject {
                        put("runtimeId", runtime); put("sourceId", sourceId); put("refresh", refresh)
                    }) as? JsonObject ?: throw WireException("invalid_params", "Invalid quota result")
                    require(result.string("runtimeId") == runtime && result.string("sourceId") == sourceId)
                    publish { q -> q.withResult(result).copy(readTimings = q.readTimings +
                        (sourceId to (q.readTimings[sourceId] ?: timing).withRetry(result.number("retryAfterSeconds"), refresh, System.currentTimeMillis()))) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { publish { it.withFailure(quotaFailureStatus((failure as? WireException)?.code)) } }
                finally {
                    publish { it.copy(loading = false) }
                    synchronized(quotaGenerationGate) { if (quotaReadFlight?.first == ticket) quotaReadFlight = null }
                }
            }
            quotaReadFlight = Triple(ticket, sourceId, job)
            job.start()
            job
        }
    }

    fun answerAsync(id: String, answers: JsonObject): Job {
        val pending = _state.value.advanced.asyncQuestions.firstOrNull { it.string("id") == id }
            ?: return scope.launch {}
        val answer = answers.string("answer").ifBlank {
            val entry = answers[id]
            val list = if (entry is JsonArray) entry else (entry as? JsonObject)?.get("answers") as? JsonArray
            list?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString("\n").orEmpty()
        }
        return answerAsyncBatch(listOf(pending to answer))
    }
    fun answerAsyncBatch(answers: List<Pair<JsonObject, String>>): Job {
        val chat = selected()
        return action {
            val timeline = timelineBase(chat.key)
            val pending = AsyncQuestions.pending(timeline, submittedQuestions(chat.key)).associateBy { it.string("id") }
            require(answers.isNotEmpty() && answers.size <= 32)
            val checked = answers.map { (question, answer) ->
                val current = pending[question.string("id")] ?: error("Question already answered")
                require(!current.flag("submitted") && answer.isNotBlank() && answer.length <= 64_000)
                current to answer.trim()
            }
            require(checked.none { it.first.flag("isSecret") }) { "Sensitive questions cannot use the ordinary asynchronous reply transport" }
            val ids = checked.map { it.first.string("id") }.toSet()
            require(ids.size == checked.size)
            val payload = buildJsonArray { checked.forEach { (question, answer) -> add(buildJsonObject {
                put("questionItemId", question.string("questionItemId")); put("question", question.string("title")); put("answer", answer)
            }) } }
            val text = "<send_user_message_question_reply>\n$payload\n</send_user_message_question_reply>"
            val running = timeline.activeTurn
            val method = if (running == null) "turn/start" else "turn/steer"
            validateMutation(chat, method)
            val submitted = submittedQuestions(chat.key) + ids
            check(preferences.edit().putStringSet("questions:${chat.key.encoded}", submitted).commit())
            updateAsyncState(chat.key, timeline)
            try {
                val response = native(chat.runtimeId, method, buildJsonObject {
                    put("threadId", chat.key.nativeId)
                    put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                    put("clientUserMessageId", UUID.randomUUID().toString())
                    running?.let { put("expectedTurnId", it) }
                }, true, chat.key).asObject()
                if (running != null) recordSteerRelation(chat, running, response)
            } catch (e: WireException) {
                preferences.edit().putStringSet("questions:${chat.key.encoded}", submitted - ids).commit()
                updateAsyncState(chat.key, timelineBase(chat.key)); throw e
            }
        }
    }
    private fun submittedQuestions(key: ThreadKey) = preferences.getStringSet("questions:${key.encoded}", emptySet()).orEmpty().toSet()
    private fun updateAsyncState(key: ThreadKey, timeline: NativeTimeline) {
        val questions = AsyncQuestions.pending(timeline, submittedQuestions(key))
        _state.update { it.copy(pendingQuestions = if (questions.isEmpty()) it.pendingQuestions - key.encoded else it.pendingQuestions + (key.encoded to questions),
            advanced = if (it.selected?.key == key) it.advanced.copy(asyncQuestions = questions) else it.advanced) }
    }
    private val referenceGeneration = AtomicLong()
    fun loadReferences(query: String = ""): Job {
        val chat = _state.value.selected ?: return scope.launch {}
        val generation = selectionGeneration.get()
        val requestGeneration = referenceGeneration.incrementAndGet()
        val connection = client
        val normalized = query.take(256)
        fun current() = isSelected(chat, generation) && client === connection && referenceGeneration.get() == requestGeneration && pair?.computerId == chat.key.computerId
        fun publish(block: (AdvancedConversationState) -> AdvancedConversationState) {
            _state.update { if (current()) it.copy(advanced = block(it.advanced)) else it }
        }
        publish { it.copy(references = emptyList(), referencesQuery = normalized, referencesLoading = true, referencesError = null, referencesUnsupported = emptySet()) }
        return scope.launch {
            try {
                require(_state.value.online && _state.value.supports("references/list"))
                val result = (connection ?: throw WireException("offline", "Offline")).request("references/list", buildJsonObject {
                    put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId); put("query", normalized)
                }).asObject()
                publish { it.copy(references = result.array("data").map(JsonElement::asObject).filter { entry -> referenceToken(entry) != null },
                    referencesLoading = false, referencesUnsupported = result.array("unsupported").mapNotNull { value -> (value as? JsonPrimitive)?.contentOrNull }.toSet()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish { it.copy(referencesLoading = false, referencesError = if (_state.value.online) "候选目录未能加载，请重试" else "连接电脑后可读取候选目录") } }
        }
    }
    fun insertReference(value: JsonObject) {
        require(value in _state.value.advanced.references)
        referenceToken(value) ?: return
        val name = referenceDisplayName(value) ?: return
        updateDraft { draft ->
            val refs = restoreReferenceDraft(draft.text, draft.references)
            if (refs.spans.size >= 12) return@updateDraft draft
            val prefix = refs.text + if (refs.text.isNotEmpty() && !refs.text.last().isWhitespace()) " " else ""
            val spans = refs.spans + ReferenceSpan(prefix.length, prefix.length + name.length, name, value)
            draft.copy(text = prefix + name + " ", references = encodeReferenceSpans(spans))
        }
    }
    fun insertReferenceAt(value: JsonObject, expectedText: String, start: Int, endExclusive: Int): Boolean {
        if (value !in _state.value.advanced.references) return false
        var inserted = false
        updateDraft { draft ->
            val refs = restoreReferenceDraft(draft.text, draft.references)
            if (refs.spans.size >= 12) return@updateDraft draft
            val edit = insertVerifiedReference(draft.text, expectedText, start, endExclusive, value, refs.spans) ?: return@updateDraft draft
            inserted = true
            draft.copy(text = edit.text, references = encodeReferenceSpans(edit.spans))
        }
        return inserted
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
    fun readSource(path: String, owner: ThreadKey? = null): Job {
        val snapshot = _state.value
        val parent = snapshot.selected ?: return scope.launch {}
        val isChild = owner != null && owner != parent.key
        val chat = (if (isChild) snapshot.agentReader?.child else parent)
            ?.takeIf { owner == null || it.key == owner } ?: return scope.launch {}
        val generation = selectionGeneration.get()
        val readerGeneration = agentReadGeneration.get()
        val link = connectionGeneration.get()
        val transport = client
        fun authorized(s: PocketState): Boolean {
            val selected = s.selected ?: return false
            if (selected.key != parent.key || selected.runtimeId != parent.runtimeId || selected.conflict ||
                s.computerId != parent.key.computerId || chat.conflict) return false
            val runtime = s.runtimes.firstOrNull { it.id == chat.runtimeId } ?: return false
            if (runtime.configStoreId != chat.key.configStoreId || !s.online || !s.supports("workspace/read", runtime)) return false
            if (!isChild) return true
            val reader = s.agentReader ?: return false
            val child = reader.child ?: return false
            return reader.parent == parent.key && !reader.loading && reader.error == null && reader.updatedAt > 0 &&
                child.key == chat.key && child.runtimeId == chat.runtimeId && !child.conflict && child.isSubagent &&
                child.parentThreadId == parent.key.nativeId && child.key.computerId == parent.key.computerId &&
                child.key.configStoreId == parent.key.configStoreId && child.runtimeId == parent.runtimeId
        }
        if (transport == null || path.isBlank() || !authorized(snapshot)) return scope.launch {}
        val requestGeneration = fileReadGeneration.incrementAndGet()
        fun current(s: PocketState = _state.value) = authorized(s) && generation == selectionGeneration.get() &&
            client === transport && link == connectionGeneration.get() && fileReadGeneration.get() == requestGeneration &&
            (!isChild || readerGeneration == agentReadGeneration.get())
        return scope.launch {
            if (!current()) return@launch
            activeActions.incrementAndGet()
            _state.update { if (current(it)) it.copy(busy = true, error = null) else it }
            try {
                if (!current()) return@launch
                // Reader metadata can inherit the parent's cwd. Verify this child's native cwd using
                // the existing read RPC; workspace/read independently proves identity and canonical bounds.
                val roots = if (isChild) {
                    val proof = transport.request("codex/request", buildJsonObject {
                        put("runtimeId", chat.runtimeId); put("method", "thread/read")
                        put("params", buildJsonObject { put("threadId", chat.key.nativeId); put("includeTurns", false) })
                    }).asObject().obj("thread")
                    if (!current()) return@launch
                    require(proof.string("id") == chat.key.nativeId && proof.string("cwd").isNotBlank())
                    listOf(proof.string("cwd"))
                } else snapshot.advanced.projectFiles?.roots.orEmpty() + chat.cwd
                if (!current()) return@launch
                val result = transport.request("workspace/read", buildJsonObject {
                    put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId); put("path", path)
                }).asObject()
                val actualPath = result.string("path", path)
                val normalized = actualPath.replace('\\', '/')
                val windows = Regex("^[A-Za-z]:/").containsMatchIn(normalized) || normalized.startsWith("//")
                val root = roots.filter { it.isNotBlank() && normalized.startsWith(it.replace('\\', '/').trimEnd('/') + "/", ignoreCase = windows) }
                    .maxByOrNull { it.length }
                _state.update { if (current(it)) it.copy(advanced = it.advanced.copy(sourcePreview =
                    SourcePreview(actualPath, result.string("text"), result.flag("truncated"), root, chat.key, readOnly = isChild))) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // A late failure must not leak into the main conversation after the reader is gone.
                _state.update { if (current(it)) it.copy(error = safeError(failure)) else it }
            } finally {
                activeActions.decrementAndGet()
                _state.update { it.copy(busy = activeActions.get() > 0) }
            }
        }
    }
    fun clearSourcePreview() { fileReadGeneration.incrementAndGet(); _state.update { it.copy(advanced = it.advanced.copy(sourcePreview = null)) } }

    /** Media is addressable only by a native item in the currently viewed main/child conversation. */
    suspend fun toolMedia(media: ToolMediaRef, offset: Long, maxBytes: Int, owner: ChatEntity? = null): ToolMediaChunk {
        require(media.readable && offset in 0 until (if (media.status == ToolMediaStatus.Recoverable) MAX_TOOL_IMAGE_BYTES else media.byteLength) && maxBytes in 1..TOOL_IMAGE_CHUNK_BYTES)
        val snapshot = _state.value
        val transport = client ?: throw WireException("offline", "电脑已离线")
        val link = connectionGeneration.get()
        val selection = selectionGeneration.get()
        val readerGeneration = agentReadGeneration.get()
        val child = snapshot.agentReader?.child
        val isChild = child?.key?.nativeId == media.threadId && child.runtimeId == media.runtimeId
        val chat = (owner ?: if (isChild) child else snapshot.selected)
            ?.takeIf { it.key.nativeId == media.threadId && it.runtimeId == media.runtimeId }
            ?: throw WireException("stale_media", "图片所属会话已切换")
        val timeline = if (isChild) snapshot.agentReader!!.timeline else snapshot.timeline
        if (chat.key.computerId != snapshot.computerId || timeline.items.values.none { item -> item.turnId == media.turnId && item.itemId == media.itemId && media in itemMedia(item) })
            throw PocketOperationException(PocketFailure.MediaOwner)
        val runtime = snapshot.runtimes.firstOrNull { it.id == media.runtimeId }
        if (!snapshot.online || runtime == null || !snapshot.supports("thread/media/read", runtime))
            throw WireException("unsupported", "图片暂不可读取，请确认连接并更新电脑助手")
        fun current() = client === transport && connectionGeneration.get() == link && selectionGeneration.get() == selection &&
            _state.value.computerId == chat.key.computerId &&
            (!isChild || (agentReadGeneration.get() == readerGeneration && _state.value.agentReader?.child?.key == chat.key))
        val result = transport.request("thread/media/read", buildJsonObject {
            put("runtimeId", media.runtimeId); put("threadId", media.threadId); put("turnId", media.turnId)
            put("itemId", media.itemId); put("mediaId", media.mediaId); put("offset", offset); put("maxBytes", maxBytes)
        }).asObject()
        if (!current()) throw CancellationException("Media view changed")
        return ToolMediaChunk.from(result)
    }

    private val mediaRenewals = ConcurrentHashMap<String, Long>()
    private val mediaRenewalGate = Mutex()

    /** Renew opaque references only from the same authenticated native history.
     * An old media ID is never interpreted as a desktop path or another item. */
    suspend fun refreshToolMedia(media: ToolMediaRef, manual: Boolean = false) = mediaRenewalGate.withLock {
        val snapshot = _state.value
        if (!snapshot.online) return@withLock
        val child = snapshot.agentReader?.child
        val isChild = child?.key?.nativeId == media.threadId && child.runtimeId == media.runtimeId
        val owner = (if (isChild) child else snapshot.selected)
            ?.takeIf { it.key.nativeId == media.threadId && it.runtimeId == media.runtimeId } ?: return@withLock
        val timeline = if (isChild) snapshot.agentReader!!.timeline else snapshot.timeline
        val currentMedia = timeline.items.values.firstOrNull { it.turnId == media.turnId && it.itemId == media.itemId }
            ?.let(::itemMedia).orEmpty()
        if (media !in currentMedia) return@withLock
        val key = "${owner.key.encoded}:${media.turnId}:${media.itemId}:${connectionGeneration.get()}"
        val now = System.nanoTime() / 1_000_000
        if (!manual && mediaRenewals[key]?.let { now - it < 60_000 } == true) return@withLock
        if (mediaRenewals.size >= 256) mediaRenewals.clear()
        mediaRenewals[key] = now
        if (isChild) openAgentReader(owner.key.nativeId).join() else loadConversation(owner)
    }

    private val imageCache = File(context.cacheDir, "desktop-images").apply { mkdirs() }
    private val imageFetches = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<File?>>()
    private data class ImageFailure(val at: Long, val link: Long, val failure: Exception)
    private val imageFailures = ConcurrentHashMap<String, ImageFailure>()
    private val imageGate = kotlinx.coroutines.sync.Semaphore(2)
    fun canFetchDesktopImages(): Boolean = _state.value.let { it.online && it.selected != null && it.supports("workspace/image") }

    /**
     * Desktop image bytes over the bounded `workspace/image` chunk API, cached on disk by path.
     * Null when the helper predates the API, the file is gone, or it is not a real image.
     */
    private fun desktopImageCacheKey(path: String, chat: ChatEntity) = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${chat.key.encoded}\u0000${chat.runtimeId}\u0000$path".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun clearDesktopImageFailure(path: String, owner: ChatEntity) { imageFailures.remove(desktopImageCacheKey(path, owner)) }

    suspend fun desktopImage(path: String, owner: ChatEntity? = _state.value.selected): File? {
        val chat = owner ?: return null
        val name = desktopImageCacheKey(path, chat)
        if (pair?.computerId != chat.key.computerId) throw PocketOperationException(PocketFailure.MediaOwner)
        val target = File(imageCache, name)
        if (target.isFile && target.length() > 0) return target.also { it.setLastModified(System.currentTimeMillis()) }
        val transport = client ?: throw WireException("offline", "Offline")
        val link = connectionGeneration.get()
        if (!_state.value.online) throw WireException("offline", "Offline")
        if (!_state.value.supports("workspace/image", _state.value.runtimes.firstOrNull { it.id == chat.runtimeId }))
            throw PocketOperationException(PocketFailure.MediaUnsupported)
        imageFailures[name]?.let { if (it.link == link && System.currentTimeMillis() - it.at < 60_000) throw it.failure else imageFailures.remove(name, it) }
        val fetchKey = "$name:$link"
        val job = imageFetches.computeIfAbsent(fetchKey) { scope.async(start = CoroutineStart.LAZY) {
            val partial = File(imageCache, "$name.$link.part")
            fun current() = client === transport && connectionGeneration.get() == link && pair?.computerId == chat.key.computerId
            fun requireCurrent() { if (!current()) throw PocketOperationException(PocketFailure.MediaOwner) }
            try {
                imageGate.withPermit {
                    requireCurrent()
                    partial.outputStream().use { out ->
                        var offset = 0L
                        while (true) {
                            requireCurrent()
                            val chunk = transport.request("workspace/image", buildJsonObject {
                                put("runtimeId", chat.runtimeId); put("threadId", chat.key.nativeId); put("path", path); put("offset", offset)
                            }).asObject()
                            requireCurrent()
                            val bytes = android.util.Base64.decode(chunk.string("data"), android.util.Base64.DEFAULT)
                            require(bytes.isNotEmpty() || chunk.flag("eof"))
                            out.write(bytes); offset += bytes.size
                            require(offset <= 24L * 1024 * 1024)
                            if (chunk.flag("eof")) break
                        }
                    }
                    requireCurrent()
                    check(partial.renameTo(target))
                    trimImageCache()
                    target
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current()) imageFailures[name] = ImageFailure(System.currentTimeMillis(), link, failure)
                throw failure
            } finally { partial.delete() }
        } }
        // Publish before execution: an immediate failure must not leave a
        // completed Deferred permanently cached by computeIfAbsent.
        job.invokeOnCompletion { imageFetches.remove(fetchKey, job) }
        job.start()
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
        val transport = client ?: return@action
        val computer = pair?.computerId
        val status = transport.request("diagnostics", timeoutMillis = 5000).asObject()
        if (client !== transport || pair?.computerId != computer) return@action
        _state.update { it.copy(upstreamVersions = status["upstreamVersions"] as? JsonObject,
            versionsObservedAt = System.currentTimeMillis(), connectionDetail = "手机业务会话已建立 · ${status.array("runtimes").size} 个运行时") }
        updateRouteEvidence(transport)
    }
    private fun updateRouteEvidence(transport: PocketConnection) {
        if (client !== transport) return
        val routing = transport as? RoutingPocketConnection
        _state.update { it.copy(activeRouteKind = routing?.activeRoute?.kind, routePhase = routing?.routePhase ?: "session_established",
            routeFallback = routing?.directFailure, relayVersion = transport.relayVersion,
            p2pEnabled = preferences.getBoolean("p2pEnabled", true), publicStunEnabled = preferences.getBoolean("publicStunEnabled", true)) }
    }
    fun configureDirectConnection(enabled: Boolean, publicStun: Boolean) {
        preferences.edit().putBoolean("p2pEnabled", enabled).putBoolean("publicStunEnabled", publicStun).apply()
        _state.update { it.copy(p2pEnabled = enabled, publicStunEnabled = publicStun) }
        (client as? RoutingPocketConnection)?.apply { p2pEnabled = enabled; usePublicStun = publicStun }
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
    private fun action(lock: Mutex? = actionLock, statusKey: String = "action:${UUID.randomUUID()}", globalBusy: Boolean = true, block: suspend () -> Unit): Job = scope.launch {
        val owner = _state.value.selected?.key
        suspend fun execute() {
            if (globalBusy) activeActions.incrementAndGet()
            _state.update { it.copy(busy = activeActions.get() > 0, error = null,
                actionStatus = it.actionStatus + (statusKey to ActionStatus(loading = true))) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (uncertain: UnknownOutcomeException) {
                // The corresponding command/question retains its delivery state.
                // Never interrupt the whole conversation with an unknown-outcome popup.
                _state.update { it.copy(actionStatus = it.actionStatus + (statusKey to ActionStatus(error = "正在确认操作结果"))) }
                scope.launch { runCatching { reconcileCommands() } }
            }
            catch (e: Exception) { _state.update { it.copy(error = if (it.selected?.key == owner || statusKey in setOf("directory", "create", "preferences")) safeError(e) else it.error,
                actionStatus = it.actionStatus + (statusKey to ActionStatus(error = safeError(e)))) } }
            finally {
                if (globalBusy) activeActions.decrementAndGet()
                _state.update { it.copy(busy = activeActions.get() > 0,
                    actionStatus = it.actionStatus.let { statuses -> if (statuses[statusKey]?.error == null) statuses - statusKey else statuses }) }
            }
        }
        if (lock == null) execute() else lock.withLock { execute() }
    }
    private fun safeError(e: Exception): String = when (e) {
        is PocketOperationException -> e.failure.label
        is UnknownOutcomeException -> context.getString(R.string.unknown_error)
        is WireException -> PocketFailure.fromCode(e.code)?.label ?: when (e.code) {
            "offline", "disconnected" -> context.getString(R.string.offline_error)
            "routes_unavailable" -> "暂时无法连接电脑：请检查同一 Wi-Fi、电脑连接状态或已配置的公网路线"
            "network_changed" -> "网络已变化，正在重新选择连接路线"
            "identity_mismatch", "protocol_mismatch" -> "电脑身份或协议不匹配，请核对当前电脑的配对信息"
            "revoked", "device_revoked", "unauthorized", "authentication_failed" -> context.getString(R.string.revoked_error)
            "unsupported", "unsupported_method", "method_not_found", "-32601" -> context.getString(R.string.unsupported_error)
            "native_history_unavailable" -> "当前电脑 Codex 暂不支持此会话的历史读取；缓存与草稿已保留，可在电脑端查看。"
            else -> context.getString(R.string.secure_request_error)
        }
        is IllegalArgumentException -> context.getString(R.string.invalid_input_error)
        else -> context.getString(R.string.action_error)
    }
}

