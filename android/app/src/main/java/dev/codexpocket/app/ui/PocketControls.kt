@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Shared interaction surfaces. Foundation semantics remain native; the visual language is Pocket. */
internal fun pocketMotionMillis(value: Int) = if (ValueAnimator.areAnimatorsEnabled()) value else 0

@Composable
internal fun pocketPressSpring(): FiniteAnimationSpec<Float> {
    val preferences = rememberPocketGlassPreferences()
    return if (preferences.animatorsEnabled && !preferences.batterySaver && !preferences.highContrast)
        spring(dampingRatio = .82f, stiffness = 900f) else snap()
}

internal val LocalPocketSheetAsScreen = staticCompositionLocalOf { false }
internal val LocalPocketScreenHeaderContent = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** A destination with its own toolbar can cover a retained page without capturing or disposing it. */
@Composable
internal fun PocketPageOverlay(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    PocketModal(title, onBack, sheet = false, screen = true) { content() }
}

@Composable
internal fun PocketScreen(title: String, onDismiss: () -> Unit, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val headerContent = LocalPocketScreenHeaderContent.current
    PocketModal(title, onDismiss, sheet = false, screen = true) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(PocketIcons.ArrowBack, "关闭", onClick = onDismiss)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                PText(title, style = Pocket.type.titleLarge, modifier = Modifier.semantics { heading() })
                if (!subtitle.isNullOrBlank()) PText(subtitle, style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
            }
        }
        headerContent?.invoke()
        Column(Modifier.weight(1f), content = content)
    }
}

/**
 * Compact popover anchored to the control that opened it. No title bar or close button:
 * tapping outside, Back or Escape dismisses it, as with iOS context menus and Radix popovers.
 * [title] remains the accessible pane title.
 */
@Composable
internal fun PocketAnchoredMenu(title: String, onDismiss: () -> Unit, anchor: Rect?, widthDp: Float = 300f, alignStart: Boolean = false,
    below: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    PocketModal(title, onDismiss, sheet = false, anchor = anchor, anchored = true, menuWidth = widthDp, alignStart = alignStart, below = below) {
        Column(Modifier.weight(1f, fill = false).padding(6.dp), content = content)
    }
}

/** One menu line: 20dp icon, label, optional gray hint and trailing slot. 48dp touch target. */
@Composable
internal fun PocketMenuItem(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    selected: Boolean = false, hint: String? = null, glyph: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    PRow(label, onClick, modifier, icon, hint, enabled, selected, glyph = glyph, trailing = trailing)
}

/** The refresh arrows of a row, making one eased turn each time [turns] changes. Same 19dp box as the row's static icon. */
@Composable
internal fun PocketRefreshGlyph(turns: Int) = PPulseGlyph(MotionGlyphs.Refresh, turns, Modifier.size(19.dp))

@Composable
internal fun PocketPageEntrance(key: String, back: Boolean = false, initial: Boolean = true, content: @Composable () -> Unit) {
    // With [initial] off the first key is simply shown: something else has already brought the page in.
    val first = remember { arrayOf<String?>(if (initial) null else key) }
    val progress = remember(key) { Animatable(if (pocketMotionMillis(1) == 0 || first[0] == key) 1f else 0f) }
    // Push comes in from the right; returning to a parent page settles in from a short parallax on the left (iOS).
    val distance = with(LocalDensity.current) { (if (back) -18 else 28).dp.toPx() }
    // Spring, not a fixed tween: fast start, gentle settle, no visible end "stop".
    LaunchedEffect(key) { if (pocketMotionMillis(1) == 0) progress.snapTo(1f) else progress.animateTo(1f, spring(dampingRatio = .9f, stiffness = 420f)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value.coerceIn(0f, 1f); translationX = distance * (1f - progress.value) }) { content() }
}

@Composable
internal fun PocketSheet(title: String, onDismiss: () -> Unit, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (LocalPocketSheetAsScreen.current) {
        PocketScreen(title, onDismiss, subtitle, content)
        return
    }
    PocketModal(title, onDismiss, sheet = true) { handle ->
        Column(handle.fillMaxWidth()) {
            // Grabber: the sheet can be pulled down to dismiss.
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(Pocket.colors.line))
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PText(title, style = Pocket.type.title, modifier = Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) PText(subtitle, style = Pocket.type.labelSmall,
                        color = Pocket.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SheetCloseButton(onDismiss)
            }
        }
        Column(Modifier.weight(1f, fill = false), content = content)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SheetCloseButton(onClick: () -> Unit) = PSheetClose(onClick)

@Composable
internal fun PocketSectionLabel(text: String) {
    PText(text, style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp).semantics { heading() })
}

@Composable
internal fun PocketTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp), content: @Composable RowScope.() -> Unit) {
    PTextAction(onClick, modifier, enabled, contentPadding = contentPadding) {
        // Callers still composing Material text inherit the same ink and label style.
        CompositionLocalProvider(LocalPInk provides Pocket.ink) {
            ProvidePTextStyle(Pocket.type.label) { content() }
        }
    }
}

@Composable
internal fun PocketActionRow(title: String, icon: ImageVector, onClick: () -> Unit, subtitle: String? = null,
    enabled: Boolean = true, selected: Boolean = false, destructive: Boolean = false, glyph: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null) {
    PRow(title, onClick, Modifier.heightIn(min = 50.dp), icon, subtitle, enabled, selected, destructive, accentSelection = true, glyph = glyph, trailing = trailing)
}

@Composable
internal fun PocketButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, secondary: Boolean = false, destructive: Boolean = false) {
    PButton(text, onClick, modifier, if (secondary) PButtonKind.Secondary else if (destructive) PButtonKind.Danger else PButtonKind.Primary, enabled)
}

@Composable
internal fun PocketPill(text: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    PPill(text, selected, onClick, modifier, enabled)
}

@Composable
internal fun PocketTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null,
    placeholder: String? = null, enabled: Boolean = true, singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None, keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    PTextField(value, onValueChange, modifier, label, placeholder, enabled, singleLine, minLines, maxLines, visualTransformation, keyboardOptions)
}

@Composable
internal fun PocketToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    PSwitch(checked, onCheckedChange, modifier, enabled)
}

@Composable
internal fun PocketCheck(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    PCheck(checked, onCheckedChange, modifier, enabled)
}

@Composable
internal fun PocketDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)? = null) {
    PocketModal("确认操作", onDismissRequest, sheet = false) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ProvidePTextStyle(Pocket.type.titleLarge) { title() } }
                ActionIcon(PocketIcons.Close, "关闭", onClick = onDismissRequest)
            }
            if (text != null) Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { text() }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dismissButton?.invoke(); confirmButton()
            }
        }
    }
}
