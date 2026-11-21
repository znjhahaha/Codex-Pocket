package dev.codexpocket.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*

/** Short reading rows disclose progress; members, results and controls live on a detail page. */
@Composable internal fun AssistantTaskSteps(task:AssistantTask,state:AssistantUiState,navigate:(String)->Unit) {
    var expanded by rememberSaveable(task.id) {mutableStateOf(false)}
    val palette=assistantPalette()
    val snapshot=state.snapshot
    val steps=remember(snapshot?.events,task.id,task.activeRunId) {
        snapshot?.events.orEmpty().filter {it.taskId==task.id || (task.activeRunId!=null && it.operationId==task.activeRunId)}
            .distinctBy {it.id}.sortedBy {it.sequence}.takeLast(5)
    }
    AssistantCard(Modifier.testTag("assistant-task:${task.id}")) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(onClickLabel=if(expanded) "收起任务步骤" else "展开任务步骤") {expanded=!expanded}
            .testTag("assistant-task-expand:${task.id}"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(expanded) "⌄" else "›",color=palette.muted)
            Text(task.prompt,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(if(task.reconciliationRequired) "核对中" else taskLabel(task.status),style=MaterialTheme.typography.labelSmall,color=palette.muted)
        }
        Text(snapshot?.taskStage(task) ?: taskLabel(task.status),style=MaterialTheme.typography.bodySmall)
        snapshot?.taskArtifacts(task.id)?.takeIf {it.isNotEmpty()}?.let {Text("已有成果 · ${it.size} 项",style=MaterialTheme.typography.labelSmall)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton({navigate("Task:${task.id}")},Modifier.testTag("assistant-task-primary:${task.id}")) {Text(when {
                snapshot?.taskNeedsAttention(task)==true -> "处理待办"
                task.status in setOf(AssistantTaskStatus.Done,AssistantTaskStatus.Failed) -> "查看成果"
                else -> "补充要求"
            })}
            TextButton({navigate("Task:${task.id}")}) {Text("查看任务")}
        }
        AssistantExpansion(expanded) {
            Column(Modifier.padding(start=18.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(steps.isEmpty()) Text(taskLabel(task.status),style=MaterialTheme.typography.bodySmall,color=palette.muted)
                steps.forEach {step -> key(step.id) {
                    val detail=step.detail.takeIf {it.isNotBlank()}?.take(160)?.let {" · $it"}.orEmpty()
                    Text("· ${assistantStepLabel(step.kind)}$detail",Modifier.testTag("assistant-step:${step.id}"),style=MaterialTheme.typography.bodySmall,color=palette.muted,maxLines=2,overflow=TextOverflow.Ellipsis)
                }}
                TextButton({navigate("Task:${task.id}")},Modifier.heightIn(min=48.dp).testTag("assistant-task-detail:${task.id}")) {Text("成员、详情与结果")}
            }
        }
    }
}

@Composable internal fun AssistantGroupSteps(group:AssistantGroup,tasks:List<AssistantTask>,state:AssistantUiState,navigate:(String)->Unit) {
    var expanded by rememberSaveable(group.id) {mutableStateOf(false)}
    val palette=assistantPalette()
    AssistantCard(Modifier.testTag("assistant-group:${group.id}")) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable {expanded=!expanded}.testTag("assistant-group-expand:${group.id}"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(expanded) "⌄" else "›",color=palette.muted)
            Text(group.title,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${tasks.count {it.status==AssistantTaskStatus.Done}}/${tasks.size}",style=MaterialTheme.typography.labelSmall,color=palette.muted)
        }
        Text("${tasks.size} 项分工：${tasks.count {it.status==AssistantTaskStatus.Done}} 已完成 · ${tasks.count {it.status==AssistantTaskStatus.Running}} 执行中 · ${tasks.count {state.snapshot?.taskNeedsAttention(it)==true}} 待处理",style=MaterialTheme.typography.bodySmall)
        if(tasks.any {state.snapshot?.taskNeedsAttention(it)==true} && tasks.any {it.status==AssistantTaskStatus.Running}) Text("部分分工等待你处理，其余仍在执行",style=MaterialTheme.typography.bodySmall)
        TextButton({navigate("Group:${group.id}")}) {Text("查看目标与分工")}
        AssistantExpansion(expanded) {
            Column(Modifier.padding(start=18.dp)) {
                tasks.forEach {task -> key(task.id) {AssistantTaskSteps(task,state,navigate)}}
                TextButton({navigate("Group:${group.id}")},Modifier.testTag("assistant-group-detail:${group.id}")) {Text("查看全部任务与结果")}
            }
        }
    }
}

@Composable internal fun AssistantDetailPage(destination:String,state:AssistantUiState,canAct:Boolean,act:(()->AssistantAction)->Unit,
    open:(String,String)->Unit,workspaces:List<AssistantWorkspaceChoice>,navigate:(String)->Unit) {
    val snapshot=state.snapshot ?: return
    val id=destination.substringAfter(':')
    // Keep the active section outside lazy-item disposal when opening an artifact.
    var taskSection by rememberSaveable(destination) {mutableStateOf("Overview")}
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-detail-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        when(destination.substringBefore(':')) {
            "Task" -> {
                val task=snapshot.tasks.firstOrNull {it.id==id}
                item("task-detail:$id") {
                    if(task==null) Text("任务已不可用，返回对话查看最新状态。")
                    else Column(Modifier.testTag("assistant-task-detail-page:$id")) {AssistantTaskDetails(task,state,canAct,act,open,workspaces,navigate,taskSection,{taskSection=it})}
                }
            }
            "Group" -> {
                val group=snapshot.groups.firstOrNull {it.id==id}
                val tasks=snapshot.tasks.filter {it.id in group?.taskIds.orEmpty() || snapshot.assignmentFor(it.id)?.groupId==id}
                item {Text(group?.title ?: "任务组已不可用",style=MaterialTheme.typography.titleMedium)}
                tasks.forEach {task -> item("task:${task.id}") {AssistantTaskSteps(task,state,navigate)}}
            }
            "Artifact" -> {
                item {
                    val artifact=snapshot.artifacts.firstOrNull {it.id==id}
                    if(artifact==null) Text("该成果尚未同步或已不可用")
                    else AssistantArtifactDetails(artifact,state,canAct,act,navigate,open)
                }
            }
            "Result" -> {
                val operation=snapshot.operations.firstOrNull {it.id==id}
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        snapshot.artifacts.filter {it.operationId==id}.forEach {artifact -> AssistantArtifactCard(artifact,snapshot,navigate,open)}
                        MarkdownText(operation?.result.orEmpty(),"assistant:${snapshot.runtimeId}:result:$id")
                        operation?.taskId?.let {task -> TextButton({navigate("Task:$task")},Modifier.testTag("assistant-result-source-task:$id")) {Text("来源任务与继续操作")}}
                        operation?.threadId?.let {thread -> TextButton({open(snapshot.runtimeId.orEmpty(),thread)},Modifier.testTag("assistant-thread:$id")) {Text("查看原对话")}}
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

@Composable internal fun AssistantStartupStatus(snapshot:AssistantSnapshot) {
    val palette=assistantPalette()
    Column(Modifier.fillMaxWidth().testTag("assistant-startup-stages"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns=if(maxWidth<340.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f) 2 else 4
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                snapshot.startupStages.chunked(columns).forEach {row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        row.forEach {stage ->
                            Column(Modifier.weight(1f).testTag("assistant-stage:${stage.id}")) {
                                Text(stage.label,style=MaterialTheme.typography.labelSmall,color=palette.muted)
                                Text(stage.statusLabel,style=MaterialTheme.typography.labelSmall,color=if(stage.status=="blocked") MaterialTheme.colorScheme.error else palette.accent)
                            }
                        }
                        repeat(columns-row.size) {Spacer(Modifier.weight(1f))}
                    }
                }
            }
        }
        snapshot.startupStages.firstOrNull {it.status in setOf("blocked","reconciling","paused")}?.recoveryText?.let {
            Text(it,Modifier.testTag("assistant-stage-recovery"),style=MaterialTheme.typography.bodySmall,color=palette.muted)
        }
    }
}
