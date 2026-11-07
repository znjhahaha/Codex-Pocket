@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codexpocket.app.ui

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
import androidx.compose.material3.*
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
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    selected: Boolean = false, hint: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(when { pressed || focused -> c.surfaceContainerHighest.copy(alpha = .8f); selected -> c.surfaceContainerHigh.copy(alpha = .7f); else -> Color.Transparent },
        tween(pocketMotionMillis(110)), label = "menu-item")
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(fill)
        .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(19.dp), tint = if (enabled) c.onSurface else c.onSurfaceVariant.copy(alpha = .6f))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) c.onSurface else c.onSurfaceVariant.copy(alpha = .7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!hint.isNullOrBlank()) Text(hint, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            trailing != null -> trailing()
            selected -> Icon(PocketIcons.Check, null, Modifier.size(17.dp), tint = c.onSurface)
        }
    }
}

@Composable
internal fun PocketPageEntrance(key: String, back: Boolean = false, content: @Composable () -> Unit) {
    val progress = remember(key) { Animatable(if (pocketMotionMillis(1) == 0) 1f else 0f) }
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
                Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SheetCloseButton(onDismiss)
            }
        }
        Column(Modifier.weight(1f, fill = false), content = content)
        Spacer(Modifier.height(8.dp))
    }
}

/** Small gray circle with an x: 48dp target, 30dp visual, like iOS sheet close buttons. */
@Composable
private fun SheetCloseButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .88f else 1f, pocketPressSpring(), label = "sheet-close")
    Box(Modifier.size(48.dp).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "关闭" }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(30.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(PocketIcons.Close, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PocketSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp).semantics { heading() })
}

@Composable
internal fun PocketTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp), content: @Composable RowScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, pocketPressSpring(), label = "text-action-press")
    Row(modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(PocketOval)
        .background(if (pressed || focused) c.surfaceContainerHigh else Color.Transparent)
        .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(contentPadding), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) c.onSurface else c.onSurfaceVariant) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

@Composable
internal fun PocketActionRow(title: String, icon: ImageVector, onClick: () -> Unit, subtitle: String? = null,
    enabled: Boolean = true, selected: Boolean = false, destructive: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, pocketPressSpring(), label = "action-row-press")
    val color by animateColorAsState(if (pressed) c.surfaceContainerHigh else if (selected) c.primaryContainer.copy(alpha = .42f) else Color.Transparent,
        tween(pocketMotionMillis(120)), label = "action-feedback")
    Row(Modifier.fillMaxWidth().graphicsLayer { scaleX = 1f - .02f * press; scaleY = 1f - .02f * press }.clip(RoundedCornerShape(16.dp)).background(color)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
        .heightIn(min = 50.dp).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(19.dp), tint = if (destructive) c.error else if (enabled) c.onSurface else c.onSurfaceVariant.copy(alpha = .6f))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (destructive) c.error else if (enabled) c.onSurface else c.onSurfaceVariant)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
        else if (selected) Icon(PocketIcons.Check, null, Modifier.size(19.dp), tint = c.secondary)
    }
}

@Composable
internal fun PocketButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, secondary: Boolean = false, destructive: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, pocketPressSpring(), label = "button-press")
    val fill = when { !enabled -> c.surfaceContainerHigh; secondary -> c.surfaceContainerLow; destructive -> c.error; else -> c.primary }
    val foreground = when { !enabled -> c.onSurfaceVariant; secondary -> c.onSurface; destructive -> c.onError; else -> c.onPrimary }
    Box(modifier.heightIn(min = 50.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(PocketOval).background(fill)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = foreground, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun PocketPill(text: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, pocketPressSpring(), label = "pill-press")
    val background by animateColorAsState(if (selected) c.primary else c.surfaceContainerLow, tween(pocketMotionMillis(140)), label = "pill-selection")
    val foreground by animateColorAsState(if (!enabled) c.onSurfaceVariant else if (selected) c.onPrimary else c.onSurface,
        tween(pocketMotionMillis(140)), label = "pill-label")
    Box(modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(PocketOval).background(if (enabled) background else c.surfaceContainerLow)
        .border(if (focused) 2.dp else 0.dp, if (focused) c.secondary else Color.Transparent, PocketOval)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, interactionSource = interaction, indication = null, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}

@Composable
internal fun PocketTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null,
    placeholder: String? = null, enabled: Boolean = true, singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None, keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    val c = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(if (focused) c.secondary.copy(alpha = .7f) else c.outlineVariant.copy(alpha = .5f), tween(pocketMotionMillis(160)), label = "field-focus")
    BasicTextField(value, onValueChange, modifier.fillMaxWidth()
        .semantics { if (label != null) contentDescription = label }, enabled = enabled, singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines, maxLines = if (singleLine) 1 else maxLines,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface), cursorBrush = SolidColor(c.secondary),
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, interactionSource = interactions,
        decorationBox = { inner ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(18.dp))
                    .background(c.surfaceContainerLow).border(1.dp, border, RoundedCornerShape(18.dp)).padding(15.dp)) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    inner()
                }
            }
        })
}

@Composable
internal fun PocketToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    val fraction by animateFloatAsState(if (checked) 1f else 0f, tween(pocketMotionMillis(180)), label = "toggle-thumb")
    val track by animateColorAsState(if (checked) c.primary else c.surfaceContainerHighest, tween(pocketMotionMillis(180)), label = "toggle-track")
    Box(modifier.size(52.dp, 48.dp).then(if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange) else Modifier), contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp, 27.dp).clip(CircleShape).background(track.copy(alpha = if (enabled) 1f else .4f))) {
            Box(Modifier.padding(3.dp).offset(x = (17f * fraction).dp).size(21.dp).background(if (checked) c.onPrimary else c.surface, CircleShape))
        }
    }
}

@Composable
internal fun PocketCheck(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    Box(modifier.size(48.dp).then(if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange) else Modifier), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (checked) c.primary else c.surfaceContainerLow)
            .border(1.dp, if (checked) c.primary else c.outline, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            if (checked) Icon(PocketIcons.Check, null, Modifier.size(15.dp), tint = c.onPrimary)
        }
    }
}

@Composable
internal fun PocketDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)? = null) {
    PocketModal("确认操作", onDismissRequest, sheet = false) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() } }
                ActionIcon(PocketIcons.Close, "关闭", onClick = onDismissRequest)
            }
            if (text != null) Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { text() }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dismissButton?.invoke(); confirmButton()
            }
        }
    }
}
