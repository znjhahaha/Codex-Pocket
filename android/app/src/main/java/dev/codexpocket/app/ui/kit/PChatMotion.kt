package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Grows out of the corner a bubble hangs by, with a small bounce. Shown as it is when motion is off. */
@Composable
internal fun Modifier.pocketBubblePop(origin: TransformOrigin = TransformOrigin(0f, 1f), delayMillis: Long = 0): Modifier {
    val motion = rememberPocketMotion()
    val shown = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (shown.value < 1f) { pocketPause(delayMillis); shown.animateTo(1f, PocketSprings.bouncy(.42f)) } }
    return graphicsLayer {
        val t = shown.value
        if (t >= 1f && !shown.isRunning) return@graphicsLayer
        alpha = (t * 2.2f).coerceIn(0f, 1f)
        val size = .55f + .45f * t
        scaleX = size; scaleY = size
        transformOrigin = origin
    }
}

/**
 * Someone is writing: three dots rising in turn inside a bubble that pops out of its corner.
 * [label] sits beside the dots for the cases that say what is happening.
 */
@Composable
internal fun PTypingBubble(modifier: Modifier = Modifier, label: String? = null, outgoing: Boolean = false,
    fill: Color = Pocket.colors.fillStrong, ink: Color = Pocket.colors.inkMuted) {
    Row(modifier.pocketBubblePop(if (outgoing) TransformOrigin(1f, 1f) else TransformOrigin(0f, 1f))
        .clip(RoundedCornerShape(20.dp)).background(fill).padding(horizontal = 14.dp, vertical = 11.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PDots(dot = 6.dp, color = ink)
        if (label != null) PText(label, style = Pocket.type.bodySmall, color = ink)
    }
}

/**
 * A conversation that is still being read, drawn as the exchange it is about to become: a request on
 * the trailing side, lines of an answer, a line of work. The shapes arrive one after another and a
 * light crosses them while they wait. [label] is announced once and shown beside Pocket's own loader.
 */
@Composable
internal fun PChatSkeleton(modifier: Modifier = Modifier, label: String? = null, exchanges: Int = 2) {
    val entrance = rememberPocketEntrance(windowMillis = 600)
    var index = 0
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (label != null) Row(Modifier.pocketEntrance(entrance, index++).semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            PBrandLoader(Modifier.size(18.dp))
            PText(label, style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted)
        }
        repeat(exchanges) { round ->
            Box(Modifier.fillMaxWidth().padding(top = if (round == 0) 0.dp else 10.dp), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.pocketEntrance(entrance, index++).fillMaxWidth(if (round == 0) .46f else .62f).height(38.dp)
                    .clip(RoundedCornerShape(19.dp)).pocketSkeleton())
            }
            (if (round == 0) listOf(.92f, .84f, .58f) else listOf(.88f, .7f)).forEach { fraction ->
                SkeletonLine(Modifier.pocketEntrance(entrance, index++), fraction, 12.dp)
            }
            SkeletonLine(Modifier.pocketEntrance(entrance, index++), .34f, 10.dp)
        }
    }
}

@Composable
private fun SkeletonLine(modifier: Modifier, fraction: Float, height: Dp) {
    Box(modifier.fillMaxWidth(fraction).height(height).clip(RoundedCornerShape(height / 2)).pocketSkeleton())
}

/** A message on its way out: an outlined bubble on the trailing side with the dots of something in progress. */
@Composable
internal fun PSendingBubble(label: String, modifier: Modifier = Modifier) {
    val c = Pocket.colors
    val shape = RoundedCornerShape(20.dp)
    Row(modifier.pocketBubblePop(TransformOrigin(1f, 1f)).clip(shape).border(1.dp, c.line, shape).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PText(label, style = Pocket.type.bodySmall, color = c.inkMuted)
        PDots(dot = 4.dp, color = c.inkMuted)
    }
}
