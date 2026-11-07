package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativePresentationTest {
    private fun raw(value: String) = pocketJson.parseToJsonElement(value).jsonObject
    private val chat = ChatEntity(ThreadKey("pc", "config", "thread"), "runtime", "desktop", "Title", "", "", 0, "completed", false)
    private val runtime = RuntimeInfo("runtime", "desktop", "v", "config", true, setOf("thread"), emptySet())

    @Test fun planPreservesNativeStepsAndUnknownStatusesWithoutGuessing() {
        val item = TimelineItem("turn", "event", "turn/plan/updated", raw = raw("""{"params":{"explanation":"验证","plan":[{"step":"测试","status":"completed"},{"step":"发布","status":"future-state"}]}}"""))
        val plan = nativePlan(item)!!
        assertEquals("验证", plan.explanation)
        assertEquals(listOf("completed", "future-state"), plan.steps.map { it.status })
        assertNull(nativePlan(item.copy(raw = raw("""{"params":{"plan":[{"status":"pending"}]}}"""))))
        assertNull(nativePlan(item.copy(type = "mcpToolCall")))
    }

    @Test fun queueChangeWithoutEntriesIsNotTurnedIntoAnInventedPlan() {
        val queue = TimelineItem("", "event:7", "thread/queue/changed", raw = raw("""{"params":{"threadId":"thread"}}"""))
        assertNull(nativePlan(queue))
        assertTrue(diffFiles(queue).isEmpty())
    }

    @Test fun fileChangesPreservePathsDuplicateEntriesAndEmptyBinaryDiffs() {
        val item = TimelineItem("turn", "change", "fileChange", raw = raw("""{"changes":[{"path":"src/a.kt","kind":{"type":"update"},"diff":"@@ -1 +1 @@\n-old\n+new"},{"path":"src/a.kt","kind":{"type":"delete"},"diff":""}]}"""))
        val files = diffFiles(item)
        assertEquals(2, files.size)
        assertNotEquals(files[0].id, files[1].id)
        assertEquals("src/a.kt", files[0].path)
        assertEquals(1, files[0].additions)
        assertEquals(1, files[0].deletions)
        assertEquals("delete", files[1].kind)
        assertEquals("", files[1].diff)
    }

    @Test fun gitDiffListsBothFilesAndKeepsHunksWhole() {
        val diff = "diff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-old\n+new\ndiff --git a/b.kt b/b.kt\n--- a/b.kt\n+++ b/b.kt\n@@ -1 +1 @@\n-before\n+after"
        val item = TimelineItem("turn", "diff", "turn/diff/updated", raw = buildJsonObject { put("params", buildJsonObject { put("diff", diff) }) })
        val files = diffFiles(item)
        assertEquals(listOf("a.kt", "b.kt"), files.map { it.path })
        assertTrue(files[0].diff.endsWith("+new"))
        assertTrue(files[1].diff.contains("-before\n+after"))
    }

    @Test fun headerLikeDeletedAndAddedLinesDoNotCreateFakeFiles() {
        val diff = "--- a/one\n+++ b/one\n@@ -1 +1 @@\n--- old data\n+++ new data\n--- a/two\n+++ b/two\n@@ -0,0 +1 @@\n+added"
        val item = TimelineItem("turn", "diff", "turn/diff/updated", raw = buildJsonObject { put("params", buildJsonObject { put("diff", diff) }) })
        assertEquals(listOf("one", "two"), diffFiles(item).map { it.path })
        assertEquals(1, diffFiles(item).first().additions)
        assertEquals(1, diffFiles(item).first().deletions)
    }

    @Test fun nativePlanNormalizesProgressSpellingButKeepsUnknownStates() {
        val item = TimelineItem("t", "p", "turn/plan/updated", raw = raw("""{"params":{"plan":[{"step":"Run tests","status":"in_progress"},{"step":"Ship","status":"pending"}]}}"""))
        val plan = nativePlan(item)!!
        assertEquals("inProgress", plan.steps.first().status)
        assertEquals(0, plan.completed)
        assertEquals("Run tests", plan.activeStep?.text)
    }

    @Test fun chatStatusUsesRuntimeConnectionApprovalsAndLiveTurnBeforeCachedCompletion() {
        val connected = PocketState(online = true, chats = listOf(chat), runtimes = listOf(runtime), selected = chat)
        assertEquals("completed", chatStatus(connected, chat))
        val running = connected.copy(timeline = NativeTimeline(activeTurn = "turn"))
        assertEquals("inProgress", chatStatus(running.copy(busy = true), chat))
        val pending = running.copy(approvals = listOf(PendingApproval("runtime", JsonPrimitive(1), "approval", raw("""{"threadId":"thread"}"""))))
        assertEquals("pending", chatStatus(pending, chat))
        assertEquals("offline", chatStatus(pending.copy(runtimes = listOf(runtime.copy(online = false))), chat))
        assertEquals("offline", chatStatus(pending.copy(online = false), chat))
    }

    @Test fun unresolvedCommandsDoNotChangeAnotherChatsStatus() {
        val command = CommandEntity("cmd", "pc", chat.key, "turn/start", "unknown", 0)
        val state = PocketState(online = true, runtimes = listOf(runtime), commands = listOf(command))
        assertEquals("unknown", chatStatus(state, chat))
        assertEquals("completed", chatStatus(state, chat.copy(key = chat.key.copy(nativeId = "other"))))
    }
}
