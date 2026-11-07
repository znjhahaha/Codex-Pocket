package dev.codexpocket.app.ui

import dev.codexpocket.app.data.TimelineItem
import dev.codexpocket.app.data.TranscriptKind
import dev.codexpocket.app.data.TranscriptRow
import org.junit.Assert.*
import org.junit.Test

class ConversationMinimapMappingTest {
    private fun row(key: String, kind: TranscriptKind = TranscriptKind.Answer) =
        TranscriptRow(key, kind, listOf(TimelineItem("turn", key, "agentMessage", "A short line")))

    @Test fun targetsOnlyLoadedRowsAndResolvesTheActualLazyListKeys() {
        val map = ConversationMinimapMap(listOf(row("a"), row("b")), listOf("history", "a", "live", "b", "end"))
        assertEquals(MinimapTarget("a", 0f), map.targetAt(-1f))
        assertEquals("b", map.targetAt(1f)?.key)
        assertEquals(1, map.listIndex("a"))
        assertEquals(3, map.listIndex("b"))
        assertNull(map.listIndex("history"))
        assertNull(map.targetAt(Float.NaN))
        assertNull(map.targetAt(Float.POSITIVE_INFINITY))
    }

    @Test fun aGestureKeepsItsRowKeyAcrossPrependsAndInsertions() {
        val before = ConversationMinimapMap(listOf(row("a"), row("b"), row("c")), listOf("history", "a", "b", "c"))
        val target = before.targetAt(.5f)!!
        val after = ConversationMinimapMap(listOf(row("older"), row("a"), row("inserted"), row("b"), row("c")),
            listOf("history", "older", "a", "inserted", "b", "c", "live"))
        assertEquals("b", target.key)
        assertEquals(4, after.listIndex(target.key))
        assertEquals(.5f, target.withinRow, .0001f)
    }

    @Test fun foldedAwayRowsNeverFallBackToAnUnrelatedIndex() {
        val map = ConversationMinimapMap(listOf(row("user"), row("process", TranscriptKind.Process), row("answer")),
            listOf("history", "user", "process", "answer", "end"))
        assertEquals(listOf("user", "process", "answer"), map.entries.map { it.key })
        assertNull(map.listIndex("hidden-tool"))
        val folded = ConversationMinimapMap(listOf(row("user"), row("answer")), listOf("history", "user", "answer", "end"))
        assertNull(folded.listIndex(map.targetAt(.5f)!!.key))
    }

    @Test fun emptyMissingAndAmbiguousKeysAreSafe() {
        assertNull(ConversationMinimapMap(emptyList(), emptyList()).targetAt(.4f))
        assertTrue(ConversationMinimapMap(listOf(row("a")), listOf("history")).entries.isEmpty())
        assertTrue(ConversationMinimapMap(listOf(row("a")), listOf("a", "a")).entries.isEmpty())
        assertTrue(ConversationMinimapMap(listOf(row("a"), row("a")), listOf("a")).entries.isEmpty())
    }

    @Test fun viewportUsesKeysAndClippedFractionsInsteadOfStaleIndices() {
        val map = ConversationMinimapMap(listOf(row("a"), row("b"), row("c")), listOf("history", "a", "b", "c", "end"))
        val viewport = map.viewport(listOf(
            MinimapVisibleRow("history", -90, 40),
            MinimapVisibleRow("a", -50, 100),
            MinimapVisibleRow("b", 60, 100),
            MinimapVisibleRow("end", 160, 20),
        ), 0, 110)!!
        assertEquals(1f / 6, viewport.start, .0001f)
        assertEquals(.5f, viewport.end, .0001f)
        assertNull(map.viewport(listOf(MinimapVisibleRow("removed", 0, 100)), 0, 100))
        assertNull(map.viewport(listOf(MinimapVisibleRow("a", 0, 0)), 0, 100))
        assertNull(map.viewport(listOf(MinimapVisibleRow("a", 0, 100)), 10, 10))
    }

    @Test fun aTallSingleRowHasAPartialViewportAndWithinRowTargets() {
        val map = ConversationMinimapMap(listOf(row("long")), listOf("history", "long", "end"))
        val viewport = map.viewport(listOf(MinimapVisibleRow("long", -200, 1000)), 0, 300)!!
        assertEquals(.2f, viewport.start, .0001f)
        assertEquals(.5f, viewport.end, .0001f)
        assertEquals(MinimapTarget("long", .7f), map.targetAt(.7f))
    }

    @Test fun thumbStaysInsideTheTrackEvenForTinyViewports() {
        val thumb = minimapThumb(MinimapViewport(.999f, 1f), 300f, 8f, 16f)!!
        assertEquals(276f, thumb.top, .0001f)
        assertEquals(16f, thumb.height, .0001f)
        val tiny = minimapThumb(MinimapViewport(0f, 1f), 10f, 8f, 16f)!!
        assertTrue(tiny.top >= 0f)
        assertTrue(tiny.top + tiny.height <= 10f)
        assertNull(minimapThumb(null, 300f, 8f, 16f))
        assertNull(minimapThumb(MinimapViewport(0f, 1f), 0f, 8f, 16f))
    }

    @Test fun samplingAndTextWorkAreBoundedForLargeLoadedHistories() {
        val indices = minimapSampleIndices(100_000, 160)
        assertEquals(160, indices.size)
        assertEquals(0, indices.first())
        assertEquals(99_999, indices.last())
        assertEquals(indices.sorted().distinct(), indices)
        assertTrue(minimapSampleIndices(20, 0).isEmpty())
        assertTrue(minimapSampleIndices(0, 20).isEmpty())
        val map = ConversationMinimapMap((0 until 1000).map { row("r$it") }, (0 until 1000).map { "r$it" })
        assertTrue(map.ticks.size <= 160)
        assertTrue(map.ticks.all { it.widths.size in 1..3 })
    }
}
