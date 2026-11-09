package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentReaderStateTest {
    private val parent = ThreadKey("computer", "store", "native-parent")
    private val child = parent.copy(nativeId = "native-child")
    private fun json(value: String) = pocketJson.parseToJsonElement(value).jsonObject
    private fun timeline(turn: String = "child-turn", status: String = "inProgress", threadStatus: String = "active", flags: String = "[]") =
        NativeReducer.snapshot(json("""{"id":"native-child","status":{"type":"$threadStatus","activeFlags":$flags},"turns":[{"id":"$turn","status":"$status","items":[]}]}"""))
    private fun live(state: NativeTimeline, sequence: Long, epoch: String = "e") = AgentLiveStatus.fromTimeline(state, Cursor(epoch, sequence))!!
    private fun parentTimeline(state: String = "pendingInit"): NativeTimeline {
        val item = TimelineItem.from("parent-turn", json("""{"id":"spawn","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","receiverThreadIds":["native-child","native-child"],"agentsStates":{"native-child":{"status":"$state"}}}"""))
        return NativeTimeline(items = linkedMapOf(item.key to item), activeTurn = "parent-turn")
    }

    @Test fun childSnapshotFixesPendingInitAndCountsOneNativeIdentityThroughCompletion() {
        var reader = AgentReaderState(parent)
        val source = parentTimeline()
        assertEquals(ActivityStatus.Waiting, agentRoster(source).single().status)
        reader = reader.withLiveStatus(child.nativeId, live(timeline(), 10))
        var roster = agentRoster(source, reader.liveStatuses)
        assertEquals(1, roster.count { it.isCurrent && it.status == ActivityStatus.Running })
        assertEquals("native-child", roster.single().threadId)
        assertEquals("inProgress", roster.single().nativeStatus)
        assertEquals("pendingInit", source.items.values.single().raw.obj("agentsStates").obj("native-child").string("status"))
        reader = reader.withLiveStatus(child.nativeId, live(timeline(status = "completed", threadStatus = "idle"), 11))
        roster = agentRoster(source, reader.liveStatuses)
        assertEquals(ActivityStatus.Completed, roster.single().status)
        assertEquals(0, roster.count { it.isCurrent && it.status == ActivityStatus.Running })
        assertEquals(source.items.values.single(), roster.single().item)
    }

    @Test fun lateDuplicateUnversionedAndTerminalStaleUpdatesCannotRestoreRunning() {
        val done = live(timeline(status = "completed", threadStatus = "idle"), 20)
        val reader = AgentReaderState(parent).withLiveStatus(child.nativeId, done)
        listOf(19L, 20L, 21L).forEach { sequence ->
            assertSame(reader, reader.withLiveStatus(child.nativeId, live(timeline(), sequence)))
        }
        assertSame(reader, reader.withLiveStatus(child.nativeId, live(timeline(), 99, "old-epoch")))
        assertSame(reader, reader.withLiveStatus(child.nativeId, AgentLiveStatus("running", "new-turn")))
        assertSame(reader, reader.withLiveStatus(child.nativeId, AgentLiveStatus("active", cursor = Cursor("e", 20))))
        assertEquals(ActivityStatus.Running, reader.withLiveStatus(child.nativeId, AgentLiveStatus("active", cursor = Cursor("e", 22))).liveStatuses.getValue(child.nativeId).status)
        val idleReader = reader.withLiveStatus(child.nativeId, AgentLiveStatus("idle", cursor = Cursor("e", 22)))
        assertEquals(ActivityStatus.Completed, idleReader.liveStatuses.getValue(child.nativeId).status)
        assertSame(idleReader, idleReader.withLiveStatus(child.nativeId, AgentLiveStatus("notLoaded", "child-turn", Cursor("e", 22))))
        val restarted = idleReader.withLiveStatus(child.nativeId, live(timeline(turn = "new-native-turn"), 23))
        assertEquals(ActivityStatus.Running, restarted.liveStatuses.getValue(child.nativeId).status)
        assertEquals("new-native-turn", restarted.liveStatuses.getValue(child.nativeId).turnId)
    }

    @Test fun parentTerminalStateIsNotOverriddenByAStaleRunningChildRead() {
        val reader = AgentReaderState(parent).withLiveStatus(child.nativeId, AgentLiveStatus("running", "child-turn"))
        listOf("completed", "errored", "interrupted", "shutdown").forEach { terminal ->
            assertEquals(NativeActivityPresentation.status(terminal), agentRoster(parentTimeline(terminal), reader.liveStatuses).single().status)
        }
    }

    @Test fun metadataAndNativeStatusEventsUpdateAlreadyNamedChildrenWithoutTurnItems() {
        val active = AgentLiveStatus.fromNativeStatus(json("""{"type":"active","activeFlags":[]}"""), "child-turn", Cursor("e", 2))!!
        val waiting = AgentLiveStatus.fromNativeStatus(json("""{"type":"active","activeFlags":["waitingOnApproval"]}"""), "child-turn", Cursor("e", 3))!!
        var statuses = emptyMap<String, AgentLiveStatus>().withAgentLiveStatus(child.nativeId, active)
        assertEquals(ActivityStatus.Running, agentRoster(parentTimeline(), statuses).single().status)
        statuses = statuses.withAgentLiveStatus(child.nativeId, waiting)
        assertEquals(ActivityStatus.Waiting, agentRoster(parentTimeline(), statuses).single().status)
        statuses = statuses.withAgentLiveStatus(child.nativeId, active.copy(cursor = Cursor("e", 4)))
        assertEquals(ActivityStatus.Running, agentRoster(parentTimeline(), statuses).single().status)
        assertEquals("active", statuses.getValue(child.nativeId).nativeStatus)
        assertNull(AgentLiveStatus.fromNativeStatus(json("""{"type":true,"text":"running"}""")))
        listOf("idle", "notLoaded").forEach { type ->
            val metadata = AgentLiveStatus.fromNativeStatus(json("""{"type":"$type"}"""))!!
            assertEquals(type, metadata.nativeStatus)
            assertEquals(ActivityStatus.Unknown, metadata.status)
        }
    }

    @Test fun mapKeysNeverCreateIdentitiesOrGuessFromNamesAndText() {
        val statuses = mapOf("Godel" to AgentLiveStatus("running"), "phantom" to AgentLiveStatus("completed"))
        assertEquals(listOf("native-child"), agentRoster(parentTimeline(), statuses).map { it.threadId })
        assertEquals(ActivityStatus.Waiting, agentRoster(parentTimeline(), statuses).single().status)
        assertSame(statuses, statuses.withAgentLiveStatus("", AgentLiveStatus("running")))
    }

    @Test fun unknownIdleAndWaitingFlagsRemainDistinctFromRunningOrCompleted() {
        val idle = live(NativeTimeline(status = "idle"), 1)
        assertEquals(ActivityStatus.Unknown, idle.status)
        assertEquals("idle", idle.nativeStatus)
        assertEquals(ActivityStatus.Unknown, live(timeline(status = "futureState", threadStatus = "idle"), 2).status)
        val future = AgentLiveStatus.fromNativeStatus(json("""{"type":"futureState"}"""), cursor = Cursor("e", 3))!!
        assertEquals(ActivityStatus.Unknown, agentRoster(parentTimeline("running"), mapOf(child.nativeId to future)).single().status)
        assertEquals(ActivityStatus.Waiting, live(timeline(flags = """["waitingOnApproval"]"""), 3).status)
        assertEquals(ActivityStatus.Waiting, live(timeline(flags = """["waitingOnUserInput"]"""), 4).status)
        assertEquals(ActivityStatus.Running, live(timeline(flags = """["futureFlag"]"""), 5).status)
        assertNull(AgentLiveStatus.fromTimeline(NativeTimeline()))
    }

    @Test fun selectedChildStatusUsesItsTimelineAndIgnoresDirectoryTitleAndOldStatus() {
        val chat = ChatEntity(child, "runtime", "desktop", "Godel", "", "", 0, "pendingInit", false)
        val state = AgentReaderState(parent, child = chat, timeline = timeline()).observeLiveStatus(Cursor("e", 2))
        assertEquals(ActivityStatus.Running, state.liveStatuses.getValue(child.nativeId).status)
        val event = json("""{"method":"thread/status/changed","params":{"status":{"type":"active","activeFlags":["waitingOnUserInput"]}}}""")
        val waiting = state.copy(timeline = NativeReducer.reduce(state.timeline, event)).observeLiveStatus(Cursor("e", 3))
        assertEquals(ActivityStatus.Waiting, waiting.liveStatuses.getValue(child.nativeId).status)
        assertEquals(state, state.copy(child = null).observeLiveStatus(Cursor("e", 9)).copy(child = chat))
    }

    @Test fun idleMetadataWatermarkCannotBlockEarlierOrEqualExplicitTurnCompletion() {
        listOf("idle", "notLoaded").forEach { native ->
            listOf(19L, 20L).forEach { sequence ->
                val metadata = AgentLiveStatus.fromNativeStatus(json("""{"type":"$native"}"""), cursor = Cursor("e", 20))!!
                assertEquals(ActivityStatus.Unknown, metadata.status)
                val completed = live(timeline(status = "completed", threadStatus = "idle"), sequence)
                val merged = mergeAgentLiveStatus(metadata, completed)
                assertEquals(ActivityStatus.Completed, merged.status)
                assertEquals("child-turn", merged.turnId)
                assertEquals(completed.cursor, merged.cursor)
                assertSame(merged, mergeAgentLiveStatus(merged, completed))
                // Completion refinement must not roll back the metadata freshness bound.
                assertSame(merged, mergeAgentLiveStatus(merged, AgentLiveStatus("running", "another-turn", Cursor("e", 20))))
            }
        }
    }

    @Test fun terminalEvidenceKeepsItsOwnOrderBelowTheMetadataWatermark() {
        val metadata = AgentLiveStatus("idle", cursor = Cursor("e", 30))
        val first = AgentLiveStatus("completed", "first-turn", Cursor("e", 18))
        val second = AgentLiveStatus("completed", "second-turn", Cursor("e", 19))
        val initial = mergeAgentLiveStatus(metadata, first)
        val merged = mergeAgentLiveStatus(initial, second)
        assertEquals("second-turn", merged.turnId)
        assertEquals(second.cursor, merged.cursor)
        assertSame(merged, mergeAgentLiveStatus(merged, first))
        assertSame(merged, mergeAgentLiveStatus(merged, second))
        assertSame(merged, mergeAgentLiveStatus(merged, AgentLiveStatus("running", "new-turn", Cursor("e", 29))))
        assertEquals(ActivityStatus.Running, mergeAgentLiveStatus(merged, AgentLiveStatus("running", "new-turn", Cursor("e", 31))).status)
    }

    @Test fun metadataAndCompletionMergeToTheSameStateInEitherArrivalOrder() {
        val metadata = AgentLiveStatus("notLoaded", cursor = Cursor("e", 20))
        val completed = AgentLiveStatus("completed", "child-turn", Cursor("e", 19))
        val metadataFirst = mergeAgentLiveStatus(metadata, completed)
        val completionFirst = mergeAgentLiveStatus(completed, metadata)
        assertEquals(ActivityStatus.Completed, metadataFirst.status)
        assertEquals(metadataFirst, completionFirst)
        assertSame(completionFirst, mergeAgentLiveStatus(completionFirst, AgentLiveStatus("active", "new-turn", Cursor("e", 20))))
    }

    @Test fun completionRefinementRequiresSameEpochAndExplicitNativeTurnEvidence() {
        val metadata = AgentLiveStatus("idle", cursor = Cursor("e", 20))
        listOf(
            AgentLiveStatus("completed", "child-turn", Cursor("different-epoch", 19)),
            AgentLiveStatus("completed", "child-turn"),
            AgentLiveStatus("completed", cursor = Cursor("e", 19)),
            AgentLiveStatus("running", "child-turn", Cursor("e", 19)),
            AgentLiveStatus("futureState", "child-turn", Cursor("e", 19))
        ).forEach { assertSame(metadata, mergeAgentLiveStatus(metadata, it)) }
        listOf("active", "waitingOnApproval", "futureState").forEach { native ->
            val observed = AgentLiveStatus(native, cursor = Cursor("e", 20))
            assertSame(observed, mergeAgentLiveStatus(observed, AgentLiveStatus("completed", "child-turn", Cursor("e", 19))))
        }
    }

    @Test fun idleMetadataAlsoAllowsExplicitFailedAndInterruptedTurnEvidence() {
        listOf("failed", "interrupted").forEach { terminal ->
            val merged = mergeAgentLiveStatus(AgentLiveStatus("idle", cursor = Cursor("e", 20)),
                AgentLiveStatus(terminal, "child-turn", Cursor("e", 19)))
            assertEquals(NativeActivityPresentation.status(terminal), merged.status)
        }
    }
}
