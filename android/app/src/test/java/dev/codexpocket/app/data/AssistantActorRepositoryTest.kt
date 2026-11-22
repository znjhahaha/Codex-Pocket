package dev.codexpocket.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.wire.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
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
    private lateinit var queryExecutor:PausingExecutor
    private fun assign(name:String,value:Any?)=PocketRepository::class.java.getDeclaredField(name).apply {isAccessible=true}.set(repo,value)
    @Suppress("UNCHECKED_CAST")
    private fun state()=PocketRepository::class.java.getDeclaredField("_state").apply {isAccessible=true}.get(repo) as MutableStateFlow<PocketState>

    @Before fun prepare()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<Application>()
        queryExecutor=PausingExecutor()
        db=Room.inMemoryDatabaseBuilder(app,PocketDatabase::class.java).setQueryExecutor(queryExecutor).build()
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
        queryExecutor.resume()
        scope.coroutineContext[Job]!!.cancelAndJoin();db.close();queryExecutor.close()
    }

    @Test fun assistantAttachmentUploadsBeforeBoundSendAndKeepsDraftWhenUploadFails()=runBlocking<Unit> {
        repo.loadAssistant().join()
        val app=ApplicationProvider.getApplicationContext<Application>()
        val a=LocalAttachment(java.util.UUID.randomUUID().toString(),"fixture.png","image/png")
        assistantAttachmentFile(app,a).apply {parentFile!!.mkdirs();writeBytes(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10,1))}
        transport.uploadFailure=true
        val rejected=boundSend().copy(attachments=listOf(a))
        repo.assistantAction(rejected).join()
        assertTrue(transport.calls.none {it.first=="assistant/send"})
        assertEquals("rejected",db.dao().assistantSendReceipts().first().single {it.id==rejected.commandId}.status)
        assertTrue(assistantAttachmentFile(app,a).exists())
        transport.uploadFailure=false
        val accepted=boundSend().copy(commandId=java.util.UUID.randomUUID().toString(),attachments=listOf(a))
        repo.assistantAction(accepted).join()
        val sent=transport.calls.single {it.first=="assistant/send"}.second
        assertEquals("pocketImage",sent.array("attachments").single().jsonObject.string("type"))
        assertFalse(sent.toString().contains("base64"));assertFalse(sent.toString().contains(app.filesDir.path))
        assertTrue(assistantAttachmentFile(app,a).exists())
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
        assertTrue(repo.state.value.assistant.sendReceipts.isEmpty())
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

        repo.loadAssistant(AssistantActions.read(17,before=2,limit=1).params,automaticPrepare=true,explicitRead=true).join()

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
            Triple("missing_config","assistant_missing_config","配置"),Triple("recoverable_error","assistant_prepare_failed","重试"),
            Triple("recoverable_error","assistant_unavailable_on_device","此手机"))) {
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

    @Test fun optionalModelCatalogFailureDoesNotInvalidateAReadableWaitingQuestion()=runBlocking<Unit> {
        transport.modelFailure=true
        repo.loadAssistant().join()
        val state=repo.state.value.assistant
        assertEquals(AssistantOperationPhase.Waiting,state.snapshot!!.operations.single().phase)
        assertNull("Model catalog failure must not disable the question",state.error)
    }

    @Test fun transientReadFailureKeepsTheLastWaitingSnapshotUsable()=runBlocking<Unit> {
        repo.loadAssistant().join()
        val snapshot=repo.state.value.assistant.snapshot
        transport.readFailure=true
        repo.loadAssistant().join()
        assertEquals(snapshot,repo.state.value.assistant.snapshot)
        assertNull("A read failure is not a command or preparation failure",repo.state.value.assistant.error)
    }

    @Test fun explicitRefreshDoesNotPrepareAnUnreadyAssistant()=runBlocking<Unit> {
        transport.snapshot=JsonObject(actorSnapshot()+mapOf("ready" to JsonPrimitive(false)))
        repo.assistantAction(AssistantActions.read(0)).join()
        assertTrue("Refresh must remain read-only",transport.calls.none {it.first=="assistant/prepare"})
    }

    @Test fun cachedDispatchIsNotAStartupFailureAndDoesNotAutomaticallyPrepare()=runBlocking<Unit> {
        transport.snapshot=JsonObject(actorSnapshot()+mapOf("ready" to JsonPrimitive(false),"fresh" to JsonPrimitive(false),
            "dispatchInFlight" to JsonPrimitive(true),"preparationStatus" to JsonPrimitive("ready")))
        repo.loadAssistant().join()
        assertNull(repo.state.value.assistant.error)
        assertNotNull(repo.state.value.assistant.readError)
        assertFalse(repo.state.value.assistant.snapshot!!.prepared)
        assertEquals(listOf("assistant/read"),transport.calls.map {it.first})
    }

    @Test fun directAcceptedSendPublishesTheMatchingDurableComposerReceipt()=runBlocking<Unit> {
        repo.loadAssistant().join()
        repo.assistantAction(AssistantActions.send("managed","send once",null).copy(commandId="direct-once")).join()
        val receipt=withTimeout(5000) {repo.state.first {it.assistant.sendReceipts.any {r->r.commandId=="direct-once"}}}.assistant.sendReceipts.single()
        assertEquals(AssistantSendReceipt("direct-once","pc","managed","assistant:assistant","accepted"),receipt)
        assertEquals(1,transport.calls.count {it.first=="assistant/send"})
    }

    @Test fun unknownThenStatusAcceptedPublishesOnceAndRehydratesWithoutResending()=runBlocking<Unit> {
        repo.loadAssistant().join()
        transport.loseSend=true
        repo.assistantAction(AssistantActions.send("managed","uncertain once",null).copy(commandId="reconciled-once")).join()
        assertEquals("unknown",db.dao().uncertain("pc").single().status)
        assertTrue(repo.state.value.assistant.sendReceipts.isEmpty())
        repo.checkCommands().join()
        withTimeout(5000) {repo.state.first {it.assistant.sendReceipts.any {r->r.commandId=="reconciled-once" && r.status=="accepted"}}}
        repo.checkCommands().join()
        assign("assistantComputer",null)
        state().update {it.copy(assistant=AssistantUiState())}
        repo.loadAssistant().join()
        assertEquals("reconciled-once",repo.state.value.assistant.sendReceipts.single().commandId)
        assertEquals(1,transport.calls.count {it.first=="assistant/send"})
        assertEquals(1,transport.calls.count {it.first=="commands/status"})
    }

    @Test fun staleConnectionStatusCannotConfirmTheOriginalCommand()=runBlocking<Unit> {
        repo.loadAssistant().join();transport.loseSend=true
        repo.assistantAction(AssistantActions.send("managed","uncertain",null).copy(commandId="old-link")).join()
        transport.statusStarted=CompletableDeferred();transport.statusRelease=CompletableDeferred()
        val read=repo.checkCommands()
        withTimeout(5000) {transport.statusStarted!!.await()}
        assign("client",ActorConnection())
        transport.statusRelease!!.complete(Unit)
        withTimeout(5000) {read.join()}
        assertEquals("unknown",db.dao().uncertain("pc").single().status)
        assertTrue(repo.state.value.assistant.sendReceipts.isEmpty())
    }

    @Test fun duplicatePendingCallbackCannotReplaceUnknownWithLocalRejection()=runBlocking<Unit> {
        repo.loadAssistant().join();transport.loseSend=true
        val action=AssistantActions.send("managed","one attempt",null).copy(commandId="duplicate-callback")
        repo.assistantAction(action).join()
        transport.commandStatus="unknown"
        repo.assistantAction(action).join()
        assertEquals("unknown",db.dao().uncertain("pc").single().status)
        assertEquals(1,transport.calls.count {it.first=="assistant/send"})
    }

    @Test fun v2UncertainMessageDoesNotLockAnIndependentGoalOrReplayTheOriginal()=runBlocking<Unit> {
        transport.snapshot=JsonObject(actorSnapshot()+mapOf("protocolVersion" to JsonPrimitive(2),"acceptingMessages" to JsonPrimitive(true)))
        repo.loadAssistant().join();transport.loseSend=true;transport.commandStatus="unknown"
        val original=boundSend("uncertain-original")
        repo.assistantAction(original).join()
        transport.loseSend=false
        repo.assistantAction(boundSend("independent-goal")).join()
        repo.assistantAction(original).join()
        assertEquals(2,transport.calls.count {it.first=="assistant/send"})
        assertEquals("unknown",db.dao().uncertain("pc").single {it.id=="uncertain-original"}.status)
        assertTrue(db.dao().assistantSendReceipts().first().any {it.id=="independent-goal" && it.status=="accepted"})
    }

    private fun badAck(reply:String)=runBlocking<Unit> {
        repo.loadAssistant().join()
        transport.sendReply=pocketJson.parseToJsonElement(reply)
        repo.assistantAction(AssistantActions.send("managed","retain this draft",null).copy(commandId="bad-ack")).join()
        assertEquals("unknown",db.dao().uncertain("pc").single().status)
        assertTrue(repo.state.value.assistant.sendReceipts.isEmpty())
        assertEquals(1,transport.calls.count {it.first=="assistant/send"})
    }
    @Test fun missingAckStaysUnknown()=badAck("{}")
    @Test fun falseAckWithoutExplicitRejectionStaysUnknown()=badAck("""{"accepted":false,"commandId":"bad-ack"}""")
    @Test fun missingAcceptedStaysUnknown()=badAck("""{"commandId":"bad-ack","status":"accepted"}""")
    @Test fun missingCommandIdStaysUnknown()=badAck("""{"accepted":true}""")
    @Test fun wrongCommandIdStaysUnknown()=badAck("""{"accepted":true,"commandId":"another-command"}""")
    @Test fun contradictoryRejectedAckStaysUnknown()=badAck("""{"accepted":true,"commandId":"bad-ack","status":"rejected"}""")
    @Test fun rejectedStatusWithoutAcceptedFlagStaysUnknown()=badAck("""{"commandId":"bad-ack","status":"rejected"}""")
    @Test fun stringTrueIsNotAnAcceptedAck()=badAck("""{"accepted":"true","commandId":"bad-ack"}""")
    @Test fun acceptedAckWithErrorStaysUnknown()=badAck("""{"accepted":true,"commandId":"bad-ack","error":"rejected"}""")
    @Test fun unknownStatusCannotBeUpgradedByAcceptedFlag()=badAck("""{"accepted":true,"commandId":"bad-ack","status":"unknown"}""")
    @Test fun nonObjectAckStaysUnknown()=badAck("[]")
    @Test fun nullStatusIsNotAnAcceptedAck()=badAck("""{"accepted":true,"commandId":"bad-ack","status":null}""")
    @Test fun falseAckWithAcceptedStatusStaysUnknown()=badAck("""{"accepted":false,"commandId":"bad-ack","status":"accepted"}""")
    @Test fun wrongCommandIdCannotConfirmAnExplicitRejection()=badAck("""{"accepted":false,"commandId":"another-command","status":"rejected"}""")

    @Test fun explicitMatchingRejectionRetainsRejectedAndNeverAccepted()=runBlocking<Unit> {
        repo.loadAssistant().join()
        transport.sendReply=pocketJson.parseToJsonElement("""{"accepted":false,"commandId":"rejected-ack","status":"rejected"}""")
        repo.assistantAction(AssistantActions.send("managed","retain rejected draft",null).copy(commandId="rejected-ack")).join()
        val receipt=withTimeout(5000) {repo.state.first {it.assistant.sendReceipts.any {r->r.commandId=="rejected-ack"}}}.assistant.sendReceipts.single()
        assertEquals("rejected",receipt.status)
        assertTrue(db.dao().uncertain("pc").isEmpty())
    }

    private fun boundSend(id:String="scope-once")=AssistantActions.send("managed","original scope draft",null).copy(commandId=id,
        composerBinding=repo.state.value.assistant.snapshot!!.composerBinding("pc"))

    @Test fun uiBindingCannotBeReplacedByALaterRuntimeBeforeRepositoryInvocation()=runBlocking<Unit> {
        repo.loadAssistant().join()
        val action=boundSend()
        state().update {it.copy(assistant=it.assistant.copy(snapshot=it.assistant.snapshot!!.copy(runtimeId="later-runtime")))}
        repo.assistantAction(action).join()
        assertTrue(transport.calls.none {it.first=="assistant/send"})
        val receipt=db.dao().assistantSendReceipts().first().single().let(AssistantSendReceipt::from)!!
        assertEquals("managed",receipt.runtimeId)
        assertEquals(action.composerBinding!!.conversationId,receipt.conversationId)
        assertEquals("rejected",receipt.status)
    }

    @Test fun connectionReplacementBeforeCoroutineLaunchCannotDispatchToEitherSocket()=runBlocking<Unit> {
        repo.loadAssistant().join()
        val action=boundSend()
        val originalScope=PocketRepository::class.java.getDeclaredField("scope").apply {isAccessible=true}.get(repo) as CoroutineScope
        originalScope.coroutineContext[Job]!!.cancelAndJoin()
        val dispatcher=PausingDispatcher()
        assign("scope",CoroutineScope(SupervisorJob()+dispatcher))
        val job=repo.assistantAction(action)
        val replacement=ActorConnection()
        assign("client",replacement)
        dispatcher.resume()
        withTimeout(5000) {job.join()}
        assertTrue(transport.calls.none {it.first=="assistant/send"})
        assertTrue(replacement.calls.isEmpty())
        val receipt=db.dao().assistantSendReceipts().first().single().let(AssistantSendReceipt::from)!!
        assertEquals("pc",receipt.computerId)
        assertEquals("rejected",receipt.status)
    }

    @Test fun daoAwaitCannotMoveOriginalSendToAnotherComputer()=runBlocking<Unit> {
        repo.loadAssistant().join()
        queryExecutor.pause()
        val action=boundSend()
        val job=repo.assistantAction(action)
        assertTrue(queryExecutor.queued.await(5,TimeUnit.SECONDS))
        val replacement=ActorConnection()
        assign("client",replacement)
        assign("pair",PairedComputer(ConnectionConfig("wss://fixture.invalid","room","fixture-token","00".repeat(32)),"phone2","fixture-secret","pc2","fixture"))
        state().update {it.copy(computerId="pc2",assistant=it.assistant.copy(computerId="pc2"))}
        queryExecutor.resume()
        withTimeout(5000) {job.join()}
        assertTrue(transport.calls.none {it.first=="assistant/send"})
        assertTrue(replacement.calls.isEmpty())
        val receipt=db.dao().assistantSendReceipts().first().single().let(AssistantSendReceipt::from)!!
        assertEquals("pc",receipt.computerId)
        assertEquals("rejected",receipt.status)
    }

    @Test fun daoAwaitCannotMoveSendAcrossReconnectToTheSameComputer()=runBlocking<Unit> {
        repo.loadAssistant().join()
        queryExecutor.pause()
        val action=boundSend()
        val job=repo.assistantAction(action)
        assertTrue(queryExecutor.queued.await(5,TimeUnit.SECONDS))
        val generation=PocketRepository::class.java.getDeclaredField("connectionGeneration").apply {isAccessible=true}.get(repo) as java.util.concurrent.atomic.AtomicLong
        generation.incrementAndGet()
        queryExecutor.resume()
        withTimeout(5000) {job.join()}
        assertTrue(transport.calls.none {it.first=="assistant/send"})
        val receipt=db.dao().assistantSendReceipts().first().single().let(AssistantSendReceipt::from)!!
        assertEquals(action.composerBinding!!.conversationId,receipt.conversationId)
        assertEquals("rejected",receipt.status)
    }

    @Test fun daoAwaitCannotMoveSendToAnotherRuntimeOnTheSameSocket()=runBlocking<Unit> {
        repo.loadAssistant().join()
        queryExecutor.pause()
        val action=boundSend()
        val job=repo.assistantAction(action)
        assertTrue(queryExecutor.queued.await(5,TimeUnit.SECONDS))
        state().update {it.copy(assistant=it.assistant.copy(snapshot=it.assistant.snapshot!!.copy(runtimeId="another-runtime")))}
        queryExecutor.resume()
        withTimeout(5000) {job.join()}
        assertTrue(transport.calls.none {it.first=="assistant/send"})
        val receipt=db.dao().assistantSendReceipts().first().single().let(AssistantSendReceipt::from)!!
        assertEquals("managed",receipt.runtimeId)
        assertEquals("rejected",receipt.status)
    }

    @Test fun daoAwaitAllowsSamePublicAssistantCoordinatorSuccessorWithoutRebinding()=runBlocking<Unit> {
        repo.loadAssistant().join()
        queryExecutor.pause()
        val action=boundSend()
        val job=repo.assistantAction(action)
        assertTrue(queryExecutor.queued.await(5,TimeUnit.SECONDS))
        transport.snapshot=JsonObject(actorSnapshot()+mapOf("coordinatorThreadId" to JsonPrimitive("successor")))
        state().update {it.copy(assistant=it.assistant.copy(snapshot=it.assistant.snapshot!!.copy(coordinatorThreadId="successor")))}
        queryExecutor.resume()
        withTimeout(5000) {job.join()}
        val receipt=withTimeout(5000) {repo.state.first {it.assistant.sendReceipts.any {r->r.commandId==action.commandId}}}.assistant.sendReceipts.single()
        assertEquals(action.composerBinding!!.conversationId,receipt.conversationId)
        assertEquals("accepted",receipt.status)
        assertEquals(1,transport.calls.count {it.first=="assistant/send"})
        assertFalse(transport.calls.single {it.first=="assistant/send"}.second.containsKey("composerBinding"))
    }

    @Test fun correctLateAckStillCompletesOnlyTheOriginalComputerCommand()=runBlocking<Unit> {
        repo.loadAssistant().join()
        transport.sendStarted=CompletableDeferred();transport.sendRelease=CompletableDeferred()
        val action=boundSend()
        val job=repo.assistantAction(action)
        withTimeout(5000) {transport.sendStarted!!.await()}
        val replacement=ActorConnection()
        assign("client",replacement)
        assign("pair",PairedComputer(ConnectionConfig("wss://fixture.invalid","room","fixture-token","00".repeat(32)),"phone2","fixture-secret","pc2","fixture"))
        state().update {it.copy(computerId="pc2",assistant=AssistantUiState(computerId="pc2"))}
        transport.sendRelease!!.complete(Unit)
        withTimeout(5000) {job.join()}
        val receipt=db.dao().assistantSendReceipts().first().single().let(AssistantSendReceipt::from)!!
        assertEquals("pc",receipt.computerId)
        assertEquals(action.composerBinding!!.conversationId,receipt.conversationId)
        assertEquals("accepted",receipt.status)
        assertTrue(replacement.calls.isEmpty())
        assertNull(repo.state.value.assistant.snapshot)
    }

    private class PausingExecutor:Executor {
        private val delegate=Executors.newSingleThreadExecutor()
        private val pending=mutableListOf<Runnable>()
        private var paused=false
        var queued=CountDownLatch(1);private set
        @Synchronized fun pause() {paused=true;queued=CountDownLatch(1)}
        @Synchronized override fun execute(command:Runnable) {
            if(paused) {pending+=command;queued.countDown()} else delegate.execute(command)
        }
        @Synchronized fun resume() {paused=false;pending.toList().also {pending.clear()}.forEach(delegate::execute)}
        fun close() {delegate.shutdownNow()}
    }
    private class PausingDispatcher:CoroutineDispatcher() {
        private var paused=true
        private val pending=mutableListOf<Pair<CoroutineContext,Runnable>>()
        @Synchronized override fun dispatch(context:CoroutineContext,block:Runnable) {
            if(paused) pending+=context to block else Dispatchers.IO.dispatch(context,block)
        }
        @Synchronized fun resume() {paused=false;pending.toList().also {pending.clear()}.forEach {(context,block)->Dispatchers.IO.dispatch(context,block)}}
    }

    private class ActorConnection:PocketConnection {
        override val state=MutableStateFlow(LinkState.ONLINE)
        override val notifications=emptyFlow<JsonObject>()
        var snapshot=actorSnapshot()
        var preparation: JsonObject? = null
        var olderPage: JsonElement? = null
        var readStarted:CompletableDeferred<Unit>?=null
        var readRelease:CompletableDeferred<Unit>?=null
        var readFailure=false
        var modelFailure=false
        var uploadFailure=false
        var loseSend=false
        var sendReply:JsonElement?=null
        var sendStarted:CompletableDeferred<Unit>?=null
        var sendRelease:CompletableDeferred<Unit>?=null
        var commandStatus="accepted"
        var statusStarted:CompletableDeferred<Unit>?=null
        var statusRelease:CompletableDeferred<Unit>?=null
        val calls=CopyOnWriteArrayList<Pair<String,JsonObject>>()
        override suspend fun connect(config:ConnectionConfig):Unit=error("Fixture must never connect")
        override suspend fun request(method:String,params:JsonObject,commandId:String?,timeoutMillis:Long):JsonElement {
            calls+=method to params
            return when(method) {
                "attachment/upload"->{if(uploadFailure) throw WireException("upload_failed","fixture upload failed");buildJsonObject {put("attachmentId",java.util.UUID.randomUUID().toString())}}
                "assistant/send" -> {
                    sendStarted?.complete(Unit);sendRelease?.await()
                    if(loseSend) throw UnknownOutcomeException(requireNotNull(commandId))
                    sendReply ?: buildJsonObject {put("accepted",true);put("commandId",commandId)}
                }
                "commands/status" -> {
                    statusStarted?.complete(Unit);statusRelease?.await()
                    buildJsonObject {put("status",commandStatus)}
                }
                "assistant/read"->{
                    if(readFailure) throw WireException("timeout","fixture read failure")
                    readStarted?.complete(Unit);readRelease?.await()
                    if(params.containsKey("before") && olderPage!=null) JsonObject(snapshot+mapOf("conversationPage" to olderPage!!)) else snapshot
                }
                "assistant/prepare"-> {
                    assertFalse(params.containsKey("runtimeId"))
                    preparation?.also {snapshot=it} ?: throw WireException("unsupported", "Preparation unavailable")
                }
                "codex/request"->{require(params.string("method")=="model/list");if(modelFailure) throw WireException("timeout","fixture catalog failure");buildJsonObject {
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
