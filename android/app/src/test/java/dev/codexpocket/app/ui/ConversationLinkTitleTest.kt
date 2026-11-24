package dev.codexpocket.app.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class ConversationLinkTitleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val id = "01a207c4-9742-7b63-95b7-9c744483d368"
    private val url = "codex://threads/$id"
    private val title = "完成 Codex Pocket 功能与发布收尾"
    private lateinit var repo: PocketRepository
    private lateinit var db: PocketDatabase

    private fun content(cachedTitle: String, block: @Composable () -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        val store = object : PairingStorage {
            override fun load() = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "fixture", "00".repeat(32)), "phone", "fixture", "pc", "Fixture")
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(app, { error("No connection") }, store, db, emptyFlow())
        compose.waitUntil(5_000) { repo.state.value.initialized }
        val chat = ChatEntity(ThreadKey("pc", "profile", id), "runtime", "desktop", cachedTitle, "", "", 0, "idle", false)
        runBlocking { db.dao().putChat(chat) }
        compose.waitUntil(5_000) { repo.state.value.chats.any { it.key == chat.key } }
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            PocketBackdrop { CompositionLocalProvider(LocalThreadLinkBinding provides ThreadLinkBinding(repo, chat.key)) { block() } }
        } } }
        compose.waitForIdle()
    }
    @After fun close() = runBlocking {
        if (::repo.isInitialized) {
            val scope = repo.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(repo) as CoroutineScope
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
        if (::db.isInitialized) db.close()
    }
    @Test fun sentLinkKeepsItsExplicitTitleInsteadOfCachedFirstMessage() {
        content("# 交接\n" + "This is the whole original request. ".repeat(30)) {
            ReferenceText("[$title]($url) 继续任务", emptyList(), Modifier.testTag("sent-link"))
        }
        compose.onNodeWithTag("sent-link").assertTextEquals(" $title 继续任务")
    }
    @Test fun composerShowsTheConversationTitleWhileKeepingItsOriginalUrl() {
        var editor = TextFieldValue("查看 $url 继续")
        content(title) {
            ReferenceEditor(editor, { editor = it }, emptyList(), Modifier.testTag("link-editor"))
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("link-editor").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().layoutInput.text.text.contains(title))
        assertFalse(layouts.single().layoutInput.text.text.contains("codex://"))
        assertEquals("查看 $url 继续", editor.text)
    }
}
