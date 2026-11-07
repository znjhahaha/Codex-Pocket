package dev.codexpocket.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Ignore
import org.junit.runner.RunWith
import java.io.File

/** Opt-in control of the dedicated phone-created integration chat only. */
@RunWith(AndroidJUnit4::class)
@Ignore("Long counting probes disabled at user request; replace with a bounded fixture before live acceptance")
class LiveControlTest {
    @Test fun steerAndStop() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveControl") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketApplication
        val repo = app.repository
        val threadId = args.getString("threadId") ?: error("Dedicated integration thread required")
        var stage = "connect"
        try {
            runBlocking {
                withTimeout(240_000) {
                    repo.state.first { it.initialized }
                    withContext(Dispatchers.Main) { repo.setForeground(true) }
                    repo.state.first { it.online }
                    repo.refresh("Codex Pocket MuMu integration").join()
                    val chat = repo.state.first { s -> s.chats.any { it.key.nativeId == threadId } }.chats.first { it.key.nativeId == threadId }
                    check(chat.title == "Codex Pocket MuMu integration" && chat.source == "cli")
                    repo.select(chat).join()
                    check(repo.state.value.timeline.activeTurn == null && !repo.state.value.uncertainSend)
                    stage = "native steer"
                    val marker = "MUMU_STEER_" + System.currentTimeMillis()
                    repo.editDraft("Count from 1 to 10000, one number per line. Do not call tools or read files.")
                    repo.send().join()
                    check(repo.state.value.error == null)
                    repo.state.first { it.timeline.activeTurn != null }
                    repo.editDraft("Additional instruction: stop counting and reply exactly $marker. Do not use tools.")
                    repo.send(steer = true).join()
                    check(repo.state.value.error == null)
                    repo.state.first { s -> s.timeline.activeTurn == null && s.timeline.items.values.any { it.type == "agentMessage" && it.text.contains(marker) } }
                    stage = "native interrupt"
                    repo.editDraft("Count from 1 to 10000, one number per line. Do not call tools or read files.")
                    repo.send().join()
                    check(repo.state.value.error == null)
                    val turn = repo.state.first { it.timeline.activeTurn != null }.timeline.activeTurn
                    repo.state.first { s -> s.timeline.items.values.any { it.turnId == turn && it.type == "agentMessage" && it.text.isNotBlank() && !it.complete } }
                    repo.stop().join()
                    check(repo.state.value.error == null)
                    repo.state.first { it.timeline.activeTurn == null && it.timeline.status == "interrupted" }
                    File(app.filesDir, "integration").mkdirs()
                    writeLiveReport("control-report.json", buildJsonObject {
                        put("passed", true); put("nativeThreadId", threadId); put("steer", true); put("interruptDuringStream", true)
                    })
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Live controls failed at $stage (${failure.javaClass.simpleName}); online=${repo.state.value.online}; uiError=${repo.state.value.error}")
        } finally {
            runBlocking { withContext(Dispatchers.Main) { repo.setForeground(false) } }
        }
    }
}
