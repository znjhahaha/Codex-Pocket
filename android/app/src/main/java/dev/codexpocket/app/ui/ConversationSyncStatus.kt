package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Supplied by the transcript owner; the header keeps the same bounds while data refreshes. */
internal val LocalConversationSyncLabel = staticCompositionLocalOf<String?> { null }

@Composable
internal fun ConversationSyncStatus(label: String, compact: Boolean = false) {
    Row(Modifier.testTag("conversation-sync").semantics(mergeDescendants = true) {
        liveRegion = LiveRegionMode.Polite
        if(label!="离线"&&label!="等待本轮结束") progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        if (compact) contentDescription = label
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ActivityMotionIcon(ActivityMotion.Connection,label!="离线"&&label!="等待本轮结束",Modifier.size(13.dp))
        if (!compact) PText(label, style = Pocket.type.labelSmall,
            color = Pocket.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
