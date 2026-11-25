package dev.codexpocket.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Existing paired QA, read-only: never starts a turn or writes conversation metadata. */
class ConversationOpenDeviceTest {
    @Test fun existingConversationColdOpenAndWarmReturn() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("conversationOpenDevice") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketApplication
        val repo = app.repository
        val checks = mutableListOf<JsonObject>()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        try {
            withTimeout(60_000) {
                repo.state.first { it.initialized }
                repo.setForeground(true)
                repo.state.first { it.online }
                checks += buildJsonObject { put("stage", "connection"); put("elapsedMs", elapsed()) }
                repo.ensureDirectory().join()
                val state = repo.state.first { it.chats.isNotEmpty() }
                val chat = state.chats.filter { !it.archived && !it.conflict && !it.isSubagent }.sortedByDescending { it.updatedAt }
                    .firstOrNull { row -> state.runtimes.any { it.id == row.runtimeId && it.online } }
                    ?: error("No online existing conversation available")
                suspend fun open(stage: String) {
                    val start = System.nanoTime()
                    repo.select(chat).join()
                    val loaded = repo.state.value
                    assertEquals(chat.key, loaded.selected?.key)
                    assertEquals(ConversationLoadState.Ready, loaded.conversationLoadState)
                    assertTrue(loaded.online)
                    checks += buildJsonObject {
                        put("stage", stage); put("elapsedMs", (System.nanoTime() - start) / 1_000_000)
                        put("items", loaded.timeline.items.size); put("label", loaded.automaticConnectionLabel())
                        put("readSource", loaded.conversationReadSource); put("permissions", loaded.permissions.status)
                        put("models", loaded.models.size)
                    }
                }
                open("cold_open")
                val metadata = withTimeoutOrNull(15_000) { repo.state.first {
                    it.permissions.status in setOf("ready", "saved") && it.models.isNotEmpty()
                } }
                checks += buildJsonObject { put("stage", "metadata"); put("ready", metadata != null)
                    put("permissions", repo.state.value.permissions.status); put("models", repo.state.value.models.size) }
                open("reentry")
                val epoch = repo.state.value.mediaConnectionEpoch
                repo.setForeground(false)
                delay(10_000)
                repo.setForeground(true)
                assertTrue(repo.state.value.online)
                assertEquals("Ten-second background visit must reuse the authenticated connection", epoch, repo.state.value.mediaConnectionEpoch)
                checks += buildJsonObject { put("stage", "background_return"); put("retainedLink", true); put("backgroundMs", 10000) }
                File(app.getExternalFilesDir(null), "conversation-open-acceptance.json").writeText(buildJsonObject {
                    put("passed", true); put("version", BuildConfig.VERSION_NAME); put("checks", JsonArray(checks))
                }.toString())
            }
        } finally { repo.setForeground(false, retainConnection = false) }
    }
}
