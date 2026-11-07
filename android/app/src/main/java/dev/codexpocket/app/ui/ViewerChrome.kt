package dev.codexpocket.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "src/ui/App.kt" → ("App.kt", "src/ui"). Header shows the name bold and the folder quietly underneath. */
internal fun splitPath(path: String): Pair<String, String> {
    val normal = path.replace('\\', '/').trimEnd('/')
    val name = normal.substringAfterLast('/')
    val folder = normal.substringBeforeLast('/', "")
    return name.ifBlank { path } to folder
}

/** Extension colour, the way GitHub's linguist and VS Code's file icons tint by language. */
private fun badgeColor(ext: String): Color = when (ext) {
    "kt", "kts" -> Color(0xFF7F52FF)
    "java" -> Color(0xFFB07219)
    "js", "mjs", "cjs" -> Color(0xFFD4B400)
    "ts", "tsx" -> Color(0xFF3178C6)
    "py" -> Color(0xFF3572A5)
    "rs" -> Color(0xFFDEA584)
    "go" -> Color(0xFF00ADD8)
    "md", "markdown" -> Color(0xFF6E7781)
    "json", "yaml", "yml", "toml" -> Color(0xFFCB6C33)
    "html", "xml", "svg" -> Color(0xFFE34C26)
    "css", "scss" -> Color(0xFF663399)
    "c", "h", "cpp", "hpp", "cc" -> Color(0xFF555599)
    "cs" -> Color(0xFF178600)
    "swift" -> Color(0xFFF05138)
    "sql" -> Color(0xFFE38C00)
    "ps1", "sh", "bash", "bat" -> Color(0xFF4EAA25)
    else -> Color(0xFF8C959F)
}

@Composable
internal fun FileBadge(path: String, size: androidx.compose.ui.unit.Dp = 30.dp) {
    val ext = path.substringAfterLast('.', "").lowercase().take(4)
    val tint = badgeColor(ext)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .3f)).background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
        if (ext.isBlank()) Icon(PocketIcons.Description, null, Modifier.size(size * .55f), tint = tint)
        else Text(ext.uppercase(), color = tint, fontSize = if (ext.length > 3) 8.sp else 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/**
 * Two or three options in one capsule; the selected thumb slides between them on a spring, as
 * UISegmentedControl and Radix tabs do. Labels stay plain text so tests and TalkBack find them.
 */
@Composable
internal fun PocketSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var widths by remember(options) { mutableStateOf(List(options.size) { 0 }) }
    val target = with(density) { widths.take(selected).sum().toDp() }
    val width = with(density) { (widths.getOrNull(selected) ?: 0).toDp() }
    val motion = if (pocketMotionMillis(1) == 0) androidx.compose.animation.core.snap() else spring<androidx.compose.ui.unit.Dp>(dampingRatio = .78f, stiffness = 520f)
    val offset by animateDpAsState(target, motion, label = "segment-x")
    val thumb by animateDpAsState(width, motion, label = "segment-w")
    Box(modifier.clip(PocketCapsule).background(c.surfaceContainerHigh).padding(3.dp)) {
        if (widths.any { it > 0 }) Box(Modifier.offset(x = offset).width(thumb).height(34.dp).shadow(1.dp, PocketCapsule).clip(PocketCapsule).background(c.surface))
        Row {
            options.forEachIndexed { index, label ->
                val active = index == selected
                val color by animateColorAsState(if (active) c.onSurface else c.onSurfaceVariant, tween(pocketMotionMillis(140)), label = "segment-text")
                Box(Modifier.height(34.dp).onSizeChanged { size -> if (widths.getOrNull(index) != size.width) widths = widths.toMutableList().also { it[index] = size.width } }
                    .clip(PocketCapsule).clickable(role = Role.Tab) { onSelect(index) }.semantics { this.selected = active }
                    .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

/** Icon + short label capsule that tints when on. Used for wrap / search toggles in the viewers. */
@Composable
internal fun ViewerToggle(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, description: String = label) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .92f else 1f, pocketPressSpring(), label = "viewer-toggle")
    val fill by animateColorAsState(if (on) c.secondaryContainer else c.surfaceContainerHigh, tween(pocketMotionMillis(160)), label = "viewer-toggle-fill")
    val tint by animateColorAsState(if (on) c.onSecondaryContainer else c.onSurfaceVariant, tween(pocketMotionMillis(160)), label = "viewer-toggle-tint")
    Row(modifier.height(36.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(PocketCapsule).background(fill)
        .clickable(interactionSource = interaction, indication = null, role = Role.Switch, onClick = onClick)
        .semantics { contentDescription = description; selected = on }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

/** Floating "change 2 of 5" capsule with up/down, pinned bottom-end over the diff. */
@Composable
internal fun ChangeNavigator(current: Int, total: Int, onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(modifier, shape = PocketCapsule, color = c.surfaceContainerHighest, shadowElevation = 0.dp, tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(.5.dp, c.outlineVariant.copy(alpha = .7f))) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(PocketIcons.ArrowUpward, "上一处变更", current > 0, onPrevious)
            androidx.compose.animation.AnimatedContent(current, label = "change-index", transitionSpec = {
                val up = targetState > initialState
                (androidx.compose.animation.slideInVertically { if (up) it else -it } + androidx.compose.animation.fadeIn()) togetherWith
                    (androidx.compose.animation.slideOutVertically { if (up) -it else it } + androidx.compose.animation.fadeOut())
            }) { shown -> Text("${shown + 1}/$total", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace) }
            ActionIcon(PocketIcons.ArrowDownward, "下一处变更", current < total - 1, onNext)
        }
    }
}

/** Thin top bar that fills as the reader scrolls (web article progress indicator). */
@Composable
internal fun ReadingProgress(fraction: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(pocketMotionMillis(120)), label = "reading-progress")
    Box(modifier.fillMaxWidth().height(2.dp)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(MaterialTheme.colorScheme.primary.copy(alpha = .7f)))
    }
}

/** GitHub's five-square change bar: green for additions, red for deletions, proportional. */
@Composable
internal fun DiffStatBar(additions: Int, deletions: Int) {
    val (add, remove) = pocketDiffColors()
    val total = (additions + deletions).coerceAtLeast(1)
    val green = if (additions + deletions == 0) 0 else ((additions * 5f) / total).let { kotlin.math.round(it).toInt() }.coerceIn(if (additions > 0) 1 else 0, if (deletions > 0) 4 else 5)
    val red = if (deletions == 0) 0 else (5 - green).coerceAtMost(5)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(5) { i ->
            val color = when { i < green -> add; i < green + red -> remove; else -> MaterialTheme.colorScheme.outlineVariant }
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(1.5.dp)).background(color))
        }
    }
}
