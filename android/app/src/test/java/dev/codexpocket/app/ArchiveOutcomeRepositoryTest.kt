package dev.codexpocket.app

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Repository and Room; all native writes terminate in this isolated fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArchiveOutcomeRepositoryTest {
    private lateinit var repo: PocketRepository
    private lateinit var db: PocketDatabase
    private lateinit var server: Fixture
    private val key = ThreadKey("archive-computer", "store", "target")

    @Before fun setup() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        server = Fixture()
        val saved = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "token", "00".repeat(32)),
            "phone", "fixture-secret", key.computerId, "Fixture")
        val storage = object : PairingStorage {
            override fun load() = saved
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(app, { server }, storage, db, emptyFlow())
        await { it.initialized }
        repo.setForeground(true)
        await { it.online && it.chats.any { chat -> chat.key == key } }
        // Join the initial directory pass before arming a failure/interleaving.
        repo.refresh().join()
    }

    @After fun cleanup() = runBlocking<Unit> {
        val field = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        db.close()
    }

    @Test fun archiveSuccessSurvivesDirectoryRefreshFailure() = runBlocking<Unit> { successfulRefreshFailure(false) }
    @Test fun unarchiveSuccessSurvivesDirectoryRefreshFailure() = runBlocking<Unit> { successfulRefreshFailure(true) }

    private suspend fun successfulRefreshFailure(archived: Boolean) {
        val chat = chat(archived)
        server.failDirectory = true
        repo.archive(chat).join()
        repo.refresh(archived = !archived).join()
        assertEquals(!archived, db.dao().chat(key)!!.archived)
        assertEquals("accepted", receiptStatus())
        assertNull(repo.state.value.error)
        assertNotNull(repo.state.value.directoryError)
        assertEquals(listOf(if (archived) "thread/unarchive" else "thread/archive"), server.writes.map { it.params.string("method") })
    }

    @Test fun confirmedArchiveIsNotReportedFailedWhenLocalProjectionCannotBeSaved() = runBlocking<Unit> { projectionFailure(false) }
    @Test fun confirmedUnarchiveIsNotReportedFailedWhenLocalProjectionCannotBeSaved() = runBlocking<Unit> { projectionFailure(true) }

    private suspend fun projectionFailure(archived: Boolean) {
        val chat = chat(archived)
        server.failDirectory = true
        db.withTransaction {
            for (event in listOf("INSERT", "UPDATE")) db.openHelper.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER archive_fail_$event BEFORE $event ON chats BEGIN SELECT RAISE(ABORT, 'fixture cache unavailable'); END")
        }
        try {
            repo.archive(chat).join()
            assertEquals("accepted", receiptStatus())
            assertNull("A cache failure must not relabel the accepted native write", repo.state.value.error)
            assertTrue(repo.state.value.actionStatus.values.none { it.error != null })
            assertNotNull(repo.state.value.directoryError)
        } finally {
            db.withTransaction {
                for (event in listOf("INSERT", "UPDATE")) db.openHelper.writableDatabase.execSQL("DROP TRIGGER archive_fail_$event")
            }
        }
    }

    @Test fun lostArchiveReplyRetainsUnknownAndDoesNotRepeatEitherDirection() = runBlocking<Unit> {
        val chat = chat()
        server.reply = { throw IOException("reply lost after native execution") }
        repo.archive(chat).join()
        assertEquals("unknown", receiptStatus())
        assertNull("Unknown is not a definite operation failure", repo.state.value.error)
        assertFalse(db.dao().chat(key)!!.archived)
        repo.archive(chat).join()
        repo.archive(chat.copy(archived = true)).join()
        assertEquals(1, server.writes.size)
        assertEquals("unknown", receiptStatus())
    }

    @Test fun explicitUnknownUnarchiveReplyIsNotPromotedToSuccessOrReplayed() = runBlocking<Unit> {
        val chat = chat(true)
        server.reply = { buildJsonObject { put("status", "unknown") } }
        repo.archive(chat).join()
        repo.archive(chat).join()
        assertTrue(db.dao().chat(key)!!.archived)
        assertEquals("unknown", receiptStatus())
        assertNull(repo.state.value.error)
        assertEquals(1, server.writes.size)
    }

    @Test fun lateSuccessMergesOnlyArchiveFlagIntoCurrentMetadata() = runBlocking<Unit> {
        val chat = chat()
        val held = hold()
        val job = repo.archive(chat)
        withTimeout(5_000) { held.entered.await() }
        val updated = chat.copy(title = "New title", preview = "New preview", pinned = true, updatedAt = 42)
        db.dao().putChat(updated)
        held.result.complete(buildJsonObject {})
        job.join()
        assertEquals(updated.copy(archived = true), db.dao().chat(key))
        // A queued duplicate click on the old row must not dispatch a second command.
        repo.archive(chat).join()
        assertEquals(1, server.writes.size)
    }

    @Test fun lateSuccessCannotRestoreAnObsoleteRuntimeOwner() = runBlocking<Unit> {
        val chat = chat()
        val held = hold()
        val job = repo.archive(chat)
        withTimeout(5_000) { held.entered.await() }
        val replacement = chat.copy(runtimeId = "replacement", title = "Replacement owner", conflict = true)
        db.dao().putChat(replacement)
        held.result.complete(buildJsonObject {})
        job.join()
        assertEquals(replacement, db.dao().chat(key))
        assertEquals("accepted", receiptStatus())
    }

    @Test fun lateSuccessFromOldConnectionCannotRewriteNewProjection() = runBlocking<Unit> {
        val chat = chat()
        val held = hold()
        val job = repo.archive(chat)
        withTimeout(5_000) { held.entered.await() }
        connectionGeneration().incrementAndGet()
        val replacement = chat.copy(title = "New connection snapshot")
        db.dao().putChat(replacement)
        held.result.complete(buildJsonObject {})
        job.join()
        assertEquals(replacement, db.dao().chat(key))
        assertEquals("accepted", receiptStatus())
    }

    @Test fun lateRejectionCannotSetErrorOnSameThreadWithNewRuntime() = runBlocking<Unit> {
        val chat = chat()
        view(chat)
        val held = hold()
        val job = repo.archive(chat)
        withTimeout(5_000) { held.entered.await() }
        val replacement = chat.copy(runtimeId = "replacement")
        view(replacement) // The view already moved; the old RPC is still pending.
        db.dao().putChat(replacement)
        held.result.completeExceptionally(WireException("rejected", "fixture rejection"))
        job.join()
        assertNull(repo.state.value.error)
        assertEquals("rejected", receiptStatus())
        assertEquals(replacement, db.dao().chat(key))
    }

    @Test fun failureForUnselectedArchiveCannotSetAnotherConversationsError() = runBlocking<Unit> {
        val chat = chat()
        view(db.dao().chat(key.copy(nativeId = "other"))!!)
        repo.reportError("Other conversation's existing error")
        server.reply = { throw WireException("rejected", "fixture rejection") }
        repo.archive(chat).join()
        assertEquals("Other conversation's existing error", repo.state.value.error)
        assertEquals("other", repo.state.value.selected?.key?.nativeId)
        assertEquals("rejected", receiptStatus())
    }

    @Test fun currentDefiniteRejectionIsStillVisibleAndDoesNotChangeArchiveState() = runBlocking<Unit> {
        val chat = chat()
        server.reply = { throw WireException("rejected", "fixture rejection") }
        repo.archive(chat).join()
        assertNotNull(repo.state.value.error)
        assertFalse(db.dao().chat(key)!!.archived)
        assertEquals("rejected", receiptStatus())
    }

    @Test fun lateRejectionCannotAffectAnotherConversationSelectedDuringRequest() = runBlocking<Unit> {
        val chat = chat()
        view(chat)
        val held = hold()
        val job = repo.archive(chat)
        withTimeout(5_000) { held.entered.await() }
        view(db.dao().chat(key.copy(nativeId = "other"))!!)
        repo.reportError("Other conversation's existing error")
        held.result.completeExceptionally(WireException("rejected", "fixture rejection"))
        job.join()
        assertEquals("Other conversation's existing error", repo.state.value.error)
        assertEquals("rejected", receiptStatus())
    }

    @Test fun lateRejectionCannotAffectSameViewAfterConnectionReplacement() = runBlocking<Unit> {
        val chat = chat()
        val held = hold()
        val job = repo.archive(chat)
        withTimeout(5_000) { held.entered.await() }
        connectionGeneration().incrementAndGet()
        repo.reportError("New connection's existing error")
        held.result.completeExceptionally(WireException("rejected", "fixture rejection"))
        job.join()
        assertEquals("New connection's existing error", repo.state.value.error)
        assertEquals("rejected", receiptStatus())
    }

    @Test fun cancellationKeepsUnknownWithoutReplaying() = runBlocking<Unit> {
        val chat = chat()
        val held = hold()
        val job = repo.archive(chat)
        withTimeout(5_000) { held.entered.await() }
        job.cancelAndJoin()
        assertEquals("unknown", receiptStatus())
        repo.archive(chat).join()
        assertEquals(1, server.writes.size)
        assertNull(repo.state.value.error)
    }

    @Test fun unarchiveAfterArchiveReleasedOwnerUsesNativeManagementWithoutResume() = runBlocking<Unit> {
        val chat = chat()
        repo.archive(chat).join()
        assertTrue(db.dao().chat(key)!!.archived)
        server.unloaded = true
        server.accessMode = "desktop" // A remembered prepare preference is not the current execution owner.
        repo.refresh().join()
        assertFalse(key.nativeId in repo.state.value.runtimes.single().loadedThreadIds)
        repo.archive(db.dao().chat(key)!!).join()
        assertNull(repo.state.value.error)
        assertFalse(db.dao().chat(key)!!.archived)
        assertEquals(listOf("thread/archive", "thread/unarchive"), server.writes.map { it.params.string("method") })
        assertEquals(2, server.writes.map { it.commandId }.distinct().size)
        assertTrue(server.writes.all { it.params.string("runtimeId") == "runtime" && it.params.obj("params").string("threadId") == key.nativeId })
        assertFalse("Management of unloaded history must not grant sending authority", repo.state.value.ownershipVerifiedFor(db.dao().chat(key)))
        assertFalse(server.calls.any { it.method == "thread/access/update" || it.method == "runtime/ensure" ||
            it.params.string("method") in setOf("thread/resume", "turn/start", "turn/steer") })
    }

    @Test fun unloadedArchiveUsesSameNativeManagementContract() = runBlocking<Unit> {
        val chat = chat()
        server.unloaded = true
        repo.refresh().join()
        repo.archive(chat).join()
        assertNull(repo.state.value.error)
        assertEquals("thread/archive", server.writes.single().params.string("method"))
        assertEquals("accepted", receiptStatus())
        assertTrue(db.dao().chat(key)!!.archived)
    }

    @Test fun unloadedUnarchivePreservesBrokerRejectionAfterOwnershipChanges() = runBlocking<Unit> {
        val chat = chat(true)
        server.unloaded = true
        server.reply = { throw WireException("thread_owned_elsewhere", "owner changed during native proof") }
        repo.archive(chat).join()
        assertEquals(1, server.writes.size)
        assertEquals("rejected", receiptStatus())
        assertNotNull(repo.state.value.error)
        assertTrue(db.dao().chat(key)!!.archived)
    }

    @Test fun unloadedArchiveAndUnarchiveRequireExplicitUnloadedProof() = runBlocking<Unit> {
        for (reason in listOf("owner_uncertain", "multiple_owners", "runtime_offline", "")) {
            val chat = chat(true)
            server.unloaded = true
            server.accessReason = reason
            repo.archive(chat).join()
            assertTrue("Missing or conflicting proof must not dispatch native unarchive: $reason", server.writes.isEmpty())
            assertTrue(db.dao().chat(key)!!.archived)
            assertNotNull(repo.state.value.error)
        }
    }

    @Test fun unloadedUnarchiveRejectsWrongThreadOrRuntimeAccessIdentity() = runBlocking<Unit> {
        val chat = chat(true)
        server.unloaded = true
        server.accessThread = "other"
        repo.archive(chat).join()
        assertTrue(server.writes.isEmpty())
        server.accessThread = null
        server.accessRuntime = "replacement"
        repo.archive(chat).join()
        assertTrue(server.writes.isEmpty())
        assertTrue(db.dao().chat(key)!!.archived)
    }

    @Test fun unloadedUnarchiveRejectsAnotherConfigStoreOrOfflineRuntime() = runBlocking<Unit> {
        val chat = chat(true)
        server.unloaded = true
        updateState { state -> state.copy(runtimes = state.runtimes.map { it.copy(configStoreId = "another-store") }) }
        repo.archive(chat).join()
        assertTrue(server.writes.isEmpty())
        updateState { state -> state.copy(runtimes = state.runtimes.map { it.copy(configStoreId = "store", online = false) }) }
        repo.archive(chat).join()
        assertTrue(server.writes.isEmpty())
        assertTrue(db.dao().chat(key)!!.archived)
    }

    @Test fun unloadedUnarchiveCannotClearConflictOrBypassActiveHandoff() = runBlocking<Unit> {
        server.unloaded = true
        val chat = chat(true).copy(conflict = true)
        db.dao().putChat(chat)
        repo.archive(chat).join()
        assertTrue(server.writes.isEmpty())
        assertTrue(db.dao().chat(key)!!.conflict)
        val clear = chat.copy(conflict = false)
        db.dao().putChat(clear)
        server.accessPhase = "waiting_release"
        repo.archive(clear).join()
        assertTrue(server.writes.isEmpty())
    }

    @Test fun unloadedUnarchiveUnknownIsRetainedAndNeverReplayed() = runBlocking<Unit> {
        val chat = chat(true)
        server.unloaded = true
        server.reply = { throw IOException("unarchive reply lost") }
        repo.archive(chat).join()
        assertEquals(1, server.writes.size)
        assertEquals("unknown", receiptStatus())
        repo.archive(chat).join()
        assertEquals(1, server.writes.size)
        assertTrue(db.dao().chat(key)!!.archived)
        assertNull(repo.state.value.error)
    }

    @Test fun unloadedUnarchiveLosesAdmissionWhenConnectionChangesDuringAccessRead() = runBlocking<Unit> {
        val chat = chat(true)
        server.unloaded = true
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.accessWait = { entered.complete(Unit); release.await() }
        val job = repo.archive(chat)
        try {
            withTimeout(5_000) { entered.await() }
            connectionGeneration().incrementAndGet()
        } finally { release.complete(Unit); job.join() }
        assertTrue(server.writes.isEmpty())
        assertTrue(db.dao().chat(key)!!.archived)
    }

    @Test fun unloadedUnarchiveRechecksConnectionAfterPersistingReceiptBeforeDispatch() = runBlocking<Unit> {
        val chat = chat(true)
        server.unloaded = true
        val recorded = CompletableDeferred<CommandEntity>(); val release = CompletableDeferred<Unit>()
        val original = db.dao()
        val pausing = object : PocketDao by original {
            override suspend fun putCommand(command: CommandEntity) {
                original.putCommand(command)
                if (command.method == "thread/unarchive") { recorded.complete(command); release.await() }
            }
        }
        PocketRepository::class.java.getDeclaredField("dao").apply { isAccessible = true }.set(repo, pausing)
        val job = repo.archive(chat)
        val command = try {
            withTimeout(5_000) { recorded.await() }.also { connectionGeneration().incrementAndGet() }
        } finally { release.complete(Unit); job.join() }
        assertTrue("The old intent must never be sent on a replacement connection", server.writes.isEmpty())
        assertEquals("rejected", receiptStatus(command.id))
        assertTrue(db.dao().chat(key)!!.archived)
    }

    @Test fun archivedHistoryAfterRestartUsesSameStoreChannelWithoutChangingOwnerOrResuming() = runBlocking<Unit> {
        val history = chat(true).copy(runtimeId = "history:store")
        db.dao().putChat(history)
        server.unloaded = true
        repo.archive(history).join()
        assertNull(repo.state.value.error)
        assertEquals(history.copy(archived = false), db.dao().chat(key))
        assertEquals("runtime", server.writes.single().params.string("runtimeId"))
        assertEquals(key.nativeId, server.writes.single().params.obj("params").string("threadId"))
        assertEquals("thread/unarchive", server.writes.single().params.string("method"))
        assertFalse(server.calls.any { it.method in setOf("runtime/ensure", "thread/access/update") ||
            it.params.string("method") in setOf("thread/resume", "turn/start") })
        assertFalse(repo.state.value.ownershipVerifiedFor(db.dao().chat(key)))
    }

    @Test fun archivedHistoryNeverUsesAnotherStoresManagementChannel() = runBlocking<Unit> {
        val history = chat(true).copy(runtimeId = "history:store")
        db.dao().putChat(history)
        updateState { it.copy(runtimes = it.runtimes.map { runtime -> runtime.copy(configStoreId = "other") }) }
        repo.archive(history).join()
        assertTrue(server.writes.isEmpty())
        assertTrue(db.dao().chat(key)!!.archived)
    }

    @Test fun archivedHistoryUnknownCannotBeReplayedThroughAnotherChannel() = runBlocking<Unit> {
        val history = chat(true).copy(runtimeId = "history:store")
        db.dao().putChat(history)
        server.unloaded = true
        server.reply = { throw IOException("lost history management reply") }
        repo.archive(history).join()
        assertEquals("unknown", receiptStatus())
        updateState { it.copy(runtimes = it.runtimes.map { runtime -> runtime.copy(id = "replacement") }) }
        repo.archive(history).join()
        assertEquals(1, server.writes.size)
        assertTrue(db.dao().chat(key)!!.archived)
    }

    private suspend fun chat(archived: Boolean = false): ChatEntity {
        val chat = db.dao().chat(key)!!.copy(archived = archived)
        db.dao().putChat(chat)
        return chat
    }
    private suspend fun await(predicate: (PocketState) -> Boolean) = withTimeout(5_000) { repo.state.first(predicate) }
    private suspend fun receiptStatus(id: String = server.writes.single().commandId!!): String = db.withTransaction {
        db.openHelper.readableDatabase.query("SELECT status FROM commands WHERE id = ?", arrayOf(id)).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun updateState(transform: (PocketState) -> PocketState) {
        val state = PocketRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(repo) as MutableStateFlow<PocketState>
        state.update(transform)
    }
    private fun view(chat: ChatEntity) = updateState { it.copy(selected = chat) }
    private fun connectionGeneration() = PocketRepository::class.java.getDeclaredField("connectionGeneration")
        .apply { isAccessible = true }.get(repo) as AtomicLong
    private fun hold() = HeldReply().also { held ->
        server.reply = { held.entered.complete(Unit); withTimeout(5_000) { held.result.await() } }
    }
    private class HeldReply {
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<JsonElement>()
    }
    private data class Call(val params: JsonObject, val commandId: String?)
    private data class Request(val method: String, val params: JsonObject)
    private class Fixture : PocketConnection {
        override val state = MutableStateFlow(LinkState.OFFLINE)
        override val notifications = emptyFlow<JsonObject>()
        val writes = CopyOnWriteArrayList<Call>()
        val calls = CopyOnWriteArrayList<Request>()
        @Volatile var failDirectory = false
        @Volatile var unloaded = false
        @Volatile var nativeArchived = false
        @Volatile var accessReason = "thread_unloaded"
        @Volatile var accessPhase = "ready"
        @Volatile var accessMode = "cli"
        @Volatile var accessThread: String? = null
        @Volatile var accessRuntime: String? = null
        @Volatile var accessWait: suspend () -> Unit = {}
        @Volatile var reply: suspend () -> JsonElement = { buildJsonObject {} }
        override suspend fun connect(config: ConnectionConfig) { state.value = LinkState.ONLINE }
        override fun close() { state.value = LinkState.OFFLINE }
        private fun runtimes() = buildJsonArray {
            add(buildJsonObject {
                put("id", "runtime"); put("source", "cli"); put("configStoreId", "store"); put("online", true)
                put("loadedThreadIds", buildJsonArray { if (!unloaded) add("target"); add("other") })
                put("capabilities", buildJsonArray { add("thread/archive"); add("thread/unarchive"); add("thread/read") })
            })
        }
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            calls += Request(method, params)
            return when (method) {
            "hello" -> buildJsonObject {
                put("protocol", 1); put("computerId", "archive-computer"); put("runtimes", runtimes())
                put("capabilities", buildJsonArray { add("thread/access/read") })
            }
            "sync" -> buildJsonObject { put("events", buildJsonArray {}); put("cursors", buildJsonObject {}); put("pendingRequests", buildJsonArray {}) }
            "runtimes/list" -> buildJsonObject { put("data", runtimes()) }
            "threads/list" -> {
                if (failDirectory) throw IOException("fixture directory unavailable")
                buildJsonObject {
                    put("data", buildJsonArray {
                        for (id in listOf("target", "other").filter { params.flag("archived") == (it == "target" && nativeArchived) }) add(buildJsonObject {
                            put("id", id); put("name", id); put("runtimeId", "runtime"); put("configStoreId", "store"); put("source", "cli")
                        })
                    }); put("nextCursor", JsonNull)
                }
            }
            "thread/access/read" -> {
                accessWait()
                buildJsonObject {
                    put("threadId", accessThread ?: params.string("threadId")); put("runtimeId", accessRuntime ?: params.string("runtimeId"))
                    put("mode", accessMode); put("source", "cli"); put("phase", accessPhase); put("canSend", !unloaded)
                    put("ownerRuntimeId", if (unloaded) JsonNull else JsonPrimitive(params.string("runtimeId")))
                    if (unloaded) put("reason", accessReason)
                }
            }
            "commands/status" -> buildJsonObject { put("status", "unknown") }
            "codex/request" -> when (params.string("method")) {
                "thread/archive", "thread/unarchive" -> {
                    writes += Call(params, commandId)
                    reply().also { if (it.asObject().string("status") !in setOf("unknown", "submitted"))
                        nativeArchived = params.string("method") == "thread/archive" }
                }
                "thread/read" -> buildJsonObject {
                    put("thread", buildJsonObject { put("id", params.obj("params").string("threadId")); put("turns", buildJsonArray {}) })
                    put("pocketCursor", buildJsonObject { put("epoch", "fixture"); put("sequence", 0) })
                }
                else -> buildJsonObject { put("data", buildJsonArray {}) }
            }
            else -> buildJsonObject {}
        }
        }
    }
}
