package dev.codexpocket.app.ui

import org.junit.Assert.*
import org.junit.Test

class ConversationMinimapInteractionTest {
    @Test fun dragCoalescesToTheLatestTargetAndWaitsFiftyMillis() {
        val coalescer = MinimapSeekCoalescer()
        val first = MinimapTarget("a", 0f)
        val middle = MinimapTarget("b", .2f)
        val last = MinimapTarget("c", .4f)
        assertEquals(first, coalescer.offer(first, 0))
        assertNull(coalescer.offer(middle, 10))
        assertNull(coalescer.offer(last, 49))
        assertEquals(1L, coalescer.waitMillis(49))
        assertNull(coalescer.take(49))
        assertEquals(last, coalescer.take(50))
        assertNull(coalescer.take(100))
    }

    @Test fun releaseFlushesThePendingTargetWithoutReapplyingACompletedSeek() {
        val coalescer = MinimapSeekCoalescer()
        coalescer.offer(MinimapTarget("a", 0f), 0)
        val last = MinimapTarget("z", .95f)
        assertNull(coalescer.offer(last, 10))
        assertEquals(last, coalescer.offer(last, 11, flush = true))
        // The finger did not move again. Re-measuring a fractional row on release can
        // change its pixel offset after text layout and visibly nudge the transcript.
        assertNull(coalescer.offer(last, 12, flush = true))
        assertNull(coalescer.take(50))
    }

    @Test fun aCompletedHiddenPreviewCanBePromotedToAnExpansionOnRelease() {
        val coalescer = MinimapSeekCoalescer()
        val hidden = MinimapTarget("hidden-question", 0f, "folded-process")
        assertEquals(hidden, coalescer.offer(hidden, 0))
        // The consumer explicitly invalidates a preview-only seek before committing
        // the hidden question, so duplicate suppression cannot swallow expansion.
        coalescer.abandon(hidden)
        assertEquals(hidden, coalescer.offer(hidden, 10, flush = true))
        assertNull(coalescer.offer(hidden, 11, flush = true))
    }

    @Test fun cancellationDropsThePendingSeek() {
        val coalescer = MinimapSeekCoalescer()
        coalescer.offer(MinimapTarget("a", 0f), 0)
        coalescer.offer(MinimapTarget("b", 0f), 10)
        coalescer.cancel()
        assertNull(coalescer.take(50))
    }

    @Test fun anInterruptedIdenticalFinalTargetIsRetriedOnRelease() {
        val coalescer = MinimapSeekCoalescer()
        val target = MinimapTarget("tall-row", .9f)
        assertEquals(target, coalescer.offer(target, 0))
        coalescer.abandon(target)
        assertEquals(target, coalescer.offer(target, 1, flush = true))
    }

    @Test fun aNewGestureCanSeekTheSamePositionAfterTheTranscriptWasScrolledElsewhere() {
        val coalescer = MinimapSeekCoalescer()
        val target = MinimapTarget("row", .3f)
        assertEquals(target, coalescer.offer(target, 0, flush = true))
        coalescer.cancel()
        assertEquals(target, coalescer.offer(target, 1, flush = true))
    }

    @Test fun longHistoryVisibilityStaysAfterFoldingAndResetsForANewConversation() {
        val latch = MinimapVisibilityLatch()
        assertFalse(latch.update("chat-a", 1.49f))
        assertTrue(latch.update("chat-a", 1.5f))
        assertTrue(latch.update("chat-a", .1f))
        assertTrue(latch.update("chat-a", Float.NaN))
        assertFalse(latch.update("chat-b", .1f))
    }

    @Test fun accessibilityOrHeldPreviewPreventsAutomaticCollapse() {
        assertTrue(minimapCanIdleCollapse(expanded = true, held = false, preview = false, focused = false))
        assertFalse(minimapCanIdleCollapse(expanded = true, held = true, preview = false, focused = false))
        assertFalse(minimapCanIdleCollapse(expanded = true, held = false, preview = true, focused = false))
        assertFalse(minimapCanIdleCollapse(expanded = true, held = false, preview = false, focused = true))
        assertFalse(minimapCanIdleCollapse(expanded = false, held = false, preview = false, focused = false))
    }
}
