package dev.codexpocket.app.ui

import dev.codexpocket.app.data.TimelineItem
import dev.codexpocket.app.data.TranscriptKind
import dev.codexpocket.app.data.TranscriptRow
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class ConversationMinimapMappingTest {
    @Test fun expandedRailUsesOneQuestionLandmarkAndNarrowVariableProse() {
        val question = row("question", TranscriptKind.User).copy(items = listOf(TimelineItem("t", "q", "userMessage", "Long question ".repeat(100))))
        val answer = row("answer").copy(items = listOf(TimelineItem("t", "a", "agentMessage", "Short\nA somewhat longer line\nTiny\nAn even longer line of readable text")))
        val map = ConversationMinimapMap(listOf(question, answer), listOf("question", "answer"))
        val marks = minimapMarks(map, null, 48f, 600f, 1f, true)
        assertEquals(1, marks.count { it.kind == TranscriptKind.User })
        assertTrue(marks.all { it.left >= 8f && it.left + it.width <= 32f })
        assertEquals(4, marks.count { it.key == "answer" })
        assertTrue(marks.filter { it.key == "answer" }.map { it.width }.distinct().size > 2)
        assertTrue(marks.filter { it.key == "answer" }.all { it.height <= .60f })
    }
    @Test fun expandedInkUsesRealDiffLinesAndNeverUnfoldsHiddenProcess() {
        val item = TimelineItem("turn", "file", "fileChange", raw = Json.parseToJsonElement(
            """{"changes":[{"path":"example.kt","diff":"@@ -1 +1 @@\n-removed\n+added"}]}""").let { it as kotlinx.serialization.json.JsonObject })
        val rows = listOf(row("question", TranscriptKind.User),
            TranscriptRow("file", TranscriptKind.Activity, listOf(item)),
            TranscriptRow("folded", TranscriptKind.Process, listOf(item), processLabel = "编辑了文件"))
        val map = ConversationMinimapMap(rows, rows.map { it.key })
        assertEquals(listOf(MinimapInk.Question), map.tick(0).inks)
        assertEquals(listOf(MinimapInk.Removed, MinimapInk.Added), map.tick(1).inks)
        assertEquals(listOf(MinimapInk.Faint), map.tick(2).inks)
        val expanded = minimapMarks(map, null, 64f, 300f, 1f, true)
        assertTrue(expanded.any { it.ink == MinimapInk.Added })
        assertTrue(expanded.any { it.ink == MinimapInk.Removed })
        assertTrue(minimapMarks(map, null, 24f, 300f, 1f, false).all { it.ink == MinimapInk.Text })
    }

    @Test fun inspectedCollapsedRowHasAMarkEvenWhenNormalSamplingOmitsIt() {
        val rows = (0 until 1000).map { row("r$it", TranscriptKind.User) }
        val map = ConversationMinimapMap(rows, rows.map { it.key })
        val marks = minimapMarks(map, null, 24f, 300f, 1f, false, "r517")
        assertEquals(15f, marks.single { it.key == "r517" }.width, .01f)
        assertTrue(marks.size <= 300)
    }

    @Test fun collapsedRailContainsOnlyQuestionsAndFindsTheNearestQuestion() {
        val rows = listOf(row("u1", TranscriptKind.User), row("a1"), row("tool", TranscriptKind.Activity),
            row("u2", TranscriptKind.User), row("a2"))
        val map = ConversationMinimapMap(rows, rows.map { it.key })
        val marks = minimapMarks(map, MinimapViewport(.4f, .6f), 24f, 300f, 1f, false)
        assertEquals(setOf("u1", "u2"), marks.map { it.key }.toSet())
        assertTrue(marks.all { it.kind == TranscriptKind.User })
        assertEquals("u2", map.userTargetAt(.95f)?.key)
        assertEquals("u1", map.userTargetAt(0f)?.key)
        assertNull(ConversationMinimapMap(listOf(row("answer")), listOf("answer")).userTargetAt(.5f))
    }

    @Test fun expandedMapIncludesEveryLoadedRowAndLongRowsGetMoreSpace() {
        val rows = (0 until 60).map { row("r$it", if (it % 5 == 0) TranscriptKind.User else TranscriptKind.Answer) }
        val map = ConversationMinimapMap(rows, rows.map { it.key })
        assertEquals(rows.map { it.key }.toSet(), minimapMarks(map, null, 64f, 1600f, 1f, true).map { it.key }.toSet())
        val short = row("short")
        val long = row("long").copy(items = listOf(TimelineItem("turn", "long", "agentMessage", "Long message ".repeat(200))))
        val lengths = ConversationMinimapMap(listOf(short, long), listOf("short", "long"))
        assertEquals("long", lengths.targetAt(.2f)?.key)
    }

    @Test fun questionTicksStayEquallySpacedDespiteDifferentAnswerLengths() {
        val rows = listOf(row("u0", TranscriptKind.User), row("huge").copy(items = listOf(
            TimelineItem("t", "a", "agentMessage", "Long answer ".repeat(1000)))),
            row("u1", TranscriptKind.User), row("short"), row("u2", TranscriptKind.User))
        val map = ConversationMinimapMap(rows, rows.map { it.key })
        val marks = minimapMarks(map, null, 24f, 300f, 1f, false)
        assertEquals(3, marks.size)
        assertEquals(marks[1].top - marks[0].top, marks[2].top - marks[1].top, .01f)
        assertEquals("u1", map.userTargetAt(.5f)?.key)
        assertEquals("u2", map.userTargetAt(.9f)?.key)
    }

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

    @Test fun floatCoordinatesAtRowBoundariesDoNotSelectThePrecedingRow() {
        val rows = (0 until 1000).map { row("r$it") }
        val map = ConversationMinimapMap(rows, rows.map { it.key })
        assertEquals(MinimapTarget("r501", 0f), map.targetAt(501f / 1000))
    }

    @Test fun reverseViewportClipsTheSameLoadedRowAtTheOppositeEdge() {
        val map = ConversationMinimapMap(listOf(row("long")), listOf("history", "long", "end"))
        val viewport = map.viewport(listOf(MinimapVisibleRow("long", -200, 1000)), 0, 300, reverse = true)!!
        assertEquals(.5f, viewport.start, .0001f)
        assertEquals(.8f, viewport.end, .0001f)
    }

    @Test fun foldedAwayRowsNeverFallBackToAnUnrelatedIndex() {
        val map = ConversationMinimapMap(listOf(row("user"), row("process", TranscriptKind.Process), row("answer")),
            listOf("history", "user", "process", "answer", "end"))
        assertEquals(listOf("user", "process", row("process").items.single().key, "answer"), map.entries.map { it.key })
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
        assertEquals(1000, map.ticks.size)
        assertTrue(map.ticks.all { it.widths.size in 1..24 })
    }

    @Test fun miniatureOrderFollowsTheRenderedKeysEvenIfRowsArriveOutOfOrder() {
        val map = ConversationMinimapMap(listOf(row("b"), row("a")), listOf("history", "a", "b", "end"))
        assertEquals(listOf("a", "b"), map.entries.map { it.key })
        assertEquals("a", map.targetAt(0f)?.key)
    }

    @Test fun previewUsesOnlyTheLoadedQuestionAndNeighborAndBoundsPlainText() {
        val question = row("question", TranscriptKind.User).copy(items = listOf(
            TimelineItem("turn", "question", "userMessage", "Question\n\t with whitespace " + "x".repeat(100_000))))
        val process = row("fold", TranscriptKind.Process).copy(processLabel = "3 steps", processDetail = "Hidden secret")
        val answer = row("answer").copy(items = listOf(TimelineItem("turn", "answer", "agentMessage", "**Literal** [link](https://example.com)\nAnswer")))
        val map = ConversationMinimapMap(listOf(question, process, answer), listOf("question", "fold", "answer"))
        val preview = map.preview("answer")!!
        assertEquals("answer", preview.key)
        assertTrue(preview.question.startsWith("Question with whitespace"))
        assertTrue(preview.question.length <= 96)
        assertFalse(preview.question.contains('\n'))
        assertTrue(preview.neighbor.startsWith("**Literal** [link](https://example.com)"))
        assertEquals("4 / 4 · 已加载", preview.position)
        assertEquals("3 steps", map.preview("fold")!!.neighbor)
        assertNull(map.preview("not-loaded"))
        assertTrue(map.preview("fold")!!.neighbor != "Hidden secret")
    }

    @Test fun collapsedMarksHighlightTheQuestionOfTheCurrentAnswer() {
        val rows = (0 until 1000).map { row("r$it", if (it == 500) TranscriptKind.User else TranscriptKind.Answer) }
        val map = ConversationMinimapMap(rows, rows.map { it.key })
        val marks = minimapMarks(map, MinimapViewport(.501f, .502f), 24f, 360f, 1f, expanded = false)
        assertTrue(marks.size <= 36)
        assertTrue(marks.all { it.left >= 0f && it.left + it.width <= 24f && it.top >= 8f && it.top + it.height <= 352f })
        val current = marks.single { it.current }
        assertEquals("r500", current.key)
        assertTrue(marks.all { it.kind == TranscriptKind.User })
        val shortMap = ConversationMinimapMap(listOf(row("user", TranscriptKind.User), row("answer")), listOf("user", "answer"))
        val shortMarks = minimapMarks(shortMap, null, 24f, 100f, 1f, expanded = false)
        assertEquals(listOf("user"), shortMarks.map { it.key })
    }

    @Test fun expandedParagraphGeometryHasGapsAndFitsTinyAndLargeTracks() {
        val rows = (0 until 2000).map { row("r$it").copy(items = listOf(
            TimelineItem("turn", "$it", "agentMessage", "A paragraph with enough words to form a miniature\n\nAnother paragraph"))) }
        val map = ConversationMinimapMap(rows, rows.map { it.key })
        val marks = minimapMarks(map, MinimapViewport(0f, .001f), 64f, 360f, 1f, expanded = true)
        assertTrue(marks.size <= 360)
        assertTrue(marks.all { it.left >= 0 && it.left + it.width <= 64 && it.top >= 8 && it.top + it.height <= 352 })
        assertTrue(marks.groupBy { it.key }.values.all { it.size <= 24 })
        val tiny = minimapMarks(map, null, 4f, 5f, 1f, expanded = true)
        assertTrue(tiny.all { it.left + it.width <= 4f && it.top + it.height <= 5f })
        assertTrue(minimapMarks(map, null, Float.NaN, 360f, 1f, expanded = true).isEmpty())
    }

    @Test fun aLongUnbrokenParagraphSamplesAsABoundedMiniatureParagraph() {
        val paragraph = row("paragraph").copy(items = listOf(TimelineItem("turn", "paragraph", "agentMessage", "Long paragraph ".repeat(10000))))
        val map = ConversationMinimapMap(listOf(paragraph), listOf("paragraph"))
        assertEquals(24, map.ticks.single().widths.size)
        assertTrue(map.ticks.single().widths.all { it in .10f..1f })
    }

    @Test fun expandedTickHitTestingIgnoresTheEmptyGapAndTracksStableKeys() {
        val map = ConversationMinimapMap(listOf(row("a"), row("b")), listOf("a", "b"))
        assertEquals("a", minimapTickAt(map, null, 64f, 200f, 1f, 54f)?.key)
        assertNull(minimapTickAt(map, null, 64f, 200f, 1f, 100f))
    }

    @Test fun minimumThumbUsesItsActualTravelForGrabbedDragging() {
        val viewport = MinimapViewport(.5f, .501f)
        val thumb = minimapThumb(viewport, 300f, 8f, 16f)!!
        val fraction = minimapDragFraction(viewport, thumb, 300f, 8f, thumb.height / 2, 284f)
        assertEquals(.999f, fraction, .0001f)
        assertEquals(.5f, minimapDragFraction(viewport, thumb, 300f, 8f, 8f, thumb.top + 8f), .0001f)
        assertNull(minimapThumb(MinimapViewport(.8f, .2f), 300f, 8f, 16f))
        assertNull(minimapThumb(viewport, 300f, Float.NaN, 16f))
    }
}
