package dev.codexpocket.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.resolveWorkspaceLink
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
import java.util.concurrent.atomic.AtomicLong

/** Only the read transport is replaced; selection, reader and source state belong to the repository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChildSourcePreviewRepositoryTest {
    private lateinit var db: PocketDatabase
    private lateinit var repo: PocketRepository
    private val parent = ChatEntity(ThreadKey("pc", "store", "main"), "runtime", "desktop", "Main", "", "D:/main", 0, "idle", false)
    // Deliberately inherited cwd: only a native proof may establish the child's workspace root.
    private val child = parent.copy(key = parent.key.copy(nativeId = "child"), isSubagent = true, parentThreadId = "main")
    private val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private var intercept: (suspend (String, JsonObject) -> JsonElement?)? = null
    private var proofId = "child"
    private var proofCwd = "D:/child"
    private val link = object : PocketConnection {
        override val state = MutableStateFlow(LinkState.ONLINE)
        override val notifications = emptyFlow<JsonObject>()
        override suspend fun connect(config: ConnectionConfig) = Unit
        override fun close() = Unit
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            assertNull(commandId)
            calls += method to params
            intercept?.invoke(method, params)?.let { return it }
            return when (method) {
                "codex/request" -> {
                    assertEquals("thread/read", params.string("method"))
                    assertFalse(params.obj("params").flag("includeTurns"))
                    buildJsonObject { put("thread", buildJsonObject { put("id", proofId); put("cwd", proofCwd) }) }
                }
                "workspace/read" -> sourceResult(params)
                else -> error("Unexpected request: $method")
            }
        }
    }

    private fun sourceResult(params: JsonObject) = buildJsonObject {
        val root = if (params.string("threadId") == "child") "D:/child" else "D:/main"
        put("path", params.string("path").takeIf { it.contains(':') } ?: "$root/${params.string("path")}")
        put("text", "fixture source"); put("truncated", false)
    }

    @Suppress("UNCHECKED_CAST") private fun update(block: (PocketState) -> PocketState) {
        val field = PocketRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (field.get(repo) as MutableStateFlow<PocketState>).update(block)
    }
    private fun advanceGeneration(name: String) {
        val field = PocketRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
        (field.get(repo) as AtomicLong).incrementAndGet()
    }
    private fun verifiedReader(value: ChatEntity = child) = AgentReaderState(parent.key, value, updatedAt = 1)

    @Before fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        val saved = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "token", "00".repeat(32)), "phone", "secret", "pc", "fixture")
        val storage = object : PairingStorage {
            override fun load() = saved
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(app, { link }, storage, db, emptyFlow())
        withTimeout(5_000) { repo.state.first { it.initialized } }
        db.dao().putChat(parent)
        repo.select(parent).join()
        PocketRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repo, link)
        update { it.copy(online = true, paired = true, computerId = "pc", selected = parent, draft = DraftEntity(parent.key, "parent draft"),
            runtimes = listOf(RuntimeInfo("runtime", "desktop", "fixture", "store", true, setOf("main", "child"), setOf("workspace/read", "thread/read"))),
            agentReader = verifiedReader()) }
    }

    @After fun tearDown() = runBlocking {
        val field = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        db.close()
    }

    @Test fun mainCallRetainsDefaultCommentablePreview() = runBlocking {
        repo.readSource("main.kt").join()
        val source = repo.state.value.advanced.sourcePreview!!
        assertEquals(parent.key, source.owner)
        assertFalse(source.readOnly)
        assertEquals("D:/main", source.workspaceRoot)
        assertEquals(listOf("workspace/read"), calls.map { it.first })
        assertEquals("main", calls.single().second.string("threadId"))
        assertFalse(SourcePreview("legacy", "text", false).readOnly)
    }

    @Test fun childUsesNativeCwdAndKeepsParentSelectionAndDraft() = runBlocking {
        repo.readSource("docs/readme.md", owner = child.key).join()
        val source = repo.state.value.advanced.sourcePreview!!
        assertEquals(child.key, source.owner)
        assertTrue(source.readOnly)
        assertEquals("D:/child", source.workspaceRoot)
        assertEquals(parent.key, repo.state.value.selected?.key)
        assertEquals("parent draft", repo.state.value.draft?.text)
        assertTrue(calls.all { it.second.string("runtimeId") == "runtime" })
        assertEquals("child", calls.last().second.string("threadId"))
        val target = resolveWorkspaceLink(source.path, "../next.kt", source.workspaceRoot)!!
        assertEquals("D:/child/next.kt", target)
        repo.readSource(target, owner = source.owner).join()
        assertEquals("child", calls.last().second.string("threadId"))
        assertEquals(target, calls.last().second.string("path"))
    }

    @Test fun arbitraryOrUnverifiedOwnersNeverReachTransport() = runBlocking {
        repo.readSource("file.kt", owner = child.key.copy(nativeId = "other")).join()
        for (reader in listOf(null, verifiedReader().copy(loading = true), verifiedReader().copy(error = "failed"), verifiedReader().copy(updatedAt = 0))) {
            update { it.copy(agentReader = reader) }
            repo.readSource("file.kt", owner = child.key).join()
        }
        assertTrue(calls.isEmpty())
        assertNull(repo.state.value.advanced.sourcePreview)
    }

    @Test fun conflictingChildIdentityNeverReachesTransport() = runBlocking {
        for (value in listOf(child.copy(conflict = true), child.copy(runtimeId = "other-runtime"),
            child.copy(key = child.key.copy(computerId = "other-pc")), child.copy(key = child.key.copy(configStoreId = "other-store")),
            child.copy(parentThreadId = "other-parent"))) {
            update { it.copy(agentReader = verifiedReader(value)) }
            repo.readSource("file.kt", owner = value.key).join()
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun mismatchedNativeProofAndMissingCwdCannotReadFiles() = runBlocking {
        proofId = "wrong-child"
        repo.readSource("file.kt", owner = child.key).join()
        proofId = "child"; proofCwd = ""
        repo.readSource("file.kt", owner = child.key).join()
        assertTrue(calls.none { it.first == "workspace/read" })
        assertNull(repo.state.value.advanced.sourcePreview)
    }

    private suspend fun lateRead(change: suspend () -> Unit, fail: Boolean = false) {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        intercept = { method, params ->
            if (method == "workspace/read" && params.string("path") == "file.kt") {
                entered.complete(Unit); release.await()
                if (fail) throw WireException("offline", "late failure")
            }
            null
        }
        val job = repo.readSource("file.kt", owner = child.key)
        try { withTimeout(5_000) { entered.await() }; change() }
        finally { release.complete(Unit); job.join() }
    }

    @Test fun closingChildDiscardsLateSuccess() = runBlocking {
        lateRead({ repo.closeAgentReader() })
        assertNull(repo.state.value.advanced.sourcePreview)
    }

    @Test fun returningToRosterDiscardsLateSuccess() = runBlocking {
        lateRead({ repo.agentReaderList() })
        assertNull(repo.state.value.advanced.sourcePreview)
    }

    @Test fun reopeningSameChildStillDiscardsEarlierRead() = runBlocking {
        lateRead({ repo.agentReaderList(); update { it.copy(agentReader = verifiedReader()) } })
        assertNull(repo.state.value.advanced.sourcePreview)
    }

    @Test fun parentSelectionGenerationDiscardsLateSuccess() = runBlocking {
        lateRead({ advanceGeneration("selectionGeneration") })
        assertNull(repo.state.value.advanced.sourcePreview)
    }

    @Test fun sameTransportWithNewConnectionGenerationDiscardsLateSuccess() = runBlocking {
        lateRead({ advanceGeneration("connectionGeneration") })
        assertNull(repo.state.value.advanced.sourcePreview)
    }

    @Test fun clearingPreviewDiscardsPendingRead() = runBlocking {
        lateRead({ repo.clearSourcePreview() })
        assertNull(repo.state.value.advanced.sourcePreview)
    }

    @Test fun lateFailureCannotReplaceCurrentError() = runBlocking {
        lateRead({ repo.closeAgentReader(); update { it.copy(error = "current error") } }, fail = true)
        assertEquals("current error", repo.state.value.error)
    }

    @Test fun closingDuringNativeProofPreventsWorkspaceRequest() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        intercept = { method, _ -> if (method == "codex/request") { entered.complete(Unit); release.await() }; null }
        val job = repo.readSource("file.kt", owner = child.key)
        try { withTimeout(5_000) { entered.await() }; repo.closeAgentReader() }
        finally { release.complete(Unit); job.join() }
        assertTrue(calls.none { it.first == "workspace/read" })
    }

    @Test fun newestSourceReadWins() = runBlocking {
        lateRead({ repo.readSource("new.kt").join() })
        assertEquals("D:/main/new.kt", repo.state.value.advanced.sourcePreview?.path)
        assertEquals(parent.key, repo.state.value.advanced.sourcePreview?.owner)
    }
}
