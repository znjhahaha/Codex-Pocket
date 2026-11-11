package dev.codexpocket.app

import android.app.Application
import androidx.room.Room
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ThreadDeepLinkRepositoryTest {
    private lateinit var db: PocketDatabase
    private lateinit var repo: PocketRepository
    private val parent = ChatEntity(ThreadKey("pc", "store", "01a1c7fb-7bda-7a71-a782-a020229c9574"), "runtime", "cli", "Parent", "", "D:/work", 0, "idle", false)
    private val child = parent.copy(key = parent.key.copy(nativeId = "01a1c7fb-7bda-7a71-a782-a020229c9573"), title = "Child", isSubagent = true, parentThreadId = parent.key.nativeId)
    private val url = "codex://threads/${child.key.nativeId}"
    private val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private var proofId = child.key.nativeId
    private var intercept: (suspend (String, JsonObject) -> Unit)? = null
    private var directory = JsonArray(emptyList())
    private val link = object : PocketConnection {
        override val state = MutableStateFlow(LinkState.ONLINE)
        override val notifications = emptyFlow<JsonObject>()
        override suspend fun connect(config: ConnectionConfig) = Unit
        override fun close() = Unit
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            assertNull("Deep links must not issue commands", commandId)
            calls += method to params
            intercept?.invoke(method, params)
            return when (method) {
                "threads/list" -> buildJsonObject { put("data", directory); put("nextCursor", JsonNull) }
                "codex/request" -> when (params.string("method")) {
                    "thread/read" -> buildJsonObject { put("thread", buildJsonObject { put("id", proofId); put("cwd", "D:/work"); put("turns", JsonArray(emptyList())) }) }
                    "model/list" -> buildJsonObject { put("data", JsonArray(emptyList())) }
                    else -> buildJsonObject {}
                }
                "phone/presence" -> buildJsonObject {}
                else -> buildJsonObject {}
            }
        }
    }
    @Suppress("UNCHECKED_CAST") private fun update(block: (PocketState) -> PocketState) {
        val field = PocketRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (field.get(repo) as MutableStateFlow<PocketState>).update(block)
    }
    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        val saved = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "fixture-token", "00".repeat(32)), "phone", "fixture-secret", "pc", "Fixture")
        val storage = object : PairingStorage {
            override fun load() = saved
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(app, { link }, storage, db, emptyFlow())
        withTimeout(5000) { repo.state.first { it.initialized } }
        db.dao().putChats(listOf(parent, child))
        repo.select(parent).join()
        PocketRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repo, link)
        update { it.copy(paired = true, online = true, computerId = "pc", selected = parent, draft = DraftEntity(parent.key, "keep parent draft"),
            runtimes = listOf(RuntimeInfo("runtime", "cli", "fixture", "store", true, emptySet(), setOf("thread/read")))) }
    }
    @After fun cleanup() = runBlocking {
        (PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        assertFalse(calls.any { it.first == "runtime/ensure" || it.first == "workspace/read" ||
            it.second.string("method") in setOf("thread/resume", "thread/start", "turn/start", "turn/steer", "thread/queue/add") })
    }
    @Test fun opensVerifiedChildAsItsOwnNativeConversationWithoutSending() = runBlocking {
        repo.openThreadLink(url, parent.key).join()
        assertEquals(child.key, repo.state.value.selected?.key)
        assertEquals("cli", repo.state.value.selected?.source)
        assertTrue(repo.state.value.selected!!.isSubagent)
        assertEquals(parent.key.nativeId, repo.state.value.selected?.parentThreadId)
        val proof = calls.first { it.first == "codex/request" }
        assertEquals("runtime", proof.second.string("runtimeId"))
        assertEquals(child.key.nativeId, proof.second.obj("params").string("threadId"))
        assertFalse(proof.second.obj("params").flag("includeTurns"))
    }
    @Test fun invalidOfflineAndDifferentComputerDoNotReachTransport() = runBlocking {
        repo.openThreadLink("codex://threads/../rpc", parent.key).join()
        assertTrue(repo.state.value.error!!.contains("无效"))
        repo.openThreadLink(url, parent.key.copy(computerId = "elsewhere")).join()
        update { it.copy(online = false) }
        repo.openThreadLink(url, parent.key).join()
        assertTrue(repo.state.value.error!!.contains("离线"))
        assertEquals(parent.key, repo.state.value.selected?.key)
        assertTrue(calls.isEmpty())
    }
    @Test fun otherProfileAndOwnerConflictNeverProbeNativeRpc() = runBlocking {
        db.dao().putChat(child.copy(key = child.key.copy(configStoreId = "other"), runtimeId = "other"))
        db.dao().putChat(child.copy(conflict = true))
        repo.openThreadLink(url, parent.key).join()
        assertEquals(parent.key, repo.state.value.selected?.key)
        assertTrue(calls.isEmpty())
    }
    @Test fun proofMismatchAndUnreadableTargetPreserveCurrentConversation() = runBlocking {
        proofId = parent.key.nativeId
        repo.openThreadLink(url, parent.key).join()
        assertEquals(parent.key, repo.state.value.selected?.key)
        assertEquals("keep parent draft", repo.state.value.draft?.text)
        proofId = child.key.nativeId
        intercept = { method, _ -> if (method == "codex/request") throw WireException("native_history_unavailable", "fixture") }
        repo.openThreadLink(url, parent.key).join()
        assertTrue(repo.state.value.error!!.contains("读取"))
        assertEquals(parent.key, repo.state.value.selected?.key)
    }
    @Test fun lateProofCannotChangeSelectionAfterConnectionGenerationChanges() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        intercept = { method, _ -> if (method == "codex/request") { entered.complete(Unit); release.await() } }
        val job = repo.openThreadLink(url, parent.key)
        try {
            withTimeout(5000) { entered.await() }
            (PocketRepository::class.java.getDeclaredField("connectionGeneration").apply { isAccessible = true }.get(repo) as AtomicLong).incrementAndGet()
        } finally { release.complete(Unit); job.join() }
        assertEquals(parent.key, repo.state.value.selected?.key)
        assertEquals(1, calls.size)
    }
    @Test fun missingTargetIsReportedWithoutProbingArbitraryNativeId() = runBlocking {
        val absent = "codex://threads/01a1c7fb-7bda-7a71-a782-a020229c9500"
        repo.openThreadLink(absent, parent.key).join()
        assertEquals(parent.key, repo.state.value.selected?.key)
        assertTrue(repo.state.value.error!!.contains("未找到"))
        assertTrue(calls.all { it.first == "threads/list" })
        assertEquals(2, calls.size)
    }
}
