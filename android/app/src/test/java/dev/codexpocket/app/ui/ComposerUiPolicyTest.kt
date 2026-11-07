package dev.codexpocket.app.ui

import org.junit.Assert.*
import org.junit.Test

class ComposerUiPolicyTest {
    @Test fun historyCompactsOnlyWhenEveryInteractionLockIsReleased() {
        assertTrue(composerIsCompact(true, false, false, false, false))
        assertFalse(composerIsCompact(false, false, false, false, false))
        assertFalse(composerIsCompact(true, true, false, false, false))
        assertFalse(composerIsCompact(true, false, true, false, false))
        assertFalse(composerIsCompact(true, false, false, true, false))
        assertFalse(composerIsCompact(true, false, false, false, true))
    }

    @Test fun capsuleShrinksItsWidthAndCapsTabletWidth() {
        assertEquals(319.2f, composerWidthDp(380f, true), .01f)
        assertEquals(480f, composerWidthDp(1000f, true), .01f)
        assertEquals(380f, composerWidthDp(380f, false), .01f)
        assertEquals(720f, composerWidthDp(1000f, false), .01f)
    }
    @Test fun firstTapWaitsForImeButDismissedImeReleasesTheHistoryFocusLock() {
        assertFalse(composerShouldReleaseFocus(true, false, false, true, false, false, false, false))
        assertFalse(composerIsCompact(true, true, false, false, true))
        assertTrue(composerShouldReleaseFocus(true, false, true, true, false, false, false, false))
        assertTrue(composerIsCompact(true, false, false, false, false))
    }
    @Test fun metadataBlocksUntilClosedThenHistoryCompactsDespiteRestoredFocus() {
        assertFalse(composerIsCompact(true, false, false, true, false))
        assertTrue(composerShouldReleaseFocus(true, false, false, true, false, false, false, false, true))
        assertTrue(composerIsCompact(true, false, false, false, false))
        assertFalse(composerIsCompact(false, false, false, false, false))
    }
}
