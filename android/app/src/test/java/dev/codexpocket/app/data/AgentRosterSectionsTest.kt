package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class AgentRosterSectionsTest {
    @Test fun activeChildrenLeadAndClosedUnknownAndHistoricalChildrenStartFolded() {
        val agents=listOf(
            AgentRosterEntry("unknown",status=ActivityStatus.Unknown,isCurrent=true),
            AgentRosterEntry("stopped",status=ActivityStatus.Interrupted,nativeStatus="shutdown",isCurrent=true),
            AgentRosterEntry("waiting",status=ActivityStatus.Waiting,isCurrent=true),
            AgentRosterEntry("running",status=ActivityStatus.Running,isCurrent=true),
            AgentRosterEntry("old-running",status=ActivityStatus.Running,isCurrent=false),
            AgentRosterEntry("done",status=ActivityStatus.Completed,isCurrent=true))
        val groups=agentRosterSections(agents)
        assertEquals(listOf("running","waiting"),groups.current.map {it.threadId})
        assertEquals(listOf("unknown","stopped","old-running","done"),groups.folded.map {it.threadId})
        assertEquals(agents.size,groups.current.size+groups.folded.size)
        assertEquals(ActivityStatus.Running,groups.folded.single {it.threadId=="old-running"}.status)
    }
}
