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
class PocketRepositoryTest {
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
        await { it.nativeSettings.model.known && it.model == "model" }
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

    private suspend fun childLink() {
        server.emit("item/completed", buildJsonObject {
            put("threadId", "t"); put("turnId", "parent-turn")
            put("item", buildJsonObject {
                put("id", "spawn"); put("type", "collabAgentToolCall"); put("tool", "spawnAgent"); put("status", "completed")
                put("senderThreadId", "t"); put("receiverThreadIds", buildJsonArray { add("child") })
                put("agentsStates", buildJsonObject { put("child", buildJsonObject { put("status", "running") }) })
            })
        })
        await { it.timeline.items.values.any { item -> item.itemId == "spawn" } }
    }
    @Test fun referenceSearchDiscardsLateQueriesAndNeverBlocksChatConnection() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        update { s -> s.copy(runtimes = s.runtimes.map { it.copy(capabilities = (it.capabilities ?: emptySet()) + "references/list") }) }
        server.intercept = { method, params, _ ->
            if (method == "references/list") {
                val query = params.string("query")
                if (query == "old") { entered.complete(Unit); release.await() }
                buildJsonObject { put("data", buildJsonArray { add(buildJsonObject {
                    put("type", "file"); put("name", "$query.md"); put("path", "D:/fixture/$query.md")
                }) }) }
            } else null
        }
        val old = repo.loadReferences("old")
        withTimeout(5_000) { entered.await() }
        repo.loadReferences("new").join()
        release.complete(Unit); old.join()
        assertEquals("new.md", repo.state.value.advanced.references.single().string("name"))
        assertTrue(repo.state.value.online)
        assertNull(repo.state.value.error)
    }
    @Test fun verifiedReferenceReplacesCurrentTokenWithNoSubmission() = runBlocking<Unit> {
        val ref = buildJsonObject { put("type", "skill"); put("name", "review"); put("path", "D:/fixture/SKILL.md") }
        update { it.copy(advanced = it.advanced.copy(references = listOf(ref))) }
        repo.editDraft("/rev 请保留后缀")
        val before = server.calls.size
        assertTrue(repo.insertReferenceAt(ref, "/rev 请保留后缀", 0, 4))
        assertEquals("review 请保留后缀", repo.state.value.draft?.text)
        assertEquals(before, server.calls.size)
        assertFalse(repo.insertReferenceAt(ref, "/rev 请保留后缀", 0, 4))
        assertEquals("review 请保留后缀", repo.state.value.draft?.text)
        val refs = restoreReferenceDraft(repo.state.value.draft!!.text, repo.state.value.draft!!.references)
        assertEquals("\$review 请保留后缀", prepareReferenceInputs(refs.text, refs.spans).text)
        assertTrue(repo.state.value.draft!!.references.contains("D:/fixture/SKILL.md"))
    }
    @Test fun nativeEditorRemovalCannotTransferAReferenceToAnIdenticalPlainWord() = runBlocking<Unit> {
        val ref = buildJsonObject { put("type", "skill"); put("name", "review"); put("path", "D:/fixture/SKILL.md") }
        update { it.copy(advanced = it.advanced.copy(references = listOf(ref))) }
        repo.editDraft("/rev review")
        assertTrue(repo.insertReferenceAt(ref, "/rev review", 0, 4))
        repo.editDraft("review review", "review", ReferenceTextEdit(0, 7, ""), key)
        assertEquals("review", repo.state.value.draft?.text)
        assertTrue(restoreReferenceDraft("review", repo.state.value.draft!!.references).spans.isEmpty())
        // A delayed editor callback must not overwrite a newer repository edit or another chat.
        repo.editDraft("newer")
        repo.editDraft("review", "stale", null, key)
        repo.editDraft("newer", "wrong chat", null, otherKey)
        assertEquals("newer", repo.state.value.draft?.text)
    }
    @Test fun namedChildStatusRefreshAndOrderedLifecycleUpdateTheRoster() = runBlocking<Unit> {
        childLink()
        val childKey = key.copy(nativeId = "child")
        until { repo.state.value.agentChats.any { it.key == childKey } }
        server.emit("thread/status/changed", buildJsonObject {
            put("threadId", "child"); put("status", buildJsonObject { put("type", "active"); put("activeFlags", JsonArray(emptyList())) })
        })
        await { it.agentStatuses[childKey]?.status == ActivityStatus.Running }
        assertEquals(ActivityStatus.Running, repo.state.value.currentAgents().single { it.threadId == "child" }.status)
        server.emit("turn/completed", buildJsonObject {
            put("threadId", "child"); put("turn", buildJsonObject { put("id", "child-turn"); put("status", "completed") })
        })
        await { it.agentStatuses[childKey]?.status == ActivityStatus.Completed }
        assertEquals(0, repo.state.value.currentAgents().count { it.status == ActivityStatus.Running })
    }
    @Test fun historicalChildResumeCountsAgainAndLateOldCompletionCannotHideIt() = runBlocking<Unit> {
        childLink()
        val childKey = key.copy(nativeId = "child")
        until { repo.state.value.agentChats.any { it.key == childKey } }
        server.emit("turn/completed", buildJsonObject {
            put("threadId", "child"); put("turn", buildJsonObject { put("id", "old-run"); put("status", "completed") })
        })
        await { it.agentStatuses[childKey]?.status == ActivityStatus.Completed }
        server.emit("item/completed", buildJsonObject {
            put("threadId", "t"); put("turnId", "parent-turn")
            put("item", buildJsonObject { put("id", "resume-old-child"); put("type", "collabAgentToolCall")
                put("tool", "resumeAgent"); put("status", "completed"); put("receiverThreadIds", buildJsonArray { add("child") })
                put("agentsStates", buildJsonObject { put("child", buildJsonObject { put("status", "running") }) }) })
        })
        await { it.agentStatuses[childKey]?.status == ActivityStatus.Running && (it.agentStatuses[childKey]?.executionGeneration ?: 0) > 0 }
        assertEquals(1, currentAgentWork(repo.state.value.currentAgents()).running.size)
        server.emit("turn/completed", buildJsonObject {
            put("threadId", "child"); put("turn", buildJsonObject { put("id", "old-run"); put("status", "completed") })
        })
        server.emit("turn/started", buildJsonObject {
            put("threadId", "child"); put("turn", buildJsonObject { put("id", "new-run"); put("status", "inProgress") })
        })
        await { it.agentStatuses[childKey]?.turnId == "new-run" }
        assertEquals(1, currentAgentWork(repo.state.value.currentAgents()).running.size)
        assertEquals(1, repo.state.value.currentAgents().count { it.threadId == "child" })
        server.emit("turn/completed", buildJsonObject {
            put("threadId", "child"); put("turn", buildJsonObject { put("id", "new-run"); put("status", "completed") })
        })
        await { it.agentStatuses[childKey]?.status == ActivityStatus.Completed }
        assertEquals(0, currentAgentWork(repo.state.value.currentAgents()).running.size)
    }
    @Test fun cachedChildFromStoppedRuntimeRebindsOnlyAfterNativeRead() = runBlocking<Unit> {
        childLink()
        val parent = repo.state.value.selected!!
        val childKey = key.copy(nativeId = "child")
        db.dao().putChat(parent.copy(key = childKey, runtimeId = "stopped-runtime", isSubagent = true, parentThreadId = key.nativeId))
        repo.editDraft("preserve parent draft")
        repo.openAgentReader("child").join()
        assertEquals(childKey, repo.state.value.agentReader?.child?.key)
        assertNull(repo.state.value.agentReader?.error)
        assertEquals(parent.runtimeId, db.dao().chat(childKey)?.runtimeId)
        assertEquals(key, repo.state.value.selected?.key)
        assertEquals("preserve parent draft", repo.state.value.draft?.text)
    }
    @Test fun staleChildCannotStealALiveOwnerOrCommitMismatchedIdentity() = runBlocking<Unit> {
        childLink()
        val parent = repo.state.value.selected!!
        val childKey = key.copy(nativeId = "child")
        val child = parent.copy(key = childKey, runtimeId = "other-runtime", isSubagent = true, parentThreadId = key.nativeId)
        db.dao().putChat(child)
        update { it.copy(runtimes = it.runtimes + it.activeRuntime!!.copy(id = "other-runtime")) }
        repo.openAgentReader("child").join()
        assertNotNull(repo.state.value.agentReader?.error)
        assertEquals("other-runtime", db.dao().chat(childKey)?.runtimeId)
        update { it.copy(runtimes = it.runtimes.filterNot { r -> r.id == "other-runtime" }) }
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/read" && params.obj("params").string("threadId") == "child")
                buildJsonObject { put("thread", server.thread("wrong-child")) } else null
        }
        repo.openAgentReader("child").join()
        assertNotNull(repo.state.value.agentReader?.error)
        assertEquals("other-runtime", db.dao().chat(childKey)?.runtimeId)
        assertEquals(key, repo.state.value.selected?.key)
    }
    @Test fun metadataRefreshRebindsAStoppedChildRuntime() = runBlocking<Unit> {
        childLink()
        val parent = repo.state.value.selected!!
        val childKey = key.copy(nativeId = "child")
        db.dao().putChat(parent.copy(key = childKey, runtimeId = "stopped-runtime", isSubagent = true, parentThreadId = key.nativeId))
        await { it.agentChats.any { c -> c.key == childKey && c.runtimeId == "stopped-runtime" } }
        repo.refreshAgentNames().join()
        assertEquals(parent.runtimeId, db.dao().chat(childKey)?.runtimeId)
    }
    @Test fun nativeChildMissingFromDirectoryOpensReadOnlyAndReturnsToParentDraft() = runBlocking<Unit> {
        childLink()
        repo.editDraft("parent draft")
        val before = server.calls.size
        repo.openAgent("child").join()
        assertEquals("child", repo.state.value.selected?.key?.nativeId)
        assertTrue(repo.state.value.online)
        assertTrue(server.calls.drop(before).all { it.commandId == null })
        assertTrue(server.calls.drop(before).none { it.nativeMethod in setOf("thread/start", "thread/resume", "turn/start") })
        repo.returnFromAgent(key).join()
        assertEquals(key, repo.state.value.selected?.key)
        assertEquals("parent draft", repo.state.value.draft?.text)
    }
    @Test fun unverifiedChildAndMismatchedIdentityCannotNavigateAway() = runBlocking<Unit> {
        val before = server.calls.size
        repo.openAgent("invented").join()
        assertEquals(before, server.calls.size)
        childLink()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/read" && params.obj("params").string("threadId") == "child")
                buildJsonObject { put("thread", server.thread("wrong-id")) } else null
        }
        repo.openAgent("child").join()
        assertEquals(key, repo.state.value.selected?.key)
        assertTrue(repo.state.value.online)
    }
    @Test fun lateChildReadCannotOverrideNewSelection() = runBlocking<Unit> {
        childLink()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/read" && params.obj("params").string("threadId") == "child") {
                entered.complete(Unit); release.await()
            }
            null
        }
        val opening = repo.openAgent("child")
        withTimeout(5_000) { entered.await() }
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        release.complete(Unit); opening.join()
        assertEquals(otherKey, repo.state.value.selected?.key)
        assertTrue(repo.state.value.online)
    }
    @Test fun failedSelectedHistoryCannotKeepAuthenticatedConnectionOffline() = runBlocking<Unit> {
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/read") throw WireException("remote_error", "fixture missing history")
            null
        }
        server.links.last().close()
        until { server.attempts.get() > 1 }
        await { it.online }
        assertEquals(key, repo.state.value.selected?.key)
    }

    @Test fun slowHistoryDoesNotBlockLiveEventsAndSnapshotRetainsLaterEvents() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val unblock = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/read" && params.obj("params").string("threadId") == "t") {
                entered.complete(Unit); unblock.await()
                buildJsonObject { put("thread", buildJsonObject { put("id", "t"); put("turns", buildJsonArray {}) }); put("pocketCursor", buildJsonObject { put("epoch", "epoch"); put("sequence", 0) }) }
            } else null
        }
        val selection = repo.select(repo.state.value.selected!!)
        withTimeout(5_000) { entered.await() }
        try {
            server.emit("item/completed", buildJsonObject {
                put("threadId", "t"); put("turnId", "during-read")
                put("item", buildJsonObject { put("id", "live"); put("type", "agentMessage"); put("text", "arrived during history") })
            })
            await { it.timeline.items.values.any { item -> item.text == "arrived during history" } }
        } finally { unblock.complete(Unit); selection.join() }
        assertTrue(repo.state.value.timeline.items.values.any { it.text == "arrived during history" })
        assertTrue(repo.state.value.online)
    }

    @Test fun supersededHistoryCaptureCannotAuthorizeUnknownCommandResolution() = runBlocking<Unit> {
        db.dao().putCommand(CommandEntity("superseded-inspection", "computer", key, "turn/start", "unknown", 1))
        server.statuses["superseded-inspection"] = "unknown"
        await { it.uncertainSend }
        val firstEntered = CompletableDeferred<Unit>(); val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>(); val secondRelease = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/read") {
                when (reads.incrementAndGet()) {
                    1 -> {
                        // Include a turn so this response is one complete snapshot, without a fallback read.
                        val snapshot = server.historySnapshot(JsonObject(server.thread("t") + ("turns" to buildJsonArray {
                            add(buildJsonObject { put("id", "inspected"); put("status", "completed"); put("items", buildJsonArray {}) })
                        })))
                        firstEntered.complete(Unit); firstRelease.await(); snapshot
                    }
                    2 -> {
                        secondEntered.complete(Unit); secondRelease.await()
                        throw WireException("timeout", "Replacement history never applied")
                    }
                    else -> error("Unexpected extra history read")
                }
            } else null
        }
        val first = repo.inspectUnknownHistory()
        var second: Job? = null
        try {
            withTimeout(5_000) { firstEntered.await() }
            // Both inspections use the same selection and connection generations.
            second = repo.inspectUnknownHistory()
            withTimeout(5_000) { secondEntered.await() }
            firstRelease.complete(Unit)
            withTimeout(5_000) { first.join() }
            assertFalse("A discarded capture is not successful history inspection", repo.state.value.unknownHistoryInspected)
            repo.resolveUnknownAfterInspection().join()
            assertEquals("unknown", db.dao().uncertain("computer").single().status)
            assertTrue(repo.state.value.uncertainSend)
            assertFalse(server.calls.any { it.nativeMethod == "turn/start" })
        } finally {
            firstRelease.complete(Unit); secondRelease.complete(Unit)
            withTimeout(5_000) { first.join(); second?.join() }
        }
        assertFalse(repo.state.value.unknownHistoryInspected)
    }

    @Test fun fullHistorySnapshotRetainsNewerGoalUsageAndFastMode() = runBlocking<Unit> {
        assertHistoryRetainsLiveMetadata(paged = false)
    }

    @Test fun pagedHistoryUsesMetadataCursorForGoalUsageAndFastMode() = runBlocking<Unit> {
        assertHistoryRetainsLiveMetadata(paged = true)
    }

    private suspend fun assertHistoryRetainsLiveMetadata(paged: Boolean) {
        server.pagedHistory = paged
        val entered = CompletableDeferred<Unit>(); val unblock = CompletableDeferred<Unit>()
        val oldGoal = buildJsonObject { put("objective", "Old goal"); put("status", "active") }
        val newGoal = buildJsonObject { put("objective", "Updated during history"); put("status", "paused") }
        val oldUsage = buildJsonObject { put("total", buildJsonObject { put("totalTokens", 10) }) }
        val newUsage = buildJsonObject { put("total", buildJsonObject { put("totalTokens", 900) }) }
        val metadata = server.historySnapshot(JsonObject(server.thread("t") + buildJsonObject {
            put("goal", oldGoal); put("tokenUsage", oldUsage); put("serviceTier", JsonNull)
            if (!paged) put("turns", buildJsonArray {
                add(buildJsonObject { put("id", "completed"); put("status", "completed"); put("items", buildJsonArray {}) })
            })
        }))
        val pageCallsBefore = server.calls.count { it.nativeMethod == "thread/turns/list" }
        server.intercept = { method, params, _ ->
            if (method != "codex/request") null else when (params.string("method")) {
                "thread/read" -> {
                    if (!paged) { entered.complete(Unit); unblock.await() }
                    metadata
                }
                "thread/turns/list" -> {
                    check(paged)
                    entered.complete(Unit); unblock.await()
                    // The fixture attaches the later page cursor after all three events.
                    null
                }
                else -> null
            }
        }
        val reading = repo.select(repo.state.value.selected!!)
        try {
            withTimeout(5_000) { entered.await() }
            server.emit("thread/goal/updated", buildJsonObject { put("threadId", "t"); put("goal", newGoal) })
            server.emit("thread/tokenUsage/updated", buildJsonObject { put("threadId", "t"); put("tokenUsage", newUsage) })
            server.emit("thread/settings/updated", buildJsonObject {
                put("threadId", "t"); put("threadSettings", buildJsonObject { put("serviceTier", "fast") })
            })
            await { it.advanced.goal == newGoal && it.advanced.usage == newUsage && it.advanced.fastMode }
            // State updates precede cursor persistence; wait for consumption, not merely rendering.
            withTimeout(5_000) {
                while (db.dao().cursors("computer").none { it.runtimeId == "runtime" && it.sequence == 3L }) delay(10)
            }
        } finally { unblock.complete(Unit); withTimeout(5_000) { reading.join() } }
        assertEquals(ConversationLoadState.Ready, repo.state.value.conversationLoadState)
        assertEquals("History must not restore the old goal", newGoal, repo.state.value.advanced.goal)
        assertEquals("History must not restore old token usage", newUsage, repo.state.value.advanced.usage)
        assertTrue("History must retain the live service tier", repo.state.value.advanced.fastMode)
        assertEquals(pageCallsBefore + (if (paged) 1 else 0), server.calls.count { it.nativeMethod == "thread/turns/list" })
        if (paged) assertEquals(3L, db.dao().snapshot(key)!!.sequence)
    }

    @Test fun initialAndOlderHistoryPagesRequestFullItemsAndRetainToolDetails() = runBlocking<Unit> {
        server.pagedHistory = true
        server.pageTurns = listOf(buildJsonObject {
            put("id", "full-turn"); put("status", "completed"); put("itemsView", "full")
            put("items", buildJsonArray {
                add(buildJsonObject { put("id", "answer"); put("type", "agentMessage"); put("text", "Visible summary") })
                add(buildJsonObject {
                    put("id", "tool-detail"); put("type", "commandExecution"); put("command", "fixture-command")
                    put("status", "completed"); put("aggregatedOutput", "Full history tool output")
                })
            })
        })
        assertTrue(repo.state.value.supports("thread/turns/list"))
        val before = server.calls.size
        repo.select(repo.state.value.selected!!).join()
        assertNull(repo.state.value.error)
        assertEquals(ConversationLoadState.Ready, repo.state.value.conversationLoadState)
        val initial = server.calls.drop(before).single { it.nativeMethod == "thread/turns/list" }
        assertEquals("full", initial.params.obj("params").string("itemsView"))
        assertEquals(HISTORY_FIRST_PAGE.toLong(), initial.params.obj("params").number("limit"))
        assertEquals(JsonPrimitive("page-2"), repo.state.value.nextTurns)
        assertTrue(repo.state.value.timeline.items.values.any {
            it.itemId == "tool-detail" && it.raw.string("aggregatedOutput") == "Full history tool output"
        })
        assertTrue(db.dao().items(key).any { it.item().itemId == "tool-detail" })
        assertEquals(1, server.calls.drop(before).count { it.nativeMethod == "thread/read" })
        repo.older().join()
        assertNull("The fixture must accept the full-items broker contract", repo.state.value.error)
        val older = server.calls.drop(before).last { it.nativeMethod == "thread/turns/list" }.params.obj("params")
        assertEquals("full", older.string("itemsView"))
        assertEquals("page-2", older.string("cursor"))
        assertEquals(HISTORY_OLDER_PAGE.toLong(), older.number("limit"))
        assertTrue(repo.state.value.historyExhausted)
        assertTrue(repo.state.value.timeline.items.values.any { it.itemId == "tool-detail" })
    }

    private suspend fun question(thread: String = "t", item: String = "question") {
        server.emit("item/completed", buildJsonObject {
            put("threadId", thread); put("turnId", "ask")
            put("item", buildJsonObject {
                put("id", item); put("type", "agentMessage"); put("delivery", "async"); put("text", "Choose")
                put("questions", buildJsonArray { add(buildJsonObject { put("title", "Which client?") }) })
            })
        })
    }

    @Test fun acceptedAsyncAnswerWaitsForNativeHistoryAndRepeatedCallbackDoesNotResend() = runBlocking<Unit> {
        question()
        val q = await { it.advanced.asyncQuestions.isNotEmpty() }.advanced.asyncQuestions.single()
        repo.answerAsync(q.string("id"), buildJsonObject { put("answer", "Phone") }).join()
        assertTrue(repo.state.value.advanced.asyncQuestions.single().flag("submitted"))
        repo.answerAsync(q.string("id"), buildJsonObject { put("answer", "Phone") }).join()
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
        val reply = server.calls.single { it.nativeMethod == "turn/start" }.params.obj("params").array("input").first().asObject().string("text")
        server.emit("item/completed", buildJsonObject {
            put("threadId", "t"); put("turnId", "answer")
            put("item", buildJsonObject { put("id", "reply"); put("type", "userMessage"); put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", reply) }) }) })
        })
        await { it.advanced.asyncQuestions.isEmpty() }
        assertFalse(server.calls.any { it.method == "approvals/respond" })
    }

    @Test fun failedAsyncPreflightDoesNotPersistSubmittedGuard() = runBlocking<Unit> {
        question()
        val q = await { it.advanced.asyncQuestions.isNotEmpty() }.advanced.asyncQuestions.single()
        update { it.copy(online = false) }
        repo.answerAsync(q.string("id"), buildJsonObject { put("answer", "Phone") }).join()
        assertFalse(repo.state.value.advanced.asyncQuestions.single().flag("submitted"))
        assertFalse(server.calls.any { it.nativeMethod == "turn/start" })
    }

    @Test fun asyncNativeAnswerDuringRejectedReplyCannotBeResurrected() = runBlocking<Unit> {
        question()
        val q = await { it.advanced.asyncQuestions.isNotEmpty() }.advanced.asyncQuestions.single()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") {
                entered.complete(Unit); release.await(); throw WireException("rejected", "fixture")
            }
            null
        }
        val job = repo.answerAsync(q.string("id"), buildJsonObject { put("answer", "Phone") })
        withTimeout(5_000) { entered.await() }
        try {
            server.emit("item/completed", buildJsonObject {
                put("threadId", "t"); put("turnId", "answer")
                put("item", buildJsonObject { put("id", "reply"); put("type", "userMessage"); put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", AsyncQuestions.reply(q, "Desktop")) }) }) })
            })
            await { it.advanced.asyncQuestions.isEmpty() }
        } finally { release.complete(Unit); job.join() }
        assertTrue(repo.state.value.advanced.asyncQuestions.isEmpty())
    }

    @Test fun unknownAsyncAnswerSurvivesRestartWithoutAutomaticReplay() = runBlocking<Unit> {
        question()
        val q = await { it.advanced.asyncQuestions.isNotEmpty() }.advanced.asyncQuestions.single()
        server.intercept = { method, params, id ->
            if (method == "codex/request" && params.string("method") == "turn/start") {
                server.statuses[id!!] = "unknown"; throw UnknownOutcomeException(id)
            }
            null
        }
        repo.answerAsync(q.string("id"), buildJsonObject { put("answer", "Phone") }).join()
        restartOffline()
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertTrue(repo.state.value.advanced.asyncQuestions.single().flag("submitted"))
        repo.answerAsync(q.string("id"), buildJsonObject { put("answer", "Phone") }).join()
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
    }

    @Test fun unreadSurvivesRestartAndReadClearsOnlyItsConversation() = runBlocking<Unit> {
        server.emit("turn/completed", buildJsonObject { put("threadId", "other"); put("turn", buildJsonObject { put("id", "done"); put("status", "completed") }) })
        await { otherKey.encoded in it.unreadKeys }
        restartOffline()
        assertEquals(setOf(otherKey.encoded), repo.state.value.unreadKeys)
        repo.markRead(repo.state.value.chats.first { it.key == key })
        assertTrue(otherKey.encoded in repo.state.value.unreadKeys)
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        restartOffline()
        assertTrue(repo.state.value.unreadKeys.isEmpty())
    }
    @Test fun pendingQuestionsFromOtherChatsSurviveRestartAndKeepIdentity() = runBlocking<Unit> {
        question("other", "other-question")
        await { it.pendingQuestions[otherKey.encoded]?.size == 1 }
        assertTrue(repo.state.value.advanced.asyncQuestions.isEmpty())
        restartOffline()
        await { it.pendingQuestions[otherKey.encoded]?.size == 1 }
        assertEquals("[\"request_user_input_async\",\"other-question\",0]", repo.state.value.pendingQuestions.getValue(otherKey.encoded).single().string("id"))
    }

    @Test fun createClearsSourceAdvancedState() = runBlocking<Unit> {
        update { it.copy(advanced = AdvancedConversationState(goal = buildJsonObject { put("objective", "old") }, fastMode = true,
            sourcePreview = SourcePreview("old", "old", false), uploadProgress = mapOf("old" to 1f))) }
        repo.create(repo.state.value.runtimes.first(), "D:/fixture").join()
        assertEquals("new", repo.state.value.selected!!.key.nativeId)
        assertEquals(AdvancedConversationState(), repo.state.value.advanced)
    }

    @Test fun acceptedDraftClearsReferencesButPreservesScrollAndSubsequentEdits() = runBlocking<Unit> {
        val ref = buildJsonObject { put("type", "skill"); put("name", "review"); put("path", "D:/fixture/SKILL.md") }
        update { it.copy(advanced = it.advanced.copy(references = listOf(ref))) }
        repo.insertReference(ref)
        repo.scroll(4, 23, false, "native-row")
        repo.send().join()
        assertEquals("[]", repo.state.value.draft!!.references)
        assertEquals("native-row", repo.state.value.draft!!.scrollItemKey)
        assertEquals(23, repo.state.value.draft!!.scrollOffset)
        assertFalse(repo.state.value.draft!!.followEnd)
        assertTrue(server.calls.single { it.nativeMethod == "turn/start" }.params.obj("params").array("input").any { it.asObject().string("type") == "skill" })
    }

    @Test fun queueMutationsUseExactNativeContractsAndDurableCommandIds() = runBlocking<Unit> {
        repo.queueEdit("q1", "edited").join()
        repo.queueMove("q2", "q1").join()
        repo.queueDelete("q1").join()
        val edits = server.calls.filter { it.nativeMethod in setOf("thread/queue/update", "thread/queue/reorder", "thread/queue/delete") }
        assertEquals(3, edits.size)
        assertTrue(edits.all { !it.commandId.isNullOrBlank() })
        assertEquals(setOf("threadId", "queuedSubmissionId", "input"), edits[0].params.obj("params").keys)
        assertEquals(listOf("q2", "q1"), edits[1].params.obj("params").array("queuedSubmissionIds").map { it.jsonPrimitive.content })
        assertEquals(setOf("threadId", "queuedSubmissionIds"), edits[1].params.obj("params").keys)
        assertEquals(setOf("threadId", "queuedSubmissionId"), edits[2].params.obj("params").keys)
    }

    @Test fun historyUsesAllowedFieldsRetainsCursorAndStopsAtExhaustion() = runBlocking<Unit> {
        repo.older().join()
        assertNull("History request must pass the broker allowlist", repo.state.value.error)
        assertEquals(JsonPrimitive("page-2"), repo.state.value.nextTurns)
        repo.older().join()
        assertTrue(repo.state.value.historyExhausted)
        val calls = server.calls.count { it.nativeMethod == "thread/turns/list" }
        repo.older().join()
        assertEquals(calls, server.calls.count { it.nativeMethod == "thread/turns/list" })
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        assertFalse(repo.state.value.historyExhausted)
    }

    @Test fun explicitUnknownResolutionRequiresInspectionAndRetainsDraftWithoutReplay() = runBlocking<Unit> {
        repo.editDraft("keep this draft")
        db.dao().putCommand(CommandEntity("lost", "computer", key, "turn/start", "unknown", 1, "keep this draft"))
        server.statuses["lost"] = "unknown"
        await { it.uncertainSend }
        call("resolveUnknownAfterInspection").join()
        assertTrue(repo.state.value.uncertainSend)
        call("inspectUnknownHistory").join()
        assertTrue(repo.state.value.unknownHistoryInspected)
        call("resolveUnknownAfterInspection").join()
        await { !it.uncertainSend }
        assertEquals("keep this draft", repo.state.value.draft?.text)
        assertTrue(db.dao().uncertain("computer").isEmpty())
        assertEquals(0, server.calls.count { it.nativeMethod == "turn/start" })
    }

    @Test fun newUnknownCommandInvalidatesEarlierInspection() = runBlocking<Unit> {
        db.dao().putCommand(CommandEntity("one", "computer", key, "turn/start", "unknown", 1))
        server.statuses["one"] = "unknown"
        await { it.uncertainSend }
        call("inspectUnknownHistory").join()
        assertTrue(repo.state.value.unknownHistoryInspected)
        db.dao().putCommand(CommandEntity("two", "computer", key, "turn/start", "unknown", 2))
        server.statuses["two"] = "unknown"
        await { it.commands.size == 2 }
        call("resolveUnknownAfterInspection").join()
        assertTrue(repo.state.value.uncertainSend)
        assertEquals(2, db.dao().uncertain("computer").size)
    }

    @Test fun liveThreadCreationAppearsWithoutManualRefresh() = runBlocking<Unit> {
        server.rows += server.row("created-on-desktop")
        server.emit("thread/started", buildJsonObject { put("thread", server.thread("created-on-desktop")) })
        await { s -> s.chats.any { it.key.nativeId == "created-on-desktop" } }
    }

    @Test fun runtimeOwnershipChangeRefreshesSelectedChatAndConflict() = runBlocking<Unit> {
        server.owner = "new-owner"
        server.conflict = true
        server.notify("runtimes/changed", buildJsonObject { put("data", server.runtimes()) })
        await { it.selected?.runtimeId == "new-owner" && it.selected?.conflict == true }
    }

    @Test fun approvalUsesOriginalChatAndNativeResolutionReconcilesJournal() = runBlocking<Unit> {
        val approval = server.approval()
        server.pending += approval
        server.emitRaw(approval)
        await { it.approvals.isNotEmpty() }
        repo.respond(repo.state.value.approvals.single(), buildJsonObject { put("decision", "accept") }).join()
        val command = db.dao().uncertain("computer").single { it.method == "approvals/respond" }
        assertEquals(otherKey, command.chatKey)
        server.statuses[command.id] = "accepted"
        server.pending.clear()
        server.emit("serverRequest/resolved", buildJsonObject { put("requestId", "approval-1") })
        await { it.approvals.isEmpty() && it.commands.none { c -> c.id == command.id } }
    }

    @Test fun definiteApprovalFailureRestoresOnlyAuthoritativePendingRequest() = runBlocking<Unit> {
        val approval = server.approval()
        server.pending += approval
        server.emitRaw(approval)
        await { it.approvals.isNotEmpty() }
        server.intercept = { method, _, _ -> if (method == "approvals/respond") throw WireException("invalid_params", "Rejected"); null }
        repo.respond(repo.state.value.approvals.single(), buildJsonObject { put("decision", "accept") }).join()
        assertFalse(repo.state.value.approvals.single().submitted)
    }

    @Test fun rejectedApprovalWaitsForAuthoritativeSyncThenAllowsExplicitRetryWithExactId() = runBlocking<Unit> {
        val id = buildJsonObject { put("connection", "cli-generation"); put("id", 19) }
        val event = server.approval(id)
        server.pending += event
        server.emitRaw(event)
        val approval = await { it.approvals.isNotEmpty() }.approvals.single()
        val entered = CompletableDeferred<Unit>(); val unblock = CompletableDeferred<Unit>()
        server.intercept = { method, _, _ ->
            if (method == "approvals/respond") throw WireException("rejected", "private-native-diagnostic")
            if (method == "sync") { entered.complete(Unit); unblock.await() }
            null
        }
        val response = buildJsonObject { put("decision", "decline") }
        val rejected = repo.respond(approval, response)
        entered.await()
        try {
            assertTrue(repo.state.value.approvals.single().submitted)
            repo.respond(approval, response).join()
            assertEquals(1, server.calls.count { it.method == "approvals/respond" })
        } finally { unblock.complete(Unit); rejected.join() }
        assertFalse(repo.state.value.approvals.single().submitted)
        assertTrue(repo.state.value.submittingApprovalKeys.isEmpty())
        assertTrue(db.dao().uncertain("computer").isEmpty())
        assertFalse(repo.state.value.error.orEmpty().contains("private-native-diagnostic"))
        server.intercept = { _, _, _ -> null }
        repo.respond(repo.state.value.approvals.single(), response).join()
        val attempts = server.calls.filter { it.method == "approvals/respond" }
        assertEquals(2, attempts.size)
        assertTrue(attempts.all { it.params["requestId"] == id && it.params.string("runtimeId") == "runtime" && it.params.obj("response") == response })
        assertNotEquals(attempts[0].commandId, attempts[1].commandId)
        assertEquals(otherKey, db.dao().uncertain("computer").single().chatKey)
    }

    @Test fun failedRejectedApprovalSyncRecoversOnCheckWithoutSendingAgain() = runBlocking<Unit> {
        val event = server.approval()
        server.pending += event; server.emitRaw(event)
        val approval = await { it.approvals.isNotEmpty() }.approvals.single()
        server.intercept = { method, _, _ ->
            when (method) {
                "approvals/respond" -> throw WireException("rejected", "private rejection")
                "sync" -> throw WireException("timeout", "private sync failure")
            }
            null
        }
        repo.respond(approval, buildJsonObject { put("decision", "decline") }).join()
        assertTrue("A failed sync is not evidence that the request is still actionable", repo.state.value.approvals.single().submitted)
        server.intercept = { _, _, _ -> null }
        repo.checkCommands().join()
        assertFalse("An authoritative still-pending request must recover after definite rejection", repo.state.value.approvals.single().submitted)
        assertEquals(1, server.calls.count { it.method == "approvals/respond" })
    }

    @Test fun ledgerRejectedApprovalRefreshesPendingCardWithoutMutationReplay() = runBlocking<Unit> {
        val event = server.approval()
        server.pending += event; server.emitRaw(event)
        val approval = await { it.approvals.isNotEmpty() }.approvals.single()
        repo.respond(approval, buildJsonObject { put("decision", "decline") }).join()
        val command = db.dao().uncertain("computer").single()
        assertTrue(repo.state.value.approvals.single().submitted)
        server.statuses[command.id] = "rejected"
        repo.checkCommands().join()
        assertFalse(repo.state.value.approvals.single().submitted)
        assertTrue(db.dao().uncertain("computer").isEmpty())
        assertEquals(1, server.calls.count { it.method == "approvals/respond" })
    }

    @Test fun nativeResolutionDuringRejectedSyncCannotResurrectOldPendingSnapshot() = runBlocking<Unit> {
        val id = buildJsonObject { put("connection", "cli-generation"); put("id", 23) }
        val event = server.approval(id)
        server.pending += event; server.emitRaw(event)
        val approval = await { it.approvals.isNotEmpty() }.approvals.single()
        val entered = CompletableDeferred<Unit>(); val unblock = CompletableDeferred<Unit>()
        val secondSync = CompletableDeferred<Unit>(); val syncCount = AtomicInteger()
        server.intercept = { method, _, _ ->
            if (method == "approvals/respond") throw WireException("rejected", "private rejection")
            if (method == "sync") {
                if (syncCount.incrementAndGet() > 1) {
                    secondSync.complete(Unit)
                    throw WireException("timeout", "Second refresh cannot repair the first snapshot")
                }
                val snapshot = server.syncSnapshot()
                entered.complete(Unit); unblock.await(); snapshot
            } else null
        }
        val rejected = repo.respond(approval, buildJsonObject { put("decision", "decline") })
        entered.await()
        try {
            server.pending.clear()
            server.emit("serverRequest/resolved", buildJsonObject { put("requestId", id) })
            await { it.approvals.isEmpty() }
            withTimeout(5_000) { secondSync.await() }
        } finally { unblock.complete(Unit); rejected.join() }
        assertTrue("A delayed authoritative snapshot must not undo a newer native resolution", repo.state.value.approvals.isEmpty())
        assertEquals(1, server.calls.count { it.method == "approvals/respond" })
        val attempts = server.attempts.get()
        val nextId = buildJsonObject { put("connection", "cli-generation"); put("id", 24) }
        val nextRequest = server.approval(nextId)
        server.pending += nextRequest
        server.emitRaw(nextRequest)
        await { it.approvals.any { a -> a.requestId == nextId } }
        assertEquals("A stale sync cursor must not force reconnect on the next contiguous event", attempts, server.attempts.get())
        // The cursor is persisted after the approval reaches state; wait for the write, not a fixed instant.
        withTimeout(5_000) { while (db.dao().cursors("computer").singleOrNull { it.runtimeId == "runtime" }?.sequence != 3L) delay(10) }
        assertEquals(3L, db.dao().cursors("computer").single { it.runtimeId == "runtime" }.sequence)
    }

    @Test fun skippedEventsResyncInBandWithoutReconnecting() = runBlocking<Unit> {
        await { it.online }
        val connections = server.links.size
        val syncsBefore = server.calls.count { it.method == "sync" }
        // The helper dropped two events for this slow subscriber; the journal still has them.
        val replay = mutableListOf<JsonObject>()
        val startCursor = server.cursor().number("sequence")
        val skipped = (1..2).map { offset -> buildJsonObject {
            put("runtimeId", "runtime")
            put("cursor", buildJsonObject { put("epoch", "epoch"); put("sequence", startCursor + offset) })
            put("event", buildJsonObject { put("method", "turn/started"); put("params", buildJsonObject { put("threadId", "t"); put("turn", buildJsonObject { put("id", "replayed-$offset") }) }) })
        } }
        replay += skipped
        server.intercept = { method, _, _ ->
            if (method == "sync") JsonObject(server.syncSnapshot() + ("events" to JsonArray(replay))) else null
        }
        server.moveEpoch("epoch", (startCursor + 2).toInt())
        server.emit("turn/completed", buildJsonObject { put("threadId", "t"); put("turn", buildJsonObject { put("id", "live"); put("status", "completed") }) })
        server.notify("pocket/resync", buildJsonObject { put("reason", "event_backlog") })
        until { server.calls.count { it.method == "sync" } > syncsBefore }
        withTimeout(5_000) { while ((db.dao().cursors("computer").singleOrNull { c -> c.runtimeId == "runtime" }?.sequence ?: 0) < startCursor + 2) delay(10) }
        assertEquals("Skipped events must not tear down a healthy tunnel", connections, server.links.size)
        assertTrue(repo.state.value.online)
    }

    @Test fun delayedRejectionSnapshotKeepsNewPendingApprovalBeforeAnyFurtherEvent() = runBlocking<Unit> {
        val original = server.approval()
        server.pending += original; server.emitRaw(original)
        val approval = await { it.approvals.isNotEmpty() }.approvals.single()
        val entered = CompletableDeferred<Unit>(); val unblock = CompletableDeferred<Unit>()
        server.intercept = { method, _, _ ->
            if (method == "approvals/respond") throw WireException("rejected", "private rejection")
            if (method == "sync") {
                val snapshot = server.syncSnapshot()
                entered.complete(Unit); unblock.await(); snapshot
            } else null
        }
        val rejected = repo.respond(approval, buildJsonObject { put("decision", "decline") })
        entered.await()
        val nextId = JsonPrimitive("new-pending")
        try {
            val next = server.approval(nextId)
            server.pending += next; server.emitRaw(next)
            await { it.approvals.any { a -> a.requestId == nextId } }
        } finally { unblock.complete(Unit); rejected.join() }
        assertTrue("A live pending request newer than the snapshot must remain visible", repo.state.value.approvals.any { it.requestId == nextId })
        assertEquals(2L, db.dao().cursors("computer").single { it.runtimeId == "runtime" }.sequence)
    }

    @Test fun snapshotWithCapturedEmptyJournalCannotClearLaterUnknownSubmission() = runBlocking<Unit> {
        val original = server.approval()
        val nextId = JsonPrimitive("concurrent-unknown")
        val next = server.approval(nextId)
        server.pending += original; server.pending += next
        server.emitRaw(original); server.emitRaw(next)
        await { it.approvals.size == 2 }
        val queryCaptured = CompletableDeferred<Unit>(); val unblockQuery = CompletableDeferred<Unit>()
        val captureNext = java.util.concurrent.atomic.AtomicBoolean()
        val originalDao = db.dao()
        val pausingDao = object : PocketDao by originalDao {
            override suspend fun uncertain(computer: String): List<CommandEntity> {
                val captured = originalDao.uncertain(computer)
                if (captureNext.compareAndSet(true, false)) { queryCaptured.complete(Unit); unblockQuery.await() }
                return captured
            }
        }
        PocketRepository::class.java.getDeclaredField("dao").apply { isAccessible = true }.set(repo, pausingDao)
        server.intercept = { method, params, commandId ->
            if (method == "approvals/respond") {
                if (params["requestId"] == nextId) {
                    server.statuses[commandId!!] = "unknown"
                    throw UnknownOutcomeException(commandId)
                }
                throw WireException("rejected", "private rejection")
            }
            if (method == "sync") captureNext.set(true)
            null
        }
        val rejected = repo.respond(repo.state.value.approvals.single { it.requestId == original["id"] }, buildJsonObject { put("decision", "decline") })
        withTimeout(5_000) { queryCaptured.await() }
        val unknown = repo.state.value.approvals.single { it.requestId == nextId }
        try {
            repo.respond(unknown, buildJsonObject { put("decision", "decline") }).join()
            assertTrue(repo.state.value.approvals.single { it.requestId == nextId }.submitted)
            assertTrue(repo.state.value.submittingApprovalKeys.none { it == unknown.key })
        } finally { unblockQuery.complete(Unit); rejected.join() }
        assertTrue("The old empty journal result must not erase the completed Unknown submission guard", repo.state.value.approvals.single { it.requestId == nextId }.submitted)
        repo.respond(unknown, buildJsonObject { put("decision", "decline") }).join()
        assertEquals(1, server.calls.count { it.method == "approvals/respond" && it.params["requestId"] == nextId })
        assertEquals("unknown", db.dao().uncertain("computer").single().status)
    }

    @Test fun authoritativeNewEpochStartsAtLowerSequenceAndOldOutstandingSyncCannotRestoreOldEpoch() = runBlocking<Unit> {
        val original = server.approval()
        server.pending += original; server.emitRaw(original)
        val approval = await { it.approvals.isNotEmpty() }.approvals.single()
        val firstEntered = CompletableDeferred<Unit>(); val firstRelease = CompletableDeferred<Unit>()
        val syncCount = AtomicInteger()
        server.intercept = { method, _, _ ->
            if (method == "approvals/respond") throw WireException("rejected", "private rejection")
            if (method == "sync" && syncCount.incrementAndGet() == 1) {
                val snapshot = JsonObject(server.syncSnapshot() + ("events" to buildJsonArray {
                    add(buildJsonObject {
                        put("runtimeId", "runtime")
                        put("cursor", buildJsonObject { put("epoch", "epoch"); put("sequence", 2) })
                        put("event", server.approval(JsonPrimitive("old-replay")))
                    })
                }))
                firstEntered.complete(Unit); firstRelease.await(); snapshot
            } else null
        }
        val rejected = repo.respond(approval, buildJsonObject { put("decision", "decline") })
        firstEntered.await()
        try {
            server.moveEpoch("replacement-epoch", 0)
            repo.checkCommands().join()
            val baseline = db.dao().cursors("computer").single { it.runtimeId == "runtime" }
            assertEquals("replacement-epoch", baseline.epoch)
            assertEquals(0L, baseline.sequence)
        } finally { firstRelease.complete(Unit); rejected.join() }
        assertEquals("replacement-epoch", db.dao().cursors("computer").single { it.runtimeId == "runtime" }.epoch)
        val attempts = server.attempts.get()
        val nextId = JsonPrimitive("new-epoch-request")
        val next = server.approval(nextId)
        server.pending += next; server.emitRaw(next)
        await { it.approvals.any { a -> a.requestId == nextId } }
        assertEquals(attempts, server.attempts.get())
        // UI delivery and Room transaction commit are asynchronous. Wait for the durable
        // cursor, then assert its exact epoch/sequence instead of racing the commit.
        withTimeout(5_000) { while (db.dao().cursors("computer").single { it.runtimeId == "runtime" }.sequence != 1L) delay(10) }
        assertEquals(1L, db.dao().cursors("computer").single { it.runtimeId == "runtime" }.sequence)
        assertEquals("replacement-epoch", db.dao().cursors("computer").single { it.runtimeId == "runtime" }.epoch)
    }

    @Test fun oldRejectedSnapshotCannotAuthorizeClearingNewUnknownAttemptOfSameRequest() = runBlocking<Unit> {
        val event = server.approval()
        server.pending += event; server.emitRaw(event)
        val approval = await { it.approvals.isNotEmpty() }.approvals.single()
        val queryCaptured = CompletableDeferred<Unit>(); val unblockQuery = CompletableDeferred<Unit>()
        val captureNext = java.util.concurrent.atomic.AtomicBoolean()
        val originalDao = db.dao()
        val pausingDao = object : PocketDao by originalDao {
            override suspend fun uncertain(computer: String): List<CommandEntity> {
                val captured = originalDao.uncertain(computer)
                if (captureNext.compareAndSet(true, false) && captured.isEmpty()) { queryCaptured.complete(Unit); unblockQuery.await() }
                return captured
            }
        }
        PocketRepository::class.java.getDeclaredField("dao").apply { isAccessible = true }.set(repo, pausingDao)
        val firstEntered = CompletableDeferred<Unit>(); val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>(); val secondRelease = CompletableDeferred<Unit>()
        val syncCount = AtomicInteger()
        val mutationCount = AtomicInteger()
        server.intercept = { method, _, commandId ->
            if (method == "sync") {
                val snapshot = server.syncSnapshot()
                when (syncCount.incrementAndGet()) {
                    1 -> { firstEntered.complete(Unit); firstRelease.await() }
                    2 -> { secondEntered.complete(Unit); secondRelease.await(); captureNext.set(true) }
                }
                snapshot
            } else {
            if (method == "approvals/respond") {
                if (mutationCount.incrementAndGet() == 1) throw WireException("rejected", "private rejection")
                server.statuses[commandId!!] = "unknown"
                throw UnknownOutcomeException(commandId)
            }
            null
            }
        }
        val firstResponse = repo.respond(approval, buildJsonObject { put("decision", "decline") })
        withTimeout(5_000) { firstEntered.await() }
        val oldCheck = repo.checkCommands()
        withTimeout(5_000) { secondEntered.await() }
        firstRelease.complete(Unit); firstResponse.join()
        assertFalse(repo.state.value.approvals.single().submitted)
        secondRelease.complete(Unit)
        withTimeout(5_000) { queryCaptured.await() }
        try {
            repo.respond(approval, buildJsonObject { put("decision", "decline") }).join()
            assertTrue(repo.state.value.approvals.single().submitted)
        } finally { unblockQuery.complete(Unit); oldCheck.join() }
        assertTrue("Only rejection of the current attempt can clear its submitted evidence", repo.state.value.approvals.single().submitted)
        assertEquals("unknown", db.dao().uncertain("computer").single().status)
        repo.respond(approval, buildJsonObject { put("decision", "decline") }).join()
        assertEquals(2, server.calls.count { it.method == "approvals/respond" })
    }

    @Test fun delayedSyncFromReplacedConnectionCannotApplyPendingOrCursors() = runBlocking<Unit> {
        val original = server.approval()
        server.pending += original; server.emitRaw(original)
        val approval = await { it.approvals.isNotEmpty() }.approvals.single()
        val firstEntered = CompletableDeferred<Unit>(); val firstRelease = CompletableDeferred<Unit>()
        val syncCount = AtomicInteger()
        server.intercept = { method, _, _ ->
            if (method == "approvals/respond") throw WireException("rejected", "private rejection")
            if (method == "sync" && syncCount.incrementAndGet() == 1) {
                val snapshot = server.syncSnapshot()
                firstEntered.complete(Unit); firstRelease.await(); snapshot
            } else null
        }
        val rejected = repo.respond(approval, buildJsonObject { put("decision", "decline") })
        firstEntered.await()
        try {
            repo.setForeground(false)
            val attempts = server.attempts.get()
            server.pending.clear(); server.moveEpoch("replacement-connection", 0)
            repo.setForeground(true)
            until { server.attempts.get() > attempts }
            await { it.online }
        } finally { firstRelease.complete(Unit); rejected.join() }
        assertTrue(repo.state.value.approvals.isEmpty())
        assertEquals("replacement-connection", db.dao().cursors("computer").single { it.runtimeId == "runtime" }.epoch)
    }

    @Test fun unknownApprovalRemainsGuardedAfterRepeatedNativeRequestAndHistoryInspection() = runBlocking<Unit> {
        val id = buildJsonObject { put("connection", "cli-generation"); put("id", 31) }
        val event = server.approval(id)
        server.pending += event; server.emitRaw(event)
        val approval = await { it.approvals.isNotEmpty() }.approvals.single()
        server.intercept = { method, _, commandId ->
            if (method == "approvals/respond") {
                server.statuses[commandId!!] = "unknown"
                throw UnknownOutcomeException(commandId)
            }
            null
        }
        repo.respond(approval, buildJsonObject { put("decision", "decline") }).join()
        val command = db.dao().uncertain("computer").single()
        assertEquals("unknown", command.status)
        server.emitRaw(event)
        // A following resolution for another full identifier confirms notification processing.
        val otherId = buildJsonObject { put("connection", "other-generation"); put("id", 31) }
        server.emitRaw(server.approval(otherId))
        await { it.approvals.any { a -> a.requestId == otherId } }
        assertTrue("Repeated request notifications cannot unblock an unknown submission", repo.state.value.approvals.single { it.requestId == id }.submitted)
        assertFalse(repo.state.value.approvals.single { it.requestId == otherId }.submitted)
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        repo.inspectUnknownHistory().join()
        assertTrue(repo.state.value.approvals.single { it.requestId == id }.submitted)
        repo.respond(approval, buildJsonObject { put("decision", "decline") }).join()
        assertEquals(1, server.calls.count { it.method == "approvals/respond" })
        assertEquals("unknown", db.dao().uncertain("computer").single().status)
    }

    @Test fun draftIsCapturedBeforeSwitchEvenWhenPersistenceIsDelayed() = runBlocking<Unit> {
        val field = PocketRepository::class.java.getDeclaredField("draftLock").apply { isAccessible = true }
        val lock = field.get(repo) as Mutex
        lock.lock()
        try {
            repo.editDraft("未发送的中文草稿")
            repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        } finally { lock.unlock() }
        until { runBlocking { db.dao().draft(key)?.text == "未发送的中文草稿" } }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertEquals("未发送的中文草稿", repo.state.value.draft?.text)
    }

    @Test fun createTransfersPendingSharedAttachmentsWithSameLocalIdentity() = runBlocking<Unit> {
        val attachment = image()
        update { it.copy(selected = null, sharedAttachments = listOf(attachment)) }
        repo.create(repo.state.value.runtimes.first(), "D:/fixture").join()
        assertEquals(listOf(attachment), repo.state.value.attachments)
        assertTrue(repo.state.value.sharedAttachments.isEmpty())
        assertTrue(db.dao().draft(repo.state.value.selected!!.key)!!.attachments.contains(attachment.id))
    }

    @Test fun stopBypassesBlockedHistoryReadAndDeduplicates() = runBlocking<Unit> {
        server.emit("turn/started", buildJsonObject { put("threadId", "t"); put("turn", buildJsonObject { put("id", "running") }) })
        await { it.timeline.activeTurn == "running" }
        val entered = CompletableDeferred<Unit>(); val unblock = CompletableDeferred<Unit>(); val interrupted = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/read") { entered.complete(Unit); unblock.await() }
            if (method == "codex/request" && params.string("method") == "turn/interrupt") { interrupted.complete(Unit); unblock.await() }
            null
        }
        val reading = repo.select(repo.state.value.selected!!)
        entered.await()
        try {
            repo.stop(); repo.stop()
            assertTrue("Urgent interrupt was blocked by history loading", withTimeoutOrNull(1_000) { interrupted.await(); true } == true)
        } finally { unblock.complete(Unit); reading.join() }
        until { !repo.state.value.stopping }
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/interrupt" })
    }

    @Test fun approvalBypassesBlockedAttachmentUpload() = runBlocking<Unit> {
        val approval = server.approval(); server.pending += approval; server.emitRaw(approval)
        await { it.approvals.isNotEmpty() }
        val attachment = image()
        repo.attach(listOf(Uri.fromFile(repo.attachmentFile(attachment)))).join()
        repo.editDraft("message with image")
        val entered = CompletableDeferred<Unit>(); val unblock = CompletableDeferred<Unit>(); val responded = CompletableDeferred<Unit>()
        server.intercept = { method, _, _ ->
            if (method == "attachment/upload") { entered.complete(Unit); unblock.await() }
            if (method == "approvals/respond") responded.complete(Unit)
            null
        }
        val sending = repo.send()
        entered.await()
        try {
            repo.respond(repo.state.value.approvals.single(), buildJsonObject { put("decision", "accept") })
            assertTrue("Approval was blocked by upload", withTimeoutOrNull(1_000) { responded.await(); true } == true)
        } finally { unblock.complete(Unit); sending.join() }
    }

    @Test fun networkAvailabilityWakesReconnectBackoff() = runBlocking<Unit> {
        server.failConnections = true
        val baseline = server.attempts.get()
        server.links.last().close()
        // Allow the exponential 1s/2s/4s backoff plus jitter to establish itself;
        // the restoration assertion below still requires a wake within one second.
        withTimeout(12_000) { while (server.attempts.get() < baseline + 3) delay(10) }
        server.failConnections = false
        network.emit(Unit)
        assertTrue("Network restore slept through backoff", withTimeoutOrNull(1_000) { repo.state.first { it.online }; true } == true)
    }

    @Test fun repeatedAvailabilityDoesNotTearDownHealthyForegroundChat() = runBlocking<Unit> {
        val connections = server.links.size
        repeat(3) { network.emit(Unit); delay(100) }
        assertEquals("A validated network callback must not disconnect a healthy chat", connections, server.links.size)
        assertTrue(repo.state.value.online)
    }

    @Test fun reconnectBecomesUsableBeforeOptionalModelCatalogReturns() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "model/list") { entered.complete(Unit); unblock.await() }
            null
        }
        server.links.last().close()
        network.emit(Unit)
        withTimeout(5_000) { entered.await() }
        try { assertTrue("Optional catalogs must not keep the synchronized chat offline", repo.state.value.online) }
        finally { unblock.complete(Unit) }
    }

    @Test fun modelCatalogFailureIsDistinctAndRetryRecoversWithoutDisconnect() = runBlocking<Unit> {
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "model/list") throw WireException("timeout", "fixture")
            null
        }
        call("refreshModels").join()
        assertEquals(ModelCatalogState.Failed, repo.state.value.modelCatalogState)
        assertTrue(repo.state.value.online)
        assertNotNull(repo.state.value.modelCatalogError)
        server.intercept = { _, _, _ -> null }
        call("refreshModels").join()
        assertEquals(ModelCatalogState.Ready, repo.state.value.modelCatalogState)
        assertNull(repo.state.value.modelCatalogError)
        assertTrue(repo.state.value.models.isNotEmpty())
    }

    @Test fun emptyAndUnsupportedModelsHaveDifferentStates() = runBlocking<Unit> {
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "model/list") buildJsonObject { put("data", JsonArray(emptyList())) } else null
        }
        call("refreshModels").join()
        assertEquals(ModelCatalogState.Empty, repo.state.value.modelCatalogState)
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "model/list") throw WireException("unsupported", "fixture")
            null
        }
        call("refreshModels").join()
        assertEquals(ModelCatalogState.Unsupported, repo.state.value.modelCatalogState)
        assertTrue(repo.state.value.online)
    }

    @Test fun supersededUnsupportedCatalogCannotDisableNewerSuccessfulCatalog() = runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>();val reads=AtomicInteger()
        server.intercept={method,params,_->
            if(method=="codex/request"&&params.string("method")=="model/list"&&reads.incrementAndGet()==1){
                entered.complete(Unit);release.await();throw WireException("unsupported","fixture")
            };null
        }
        val older=repo.refreshModels();entered.await()
        repo.refreshModels().join()
        release.complete(Unit);older.join()
        assertEquals(ModelCatalogState.Ready,repo.state.value.modelCatalogState)
        assertTrue(repo.state.value.supports("model/list"))
        repo.refreshModels().join();assertEquals(3,reads.get())
    }

    @Test fun catalogCompletionCannotReplaceAnotherSelection() = runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val reads=AtomicInteger()
        server.intercept={method,params,_->
            if(method=="codex/request"&&params.string("method")=="model/list"&&reads.incrementAndGet()==1){
                entered.complete(Unit);release.await();buildJsonObject{put("data",JsonArray(emptyList()))}
            }else null
        }
        val older=repo.refreshModels();entered.await()
        repo.select(repo.state.value.chats.first{it.key==otherKey}).join()
        release.complete(Unit);older.join()
        assertEquals(otherKey,repo.state.value.selected?.key)
        assertEquals(ModelCatalogState.Ready,repo.state.value.modelCatalogState)
        assertTrue(repo.state.value.models.isNotEmpty())
    }

    @Test fun missingExplicitModelDoesNotBorrowDefaultNameOrCapabilities() = runBlocking<Unit> {
        val chosen=repo.state.value.models.first()
        repo.chooseModel(chosen,null)
        server.intercept={method,params,_->
            if(method=="codex/request"&&params.string("method")=="model/list") buildJsonObject {put("data",buildJsonArray{
                add(buildJsonObject{put("id","replacement");put("model","replacement");put("displayName","Replacement");put("isDefault",true);put("inputModalities",buildJsonArray{add("image")})})
            })}else null
        }
        repo.refreshModels().join()
        assertNull(repo.state.value.currentModel)
        assertEquals(chosen.model,repo.state.value.model)
        repo.editDraft("model identity fixture");repo.send().join()
        assertEquals(chosen.model,server.calls.last{it.nativeMethod=="turn/start"}.params.obj("params").string("model"))
    }

    @Test fun offlineApprovalsStayVisibleButCannotBeSubmittedUntilResync() = runBlocking<Unit> {
        val event=server.approval();server.pending+=event;server.emitRaw(event)
        val approval=await{it.approvals.isNotEmpty()}.approvals.single()
        repo.setForeground(false)
        await{!it.online}
        assertFalse(repo.state.value.approvalsFresh)
        assertEquals(approval.key,repo.state.value.approvals.single().key)
        val chat=repo.state.value.chats.first{it.key==otherKey}
        assertEquals("pending",dev.codexpocket.app.ui.homeChatStatus(repo.state.value,chat))
        repo.respond(approval,buildJsonObject{put("decision","decline")}).join()
        assertFalse(server.calls.any{it.method=="approvals/respond"})
        server.pending.clear();repo.setForeground(true)
        await{it.online&&it.approvalsFresh}
        assertTrue(repo.state.value.approvals.isEmpty())
    }

    @Test fun invalidPairingDoesNotDisconnectAnExistingWorkingComputer() = runBlocking<Unit> {
        val connections=server.links.size
        repo.pair("invalid", "Phone").join()
        assertEquals(PairingPhase.Failed,repo.state.value.pairingPhase)
        assertTrue(repo.state.value.online)
        assertEquals(connections,server.links.size)
    }

    @Test fun revokedAuthorizationShowsPairingRecoveryWithoutDeletingLocalHistory() = runBlocking<Unit> {
        repo.editDraft("保留撤销后的草稿")
        repo.setForeground(false)
        server.intercept={method,_,_->if(method=="hello") throw WireException("unauthorized","fixture") else null}
        repo.setForeground(true)
        await{it.connectionPhase=="authorization_required"}
        assertFalse(repo.state.value.online)
        assertTrue(repo.state.value.paired)
        assertEquals("保留撤销后的草稿",repo.state.value.draft?.text)
        assertTrue(repo.state.value.chats.any{it.key==key})
        assertEquals("需要重新配对",app.stateLabel(repo.state.value.connectionPhase))
    }

    @Test fun nativeQueueReadsEveryPageAndRefreshesOnlyMatchingThread() = runBlocking<Unit> {
        assertEquals(true, repo.state.value.queueSupported)
        assertEquals(listOf("q1", "q2"), repo.state.value.nativeQueue.map { it.string("id") })
        assertEquals("client-q1", repo.state.value.nativeQueue.first().string("clientUserMessageId"))
        val initial = server.calls.count { it.nativeMethod == "thread/queue/list" }
        server.emit("thread/queue/changed", buildJsonObject { put("threadId", "other") })
        repo.devices().join()
        assertEquals(initial, server.calls.count { it.nativeMethod == "thread/queue/list" })
        server.queuePrefix = "changed"
        server.emit("thread/queue/changed", buildJsonObject { put("threadId", "t") })
        await { it.nativeQueue.firstOrNull()?.string("id") == "changed1" }
        assertEquals(listOf("changed1", "changed2"), repo.state.value.nativeQueue.map { it.string("id") })
        assertTrue(server.calls.filter { it.nativeMethod == "thread/queue/list" }.all { it.commandId == null && it.params.obj("params").keys.all { k -> k in setOf("threadId", "cursor", "limit") } })
    }

    @Test fun ordinaryInputDuringTurnUsesNativeQueueInsteadOfSteering() = runBlocking<Unit> {
        server.emit("turn/started", buildJsonObject { put("threadId", "t"); put("turn", buildJsonObject { put("id", "running") }) })
        await { it.timeline.activeTurn == "running" }
        repo.editDraft("do this next")
        repo.send().join()
        assertNull(repo.state.value.error)
        assertEquals(1, server.calls.count { it.nativeMethod == "thread/queue/add" })
        assertEquals(0, server.calls.count { it.nativeMethod == "turn/steer" })
        val queued = server.calls.single { it.nativeMethod == "thread/queue/add" }
        assertTrue(queued.params.obj("params").string("clientUserMessageId").isNotBlank())
        assertNotNull(queued.commandId)
    }

    @Test fun unsupportedQueueGracefullyDisablesAndDoesNotKeepOldRows() = runBlocking<Unit> {
        server.intercept = { method, params, _ -> if (method == "codex/request" && params.string("method") == "thread/queue/list") throw WireException("unsupported", "fixture"); null }
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        assertEquals(false, repo.state.value.queueSupported)
        assertTrue(repo.state.value.nativeQueue.isEmpty())
        assertNull(repo.state.value.error)
    }

    @Test fun lateQueueResponseCannotReplaceNewSelection() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val unblock = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/queue/list" && params.obj("params").string("threadId") == "t") { entered.complete(Unit); unblock.await() }
            null
        }
        server.emit("thread/queue/changed", buildJsonObject { put("threadId", "t") })
        entered.await()
        val next = repo.select(repo.state.value.chats.first { it.key == otherKey })
        try { await { it.selected?.key == otherKey && it.nativeQueue.isEmpty() && it.queueSupported == null } }
        finally { unblock.complete(Unit); next.join() }
        assertEquals(otherKey, repo.state.value.selected?.key)
        assertEquals("other", repo.state.value.nativeQueue.first().array("input").first().asObject().string("text"))
    }

    @Test fun createRenameAndFirstSendKeepNativeIdentity() = runBlocking<Unit> {
        repo.create(repo.state.value.runtimes.first(), "D:/fixture").join()
        assertNull(repo.state.value.error)
        val created = repo.state.value.selected!!
        assertEquals("new", created.key.nativeId)
        repo.rename("Phone chat").join()
        repo.editDraft("first turn")
        repo.send().join()
        assertNull(repo.state.value.error)
        assertEquals(created.key, repo.state.value.selected?.key)
        val mutations = server.calls.filter { it.nativeMethod in setOf("thread/start", "thread/name/set", "turn/start") }
        assertEquals(3, mutations.size)
        assertTrue(mutations.all { it.commandId != null })
        assertTrue(mutations.drop(1).all { it.params.obj("params").string("threadId") == "new" })
    }

    @Test fun unboundCommandJournalRoundTripsNullChatForCreateAndDeviceActions() = runBlocking<Unit> {
        db.dao().putCommand(CommandEntity("unbound", "computer", null, "thread/start", "submitted", 1))
        db.dao().markInterruptedUnknown()
        val command = db.dao().uncertain("computer").single { it.id == "unbound" }
        assertNull(command.chatKey)
        assertEquals("unknown", command.status)
    }

    @Test fun unboundUnknownCreateRequiresExplicitDirectoryInspectionBeforeAnotherCreate() = runBlocking<Unit> {
        db.dao().putCommand(CommandEntity("lost-create", "computer", null, "thread/start", "unknown", 1))
        server.statuses["lost-create"] = "not_found"
        await { it.unresolvedCommands.any { c -> c.id == "lost-create" } }
        repo.create(repo.state.value.runtimes.first(), "D:/fixture").join()
        assertEquals(0, server.calls.count { it.nativeMethod == "thread/start" })
        repo.inspectUnknownHistory().join()
        assertTrue(repo.state.value.unknownHistoryInspected)
        repo.resolveUnknownAfterInspection().join()
        assertEquals(0, server.calls.count { it.nativeMethod == "thread/start" })
        assertTrue(db.dao().uncertain("computer").isEmpty())
    }

    @Test fun lateStatusCannotUndoAcceptedOrExplicitlyReconciledOutcome() = runBlocking<Unit> {
        for (terminal in listOf("accepted", "reconciled")) {
            db.dao().putCommand(CommandEntity(terminal, "computer", key, "turn/start", "submitted", 1))
            db.dao().commandStatus(terminal, terminal)
            db.dao().commandStatus(terminal, "unknown")
            db.dao().commandStatus(terminal, "submitted")
        }
        assertTrue(db.dao().uncertain("computer").isEmpty())
    }

    @Test fun globalUnknownRevocationCanBeInspectedWithoutSelection() = runBlocking<Unit> {
        update { it.copy(selected = null) }
        db.dao().putCommand(CommandEntity("lost-revoke", "computer", null, "devices/revoke", "unknown", 1))
        server.statuses["lost-revoke"] = "unknown"
        await { it.unresolvedCommands.size == 1 }
        repo.inspectUnknownHistory().join()
        assertNull(repo.state.value.error)
        assertTrue(repo.state.value.unknownHistoryInspected)
        assertTrue(server.calls.any { it.method == "devices/list" })
        repo.resolveUnknownAfterInspection().join()
        assertTrue(db.dao().uncertain("computer").isEmpty())
        assertFalse(server.calls.any { it.method == "devices/revoke" })
    }

    @Test fun completedTurnIsNotResurrectedByLateSendReplyAndDraftEditsSurvive() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val unblock = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") { entered.complete(Unit); unblock.await() }
            null
        }
        repo.editDraft("sent once")
        val sending = repo.send()
        entered.await()
        server.emit("turn/started", buildJsonObject { put("threadId", "t"); put("turn", buildJsonObject { put("id", "sent") }) })
        server.emit("turn/completed", buildJsonObject { put("threadId", "t"); put("turn", buildJsonObject { put("id", "sent"); put("status", "completed") }) })
        try {
            await { "sent" in it.timeline.completedTurns }
            repo.editDraft("new unsent draft")
            repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        } finally { unblock.complete(Unit); sending.join() }
        assertEquals("new unsent draft", db.dao().draft(key)?.text)
        assertNull(repo.state.value.timeline.activeTurn)
    }

    @Test fun acceptedUnknownSendClearsOnlyItsOriginalAttachmentsAndKeepsNewDraft() = runBlocking<Unit> {
        repo.attach(listOf(Uri.fromFile(repo.attachmentFile(image())))).join()
        val original = repo.state.value.attachments.single()
        repo.editDraft("original")
        server.intercept = { method, params, id ->
            if (method == "codex/request" && params.string("method") == "turn/start") {
                server.statuses[id!!] = "unknown"
                throw UnknownOutcomeException(id)
            }
            null
        }
        repo.send().join()
        await { it.uncertainSend }
        val pending = repo.state.value.commands.single { it.method == "turn/start" }
        assertTrue(repo.attachmentFile(original).exists())
        repo.editDraft("new unsent text")
        repo.attach(listOf(Uri.fromFile(repo.attachmentFile(image())))).join()
        val additional = repo.state.value.attachments.single { it.id != original.id }
        server.statuses[pending.id] = "accepted"
        repo.checkCommands().join()
        await { !it.uncertainSend }
        assertEquals("new unsent text", repo.state.value.draft?.text)
        assertEquals(listOf(additional), repo.state.value.attachments)
        assertFalse(repo.attachmentFile(original).exists())
        assertTrue(repo.attachmentFile(additional).exists())
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
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

    @Test fun planImplementationSendsOnceAndPreservesOrdinaryDraftAndAttachments() = runBlocking<Unit> {
        val plan = completedProposal()
        repo.editDraft("保留我的中文草稿")
        val attachment = image()
        update { it.copy(attachments = listOf(attachment)) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") { entered.complete(Unit); release.await() }
            null
        }
        val first = repo.implementPlan(plan)
        entered.await()
        repo.implementPlan(plan).join()
        release.complete(Unit); first.join()
        val submissions = server.calls.filter { it.nativeMethod == "turn/start" }
        assertEquals(1, submissions.size)
        val payload = submissions.single().params.obj("params")
        assertEquals(1, payload.array("input").size)
        assertEquals(plan.prompt, payload.array("input").single().asObject().string("text"))
        assertEquals("default", payload.obj("collaborationMode").string("mode"))
        assertEquals("保留我的中文草稿", repo.state.value.draft?.text)
        assertEquals(listOf(attachment), repo.state.value.attachments)
        assertTrue(repo.attachmentFile(attachment).exists())
        assertTrue(plan.revision in repo.state.value.resolvedPlans)
        repo.implementPlan(plan).join()
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
    }

    @Test fun stalePlanAndUnknownSubmissionCannotBeReplayed() = runBlocking<Unit> {
        val plan = completedProposal()
        repo.implementPlan(plan.copy(content = "stale content")).join()
        assertEquals(0, server.calls.count { it.nativeMethod == "turn/start" })
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") throw java.io.IOException("Response lost")
            null
        }
        repo.implementPlan(plan).join()
        assertTrue(plan.revision in repo.state.value.resolvedPlans)
        repo.implementPlan(plan).join()
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
        assertTrue(db.dao().uncertain("computer").any { it.draftText.contains(plan.revision) })
    }

    @Test fun definitelyRejectedPlanCanBeRetriedWithoutConsumingDraft() = runBlocking<Unit> {
        val plan = completedProposal()
        repo.editDraft("keep")
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") throw WireException("rejected", "Fixture policy")
            null
        }
        repo.implementPlan(plan).join()
        assertFalse(plan.revision in repo.state.value.resolvedPlans)
        assertEquals("keep", repo.state.value.draft?.text)
    }

    @Test fun planFeedbackModeIsAcknowledgedWithoutLosingLaterLocalChoice() = runBlocking<Unit> {
        val plan = completedProposal()
        assertTrue(repo.beginPlanFeedback(plan))
        assertEquals("plan", repo.state.value.mode)
        repo.implementPlan(plan).join()
        assertEquals("default", repo.state.value.mode)
        assertFalse(NativeSettingsField.Mode in repo.state.value.pendingSettings)
    }

    @Test fun resyncInvalidatesPlanBeforeItsHistoryRefreshFinishes() = runBlocking<Unit> {
        val plan = completedProposal()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, _, _ -> if (method == "sync") { entered.complete(Unit); release.await() }; null }
        server.notify("pocket/resync", buildJsonObject {})
        withTimeout(5_000) { entered.await() }
        try {
            assertTrue(repo.state.value.online)
            assertNotEquals(ConversationLoadState.Ready, repo.state.value.conversationLoadState)
            repo.implementPlan(plan).join()
            assertEquals(0, server.calls.count { it.nativeMethod == "turn/start" })
        } finally { release.complete(Unit) }
    }

    @Test fun acknowledgedPlanUpdatesItsOriginalConversationAfterSelectionChanges() = runBlocking<Unit> {
        val plan = completedProposal()
        assertTrue(repo.beginPlanFeedback(plan))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") { entered.complete(Unit); release.await() }
            null
        }
        val job = repo.implementPlan(plan)
        withTimeout(5_000) { entered.await() }
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        release.complete(Unit); job.join()
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertEquals("default", repo.state.value.mode)
        assertFalse(NativeSettingsField.Mode in repo.state.value.pendingSettings)
    }

    @Test fun acceptedUnknownPlanAcknowledgesModeWithoutReplayingOrConsumingDraft() = runBlocking<Unit> {
        val plan = completedProposal()
        assertTrue(repo.beginPlanFeedback(plan))
        repo.editDraft("保留")
        server.intercept = { method, params, id ->
            if (method == "codex/request" && params.string("method") == "turn/start") {
                server.statuses[id!!] = "unknown"
                throw UnknownOutcomeException(id)
            }
            null
        }
        repo.implementPlan(plan).join()
        val command = db.dao().uncertain("computer").single { it.method == "turn/start" }
        server.statuses[command.id] = "accepted"
        repo.checkCommands().join()
        assertEquals("default", repo.state.value.mode)
        assertFalse(NativeSettingsField.Mode in repo.state.value.pendingSettings)
        assertEquals("保留", repo.state.value.draft?.text)
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
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
        private val capabilities = listOf("thread/start", "thread/read", "thread/resume", "thread/turns/list", "thread/queue/list", "thread/queue/add", "thread/queue/update", "thread/queue/delete", "thread/queue/reorder", "thread/name/set", "thread/archive", "turn/start", "turn/steer", "turn/interrupt", "model/list")
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
        fun runtimes() = buildJsonArray { add(buildJsonObject { put("id", owner); put("source", "desktop"); put("version", "fixture"); put("configStoreId", "store"); put("online", true); put("loadedThreadIds", JsonArray((rows.map { it.string("id") } + "new").map(::JsonPrimitive))); put("capabilities", JsonArray(capabilities.map(::JsonPrimitive))) }) }
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
