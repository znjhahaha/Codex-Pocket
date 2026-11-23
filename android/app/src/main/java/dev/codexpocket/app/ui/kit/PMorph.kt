package dev.codexpocket.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Where a page grows out of, in the coordinates of the page that holds the source. */
internal class PMorphBounds(val rect: Rect, val radius: Float)

/** One page of a stage, as the things on it see it: where the page is, and the stage's sources. */
internal class PMorphPage(val sources: PMorphSources) {
    var coordinates: LayoutCoordinates? = null
}

internal class PMorphSource(val page: PMorphPage, var radius: Float) {
    var coordinates: LayoutCoordinates? = null
}

/**
 * The rows, cards and buttons on a stage that a deeper page can open out of. Bounds are measured
 * when asked for, against the page the source sits on and beneath that page's own transform, so a
 * source that has scrolled or been re-sorted is found where it is now.
 */
internal class PMorphSources {
    private val entries = HashMap<Any, ArrayList<PMorphSource>>()

    fun add(key: Any, source: PMorphSource) { entries.getOrPut(key) { ArrayList(1) }.add(source) }
    fun remove(key: Any, source: PMorphSource) { entries[key]?.let { it.remove(source); if (it.isEmpty()) entries.remove(key) } }

    /** The first source for [key] that is laid out and at least partly on its page; null when there is none. */
    fun bounds(key: Any): PMorphBounds? {
        val sources = entries[key] ?: return null
        for (index in sources.indices) {
            val source = sources[index]
            val page = source.page.coordinates?.takeIf { it.isAttached } ?: continue
            val own = source.coordinates?.takeIf { it.isAttached } ?: continue
            val rect = page.localBoundingBoxOf(own, clipBounds = false)
            if (rect.width < 1f || rect.height < 1f) continue
            if (rect.bottom <= 0f || rect.top >= page.size.height || rect.right <= 0f || rect.left >= page.size.width) continue
            return PMorphBounds(rect, source.radius)
        }
        return null
    }
}

internal val LocalPMorphPage = staticCompositionLocalOf<PMorphPage?> { null }

/**
 * Marks this element as what the page named [key] opens out of. A [PStage] whose `source` names the
 * same key grows that page from here and folds it back here, instead of sliding it in from the edge.
 * Outside a stage, or with a null key, this does nothing.
 */
@Composable
internal fun Modifier.pocketMorphSource(key: Any?, radius: Dp = 16.dp): Modifier {
    val page = LocalPMorphPage.current
    if (key == null || page == null) return this
    val px = with(LocalDensity.current) { radius.toPx() }
    val source = remember(page) { PMorphSource(page, px) }
    source.radius = px
    DisposableEffect(page, key, source) {
        page.sources.add(key, source)
        onDispose { page.sources.remove(key, source) }
    }
    return onPlaced { source.coordinates = it }
}

/** A page opening out of a source: what it grows from, kept up to date while the source can be found. */
internal class PStageMorph(val key: Any, bounds: PMorphBounds) {
    var rect: Rect = bounds.rect
        private set
    var radius: Float = bounds.radius
        private set

    /** Follows the source if it has moved; keeps the last place it was seen when it is gone. */
    fun resolve(sources: PMorphSources): Rect {
        sources.bounds(key)?.let { rect = it.rect; radius = it.radius }
        return rect
    }
}

/**
 * The frame of a morphing page when it is [grown] of the way from [source] to the whole stage.
 * Every edge moves in a straight line, so the frame never leaves the stage or turns inside out.
 */
internal fun pocketMorphFrame(source: Rect, width: Float, height: Float, grown: Float): Rect {
    val g = grown.coerceIn(0f, 1f)
    fun mix(from: Float, to: Float) = from + (to - from) * g
    return Rect(mix(source.left, 0f), mix(source.top, 0f), mix(source.right, width), mix(source.bottom, height))
}

/** The part of a morphing page that shows: its full width, [height] from the top, rounded by [radius]. */
internal data class PMorphShape(val height: Float, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(0f, 0f, size.width, height.coerceIn(0f, size.height), CornerRadius(radius.coerceAtLeast(0f))))
}
