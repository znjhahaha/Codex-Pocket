package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationPresentationTest {
    private fun raw(s: String) = pocketJson.parseToJsonElement(s).jsonObject
    @Test fun hookVariantsDisappearFromLiveSnapshotAndOldCache() {
        listOf("hookPrompt", "hook", "hook/started", "hook/completed", "item/hook/started", "codex/event/hook_completed").forEach { type ->
            val item = TimelineItem("native-turn", "native-item", type, "secret hook data", raw = raw("""{"type":"$type"}"""))
            assertTrue(ConversationPresentation.rows(NativeTimeline(mapOf(item.key to item))).isEmpty())
            assertTrue(NativeReducer.reduce(NativeTimeline(), raw("""{"method":"item/completed","params":{"turnId":"native-turn","item":{"id":"native-item","type":"$type","text":"secret"}}}""")).items.isEmpty())
            assertTrue(NativeReducer.snapshot(raw("""{"turns":[{"id":"native-turn","status":"completed","items":[{"id":"native-item","type":"$type","text":"secret"}]}]}""")).items.isEmpty())
        }
        val cached = TimelineItem("t", "i", "unknown", "cached", raw = raw("""{"method":"item/completed","params":{"item":{"type":"hookPrompt"}}}"""))
        assertFalse(ConversationPresentation.visible(cached))
    }
    @Test fun hookMetadataIsNotConfusedWithUserProseAboutHooks() {
        val user = TimelineItem("t", "u", "userMessage", "Explain React hooks and hookPrompt")
        assertTrue(ConversationPresentation.visible(user))
        assertFalse(ConversationPresentation.visible(user.copy(raw = raw("""{"content":[{"type":"hookPrompt","text":"hidden"}]}"""))))
    }
    @Test fun unknownTelemetryNeverFallsBackToRawJson() {
        val telemetry = TimelineItem("t", "x", "future/telemetry", "debug", raw = raw("""{"secret":"raw"}"""))
        assertEquals("", ConversationPresentation.detail(telemetry))
        assertTrue(ConversationPresentation.rows(NativeTimeline(mapOf(telemetry.key to telemetry))).isEmpty())
        assertTrue(NativeReducer.reduce(NativeTimeline(), raw("""{"method":"future/telemetry","params":{"message":"debug"}}""")).items.isEmpty())
    }
    @Test fun webhookToolAndArbitraryToolPayloadCannotTriggerHookFiltering() {
        val tool = TimelineItem("t", "tool", "mcpToolCall", "Webhook delivered", raw = raw("""{"type":"mcpToolCall","tool":"webhook","arguments":{"type":"hookPrompt","hook":true},"result":{"source":"hook","content":[{"type":"text","text":"hook completed"}]}}"""))
        assertTrue(ConversationPresentation.visible(tool))
        assertTrue(ConversationPresentation.visible(tool.copy(type = "webSearch", raw = raw("""{"type":"webSearch","query":"webhook hookPrompt"}"""))))
        assertTrue(ConversationPresentation.visible(tool.copy(type = "agentMessage", text = "Your webhook uses hooks", raw = raw("""{"type":"agentMessage","text":"hookPrompt"}"""))))
    }
    @Test fun failedTurnsRetainMeaningfulErrorAndNativeIdentity() {
        val state = NativeReducer.reduce(NativeTimeline(activeTurn = "native-turn"), raw("""{"method":"turn/completed","params":{"turn":{"id":"native-turn","status":"failed","error":{"message":"Permission denied"}}}}"""), "cursor-42")
        assertNull(state.activeTurn)
        val row = ConversationPresentation.rows(state).single()
        assertEquals("native-turn", row.items.single().turnId)
        assertEquals("Permission denied", ConversationPresentation.detail(row.items.single()))
    }
    @Test fun compactToolGroupsKeepAllNativeIdsAndStableFirstKey() {
        val first = TimelineItem("t", "one", "commandExecution", "pwd")
        val second = TimelineItem("t", "two", "mcpToolCall")
        val timeline = NativeTimeline(linkedMapOf(first.key to first, second.key to second))
        val row = ConversationPresentation.rows(timeline).single()
        assertEquals(listOf("one", "two"), row.items.map { it.itemId })
        assertEquals(ConversationPresentation.rows(timeline.copy(items = mapOf(first.key to first))).single().key, row.key)
    }
    @Test fun commentaryNeverBecomesFinalJustBecauseItemCompleted() {
        val commentary = TimelineItem("t", "c", "agentMessage", "Checking", raw = raw("""{"phase":"commentary"}"""), complete = true)
        val final = commentary.copy(itemId = "f", text = "Done", raw = raw("""{"phase":"final_answer"}"""))
        assertEquals(TranscriptKind.Commentary, ConversationPresentation.rows(NativeTimeline(linkedMapOf(commentary.key to commentary, final.key to final)))[0].kind)
        assertEquals(TranscriptKind.Answer, ConversationPresentation.rows(NativeTimeline(mapOf(final.key to final))).single().kind)
    }
    @Test fun scrollAnchorSurvivesPrependAndFallsBackWhenItsGroupWasRemoved() {
        assertEquals(2, TranscriptAnchor("b", 17).resolve(listOf("older", "a", "b"), 1))
        assertEquals(1, TranscriptAnchor("gone", 17).resolve(listOf("a", "b"), 9))
        assertEquals(0, TranscriptAnchor("gone", 17).resolve(emptyList(), 9))
    }
    @Test fun completedTraceKeepsCommentaryBetweenContiguousToolGroups() {
        val tool = TimelineItem("t", "cmd", "commandExecution", "test", complete = true)
        val progress = TimelineItem("t", "progress", "agentMessage", "Checked", raw = raw("""{"phase":"commentary"}"""), complete = true)
        val file = TimelineItem("t", "file", "fileChange", complete = true)
        val answer = TimelineItem("t", "answer", "agentMessage", "Ready", complete = true)
        val timeline = NativeTimeline(listOf(tool, progress, file, answer).associateBy { it.key }, completedTurns = setOf("t"))
        val rows = ConversationPresentation.rows(timeline)
        assertEquals(listOf(TranscriptKind.Activity, TranscriptKind.Commentary, TranscriptKind.Activity, TranscriptKind.Answer), rows.map { it.kind })
        assertEquals(listOf("cmd"), rows.first().items.map { it.itemId })
        assertEquals("answer", rows.last().items.single().itemId)
        assertEquals(rows[2].key, ConversationPresentation.anchorKey(rows, "activity:${file.key}"))
    }

    @Test fun foldingNeverMovesAsyncOutputAcrossAnswersPlansOrOtherTurns() {
        val items = listOf(
            TimelineItem("one", "cmd", "commandExecution", complete = true),
            TimelineItem("one", "comment", "agentMessage", "Checking", raw = raw("""{"phase":"commentary"}"""), complete = true),
            TimelineItem("one", "plan", "turn/plan/updated", complete = true),
            TimelineItem("one", "file", "fileChange", complete = true),
            TimelineItem("one", "answer", "agentMessage", "Done", complete = true),
            TimelineItem("two", "user", "userMessage", "Next"),
            TimelineItem("one", "async", "functionCallOutput", raw = raw("""{"name":"check","output":"late result"}"""), complete = true),
            TimelineItem("two", "cmd", "commandExecution"),
        )
        val rows = ConversationPresentation.rows(NativeTimeline(items.associateBy { it.key }, completedTurns = setOf("one")))
        assertEquals(items.map { it.key }, rows.flatMap { it.items }.map { it.key })
        assertEquals(listOf("cmd"), rows.first().items.map { it.itemId })
        assertTrue(rows.filter { it.items.first().turnId == "one" }.all { it.completedTurn })
        assertFalse(rows.last().completedTurn)
        assertEquals("late result", ConversationPresentation.detail(items[6]))
    }

    @Test fun tokenUpdatesAndCompletionKeepCommentaryAndToolAnchorKeys() {
        val comment = TimelineItem("t", "comment", "agentMessage", "Checking", raw = raw("""{"phase":"commentary"}"""))
        val command = TimelineItem("t", "cmd", "commandExecution", "echo")
        val timeline = NativeTimeline(listOf(comment, command).associateBy { it.key })
        val live = ConversationPresentation.rows(timeline)
        val streaming = ConversationPresentation.rows(timeline.copy(items = listOf(comment, command.copy(text = "echo\nresult")).associateBy { it.key }))
        assertEquals(live.map { it.key }, streaming.map { it.key })
        val done = ConversationPresentation.rows(timeline.copy(completedTurns = setOf("t")))
        assertEquals(live.map { it.key }, done.map { it.key })
        assertEquals("activity:${command.key}", ConversationPresentation.anchorKey(done, "activity:${command.key}"))
        assertEquals(comment.key, ConversationPresentation.anchorKey(done, comment.key))
    }

    @Test fun nativeAsyncOutputsKeepOrderInLiveSnapshotAndCacheWhileHookPayloadsStayHidden() {
        val native = raw("""{"id":"async","type":"functionCallOutput","name":"worker","output":[{"type":"input_text","text":"Finished"}]}""")
        val event = buildJsonObject { put("method", "item/completed"); put("params", buildJsonObject { put("turnId", "t"); put("item", native) }) }
        val live = NativeReducer.reduce(NativeTimeline(), event)
        val snapshot = NativeReducer.snapshot(buildJsonObject {
            put("turns", buildJsonArray {
                add(buildJsonObject {
                    put("id", "t")
                    put("status", "completed")
                    put("items", buildJsonArray { add(native) })
                })
            })
        })
        assertEquals(listOf("async"), live.items.values.map { it.itemId })
        assertEquals(live.items.keys, snapshot.items.keys)
        assertEquals("Finished", ConversationPresentation.detail(snapshot.items.values.single()))
        assertFalse(ConversationPresentation.visible(live.items.values.single().copy(raw = raw("""{"method":"item/completed","params":{"item":{"type":"hookPrompt"}}}"""))))
    }

    @Test fun completedTurnsNeverFoldPendingNativeQuestionsIntoTrace() {
        val first = TimelineItem("t", "one", "commandExecution", complete = true)
        val question = TimelineItem("t", "native-input", "agentMessage", "Choose", raw = raw("""{"phase":"commentary","questions":[{"id":"native-question","question":"Choose"}]}"""), complete = true)
        val second = TimelineItem("t", "two", "commandExecution", complete = true)
        val rows = ConversationPresentation.rows(NativeTimeline(listOf(first, question, second).associateBy { it.key }, completedTurns = setOf("t")))
        assertEquals(3, rows.size)
        assertEquals(TranscriptKind.Commentary, rows[1].kind)
        assertSame(question, rows[1].items.single())
        assertEquals(question.key, rows[1].key)
    }

    @Test fun toolsNeverGroupAcrossFailedWaitingQuestionOrApprovalItems() {
        val before = TimelineItem("t", "before", "commandExecution", complete = true)
        val after = before.copy(itemId = "after")
        listOf(
            before.copy(itemId = "boundary", status = "failed"),
            before.copy(itemId = "boundary", status = "waitingOnApproval"),
            before.copy(itemId = "boundary", raw = raw("""{"questions":[{"question":"Choose"}]}""")),
            before.copy(itemId = "boundary", raw = raw("""{"method":"item/commandExecution/requestApproval"}""")),
            before.copy(itemId = "boundary", type = "dynamicToolCall", raw = raw("""{"tool":"request_user_input","arguments":{"questions":[{"question":"Choose"}]}}""")),
        ).forEach { boundary ->
            val rows = ConversationPresentation.rows(NativeTimeline(listOf(before, boundary, after).associateBy { it.key }, completedTurns = setOf("t")))
            assertEquals(listOf(listOf(before), listOf(boundary), listOf(after)), rows.map { it.items })
            assertFalse(NativeActivityPresentation.foldedByDefault(rows[1]))
        }
    }

    @Test fun hiddenApprovalEventStillBreaksContiguity() {
        val first = TimelineItem("t", "first", "commandExecution", complete = true)
        val approval = TimelineItem("t", "approval", "item/commandExecution/requestApproval")
        val last = first.copy(itemId = "last")
        val rows = ConversationPresentation.rows(NativeTimeline(listOf(first, approval, last).associateBy { it.key }))
        assertEquals(listOf(listOf(first), listOf(last)), rows.map { it.items })
    }
}
