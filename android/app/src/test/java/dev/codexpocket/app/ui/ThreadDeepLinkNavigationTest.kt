package dev.codexpocket.app.ui

import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class ThreadDeepLinkNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun field(repo: PocketRepository, name: String) = PocketRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun clickingCurrentConversationInsideAssistantClosesOverlay() {
        val chat = ChatEntity(ThreadKey("pc", "store", "01a1c7fb-7bda-7a71-a782-a020229c9573"), "runtime", "cli", "Fixture", "", "D:/work", 0, "idle", false)
        val db = Room.inMemoryDatabaseBuilder(compose.activity.application, PocketDatabase::class.java).build()
        val storage = object : PairingStorage {
            override fun load(): PairedComputer? = null
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        val link = object : PocketConnection {
            override val state = MutableStateFlow(LinkState.ONLINE)
            override val notifications = emptyFlow<JsonObject>()
            override suspend fun connect(config: ConnectionConfig) = Unit
            override fun close() = Unit
            override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
                assertNull(commandId)
                return when (method) {
                    "codex/request" -> if (params.string("method") == "thread/read") buildJsonObject {
                        put("thread", buildJsonObject { put("id", chat.key.nativeId); put("turns", JsonArray(emptyList())) })
                    } else buildJsonObject { put("data", JsonArray(emptyList())) }
                    else -> buildJsonObject {}
                }
            }
        }
        val repo = PocketRepository(compose.activity.application, { error("No real connection") }, storage, db, emptyFlow())
        val state = field(repo, "_state").get(repo) as MutableStateFlow<PocketState>
        val assistantLock = field(repo, "assistantRead").get(repo) as Mutex
        try {
            compose.waitUntil(5000) { repo.state.value.initialized }
            runBlocking { db.dao().putChat(chat) }
            assistantLock.tryLock()
            field(repo, "client").set(repo, link)
            field(repo, "pair").set(repo, PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "fixture-token", "00".repeat(32)), "phone", "fixture-secret", "pc", "Fixture"))
            state.update { it.copy(paired = true, online = true, selected = chat, computerId = "pc", glassEffects = false,
                runtimes = listOf(RuntimeInfo("runtime", "cli", "fixture", "store", true, setOf(chat.key.nativeId), setOf("thread/read"))),
                assistant = AssistantUiState(snapshot = AssistantSnapshot(enabled = true, runtimeId = "runtime"),
                    messages = listOf(AssistantChatMessage("message", "Assistant", "[当前会话](codex://threads/${chat.key.nativeId})")))) }
            compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
                val current by repo.state.collectAsState(); PocketRoot(current, repo)
            } } }
            compose.onNodeWithTag("assistant-pocket-button").performClick()
            compose.onNodeWithTag("assistant-screen").assertExists()
            compose.waitUntil(10_000) { textViews(compose.activity.window.decorView).any { it.text.toString() == "当前会话" } }
            compose.runOnIdle {
                val view = textViews(compose.activity.window.decorView).first { it.text.toString() == "当前会话" }
                val text = view.text as Spanned
                text.getSpans(0, text.length, ClickableSpan::class.java).single().onClick(view)
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("assistant-screen").fetchSemanticsNodes().isEmpty() }
            assertEquals(chat.key, repo.state.value.selected?.key)
        } finally {
            compose.activity.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            if (assistantLock.isLocked) assistantLock.unlock()
            runBlocking { (field(repo, "scope").get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
        }
    }
}
