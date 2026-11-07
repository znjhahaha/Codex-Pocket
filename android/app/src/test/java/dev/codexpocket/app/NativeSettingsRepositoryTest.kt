package dev.codexpocket.app

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No inference or mutation: all native work is an in-memory read/event fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeSettingsRepositoryTest {
    private lateinit var app: Application
    private lateinit var db: PocketDatabase
    private lateinit var repo: PocketRepository
    private lateinit var server: SettingsFixture
    private lateinit var storage: PairingStorage
    private val key = ThreadKey("settings-computer", "store", "t")
    private val other = key.copy(nativeId = "other")

    @Before fun prepare() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        server = SettingsFixture()
        val saved = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "token", "00".repeat(32)), "phone", "secret", key.computerId, "Fixture")
        storage = object : PairingStorage {
            override fun load() = saved
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(app, { server.connection() }, storage, db, emptyFlow())
        await { it.initialized }
        repo.setForeground(true)
        await { it.online && it.chats.any { chat -> chat.key == key } }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        await { server.calls.any { call -> call.first == "thread/settings/read" } && it.modelCatalogState == ModelCatalogState.Ready }
    }

    @After fun release() = runBlocking<Unit> {
        stopRepository()
        db.close()
        assertFalse("Settings sync must not send mutations or inference", server.calls.any { (method, params) ->
            method == "codex/request" && params.string("method") !in setOf("thread/read", "model/list", "collaborationMode/list")
        })
    }

    private suspend fun stopRepository() {
        val field = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
    }
    private suspend fun await(predicate: (PocketState) -> Boolean) = withTimeout(5_000) { repo.state.first(predicate) }
    private fun json(value: String) = pocketJson.parseToJsonElement(value).jsonObject
    private suspend fun consumed() = withTimeout(5_000) {
        while (db.dao().cursors(key.computerId).none { it.sequence == server.sequence.get() }) delay(10)
    }

    @Test fun largeCachedToolRowsKeepUnicodeTextRawIdentityAndOrder() = runBlocking<Unit> {
        val text = "中🙂".repeat(200_000)
        val raw = buildJsonObject { put("id", "large"); put("type", "commandExecution"); put("aggregatedOutput", text) }
        val item = ItemEntity(key, "large", "turn", "large", "commandExecution", text, "completed", raw, true, 7)
        db.dao().putItems(listOf(item))
        val restored = db.withTransaction { db.dao().chunkedItems(key) }
        assertEquals(listOf(item), restored)
    }

    @Test fun unreadableCachedQuestionsNeverHidePairedComputerOrDirectory() = runBlocking<Unit> {
        stopRepository()
        app.getSharedPreferences("settings", 0).edit().putString("questions:${key.encoded}", "legacy-incompatible-value").commit()
        repo = PocketRepository(app, { server.connection() }, storage, db, emptyFlow())
        await { it.initialized }
        assertTrue(repo.state.value.paired)
        assertEquals(key.computerId, repo.state.value.computerId)
        assertTrue(repo.state.value.chats.any { it.key == key })
        repo.setForeground(true)
        await { it.online }
        repo.refresh().join()
        assertTrue(repo.state.value.chats.any { it.key == other })
    }

    @Test fun unknownSettingsNeverSelectCatalogDefaultOrBorrowItsName() {
        assertNull(repo.state.value.model)
        assertNull(repo.state.value.currentModel)
        assertEquals("模型未同步", repo.state.value.modelSelectionLabel)
        assertEquals("", repo.state.value.effortSelectionLabel)
        assertFalse(repo.state.value.nativeSettings.model.known)
    }

    @Test fun desktopEventsImmediatelyChangeModelHighToXhighAndPlanToDefault() = runBlocking<Unit> {
        server.event("t", json("""{"model":"desktop-a","effort":"high","serviceTier":"fast",
            "collaborationMode":{"mode":"plan","settings":{"model":"desktop-a","reasoning_effort":"high"}}}"""))
        await { it.model == "desktop-a" && it.effort == "high" && it.mode == "plan" && it.advanced.fastMode }
        server.event("t", json("""{"model":"desktop-b","effort":"xhigh","serviceTier":null,
            "collaborationMode":{"mode":"default","settings":{"model":"desktop-b","reasoning_effort":"xhigh"}}}"""))
        await { it.model == "desktop-b" && it.effort == "xhigh" && it.mode == "default" && !it.advanced.fastMode }
        assertTrue(repo.state.value.settingsFresh)
    }

    @Test fun partialNotificationRetainsEffortButExplicitNullSelectsDefault() = runBlocking<Unit> {
        server.event("t", json("""{"model":"desktop-a","effort":"high","serviceTier":"fast"}"""))
        await { it.effort == "high" && it.advanced.fastMode }
        server.event("t", json("""{"model":"desktop-b"}""")); consumed()
        assertEquals("high", repo.state.value.effort)
        assertTrue(repo.state.value.advanced.fastMode)
        server.event("t", json("""{"effort":null,"serviceTier":null}"""))
        await { it.nativeSettings.effort.known && it.effort == null && !it.advanced.fastMode }
        assertEquals("", repo.state.value.effortSelectionLabel)
    }

    @Test fun lateHelperReadCannotOverwriteDesktopEvent() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.read = { id -> entered.complete(Unit); release.await(); server.settings(id, json("""{"model":"old","effort":"high"}""")) }
        val read = repo.refreshNativeSettings(); entered.await()
        try {
            server.event("t", json("""{"model":"new","effort":"xhigh"}"""))
            await { it.model == "new" && it.effort == "xhigh" }
        } finally { release.complete(Unit); read.join() }
        assertEquals("new", repo.state.value.model)
        assertEquals("xhigh", repo.state.value.effort)
    }

    @Test fun lateHelperAndHistoryCannotOverwritePhoneEditMadeAfterLiveEvent() = runBlocking<Unit> {
        val chosen = repo.state.value.models.single()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.history = { id -> entered.complete(Unit); release.await(); server.snapshot(id) }
        val history = repo.reloadConversation(); entered.await()
        try {
            server.event("t", json("""{"model":"desktop","effort":"high","serviceTier":"fast"}"""))
            await { it.model == "desktop" && it.advanced.fastMode }; consumed()
            repo.chooseModel(chosen, null)
            repo.chooseMode(null)
        } finally { release.complete(Unit); history.join() }
        assertEquals("catalog-default", repo.state.value.model)
        assertEquals(NativeSetting(true, null), repo.state.value.nativeSettings.effort)
        assertTrue(repo.state.value.advanced.fastMode)
        server.read = { id -> server.settings(id, json("""{"model":"desktop","effort":"high"}""")) }
        repo.refreshNativeSettings().join()
        assertEquals("catalog-default", repo.state.value.model)
        assertNull(repo.state.value.effort)
        // A helper read can also publish the old snapshot as a journal settings event.
        server.event("t", json("""{"model":"desktop","effort":"high"}""")); consumed()
        assertEquals("catalog-default", repo.state.value.model)
        assertNull(repo.state.value.effort)
    }

    @Test fun editsDuringSettingsReadSurviveAndSelectionFencesRejectAnotherChat() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.read = { id ->
            if (id == "t") { entered.complete(Unit); release.await() }
            server.settings(id, json("""{"model":"desktop","effort":"high"}"""))
        }
        val oldRead = repo.refreshNativeSettings(); entered.await()
        try {
            repo.chooseModel(repo.state.value.models.single(), "xhigh")
            repo.select(repo.state.value.chats.first { it.key == other }).join()
            await { it.model == "desktop" }
        } finally { release.complete(Unit); oldRead.join() }
        server.read = { id -> server.unknown(id) }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertEquals("catalog-default", repo.state.value.model)
        assertEquals("xhigh", repo.state.value.effort)
    }

    @Test fun perChatCachePersistsEditsAndNeverCopiesOtherChatSettings() = runBlocking<Unit> {
        repo.chooseModel(repo.state.value.models.single(), null)
        repo.chooseMode("plan")
        server.event("other", json("""{"model":"other-model","effort":"high"}""")); consumed()
        repo.select(repo.state.value.chats.first { it.key == other }).join()
        assertEquals("other-model", repo.state.value.model)
        stopRepository()
        repo = PocketRepository(app, { server.connection() }, storage, db, emptyFlow())
        await { it.initialized }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertEquals("catalog-default", repo.state.value.model)
        assertEquals("plan", repo.state.value.mode)
        assertEquals("", repo.state.value.effortSelectionLabel)
        assertFalse(repo.state.value.settingsFresh)
        assertTrue(NativeSettingsField.Model in repo.state.value.pendingSettings)
    }

    @Test fun helperUsesReadOnlyTopLevelRpcWithExactIdentity() = runBlocking<Unit> {
        server.read = { id -> server.settings(id, json("""{"model":"desktop","effort":null,
            "collaborationMode":{"mode":"plan","settings":{"model":"desktop","reasoning_effort":null}}}""")) }
        repo.refreshNativeSettings().join()
        assertEquals("desktop", repo.state.value.model)
        assertEquals("plan", repo.state.value.mode)
        val request = server.calls.last { it.first == "thread/settings/read" }.second
        assertEquals(setOf("runtimeId", "threadId"), request.keys)
        assertEquals("r", request.string("runtimeId"))
        assertEquals("t", request.string("threadId"))
    }

    @Test fun reconnectReadsKnownSettingsAndRejectsOldConnectionResponse() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val reads = AtomicInteger()
        server.read = { id ->
            if (reads.incrementAndGet() == 1) {
                entered.complete(Unit); release.await()
                server.settings(id, json("""{"model":"old-link","effort":"high"}"""))
            } else server.settings(id, json("""{"model":"new-link","effort":null,
                "collaborationMode":{"mode":"plan","settings":{"model":"new-link","reasoning_effort":null}}}"""))
        }
        val old = repo.refreshNativeSettings(); entered.await()
        try {
            repo.setForeground(false); await { !it.online }
            repo.setForeground(true)
            await { it.online && it.settingsFresh && it.model == "new-link" && it.mode == "plan" }
        } finally { release.complete(Unit); old.join() }
        assertEquals("new-link", repo.state.value.model)
        assertNull(repo.state.value.effort)
        assertEquals("", repo.state.value.effortSelectionLabel)
    }

    @Test fun contextLiveEventWinsLateReadAndSurvivesHistoryAndSelection() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.contextRead = { id -> entered.complete(Unit); release.await(); json("""{"runtimeId":"r","threadId":"$id","status":"ok","usage":{"last":{"totalTokens":10},"modelContextWindow":100}}""") }
        val pending = repo.refreshContext(); entered.await()
        server.usage("t", json("""{"last":{"inputTokens":50,"outputTokens":5,"totalTokens":55},"modelContextWindow":100,"total":{"totalTokens":9000}}""")); consumed()
        release.complete(Unit); pending.join()
        assertEquals(55L, ContextUsage.from(repo.state.value.advanced.usage).used)
        server.contextRead = { id -> json("""{"runtimeId":"r","threadId":"$id","status":"empty"}""") }
        repo.reloadConversation().join()
        assertEquals(55L, ContextUsage.from(repo.state.value.advanced.usage).used)
        repo.select(repo.state.value.chats.first { it.key == other }).join()
        assertNull(repo.state.value.advanced.usage)
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertEquals(55L, ContextUsage.from(repo.state.value.advanced.usage).used)
    }

    @Test fun childReaderKeepsParentDraftAndRemovesProvenChildFromHome() = runBlocking<Unit> {
        val childKey = key.copy(nativeId = "child")
        db.dao().putChat(ChatEntity(childKey, "r", "desktop", "Child", "", "", 0, "idle", false))
        server.history = { id -> if (id == "t") json("""{"pocketCursor":{"epoch":"settings-epoch","sequence":0},"thread":{"id":"t","turns":[{"id":"turn","status":"completed","items":[{"id":"spawn","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","receiverThreadIds":["child"]}]}]}}""") else json("""{"pocketCursor":{"epoch":"settings-epoch","sequence":0},"thread":{"id":"child","name":"Child","turns":[{"id":"childturn","status":"completed","items":[{"id":"answer","type":"agentMessage","text":"Child result"}]}]}}""") }
        repo.reloadConversation().join()
        await { it.chats.none { c -> c.key == childKey } }
        repo.editDraft("主任务草稿🙂")
        repo.showAgentReader()
        repo.openAgentReader("child").join()
        assertEquals(key, repo.state.value.selected?.key)
        assertEquals("主任务草稿🙂", repo.state.value.draft?.text)
        assertEquals(childKey, repo.state.value.agentReader?.child?.key)
        assertEquals("Child result", repo.state.value.agentReader?.timeline?.items?.values?.single()?.text)
        repo.agentReaderFullscreen(true)
        assertTrue(repo.state.value.agentReader!!.fullScreen)
        repo.closeAgentReader()
        assertNull(repo.state.value.agentReader)
        assertEquals(key, repo.state.value.selected?.key)
        assertEquals("主任务草稿🙂", repo.state.value.draft?.text)
    }

    private class SettingsFixture {
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val links = CopyOnWriteArrayList<Link>()
        val sequence = AtomicLong()
        var read: suspend (String) -> JsonObject = { unknown(it) }
        var history: suspend (String) -> JsonObject = { snapshot(it) }
        var contextRead: suspend (String) -> JsonObject = { id -> buildJsonObject { put("runtimeId", "r"); put("threadId", id); put("status", "empty") } }
        fun unknown(id: String) = buildJsonObject { put("runtimeId", "r"); put("threadId", id); put("settings", buildJsonObject {}); put("known", false) }
        fun settings(id: String, value: JsonObject) = JsonObject(unknown(id) + mapOf("settings" to value, "known" to JsonPrimitive(true)))
        fun cursor() = buildJsonObject { put("epoch", "settings-epoch"); put("sequence", sequence.get()) }
        fun snapshot(id: String) = buildJsonObject {
            put("pocketCursor", cursor()); put("thread", buildJsonObject {
                put("id", id); put("status", buildJsonObject { put("type", "idle") }); put("turns", buildJsonArray {})
            })
        }
        fun runtimes() = buildJsonArray { add(buildJsonObject {
            put("id", "r"); put("source", "desktop"); put("configStoreId", "store"); put("online", true)
            put("loadedThreadIds", buildJsonArray { add("t"); add("other") })
            put("capabilities", buildJsonArray { add("thread/read"); add("model/list"); add("collaborationMode/list") })
        }) }
        fun connection() = Link().also { links += it }
        suspend fun event(id: String, settings: JsonObject) {
            val cursor = buildJsonObject { put("epoch", "settings-epoch"); put("sequence", sequence.incrementAndGet()) }
            links.last().notes.send(buildJsonObject {
                put("method", "codex/event"); put("params", buildJsonObject {
                    put("runtimeId", "r"); put("cursor", cursor); put("event", buildJsonObject {
                        put("method", "thread/settings/updated"); put("params", buildJsonObject { put("threadId", id); put("threadSettings", settings) })
                    })
                })
            })
        }
        suspend fun usage(id: String, usage: JsonObject) {
            val cursor = buildJsonObject { put("epoch", "settings-epoch"); put("sequence", sequence.incrementAndGet()) }
            links.last().notes.send(buildJsonObject { put("method", "codex/event"); put("params", buildJsonObject {
                put("runtimeId", "r"); put("cursor", cursor); put("event", buildJsonObject {
                    put("method", "thread/tokenUsage/updated"); put("params", buildJsonObject { put("threadId", id); put("tokenUsage", usage) })
                })
            }) })
        }
        inner class Link : PocketConnection {
            override val state = MutableStateFlow(LinkState.OFFLINE)
            val notes = Channel<JsonObject>(Channel.UNLIMITED)
            override val notifications = notes.receiveAsFlow()
            override suspend fun connect(config: ConnectionConfig) { state.value = LinkState.ONLINE }
            override fun close() { state.value = LinkState.OFFLINE; notes.close() }
            override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
                check(commandId == null) { "Read-only fixture forbids commands" }
                calls += method to params
                return when (method) {
                    "hello" -> buildJsonObject { put("protocol", 1); put("computerId", "settings-computer"); put("runtimes", runtimes()) }
                    "runtimes/list" -> buildJsonObject { put("data", runtimes()) }
                    "threads/list" -> buildJsonObject { put("data", buildJsonArray { if (!params.flag("archived")) listOf("t", "other").forEach { id -> add(buildJsonObject {
                        put("id", id); put("runtimeId", "r"); put("configStoreId", "store"); put("source", "desktop"); put("name", id)
                    }) } }); put("nextCursor", JsonNull) }
                    "sync" -> buildJsonObject { put("events", buildJsonArray {}); put("cursors", buildJsonObject { put("r", cursor()) }); put("pendingRequests", buildJsonArray {}) }
                    "thread/settings/read" -> read(params.string("threadId"))
                    "thread/context/read" -> contextRead(params.string("threadId"))
                    "codex/request" -> when (params.string("method")) {
                        "thread/read" -> history(params.obj("params").string("threadId"))
                        "model/list" -> buildJsonObject { put("data", buildJsonArray { add(buildJsonObject {
                            put("id", "catalog"); put("model", "catalog-default"); put("displayName", "Catalog default"); put("isDefault", true)
                            put("defaultReasoningEffort", "high"); put("supportedReasoningEfforts", buildJsonArray { listOf("high", "xhigh").forEach { effort -> add(buildJsonObject { put("reasoningEffort", effort) }) } })
                        }) }); put("nextCursor", JsonNull) }
                        "collaborationMode/list" -> buildJsonObject { put("data", buildJsonArray { listOf("plan", "default").forEach { mode -> add(buildJsonObject { put("name", mode); put("mode", mode) }) } }) }
                        else -> error("Forbidden native request: ${params.string("method")}")
                    }
                    else -> buildJsonObject {}
                }
            }
        }
    }
}
