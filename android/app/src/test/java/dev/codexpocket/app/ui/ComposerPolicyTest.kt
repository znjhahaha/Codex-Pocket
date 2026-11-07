package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComposerPolicyTest {
    private val key = ThreadKey("pc", "store", "thread")
    private val chat = ChatEntity(key, "runtime", "desktop", "Test", "", "", 0, "inProgress", false)
    private fun state(capabilities: Set<String>) = PocketState(online = true, selected = chat, draft = DraftEntity(key, text = "Hello"), runtimes = listOf(RuntimeInfo("runtime", "desktop", "v", "store", true, setOf("thread"), capabilities)), timeline = NativeTimeline(activeTurn = "turn"))
    @Test fun queueAndSteerRequireTheirOwnAdvertisedMethods() {
        val queueOnly = composerAvailability(state(setOf("thread/queue/add")))
        assertTrue(queueOnly.send); assertFalse(queueOnly.steer)
        val steerOnly = composerAvailability(state(setOf("turn/steer")))
        assertFalse(steerOnly.send); assertTrue(steerOnly.steer)
        assertFalse(composerAvailability(state(emptySet())).send)
    }
    @Test fun uncertainOwnershipAndOfflineDisableBothMutations() {
        val ready = state(setOf("thread/queue/add", "turn/steer"))
        listOf(ready.copy(online = false), ready.copy(selected = chat.copy(conflict = true)), ready.copy(selected = chat.copy(archived = true)), ready.copy(commands = listOf(CommandEntity("cmd", "pc", key, "turn/start", "unknown", 0)))).forEach {
            assertFalse(composerAvailability(it).send); assertFalse(composerAvailability(it).steer)
        }
    }
    @Test fun queueMovesUseNextSiblingAfterRemovalAndNullForEnd() {
        assertEquals("c", queueMoveBefore(listOf("a", "b", "c"), 0, 1))
        assertNull(queueMoveBefore(listOf("a", "b", "c"), 1, 1))
        assertEquals("a", queueMoveBefore(listOf("a", "b", "c"), 1, -1))
    }
    @Test fun inlineCommentUsesNewHunkLineNotDisplayIndex() {
        assertEquals(listOf(null, null, null, null, 20, 21), diffNewLineNumbers("--- a/a\n+++ b/a\n@@ -10,2 +20,2 @@\n-old\n+new\n same"))
    }
    @Test fun imageSourcesExcludeDesktopPathsAndHiddenHooks() {
        val item = TimelineItem("t", "i", "userMessage", raw = pocketJson.parseToJsonElement("""{"content":[{"type":"localImage","path":"C:/private/a.png"},{"type":"image","url":"data:image/png;base64,a"}]}""").jsonObject)
        assertEquals(listOf("data:image/png;base64,a"), transcriptImageSources(item))
        assertTrue(transcriptImageSources(item.copy(type = "hookPrompt")).isEmpty())
    }
}
