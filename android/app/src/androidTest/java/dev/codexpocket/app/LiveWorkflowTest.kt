package dev.codexpocket.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.serialization.json.*

/** Opt-in controls only dedicated probe chats. Credentials stay in encrypted app storage. */
@RunWith(AndroidJUnit4::class)
class LiveWorkflowTest {
    @Test fun encryptedNativeWorkflow() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveWorkflow") == "true")
        val fixtureCwd = args.getString("fixtureCwd")?.takeIf { it.isNotBlank() }
            ?: error("Explicit fixtureCwd required for dedicated acceptance chats")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketApplication
        val repository = app.repository
        var stage = "initialization"
        try {
            runBlocking {
                withTimeout(240_000) {
                    repository.state.first { it.initialized }
                    check(repository.state.value.paired)
                    withContext(Dispatchers.Main) { repository.setForeground(true) }
                    stage = "encrypted reconnect and runtime discovery"
                    repository.state.first { it.online }
                    check(repository.state.value.runtimes.any { it.online && it.source == "cli" })
                    check(repository.state.value.runtimes.any { it.online && it.source == "desktop" })
                    val desktopId = args.getString("desktopThreadId") ?: error("Dedicated desktop probe ID required")
                    stage = "desktop history and native ID"
                    repository.refresh("DESKTOP_PHONE_TEST").join()
                    val desktop = repository.state.value.chats.first { it.key.nativeId == desktopId }
                    stage = "desktop source classification"
                    check(desktop.source == "desktop") { "Original desktop test must route through the desktop runtime" }
                    stage = "desktop selected history hydration"
                    repository.select(desktop).join()
                    check(repository.state.value.selected?.key?.nativeId == desktopId)
                    stage = "desktop marker in native history"
                    check(repository.state.value.timeline.items.values.any { it.text.contains("DESKTOP_PHONE_TEST") })
                    stage = "desktop model catalog"
                    check(repository.state.value.models.isNotEmpty())
                    if (args.getString("desktopOnly") == "true") {
                        stage = "resume original desktop history after bridge restart"
                        check(desktop.source == "desktop")
                        repository.resume().join()
                        check(repository.state.value.error == null)
                        check(repository.state.value.selected?.key?.nativeId == desktopId)
                    }
                    if (args.getString("skipDesktopMutation") != "true") {
                    stage = "desktop follow-up"
                    val desktopMarker = "MUMU_DESKTOP_" + System.currentTimeMillis()
                    repository.editDraft("Reply exactly $desktopMarker. Do not use tools.")
                    repository.send().join()
                    check(repository.state.value.error == null) { "Send failed" }
                    repository.state.first { s -> s.timeline.activeTurn == null && s.timeline.items.values.any { it.type == "agentMessage" && it.text.contains(desktopMarker) } }
                    }
                    if (args.getString("desktopOnly") == "true") {
                        writeLiveReport("desktop-report.json", buildJsonObject {
                            put("passed", true); put("desktopThreadId", desktopId)
                            put("sameNativeIdAfterRestart", true); put("phoneFollowup", true)
                            put("desktopAndCliDiscovered", true)
                        })
                        return@withTimeout
                    }
                    stage = "CLI runtime discovery after desktop follow-up"
                    repository.refresh().join()
                    stage = "mobile-created native CLI chat"
                    val cli = repository.state.value.runtimes.first { it.online && it.source == "cli" }
                    stage = "CLI thread/start"
                    repository.create(cli, fixtureCwd).join()
                    check(repository.state.value.error == null)
                    val created = repository.state.value.selected ?: error("Missing native chat")
                    check(created.source == "cli" && created.key.nativeId != desktopId)
                    stage = "CLI rename"
                    repository.rename("Codex Pocket MuMu integration").join()
                    val cliMarker = "MUMU_CLI_" + System.currentTimeMillis()
                    repository.editDraft("Reply exactly $cliMarker. Do not use tools.")
                    stage = "CLI follow-up"
                    repository.send().join()
                    check(repository.state.value.error == null)
                    repository.state.first { s -> s.timeline.activeTurn == null && s.timeline.items.values.any { it.type == "agentMessage" && it.text.contains(cliMarker) } }
                    stage = "disconnect, draft persistence and catch-up"
                    val before = repository.state.value.timeline.items.values.count { it.type == "agentMessage" && it.text.contains(cliMarker) }
                    repository.editDraft("未发送的中文草稿")
                    repository.select(desktop).join()
                    repository.select(created).join()
                    check(repository.state.value.draft?.text == "未发送的中文草稿")
                    withContext(Dispatchers.Main) { repository.setForeground(false) }
                    repository.state.first { !it.online }
                    withContext(Dispatchers.Main) { repository.setForeground(true) }
                    repository.state.first { it.online }
                    check(repository.state.value.selected?.key == created.key)
                    check(repository.state.value.draft?.text == "未发送的中文草稿")
                    val after = repository.state.value.timeline.items.values.count { it.type == "agentMessage" && it.text.contains(cliMarker) }
                    check(before == 1 && after == before)
                    check(!repository.state.value.uncertainSend)
                    File(app.filesDir, "integration").mkdirs()
                    writeLiveReport("workflow-report.json", buildJsonObject {
                        put("passed", true); put("desktopThreadId", desktopId); put("cliThreadId", created.key.nativeId)
                        put("desktopFollowupTested", args.getString("skipDesktopMutation") != "true")
                        put("cliMarker", cliMarker); put("draftPreserved", true); put("duplicateReplies", after - before)
                    })
                }
            }
        } catch (failure: Throwable) {
            val sources = repository.state.value.runtimes.map { "${it.source}:${it.online}" }
            throw AssertionError("Live workflow failed at $stage (${failure.javaClass.simpleName}); online=${repository.state.value.online}; sources=$sources; uiError=${repository.state.value.error}. Sensitive native diagnostics omitted.")
        } finally {
            runBlocking { withContext(Dispatchers.Main) { repository.setForeground(false) } }
        }
    }
}
