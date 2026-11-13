package dev.codexpocket.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class V06SessionRepositoryTest {
    private lateinit var repo: PocketRepository
    private lateinit var db: PocketDatabase
    private lateinit var server: Fixture
    private val key = ThreadKey("pc", "store", "t")

    @Before fun setup() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        server = Fixture()
        val paired = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "token", "00".repeat(32)), "phone", "secret", "pc", "Fixture")
        val storage = object : PairingStorage {
            override fun load() = paired
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(app, { server }, storage, db, emptyFlow())
        await { it.initialized }
        repo.setForeground(true)
        await { it.online && it.chats.any { chat -> chat.key == key } }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        await { it.conversationLoadState == ConversationLoadState.Ready }
    }

    @After fun cleanup() = runBlocking<Unit> {
        val field = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        assertFalse(server.calls.any { it.method == "codex/request" && it.params.string("method") in setOf("turn/start", "turn/steer", "thread/queue/add") })
    }

    private suspend fun await(predicate: (PocketState) -> Boolean) = withTimeout(7000) { repo.state.first(predicate) }

    @Test fun ownershipRecheckRecoversSameRuntimeWithoutAnyHandoff() = runBlocking<Unit> {
        repo.editDraft("keep my draft")
        server.conflict = true
        server.ownershipReason = "owner_uncertain"
        repo.refresh().join()
        await { it.selected?.conflict == true }
        repo.refreshThreadAccess().join()
        assertFalse(repo.state.value.accessAllowsSending())
        server.conflict = false
        server.ownershipReason = null
        repo.recheckThreadOwnership().join()
        assertFalse("A confirmed same-runtime owner must clear stale local conflict", db.dao().chat(key)!!.conflict)
        await { it.selected?.conflict == false }
        assertTrue(repo.state.value.accessAllowsSending())
        assertEquals("r", repo.state.value.selected!!.runtimeId)
        assertEquals("keep my draft", repo.state.value.draft!!.text)
        assertEquals(OwnershipCheck.Recovered, repo.state.value.ownershipChecks[key])
        assertFalse(server.calls.any { it.method in setOf("runtime/ensure", "thread/access/update") })
    }

    @Test fun ownershipRecheckFailureKeepsSendingBlockedAndDraftIntact() = runBlocking<Unit> {
        repo.editDraft("keep blocked draft")
        server.conflict = true
        server.ownershipReason = "owner_uncertain"
        repo.refresh().join()
        await { it.selected?.conflict == true }
        repo.refreshThreadAccess().join()
        server.accessFails = true
        repo.recheckThreadOwnership().join()
        assertTrue(db.dao().chat(key)!!.conflict)
        assertFalse(repo.state.value.accessAllowsSending())
        assertEquals(OwnershipCheck.Failed, repo.state.value.ownershipChecks[key])
        assertEquals("keep blocked draft", repo.state.value.draft!!.text)
        assertFalse(server.calls.any { it.method in setOf("runtime/ensure", "thread/access/update") })
    }

    @Test fun ownershipRecheckRemainsBlockedAndDeduplicatesReadOnlyInventory() = runBlocking<Unit> {
        server.conflict = true
        server.ownershipReason = "multiple_owners"
        repo.refresh().join()
        await { it.selected?.conflict == true && it.threadOwnershipReasons[key] == OwnershipReason.Multiple }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.directory = { entered.complete(Unit); release.await() }
        val before = server.calls.count { it.method == "threads/list" }
        val first = repo.recheckThreadOwnership()
        entered.await()
        assertEquals(OwnershipCheck.Checking, repo.state.value.ownershipChecks[key])
        repo.recheckThreadOwnership().join()
        assertEquals(before + 1, server.calls.count { it.method == "threads/list" })
        release.complete(Unit); first.join()
        assertEquals(OwnershipCheck.Blocked, repo.state.value.ownershipChecks[key])
        assertEquals(OwnershipReason.Multiple, repo.state.value.ownershipReasonFor())
        assertTrue(db.dao().chat(key)!!.conflict)
        assertFalse(repo.state.value.accessAllowsSending())
        assertFalse(server.calls.any { it.method in setOf("runtime/ensure", "thread/access/update") })
    }

    @Test fun ownershipRecheckDoesNotClearConflictWithoutExactOnlineOwnerPermission() = runBlocking<Unit> {
        server.conflict = true
        repo.refresh().join()
        await { it.selected?.conflict == true }
        server.conflict = false
        server.ownerMissing = true
        repo.recheckThreadOwnership().join()
        assertTrue(db.dao().chat(key)!!.conflict)
        assertEquals(OwnershipCheck.Blocked, repo.state.value.ownershipChecks[key])
        assertFalse(repo.state.value.accessAllowsSending())
        server.ownerMissing = false
        server.accessCanSend = false
        repo.recheckThreadOwnership().join()
        assertTrue(db.dao().chat(key)!!.conflict)
        assertEquals(OwnershipCheck.Blocked, repo.state.value.ownershipChecks[key])
        server.accessCanSend = true
        server.runtimeOnline = false
        repo.recheckThreadOwnership().join()
        assertTrue(db.dao().chat(key)!!.conflict)
        assertEquals(OwnershipCheck.Blocked, repo.state.value.ownershipChecks[key])
        assertFalse(repo.state.value.ownershipVerifiedFor())
    }

    @Test fun cliHistoryReadingDoesNotPrepareOrHandoffAndPreservesDraft() = runBlocking<Unit> {
        repo.editDraft("local draft")
        repo.reloadConversation().join()
        assertEquals("cli", repo.state.value.selected!!.source)
        assertEquals(SessionMode.Cli, repo.state.value.accessFor()!!.mode)
        assertTrue(repo.state.value.accessAllowsSending())
        assertEquals("local draft", repo.state.value.draft!!.text)
        assertFalse(server.calls.any { it.method in setOf("runtime/ensure", "thread/access/update") })
    }

    @Test fun preferencesUseRevisionAndCommandIdAndRefreshAuthority() = runBlocking<Unit> {
        repo.refreshSessionPreferences().join()
        assertEquals(SessionMode.Desktop, repo.state.value.sessionPreferences.defaultMode)
        repo.setDefaultSessionMode(SessionMode.Cli).join()
        val update = server.calls.single { it.method == "session/preferences/update" }
        assertEquals(4, update.params.number("knownRevision").toInt())
        assertEquals(update.command, update.params.string("commandId"))
        assertNotNull(update.command)
        assertEquals(SessionMode.Cli, repo.state.value.sessionPreferences.defaultMode)
        assertEquals(5, repo.state.value.sessionPreferences.revision)
        assertTrue(repo.state.value.preferencesFresh)
    }

    @Test fun directoryRequestDoesNotMarkSendingOrGlobalBusy() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.directory = { entered.complete(Unit); release.await() }
        val refresh = repo.refresh()
        entered.await()
        assertTrue(repo.state.value.directoryRefreshing)
        assertFalse("Cached rows do not become a blocking initial load", repo.state.value.directoryLoading)
        assertFalse(repo.state.value.busy)
        assertFalse(repo.state.value.sendBusy)
        release.complete(Unit); refresh.join()
        assertFalse(repo.state.value.directoryLoading)
        assertFalse(repo.state.value.directoryRefreshing)
    }

    @Test fun desktopEnsureIsOneCommandAndOnlyInventoryIsPolled() = runBlocking<Unit> {
        repo.create(SessionMode.Desktop, "").join()
        assertEquals("d", repo.state.value.selected!!.runtimeId)
        val ensure = server.calls.single { it.method == "runtime/ensure" }
        assertEquals("desktop", ensure.params.string("source"))
        assertEquals(ensure.command, ensure.params.string("commandId"))
        assertTrue(server.calls.count { it.method == "runtimes/list" } >= 2)
        assertEquals(listOf("d"), server.calls.filter { it.method == "codex/request" && it.params.string("method") == "thread/start" }.map { it.params.string("runtimeId") })
    }
    @Test fun newConversationDoesNotInheritPreviousHistoryFailureOrPagination() = runBlocking<Unit> {
        repo.editDraft("preserve previous conversation")
        server.historyFails = true
        repo.reloadConversation().join()
        assertEquals(ConversationLoadState.Failed, repo.state.value.conversationLoadState)
        assertNotNull(repo.state.value.conversationLoadError)
        server.historyFails = false
        repo.create(SessionMode.Desktop, "").join()
        assertEquals("new", repo.state.value.selected!!.key.nativeId)
        assertEquals(ConversationLoadState.Ready, repo.state.value.conversationLoadState)
        assertNull(repo.state.value.conversationLoadError)
        assertTrue(repo.state.value.historyExhausted)
        assertEquals("preserve previous conversation", db.dao().draft(key)!!.text)
    }
    @Test fun expiredMediaRenewsThroughSameHistoryOnceAndKeepsNewReference() = runBlocking<Unit> {
        val old = userMessageMedia(repo.state.value.timeline.items.values.first()).single()
        server.mediaId = "b".repeat(32)
        repo.refreshToolMedia(old)
        val fresh = userMessageMedia(repo.state.value.timeline.items.values.first()).single()
        assertEquals(server.mediaId, fresh.mediaId)
        val reads = server.calls.count { it.method == "codex/request" && it.params.string("method") == "thread/read" }
        repo.refreshToolMedia(old)
        repo.refreshToolMedia(fresh)
        assertEquals(reads, server.calls.count { it.method == "codex/request" && it.params.string("method") == "thread/read" })
        repo.refreshToolMedia(fresh, manual = true)
        assertEquals(reads + 1, server.calls.count { it.method == "codex/request" && it.params.string("method") == "thread/read" })
    }

    @Test fun explicitHandoffUsesActualOwnerAndKeepsHistoricalSource() = runBlocking<Unit> {
        repo.editDraft("retained")
        repo.setThreadSessionMode(SessionMode.Desktop).join()
        assertFalse(repo.state.value.accessAllowsSending())
        assertEquals(AccessPhase.WaitingIdle, repo.state.value.accessFor()!!.phase)
        server.desktop = true; server.completedHandoff = true
        repo.refresh().join(); repo.refreshThreadAccess().join()
        await { it.selected?.runtimeId == "d" && it.accessAllowsSending() }
        assertEquals("cli", repo.state.value.selected!!.source)
        assertEquals("retained", repo.state.value.draft!!.text)
        assertEquals("d", repo.state.value.accessFor()!!.ownerRuntimeId)
    }

    @Test fun userMediaReadCarriesExactContentOwnerAndConnection() = runBlocking<Unit> {
        val ref = userMessageMedia(repo.state.value.timeline.items.values.first()).single()
        val result = repo.toolMedia(ref, 0, 10)
        assertEquals(ref.mediaId, result.mediaId)
        val call = server.calls.last { it.method == "thread/media/read" }
        assertEquals("c", ref.connectionId)
        assertFalse(call.params.containsKey("connectionId")) // Broker derives the current authenticated connection.
        assertEquals("user", call.params.string("itemId"))
        assertEquals("t", call.params.string("threadId"))
        assertNull(call.command)
    }

    @Test fun galleryKeepsCapturedOwnerAndRetryClearsNegativeCache() = runBlocking<Unit> {
        val owner = repo.state.value.selected!!
        server.imageFails = true
        val path = "image-${java.util.UUID.randomUUID()}.png"
        try { repo.desktopImage(path, owner); fail("Expected failure") } catch (_: WireException) { }
        val reads = server.calls.count { it.method == "workspace/image" }
        server.imageFails = false
        try { repo.desktopImage(path, owner); fail("Expected negative cache") }
        catch (failure: WireException) { assertEquals("media_failed", failure.code) }
        assertEquals(reads, server.calls.count { it.method == "workspace/image" })
        val other = owner.copy(key = owner.key.copy(nativeId = "other"))
        db.dao().putChat(other); repo.select(other).join()
        repo.clearDesktopImageFailure(path, owner)
        assertNotNull(repo.desktopImage(path, owner))
        assertEquals("t", server.calls.last { it.method == "workspace/image" }.params.string("threadId"))
    }

    @Test fun fastRefreshesNativeAuthorityAfterSuccessfulUpdate() = runBlocking<Unit> {
        server.fastSupported = true
        repo.refresh().join(); repo.reloadConversation().join()
        await { it.currentModel?.supportsFast == true && it.settingsFresh }
        repo.setFastMode(true).join()
        val update = server.calls.indexOfLast { it.method == "codex/request" && it.params.string("method") == "thread/settings/update" }
        assertTrue(update >= 0)
        assertTrue(server.calls.drop(update + 1).any { it.method == "thread/settings/read" })
        assertFalse("Native normalization is authoritative", repo.state.value.advanced.fastMode)
    }

    @Test fun cachedImageRemainsReadableOfflineWithoutKeepingACompletedFetch() = runBlocking<Unit> {
        val owner = repo.state.value.selected!!
        val path = "cached-${java.util.UUID.randomUUID()}.png"
        val image = repo.desktopImage(path, owner)!!
        val reads = server.calls.count { it.method == "workspace/image" }
        val fetches = PocketRepository::class.java.getDeclaredField("imageFetches").apply { isAccessible = true }.get(repo) as Map<*, *>
        withTimeout(5_000) { while(fetches.isNotEmpty()) delay(10) }
        repo.setForeground(false)
        PocketRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repo, null)
        assertEquals(image, repo.desktopImage(path, owner))
        assertEquals(reads, server.calls.count { it.method == "workspace/image" })
    }

    @Test fun reconnectedImageDoesNotJoinOrPublishAnOldConnectionsDownload() = runBlocking<Unit> {
        val owner = repo.state.value.selected!!
        val path = "reconnect-inflight-${java.util.UUID.randomUUID()}.png"
        val entered = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val freshBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10, 7)
        server.imageRead = {
            val bytes = if (calls.incrementAndGet() == 1) {
                entered.complete(Unit); releaseOld.await(); freshBytes.copyOf().also { it[it.lastIndex] = 3 }
            } else freshBytes
            buildJsonObject { put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)); put("eof", true) }
        }
        val old = async { runCatching { repo.desktopImage(path, owner) } }
        entered.await()
        val generation = PocketRepository::class.java.getDeclaredField("connectionGeneration").apply { isAccessible = true }
            .get(repo) as java.util.concurrent.atomic.AtomicLong
        generation.incrementAndGet()
        val fresh = try { withTimeoutOrNull(1_500) { repo.desktopImage(path, owner) } }
            finally { releaseOld.complete(Unit) }
        val stale = old.await()
        assertNotNull("A new connection must not wait on the previous connection's download", fresh)
        assertTrue("The late old response must not be published", stale.isFailure)
        assertArrayEquals(freshBytes, fresh!!.readBytes())
    }

    @Test fun unloadedIdleThreadPreparesOnceUsingRecordedDesktopMode() = runBlocking<Unit> {
        server.ownerMissing = true; server.configuredMode = "desktop"; server.desktop = true
        repo.refresh().join(); repo.refreshThreadAccess().join()
        assertNull(repo.state.value.accessFor()!!.ownerRuntimeId)
        val owned = repo.prepareThreadForSend(repo.state.value.selected!!)
        assertEquals("d", owned.runtimeId)
        assertEquals("cli", owned.source)
        assertEquals(1, server.calls.count { it.method == "thread/access/update" })
        assertEquals("desktop", server.calls.last { it.method == "thread/access/update" }.params.string("mode"))
        assertTrue(repo.state.value.accessAllowsSending())
    }

    private data class Call(val method: String, val params: JsonObject, val command: String?)
    private class Fixture : PocketConnection {
        override val state = MutableStateFlow(LinkState.OFFLINE)
        override val notifications = emptyFlow<JsonObject>()
        val calls = CopyOnWriteArrayList<Call>()
        var directory: suspend () -> Unit = {}
        var desktop = false
        var ensureRequested = false
        var inventoryPolls = 0
        var handoff = false
        var completedHandoff = false
        var mode = "desktop"
        var revision = 4
        var imageFails = false
        var historyFails = false
        var mediaId = "a".repeat(32)
        var imageRead: suspend () -> JsonElement? = { null }
        var fastSupported = false
        var ownerMissing = false
        var conflict = false
        var ownershipReason: String? = null
        var accessFails = false
        var accessCanSend = true
        var runtimeOnline = true
        var configuredMode: String? = null
        override suspend fun connect(config: ConnectionConfig) { state.value = LinkState.ONLINE }
        override fun close() { state.value = LinkState.OFFLINE }
        private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
        private fun runtimes() = buildJsonArray {
            listOf("r" to "cli", "d" to "desktop").filter { it.first != "d" || desktop }.forEach { (id, source) -> add(buildJsonObject {
                put("id", id); put("source", source); put("configStoreId", "store"); put("online", id != "r" || runtimeOnline)
                put("loadedThreadIds", buildJsonArray { add("t"); add("new") })
                put("capabilities", buildJsonArray { listOf("thread/read", "thread/start", "model/list", "collaborationMode/list", "thread/media/read", "workspace/image", "thread/settings/update").forEach { add(it) } })
                put("capabilityFields", buildJsonObject { put("thread/settings/update", buildJsonArray { add("threadId"); if (fastSupported) add("serviceTier") }) })
            }) }
        }
        private fun access(id: String) = buildJsonObject {
            put("mode", configuredMode ?: if (handoff || completedHandoff || id == "new") "desktop" else "cli")
            put("ownerRuntimeId", if (ownerMissing) JsonNull else JsonPrimitive(if (completedHandoff || id == "new") "d" else "r"))
            put("source", if (id == "new") "desktop" else "cli")
            put("phase", if (handoff && !completedHandoff) "waiting_idle" else "ready")
            put("canSend", accessCanSend && (!handoff || completedHandoff)); if (handoff) put("operationId", "operation")
            if (conflict) {
                put("phase", "blocked"); put("canSend", false); put("ownerRuntimeId", JsonNull)
                put("reason", ownershipReason ?: "thread_owned_elsewhere")
                ownershipReason?.let { put("ownershipReason", it) }
            }
        }
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            calls += Call(method, params, commandId)
            return when (method) {
                "hello" -> buildJsonObject { put("protocol", 1); put("computerId", "pc"); put("runtimes", runtimes())
                    put("capabilities", JsonArray(BrokerCapability.entries.map { JsonPrimitive(it.method) })) }
                "runtimes/list" -> { if (ensureRequested && ++inventoryPolls >= 2) desktop = true; obj("data" to runtimes()) }
                "threads/list" -> { directory(); buildJsonObject { put("data", buildJsonArray { add(buildJsonObject {
                    put("id", "t"); put("runtimeId", if (completedHandoff) "d" else "r"); put("configStoreId", "store"); put("source", "cli")
                    put("conflict", conflict); ownershipReason?.let { put("ownershipReason", it) }
                }) }); put("nextCursor", JsonNull) } }
                "sync" -> buildJsonObject { put("events", buildJsonArray {}); put("cursors", buildJsonObject {}); put("pendingRequests", buildJsonArray {}) }
                "session/preferences/read" -> buildJsonObject { put("defaultMode", mode); put("revision", revision) }
                "session/preferences/update" -> { check(commandId == params.string("commandId")); check(params.number("knownRevision") == revision.toLong()); mode = params.string("mode"); revision++; buildJsonObject {} }
                "thread/access/read" -> { if (accessFails) throw WireException("request_timeout", "fixture"); access(params.string("threadId")) }
                "thread/settings/read" -> buildJsonObject {
                    put("runtimeId", params.string("runtimeId")); put("threadId", params.string("threadId")); put("known", true)
                    put("settings", buildJsonObject { put("model", "m"); put("effort", JsonNull); put("serviceTier", JsonNull) })
                }
                "thread/access/update" -> {
                    check(commandId == params.string("commandId")); handoff = true
                    if (ownerMissing) { ownerMissing = false; completedHandoff = true }
                    access("t")
                }
                "runtime/ensure" -> { check(commandId == params.string("commandId")); ensureRequested = true; buildJsonObject { put("phase", "starting_desktop"); put("runtimeId", JsonNull) } }
                "thread/media/read" -> buildJsonObject { put("mediaId", params.string("mediaId")); put("status", "ready"); put("mimeType", "image/png"); put("size", 10); put("offset", 0); put("eof", true); put("data", "fixture") }
                "workspace/image" -> {
                    imageRead()?.let { return it }
                    if (imageFails) throw WireException("media_failed", "fixture")
                    buildJsonObject { put("data", "iVBORw0KGgo="); put("eof", true) }
                }
                "codex/request" -> when (params.string("method")) {
                    "thread/start" -> buildJsonObject { put("thread", buildJsonObject { put("id", "new"); put("turns", buildJsonArray {}) }) }
                    "thread/read" -> buildJsonObject { if (historyFails) throw WireException("remote_error", "fixture"); put("pocketCursor", buildJsonObject { put("epoch", "fixture"); put("sequence", 0) }); put("thread", buildJsonObject {
                        put("id", params.obj("params").string("threadId")); put("turns", buildJsonArray { add(buildJsonObject {
                            put("id", "turn"); put("status", "completed"); put("items", buildJsonArray { add(buildJsonObject {
                                put("id", "user"); put("type", "userMessage"); put("content", buildJsonArray { add(buildJsonObject {
                                    put("type", "image"); put("mediaId", mediaId); put("runtimeId", params.string("runtimeId")); put("connectionId", "c")
                                    put("threadId", params.obj("params").string("threadId")); put("turnId", "turn"); put("itemId", "user"); put("mimeType", "image/png"); put("byteLength", 10); put("status", "ready")
                                }) })
                            }) })
                        }) })
                    }) }
                    "thread/settings/update" -> buildJsonObject {}
                    "model/list" -> buildJsonObject {
                        put("data", buildJsonArray { add(buildJsonObject { put("id", "m"); put("model", "m"); put("displayName", "Model");
                            put("serviceTiers", buildJsonArray { if (fastSupported) add("fast") }); put("additionalSpeedTiers", buildJsonArray {})
                        }) }); put("nextCursor", JsonNull)
                    }
                    "collaborationMode/list" -> buildJsonObject { put("data", buildJsonArray {}); put("nextCursor", JsonNull) }
                    else -> error("Unexpected native method")
                }
                else -> buildJsonObject {}
            }
        }
    }
}
