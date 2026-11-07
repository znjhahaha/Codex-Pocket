package dev.codexpocket.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
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
class LiveImageTest {
    @Test fun syntheticImageReachesNativeTurn() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveImage") == "true")
        val threadId = args.getString("threadId") ?: error("Dedicated integration thread required")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketApplication
        val repo = app.repository
        val file = File(app.cacheDir, "mumu-image-probe.png")
        var stage = "connect"
        try {
            runBlocking {
                withTimeout(180_000) {
                    repo.state.first { it.initialized }
                    withContext(Dispatchers.Main) { repo.setForeground(true) }
                    repo.state.first { it.online }
                    repo.refresh("Codex Pocket MuMu integration").join()
                    val chat = repo.state.first { s -> s.chats.any { it.key.nativeId == threadId } }.chats.first { it.key.nativeId == threadId }
                    check(chat.title == "Codex Pocket MuMu integration" && chat.source == "cli")
                    repo.select(chat).join()
                    stage = "advertised image capability"
                    check(repo.state.value.currentModel?.images == true && !repo.state.value.uncertainSend)
                    stage = "local attachment"
                    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.rgb(34, 88, 218))
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    bitmap.recycle()
                    repo.attach(listOf(Uri.fromFile(file))).join()
                    check(repo.state.value.attachments.size == 1)
                    stage = "encrypted upload and native image input"
                    repo.editDraft("Describe the dominant color in the attached image using exactly one of RED, GREEN, BLUE, YELLOW. Do not use tools.")
                    repo.send().join()
                    check(repo.state.value.error == null)
                    val turn = repo.state.first { it.timeline.activeTurn != null }.timeline.activeTurn
                    val done = repo.state.first { s -> s.timeline.activeTurn == null && s.timeline.items.values.any { it.turnId == turn && it.type == "agentMessage" && it.complete } }
                    check(done.timeline.items.values.any { it.turnId == turn && it.type == "agentMessage" && Regex("\\bBLUE\\b").containsMatchIn(it.text) })
                    check(done.timeline.items.values.any { it.turnId == turn && it.type == "userMessage" && it.raw.array("content").any { input -> input.asObject().string("type") in setOf("image", "localImage") } })
                    check(done.attachments.isEmpty())
                    writeLiveReport("image-report.json", buildJsonObject {
                        put("passed", true); put("nativeThreadId", threadId); put("nativeImageRecorded", true); put("colorRecognized", true)
                    })
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Live image failed at $stage (${failure.javaClass.simpleName}); uiError=${repo.state.value.error}")
        } finally {
            file.delete()
            runBlocking { withContext(Dispatchers.Main) { repo.setForeground(false) } }
        }
    }
}
