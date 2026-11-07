package dev.codexpocket.app.ui

import org.junit.Assert.*
import org.junit.Test

class ComposerFocusTest {
    @Test fun firstTapAndVisibleKeyboardAreLegitimateEditingLocks() {
        assertFalse(composerShouldReleaseFocus(true, false, false, false, false, false, false, false))
        assertFalse(composerShouldReleaseFocus(true, true, true, true, false, false, false, false))
    }
    @Test fun historyGestureAndKeyboardDismissalReleaseStaleFocus() {
        assertTrue(composerShouldReleaseFocus(true, false, false, true, true, false, false, false))
        assertTrue(composerShouldReleaseFocus(true, true, true, true, true, false, false, false))
        assertTrue(composerShouldReleaseFocus(true, false, true, false, false, false, false, false))
        assertTrue(composerIsCompact(true, false, false, false, false))
    }
    @Test fun menusFullscreenAndExplicitPreviewFocusAreTemporaryLocks() {
        assertFalse(composerShouldReleaseFocus(true, false, true, true, true, true, false, false))
        assertFalse(composerShouldReleaseFocus(true, false, true, true, true, false, true, false))
        assertFalse(composerShouldReleaseFocus(true, false, true, true, true, false, false, true))
        assertTrue(composerShouldReleaseFocus(true, false, true, true, false, false, false, false))
    }
    @Test fun restoredFocusAfterMetadataCloseDoesNotDependOnRememberingImeVisibility() {
        assertTrue(composerShouldReleaseFocus(true, false, false, true, false, false, false, false, true))
        assertFalse(composerShouldReleaseFocus(true, false, false, true, false, true, false, false, true))
        assertFalse(composerShouldReleaseFocus(true, false, false, true, false, false, false, true, true))
        assertTrue(composerIsCompact(true, false, false, false, false))
    }
    @Test fun historyThresholdAfterScrollEndsStillReleasesImmediateFocus() {
        assertFalse(composerShouldReleaseFocus(true, false, false, false, false, false, false, false, historyGestureSeen = true))
        assertTrue(composerShouldReleaseFocus(true, false, false, true, false, false, false, false, historyGestureSeen = true))
        assertFalse(composerShouldReleaseFocus(true, true, true, true, false, false, false, false))
        assertFalse(composerShouldReleaseFocus(true, false, false, true, false, false, false, true, historyGestureSeen = true))
    }
}
