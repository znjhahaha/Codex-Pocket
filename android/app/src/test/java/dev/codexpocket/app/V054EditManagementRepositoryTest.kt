package dev.codexpocket.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.data.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class V054EditManagementRepositoryTest {
    private lateinit var db: PocketDatabase
    private lateinit var repo: PocketRepository
    private val key = ThreadKey("pc", "store", "thread")
    private val chat = ChatEntity(key, "runtime", "desktop", "test", "", "D:/fixture", 0, "idle", false)
    private val user = TimelineItem("turn", "user", "userMessage", "original", complete = true)
    private val calls = CopyOnWriteArrayList<String>()
    private var reverted = false
    private var unknownRevert = false
    private var unknownSend = false
    private var malformedSend = false
    private var extensionEnabled = true
    private var failExtension = false
    private var prepareWait: CompletableDeferred<Unit>? = null
    private var editStatusWait: CompletableDeferred<Unit>? = null
    private var commandStatus = "unknown"
    private val caps = setOf("thread/edit/prepare", "thread/edit/revert", "thread/edit/status", "thread/read", "turn/start", "extensions/list", "extensions/update")
    private val link = object : PocketConnection {
        override val state = MutableStateFlow(LinkState.ONLINE)
        override val notifications = emptyFlow<JsonObject>()
        override suspend fun connect(config: ConnectionConfig) = Unit
        override fun close() = Unit
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            calls += method + ":" + params.string("method")
            return when (method) {
                "thread/edit/prepare" -> { prepareWait?.await(); buildJsonObject {
                    put("threadId", "thread"); put("turnId", "turn"); put("itemId", "user"); put("allowed", true)
                    put("version", "v1"); put("editToken", "token"); put("text", "original")
                    put("originalInput", buildJsonArray { add(buildJsonObject { put("type", "retained"); put("kind", "image") }) })
                } }
                "thread/edit/revert" -> {
                    assertNotNull(commandId); assertEquals("token", params.string("editToken")); reverted = true
                    if (unknownRevert) throw UnknownOutcomeException(commandId!!)
                    buildJsonObject { put("threadId", "thread"); put("status", "reverted"); put("version", "v2") }
                }
                "thread/edit/status" -> {
                    val result = buildJsonObject { put("status", if (unknownSend) "unknown" else if (reverted) "reverted" else "prepared"); put("version", "v2") }
                    editStatusWait?.await()
                    result
                }
                "commands/status" -> buildJsonObject { put("status", commandStatus) }
                "extensions/list" -> buildJsonObject {
                    put("status", "ready"); put("version", "ext-v1")
                    put("data", buildJsonArray { add(buildJsonObject {
                        put("id", "opaque"); put("kind", "mcp"); put("name", "Fixture"); put("enabled", extensionEnabled)
                        put("capabilities", buildJsonObject { put("update", true) })
                    }) })
                }
                "extensions/update" -> {
                    assertNotNull(commandId); assertEquals("opaque", params.string("id"))
                    if (failExtension) throw WireException("rejected", "fixture")
                    extensionEnabled = params.flag("enabled"); buildJsonObject { put("status", "updated") }
                }
                "codex/request" -> when (params.string("method")) {
                    "turn/start" -> {
                        assertNotNull(commandId); assertEquals("token", params.string("preparedInput"))
                        assertEquals("edited", params.obj("params").array("input").single().asObject().string("text"))
                        if (unknownSend) throw UnknownOutcomeException(commandId!!)
                        if (malformedSend) return buildJsonObject { put("status", "accepted") }
                        buildJsonObject { put("turn", buildJsonObject { put("id", "new-turn") }) }
                    }
                    "thread/read" -> buildJsonObject { put("thread", buildJsonObject {
                        put("id", "thread"); put("status", buildJsonObject { put("type", "idle") }); put("turns", JsonArray(emptyList()))
                    }) }
                    else -> buildJsonObject {}
                }
                else -> buildJsonObject {}
            }
        }
    }
    @Suppress("UNCHECKED_CAST") private fun update(block: (PocketState) -> PocketState) {
        val f = PocketRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (f.get(repo) as MutableStateFlow<PocketState>).update(block)
    }
    @Before fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        val pair = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "token", "00".repeat(32)), "phone", "secret", "pc", "fixture")
        val store = object : PairingStorage { override fun load() = pair; override fun save(pair: PairedComputer) = Unit; override fun forget() = Unit }
        repo = PocketRepository(app, { link }, store, db, emptyFlow())
        withTimeout(5000) { repo.state.first { it.initialized } }
        db.dao().putChat(chat)
        db.dao().putDraft(DraftEntity(key, "ordinary draft", attachments = "[]"))
        repo.select(chat).join()
        PocketRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repo, link)
        update { it.copy(online = true, paired = true, computerId = "pc", selected = chat,
            runtimes = listOf(RuntimeInfo("runtime", "desktop", "fixture", "store", true, setOf("thread"), caps)),
            timeline = NativeTimeline(items = linkedMapOf(user.key to user)), conversationLoadState = ConversationLoadState.Ready) }
    }
    @After fun tearDown() = runBlocking {
        val f = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (f.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        db.close()
    }
    @Test fun cancelEditPreservesOrdinaryDraft() = runBlocking {
        repo.beginMessageEdit(user).join(); repo.changeMessageEdit("edited"); repo.cancelMessageEdit()
        assertNull(repo.state.value.messageEdit)
        assertEquals("ordinary draft", repo.state.value.draft?.text)
        assertTrue(calls.none { it.startsWith("thread/edit/revert") || it == "codex/request:turn/start" })
    }
    @Test fun editSendsOnlyOnceAndKeepsOrdinaryDraft() = runBlocking {
        repo.beginMessageEdit(user).join(); repo.changeMessageEdit("edited")
        val first = repo.submitMessageEdit(); val second = repo.submitMessageEdit(); joinAll(first, second)
        assertEquals(1, calls.count { it.startsWith("thread/edit/revert") })
        assertEquals(1, calls.count { it == "codex/request:turn/start" })
        assertNull(repo.state.value.messageEdit)
        assertEquals("ordinary draft", repo.state.value.draft?.text)
    }
    @Test fun uncertainSendStaysRecoverableAndNeverResendsOnCheck() = runBlocking {
        unknownSend = true
        repo.beginMessageEdit(user).join(); repo.changeMessageEdit("edited"); repo.submitMessageEdit().join()
        assertEquals(MessageEditPhase.UnknownSend, repo.state.value.messageEdit?.phase)
        assertNotNull(repo.state.value.messageEdit?.commandId)
        repo.checkMessageEdit().join(); repo.submitMessageEdit().join()
        assertEquals(1, calls.count { it == "codex/request:turn/start" })
        assertEquals("edited", repo.state.value.messageEdit?.text)
        assertEquals("ordinary draft", repo.state.value.draft?.text)
    }
    private suspend fun assertUncertainEditSurvivesReopen(phase: MessageEditPhase) {
        repo.beginMessageEdit(user).join(); repo.changeMessageEdit("edited"); repo.submitMessageEdit().join()
        val before = repo.state.value.messageEdit!!
        assertEquals(phase, before.phase)
        assertNotNull(before.commandId)
        val preferences = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("settings", 0)
        val saved = preferences.getString("messageEdit:${key.encoded}", null)
        val callsBefore = calls.toList()
        repo.cancelMessageEdit()
        assertNull(repo.state.value.messageEdit)
        val requested = if (phase == MessageEditPhase.UnknownRevert) user
            else user.copy(turnId = "different-turn", itemId = "different-user", text = "different message")
        repo.beginMessageEdit(requested).join()
        assertEquals(before, repo.state.value.messageEdit)
        assertEquals(saved, preferences.getString("messageEdit:${key.encoded}", null))
        repo.submitMessageEdit().join()
        assertEquals(callsBefore, calls.toList())
        assertEquals("ordinary draft", repo.state.value.draft?.text)
    }
    @Test fun closeThenReopenUnknownRevertKeepsOriginalEditIdentity() = runBlocking {
        unknownRevert = true
        assertUncertainEditSurvivesReopen(MessageEditPhase.UnknownRevert)
    }
    @Test fun anotherMessageCannotReplaceAHiddenUnknownSendDraft() = runBlocking {
        unknownSend = true
        assertUncertainEditSurvivesReopen(MessageEditPhase.UnknownSend)
    }
    @Test fun delayedRevertStatusPreservesTextTypedAfterCommandReconciliation() = runBlocking {
        unknownRevert = true
        repo.beginMessageEdit(user).join(); repo.changeMessageEdit("edited"); repo.submitMessageEdit().join()
        commandStatus = "accepted"
        val gate = CompletableDeferred<Unit>().also { editStatusWait = it }
        val check = repo.checkMessageEdit()
        try {
            withTimeout(5000) { while (calls.none { it.startsWith("thread/edit/status") }) delay(10) }
            assertEquals(MessageEditPhase.Reverted, repo.state.value.messageEdit?.phase)
            repo.changeMessageEdit("newer edited text")
        } finally { gate.complete(Unit) }
        check.join()
        assertEquals("newer edited text", repo.state.value.messageEdit?.text)
        assertEquals("v2", repo.state.value.messageEdit?.version)
        assertEquals(MessageEditPhase.Reverted, repo.state.value.messageEdit?.phase)
        assertTrue(calls.none { it == "codex/request:turn/start" })
        assertEquals("ordinary draft", repo.state.value.draft?.text)
    }
    @Test fun delayedStatusCannotReplaceANewEditorForTheSameMessage() = runBlocking {
        unknownRevert = true
        repo.beginMessageEdit(user).join(); repo.changeMessageEdit("edited"); repo.submitMessageEdit().join()
        commandStatus = "accepted"
        val gate = CompletableDeferred<Unit>().also { editStatusWait = it }
        val check = repo.checkMessageEdit()
        try {
            withTimeout(5000) { while (calls.none { it.startsWith("thread/edit/status") }) delay(10) }
            withTimeout(5000) { repo.state.first { !it.uncertainSend } }
            repo.cancelMessageEdit()
            repo.beginMessageEdit(user).join(); repo.changeMessageEdit("replacement editor text")
        } finally { gate.complete(Unit) }
        check.join()
        assertEquals("replacement editor text", repo.state.value.messageEdit?.text)
        assertEquals(MessageEditPhase.Ready, repo.state.value.messageEdit?.phase)
        assertEquals("v1", repo.state.value.messageEdit?.version)
        assertTrue(calls.none { it == "codex/request:turn/start" })
    }
    @Test fun acceptedButMalformedSendIsUnknownAndCannotBeRepeated() = runBlocking {
        malformedSend = true
        repo.beginMessageEdit(user).join(); repo.changeMessageEdit("edited"); repo.submitMessageEdit().join()
        assertEquals(MessageEditPhase.UnknownSend, repo.state.value.messageEdit?.phase)
        assertNotNull(repo.state.value.messageEdit?.commandId)
        repo.submitMessageEdit().join()
        assertEquals(1, calls.count { it == "codex/request:turn/start" })
        assertEquals("ordinary draft", repo.state.value.draft?.text)
    }
    @Test fun latePrepareCannotReplaceAnotherConversationEditor() = runBlocking {
        prepareWait = CompletableDeferred()
        val request = repo.beginMessageEdit(user)
        withTimeout(5000) { while (calls.none { it.startsWith("thread/edit/prepare") }) delay(10) }
        update { it.copy(selected = chat.copy(key = key.copy(nativeId = "other")), messageEdit = null) }
        prepareWait!!.complete(Unit); request.join()
        assertNull(repo.state.value.messageEdit)
    }
    @Test fun managementRequiresAuthoritativeReadbackAndKeepsSelectionOnRejection() = runBlocking {
        repo.loadExtensions("runtime").join()
        assertTrue(repo.state.value.extensions.entries.single().enabled == true)
        failExtension = true; repo.setExtensionEnabled("opaque", false).join()
        assertTrue(repo.state.value.extensions.entries.single().enabled == true)
        assertNotNull(repo.state.value.extensions.error)
        failExtension = false; repo.setExtensionEnabled("opaque", false).join()
        assertFalse(repo.state.value.extensions.entries.single().enabled == true)
        assertTrue(repo.state.value.online)
    }
}
