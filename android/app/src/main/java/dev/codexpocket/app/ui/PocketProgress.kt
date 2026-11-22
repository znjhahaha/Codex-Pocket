package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.kit.PDots
import dev.codexpocket.app.ui.kit.PRing
import dev.codexpocket.app.ui.kit.PText
import dev.codexpocket.app.ui.kit.Pocket
import dev.codexpocket.app.ui.kit.pocketSkeleton

/** Soft highlight sweeping across a placeholder, as skeleton screens on the web do. Flat when motion is off. */
@Composable
internal fun PocketShimmer(modifier: Modifier = Modifier) {
    Box(modifier.pocketSkeleton())
}

/**
 * Three dots breathing in sequence (iMessage typing / Vercel loading-dots). Each dot rises,
 * brightens and grows slightly on a 1.1s cycle offset by 140ms. Still dots when motion is off.
 */
@Composable
internal fun PocketLoader(modifier: Modifier = Modifier, dot: Dp = 5.dp, color: Color = Pocket.colors.inkMuted) {
    PDots(modifier, dot, color)
}

/** Determinate ring with rounded caps; the arc eases toward its value instead of jumping. */
@Composable
internal fun PocketRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 2.dp) {
    PRing(progress, modifier, stroke)
}

/** Soft light sweep across a placeholder (1.3s), the skeleton pattern used by iOS and shadcn. Clip first. */
internal fun Modifier.pocketShimmer(): Modifier = pocketSkeleton()

/** Small feedback owned by the control doing work, with no invented percentage. */
@Composable
internal fun PocketActivityIndicator(label: String) {
    PocketLoader(Modifier.semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }, dot = 4.dp)
}

/** Null means an unknown proportion. Never manufacture a percentage from connection phases. */
@Composable
internal fun PocketProgressStatus(label: String, modifier: Modifier = Modifier, progress: Float? = null) {
    val measured = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    Row(modifier.widthIn(max = 360.dp).semantics(mergeDescendants = true) {
        liveRegion = LiveRegionMode.Polite
        progressBarRangeInfo = measured?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (measured != null) PocketRing(measured, Modifier.size(16.dp)) else PocketLoader()
        PText(label, Modifier.weight(1f, fill = false), style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted)
        if (measured != null) PText("${(measured * 100).toInt()}%", style = Pocket.type.caption)
    }
}
