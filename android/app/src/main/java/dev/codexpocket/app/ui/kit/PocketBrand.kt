package dev.codexpocket.app.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.kit.GlyphMotion.easeOut
import dev.codexpocket.app.ui.kit.GlyphMotion.pulse
import dev.codexpocket.app.ui.kit.GlyphMotion.window
import kotlin.math.PI
import kotlin.math.cos

/**
 * Pocket's mark: a pocket holding a prompt. The launcher icon in `res/drawable` is drawn from the
 * same outlines on the same 108-unit grid, so the mark inside the app is the icon on the home screen.
 */
internal object PocketBrand {
    const val Pocket = "M32 37a6 6 0 0 1 6-6h32a6 6 0 0 1 6 6v24c0 5-2.6 8.6-7 11l-11 6.2a8 8 0 0 1-8 0l-11-6.2c-4.4-2.4-7-6-7-11z"
    const val Hem = "M32 39.5h44"
    const val Chevron = "M44 45l9 8.5-9 8.5"
    const val Cursor = "M57.5 62h8.5"
    /** The icon's own colours. They are the same in light and dark: an icon does not change with the theme. */
    val Night = Color(0xFF121017)
    val Dusk = Color(0xFF2E2547)
    val Glow = Color(0xFF9A78E6)
    val Paper = Color(0xFFFFFFFF)
    val PaperShade = Color(0xFFE4DDF2)
    val Ink = Color(0xFF1B1724)
    val Violet = Color(0xFF8257D8)
}

/**
 * The mark in full colour. [progress] assembles it: the tile grows, the pocket drops in, the prompt
 * is drawn and the cursor arrives; at 1 it is the icon. [cursor] dims the cursor so it can blink.
 * Without [tile] only the pocket is drawn, for places that supply their own ground.
 */
@Composable
internal fun PBrandMark(modifier: Modifier = Modifier.size(72.dp), progress: () -> Float = { 1f }, cursor: () -> Float = { 1f },
    tile: Boolean = true, contentDescription: String? = null) {
    val measure = remember { PathMeasure() }
    val part = remember { Path() }
    val described = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.then(described)) {
        val t = progress().coerceIn(0f, 1f)
        // The middle 72 units are what a launcher shows of an adaptive icon; the mark fills its box the same way.
        val unit = size.minDimension / 72f
        val pocket = GlyphScope.parsed(PocketBrand.Pocket)
        withTransform({ translate((size.width - 108f * unit) / 2f, (size.height - 108f * unit) / 2f); scale(unit, unit, Offset.Zero) }) {
            if (tile) {
                val grown = .72f + .28f * easeOut(window(t, 0f, .34f))
                scale(grown, Offset(54f, 54f)) {
                    val alpha = window(t, 0f, .16f)
                    drawRoundRect(Brush.linearGradient(listOf(PocketBrand.Dusk, PocketBrand.Night), Offset(20f, 16f), Offset(88f, 92f)),
                        Offset(18f, 18f), Size(72f, 72f), CornerRadius(19f), alpha = alpha)
                    drawRoundRect(Brush.radialGradient(listOf(PocketBrand.Glow.copy(alpha = .42f), Color.Transparent), Offset(78f, 24f), 52f),
                        Offset(18f, 18f), Size(72f, 72f), CornerRadius(19f), alpha = alpha)
                }
            }
            val landed = easeOut(window(t, .14f, .56f))
            val settle = 1f + .05f * pulse(window(t, .5f, .8f))
            translate(0f, -16f * (1f - landed)) { scale(settle, Offset(54f, 78f)) {
                val there = window(t, .14f, .34f)
                if (tile) translate(0f, 2.2f) { drawPath(pocket, Color.Black, alpha = .28f * there) }
                drawPath(pocket, Brush.verticalGradient(listOf(PocketBrand.Paper, PocketBrand.PaperShade), 31f, 79f), alpha = there)
                clipPath(pocket) { drawPath(GlyphScope.parsed(PocketBrand.Hem), PocketBrand.Ink.copy(alpha = .1f * landed), style = Stroke(1.6f)) }
                val pen = Stroke(5.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val drawn = window(t, .5f, .82f)
                if (drawn > 0f) {
                    measure.setPath(GlyphScope.parsed(PocketBrand.Chevron), false)
                    part.rewind(); measure.getSegment(0f, measure.length * drawn, part, true)
                    drawPath(part, PocketBrand.Ink, style = pen)
                }
                val lit = window(t, .78f, .94f) * cursor().coerceIn(0f, 1f)
                if (lit > 0f) drawPath(GlyphScope.parsed(PocketBrand.Cursor), PocketBrand.Violet.copy(alpha = lit), style = pen)
            } }
        }
    }
}

/** The mark as a line glyph on the 24-unit icon grid, for loaders and quiet places. */
internal object BrandGlyphs {
    const val Pocket = "M5 6.5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v8c0 1.7-.9 2.9-2.3 3.7l-3.4 1.9a2.6 2.6 0 0 1-2.6 0l-3.4-1.9C5.9 17.4 5 16.2 5 14.5z"
    const val Chevron = "M8.7 9.2l2.8 2.7-2.8 2.7"
    private fun blink(t: Float): Float = (.5 + .5 * cos(4.0 * PI * t.coerceIn(0f, 1f))).toFloat()

    /** At rest. */
    val Mark = MotionGlyph { path(Pocket); path(Chevron); line(13.3f, 14.6f, 15.9f, 14.6f) }
    /** Working: the prompt steps forward and the cursor blinks twice, over and over. */
    val Working = MotionGlyph { t ->
        path(Pocket); move(1.2f * pulse(window(t, 0f, .5f)), 0f) { path(Chevron) }; line(13.3f, 14.6f, 15.9f, 14.6f, blink(t))
    }
    /** Arriving: the pocket is drawn, then the prompt, then the cursor. */
    val Entrance = MotionGlyph { t ->
        trim(Pocket, window(t, 0f, .62f)); trim(Chevron, window(t, .5f, .86f))
        val lit = window(t, .8f, 1f); if (lit > 0f) line(13.3f, 14.6f, 15.9f, 14.6f, lit)
    }
}

/** Pocket at work: the small sign that something of the app's own is loading. */
@Composable
internal fun PBrandLoader(modifier: Modifier = Modifier.size(18.dp), tint: Color = Pocket.colors.inkMuted, active: Boolean = true) {
    PLoopGlyph(BrandGlyphs.Working, active, modifier, tint, millis = 1500)
}
