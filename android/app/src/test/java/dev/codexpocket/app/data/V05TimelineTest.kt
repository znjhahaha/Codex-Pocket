package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V05TimelineTest {
    private fun event(method: String, body: String) = buildJsonObject {
        put("method", method); put("params", pocketJson.parseToJsonElement(body))
    }
    @Test fun summaryIndicesRemainSeparateThroughInterleavedDeltas() {
        var state = NativeTimeline()
        listOf(0 to "First", 1 to "Second", 0 to " part").forEach { (index, text) ->
            state = NativeReducer.reduce(state, event("item/reasoning/summaryTextDelta", """{"turnId":"t","itemId":"r","summaryIndex":$index,"delta":"$text"}"""))
        }
        assertEquals("First part\n\nSecond", state.items.values.single().text)
        state = NativeReducer.reduce(state, event("item/completed", """{"turnId":"t","item":{"id":"r","type":"reasoning","summary":["Final one","Final two"]}}"""))
        assertEquals("Final one\n\nFinal two", state.items.values.single().text)
    }
    @Test fun completedTaskKeepsFirstQuestionAndLastAnswerWithOneProcessRow() {
        val timeline = NativeReducer.snapshot(pocketJson.parseToJsonElement("""{"turns":[{"id":"t","status":"completed","items":[
          {"id":"u","type":"userMessage","content":[{"type":"text","text":"Start"}]},
          {"id":"c","type":"agentMessage","phase":"commentary","text":"Checking"},
          {"id":"u2","type":"userMessage","content":[{"type":"text","text":"Also this"}]},
          {"id":"a","type":"agentMessage","phase":"final_answer","text":"Done"}]}]}""").jsonObject)
        val rows = ConversationPresentation.rows(timeline)
        assertEquals(listOf("User", "Process", "Answer"), rows.map { it.kind.name })
        assertEquals(listOf("c", "u2"), rows[1].items.map { it.itemId })
        assertEquals("Done", rows.last().items.single().text)
    }
    @Test fun failedTaskAndUnknownRunningHistoryNeverBecomeSuccessfulFold() {
        for (status in listOf("failed", "interrupted", "inProgress")) {
            val timeline = NativeReducer.snapshot(pocketJson.parseToJsonElement("""{"turns":[{"id":"t","status":"$status","items":[{"id":"c","type":"agentMessage","phase":"commentary","text":"Checking"},{"id":"a","type":"agentMessage","text":"Partial"}]}]}""").jsonObject)
            assertFalse(ConversationPresentation.rows(timeline).any { it.kind.name == "Process" })
        }
    }
    @Test fun nativeTaskChainPreservesEveryFinalPartAndCachedTiming() {
        val timeline = NativeReducer.snapshot(pocketJson.parseToJsonElement("""{"turns":[
          {"id":"t1","status":"completed","startedAt":100,"completedAt":102,"items":[{"id":"u","type":"userMessage","content":[{"type":"text","text":"task"}]},{"id":"c","type":"agentMessage","phase":"commentary","text":"working"}]},
          {"id":"t2","rootTurnId":"t1","status":"completed","durationMs":3000,"items":[{"id":"u2","type":"userMessage","content":[{"type":"text","text":"extra"}]},{"id":"a","type":"agentMessage","phase":"final_answer","text":"result one"},{"id":"b","type":"agentMessage","phase":"final_answer","text":"result two"}]}]}""").jsonObject)
        val rows = ConversationPresentation.rows(timeline)
        assertEquals(listOf("User", "Process", "Answer", "Answer"), rows.map { it.kind.name })
        assertTrue(rows[1].processDetail!!.contains("5 秒"))
        val key = ThreadKey("c", "s", "t")
        val cached = timeline.items.values.mapIndexed { index, item -> ItemEntity.from(key, item.withTurnCache(timeline.turns[item.turnId]), index.toLong()).item() }
        val restored = NativeTimeline(items = cached.associateBy { it.key }).withCachedTurns()
        assertEquals(rows, ConversationPresentation.rows(restored).map { row -> row.copy(items = row.items.map { it.copy(raw = JsonObject(it.raw - "_pocketTurn")) }) })
        assertFalse(ConversationPresentation.rows(timeline, setOf("t2")).any { it.kind == TranscriptKind.Process })
    }
    @Test fun unknownTimestampsDoNotInventDurationAndSeparateTasksStaySeparate() {
        val state = NativeReducer.snapshot(pocketJson.parseToJsonElement("""{"turns":[{"id":"a","status":"completed","items":[{"id":"c","type":"agentMessage","phase":"commentary","text":"work"},{"id":"f","type":"agentMessage","text":"done"}]},{"id":"b","status":"completed","items":[{"id":"u","type":"userMessage","content":[{"type":"text","text":"continue"}]},{"id":"f","type":"agentMessage","text":"next"}]}]}""").jsonObject)
        assertNull(state.turns["a"]!!.elapsedMs)
        val rows = ConversationPresentation.rows(state)
        assertEquals(TaskFoldReason.MissingInitialUser, rows.first().foldReason)
        assertNull(rows.first().processDetail)
        assertTrue(rows.any { it.kind == TranscriptKind.User && it.items.first().turnId == "b" })
    }
    @Test fun snapshotKeepsObservedClockAndConfirmedSteerWithoutReplacingNativeStatus() {
        val old = NativeTimeline(turns = mapOf("t" to NativeTurnInfo("t", status = "inProgress", observedStartNanos = 123L)))
            .withConfirmedSteer("root", "t")
        val refreshed = NativeTimeline(turns = mapOf("t" to NativeTurnInfo("t", status = "inProgress"))).withObservedTurns(old)
        assertEquals(123L, refreshed.turns.getValue("t").observedStartNanos)
        assertEquals("root", refreshed.turns.getValue("t").observedParentId)
        assertNull(NativeTurnInfo.from(refreshed.turns.getValue("t").json(), true).observedStartNanos)
        val finished = NativeTimeline(turns = mapOf("t" to NativeTurnInfo("t", status = "completed"))).withObservedTurns(old)
        assertNull(finished.turns.getValue("t").observedStartNanos)
        assertNull(finished.turns.getValue("t").elapsedMs)
    }
    @Test fun noncontiguousTaskRootsNeverDuplicateLazyKeysOrReorderMessages() {
        val turns = listOf("a" to null, "b" to null, "c" to "a")
        val timeline = NativeTimeline(items = turns.flatMap { (id, _) -> listOf(
            TimelineItem(id, "work", "agentMessage", "working $id", raw = buildJsonObject { put("phase", "commentary") }, complete = true),
            TimelineItem(id, "answer", "agentMessage", "answer $id", complete = true)) }.associateBy { it.key },
            turns = turns.associate { (id, root) -> id to NativeTurnInfo(id, rootId = root, status = "completed") })
        val rows = ConversationPresentation.rows(timeline)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals("working a", rows.first().items.first().text)
        assertFalse(rows.any { it.kind == TranscriptKind.Process && it.items.first().turnId in setOf("a", "c") })
    }

    private fun completed(vararg items: TimelineItem, turns: Map<String, NativeTurnInfo>? = null) = NativeTimeline(
        items = items.associateTo(linkedMapOf()) { it.key to it },
        turns = turns ?: items.map { it.turnId }.distinct().associateWith { NativeTurnInfo(it, status = "completed") },
        completedTurns = items.map { it.turnId }.toSet())

    private fun message(id: String, phase: String = "", turn: String = "t") = TimelineItem(
        turn, id, "agentMessage", id, complete = true, raw = buildJsonObject { if (phase.isNotBlank()) put("phase", phase) })
    private fun initial(turn: String = "t") = TimelineItem(turn, "initial", "userMessage", "Start", complete = true)

    @Test fun legacyProtocolKeepsOnlyLastOfThreeUnphasedRepliesOutsideProcess() {
        val rows = ConversationPresentation.rows(completed(initial(), message("one"), message("two"), message("three")))
        assertEquals(listOf("three"), rows.filter { it.kind == TranscriptKind.Answer }.flatMap { it.items }.map { it.itemId })
        assertEquals(listOf("one", "two"), rows.single { it.kind == TranscriptKind.Process }.items.map { it.itemId })
    }

    @Test fun finalLinkedTurnPreservesAllExplicitFinalFragmentsAndEarlierAnswersStayInProcess() {
        val state = completed(initial("root"), message("work", "commentary", "root"), message("one", "final_answer", "root"),
            message("two", "final_answer", "child"), message("three", turn = "child"), message("four", "final", "child"))
            .withConfirmedSteer("root", "child")
        val rows = ConversationPresentation.rows(state)
        assertEquals(listOf("two", "four"), rows.filter { it.kind == TranscriptKind.Answer }.flatMap { it.items }.map { it.itemId })
        assertEquals(listOf("work", "one", "three"), rows.single { it.kind == TranscriptKind.Process }.items.map { it.itemId })
    }

    @Test fun unresolvedDecisionsNeverBecomeHiddenProcessItems() {
        val work = TimelineItem("t", "tool", "commandExecution", complete = true)
        val boundaries = listOf(
            work.copy(status = "waitingOnApproval"),
            work.copy(raw = buildJsonObject { put("questions", buildJsonArray { add(buildJsonObject { put("question", "Choose") }) }) }),
            work.copy(type = "item/commandExecution/requestApproval"))
        boundaries.forEach { boundary ->
            val rows = ConversationPresentation.rows(completed(boundary, message("final")))
            assertFalse("Hidden boundary: $boundary", rows.any { it.kind == TranscriptKind.Process })
        }
    }

    @Test fun failedOrWaitingAncestorsWithoutItemsStillPreventFolding() {
        listOf("failed", "interrupted", "waitingOnUserInput", "inProgress").forEach { status ->
            val state = completed(message("work", "commentary", "child"), message("final", turn = "child"), turns = mapOf(
                "root" to NativeTurnInfo("root", status = status),
                "child" to NativeTurnInfo("child", rootId = "root", status = "completed")))
            assertFalse("Ancestor $status", ConversationPresentation.rows(state).any { it.kind == TranscriptKind.Process })
        }
    }

    @Test fun missingRootMetadataKeepsPaginatedChildUnfoldedUntilRootIsLoaded() {
        val child = NativeTurnInfo("child", rootId = "root", status = "completed")
        val state = completed(message("work", "commentary", "child"), message("final", turn = "child"), turns = mapOf("child" to child))
        assertFalse(ConversationPresentation.rows(state).any { it.kind == TranscriptKind.Process })
        val rootUser = initial("root")
        val loaded = state.copy(items = linkedMapOf(rootUser.key to rootUser).apply { putAll(state.items) },
            turns = state.turns + ("root" to NativeTurnInfo("root", status = "completed")))
        assertTrue(ConversationPresentation.rows(loaded).any { it.kind == TranscriptKind.Process })
    }

    @Test fun expandedProcessIncludesEveryReasoningSegmentExactlyOnce() {
        val reasoning = TimelineItem.from("t", pocketJson.parseToJsonElement("""{"id":"r","type":"reasoning","summary":["first","second"]}""").jsonObject)
        val state = completed(TimelineItem("t", "u", "userMessage", "start", complete = true), reasoning,
            message("work", "commentary"), message("final"))
        val folded = ConversationPresentation.rows(state)
        val expanded = expandTaskRows(folded, folded.filter { it.kind == TranscriptKind.Process }.map { it.key }.toSet())
        assertEquals(listOf("first", "second"), expanded.single { it.kind == TranscriptKind.Activity }.items.single().summaryParts)
        assertEquals(expanded.size, expanded.map { it.key }.toSet().size)
        assertEquals(listOf("u", "r", "work", "final"), expanded.filter { it.kind != TranscriptKind.Process }.flatMap { it.items }.map { it.itemId })
    }

    @Test fun rootChangesPaginationAndExpansionNeverProduceDuplicateKeys() {
        val a = listOf(message("work", "commentary", "a"), message("final", turn = "a"))
        val b = listOf(message("work", "commentary", "b"), message("final", turn = "b"))
        val c = listOf(message("work", "commentary", "c"), message("final", turn = "c"))
        val states = listOf(completed(*b.toTypedArray()), completed(*(a + b + c).toTypedArray()),
            completed(*(a + b + c).toTypedArray()).withConfirmedSteer("a", "c"),
            completed(*(a + b + c).toTypedArray()).withConfirmedSteer("a", "b").withConfirmedSteer("b", "c"))
        states.forEach { state ->
            val rows = ConversationPresentation.rows(state)
            val expanded = expandTaskRows(rows, rows.filter { it.kind == TranscriptKind.Process }.map { it.key }.toSet())
            assertEquals(rows.size, rows.map { it.key }.toSet().size)
            assertEquals(expanded.size, expanded.map { it.key }.toSet().size)
            assertEquals(state.items.keys, expanded.filter { it.kind != TranscriptKind.Process }.flatMap { it.items }.map { it.key }.toSet())
        }
    }

    @Test fun summaryDeltasInterleaveByTurnItemAndIndexAndCompletionRejectsLateEvents() {
        var state = NativeTimeline()
        listOf(Triple("a", "r", 2), Triple("b", "r", 0), Triple("a", "other", 0), Triple("a", "r", 0), Triple("a", "r", 2))
            .forEachIndexed { number, (turn, item, index) ->
                state = NativeReducer.reduce(state, event("item/reasoning/summaryTextDelta", """{"turnId":"$turn","itemId":"$item","summaryIndex":$index,"delta":"$number"}"""))
            }
        assertEquals(listOf("3", "", "04"), state.items.getValue(TimelineItem("a", "r", "reasoning").key).summaryParts)
        assertEquals("1", state.items.getValue(TimelineItem("b", "r", "reasoning").key).text)
        assertEquals("2", state.items.getValue(TimelineItem("a", "other", "reasoning").key).text)
        state = NativeReducer.reduce(state, event("item/completed", """{"turnId":"a","item":{"id":"r","type":"reasoning","summary":[{"text":"authoritative"}]}}"""))
        val done = state
        state = NativeReducer.reduce(state, event("item/started", """{"turnId":"a","item":{"id":"r","type":"reasoning","summary":[]}}"""))
        state = NativeReducer.reduce(state, event("item/reasoning/summaryTextDelta", """{"turnId":"a","itemId":"r","summaryIndex":0,"delta":"stale"}"""))
        assertEquals(done, state)
    }

    @Test fun lateItemStartAddsMetadataWithoutDroppingObservedSummaryParts() {
        var state = NativeReducer.reduce(NativeTimeline(), event("item/reasoning/summaryTextDelta", """{"turnId":"t","itemId":"r","summaryIndex":1,"delta":"observed"}"""))
        state = NativeReducer.reduce(state, event("item/started", """{"turnId":"t","item":{"id":"r","type":"reasoning","summary":[],"status":"inProgress"}}"""))
        assertEquals(listOf("", "observed"), state.items.values.single().summaryParts)
        assertEquals("inProgress", state.items.values.single().status)
    }

    @Test fun repeatedTurnStartPreservesNativeRootAndStartTimestamp() {
        val old = NativeTimeline(turns = mapOf("t" to NativeTurnInfo("t", rootId = "root", status = "inProgress", startedAtMs = 1000, observedStartNanos = 10)))
        val state = NativeReducer.reduce(old, event("turn/started", """{"turn":{"id":"t"}}"""))
        assertEquals("root", state.turns.getValue("t").rootId)
        assertEquals(1000L, state.turns.getValue("t").startedAtMs)
        assertEquals(10L, state.turns.getValue("t").observedStartNanos)
    }

    @Test fun snapshotRunningTurnCannotAcquireAClockFromARepeatedStart() {
        val old = NativeTimeline(turns = mapOf("t" to NativeTurnInfo("t", status = "inProgress")), activeTurn = "t")
        val started = NativeReducer.reduce(old, event("turn/started", """{"turn":{"id":"t"}}"""))
        assertNull(started.turns.getValue("t").observedStartNanos)
        val completed = NativeReducer.reduce(started, event("turn/completed", """{"turn":{"id":"t","status":"completed"}}"""))
        assertNull(completed.turns.getValue("t").elapsedMs)
    }

    @Test fun duplicateCompletionPreservesObservedDurationAndLateStartCannotReopenTurn() {
        val old = NativeTimeline(turns = mapOf("t" to NativeTurnInfo("t", status = "inProgress", observedStartNanos = System.nanoTime() - 5_000_000)))
        val done = NativeReducer.reduce(old, event("turn/completed", """{"turn":{"id":"t","status":"completed"}}"""))
        assertNotNull(done.turns.getValue("t").elapsedMs)
        assertTrue(done.turns.getValue("t").observed)
        val repeated = NativeReducer.reduce(done, event("turn/completed", """{"turn":{"id":"t","status":"completed"}}"""))
        assertEquals(done.turns.getValue("t").elapsedMs, repeated.turns.getValue("t").elapsedMs)
        val lateStart = NativeReducer.reduce(repeated, event("turn/started", """{"turn":{"id":"t"}}"""))
        assertEquals(repeated, lateStart)
    }

    @Test fun nativeClockWinsOverObservationAndInvalidNativeIntervalsRemainUnknown() {
        assertEquals(2000L, NativeTurnInfo("t", startedAtMs = 1000, completedAtMs = 3000, observedDurationMs = 99).elapsedMs)
        val invalid = NativeTurnInfo("t", startedAtMs = 3000, completedAtMs = 1000, observedDurationMs = 99)
        assertEquals(99L, invalid.elapsedMs)
        assertTrue(invalid.observed)
        assertNull(NativeTurnInfo("t", durationMs = -1).elapsedMs)
    }

    @Test fun duplicateCompletionWithoutStatusKeepsFullyObservedClock() {
        val old = NativeTimeline(turns = mapOf("t" to NativeTurnInfo("t", status = "completed", observedDurationMs = 42)))
        val refreshed = NativeReducer.reduce(old, event("turn/completed", """{"turn":{"id":"t"}}"""))
        assertEquals(42L, refreshed.turns.getValue("t").elapsedMs)
    }

    @Test fun completedParentCanFoldHistoricalFailureButNeverPendingChildDecision() {
        listOf("failed", "waitingOnUserInput", "shutdown").forEach { status ->
            val tool = TimelineItem("t", "agents", "collabAgentToolCall", complete = true, raw = buildJsonObject {
                put("status", "completed"); put("agentsStates", buildJsonObject {
                    put("child", buildJsonObject { put("status", status); put("message", "needs attention") })
                })
            })
            val rows = ConversationPresentation.rows(completed(initial(), tool, message("final")))
            assertEquals("Child $status", status != "waitingOnUserInput", rows.any { it.kind == TranscriptKind.Process })
        }
    }

    @Test fun successfulTurnFinalClosesStaleProcessPresentation() {
        val tool = TimelineItem("t", "tool", "commandExecution", status = "inProgress", liveStarted = true)
        assertTrue(ConversationPresentation.rows(completed(initial(), tool, message("final"))).any { it.kind == TranscriptKind.Process })
    }

    @Test fun invalidSummaryIndexNeverAppendsToAnUnrelatedSegment() {
        val old = NativeReducer.reduce(NativeTimeline(), event("item/reasoning/summaryTextDelta", """{"turnId":"t","itemId":"r","summaryIndex":0,"delta":"original"}"""))
        listOf("-1", "4096", "\"invalid\"", "null").forEach { index ->
            assertEquals(old, NativeReducer.reduce(old, event("item/reasoning/summaryTextDelta", """{"turnId":"t","itemId":"r","summaryIndex":$index,"delta":"wrong"}""")))
        }
    }

    @Test fun partAddedPreservesOtherSegmentsAndEmptyFinalReplacesAllDraftText() {
        var state = NativeReducer.reduce(NativeTimeline(), event("item/reasoning/summaryTextDelta", """{"turnId":"t","itemId":"r","summaryIndex":0,"delta":"draft"}"""))
        state = NativeReducer.reduce(state, event("item/reasoning/summaryPartAdded", """{"turnId":"t","itemId":"r","summaryIndex":2}"""))
        assertEquals(listOf("draft", "", ""), state.items.values.single().summaryParts)
        state = NativeReducer.reduce(state, event("item/completed", """{"turnId":"t","item":{"id":"r","type":"reasoning","summary":[]}}"""))
        assertEquals("", state.items.values.single().text)
        assertTrue(state.items.values.single().summaryParts.isEmpty())
    }

    @Test fun nativeRootTakesPrecedenceOverConfirmedSteerAndCyclesDoNotMergeTurns() {
        val state = completed(initial("a"), message("work", "commentary", "a"), message("final", turn = "a"),
            initial("b"), message("work", "commentary", "b"), message("final", turn = "b"))
        val native = state.copy(turns = state.turns + ("b" to state.turns.getValue("b").copy(rootId = "b")))
            .withConfirmedSteer("a", "b")
        assertEquals(2, ConversationPresentation.rows(native).count { it.kind == TranscriptKind.Process })
        val cyclic = state.withConfirmedSteer("a", "b").withConfirmedSteer("b", "a")
        val rows = ConversationPresentation.rows(cyclic)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertTrue(rows.filter { it.kind == TranscriptKind.Process }.all { row -> row.items.map { it.turnId }.distinct().size == 1 })
    }

    @Test fun nativeStartIsRetainedThroughCompletionAndObservedSnapshotRefresh() {
        val started = NativeReducer.reduce(NativeTimeline(), event("turn/started", """{"turn":{"id":"t","startedAt":10}}"""))
        assertNotNull(started.turns.getValue("t").observedStartNanos)
        val completed = NativeReducer.reduce(started, event("turn/completed", """{"turn":{"id":"t","status":"completed","completedAt":12}}"""))
        assertEquals(2000L, completed.turns.getValue("t").elapsedMs)
        assertFalse(completed.turns.getValue("t").observed)
        val snapshot = NativeReducer.snapshot(pocketJson.parseToJsonElement("""{"turns":[{"id":"t","status":"completed","startedAt":10,"completedAt":12,"items":[]}]}""").jsonObject)
            .withObservedTurns(completed)
        assertEquals(2000L, snapshot.turns.getValue("t").elapsedMs)
        assertNull(snapshot.turns.getValue("t").observedStartNanos)
    }

    @Test fun overflowingTaskDurationsNeverDisplayAWrappedClock() {
        val state = completed(initial("root"), message("work", "commentary", "root"), message("final", turn = "child"), turns = mapOf(
            "root" to NativeTurnInfo("root", status = "completed", durationMs = Long.MAX_VALUE),
            "child" to NativeTurnInfo("child", rootId = "root", status = "completed", durationMs = 1)))
        assertEquals("上 1 条消息", ConversationPresentation.rows(state).single { it.kind == TranscriptKind.Process }.processLabel)
    }

    @Test fun resolvedAsyncQuestionBelongsToProcessWhilePendingQuestionStaysUnfolded() {
        val initial = TimelineItem("t", "u", "userMessage", "start", complete = true)
        val question = TimelineItem("t", "q", "agentMessage", "Choose", complete = true, raw = buildJsonObject {
            put("delivery", "async"); put("questions", buildJsonArray { add(buildJsonObject { put("title", "Choose") }) })
        })
        val waiting = completed(initial, question, message("final"))
        assertFalse(ConversationPresentation.rows(waiting).any { it.kind == TranscriptKind.Process })
        val reply = TimelineItem("t", "reply", "userMessage", AsyncQuestions.reply(AsyncQuestions.pending(waiting).single(), "option"), complete = true)
        val resolved = completed(initial, question, reply, message("final"))
        val rows = ConversationPresentation.rows(resolved)
        assertEquals(listOf("User", "Process", "Answer"), rows.map { it.kind.name })
        assertEquals(listOf("q", "reply"), rows.single { it.kind == TranscriptKind.Process }.items.map { it.itemId })
        assertEquals(listOf("final"), rows.filter { it.kind == TranscriptKind.Answer }.flatMap { it.items }.map { it.itemId })
    }

    @Test fun reloadKeepsKnownNativeRootAndClockButProvidedSnapshotValuesWin() {
        val old = NativeTimeline(turns = mapOf("t" to NativeTurnInfo("t", rootId = "root", status = "inProgress", startedAtMs = 1000, observedStartNanos = 123)))
        val reloaded = NativeTimeline(turns = mapOf("t" to NativeTurnInfo("t", status = "inProgress"))).withObservedTurns(old)
        assertEquals("root", reloaded.turns.getValue("t").rootId)
        assertEquals(1000L, reloaded.turns.getValue("t").startedAtMs)
        assertEquals(123L, reloaded.turns.getValue("t").observedStartNanos)
        val native = NativeTurnInfo("t", rootId = "new-root", status = "completed", startedAtMs = 2000, completedAtMs = 2500, durationMs = 500)
        val completed = NativeTimeline(turns = mapOf("t" to native)).withObservedTurns(reloaded)
        assertEquals(native, completed.turns.getValue("t"))
        assertNull(completed.turns.getValue("t").observedDurationMs)
    }

    @Test fun completedReloadRetainsOptionalNativeTimingOnlyForTheSameIdentity() {
        val known = NativeTurnInfo("t", rootId = "root", status = "completed", startedAtMs = 1000, completedAtMs = 3000, durationMs = 2000)
        val previous = NativeTimeline(turns = mapOf("t" to known))
        val reloaded = NativeTimeline(turns = mapOf("t" to NativeTurnInfo("t", status = "completed"))).withObservedTurns(previous)
        assertEquals(known, reloaded.turns.getValue("t"))
        val other = NativeTurnInfo("other", status = "inProgress")
        assertEquals(other, NativeTimeline(turns = mapOf("other" to other)).withObservedTurns(previous).turns.getValue("other"))
    }

    private fun collab(id: String, tool: String, childStatus: String) = TimelineItem("t", id, "collabAgentToolCall", status = "completed", complete = true,
        raw = buildJsonObject { put("tool", tool); put("agentsStates", buildJsonObject { put("child", buildJsonObject { put("status", childStatus) }) }) })

    @Test fun latestCompletedChildAllowsFoldingAfterHistoricalRunningSpawn() {
        val spawn = collab("spawn", "spawnAgent", "running")
        val wait = collab("wait", "wait", "completed")
        val rows = ConversationPresentation.rows(completed(initial(), spawn, wait, message("final")))
        assertEquals(listOf("spawn", "wait"), rows.single { it.kind == TranscriptKind.Process }.items.map { it.itemId })
        assertEquals("final", rows.last().items.single().itemId)
    }

    @Test fun successfulCloseAfterCompletedChildDoesNotTurnParentTaskIntoInterruption() {
        val done = collab("wait", "wait", "completed")
        val close = collab("close", "closeAgent", "shutdown")
        assertTrue(ConversationPresentation.rows(completed(initial(), done, close, message("final"))).any { it.kind == TranscriptKind.Process })
        val historicalClose = ConversationPresentation.rows(completed(initial(), close, message("final")))
        assertTrue(historicalClose.any { it.kind == TranscriptKind.Process })
    }
}
