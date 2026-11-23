package dev.codexpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.remember
import dev.codexpocket.app.ui.kit.*

/**
 * The assistant's colours, read from Pocket's shared tokens so they follow the selected appearance.
 * Surfaces are the app's neutrals; the one green is the shared positive tone, used for the assistant's
 * mark, its readiness and work that is moving.
 */
@Immutable
data class AssistantPalette(
    val background: Color,
    val surface: Color,
    val outgoing: Color,
    val onOutgoing: Color,
    val accent: Color,
    val onAccent: Color,
    val muted: Color,
    val border: Color,
    /** A quiet fill behind the accent: the mark's tile, a running badge. */
    val accentSoft: Color = Color.Unspecified,
)

@Composable
fun assistantPalette(): AssistantPalette {
    val c = Pocket.colors
    return AssistantPalette(c.bg, c.sunken, c.fillStrong, c.ink, c.positive, c.bg, c.inkMuted, c.line, c.positiveSoft)
}

/** Decorative identity only. The surrounding chat row or control supplies its semantics. */
@Composable
fun AssistantAvatar(modifier: Modifier = Modifier, size: Dp = 32.dp, tint: Color = Color.Unspecified, lively: Boolean = false) {
    val palette = assistantPalette()
    Box(modifier.size(size).background(palette.outgoing, RoundedCornerShape(30)), contentAlignment = Alignment.Center) {
        AssistantFace(Modifier.fillMaxSize(.8f), tint = if (tint != Color.Unspecified) tint else palette.accent, lively = lively)
    }
}

/**
 * The assistant's face, alive: it blinks and glances while [lively], narrows its eyes into a smile
 * while [held] under a finger, and hops with a few sparks each time [cheer] changes. At rest it is
 * [AssistantIcons.Mark]. Only the drawing moves; its box and whatever surrounds it stay put.
 */
@Composable
internal fun AssistantFace(modifier: Modifier = Modifier.size(24.dp), tint: Color = Color.Unspecified, lively: Boolean = false,
    held: Boolean = false, cheer: Any? = null) {
    val ink = if (tint != Color.Unspecified) tint else Pocket.ink
    val idle = rememberGlyphLoop(lively, 4600)
    val hop = rememberGlyphPulse(cheer, 760)
    val squint = rememberGlyphToggle(held)
    val pen = remember { GlyphScope() }
    // The loop hands out a different state when it starts or stops, so the glyph is rebuilt around whichever is current.
    val glyph = remember(idle, hop, squint) { MotionGlyph { with(AssistantGlyphs) { face(idle.value, hop.value, squint.value) } } }
    Canvas(modifier) { drawGlyph(pen, glyph, 1f, ink) }
}

/** The assistant's mark in motion. Every curve is a function of progress, so each pose can be checked on its own. */
internal object AssistantGlyphs {
    /** How shut an eye is at [t] for a blink centred on [at]. */
    private fun shut(t: Float, at: Float, half: Float = .028f): Float = GlyphMotion.smooth(1f - kotlin.math.abs(t - at) / half)

    /**
     * [idle] runs 0..1 over a few seconds: one blink, a glance to the side, a double blink. [hop] runs 0..1 once
     * per tap: up and down with sparks. [held] is 0..1 while a finger rests on the face. All at rest: 1, 1, 0.
     */
    fun GlyphScope.face(idle: Float, hop: Float, held: Float) {
        val up = GlyphMotion.pulse(GlyphMotion.window(hop, 0f, .5f))
        val land = GlyphMotion.pulse(GlyphMotion.window(hop, .5f, .82f))
        val glad = maxOf(held.coerceIn(0f, 1f), GlyphMotion.window(hop, .04f, .2f) * (1f - GlyphMotion.window(hop, .7f, .92f)))
        val open = 1f - maxOf(shut(idle, .32f), shut(idle, .84f), shut(idle, .9f))
        val glance = .8f * (GlyphMotion.window(idle, .5f, .56f) - GlyphMotion.window(idle, .68f, .74f))
        move(0f, -1.7f * up) { stretch(1f + .05f * land, 1f - .06f * land, 12f, 20.4f) {
            path(BrandGlyphs.Pocket)
            if (glad < 1f) for (x in floatArrayOf(9.4f, 14.6f)) {
                val half = .12f + .9f * open
                line(x + glance, 10.6f - half, x + glance, 10.6f + half, 1f - glad)
            }
            if (glad > 0f) { path("M8.3 11.3q1.1-1.7 2.2 0", glad); path("M13.5 11.3q1.1-1.7 2.2 0", glad) }
            // One outline, widened and deepened: a path made per frame would never leave the cache.
            stretch(1f + .22f * glad, 1f + .7f * glad, 12f, 14.3f) { path("M9.6 14.3q2.4 1.9 4.8 0") }
        } }
        val spark = GlyphMotion.pulse(GlyphMotion.window(hop, .12f, .8f))
        if (spark > 0f) {
            val out = GlyphMotion.easeOut(GlyphMotion.window(hop, .12f, .6f))
            line(19.4f + .6f * out, 4.6f - .6f * out, 20.2f + 1.3f * out, 3.8f - 1.3f * out, spark)
            line(20.2f + .7f * out, 7.4f, 21f + 1.6f * out, 7.4f, spark)
            line(16.6f, 3.8f - .7f * out, 16.6f, 3f - 1.6f * out, spark)
        }
    }
}

/** Original assistant artwork: Pocket's own pocket with a face, and a matching, compact outline set. */
object AssistantIcons {
    val Mark: ImageVector by lazy {
        icon(
            "Mark",
            "M5 6.5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v8c0 1.7-.9 2.9-2.3 3.7l-3.4 1.9a2.6 2.6 0 0 1-2.6 0l-3.4-1.9C5.9 17.4 5 16.2 5 14.5z",
            "M9.4 9.58V11.62M14.6 9.58V11.62",
            "M9.6 14.3q2.4 1.9 4.8 0",
        )
    }
    val Tasks: ImageVector by lazy {
        icon("Tasks", "M3.5 7L5.5 9L9 5M12.5 7H20.5", "M3.5 16L5.5 18L9 14M12.5 16H20.5")
    }
    val Memory: ImageVector by lazy {
        icon(
            "Memory",
            "M7 3.5H19V20.5H7C5.6 20.5 4.5 19.4 4.5 18V6C4.5 4.6 5.6 3.5 7 3.5Z",
            "M4.5 18C4.5 16.6 5.6 15.5 7 15.5H19",
            "M10 3.5V10L12.5 8.5L15 10V3.5",
        )
    }
    val Schedule: ImageVector by lazy {
        icon(
            "Schedule",
            "M6 5H18C19.4 5 20.5 6.1 20.5 7.5V18C20.5 19.4 19.4 20.5 18 20.5H6C4.6 20.5 3.5 19.4 3.5 18V7.5C3.5 6.1 4.6 5 6 5Z",
            "M8 3V7M16 3V7M3.5 9H20.5",
            "M15.5 14.5A3.5 3.5 0 1 1 8.5 14.5A3.5 3.5 0 1 1 15.5 14.5Z",
            "M12 12.5V14.5L13.5 15.5",
        )
    }
    val Settings: ImageVector by lazy {
        icon(
            "Settings",
            "M3.5 7.5H7M12 7.5H20.5M3.5 16.5H12M17 16.5H20.5",
            "M12 7.5A2.5 2.5 0 1 1 7 7.5A2.5 2.5 0 1 1 12 7.5Z",
            "M17 16.5A2.5 2.5 0 1 1 12 16.5A2.5 2.5 0 1 1 17 16.5Z",
        )
    }

    private fun icon(name: String, vararg paths: String, autoMirror: Boolean = false): ImageVector {
        val builder = ImageVector.Builder("Assistant$name", 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror)
        paths.forEach { path ->
            builder.addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
