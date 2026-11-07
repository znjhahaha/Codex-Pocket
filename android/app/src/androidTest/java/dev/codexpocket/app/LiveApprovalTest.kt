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

/** Uses only an advertised deny action on a separately initiated marked native probe. */
@RunWith(AndroidJUnit4::class)
class LiveApprovalTest {
    @Test fun nativeDeclineFromPhone() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveApproval") == "true")
        val threadId = args.getString("threadId") ?: error("Dedicated integration thread required")
        val marker = args.getString("marker") ?: error("Dedicated command marker required")
        require(marker.matches(Regex("MUMU_APPROVAL_[0-9a-f]{32}")))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketApplication
        val repo = app.repository
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
                    stage = "native approval delivery"
                    val pending = repo.state.first { s -> s.approvals.any { it.threadId == threadId && it.params.string("command").contains(marker) } }
                        .approvals.first { it.threadId == threadId && it.params.string("command").contains(marker) }
                    check(pending.method == "item/commandExecution/requestApproval" && pending.runtimeId == chat.runtimeId)
                    val nativeTurnId = pending.params.string("turnId")
                    check(nativeTurnId.isNotBlank())
                    val advertised = pending.params["availableDecisions"] as? JsonArray
                    fun offered(value: String) = advertised == null || JsonPrimitive(value) in advertised
                    val decision = when {
                        offered("decline") -> "decline"
                        offered("cancel") -> "cancel"
                        else -> error("Native request offers no supported deny decision")
                    }
                    stage = "phone decline"
                    repo.respond(pending, buildJsonObject { put("decision", decision) }).join()
                    check(repo.state.value.error == null) { "Phone decision was rejected" }
                    stage = "obsolete approval removal"
                    repo.state.first { s -> s.approvals.none { it.key == pending.key } }
                    stage = "native turn completion"
                    repo.state.first { s -> s.timeline.activeTurn == null &&
                        if (decision == "cancel") s.timeline.status == "interrupted"
                        else s.timeline.items.values.any { it.type == "agentMessage" && it.text.contains("MUMU_APPROVAL_DENIED") } }
                    stage = "independent native outcome inspection"
                    repo.checkCommands().join()
                    delay(100)
                    val wasUnknown = repo.state.value.uncertainSend
                    if (wasUnknown) {
                        check(repo.state.value.unresolvedCommands.all { it.method == "approvals/respond" && it.chatKey == chat.key })
                        repo.inspectUnknownHistory().join()
                        check(repo.state.value.timeline.items.values.any { it.type == "userMessage" && it.text.contains(marker) })
                        if (decision == "cancel") check(nativeTurnId in repo.state.value.timeline.completedTurns)
                        else check(repo.state.value.timeline.items.values.any { it.type == "commandExecution" && it.status == "declined" && it.text.contains(marker) })
                        check(repo.state.value.unknownHistoryInspected)
                        repo.resolveUnknownAfterInspection().join()
                        repo.state.first { !it.uncertainSend }
                    }
                    writeLiveReport("approval-report.json", buildJsonObject {
                        put("passed", true); put("sameNativeThreadId", threadId); put("decision", decision)
                        put("obsoleteApprovalRemoved", true); put("unknownBeforeInspection", wasUnknown)
                        put("nativeOutcomeInspectedBeforeResolution", wasUnknown)
                    })
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Live approval failed at $stage (${failure.javaClass.simpleName}); sensitive native diagnostics omitted")
        } finally {
            runBlocking { withContext(Dispatchers.Main) { repo.setForeground(false) } }
        }
    }
}
