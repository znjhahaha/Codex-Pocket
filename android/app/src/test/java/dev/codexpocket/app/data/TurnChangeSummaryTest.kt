package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TurnChangeSummaryTest {
    private fun edit(id: String, path: String = "a.rs", diff: String = "@@ -1 +1,2 @@\n-old\n+new\n+line", status: String = "completed") =
        TimelineItem("turn", id, "fileChange", complete = true, status = status, raw = buildJsonObject {
            put("changes", buildJsonArray { add(buildJsonObject { put("path", path); put("kind", "update"); put("diff", diff) }) })
        })
    private fun snapshot(id: String, text: String) = TimelineItem("turn", id, "turn/diff/updated", raw = buildJsonObject {
        put("params", buildJsonObject { put("diff", text) })
    })
    private fun timeline(vararg items: TimelineItem) = NativeTimeline(items.associateBy { it.key }, completedTurns = setOf("turn"))

    @Test fun latestCumulativeSnapshotReplacesEditsAndEarlierDiffs() {
        val value = timeline(edit("edit"), snapshot("first", "--- a/a.rs\n+++ b/a.rs\n@@ -0,0 +1,2 @@\n+a\n+b"),
            snapshot("last", "--- a/a.rs\n+++ b/a.rs\n@@ -0,0 +1 @@\n+a"))
        val summary = turnChangeSummaries(value).single()
        assertTrue(summary.cumulative); assertEquals(1, summary.files.size)
        assertEquals(1, summary.additions); assertEquals(0, summary.deletions)
    }
    @Test fun emptyAuthoritativeDiffDoesNotReviveOldEdits() {
        assertTrue(turnChangeSummaries(timeline(edit("edit"), snapshot("last", ""))).isEmpty())
    }
    @Test fun recordedEditsDeduplicatePathsAndExcludeFailures() {
        val result = turnChangeSummaries(timeline(edit("one"), edit("two"), edit("failed", "b.rs", status = "failed"))).single()
        assertFalse(result.cumulative); assertEquals(1, result.files.size)
        assertEquals(4, result.additions); assertEquals(2, result.deletions)
    }
    @Test fun activeAndUnknownTurnsNeverClaimCompletion() {
        val value = timeline(edit("one"))
        assertTrue(turnChangeSummaries(value.copy(activeTurn = "turn")).isEmpty())
        assertTrue(turnChangeSummaries(value.copy(completedTurns = emptySet())).isEmpty())
    }
    @Test fun binaryCountsRemainUnknownAndNativeRenameUsesDestination() {
        val binary = edit("one", "image.png", "Binary files a/image.png and b/image.png differ")
        val original = edit("rename").copy(raw = buildJsonObject {
            put("changes", buildJsonArray { add(buildJsonObject { put("path", "old.rs"); put("diff", ""); put("kind", buildJsonObject { put("type", "update"); put("movePath", "new.rs") }) }) })
        })
        val result = turnChangeSummaries(timeline(binary, original)).single()
        assertTrue(result.hasUnknownCounts); assertTrue(result.counted.isEmpty())
        assertEquals(listOf("image.png", "new.rs"), result.files.map { it.path })
    }
    @Test fun summaryAttachesOnlyAfterTheFinalVisibleRowAndReviewKeepsDiff() {
        val edit = edit("one")
        val answer = TimelineItem("turn", "answer", "agentMessage", "Done", complete = true)
        val value = timeline(edit, answer)
        val cards = changeSummaryRows(value, ConversationPresentation.rows(value))
        assertEquals(setOf(answer.key), cards.keys)
        val summary = cards.values.single().single()
        assertEquals(summary.files.map { it.diff }, activityFiles(summary.review()).map { it.diff })
    }
}
