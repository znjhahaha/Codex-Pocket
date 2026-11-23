package dev.codexpocket.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.questionDockEntries
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

/** Real repository/Room with a read-only desktop history fixture. Never calls a model or device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AsyncQuestionReconciliationTest {
    private lateinit var app: Application
    private lateinit var db: PocketDatabase
    private lateinit var repo: PocketRepository
    private lateinit var link: QuestionLink
    private val key = ThreadKey("question-sync-computer", "question-sync-store", "question-sync-thread")
    private val chat = ChatEntity(key, "question-follower", "desktop", "Question sync", "", "", 0, "", false)
    private val callId = "call_async_fixture"
    private val questionId = "[\"request_user_input_async\",\"$callId\",0]"
    private val question = buildJsonObject {
        put("id", callId); put("type", "agentMessage"); put("delivery", "async"); put("text", "Choose work")
        put("questions", buildJsonArray { add(buildJsonObject { put("title", "What next?") }) })
    }
    private fun reply(id: String = questionId) = buildJsonObject {
        put("id", "native-answer"); put("type", "userMessage")
        put("content", buildJsonArray { add(buildJsonObject {
            put("type", "text")
            val answer = buildJsonArray { add(buildJsonObject {
                put("questionItemId", id); put("question", "What next?"); put("answer", "Performance first")
            }) }
            put("text", "<send_user_message_question_reply>\n$answer\n</send_user_message_question_reply>")
        }) })
    }
    private fun turn(items: List<JsonObject>, id: String = "execution-turn") = buildJsonObject {
        put("id", id); put("status", "completed"); put("items", JsonArray(items))
    }
    @Before fun prepare() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("settings", 0).edit().remove("questions:${key.encoded}").commit()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        link = QuestionLink()
        val pair = PairedComputer(ConnectionConfig("wss://fixture.invalid", "fixture", "fixture", "00".repeat(32)),
            "fixture-phone", "fixture", key.computerId, "Fixture")
        val storage = object : PairingStorage {
            override fun load() = pair
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(app, { link }, storage, db, emptyFlow())
        withTimeout(5_000) { repo.state.first { it.initialized } }
        field("client").set(repo, link)
        db.dao().putChat(chat)
        update { it.copy(online = true, capabilities = setOf("threads/history/read"),
            runtimes = listOf(RuntimeInfo(chat.runtimeId, "desktop", "fixture", key.configStoreId, true,
                setOf(key.nativeId), setOf("turn/start", "turn/steer"), role = "desktopFollower"))) }
    }

    @After fun close() = runBlocking<Unit> {
        (field("scope").get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        app.getSharedPreferences("settings", 0).edit().remove("questions:${key.encoded}").commit()
    }
    private fun field(name: String) = PocketRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun update(change: (PocketState) -> PocketState) = (field("_state").get(repo) as MutableStateFlow<PocketState>).update(change)
    private suspend fun openCachedQuestion() {
        val item = TimelineItem.from("execution-turn", question)
        db.dao().putItems(listOf(ItemEntity.from(key, item, 0)))
        repo.select(chat).join()
        assertEquals("persisted", repo.state.value.conversationReadSource)
        assertEquals(listOf(questionId), repo.state.value.advanced.asyncQuestions.map { it.string("id") })
        update { it.copy(pendingQuestions = mapOf(key.encoded to it.advanced.asyncQuestions)) }
    }
    private fun assertNoPending() {
        val state = repo.state.value
        assertTrue("Native answered history must clear the selected dock", state.advanced.asyncQuestions.isEmpty())
        assertTrue("The home badge/dock fallback must be cleared too", state.pendingQuestions[key.encoded].isNullOrEmpty())
        assertTrue(questionDockEntries(state).none { it.asynchronous })
    }

    @Test fun firstPersistedReadPublishesNativeAsyncQuestions() = runBlocking<Unit> {
        repo.select(chat).join()
        assertEquals(1, repo.state.value.timeline.items.size)
        assertEquals(listOf(questionId), repo.state.value.advanced.asyncQuestions.map { it.string("id") })
        assertEquals(listOf(questionId), repo.state.value.pendingQuestions[key.encoded].orEmpty().map { it.string("id") })
        assertTrue(link.writes.isEmpty())
    }

    @Test fun foregroundPersistedPollClearsDesktopAnsweredQuestionAndHomeBadge() = runBlocking<Unit> {
        openCachedQuestion()
        field("foreground").setBoolean(repo, true)
        val follow = repo.observeConversation(key)
        try {
            link.items = listOf(question, reply())
            withTimeout(8_000) { repo.state.first { s -> s.timeline.items.values.any { it.itemId == "native-answer" } } }
            assertTrue(AsyncQuestions.pending(repo.state.value.timeline).isEmpty())
            assertNoPending()
            assertTrue("History observation must never resend an answer", link.writes.isEmpty())
        } finally { follow.cancelAndJoin() }
    }

    @Test fun acceptedNativeAnswerReceiptClearsPendingWithoutWaitingForAnEvent() = runBlocking<Unit> {
        openCachedQuestion()
        link.sendResult = buildJsonObject { put("turn", turn(listOf(reply()), "answer-turn")) }
        val pending = repo.state.value.advanced.asyncQuestions.single()
        repo.answerAsyncBatch(listOf(pending to "Performance first")).join()
        assertEquals(1, link.writes.size)
        assertNoPending()
        repo.answerAsyncBatch(listOf(pending to "Performance first")).join()
        assertEquals("A repeated callback must not replay the answer", 1, link.writes.size)
    }

    @Test fun unknownReceiptRemainsGuardedUntilNativeRejectionAllowsRetry() = runBlocking<Unit> {
        openCachedQuestion()
        link.unknownSend = true
        val pending = repo.state.value.advanced.asyncQuestions.single()
        repo.answerAsyncBatch(listOf(pending to "Performance first")).join()
        val command = db.dao().uncertain(key.computerId).single()
        val marker = pocketJson.parseToJsonElement(command.draftText).jsonObject
        assertEquals(setOf("pocketAsyncQuestions", "questionIds"), marker.keys)
        assertEquals(JsonPrimitive(1), marker["pocketAsyncQuestions"])
        assertEquals(listOf(questionId), marker.array("questionIds").map { it.jsonPrimitive.content })
        assertFalse(command.draftText.contains("Performance first"))
        assertFalse(command.draftText.contains("What next?"))
        repo.checkCommands().join()
        assertTrue(repo.state.value.advanced.asyncQuestions.single().flag("submitted"))
        repo.answerAsyncBatch(listOf(pending to "Performance first")).join()
        assertEquals(1, link.writes.size)
        link.receiptStatus = "rejected"
        repo.checkCommands().join()
        assertFalse("A definitely rejected async command must release only its submission guard",
            repo.state.value.advanced.asyncQuestions.single().flag("submitted"))
        assertEquals(1, link.writes.size)
    }

    @Test fun reconciledNativeAnswerReceiptClearsPendingWhilePreservingComposerDraft() = runBlocking<Unit> {
        openCachedQuestion()
        repo.editDraft("A separate unsent draft")
        link.unknownSend = true
        repo.answerAsyncBatch(listOf(repo.state.value.advanced.asyncQuestions.single() to "Performance first")).join()
        link.receiptStatus = "accepted"
        link.receiptResult = buildJsonObject { put("turn", turn(listOf(reply()), "answer-turn")) }
        repo.checkCommands().join()
        assertNoPending()
        assertEquals("A separate unsent draft", repo.state.value.draft?.text)
        assertEquals(1, link.writes.size)
    }

    @Test fun receiptFromReplacedConnectionCannotChangeQuestionHistory() = runBlocking<Unit> {
        openCachedQuestion()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        link.sendResult = buildJsonObject { put("turn", turn(listOf(reply()), "answer-turn")) }
        link.onSend = { entered.complete(Unit); release.await() }
        val job = repo.answerAsyncBatch(listOf(repo.state.value.advanced.asyncQuestions.single() to "Performance first"))
        try {
            withTimeout(5_000) { entered.await() }
            field("client").set(repo, QuestionLink())
        } finally { release.complete(Unit); job.join() }
        assertTrue(repo.state.value.timeline.items.values.none { it.itemId == "native-answer" })
        assertTrue(repo.state.value.advanced.asyncQuestions.single().flag("submitted"))
        assertEquals(1, link.writes.size)
    }

    @Test fun genericAcceptedReceiptAndForeignAnswerDoNotResolveTheQuestion() = runBlocking<Unit> {
        openCachedQuestion()
        link.sendResult = buildJsonObject { put("turn", turn(emptyList(), "answer-turn")) }
        repo.answerAsyncBatch(listOf(repo.state.value.advanced.asyncQuestions.single() to "Performance first")).join()
        assertTrue(repo.state.value.advanced.asyncQuestions.single().flag("submitted"))
        link.items = listOf(question, reply("[\"request_user_input_async\",\"other-call\",0]"))
        repo.reloadConversation().join()
        assertEquals(listOf(questionId), AsyncQuestions.pending(repo.state.value.timeline).map { it.string("id") })
        assertTrue(repo.state.value.advanced.asyncQuestions.single().flag("submitted"))
        assertEquals(1, link.writes.size)
    }

    private inner class QuestionLink : PocketConnection {
        override val state = MutableStateFlow(LinkState.ONLINE)
        override val notifications: Flow<JsonObject> = emptyFlow()
        @Volatile var items = listOf(question)
        @Volatile var sendResult: JsonObject = buildJsonObject { put("turn", turn(emptyList())) }
        @Volatile var unknownSend = false
        @Volatile var receiptStatus = "unknown"
        @Volatile var receiptResult: JsonObject? = null
        var onSend: suspend () -> Unit = {}
        val writes = CopyOnWriteArrayList<String>()
        override suspend fun connect(config: ConnectionConfig) = Unit
        override fun close() = Unit
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            if (commandId != null) writes += commandId
            return when (method) {
                "threads/history/read" -> buildJsonObject {
                    put("thread", buildJsonObject { put("id", params.string("threadId")) })
                    put("data", buildJsonArray { add(turn(items)) }); put("nextCursor", JsonNull); put("historyEpoch", "fixture")
                }
                "commands/status" -> buildJsonObject {
                    put("status", receiptStatus); receiptResult?.let { put("result", it) }
                }
                "codex/request" -> {
                    check(params.string("method") in setOf("turn/start", "turn/steer"))
                    check(commandId != null)
                    onSend()
                    if (unknownSend) throw UnknownOutcomeException(commandId)
                    sendResult
                }
                else -> buildJsonObject {}
            }
        }
    }
}
