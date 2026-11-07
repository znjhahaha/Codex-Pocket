package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HomeStateTest {
    private fun chat(id: String, cwd: String = "D:/Work/Pocket", computer: String = "pc", runtime: String = "desktop", updated: Long = 10) =
        ChatEntity(ThreadKey(computer, "store", id), runtime, "desktop", "Native $id", "A useful summary", cwd, updated, "completed", false)

    @Test fun allProjectsGroupByComputerAndExactDirectoryWithStableOrdering() {
        val first = chat("a")
        val second = chat("b", cwd = "D:/Other/Pocket")
        val otherComputer = chat("c", computer = "pc-2")
        val olderDuplicate = first.copy(updatedAt = 1, title = "stale")
        val rows = listOf(otherComputer, second, olderDuplicate, first)
        val groups = homeProjectGroups(rows)
        assertEquals(3, groups.size)
        assertEquals(3, groups.map { it.id }.distinct().size)
        assertEquals(groups, homeProjectGroups(rows.reversed()))
        assertEquals("Native a", groups.flatMap { it.chats }.single { it.key == first.key }.title)
        assertEquals(3, groups.sumOf { it.chats.size })
    }

    @Test fun selectedProjectPartitionsRunningPinnedRecentWithoutDuplicates() {
        val running = chat("running").copy(pinned = true)
        val pin = chat("pin").copy(pinned = true)
        val recent = chat("recent")
        val rows = listOf(recent, running, pin, running)
        val groups = homeChatGroups(rows, mapOf(running.key.encoded to "inProgress"), "", false)
        assertEquals(listOf("running", "pinned", "recent"), groups.map { it.id })
        assertEquals(listOf(running), groups[0].chats)
        assertEquals(listOf(pin), groups[1].chats)
        assertEquals(listOf(recent), groups[2].chats)
        assertEquals(listOf("search"), homeChatGroups(rows, emptyMap(), "query", false).map { it.id })
        assertEquals(listOf("archive"), homeChatGroups(rows, emptyMap(), "query", true).map { it.id })
    }

    @Test fun filtersApplyComputerRuntimeProjectArchiveAndSafeSearchTogether() {
        val match = chat("a").copy(archived = true)
        val rows = listOf(match, chat("b", computer = "pc-2").copy(archived = true),
            chat("c", runtime = "cli").copy(archived = true), chat("d"),
            chat("e", cwd = "D:/Other/Pocket").copy(archived = true))
        assertEquals(listOf(match), homeFilteredChats(rows, "pc", "desktop", HomeProject("pc", match.cwd), "useful", true))
        assertTrue(homeFilteredChats(rows, "pc", "desktop", HomeProject("pc", match.cwd.lowercase()), "", true).isEmpty())
        val unsafe = match.copy(preview = "{\"method\":\"hook/started\",\"params\":{\"text\":\"secret-needle\"}}")
        assertTrue(homeFilteredChats(listOf(unsafe), "pc", null, null, "secret-needle", true).isEmpty())
    }

    @Test fun summariesNeverExposeProtocolHooksOrStructuredPayloads() {
        listOf("{\"method\":\"thread/updated\"}", "[ {\"type\":\"hookPrompt\"} ]",
            "<hook_context>internal</hook_context>", "hook/started secret", "item/completed {payload}", "SessionStart:resume internal context", "Hook started: internal",
            "```json\n{\"method\":\"item/started\"}\n```", "<send_user_message_question_reply>private</send_user_message_question_reply>")
            .forEach { assertEquals(it, "", homeSafePreview(it)) }
        assertEquals("Fix React hooks and add tests", homeSafePreview("Fix React hooks\n  and add tests"))
        assertTrue(homeSafePreview("a".repeat(400)).length <= 160)
        assertEquals("", homeSafePreview("  "))
    }

    @Test fun scopeKeysCannotBleedBetweenComputersRuntimesOrProjects() {
        val scopes = listOf(homeScopeKey("pc", null), homeScopeKey("pc", "cli"), homeScopeKey("pc-2", "cli"),
            homeScopeKey("a:b", "c"), homeScopeKey("a", "b:c"))
        assertEquals(scopes.size, scopes.distinct().size)
        assertNotEquals(HomeProject("pc", "D:/A").id, HomeProject("pc-2", "D:/A").id)
        assertNotEquals(HomeProject("pc", "D:/A").id, HomeProject("pc", "D:/a").id)
    }

    @Test fun submittedApprovalsAreNotPendingButUnansweredQuestionsAre() {
        val row = chat("a")
        val runtime = RuntimeInfo("desktop", "desktop", "1", "store", true, emptySet(), emptySet())
        val approval = PendingApproval("desktop", JsonPrimitive(1), "item/commandExecution/requestApproval", buildJsonObject { put("threadId", "a") }, submitted = true)
        val state = PocketState(online = true, computerId = "pc", runtimes = listOf(runtime), chats = listOf(row), approvals = listOf(approval))
        assertEquals("completed", homeChatStatus(state, row))
        val question = buildJsonObject { put("id", "q"); put("title", "Choose"); put("submitted", false) }
        val pending = state.copy(pendingQuestions = mapOf(row.key.encoded to listOf(question)))
        assertEquals("pending", homeChatStatus(pending, row))
        assertEquals("pending", homeChatStatus(pending.copy(online = false), row))
        assertEquals("failed", homeChatStatus(state.copy(online = false), row.copy(status = "failed")))
        assertEquals("offline", homeChatStatus(state.copy(online = false), row))
    }

    @Test fun secondsAndMillisecondsRepresentTheSameUpdateTime() {
        assertEquals(1_799_999_999_000L, homeUpdatedAtMillis(1_799_999_999L))
        assertEquals(1_799_999_999_000L, homeUpdatedAtMillis(1_799_999_999_000L))
        assertEquals(0L, homeUpdatedAtMillis(-1))
    }

    @Test fun projectChipsKeepRecentOrderAndRetainSelectionAcrossPartialRefreshes() {
        val old = chat("old", cwd = "D:/Old", updated = 1)
        val recent = chat("recent", cwd = "D:/Recent", updated = 20)
        val selected = HomeProject("pc", "D:/Selected")
        assertEquals(listOf(selected, HomeProject("pc", recent.cwd), HomeProject("pc", old.cwd)),
            homeProjects(listOf(old, recent, recent), "pc", null, selected))
        assertTrue(homeProjects(listOf(old, recent), "different-pc", null, null).isEmpty())
    }

    @Test fun blankDirectoryIsOneRealProjectAndNeverAllProjects() {
        val unassigned = chat("blank", cwd = "")
        val other = chat("other")
        assertEquals(listOf(unassigned), homeFilteredChats(listOf(unassigned, other), "pc", null, HomeProject("pc", ""), "", false))
        assertEquals(2, homeProjectGroups(listOf(unassigned, other)).size)
    }
}
