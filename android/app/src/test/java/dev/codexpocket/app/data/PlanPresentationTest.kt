package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class PlanPresentationTest {
    @Test fun writingRequiresCurrentLiveIncompletePlan() {
        val item = TimelineItem("live", "proposal", "plan", "# Draft", complete = false)
        val timeline = NativeTimeline(items = mapOf(item.key to item), activeTurn = "live", status = "inProgress")
        assertTrue(PlanPresentation.isWriting(item, timeline, true))
        assertFalse(PlanPresentation.isWriting(item, timeline, false))
        assertFalse(PlanPresentation.isWriting(item.copy(complete = true), timeline, true))
        assertFalse(PlanPresentation.isWriting(item, timeline.copy(activeTurn = "other"), true))
        assertFalse(PlanPresentation.isWriting(item, timeline.copy(status = "idle"), true))
        assertFalse(PlanPresentation.isWriting(item, timeline.copy(activeFlags = setOf("waitingOnApproval")), true))
        assertFalse(PlanPresentation.isWriting(item, timeline.copy(activeFlags = setOf("waitingOnUserInput")), true))
        assertFalse(PlanPresentation.isWriting(item, timeline.copy(completedTurns = setOf("live")), true))
        assertFalse(PlanPresentation.isWriting(item, timeline.copy(turns = mapOf("live" to NativeTurnInfo("live", status = "failed"))), true))
    }
    private val proposal = TimelineItem("p", "plan", "plan", "# Plan\n\nDo the requested work.", complete = true)
    private fun timeline(vararg rest: TimelineItem) = NativeTimeline(
        items = (listOf(proposal) + rest).associateBy { it.key },
        turns = mapOf("p" to NativeTurnInfo("p", status = "completed")))

    @Test fun onlyLatestSuccessfulNativeProposalCanBeImplemented() {
        assertNotNull(PlanPresentation.latest(timeline()))
        assertNull(PlanPresentation.latest(timeline().copy(activeTurn = "next")))
        assertNull(PlanPresentation.latest(timeline().copy(turns = mapOf("p" to NativeTurnInfo("p", status = "failed")))))
        assertNull(PlanPresentation.latest(timeline(TimelineItem("next", "u", "userMessage", "change it"))))
        assertNull(PlanPresentation.latest(timeline().copy(items = mapOf(proposal.key to proposal.copy(type = "turn/plan/updated")))))
    }

    @Test fun contentRevisionAndSyntheticMessageKeepNativeAssociation() {
        val plan = PlanPresentation.latest(timeline())!!
        assertNotEquals(plan.revision, plan.copy(content = "changed").revision)
        val message = TimelineItem("implementation", "u", "userMessage", plan.prompt)
        assertEquals(plan, PlanPresentation.implementedProposal(message, timeline(message)))
        assertNull(PlanPresentation.implementedProposal(message.copy(text = plan.prompt + "\nextra"), timeline(message)))
        assertNull(PlanPresentation.implementedProposal(message, NativeTimeline(items = mapOf(message.key to message))))
    }
}
