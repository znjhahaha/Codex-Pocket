package dev.codexpocket.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Repository/Room; creation and all reads terminate at this in-memory broker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class)
class CreationPermissionRepositoryTest {
    private lateinit var repo:PocketRepository
    private lateinit var db:PocketDatabase
    private lateinit var server:Fixture
    private val other=ThreadKey("creation-permission-computer","store","other")

    @Before fun setup()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("settings",0).edit().clear().commit()
        db=Room.inMemoryDatabaseBuilder(app,PocketDatabase::class.java).build()
        server=Fixture()
        val saved=PairedComputer(ConnectionConfig("wss://fixture.invalid","room","token","00".repeat(32)),
            "phone","fixture-secret",other.computerId,"Fixture")
        val storage=object:PairingStorage {
            override fun load()=saved
            override fun save(pair:PairedComputer)=Unit
            override fun forget()=Unit
        }
        repo=PocketRepository(app,{server},storage,db,emptyFlow())
        await {it.initialized}
        repo.setForeground(true)
        await {it.online && it.chats.any {chat->chat.key==other}}
        assertNull(repo.state.value.selected)
    }
    @After fun cleanup()=runBlocking<Unit> {
        val field=PocketRepository::class.java.getDeclaredField("scope").apply {isAccessible=true}
        (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        assertEquals(1,server.calls.count {it.method=="codex/request" && it.params.string("method")=="thread/start"})
        assertFalse("Permission loading must not dispatch a turn or permission mutation",server.calls.any {
            it.method=="thread/permissions/update" || it.method=="thread/access/update" ||
                (it.method=="codex/request" && it.params.string("method") in setOf("turn/start","turn/steer","thread/queue/add"))
        })
    }
    private suspend fun await(predicate:(PocketState)->Boolean)=withTimeout(5_000) {repo.state.first(predicate)}
    private suspend fun create() {
        withTimeout(5_000) {repo.create(SessionMode.Cli,"D:/fixture","runtime").join()}
        assertEquals("new",repo.state.value.selected?.key?.nativeId)
        assertNull(repo.state.value.actionStatus["create"]?.error)
    }
    private suspend fun heldCreation():Pair<HeldRead,Job> {
        val held=HeldRead(server.ready("new","obsolete"))
        server.hold.set(held)
        create() // The permission read must not hold creation open.
        return held to withTimeout(5_000) {held.entered.await()}
    }
    @Test fun creationRequiresNativeReadbackBeforeSelectingNewConversation()=runBlocking<Unit> {
        server.rejectNewRead=true
        repo.create(SessionMode.Cli,"D:/fixture","runtime").join()
        assertNotEquals("new",repo.state.value.selected?.key?.nativeId)
        assertNotNull(repo.state.value.actionStatus["create"]?.error)
        assertEquals(1,server.calls.count {it.method=="codex/request" && it.params.string("method")=="thread/start"})
        assertTrue(server.calls.any {it.method=="codex/request" && it.params.string("method")=="thread/read" && it.params.obj("params").string("threadId")=="new"})
    }
    @Test fun projectCreationNamesAndReadsSameNativeThreadBeforeSelecting()=runBlocking<Unit> {
        repo.create(SessionMode.Cli,"D:/fixture","runtime",linkProject=true).join()
        assertNull(repo.state.value.actionStatus["create"]?.error)
        assertEquals("new",repo.state.value.selected?.key?.nativeId)
        val nativeCalls=server.calls.filter {it.method=="codex/request"}
        val start=nativeCalls.single {it.params.string("method")=="thread/start"}
        assertEquals("project",start.params.obj("params").string("projectId"))
        assertTrue(nativeCalls.any {it.params.string("method")=="thread/name/set" &&
            it.params.obj("params").string("threadId")=="new"})
        assertTrue(nativeCalls.any {it.params.string("method")=="project/read"})
    }
    @Test fun projectCreationAcceptsCanonicalReadbackForExtendedWindowsDirectory()=runBlocking<Unit> {
        val chosen="\\\\?\\D:\\fixture"
        repo.create(SessionMode.Cli,chosen,"runtime",linkProject=true).join()
        assertNull(repo.state.value.actionStatus["create"]?.error)
        assertEquals("new",repo.state.value.selected?.key?.nativeId)
        val calls=server.calls.filter {it.method=="codex/request"}
        assertEquals(chosen,calls.single {it.params.string("method")=="project/create"}.params.obj("params").array("roots").first().jsonObject.string("path"))
        assertEquals("project",calls.single {it.params.string("method")=="thread/start"}.params.obj("params").string("projectId"))
    }
    @Test fun recentWindowsDirectoryReusesExistingProjectWithoutDuplicateCreation()=runBlocking<Unit> {
        server.existingProject=true
        repo.create(SessionMode.Cli,"\\\\?\\D:\\fixture","runtime",linkProject=true).join()
        assertNull(repo.state.value.actionStatus["create"]?.error)
        assertEquals("new",repo.state.value.selected?.key?.nativeId)
        assertFalse(server.calls.any {it.method=="codex/request" && it.params.string("method")=="project/create"})
        assertTrue(server.projectRequested)
    }
    @Test fun createLoadsCurrentPermissionSnapshotWithoutReopeningOrSending()=runBlocking<Unit> {
        create()
        val ready=withTimeoutOrNull(5_000) {repo.state.first {it.permissions.status=="ready"}}
        assertNotNull("create must load its selected thread's permission snapshot; calls=${server.calls.map {it.method}}",ready)
        assertEquals("自定义权限",ready!!.permissions.label)
        assertEquals(JsonPrimitive("permission:new"),ready.permissions.revision)
        val read=server.calls.single {it.method=="thread/permissions/read"}
        assertEquals(setOf("runtimeId","threadId"),read.params.keys)
        assertEquals("runtime",read.params.string("runtimeId"))
        assertEquals("new",read.params.string("threadId"))
        assertNull(read.commandId)
        assertTrue(ready.accessAllowsSending())
    }
    @Test fun delayedCreationPermissionCannotOverwriteAnotherSelection()=runBlocking<Unit> {
        val (held,oldRead)=heldCreation()
        repo.select(repo.state.value.chats.first {it.key==other}).join()
        await {it.permissions.revision==JsonPrimitive("permission:other")}
        held.release.complete(Unit)
        withTimeout(5_000) {oldRead.join()}
        assertEquals(other,repo.state.value.selected?.key)
        assertEquals(JsonPrimitive("permission:other"),repo.state.value.permissions.revision)
    }
    @Test fun delayedCreationPermissionCannotCrossConnectionGenerationOnSameTransport()=runBlocking<Unit> {
        val (held,oldRead)=heldCreation()
        val generation=PocketRepository::class.java.getDeclaredField("connectionGeneration").apply {isAccessible=true}.get(repo) as AtomicLong
        generation.incrementAndGet() // A new connection generation with the same transport/selection.
        held.release.complete(Unit)
        withTimeout(5_000) {oldRead.join()}
        assertNull("No second read may hide acceptance of the old generation",repo.state.value.permissions.revision)
        assertNotEquals("ready",repo.state.value.permissions.status)
        assertEquals(1,server.calls.count {it.method=="thread/permissions/read"})
    }
    @Test fun delayedCreationPermissionCannotOverwriteANewerReadTicket()=runBlocking<Unit> {
        val (held,oldRead)=heldCreation()
        repo.refreshPermissions().join()
        assertEquals(JsonPrimitive("permission:new"),repo.state.value.permissions.revision)
        held.release.complete(Unit)
        withTimeout(5_000) {oldRead.join()}
        assertEquals(JsonPrimitive("permission:new"),repo.state.value.permissions.revision)
    }
    @Test fun olderCreateCannotStealNewSelectionsTicketBeforeSendingItsOwnRead()=runBlocking<Unit> {
        val stateField=PocketRepository::class.java.getDeclaredField("_state").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST")
        val original=stateField.get(repo) as MutableStateFlow<PocketState>
        val pause=IssuanceWindow(original)
        stateField.set(repo,pause)
        val ticket=PocketRepository::class.java.getDeclaredField("permissionReadGeneration").apply {isAccessible=true}.get(repo) as AtomicLong
        val heldOther=HeldRead(server.ready("other"))
        server.holdOther.set(heldOther)
        val creating=repo.create(SessionMode.Cli,"D:/fixture","runtime")
        try {
            withTimeout(5_000) {pause.reached.await()}
            val selecting=repo.select(repo.state.value.chats.first {it.key==other})
            val newRead=withTimeout(5_000) {heldOther.entered.await()}
            withTimeout(5_000) {selecting.join()}
            assertTrue(repo.state.value.permissions.loading)
            val newTicket=ticket.get()
            pause.release()
            withTimeout(5_000) {creating.join()}
            heldOther.release.complete(Unit)
            withTimeout(5_000) {newRead.join()}
            assertFalse("Old create stole ticket $newTicket -> ${ticket.get()} and stranded the current read",repo.state.value.permissions.loading)
            assertEquals(JsonPrimitive("permission:other"),repo.state.value.permissions.revision)
            assertEquals(other,repo.state.value.selected?.key)
            assertEquals(newTicket,ticket.get())
            assertEquals(1,server.calls.count {it.method=="thread/permissions/read" && it.params.string("threadId")=="other"})
            assertEquals(0,server.calls.count {it.method=="thread/permissions/read" && it.params.string("threadId")=="new"})
        } finally {
            pause.release()
            heldOther.release.complete(Unit)
        }
    }
    @Test fun failedPermissionReadRetainsCreatedConversationAndHonestUnknown()=runBlocking<Unit> {
        server.permissionReply={throw WireException("request_timeout","fixture permission unavailable")}
        create()
        val failed=await {it.permissions.status=="failed"}
        assertFalse(failed.permissions.loading)
        assertNull(failed.permissions.revision)
        assertTrue(failed.permissions.presets.isEmpty())
        assertEquals("会话权限未同步",failed.permissions.label)
        assertTrue(failed.accessAllowsSending())
        assertNull(failed.actionStatus["create"]?.error)
    }
    @Test fun wrongPermissionIdentityCannotGrantCreatedThreadPermissions()=runBlocking<Unit> {
        server.permissionReply={id->JsonObject(server.ready(id)+("runtimeId" to JsonPrimitive("another-runtime")))}
        create()
        val failed=await {it.permissions.status=="failed"}
        assertNull(failed.permissions.revision)
        assertTrue(failed.permissions.presets.isEmpty())
        assertEquals("new",failed.selected?.key?.nativeId)
    }
    @Test fun initializingPermissionResponseIsNotPromotedToReady()=runBlocking<Unit> {
        server.permissionReply={id->buildJsonObject {put("runtimeId","runtime");put("threadId",id);put("status","initializing")}}
        create()
        val pending=await {it.permissions.status=="initializing"}
        assertEquals("正在同步权限",pending.permissions.label)
        assertNull(pending.permissions.revision)
        assertTrue(pending.permissions.presets.isEmpty())
        assertEquals(1,server.calls.count {it.method=="thread/permissions/read"})
    }

    private data class Call(val method:String,val params:JsonObject,val commandId:String?)
    private class HeldRead(val response:JsonObject) {
        val entered=CompletableDeferred<Job>()
        val release=CompletableDeferred<Unit>()
    }
    /** Pause the last view check after capturing A, before its ticket can be claimed.
     * The delegate still publishes B normally; no production hook or second read is used. */
    private class IssuanceWindow(private val delegate:MutableStateFlow<PocketState>):MutableStateFlow<PocketState> by delegate {
        val reached=CompletableDeferred<Unit>()
        private val resume=CountDownLatch(1)
        private val armed=AtomicBoolean(true)
        override var value:PocketState
            get() {
                val captured=delegate.value
                if(captured.selected?.key?.nativeId=="new" && captured.online && armed.get()) {
                    val frames=Thread.currentThread().stackTrace.filter {it.className==PocketRepository::class.java.name}
                    if(frames.any {it.methodName.startsWith("refreshPermissions") && it.methodName.contains("viewCurrent")} && frames.none {it.methodName=="isSelected"} && armed.compareAndSet(true,false)) {
                        reached.complete(Unit)
                        check(resume.await(10,TimeUnit.SECONDS)) {"Issuance interleaving was not released"}
                    }
                }
                return captured
            }
            set(value) {delegate.value=value}
        fun release() {resume.countDown()}
    }
    private class Fixture:PocketConnection {
        override val state=MutableStateFlow(LinkState.OFFLINE)
        override val notifications=emptyFlow<JsonObject>()
        val calls=CopyOnWriteArrayList<Call>()
        val hold=AtomicReference<HeldRead?>()
        val holdOther=AtomicReference<HeldRead?>()
        @Volatile var permissionReply:(String)->JsonObject={ready(it)}
        @Volatile var rejectNewRead=false
        @Volatile var projectMaterialized=false
        @Volatile var projectRequested=false
        @Volatile var existingProject=false
        override suspend fun connect(config:ConnectionConfig) {state.value=LinkState.ONLINE}
        override fun close() {state.value=LinkState.OFFLINE}
        fun ready(id:String,revision:String="permission:$id")=buildJsonObject {
            put("runtimeId","runtime");put("threadId",id);put("status","ready");put("revision",revision)
            put("currentPresetId","custom");put("currentLabel","自定义权限")
            put("presets",buildJsonArray {add(buildJsonObject {put("id","request");put("label","请求批准");put("enabled",true)})})
        }
        private fun runtimes()=buildJsonArray {add(buildJsonObject {
            put("id","runtime");put("source","cli");put("configStoreId","store");put("online",true)
            put("loadedThreadIds",buildJsonArray {add("new");add("other")})
            put("capabilities",buildJsonArray {listOf("thread/start","thread/read","thread/name/set","project/list","project/read","project/create","model/list","collaborationMode/list").forEach {add(it)}})
            put("capabilityFields",buildJsonObject {put("thread/start",buildJsonArray {add("cwd");add("projectId")})})
        })}
        private fun thread(id:String)=buildJsonObject {put("id",id);put("name",id);put("cwd","D:/fixture");put("turns",buildJsonArray {}); if(id=="new" && projectMaterialized) put("projectId","project")}
        @OptIn(ExperimentalCoroutinesApi::class)
        override suspend fun request(method:String,params:JsonObject,commandId:String?,timeoutMillis:Long):JsonElement {
            calls+=Call(method,params,commandId)
            return when(method) {
                "hello"->buildJsonObject {
                    put("protocol",1);put("computerId","creation-permission-computer");put("runtimes",runtimes())
                    put("capabilities",buildJsonArray {listOf("thread/access/read","thread/permissions/read").forEach {add(it)}})
                }
                "sync"->buildJsonObject {put("events",buildJsonArray {});put("cursors",buildJsonObject {});put("pendingRequests",buildJsonArray {})}
                "runtimes/list"->buildJsonObject {put("data",runtimes())}
                "threads/list"->buildJsonObject {put("data",buildJsonArray {add(buildJsonObject {put("id","other");put("runtimeId","runtime");put("configStoreId","store");put("source","cli")})});put("nextCursor",JsonNull)}
                "thread/access/read"->buildJsonObject {put("mode","cli");put("source","cli");put("ownerRuntimeId","runtime");put("phase","ready");put("canSend",true)}
                "thread/settings/read"->buildJsonObject {put("runtimeId","runtime");put("threadId",params.string("threadId"));put("known",true);put("settings",buildJsonObject {put("model","model")})}
                "thread/permissions/read"->{
                    check(commandId==null)
                    val held=when(params.string("threadId")) {
                        "new"->hold.getAndSet(null)
                        "other"->holdOther.getAndSet(null)
                        else->null
                    }
                    if(held!=null) {
                        // request runs in withTimeout; join its outer read Job to observe adoption,
                        // not just network return, without a second read masking a stale response.
                        held.entered.complete(currentCoroutineContext()[Job]!!.parent!!)
                        held.release.await()
                        held.response
                    } else permissionReply(params.string("threadId"))
                }
                "codex/request"->when(params.string("method")) {
                    "project/list"->buildJsonObject {put("data",buildJsonArray {if(existingProject) add(buildJsonObject {put("id","project");put("roots",buildJsonArray {add(buildJsonObject {put("path","D:/fixture")})})})});put("nextCursor",JsonNull)}
                    "project/create","project/read"->buildJsonObject {put("project",buildJsonObject {put("id","project");put("roots",buildJsonArray {add(buildJsonObject {put("path","D:/fixture")})})})}
                    "thread/name/set"->{check(commandId!=null);projectMaterialized=projectRequested;buildJsonObject {}}
                    "thread/start"->{check(commandId!=null);projectRequested=params.obj("params").string("projectId")=="project";buildJsonObject {put("thread",thread("new"))}}
                    "thread/read"->{
                        if(rejectNewRead && params.obj("params").string("threadId")=="new") throw WireException("runtime_offline","Readback unavailable")
                        buildJsonObject {put("thread",thread(params.obj("params").string("threadId")));put("pocketCursor",buildJsonObject {put("epoch","fixture");put("sequence",0)})}
                    }
                    "model/list"->buildJsonObject {put("data",buildJsonArray {add(buildJsonObject {put("id","model");put("model","model");put("displayName","Fixture model")})});put("nextCursor",JsonNull)}
                    "collaborationMode/list"->buildJsonObject {put("data",buildJsonArray {})}
                    else->error("Unexpected native request: ${params.string("method")}")
                }
                else->buildJsonObject {}
            }
        }
    }
}
