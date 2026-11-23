package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberTurnChangeCards(timeline: NativeTimeline, rows: List<TranscriptRow>): Map<String, List<TurnChangeSummary>> {
    val cards by produceState(emptyMap<String, List<TurnChangeSummary>>(), timeline, rows) {
        value = withContext(Dispatchers.Default) { changeSummaryRows(timeline, rows) }
    }
    // produceState retains the previous value while recomputing. Never leave a
    // terminal card visible for a turn the fresh snapshot now reports running.
    return cards.mapValues { (_, summaries) -> summaries.filter { summary ->
        summary.turnId != timeline.activeTurn && timeline.turns[summary.turnId]?.status != "inProgress"
    } }.filterValues { it.isNotEmpty() }
}

@Composable
internal fun TurnChangesCard(summary: TurnChangeSummary, onDetail: (TimelineItem) -> Unit) {
    var shown by rememberSaveable(summary.turnId) { mutableIntStateOf(3) }
    val colors = Pocket.colors
    val (add, remove) = pocketDiffColors()
    PSurface(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("turn-changes:${summary.turnId}"),
        shape = RoundedCornerShape(16.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.line.copy(alpha = .6f))) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PIcon(PocketIcons.Description, null, Modifier.size(22.dp), tint = colors.inkMuted)
                Column(Modifier.weight(1f)) {
                    PText("已编辑 ${summary.files.size} 个文件", style = Pocket.type.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (summary.counted.isNotEmpty()) {
                            PText("+${summary.additions}", color = add, style = Pocket.type.labelSmall)
                            PText("−${summary.deletions}", color = remove, style = Pocket.type.labelSmall)
                        }
                        if (summary.hasUnknownCounts) PText("部分行数未提供", style = Pocket.type.caption, color = colors.inkMuted)
                    }
                    if (!summary.cumulative) PText("按已记录编辑统计", style = Pocket.type.caption, color = colors.inkMuted)
                }
                PocketTextButton({ onDetail(summary.review()) }) { PText("查看变更", maxLines = 1) }
            }
            PDivider(color = colors.line.copy(alpha = .5f))
            summary.files.take(shown).forEach { file ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onDetail(summary.review(file)) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PText(file.path.replace('\\', '/'), Modifier.weight(1f), style = Pocket.type.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (changeLineCountsKnown(file)) {
                        PText("+${file.additions}", color = add, style = Pocket.type.caption)
                        PText("−${file.deletions}", color = remove, style = Pocket.type.caption)
                    } else PText(if (file.kind == "rename") "重命名" else "查看", style = Pocket.type.caption, color = colors.inkMuted)
                }
            }
            if (summary.files.size > 3) Row(Modifier.padding(horizontal = 6.dp)) {
                PocketTextButton({ shown = if (shown >= summary.files.size) 3 else (shown + 30).coerceAtMost(summary.files.size) }) {
                    PText(if (shown >= summary.files.size) "收起文件" else "再显示 ${minOf(30, summary.files.size - shown)} 个文件")
                    PIcon(if (shown >= summary.files.size) PocketIcons.ExpandLess else PocketIcons.ExpandMore, null, Modifier.size(18.dp))
                }
            }
        }
    }
}
