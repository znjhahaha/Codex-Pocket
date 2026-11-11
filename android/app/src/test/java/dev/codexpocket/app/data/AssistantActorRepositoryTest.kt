package dev.codexpocket.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

/** snapshot_value + Worker::snapshot serde shape from assistant_product.rs; no live Actor or inference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class AssistantActorRepositoryTest {
    private lateinit var repo:PocketRepository
    private lateinit var db:PocketDatabase
    private lateinit var transport:ActorConnection
    private fun assign(name:String,value:Any?)=PocketRepository::class.java.getDeclaredField(name).apply {isAccessible=true}.set(repo,value)
    @Suppress("UNCHECKED_CAST")
    private fun state()=PocketRepository::class.java.getDeclaredField("_state").apply {isAccessible=true}.get(repo) as MutableStateFlow<PocketState>

    @Before fun prepare()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<Application>()
        db=Room.inMemoryDatabaseBuilder(app,PocketDatabase::class.java).build()
        val storage=object:PairingStorage {override fun load():PairedComputer?=null;override fun save(pair:PairedComputer)=Unit;override fun forget()=Unit}
        repo=PocketRepository(app,{error("Fixture must never connect")},storage,db,emptyFlow())
        withTimeout(5_000) {repo.state.first {it.initialized}}
        transport=ActorConnection()
        assign("pair",PairedComputer(ConnectionConfig("wss://fixture.invalid","room","fixture-token","00".repeat(32)),"phone","fixture-secret","pc","fixture"))
        assign("client",transport);assign("assistantComputer","pc")
        state().update {it.copy(paired=true,online=true,computerId="pc",capabilities=setOf("assistant.v1"))}
    }
    @After fun release()=runBlocking<Unit> {
        val scope=PocketRepository::class.java.getDeclaredField("scope").apply {isAccessible=true}.get(repo) as CoroutineScope
        scope.coroutineContext[Job]!!.cancelAndJoin();db.close()
    }

    @Test fun workerReadProjectsActualMessagesQuestionsResultsAndManagedModelDirectory()=runBlocking<Unit> {
        repo.loadAssistant().join()
        val current=repo.state.value
        assertNull(current.assistant.error)
        val value=current.assistant.snapshot!!
        assertEquals("managed",value.runtimeId)
        assertEquals("coordinator",value.coordinatorThreadId)
        assertEquals(AssistantTaskStatus.WaitingUser,value.tasks.single().status)
        assertEquals(AssistantQuestionKind.Approval,value.operations.single().question!!.kind)
        assertEquals(17L,value.after)
        assertEquals(listOf("用户实际发送的文本","协调器实际回复"),current.assistant.messages.map {it.text})
        assertEquals("op",current.assistant.messages.last().operationId)
        assertEquals(1_893_456_000_123L,current.assistant.messages.first().atMs)
        assertFalse(current.assistant.toString().contains("PRIVATE_FIXTURE"))
        assertEquals(listOf("default"),current.assistantModels.map {it.model})
        val modelRead=transport.calls.single {it.first=="codex/request"}
        assertEquals("managed",modelRead.second.string("runtimeId"))
        assertEquals("model/list",modelRead.second.string("method"))
        assertEquals(0L,transport.calls.first().second.number("after"))
    }

    @Test fun disabledActorHasEmptyDirectoryUntilARealManagedRuntimeIsReturned()=runBlocking<Unit> {
        transport.snapshot=buildJsonObject {put("enabled",false);put("ready",false)}
        repo.loadAssistant().join()
        assertEquals(false,repo.state.value.assistant.snapshot!!.enabled)
        assertTrue(repo.state.value.assistantModels.isEmpty())
        assertEquals(listOf("assistant/read"),transport.calls.map {it.first})
        transport.snapshot=actorSnapshot()
        repo.loadAssistant().join()
        assertEquals(listOf("default"),repo.state.value.assistantModels.map {it.model})
    }

    @Test fun actorNotReadyRetainsReadableFieldsButDoesNotClaimWritableService()=runBlocking<Unit> {
        transport.snapshot=JsonObject(actorSnapshot()+mapOf("ready" to JsonPrimitive(false),"runtimeReady" to JsonPrimitive(false),
            "error" to JsonPrimitive("runtime_unavailable_or_unsupported")))
        repo.loadAssistant().join()
        assertNotNull(repo.state.value.assistant.error)
        assertEquals(2,repo.state.value.assistant.messages.size)
        assertTrue(repo.state.value.assistant.snapshot!!.enabled==true)
        assertEquals(0L,repo.state.value.assistant.sendAcknowledgement)
    }

    private class ActorConnection:PocketConnection {
        override val state=MutableStateFlow(LinkState.ONLINE)
        override val notifications=emptyFlow<JsonObject>()
        var snapshot=actorSnapshot()
        val calls=CopyOnWriteArrayList<Pair<String,JsonObject>>()
        override suspend fun connect(config:ConnectionConfig):Unit=error("Fixture must never connect")
        override suspend fun request(method:String,params:JsonObject,commandId:String?,timeoutMillis:Long):JsonElement {
            calls+=method to params
            return when(method) {
                "assistant/read"->snapshot
                "codex/request"->{require(params.string("method")=="model/list");buildJsonObject {
                    put("data",buildJsonArray {
                        add(buildJsonObject {put("id","directory-id");put("model","default");put("displayName","实际目录模型");put("isDefault",true)})
                        add(buildJsonObject {put("id","hidden");put("model","hidden");put("hidden",true)})
                    });put("nextCursor",JsonNull)
                }}
                else->error("No model calls or mutations in Actor read fixture: $method")
            }
        }
        override fun close()=Unit
    }
    companion object {
        private fun actorSnapshot()=pocketJson.parseToJsonElement("""{
          "enabled":true,"ready":true,"runtimeId":"managed","coordinatorThreadId":"coordinator","assistantId":"assistant",
          "paused":false,"fresh":true,"snapshotSource":"worker_read","updatedAtMs":1893456000123,"revision":9,
          "runtimeReady":true,"reconciliationRequired":false,"unconfirmedWrites":0,"unsupportedOperation":null,"error":null,
          "settings":{"personality":"认真","tone":"简洁","model":null,"timezone":"UTC","notifications":{"completed":true,"failed":true,"waiting_user":true}},
          "tasks":[{"id":"task","assistant_id":"assistant","prompt":"整理项目","directory":"PRIVATE_FIXTURE_DIRECTORY","writes":false,"not_before_ms":0,"status":"waiting_user","active_run_id":"op","reconciliation_required":false,"detail":"请审批"}],
          "operations":[{"id":"op","role":"Execution","task_id":"task","mapping":{"host_generation":"PRIVATE_FIXTURE_GENERATION","thread_id":"execution","turn_id":"turn"},"expected_generation":"PRIVATE_FIXTURE_GENERATION","phase":"Waiting","suspended":false,"question":{"request_id":"request","kind":"Approval","text":"允许这次操作？"},"input_tool_call":null,"result":null,"authorized_directories":["PRIVATE_FIXTURE_DIRECTORY"],"prompt":"PRIVATE_FIXTURE_PROMPT"}],
          "events":[{"sequence":17,"id":"event","operation_id":null,"kind":"user_message","detail":"Explicit user handoff queued","notify":false,"wake_coordinator":true,"at_ms":1893456000123}],"eventCursor":17,
          "memories":[{"id":"memory","text":"使用中文"}],"schedules":[],
          "messages":[{"sequence":1,"id":"user:command","role":"User","text":"用户实际发送的文本","atMs":1893456000123,"operationId":null,"sourceEventSequence":null},{"sequence":2,"id":"event:summary","role":"Assistant","text":"协调器实际回复","atMs":1893456000223,"operationId":"op","sourceEventSequence":16}],
          "pendingRequests":[{"operationId":"op","requestId":"request","method":"item/commandExecution/requestApproval","params":{"PRIVATE_FIXTURE_PARAMS":true}}],
          "projectAuthorization":[{"threadId":"authorized","directory":"PRIVATE_FIXTURE_DIRECTORY","writable":true,"verified":true}]
        }""").jsonObject
    }
}
