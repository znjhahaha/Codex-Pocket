package dev.codexpocket.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Broker history metadata is display evidence and never a runtime ownership claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PersistedConversationMetadataTest {
    private lateinit var app: Application
    private lateinit var db: PocketDatabase
    private lateinit var repo: PocketRepository
    private lateinit var link: HistoryLink
    private val key = ThreadKey("history-meta-computer", "history-meta-store", "old-thread")
    private val chat = ChatEntity(key, "history:${key.configStoreId}", "desktop", "History", "", "", 0, "", false)
    private val jobs = mutableListOf<Job>()

    @Before fun prepare() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("settings", 0).edit().remove("selected")
            .remove("nativeSettings:${key.encoded}").remove("nativeSettings:${key.copy(nativeId = "other").encoded}").commit()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        link = HistoryLink()
        val pair = PairedComputer(ConnectionConfig("wss://fixture.invalid", "fixture", "fixture", "00".repeat(32)),
            "history-phone", "fixture", key.computerId, "Fixture")
        val storage = object : PairingStorage {
            override fun load() = pair
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(app, { link }, storage, db, emptyFlow())
        withTimeout(5_000) { repo.state.first { it.initialized } }
        field("client").set(repo, link)
        db.dao().putChat(chat)
        update { it.copy(online = true, capabilities = setOf("threads/history/read", "threads/control/read",
            "threads/settings/read", "threads/catalog/read")) }
    }

    @After fun close() = runBlocking<Unit> {
        link.controlRelease.complete(Unit)
        link.settingsRelease.complete(Unit)
        jobs.forEach { it.cancelAndJoin() }
        (field("scope").get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        db.close()
    }

    private fun field(name: String) = PocketRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun update(change: (PocketState) -> PocketState) =
        (field("_state").get(repo) as MutableStateFlow<PocketState>).update(change)
    private suspend fun eventually(predicate: () -> Boolean): Boolean = withTimeoutOrNull(1_500) {
        while (!predicate()) delay(10)
        true
    } == true
    private fun select(value: ChatEntity = chat) = repo.select(value, forceRefresh = true).also { jobs += it }

    @Test fun historyMetadataAndCatalogDoNotWaitForControlOrClaimOwnership() = runBlocking<Unit> {
        val selection = select()
        assertTrue("Saved settings, history and catalog must load without waiting for control attachment", eventually {
            val state = repo.state.value
            state.model == "saved-model" && state.permissions.status == "saved" &&
                state.modelCatalogState == ModelCatalogState.Ready && state.conversationLoadState == ConversationLoadState.Ready &&
                state.modes.isNotEmpty()
        })
        val state = repo.state.value
        assertEquals("历史会话", state.automaticConnectionLabel())
        assertEquals(listOf("catalog-first", "catalog-second"), state.models.map { it.model })
        assertEquals("high", state.effort)
        assertEquals("此前的自定义权限", state.permissions.label)
        assertNull(state.permissions.revision)
        assertTrue(state.permissions.presets.none { it.enabled })
        assertFalse(state.settingsFresh)
        assertFalse(key in state.accessFresh)
        assertEquals(chat.runtimeId, state.selected?.runtimeId)
        assertTrue("Saved reads never depend on native authority", link.calls.none { it.first in setOf("thread/access/read", "codex/request") })
        assertTrue(link.calls.all { (_, params) -> params.string("computerId") == key.computerId &&
            params.string("configStoreId") == key.configStoreId && params.string("threadId") == key.nativeId })
        repo.choosePermission("ask").join()
        assertTrue(link.writes.isEmpty())
        assertTrue("An optional control lookup must not keep the displayed history busy", eventually { selection.isCompleted })
        assertFalse(repo.state.value.busy)
    }

    @Test fun persistedMetadataRequiresExactComputerStoreAndThreadIdentity() = runBlocking<Unit> {
        link.controlRelease.complete(Unit)
        for (name in listOf("computerId", "configStoreId", "threadId")) {
            link.foreignField = name
            val before = link.calls.count { it.first == "threads/settings/read" }
            val selection = select()
            assertTrue("The history settings capability must be queried for $name checking", eventually {
                link.calls.count { it.first == "threads/settings/read" } > before
            })
            withTimeout(5_000) { selection.join() }
            assertNull(repo.state.value.model)
            assertFalse(repo.state.value.settingsFresh)
            assertEquals("unknown", repo.state.value.permissions.status)
            assertFalse(key in repo.state.value.accessFresh)
        }
    }

    @Test fun lateSavedMetadataCannotOverwriteAnotherHistorySelectionOrPhoneEdit() = runBlocking<Unit> {
        link.controlRelease.complete(Unit)
        link.holdThread = key.nativeId
        val old = select()
        assertTrue("First history read must reach saved metadata", eventually { link.settingsEntered.isCompleted })
        val other = chat.copy(key = key.copy(nativeId = "other"))
        db.dao().putChat(other)
        select(other)
        assertTrue(eventually { repo.state.value.model == "other-saved-model" })
        repo.chooseModel(NativeModel("phone", "phone-choice", "Phone choice", "", emptyList(), "", false, false), null)
        link.settingsRelease.complete(Unit)
        withTimeout(5_000) { old.join() }
        assertEquals(other.key, repo.state.value.selected?.key)
        assertEquals("phone-choice", repo.state.value.model)
        assertTrue(NativeSettingsField.Model in repo.state.value.pendingSettings)
        assertFalse(repo.state.value.settingsFresh)
        assertTrue(link.writes.isEmpty())
    }

    @Test fun foreignCatalogIdentityCannotReplaceTheSelectedProfilesModels() = runBlocking<Unit> {
        link.foreignCatalog = true
        select()
        assertTrue(eventually { repo.state.value.modelCatalogState == ModelCatalogState.Failed })
        assertTrue(repo.state.value.models.isEmpty())
        assertFalse(repo.state.value.settingsFresh)
        assertTrue(link.writes.isEmpty())
    }

    private inner class HistoryLink : PocketConnection {
        override val state = MutableStateFlow(LinkState.ONLINE)
        override val notifications: Flow<JsonObject> = emptyFlow()
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val writes = CopyOnWriteArrayList<String>()
        val controlRelease = CompletableDeferred<Unit>()
        val settingsEntered = CompletableDeferred<Unit>()
        val settingsRelease = CompletableDeferred<Unit>()
        @Volatile var foreignField: String? = null
        @Volatile var foreignCatalog = false
        @Volatile var holdThread: String? = null
        override suspend fun connect(config: ConnectionConfig) = Unit
        override fun close() = Unit
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            calls += method to params
            if (commandId != null) { writes += method; error("History metadata must not mutate") }
            return when (method) {
                "threads/control/read" -> { controlRelease.await(); buildJsonObject {} }
                "threads/history/read" -> buildJsonObject {
                    put("thread", buildJsonObject { put("id", params.string("threadId")) })
                    put("data", buildJsonArray {}); put("nextCursor", JsonNull); put("historyEpoch", "fixture")
                }
                "threads/settings/read" -> {
                    if (params.string("threadId") == holdThread) { settingsEntered.complete(Unit); settingsRelease.await() }
                    buildJsonObject {
                        listOf("computerId", "configStoreId", "threadId").forEach { name ->
                            put(name, if (name == foreignField) "foreign" else params.string(name))
                        }
                        put("known", false); put("settings", buildJsonObject {})
                        put("readSource", "persisted"); put("savedKnown", true)
                        put("savedSettings", buildJsonObject {
                            put("model", if (params.string("threadId") == "other") "other-saved-model" else "saved-model")
                            put("effort", "high")
                        })
                        put("savedPermissions", buildJsonObject {
                            put("status", "saved"); put("revision", JsonNull); put("currentPresetId", "custom")
                            put("currentLabel", "此前的自定义权限")
                            put("presets", buildJsonArray { add(buildJsonObject {
                                put("id", "ask"); put("label", "请求批准"); put("enabled", false)
                            }) })
                        })
                    }
                }
                "threads/catalog/read" -> buildJsonObject {
                    listOf("computerId", "configStoreId", "threadId").forEach { name ->
                        put(name, if (foreignCatalog && name == "configStoreId") "foreign" else params.string(name))
                    }
                    when (params.string("method")) {
                        "model/list" -> {
                            val next = params.obj("params")["cursor"] == JsonPrimitive("second-page")
                            put("data", buildJsonArray { add(buildJsonObject {
                                val model = if (next) "catalog-second" else "catalog-first"
                                put("id", model); put("model", model); put("displayName", model)
                            }) }); put("nextCursor", if (next) JsonNull else JsonPrimitive("second-page"))
                        }
                        "collaborationMode/list" -> put("data", buildJsonArray { add(buildJsonObject { put("name", "Default"); put("mode", "default") }) })
                        else -> error("Unsupported history catalog")
                    }
                }
                else -> buildJsonObject {}
            }
        }
    }
}
