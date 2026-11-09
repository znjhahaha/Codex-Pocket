package dev.codexpocket.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.ProposedPlan

/** Replaces the ordinary composer; feedback never writes to that composer's draft. */
@Composable
internal fun PlanConfirmation(
    plan: ProposedPlan, reason: String?, onImplement: () -> Unit, onModify: () -> Unit,
    modifier: Modifier = Modifier, feedback: String?, onFeedbackChange: (String) -> Unit = {},
    onSubmitFeedback: () -> Unit = {}, onCancelFeedback: () -> Unit = {}, onDismiss: () -> Unit = {},
) {
    val editing = feedback != null
    val c = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().navigationBarsPadding().imePadding().testTag("plan-confirmation"), shape = RoundedCornerShape(20.dp), color = c.surface,
        border = BorderStroke(1.dp, c.outlineVariant.copy(alpha = .65f))) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (editing) "修改计划" else "实施此计划？", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (editing) PlanIconAction(PocketIcons.ArrowBack, "返回方案确认", reason != "正在提交", onCancelFeedback)
                PlanIconAction(PocketIcons.Close, "关闭计划确认", reason != "正在提交", onDismiss)
            }
            if (editing) {
                val focus = remember(plan.revision) { FocusRequester() }
                LaunchedEffect(plan.revision) { focus.requestFocus() }
                PocketTextField(feedback.orEmpty(), onFeedbackChange,
                    Modifier.padding(end = 8.dp).focusRequester(focus).testTag("plan-feedback-input"),
                    placeholder = "补充你希望修改的内容", enabled = reason == null, minLines = 2, maxLines = 5)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Plan", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    Box(Modifier.heightIn(min = 48.dp).testTag("plan-feedback-cancel").then(planPress(onDismiss, reason != "正在提交"))
                        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text("取消", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                    }
                    Row(Modifier.testTag("plan-feedback-submit").heightIn(min = 48.dp)
                        .then(planPress(onSubmitFeedback, reason == null && !feedback.isNullOrBlank())).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("提交修改", style = MaterialTheme.typography.labelLarge)
                        Icon(PocketIcons.ArrowUpward, null, Modifier.size(18.dp))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("plan-implement")
                    .then(planPress(onImplement, reason == null)).padding(start = 2.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PocketIcons.CheckCircle, null, Modifier.padding(end = 10.dp).size(19.dp), tint = c.onSurfaceVariant)
                    Text("是，实施此计划", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(PocketIcons.ArrowForward, null, Modifier.size(20.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("plan-modify")
                        .then(planPress(onModify, reason == null)), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(PocketIcons.Edit, null, Modifier.size(17.dp), tint = c.onSurfaceVariant)
                        Text("提出修改…", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    }
                    Box(Modifier.heightIn(min = 48.dp).testTag("plan-skip").then(planPress(onDismiss, reason != "正在提交"))
                        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text("跳过", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                    }
                }
            }
            if (reason != null) Text(reason, Modifier.padding(end = 10.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall,
                color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Legacy callback-only callers. Production uses the controlled feedback overload above. */
@Composable
internal fun PlanConfirmation(plan: ProposedPlan, reason: String?, onImplement: () -> Unit, onModify: () -> Unit,
    modifier: Modifier = Modifier) {
    var editing by rememberSaveable(plan.revision) { mutableStateOf(false) }
    PlanConfirmation(plan, reason, onImplement, { onModify(); editing = true }, modifier,
        feedback = if (editing) "" else null, onCancelFeedback = { editing = false })
}
