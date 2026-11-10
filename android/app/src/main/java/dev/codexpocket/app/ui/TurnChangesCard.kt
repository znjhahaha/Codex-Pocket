package dev.codexpocket.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
    return cards
}

@Composable
internal fun TurnChangesCard(summary: TurnChangeSummary, onDetail: (TimelineItem) -> Unit) {
    var shown by rememberSaveable(summary.turnId) { mutableIntStateOf(3) }
    val colors = MaterialTheme.colorScheme
    val (add, remove) = pocketDiffColors()
    Surface(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("turn-changes:${summary.turnId}"),
        shape = RoundedCornerShape(16.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .6f))) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(PocketIcons.Description, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text("已编辑 ${summary.files.size} 个文件", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (summary.counted.isNotEmpty()) {
                            Text("+${summary.additions}", color = add, style = MaterialTheme.typography.labelMedium)
                            Text("−${summary.deletions}", color = remove, style = MaterialTheme.typography.labelMedium)
                        }
                        if (summary.hasUnknownCounts) Text("部分行数未提供", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    if (!summary.cumulative) Text("按已记录编辑统计", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                PocketTextButton({ onDetail(summary.review()) }) { Text("查看变更", maxLines = 1) }
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
            summary.files.take(shown).forEach { file ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onDetail(summary.review(file)) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.path.replace('\\', '/'), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (changeLineCountsKnown(file)) {
                        Text("+${file.additions}", color = add, style = MaterialTheme.typography.labelSmall)
                        Text("−${file.deletions}", color = remove, style = MaterialTheme.typography.labelSmall)
                    } else Text(if (file.kind == "rename") "重命名" else "查看", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            if (summary.files.size > 3) Row(Modifier.padding(horizontal = 6.dp)) {
                PocketTextButton({ shown = if (shown >= summary.files.size) 3 else (shown + 30).coerceAtMost(summary.files.size) }) {
                    Text(if (shown >= summary.files.size) "收起文件" else "再显示 ${minOf(30, summary.files.size - shown)} 个文件")
                    Icon(if (shown >= summary.files.size) PocketIcons.ExpandLess else PocketIcons.ExpandMore, null, Modifier.size(18.dp))
                }
            }
        }
    }
}
