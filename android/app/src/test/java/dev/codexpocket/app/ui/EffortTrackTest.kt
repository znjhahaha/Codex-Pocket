package dev.codexpocket.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EffortTrackTest {
    @Test fun positionsSnapToNativeStopsAndClampOutsideTrack() {
        assertEquals(0, effortTrackIndex(-20f, 300f, 17f, 5))
        assertEquals(0, effortTrackIndex(17f, 300f, 17f, 5))
        assertEquals(2, effortTrackIndex(150f, 300f, 17f, 5))
        assertEquals(4, effortTrackIndex(340f, 300f, 17f, 5))
        assertEquals(0, effortTrackIndex(15f, 30f, 17f, 5))
        assertEquals(0, effortTrackIndex(140f, 300f, 17f, 1))
    }
}
