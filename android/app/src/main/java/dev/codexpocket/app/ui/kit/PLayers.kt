package dev.codexpocket.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.PocketIcons
import dev.codexpocket.app.ui.PocketModal

/**
 * A sheet rising from the bottom edge inside the app's own window. Pull the header down, tap
 * outside or press Back to dismiss. Glass belongs to the panel; the content inside stays legible.
 */
@Composable
internal fun PSheet(title: String, onDismiss: () -> Unit, subtitle: String? = null, maxHeightFraction: Float = .55f,
    content: @Composable ColumnScope.() -> Unit) {
    PocketModal(title, onDismiss, sheet = true, maxHeightFraction = maxHeightFraction) { handle ->
        Column(handle.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(Pocket.colors.line))
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PText(title, Modifier.semantics { heading() }, style = Pocket.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) PText(subtitle, style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PSheetClose(onDismiss)
            }
        }
        Column(Modifier.weight(1f, fill = false), content = content)
        Spacer(Modifier.height(8.dp))
    }
}

/** A small gray circle with a cross: 48dp target, 30dp visual. */
@Composable
internal fun PSheetClose(onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).pocketPressable(role = Role.Button, pressedScale = .88f, highlight = false, onClick = onClick)
        .semantics { contentDescription = "关闭" }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Pocket.colors.fillStrong), contentAlignment = Alignment.Center) {
            PIcon(PocketIcons.Close, null, Modifier.size(15.dp), tint = Pocket.colors.inkMuted)
        }
    }
}

/** A centered decision. The confirming action is last, so it sits at the trailing edge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PDialog(title: String, onDismiss: () -> Unit, confirmLabel: String, onConfirm: () -> Unit, text: String? = null,
    dismissLabel: String? = "取消", destructive: Boolean = false, confirmEnabled: Boolean = true, busy: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null) {
    PocketModal(title, onDismiss, sheet = false) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PText(title, Modifier.semantics { heading() }, style = Pocket.type.titleLarge)
            if (text != null || content != null) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (text != null) PText(text, style = Pocket.type.body, color = Pocket.colors.inkMuted)
                content?.invoke(this)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dismissLabel != null) PButton(dismissLabel, onDismiss, kind = PButtonKind.Secondary, haptic = null)
                PButton(confirmLabel, onConfirm, kind = if (destructive) PButtonKind.Danger else PButtonKind.Primary,
                    enabled = confirmEnabled, busy = busy, haptic = if (destructive) PocketHaptic.Reject else PocketHaptic.Confirm)
            }
        }
    }
}

/** Where a menu grows from. Attach [pocketAnchor] to the control that opens it. */
@Stable
internal class PAnchor { var bounds by mutableStateOf<Rect?>(null) }

@Composable
internal fun rememberPAnchor() = remember { PAnchor() }

internal fun Modifier.pocketAnchor(anchor: PAnchor): Modifier = onGloballyPositioned { anchor.bounds = it.boundsInRoot() }

/** A compact popover anchored to its control. No title bar: outside tap, Back or Escape dismisses. */
@Composable
internal fun PMenu(title: String, onDismiss: () -> Unit, anchor: PAnchor, widthDp: Float = 280f, alignStart: Boolean = false,
    below: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    PocketModal(title, onDismiss, sheet = false, anchor = anchor.bounds, anchored = true, menuWidth = widthDp, alignStart = alignStart, below = below) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(6.dp), content = content)
    }
}
