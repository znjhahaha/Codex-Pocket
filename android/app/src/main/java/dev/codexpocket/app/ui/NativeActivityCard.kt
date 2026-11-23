package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.app.data.*

/** Native proposals and execution checklists are separate presentations. */
@Composable
internal fun NativeActivityCard(item: TimelineItem, messageKey: String, expanded: Boolean, onExpand: () -> Unit,
    onDetail: () -> Unit, onPlan: ((Boolean) -> Unit)? = null, onReopenPlan: (() -> Unit)? = null, writingPlan: Boolean = false) {
    if (item.type == "plan") {
        PlanProposalCard(ProposedPlan(item.turnId, item.itemId, item.text), messageKey, onReopenPlan, writingPlan)
        return
    }
    val plan = remember(item.raw, item.type) { nativePlan(item) }
    val open = expanded
    val c = Pocket.colors
    Column(Modifier.fillMaxWidth().testTag("execution-plan")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp)
            .semantics { stateDescription = if (open) "已展开" else "已收起" }
            .then(planPress(onExpand))
            .padding(horizontal = 4.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PIcon(PocketIcons.Checklist, null, Modifier.size(16.dp), tint = c.inkMuted)
            PText(plan?.activeStep?.text ?: "执行计划", Modifier.weight(1f, fill = false),
                fontSize = 14.sp, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            plan?.let { PText("${it.completed}/${it.steps.size}", style = Pocket.type.caption, color = c.inkMuted) }
            ActivityExpansionIcon(open)
        }
        if (open) {
            if (plan != null) Column(Modifier.padding(start = 26.dp, bottom = 8.dp)) {
                if (plan.explanation.isNotBlank()) MarkdownText(plan.explanation, "$messageKey/explanation")
                plan.steps.forEach { step ->
                    val status = NativeActivityPresentation.status(step.status)
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 5.dp), verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PIcon(when (status) {
                            ActivityStatus.Completed -> PocketIcons.Check
                            ActivityStatus.Running -> PocketIcons.PlayCircle
                            ActivityStatus.Failed -> PocketIcons.ErrorOutline
                            else -> PocketIcons.RadioButtonUnchecked
                        }, activityStatusLabel(status), Modifier.size(16.dp), tint = if (status == ActivityStatus.Failed) c.danger else c.inkMuted)
                        PText(step.text, fontSize = 14.sp, lineHeight = 20.sp, color = if (status == ActivityStatus.Completed) c.inkMuted else c.ink)
                    }
                }
            }
        }
    }
}
