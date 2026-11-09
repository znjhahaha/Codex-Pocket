package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class V053PlanStateTest {
    private val key = ThreadKey("computer", "store", "thread")
    private val plan = ProposedPlan("turn", "item", "# Full plan\n\nKeep all text.")

    @Test fun feedbackIsBoundToOwnerThreadAndExactRevision() {
        val feedback = PlanFeedbackDraft(key, "runtime", plan.revision, "Change step two")
        assertTrue(feedback.matches(key, "runtime", plan))
        assertFalse(feedback.matches(key.copy(nativeId = "other"), "runtime", plan))
        assertFalse(feedback.matches(key, "new-owner", plan))
        assertFalse(feedback.matches(key, "runtime", plan.copy(content = "new revision")))
    }

    @Test fun feedbackPersistenceKeepsTextAndOriginalModeFence() {
        val feedback = PlanFeedbackDraft(key, "runtime", plan.revision, "中文\nfeedback", NativeSetting(true, "default"), true, 17L)
        assertEquals(feedback, PlanFeedbackDraft.restore(feedback.persisted(), key, "runtime"))
        assertNull(PlanFeedbackDraft.restore(feedback.persisted(), key, "new-owner"))
    }

    @Test fun exportNameIsLocalAndNeverUsesNativeIdsAsPaths() {
        assertEquals("plan-${plan.revision.take(12)}.md", plan.markdownFilename)
        assertEquals(plan.content, plan.markdownText)
    }
}
