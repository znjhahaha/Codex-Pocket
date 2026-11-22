package dev.codexpocket.app.ui.kit

import dev.codexpocket.app.ui.kit.GlyphMotion.easeOut
import dev.codexpocket.app.ui.kit.GlyphMotion.pulse
import dev.codexpocket.app.ui.kit.GlyphMotion.smooth
import dev.codexpocket.app.ui.kit.GlyphMotion.swing
import dev.codexpocket.app.ui.kit.GlyphMotion.window

/**
 * Pocket's moving glyphs, on the same 24-unit grid and outline weight as the static icons. Each
 * motion says what happened: a lid lifts to delete, a clip slides on, two branches join. Glyphs in
 * [restAtBothEnds] start and finish in their static pose, so an interrupted one never looks broken.
 */
internal object MotionGlyphs {
    private const val CheckPath = "M5 12l4.5 4.5L19 7"
    /** Degrees for a fraction of a turn; a completed turn is exactly 0, so the resting pose is pixel-identical. */
    fun turn(fraction: Float, full: Float = 360f): Float = (full * fraction) % full

    private const val RefreshPath = "M20 9a8 8 0 0 0-14-3L3 9M3 3v6h6M4 15a8 8 0 0 0 14 3l3-3M15 15h6v6"

    /** 0 is the send arrow, 1 the stop square: the arrow leaves upward as the square turns in. */
    val SendStop = MotionGlyph { t ->
        val leave = window(t, 0f, .6f)
        val arrive = window(t, .35f, 1f)
        if (leave < 1f) move(0f, -3f * leave) { scale(1f - .4f * leave) {
            line(12f, 20f, 12f, 4f, 1f - leave); line(5.5f, 10.5f, 12f, 4f, 1f - leave); line(12f, 4f, 18.5f, 10.5f, 1f - leave)
        } }
        if (arrive > 0f) rotate(90f * (1f - arrive)) { scale(.5f + .5f * arrive) { roundRect(7f, 7f, 10f, 10f, 2.2f, arrive, filled = true) } }
    }

    /** 0 is add, 1 is close: the same cross turned an eighth. */
    val PlusClose = MotionGlyph { t -> rotate(45f * t) { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) } }

    /** 0 points down (collapsed), 1 points up (expanded). */
    val Chevron = MotionGlyph { t -> rotate(180f * t) { line(5.5f, 9f, 12f, 15.5f); line(12f, 15.5f, 18.5f, 9f) } }

    /** 0 is copy, 1 is the confirming check. */
    val CopyCheck = MotionGlyph { t ->
        val leave = window(t, 0f, .5f)
        val arrive = window(t, .35f, 1f)
        if (leave < 1f) scale(1f - .2f * leave) {
            path("M9 8h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", 1f - leave)
            path("M15 4H5a2 2 0 0 0-2 2v10", 1f - leave)
        }
        trim(CheckPath, arrive)
    }

    /** Draws the mark in, then settles with a small swell. For a result that has just arrived. */
    val Check = MotionGlyph { t -> scale(1f + .12f * pulse(window(t, .55f, 1f))) { trim(CheckPath, window(t, 0f, .8f)) } }

    /** The ring closes, then the mark is drawn inside it. */
    val CheckCircle = MotionGlyph { t ->
        arc(12f, 12f, 8.5f, -90f, 360f * window(t, 0f, .6f))
        trim("M8 12l3 3 5-6", window(t, .45f, 1f))
    }

    /** One eased turn for a tap on refresh. */
    val Refresh = MotionGlyph { t -> rotate(turn(easeOut(t))) { path(RefreshPath) } }
    /** Continuous turning while a refresh is in flight. */
    val RefreshSpin = MotionGlyph { t -> rotate(turn(t)) { path(RefreshPath) } }
    /** An open arc turning: work of unknown length. */
    val Spin = MotionGlyph { t -> rotate(turn(t)) { path("M20 12a8 8 0 1 1-8-8") } }

    /** The bell swings from its crown; the clapper trails it. */
    val Bell = MotionGlyph { t ->
        rotate(14f * swing(t), 12f, 4f) { path("M7 10a5 5 0 0 1 10 0v5l1.5 2H5.5L7 15z"); line(12f, 3f, 12f, 5f) }
        move(-1.4f * swing((t - .08f) / .92f), 0f) { line(10f, 20f, 14f, 20f) }
    }

    /** The minute hand goes once round. */
    val Clock = MotionGlyph { t ->
        circle(12f, 12f, 8.5f)
        line(12f, 12f, 15f, 14f)
        rotate(turn(smooth(t))) { line(12f, 12f, 12f, 7.5f) }
    }

    /** Two sliders move apart and return. */
    val Tune = MotionGlyph { t ->
        val upper = tuneUpperKnob(t)
        val lower = tuneLowerKnob(t)
        line(3f, 7f, upper, 7f); line(upper + 5f, 7f, 21f, 7f); roundRect(upper, 4.5f, 5f, 5f, .6f)
        line(3f, 17f, lower, 17f); line(lower + 5f, 17f, 21f, 17f); roundRect(lower, 14.5f, 5f, 5f, .6f)
    }
    /** Left edge of each knob on the grid; both stay between the track ends. */
    fun tuneUpperKnob(t: Float): Float = 11f - 4f * pulse(t)
    fun tuneLowerKnob(t: Float): Float = 7f + 5f * pulse(t)

    /** The lens looks to one side and comes back. */
    val Search = MotionGlyph { t ->
        move(1.8f * swing(t, 1f), -1.6f * pulse(t)) { circle(10f, 10f, 6.5f); line(15f, 15f, 20.5f, 20.5f) }
    }

    /** The folder hops as its flap dips open. */
    val Folder = MotionGlyph { t ->
        val open = pulse(t)
        move(0f, -1.2f * open) {
            path("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2z")
            if (open > 0f) line(2f, 10f + 1.5f * open, 22f, 10f + 1.5f * open, open)
        }
    }

    /** The lid lifts on its hinge; the can stays put. */
    val Trash = MotionGlyph { t ->
        val lift = pulse(t)
        rotate(-16f * lift, 4f, 6f) { move(0f, -1f * lift) { line(4f, 6f, 20f, 6f); path("M9 6V3h6v3") } }
        path("M6 6l1 15h10l1-15"); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
    }

    /** The clip slides along its own axis, as if pushed onto a page. */
    val Paperclip = MotionGlyph { t ->
        val slide = pulse(t)
        rotate(-8f * slide) { move(-1.2f * slide, 1.2f * slide) {
            path("M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48")
        } }
    }

    /** A quarter turn of a four-pointed star, with its small companion twinkling. */
    val Sparkle = MotionGlyph { t ->
        scale(1f - .14f * pulse(t)) { rotate(turn(smooth(t), 90f)) { path("M12 3l2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z") } }
        val twinkle = sparkleCompanion(t)
        scale(twinkle, 19f, 4.5f) { line(19f, 3f, 19f, 6f, twinkle); line(17.5f, 4.5f, 20.5f, 4.5f, twinkle) }
    }
    /** The companion's size and opacity: full at rest, gone at the middle of the turn. */
    fun sparkleCompanion(t: Float): Float = 1f - pulse(t)

    /** Two people: the one in front nods, the one behind steps in. Subagents at work. */
    val Users = MotionGlyph { t ->
        val nod = pulse(window(t, 0f, .5f))
        val step = pulse(window(t, .4f, 1f))
        move(0f, -.9f * nod) { circle(9f, 7f, 4f) }
        path("M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2")
        move(1.2f * step, 0f) { path("M22 21v-2a4 4 0 0 0-3-3.87", 1f - .45f * step); path("M16 3.13a4 4 0 0 1 0 7.75", 1f - .45f * step) }
    }

    /** A branch curves into its target, which swells as the work lands. */
    val Merge = MotionGlyph { t ->
        circle(6f, 6f, 3f)
        line(6f, 9f, 6f, 21f)
        trim("M6 9a9 9 0 0 0 9 9", window(t, .05f, .7f))
        scale(.6f + .4f * window(t, .5f, .85f) + .14f * pulse(window(t, .7f, 1f)), 18f, 18f) { circle(18f, 18f, 3f, window(t, .45f, .75f)) }
    }

    /** A downward arrow dips: newer content is below. */
    val ArrowDownNudge = MotionGlyph { t ->
        move(0f, 2f * pulse(t)) { line(12f, 4f, 12f, 20f); line(5.5f, 13.5f, 12f, 20f); line(12f, 20f, 18.5f, 13.5f) }
    }

    /** The exclamation mark hops inside its ring: something needs a decision. */
    val Alert = MotionGlyph { t ->
        circle(12f, 12f, 8.5f)
        move(0f, -1.6f * swing(t, 1.5f)) { line(12f, 7.5f, 12f, 13f); line(12f, 16.5f, 12.01f, 16.5f) }
    }

    /** Glyphs whose pose at 0 equals their pose at 1. Safe to pulse or loop from the static icon. */
    val restAtBothEnds: Map<String, MotionGlyph> = linkedMapOf("Refresh" to Refresh, "RefreshSpin" to RefreshSpin, "Spin" to Spin,
        "Bell" to Bell, "Clock" to Clock, "Tune" to Tune, "Search" to Search, "Folder" to Folder, "Trash" to Trash,
        "Paperclip" to Paperclip, "Sparkle" to Sparkle, "Users" to Users, "ArrowDownNudge" to ArrowDownNudge, "Alert" to Alert)
    /** Glyphs that join two different poses, or draw themselves in toward the pose at 1. */
    val twoPose: Map<String, MotionGlyph> = linkedMapOf("SendStop" to SendStop, "PlusClose" to PlusClose, "Chevron" to Chevron,
        "CopyCheck" to CopyCheck, "Check" to Check, "CheckCircle" to CheckCircle, "Merge" to Merge)
}
