package dev.codexpocket.app.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.PocketIcons

internal enum class PButtonKind { Primary, Secondary, Ghost, Danger }
internal enum class PTone { Neutral, Accent, Positive, Warning, Danger }

@Composable
private fun focusRing(focused: Boolean): Modifier {
    val accent = Pocket.colors.accent
    // Always present: adding a modifier when focus arrives would rebuild the chain under a finger already down.
    return Modifier.drawWithContent { drawContent(); if (focused) drawOutline(PocketRadii.capsule.createOutline(size, layoutDirection, this), accent, style = Stroke(2.dp.toPx())) }
}

/**
 * A labelled action. While [busy] the label gives way to breathing dots at the same size, so the
 * button never jumps and cannot be pressed twice.
 */
@Composable
internal fun PButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: PButtonKind = PButtonKind.Primary,
    enabled: Boolean = true, busy: Boolean = false, leading: ImageVector? = null, haptic: PocketHaptic? = PocketHaptic.Tap) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(when {
        !enabled -> c.fillStrong
        kind == PButtonKind.Primary -> c.solid
        kind == PButtonKind.Secondary -> c.sunken
        kind == PButtonKind.Danger -> c.danger
        else -> Color.Transparent
    }, PocketMotion.color<Color>().orSnap(motion), label = "button-fill")
    val label = when {
        !enabled -> c.inkMuted
        kind == PButtonKind.Primary -> c.onSolid
        kind == PButtonKind.Danger -> c.onDanger
        else -> c.ink
    }
    val ink by animateColorAsState(label, PocketMotion.color<Color>().orSnap(motion), label = "button-ink")
    val working by animateFloatAsState(if (busy) 1f else 0f, PocketMotion.state<Float>().orSnap(motion), label = "button-busy")
    Box(modifier.heightIn(min = 50.dp).pocketPressScale(interaction).then(focusRing(focused)).clip(PocketRadii.capsule).background(fill)
        .pocketPressable(enabled && !busy, Role.Button, interaction, pressedScale = 1f, haptic = haptic, tint = label, onClick = onClick)
        .semantics { if (busy) stateDescription = "进行中" }
        .padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        ProvidePInk(ink, Pocket.type.label) {
            Row(Modifier.graphicsLayer { alpha = 1f - working; scaleX = 1f - .06f * working; scaleY = scaleX },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (leading != null) PIcon(leading, null, Modifier.size(18.dp))
                PText(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (busy || working > .01f) PDots(Modifier.graphicsLayer { alpha = working }, dot = 5.dp, color = ink)
        }
    }
}

/** A quiet inline action whose content the caller composes; 48dp tall at least. */
@Composable
internal fun PTextAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp), haptic: PocketHaptic? = null,
    content: @Composable RowScope.() -> Unit) {
    val c = Pocket.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(modifier.heightIn(min = PocketSpace.touch).pocketPressScale(interaction, .96f).then(focusRing(focused)).clip(PocketRadii.capsule)
        .pocketPressable(enabled, Role.Button, interaction, pressedScale = 1f, haptic = haptic, onClick = onClick)
        .padding(contentPadding), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ProvidePInk(if (!enabled) c.inkMuted else if (tint != Color.Unspecified) tint else c.ink, Pocket.type.label) { content() }
    }
}

@Composable
internal fun PTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    tint: Color = Color.Unspecified, leading: ImageVector? = null, haptic: PocketHaptic? = null) {
    PTextAction(onClick, modifier, enabled, tint, haptic = haptic) {
        if (leading != null) PIcon(leading, null, Modifier.padding(end = 6.dp).size(18.dp))
        PText(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * An icon-only action: 48dp target, round highlight, always named for accessibility. When the action
 * lands the icon answers in the way its meaning suggests; a caller's own [glyph] moves itself.
 */
@Composable
internal fun PIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    tint: Color = Color.Unspecified, haptic: PocketHaptic? = null, motion: PIconMotion = pocketIconMotion(icon),
    glyph: @Composable (() -> Unit)? = null) {
    val c = Pocket.colors
    val interaction = remember { MutableInteractionSource() }
    var taps by remember { mutableIntStateOf(0) }
    Box(modifier.size(PocketSpace.touch).pocketPressScale(interaction, .92f).clip(CircleShape)
        .pocketPressable(enabled, Role.Button, interaction, pressedScale = 1f, haptic = haptic) { taps++; onClick() }
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        ProvidePInk(if (!enabled) c.inkMuted.copy(alpha = .6f) else if (tint != Color.Unspecified) tint else c.ink) {
            if (glyph != null) glyph() else PIcon(icon, null, Modifier.size(20.dp).pocketIconMotion(taps, motion))
        }
    }
}

/** A choice among a few: the selected pill takes the solid fill. */
@Composable
internal fun PPill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    leading: ImageVector? = null) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(if (selected && enabled) c.solid else c.sunken, PocketMotion.color<Color>().orSnap(motion), label = "pill-fill")
    val ink by animateColorAsState(if (!enabled) c.inkMuted else if (selected) c.onSolid else c.ink,
        PocketMotion.color<Color>().orSnap(motion), label = "pill-ink")
    Row(modifier.heightIn(min = PocketSpace.touch).pocketPressScale(interaction, .96f).then(focusRing(focused)).clip(PocketRadii.capsule).background(fill)
        .pocketSelectable(selected, enabled, Role.RadioButton, interaction, pressedScale = 1f, highlight = true,
            tint = if (selected && enabled) c.onSolid else c.ink, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        ProvidePInk(ink, Pocket.type.labelSmall) {
            if (leading != null) PIcon(leading, null, Modifier.size(16.dp))
            PText(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A small status label. The tone names an outcome; neutral is for plain facts. */
@Composable
internal fun PBadge(text: String, modifier: Modifier = Modifier, tone: PTone = PTone.Neutral, leading: ImageVector? = null) {
    val c = Pocket.colors
    val (fill, ink) = when (tone) {
        PTone.Neutral -> c.fillStrong to c.inkMuted
        PTone.Accent -> c.accentSoft to c.onAccentSoft
        PTone.Positive -> c.positiveSoft to c.positive
        PTone.Warning -> c.warningSoft to c.warning
        PTone.Danger -> c.dangerSoft to c.onDangerSoft
    }
    Row(modifier.clip(PocketRadii.chip).background(fill).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ProvidePInk(ink, Pocket.type.labelSmall) {
            if (leading != null) PIcon(leading, null, Modifier.size(14.dp))
            PText(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One line in a menu or a settings list: glyph, label, optional hint and trailing slot. [glyph] replaces the static icon with an animated one. */
@Composable
internal fun PRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, subtitle: String? = null,
    enabled: Boolean = true, selected: Boolean = false, destructive: Boolean = false, haptic: PocketHaptic? = null,
    accentSelection: Boolean = false, glyph: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val fill by animateColorAsState(if (!selected) Color.Transparent else if (accentSelection) c.accentSoft.copy(alpha = .42f) else c.fillStrong.copy(alpha = .7f),
        PocketMotion.color<Color>().orSnap(motion), label = "row-fill")
    val ink = when { destructive -> c.danger; enabled -> c.ink; else -> c.inkMuted.copy(alpha = .7f) }
    Row(modifier.fillMaxWidth().heightIn(min = PocketSpace.touch).clip(PocketRadii.control).background(fill)
        .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
        .pocketPressable(enabled, Role.Button, pressedScale = .985f, haptic = haptic, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProvidePInk(ink) {
            if (glyph != null) glyph() else if (icon != null) PIcon(icon, null, Modifier.size(19.dp))
            Column(Modifier.weight(1f)) {
                PText(title, style = Pocket.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) PText(subtitle, style = Pocket.type.caption, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) trailing() else if (selected) PIcon(PocketIcons.Check, null, Modifier.size(17.dp))
        }
    }
}
