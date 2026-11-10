package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentRosterTest {
    private fun item(turn: String, json: String) = TimelineItem.from(turn, pocketJson.parseToJsonElement(json).jsonObject)
    private fun timeline(vararg items: TimelineItem, active: String? = null) = NativeTimeline(
        items = items.associateByTo(linkedMapOf()) { it.key }, activeTurn = active
    )

    @Test fun aggregatesByOriginalNativeIdentityAndPreservesIndependentStates() {
        val first = item("one", """{"id":"spawn","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","receiverThreadIds":["a","b","a"],"prompt":"Review the changes","agentsStates":{"a":{"status":"running"},"b":{"status":"errored","message":"Permission denied"}}}""")
        val named = item("one", """{"id":"named","type":"subAgentActivity","agentThreadId":"a","agentPath":"root/reviewer","kind":"started"}""")
        val roster = agentRoster(timeline(first, named, active = "one"))
        assertEquals(listOf("a", "b"), roster.map { it.threadId })
        assertEquals("root/reviewer", roster[0].name)
        assertEquals(ActivityStatus.Running, roster[0].status)
        assertEquals(ActivityStatus.Failed, roster[1].status)
        assertEquals("Review the changes", roster[0].taskPreview)
        assertEquals("Permission denied", roster[1].resultPreview)
        assertEquals(named, roster[0].item)
        assertTrue(roster.all { it.isCurrent })
    }

    @Test fun lastExplicitStateWinsWhileMissingFieldsRetainKnownValues() {
        val named = item("one", """{"id":"name","type":"subAgentActivity","agentThreadId":"a","agentPath":"root/reviewer","kind":"started"}""")
        val completed = item("one", """{"id":"done","type":"collabAgentToolCall","receiverThreadIds":["a"],"agentsStates":{"a":{"status":"completed","message":"Three issues fixed"}}}""")
        val missing = item("two", """{"id":"wait","type":"collabAgentToolCall","status":"inProgress","receiverThreadIds":["a"],"agentsStates":{"a":{"status":null,"message":null,"name":""}},"prompt":null}""")
        val interacted = item("two", """{"id":"touch","type":"subAgentActivity","agentThreadId":"a","agentPath":"","kind":"interacted"}""")
        val agent = agentRoster(timeline(named, completed, missing, interacted, active = "two")).single()
        assertEquals(ActivityStatus.Completed, agent.status)
        assertEquals("completed", agent.nativeStatus)
        assertEquals("root/reviewer", agent.name)
        assertEquals("Three issues fixed", agent.resultPreview)
        assertTrue(agent.isCurrent)
        val restarted = item("two", """{"id":"restart","type":"subAgentActivity","agentThreadId":"a","agentPath":"root/reviewer","kind":"started"}""")
        assertEquals(ActivityStatus.Running, agentRoster(timeline(named, completed, missing, interacted, restarted)).single().status)
    }

    @Test fun futureExplicitStateReplacesOldStatusWithoutInventingAState() {
        val start = item("one", """{"id":"start","type":"subAgentActivity","agentThreadId":"a","agentPath":"root/a","kind":"started"}""")
        val future = item("one", """{"id":"future","type":"collabAgentToolCall","receiverThreadIds":["a"],"agentsStates":{"a":{"status":"futureState"}}}""")
        val agent = agentRoster(timeline(start, future)).single()
        assertEquals(ActivityStatus.Unknown, agent.status)
        assertEquals("futureState", agent.nativeStatus)
    }

    @Test fun parentActivityAndCompletionNeverBecomeChildActivity() {
        val call = item("one", """{"id":"call","type":"collabAgentToolCall","status":"inProgress","receiverThreadIds":["child"],"agentsStates":{}}""").copy(liveStarted = true)
        assertEquals(ActivityStatus.Unknown, agentRoster(timeline(call, active = "one")).single().status)
        assertEquals(ActivityStatus.Unknown, agentRoster(timeline(call).copy(status = "completed", completedTurns = setOf("one"))).single().status)
    }

    @Test fun foldingAndSnapshotRecreationKeepLatestAndHistoricalChildren() {
        val native = pocketJson.parseToJsonElement("""{"id":"parent","status":{"type":"idle"},"turns":[
            {"id":"old","status":"completed","items":[{"id":"old-call","type":"collabAgentToolCall","tool":"wait","status":"completed","receiverThreadIds":["old-child"],"agentsStates":{"old-child":{"status":"completed","message":"Historical result"}}}]},
            {"id":"latest","status":"completed","items":[{"id":"new-call","type":"subAgentActivity","agentThreadId":"new-child","agentPath":"root/new","kind":"completed"}]}
        ]}""").jsonObject
        val snapshot = NativeReducer.snapshot(native)
        val first = agentRoster(snapshot)
        val reopened = agentRoster(NativeReducer.snapshot(pocketJson.parseToJsonElement(native.toString()).jsonObject))
        assertEquals(first, reopened)
        assertEquals(2, first.size)
        assertFalse(first.first().isCurrent)
        assertFalse(first.last().isCurrent)
        assertEquals(ActivityStatus.Completed, first.last().status)
        assertEquals("Historical result", first.first().resultPreview)
    }

    @Test fun activeTurnWithoutAgentsDoesNotMakeHistoricalRunningChildCurrent() {
        val call = item("old", """{"id":"call","type":"collabAgentToolCall","receiverThreadIds":["child"],"agentsStates":{"child":{"status":"running"}}}""")
        val child = agentRoster(timeline(call, active = "new")).single()
        assertFalse(child.isCurrent)
        assertEquals(ActivityStatus.Running, child.status)
        val completed = timeline(call).copy(turns = linkedMapOf("old" to NativeTurnInfo("old", status = "completed"), "new" to NativeTurnInfo("new", status = "completed")))
        assertFalse(agentRoster(completed).single().isCurrent)
    }

    @Test fun latestRunningSnapshotNeedsCurrentNativeExecutionEvidence() {
        val call = item("old", """{"id":"call","type":"collabAgentToolCall","receiverThreadIds":["child"],"agentsStates":{"child":{"status":"running"}}}""")
        val history = timeline(call).copy(turns = mapOf("old" to NativeTurnInfo("old", status = "completed")))
        for (snapshot in listOf(history, timeline(call), history.copy(activeTurn = "old"),
            timeline(call, active = "old").copy(status = "idle"))) {
            val roster = agentRoster(snapshot)
            assertFalse(roster.single().isCurrent)
            assertEquals(ActivityStatus.Running, roster.single().status)
            assertTrue(agentRosterSections(roster).current.isEmpty())
        }
        val resumed = agentRoster(history, mapOf("child" to AgentLiveStatus("active", cursor = Cursor("epoch", 10))))
        assertEquals(listOf("child"), agentRosterSections(resumed).current.map { it.threadId })
        assertTrue(agentRosterSections(agentRoster(history, mapOf("child" to AgentLiveStatus("active")))).current.isEmpty())
    }

    @Test fun onlyActualTypedRecordsSupplyChildrenAndAllowlistedProse() {
        val blob = item("turn", """{"id":"blob","type":"dynamicToolCall","arguments":{"type":"collabAgentToolCall","receiverThreadIds":["invented"]},"output":"{\"agentThreadId\":\"invented\"}"}""")
        val noId = item("turn", """{"id":"no-id","type":"subAgentActivity","agentPath":"root/phantom","kind":"started"}""")
        val real = item("turn", """{"id":"real","type":"collabAgentToolCall","receiverThreadIds":[true,42,"native: original id"],"prompt":{"secret":"hidden"},"agentsStates":{"native: original id":{"status":"notFound","message":{"text":"hidden"}}},"result":{"threadId":"invented"}}""")
        val roster = agentRoster(timeline(blob, noId, real))
        assertEquals(listOf("native: original id"), roster.map { it.threadId })
        assertEquals("notFound", roster.single().nativeStatus)
        assertEquals("", roster.single().taskPreview)
        assertEquals("", roster.single().resultPreview)
    }

    @Test fun stateMapIdentitiesWorkWithoutDirectoryOrReceiverArray() {
        val call = item("turn", """{"id":"call","type":"collabAgentToolCall","agentsStates":{"native-child":{"status":"pendingInit"}}}""")
        assertEquals("native-child", agentRoster(timeline(call)).single().threadId)
        assertEquals(ActivityStatus.Waiting, agentRoster(timeline(call)).single().status)
    }

    @Test fun nativeTaskRootKeepsEarlierSpawnCurrentAfterSteer() {
        val spawn = item("spawn-turn", """{"id":"spawn","type":"subAgentActivity","agentThreadId":"child","agentPath":"root/reviewer","kind":"started"}""")
        val linked = timeline(spawn, active = "steer-turn").copy(turns = linkedMapOf(
            "spawn-turn" to NativeTurnInfo("spawn-turn", rootId = "native-task"),
            "steer-turn" to NativeTurnInfo("steer-turn", rootId = "native-task", status = "inProgress")
        ))
        val agent = agentRoster(linked).single()
        assertTrue(agent.isCurrent)
        assertEquals(ActivityStatus.Running, agent.status)
    }

    @Test fun confirmedSteerChainsUseTheirNativeParentIdentity() {
        val spawn = item("original", """{"id":"spawn","type":"subAgentActivity","agentThreadId":"child","agentPath":"root/reviewer","kind":"started"}""")
        val linked = timeline(spawn, active = "second-steer").copy(turns = linkedMapOf(
            "original" to NativeTurnInfo("original", status = "completed"),
            "first-steer" to NativeTurnInfo("first-steer", observedParentId = "original"),
            "second-steer" to NativeTurnInfo("second-steer", observedParentId = "first-steer")
        ))
        assertTrue(agentRoster(linked).single().isCurrent)
        assertFalse(agentRoster(linked.copy(turns = linkedMapOf("original" to NativeTurnInfo("original"), "second-steer" to NativeTurnInfo("second-steer")))).single().isCurrent)
    }

    @Test fun cyclicLinksFallBackToExactTurnWithoutGroupingUnrelatedTurns() {
        val old = item("old", """{"id":"old-call","type":"subAgentActivity","agentThreadId":"old-child","agentPath":"root/old","kind":"started"}""")
        val latest = item("latest", """{"id":"new-call","type":"subAgentActivity","agentThreadId":"new-child","agentPath":"root/new","kind":"started"}""")
        val cyclic = timeline(old, latest, active = "latest").copy(turns = linkedMapOf(
            "old" to NativeTurnInfo("old", rootId = "latest"),
            "latest" to NativeTurnInfo("latest", observedParentId = "old")
        ))
        val agents = agentRoster(cyclic)
        assertFalse(agents.first().isCurrent)
        assertTrue(agents.last().isCurrent)
    }

    @Test fun previewsAreBoundedWithoutTruncatingNavigationIdentity() {
        val id = "native-" + "x".repeat(900)
        val raw = buildJsonObject {
            put("id", "call"); put("type", "collabAgentToolCall")
            put("receiverThreadIds", buildJsonArray { add(id) }); put("prompt", "p".repeat(30_000))
            put("agentsStates", buildJsonObject { put(id, buildJsonObject { put("status", "completed"); put("message", "r".repeat(30_000)) }) })
        }
        val agent = agentRoster(timeline(TimelineItem.from("one", raw))).single()
        assertEquals(id, agent.threadId)
        assertTrue(agent.taskPreview.length <= 600)
        assertTrue(agent.resultPreview.length <= 600)
    }
}
