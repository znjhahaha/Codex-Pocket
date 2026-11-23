package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The page's own bar, as every pushed page has it: the way out, then the title. */
@Composable
internal fun CreationHeader(title: String, icon: ImageVector, enabled: Boolean, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(icon, "关闭", enabled, onClose)
        PText(title, Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() }, style = Pocket.type.titleLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Which computer the conversation will start on, and whether it can be reached. */
@Composable
internal fun CreationComputer(name: String, online: Boolean, modifier: Modifier = Modifier) {
    val c = Pocket.colors
    val connectivity by animateColorAsState(if (online) c.positive else c.danger,
        PocketMotion.color<Color>().orSnap(rememberPocketMotion()), label = "project-connectivity")
    Row(modifier.fillMaxWidth().height(48.dp).clip(PocketRadii.capsule).background(c.sunken).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PIcon(PocketIcons.Computer, null, Modifier.size(17.dp), tint = c.inkMuted)
        PText(name.ifBlank { "当前电脑" }, Modifier.weight(1f), style = Pocket.type.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(6.dp).background(connectivity, CircleShape))
        PSwapText(if (online) "已连接" else "未连接", style = Pocket.type.labelSmall, color = c.inkMuted, contentAlignment = Alignment.CenterEnd)
    }
}

/** An existing directory or a new one. The selection slides and can be dragged; it is silent, the page says what changed. */
@Composable
internal fun CreationDirectorySwitch(selected: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    PSegmented(listOf("已有目录", "新建目录"), if (selected) 1 else 0, { onSelect(it == 1) }, enabled = enabled,
        icons = listOf(PocketIcons.FolderOpen, PocketIcons.Add), itemHeight = 48.dp, haptic = null)
}

/** A folder in a round well. It tips and settles each time [pulse] changes, and takes the accent when it is the chosen one. */
@Composable
internal fun CreationFolderBadge(selected: Boolean, modifier: Modifier = Modifier, icon: ImageVector = PocketIcons.FolderOpen, pulse: Any? = selected) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val well by animateColorAsState(if (selected) c.accentSoft else c.sunken, PocketMotion.color<Color>().orSnap(motion), label = "folder-well")
    val ink by animateColorAsState(if (selected) c.accent else c.inkMuted, PocketMotion.color<Color>().orSnap(motion), label = "folder-ink")
    val tip = remember { Animatable(0f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(pulse, motion) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (!motion || !selected) { tip.snapTo(0f); return@LaunchedEffect }
        tip.snapTo(1f)
        tip.animateTo(0f, PocketSprings.bouncy(.42f))
    }
    Box(modifier.size(38.dp).background(well, CircleShape), contentAlignment = Alignment.Center) {
        PIcon(icon, null, Modifier.size(19.dp).graphicsLayer {
            rotationZ = -tip.value * 12f
            scaleX = 1f + tip.value * .1f; scaleY = scaleX
        }, tint = ink)
    }
}

/** One thing that can be chosen: its badge, its name, where it is, and a tick that arrives when it is the one. */
@Composable
internal fun CreationChoiceRow(title: String, subtitle: String?, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    icon: ImageVector = PocketIcons.FolderOpen, onClick: () -> Unit) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val fill by animateColorAsState(if (selected) c.accentSoft.copy(alpha = .42f) else Color.Transparent,
        PocketMotion.color<Color>().orSnap(motion), label = "choice-fill")
    val tick by animateFloatAsState(if (selected) 1f else 0f, PocketMotion.glyph<Float>().orSnap(motion), label = "choice-tick")
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).graphicsLayer { alpha = if (enabled) 1f else .5f }.clip(PocketRadii.control).background(fill)
        .pocketSelectable(selected, enabled, Role.RadioButton, pressedScale = .985f, highlight = true, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CreationFolderBadge(selected, icon = icon)
        Column(Modifier.weight(1f)) {
            PText(title, style = Pocket.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) PText(subtitle, style = Pocket.type.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PIcon(PocketIcons.Check, null, Modifier.size(18.dp).graphicsLayer { alpha = tick.coerceIn(0f, 1f); scaleX = .5f + .5f * tick; scaleY = scaleX },
            tint = c.accent)
    }
}

/** A quiet action inside a group: a glyph that answers the tap, and a label. */
@Composable
internal fun CreationInlineAction(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val taps = rememberPTaps()
    PTextAction(taps.then(onClick), modifier, enabled) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            PTapIcon(icon, taps.count, Modifier.size(16.dp))
            PSwapText(label, style = Pocket.type.labelSmall)
        }
    }
}

/**
 * The page's one decision. The label keeps to the left and what will happen sits at the right: an
 * arrow that leans forward when pressed, and a turning arc for as long as the work is in flight.
 */
@Composable
internal fun CreationSubmit(label: String, busy: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val live = enabled || busy
    val fill by animateColorAsState(if (live) c.solid else c.fillStrong, PocketMotion.color<Color>().orSnap(motion), label = "submit-fill")
    val ink by animateColorAsState(if (live) c.onSolid else c.inkMuted, PocketMotion.color<Color>().orSnap(motion), label = "submit-ink")
    val taps = rememberPTaps()
    Row(modifier.fillMaxWidth().heightIn(min = 54.dp).clip(PocketRadii.capsule).background(fill)
        .pocketPressable(enabled, Role.Button, haptic = PocketHaptic.Tap, tint = c.onSolid, onClick = taps.then(onClick))
        .padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProvidePInk(ink, Pocket.type.label) {
            PSwapText(label, Modifier.weight(1f))
            // Turns only while the request is in flight; the arrow is back as soon as it ends.
            if (busy) PLoopGlyph(MotionGlyphs.Spin, true, Modifier.size(20.dp), ink, millis = 900)
            else PTapIcon(PocketIcons.ArrowForward, taps.count, Modifier.size(20.dp))
        }
    }
}
