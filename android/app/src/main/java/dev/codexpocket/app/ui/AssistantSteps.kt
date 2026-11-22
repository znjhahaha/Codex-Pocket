package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*

/** Short reading rows disclose progress; members, results and controls live on a detail page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AssistantTaskSteps(task:AssistantTask,state:AssistantUiState,navigate:(String)->Unit) {
    var expanded by rememberSaveable(task.id) {mutableStateOf(false)}
    val c=Pocket.colors
    val snapshot=state.snapshot
    val steps=remember(snapshot?.events,task.id,task.activeRunId) {
        snapshot?.events.orEmpty().filter {it.taskId==task.id || (task.activeRunId!=null && it.operationId==task.activeRunId)}
            .distinctBy {it.id}.sortedBy {it.sequence}.takeLast(5)
    }
    AssistantCard(Modifier.testTag("assistant-task:${task.id}")) {
        Row(Modifier.fillMaxWidth().heightIn(min=PocketSpace.touch).pocketPressable(role=Role.Button,pressedScale=.99f,onClickLabel=if(expanded) "收起任务步骤" else "展开任务步骤") {expanded=!expanded}
            .testTag("assistant-task-expand:${task.id}"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            PText(task.prompt,Modifier.weight(1f),style=Pocket.type.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
            PText(if(task.reconciliationRequired) "核对中" else taskLabel(task.status),style=Pocket.type.labelSmall,color=c.inkMuted,maxLines=1)
            PToggleGlyph(MotionGlyphs.Chevron,expanded,Modifier.size(18.dp),c.inkFaint)
        }
        PText(snapshot?.taskStage(task) ?: taskLabel(task.status),style=Pocket.type.bodySmall,color=c.inkMuted)
        snapshot?.taskArtifacts(task.id)?.takeIf {it.isNotEmpty()}?.let {PText("已有成果 · ${it.size} 项",style=Pocket.type.caption,color=c.inkMuted)}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            AssistantCardAction(when {
                snapshot?.taskNeedsAttention(task)==true -> "处理待办"
                task.status in setOf(AssistantTaskStatus.Done,AssistantTaskStatus.Failed) -> "查看成果"
                else -> "补充要求"
            },{navigate("Task:${task.id}")},Modifier.testTag("assistant-task-primary:${task.id}"))
            AssistantCardAction("查看任务",{navigate("Task:${task.id}")})
        }
        AssistantExpansion(expanded) {
            Column(Modifier.padding(start=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(steps.isEmpty()) PText(taskLabel(task.status),style=Pocket.type.bodySmall,color=c.inkMuted)
                steps.forEach {step -> key(step.id) {
                    val detail=step.detail.takeIf {it.isNotBlank()}?.take(160)?.let {" · $it"}.orEmpty()
                    PText("· ${assistantStepLabel(step.kind)}$detail",Modifier.testTag("assistant-step:${step.id}"),style=Pocket.type.bodySmall,color=c.inkMuted,maxLines=2,overflow=TextOverflow.Ellipsis)
                }}
                AssistantCardAction("成员、详情与结果",{navigate("Task:${task.id}")},Modifier.testTag("assistant-task-detail:${task.id}"))
            }
        }
    }
}

/** A group's tasks, one artifact, or one execution's full result. A task has its own page. */
@Composable internal fun AssistantDetailPage(destination:String,state:AssistantUiState,canAct:Boolean,act:(()->AssistantAction)->Unit,
    open:(String,String)->Unit,workspaces:List<AssistantWorkspaceChoice>,navigate:(String)->Unit) {
    val snapshot=state.snapshot ?: return
    val id=destination.substringAfter(':')
    if(destination.substringBefore(':')=="Task") {AssistantWorkDetail(id,state,canAct,act,navigate,open);return}
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-detail-list"),contentPadding=AssistantPagePadding,verticalArrangement=Arrangement.spacedBy(AssistantPageSpacing)) {
        when(destination.substringBefore(':')) {
            "Group" -> {
                val group=snapshot.groups.firstOrNull {it.id==id}
                val tasks=snapshot.tasks.filter {it.id in group?.taskIds.orEmpty() || snapshot.assignmentFor(it.id)?.groupId==id}
                item {PText(group?.title ?: "任务组已不可用",Modifier.padding(horizontal=4.dp).semantics {heading()},style=Pocket.type.titleLarge)}
                tasks.forEach {task -> item("task:${task.id}") {AssistantTaskSteps(task,state,navigate)}}
            }
            "Artifact" -> {
                item {
                    val artifact=snapshot.artifacts.firstOrNull {it.id==id}
                    if(artifact==null) AssistantPageNote("该成果尚未同步或已不可用")
                    else AssistantArtifactDetails(artifact,state,canAct,act,navigate,open)
                }
            }
            "Result" -> {
                val operation=snapshot.operations.firstOrNull {it.id==id}
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        snapshot.artifacts.filter {it.operationId==id}.forEach {artifact -> AssistantArtifactCard(artifact,snapshot,navigate,open)}
                        MarkdownText(operation?.result.orEmpty(),"assistant:${snapshot.runtimeId}:result:$id")
                        operation?.taskId?.let {task -> AssistantCardAction("来源任务与继续操作",{navigate("Task:$task")},Modifier.testTag("assistant-result-source-task:$id"))}
                        operation?.threadId?.let {thread -> AssistantCardAction("查看原对话",{open(snapshot.runtimeId.orEmpty(),thread)},Modifier.testTag("assistant-thread:$id"))}
                    }
                }
            }
        }
    }
}

private fun assistantStepLabel(kind:String)=when(kind) {
    "started", "running"->"开始执行";"completed"->"执行完成";"failed"->"执行遇到问题";"paused"->"已暂停"
    "waiting_user"->"等待你确认";"progress"->"更新进展";"assigned"->"已安排任务";"resumed"->"已恢复"
    else->"更新任务状态"
}

/** The four things that must be ready before the assistant can take a message, and what to do about the one that is not. */
@Composable internal fun AssistantStartupStatus(snapshot:AssistantSnapshot) {
    val c=Pocket.colors
    Column(Modifier.fillMaxWidth().testTag("assistant-startup-stages"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns=if(maxWidth<340.dp || LocalDensity.current.fontScale>1.3f) 2 else 4
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                snapshot.startupStages.chunked(columns).forEach {row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        row.forEach {stage ->
                            Column(Modifier.weight(1f).testTag("assistant-stage:${stage.id}")) {
                                PText(stage.label,style=Pocket.type.caption,color=c.inkMuted)
                                PText(stage.statusLabel,style=Pocket.type.labelSmall,color=when(stage.status) {
                                    "blocked"->c.danger;"ready"->c.positive;"reconciling","paused"->c.warning;else->c.inkMuted})
                            }
                        }
                        repeat(columns-row.size) {Spacer(Modifier.weight(1f))}
                    }
                }
            }
        }
        snapshot.startupStages.firstOrNull {it.status in setOf("blocked","reconciling","paused")}?.recoveryText?.let {
            PText(it,Modifier.testTag("assistant-stage-recovery"),style=Pocket.type.bodySmall,color=c.inkMuted)
        }
    }
}
