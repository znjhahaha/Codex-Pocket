package dev.codexpocket.app

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.view.PixelCopy
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import dev.codexpocket.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual release UI/repository, deterministic transport, and in-memory history only. */
@RunWith(AndroidJUnit4::class)
class ContinuationDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var repo: PocketRepository
    private lateinit var db: PocketDatabase
    private val calls = CopyOnWriteArrayList<String>()
    private val checks = mutableListOf<String>()
    private val id = "01a1c7fb-7bda-7a71-a782-a020229c9573"
    private val url get() = "codex://threads/$id"
    private var unreadable = false
    private val runtime = RuntimeInfo("fixture", "desktop", "fixture", "store", true, setOf(id),
        setOf("thread/start", "thread/read", "turn/start"))
    private val pair = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "fixture-token", "00".repeat(32)),
        "fixture-phone", "fixture-secret", "fixture-pc", "设备验收")
    private fun thread() = buildJsonObject {
        put("id", id); put("name", "空会话验收"); put("cwd", "D:/fixture"); put("turns", JsonArray(emptyList()))
        put("status", buildJsonObject { put("type", "idle") }); put("historyMode", "legacy")
    }
    private val transport = object : PocketConnection {
        override val state = MutableStateFlow(LinkState.ONLINE)
        override val notifications = emptyFlow<JsonObject>()
        override suspend fun connect(config: ConnectionConfig): Unit = error("Fixture cannot connect")
        override fun close() = Unit
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            val native = if (method == "codex/request") params.string("method") else method
            calls += native
            return when (native) {
                "runtimes/list" -> buildJsonObject { put("data", buildJsonArray { add(buildJsonObject {
                    put("id", runtime.id); put("source", runtime.source); put("configStoreId", runtime.configStoreId)
                    put("online", true); put("loadedThreadIds", buildJsonArray { add(id) })
                    put("capabilities", JsonArray(runtime.capabilities!!.map(::JsonPrimitive)))
                }) }) }
                "thread/start" -> { assertNotNull(commandId); buildJsonObject { put("thread", thread()) } }
                "thread/read" -> {
                    assertNull(commandId)
                    if (unreadable) throw WireException("native_history_unavailable", "Fixture history unavailable")
                    buildJsonObject { put("thread", thread()); put("readState", "awaiting_first_message") }
                }
                "turn/start" -> {
                    assertNotNull(commandId)
                    assertEquals("手机首发验收", params.obj("params").array("input").first().asObject().string("text"))
                    buildJsonObject { put("turn", buildJsonObject { put("id", "fixture-turn") }) }
                }
                "phone/presence", "assistant/snapshot", "assistant/chat/read", "threads/list" -> buildJsonObject { put("data", JsonArray(emptyList())) }
                else -> error("Unexpected fixture RPC: $native")
            }
        }
    }
    private fun field(name: String) = PocketRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun update(block: (PocketState) -> PocketState) =
        (field("_state").get(repo) as MutableStateFlow<PocketState>).update(block)

    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("continuationDevice") == "true")
            val app = instrumentation.targetContext.applicationContext as PocketApplication
            val delegate = PocketApplication::class.java.getDeclaredField("repository\$delegate").apply { isAccessible = true }
            val original = delegate.get(app) as Lazy<*>
            check(!original.isInitialized()) { "Run alone in a fresh instrumentation process" }
            db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
            instrumentation.runOnMainSync {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(app)
                delegate.set(app, lazy {
                    PocketRepository(app, connectionFactory = { error("No network permitted") },
                        pairStore = object : PairingStorage {
                            override fun load(): PairedComputer? = null
                            override fun save(pair: PairedComputer): Unit = error("No pairing writes permitted")
                            override fun forget(): Unit = error("No pairing writes permitted")
                        }, db = db, networkAvailable = emptyFlow()).also { repo = it }
                })
            }
            try { base.evaluate() } finally {
                if (::repo.isInitialized) runBlocking { (field("scope").get(repo) as CoroutineScope).coroutineContext[Job]?.cancelAndJoin() }
                db.close()
                instrumentation.runOnMainSync { delegate.set(app, original) }
                // Leave the observer detached until instrumentation ends, avoiding real reconnect on teardown.
            }
        }
    }}).around(compose)

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun clickLink(label: String) {
        compose.waitUntil(10_000) { textViews(compose.activity.window.decorView).any { it.text.toString() == label } }
        compose.runOnIdle {
            val view = textViews(compose.activity.window.decorView).first { it.text.toString() == label }
            val spans = view.text as Spanned
            spans.getSpans(0, spans.length, ClickableSpan::class.java).single().onClick(view)
        }
    }
    private fun screenshot(name: String) {
        val dir = File(compose.activity.getExternalFilesDir(null), "continuation-device-proof").apply { mkdirs() }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = -1
        instrumentation.runOnMainSync {
            PixelCopy.request(compose.activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        }
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    @Test(timeout = 120_000) fun linksEmptyHistoryAndFirstPhoneSend() {
        compose.waitUntil(5_000) { ::repo.isInitialized && repo.state.value.initialized }
        val child = ChatEntity(ThreadKey(pair.computerId, "store", id), runtime.id, "desktop", "空会话验收", "", "D:/fixture", 0, "idle", false, isSubagent = true)
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var copied: String? = null
        instrumentation.runOnMainSync { compose.activity.setContent { PocketTheme("light") { Surface(Modifier.fillMaxSize()) {
            val local = LocalClipboardManager.current
            Column(Modifier.padding(24.dp)) { CopyConversationLinkItem(child) { local.setText(AnnotatedString(it)); copied = it }
                ProvideMarkdownRendering { MarkdownText("[继续会话]($url)\n\n$url\n\n`$url`", "device-links") }
            }
        } } } }
        compose.onNodeWithText("复制会话链接").performClick()
        compose.runOnIdle { assertEquals(url, copied); assertEquals(url, clipboard.primaryClip?.getItemAt(0)?.text.toString()) }
        checks += "offline_empty_child_copy_exact_uri"
        compose.waitUntil(10_000) { textViews(compose.activity.window.decorView).any { it.text.toString().contains("打开会话") } }
        compose.runOnIdle {
            val text = textViews(compose.activity.window.decorView).first { it.text.toString().contains("打开会话") }.text as Spanned
            assertTrue(text.toString().contains("继续会话")); assertTrue(text.toString().contains(url))
            assertEquals(2, text.getSpans(0, text.length, ClickableSpan::class.java).size)
        }
        screenshot("copy-and-render")
        checks += "markdown_explicit_bare_links_and_literal_code"

        field("pair").set(repo, pair); field("client").set(repo, transport)
        update { it.copy(paired = true, online = true, computerId = pair.computerId, computerName = pair.computerName,
            runtimes = listOf(runtime), glassEffects = false) }
        runBlocking { withTimeout(10_000) { repo.create(runtime, "D:/fixture").join() } }
        val chat = repo.state.value.selected ?: error("Create failed: ${repo.state.value.error}")
        assertEquals(id, chat.key.nativeId)
        assertEquals(ConversationLoadState.Ready, repo.state.value.conversationLoadState)
        assertEquals(1, calls.count { it == "thread/start" })
        assertFalse(calls.contains("turn/start"))
        runBlocking { withTimeout(10_000) { repo.select(chat, forceRefresh = true).join() } }
        assertEquals(ConversationLoadState.Ready, repo.state.value.conversationLoadState)
        assertNull(repo.state.value.conversationLoadError)
        assertTrue(repo.state.value.timeline.items.isEmpty())
        checks += "create_and_reopen_empty_thread_ready_without_turn"
        instrumentation.runOnMainSync { compose.activity.setContent { PocketTheme("light") {
            val state by repo.state.collectAsState(); PocketRoot(state, repo)
        } } }
        compose.onNodeWithText("空会话验收").performClick()
        compose.onNodeWithTag("composer-input").assertExists()
        compose.waitForIdle(); screenshot("empty-conversation")

        // Real failure stays a failure and retains the current draft.
        repo.editDraft("保留草稿")
        unreadable = true
        runBlocking { withTimeout(10_000) { repo.select(chat, forceRefresh = true).join() } }
        assertEquals(ConversationLoadState.Failed, repo.state.value.conversationLoadState)
        assertEquals("保留草稿", repo.state.value.draft?.text)
        checks += "unreadable_history_not_disguised_as_empty"
        unreadable = false
        runBlocking { withTimeout(10_000) { repo.select(chat, forceRefresh = true).join() } }
        instrumentation.runOnMainSync { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assistant-pocket-button").fetchSemanticsNodes().isNotEmpty() }

        val lock = field("assistantRead").get(repo) as Mutex
        check(lock.tryLock())
        try {
            update { it.copy(assistant = AssistantUiState(snapshot = AssistantSnapshot(enabled = true, runtimeId = runtime.id),
                messages = listOf(AssistantChatMessage("device-message", "Assistant", "[当前会话]($url)")))) }
            compose.onNodeWithTag("assistant-pocket-button").performClick()
            compose.onNodeWithTag("assistant-screen").assertExists()
            clickLink("当前会话")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("assistant-screen").fetchSemanticsNodes().isEmpty() }
            assertEquals(chat.key, repo.state.value.selected?.key)
            assertEquals("保留草稿", repo.state.value.draft?.text)
            checks += "assistant_link_returns_to_conversation_preserving_draft"
        } finally { lock.unlock() }
        assertFalse(calls.contains("turn/start"))
        repo.editDraft("手机首发验收")
        compose.onNodeWithTag("composer-send").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { calls.contains("turn/start") && !repo.state.value.sendBusy }
        assertEquals("First send must dispatch once", 1, calls.count { it == "turn/start" })
        assertEquals("", repo.state.value.draft?.text)
        assertEquals("fixture-turn", repo.state.value.timeline.activeTurn)
        checks += "first_phone_send_dispatches_once_without_prior_turn"
        val proof = buildJsonObject {
            put("checks", JsonArray(checks.map(::JsonPrimitive))); put("passed", checks.size)
            put("networkConnections", 0); put("realModelTurns", 0); put("fixtureTurnStarts", calls.count { it == "turn/start" })
        }
        File(compose.activity.getExternalFilesDir(null), "continuation-device-proof/result.json").writeText(proof.toString())
        println("POCKET_CONTINUATION_DEVICE ${checks.size} checks passed; deterministic transport; realModelTurns=0")
    }
}
