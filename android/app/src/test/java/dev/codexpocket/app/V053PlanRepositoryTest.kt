package dev.codexpocket.app

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.stateLabel
import dev.codexpocket.wire.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real repository, reducer and Room, with the encrypted channel boundary replaced by a broker fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class V053PlanRepositoryTest {
    private lateinit var app: Application
    private lateinit var db: PocketDatabase
    private lateinit var repo: PocketRepository
    private lateinit var server: BrokerFixture
    private lateinit var storage: PairingStorage
    private val network = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val key = ThreadKey("computer", "store", "t")
    private val otherKey = ThreadKey("computer", "store", "other")

    @Before fun prepare() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        server = BrokerFixture()
        val saved = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "fixture-token", "00".repeat(32)), "phone", "fixture-secret", "computer", "Fixture")
        storage = object : PairingStorage {
            override fun load() = saved
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(app, { server.connection() }, storage, db, network)
        await { it.initialized }
        repo.setForeground(true)
        await { it.online && it.chats.any { c -> c.key == key } }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        try { await { it.nativeSettings.model.known && it.model == "model" } }
        catch (failure: TimeoutCancellationException) {
            val s=repo.state.value
            throw AssertionError("Fixture metadata timeout: online=${s.online}, selected=${s.selected?.key}, known=${s.nativeSettings.model.known}, model=${s.model}, settingsFresh=${s.settingsFresh}, load=${s.conversationLoadState}, error=${s.error}, calls=${server.calls.takeLast(16).map { it.method + ':' + it.nativeMethod }}",failure)
        }
        assertNull(repo.state.value.error)
    }

    @After fun release() = runBlocking<Unit> {
        val field = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        server.links.forEach { it.close() }
        db.close()
    }

    private suspend fun await(predicate: (PocketState) -> Boolean) = withTimeout(5_000) { repo.state.first(predicate) }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }
    private fun call(name: String): Job = PocketRepository::class.java.getMethod(name).invoke(repo) as Job
    @Suppress("UNCHECKED_CAST") private fun update(transform: (PocketState) -> PocketState) {
        val field = PocketRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (field.get(repo) as MutableStateFlow<PocketState>).update(transform)
    }
    private fun image(): LocalAttachment {
        val image = LocalAttachment(UUID.randomUUID().toString(), "fixture.png", "image/png")
        File(app.filesDir, "attachments/${image.id}").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))
        return image
    }

    private suspend fun restartOffline() {
        val field = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        repo = PocketRepository(app, { server.connection() }, storage, db, network)
        await { it.initialized }
    }

    private suspend fun completedProposal(): ProposedPlan {
        server.emit("turn/started", buildJsonObject { put("threadId", "t"); put("turn", buildJsonObject { put("id", "proposal-turn"); put("status", "inProgress") }) })
        server.emit("item/completed", buildJsonObject {
            put("threadId", "t"); put("turnId", "proposal-turn")
            put("item", buildJsonObject { put("id", "proposal"); put("type", "plan"); put("text", "# A verified proposal\n\nUpdate the requested UI.") })
        })
        server.emit("turn/completed", buildJsonObject { put("threadId", "t"); put("turn", buildJsonObject { put("id", "proposal-turn"); put("status", "completed") }) })
        return PlanPresentation.latest(await { PlanPresentation.latest(it.timeline) != null }.timeline)!!
    }

    @Test fun feedbackIsExplicitTextOnlyAndCancellationKeepsOrdinaryDraft() = runBlocking<Unit> {
        val plan = completedProposal()
        repo.editDraft("保留普通草稿")
        val attachment = image()
        update { it.copy(attachments = listOf(attachment), draft = it.draft?.copy(references = "[]")) }
        val original = repo.state.value.draft
        val oldMode = repo.state.value.mode
        assertTrue(repo.beginPlanFeedback(plan))
        repo.editPlanFeedback(plan, "只修改第二步")
        assertEquals(0, server.calls.count { it.nativeMethod == "turn/start" })
        repo.cancelPlanFeedback(plan)
        assertEquals(original, repo.state.value.draft)
        assertEquals(listOf(attachment), repo.state.value.attachments)
        assertEquals(oldMode, repo.state.value.mode)
        assertNull(repo.state.value.planFeedback)
        assertTrue(repo.beginPlanFeedback(plan))
        repo.editPlanFeedback(plan, "只修改第二步")
        repo.submitPlanFeedback(plan).join()
        val request = server.calls.single { it.nativeMethod == "turn/start" }.params.obj("params")
        assertEquals(1, request.array("input").size)
        assertEquals("只修改第二步", request.array("input").single().asObject().string("text"))
        assertEquals("plan", request.obj("collaborationMode").string("mode"))
        assertEquals(original, repo.state.value.draft)
        assertEquals(listOf(attachment), repo.state.value.attachments)
        assertTrue(repo.attachmentFile(attachment).exists())
        assertNull(repo.state.value.planFeedback)
    }

    @Test fun dismissedRevisionAndFeedbackSurviveSelectionAndRestart() = runBlocking<Unit> {
        val plan = completedProposal()
        repo.dismissPlan(plan)
        assertTrue(plan.revision in repo.state.value.dismissedPlans)
        assertEquals(0, server.calls.count { it.nativeMethod == "turn/start" })
        assertTrue(repo.reopenPlan(plan))
        assertFalse(plan.revision in repo.state.value.dismissedPlans)
        assertTrue(repo.beginPlanFeedback(plan))
        repo.editPlanFeedback(plan, "保留修改草稿")
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        assertNull(repo.state.value.planFeedback)
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertEquals("保留修改草稿", repo.state.value.planFeedback?.text)
        restartOffline()
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertEquals("保留修改草稿", repo.state.value.planFeedback?.text)
    }

    @Test fun unknownFeedbackCannotRepeatAndReconciliationKeepsOrdinaryDraft() = runBlocking<Unit> {
        val plan = completedProposal()
        repo.editDraft("keep")
        assertTrue(repo.beginPlanFeedback(plan))
        repo.editPlanFeedback(plan, "feedback")
        server.intercept = { method, params, id ->
            if (method == "codex/request" && params.string("method") == "turn/start") {
                server.statuses[id!!] = "unknown"
                throw UnknownOutcomeException(id)
            }; null
        }
        repo.submitPlanFeedback(plan).join()
        repo.submitPlanFeedback(plan).join()
        repo.implementPlan(plan).join()
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
        assertEquals("feedback", repo.state.value.planFeedback?.text)
        assertEquals("keep", repo.state.value.draft?.text)
        val command = db.dao().uncertain("computer").single { it.method == "turn/start" }
        server.statuses[command.id] = "accepted"
        repo.checkCommands().join()
        assertNull(repo.state.value.planFeedback)
        assertEquals("plan", repo.state.value.mode)
        assertEquals("keep", repo.state.value.draft?.text)
    }

    @Test fun dismissalIsLocalPersistentAndOnlyMatchesItsRevision() = runBlocking<Unit> {
        val plan = completedProposal()
        repo.dismissPlan(plan)
        restartOffline()
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertTrue(plan.revision in repo.state.value.dismissedPlans)
        assertFalse(plan.copy(content = "revised proposal").revision in repo.state.value.dismissedPlans)
        assertEquals(0, server.calls.count { it.nativeMethod == "turn/start" })
    }

    @Test fun cancellingFeedbackDoesNotOverwriteALaterModeChoice() = runBlocking<Unit> {
        val plan = completedProposal()
        assertTrue(repo.beginPlanFeedback(plan))
        repo.chooseMode(null)
        repo.cancelPlanFeedback(plan)
        assertNull(repo.state.value.mode)
        assertTrue(NativeSettingsField.Mode in repo.state.value.pendingSettings)
    }

    @Test fun staleFeedbackAndRapidSecondClickNeverDispatch() = runBlocking<Unit> {
        val plan = completedProposal()
        assertTrue(repo.beginPlanFeedback(plan))
        repo.editPlanFeedback(plan, "feedback")
        repo.submitPlanFeedback(plan.copy(content = "new revision")).join()
        assertEquals(0, server.calls.count { it.nativeMethod == "turn/start" })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") { entered.complete(Unit); release.await() }; null
        }
        val first = repo.submitPlanFeedback(plan)
        withTimeout(5000) { entered.await() }
        try {
            repo.submitPlanFeedback(plan).join()
            repo.implementPlan(plan).join()
            repo.editPlanFeedback(plan, "changed during dispatch")
            assertEquals("feedback", repo.state.value.planFeedback?.text)
            assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
        } finally { release.complete(Unit); first.join() }
    }

    @Test fun fastPendingDisablesDuplicatesAndFailureRetainsState() = runBlocking<Unit> {
        await { !it.busy && !it.sendBusy }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/settings/update") {
                entered.complete(Unit); release.await(); throw WireException("rejected", "Fixture rejection")
            }; null
        }
        val first = repo.setFastMode(true)
        withTimeout(5000) { entered.await() }
        try {
            assertTrue(key in repo.state.value.fastPending)
            repo.setFastMode(true).join()
            assertEquals(1, server.calls.count { it.nativeMethod == "thread/settings/update" })
        } finally { release.complete(Unit); first.join() }
        assertFalse(key in repo.state.value.fastPending)
        assertFalse(repo.state.value.advanced.fastMode)
    }

    private data class Call(val method: String, val params: JsonObject, val commandId: String?) { val nativeMethod get() = params.string("method") }
    private class BrokerFixture {
        val calls = CopyOnWriteArrayList<Call>()
        val links = CopyOnWriteArrayList<Link>()
        val rows = CopyOnWriteArrayList<JsonObject>().apply { add(row("t")); add(row("other")) }
        val pending = CopyOnWriteArrayList<JsonObject>()
        val statuses = ConcurrentHashMap<String, String>()
        val attempts = AtomicInteger()
        var intercept: suspend (String, JsonObject, String?) -> JsonElement? = { _, _, _ -> null }
        @Volatile var owner = "runtime"
        @Volatile var conflict = false
        @Volatile var failConnections = false
        @Volatile var queuePrefix = "q"
        @Volatile var pagedHistory = false
        @Volatile var pageTurns: List<JsonObject> = emptyList()
        private val sequence = AtomicInteger()
        @Volatile private var epoch = "epoch"
        fun moveEpoch(value: String, baseline: Int) { epoch = value; sequence.set(baseline) }
        private val capabilities = listOf("thread/start", "thread/read", "thread/resume", "thread/turns/list", "thread/queue/list", "thread/queue/add", "thread/queue/update", "thread/queue/delete", "thread/queue/reorder", "thread/name/set", "thread/archive", "thread/settings/update", "turn/start", "turn/steer", "turn/interrupt", "model/list")
        fun row(id: String) = buildJsonObject { put("id", id); put("name", id); put("preview", "fixture"); put("cwd", "D:/fixture"); put("updatedAt", 1); put("status", "idle") }
        fun thread(id: String) = buildJsonObject { put("id", id); put("name", id); put("cwd", "D:/fixture"); put("status", buildJsonObject { put("type", "idle") }); put("turns", JsonArray(emptyList())) }
        fun cursor() = buildJsonObject { put("epoch", epoch); put("sequence", sequence.get()) }
        fun historySnapshot(thread: JsonObject) = buildJsonObject { put("thread", thread); put("pocketCursor", cursor()) }
        fun turnsPage(params: JsonObject): JsonObject {
            val allowed = setOf("threadId", "cursor", "limit", "sortDirection", "itemsView")
            if (params.keys.any { it !in allowed }) throw WireException("invalid_params", "Broker allowlist")
            val view = params.string("itemsView", "summary")
            if (view !in setOf("notLoaded", "summary", "full")) throw WireException("invalid_params", "Native itemsView enum")
            val turns = if (params["cursor"] != null) emptyList() else pageTurns.map { turn ->
                val items = when (view) {
                    "full" -> turn.array("items")
                    "notLoaded" -> buildJsonArray {}
                    else -> JsonArray(turn.array("items").filter { it.asObject().string("type") in setOf("userMessage", "agentMessage") })
                }
                JsonObject(turn + mapOf("items" to items, "itemsView" to JsonPrimitive(view)))
            }
            return buildJsonObject {
                put("data", JsonArray(turns)); put("nextCursor", if (params["cursor"] == null) JsonPrimitive("page-2") else JsonNull)
                // Existing tests retain the legacy no-cursor fallback unless explicitly enabled.
                if (pagedHistory) put("pocketCursor", cursor())
            }
        }
        fun runtimes() = buildJsonArray { add(buildJsonObject { put("id", owner); put("source", "desktop"); put("version", "fixture"); put("configStoreId", "store"); put("online", true); put("loadedThreadIds", JsonArray((rows.map { it.string("id") } + "new").map(::JsonPrimitive))); put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
            put("capabilityFields", buildJsonObject { put("thread/settings/update", buildJsonArray { add("threadId"); add("serviceTier") }) })
        }) }
        fun approval(requestId: JsonElement = JsonPrimitive("approval-1")) = buildJsonObject { put("id", requestId); put("method", "item/commandExecution/requestApproval"); put("params", buildJsonObject { put("threadId", "other"); put("command", "fixture"); put("availableDecisions", buildJsonArray { add("accept"); add("decline") }) }) }
        fun syncSnapshot() = buildJsonObject { put("events", JsonArray(emptyList())); put("snapshotRequired", JsonArray(emptyList())); put("cursors", buildJsonObject { put(owner, buildJsonObject { put("epoch", epoch); put("sequence", sequence.get()) }) }); put("pendingRequests", JsonArray(pending.map { buildJsonObject { put("runtimeId", owner); put("event", it) } })) }
        fun connection() = Link().also { links += it }
        suspend fun notify(method: String, params: JsonObject) { links.last().notes.send(buildJsonObject { put("method", method); put("params", params) }) }
        suspend fun emit(method: String, params: JsonObject) = emitRaw(buildJsonObject { put("method", method); put("params", params) })
        suspend fun emitRaw(event: JsonObject) = notify("codex/event", buildJsonObject { put("runtimeId", owner); put("cursor", buildJsonObject { put("epoch", epoch); put("sequence", sequence.incrementAndGet()) }); put("event", event) })
        inner class Link : PocketConnection {
            override val state = MutableStateFlow(LinkState.OFFLINE)
            val notes = Channel<JsonObject>(Channel.UNLIMITED)
            override val notifications = notes.receiveAsFlow()
            override suspend fun connect(config: ConnectionConfig) { attempts.incrementAndGet(); if (failConnections) throw WireException("offline", "Fixture offline"); state.value = LinkState.ONLINE }
            override fun close() { state.value = LinkState.OFFLINE; notes.close(WireException("disconnected", "Fixture disconnected")) }
            override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
                calls += Call(method, params, commandId)
                intercept(method, params, commandId)?.let { return it }
                return when (method) {
                    "hello" -> buildJsonObject { put("protocol", 1); put("computerId", "computer"); put("computerName", "Fixture"); put("runtimes", runtimes()); put("capabilities", JsonArray(capabilities.map(::JsonPrimitive))) }
                    "runtimes/list" -> buildJsonObject { put("data", runtimes()) }
                    "threads/list" -> buildJsonObject { put("data", if (params.flag("archived")) JsonArray(emptyList()) else JsonArray(rows.map { JsonObject(it + mapOf("runtimeId" to JsonPrimitive(owner), "source" to JsonPrimitive("desktop"), "configStoreId" to JsonPrimitive("store"), "conflict" to JsonPrimitive(conflict))) })); put("nextCursor", JsonNull) }
                    "sync" -> syncSnapshot()
                    "commands/status" -> buildJsonObject { put("status", statuses[params.string("commandId")] ?: "accepted") }
                    "approvals/respond" -> { statuses[commandId!!] = "submitted"; buildJsonObject { put("status", "submitted") } }
                    "attachment/upload" -> buildJsonObject { put("attachmentId", "uploaded") }
                    "thread/settings/read" -> buildJsonObject {
                        put("runtimeId", owner); put("threadId", params.string("threadId")); put("known", true)
                        put("settings", buildJsonObject { put("model", "model"); put("effort", JsonNull) })
                    }
                    "codex/request" -> when (params.string("method")) {
                        "thread/queue/list" -> {
                            val p = params.obj("params"); val id = queuePrefix + if (p["cursor"] == null) "1" else "2"
                            buildJsonObject { put("data", buildJsonArray { add(buildJsonObject { put("id", id); put("clientUserMessageId", "client-$id"); put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", p.string("threadId")) }) }) }) }); put("nextCursor", if (p["cursor"] == null) JsonPrimitive("queue-2") else JsonNull) }
                        }
                        "thread/read" -> buildJsonObject { put("thread", thread(params.obj("params").string("threadId"))); put("pocketCursor", buildJsonObject { put("epoch", epoch); put("sequence", sequence.get()) }) }
                        "model/list" -> buildJsonObject {
                            put("data", buildJsonArray {
                                add(buildJsonObject {
                                    put("id", "model"); put("model", "model"); put("displayName", "Model"); put("isDefault", true)
                                    put("inputModalities", buildJsonArray { add("text"); add("image") })
                                    put("serviceTiers", buildJsonArray { add("fast") })
                                })
                            })
                            put("nextCursor", JsonNull)
                        }
                        "thread/start" -> buildJsonObject { put("thread", thread("new")) }
                        "thread/turns/list" -> turnsPage(params.obj("params"))
                        "turn/start" -> buildJsonObject { put("turn", buildJsonObject { put("id", "sent"); put("status", "inProgress") }) }
                        else -> buildJsonObject {}
                    }
                    else -> buildJsonObject {}
                }
            }
        }
    }
}
