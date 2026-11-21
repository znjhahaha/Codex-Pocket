package dev.codexpocket.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.wire.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class AssistantProjectReadTest {
    private lateinit var repo: PocketRepository
    private lateinit var db: PocketDatabase
    private lateinit var transport: ReadConnection
    private val workspace=AssistantWorkspaceChoice("source","t","Project",configStoreId="store")
    private val chat=ChatEntity(ThreadKey("pc","store","t"),"source","desktop","Project","","",0,"idle",false)
    private fun assign(name:String,value:Any?)=PocketRepository::class.java.getDeclaredField(name).apply {isAccessible=true}.set(repo,value)
    @Suppress("UNCHECKED_CAST") private fun state()=PocketRepository::class.java.getDeclaredField("_state").apply {isAccessible=true}.get(repo) as MutableStateFlow<PocketState>
    private fun generation()=PocketRepository::class.java.getDeclaredField("connectionGeneration").apply {isAccessible=true}.get(repo) as AtomicLong
    private suspend fun receive(value:JsonObject):Unit=suspendCoroutine { continuation ->
        val method=PocketRepository::class.java.getDeclaredMethod("receive",JsonObject::class.java,Continuation::class.java).apply {isAccessible=true}
        val result=method.invoke(repo,value,continuation)
        if(result!==COROUTINE_SUSPENDED) continuation.resume(Unit)
    }
    private fun event()=buildJsonObject {
        put("method","codex/event");put("params",buildJsonObject {
            put("runtimeId","source");put("cursor",buildJsonObject {put("epoch","fixture");put("sequence",1)})
            put("event",buildJsonObject {put("method","thread/settings/updated");put("params",buildJsonObject {
                put("threadId","t");put("threadSettings",buildJsonObject {put("sandboxPolicy",buildJsonObject {put("type","readOnly")})})
            })})
        })
    }
    @Before fun prepare()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<Application>()
        db=Room.inMemoryDatabaseBuilder(app,PocketDatabase::class.java).build()
        val storage=object:PairingStorage {override fun load():PairedComputer?=null;override fun save(pair:PairedComputer)=Unit;override fun forget()=Unit}
        repo=PocketRepository(app,{error("No real connection")},storage,db,emptyFlow())
        withTimeout(5000) {repo.state.first {it.initialized}}
        transport=ReadConnection()
        assign("pair",PairedComputer(ConnectionConfig("wss://fixture.invalid","room","token","00".repeat(32)),"phone","secret","pc","fixture"))
        assign("client",transport);assign("foreground",true);assign("assistantComputer","pc");assign("assistantModelRuntime","assistant")
        state().update {it.copy(paired=true,online=true,computerId="pc",capabilities=setOf("assistant.v1","assistant/project/read"),
            runtimes=listOf(RuntimeInfo("source","desktop","fixture","store",true,setOf("t"),emptySet())),
            assistant=AssistantUiState(AssistantSnapshot.from(transport.assistantReply)))}
        db.dao().putChats(listOf(chat))
        withTimeout(5000) {repo.state.first {it.chats.any {c->c.key==chat.key}}}
    }
    @After fun release()=runBlocking<Unit> {
        val scope=PocketRepository::class.java.getDeclaredField("scope").apply {isAccessible=true}.get(repo) as CoroutineScope
        scope.coroutineContext[Job]!!.cancelAndJoin();db.close()
    }
    private fun writable()=repo.state.value.assistantWorkspaces().firstOrNull {it.selectionKey==workspace.selectionKey}?.canWrite==true

    @Test fun historyNeverGrantsWriteAndSelectedReadUsesOnlyTheExactReadonlyContract()=runBlocking<Unit> {
        assertFalse(writable());assertTrue(transport.calls.isEmpty())
        repo.readAssistantProject(workspace).join()
        assertTrue(writable())
        val call=transport.calls.single()
        assertEquals("assistant/project/read",call.first);assertNull(call.third)
        assertEquals(setOf("runtimeId","sourceRuntimeId","sourceConfigStoreId","threadId"),call.second.keys)
        assertEquals("assistant",call.second.string("runtimeId"));assertEquals("source",call.second.string("sourceRuntimeId"))
        assertEquals("store",call.second.string("sourceConfigStoreId"));assertEquals("t",call.second.string("threadId"))
        assertTrue(db.dao().uncertain("pc").isEmpty())
        repo.loadAssistant(automaticPrepare=false).join()
        assertEquals(1,transport.calls.count {it.first=="assistant/project/read"})
    }
    @Test fun permissionEventRevokesProofAndOrdinaryAssistantReadCannotRestoreIt()=runBlocking<Unit> {
        repo.readAssistantProject(workspace).join();assertTrue(writable())
        receive(event());assertFalse(writable())
        repo.loadAssistant(automaticPrepare=false).join();assertFalse(writable())
        assertEquals(1,transport.calls.count {it.first=="assistant/project/read"})
        transport.projectReply=proof(false)
        repo.readAssistantProject(workspace).join()
        assertFalse(writable());assertNotNull(repo.state.value.assistantProject.proof)
        assertEquals("已核验 · 只读",repo.state.value.assistantWorkspaces().single().permissionStatus)
    }
    @Test fun lateReadAfterPermissionEventCannotRestoreWrite()=runBlocking<Unit> {
        transport.started=CompletableDeferred();transport.release=CompletableDeferred()
        val read=repo.readAssistantProject(workspace)
        withTimeout(5000) {transport.started!!.await()}
        receive(event());transport.release!!.complete(Unit);read.join()
        assertFalse(writable());assertNull(repo.state.value.assistantProject.proof)
        assertFalse(repo.state.value.assistantProject.loading)
    }
    @Test fun sameTransportNewConnectionGenerationCannotAdoptItsOldReply()=runBlocking<Unit> {
        transport.started=CompletableDeferred();transport.release=CompletableDeferred()
        val read=repo.readAssistantProject(workspace)
        withTimeout(5000) {transport.started!!.await()}
        generation().incrementAndGet()
        transport.release!!.complete(Unit);read.join()
        assertFalse(writable())
    }
    @Test fun reconnectInvalidatesSuccessAndDoesNotAutomaticallyRecheck()=runBlocking<Unit> {
        repo.readAssistantProject(workspace).join();assertTrue(writable())
        repo.setForeground(false)
        assign("client",transport);assign("foreground",true);generation().incrementAndGet()
        state().update {it.copy(online=true)}
        repo.loadAssistant(automaticPrepare=false).join()
        assertFalse(writable());assertEquals(1,transport.calls.count {it.first=="assistant/project/read"})
        repo.readAssistantProject(workspace).join();assertTrue(writable())
    }
    @Test fun runtimeNotificationAndOwnerChangeInvalidateWithoutPolling()=runBlocking<Unit> {
        repo.readAssistantProject(workspace).join();assertTrue(writable())
        receive(buildJsonObject {put("method","runtimes/changed");put("params",buildJsonObject {put("data",buildJsonArray {
            add(buildJsonObject {put("id","source");put("source","desktop");put("version","fixture");put("configStoreId","store");put("online",true)})
        })})})
        assertFalse(writable())
        repo.readAssistantProject(workspace).join();assertTrue(writable())
        db.dao().putChats(listOf(chat.copy(runtimeId="new-owner")))
        withTimeout(5000) {repo.state.first {it.chats.firstOrNull()?.runtimeId=="new-owner"}}
        assertFalse(writable());assertNull(repo.state.value.assistantProject.proof)
        assertEquals(2,transport.calls.count {it.first=="assistant/project/read"})
    }
    @Test fun selectionAndAssistantRuntimeChangesFenceLateReplies()=runBlocking<Unit> {
        transport.started=CompletableDeferred();transport.release=CompletableDeferred()
        val read=repo.readAssistantProject(workspace)
        withTimeout(5000) {transport.started!!.await()}
        repo.readAssistantProject(null).join()
        state().update {it.copy(assistant=it.assistant.copy(snapshot=it.assistant.snapshot!!.copy(runtimeId="replacement")))}
        transport.release!!.complete(Unit);read.join()
        assertFalse(writable());assertNull(repo.state.value.assistantProject.proof)
    }
    @Test fun failedMismatchedAndBackgroundReadsStayExplicitlyReadonly()=runBlocking<Unit> {
        transport.fail=true;repo.readAssistantProject(workspace).join()
        assertFalse(writable());assertTrue(repo.state.value.assistantProject.error!!.contains("只读"))
        transport.fail=false;transport.projectReply=JsonObject(proof(true)+("sourceConfigStoreId" to JsonPrimitive("other")))
        repo.readAssistantProject(workspace).join();assertFalse(writable())
        val count=transport.calls.size
        repo.readAssistantProject(workspace.copy(configStoreId="other")).join()
        assign("foreground",false);repo.readAssistantProject(workspace).join()
        assertEquals(count,transport.calls.size);assertFalse(writable())
    }
    @Test fun persistedUnknownRecoverySurvivesReadAndCannotSendAgain()=runBlocking<Unit> {
        val id="1a6fadfb-7344-4d50-88bb-04b68289d2b0"
        db.dao().putCommand(CommandEntity(id,"pc",null,"assistant/respond","unknown",1))
        withTimeout(5000) {repo.state.first {it.commands.any {c->c.id==id}}}
        transport.assistantReply=JsonObject(transport.assistantReply+mapOf("ready" to JsonPrimitive(false),
            "pendingRequests" to buildJsonArray {add(buildJsonObject {put("operationId","original");put("requestId","n:1");put("method","item/tool/call");put("recoveryAction","reject_original_tool_answer")})}))
        repo.loadAssistant(automaticPrepare=false).join()
        assertTrue(repo.state.value.assistant.unconfirmedActions.contains("assistant/respond"))
        assertTrue(repo.state.value.assistant.error!!.contains("尚未确认"))
        repo.assistantAction(AssistantActions.approve("assistant","original","n:1",false)).join()
        assertTrue(transport.calls.none {it.first=="assistant/respond"})
        repo.loadAssistant(automaticPrepare=false).join()
        assertTrue(repo.state.value.assistant.error!!.contains("尚未确认"))
        assertEquals(id,db.dao().uncertain("pc").single().id)
    }
    private class ReadConnection:PocketConnection {
        override val state=MutableStateFlow(LinkState.ONLINE)
        override val notifications=emptyFlow<JsonObject>()
        val calls=CopyOnWriteArrayList<Triple<String,JsonObject,String?>>()
        var projectReply=proof(true)
        var assistantReply=pocketJson.parseToJsonElement("""{"enabled":true,"ready":true,"runtimeId":"assistant","projectAuthorization":[{"sourceRuntimeId":"source","threadId":"t","verified":true,"writable":true,"sourceGeneration":"old","permissionRevision":"old"}]}""").jsonObject
        var started:CompletableDeferred<Unit>?=null
        var release:CompletableDeferred<Unit>?=null
        var fail=false
        override suspend fun connect(config:ConnectionConfig):Unit=error("No real connection")
        override suspend fun request(method:String,params:JsonObject,commandId:String?,timeoutMillis:Long):JsonElement {
            calls+=Triple(method,params,commandId)
            return when(method) {
                "assistant/project/read"->{val result=projectReply;started?.complete(Unit);release?.await();if(fail) throw WireException("unknown","Unknown permissions");result}
                "assistant/read"->assistantReply
                "commands/status"->buildJsonObject {put("status","unknown")}
                else->error("No mutations / model calls: $method")
            }
        }
        override fun close()=Unit
    }
    companion object {
        private fun proof(writable:Boolean)=buildJsonObject {put("sourceRuntimeId","source");put("sourceConfigStoreId","store");put("threadId","t");put("verified",true);put("writable",writable);put("sourceGeneration","source-generation");put("permissionRevision","permission-revision")}
    }
}
