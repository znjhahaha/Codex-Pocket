package dev.codexpocket.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated repository, private pairing input, no conversation or permission mutations. */
@RunWith(AndroidJUnit4::class)
class CloseoutDisplayDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun savedHistoryTitlesComposerAndSnapshotHighlight() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pocketCloseoutFixture") == "true")
        compose.activity.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(target.filesDir, "integration/closeout-pairing.json")
        require(input.isFile && input.length() in 1..65_536)
        val bytes = input.readBytes()
        check(input.delete())
        val config = try { pocketJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
            finally { bytes.fill(0) }
        val expectedTitle = config.string("expectedTitle")
        val threadId = config.string("threadId")
        val isolated = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("closeout-fixture-$name", mode)
        }
        isolated.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(isolated, PocketDatabase::class.java).build()
        val storage = object : PairingStorage {
            var pair: PairedComputer? = null
            override fun load() = pair
            override fun save(pair: PairedComputer) { this.pair = pair }
            override fun forget() { pair = null }
        }
        val repo = PocketRepository(isolated, pairStore = storage, db = db, networkAvailable = emptyFlow())
        var stage = "pairing"
        try {
            val started = System.nanoTime()
            lateinit var chat: ChatEntity
            runBlocking { withTimeout(90_000) {
                repo.state.first { it.initialized }
                repo.setForeground(true)
                repo.pair(config.getValue("pairing").toString(), "Closeout display QA").join()
                check(repo.state.value.paired && repo.state.value.error == null) { "Pairing did not complete" }
                stage = "connection"
                repo.state.first { it.online }
                stage = "named history"
                repo.refresh(expectedTitle).join()
                chat = repo.state.first { state -> state.chats.any { it.key.nativeId == threadId } }.chats.single { it.key.nativeId == threadId }
                check(chat.title == expectedTitle)
                stage = "saved settings"
                repo.select(chat).join()
                repo.state.first { it.modelCatalogState == ModelCatalogState.Ready && it.model != null && !it.permissions.loading }
                check(!repo.state.value.settingsFresh)
                check(chat.key !in repo.state.value.accessFresh)
                check(repo.state.value.permissions.revision == null)
            } }
            val loadedMs = (System.nanoTime() - started) / 1_000_000
            stage = "render"
            val url = "codex://threads/$threadId"
            var editor by mutableStateOf(TextFieldValue("查看 $url 继续任务"))
            val timeline = NativeReducer.snapshot(pocketJson.parseToJsonElement("""
                {"id":"fixture","status":{"type":"active"},"turns":[{"id":"turn","status":"inProgress",
                 "items":[{"id":"r","type":"reasoning","summary":["正在核对会话标题与原生配置，保留草稿和已有会话。"],"completedAtMs":200}]}]}
            """).jsonObject)
            var timing by mutableStateOf(timeline.turns.getValue("turn"))
            compose.mainClock.autoAdvance = false
            compose.activity.runOnUiThread { compose.activity.setContent {
                PocketTheme("light") { PocketBackdrop {
                    CompositionLocalProvider(LocalThreadLinkBinding provides ThreadLinkBinding(repo, chat.key)) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 72.dp)
                            .testTag("closeout-display"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            PText("会话引用", style = Pocket.type.title)
                            PCard {
                                ReferenceText("[$expectedTitle]($url) 继续任务", emptyList(), Modifier.padding(16.dp).testTag("sent-title"))
                            }
                            PText("输入框", style = Pocket.type.titleSmall)
                            PCard { ReferenceEditor(editor, { editor = it }, emptyList(), Modifier.padding(16.dp).testTag("title-editor")) }
                            PText("思维摘要", style = Pocket.type.titleSmall)
                            LiveTurnStatus("turn", ConversationPresentation.LiveStatus.Thinking,
                                reasoning = ConversationPresentation.liveReasoning(timeline), timing = timing)
                        }
                    }
                } }
            } }
            fun frames(count: Int) { repeat(count) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
            frames(4)
            compose.onNodeWithTag("sent-title").assertTextContains(expectedTitle, substring = true)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("title-editor").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().layoutInput.text.text.contains(expectedTitle))
            assertFalse(layouts.single().layoutInput.text.text.contains("codex://"))
            assertEquals("查看 $url 继续任务", editor.text)
            val output = File(target.getExternalFilesDir(null), "closeout-display").apply { mkdirs() }
            fun save(name: String) {
                compose.onNodeWithTag("closeout-display").captureToImage().asAndroidBitmap().let { bitmap ->
                    File(output, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            fun pixels() = compose.onNodeWithTag("live-turn-status").captureToImage().toPixelMap()
            val before = pixels()
            save("title-and-summary-start.png")
            frames(40)
            val moving = pixels()
            var changed = 0
            for (y in 0 until before.height) for (x in 0 until before.width) if (before[x,y] != moving[x,y]) changed++
            assertTrue("Restored summary must show a moving glyph highlight", changed > 5)
            save("title-and-summary-moving.png")
            compose.runOnIdle { timing = timing.copy(status = "completed") }
            frames(2)
            val stopped = pixels()
            frames(40)
            val later = pixels()
            for (y in 0 until stopped.height) for (x in 0 until stopped.width) assertEquals(stopped[x,y], later[x,y])
            writeLiveReport("closeout-report.json", buildJsonObject {
                put("passed", true); put("realHistoryTitle", true); put("savedSettingsReadOnly", true)
                put("catalogCount", repo.state.value.models.size); put("pairAndReadMs", loadedMs)
                put("composerKeepsOriginalUrl", true); put("movingGlyphPixels", changed); put("completedSummaryStatic", true)
            })
        } catch (failure: Throwable) {
            if (stage == "render") throw failure
            throw AssertionError("Closeout display failed at $stage (${failure.javaClass.simpleName}); private input omitted")
        } finally {
            runBlocking {
                val scope = repo.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(repo) as CoroutineScope
                scope.coroutineContext[Job]!!.cancelAndJoin()
            }
            db.close()
            isolated.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
