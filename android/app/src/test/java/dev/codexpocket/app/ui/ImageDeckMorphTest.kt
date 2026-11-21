package dev.codexpocket.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ImageDeckMorphTest {
    @Test fun cardsFanThenSettleIntoTheSameGridInBothAlignments() {
        val geometry = multiImageGeometry(4, 240.dp)
        for (end in listOf(false, true)) {
            val closed = imageDeckPose(1, 4, geometry, end, 0f)
            val fanned = imageDeckPose(1, 4, geometry, end, .3f)
            val settled = imageDeckPose(1, 4, geometry, end, 1f)
            assertEquals(geometry.card, closed.size)
            assertTrue(fanned.rotation > closed.rotation)
            assertTrue(fanned.size < closed.size && fanned.size > settled.size)
            assertNotEquals(closed.x, fanned.x)
            assertEquals(geometry.cell, settled.size)
            assertEquals(0f, settled.rotation, .001f)
            assertEquals(108.dp, settled.x)
            assertEquals(0.dp, settled.y)
        }
    }

    @Test fun everyFrameStaysInsideTheReservedFrameIncludingNarrowAndPartialRows() {
        for (count in listOf(2, 3, 4, 5, 6, 9, 12)) for (width in listOf(144.dp, 240.dp)) {
            val geometry = multiImageGeometry(count, width)
            for (end in listOf(false, true)) for (index in 0 until minOf(count, 9)) for (step in 0..100) {
                val pose = imageDeckPose(index, count, geometry, end, step / 100f)
                val angle = Math.toRadians(pose.rotation.toDouble())
                val inset = pose.size.value * (abs(cos(angle)) + abs(sin(angle)) - 1) / 2
                assertTrue(pose.x.value - inset >= -.01)
                assertTrue(pose.y.value - inset >= -.01)
                assertTrue(pose.x.value + pose.size.value + inset <= geometry.width.value + .01)
                assertTrue(pose.y.value + pose.size.value + inset <= geometry.height.value + .01)
            }
        }
        val geometry = multiImageGeometry(5, 240.dp)
        assertEquals(geometry.width - geometry.cell, imageDeckPose(4, 5, geometry, true, 1f).x)
    }

    @Test fun cardStaggerAndReverseUseOneContinuousPathWithoutAnOpacitySwap() {
        val geometry = multiImageGeometry(9, 240.dp)
        assertTrue(imageDeckPose(0, 9, geometry, false, .1f).gridFraction > 0f)
        assertEquals(0f, imageDeckPose(8, 9, geometry, false, .1f).gridFraction, .001f)
        val forward = (0..100).map { imageDeckPose(2, 9, geometry, false, it / 100f) }
        val reverse = (100 downTo 0).map { imageDeckPose(2, 9, geometry, false, it / 100f) }
        assertEquals(forward, reverse.reversed())
        forward.zipWithNext().forEach { (a, b) ->
            assertTrue(abs(a.x.value - b.x.value) < 8f)
            assertTrue(abs(a.y.value - b.y.value) < 8f)
            assertTrue(abs(a.rotation - b.rotation) < 1f)
        }
    }
}
