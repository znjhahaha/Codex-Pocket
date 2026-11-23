package dev.codexpocket.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.app.ui.kit.*

/** Series tints for charts and legends; every one derives from a token. */
internal class UsageSeries(val main: Color, val cacheRead: Color, val input: Color, val cacheWrite: Color, val output: Color,
    val failed: Color, val grid: Color, val idle: Color) {
    val tokens get() = listOf(cacheRead, input, cacheWrite, output)
}
internal val UsageTokenLabels = listOf("缓存读", "输入", "缓存写", "输出")

@Composable @ReadOnlyComposable
internal fun usageSeries(): UsageSeries {
    val c = Pocket.colors
    return UsageSeries(c.accent, c.accent, c.accent.copy(alpha = .42f), c.warning.copy(alpha = .85f), c.positive.copy(alpha = .85f),
        c.danger.copy(alpha = .8f), c.line.copy(alpha = .7f), c.inkFaint)
}

/** The tabular number style at a reading size, for figures inside rows and legends. */
@Composable @ReadOnlyComposable
internal fun usageNumber(size: Int = 15, weight: FontWeight = FontWeight.Medium): TextStyle =
    Pocket.type.number.copy(fontSize = size.sp, lineHeight = (size + 8).sp, fontWeight = weight, letterSpacing = 0.sp)

/** A solid content card on the page. */
@Composable
internal fun UsageCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(14.dp), spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit) {
    PCard(modifier, color = Pocket.colors.surface, padding = padding, spacing = spacing, content = content)
}

/** A card that opens something: the whole card settles in under the finger. */
@Composable
internal fun UsagePressCard(onClick: () -> Unit, description: String, modifier: Modifier = Modifier, spacing: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    UsageCard(modifier.pocketPressScale(interaction, .98f).clip(PocketRadii.card)
        .pocketPressable(interactionSource = interaction, pressedScale = 1f, onClickLabel = "查看明细", onClick = onClick)
        .semantics { contentDescription = description }, spacing = spacing, content = content)
}

@Composable
internal fun UsageNote(text: String, modifier: Modifier = Modifier, color: Color = Pocket.colors.inkMuted, maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null) {
    PText(text, modifier, style = Pocket.type.caption, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
}

@Composable
internal fun UsageHeading(text: String, modifier: Modifier = Modifier) {
    PText(text, modifier.semantics { heading() }, style = Pocket.type.titleSmall)
}

@Composable
internal fun UsageDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** The one-line width of a text in a style, for layouts that choose their shape before anything is measured. */
@Composable
internal fun rememberUsageTextWidth(): (String, TextStyle) -> Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        { text, style -> with(density) { measurer.measure(text, style, softWrap = false, maxLines = 1, density = density).size.width.toDp() } }
    }
}

/** Facts for one caption that may wrap between facts but never inside one: spaces do not break, and a word joiner follows each slash. */
internal fun usageFacts(facts: List<String>): String = facts.joinToString("${Typography.nbsp}· ") { it.replace(' ', Typography.nbsp).replace("/", "/\u2060") }

internal class UsageSegment(val label: String, val tag: String, val haptic: PocketHaptic? = PocketHaptic.Tick)

/**
 * The kit's sliding segmented control with a tag and a full 48dp target per segment. [selected] of
 * -1 shows no selection, for quick choices that are not the current value. Labels share one size:
 * the largest at which the longest still fits its segment, so large text is never cut short.
 */
@Composable
internal fun UsageSegmented(segments: List<UsageSegment>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, track: Color = Pocket.colors.fill) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val width = rememberUsageTextWidth()
    val label = Pocket.type.labelSmall
    val shown = if (selected in segments.indices) selected else -1
    val slide = rememberPSlide(segments.size, shown, enabled, haptic = PocketHaptic.Tick, onSelect = onSelect)
    val present = animateFloatAsState(if (shown >= 0) 1f else 0f, PocketMotion.color<Float>().orSnap(motion), label = "usage-segment-thumb")
    val thumb = c.surface
    BoxWithConstraints(modifier.fillMaxWidth().clip(PocketRadii.capsule).background(track).pocketSlide(slide).drawBehind {
        if (segments.isEmpty() || present.value <= 0f) return@drawBehind
        // Held, the thumb presses in; pulled past an end it flattens against it.
        val inset = 4.dp.toPx() + 1.5.dp.toPx() * slide.lift.value
        val item = size.width / segments.size
        val left = (slide.start.value * item).coerceIn(0f, size.width) + inset
        val right = ((slide.end.value * item).coerceIn(0f, size.width) - inset).coerceAtLeast(left)
        val height = size.height - inset * 2
        drawRoundRect(thumb.copy(alpha = present.value.coerceIn(0f, 1f)), Offset(left, inset), Size(right - left, height), CornerRadius(height / 2))
    }.selectableGroup()) {
        val room = maxWidth / segments.size.coerceAtLeast(1) - 12.dp
        val style = remember(segments.map { it.label }, room, label, width) {
            val sizes = (label.fontSize.value.toInt() downTo 8).map { label.copy(fontSize = it.sp) }
            sizes.firstOrNull { size -> segments.all { width(it.label, size) <= room } } ?: sizes.last()
        }
        Row(Modifier.fillMaxWidth()) {
            segments.forEachIndexed { index, segment ->
                val active = index == shown
                val ink by animateColorAsState(if (!enabled) c.inkMuted.copy(alpha = .6f) else if (index == slide.shown) c.ink else c.inkMuted,
                    PocketMotion.color<Color>().orSnap(motion), label = "usage-segment-ink")
                Box(Modifier.weight(1f).heightIn(min = PocketSpace.touch).clip(PocketRadii.capsule)
                    .pocketSelectable(active, enabled, Role.Tab, pressedScale = .96f, haptic = segment.haptic) { onSelect(index) }
                    .testTag(segment.tag).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    PText(segment.label, style = style, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * One of a few options too long for a single track. Like the kit's pill, with an unselected fill
 * that still reads as a control on a sheet in the dark theme.
 */
@Composable
internal fun UsageChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val interaction = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (!enabled) c.fill.copy(alpha = .5f) else if (selected) c.solid else c.fill,
        PocketMotion.color<Color>().orSnap(motion), label = "usage-choice-fill")
    val ink by animateColorAsState(if (!enabled) c.inkMuted.copy(alpha = .6f) else if (selected) c.onSolid else c.ink,
        PocketMotion.color<Color>().orSnap(motion), label = "usage-choice-ink")
    Box(modifier.heightIn(min = PocketSpace.touch).pocketPressScale(interaction, .96f).clip(PocketRadii.capsule).background(fill)
        .pocketSelectable(selected, enabled, Role.RadioButton, interaction, pressedScale = 1f, highlight = true,
            tint = if (selected && enabled) c.onSolid else c.ink, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        PText(text, style = Pocket.type.labelSmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A capsule action with a glyph and a label; violet once it carries a state worth noticing. */
@Composable
internal fun UsageChipButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, leading: ImageVector? = null) {
    val c = Pocket.colors
    val interaction = remember { MutableInteractionSource() }
    val ink = if (active) c.onAccentSoft else c.ink
    Row(modifier.heightIn(min = PocketSpace.touch).widthIn(min = PocketSpace.touch).pocketPressScale(interaction, .96f).clip(PocketRadii.capsule)
        .background(if (active) c.accentSoft else c.fill)
        .pocketPressable(interactionSource = interaction, pressedScale = 1f, haptic = PocketHaptic.Tap, tint = ink, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        ProvidePInk(ink, Pocket.type.labelSmall) {
            if (leading != null) PIcon(leading, null, Modifier.size(16.dp))
            PText(text, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * One row of a grouped list that is built from lazy items: the first and last rows carry the
 * group's rounded corners, the rows between are square and separated by a hairline.
 */
@Composable
internal fun UsageListRow(first: Boolean, last: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onClickLabel: String? = null,
    divider: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = Pocket.colors
    val top = if (first) 20.dp else 0.dp
    val bottom = if (last) 20.dp else 0.dp
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(top, top, bottom, bottom)).background(c.surface)
        .pocketPressable(pressedScale = 1f, onClickLabel = onClickLabel, onClick = onClick)) {
        Column(Modifier.fillMaxWidth().heightIn(min = PocketSpace.touch).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically), content = content)
        if (divider && !last) PDivider(Modifier.padding(horizontal = 14.dp), color = c.line.copy(alpha = .7f))
    }
}

/** A share of the whole as a thin bar; described in words for assistive technology. */
@Composable
internal fun UsageShareBar(fraction: Float, description: String, modifier: Modifier = Modifier, color: Color = Pocket.colors.accent.copy(alpha = .8f)) {
    val track = Pocket.colors.fillStrong
    Box(modifier.fillMaxWidth().height(3.dp).semantics { contentDescription = description }.drawBehind {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (fraction > 0f) drawRoundRect(color, size = Size((size.width * fraction).coerceAtLeast(size.height), size.height), cornerRadius = radius)
    })
}
