package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Test

class V04TranscriptPresentationTest {
    @Test fun failedCommandKeepsContiguousWorkFoldableAndPreservesFailure() {
        val first=TimelineItem("t","first","commandExecution",status="completed")
        val failed=first.copy(itemId="failed",status="failed",text="recorded error")
        val last=first.copy(itemId="last")
        val row=ConversationPresentation.rows(NativeTimeline(listOf(first,failed,last).associateBy {it.key}),foldTasks=false).single()
        assertEquals(listOf(first.key,failed.key,last.key),row.items.map {it.key})
        assertTrue(NativeActivityPresentation.foldedByDefault(row))
        assertFalse(NativeActivityPresentation.groupExpanded(row,true,false,listOf(failed.key)))
        assertEquals(1,NativeActivityPresentation.summary(row.items).failed)
        assertEquals(ActivityStatus.Failed,NativeActivityPresentation.state(failed))
        assertFalse(NativeActivityPresentation.foldedByDefault(row.copy(items=listOf(failed.copy(status="waitingOnApproval")))))
    }
    @Test fun completionFoldsByDefaultButNeverOverridesAnExplicitExpansion() {
        val item = TimelineItem("t", "cmd", "commandExecution", liveStarted = true)
        val live = TranscriptRow("activity:${item.key}", TranscriptKind.Activity, listOf(item))
        val done = live.copy(items = listOf(item.copy(complete = true)), completedTurn = true)
        assertTrue(NativeActivityPresentation.groupExpanded(live, false, null, emptyList()))
        assertFalse(NativeActivityPresentation.groupExpanded(done, false, null, emptyList()))
        val recordedCompletion = live.copy(items = listOf(item.copy(status = "completed")))
        assertTrue(NativeActivityPresentation.foldedByDefault(recordedCompletion))
        assertTrue(NativeActivityPresentation.groupExpanded(done, true, null, emptyList()))
        assertTrue(NativeActivityPresentation.groupExpanded(done, false, null, listOf(item.key)))
        assertFalse(NativeActivityPresentation.groupExpanded(done, true, false, listOf(item.key)))
        assertTrue(NativeActivityPresentation.groupExpanded(done, false, true, emptyList()))
    }

    @Test fun cachedContentSurvivesEveryReadStateAndOnlyEmptyLoadingShowsSkeleton() {
        ConversationLoadState.entries.forEach { state ->
            val view = conversationReadPresentation(state, hasRows = true)
            assertFalse(view.skeleton)
            assertFalse(view.empty)
            assertEquals(state == ConversationLoadState.Failed, view.failed)
            assertEquals(state == ConversationLoadState.Loading, view.refreshing)
        }
        assertTrue(conversationReadPresentation(ConversationLoadState.Loading, false).skeleton)
        assertFalse(conversationReadPresentation(ConversationLoadState.Loading, false, hasCachedWindow = true).skeleton)
        assertTrue(conversationReadPresentation(ConversationLoadState.Loading, false, hasCachedWindow = true).refreshing)
        assertFalse(conversationReadPresentation(ConversationLoadState.Failed, false).empty)
        assertTrue(conversationReadPresentation(ConversationLoadState.Ready, false).empty)
        assertTrue(conversationReadPresentation(ConversationLoadState.Idle, false).empty)
    }

    @Test fun compactNamesSupportBothDesktopPathSeparatorsWithoutChangingIdentity() {
        assertEquals("Transcript.kt", compactFileName("C:\\project\\ui\\Transcript.kt"))
        assertEquals("Transcript.kt", compactFileName("src/ui/Transcript.kt"))
        assertEquals("", compactFileName(""))
    }
}
