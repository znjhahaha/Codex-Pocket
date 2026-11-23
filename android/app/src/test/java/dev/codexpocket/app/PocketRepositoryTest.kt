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
    private var traceCacheQueries = false
    private var providerCatalog: ModelCatalogRequest? = null
    private val cacheQueries = CopyOnWriteArrayList<String>()

    @Before fun prepare() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java)
            .setQueryCallback({ sql, _ -> if (traceCacheQueries && sql.contains("items", ignoreCase = true)) cacheQueries.add(sql) },
                java.util.concurrent.Executor { it.run() }).build()
        server = BrokerFixture()
        val saved = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "fixture-token", "00".repeat(32)), "phone", "fixture-secret", "computer", "Fixture")
        storage = object : PairingStorage {
            override fun load() = saved
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(app, { server.connection() }, storage, db, network, modelCatalogOverride = { providerCatalog })
        await { it.initialized }
        repo.setForeground(true)
        try { await { it.online && it.chats.any { c -> c.key == key } } }
        catch (failure: TimeoutCancellationException) {
            throw AssertionError("Fixture connect timeout: state=${repo.state.value.connectionPhase}, online=${repo.state.value.online}, chats=${repo.state.value.chats.size}, attempts=${server.attempts.get()}, calls=${server.calls.map { it.method }}, diagnostics=${repo.connectionDiagnostics()}", failure)
        }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        try { await { it.nativeSettings.model.known && it.model == "model" } }
        catch (failure: TimeoutCancellationException) {
            val s=repo.state.value
            throw AssertionError("Fixture metadata timeout: online=${s.online}, selected=${s.selected?.key}, known=${s.nativeSettings.model.known}, model=${s.model}, load=${s.conversationLoadState}, calls=${server.calls.takeLast(16).map { it.method + ':' + it.nativeMethod }}",failure)
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

    @Test fun clearingTheCacheLeavesAnotherLocationsHistoryCursorsAndSnapshotsInPlace() = runBlocking<Unit> {
        val dao = db.dao()
        // "computer-2" begins with this computer's id: only a whole-id match may clear a row.
        val foreign = ThreadKey("computer-2", "store", "t"); val gone = ThreadKey("computer", "store", "no-longer-listed")
        val item = TimelineItem("turn", "item", "agentMessage", "kept", "completed", buildJsonObject {}, true)
        dao.putItems(listOf(ItemEntity.from(foreign, item, 0), ItemEntity.from(gone, item, 0)))
        dao.putCursor(CursorEntity("computer-2", "runtime", "epoch", 7)); dao.putCursor(CursorEntity("computer", "runtime-unused", "epoch", 3))
        dao.putSnapshot(SnapshotEntity(foreign, "epoch", 7)); dao.putSnapshot(SnapshotEntity(gone, "epoch", 3))
        repo.clearCache().join()
        assertTrue(dao.items(gone).isEmpty())
        assertNull(dao.snapshot(gone))
        assertTrue(dao.cursors("computer").none { it.runtimeId == "runtime-unused" })
        assertEquals("kept", dao.items(foreign).single().text)
        assertEquals(7L, dao.snapshot(foreign)!!.sequence)
        assertEquals(7L, dao.cursors("computer-2").single().sequence)
    }

    @Test fun persistedHistoryIsBrowsableWithoutAnOnlineNativeRuntime() = runBlocking<Unit> {
        val diskKey=ThreadKey("computer","store","disk-cli")
        update { it.copy(capabilities=it.capabilities+"threads/history/read",runtimes=emptyList()) }
        server.intercept={method,_,_->when(method){
            "threads/list"->buildJsonObject { put("data",buildJsonArray { add(buildJsonObject {
                put("id","disk-cli");put("runtimeId","history:store");put("configStoreId","store");put("source","cli");put("name","Saved CLI");put("readable",true)
            }) });put("nextCursor",JsonNull) }
            "threads/history/read"->buildJsonObject {
                put("thread",buildJsonObject {put("id","disk-cli");put("status",buildJsonObject {put("type","notLoaded")})})
                put("data",buildJsonArray {add(buildJsonObject {put("id","disk-turn");put("status","completed");put("items",buildJsonArray {add(buildJsonObject {put("id","disk-item");put("type","agentMessage");put("text","Saved answer")})})})})
                put("historyState","ready");put("nextCursor",JsonNull)
            }
            else->null
        }}
        repo.refresh().join()
        await { it.chats.any { c->c.key==diskKey } }
        val before=server.calls.size
        repo.select(repo.state.value.chats.first {it.key==diskKey}).join()
        await { it.conversationLoadState==ConversationLoadState.Ready&&it.timeline.items.values.any {i->i.text=="Saved answer"} }
        assertEquals("persisted",repo.state.value.conversationReadSource)
        assertFalse(repo.state.value.ownershipVerifiedFor())
        assertTrue(server.calls.drop(before).none {it.nativeMethod in setOf("thread/start","thread/resume","turn/start")})
    }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }

    @Test fun persistedHistoryRebuildsPagingAfterReaderRestartWithoutLosingDraftOrAnchor() = runBlocking<Unit> {
        val chat=repo.state.value.selected!!.copy(runtimeId="history:store")
        var epoch="first"
        update {it.copy(capabilities=it.capabilities+"threads/history/read")}
        server.intercept={method,params,_->if(method=="threads/history/read") {
            val cursor=params.string("cursor")
            if(cursor.isNotBlank()) check(cursor.startsWith("$epoch:")) {"Stale reader cursor"}
            val end=cursor.substringAfter(':',"12").toInt();val start=(end-params.number("limit").toInt()+1).coerceAtLeast(0)
            buildJsonObject {
                put("thread",server.thread("t"));put("historyEpoch",epoch)
                put("data",buildJsonArray {for(n in end downTo start) add(buildJsonObject {
                    put("id","turn-$n");put("status","completed");put("items",buildJsonArray {add(buildJsonObject {
                        put("id","i-$n");put("type","agentMessage");put("text","$epoch-$n")
                    })})
                })})
                put("nextCursor",if(start==0) JsonNull else JsonPrimitive("$epoch:${start-1}"))
            }
        }else null}
        repo.select(chat,true).join();repo.editDraft("保留草稿");repo.scroll(3,24,false,"turn-6:i-6")
        val draft=repo.state.value.draft
        epoch="second"
        val follow=repo.observeConversation(chat.key)
        try {
            await {it.timeline.items.values.lastOrNull()?.text=="second-12"}
            assertEquals(draft,repo.state.value.draft)
            assertTrue(repo.state.value.nextTurns!!.jsonPrimitive.content.startsWith("second:"))
            repo.older().join();assertTrue(repo.state.value.historyExhausted)
            assertEquals(13,repo.state.value.timeline.turns.size)
            assertNull(repo.state.value.conversationLoadError)
        }finally {follow.cancelAndJoin()}
    }

    @Test fun historyRecoveryDeduplicatesAndKeepsUnknownOperationForStatusCheck() = runBlocking<Unit> {
        update {it.copy(capabilities=it.capabilities+setOf("threads/history/read","threads/takeover","threads/takeover/status"))}
        val disk=repo.state.value.selected!!.copy(runtimeId="history:store")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        server.intercept={method,_,_->when(method){
            "threads/history/read"->buildJsonObject {put("thread",server.thread("t"));put("data",buildJsonArray {});put("nextCursor",JsonNull)}
            "threads/takeover/status"->buildJsonObject {put("phase",if(release.isCompleted) "blocked" else "absent");if(release.isCompleted) put("reason","resume_result_unknown")}
            "threads/takeover"->{entered.complete(Unit);release.await();buildJsonObject {put("phase","blocked");put("reason","resume_result_unknown");put("operationId","original")}}
            else->null
        }}
        repo.select(disk,true).join();repo.editDraft("保留原草稿")
        val first=repo.takeoverHistory();withTimeout(5000) {entered.await()}
        val second=repo.takeoverHistory();delay(100);release.complete(Unit);first.join();second.join()
        repo.takeoverHistory().join()
        assertEquals(1,server.calls.count {it.method=="threads/takeover"})
        assertEquals("保留原草稿",repo.state.value.draft!!.text)
        assertFalse(repo.state.value.actionStatus["access:${key.encoded}"]?.loading==true)
        assertTrue(server.calls.none {it.nativeMethod=="thread/resume"})
    }

    @Test fun retiredNativeHistoryReadsRetainedRecoveryWithoutStartingAnotherOperation() = runBlocking<Unit> {
        update {it.copy(capabilities=it.capabilities+setOf("threads/history/read","threads/takeover","threads/takeover/status"))}
        val disk=repo.state.value.selected!!.copy(runtimeId="retired-native")
        server.intercept={method,_,_->when(method){
            "threads/history/read"->buildJsonObject {put("thread",server.thread("t"));put("data",buildJsonArray {});put("nextCursor",JsonNull)}
            "threads/takeover/status"->buildJsonObject {put("phase","waiting_release");put("reason","native_start_unconfirmed");put("operationId","retained")}
            else->null
        }}
        repo.select(disk,true).join()
        val observer=repo.observeConversation(key)
        try {
            await {it.conversationRecovery[key]?.operationId=="retained"}
            assertTrue(server.calls.none {it.method=="threads/takeover"})
        } finally {observer.cancelAndJoin()}
    }

    @Test fun disconnectedRecoveryCanRequeueButUnknownAndTransientErrorsNeverReplay() = runBlocking<Unit> {
        update {it.copy(capabilities=it.capabilities+setOf("threads/history/read","threads/takeover","threads/takeover/status"))}
        val disk=repo.state.value.selected!!.copy(runtimeId="history:store")
        var queued=false
        var failRead=false
        server.intercept={method,_,_->when(method){
            "threads/history/read"->buildJsonObject {put("thread",server.thread("t"));put("data",buildJsonArray {});put("nextCursor",JsonNull)}
            "threads/takeover/status"->{if(failRead) throw WireException("runtime_unavailable","Unavailable")
                buildJsonObject {put("phase",if(queued) "queued" else "disconnected");put("reason","runtime_restarted")}}
            "threads/takeover"->{queued=true;buildJsonObject {put("phase","queued");put("operationId","new")}}
            else->null
        }}
        repo.select(disk,true).join();repo.editDraft("保留草稿")
        repo.takeoverHistory().join();repo.takeoverHistory().join()
        assertEquals(1,server.calls.count {it.method=="threads/takeover"})
        failRead=true;repo.takeoverHistory().join()
        assertNull(repo.state.value.error)
        assertNotNull(repo.state.value.actionStatus["access:${key.encoded}"]?.error)
        assertEquals("保留草稿",repo.state.value.draft?.text)
        assertEquals(1,server.calls.count {it.method=="threads/takeover"})
    }

    @Test fun existingDesktopHistoryAttachesToOriginalOwnerAndLoadsNativeControlsWithoutResume() = runBlocking<Unit> {
        update { it.copy(capabilities=it.capabilities+setOf("threads/control/read","threads/history/read")) }
        val disk=repo.state.value.selected!!.copy(runtimeId="history:store")
        server.intercept={method,params,_->when(method){
            "threads/control/read"->buildJsonObject {
                assertEquals("t",params.string("threadId"));put("runtimeId",server.owner)
                put("runtimes",JsonArray(server.runtimes().map {JsonObject(it.asObject()+ ("role" to buildJsonObject {put("kind","desktopFollower")}))}))
            }
            "threads/history/read"->buildJsonObject {put("thread",server.thread("t"));put("data",buildJsonArray {});put("nextCursor",JsonNull)}
            else->null
        }}
        val before=server.calls.size
        repo.select(disk,true).join()
        await { it.selected?.runtimeId==server.owner && it.nativeSettings.model.known && it.settingsFresh &&
            it.modelCatalogState==ModelCatalogState.Ready && it.conversationLoadState==ConversationLoadState.Ready }
        assertTrue(repo.state.value.settingsFresh)
        assertEquals("persisted",repo.state.value.conversationReadSource)
        assertTrue(server.calls.drop(before).none {it.nativeMethod in setOf("thread/resume","thread/start","turn/start") || it.commandId!=null})
    }

    @Test fun openHistoryAutomaticallyAttachesWhenOriginalRuntimeStartsLater() = runBlocking<Unit> {
        update {it.copy(capabilities=it.capabilities+setOf("threads/control/read","threads/history/read"))}
        val disk=repo.state.value.selected!!.copy(runtimeId="history:store",source="cli")
        val started=java.util.concurrent.atomic.AtomicBoolean(false)
        server.intercept={method,_,_->when(method){
            "threads/control/read"->buildJsonObject {
                put("runtimeId",if(started.get()) JsonPrimitive(server.owner) else JsonNull)
                put("runtimes",server.runtimes())
            }
            "threads/history/read"->buildJsonObject {put("thread",server.thread("t"));put("data",buildJsonArray {});put("nextCursor",JsonNull)}
            else->null
        }}
        repo.select(disk,true).join()
        await {it.selected?.runtimeId=="history:store"&&it.conversationLoadState==ConversationLoadState.Ready}
        val before=server.calls.size
        val observe=repo.observeConversation(disk.key)
        started.set(true)
        try {
            await {it.selected?.runtimeId==server.owner&&it.settingsFresh}
            assertTrue(server.calls.drop(before).none {it.nativeMethod in setOf("thread/start","thread/resume","turn/start")||it.commandId!=null})
        } finally {observe.cancelAndJoin()}
    }

    @Test fun nativeRestartReconnectsWhileOldHistoryReadIsLoadingAndPreservesDraft() = runBlocking<Unit> {
        val previous = repo.state.value.selected!!.copy(runtimeId="retired-desktop")
        update { it.copy(capabilities=it.capabilities+setOf("threads/control/read","threads/history/read"),
            selected=previous, conversationLoadState=ConversationLoadState.Loading, settingsFresh=false) }
        repo.editDraft("重连时保留中文草稿")
        repo.scroll(3, 24, false, "kept-anchor")
        val draft=repo.state.value.draft
        server.intercept={method,_,_->when(method) {
            "threads/control/read"->buildJsonObject {put("runtimeId",server.owner);put("runtimes",server.runtimes())}
            else->null
        }}
        val before=server.calls.size
        val observer=repo.observeConversation(key)
        try {
            await {it.selected?.runtimeId==server.owner && it.settingsFresh && it.conversationLoadState==ConversationLoadState.Ready}
            assertEquals(draft,repo.state.value.draft)
            assertTrue(server.calls.drop(before).none {it.commandId!=null || it.nativeMethod in setOf("thread/resume","thread/start","turn/start")})
        } finally {observer.cancelAndJoin()}
    }

    @Test fun helperUpgradeReconnectAttachesTheAlreadyOpenHistoryWithoutResume() = runBlocking<Unit> {
        val disk=repo.state.value.selected!!.copy(runtimeId="history:store")
        var upgraded=false
        var attached=false
        fun runtimes()=JsonArray(server.runtimes().map {JsonObject(it.asObject()+ ("role" to buildJsonObject {put("kind","desktopFollower")}))})
        update {it.copy(capabilities=it.capabilities+"threads/history/read")}
        server.intercept={method,_,_->when(method) {
            "hello"->if(upgraded) buildJsonObject {
                put("protocol",1);put("computerId","computer");put("computerName","Fixture");put("runtimes",runtimes())
                put("capabilities",JsonArray((repo.state.value.capabilities+setOf("threads/history/read","threads/control/read")).map(::JsonPrimitive)))
            }else null
            "threads/list"->if(!attached) buildJsonObject {put("data",buildJsonArray {add(JsonObject(server.row("t")+mapOf("runtimeId" to JsonPrimitive("history:store"),"source" to JsonPrimitive("desktop"),"configStoreId" to JsonPrimitive("store"))))});put("nextCursor",JsonNull)}else null
            "threads/history/read"->buildJsonObject {put("thread",server.thread("t"));put("data",buildJsonArray {});put("nextCursor",JsonNull)}
            "threads/control/read"->{attached=true;buildJsonObject {put("runtimeId",server.owner);put("runtimes",runtimes())}}
            else->null
        }}
        repo.select(disk,true).join()
        await {it.selected?.runtimeId=="history:store"&&it.conversationLoadState==ConversationLoadState.Ready}
        val before=server.calls.size
        upgraded=true
        server.links.last().close()
        network.emit(Unit)
        try { await {it.online&&it.selected?.runtimeId==server.owner&&it.settingsFresh&&it.nativeSettings.model.known} }
        catch(failure:TimeoutCancellationException) {throw AssertionError("Upgrade control: attached=$attached selected=${repo.state.value.selected?.runtimeId} online=${repo.state.value.online} fresh=${repo.state.value.settingsFresh} phase=${repo.state.value.connectionPhase} calls=${server.calls.drop(before).map {it.method+':'+it.nativeMethod}} diagnostics=${repo.connectionDiagnostics()}",failure)}
        assertTrue(attached)
        assertTrue(server.calls.drop(before).none {it.nativeMethod in setOf("thread/resume","thread/start","turn/start") || it.commandId!=null})
    }

    @Test fun helperRestartReattachesSelectedControlBeforeReadingPermissionsAndKeepsDraft() = runBlocking<Unit> {
        val capabilities = repo.state.value.capabilities + "threads/control/read"
        var attached = true
        var generation = "first"
        server.intercept = { method, params, _ -> when (method) {
            "hello" -> buildJsonObject {
                put("protocol", 1); put("computerId", "computer"); put("computerName", "Fixture")
                put("runtimes", server.runtimes()); put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
            }
            "threads/control/read" -> {
                assertEquals(key.nativeId, params.string("threadId"))
                attached = true
                buildJsonObject { put("runtimeId", server.owner); put("runtimes", server.runtimes()) }
            }
            "thread/permissions/read" -> {
                if (!attached) throw WireException("offline", "Follower subscription lost after helper restart")
                buildJsonObject {
                    put("runtimeId", server.owner); put("threadId", key.nativeId); put("status", "ready")
                    put("revision", generation); put("currentPresetId", "full"); put("currentLabel", "完全访问权限")
                    put("presets", buildJsonArray {})
                }
            }
            else -> null
        } }
        repo.refreshPermissions().join()
        assertEquals(JsonPrimitive("first"), repo.state.value.permissions.revision)
        repo.editDraft("保留草稿🙂")
        repo.scroll(3, 42, false, "kept-anchor")
        val draft = repo.state.value.draft
        val selected = repo.state.value.selected!!.key
        val before = server.calls.size
        attached = false; generation = "restarted"
        server.links.last().close(); network.emit(Unit)
        await { it.online && it.permissions.revision == JsonPrimitive("restarted") }
        assertTrue(attached)
        assertEquals(selected, repo.state.value.selected!!.key)
        assertEquals(draft, repo.state.value.draft)
        assertNull(repo.state.value.permissions.error)
        assertTrue(server.calls.drop(before).none { it.nativeMethod in setOf("thread/resume", "thread/start", "turn/start") || it.commandId != null })
    }

    @Test fun persistedLargeHistoryRefreshUsesTailPagesAndIncrementalCacheWithoutLosingOldItems() = runBlocking<Unit> {
        val chat = ChatEntity(ThreadKey("computer", "store", "large-external"), "history:store", "desktop", "Large history", "", "", 1, "", false)
        val revision = AtomicInteger(0)
        val latest = AtomicInteger(37)
        val pageLimits = CopyOnWriteArrayList<Int>()
        fun count(turn: Int) = when (turn) { 37 -> 316; 36 -> 84; in 0..35 -> 100; else -> 1 }
        fun nativeTurn(turn: Int) = buildJsonObject {
            put("id", "turn-$turn"); put("status", if (turn == latest.get()) "inProgress" else "completed")
            put("items", buildJsonArray { repeat(count(turn)) { item -> add(buildJsonObject {
                put("id", "item-$item"); put("type", if (item % 2 == 0) "commandExecution" else "agentMessage")
                if (item % 2 == 0) put("command", "echo recorded")
                put(if (item % 2 == 0) "aggregatedOutput" else "text", "turn-$turn/item-$item:" + "recorded output ".repeat(256) +
                    if (turn == latest.get() && item == count(turn) - 1) " revision-${revision.get()}" else "")
            }) } })
        }
        update { it.copy(capabilities = it.capabilities + "threads/history/read") }
        server.intercept = { method, params, _ -> if (method == "threads/history/read") {
            val limit = params.number("limit").toInt(); pageLimits.add(limit)
            val end = params.string("cursor").toIntOrNull() ?: latest.get()
            val start = (end - limit + 1).coerceAtLeast(0)
            // Exercise the serialization boundary, not shared in-memory fixture trees.
            pocketJson.parseToJsonElement(buildJsonObject {
                put("thread", buildJsonObject { put("id", chat.key.nativeId) })
                put("data", JsonArray((end downTo start).map(::nativeTurn)))
                put("nextCursor", if (start == 0) JsonNull else JsonPrimitive((start - 1).toString()))
            }.toString())
        } else null }
        repo.select(chat).join()
        withTimeout(60_000) { while (!repo.state.value.historyExhausted) repo.older().join() }
        assertEquals(4_000, repo.state.value.timeline.items.size)
        repo.editDraft("Keep my draft")
        repo.scroll(21, 42, false, "turn-0:item-20")
        val draft = repo.state.value.draft
        val oldest = repo.state.value.timeline.items.values.first()
        val unchangedTail = repo.state.value.timeline.items.values.first { it.turnId == "turn-37" }
        val before = server.calls.size
        pageLimits.clear(); cacheQueries.clear(); traceCacheQueries = true
        val follow = repo.observeConversation(chat.key)
        try {
            for (version in 1..3) {
                revision.set(version)
                withTimeout(30_000) { repo.state.first { it.timeline.items.values.last().text.endsWith("revision-$version") } }
            }
            assertTrue("Refresh must read the latest turn, not reparse the full first page: $pageLimits", pageLimits.all { it == 1 })
            assertTrue("Saving history must not materialize all cached text/raw again: $cacheQueries",
                cacheQueries.none { it.startsWith("SELECT * FROM items", ignoreCase = true) })
            assertTrue("Only changed rows should be written", cacheQueries.count { it.startsWith("INSERT", true) || it.startsWith("UPDATE", true) } <= 6)
            assertSame("Old pages remain shared, not reconstructed", oldest, repo.state.value.timeline.items.values.first())
            assertSame("Unchanged items in the refreshed turn must retain their text and raw trees", unchangedTail,
                repo.state.value.timeline.items[unchangedTail.key])
            assertEquals(draft, repo.state.value.draft)
            // Two turns arrive between polls. Read through to turn 37 so its final state is updated.
            latest.set(39); revision.set(4)
            withTimeout(30_000) { repo.state.first { it.timeline.turns["turn-39"] != null } }
            assertEquals(4_002, repo.state.value.timeline.items.size)
            assertEquals("completed", repo.state.value.timeline.turns.getValue("turn-37").status)
            assertEquals("completed", repo.state.value.timeline.turns.getValue("turn-38").status)
            assertEquals("inProgress", repo.state.value.timeline.turns.getValue("turn-39").status)
            assertEquals(draft, repo.state.value.draft)
            assertTrue(repo.state.value.historyExhausted)
            assertTrue(server.calls.drop(before).none { it.method == "codex/request" || it.commandId != null })
            // An authoritative revert removes the newer branch while retaining the older pages.
            latest.set(37); revision.set(5)
            withTimeout(30_000) { repo.state.first { it.timeline.items.size == 4_000 && it.timeline.items.values.last().text.endsWith("revision-5") } }
            assertEquals("inProgress", repo.state.value.timeline.turns.getValue("turn-37").status)
            assertFalse("Reverted turns must not survive in the reader", repo.state.value.timeline.turns.containsKey("turn-39"))
            assertEquals(draft, repo.state.value.draft)
            println("large-history: items=4000 latestTurnItems=316 refreshes=5 heapLimitMiB=${Runtime.getRuntime().maxMemory() / 1024 / 1024} textChars=${repo.state.value.timeline.items.values.sumOf { it.text.length.toLong() }}")
        } finally { follow.cancelAndJoin(); traceCacheQueries = false }
        val headers = db.dao().itemHeaders(chat.key)
        assertEquals(4_000, headers.size)
        assertEquals(4_000, headers.map { it.itemKey }.distinct().size)
        assertEquals((0L..3999L).toList(), headers.map { it.ordinal })
    }

    @Test fun persistedReaderDoesNotRequestRuntimeAccessOnReconnectOrMetadataRefresh() = runBlocking<Unit> {
        val chat=ChatEntity(ThreadKey("computer","store","external"),"history:store","desktop","External","","",1,"",false)
        update { it.copy(capabilities=it.capabilities+setOf("threads/history/read","thread/access/read")) }
        server.intercept={method,params,_->
            if(params.string("runtimeId").startsWith("history:")) throw WireException("runtime_offline","Runtime not available")
            if(method=="threads/history/read") buildJsonObject {
                put("thread",buildJsonObject {put("id","external")});put("data",JsonArray(emptyList()));put("nextCursor",JsonNull)
            } else null
        }
        repo.select(chat).join()
        val before=server.calls.size
        // The reconnect and foreground refresh paths invoke these independently of select().
        repo.refreshThreadAccess().join()
        repo.refreshPermissions().join()
        repo.refreshContext().join()
        repo.refreshModels().join()
        repo.refreshAgentNames().join()
        assertNull("A readable history thread has no native access endpoint",repo.state.value.error)
        assertTrue(server.calls.drop(before).none {it.params.string("runtimeId").startsWith("history:")})
        assertEquals(ConversationLoadState.Ready,repo.state.value.conversationLoadState)
        assertFalse(repo.state.value.ownershipVerifiedFor())
    }

    @Test fun persistedReadFailureRemainsVisibleAfterUnavailableMetadataIsSkipped() = runBlocking<Unit> {
        val chat=ChatEntity(ThreadKey("computer","store","external"),"history:store","desktop","External","","",1,"",false)
        update { it.copy(capabilities=it.capabilities+setOf("threads/history/read","thread/access/read")) }
        server.intercept={method,_,_->if(method=="threads/history/read") throw WireException("native_history_unavailable","Unreadable") else null}
        repo.select(chat).join()
        repo.refreshThreadAccess().join()
        assertEquals(ConversationLoadState.Failed,repo.state.value.conversationLoadState)
        assertFalse(repo.state.value.conversationLoadError.isNullOrBlank())
    }

    @Test fun persistedConversationFollowsExternalProgressWithoutTakingOwnership() = runBlocking<Unit> {
        val chat = ChatEntity(ThreadKey("computer", "store", "external"), "history:store", "desktop", "Project conversation", "", "D:/project", 1, "", false)
        val revision = AtomicInteger(0)
        update { it.copy(capabilities = it.capabilities + "threads/history/read") }
        server.intercept = { method, params, _ -> if (method == "threads/history/read") {
            assertEquals("external", params.string("threadId"))
            buildJsonObject {
                put("thread", buildJsonObject { put("id", "external") })
                put("data", buildJsonArray { add(buildJsonObject {
                    put("id", "external-turn"); put("status", if (revision.get() == 0) "inProgress" else "completed")
                    put("items", buildJsonArray { add(buildJsonObject { put("id", "answer"); put("type", "agentMessage"); put("text", "Progress ${revision.get()}") }) })
                }) }); put("nextCursor", JsonNull)
            }
        } else null }
        repo.select(chat).join()
        val before = server.calls.size
        // The screen owns this job; leaving the screen stops its reads.
        val follow = repo.observeConversation(chat.key)
        revision.set(1)
        try {
            await { it.timeline.items.values.any { item -> item.text == "Progress 1" } }
            assertNull(repo.state.value.timeline.activeTurn)
            assertTrue(server.calls.drop(before).none { it.method == "codex/request" || it.commandId != null })
        } finally { follow.cancelAndJoin() }
    }

    @Test fun persistedRefreshKeepsOlderPagesAndDraftWhileReplacingTheLiveTurn() = runBlocking<Unit> {
        val chat = ChatEntity(ThreadKey("computer", "store", "external"), "history:store", "desktop", "Project conversation", "", "D:/project", 1, "", false)
        val revision = AtomicInteger(0)
        fun turn(id: String, text: String) = buildJsonObject {
            put("id", id); put("status", "completed"); put("items", buildJsonArray { add(buildJsonObject { put("id", "answer"); put("type", "agentMessage"); put("text", text) }) })
        }
        update { it.copy(capabilities = it.capabilities + "threads/history/read") }
        server.intercept = { method, params, _ -> if (method == "threads/history/read") buildJsonObject {
            put("thread", buildJsonObject { put("id", "external") })
            val older = params["cursor"] != null
            put("data", JsonArray(if (older) listOf(turn("old", "Older answer")) else listOf(turn("live", "Progress ${revision.get()}"))))
            put("nextCursor", if (older) JsonNull else JsonPrimitive("older"))
        } else null }
        repo.select(chat).join(); repo.older().join()
        repo.scroll(3, 42, false, "saved-anchor")
        val draft = repo.state.value.draft
        val follow = repo.observeConversation(chat.key)
        revision.set(1)
        try {
            await { it.timeline.items.values.any { item -> item.text == "Progress 1" } }
            assertEquals(listOf("Older answer", "Progress 1"), repo.state.value.timeline.items.values.map { it.text })
            assertEquals(draft, repo.state.value.draft)
            assertTrue(repo.state.value.historyExhausted)
        } finally { follow.cancelAndJoin() }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        repo.select(chat).join()
        assertEquals(listOf("Older answer", "Progress 1"), repo.state.value.timeline.items.values.map { it.text })
        assertEquals(draft, repo.state.value.draft)
        assertTrue(repo.state.value.historyExhausted)
    }

    @Test fun persistedProgressReplyCannotReplaceAnotherSelectedConversation() = runBlocking<Unit> {
        val chat = ChatEntity(ThreadKey("computer", "store", "external"), "history:store", "desktop", "Project conversation", "", "D:/project", 1, "", false)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        update { it.copy(capabilities = it.capabilities + "threads/history/read") }
        server.intercept = { method, _, _ -> if (method == "threads/history/read") {
            if (reads.incrementAndGet() > 1) { entered.complete(Unit); release.await() }
            buildJsonObject { put("thread", buildJsonObject { put("id", "external") }); put("data", buildJsonArray {}); put("nextCursor", JsonNull) }
        } else null }
        repo.select(chat).join()
        val follow = repo.observeConversation(chat.key)
        try {
            withTimeout(5_000) { entered.await() }
            repo.select(repo.state.value.chats.first { it.key == key }).join()
            release.complete(Unit)
            follow.join()
            assertEquals(key, repo.state.value.selected?.key)
            assertEquals("native", repo.state.value.conversationReadSource)
            assertEquals(ConversationLoadState.Ready, repo.state.value.conversationLoadState)
        } finally { release.complete(Unit); follow.cancelAndJoin() }
    }

    @Test fun persistedRefreshBridgesNewPagesBeforeAppendingAndKeepsItsOldestCursor() = runBlocking<Unit> {
        val chat = ChatEntity(ThreadKey("computer", "store", "external"), "history:store", "desktop", "Project conversation", "", "D:/project", 1, "", false)
        val advanced = AtomicInteger()
        update { it.copy(capabilities = it.capabilities + "threads/history/read") }
        server.intercept = { method, params, _ -> if (method == "threads/history/read") {
            val ids = when {
                advanced.get() == 0 -> listOf("old")
                params.string("cursor") == "bridge" -> listOf("middle", "old")
                else -> listOf("new")
            }
            buildJsonObject {
                put("thread", buildJsonObject { put("id", "external") })
                put("data", JsonArray(ids.map { id -> buildJsonObject {
                    put("id", id); put("status", "completed"); put("items", buildJsonArray { add(buildJsonObject { put("id", "answer"); put("type", "agentMessage"); put("text", id) }) })
                } }))
                put("nextCursor", if (advanced.get() > 0 && params["cursor"] == null) JsonPrimitive("bridge") else JsonNull)
            }
        } else null }
        repo.select(chat).join()
        advanced.set(1)
        val follow = repo.observeConversation(chat.key)
        try {
            await { it.timeline.items.values.any { item -> item.text == "new" } }
            assertEquals(listOf("old", "middle", "new"), repo.state.value.timeline.items.values.map { it.text })
            assertTrue(repo.state.value.historyExhausted)
        } finally { follow.cancelAndJoin() }
    }
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

    private suspend fun restartOffline(historyCacheBudget: Long = 32L * 1024 * 1024) {
        val field = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        repo = PocketRepository(app, { server.connection() }, storage, db, network, historyCacheBudget)
        await { it.initialized }
    }

    @Test fun cancelledConnectionFactoryCannotReplaceAnAuthenticatedConnection() = runBlocking<Unit> {
        val scopeField = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (scopeField.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        server.links.forEach { it.close() }
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val created = AtomicInteger()
        repo = PocketRepository(app, {
            val link = server.connection()
            if (created.incrementAndGet() == 1) {
                entered.complete(Unit)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            link
        }, storage, db, network)
        await { it.initialized }
        repo.setForeground(true)
        withTimeout(5_000) { entered.await() }
        try {
            repo.reconnect()
            await { it.online && it.chats.any { chat -> chat.key == key } }
            val winner = server.links.last()
            release.countDown()
            until { server.links.dropLast(1).all { it.closed } }
            repo.refresh().join()
            assertTrue("Cancelled startup must not clear the current client", repo.state.value.online)
            assertEquals(LinkState.ONLINE, winner.state.value)
            assertNull(repo.state.value.directoryError)
        } finally { release.countDown() }
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
        // Install the identity mismatch before publishing the collaboration event:
        // that event also schedules a metadata read. An earlier successful reply
        // would legitimately rebind the stopped owner during the second phase.
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/read" && params.obj("params").string("threadId") == "child")
                buildJsonObject { put("thread", server.thread("wrong-child")) } else null
        }
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
        repo.openAgentReader("child").join()
        assertNotNull(repo.state.value.agentReader?.error)
        assertEquals("other-runtime", db.dao().chat(childKey)?.runtimeId)
        assertEquals(key, repo.state.value.selected?.key)
    }
    @Test fun metadataRefreshRebindsAStoppedChildRuntime() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/read" && params.obj("params").string("threadId") == "child") {
                entered.complete(Unit); release.await()
            }
            null
        }
        childLink()
        withTimeout(5_000) { entered.await() }
        val parent = repo.state.value.selected!!
        val childKey = key.copy(nativeId = "child")
        try {
            db.dao().putChat(parent.copy(key = childKey, runtimeId = "stopped-runtime", isSubagent = true, parentThreadId = key.nativeId))
            await { it.agentChats.any { c -> c.key == childKey && c.runtimeId == "stopped-runtime" } }
        } finally { release.complete(Unit) }
        // The collaboration event owns this in-flight refresh. Await its commit,
        // not a second request that is intentionally coalesced or throttled.
        await { it.agentChats.any { c -> c.key == childKey && c.runtimeId == parent.runtimeId } }
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
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/read" && params.obj("params").string("threadId") == "child")
                buildJsonObject { put("thread", server.thread("wrong-id")) } else null
        }
        childLink()
        repo.openAgent("child").join()
        assertEquals(key, repo.state.value.selected?.key)
        assertTrue(repo.state.value.online)
    }
    @Test fun lateChildReadCannotOverrideNewSelection() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/read" && params.obj("params").string("threadId") == "child") {
                entered.complete(Unit); release.await()
            }
            null
        }
        childLink()
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
        val selection = repo.reloadConversation()
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
        val reading = repo.reloadConversation()
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
        repo.reloadConversation().join()
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

    private suspend fun pendingQuestion(secret: Boolean = false): PendingApproval {
        val event = buildJsonObject {
            put("id", "question-request"); put("method", "item/tool/requestUserInput")
            put("params", buildJsonObject {
                put("threadId", "t"); put("turnId", "question-turn"); put("itemId", "question-item")
                put("questions", buildJsonArray { add(buildJsonObject {
                    put("id", "q"); put("question", "Which client?"); put("isSecret", secret)
                }) })
            })
        }
        server.pending += event; server.emitRaw(event)
        return await { it.approvals.any { a -> a.requestId == JsonPrimitive("question-request") } }.approvals
            .first { it.requestId == JsonPrimitive("question-request") }
    }
    private fun questionResponse(answer: String) = buildJsonObject { put("answers", buildJsonObject {
        put("q", buildJsonObject { put("answers", buildJsonArray { add(answer) }) })
    }) }

    private suspend fun confirmationBeforeSubmissionReply(lateUnknown: Boolean) {
        val approval = pendingQuestion()
        val entered = CompletableDeferred<String>(); val release = CompletableDeferred<Unit>()
        val confirmed = CompletableDeferred<Unit>()
        fun delivery(id: String) = buildJsonObject {
            put("commandId", id); put("runtimeId", approval.runtimeId); put("requestId", approval.requestId)
            put("threadId", approval.threadId); put("turnId", "question-turn"); put("itemId", "question-item")
            put("phase", if (confirmed.isCompleted) "confirmed" else "forwarded")
        }
        server.intercept = { method, params, id -> when (method) {
            "approvals/respond" -> {
                entered.complete(id!!); release.await()
                if (lateUnknown) throw UnknownOutcomeException(id)
                buildJsonObject { put("status", "submitted") }
            }
            "commands/status" -> buildJsonObject {
                put("status", if (confirmed.isCompleted) "accepted" else "submitted")
                put("delivery", delivery(params.string("commandId")))
            }
            else -> null
        } }
        val sending = repo.respond(approval, questionResponse("Phone"))
        val id = withTimeout(5_000) { entered.await() }
        try {
            confirmed.complete(Unit)
            repo.checkCommands().join()
            assertEquals(QuestionDeliveryPhase.Confirmed, QuestionAnswerStore(app).command(id)!!.phase)
            assertTrue(db.dao().uncertain("computer").none { it.id == id })
            release.complete(Unit); sending.join()
            assertEquals(QuestionDeliveryPhase.Confirmed, QuestionAnswerStore(app).command(id)!!.phase)
            assertEquals(QuestionDeliveryPhase.Confirmed, repo.state.value.questionDeliveries.single { it.commandId == id }.phase)
            repo.checkCommands().join()
            assertEquals(1, server.calls.count { it.method == "approvals/respond" })
        } finally { release.complete(Unit); sending.join() }
    }
    @Test fun confirmedQuestionSurvivesLateSubmittedReply() = runBlocking<Unit> { confirmationBeforeSubmissionReply(false) }
    @Test fun confirmedQuestionSurvivesLateUnknownOutcome() = runBlocking<Unit> { confirmationBeforeSubmissionReply(true) }

    private fun currentConnection(): PocketConnection = PocketRepository::class.java.getDeclaredField("client")
        .apply { isAccessible = true }.get(repo) as PocketConnection
    @Suppress("UNCHECKED_CAST") private fun currentCursors(): Map<String, Cursor> =
        (PocketRepository::class.java.getDeclaredField("cursors").apply { isAccessible = true }.get(repo) as Map<String, Cursor>).toMap()
    @Suppress("UNCHECKED_CAST")
    private suspend fun syncCandidate(source: PocketConnection, owner: PocketConnection): List<String> = kotlin.coroutines.suspendCoroutine { continuation ->
        val method = PocketRepository::class.java.getDeclaredMethod("sync", PocketConnection::class.java, PocketConnection::class.java, kotlin.coroutines.Continuation::class.java)
            .apply { isAccessible = true }
        try {
            val result = method.invoke(repo, source, owner, continuation)
            if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resumeWith(Result.success(result as List<String>))
        } catch (failure: java.lang.reflect.InvocationTargetException) {
            continuation.resumeWith(Result.failure(failure.cause ?: failure))
        }
    }
    @Test fun routePreparationRequiringSnapshotLeavesCursorAndCacheUntouched() = runBlocking<Unit> {
        val owner = currentConnection(); val before = currentCursors()
        val cached = db.dao().cachedItems(key)
        val candidate = object : PocketConnection by owner {
            override val state = MutableStateFlow(LinkState.ONLINE)
            override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long) = buildJsonObject {
                put("events", JsonArray(emptyList())); put("pendingRequests", JsonArray(emptyList()))
                put("snapshotRequired", buildJsonArray { add(server.owner) })
                put("cursors", buildJsonObject { put(server.owner, buildJsonObject { put("epoch", "replacement"); put("sequence", 99) }) })
            }
        }
        val failure = runCatching { syncCandidate(candidate, owner) }.exceptionOrNull()
        assertEquals("snapshot_required", (failure as? WireException)?.code)
        assertEquals(before, currentCursors())
        assertEquals(cached, db.dao().cachedItems(key))
        assertEquals(ConversationLoadState.Idle, repo.state.value.conversationLoadState)
        assertTrue(repo.state.value.online)
    }
    @Test fun offlinePreparationCannotReportSuccessfulSyncOrAdvanceCursor() = runBlocking<Unit> {
        val owner = currentConnection(); val before = currentCursors()
        val candidate = object : PocketConnection by owner {
            override val state = MutableStateFlow(LinkState.ONLINE)
            override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
                state.value = LinkState.OFFLINE
                return server.syncSnapshot()
            }
        }
        assertEquals("disconnected", (runCatching { syncCandidate(candidate, owner) }.exceptionOrNull() as? WireException)?.code)
        assertEquals(before, currentCursors())
        assertTrue(repo.state.value.online)
    }
    @Test fun replayPrefixAndBufferedCandidateSuffixApplyExactlyOnce() = runBlocking<Unit> {
        server.emit("item/started", buildJsonObject {
            put("threadId", "t"); put("turnId", "suffix-turn")
            put("item", buildJsonObject { put("id", "suffix-item"); put("type", "agentMessage"); put("text", "") })
        })
        await { it.timeline.items.values.any { item -> item.itemId == "suffix-item" } }
        val baseline = server.cursor(); val sequence = baseline.number("sequence")
        fun delta(text: String) = buildJsonObject {
            put("method", "item/agentMessage/delta"); put("params", buildJsonObject {
                put("threadId", "t"); put("turnId", "suffix-turn"); put("itemId", "suffix-item"); put("delta", text)
            })
        }
        val owner = currentConnection()
        val candidate = object : PocketConnection by owner {
            override val state = MutableStateFlow(LinkState.ONLINE)
            override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long) = buildJsonObject {
                put("snapshotRequired", JsonArray(emptyList())); put("pendingRequests", JsonArray(emptyList()))
                put("events", buildJsonArray { listOf("A", "B").forEachIndexed { index, text -> add(buildJsonObject {
                    put("runtimeId", server.owner); put("cursor", JsonObject(baseline + ("sequence" to JsonPrimitive(sequence + index + 1))))
                    put("event", delta(text))
                }) } })
                put("cursors", buildJsonObject { put(server.owner, JsonObject(baseline + ("sequence" to JsonPrimitive(sequence + 2)))) })
            }
        }
        assertTrue(syncCandidate(candidate, owner).isEmpty())
        listOf("A", "B", "C").forEach { server.emitRaw(delta(it)) }
        await { it.timeline.items.values.single { item -> item.itemId == "suffix-item" }.text == "ABC" }
        assertEquals("ABC", db.dao().cachedItems(key).single { it.itemId == "suffix-item" }.text)
    }
    @Test fun nativeHistoryUnavailableHasSpecificDraftPreservingMessage() {
        val method = PocketRepository::class.java.getDeclaredMethod("safeError", Exception::class.java).apply { isAccessible = true }
        assertEquals("当前电脑 Codex 暂不支持此会话的历史读取；缓存与草稿已保留，可在电脑端查看。",
            method.invoke(repo, WireException("native_history_unavailable", "private diagnostic")))
    }

    @Test fun genericResolutionCannotAcceptQuestionButMatchingNativeReceiptCan() = runBlocking<Unit> {
        val approval = pendingQuestion()
        var phase = "forwarded"
        fun delivery(id: String) = buildJsonObject {
            put("commandId", id); put("runtimeId", "runtime"); put("threadId", "t")
            put("turnId", "question-turn"); put("itemId", "question-item")
            put("requestId", approval.requestId); put("phase", phase)
        }
        server.intercept = { method, params, id -> when (method) {
            "approvals/respond" -> buildJsonObject { put("status", "accepted"); put("delivery", delivery(id!!)) }
            "commands/status" -> buildJsonObject { put("status", "accepted"); put("delivery", delivery(params.string("commandId"))) }
            else -> null
        } }
        repo.respond(approval, questionResponse("Phone")).join()
        val command = db.dao().uncertain("computer").single { it.method == "approvals/respond" }
        assertEquals(QuestionDeliveryPhase.Forwarded, repo.state.value.questionDeliveries.single().phase)
        assertFalse(repo.state.value.uncertainSend)
        server.pending.clear(); server.emit("serverRequest/resolved", buildJsonObject { put("requestId", approval.requestId) })
        await { it.approvals.isEmpty() }
        repo.checkCommands().join()
        assertEquals(command.id, db.dao().uncertain("computer").single().id)
        phase = "confirmed"
        server.emit("item/completed", buildJsonObject {
            put("threadId", "t"); put("turnId", "question-turn")
            put("item", buildJsonObject {
                put("id", "question-item"); put("type", "dynamicToolCall"); put("tool", "request_user_input")
                put("status", "completed"); put("success", true); put("arguments", approval.params)
                put("contentItems", buildJsonArray { add(buildJsonObject { put("type", "inputText"); put("text", questionResponse("Phone").toString()) }) })
            })
        })
        await { it.questionDeliveries.any { r -> r.commandId == command.id && r.phase == QuestionDeliveryPhase.Confirmed } }
        await { it.commands.none { c -> c.id == command.id } }
        assertEquals(1, server.calls.count { it.method == "approvals/respond" })
        assertEquals(command.id, server.calls.single { it.method == "approvals/respond" }.commandId)
    }

    @Test fun unknownQuestionCannotReplayAndPublicAnswerRestoresAfterOfflineRestart() = runBlocking<Unit> {
        val approval = pendingQuestion()
        server.intercept = { method, _, id ->
            if (method == "approvals/respond") { server.statuses[id!!] = "unknown"; throw UnknownOutcomeException(id) }
            null
        }
        repo.respond(approval, questionResponse("Phone retained fixture")).join()
        assertFalse(repo.state.value.uncertainSend)
        repo.respond(approval, questionResponse("Changed answer")).join()
        assertEquals(1, server.calls.count { it.method == "approvals/respond" })
        val command = db.dao().uncertain("computer").single()
        val sends = server.calls.count { it.method == "codex/request" && it.params.string("method") == "turn/start" }
        repo.editDraft("An unrelated fixture message")
        repo.send().join()
        assertEquals(sends + 1, server.calls.count { it.method == "codex/request" && it.params.string("method") == "turn/start" })
        val before = server.calls.count { it.commandId != null }
        restartOffline()
        val restored = QuestionAnswerStore(app).command(command.id)!!
        assertEquals("Phone retained fixture", restored.restorableAnswers(QuestionBatches.blocking(approval.key, approval.params))["q"])
        assertEquals(before, server.calls.count { it.commandId != null })
    }

    @Test fun secretQuestionSubmissionPersistsIdentityButNoSecretAnswerOrHash() = runBlocking<Unit> {
        val approval = pendingQuestion(secret = true)
        repo.respond(approval, questionResponse("private-answer-fixture")).join()
        val command = db.dao().uncertain("computer").single()
        assertEquals(approval.key, command.draftText)
        val stored = app.getSharedPreferences("question-delivery", Application.MODE_PRIVATE).all.values.joinToString()
        assertFalse(stored.contains("private-answer-fixture"))
        assertTrue(QuestionAnswerStore(app).command(command.id)!!.restorableAnswers(QuestionBatches.blocking(approval.key, approval.params)).isEmpty())
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
        val reading = repo.reloadConversation()
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

    private fun providerModel(id: String) = NativeModel(id, id, id, "", emptyList(), "", false, false)

    @Test fun phoneProviderCatalogReplacesOfficialModelsWithoutChangingConversationOwner() = runBlocking<Unit> {
        val custom = providerModel("ccs-aggit/gpt-6.1-sol")
        val owner = repo.state.value.selected!!
        val nativeReads = server.calls.count { it.nativeMethod == "model/list" }
        providerCatalog = ModelCatalogRequest({ true }) { listOf(custom) }
        repo.refreshModels().join()
        assertEquals(listOf(custom), repo.state.value.models)
        assertEquals(nativeReads, server.calls.count { it.nativeMethod == "model/list" })
        repo.chooseModel(custom, null)
        assertEquals(custom.model, repo.state.value.model)
        assertEquals(owner, repo.state.value.selected)
        assertEquals(owner.key.computerId, repo.state.value.computerId)
        assertTrue(repo.state.value.online)
    }

    @Test fun changingPhoneProviderDiscardsItsOutstandingModelCatalog() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var revision = 1
        providerCatalog = ModelCatalogRequest({ revision == 1 }) {
            entered.complete(Unit); release.await(); listOf(providerModel("old-provider/model"))
        }
        val older = repo.refreshModels()
        try {
            withTimeout(5_000) { entered.await() }
            revision = 2
            providerCatalog = ModelCatalogRequest({ revision == 2 }) { listOf(providerModel("new-provider/model")) }
            repo.refreshModels().join()
            release.complete(Unit); older.join()
            assertEquals(listOf("new-provider/model"), repo.state.value.models.map { it.model })
            assertEquals(ModelCatalogState.Ready, repo.state.value.modelCatalogState)
        } finally { release.complete(Unit); older.cancelAndJoin() }
    }

    @Test fun phoneProviderModelFailureKeepsConnectionDraftAndChosenModel() = runBlocking<Unit> {
        repo.editDraft("Keep the local draft")
        val before = repo.state.value
        providerCatalog = ModelCatalogRequest({ true }) { throw WireException("model_catalog_failed", "Fixture authentication failed") }
        repo.refreshModels().join()
        val after = repo.state.value
        assertEquals(ModelCatalogState.Failed, after.modelCatalogState)
        assertTrue(after.online)
        assertEquals(before.selected, after.selected)
        assertEquals(before.draft, after.draft)
        assertEquals(before.model, after.model)
        assertEquals(before.disabledMethods, after.disabledMethods)
    }

    @Test fun changingBackToOfficialIgnoresLatePhoneProviderFailure() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        providerCatalog = ModelCatalogRequest({ providerCatalog != null }) {
            entered.complete(Unit); release.await(); throw WireException("unsupported", "Old provider has no model list")
        }
        val older = repo.refreshModels()
        try {
            withTimeout(5_000) { entered.await() }
            providerCatalog = null
            repo.refreshModels().join()
            val official = repo.state.value.models
            release.complete(Unit); older.join()
            assertTrue(official.isNotEmpty())
            assertEquals(official, repo.state.value.models)
            assertEquals(ModelCatalogState.Ready, repo.state.value.modelCatalogState)
            assertTrue(repo.state.value.supports("model/list"))
        } finally { release.complete(Unit); older.cancelAndJoin() }
    }

    @Test fun nativeCatalogReadCannotPublishAfterPhoneProviderChanges() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "model/list") {
                entered.complete(Unit); release.await()
                buildJsonObject { put("data", buildJsonArray { add(buildJsonObject {
                    put("id", "stale-official"); put("model", "stale-official")
                }) }) }
            } else null
        }
        val older = repo.refreshModels()
        try {
            withTimeout(5_000) { entered.await() }
            providerCatalog = ModelCatalogRequest({ true }) { listOf(providerModel("provider-model")) }
            // A service change can happen before reconnect issues a newer catalog request.
            val before = repo.state.value.models
            release.complete(Unit); older.join()
            assertEquals(before, repo.state.value.models)
            assertFalse(repo.state.value.models.any { it.model == "stale-official" })
        } finally { release.complete(Unit); older.cancelAndJoin() }
    }

    @Test fun phoneProviderCatalogDoesNotHoldTheNewConversationPageOpen() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        providerCatalog = ModelCatalogRequest({ true }) {
            entered.complete(Unit); release.await(); listOf(providerModel("provider-model"))
        }
        val creation = repo.create(repo.state.value.runtimes.first(), "D:/fixture")
        try {
            withTimeout(5_000) { entered.await() }
            withTimeout(2_000) { creation.join() }
            assertFalse(repo.state.value.actionStatus["create"]?.loading == true)
            assertEquals(ConversationLoadState.Ready, repo.state.value.conversationLoadState)
            assertEquals(ModelCatalogState.Loading, repo.state.value.modelCatalogState)
            assertTrue(repo.state.value.online)
        } finally { release.complete(Unit); creation.cancelAndJoin() }
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

    @Test fun supersededSelectionContextReadCannotStrandSameChatInIdle() = runBlocking<Unit> {
        assertSupersededSelectionContextRead(key)
    }

    @Test fun supersededSelectionContextReadCannotReplaceAnotherChat() = runBlocking<Unit> {
        assertSupersededSelectionContextRead(otherKey)
    }

    private suspend fun assertSupersededSelectionContextRead(newKey: ThreadKey) {
        val olderChat = repo.state.value.chats.first { it.key == key }
        val newerChat = repo.state.value.chats.first { it.key == newKey }
        repo.editDraft("Draft captured by the older selection")
        val queryCaptured = CompletableDeferred<Unit>()
        val unblockQuery = CompletableDeferred<Unit>()
        val contextReads = AtomicInteger()
        val daoField = PocketRepository::class.java.getDeclaredField("dao").apply { isAccessible = true }
        val originalDao = daoField.get(repo) as PocketDao
        val pausingDao = object : PocketDao by originalDao {
            override suspend fun contextUsage(key: ThreadKey): ContextEntity? {
                val captured = originalDao.contextUsage(key)
                // Suspend after the real Room query releases its worker. The newer select
                // must be able to finish its own context/history reads while this one waits.
                if (key == olderChat.key && contextReads.incrementAndGet() == 1) {
                    queryCaptured.complete(Unit)
                    unblockQuery.await()
                }
                return captured
            }
        }
        daoField.set(repo, pausingDao)
        val older = repo.select(olderChat, forceRefresh = true)
        var newer: Job? = null
        try {
            withTimeout(5_000) { queryCaptured.await() }
            val currentSelection = repo.select(newerChat, forceRefresh = true)
            newer = currentSelection
            withTimeout(5_000) { currentSelection.join() }
            await { it.selected?.key == newKey && it.conversationLoadState == ConversationLoadState.Ready &&
                it.modelCatalogState == ModelCatalogState.Ready && it.settingsFresh && it.nativeSettings.model.known }
            repo.editDraft("Draft edited after the newer selection completed")
            val ready = repo.state.value
            fun hydrationReads() = server.calls.count {
                it.nativeMethod in setOf("thread/read", "thread/turns/list", "model/list")
            }
            val completedReads = hydrationReads()
            // Both selections have the same connection; even the same chat has a new
            // selection generation. Returning the old cache result must not publish Idle.
            unblockQuery.complete(Unit)
            withTimeout(5_000) { older.join() }
            val actual = repo.state.value
            val detail = "selected=${actual.selected?.key}, catalog=${actual.modelCatalogState}, " +
                "history=${actual.conversationLoadState}, hydrationReads=$completedReads->${hydrationReads()}"
            assertEquals("A stale context read must not replace the current selection: $detail", newKey, actual.selected?.key)
            assertEquals("A completed catalog must not return to Idle without a reader: $detail", ModelCatalogState.Ready, actual.modelCatalogState)
            assertEquals("A completed history must not return to Loading without a reader: $detail", ConversationLoadState.Ready, actual.conversationLoadState)
            assertEquals(ready.models, actual.models)
            assertEquals(ready.nativeSettings, actual.nativeSettings)
            assertEquals(ready.settingsFresh, actual.settingsFresh)
            assertEquals("A stale context read must not replace the current draft", ready.draft, actual.draft)
            assertEquals("Discard the stale selection before starting another hydration", completedReads, hydrationReads())
        } finally {
            unblockQuery.complete(Unit)
            older.cancelAndJoin()
            newer?.cancelAndJoin()
            daoField.set(repo, originalDao)
        }
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

    private fun optimisticUserRows(): List<TranscriptRow> = ConversationPresentation.rows(repo.state.value.timeline)
        .filter { it.kind == TranscriptKind.User }

    private fun onlyOptimisticUser(text: String): TranscriptRow {
        val rows = optimisticUserRows()
        assertEquals("The accepted click must already have one visible user row", 1, rows.size)
        assertEquals(text, rows.single().items.single().text)
        return rows.single()
    }

    // Native protocol: TurnStartParams.clientUserMessageId is echoed as UserMessage.clientId,
    // while UserMessage.id remains a separately generated native ID. See upstream
    // codex-rs/app-server-protocol/src/protocol/v2/item.rs (ThreadItemPayload::UserMessage)
    // and app-server/tests/suite/v2/turn_start.rs::turn_start_emits_user_message_item_with_text_elements.
    // Do not turn clientUserMessageId into a fabricated item field or overwrite the native id.
    private fun echoedUser(clientId: String?, text: String = "sent once", id: String = "native-user") = buildJsonObject {
        put("id", id); put("type", "userMessage")
        put("clientId", clientId?.let(::JsonPrimitive) ?: JsonNull)
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
    }

    private fun echoedTurn(user: JsonObject) = buildJsonObject {
        put("id", "sent"); put("status", "inProgress"); put("items", buildJsonArray { add(user) })
    }

    @Test fun optimisticSendAppearsBeforeResumeAndSurvivesItsEmptyHistoryRead() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val sent = CompletableDeferred<Unit>(); val accept = CompletableDeferred<Unit>()
        update { s -> s.copy(runtimes = s.runtimes.map { it.copy(loadedThreadIds = emptySet()) }) }
        server.intercept = { method, params, _ ->
            if (method == "codex/request") when (params.string("method")) {
                "thread/resume" -> { entered.complete(Unit); release.await() }
                "turn/start" -> { sent.complete(Unit); accept.await() }
            }
            null
        }
        repo.editDraft("sent once")
        val job = repo.send()
        try {
            withTimeout(5_000) { entered.await() }
            val displayKey = onlyOptimisticUser("sent once").key
            assertEquals("sent once", repo.state.value.draft?.text)
            release.complete(Unit)
            withTimeout(5_000) { sent.await() }
            assertEquals("An empty pre-send snapshot must retain the optimistic row", displayKey, onlyOptimisticUser("sent once").key)
        } finally { release.complete(Unit); accept.complete(Unit); job.join() }
    }

    @Test fun optimisticSendAppearsBeforeUploadAndDefiniteRejectionKeepsDraftAndAttachment() = runBlocking<Unit> {
        repo.attach(listOf(Uri.fromFile(repo.attachmentFile(image())))).join()
        val original = repo.state.value.attachments.single()
        repo.editDraft("sent once")
        val entered = CompletableDeferred<Unit>(); val reject = CompletableDeferred<Unit>()
        server.intercept = { method, _, _ ->
            if (method == "attachment/upload") {
                entered.complete(Unit); reject.await(); throw WireException("rejected", "fixture upload rejection")
            }
            null
        }
        val job = repo.send()
        try {
            withTimeout(5_000) { entered.await() }
            onlyOptimisticUser("sent once")
            assertEquals("sent once", repo.state.value.draft?.text)
            assertEquals(listOf(original), repo.state.value.attachments)
        } finally { reject.complete(Unit); job.join() }
        assertTrue("A definite upload rejection removes only the temporary row", optimisticUserRows().isEmpty())
        assertEquals("sent once", repo.state.value.draft?.text)
        assertEquals(listOf(original), repo.state.value.attachments)
        assertTrue(repo.attachmentFile(original).exists())
        assertTrue(server.calls.none { it.nativeMethod == "turn/start" })
    }

    @Test fun optimisticSendDefiniteNativeRejectionRemovesOnlyItsTemporaryRow() = runBlocking<Unit> {
        repo.attach(listOf(Uri.fromFile(repo.attachmentFile(image())))).join()
        val attachment = repo.state.value.attachments.single()
        val entered = CompletableDeferred<Unit>(); val reject = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") {
                entered.complete(Unit); reject.await(); throw WireException("rejected", "fixture native rejection")
            }
            null
        }
        repo.editDraft("sent once")
        val job = repo.send()
        try {
            withTimeout(5_000) { entered.await() }
            onlyOptimisticUser("sent once")
        } finally { reject.complete(Unit); job.join() }
        assertTrue(optimisticUserRows().isEmpty())
        assertEquals("sent once", repo.state.value.draft?.text)
        assertEquals(listOf(attachment), repo.state.value.attachments)
        assertTrue(repo.attachmentFile(attachment).exists())
        assertTrue(db.dao().uncertain("computer").isEmpty())
    }

    @Test fun optimisticSendUnknownSurvivesOfflineRestartAndCannotBeReplayed() = runBlocking<Unit> {
        server.intercept = { method, params, id ->
            if (method == "codex/request" && params.string("method") == "turn/start") {
                server.statuses[id!!] = "unknown"; throw UnknownOutcomeException(id)
            }
            null
        }
        repo.editDraft("sent once")
        repo.send().join()
        await { it.uncertainSend }
        val displayKey = onlyOptimisticUser("sent once").key
        assertEquals("sent once", repo.state.value.draft?.text)
        assertTrue(optimisticUserRows().none { row -> row.items.any { it.status == "failed" } })
        repo.send().join()
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
        restartOffline()
        repo.select(db.dao().chat(key)!!).join()
        assertEquals(displayKey, onlyOptimisticUser("sent once").key)
        assertEquals("sent once", repo.state.value.draft?.text)
        assertTrue(repo.state.value.uncertainSend)
        repo.send().join()
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
    }

    @Test fun optimisticSendBindsTheClickedDraftBeforeAccessPreparation() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        update { it.copy(capabilities = it.capabilities + "thread/access/read") }
        server.intercept = { method, _, _ ->
            if (method == "thread/access/read") {
                entered.complete(Unit); release.await()
                buildJsonObject {
                    put("mode", "desktop"); put("phase", "ready"); put("source", "desktop")
                    put("ownerRuntimeId", "runtime"); put("canSend", true)
                }
            } else null
        }
        repo.editDraft("clicked draft")
        val job = repo.send()
        try {
            withTimeout(5_000) { entered.await() }
            onlyOptimisticUser("clicked draft")
            repo.editDraft("new draft while preparing")
        } finally { release.complete(Unit); job.join() }
        val request = server.calls.single { it.nativeMethod == "turn/start" }.params.obj("params")
        assertEquals("clicked draft", request.array("input").first().asObject().string("text"))
        assertEquals("new draft while preparing", repo.state.value.draft?.text)
    }

    @Test fun optimisticSendAcceptedWithoutEchoSurvivesProcessRecoveryAndReconnect() = runBlocking<Unit> {
        repo.editDraft("sent once")
        repo.send().join()
        val displayKey = onlyOptimisticUser("sent once").key
        val clientId = server.calls.single { it.nativeMethod == "turn/start" }.params.obj("params").string("clientUserMessageId")
        assertEquals("", repo.state.value.draft?.text)
        restartOffline()
        repo.select(db.dao().chat(key)!!).join()
        assertEquals(displayKey, onlyOptimisticUser("sent once").key)
        server.pagedHistory = true; server.pageTurns = listOf(echoedTurn(echoedUser(clientId)))
        repo.setForeground(true)
        await { it.online && it.timeline.items.values.any { item -> item.itemId == "native-user" } }
        assertEquals(displayKey, onlyOptimisticUser("sent once").key)
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
    }

    @Test fun optimisticSendUnknownRejectedDuringReconciliationKeepsTheRecoverableDraft() = runBlocking<Unit> {
        repo.attach(listOf(Uri.fromFile(repo.attachmentFile(image())))).join()
        val attachment = repo.state.value.attachments.single()
        server.intercept = { method, params, id ->
            if (method == "codex/request" && params.string("method") == "turn/start") {
                server.statuses[id!!] = "unknown"; throw UnknownOutcomeException(id)
            }
            null
        }
        repo.editDraft("sent once")
        repo.send().join()
        onlyOptimisticUser("sent once")
        val pending = db.dao().uncertain("computer").single { it.method == "turn/start" }
        server.statuses[pending.id] = "rejected"
        repo.checkCommands().join()
        await { !it.uncertainSend }
        assertTrue(optimisticUserRows().isEmpty())
        assertEquals("sent once", repo.state.value.draft?.text)
        assertEquals(listOf(attachment), repo.state.value.attachments)
        assertTrue(repo.attachmentFile(attachment).exists())
        restartOffline()
        repo.select(db.dao().chat(key)!!).join()
        assertTrue(optimisticUserRows().isEmpty())
        assertEquals("sent once", repo.state.value.draft?.text)
        assertEquals(listOf(attachment), repo.state.value.attachments)
    }

    @Test fun optimisticSendUnknownAcceptedReceiptAndLateClientEchoKeepOneDisplayKey() = runBlocking<Unit> {
        server.intercept = { method, params, id ->
            if (method == "codex/request" && params.string("method") == "turn/start") {
                server.statuses[id!!] = "unknown"; throw UnknownOutcomeException(id)
            }
            null
        }
        repo.editDraft("sent once")
        repo.send().join()
        val displayKey = onlyOptimisticUser("sent once").key
        val request = server.calls.single { it.nativeMethod == "turn/start" }
        // crates/agent/src/broker.rs::status_value returns native_result under result.
        server.intercept = { method, _, _ ->
            if (method == "commands/status") buildJsonObject {
                put("status", "accepted"); put("result", buildJsonObject { put("turn", echoedTurn(echoedUser(request.params.obj("params").string("clientUserMessageId")))) })
            } else null
        }
        repo.checkCommands().join()
        await { !it.uncertainSend }
        assertEquals("", repo.state.value.draft?.text)
        assertEquals(displayKey, onlyOptimisticUser("sent once").key)
        server.emit("item/completed", buildJsonObject {
            put("threadId", "t"); put("turnId", "sent")
            put("item", echoedUser(request.params.obj("params").string("clientUserMessageId")))
        })
        await { it.timeline.items.values.any { item -> item.itemId == "native-user" } }
        assertEquals(displayKey, onlyOptimisticUser("sent once").key)
        assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
    }

    @Test fun optimisticSendDelayedReceiptDoesNotConsumeANewDraftWithTheSameText() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val accept = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") { entered.complete(Unit); accept.await() }
            null
        }
        repo.editDraft("repeat this")
        val job = repo.send()
        try {
            withTimeout(5_000) { entered.await() }
            repo.editDraft("a new thought")
            repo.editDraft("repeat this")
        } finally { accept.complete(Unit); job.join() }
        assertEquals("A new draft is not the submitted draft even when its text is equal", "repeat this", repo.state.value.draft?.text)
        assertEquals("repeat this", db.dao().draft(key)?.text)
    }

    @Test fun optimisticSendRetainsItsRowWhileSwitchingChatsAndKeepsBothNewDrafts() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val accept = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") { entered.complete(Unit); accept.await() }
            null
        }
        repo.editDraft("sent once")
        val job = repo.send()
        try {
            withTimeout(5_000) { entered.await() }
            val displayKey = onlyOptimisticUser("sent once").key
            repo.editDraft("new draft in original chat")
            repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
            repo.editDraft("other chat draft")
            assertTrue(optimisticUserRows().isEmpty())
            accept.complete(Unit); job.join()
            assertEquals(otherKey, repo.state.value.selected?.key)
            assertEquals("other chat draft", repo.state.value.draft?.text)
            repo.select(repo.state.value.chats.first { it.key == key }).join()
            assertEquals(displayKey, onlyOptimisticUser("sent once").key)
            assertEquals("new draft in original chat", repo.state.value.draft?.text)
            assertEquals("other chat draft", db.dao().draft(otherKey)?.text)
        } finally { accept.complete(Unit); job.join() }
    }

    @Test fun optimisticSendEventSnapshotReceiptOrderKeepsDisplayIdentity() = runBlocking<Unit> { optimisticEchoOrder("ESR") }
    @Test fun optimisticSendEventReceiptSnapshotOrderKeepsDisplayIdentity() = runBlocking<Unit> { optimisticEchoOrder("ERS") }
    @Test fun optimisticSendSnapshotEventReceiptOrderKeepsDisplayIdentity() = runBlocking<Unit> { optimisticEchoOrder("SER") }
    @Test fun optimisticSendSnapshotReceiptEventOrderKeepsDisplayIdentity() = runBlocking<Unit> { optimisticEchoOrder("SRE") }
    @Test fun optimisticSendReceiptEventSnapshotOrderKeepsDisplayIdentity() = runBlocking<Unit> { optimisticEchoOrder("RES") }
    @Test fun optimisticSendReceiptSnapshotEventOrderKeepsDisplayIdentity() = runBlocking<Unit> { optimisticEchoOrder("RSE") }

    private suspend fun optimisticEchoOrder(order: String) {
        val entered = CompletableDeferred<String>(); val accept = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") {
                entered.complete(params.obj("params").string("clientUserMessageId")); accept.await()
            }
            null
        }
        repo.editDraft("sent once")
        val job = repo.send()
        try {
            val clientId = withTimeout(5_000) { entered.await() }
            assertTrue(clientId.isNotBlank())
            val displayKey = onlyOptimisticUser("sent once").key
            val native = echoedUser(clientId)
            for (stage in order) {
                when (stage) {
                    'E' -> {
                        server.emit("item/started", buildJsonObject { put("threadId", "t"); put("turnId", "sent"); put("item", native) })
                        server.emit("item/completed", buildJsonObject { put("threadId", "t"); put("turnId", "sent"); put("item", native) })
                        await { it.timeline.items.values.any { item -> item.itemId == "native-user" && item.complete } }
                    }
                    'S' -> {
                        server.pagedHistory = true; server.pageTurns = listOf(echoedTurn(native))
                        repo.reloadConversation().join()
                        assertNull(repo.state.value.conversationLoadError)
                    }
                    'R' -> { accept.complete(Unit); job.join() }
                }
                assertEquals("Display identity changed at $stage in $order", displayKey, onlyOptimisticUser("sent once").key)
            }
            val item = repo.state.value.timeline.items.values.single { it.type == "userMessage" }
            val nativeKey = JsonArray(listOf(JsonPrimitive("sent"), JsonPrimitive("native-user"))).toString()
            assertEquals("Native item identity must retain its protocol meaning", nativeKey, item.key)
            assertEquals(nativeKey, db.dao().items(key).single { it.type == "userMessage" }.itemKey)
            assertEquals(clientId, item.raw.string("clientId"))
            assertEquals("", repo.state.value.draft?.text)
            assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
            assertEquals(setOf(clientId), server.calls.filter { it.nativeMethod == "turn/start" }
                .map { it.params.obj("params").string("clientUserMessageId") }.toSet())
            restartOffline()
            repo.select(db.dao().chat(key)!!).join()
            assertEquals("Display identity must survive process recovery", displayKey, onlyOptimisticUser("sent once").key)
        } finally { accept.complete(Unit); job.join() }
    }

    @Test fun optimisticSendDoesNotMergeAnUnrelatedNativeUserWithIdenticalText() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val accept = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") { entered.complete(Unit); accept.await() }
            null
        }
        repo.editDraft("sent once")
        val job = repo.send()
        try {
            withTimeout(5_000) { entered.await() }
            val localKey = onlyOptimisticUser("sent once").key
            server.emit("item/completed", buildJsonObject {
                put("threadId", "t"); put("turnId", "other-turn")
                put("item", echoedUser("another-client", id = "another-user"))
            })
            await { it.timeline.items.values.any { item -> item.itemId == "another-user" } }
            assertEquals("Text equality is not identity evidence", 2, optimisticUserRows().size)
            assertTrue(optimisticUserRows().any { it.key == localKey })
        } finally { accept.complete(Unit); job.join() }
    }

    @Test fun optimisticSendReconnectDuringResumeWithdrawsUnsentMessage() = runBlocking<Unit> {
        optimisticReconnectBeforeSend("thread/resume")
    }

    @Test fun optimisticSendReconnectDuringUploadWithdrawsUnsentMessage() = runBlocking<Unit> {
        optimisticReconnectBeforeSend("attachment/upload")
    }

    private suspend fun optimisticReconnectBeforeSend(stage: String) {
        if (stage == "attachment/upload") repo.attach(listOf(Uri.fromFile(repo.attachmentFile(image())))).join()
        else update { state -> state.copy(runtimes = state.runtimes.map { it.copy(loadedThreadIds = emptySet()) }) }
        val originalAttachments = repo.state.value.attachments
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val held = java.util.concurrent.atomic.AtomicBoolean(false)
        server.intercept = { method, params, id ->
            val matches = if (stage == "attachment/upload") method == stage
                else method == "codex/request" && params.string("method") == stage
            if (matches && held.compareAndSet(false, true)) {
                server.statuses[id!!] = "accepted" // Only the preparation/upload was accepted, never turn/start.
                entered.complete(Unit); release.await()
            }
            null
        }
        repo.editDraft("clicked before reconnect")
        val before = server.links.last()
        val epoch = repo.state.value.mediaConnectionEpoch
        val job = repo.send()
        try {
            withTimeout(5_000) { entered.await() }
            onlyOptimisticUser("clicked before reconnect")
            // Exercise the real disconnect/hello/sync path with the SAME computer/store/thread.
            repo.setForeground(false); repo.setForeground(true)
            await { it.online && it.mediaConnectionEpoch > epoch && server.links.last() !== before }
            assertEquals("computer", repo.state.value.computerId)
            assertEquals(key, repo.state.value.selected?.key)
            release.complete(Unit)
            withTimeout(5_000) { job.join() }
            assertTrue("An old click must not send on a replacement connection", server.calls.none { it.nativeMethod == "turn/start" })
            assertTrue("The replacement transport must receive no user write", server.links.last().requests.none { it.nativeMethod == "turn/start" })
            assertTrue("Definitely unsent local rows must be withdrawn", optimisticUserRows().isEmpty())
            assertEquals("clicked before reconnect", repo.state.value.draft?.text)
            assertEquals(originalAttachments, repo.state.value.attachments)
            originalAttachments.forEach { assertTrue(repo.attachmentFile(it).exists()) }
            val outgoing = db.dao().outgoingMessages().single()
            assertEquals("rejected", outgoing.phase)
            assertNull("The stale click must stop before durable turn submission", db.dao().command(outgoing.commandId))
        } finally { release.complete(Unit); job.join() }
    }

    @Test fun optimisticSendReconnectAfterNativeWriteKeepsUnknownAndNeverReplays() = runBlocking<Unit> {
        val entered = CompletableDeferred<String>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, id ->
            if (method == "codex/request" && params.string("method") == "turn/start") {
                server.statuses[id!!] = "unknown"
                entered.complete(id); release.await(); throw UnknownOutcomeException(id)
            }
            null
        }
        repo.editDraft("written before reconnect")
        val before = server.links.last()
        val epoch = repo.state.value.mediaConnectionEpoch
        val job = repo.send()
        try {
            val commandId = withTimeout(5_000) { entered.await() }
            val displayKey = onlyOptimisticUser("written before reconnect").key
            repo.setForeground(false); repo.setForeground(true)
            await { it.online && it.mediaConnectionEpoch > epoch && server.links.last() !== before }
            release.complete(Unit)
            withTimeout(5_000) { job.join() }
            repo.checkCommands().join()
            await { it.uncertainSend }
            assertEquals(displayKey, onlyOptimisticUser("written before reconnect").key)
            assertEquals("unknown", db.dao().command(commandId)?.status)
            assertEquals("written before reconnect", repo.state.value.draft?.text)
            repo.send().join()
            assertEquals(1, server.calls.count { it.nativeMethod == "turn/start" })
            assertTrue(server.links.last().requests.none { it.nativeMethod == "turn/start" })
        } finally { release.complete(Unit); job.join() }
    }

    @Test fun optimisticSendLegacyCachedUserKeepsItsNativeDisplayKey() {
        val raw = JsonObject(echoedUser(null, "old cached text", "old-user") - "clientId")
        val nativeKey = JsonArray(listOf(JsonPrimitive("old-turn"), JsonPrimitive("old-user"))).toString()
        // A pre-optimistic Room row has no clientId and no local display metadata.
        val restored = ItemEntity(key, nativeKey, "old-turn", "old-user", "userMessage", "old cached text",
            "", raw, true, 0).item()
        val timeline = NativeTimeline(items = linkedMapOf(restored.key to restored))
        val row = ConversationPresentation.rows(timeline).single()
        assertEquals(nativeKey, restored.key)
        assertEquals(nativeKey, row.key)
        assertEquals(raw, ItemEntity.from(key, restored, 0).item().raw)
    }

    @Test fun optimisticSendQueueDoesNotPublishATemporaryBodyBubble() = runBlocking<Unit> {
        server.emit("turn/started", buildJsonObject { put("threadId", "t"); put("turn", buildJsonObject { put("id", "running") }) })
        await { it.timeline.activeTurn == "running" }
        val entered = CompletableDeferred<Unit>(); val accept = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/queue/add") { entered.complete(Unit); accept.await() }
            null
        }
        repo.editDraft("queued next")
        val job = repo.send()
        try {
            withTimeout(5_000) { entered.await() }
            assertTrue(optimisticUserRows().isEmpty())
            assertEquals("queued next", repo.state.value.draft?.text)
        } finally { accept.complete(Unit); job.join() }
        assertTrue(optimisticUserRows().isEmpty())
        assertEquals("", repo.state.value.draft?.text)
        assertTrue(server.calls.none { it.nativeMethod == "turn/steer" })
    }

    @Test fun optimisticSendSteerKeepsNativeExpectedTurnAndDoesNotCreateANewTurnBubble() = runBlocking<Unit> {
        server.emit("turn/started", buildJsonObject { put("threadId", "t"); put("turn", buildJsonObject { put("id", "running") }) })
        await { it.timeline.activeTurn == "running" }
        val entered = CompletableDeferred<Unit>(); val accept = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/steer") { entered.complete(Unit); accept.await() }
            null
        }
        repo.editDraft("steer current")
        val job = repo.send(true)
        try {
            withTimeout(5_000) { entered.await() }
            assertTrue(optimisticUserRows().isEmpty())
            val request = server.calls.single { it.nativeMethod == "turn/steer" }.params.obj("params")
            assertEquals("running", request.string("expectedTurnId"))
            assertTrue(request.string("clientUserMessageId").isNotBlank())
            assertEquals("steer current", repo.state.value.draft?.text)
        } finally { accept.complete(Unit); job.join() }
        assertTrue(optimisticUserRows().isEmpty())
        assertEquals("running", repo.state.value.timeline.activeTurn)
        assertEquals("", repo.state.value.draft?.text)
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
    @Test fun continuousRevisitRetainsPagedWindowAndDraftWithoutHistoryRead() = runBlocking<Unit> {
        server.pagedHistory = true
        repo.reloadConversation().join()
        repo.older().join()
        repo.editDraft("retained draft")
        repo.scroll(7, 31, false, "stable-item")
        repo.toggleExpansion("stable-item")
        val before = repo.state.value
        val reads = server.calls.count { it.nativeMethod == "thread/read" }
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertEquals(reads + 1, server.calls.count { it.nativeMethod == "thread/read" })
        assertEquals(before.nextTurns, repo.state.value.nextTurns)
        assertEquals(before.historyExhausted, repo.state.value.historyExhausted)
        assertEquals(ConversationLoadState.Ready, repo.state.value.conversationLoadState)
        assertEquals("retained draft", repo.state.value.draft?.text)
        assertEquals(7, repo.state.value.draft?.scrollIndex)
        assertEquals(31, repo.state.value.draft?.scrollOffset)
        assertEquals("stable-item", repo.state.value.draft?.scrollItemKey)
        assertEquals(before.draft?.expansions, repo.state.value.draft?.expansions)
        repo.reloadConversation().join()
        assertEquals(reads + 2, server.calls.count { it.nativeMethod == "thread/read" })
    }

    @Test fun extensionRuntimeCacheSkipsReadsButExplicitRefreshBypassesIt() = runBlocking<Unit> {
        update { s -> s.copy(runtimes = s.runtimes.map { it.copy(capabilities = (it.capabilities ?: emptySet()) + "extensions/list") }) }
        server.intercept = { method, _, _ -> if (method == "extensions/list") buildJsonObject {
            put("status", "ready"); put("version", "v1"); put("data", buildJsonArray {})
        } else null }
        repo.loadExtensions("runtime").join()
        repo.loadExtensions("runtime").join()
        assertEquals(1, server.calls.count { it.method == "extensions/list" })
        repo.refreshExtensions().join()
        assertEquals(2, server.calls.count { it.method == "extensions/list" })
    }

    @Test fun cachedExtensionRefreshIsLocalAndUnknownAcknowledgementBlocksRow() = runBlocking<Unit> {
        update { s -> s.copy(runtimes = s.runtimes.map { it.copy(capabilities = (it.capabilities ?: emptySet()) + "extensions/list") }) }
        val inventory = buildJsonObject {
            put("status", "ready"); put("version", "v1"); put("data", buildJsonArray { add(buildJsonObject {
                put("id", "opaque-id"); put("kind", "mcp"); put("name", "Tool"); put("enabled", true)
                put("capabilities", buildJsonObject { put("update", true) })
            }) })
        }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var blockRead = false
        server.intercept = { method, _, _ -> when (method) {
            "extensions/list" -> { if (blockRead) { entered.complete(Unit); release.await() }; inventory }
            "extensions/update" -> buildJsonObject { put("status", "unknown") }
            else -> null
        } }
        repo.loadExtensions("runtime").join()
        blockRead = true
        val read = repo.refreshExtensions()
        withTimeout(5_000) { entered.await() }
        assertEquals(ExtensionPhase.Ready, repo.state.value.extensions.phase)
        assertTrue(repo.state.value.extensions.refreshing)
        assertFalse(repo.state.value.directoryLoading)
        assertTrue(repo.state.value.online)
        release.complete(Unit); read.join()
        repo.setExtensionEnabled("opaque-id", false).join()
        assertEquals(setOf("opaque-id"), repo.state.value.extensions.uncertainIds)
        assertTrue(repo.state.value.extensions.pendingIds.isEmpty())
        assertEquals(true, repo.state.value.extensions.entries.single().enabled)
        repo.setExtensionEnabled("opaque-id", false).join()
        assertEquals(1, server.calls.count { it.method == "extensions/update" })
    }

    @Test fun runtimeFlightsDoNotLeakLateInventoriesIntoAnotherRuntime() = runBlocking<Unit> {
        update { s -> s.copy(runtimes = s.runtimes.flatMap { r -> listOf(r.copy(capabilities = setOf("extensions/list")), r.copy(id = "runtime-b", capabilities = setOf("extensions/list"))) }) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ -> if (method == "extensions/list") {
            if (params.string("runtimeId") == "runtime") { entered.complete(Unit); release.await() }
            buildJsonObject { put("status", "ready"); put("version", params.string("runtimeId")); put("data", buildJsonArray {}) }
        } else null }
        val first = repo.loadExtensions("runtime")
        withTimeout(5_000) { entered.await() }
        repo.loadExtensions("runtime-b").join()
        release.complete(Unit); first.join()
        assertEquals("runtime-b", repo.state.value.extensions.runtimeId)
        assertEquals("runtime-b", repo.state.value.extensions.version)
        repo.loadExtensions("runtime").join()
        assertEquals("runtime", repo.state.value.extensions.version)
        assertEquals(2, server.calls.count { it.method == "extensions/list" })
    }

    @Test fun revisitKeepsEventsReceivedWhileAnotherChatIsSelected() = runBlocking<Unit> {
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        server.emit("item/started", buildJsonObject {
            put("threadId", "t"); put("turnId", "offscreen")
            put("item", buildJsonObject { put("id", "message"); put("type", "agentMessage"); put("text", "updated offscreen") })
        })
        until { runBlocking { db.dao().items(key).any { it.text == "updated offscreen" } } }
        val reads = server.calls.count { it.nativeMethod == "thread/read" }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertTrue(repo.state.value.timeline.items.values.any { it.text == "updated offscreen" })
        assertEquals(reads, server.calls.count { it.nativeMethod == "thread/read" })
    }

    @Test fun readStartedDuringUpdateCannotClearItsLaterUnknownOutcome() = runBlocking<Unit> {
        update { s -> s.copy(runtimes = s.runtimes.map { it.copy(capabilities = setOf("extensions/list")) }) }
        val inventory = buildJsonObject {
            put("status", "ready"); put("version", "v1"); put("data", buildJsonArray { add(buildJsonObject {
                put("id", "opaque"); put("kind", "mcp"); put("name", "Tool"); put("enabled", true)
                put("capabilities", buildJsonObject { put("update", true) })
            }) })
        }
        val updating = CompletableDeferred<Unit>(); val finishUpdate = CompletableDeferred<Unit>()
        val reading = CompletableDeferred<Unit>(); val finishRead = CompletableDeferred<Unit>()
        var blockRead = false
        server.intercept = { method, _, _ -> when (method) {
            "extensions/list" -> { if (blockRead) { reading.complete(Unit); finishRead.await() }; inventory }
            "extensions/update" -> { updating.complete(Unit); finishUpdate.await(); buildJsonObject { put("status", "unknown") } }
            else -> null
        } }
        repo.loadExtensions("runtime").join()
        val mutation = repo.setExtensionEnabled("opaque", false)
        withTimeout(5_000) { updating.await() }
        blockRead = true
        val read = repo.refreshExtensions()
        withTimeout(5_000) { reading.await() }
        finishUpdate.complete(Unit); mutation.join()
        finishRead.complete(Unit); read.join()
        assertEquals(setOf("opaque"), repo.state.value.extensions.uncertainIds)
        assertTrue(repo.state.value.extensions.pendingIds.isEmpty())
    }

    private suspend fun restrictedHistory() {
        restartOffline(256)
        repo.setForeground(true)
        await { it.online && it.chats.any { c -> c.key == key } }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        for (id in listOf("first", "second")) server.emit("item/started", buildJsonObject {
            put("threadId", "t"); put("turnId", "recorded")
            put("item", buildJsonObject { put("id", id); put("type", "agentMessage"); put("text", "Recorded $id ".repeat(40)) })
        })
        await { it.timeline.items.size == 2 }
    }

    @Test fun oversizedActiveHistorySurvivesLiveDeltaAndAcknowledgedSend() = runBlocking<Unit> {
        restrictedHistory()
        server.emit("item/agentMessage/delta", buildJsonObject {
            put("threadId", "t"); put("turnId", "recorded"); put("itemId", "second"); put("delta", " retained delta")
        })
        await { it.timeline.items.values.any { i -> i.text.endsWith("retained delta") } }
        repo.editDraft("Broker fixture only")
        repo.send().join()
        assertNull(repo.state.value.error)
        assertEquals(setOf("first", "second"), repo.state.value.timeline.items.values.mapTo(hashSetOf()) { it.itemId })
        assertEquals(2, db.dao().cachedItems(key).size)
        assertTrue(repo.state.value.timeline.items.values.any { it.text.endsWith("retained delta") })
    }

    @Test fun delayedWriteToEvictedConversationRecoversRoomInsteadOfEmptyHistory() = runBlocking<Unit> {
        restrictedHistory()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "turn/start") { entered.complete(Unit); release.await() }
            null
        }
        repo.editDraft("Broker fixture only")
        val writing = repo.send()
        withTimeout(5_000) { entered.await() }
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        server.emit("item/started", buildJsonObject {
            put("threadId", "t"); put("turnId", "recorded")
            put("item", buildJsonObject { put("id", "third"); put("type", "agentMessage"); put("text", "Arrived offscreen") })
        })
        until { runBlocking { db.dao().cachedItems(key).size == 3 } }
        release.complete(Unit); writing.join()
        assertEquals(otherKey, repo.state.value.selected?.key)
        assertEquals(setOf("first", "second", "third"), db.dao().cachedItems(key).mapTo(hashSetOf()) { it.itemId })
    }

    @Test fun childOlderPageKeepsWatermarkedEventsAfterCacheEviction() = runBlocking<Unit> {
        restartOffline(256)
        repo.setForeground(true)
        await { it.online && it.chats.any { c -> c.key == key } }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        childLink()
        server.pagedHistory = true
        repo.openAgentReader("child").join()
        val childKey = key.copy(nativeId = "child")
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/turns/list" &&
                params.obj("params").string("threadId") == "child" && params.obj("params")["cursor"] != null) {
                entered.complete(Unit); resume.await()
                buildJsonObject { put("data", JsonArray(emptyList())); put("nextCursor", JsonNull); put("pocketCursor", server.cursor()) }
            } else null
        }
        val older = repo.olderAgent()!!
        try {
            withTimeout(5_000) { entered.await() }
            server.emit("item/started", buildJsonObject {
                put("threadId", "child"); put("turnId", "new-child-turn")
                put("item", buildJsonObject { put("id", "during-read"); put("type", "agentMessage"); put("text", "Recorded child output".repeat(40)) })
            })
            await { it.agentReader?.timeline?.items?.values?.any { item -> item.itemId == "during-read" } == true }
            val cache = PocketRepository::class.java.getDeclaredField("timelines").apply { isAccessible = true }.get(repo) as TimelineCache
            assertNull("The unpinned child must be evicted before the page commits", cache[childKey])
            resume.complete(Unit); older.join()
            assertTrue("An event covered by the page watermark still belongs to the newer window",
                repo.state.value.agentReader!!.timeline.items.values.any { it.itemId == "during-read" })
            assertTrue("Replacement must preserve the durable event", db.dao().cachedItems(childKey).any { it.itemId == "during-read" })
            assertEquals(key, repo.state.value.selected?.key)
            assertTrue(server.calls.none { it.nativeMethod in setOf("turn/start", "turn/steer", "thread/queue/add") })
        } finally { resume.complete(Unit); older.join() }
    }

    /** Switch immediately after a successful publication, before its caller captures window metadata. */
    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    private fun switchAfterWindowPublication(destination: PocketState, cursor: String): java.util.concurrent.atomic.AtomicBoolean {
        val field = PocketRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val original = field.get(repo) as MutableStateFlow<PocketState>
        val switched = java.util.concurrent.atomic.AtomicBoolean()
        field.set(repo, object : MutableStateFlow<PocketState> by original {
            override fun compareAndSet(expect: PocketState, update: PocketState): Boolean {
                val accepted = original.compareAndSet(expect, update)
                if (accepted && update.selected?.key == key && update.conversationLoadState == ConversationLoadState.Ready &&
                    update.nextTurns == JsonPrimitive(cursor) && switched.compareAndSet(false, true)) original.value = destination
                return accepted
            }
        })
        return switched
    }

    @Test fun hydratedWindowCannotCaptureAnotherSelectedThreadCursor() = runBlocking<Unit> {
        server.pagedHistory = true
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/turns/list") buildJsonObject {
                put("data", JsonArray(emptyList())); put("pocketCursor", server.cursor())
                put("nextCursor", if (params.obj("params").string("threadId") == "t") JsonPrimitive("a-window") else JsonNull)
            } else null
        }
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        val destination = repo.state.value
        val switched = switchAfterWindowPublication(destination, "a-window")
        repo.select(repo.state.value.chats.first { it.key == key }, forceRefresh = true).join()
        assertTrue("Exercise the publication/capture gap", switched.get())
        assertEquals(otherKey, repo.state.value.selected?.key)
        val reads = server.calls.count { it.nativeMethod == "thread/read" }
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertEquals("Retain A's cursor even when B was selected during capture", JsonPrimitive("a-window"), repo.state.value.nextTurns)
        assertFalse(repo.state.value.historyExhausted)
        assertEquals("The correctly bound ready window remains reusable", reads, server.calls.count { it.nativeMethod == "thread/read" })
    }

    @Test fun olderWindowCannotCaptureAnotherSelectedThreadCursor() = runBlocking<Unit> {
        server.pagedHistory = true
        server.intercept = { method, params, _ ->
            if (method == "codex/request" && params.string("method") == "thread/turns/list") buildJsonObject {
                put("data", JsonArray(emptyList())); put("pocketCursor", server.cursor())
                put("nextCursor", when {
                    params.obj("params").string("threadId") != "t" -> JsonNull
                    params.obj("params")["cursor"] != null -> JsonPrimitive("a-older")
                    else -> JsonPrimitive("a-first")
                })
            } else null
        }
        repo.reloadConversation().join()
        repo.select(repo.state.value.chats.first { it.key == otherKey }).join()
        val destination = repo.state.value
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        val switched = switchAfterWindowPublication(destination, "a-older")
        repo.older().join()
        assertTrue("Exercise the older-page publication/capture gap", switched.get())
        assertEquals(otherKey, repo.state.value.selected?.key)
        repo.select(repo.state.value.chats.first { it.key == key }).join()
        assertEquals(JsonPrimitive("a-older"), repo.state.value.nextTurns)
        assertFalse(repo.state.value.historyExhausted)
    }

    @Test fun homeReturnUsesFreshDirectoryWithoutMetadataReload() = runBlocking<Unit> {
        repo.refresh().join()
        val before = server.calls.size
        repeat(8) { repo.ensureDirectory().join() }
        assertEquals("Returning home must reuse the verified first page", before, server.calls.size)
        assertFalse(repo.state.value.directoryLoading)
        assertFalse(repo.state.value.directoryRefreshing)
        assertTrue(repo.state.value.online)
    }

    @Test fun homeRefreshMergesIdenticalRequestsAndKeepsCachedRowsVisible() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.intercept = { method, _, _ -> if (method == "threads/list") { entered.complete(Unit); release.await() }; null }
        val first = repo.refresh(); withTimeout(5_000) { entered.await() }
        val second = repo.refresh()
        assertSame(first, second)
        assertTrue(repo.state.value.directoryRefreshing)
        assertFalse("Existing rows must not acquire the blocking first-load state", repo.state.value.directoryLoading)
        assertTrue(repo.state.value.chats.any { it.key == key })
        assertTrue(repo.state.value.online)
        release.complete(Unit); first.join()
        assertFalse(repo.state.value.directoryRefreshing)
    }

    @Test fun homeDirectoryTimeoutStopsLoadingWithoutDisconnectOrCacheLoss() = runBlocking<Unit> {
        val field = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        repo = PocketRepository(app, { server.connection() }, storage, db, network, directoryTimeoutMillis = 250)
        await { it.initialized }; repo.setForeground(true); await { it.online }; repo.refresh().join()
        val before = repo.state.value.chats.map { it.key }.toSet()
        server.intercept = { method, _, _ -> if (method == "threads/list") awaitCancellation(); null }
        withTimeout(3_000) { repo.refresh().join() }
        assertFalse(repo.state.value.directoryLoading); assertFalse(repo.state.value.directoryRefreshing)
        assertTrue(repo.state.value.directoryError.orEmpty().contains("超时"))
        assertEquals(before, repo.state.value.chats.map { it.key }.toSet())
        assertTrue(repo.state.value.online); assertNull(repo.state.value.error)
        server.intercept = { _, _, _ -> null }; repo.refresh().join()
        assertNull(repo.state.value.directoryError)
    }

    @Test fun cancelledDirectoryRefreshCannotLeaveHomeSpinnerRunning() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        server.intercept = { method, _, _ -> if (method == "threads/list") { entered.complete(Unit); awaitCancellation() }; null }
        val job = repo.refresh(); withTimeout(5_000) { entered.await() }; job.cancelAndJoin()
        assertFalse(repo.state.value.directoryLoading); assertFalse(repo.state.value.directoryRefreshing)
        assertTrue(repo.state.value.online)
    }

    @Test fun lateRuntimeReadCannotOverwriteReadyNotification() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = AtomicInteger()
        val older = buildJsonObject { put("data", server.runtimes()) }
        server.intercept = { method, _, _ ->
            if (method == "runtimes/list" && held.incrementAndGet() == 1) {
                entered.complete(Unit); release.await(); older
            } else null
        }
        val refreshing = repo.refresh()
        withTimeout(5_000) { entered.await() }
        server.owner = "ready-runtime"
        server.notify("runtimes/changed", buildJsonObject { put("data", server.runtimes()) })
        await { it.runtimes.singleOrNull()?.id == "ready-runtime" }
        release.complete(Unit)
        refreshing.join()
        assertEquals("ready-runtime", repo.state.value.runtimes.single().id)
    }

    @Test fun returningToForegroundRefreshesRuntimeWithoutReplacingHealthyLink() = runBlocking<Unit> {
        repo.setWatch(true)
        repo.setForeground(false)
        val link = server.links.last()
        val before = server.calls.count { it.method == "runtimes/list" }
        repo.setForeground(true)
        until { server.calls.count { it.method == "runtimes/list" } > before }
        assertSame(link, server.links.last())
        assertTrue(repo.state.value.online)
    }

    @Test fun lateOwnershipRecheckCannotOverwriteReadyRuntimeNotification() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val held = AtomicInteger()
        val older = buildJsonObject { put("data", server.runtimes()) }
        server.intercept = { method, _, _ ->
            if (method == "runtimes/list" && held.incrementAndGet() == 1) {
                entered.complete(Unit); release.await(); older
            } else null
        }
        val checking = repo.recheckThreadOwnership()
        withTimeout(5_000) { entered.await() }
        server.owner = "ready-runtime"
        server.notify("runtimes/changed", buildJsonObject { put("data", server.runtimes()) })
        await { it.runtimes.singleOrNull()?.id == "ready-runtime" }
        release.complete(Unit); checking.join()
        assertEquals("ready-runtime", repo.state.value.runtimes.single().id)
    }

    private fun assistantSnapshot(messages: List<Pair<String, String>> = emptyList()) = buildJsonObject {
        put("enabled", true); put("ready", true); put("fresh", true); put("runtimeId", "assistant-runtime"); put("coordinatorThreadId", "coordinator"); put("assistantId", "assistant"); put("paused", false)
        put("operations", buildJsonArray { add(buildJsonObject { put("id", "op-1"); put("role", "Coordinator"); put("phase", "Running"); put("suspended", false) }) })
        put("messages", buildJsonArray { messages.forEach { (id, text) -> add(buildJsonObject { put("id", id); put("role", "Assistant"); put("text", text); put("operationId", "op-1") }) } })
    }
    private fun streamFrame(sequence: Long, kind: String, runtime: String = "assistant-runtime", fields: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("runtimeId", runtime); put("generation", "generation"); put("epoch", "stream-epoch"); put("sequence", sequence); put("kind", kind); fields()
    }
    private fun streamDelta(sequence: Long, text: String, runtime: String = "assistant-runtime") =
        streamFrame(sequence, "coordinator_delta", runtime) { put("operationId", "op-1"); put("threadId", "coordinator"); put("delta", text); put("reset", false) }
    private fun assistantReads() = server.calls.count { it.method == "assistant/read" }
    /** The assistant page was opened on this link, so its snapshot is loaded, as it is whenever frames matter. */
    private suspend fun openAssistant(read: suspend () -> JsonObject = { assistantSnapshot() }) {
        update { it.copy(capabilities = it.capabilities + setOf("assistant.v1", "assistant.stream.v1")) }
        server.intercept = { method, _, _ -> if (method == "assistant/read") read() else null }
        repo.loadAssistant().join()
        assertEquals("assistant-runtime", repo.state.value.assistant.snapshot?.runtimeId)
    }

    @Test fun assistantStreamFramesReachOnlyTheLiveLinkAndTheRuntimeOfTheLoadedSnapshot() = runBlocking<Unit> {
        val syncs = server.calls.count { it.method == "sync" }
        server.notify("assistant/stream", streamDelta(1, "无人打开助理"))
        server.notify("pocket/resync", buildJsonObject {})
        until { server.calls.count { it.method == "sync" } > syncs }
        assertNull(repo.state.value.assistant.streamingCoordinatorOpId)
        assertEquals("An assistant that was never opened is not read in the background", 0, assistantReads())

        openAssistant()
        server.notify("assistant/stream", streamDelta(1, "你"))
        server.notify("assistant/stream", streamDelta(2, "别的运行时", runtime = "another-runtime"))
        server.notify("assistant/stream", buildJsonObject { put("kind", "coordinator_delta"); put("sequence", "2") })
        server.notify("assistant/stream", streamDelta(2, "好"))
        await { it.assistant.streamingCoordinatorText == "你好" }
        assertEquals("op-1", repo.state.value.assistant.streamingCoordinatorOpId)
        assertEquals("Frames in order need no snapshot read", 1, assistantReads())

        val owner = PocketRepository::class.java.getDeclaredField("assistantComputer").apply { isAccessible = true }
        owner.set(repo, "another-computer")
        repo.consumeAssistantStream(streamDelta(3, "别的电脑"))
        owner.set(repo, "computer")
        repo.consumeAssistantStream(streamDelta(3, "被取代的连接")) { false }
        assertEquals("你好", repo.state.value.assistant.streamingCoordinatorText)
        repo.consumeAssistantStream(streamDelta(3, "！"))
        assertEquals("你好！", repo.state.value.assistant.streamingCoordinatorText)
        assertEquals("Dropped frames took no place in the sequence", 1, assistantReads())

        server.failConnections = true
        server.links.last().close()
        val offline = await { !it.online && it.assistant.streamingCoordinatorOpId == null }
        assertEquals("", offline.assistant.streamingCoordinatorText)
        assertNull(offline.assistant.lastStreamEpoch)
        assertNotNull("The last snapshot stays readable offline", offline.assistant.snapshot)
        repo.consumeAssistantStream(streamDelta(4, "断开之后"))
        assertNull(repo.state.value.assistant.streamingCoordinatorOpId)
        assertEquals(0L, repo.state.value.assistant.lastStreamSequence)
        assertEquals(1, assistantReads())
    }

    @Test fun aBurstOfOutOfOrderAssistantFramesSharesOneSnapshotReadAfterTheOneInFlight() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val hold = java.util.concurrent.atomic.AtomicBoolean(false)
        val active = AtomicInteger(); val parallel = AtomicInteger()
        openAssistant {
            val now = active.incrementAndGet(); parallel.updateAndGet { maxOf(it, now) }
            try { if (hold.get()) release.await(); assistantSnapshot() } finally { active.decrementAndGet() }
        }
        server.notify("assistant/stream", streamDelta(1, "你好"))
        await { it.assistant.streamingCoordinatorText == "你好" }
        hold.set(true)
        server.notify("assistant/stream", streamDelta(5, "断档"))
        until { assistantReads() == 2 }
        for (sequence in 1..40L) server.notify("assistant/stream", streamDelta(sequence * 10, "乱序"))
        await { it.assistant.lastStreamSequence == 400L }
        assertEquals("Text from before a gap is dropped, not joined", "乱序", repo.state.value.assistant.streamingCoordinatorText)
        assertEquals(2, assistantReads())
        release.complete(Unit)
        until { assistantReads() == 3 }
        delay(300)
        assertEquals("Forty-one hints cost the read in flight and one after it", 3, assistantReads())
        assertEquals("Snapshot reads never overlap", 1, parallel.get())
    }

    @Test fun aFinishedAssistantReplyStaysUntilTheSnapshotReadAfterItsEndIsStored() = runBlocking<Unit> {
        // Room evidence of an accepted draft and of an action whose outcome is still unconfirmed.
        db.dao().insertAssistantCommand(CommandEntity("accepted-send", "computer", null, "assistant/send", "accepted", 1, AssistantSendReceipt.draft("assistant-runtime", "assistant:assistant", "已接受的草稿")))
        server.statuses["unconfirmed-pause"] = "unknown"
        db.dao().putCommand(CommandEntity("unconfirmed-pause", "computer", null, "assistant/task/pause", "unknown", 1))
        val entered = Channel<Unit>(Channel.UNLIMITED); val releases = Channel<Unit>(Channel.UNLIMITED)
        val hold = java.util.concurrent.atomic.AtomicBoolean(false); val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        openAssistant {
            // The computer answers with what it holds when the request arrives, however late the reply lands.
            val reply = assistantSnapshot(if (finished.get()) listOf("message:final" to "最终回复") else emptyList())
            if (hold.get()) { entered.send(Unit); releases.receive() }
            reply
        }
        update { it.copy(assistant = it.assistant.copy(pendingActions = it.assistant.pendingActions + "assistant/send")) }
        await { it.assistant.sendReceipts.any { r -> r.commandId == "accepted-send" } && "assistant/task/pause" in it.assistant.unconfirmedActions }
        server.notify("assistant/stream", streamDelta(1, "最终"))
        server.notify("assistant/stream", streamDelta(2, "回复"))
        await { it.assistant.streamingCoordinatorText == "最终回复" }

        hold.set(true)
        val poll = repo.loadAssistant()
        withTimeout(5_000) { entered.receive() }
        finished.set(true)
        server.notify("assistant/stream", streamFrame(3, "coordinator_done") { put("operationId", "op-1"); put("threadId", "coordinator"); put("status", "completed") })
        await { it.assistant.streamCoordinatorEnd > 0 }
        assertEquals("The end of the turn alone removes nothing", "最终回复", repo.state.value.assistant.streamingCoordinatorText)
        releases.send(Unit); poll.join()
        assertTrue(repo.state.value.assistant.messages.none { it.id == "message:final" })
        assertEquals("A snapshot requested before the end cannot replace the reply", "最终回复", repo.state.value.assistant.streamingCoordinatorText)

        withTimeout(5_000) { entered.receive() }
        assertEquals("最终回复", repo.state.value.assistant.streamingCoordinatorText)
        releases.send(Unit)
        val stored = await { it.assistant.messages.any { m -> m.id == "message:final" } }.assistant
        assertNull("The message and the live reply are never shown together", stored.streamingCoordinatorOpId)
        assertEquals("", stored.streamingCoordinatorText)
        assertEquals("One poll and one read for the finished turn", 3, assistantReads())
        assertTrue("assistant/send" in stored.pendingActions)
        assertEquals(setOf("assistant/task/pause"), stored.unconfirmedActions)
        assertEquals(listOf("accepted-send"), stored.sendReceipts.map { it.commandId })
        assertEquals("原操作结果尚未确认，正在核对；不会重复提交", stored.error)
    }

    @Test fun resyncReadsTheLoadedAssistantAndItsSnapshotReplacesWhatNoFrameRenewed() = runBlocking<Unit> {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        openAssistant { assistantSnapshot(if (finished.get()) listOf("message:final" to "最终回复") else emptyList()) }
        server.notify("assistant/stream", streamDelta(1, "最终回复"))
        server.notify("assistant/stream", streamFrame(2, "job_stage") { put("operationId", "job-op"); put("taskId", "task-1"); put("threadId", JsonNull); put("status", "running"); put("stage", "command"); put("stageLabel", "运行测试"); put("activeSubagents", 1) })
        await { it.assistant.liveJobStages["task-1"]?.stageLabel == "运行测试" && it.assistant.streamingCoordinatorText == "最终回复" }
        // The frames that ended both were among the notifications the computer had to skip.
        finished.set(true)
        server.notify("pocket/resync", buildJsonObject { put("reason", "event_backlog") })
        val stored = await { it.assistant.messages.any { m -> m.id == "message:final" } }.assistant
        assertNull(stored.streamingCoordinatorOpId)
        assertTrue(stored.liveJobStages.isEmpty())
        assertEquals(2, assistantReads())
    }

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
            @Volatile var closed = false
            val requests = CopyOnWriteArrayList<Call>()
            override val state = MutableStateFlow(LinkState.OFFLINE)
            val notes = Channel<JsonObject>(Channel.UNLIMITED)
            override val notifications = notes.receiveAsFlow()
            override suspend fun connect(config: ConnectionConfig) { attempts.incrementAndGet(); if (failConnections) throw WireException("offline", "Fixture offline"); state.value = LinkState.ONLINE }
            override fun close() { state.value = LinkState.OFFLINE; notes.close(WireException("disconnected", "Fixture disconnected")); closed = true }
            override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
                val call = Call(method, params, commandId)
                calls += call; requests += call
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
