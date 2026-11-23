package dev.codexpocket.app.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A text input. [bare] drops the well for hosts that draw their own container, such as a glass
 * dock; the label stays visible above the field rather than living in the placeholder.
 */
@Composable
internal fun PTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null,
    placeholder: String? = null, enabled: Boolean = true, singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default, bare: Boolean = false, textStyle: TextStyle? = null,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val outline by animateColorAsState(if (focused) c.accent.copy(alpha = .7f) else c.line.copy(alpha = .5f),
        PocketMotion.color<Color>().orSnap(motion), label = "field-focus")
    val style = (textStyle ?: Pocket.type.body).copy(color = if (enabled) c.ink else c.inkMuted)
    BasicTextField(value, onValueChange, modifier.fillMaxWidth().semantics { if (label != null) contentDescription = label },
        enabled = enabled, singleLine = singleLine, minLines = if (singleLine) 1 else minLines, maxLines = if (singleLine) 1 else maxLines,
        textStyle = style, cursorBrush = SolidColor(c.accent), visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, interactionSource = interactions,
        decorationBox = { inner ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (label != null) PText(label, Modifier.padding(horizontal = 4.dp), style = Pocket.type.labelSmall, color = c.inkMuted)
                val well = if (bare) Modifier else Modifier.heightIn(min = 54.dp).clip(PocketRadii.field).background(c.sunken)
                    .border(1.dp, outline, PocketRadii.field).padding(15.dp)
                Row(Modifier.fillMaxWidth().then(well), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (leading != null) ProvidePInk(c.inkMuted) { leading() }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) PText(placeholder, style = style, color = c.inkMuted,
                            maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                    if (trailing != null) ProvidePInk(c.inkMuted) { trailing() }
                }
            }
        })
}

/**
 * A switch that can be tapped or dragged. The thumb follows the finger, widens while held, stretches
 * with its own speed as it crosses, and the track takes its colour from where the thumb is. 52x48 target.
 */
@Composable
internal fun PSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val haptics = rememberPocketHaptics()
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val travel = remember { Animatable(if (checked) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(checked)
    val change by rememberUpdatedState(onCheckedChange)
    val spec = PocketMotion.state<Float>().orSnap(motion)
    LaunchedEffect(checked) { if (!dragging) travel.animateTo(if (checked) 1f else 0f, spec) }
    val stretch = animateFloatAsState(if (pressed || dragging) 1f else 0f, PocketMotion.press<Float>().orSnap(motion), label = "switch-press")
    val run = with(LocalDensity.current) { (44.dp - 6.dp - 21.dp).toPx() }
    val flick = with(LocalDensity.current) { 400.dp.toPx() }
    val drag = rememberDraggableState { delta -> scope.launch { travel.snapTo((travel.value + delta / run).coerceIn(0f, 1f)) } }
    val toggle = if (onCheckedChange != null) Modifier.pocketToggleable(checked, enabled, Role.Switch, interaction, onValueChange = onCheckedChange)
        .draggable(drag, Orientation.Horizontal, enabled, onDragStarted = { dragging = true }, onDragStopped = { velocity ->
            dragging = false
            // A flick decides by its direction; a slow drag by which half the thumb was left in.
            val on = if (abs(velocity) > flick) velocity > 0f else travel.value > .5f
            if (on != latest) { haptics.perform(if (on) PocketHaptic.ToggleOn else PocketHaptic.ToggleOff); change?.invoke(on) }
            travel.animateTo(if (on) 1f else 0f, spec, velocity / run)
            // The owner may have refused the change: show what it holds.
            if (!dragging) travel.animateTo(if (latest) 1f else 0f, spec)
        }) else Modifier
    val off = c.fillStrongest; val on = c.solid
    val thumbOff = c.surface; val thumbOn = c.onSolid
    Box(modifier.size(52.dp, 48.dp).then(toggle).drawBehind {
        val w = 44.dp.toPx(); val h = 27.dp.toPx()
        val left = (size.width - w) / 2; val top = (size.height - h) / 2
        val fade = if (enabled) 1f else .4f
        val at = travel.value.coerceIn(0f, 1f)
        val track = lerp(off, on, at)
        drawRoundRect(track.copy(alpha = track.alpha * fade), Offset(left, top), Size(w, h), CornerRadius(h / 2))
        val inset = 3.dp.toPx(); val d = h - inset * 2
        // Held it widens; in flight it stretches along its travel and thins across it.
        val speed = if (motion) (abs(travel.velocity) * .9f).coerceAtMost(6f) else 0f
        val extra = 5.dp.toPx() * stretch.value + speed.dp.toPx()
        val thin = (speed * .25f).dp.toPx()
        val x = left + inset + (w - inset * 2 - d - extra) * travel.value.coerceIn(-.05f, 1.05f)
        val thumb = lerp(thumbOff, thumbOn, at)
        drawRoundRect(thumb.copy(alpha = thumb.alpha * (if (enabled) 1f else .7f)), Offset(x, top + inset + thin / 2), Size(d + extra, d - thin),
            CornerRadius((d - thin) / 2))
    })
}

/** A checkbox whose mark is drawn stroke by stroke when it becomes checked. */
@Composable
internal fun PCheck(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val drawn = animateFloatAsState(if (checked) 1f else 0f, PocketMotion.state<Float>().orSnap(motion), label = "check-mark")
    val fill by animateColorAsState(if (checked) c.solid else c.sunken, PocketMotion.color<Color>().orSnap(motion), label = "check-fill")
    val outline = if (checked) c.solid else c.inkFaint
    val mark = c.onSolid
    val path = remember { Path() }
    val segment = remember { Path() }
    val measure = remember { PathMeasure() }
    val toggle = if (onCheckedChange != null) Modifier.pocketToggleable(checked, enabled, Role.Checkbox, pressedScale = .92f, onValueChange = onCheckedChange) else Modifier
    Box(modifier.size(48.dp).then(toggle).drawBehind {
        val box = 22.dp.toPx(); val radius = CornerRadius(7.dp.toPx())
        val origin = Offset((size.width - box) / 2, (size.height - box) / 2)
        val fade = if (enabled) 1f else .5f
        drawRoundRect(fill.copy(alpha = fill.alpha * fade), origin, Size(box, box), radius)
        drawRoundRect(outline.copy(alpha = outline.alpha * fade), origin, Size(box, box), radius, style = Stroke(1.dp.toPx()))
        val amount = drawn.value.coerceIn(0f, 1f)
        if (amount > 0f) {
            val u = box / 24f
            path.rewind(); path.moveTo(origin.x + 6f * u, origin.y + 12.5f * u)
            path.lineTo(origin.x + 10.2f * u, origin.y + 16.5f * u); path.lineTo(origin.x + 18f * u, origin.y + 8f * u)
            measure.setPath(path, false)
            segment.rewind(); measure.getSegment(0f, measure.length * amount, segment, true)
            drawPath(segment, mark, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    })
}

/**
 * A few mutually exclusive views. The selection is one pill that slides between items; its leading
 * edge moves first and the trailing edge follows, so it stretches in the direction of travel. The
 * pill can also be dragged: it follows the finger, presses in while held, flattens against either
 * end, and selects the item it is let go over.
 */
@Composable
internal fun PSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val slide = rememberPSlide(options.size, selected, enabled, onSelect = onSelect)
    val thumb = c.surface
    Row(modifier.fillMaxWidth().clip(PocketRadii.capsule).background(c.fill).padding(4.dp).pocketSlide(slide).drawBehind {
        if (options.isEmpty()) return@drawBehind
        val item = size.width / options.size
        val held = 1.5.dp.toPx() * slide.lift.value
        val left = (slide.start.value * item).coerceIn(0f, size.width)
        val right = (slide.end.value * item).coerceIn(left, size.width)
        drawRoundRect(thumb, Offset(left + held, held), Size((right - left - held * 2).coerceAtLeast(0f), size.height - held * 2), CornerRadius(size.height / 2))
    }.selectableGroup()) {
        options.forEachIndexed { index, label ->
            val ink by animateColorAsState(if (!enabled) c.inkMuted.copy(alpha = .6f) else if (index == slide.shown) c.ink else c.inkMuted,
                PocketMotion.color<Color>().orSnap(motion), label = "segment-ink")
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(PocketRadii.capsule)
                .pocketSelectable(index == selected, enabled, Role.Tab, pressedScale = .96f) { onSelect(index) }
                .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                PText(label, style = Pocket.type.labelSmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
