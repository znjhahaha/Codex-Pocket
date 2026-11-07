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

/** Reads existing conversations only. Never creates a turn, sends input or resumes a runtime. */
@RunWith(AndroidJUnit4::class)
class LiveBrowseTest {
    @Test fun existingChatsStayConnected() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveBrowse") == "true")
        val projectCwd = args.getString("projectCwd")?.takeIf { it.isNotBlank() }
            ?.replace('\\', '/')?.removePrefix("//?/")?.trimEnd('/')
            ?: error("Explicit projectCwd required for existing-history acceptance")
        val longId = args.getString("longThreadId")?.takeIf { it.isNotBlank() }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketApplication
        val repo = app.repository
        val checks = mutableListOf<JsonElement>()
        var stage = "connect"
        var drops = 0
        try {
            runBlocking { withTimeout(600_000) {
                repo.state.first { it.initialized }
                withContext(Dispatchers.Main) { repo.setForeground(true) }
                withTimeout(45_000) { repo.state.first { it.online } }
                repo.refresh().join()
                fun inProject(chat: ChatEntity): Boolean {
                    val cwd = chat.cwd.replace('\\', '/').removePrefix("//?/").trimEnd('/')
                    return cwd.equals(projectCwd, true) || cwd.startsWith("$projectCwd/", true)
                }
                withTimeout(10_000) { repo.state.first { state -> state.chats.any { inProject(it) && state.runtimes.any { r -> r.id == it.runtimeId && r.online } } } }
                val available = repo.state.value.chats.filter {
                    !it.archived && !it.conflict && inProject(it) &&
                        repo.state.value.runtimes.any { r -> r.id == it.runtimeId && r.online }
                }.sortedByDescending { it.updatedAt }
                val candidates = (available.filter { it.key.nativeId == longId } +
                    available.filter { it.status.contains("error", true) || it.status.contains("fail", true) }.take(2) +
                    available.filter { it.status.contains("active", true) || it.status.contains("running", true) }.take(2) +
                    available).distinctBy { it.key }.take(8)
                check(candidates.size >= 2) { "Existing project chats required" }
                val monitor = launch {
                    var online = true
                    repo.state.collect { s -> if (online && !s.online) drops++; online = s.online }
                }
                try {
                    val repeats = args.getString("switches")?.toIntOrNull()?.coerceIn(1, 20) ?: 20
                    repeat(repeats) { index ->
                        stage = "history_${index + 1}"
                        val chat = candidates[index % candidates.size]
                        withTimeout(40_000) { repo.select(chat).join() }
                        delay(250)
                        val s = repo.state.value
                        checks += buildJsonObject { put("threadId", chat.key.nativeId); put("source", chat.source); put("status", chat.status); put("online", s.online); put("items", s.timeline.items.size); put("textChars", s.timeline.items.values.sumOf { it.text.length }); put("hasOlder", s.nextTurns != null); put("historyError", s.conversationLoadError); put("loadState", s.conversationLoadState.name) }
                        check(s.online && drops == 0) { "Opening history disconnected transport" }
                        check(s.selected?.key == chat.key && s.conversationLoadState == ConversationLoadState.Ready) { "History was not loaded for selected chat" }
                        check(s.timeline.items.isNotEmpty()) { "Expected existing history" }
                        if (chat.key.nativeId == longId) check(s.timeline.items.size > 100) { "Long conversation lost full tool details" }
                    }
                    stage = "dwell"
                    val seconds = args.getString("dwellSeconds")?.toIntOrNull()?.coerceIn(0, 120) ?: 120
                    repeat(seconds) { delay(1000); check(repo.state.value.online && drops == 0) }
                    writeLiveReport("browse-report.json", buildJsonObject { put("passed", true); put("switches", repeats); put("dwellSeconds", seconds); put("connectionDrops", drops); put("modelRequests", 0); put("checks", JsonArray(checks)) })
                } finally { monitor.cancelAndJoin() }
            } }
        } catch (failure: Throwable) {
            val s = repo.state.value
            writeLiveReport("browse-report.json", buildJsonObject { put("passed", false); put("stage", stage); put("exception", failure.javaClass.simpleName); put("online", s.online); put("phase", s.connectionPhase); put("detail", s.connectionDetail); put("connectionDrops", drops); put("modelRequests", 0); put("checks", JsonArray(checks)); put("diagnostics", buildJsonArray { repo.connectionDiagnostics().forEach { add(buildJsonObject { put("generation", it.generation); put("stage", it.stage); put("code", it.code); put("elapsedMs", it.elapsedMs) }) } }) })
            throw AssertionError("Read-only browse failed at $stage (${failure.javaClass.simpleName})")
        } finally { runBlocking { withContext(Dispatchers.Main) { repo.setForeground(false) } } }
    }
}
