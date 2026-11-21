package dev.codexpocket.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*

@Composable
internal fun AssistantExpansion(expanded: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(expanded,
        enter = fadeIn(tween(pocketMotionMillis(180))) + expandVertically(tween(pocketMotionMillis(180))),
        exit = fadeOut(tween(pocketMotionMillis(140))) + shrinkVertically(tween(pocketMotionMillis(140)))) { content() }
}

/** The task owns its member disclosure and native descendants; no separate member administration. */
@Composable
internal fun AssistantTaskDetails(task: AssistantTask, state: AssistantUiState, canAct: Boolean,
    act: (() -> AssistantAction) -> Unit, open: (String, String) -> Unit,
    workspaces: List<AssistantWorkspaceChoice>, navigate:(String)->Unit = {}, selectedSection:String? = null, onSectionSelected:(String)->Unit = {}) {
    val snapshot = state.snapshot ?: return
    val runtime = snapshot.runtimeId.orEmpty()
    val assignment = snapshot.assignmentFor(task.id)
    val operations = snapshot.operations.filter {it.taskId == task.id || it.id == task.activeRunId}
    val memberIds = (listOfNotNull(assignment?.memberId) + snapshot.events.filter {it.taskId == task.id}.mapNotNull {it.memberId}).distinct()
    val members = snapshot.members.filter {it.id in memberIds}.distinctBy {it.id}
    val holder = rememberSaveableStateHolder()
    var localSection by rememberSaveable(task.id) {mutableStateOf("Overview")}
    val section=selectedSection ?: localSection
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(task.prompt,Modifier.testTag("assistant-task-full-prompt:${task.id}"),style=MaterialTheme.typography.bodyLarge)
        Text(snapshot.taskStage(task),style=MaterialTheme.typography.bodyMedium)
        operations.filter {it.question!=null && it.phase==AssistantOperationPhase.Waiting && !it.suspended}.forEach {op ->
            key(op.id) {AssistantHumanQuestion(op,runtime,state,canAct,act)}
        }
        snapshot.recoveries.filter {request -> operations.any {it.id==request.operationId}}.forEach {request ->
            AssistantRecoveryCard(request,runtime,state,canAct,act)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Overview" to "概览","Process" to "过程","Artifacts" to "成果").forEach {(id,label) ->
                FilterChip(selected=section==id,onClick={localSection=id;onSectionSelected(id)},label={Text(label)},modifier=Modifier.testTag("assistant-task-section:$id"))
            }
        }
        if(section=="Artifacts") {
            val artifacts=snapshot.taskArtifacts(task.id)
            if(artifacts.isEmpty()) AssistantTaskOperationResults(operations,runtime,snapshot)
            artifacts.forEach {artifact -> AssistantArtifactCard(artifact,snapshot,navigate,open)}
            if(artifacts.isEmpty() && operations.none {!it.result.isNullOrBlank()}) Text("尚无成果，完成后会保留在此任务中。")
        }
        if(section=="Process") {
            snapshot.events.filter {it.taskId==task.id || operations.any {op->op.id==it.operationId}}.sortedBy {it.sequence}.forEach {event ->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(event.atMs?.let {assistantTime(it,snapshot.settings?.timezone ?: "Asia/Shanghai")} ?: "时间未提供",style=MaterialTheme.typography.labelSmall)
                    Text(event.detail.ifBlank {event.kind},style=MaterialTheme.typography.bodySmall)
                }
            }
            snapshot.deliveries.filter {it.taskId==task.id}.forEach {Text("${it.label} · ${it.message}",style=MaterialTheme.typography.bodySmall)}
        }
        if(section=="Overview") {
        task.detail?.takeIf {it.isNotBlank()}?.let {Text(it, style = MaterialTheme.typography.bodyMedium)}
        assignment?.groupId?.let {id -> snapshot.groups.firstOrNull {it.id == id}}?.let {group ->
            Text("${group.title} · ${assistantGroupLabel(group.status)}", style = MaterialTheme.typography.labelMedium)
        }
        assignment?.completionCriteria?.takeIf {it.isNotBlank()}?.let {Text("完成标准 · $it", style = MaterialTheme.typography.bodyMedium)}
        assignment?.dependencies.orEmpty().forEach {id ->
            val dependency = snapshot.tasks.firstOrNull {it.id == id}
            Text("依赖 · ${dependency?.prompt ?: id}${dependency?.let {" · ${taskLabel(it.status)}"}.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        }
        assignment?.continueFromTask?.let {id ->
            Text("继续任务 · ${snapshot.tasks.firstOrNull {it.id == id}?.prompt ?: id}", style = MaterialTheme.typography.bodySmall)
        }
        if (task.reconciliationRequired) Text("电脑正在核对任务，暂停或恢复需等核对完成。", style = MaterialTheme.typography.bodySmall)
        if (members.isEmpty() && task.status == AssistantTaskStatus.Queued) Text("助理会自动安排负责成员", style = MaterialTheme.typography.bodySmall)
        members.forEach {member -> key(member.id) {
            var expanded by rememberSaveable(task.id, member.id) {mutableStateOf(false)}
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = if(expanded) "收起负责成员" else "展开负责成员") {expanded = !expanded}
                    .testTag("assistant-member:${task.id}:${member.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PocketIcons.Users, null, Modifier.size(18.dp))
                    Text("负责成员 · ${member.name}", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge)
                    Text(if(expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
                }
                AssistantExpansion(expanded) {holder.SaveableStateProvider(member.id) {
                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if(member.duty.isNotBlank()) Text(member.duty, style = MaterialTheme.typography.bodyMedium)
                        val ownedOperations=operations.filter {snapshot.operationMemberId(it.id,it.taskId)==member.id}
                        AssistantTaskOperationResults(ownedOperations,runtime,snapshot)
                        val mapped = (ownedOperations.mapNotNull {it.threadId} + snapshot.events.filter {it.taskId == task.id && it.memberId == member.id}.mapNotNull {it.threadId}).distinct()
                        val threads = mapped.ifEmpty {listOfNotNull(member.threadId)}
                        threads.forEach {thread -> key(thread) {
                            NativeThreadBranch(thread, runtime, workspaces, open, emptySet())
                        }}
                    }
                }}
            }
        }}
        if(members.isEmpty()) {
            AssistantTaskOperationResults(operations,runtime,snapshot)
            operations.mapNotNull {it.threadId}.distinct().forEach {thread -> NativeThreadBranch(thread, runtime, workspaces, open, emptySet())}
        }
        val memoryIds=operations.flatMap {snapshot.memoryUsage[it.id].orEmpty()}.distinct()
        if(memoryIds.isNotEmpty()) {
            Text("提供给执行的记忆",style=MaterialTheme.typography.labelLarge)
            memoryIds.forEach {id->Text(snapshot.memories.firstOrNull {it.id==id}?.text ?: "已删除或撤销的记忆",style=MaterialTheme.typography.bodySmall)}
        }
        val excluded=task.id in snapshot.memoryDisabledTasks
        TextButton({act {AssistantActions.taskMemory(runtime,task.id,excluded)}},enabled=canAct && !state.busy("assistant/task/memory"),modifier=Modifier.testTag("assistant-task-memory:${task.id}")) {Text(if(excluded) "后续执行恢复使用记忆" else "后续执行不使用记忆")}
        Text("从后续执行起生效，已运行的过程仍保留原记录。",style=MaterialTheme.typography.bodySmall)
        snapshot.scheduleRuns.filter {it.taskId==task.id}.forEach {run ->
            Text("定时来源 · ${snapshot.schedules.firstOrNull {it.id==run.scheduleId}?.prompt ?: run.scheduleId}",style=MaterialTheme.typography.bodySmall)
        }
        }
        AssistantTaskFollowup(task,state,canAct,act)
        if(task.status in setOf(AssistantTaskStatus.Done,AssistantTaskStatus.Failed)) TextButton({navigate("ScheduleFrom:${task.id}")},Modifier.testTag("assistant-task-schedule:${task.id}")) {Text("设为定时")}
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if(!task.reconciliationRequired && task.status in setOf(AssistantTaskStatus.Running, AssistantTaskStatus.Queued)) PocketButton("暂停任务", {act {AssistantActions.task(runtime,task.id,false)}},
                Modifier.weight(1f).testTag("assistant-task-pause:${task.id}"), enabled = canAct && !task.reconciliationRequired && !state.busy("assistant/task/pause",task.id), secondary = true)
            if(!task.reconciliationRequired && task.status == AssistantTaskStatus.Paused) PocketButton("恢复任务", {act {AssistantActions.task(runtime,task.id,true)}},
                Modifier.weight(1f).testTag("assistant-task-resume:${task.id}"), enabled = canAct && !task.reconciliationRequired && !state.busy("assistant/task/resume",task.id), secondary = true)
        }
    }
}

@Composable
private fun NativeThreadBranch(thread: String, runtime: String, workspaces: List<AssistantWorkspaceChoice>, open: (String,String) -> Unit, ancestors: Set<String>) {
    if(thread in ancestors || ancestors.size >= 16) return
    val children = workspaces.filter {it.parentThreadId == thread && it.isSubagent}.distinctBy {it.threadId}
    val known = workspaces.firstOrNull {it.threadId == thread}
    var expanded by rememberSaveable(thread) {mutableStateOf(false)}
    val holder = rememberSaveableStateHolder()
    Column {
        PocketTextButton({open(runtime,thread)}, Modifier.heightIn(min = 48.dp).testTag("assistant-native-thread:$thread"), enabled = runtime.isNotBlank()) {
            Icon(PocketIcons.Link2,null,Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(known?.label ?: "完整对话")
        }
        if(children.isNotEmpty()) {
            PocketTextButton({expanded = !expanded}, Modifier.heightIn(min = 48.dp).testTag("assistant-native-agents:$thread")) {Text("子代理 · ${children.size} · ${if(expanded) "收起" else "展开"}")}
            AssistantExpansion(expanded) {holder.SaveableStateProvider(thread) {
                Column(Modifier.padding(start = 12.dp)) {children.forEach {child -> key(child.threadId) {
                    NativeThreadBranch(child.threadId, runtime, workspaces, open, ancestors + thread)
                }}}
            }}
        }
    }
}

internal fun assistantGroupLabel(status: String): String = when(status) {
    "planning" -> "安排中"; "queued" -> "排队中"; "running" -> "运行中"; "waiting_user" -> "等你确认"
    "paused" -> "已暂停"; "done", "completed" -> "已完成"; "failed" -> "执行失败"; "incomplete" -> "尚未完成"; else -> "状态待确认"
}

@Composable
private fun AssistantTaskOperationResults(operations: List<AssistantOperation>, runtime: String, snapshot: AssistantSnapshot) {
    operations.filter {it.phase in setOf(AssistantOperationPhase.Completed,AssistantOperationPhase.Failed) && !it.result.isNullOrBlank()}.forEach {op -> key(op.id) {
        Column(Modifier.testTag("assistant-task-result:${op.id}"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            snapshot.operationMemberId(op.id,op.taskId)?.let {id->snapshot.members.firstOrNull {it.id==id}}?.let {member->Text(member.name,style=MaterialTheme.typography.labelMedium)}
            if(op.phase==AssistantOperationPhase.Failed) Text("执行失败",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)
            MarkdownText(op.result.orEmpty(),"assistant:$runtime:task-result:${op.id}")
        }
    }}
}
