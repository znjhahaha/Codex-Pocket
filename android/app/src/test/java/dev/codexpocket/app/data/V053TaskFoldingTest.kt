package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V053TaskFoldingTest {
    private fun snapshot(items: String, status: String = "completed") = NativeReducer.snapshot(
        pocketJson.parseToJsonElement("""{"turns":[{"id":"t","status":"$status","items":[$items]}]}""").jsonObject)
    private val user = """{"id":"u","type":"userMessage","content":[{"type":"text","text":"开始"}]}"""
    private val final = """{"id":"f","type":"agentMessage","phase":"final_answer","text":"最终结果"}"""

    @Test fun historicalToolFailureDoesNotKeepSuccessfulTaskExpanded() {
        val state = snapshot("""$user,{"id":"bad","type":"commandExecution","status":"completed","exitCode":1},$final""")
        val rows = ConversationPresentation.rows(state)
        assertEquals(listOf(TranscriptKind.User, TranscriptKind.Process, TranscriptKind.Answer), rows.map { it.kind })
        assertEquals("bad", rows[1].items.single().itemId)
        assertTrue(rows[1].processLabel!!.contains("1 条消息"))
    }

    @Test fun successfulProposalIsVisibleWhileItsCommentaryFolds() {
        val state = snapshot("""$user,{"id":"c","type":"agentMessage","phase":"commentary","text":"检查中"},{"id":"p","type":"plan","text":"# 方案"}""")
        val rows = ConversationPresentation.rows(state)
        assertEquals(listOf(TranscriptKind.User, TranscriptKind.Process, TranscriptKind.Plan), rows.map { it.kind })
        assertEquals("# 方案", rows.last().items.single().text)
    }

    @Test fun finalAnswerAndPlanAreBothKeptWithTheirNativeIdentity() {
        val state = snapshot("""$user,{"id":"c","type":"agentMessage","phase":"commentary","text":"检查中"},{"id":"p","type":"plan","text":"# 方案"},$final""")
        val rows = ConversationPresentation.rows(state)
        assertEquals(listOf("p", "f"), rows.filter { it.kind in setOf(TranscriptKind.Plan, TranscriptKind.Answer) }.flatMap { it.items }.map { it.itemId })
        val open = expandTaskRows(rows, rows.filter { it.kind == TranscriptKind.Process }.map { it.key }.toSet())
        assertEquals(state.items.keys, open.filter { it.kind != TranscriptKind.Process }.flatMap { it.items }.map { it.key }.toSet())
    }

    @Test fun terminalTaskCanFoldAStaleRunningToolButNotAnApproval() {
        val stale = snapshot("""$user,{"id":"c","type":"commandExecution","status":"inProgress"},$final""")
        assertTrue(ConversationPresentation.rows(stale).any { it.kind == TranscriptKind.Process })
        val waiting = snapshot("""$user,{"id":"c","type":"commandExecution","status":"waitingOnApproval"},$final""")
        assertFalse(ConversationPresentation.rows(waiting).any { it.kind == TranscriptKind.Process })
        assertFalse(ConversationPresentation.rows(stale, setOf("t")).any { it.kind == TranscriptKind.Process })
    }

    @Test fun failedStoppedAndActiveTasksNeverGetSuccessfulFold() {
        for (status in listOf("failed", "interrupted", "inProgress")) {
            assertFalse(ConversationPresentation.rows(snapshot("""$user,{"id":"c","type":"agentMessage","phase":"commentary","text":"过程"},$final""", status))
                .any { it.kind == TranscriptKind.Process })
        }
    }

    @Test fun sparseCompletionRequestsOneAuthoritativeReadOnlyWhenFinalMissing() {
        val partial = snapshot("""$user,{"id":"c","type":"agentMessage","phase":"commentary","text":"检查中"}""")
        assertTrue(ConversationPresentation.needsCompletionRead(partial, "t"))
        assertFalse(ConversationPresentation.needsCompletionRead(snapshot("$user,$final"), "t"))
        assertFalse(ConversationPresentation.needsCompletionRead(snapshot(user, "inProgress"), "t"))
    }
}
