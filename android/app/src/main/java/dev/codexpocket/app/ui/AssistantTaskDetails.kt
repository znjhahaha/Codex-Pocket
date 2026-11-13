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
    workspaces: List<AssistantWorkspaceChoice>) {
    val snapshot = state.snapshot ?: return
    val runtime = snapshot.runtimeId.orEmpty()
    val assignment = snapshot.assignmentFor(task.id)
    val operations = snapshot.operations.filter {it.taskId == task.id || it.id == task.activeRunId}
    val memberIds = (listOfNotNull(assignment?.memberId) + snapshot.events.filter {it.taskId == task.id}.mapNotNull {it.memberId}).distinct()
    val members = snapshot.members.filter {it.id in memberIds}.distinctBy {it.id}
    val holder = rememberSaveableStateHolder()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(task.prompt,Modifier.testTag("assistant-task-full-prompt:${task.id}"),style=MaterialTheme.typography.bodyLarge)
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
        operations.filter {it.phase == AssistantOperationPhase.Waiting && !it.suspended && it.question != null}.forEach {op ->
            key(op.id) {Text("等待你确认 · 在助理对话中处理",style=MaterialTheme.typography.bodySmall)}
        }
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
