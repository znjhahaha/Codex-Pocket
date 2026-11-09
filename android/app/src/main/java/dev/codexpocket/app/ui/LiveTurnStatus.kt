package dev.codexpocket.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.ConversationPresentation.LiveStatus
import dev.codexpocket.app.data.NativeTurnInfo
import dev.codexpocket.app.data.TimelineItem
import kotlinx.coroutines.delay

internal fun elapsedLabel(seconds: Long): String {
    val time = seconds.coerceAtLeast(0)
    val minute = (time / 60 % 60).toString().padStart(2, '0')
    val second = (time % 60).toString().padStart(2, '0')
    return if (time < 3600) "${time / 60}:$second" else "${time / 3600}:$minute:$second"
}

/** Only an actual native operation may supply a status when there is no summary. */
internal fun liveStatusLabel(status: LiveStatus): String =
    if (status == LiveStatus.Compacting) "正在压缩上下文" else ""

/** Missing or mismatched native timing keeps prose readable but supplies no live-motion proof. */
internal fun nativeSummaryActive(turnId: String, reasoning: TimelineItem?, timing: NativeTurnInfo?): Boolean =
    reasoning?.type == "reasoning" && reasoning.turnId == turnId &&
        timing?.id == turnId && timing.status == "inProgress"

/** Native summary prose, without a thought badge, activity spinner or a transcript clock. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun LiveTurnStatus(turnId: String, status: LiveStatus, modifier: Modifier = Modifier,
    reasoning: TimelineItem? = null, observedAtMillis: Long? = null, timing: NativeTurnInfo? = null) {
    val parts = reasoning?.summaryParts.orEmpty().filter(String::isNotBlank)
    if (parts.isEmpty()) {
        val nativeStatus = liveStatusLabel(status)
        if (nativeStatus.isNotBlank()) Text(nativeStatus, modifier.testTag("live-turn-status").padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var expanded by rememberSaveable(turnId) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val active = nativeSummaryActive(turnId, reasoning, timing)
    Column(modifier.fillMaxWidth().testTag("live-turn-status")) {
        // Tapping replaces the compact prose with its separate paragraphs, in the same location.
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("live-reasoning-toggle")
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开摘要" else "已收起摘要" }
            .padding(vertical = 10.dp)) {
            if (!expanded) NativeSummaryProse(parts.last(), "live-summary:$turnId:${reasoning?.itemId}:last", maxLines = 2,
                active = active)
            androidx.compose.animation.AnimatedVisibility(expanded,
                enter = fadeIn(tween(pocketMotionMillis(160))) + expandVertically(tween(pocketMotionMillis(220))),
                exit = fadeOut(tween(pocketMotionMillis(100))) + shrinkVertically(tween(pocketMotionMillis(180)))) {
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                    .testTag("live-reasoning-summary").padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    parts.forEachIndexed { index, part -> key(turnId, reasoning?.itemId, index) {
                        NativeSummaryProse(part, "live-summary:$turnId:${reasoning?.itemId}:$index",
                            Modifier.testTag("native-summary-paragraph:$index"),
                            active = active && expanded && index == parts.lastIndex)
                    } }
                }
            }
        }
    }
}

/** Markwon parses Markdown off-thread; summary headings and emphasis stay small, muted prose. */
@Composable
internal fun NativeSummaryProse(text: String, messageKey: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE,
    active: Boolean = false) {
    val renderer = LocalMarkdownCache.current
    var prose by remember(renderer, messageKey, text) { mutableStateOf<String?>(null) }
    LaunchedEffect(renderer, messageKey, text) { prose = renderer?.render(messageKey, text)?.spans?.toString() }
    Text(prose ?: remember(text) { markdownPreview(text) }, modifier.reasoningSweep(active && text.isNotBlank()), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Native lifecycle times first. A mid-turn attachment without trusted timing shows no number. */
internal fun taskElapsedSeconds(info: NativeTurnInfo?, nowMillis: Long, nowNanos: Long): Long? {
    info ?: return null
    if (info.status != "inProgress") return info.elapsedMs?.div(1000)
    info.startedAtMs?.takeIf { it >= 0 && it <= nowMillis }?.let { return (nowMillis - it) / 1000 }
    info.observedStartNanos?.let { start ->
        val elapsed = nowNanos - start
        if (elapsed >= 0) return elapsed / 1_000_000_000
    }
    return info.elapsedMs?.div(1000)
}

/** Header-only clock. Its tick invalidates this label, never the transcript or summary. */
@Composable
internal fun TaskElapsedLabel(info: NativeTurnInfo?, modifier: Modifier = Modifier) {
    var seconds by remember(info) { mutableStateOf(taskElapsedSeconds(info, System.currentTimeMillis(), System.nanoTime())) }
    LaunchedEffect(info) {
        do {
            seconds = taskElapsedSeconds(info, System.currentTimeMillis(), System.nanoTime())
            if (info?.status != "inProgress" || (info.startedAtMs == null && info.observedStartNanos == null)) break
            delay(1000)
        } while (true)
    }
    val elapsed = seconds ?: return
    val observed = info?.observed == true || (info?.startedAtMs == null && info?.observedStartNanos != null)
    Text(elapsedLabel(elapsed), modifier.testTag("task-elapsed-label").semantics {
        contentDescription = (if (observed) "本次观察用时 " else "任务用时 ") + elapsedLabel(elapsed)
    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Soft text feedback for other small controls; summary prose itself remains stable. */
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
