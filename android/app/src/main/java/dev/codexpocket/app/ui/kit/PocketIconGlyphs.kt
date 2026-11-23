package dev.codexpocket.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.codexpocket.app.ui.kit.GlyphMotion.easeOut
import dev.codexpocket.app.ui.kit.GlyphMotion.pulse
import dev.codexpocket.app.ui.kit.GlyphMotion.smooth
import dev.codexpocket.app.ui.kit.GlyphMotion.swing
import dev.codexpocket.app.ui.kit.GlyphMotion.window
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * What each static icon does when its action lands, drawn from the icon's own outline so the motion
 * starts and ends in exactly the pose the icon rests in. Keys are the names the icons are drawn
 * under in `PocketIcons`; an icon with no entry here answers with its [PIconMotion] instead.
 */
internal object IconGlyphs {
    /** A stroke that is taken away and drawn again: whole before the motion starts and once it has ended. */
    private fun again(t: Float, start: Float, end: Float): Float = if (t <= 0f) 1f else window(t, start, end)
    /** A stroke that draws back by [depth] of its length and returns. */
    private fun retract(t: Float, start: Float = 0f, end: Float = 1f, depth: Float = .75f): Float = 1f - depth * pulse(window(t, start, end))
    /** Two blinks: open at both ends, shut at a quarter and three quarters. */
    private fun blink(t: Float): Float = (.5 + .5 * cos(4.0 * PI * t.coerceIn(0f, 1f))).toFloat()

    private const val Ring = "M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0z"
    private const val Frame = "M5.5 4.5h13a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2z"

    /** Three faders slide along their tracks, one after another. */
    val Settings = MotionGlyph { t ->
        val a = 3f * pulse(window(t, 0f, .7f)); val b = -4.5f * pulse(window(t, .15f, .85f)); val c = 4f * pulse(window(t, .3f, 1f))
        line(5f, 4f, 5f, 9f + a); move(0f, a) { path("M2.5 9h5v5h-5z") }; line(5f, 14f + a, 5f, 20f)
        line(12f, 4f, 12f, 13f + b); move(0f, b) { path("M9.5 13h5v5h-5z") }; line(12f, 18f + b, 12f, 20f)
        line(19f, 4f, 19f, 6f + c); move(0f, c) { path("M16.5 6h5v5h-5z") }; line(19f, 11f + c, 19f, 20f)
    }
    /** The pencil rocks on its point and leaves a line. */
    val Edit = MotionGlyph { t ->
        val mark = pulse(window(t, .1f, .95f))
        rotate(-9f * swing(t, 1.5f), 4.5f, 19.5f) { path("M15 4.5l4.5 4.5M4.5 15.5l12-12a2.5 2.5 0 0 1 3.5 3.5l-12 12-4.5 1z") }
        if (mark > 0f) line(9f, 21f, 9f + 9f * window(t, .1f, .6f), 21f, mark)
    }
    /** The two sheets part and come back together. */
    val Copy = MotionGlyph { t ->
        val apart = pulse(t)
        move(-1.3f * apart, -1.3f * apart) { path("M15 4H5a2 2 0 0 0-2 2v10") }
        move(.9f * apart, .9f * apart) { path("M9 8h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z") }
    }
    /** The page's two lines are written again. */
    val Document = MotionGlyph { t ->
        path("M7 3.5h7l5.5 5.5v9.5a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2z"); path("M14 3.5V9h5.5")
        line(8f, 13f, 8f + 8f * retract(t, 0f, .7f), 13f); line(8f, 16.5f, 8f + 5f * retract(t, .25f, 1f), 16.5f)
    }
    /** The folder's back lifts as its front drops open. */
    val FolderOpen = MotionGlyph { t ->
        val open = pulse(t)
        move(0f, -.9f * open) { path("M4 9V6a2 2 0 0 1 2-2h4l2 3h6a2 2 0 0 1 2 2") }
        move(0f, 1.1f * open) { path("M5 10h16l-2 9H3L1.5 10H5") }
    }
    /** The skyline is traced again while the sun comes up. */
    val Image = MotionGlyph { t ->
        path(Frame); trim("M3.5 16l5-5 5 5 3-3 4 4", retract(t, 0f, .8f, .65f))
        move(0f, -1.4f * pulse(window(t, .2f, 1f))) { line(15.5f, 8.5f, 15.51f, 8.5f) }
    }
    /** The shutter closes and opens. */
    val Camera = MotionGlyph { t ->
        val shut = pulse(window(t, 0f, .6f))
        move(0f, .6f * shut) { path("M7 6l2-2h6l2 2h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z") }
        scale(1f - .42f * shut, 12f, 13f) { circle(12f, 13f, 4f) }
    }
    /** The prompt steps forward and the cursor blinks twice. */
    val Terminal = MotionGlyph { t ->
        path(Frame); move(1.3f * pulse(window(t, 0f, .5f)), 0f) { path("M7 9l3 3-3 3") }; line(13f, 15f, 17f, 15f, blink(t))
    }
    val Prompt = MotionGlyph { t -> move(1.4f * pulse(window(t, 0f, .5f)), 0f) { path("M5 7l5 5-5 5") }; line(13f, 17f, 19f, 17f, blink(t)) }
    /** The brackets open around a slash that tips. */
    val Code = MotionGlyph { t ->
        val open = pulse(t)
        move(-1.5f * open, 0f) { path("M8 6l-5 6 5 6") }; move(1.5f * open, 0f) { path("M16 6l5 6-5 6") }
        rotate(10f * swing(t, 1.5f)) { line(13f, 4f, 11f, 20f) }
    }
    /** Each item is ticked off in turn. */
    val Checklist = MotionGlyph { t ->
        trim("M3.5 7l2 2 3-4", again(t, 0f, .4f)); line(12f, 7f, 20f, 7f)
        trim("M3.5 16l2 2 3-4", again(t, .35f, .8f)); line(12f, 16f, 20f, 16f)
    }
    /** The dot of the i hops. */
    val Info = MotionGlyph { t -> path(Ring); line(12f, 10.5f, 12f, 16.5f); move(0f, -1.3f * swing(t, 1.5f)) { line(12f, 7.5f, 12.01f, 7.5f) } }
    /** The ring closes, then the mark is drawn inside it. */
    val CheckCircle = MotionGlyph { t -> arc(12f, 12f, 8.5f, -90f, 360f * again(t, 0f, .6f)); trim("M8 12l3 3 5-6", again(t, .45f, 1f)) }
    val Check = MotionGlyph { t -> scale(1f + .12f * pulse(window(t, .55f, 1f))) { trim("M5 12l4.5 4.5L19 7", again(t, 0f, .8f)) } }
    val CheckCheck = MotionGlyph { t -> trim("M2 12l4 4 9-9", again(t, 0f, .5f)); trim("M11 15l2 2 9-9", again(t, .35f, .85f)) }
    /** The arrow kicks forward inside its ring. */
    val Play = MotionGlyph { t -> path(Ring); move(1.5f * pulse(window(t, 0f, .65f)), 0f) { path("M10 8l6 4-6 4z") } }
    val Stop = MotionGlyph { t -> path(Ring); scale(1f - .24f * pulse(window(t, 0f, .65f))) { path("M9 9h6v6H9z") } }
    /** The shackle lifts and drops home. */
    val Lock = MotionGlyph { t ->
        move(0f, -1.8f * pulse(window(t, 0f, .7f))) { path("M7.5 10V7.5a4.5 4.5 0 0 1 9 0V10") }
        path("M6 10h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z"); line(12f, 14f, 12f, 17f)
    }
    /** The two links pull apart and click back together. */
    val Link = MotionGlyph { t ->
        val apart = pulse(t)
        move(1.1f * apart, -1.1f * apart) { path("M10 8l2-2a4.2 4.2 0 0 1 6 6l-2 2") }
        move(-1.1f * apart, 1.1f * apart) { path("M14 16l-2 2a4.2 4.2 0 0 1-6-6l2-2") }
        line(8.5f, 15.5f, 15.5f, 8.5f, 1f - apart)
    }
    /** The two branches grow out of the trunk and then get their heads. */
    val Split = MotionGlyph { t ->
        line(12f, 21f, 12f, 11f); trim("M12 11L6 5", again(t, 0f, .5f)); trim("M12 11l6-6", again(t, 0f, .5f))
        path("M3 9V3h6", again(t, .4f, .8f)); path("M15 3h6v6", again(t, .4f, .8f))
    }
    /** The four corners push outward. */
    val Expand = MotionGlyph { t ->
        val out = 1.4f * pulse(t)
        move(-out, -out) { path("M4 9V4h5") }; move(out, -out) { path("M15 4h5v5") }
        move(out, out) { path("M20 15v5h-5") }; move(-out, out) { path("M9 20H4v-5") }
    }
    /** The four corners draw inward. */
    val Collapse = MotionGlyph { t ->
        val inward = 1.4f * pulse(t)
        move(inward, inward) { path("M3 8h5V3") }; move(-inward, inward) { path("M16 3v5h5") }
        move(-inward, -inward) { path("M21 16h-5v5") }; move(inward, -inward) { path("M8 21v-5H3") }
    }
    /** The pin rocks about its point. */
    val Pin = MotionGlyph { t -> rotate(11f * swing(t, 1.5f), 12f, 21f) { path("M8 3h8l-1 7 3 3v2H6v-2l3-3z"); line(12f, 15f, 12f, 21f) } }
    /** The lid lifts off the box and the label slips down. */
    val Archive = MotionGlyph { t ->
        move(0f, -1.5f * pulse(window(t, 0f, .7f))) { path("M4 4h16v4H4z") }
        path("M5 8v12h14V8"); move(0f, .9f * pulse(window(t, .2f, .9f))) { line(9f, 12f, 15f, 12f) }
    }
    /** The two arrows close on the fold. */
    val Fold = MotionGlyph { t ->
        val close = 1.4f * pulse(t)
        move(0f, -close) { line(12f, 22f, 12f, 16f); path("M15 19l-3-3-3 3") }
        move(0f, close) { line(12f, 8f, 12f, 2f); path("M15 5l-3 3-3-3") }
        line(4f, 12f, 6f, 12f); line(10f, 12f, 14f, 12f); line(18f, 12f, 20f, 12f)
    }
    /** A scan line passes down the code. */
    val Scan = MotionGlyph { t ->
        path("M3 8V3h5M16 3h5v5M21 16v5h-5M8 21H3v-5"); path("M7 7h3v3H7zM14 7h3v3h-3zM7 14h3v3H7zM14 14h3v3h-3z")
        val beam = pulse(t)
        if (beam > 0f) { val y = 5f + 14f * smooth(t); line(5f, y, 19f, y, beam) }
    }
    /** A quarter turn of the four-pointed star, which looks the same after it. */
    val Sparkle = MotionGlyph { t ->
        scale(1f - .14f * pulse(t)) { rotate(MotionGlyphs.turn(smooth(t), 90f)) {
            path("M9.94 15.5A2 2 0 0 0 8.5 14.06l-6.13-1.58a.5.5 0 0 1 0-.96L8.5 9.94A2 2 0 0 0 9.94 8.5l1.58-6.13a.5.5 0 0 1 .96 0L14.06 8.5a2 2 0 0 0 1.44 1.44l6.13 1.58a.5.5 0 0 1 0 .96L15.5 14.06a2 2 0 0 0-1.44 1.44l-1.58 6.13a.5.5 0 0 1-.96 0z")
        } }
    }
    /** The tail of the sign unwinds and winds back. */
    val At = MotionGlyph { t -> trim("M16 9v6a2.5 2.5 0 0 0 5 0v-3a9 9 0 1 0-3 6.7", retract(t, 0f, 1f, .55f)); circle(12f, 12f, 4f) }
    /** The arrow drops onto the tray, which gives under it. */
    val Download = MotionGlyph { t ->
        move(0f, -2.4f * pulse(window(t, 0f, .6f))) { line(12f, 3f, 12f, 15f); path("M7 10l5 5 5-5") }
        move(0f, .8f * pulse(window(t, .5f, 1f))) { path("M4 17v4h16v-4") }
    }
    val Moon = MotionGlyph { t -> rotate(-16f * swing(t, 1.5f)) { path("M19.5 15.2A8.5 8.5 0 0 1 8.8 4.5a8.5 8.5 0 1 0 10.7 10.7z") } }
    /** The rays go a quarter round as the disc draws in. */
    val Sun = MotionGlyph { t ->
        scale(1f - .14f * pulse(t)) { circle(12f, 12f, 4.5f) }
        rotate(MotionGlyphs.turn(smooth(t), 90f)) { path("M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19") }
    }
    /** The stem lifts and the ring is drawn round again. */
    val Power = MotionGlyph { t ->
        move(0f, -1.4f * pulse(window(t, 0f, .5f))) { line(12f, 3f, 12f, 11f) }; trim("M7 5a8 8 0 1 0 10 0", again(t, .1f, .9f))
    }
    val Shield = MotionGlyph { t ->
        scale(1f + .06f * pulse(window(t, .5f, 1f))) { path("M12 3l8 3v6c0 4-4.5 7.5-8 9-3.5-1.5-8-5-8-9V6z") }
        trim("M8 12l3 3 5-6", again(t, .15f, .7f))
    }
    /** The signal builds outward from the dot, one arc at a time. */
    val Wifi = MotionGlyph { t ->
        line(12f, 20f, 12.01f, 20f); path("M9 16a4 4 0 0 1 6 0", again(t, .05f, .3f))
        path("M6 12a9 9 0 0 1 12 0", again(t, .3f, .55f)); path("M3 8a14 14 0 0 1 18 0", again(t, .55f, .8f))
    }
    /** The line across the screen is drawn again, as if it had just woken. */
    val Monitor = MotionGlyph { t ->
        path("M6 4.5h12a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 15V7A2.5 2.5 0 0 1 6 4.5z")
        line(3.5f, 13.5f, 3.5f + 17f * retract(t, 0f, .8f, .8f), 13.5f); line(12f, 17.5f, 12f, 20.5f); line(8.5f, 20.5f, 15.5f, 20.5f)
    }
    /** The phone buzzes. */
    val Phone = MotionGlyph { t ->
        rotate(7f * swing(t, 2.5f)) {
            path("M8 3.5h8A2.5 2.5 0 0 1 18.5 6v12a2.5 2.5 0 0 1-2.5 2.5H8A2.5 2.5 0 0 1 5.5 18V6A2.5 2.5 0 0 1 8 3.5z")
            line(10f, 6f, 14f, 6f); line(11f, 18f, 13f, 18f)
        }
    }
    /** The pages close a little toward the spine and fall open. */
    val Book = MotionGlyph { t ->
        line(12f, 7f, 12f, 21f)
        stretch(1f - .2f * pulse(t), 1f) { path("M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z") }
    }
    val Wrench = MotionGlyph { t ->
        rotate(22f * swing(t, 1.5f)) { path("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z") }
    }
    /** The meridian sweeps once round, so the globe turns. */
    val Globe = MotionGlyph { t ->
        circle(12f, 12f, 9f); line(3f, 12f, 21f, 12f)
        val across = cos(2.0 * PI * t.coerceIn(0f, 1f)).toFloat()
        stretch(if (abs(across) < .04f) .04f else across, 1f) { path("M12 3a14.5 14.5 0 0 0 0 18 14.5 14.5 0 0 0 0-18") }
    }
    /** The pen rocks on the corner of the page. */
    val FilePen = MotionGlyph { t ->
        path("M12.5 22H18a2 2 0 0 0 2-2V7l-5-5H6a2 2 0 0 0-2 2v9.5"); path("M14 2v4a2 2 0 0 0 2 2h4")
        rotate(-10f * swing(t, 1.5f), 4.25f, 21.75f) { path("M13.38 15.62a2.12 2.12 0 0 0-3-3L5 18l-.75 3.75L8 21z") }
    }
    /** The antenna twitches and the eyes blink. */
    val Robot = MotionGlyph { t ->
        rotate(16f * swing(t, 1.5f), 12f, 5f) { path("M12 2v3M9.5 3h2.5") }
        path("M8 5h8a3 3 0 0 1 3 3v3a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z"); path("M9 8.5h.01M15 8.5h.01", blink(t))
        path("M9.5 11.5h5M4.5 21.5a7.5 5.5 0 0 1 15 0M9.5 17l2.5 2 2.5-2")
    }
    val Cube = MotionGlyph { t -> move(0f, -1.3f * pulse(t)) { path("M12 2.5l9 5v9l-9 5-9-5v-9z"); path("M3 7.5l9 5 9-5M12 12.5v9") } }
    /** The line runs out, turns back and gets its arrow. */
    val Wrap = MotionGlyph { t ->
        line(3f, 6f, 21f, 6f); trim("M3 12h15a3 3 0 1 1 0 6h-4", again(t, 0f, .7f)); path("M16 16l-2 2 2 2", again(t, .55f, .9f)); line(3f, 18f, 10f, 18f)
    }
    /** Each branch is written out in turn. */
    val Tree = MotionGlyph { t ->
        path("M3 6v4c0 1.1.9 2 2 2h3M3 10v6c0 1.1.9 2 2 2h3")
        line(8f, 6f, 8f + 13f * again(t, 0f, .4f), 6f); line(13f, 12f, 13f + 8f * again(t, .2f, .6f), 12f); line(13f, 18f, 13f + 8f * again(t, .4f, .8f), 18f)
    }
    /** The bulb gives off light for a moment. */
    val Bulb = MotionGlyph { t ->
        path("M9 18h6M10 21h4M8 14a6 6 0 1 1 8 0c-1 .8-1 2-1 2H9s0-1.2-1-2z")
        val glow = pulse(t)
        if (glow > 0f) {
            line(12f, 1f, 12f, 1.9f, glow); line(3.1f, 9.5f, 4.2f, 9.5f, glow); line(19.8f, 9.5f, 20.9f, 9.5f, glow)
            line(6.2f, 3.8f, 5.4f, 3f, glow); line(17.8f, 3.8f, 18.6f, 3f, glow)
        }
    }
    val Bolt = MotionGlyph { t -> move(0f, -1.2f * pulse(window(t, 0f, .35f))) { rotate(9f * swing(t, 2f)) { path("M13 2L4 14h7l-1 8 10-12h-7z") } } }
    /** The three dots rise one after another. */
    val More = MotionGlyph { t ->
        for (dot in 0..2) move(0f, -2.2f * pulse(window(t, dot * .18f, .64f + dot * .18f))) { circle(5f + 7f * dot, 12f, .75f) }
    }
    /** The minute hand of the clipboard's clock goes once round. */
    val Pending = MotionGlyph { t ->
        path("M8 4H6a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h5M16 4h2a2 2 0 0 1 2 2v3M9 3h6v4H9z")
        circle(16.5f, 16.5f, 5.5f); line(16.5f, 16.5f, 19f, 18f)
        rotate(MotionGlyphs.turn(smooth(t)), 16.5f, 16.5f) { line(16.5f, 13f, 16.5f, 16.5f) }
    }
    /** The plus turns a quarter, as it does on its own. */
    val ListAdd = MotionGlyph { t ->
        line(4f, 6f, 20f, 6f); line(4f, 11f, 14f, 11f); line(4f, 16f, 10f, 16f)
        rotate(MotionGlyphs.turn(easeOut(t), 90f), 18f, 17.5f) { line(18f, 14f, 18f, 21f); line(14.5f, 17.5f, 21.5f, 17.5f) }
    }
    val ListPlay = MotionGlyph { t ->
        line(4f, 6f, 20f, 6f); line(4f, 11f, 14f, 11f); line(4f, 16f, 10f, 16f)
        move(1.4f * pulse(window(t, 0f, .65f)), 0f) { path("M16 14l6 3.5-6 3.5z") }
    }
    /** The bubble hops while its two lines are written again. */
    val Chat = MotionGlyph { t ->
        move(0f, -1.2f * pulse(t)) {
            path("M7 4.5h10a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H10l-5.5 3V7.5a3 3 0 0 1 3-3")
            line(8.5f, 9f, 8.5f + 7f * retract(t, 0f, .7f), 9f); line(8.5f, 13f, 8.5f + 4.5f * retract(t, .25f, 1f), 13f)
        }
    }
    /** The phone steps out from the monitor and back. */
    val Devices = MotionGlyph { t ->
        val step = pulse(t)
        path("M12.5 16H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2M8 16v4M5.5 20h6")
        move(1.1f * step, -1.1f * step) { path("M16 10h4a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1M17.5 18.5h1") }
    }

    /** The glyph for each icon name. Several icons share an outline, and so a motion. */
    val byName: Map<String, MotionGlyph> = linkedMapOf(
        "Settings" to Settings, "Tune" to MotionGlyphs.Tune, "Edit" to Edit, "ContentCopy" to Copy, "Description" to Document,
        "FolderOpen" to FolderOpen, "Folder" to MotionGlyphs.Folder, "Image" to Image, "PhotoCamera" to Camera,
        "Terminal" to Terminal, "Command" to Terminal, "CliOrigin" to Prompt, "Code" to Code, "Checklist" to Checklist,
        "Info" to Info, "ErrorOutline" to MotionGlyphs.Alert, "CheckCircle" to CheckCircle, "Check" to Check, "CheckCheck" to CheckCheck,
        "PlayCircle" to Play, "StopCircle" to Stop, "Lock" to Lock, "Link" to Link, "Link2" to Link, "CallSplit" to Split,
        "OpenInFull" to Expand, "CloseFullscreen" to Collapse, "PushPin" to Pin, "DeleteOutline" to MotionGlyphs.Trash,
        "Inventory2" to Archive, "FoldVertical" to Fold, "QrCodeScanner" to Scan, "AutoAwesome" to MotionGlyphs.Sparkle, "Sparkle" to Sparkle,
        "AlternateEmail" to At, "ArrowDownToLine" to Download, "Moon" to Moon, "Sun" to Sun, "Power" to Power, "ShieldCheck" to Shield,
        "Wifi" to Wifi, "Computer" to Monitor, "Monitor" to Monitor, "PhoneAndroid" to Phone, "Smartphone" to Phone, "BookOpen" to Book,
        "Wrench" to Wrench, "Globe" to Globe, "FilePen" to FilePen, "Users" to MotionGlyphs.Users, "RobotOutline" to Robot, "Cube" to Cube,
        "WrapText" to Wrap, "ListTree" to Tree, "Lightbulb" to Bulb, "Lightning" to Bolt, "MoreHoriz" to More, "PendingActions" to Pending,
        "PlaylistAdd" to ListAdd, "PlaylistPlay" to ListPlay, "ChatBubbleOutline" to Chat, "NotificationsNone" to MotionGlyphs.Bell,
        "Devices" to Devices, "Search" to MotionGlyphs.Search, "Refresh" to MotionGlyphs.Refresh, "RefreshCw" to MotionGlyphs.Refresh)
}

/** Counts the taps an action receives, so the icon beside it can answer each one. */
@Stable
internal class PTaps {
    var count by mutableIntStateOf(0)
        private set
    /** [action], counted. */
    fun then(action: () -> Unit): () -> Unit = { count++; action() }
}

@Composable
internal fun rememberPTaps(): PTaps = remember { PTaps() }

/** The glyph [icon] answers a tap with, or null when it only has a [PIconMotion]. */
internal fun pocketIconGlyph(icon: ImageVector): MotionGlyph? = IconGlyphs.byName[icon.name]

/**
 * A static icon that answers each change of [trigger]: with its own glyph from [IconGlyphs] when it
 * has one, otherwise by moving as a whole the way [motion] says. At rest it is the static icon
 * itself, pixel for pixel; nothing plays for the value it is first composed with, or with motion off.
 * A caller that passes a [motion] other than the icon's own gets that motion and no glyph.
 */
@Composable
internal fun PTapIcon(icon: ImageVector, trigger: Any?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified,
    contentDescription: String? = null, motion: PIconMotion = pocketIconMotion(icon)) {
    val glyph = if (motion == pocketIconMotion(icon)) pocketIconGlyph(icon) else null
    if (glyph == null) { PIcon(icon, contentDescription, modifier.pocketIconMotion(trigger, motion), tint); return }
    val play = rememberGlyphPulse(trigger, 620)
    val ink = if (tint != Color.Unspecified) tint else Pocket.ink
    val pen = remember { GlyphScope() }
    PIcon(icon, contentDescription, modifier.drawWithContent {
        val t = play.value
        if (t >= 1f) drawContent() else drawGlyph(pen, glyph, t, ink)
    }, tint)
}
