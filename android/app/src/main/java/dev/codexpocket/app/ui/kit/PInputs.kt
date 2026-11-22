package dev.codexpocket.app.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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

/** A switch whose thumb travels on a spring and widens while held. 52x48 target. */
@Composable
internal fun PSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val travel = animateFloatAsState(if (checked) 1f else 0f, PocketMotion.state<Float>().orSnap(motion), label = "switch-thumb")
    val stretch = animateFloatAsState(if (pressed) 1f else 0f, PocketMotion.press<Float>().orSnap(motion), label = "switch-press")
    val track by animateColorAsState(if (checked) c.solid else c.fillStrongest, PocketMotion.color<Color>().orSnap(motion), label = "switch-track")
    val thumb = if (checked) c.onSolid else c.surface
    val toggle = if (onCheckedChange != null) Modifier.pocketToggleable(checked, enabled, Role.Switch, interaction, onValueChange = onCheckedChange) else Modifier
    Box(modifier.size(52.dp, 48.dp).then(toggle).drawBehind {
        val w = 44.dp.toPx(); val h = 27.dp.toPx()
        val left = (size.width - w) / 2; val top = (size.height - h) / 2
        val fade = if (enabled) 1f else .4f
        drawRoundRect(track.copy(alpha = track.alpha * fade), Offset(left, top), Size(w, h), CornerRadius(h / 2))
        val inset = 3.dp.toPx(); val d = h - inset * 2
        val extra = 5.dp.toPx() * stretch.value
        val run = w - inset * 2 - d - extra
        val x = left + inset + run * travel.value.coerceIn(-.05f, 1.05f)
        drawRoundRect(thumb.copy(alpha = thumb.alpha * (if (enabled) 1f else .7f)), Offset(x, top + inset), Size(d + extra, d), CornerRadius(d / 2))
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
 * edge moves first and the trailing edge follows, so it stretches in the direction of travel.
 */
@Composable
internal fun PSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val last = remember { intArrayOf(selected) }
    val forward = selected >= last[0]
    last[0] = selected
    val quick = spring<Float>(dampingRatio = .85f, stiffness = 640f).orSnap(motion)
    val slow = spring<Float>(dampingRatio = .85f, stiffness = 300f).orSnap(motion)
    val start = animateFloatAsState(selected.toFloat(), if (forward) slow else quick, label = "segment-start")
    val end = animateFloatAsState(selected + 1f, if (forward) quick else slow, label = "segment-end")
    val thumb = c.surface
    Row(modifier.fillMaxWidth().clip(PocketRadii.capsule).background(c.fill).padding(4.dp).drawBehind {
        if (options.isEmpty()) return@drawBehind
        val item = size.width / options.size
        val left = (start.value * item).coerceIn(0f, size.width)
        val right = (end.value * item).coerceIn(left, size.width)
        drawRoundRect(thumb, Offset(left, 0f), Size(right - left, size.height), CornerRadius(size.height / 2))
    }.selectableGroup()) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            val ink by animateColorAsState(if (!enabled) c.inkMuted.copy(alpha = .6f) else if (active) c.ink else c.inkMuted,
                PocketMotion.color<Color>().orSnap(motion), label = "segment-ink")
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(PocketRadii.capsule)
                .pocketSelectable(active, enabled, Role.Tab, pressedScale = .96f) { onSelect(index) }
                .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                PText(label, style = Pocket.type.labelSmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
