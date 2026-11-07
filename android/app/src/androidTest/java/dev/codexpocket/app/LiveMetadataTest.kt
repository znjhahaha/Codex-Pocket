package dev.codexpocket.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveMetadataTest {
    @Test fun archiveRestoreAndRenameNativeHistory() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveMetadata") == "true")
        val threadId = args.getString("threadId") ?: error("Dedicated integration thread required")
        val title = args.getString("expectedTitle") ?: "Codex Pocket MuMu integration"
        require(title == "Codex Pocket MuMu integration" || title == "Codex Pocket 0.3 acceptance cli")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketApplication
        val repo = app.repository
        var stage = "connect"
        try {
            runBlocking {
                withTimeout(120_000) {
                    repo.state.first { it.initialized }
                    withContext(Dispatchers.Main) { repo.setForeground(true) }
                    repo.state.first { it.online }
                    repo.refresh(title).join()
                    val chat = repo.state.first { s -> s.chats.any { it.key.nativeId == threadId } }.chats.first { it.key.nativeId == threadId }
                    check(chat.title == title && chat.source == "cli")
                    repo.select(chat).join()
                    check(repo.state.value.timeline.activeTurn == null && !repo.state.value.uncertainSend)
                    stage = "archive"
                    repo.archive(chat).join()
                    check(repo.state.value.error == null)
                    repo.refresh(title, archived = true).join()
                    val archived = repo.state.first { s -> s.chats.any { it.key == chat.key && it.archived } }.chats.first { it.key == chat.key }
                    stage = "unarchive unloaded history"
                    repo.archive(archived).join()
                    check(repo.state.value.error == null)
                    repo.refresh(title).join()
                    val restored = repo.state.first { s -> s.chats.any { it.key == chat.key && !it.archived } }.chats.first { it.key == chat.key }
                    repo.select(restored).join()
                    stage = "rename unloaded history"
                    repo.rename("$title metadata").join()
                    check(repo.state.value.error == null)
                    repo.rename(title).join()
                    check(repo.state.value.error == null)
                    writeLiveReport("metadata-report.json", buildJsonObject {
                        put("passed", true); put("nativeThreadId", threadId); put("archiveRestore", true); put("rename", true)
                    })
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Live metadata failed at $stage (${failure.javaClass.simpleName}); uiError=${repo.state.value.error}")
        } finally {
            runBlocking { withContext(Dispatchers.Main) { repo.setForeground(false) } }
        }
    }
}
