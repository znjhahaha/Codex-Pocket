package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.ActivityStatus
import dev.codexpocket.app.data.NativeActivityPresentation
import dev.codexpocket.app.data.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun NativeActivityCard(item: TimelineItem, messageKey: String, expanded: Boolean, onExpand: () -> Unit, onDetail: () -> Unit, onPlan: ((Boolean) -> Unit)? = null) {
    val plan by produceState<PresentedPlan?>(null, item.raw, item.type) { value = withContext(Dispatchers.Default) { nativePlan(item) } }
    val queue = item.type == "thread/queue/changed"
    val expansionState = stringResource(if (expanded) R.string.v03_expanded else R.string.v03_collapsed)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PocketGlassSurface(modifier = Modifier.widthIn(max = 560.dp).semantics { stateDescription = expansionState }, shape = PocketOval, onClick = onExpand) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (queue) PocketIcons.PendingActions else PocketIcons.Checklist, null, Modifier.size(16.dp))
                Text(listOfNotNull(stringResource(if (queue) R.string.delivery_native_queue else R.string.plan), plan?.activeStep?.text).joinToString(" · "),
                    Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                plan?.let { value ->
                    Text(stringResource(R.string.delivery_plan_progress, value.completed, value.steps.size), Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ActivityExpansionIcon(expanded)
            }
        }
        if (queue) Text(stringResource(R.string.delivery_queue_unavailable), Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (expanded) {
            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val value = plan
                when {
                    item.type == "plan" -> MarkdownText(item.text, messageKey)
                    value != null -> {
                        if (value.explanation.isNotBlank()) MarkdownText(value.explanation, "$messageKey/explanation")
                        value.steps.forEach { step ->
                            val status = NativeActivityPresentation.status(step.status)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                Icon(when (status) {
                                    ActivityStatus.Completed -> PocketIcons.CheckCircle
                                    ActivityStatus.Running -> PocketIcons.PlayCircle
                                    ActivityStatus.Failed -> PocketIcons.ErrorOutline
                                    else -> PocketIcons.RadioButtonUnchecked
                                }, null, Modifier.size(20.dp), tint = when (status) {
                                    ActivityStatus.Running -> MaterialTheme.colorScheme.primary
                                    ActivityStatus.Failed -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(step.text)
                                    Text(activityStatusLabel(status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    !queue -> Text(stringResource(R.string.delivery_native_shape_unknown), style = MaterialTheme.typography.bodySmall)
                }
                PocketTextButton(onClick = onDetail, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(stringResource(R.string.full_details)) }
            }
        }
        if (onPlan != null && !queue) {
            // A column avoids clipping both actions at large accessibility font scales.
            Column(Modifier.padding(horizontal = 8.dp)) {
                PocketTextButton(onClick = { onPlan(false) }) { Text(stringResource(R.string.v02_plan_continue)) }
                PocketTextButton(onClick = { onPlan(true) }) { Text(stringResource(R.string.v02_plan_edit)) }
            }
        }
    }
}
