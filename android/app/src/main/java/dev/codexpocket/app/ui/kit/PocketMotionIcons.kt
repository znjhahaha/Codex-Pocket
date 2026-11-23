package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Timing curves for glyph motion. Each maps progress to a value with no clock of its own, so a
 * trajectory can be checked numerically and a glyph is always at rest when its motion ends.
 */
internal object GlyphMotion {
    /** Smoothstep over the whole range. */
    fun smooth(t: Float): Float { val v = t.coerceIn(0f, 1f); return v * v * (3f - 2f * v) }
    /** Smoothstep confined to [start, end]: 0 before, 1 after. */
    fun window(t: Float, start: Float, end: Float): Float = smooth((t - start) / (end - start))
    /** Out and back: 0 at both ends, 1 in the middle. */
    fun pulse(t: Float): Float = sin(PI * t.coerceIn(0f, 1f)).toFloat()
    /** A swing that dies away: 0 at both ends, largest in the first cycle. */
    fun swing(t: Float, cycles: Float = 2f): Float { val v = t.coerceIn(0f, 1f); return (sin(2.0 * PI * cycles * v) * (1f - v)).toFloat() }
    /** Fast start, gentle stop. */
    fun easeOut(t: Float): Float { val v = 1f - t.coerceIn(0f, 1f); return 1f - v * v * v }
}

/** Draws on Pocket's 24-unit icon grid with the outline weight the static icons use. */
internal class GlyphScope {
    @PublishedApi internal lateinit var scope: DrawScope
    internal var ink: Color = Color.Black
    private val pen = Stroke(GridStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val measure = PathMeasure()
    private val part = Path()

    private fun tint(alpha: Float) = if (alpha >= 1f) ink else ink.copy(alpha = ink.alpha * alpha.coerceIn(0f, 1f))

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, alpha: Float = 1f) =
        scope.drawLine(tint(alpha), Offset(x1, y1), Offset(x2, y2), GridStroke, StrokeCap.Round)
    fun path(data: String, alpha: Float = 1f, filled: Boolean = false) =
        scope.drawPath(parsed(data), tint(alpha), style = if (filled) Fill else pen)
    /** The leading [fraction] of a single-contour path. */
    fun trim(data: String, fraction: Float, alpha: Float = 1f) {
        if (fraction <= 0f) return
        if (fraction >= 1f) { path(data, alpha); return }
        measure.setPath(parsed(data), false)
        part.rewind()
        measure.getSegment(0f, measure.length * fraction, part, true)
        scope.drawPath(part, tint(alpha), style = pen)
    }
    fun circle(cx: Float, cy: Float, radius: Float, alpha: Float = 1f, filled: Boolean = false) =
        scope.drawCircle(tint(alpha), radius, Offset(cx, cy), style = if (filled) Fill else pen)
    fun arc(cx: Float, cy: Float, radius: Float, start: Float, sweep: Float, alpha: Float = 1f) =
        scope.drawArc(tint(alpha), start, sweep, false, Offset(cx - radius, cy - radius), Size(radius * 2, radius * 2), style = pen)
    fun roundRect(x: Float, y: Float, width: Float, height: Float, radius: Float, alpha: Float = 1f, filled: Boolean = false) =
        scope.drawRoundRect(tint(alpha), Offset(x, y), Size(width, height), CornerRadius(radius), style = if (filled) Fill else pen)
    inline fun rotate(degrees: Float, px: Float = 12f, py: Float = 12f, block: () -> Unit) = scope.rotate(degrees, Offset(px, py)) { block() }
    inline fun move(dx: Float, dy: Float, block: () -> Unit) = scope.translate(dx, dy) { block() }
    inline fun scale(factor: Float, px: Float = 12f, py: Float = 12f, block: () -> Unit) = scope.scale(factor, Offset(px, py)) { block() }
    /** Scales each axis on its own: a page closing toward its spine, a meridian turning edge-on. */
    inline fun stretch(x: Float, y: Float, px: Float = 12f, py: Float = 12f, block: () -> Unit) = scope.scale(x, y, Offset(px, py)) { block() }

    internal companion object {
        const val GridStroke = 1.65f
        private val paths = HashMap<String, Path>()
        // Drawing is confined to the UI thread; the cache holds a few dozen short outlines.
        fun parsed(data: String): Path = paths.getOrPut(data) { PathParser().parsePathString(data).toPath() }
    }
}

/** A glyph whose shape is a function of progress. At progress 1 it shows its resting pose. */
internal fun interface MotionGlyph { fun GlyphScope.draw(t: Float) }

/**
 * Renders [glyph] at [progress]. The progress is read while drawing, so a moving glyph never
 * recomposes or re-measures its surroundings.
 */
@Composable
internal fun MotionIcon(glyph: MotionGlyph, progress: () -> Float, modifier: Modifier = Modifier.size(20.dp),
    tint: Color = Color.Unspecified, contentDescription: String? = null) {
    val ink = if (tint != Color.Unspecified) tint else Pocket.ink
    val pen = remember { GlyphScope() }
    val described = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription; role = Role.Image } else Modifier
    Canvas(modifier.then(described)) { drawGlyph(pen, glyph, progress(), ink) }
}

/** Draws [glyph] at [t], centred and fitted to this area on the 24-unit grid. */
internal fun DrawScope.drawGlyph(pen: GlyphScope, glyph: MotionGlyph, t: Float, ink: Color) {
    val unit = size.minDimension / 24f
    withTransform({
        translate((size.width - 24f * unit) / 2f, (size.height - 24f * unit) / 2f)
        scale(unit, unit, Offset.Zero)
    }) {
        pen.scope = this; pen.ink = ink
        with(glyph) { pen.draw(t) }
    }
}

/** Two states joined by a spring: reversing mid-way continues from where the glyph is. */
@Composable
internal fun rememberGlyphToggle(on: Boolean, spec: FiniteAnimationSpec<Float> = PocketMotion.glyph()): State<Float> =
    animateFloatAsState(if (on) 1f else 0f, spec.orSnap(rememberPocketMotion()), label = "glyph-toggle")

/** Plays from 0 to 1 on [trigger]; a new trigger in flight continues the current pass. At rest it is 1. */
@Composable
internal fun rememberGlyphPulse(trigger: Any?, millis: Int = 560): State<Float> {
    val motion = rememberPocketMotion()
    val progress = remember { Animatable(1f) }
    val previousTrigger = remember { arrayOf(trigger) }
    LaunchedEffect(trigger, motion) {
        val triggered = previousTrigger[0] != trigger
        previousTrigger[0] = trigger
        if (!motion) { progress.snapTo(1f); return@LaunchedEffect }
        // Initial display and re-enabling motion alone must not replay an earlier trigger.
        if (!triggered) return@LaunchedEffect
        if (progress.value >= 1f) progress.snapTo(0f)
        val remainingMillis = (millis * (1f - progress.value)).toInt()
        progress.animateTo(1f, PocketMotion.glyphPulse(remainingMillis).orSnap(motion))
    }
    return progress.asState()
}

/** Draws itself in once when it appears. [animate] is false for history, which shows the result as-is. */
@Composable
internal fun rememberGlyphEntrance(animate: Boolean, millis: Int = 420): State<Float> {
    val motion = rememberPocketMotion()
    val progress = remember { Animatable(if (animate && motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (progress.value < 1f) progress.animateTo(1f, tween(millis, easing = LinearEasing)) }
    return progress.asState()
}

/** Cycles while [active]; when it stops the glyph finishes its current pass and rests at 1. */
@Composable
internal fun rememberGlyphLoop(active: Boolean, millis: Int = 1600): State<Float> {
    val motion = rememberPocketMotion()
    val last = remember { floatArrayOf(1f) }
    if (active && motion) {
        val phase = rememberInfiniteTransition(label = "glyph-loop").animateFloat(0f, 1f,
            infiniteRepeatable(tween(millis, easing = LinearEasing)), label = "glyph-loop-phase")
        return remember(phase) { object : State<Float> { override val value: Float get() = phase.value.also { last[0] = it } } }
    }
    if (!motion) return rememberUpdatedState(1f)
    val settle = remember(active) { Animatable(last[0]) }
    LaunchedEffect(settle) { settle.animateTo(1f, tween(180, easing = PocketMotion.EaseOut)); last[0] = 1f }
    return settle.asState()
}

@Composable
internal fun PToggleGlyph(glyph: MotionGlyph, on: Boolean, modifier: Modifier = Modifier.size(20.dp), tint: Color = Color.Unspecified,
    contentDescription: String? = null) {
    val progress = rememberGlyphToggle(on)
    MotionIcon(glyph, { progress.value }, modifier, tint, contentDescription)
}

@Composable
internal fun PPulseGlyph(glyph: MotionGlyph, trigger: Any?, modifier: Modifier = Modifier.size(20.dp), tint: Color = Color.Unspecified,
    millis: Int = 560, contentDescription: String? = null) {
    val progress = rememberGlyphPulse(trigger, millis)
    MotionIcon(glyph, { progress.value }, modifier, tint, contentDescription)
}

@Composable
internal fun PLoopGlyph(glyph: MotionGlyph, active: Boolean, modifier: Modifier = Modifier.size(20.dp), tint: Color = Color.Unspecified,
    millis: Int = 1600, contentDescription: String? = null) {
    val progress = rememberGlyphLoop(active, millis)
    MotionIcon(glyph, { progress.value }, modifier, tint, contentDescription)
}

@Composable
internal fun PEntranceGlyph(glyph: MotionGlyph, animate: Boolean, modifier: Modifier = Modifier.size(20.dp), tint: Color = Color.Unspecified,
    millis: Int = 420, contentDescription: String? = null) {
    val progress = rememberGlyphEntrance(animate, millis)
    MotionIcon(glyph, { progress.value }, modifier, tint, contentDescription)
}
