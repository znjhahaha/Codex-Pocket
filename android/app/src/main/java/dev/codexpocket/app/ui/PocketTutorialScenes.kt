package dev.codexpocket.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import dev.codexpocket.app.ui.kit.*
import dev.codexpocket.app.ui.kit.GlyphMotion.easeOut
import dev.codexpocket.app.ui.kit.GlyphMotion.pulse
import dev.codexpocket.app.ui.kit.GlyphMotion.window
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The moving picture at the head of each chapter of the tutorial. */
internal enum class TourScene { Welcome, Pair, Locations, Home, Send, Process, Approve, Edit, Assistant, Usage }

/**
 * A scene drawn on a 280 by 200 grid, whatever size it is shown at. Each is a loop over a few
 * seconds that ends on the state it has been building, so the still shown when motion is off, and
 * the moment before it starts again, is the finished picture rather than an empty one.
 */
internal class TourCanvas(private val d: DrawScope, val c: PocketColors, private val pen: GlyphScope) {
    private val u = d.size.width / 280f
    private fun Color.at(alpha: Float) = copy(alpha = this.alpha * alpha.coerceIn(0f, 1f))

    fun box(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color, alpha: Float = 1f) {
        if (alpha <= 0f || w <= 0f || h <= 0f) return
        d.drawRoundRect(color.at(alpha), Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u))
    }
    fun ring(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color, width: Float = 1.2f, alpha: Float = 1f) {
        if (alpha <= 0f || w <= 0f || h <= 0f) return
        d.drawRoundRect(color.at(alpha), Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u), style = Stroke(width * u))
    }
    /** A card: a raised surface with a hairline. */
    fun card(x: Float, y: Float, w: Float, h: Float, r: Float, alpha: Float = 1f) { box(x, y, w, h, r, c.surface, alpha); ring(x, y, w, h, r, c.line, alpha = alpha) }
    /** A line of text that has not been written out. */
    fun bar(x: Float, y: Float, w: Float, color: Color = c.fillStrongest, h: Float = 6f, alpha: Float = 1f) = box(x, y, w, h, h / 2f, color, alpha)
    fun dot(x: Float, y: Float, r: Float, color: Color, alpha: Float = 1f) { if (alpha > 0f && r > 0f) d.drawCircle(color.at(alpha), r * u, Offset(x * u, y * u)) }
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float = 1.6f, alpha: Float = 1f) {
        if (alpha > 0f) d.drawLine(color.at(alpha), Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), width * u, StrokeCap.Round)
    }
    fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, color: Color, width: Float = 5f) {
        d.drawArc(color, start, sweep, false, Offset((cx - r) * u, (cy - r) * u), Size(2 * r * u, 2 * r * u), style = Stroke(width * u, cap = StrokeCap.Round))
    }
    /** Anything drawn on the 24-unit icon grid, placed with its corner at ([x], [y]) and [size] across. */
    fun icon(x: Float, y: Float, size: Float, tint: Color, alpha: Float = 1f, draw: GlyphScope.() -> Unit) {
        if (alpha <= 0f) return
        d.withTransform({ translate(x * u, y * u); scale(size * u / 24f, size * u / 24f, Offset.Zero) }) { pen.scope = this; pen.ink = tint.at(alpha); pen.draw() }
    }
    fun glyph(glyph: MotionGlyph, t: Float, x: Float, y: Float, size: Float, tint: Color, alpha: Float = 1f) = icon(x, y, size, tint, alpha) { with(glyph) { draw(t) } }
    /** Draws [block] moved by ([dx], [dy]) and scaled about ([px], [py]). */
    fun moved(dx: Float, dy: Float, scale: Float = 1f, px: Float = 0f, py: Float = 0f, block: () -> Unit) {
        d.withTransform({ translate(dx * u, dy * u); scale(scale, scale, Offset(px * u, py * u)) }) { block() }
    }
    /** A check in a filled disc that pops in: the sign that a step has gone through. */
    fun done(x: Float, y: Float, r: Float, shown: Float) {
        if (shown <= 0f) return
        val size = r * (.4f + .6f * easeOut(shown)) * (1f + .14f * pulse(shown))
        dot(x, y, size, c.positive, shown * 3f)
        icon(x - size * .62f, y - size * .62f, size * 1.24f, c.bg, shown * 2f) { trim("M5 12l4.5 4.5L19 7", window(shown, .3f, 1f)) }
    }
    /** A fingertip, for the scenes that show where to touch. Pressed, it sinks. */
    fun finger(x: Float, y: Float, shown: Float, press: Float = 0f) {
        dot(x, y, 11f - 2.5f * press, c.ink, .16f * shown); dot(x, y, 6f - 1.5f * press, c.ink, .34f * shown)
    }
}

/** What a loop has built so far: [start]..[end] brings it in, and it is still there as the next pass begins. */
internal fun kept(t: Float, start: Float, end: Float): Float = maxOf(window(t, start, end), 1f - window(t, 0f, .05f))
private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

@Composable
internal fun TourStage(scene: TourScene, active: Boolean, modifier: Modifier = Modifier) {
    val c = Pocket.colors
    val loop = rememberGlyphLoop(active, if (scene == TourScene.Welcome) 6400 else 5800)
    val pen = remember { GlyphScope() }
    Box(modifier.fillMaxWidth().aspectRatio(280f / 200f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = loop.value
            with(TourCanvas(this, c, pen)) {
                when (scene) {
                    TourScene.Welcome -> welcome(t)
                    TourScene.Pair -> pair(t)
                    TourScene.Locations -> locations(t)
                    TourScene.Home -> home(t)
                    TourScene.Send -> send(t)
                    TourScene.Process -> process(t)
                    TourScene.Approve -> approve(t)
                    TourScene.Edit -> edit(t)
                    TourScene.Assistant -> assistant(t)
                    TourScene.Usage -> usage(t)
                }
            }
        }
        if (scene == TourScene.Welcome) {
            // The mark assembles once when the tutorial opens; after that only its cursor keeps time.
            val motion = rememberPocketMotion()
            val built = remember { Animatable(if (motion) 0f else 1f) }
            LaunchedEffect(Unit) { if (built.value < 1f) { pocketPause(180); built.animateTo(1f, tween(1250, easing = LinearEasing)) } }
            PBrandMark(Modifier.fillMaxSize(.5f).aspectRatio(1f), { built.value },
                { if (built.value < 1f) 1f else .5f + .5f * cos(8.0 * PI * loop.value).toFloat() }, contentDescription = "Codex Pocket")
        }
    }
}

/** Where Pocket reaches: a terminal, a computer, a phone and the model, drifting around the mark. */
private fun TourCanvas.welcome(t: Float) {
    val spots = listOf(Triple(26f, 26f, IconGlyphs.Terminal), Triple(218f, 20f, IconGlyphs.Monitor), Triple(30f, 138f, IconGlyphs.Phone), Triple(216f, 142f, IconGlyphs.Sparkle))
    spots.forEachIndexed { index, (x, y, glyph) ->
        val dy = 4.5f * sin(2.0 * PI * (t + index * .25)).toFloat()
        val dx = 2f * sin(2.0 * PI * (t + index * .4)).toFloat()
        line(x + 18f + dx, y + 18f + dy, 140f, 100f, c.line, 1f, .7f)
        card(x + dx, y + dy, 36f, 36f, 12f)
        glyph(glyph, (t * 2f + index * .25f) % 1f, x + dx + 8f, y + dy + 8f, 20f, c.inkMuted)
    }
}

/** The computer shows a code, the phone reads it, and the two are joined. */
private fun TourCanvas.pair(t: Float) {
    card(22f, 36f, 124f, 88f, 10f); box(10f, 128f, 148f, 8f, 4f, c.fillStrongest)
    val cells = intArrayOf(0b11101, 0b10011, 0b01110, 0b11001, 0b10111)
    for (row in 0..4) for (col in 0..4) if (cells[row] shr col and 1 == 1) box(57f + col * 11f, 52f + row * 11f, 9f, 9f, 2f, c.ink, .86f)
    card(190f, 26f, 66f, 132f, 15f); bar(214f, 34f, 18f, h = 4f)
    val reading = window(t, .06f, .44f)
    glyph(IconGlyphs.Scan, if (reading in .001f..0.999f) reading else 1f, 203f, 70f, 40f, c.accent)
    val beam = pulse(reading)
    if (beam > 0f) line(52f, 50f + 58f * reading, 116f, 50f + 58f * reading, c.accent, 2f, beam)
    val joined = kept(t, .44f, .68f)
    for (step in 0..5) dot(152f + step * 6.4f, 82f + 2.4f * step, 1.7f, c.accent, (joined * 6f - step).coerceIn(0f, 1f))
    done(170f, 60f, 12f, kept(t, .66f, .8f))
}

/** The same app, three places to run Codex: the selection moves between them and comes back. */
private fun TourCanvas.locations(t: Float) {
    val at = window(t, .15f, .3f) + window(t, .48f, .63f) - 2f * window(t, .82f, .97f)
    val moving = pulse(window(t, .15f, .3f)) + pulse(window(t, .48f, .63f)) + pulse(window(t, .82f, .97f))
    val glyphs = listOf(IconGlyphs.Monitor, IconGlyphs.Terminal, IconGlyphs.Phone)
    for (index in 0..2) card(40f, 20f + index * 54f, 200f, 44f, 14f)
    box(40f, 20f + at * 54f - 3f * moving, 200f, 44f + 6f * moving, 14f, c.accentSoft); ring(40f, 20f + at * 54f - 3f * moving, 200f, 44f + 6f * moving, 14f, c.accent, alpha = .5f)
    for (index in 0..2) {
        val near = (1f - kotlin.math.abs(at - index)).coerceIn(0f, 1f)
        val y = 20f + index * 54f
        glyph(glyphs[index], if (near > .5f) window(t % .33f, 0f, .33f) else 1f, 54f, y + 11f, 22f, lerp(c.inkMuted, c.onAccentSoft, near))
        bar(88f, y + 13f, if (index == 1) 64f else 78f, lerp(c.fillStrongest, c.onAccentSoft, near * .7f), 7f)
        bar(88f, y + 26f, 44f, h = 5f, alpha = .8f)
        dot(222f, y + 22f, 4f, c.positive, near)
    }
}

/** The list of conversations: a row is pressed and opens into its conversation, then folds back. */
private fun TourCanvas.home(t: Float) {
    card(80f, 6f, 120f, 188f, 18f)
    bar(92f, 18f, 46f, c.ink, 7f, .85f); dot(184f, 21f, 7f, c.fillStrong)
    val press = pulse(window(t, .1f, .22f))
    for (row in 0..3) {
        val y = 38f + row * 28f
        val sunk = if (row == 1) 1f - .04f * press else 1f
        moved(0f, 0f, sunk, 140f, y + 11f) {
            if (row == 1) box(86f, y - 3f, 108f, 28f, 9f, c.fill, press)
            dot(98f, y + 11f, 7f, if (row == 1) c.accentSoft else c.fillStrong)
            bar(112f, y + 5f, if (row % 2 == 0) 58f else 70f, h = 6f); bar(112f, y + 15f, 40f, h = 4f, alpha = .7f)
        }
    }
    box(94f, 162f, 92f, 24f, 12f, c.fill); box(97f, 165f, 28f, 18f, 9f, c.accentSoft)
    for (tab in 0..2) dot(111f + tab * 29f, 174f, 3.2f, if (tab == 0) c.onAccentSoft else c.inkFaint)
    // The row grows into the page and the page goes back into the row.
    val open = easeOut(window(t, .2f, .42f)) - easeOut(window(t, .7f, .9f))
    if (open > .001f) {
        val x = mix(86f, 81f, open); val y = mix(63f, 7f, open); val w = mix(108f, 118f, open); val h = mix(28f, 186f, open)
        box(x, y, w, h, mix(9f, 17f, open), c.surface, open * 4f)
        val page = window(open, .55f, 1f)
        bar(x + 12f, y + 12f, 50f, c.ink, 7f, .85f * page)
        box(x + w - 78f, y + 34f, 66f, 22f, 11f, c.fillStrong, page)
        for (lineIndex in 0..2) bar(x + 12f, y + 70f + lineIndex * 13f, (w - 30f) * (1f - lineIndex * .22f), alpha = page)
        box(x + 10f, y + h - 34f, w - 20f, 24f, 12f, c.fill, page)
    }
}

/** A message is typed, leaves the field as a bubble and is answered. */
private fun TourCanvas.send(t: Float) {
    card(22f, 150f, 236f, 38f, 19f)
    dot(42f, 169f, 9f, c.fill); line(38f, 169f, 46f, 169f, c.inkMuted); line(42f, 165f, 42f, 173f, c.inkMuted)
    val typed = window(t, .05f, .3f)
    val gone = window(t, .32f, .36f)
    bar(60f, 166f, 112f * typed, c.ink, 6f, .8f * (1f - gone) * window(t, .05f, .08f))
    val tap = pulse(window(t, .28f, .38f))
    dot(238f, 169f, 12f * (1f - .14f * tap), lerp(c.fillStrong, c.solid, typed))
    icon(229f, 160f, 18f, lerp(c.inkFaint, c.onSolid, typed)) { path("M12 19V5M6 11l6-6 6 6") }
    // The bubble starts where the text was and springs up to its place.
    val flown = kept(t, .34f, .56f)
    val rise = easeOut(flown) + .06f * pulse(window(flown, .6f, 1f))
    val x = mix(48f, 134f, easeOut(flown)); val y = mix(156f, 26f, rise)
    box(x, y, 124f, 28f, 14f, c.fillStrong, flown * 6f); bar(x + 12f, y + 11f, 96f, c.ink, 6f, .8f * flown * 6f)
    val writing = window(t, .58f, .64f) * (1f - window(t, .72f, .76f))
    if (writing > 0f) {
        box(22f, 70f, 52f, 26f, 13f, c.fillStrong, writing)
        for (index in 0..2) dot(36f + index * 12f, 83f - 3f * pulse(((t - .58f) * 9f - index * .22f) % 1f), 2.8f, c.inkMuted, writing)
    }
    listOf(170f, 148f, 96f).forEachIndexed { index, width -> bar(24f, 72f + index * 14f, width, alpha = kept(t, .74f + index * .05f, .82f + index * .05f)) }
}

/** The work is shown as it happens: each tool's icon moves while it runs and is ticked off, then the changes are summed up. */
private fun TourCanvas.process(t: Float) {
    val tools = listOf(IconGlyphs.Terminal, ToolGlyphs.Read, ToolGlyphs.Search, ToolGlyphs.Write)
    tools.forEachIndexed { index, glyph ->
        val begin = .05f + index * .17f
        val shown = kept(t, begin, begin + .05f)
        val running = window(t, begin, begin + .17f)
        val y = 22f + index * 29f
        glyph(glyph, if (running in .001f..0.999f) running else 1f, 30f, y, 20f, lerp(c.inkMuted, c.ink, pulse(running)), shown)
        bar(60f, y + 4f, 52f, c.ink, 6f, .7f * shown); bar(118f, y + 4f, 70f + 14f * (index % 2), alpha = shown)
        done(238f, y + 10f, 7f, kept(t, begin + .15f, begin + .22f))
    }
    val summed = kept(t, .76f, .9f)
    moved(0f, 8f * (1f - easeOut(summed)), 1f) {
        card(28f, 146f, 224f, 40f, 12f, summed)
        glyph(IconGlyphs.Document, 1f, 40f, 156f, 20f, c.inkMuted, summed)
        bar(70f, 158f, 72f, alpha = summed); bar(70f, 170f, 40f, h = 4f, alpha = .7f * summed)
        bar(170f, 163f, 44f * summed, c.positive, 7f, summed); bar(220f, 163f, 20f * summed, c.danger, 7f, summed)
    }
}

/** Codex asks before it acts: the request is read, allowed with a tap, and settles as done. */
private fun TourCanvas.approve(t: Float) {
    card(36f, 22f, 208f, 132f, 18f)
    glyph(IconGlyphs.Shield, window(t, .0f, .2f).let { if (it in .001f..0.999f) it else 1f }, 50f, 36f, 24f, c.warning)
    bar(84f, 40f, 96f, c.ink, 7f, .85f); bar(84f, 54f, 60f, h = 5f, alpha = .8f)
    box(50f, 72f, 180f, 30f, 9f, c.sunken); icon(58f, 79f, 16f, c.inkMuted) { path("M5 7l5 5-5 5") }; bar(80f, 84f, 110f, c.inkMuted, 5f, .7f)
    val settled = kept(t, .52f, .66f)
    val press = pulse(window(t, .4f, .52f))
    box(50f, 114f, 84f, 28f, 14f, c.fillStrong, 1f - settled); bar(76f, 125f, 32f, c.inkMuted, 6f, 1f - settled)
    moved(0f, 0f, 1f - .06f * press, 188f, 128f) { box(146f, 114f, 84f, 28f, 14f, c.solid, 1f - settled); bar(172f, 125f, 32f, c.onSolid, 6f, 1f - settled) }
    done(140f, 128f, 13f, kept(t, .56f, .72f))
    bar(160f, 125f, 44f, c.positive, 6f, .8f * settled)
    // Waiting requests are counted on the bell; the count clears when this one is answered.
    glyph(MotionGlyphs.Bell, window(t, .0f, .16f).let { if (it in .001f..0.999f) it else 1f }, 216f, 166f, 22f, c.inkMuted)
    dot(236f, 168f, 4.5f, c.accent, 1f - settled)
    val reach = window(t, .2f, .4f) * (1f - window(t, .56f, .66f))
    if (reach > 0f) finger(mix(236f, 190f, easeOut(window(t, .2f, .4f))), mix(190f, 130f, easeOut(window(t, .2f, .4f))), reach, press)
}

/** A sent message is changed where it is: its bubble becomes the field, and the answer is given again. */
private fun TourCanvas.edit(t: Float) {
    val editing = window(t, .18f, .3f) * (1f - window(t, .68f, .78f))
    val retyped = kept(t, .34f, .58f)
    val text = mix(92f, 124f, retyped) - 46f * pulse(window(t, .34f, .58f))
    val x = mix(mix(142f, 110f, retyped), 86f, editing); val w = mix(mix(114f, 146f, retyped), 170f, editing)
    box(x, 24f, w, 30f, 15f, lerp(c.fillStrong, c.surface, editing)); ring(x, 24f, w, 30f, 15f, c.accent, 1.6f, editing)
    bar(x + 13f, 36f, text, c.ink, 6f, .8f)
    if (editing > 0f) line(x + 16f + text, 32f, x + 16f + text, 46f, c.accent, 1.6f, editing * (.5f + .5f * cos(30.0 * PI * t).toFloat()))
    // Its pencil sits under it until it is being edited; then the two round buttons take that place.
    glyph(IconGlyphs.Edit, window(t, .1f, .24f).let { if (it in .001f..0.999f) it else 1f }, 238f, 60f, 16f, c.inkMuted, 1f - editing)
    val send = pulse(window(t, .6f, .7f))
    dot(214f, 72f, 10f, c.fillStrong, editing); icon(208f, 66f, 12f, c.ink, editing) { path("M6.5 6.5l11 11M17.5 6.5l-11 11") }
    dot(242f, 72f, 10f * (1f - .15f * send), c.solid, editing); icon(236f, 66f, 12f, c.onSolid, editing) { path("M12 19V5M6 11l6-6 6 6") }
    // What came after is withdrawn while it is edited, and answered afresh once it is sent.
    val again = window(t, 0f, .05f)
    val old = if (t < .7f) again * (1f - .64f * window(t, .18f, .3f)) * (1f - window(t, .62f, .7f)) else 0f
    listOf(168f, 140f, 104f).forEachIndexed { index, width -> bar(24f, 104f + index * 14f, width, alpha = old) }
    listOf(150f, 176f, 120f, 84f).forEachIndexed { index, width ->
        bar(24f, 104f + index * 14f, width, alpha = if (t < .7f) 1f - again else window(t, .8f + index * .04f, .86f + index * .04f))
    }
    val tapEdit = window(t, .06f, .16f) * (1f - window(t, .2f, .26f))
    if (tapEdit > 0f) finger(246f, 70f, tapEdit, pulse(window(t, .12f, .22f)))
    val tapSend = window(t, .56f, .62f) * (1f - window(t, .7f, .76f))
    if (tapSend > 0f) finger(244f, 76f, tapSend, send)
}

/** The assistant greets, then hands work out and watches it come back done. */
private fun TourCanvas.assistant(t: Float) {
    val cards = listOf(IconGlyphs.Checklist, IconGlyphs.Pending, IconGlyphs.Book)
    cards.forEachIndexed { index, glyph ->
        val out = kept(t, .3f + index * .08f, .5f + index * .08f)
        val x = mix(104f, 24f + index * 80f, easeOut(out)); val y = mix(60f, 128f, easeOut(out))
        line(140f, 86f, x + 36f, y + 4f, c.line, 1.2f, out)
        card(x, y, 72f, 54f, 13f, out)
        glyph(glyph, window(t, .5f + index * .08f, .74f + index * .08f).let { if (it in .001f..0.999f) it else 1f }, x + 10f, y + 9f, 18f, c.inkMuted, out)
        bar(x + 10f, y + 34f, 40f, h = 5f, alpha = out); bar(x + 10f, y + 43f, 26f, h = 4f, alpha = .7f * out)
        done(x + 58f, y + 14f, 7f, kept(t, .76f + index * .05f, .84f + index * .05f))
    }
    val hop = window(t, .06f, .28f)
    dot(140f, 56f, 36f + 9f * pulse(window(t, .1f, .32f)), c.accent, .1f * (1f - window(t, .1f, .32f)))
    dot(140f, 56f, 34f, c.accentSoft); arc(140f, 56f, 34f, 360f * t - 90f, 70f, c.accent, 2f)
    icon(116f, 32f, 48f, c.onAccentSoft) { with(AssistantGlyphs) { face(t, if (hop in .001f..0.999f) hop else 1f, 0f) } }
}

/** What has been used, and the few things worth setting. */
private fun TourCanvas.usage(t: Float) {
    card(22f, 18f, 150f, 116f, 16f); card(182f, 18f, 76f, 116f, 16f)
    bar(34f, 30f, 44f, c.ink, 6f, .8f)
    listOf(.42f, .66f, .5f, .86f, .6f, 1f).forEachIndexed { index, height ->
        val grown = easeOut(kept(t, .06f + index * .05f, .34f + index * .05f))
        box(36f + index * 21f, 120f - 62f * height * grown, 13f, 62f * height * grown, 5f, if (index == 5) c.accent else c.accentSoft)
    }
    arc(220f, 66f, 22f, -90f, 360f, c.fillStrongest, 6f); arc(220f, 66f, 22f, -90f, 250f * easeOut(kept(t, .12f, .56f)).coerceAtLeast(.004f), c.accent, 6f)
    bar(204f, 106f, 32f, h = 6f); bar(210f, 118f, 20f, h = 4f, alpha = .7f)
    card(22f, 144f, 236f, 40f, 14f)
    val on = kept(t, .62f, .76f)
    glyph(IconGlyphs.Moon, window(t, .6f, .84f).let { if (it in .001f..0.999f) it else 1f }, 34f, 153f, 22f, c.inkMuted)
    bar(66f, 157f, 70f, c.ink, 6f, .75f); bar(66f, 169f, 96f, h = 4f, alpha = .7f)
    box(212f, 154f, 34f, 20f, 10f, lerp(c.fillStrongest, c.positive, on)); dot(222f + 14f * on, 164f, 7.5f, c.surface)
}
