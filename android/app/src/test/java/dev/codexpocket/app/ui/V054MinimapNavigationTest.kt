package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Test

class V054MinimapNavigationTest {
    private val initial = TimelineItem("t", "initial", "userMessage", "Start", complete = true)
    private val hidden1 = TimelineItem("t", "hidden1", "userMessage", "First hidden question", complete = true)
    private val hidden2 = TimelineItem("t", "hidden2", "userMessage", "Second hidden question", complete = true)
    private val comment = TimelineItem("t", "comment", "agentMessage", "Short\nA longer process reply", complete = true)
    private val final = TimelineItem("t", "final", "agentMessage", "Answer", complete = true)
    private val process = TranscriptRow("process:t", TranscriptKind.Process, listOf(hidden1, comment, hidden2), true, "上 3 条消息")
    private val folded = listOf(TranscriptRow(initial.key, TranscriptKind.User, listOf(initial)), process,
        TranscriptRow(final.key, TranscriptKind.Answer, listOf(final)))
    private fun map(rows: List<TranscriptRow> = folded, cache: MinimapSampleCache = MinimapSampleCache()) =
        ConversationMinimapMap(rows, listOf("history") + rows.map { it.key } + "end", cache)

    @Test fun everyHiddenUserHasNativeIdentityAndOwnerWithoutBecomingARenderedRow() {
        val map = map()
        assertEquals(listOf(initial.key, hidden1.key, hidden2.key), map.userSlots.map { map.entries[it].key })
        assertEquals(process.key, map.entries.single { it.key == hidden1.key }.ownerProcessKey)
        assertNull(map.listIndex(hidden1.key))
        assertEquals(2, map.listIndex(process.key))
        assertEquals(3, folded.size)
        assertEquals("First hidden question", map.preview(hidden1.key)?.question)
        assertEquals("Short A longer process reply", map.preview(hidden1.key)?.neighbor)
    }

    @Test fun expandedProcessReusesTheSameNavigationOrderWithNoDuplicateHiddenQuestions() {
        val before = map()
        val expanded = map(expandTaskRows(folded, setOf(process.key)))
        assertEquals(before.navigationKeys, expanded.navigationKeys)
        assertEquals(1, expanded.entries.count { it.key == hidden1.key })
        assertEquals(1, expanded.entries.count { it.key == hidden2.key })
        assertNotNull(expanded.listIndex(hidden1.key))
        assertEquals(MinimapSeekPlan(hidden1.key), expanded.seekPlan(MinimapTarget(hidden1.key, 0f), true))
    }

    @Test fun hiddenDragOnlyScrollsOwnerAndFinalCommitRequestsExactlyOneExpansion() {
        val map = map()
        val first = MinimapTarget(hidden1.key, 0f)
        val last = MinimapTarget(hidden2.key, 0f)
        assertEquals(MinimapSeekPlan(process.key), map.seekPlan(first, false))
        assertEquals(MinimapSeekPlan(process.key), map.seekPlan(last, false))
        assertEquals(MinimapSeekPlan(hidden2.key, process.key), map.seekPlan(last, true))
        assertEquals(MinimapSeekPlan(initial.key), map.seekPlan(MinimapTarget(initial.key, 0f), true))
    }

    @Test fun removedOwnerOrKeyCannotResolveToAnotherProcess() {
        val target = MinimapTarget(hidden2.key, 0f)
        val replacement = process.copy(key = "process:replacement", items = listOf(comment))
        assertNull(map(listOf(folded.first(), replacement, folded.last())).seekPlan(target, true))
        assertNull(map(folded.filter { it.kind != TranscriptKind.Process }).seekPlan(target, false))
        val lostOwner = ConversationMinimapMap(folded, listOf(initial.key, final.key))
        assertNull(lostOwner.slot(hidden2.key))
    }

    @Test fun collapsedQuestionGapsAreEqualAndCappedAtTenDp() {
        for (density in listOf(1f, 3f)) {
            val marks = minimapMarks(map(), null, 24f * density, 600f * density, density, false)
            assertEquals(listOf(initial.key, hidden1.key, hidden2.key), marks.map { it.key })
            val gaps = marks.zipWithNext { a, b -> b.top - a.top }
            assertEquals(gaps[0], gaps[1], .01f)
            assertTrue(gaps.all { it <= 10f * density + .01f })
            val track = (600f - 16f) * density
            val selected = map().userTargetAt((marks[1].top + marks[1].height / 2 - 8f * density) / track,
                10f * density / track)
            assertEquals(hidden1.key, selected?.key)
        }
    }

    @Test fun expandedRailSamplesAllLoadedHiddenContentWithoutExpandingTheTranscript() {
        val map = map()
        val marks = minimapMarks(map, null, 48f, 800f, 1f, true)
        assertEquals(map.navigationKeys.toSet(), marks.map { it.key }.toSet())
        val hiddenReply = marks.filter { it.key == comment.key }
        assertEquals(2, hiddenReply.size)
        assertTrue(hiddenReply.zipWithNext { a, b -> b.top - a.top }.all { it <= 1.31f })
        assertEquals(listOf(initial.key, process.key, final.key), folded.map { it.key })
    }

    @Test fun processViewportIncludesItsHiddenSamplesAndCollapsedAndExpandedMapsAgree() {
        val map = map()
        val viewport = map.viewport(listOf(MinimapVisibleRow(process.key, 0, 100)), 0, 100)!!
        assertTrue(map.tick(map.slot(hidden1.key)!!).fraction in viewport.start..viewport.end)
        assertTrue(map.tick(map.slot(hidden2.key)!!).fraction in viewport.start..viewport.end)
        assertTrue(viewport.end > map.range(map.slot(process.key)!!).endInclusive)
    }

    @Test fun sampleIdentityIsRetainedAcrossStreamingAndProcessExpansion() {
        val cache = MinimapSampleCache()
        val first = map(cache = cache)
        val next = map(expandTaskRows(folded, setOf(process.key)), cache)
        assertSame(first.tick(first.slot(comment.key)!!).widths, next.tick(next.slot(comment.key)!!).widths)
    }

    @Test fun thumbMinimumStillReachesBothTrackEnds() {
        val top = minimapThumb(MinimapViewport(0f, .01f), 500f, 8f, 44f)!!
        val bottom = minimapThumb(MinimapViewport(.99f, 1f), 500f, 8f, 44f)!!
        assertEquals(44f, top.height, .01f)
        assertEquals(8f, top.top, .01f)
        assertEquals(492f, bottom.top + bottom.height, .01f)
    }
}
