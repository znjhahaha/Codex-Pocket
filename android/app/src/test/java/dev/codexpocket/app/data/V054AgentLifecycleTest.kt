package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V054AgentLifecycleTest {
    private fun json(value: String) = pocketJson.parseToJsonElement(value).jsonObject
    private fun parent(vararg agents: Pair<String, String>): NativeTimeline {
        val raw = buildJsonObject {
            put("id", "spawn"); put("type", "collabAgentToolCall"); put("status", "completed")
            put("agentsStates", buildJsonObject { agents.forEach { (id, status) ->
                put(id, buildJsonObject { put("status", status); put("name", id) }) } })
        }
        val item = TimelineItem.from("parent-turn", raw)
        return NativeTimeline(items = linkedMapOf(item.key to item), activeTurn = "parent-turn")
    }

    @Test fun initializationRunningErrorAndShutdownHaveDistinctTruthfulCounts() {
        val roster = agentRoster(parent("Lagrange" to "pending_init", "Feynman" to "running", "Pauli" to "running",
            "errored" to "errored", "closed" to "shutdown", "waiting" to "waitingOnApproval"))
        val work = currentAgentWork(roster)
        assertEquals(listOf("Feynman", "Pauli"), work.running.map { it.threadId })
        assertEquals(listOf("Lagrange"), work.starting.map { it.threadId })
        assertEquals(listOf("waiting"), work.waiting.map { it.threadId })
        assertEquals(listOf("errored"), work.errors.map { it.threadId })
        assertEquals(AgentWorkPhase.Stopped, roster.last { it.threadId == "closed" }.workPhase)
        assertEquals(ActivityStatus.Waiting, AgentLiveStatus("pending_init").status)
    }

    @Test fun authoritativeNativeErrorBeatsOldStartedAndCompletedPointers() {
        val state = parent("Lagrange" to "running")
        val oldStarted = TimelineItem.from("parent-turn", json(
            """{"id":"old-start","type":"subAgentActivity","agentThreadId":"Lagrange","kind":"started"}"""))
        val withOldStart = state.copy(items = state.items + (oldStarted.key to oldStarted))
        val error = AgentLiveStatus.fromTimeline(NativeTimeline(activeTurn = "old-child-turn", status = "systemError",
            turns = mapOf("old-child-turn" to NativeTurnInfo("old-child-turn", status = "completed"))), Cursor("e", 20))!!
        assertEquals(ActivityStatus.Failed, error.status)
        val roster = agentRoster(withOldStart, mapOf("Lagrange" to error))
        assertEquals(AgentWorkPhase.Error, roster.single().workPhase)
        assertTrue(currentAgentWork(roster).running.isEmpty())
    }

    @Test fun sameTurnOrUnversionedLateStartsCannotEraseATerminalError() {
        val failed = AgentLiveStatus("systemError", "child-turn", Cursor("e", 20))
        for (late in listOf(AgentLiveStatus("inProgress", "child-turn", Cursor("e", 21)),
            AgentLiveStatus("active", "new-turn", Cursor("e", 19)), AgentLiveStatus("active", "new-turn"),
            AgentLiveStatus("active", "new-turn", Cursor("old-epoch", 99)))) {
            assertSame(failed, mergeAgentLiveStatus(failed, late))
        }
    }

    @Test fun freshIdentifiedRestartAfterErrorCountsAsRunningDespiteOldParentError() {
        val failed = AgentLiveStatus("systemError", "old-turn", Cursor("e", 20))
        val resumed = mergeAgentLiveStatus(failed, AgentLiveStatus("inProgress", "new-turn", Cursor("e", 21)))
        assertTrue(resumed.confirmedRestart)
        val roster = agentRoster(parent("Lagrange" to "errored", "Feynman" to "running", "Pauli" to "running"),
            mapOf("Lagrange" to resumed))
        assertEquals(listOf("Lagrange", "Feynman", "Pauli"), currentAgentWork(roster).running.map { it.threadId })
        val waiting = mergeAgentLiveStatus(resumed, AgentLiveStatus("active", "new-turn", Cursor("e", 22), setOf("waitingOnApproval")))
        assertTrue(waiting.confirmedRestart)
        assertEquals(AgentWorkPhase.Waiting, agentRoster(parent("Lagrange" to "errored"), mapOf("Lagrange" to waiting)).single().workPhase)
    }

    @Test fun acceptedRunningLiveSnapshotOverridesOldParentTerminalButUnversionedDoesNot() {
        val state = parent("Lagrange" to "errored")
        assertEquals(1, currentAgentWork(agentRoster(state, mapOf("Lagrange" to AgentLiveStatus("active", cursor = Cursor("e", 30))))).running.size)
        assertTrue(currentAgentWork(agentRoster(state, mapOf("Lagrange" to AgentLiveStatus("active")))).running.isEmpty())
    }

    @Test fun completedOldTurnThenResumeAndActiveStartsBeforeNewTurnIdAndRejectsLateOldCompletion() {
        val old = AgentLiveStatus("completed", "old", Cursor("e", 10))
        val resumed = mergeAgentLiveStatus(old, AgentLiveStatus("running", cursor = Cursor("e", 11), resumeKey = "native-resume"))
        assertEquals(1L, resumed.executionGeneration)
        assertNull(resumed.turnId)
        val active = mergeAgentLiveStatus(resumed, AgentLiveStatus("active", cursor = Cursor("e", 12)))
        assertEquals(1L, active.executionGeneration)
        assertSame(active, mergeAgentLiveStatus(active, AgentLiveStatus("completed", "old", Cursor("e", 13))))
        val identified = mergeAgentLiveStatus(active, AgentLiveStatus("inProgress", "new", Cursor("e", 14)))
        assertEquals("new", identified.turnId)
        assertEquals(1L, identified.executionGeneration)
        assertSame(identified, mergeAgentLiveStatus(identified, AgentLiveStatus("completed", "old", Cursor("e", 15))))
        assertEquals(1, currentAgentWork(agentRoster(parent("Lagrange" to "completed"), mapOf("Lagrange" to identified))).running.size)
    }

    @Test fun resumeAndActiveArrivalOrdersProduceOneGenerationAndRepeatedResumeDoesNotRestartIt() {
        val old = AgentLiveStatus("completed", "old", Cursor("e", 10))
        val resume = AgentLiveStatus("running", cursor = Cursor("e", 11), resumeKey = "resume")
        val active = AgentLiveStatus("active", cursor = Cursor("e", 12))
        val resumeFirst = mergeAgentLiveStatus(mergeAgentLiveStatus(old, resume), active)
        val activeFirst = mergeAgentLiveStatus(mergeAgentLiveStatus(old, active), resume)
        for (state in listOf(resumeFirst, activeFirst)) {
            assertEquals(1L, state.executionGeneration)
            assertEquals(ActivityStatus.Running, state.status)
            assertTrue("old" in state.retiredTurnIds)
            val identified = mergeAgentLiveStatus(state, AgentLiveStatus("inProgress", "new", Cursor("e", 13)))
            val done = mergeAgentLiveStatus(identified, AgentLiveStatus("completed", "new", Cursor("e", 14)))
            assertSame(done, mergeAgentLiveStatus(done, resume.copy(cursor = Cursor("e", 15))))
        }
    }

    @Test fun authoritativeHigherCursorActiveIsResumeEvidenceWithoutANewTurnId() {
        val old = AgentLiveStatus("completed", "old", Cursor("e", 10))
        val active = mergeAgentLiveStatus(old, AgentLiveStatus("active", cursor = Cursor("e", 11)))
        assertEquals(ActivityStatus.Running, active.status)
        assertEquals(1L, active.executionGeneration)
        assertNull(active.turnId)
        assertSame(active, mergeAgentLiveStatus(active, AgentLiveStatus("completed", "old", Cursor("e", 12))))
        assertSame(old, mergeAgentLiveStatus(old, AgentLiveStatus("active", cursor = Cursor("e", 9))))
        val historical = parent("Lagrange" to "completed").copy(activeTurn = "another-parent-turn")
        assertEquals(listOf("Lagrange"), currentAgentWork(agentRoster(historical, mapOf("Lagrange" to active))).running.map { it.threadId })
    }

    @Test fun idleBeforeOldCompletionStillStartsASeparateResumedExecution() {
        for (metadata in listOf("idle", "notLoaded")) {
            val old = mergeAgentLiveStatus(null, AgentLiveStatus("inProgress", "old", Cursor("e", 10)))
            val idle = mergeAgentLiveStatus(old, AgentLiveStatus(metadata, cursor = Cursor("e", 11)))
            assertEquals("old", idle.turnId)
            for (restart in listOf(
                AgentLiveStatus("running", cursor = Cursor("e", 12), resumeKey = "resume"),
                AgentLiveStatus("active", cursor = Cursor("e", 12)),
            )) {
                val resumed = mergeAgentLiveStatus(idle, restart)
                assertEquals(old.executionGeneration + 1, resumed.executionGeneration)
                assertNull(resumed.turnId)
                assertTrue("old" in resumed.retiredTurnIds)
                assertSame(resumed, mergeAgentLiveStatus(resumed, AgentLiveStatus("completed", "old", Cursor("e", 13))))
                val historical = parent("Lagrange" to "completed").copy(activeTurn = "new-parent-turn")
                assertEquals(listOf("Lagrange"), currentAgentWork(agentRoster(historical, mapOf("Lagrange" to resumed))).running.map { it.threadId })
                val identified = mergeAgentLiveStatus(resumed, AgentLiveStatus("inProgress", "new", Cursor("e", 14)))
                assertEquals(resumed.executionGeneration, identified.executionGeneration)
                assertEquals(ActivityStatus.Completed,
                    mergeAgentLiveStatus(identified, AgentLiveStatus("completed", "new", Cursor("e", 15))).status)
            }
        }
    }

    @Test fun reconnectActiveSnapshotRetiresLoadedOldTerminalTurnsAndCountsResumedIdentity() {
        val snapshot = NativeTimeline(status = "active", activeTurn = "old", turns = mapOf("old" to NativeTurnInfo("old", status = "completed")))
        val live = mergeAgentLiveStatus(null, AgentLiveStatus.fromTimeline(snapshot, Cursor("reconnected", 1))!!)
        assertEquals(ActivityStatus.Running, live.status)
        assertNull(live.turnId)
        assertTrue("old" in live.retiredTurnIds)
        assertSame(live, mergeAgentLiveStatus(live, AgentLiveStatus("completed", "old", Cursor("reconnected", 2))))
        assertEquals(listOf("Lagrange"), currentAgentWork(agentRoster(parent("Lagrange" to "completed"), mapOf("Lagrange" to live))).running.map { it.threadId })
    }

    @Test fun resumeAndSendInputUseNativeIdentityAndKeepInteractionCurrentAcrossLateOldRecords() {
        for (tool in listOf("resumeAgent", "sendInput")) {
            val resumed = TimelineItem.from("new-task", json("""{"id":"resume","type":"collabAgentToolCall","tool":"$tool","status":"completed","agentsStates":{"Lagrange":{"status":"running"}}}"""))
            val lateOld = TimelineItem.from("old-task", json("""{"id":"late","type":"subAgentActivity","agentThreadId":"Lagrange","kind":"completed"}"""))
            val state = NativeTimeline(items = linkedMapOf(resumed.key to resumed, lateOld.key to lateOld), activeTurn = "new-task")
            val evidence = agentResumeEvidence(resumed, Cursor("e", 11))
            val accepted = mergeAgentLiveStatus(AgentLiveStatus("completed", "old", Cursor("e", 10)), evidence.getValue("Lagrange"))
            val roster = agentRoster(state, mapOf("Lagrange" to accepted))
            assertTrue(roster.single().isCurrent)
            assertEquals(listOf("Lagrange"), currentAgentWork(roster).running.map { it.threadId })
            assertTrue(agentResumeEvidence(resumed.copy(complete = false), Cursor("e", 11)).isEmpty())
        }
    }

    @Test fun laterNativeResumeDoesNotReopenTheHistoricalSuccessfulTaskProcess() {
        val old = parent("Lagrange" to "completed")
        val user = TimelineItem("parent-turn", "u", "userMessage", "Old task", complete = true)
        val answer = TimelineItem("parent-turn", "f", "agentMessage", "Done", raw = json("""{"phase":"final_answer"}"""), complete = true)
        val resumed = TimelineItem.from("new-task", json("""{"id":"resume","type":"collabAgentToolCall","tool":"resumeAgent","status":"completed","agentsStates":{"Lagrange":{"status":"running"}}}"""))
        val state = old.copy(activeTurn = "new-task", items = linkedMapOf(user.key to user) + old.items +
            mapOf(answer.key to answer, resumed.key to resumed), turns = mapOf("parent-turn" to NativeTurnInfo("parent-turn", status = "completed"),
                "new-task" to NativeTurnInfo("new-task", status = "inProgress")))
        val live = mergeAgentLiveStatus(AgentLiveStatus("completed", "old", Cursor("e", 10)), agentResumeEvidence(resumed, Cursor("e", 11)).getValue("Lagrange"))
        assertTrue(ConversationPresentation.rows(state, agentLiveStatuses = mapOf("Lagrange" to live)).any { it.key == "process:parent-turn" })
        assertEquals(1, currentAgentWork(agentRoster(state, mapOf("Lagrange" to live))).running.size)
    }

    @Test fun workingSetHasNoTimeoutAndDoesNotCountUnknownOrHistoricalChildren() {
        val live = AgentLiveStatus("active", "child-turn", Cursor("e", 1))
        val state = parent("Lagrange" to "running", "Unknown" to "futureState")
        val roster = agentRoster(state, mapOf("Lagrange" to live))
        assertEquals(listOf("Lagrange"), currentAgentWork(roster).running.map { it.threadId })
        assertTrue(currentAgentWork(agentRoster(state.copy(activeTurn = "unrelated"))).running.isEmpty())
    }

    @Test fun newerThreadMetadataRetainsTheConfirmedResumedTurnIdentity() {
        val failed = AgentLiveStatus("systemError", "old", Cursor("e", 10))
        val restarted = mergeAgentLiveStatus(failed, AgentLiveStatus("inProgress", "new", Cursor("e", 11)))
        val metadata = mergeAgentLiveStatus(restarted, AgentLiveStatus("active", cursor = Cursor("e", 12)))
        assertEquals("new", metadata.turnId)
        assertTrue(metadata.confirmedRestart)
        assertEquals(1, currentAgentWork(agentRoster(parent("Lagrange" to "errored"), mapOf("Lagrange" to metadata))).running.size)
        val idle = mergeAgentLiveStatus(metadata, AgentLiveStatus("idle", cursor = Cursor("e", 13)))
        assertTrue(idle.confirmedRestart)
        val work = currentAgentWork(agentRoster(parent("Lagrange" to "errored"), mapOf("Lagrange" to idle)))
        assertTrue(work.running.isEmpty()); assertTrue(work.errors.isEmpty())
    }

    @Test fun labelsAndGlyphsShareStableNativeOrderAcrossRemovalAndNameChanges() {
        val first = agentRoster(parent("Lagrange" to "running", "Feynman" to "running", "Pauli" to "running"))
        val glyphs = first.associate { it.threadId to agentAvatarGlyph(it.threadId) }
        val after = first.drop(1).map { it.copy(name = "renamed") }
        assertEquals(listOf("Feynman", "Pauli"), currentAgentWork(after).running.map { it.threadId })
        assertTrue(after.all { agentAvatarGlyph(it.threadId) == glyphs[it.threadId] })
        assertEquals(1, currentAgentWork(after + after).running.count { it.threadId == "Feynman" })
    }

    @Test fun duplicateDisplayNamesStillCountTwoDistinctNativeThreads() {
        val roster = agentRoster(parent("native-a" to "running", "native-b" to "running"))
            .map { it.copy(name = "Lagrange") }
        assertEquals(listOf("native-a", "native-b"), currentAgentWork(roster).running.map { it.threadId })
    }

    @Test fun foldUsesAuthoritativeLiveStatesInsteadOfHistoricalSpawnCounts() {
        val source = parent("Lagrange" to "running")
        val user = TimelineItem("parent-turn", "u", "userMessage", "Start", complete = true)
        val answer = TimelineItem("parent-turn", "f", "agentMessage", "Done", raw = json("""{"phase":"final_answer"}"""), complete = true)
        val completed = source.copy(activeTurn = null, items = linkedMapOf(user.key to user) + source.items + (answer.key to answer),
            turns = mapOf("parent-turn" to NativeTurnInfo("parent-turn", status = "completed")))
        assertTrue(ConversationPresentation.rows(completed).any { it.kind == TranscriptKind.Process })
        val running = mapOf("Lagrange" to AgentLiveStatus("active", "child-turn", Cursor("e", 1)))
        assertEquals(TaskFoldReason.ActiveAgent, ConversationPresentation.rows(completed, agentLiveStatuses = running).first().foldReason)
        val failed = mapOf("Lagrange" to AgentLiveStatus("systemError", "child-turn", Cursor("e", 2)))
        val fold = ConversationPresentation.rows(completed, agentLiveStatuses = failed).single { it.kind == TranscriptKind.Process }
        assertTrue(fold.processDetail!!.contains("1 个子代理失败"))
    }
}
