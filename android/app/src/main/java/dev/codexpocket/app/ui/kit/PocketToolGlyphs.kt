package dev.codexpocket.app.ui.kit

import androidx.compose.ui.graphics.vector.ImageVector
import dev.codexpocket.app.ui.kit.GlyphMotion.pulse
import dev.codexpocket.app.ui.kit.GlyphMotion.smooth
import dev.codexpocket.app.ui.kit.GlyphMotion.window
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a tool's icon does for as long as the tool is running. Each is a loop: it starts and ends in
 * the pose of the static icon, so it can repeat without a seam and stop on the icon itself. Where a
 * tool has no loop of its own here, the motion its icon answers a tap with ([IconGlyphs]) repeats instead.
 */
internal object ToolGlyphs {
    private const val Sparkle = "M9.94 15.5A2 2 0 0 0 8.5 14.06l-6.13-1.58a.5.5 0 0 1 0-.96L8.5 9.94A2 2 0 0 0 9.94 8.5l1.58-6.13a.5.5 0 0 1 .96 0L14.06 8.5a2 2 0 0 0 1.44 1.44l6.13 1.58a.5.5 0 0 1 0 .96L15.5 14.06a2 2 0 0 0-1.44 1.44l-1.58 6.13a.5.5 0 0 1-.96 0z"
    private const val Book = "M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z"
    private const val Leaf = "M12 7a4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3"
    private const val Pencil = "M15 4.5l4.5 4.5M4.5 15.5l12-12a2.5 2.5 0 0 1 3.5 3.5l-12 12-4.5 1z"
    private const val Folder = "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2z"
    private const val Wrench = "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"

    /** Thinking: the star turns a quarter, which leaves it as it was, and draws breath as it goes. */
    val Thinking = MotionGlyph { t -> scale(1f - .22f * pulse(t)) { rotate(MotionGlyphs.turn(smooth(t), 90f)) { path(Sparkle) } } }

    /** Reading: a leaf turns from the right page over to the left. */
    val Read = MotionGlyph { t ->
        line(12f, 7f, 12f, 21f); path(Book)
        val across = cos(PI * window(t, .08f, .92f)).toFloat()
        // Edge-on the leaf is a hair; it never collapses to nothing.
        stretch(if (across in -.05f..0.05f) .05f else across, 1f) { path(Leaf) }
    }

    /** Writing: the pencil scribbles and the line it leaves grows under it. */
    val Write = MotionGlyph { t ->
        val busy = pulse(t)
        val wave = sin(6.0 * PI * t).toFloat() * busy
        move(1.1f * wave + 1.6f * busy, -.9f * wave) { path(Pencil) }
        if (busy > 0f) line(8.5f, 21f, 8.5f + 10f * window(t, .1f, .7f), 21f, pulse(window(t, .05f, 1f)))
    }

    /** Searching: the glass goes round over what it is looking through, and light crosses the lens. */
    val Search = MotionGlyph { t ->
        val angle = 2.0 * PI * t
        move(1.9f * sin(angle).toFloat(), 1.5f * (cos(angle).toFloat() - 1f)) {
            circle(10f, 10f, 6.5f); line(15f, 15f, 20.5f, 20.5f)
            val glint = pulse(t)
            if (glint > 0f) arc(10f, 10f, 3.7f, 200f + 300f * t, 55f, glint)
        }
    }

    /** Listing: the entries of the folder appear one under another, then clear. */
    val Listing = MotionGlyph { t ->
        path(Folder)
        val clear = 1f - window(t, .82f, 1f)
        for (row in 0..2) {
            val shown = window(t, .08f + row * .2f, .3f + row * .2f) * clear
            if (shown > 0f) line(6f, 10.5f + row * 3f, 6f + (if (row == 1) 6.5f else 9.5f) * shown, 10.5f + row * 3f, shown)
        }
    }

    /** A tool at work: two pulls of the wrench, each quick out and slow back. */
    val Turning = MotionGlyph { t ->
        fun pull(u: Float): Float = window(u, 0f, .28f) * (1f - window(u, .28f, 1f))
        rotate(-30f * (pull(t * 2f) + pull(t * 2f - 1f))) { path(Wrench) }
    }

    /** The loop for each icon name that has one of its own. */
    val byName: Map<String, MotionGlyph> = linkedMapOf("Sparkle" to Thinking, "BookOpen" to Read, "Edit" to Write, "Search" to Search,
        "Folder" to Listing, "Wrench" to Turning)

    /** What [icon] does while its tool runs, or null when it can only be moved as a whole. */
    fun working(icon: ImageVector): MotionGlyph? = byName[icon.name] ?: IconGlyphs.byName[icon.name]

    /** One pass of [icon]'s loop, in milliseconds: slower for the ones that tell a small story. */
    fun period(icon: ImageVector): Int = when (icon.name) {
        "BookOpen", "Folder", "Edit" -> 1900
        "Sparkle", "Globe" -> 1800
        else -> 1600
    }
}
