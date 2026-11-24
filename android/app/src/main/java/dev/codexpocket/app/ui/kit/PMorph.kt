package dev.codexpocket.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
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
    /** What the element last drew, for the page that opens out of it to show on the way. Null where it is not kept. */
    var picture: GraphicsLayer? = null
    /** True while a page is opening out of this element or folding back into it: it is in that page's frame, not here. */
    var lifted by mutableStateOf(false)
}

/**
 * The rows, cards and buttons on a stage that a deeper page can open out of. Bounds are measured
 * when asked for, against the page the source sits on and beneath that page's own transform, so a
 * source that has scrolled or been re-sorted is found where it is now.
 */
internal class PMorphSources {
    private val entries = HashMap<Any, ArrayList<PMorphSource>>()
    private val lifted = HashSet<Any>()

    fun add(key: Any, source: PMorphSource) { source.lifted = key in lifted; entries.getOrPut(key) { ArrayList(1) }.add(source) }
    /** Takes every element named [key] out of its page, or puts it back. One that appears meanwhile is taken out as it arrives. */
    fun lift(key: Any, up: Boolean) {
        if (up) lifted.add(key) else lifted.remove(key)
        entries[key]?.forEach { it.lifted = up }
    }
    fun remove(key: Any, source: PMorphSource) { entries[key]?.let { it.remove(source); if (it.isEmpty()) entries.remove(key) } }

    /** The first source for [key] that is laid out and at least partly on its page; null when there is none. */
    fun bounds(key: Any): PMorphBounds? = find(key)?.second

    /** The element [bounds] measures, with where it is. */
    fun find(key: Any): Pair<PMorphSource, PMorphBounds>? {
        val sources = entries[key] ?: return null
        for (index in sources.indices) {
            val source = sources[index]
            val page = source.page.coordinates?.takeIf { it.isAttached } ?: continue
            val own = source.coordinates?.takeIf { it.isAttached } ?: continue
            val rect = page.localBoundingBoxOf(own, clipBounds = false)
            if (rect.width < 1f || rect.height < 1f) continue
            if (rect.bottom <= 0f || rect.top >= page.size.height || rect.right <= 0f || rect.left >= page.size.width) continue
            return source to PMorphBounds(rect, source.radius)
        }
        return null
    }
}

internal val LocalPMorphPage = staticCompositionLocalOf<PMorphPage?> { null }

/**
 * Marks this element as what the page named [key] opens out of. A [PStage] whose `source` names the
 * same key grows that page from here and folds it back here, instead of sliding it in from the edge.
 * Outside a stage, or with a null key, this does nothing.
 *
 * While the page is on its way the element is not drawn where it stands: it is the thing that is
 * moving. Its [picture] rides at the head of the page's frame and gives way to the page, so the
 * element is seen to become the page and the page to become the element again. Turn [picture] off
 * for an element that samples what is behind it, which cannot be drawn anywhere but in its place:
 * it then stays where it is, and the frame's ground closes over it and opens off it again.
 */
@Composable
internal fun Modifier.pocketMorphSource(key: Any?, radius: Dp = 16.dp, picture: Boolean = true): Modifier {
    val page = LocalPMorphPage.current
    if (key == null || page == null) return this
    val px = with(LocalDensity.current) { radius.toPx() }
    val source = remember(page) { PMorphSource(page, px) }
    source.radius = px
    val drawn = if (picture) rememberGraphicsLayer() else null
    DisposableEffect(page, key, source, drawn) {
        source.picture = drawn
        page.sources.add(key, source)
        onDispose { page.sources.remove(key, source); source.picture = null }
    }
    return onPlaced { source.coordinates = it }.drawWithContent {
        // With no picture to carry, the element stays where it is and the frame's ground closes over it.
        if (drawn == null) { drawContent(); return@drawWithContent }
        drawn.record { this@drawWithContent.drawContent() }
        if (!source.lifted) drawLayer(drawn)
    }
}

/** A page opening out of a source: what it grows from, kept up to date while the source can be found. */
internal class PStageMorph(val key: Any, bounds: PMorphBounds) {
    var rect: Rect = bounds.rect
        private set
    var radius: Float = bounds.radius
        private set

    /** What the source last drew, while it can still be found. */
    var picture: GraphicsLayer? = null
        private set

    /** Follows the source if it has moved; keeps the last place it was seen when it is gone. */
    fun resolve(sources: PMorphSources): Rect {
        val found = sources.find(key)
        picture = found?.first?.picture?.takeUnless { it.isReleased }
        found?.second?.let { rect = it.rect; radius = it.radius }
        return rect
    }
}

/** How much of the page itself shows when its frame is [grown] of the way out of its source. */
internal fun pocketMorphPage(grown: Float): Float = GlyphMotion.window(grown, .1f, .46f)

/** How solid the frame's own ground is. It is clear while the frame is still the element, and solid well before the page shows. */
internal fun pocketMorphGround(grown: Float): Float = GlyphMotion.window(grown, 0f, .14f)

/** How much of the source's own picture shows at the head of the frame. It has gone before the page is whole. */
internal fun pocketMorphPicture(grown: Float): Float = 1f - GlyphMotion.window(grown, .04f, .38f)

/**
 * Draws [picture], which is [source] wide, across the head of a page [width] wide. The page's own
 * layer is already fitted to its frame, so this one factor keeps the picture exactly over the
 * element at the start and as wide as the frame all the way out.
 */
internal fun DrawScope.drawMorphPicture(picture: GraphicsLayer, source: Rect, width: Float) {
    if (source.width < 1f || picture.isReleased) return
    val fit = width / source.width
    scale(fit, fit, Offset.Zero) { drawLayer(picture) }
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
