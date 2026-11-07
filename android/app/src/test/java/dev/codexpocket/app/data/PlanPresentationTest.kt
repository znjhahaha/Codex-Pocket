package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class PlanPresentationTest {
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
