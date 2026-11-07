package dev.codexpocket.app

import dev.codexpocket.app.data.*
import dev.codexpocket.app.data.ConversationPresentation.LiveStatus
import dev.codexpocket.app.ui.elapsedLabel
import org.junit.Assert.*
import org.junit.Test

class LiveStatusTest {
    private fun timeline(active: String?, vararg items: TimelineItem) = NativeTimeline(linkedMapOf(*items.map { it.key to it }.toTypedArray()), active)

    @Test fun reasoningIsNeverATranscriptRow() {
        val t = timeline(null, TimelineItem("t", "u", "userMessage", "hi"), TimelineItem("t", "r", "reasoning", "**Plan** things"),
            TimelineItem("t", "a", "agentMessage", "done"))
        assertEquals(listOf(TranscriptKind.User, TranscriptKind.Answer), ConversationPresentation.rows(t).map { it.kind })
    }

    @Test fun reasoningDoesNotSplitAToolGroup() {
        val t = timeline("t", TimelineItem("t", "c1", "commandExecution", "ls"), TimelineItem("t", "r", "reasoning", "x", complete = true),
            TimelineItem("t", "c2", "commandExecution", "pwd"))
        val rows = ConversationPresentation.rows(t)
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].items.size)
    }

    @Test fun liveStatusFollowsTheRunningTurn() {
        val user = TimelineItem("t", "u", "userMessage", "hi")
        assertNull(ConversationPresentation.liveStatus(timeline(null, user)))
        assertEquals(LiveStatus.Thinking, ConversationPresentation.liveStatus(timeline("t", user)))
        assertEquals(LiveStatus.Thinking, ConversationPresentation.liveStatus(timeline("t", user, TimelineItem("t", "r", "reasoning", "", complete = false))))
        assertEquals(LiveStatus.Working, ConversationPresentation.liveStatus(timeline("t", user, TimelineItem("t", "c", "commandExecution", "ls", complete = false))))
        assertEquals(LiveStatus.Compacting, ConversationPresentation.liveStatus(timeline("t", user, TimelineItem("t", "k", "contextCompaction", complete = false))))
    }

    @Test fun elapsedReadsLikeCodex() {
        assertEquals("0:15", elapsedLabel(15))
        assertEquals("1:05", elapsedLabel(65))
        assertEquals("1:00:05", elapsedLabel(3605))
        assertEquals("0:00", elapsedLabel(-1))
    }

    @Test fun onlyNativeSummaryFromTheCurrentThinkingItemIsExposed() {
        val summary = TimelineItem("t", "r", "reasoning", "**Checking layout**\n\nReviewing the constraints.")
        assertEquals(summary, ConversationPresentation.liveReasoning(timeline("t", summary)))
        assertNull(ConversationPresentation.liveReasoning(timeline(null, summary)))
        assertNull(ConversationPresentation.liveReasoning(timeline("other", summary)))
        assertNull(ConversationPresentation.liveReasoning(timeline("t", summary.copy(text = ""))))
        assertNull(ConversationPresentation.liveReasoning(timeline("t", summary, TimelineItem("t", "c", "commandExecution", "check"))))
    }
}
