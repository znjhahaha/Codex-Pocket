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

/** Opens an already recorded child by native ID and returns. No model/control requests. */
@RunWith(AndroidJUnit4::class)
class LiveAgentBrowseTest {
    @Test fun existingNativeChildAndCompactionState() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveAgentBrowse") == "true")
        val parentId = args.getString("parentThreadId")?.takeIf(String::isNotBlank) ?: error("Explicit parent thread required")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketApplication
        val repo = app.repository
        var stage = "connect"
        var parentKey: ThreadKey? = null
        var childId: String? = null
        try {
            runBlocking { withTimeout(180_000) {
                repo.state.first { it.initialized }
                withContext(Dispatchers.Main) { repo.setForeground(true) }
                repo.state.first { it.online }
                repo.refresh().join()
                val parent = repo.state.value.chats.first { it.key.nativeId == parentId }
                parentKey = parent.key
                stage = "parent"
                repo.select(parent).join()
                check(repo.state.value.conversationLoadState == ConversationLoadState.Ready)
                val timeline = repo.state.value.timeline
                val roster = agentRoster(timeline)
                check(roster.isNotEmpty()) { "Native history did not include children" }
                val liveStatus = ConversationPresentation.liveStatus(timeline)
                if (liveStatus == ConversationPresentation.LiveStatus.Compacting)
                    check(timeline.items.values.any { it.turnId == timeline.activeTurn && it.type == "contextCompaction" && it.liveStarted && !it.complete })
                val draft = repo.state.value.draft?.text
                val child = roster.lastOrNull { it.isCurrent } ?: roster.last()
                childId = child.threadId
                stage = "child"
                repo.showAgentReader()
                repo.openAgentReader(child.threadId).join()
                stage = "child_selection"
                check(repo.state.value.selected?.key == parent.key)
                check(repo.state.value.agentReader?.child?.key?.nativeId == child.threadId)
                stage = "child_history"
                check(repo.state.value.agentReader?.loading == false && repo.state.value.agentReader?.error == null)
                stage = "child_connection"
                check(repo.state.value.online)
                val childItems = repo.state.value.agentReader!!.timeline.items.size
                check(childItems > 0)
                check(repo.state.value.chats.none { it.key.nativeId == child.threadId })
                stage = "return"
                repo.agentReaderFullscreen(true)
                repo.agentReaderFullscreen(false)
                repo.closeAgentReader()
                check(repo.state.value.selected?.key == parent.key && repo.state.value.draft?.text == draft)
                writeLiveReport("agents-report.json", buildJsonObject {
                    put("passed", true); put("modelRequests", 0); put("agents", roster.size); put("childItems", childItems)
                    put("returned", true); put("draftPreserved", true); put("online", repo.state.value.online)
                    put("liveStatus", liveStatus?.name); put("compactionHistoryItems", timeline.items.values.count { it.type == "contextCompaction" })
                })
            } }
        } catch (failure: Throwable) {
            writeLiveReport("agents-report.json", buildJsonObject {
                put("passed", false); put("stage", stage); put("exception", failure.javaClass.simpleName)
                put("online", repo.state.value.online); put("modelRequests", 0)
                put("selectedId", repo.state.value.selected?.key?.nativeId); put("childId", childId)
                put("loadState", repo.state.value.conversationLoadState.name)
                put("connectionPhase", repo.state.value.connectionPhase)
                put("diagnostics", buildJsonArray { repo.connectionDiagnostics().forEach { entry -> add(buildJsonObject {
                    put("generation", entry.generation); put("stage", entry.stage)
                    put("code", entry.code); put("elapsedMs", entry.elapsedMs)
                    put("exceptionType", entry.exceptionType); put("location", entry.location)
                }) } })
            })
            throw AssertionError("Existing-child read failed at $stage (${failure.javaClass.simpleName})")
        } finally { runBlocking {
            parentKey?.let { withTimeoutOrNull(30_000) { repo.returnFromAgent(it).join() } }
            withContext(Dispatchers.Main) { repo.setForeground(false) }
        } }
    }
}
