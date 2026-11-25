package dev.codexpocket.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Gates prove request ordering; elapsed time is only a bounded wait for local coroutines. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsReconnectSchedulingTest {
    private lateinit var app: Application
    private lateinit var db: PocketDatabase
    private lateinit var repo: PocketRepository
    private lateinit var server: SchedulingFixture
    private val key = ThreadKey("scheduling-computer", "scheduling-store", "selected")
    private val chat = ChatEntity(key, "scheduling-runtime", "desktop", "Selected", "", "", 0, "idle", false)

    @Before fun prepare() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("settings", 0).edit().remove("selected")
            .remove("nativeSettings:${key.encoded}").commit()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        server = SchedulingFixture()
        val pair = PairedComputer(ConnectionConfig("wss://fixture.invalid", "fixture", "fixture", "00".repeat(32)),
            "scheduling-phone", "fixture", key.computerId, "Fixture")
        val storage = object : PairingStorage {
            override fun load() = pair
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(app, { server.connection() }, storage, db, emptyFlow(), backgroundRetentionMillis = 200)
        awaitState { it.initialized }
    }

    @After fun close() = runBlocking<Unit> {
        server.gates.values.forEach { it.release.complete(Unit) }
        server.connectGate?.release?.complete(Unit)
        val field = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        server.links.forEach { it.close() }
        db.close()
        app.getSharedPreferences("settings", 0).edit().remove("selected")
            .remove("nativeSettings:${key.encoded}").commit()
    }

    private suspend fun awaitState(predicate: (PocketState) -> Boolean): PocketState =
        withTimeout(5_000) { repo.state.first(predicate) }

    private suspend fun connect() {
        repo.setForeground(true)
        awaitState { it.online && it.chats.any { row -> row.key == key } }
    }

    private suspend fun selectReady() {
        connect()
        withTimeout(5_000) { repo.select(chat).join() }
        awaitState { it.settingsFresh && it.permissions.status == "ready" && it.modelCatalogState == ModelCatalogState.Ready }
    }

    private suspend fun eventually(predicate: () -> Boolean): Boolean = withTimeoutOrNull(1_500) {
        while (!predicate()) delay(10)
        true
    } == true

    @Test fun slowAccessDoesNotQueueIndependentSettingsPermissionsAndCatalog() = runBlocking<Unit> {
        connect()
        val gate = Gate().also { server.gates["thread/access/read"] = it }
        val selection = repo.select(chat)
        try {
            withTimeout(5_000) { gate.entered.await() }
            assertTrue("Metadata reads must finish while ownership proof is still blocked", eventually {
                val state = repo.state.value
                state.settingsFresh && state.permissions.status == "ready" && state.modelCatalogState == ModelCatalogState.Ready
            })
            assertFalse("Reading metadata must not invent permission to send", key in repo.state.value.accessFresh)
            assertTrue(server.writes.isEmpty())
        } finally { gate.release.complete(Unit); withTimeout(5_000) { selection.join() } }
    }

    @Test fun slowHistoryDoesNotQueueModelCatalog() = runBlocking<Unit> {
        connect()
        val gate = Gate().also { server.gates["thread/read"] = it }
        val selection = repo.select(chat)
        try {
            withTimeout(5_000) { gate.entered.await() }
            assertTrue("A blocked history page must not postpone the model menu", eventually {
                repo.state.value.modelCatalogState == ModelCatalogState.Ready
            })
            assertEquals(ConversationLoadState.Loading, repo.state.value.conversationLoadState)
            assertEquals(listOf("catalog-model"), repo.state.value.models.map { it.model })
        } finally { gate.release.complete(Unit); withTimeout(5_000) { selection.join() } }
    }

    @Test fun foregroundMetadataDoesNotWaitForRuntimeInventory() = runBlocking<Unit> {
        selectReady()
        repo.setWatch(true)
        repo.setForeground(false)
        val settingsReads = server.count("thread/settings/read")
        val permissionReads = server.count("thread/permissions/read")
        val gate = Gate().also { server.gates["runtimes/list"] = it }
        repo.setForeground(true)
        try {
            withTimeout(5_000) { gate.entered.await() }
            assertTrue("The captured current owner can be read while inventory is pending", eventually {
                server.count("thread/settings/read") > settingsReads && server.count("thread/permissions/read") > permissionReads
            })
            assertTrue(repo.state.value.online)
            assertEquals(1, server.links.size)
        } finally { gate.release.complete(Unit) }
    }

    @Test fun repeatedForegroundSignalDoesNotCancelAnInProgressHandshake() = runBlocking<Unit> {
        val gate = Gate().also { server.connectGate = it }
        repo.setForeground(true)
        withTimeout(5_000) { gate.entered.await() }
        val first = server.links.single()
        try {
            repo.setForeground(true)
            assertFalse("Lifecycle wakeup must join the active authenticated connection attempt", first.closed)
            gate.release.complete(Unit)
            awaitState { it.online }
            assertEquals(1, server.links.size)
        } finally { gate.release.complete(Unit) }
    }

    @Test fun briefBackgroundReturnReusesLinkAndDoesNotReplayUnknownCommand() = runBlocking<Unit> {
        selectReady()
        repo.editDraft("Keep this unsent draft")
        db.dao().putCommand(CommandEntity("uncertain-scheduling-command", key.computerId, key,
            "turn/start", "unknown", 1L))
        awaitState { it.uncertainSend }
        val original = server.links.single()
        val epoch = repo.state.value.mediaConnectionEpoch
        repo.setForeground(false)
        delay(50)
        repo.setForeground(true)
        awaitState { it.online }
        assertFalse("A brief app switch must not close a healthy authenticated link", original.closed)
        assertSame(original, server.links.single())
        assertEquals(epoch, repo.state.value.mediaConnectionEpoch)
        assertEquals("Keep this unsent draft", repo.state.value.draft?.text)
        assertEquals("unknown", db.dao().command("uncertain-scheduling-command")?.status)
        assertTrue("Foreground recovery is read-only for unknown user commands", server.writes.isEmpty())
    }

    @Test fun foregroundRetriesUnavailableCatalogWithoutReplacingHealthyLink() = runBlocking<Unit> {
        selectReady()
        server.failCatalog = true
        repo.refreshModels().join()
        assertEquals(ModelCatalogState.Failed, repo.state.value.modelCatalogState)
        server.failCatalog = false
        repo.setWatch(true)
        repo.setForeground(false)
        repo.setForeground(true)
        assertTrue("Returning to the conversation must recover its failed model menu", eventually {
            repo.state.value.modelCatalogState == ModelCatalogState.Ready
        })
        assertEquals(1, server.links.size)
    }

    @Test fun cachedSettingsRemainUnverifiedUntilNativeReadReturns() = runBlocking<Unit> {
        connect()
        val cached = NativeSettingsRecord(settings = NativeSettings(model = NativeSetting(true, "cached-model")))
        app.getSharedPreferences("settings", 0).edit()
            .putString("nativeSettings:${key.encoded}", cached.persisted(chat.runtimeId).toString()).commit()
        val gate = Gate().also { server.gates["thread/settings/read"] = it }
        val selection = repo.select(chat)
        try {
            withTimeout(5_000) { gate.entered.await() }
            assertEquals("cached-model", repo.state.value.model)
            assertFalse("Cached selections cannot become synchronized authorization", repo.state.value.settingsFresh)
            gate.release.complete(Unit)
            awaitState { it.settingsFresh && it.model == "native-model" }
        } finally { gate.release.complete(Unit); withTimeout(5_000) { selection.join() } }
    }

    @Test fun selectingHistoryDoesNotWaitForSlowCatalogOrAccess() = runBlocking<Unit> {
        connect()
        val access = Gate().also { server.gates["thread/access/read"] = it }
        val catalog = Gate().also { server.gates["model/list"] = it }
        val selection = repo.select(chat)
        try {
            withTimeout(5_000) { access.entered.await(); catalog.entered.await() }
            awaitState { it.conversationLoadState == ConversationLoadState.Ready }
            assertTrue("The visible conversation must finish opening while optional metadata is pending", eventually { selection.isCompleted })
            assertNull(repo.state.value.automaticConnectionLabel())
            assertFalse(repo.state.value.accessAllowsSending())
        } finally { access.release.complete(Unit); catalog.release.complete(Unit) }
    }

    @Test fun reenteringKeepsPermissionAndModelLabelsWhileRevalidating() = runBlocking<Unit> {
        selectReady()
        val permission = Gate().also { server.gates["thread/permissions/read"] = it }
        val catalog = Gate().also { server.gates["model/list"] = it }
        repo.select(chat, forceRefresh = true)
        try {
            withTimeout(5_000) { permission.entered.await(); catalog.entered.await() }
            assertEquals("Ask", repo.state.value.permissions.label)
            assertEquals("saved", repo.state.value.permissions.status)
            assertNull("Cached permissions are presentation only", repo.state.value.permissions.revision)
            assertTrue(repo.state.value.permissions.presets.none { it.enabled })
            assertEquals(listOf("catalog-model"), repo.state.value.models.map { it.model })
            permission.release.complete(Unit)
            awaitState { it.permissions.status == "ready" }
        } finally { permission.release.complete(Unit); catalog.release.complete(Unit) }
    }

    @Test fun unchangedRuntimeNotificationsDoNotInvalidateAccessOrRestartPermissions() = runBlocking<Unit> {
        selectReady()
        val before = server.count("thread/permissions/read")
        val gate = Gate().also { server.gates["thread/permissions/read"] = it }
        val refresh = repo.refreshPermissions()
        try {
            withTimeout(5_000) { gate.entered.await() }
            repeat(4) { server.links.single().runtimeNotification() }
            delay(200)
            assertEquals(before + 1, server.count("thread/permissions/read"))
            assertTrue("Identical inventory must not discard verified ownership", key in repo.state.value.accessFresh)
            gate.release.complete(Unit)
            withTimeout(5_000) { refresh.join() }
            assertEquals("ready", repo.state.value.permissions.status)
        } finally { gate.release.complete(Unit) }
    }

    @Test fun initializingPermissionsRetryInPlaceWithoutOpeningThePanel() = runBlocking<Unit> {
        server.permissionStatus = "initializing"
        connect()
        repo.select(chat).join()
        awaitState { it.permissions.status == "initializing" }
        val observer = repo.observeConversation(key)
        try {
            server.permissionStatus = "ready"
            awaitState { it.permissions.status == "ready" }
        } finally { observer.cancelAndJoin() }
    }

    @Test fun missingDirectoryStatusKeepsObservedRunningMarker() = runBlocking<Unit> {
        selectReady()
        db.dao().putChat(chat.copy(status = "active"))
        awaitState { it.chats.any { row -> row.key == key && row.status == "active" } }
        repo.refresh().join()
        assertEquals("active", db.dao().chat(key)?.status)
    }

    @Test fun liveStatusWinsOverDirectoryResponseThatStartedEarlier() = runBlocking<Unit> {
        selectReady()
        server.directoryStatus = "completed"
        val gate = Gate().also { server.gates["threads/list"] = it }
        val refresh = repo.refresh()
        try {
            withTimeout(5_000) { gate.entered.await() }
            server.links.single().statusNotification("active")
            awaitState { it.chats.any { row -> row.key == key && row.status == "active" } }
            gate.release.complete(Unit)
            withTimeout(5_000) { refresh.join() }
            assertEquals("active", db.dao().chat(key)?.status)
        } finally { gate.release.complete(Unit) }
    }

    @Test fun changedOwnerInventoryStillRevokesOldAccessUntilRevalidated() = runBlocking<Unit> {
        selectReady()
        val gate = Gate().also { server.gates["thread/access/read"] = it }
        try {
            server.runtimeOnline = false
            server.links.single().runtimeNotification()
            withTimeout(5_000) { gate.entered.await() }
            assertFalse(key in repo.state.value.accessFresh)
            assertFalse(repo.state.value.accessAllowsSending())
        } finally { gate.release.complete(Unit) }
    }

    @Test fun remainingInBackgroundStillClosesLinkWithoutWatch() = runBlocking<Unit> {
        selectReady()
        val original = server.links.single()
        repo.setForeground(false)
        withTimeout(5_000) { repo.state.first { !it.online } }
        assertTrue("Warm return must not turn into an unbounded background connection", original.closed)
        assertFalse(repo.state.value.settingsFresh)
        assertFalse(repo.state.value.approvalsFresh)
    }

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun waitHere() { entered.complete(Unit); release.await() }
    }

    private inner class SchedulingFixture {
        val links = CopyOnWriteArrayList<Link>()
        val calls = CopyOnWriteArrayList<String>()
        val writes = CopyOnWriteArrayList<String>()
        val gates = ConcurrentHashMap<String, Gate>()
        @Volatile var connectGate: Gate? = null
        @Volatile var failCatalog = false
        @Volatile var permissionStatus = "ready"
        @Volatile var directoryStatus: String? = null
        @Volatile var runtimeOnline = true
        fun count(method: String) = calls.count { it == method }
        fun connection() = Link().also { links += it }
        private fun runtimes() = buildJsonArray { add(buildJsonObject {
            put("id", chat.runtimeId); put("source", "desktop"); put("configStoreId", key.configStoreId); put("online", runtimeOnline)
            put("loadedThreadIds", buildJsonArray { add(key.nativeId) })
            put("capabilities", buildJsonArray { add("thread/read"); add("model/list") })
        }) }
        private fun identity() = buildJsonObject { put("runtimeId", chat.runtimeId); put("threadId", key.nativeId) }
        inner class Link : PocketConnection {
            @Volatile var closed = false
            override val state = MutableStateFlow(LinkState.OFFLINE)
            private val notes = Channel<JsonObject>(Channel.UNLIMITED)
            override val notifications = notes.receiveAsFlow()
            suspend fun runtimeNotification() { notes.send(buildJsonObject {
                put("method", "runtimes/changed"); put("params", buildJsonObject { put("data", runtimes()) })
            }) }
            suspend fun statusNotification(status: String) { notes.send(buildJsonObject {
                put("method", "codex/event"); put("params", buildJsonObject {
                    put("runtimeId", chat.runtimeId)
                    put("cursor", buildJsonObject { put("epoch", "fixture"); put("sequence", 1) })
                    put("event", buildJsonObject { put("method", "thread/status/changed"); put("params", buildJsonObject {
                        put("threadId", key.nativeId); put("status", buildJsonObject { put("type", status) })
                    }) })
                })
            }) }
            override suspend fun connect(config: ConnectionConfig) {
                connectGate?.waitHere()
                state.value = LinkState.ONLINE
            }
            override fun close() { closed = true; state.value = LinkState.OFFLINE; notes.close() }
            override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
                if (commandId != null) { writes += method; error("Scheduling fixture forbids mutations") }
                val operation = if (method == "codex/request") params.string("method") else method
                calls += operation
                gates[operation]?.waitHere()
                return when (operation) {
                    "hello" -> buildJsonObject {
                        put("protocol", 1); put("computerId", key.computerId); put("runtimes", runtimes())
                        put("capabilities", buildJsonArray { add("thread/access/read") })
                    }
                    "runtimes/list" -> buildJsonObject { put("data", runtimes()) }
                    "threads/list" -> buildJsonObject {
                        put("data", buildJsonArray { if (!params.flag("archived")) add(buildJsonObject {
                            put("id", key.nativeId); put("runtimeId", chat.runtimeId); put("source", "desktop")
                            put("configStoreId", key.configStoreId); put("name", "Selected")
                            directoryStatus?.let { put("status", it) }
                        }) }); put("nextCursor", JsonNull)
                    }
                    "sync" -> buildJsonObject {
                        put("events", buildJsonArray {}); put("pendingRequests", buildJsonArray {})
                        put("cursors", buildJsonObject { put(chat.runtimeId, buildJsonObject { put("epoch", "fixture"); put("sequence", 0) }) })
                    }
                    "commands/status" -> buildJsonObject { put("status", "unknown") }
                    "thread/access/read" -> buildJsonObject {
                        put("mode", "desktop"); put("source", "desktop"); put("phase", "ready")
                        put("ownerRuntimeId", chat.runtimeId); put("canSend", true)
                    }
                    "thread/settings/read" -> JsonObject(identity() + buildJsonObject {
                        put("known", true); put("settings", buildJsonObject { put("model", "native-model"); put("effort", "high") })
                    })
                    "thread/permissions/read" -> JsonObject(identity() + buildJsonObject {
                        put("status", permissionStatus); put("revision", "fixture-revision"); put("currentPresetId", "ask")
                        put("presets", buildJsonArray { add(buildJsonObject { put("id", "ask"); put("label", "Ask"); put("enabled", true) }) })
                    })
                    "thread/context/read" -> JsonObject(identity() + ("status" to JsonPrimitive("empty")))
                    "thread/read" -> buildJsonObject {
                        put("thread", buildJsonObject { put("id", params.obj("params").string("threadId")); put("turns", buildJsonArray {}) })
                        put("pocketCursor", buildJsonObject { put("epoch", "fixture"); put("sequence", 0) })
                    }
                    "model/list" -> {
                        if (failCatalog) throw WireException("timeout", "Fixture catalog timeout")
                        buildJsonObject { put("data", buildJsonArray { add(buildJsonObject {
                            put("id", "catalog-model"); put("model", "catalog-model"); put("displayName", "Catalog model")
                        }) }); put("nextCursor", JsonNull) }
                    }
                    else -> buildJsonObject {}
                }
            }
        }
    }
}
