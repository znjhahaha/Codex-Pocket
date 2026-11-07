package dev.codexpocket.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.ProposedPlan

/** The affirmative choice submits once; freeform feedback uses the retained composer. */
@Composable
internal fun PlanConfirmation(plan: ProposedPlan, reason: String?, onImplement: () -> Unit, onModify: () -> Unit,
    modifier: Modifier = Modifier) {
    var editing by androidx.compose.runtime.saveable.rememberSaveable(plan.revision) { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().testTag("plan-confirmation"), shape = RoundedCornerShape(18.dp), color = c.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PocketIcons.Checklist, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Text(if (editing) "补充你希望修改的内容" else "是否实施此计划？", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                if (editing) QuietAction(PocketIcons.ArrowBack, "返回方案确认") { editing = false }
            }
            if (!editing) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plan-implement")
                    .clickable(enabled = reason == null, role = Role.Button, onClick = onImplement)
                    .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(PocketIcons.ArrowUpward, null, Modifier.size(18.dp), tint = if (reason == null) c.onSurface else c.onSurfaceVariant)
                    Text("是，实施此计划", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = if (reason == null) c.onSurface else c.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plan-modify")
                    .clickable(enabled = reason == null, role = Role.Button) { editing = true; onModify() }
                    .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(PocketIcons.Edit, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
                    Text("提出修改…", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
            if (reason != null) Text(reason, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
