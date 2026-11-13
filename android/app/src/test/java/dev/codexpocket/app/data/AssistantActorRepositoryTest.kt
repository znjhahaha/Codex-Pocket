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
        repo.loadAssistant(automaticPrepare=false).join()
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

    @Test fun unpreparedAssistantIsPreparedOnceWithoutARunningRuntimeSelection()=runBlocking<Unit> {
        transport.snapshot=buildJsonObject {put("enabled",false);put("ready",false)}
        transport.preparation=actorSnapshot()
        repo.loadAssistant().join()
        assertEquals(listOf("assistant/read","assistant/prepare","codex/request"),transport.calls.map {it.first})
        assertTrue(transport.calls.single {it.first=="assistant/prepare"}.second.isEmpty())
        assertEquals(true,repo.state.value.assistant.snapshot?.ready)
        assertNull(repo.state.value.assistant.error)
    }

    @Test fun staleUnpreparedReadCannotPrepareAReplacementConnection()=runBlocking<Unit> {
        transport.snapshot=buildJsonObject {put("enabled",false);put("ready",false)}
        transport.readStarted=CompletableDeferred()
        transport.readRelease=CompletableDeferred()
        val poll=repo.loadAssistant()
        withTimeout(5000) {transport.readStarted!!.await()}
        val replacement=ActorConnection().apply {preparation=actorSnapshot()}
        val replacementState=AssistantUiState(error="replacement connection state")
        assign("client",replacement)
        state().update {it.copy(assistant=replacementState)}
        transport.readRelease!!.complete(Unit)
        withTimeout(5000) {poll.join()}

        assertTrue("A stale read must not send preparation to the new connection",replacement.calls.isEmpty())
        assertEquals(replacementState,repo.state.value.assistant)
        assertEquals(listOf("assistant/read"),transport.calls.map {it.first})
    }

    @Test fun preparationDuringOlderReadPreservesTheRequestedPageAndCursor()=runBlocking<Unit> {
        repo.loadAssistant().join()
        val newer=repo.state.value.assistant.messages
        val page=pocketJson.parseToJsonElement("""{"messages":[{"id":"older","role":"User","text":"更早的消息","atMs":100}],"nextBefore":1}""")
        transport.olderPage=page
        transport.snapshot=JsonObject(actorSnapshot()+mapOf("ready" to JsonPrimitive(false)))
        transport.preparation=actorSnapshot()

        repo.assistantAction(AssistantActions.read(17,before=2,limit=1)).join()

        assertEquals(listOf("older")+newer.map {it.id},repo.state.value.assistant.messages.map {it.id})
        assertEquals(1L,repo.state.value.assistant.nextBefore)
        assertTrue(repo.state.value.assistant.historyPaged)
        assertTrue(repo.state.value.assistant.snapshot!!.prepared)
        assertTrue(transport.calls.single {it.first=="assistant/prepare"}.second.isEmpty())
    }

    @Test fun olderConversationPageMergesWithoutDroppingNewerMessagesOrAuthorship()=runBlocking<Unit> {
        repo.loadAssistant().join()
        val newer=repo.state.value.assistant.messages
        transport.snapshot=JsonObject(actorSnapshot()+mapOf("conversationPage" to pocketJson.parseToJsonElement("""{"messages":[{"id":"older","role":"Assistant","text":"研究结果","at_ms":100,"authorName":"研究员","member_id":"m","source_thread_id":"native"}],"nextBefore":null}""")))
        repo.assistantAction(AssistantActions.read(17,before=2,limit=1)).join()
        assertEquals(listOf("older")+newer.map {it.id},repo.state.value.assistant.messages.map {it.id})
        assertEquals("研究员",repo.state.value.assistant.messages.first().authorName)
        assertEquals("native",repo.state.value.assistant.messages.first().sourceThreadId)
        assertNull(repo.state.value.assistant.nextBefore)
        assertEquals(2L,transport.calls.last {it.first=="assistant/read"}.second.number("before"))
        transport.snapshot=actorSnapshot()
        repo.loadAssistant().join()
        assertTrue(repo.state.value.assistant.messages.any {it.id=="older"})
        assertNull(repo.state.value.assistant.nextBefore)
    }

    @Test fun initialSnapshotExposesProductSequenceCursorWithoutAConversationPage()=runBlocking<Unit> {
        transport.snapshot=JsonObject(actorSnapshot()+mapOf("nextBefore" to JsonPrimitive(321)))
        repo.loadAssistant().join()
        assertEquals(321L,repo.state.value.assistant.nextBefore)
        assertFalse(repo.state.value.assistant.historyPaged)
    }

    @Test fun explicitPageAndTaskReadQueueBehindDelayedPollInsteadOfDisappearing()=runBlocking<Unit> {
        transport.readStarted=CompletableDeferred()
        transport.readRelease=CompletableDeferred()
        val poll=repo.loadAssistant()
        withTimeout(5000) {transport.readStarted!!.await()}
        val page=repo.assistantAction(AssistantActions.read(0,before=123,limit=50))
        val task=repo.assistantAction(AssistantActions.read(0,taskId="task"))
        delay(100)
        assertFalse("Explicit reads must wait for the poll",page.isCompleted || task.isCompleted)
        transport.readRelease!!.complete(Unit)
        withTimeout(5000) {joinAll(poll,page,task)}
        val reads=transport.calls.filter {it.first=="assistant/read"}
        assertEquals(3,reads.size)
        assertEquals(123L,reads.single {it.second.containsKey("before")}.second.number("before"))
        assertEquals("task",reads.single {it.second.containsKey("taskId")}.second.string("taskId"))
    }

    @Test fun accountAndConfigurationPreparationFailuresNeverClearErrorDespiteReadyWorker()=runBlocking<Unit> {
        for((status,code,hint) in listOf(Triple("needs_login","assistant_needs_login","登录"),Triple("missing_cli","assistant_missing_cli","CLI"),
            Triple("missing_config","assistant_missing_config","配置"),Triple("recoverable_error","assistant_prepare_failed","重试"))) {
            transport.snapshot=JsonObject(actorSnapshot()+mapOf("preparationStatus" to JsonPrimitive(status),"error" to JsonPrimitive(code)))
            repo.loadAssistant().join()
            val current=repo.state.value.assistant
            assertEquals(true,current.snapshot!!.ready)
            assertFalse(current.snapshot!!.prepared)
            assertTrue(current.error!!.contains(hint))
            repo.assistantAction(AssistantActions.send("managed","暂不可发送",null)).join()
            assertTrue(transport.calls.none {it.first=="assistant/send"})
        }
    }

    private class ActorConnection:PocketConnection {
        override val state=MutableStateFlow(LinkState.ONLINE)
        override val notifications=emptyFlow<JsonObject>()
        var snapshot=actorSnapshot()
        var preparation: JsonObject? = null
        var olderPage: JsonElement? = null
        var readStarted:CompletableDeferred<Unit>?=null
        var readRelease:CompletableDeferred<Unit>?=null
        val calls=CopyOnWriteArrayList<Pair<String,JsonObject>>()
        override suspend fun connect(config:ConnectionConfig):Unit=error("Fixture must never connect")
        override suspend fun request(method:String,params:JsonObject,commandId:String?,timeoutMillis:Long):JsonElement {
            calls+=method to params
            return when(method) {
                "assistant/read"->{
                    readStarted?.complete(Unit);readRelease?.await()
                    if(params.containsKey("before") && olderPage!=null) JsonObject(snapshot+mapOf("conversationPage" to olderPage!!)) else snapshot
                }
                "assistant/prepare"-> {
                    assertFalse(params.containsKey("runtimeId"))
                    preparation?.also {snapshot=it} ?: throw WireException("unsupported", "Preparation unavailable")
                }
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
