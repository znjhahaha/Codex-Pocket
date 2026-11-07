package dev.codexpocket.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.ConversationPresentation.LiveStatus
import dev.codexpocket.app.data.TimelineItem
import kotlinx.coroutines.delay

/** A quiet observation clock. The server does not supply a trustworthy turn start timestamp. */
internal fun elapsedLabel(seconds: Long): String {
    val time = seconds.coerceAtLeast(0)
    val minute = (time / 60 % 60).toString().padStart(2, '0')
    val second = (time % 60).toString().padStart(2, '0')
    return if (time < 3600) "${time / 60}:$second" else "${time / 3600}:$minute:$second"
}

internal fun liveStatusLabel(status: LiveStatus): String = when (status) {
    LiveStatus.Thinking -> "正在思考"
    LiveStatus.Compacting -> "正在压缩上下文 · 可能需要几分钟"
    LiveStatus.Working -> "正在处理"
}

/**
 * Codex's running-turn footer, adapted for a phone: the elapsed time and a hairline, then one shimmering
 * status line. Time counts from when this phone first saw the turn running.
 */
@Composable
internal fun LiveTurnStatus(turnId: String, status: LiveStatus, modifier: Modifier = Modifier,
    reasoning: TimelineItem? = null, observedAtMillis: Long? = null) {
    val c = MaterialTheme.colorScheme
    val started = observedAtMillis ?: remember(turnId) { android.os.SystemClock.elapsedRealtime() }
    var seconds by remember(turnId) { mutableLongStateOf(0L) }
    var expanded by rememberSaveable(turnId) { mutableStateOf(true) }
    val interaction = remember { MutableInteractionSource() }
    val angle by animateFloatAsState(if (expanded) 180f else 0f, pocketPressSpring(), label = "reasoning-chevron")
    LaunchedEffect(turnId, started) {
        while (true) {
            seconds = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0) / 1000
            delay(1000)
        }
    }
    Column(modifier.fillMaxWidth().testTag("live-turn-status").padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (reasoning != null) Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button) { expanded = !expanded }
                .testTag("live-reasoning-toggle").semantics { stateDescription = if (expanded) "已展开" else "已收起" } else Modifier)
            .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedContent(status, modifier = Modifier.weight(1f, fill = false), transitionSpec = {
            fadeIn(tween(pocketMotionMillis(160))) togetherWith fadeOut(tween(pocketMotionMillis(100)))
        }, label = "live-status") { shown ->
            Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(if (shown == LiveStatus.Compacting) PocketIcons.FoldVertical else PocketIcons.Sparkle, null, Modifier.size(15.dp), tint = c.onSurfaceVariant)
                ShimmerText(liveStatusLabel(shown), MaterialTheme.typography.bodyMedium)
            }
        }
        if (reasoning != null) Icon(PocketIcons.KeyboardArrowDown, null, Modifier.size(14.dp).rotate(angle), tint = c.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(elapsedLabel(seconds), Modifier.semantics { contentDescription = "本次查看时长 ${elapsedLabel(seconds)}" },
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = c.onSurfaceVariant)
        }
        AnimatedVisibility(reasoning != null && expanded,
            enter = fadeIn(tween(pocketMotionMillis(160))) + expandVertically(tween(pocketMotionMillis(220))),
            exit = fadeOut(tween(pocketMotionMillis(100))) + shrinkVertically(tween(pocketMotionMillis(180)))) {
            // Bounded independent scrolling keeps a long native summary from moving the whole transcript.
            Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                .padding(start = 27.dp, end = 4.dp, bottom = 10.dp).testTag("live-reasoning-summary")) {
                reasoning?.let { MarkdownText(it.text, "live-summary:$turnId:${it.itemId}") }
            }
        }
    }
}

/** Gray text with a light band sweeping through the glyphs, as Codex's "正在思考". Plain gray when motion is off. */
@Composable
internal fun ShimmerText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val base = c.onSurfaceVariant.copy(alpha = .75f)
    if (pocketMotionMillis(1) == 0) { Text(text, modifier, style = style, color = base, maxLines = 1); return }
    val bright = c.onSurface
    val x by rememberInfiniteTransition(label = "shimmer-text").animateFloat(-.3f, 1.3f,
        infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "shimmer-text-x")
    var width by remember { mutableFloatStateOf(1f) }
    val band = width * .35f
    val center = width * x
    Text(text, modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }, maxLines = 1,
        style = style.copy(brush = Brush.linearGradient(listOf(base, bright, base), start = Offset(center - band, 0f), end = Offset(center + band, 0f))))
}
