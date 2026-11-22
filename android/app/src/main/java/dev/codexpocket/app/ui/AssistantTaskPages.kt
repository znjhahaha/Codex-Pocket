package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.serialization.json.*

/** Same bound-send receipt as the main composer; navigation cannot clear or
 * retarget a draft and an unknown result never enables a second send. */
@Composable internal fun AssistantTaskFollowup(task:AssistantTask,state:AssistantUiState,canAct:Boolean,act:(()->AssistantAction)->Unit,
    initialContext:String = "", artifact:AssistantArtifact? = null) {
    val snapshot=state.snapshot ?: return
    val c=Pocket.colors
    val preferences=LocalContext.current.getSharedPreferences("assistant-drafts-v2",0)
    val draftKey=JsonArray(listOf(state.computerId,snapshot.assistantId.orEmpty(),snapshot.runtimeId.orEmpty(),task.id,artifact?.version.orEmpty()).map(::JsonPrimitive)).toString()
    var draft by rememberSaveable(draftKey) {mutableStateOf(preferences.getString(draftKey,"").orEmpty())}
    val attachments=rememberAssistantAttachments(draftKey)
    var submitted by rememberSaveable(draftKey) {mutableStateOf(attachments.pendingId)}
    var sentText by rememberSaveable(draftKey) {mutableStateOf(attachments.sentText)}
    LaunchedEffect(state.sendReceipts,submitted) {
        val receipt=state.sendReceipts.firstOrNull {it.commandId==submitted && it.computerId==state.computerId && it.runtimeId==snapshot.runtimeId && it.conversationId==snapshot.composerBinding(state.computerId).conversationId}
        if(receipt?.status=="accepted") {if(draft==sentText) {draft="";preferences.edit().putString(draftKey,"").apply()};submitted=null;sentText=null}
        else if(receipt?.status=="rejected") {submitted=null;sentText=null}
        receipt?.let {attachments.complete(it.status)}
    }
    AssistantCard(Modifier.testTag("assistant-task-followup:${task.id}")) {
        PText(if(task.status in setOf(AssistantTaskStatus.Done,AssistantTaskStatus.Failed)) "基于成果继续" else "补充要求",style=Pocket.type.titleSmall)
        PText("消息会绑定此任务。审批请在上方的原请求中处理。",style=Pocket.type.bodySmall,color=c.inkMuted)
        PTextField(draft,{draft=it;preferences.edit().putString(draftKey,it).apply()},Modifier.testTag("assistant-task-draft:${task.id}"),placeholder="写下补充要求",minLines=2,maxLines=6)
        AssistantAttachmentPicker(attachments,task.id,submitted==null)
        if(submitted!=null) PText("已提交，等待电脑回执；草稿已保留。",style=Pocket.type.bodySmall,color=c.inkMuted)
        PButton(if(submitted!=null) "确认送达中" else "发送到此任务",{
            val id=java.util.UUID.randomUUID().toString()
            act {
                val base=AssistantActions.send(snapshot.runtimeId.orEmpty(),listOf(initialContext,draft).filter(String::isNotBlank).joinToString("\n\n").ifBlank {"请结合附件继续这项任务。"},null,taskId=task.id)
                val action=base.copy(commandId=id,composerBinding=snapshot.composerBinding(state.computerId),attachments=attachments.files.toList(),params=if(artifact==null) base.params else
                    JsonObject(base.params+mapOf("artifactId" to JsonPrimitive(artifact.id),"artifactVersion" to JsonPrimitive(artifact.version))))
                submitted=id;sentText=draft;attachments.sent(id,draft);action
            }
        },Modifier.fillMaxWidth().testTag("assistant-task-send:${task.id}"),enabled=canAct && snapshot.prepared && snapshot.paused!=true && !task.reconciliationRequired && submitted==null && !state.busy("assistant/send") && (draft.isNotBlank() || attachments.files.isNotEmpty()) && '\u0000' !in draft && (initialContext+draft).toByteArray().size<65000,
            haptic=PocketHaptic.Confirm)
    }
}

@Composable internal fun AssistantTasksPage(state:AssistantUiState,navigate:(String)->Unit) {
    val snapshot=state.snapshot ?: return
    var query by rememberSaveable {mutableStateOf("")}
    var onlyPending by rememberSaveable {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-tasks-list"),contentPadding=AssistantPagePadding,verticalArrangement=Arrangement.spacedBy(AssistantPageSpacing)) {
        item {PTextField(query,{query=it},placeholder="查找任务",singleLine=true,leading={PIcon(PocketIcons.Search,null,Modifier.size(18.dp))})}
        item {PPill("待处理",onlyPending,{onlyPending=!onlyPending},Modifier.testTag("assistant-tasks-pending"))}
        val tasks=snapshot.orderedTasks().filter {t->(query.isBlank() || t.prompt.contains(query,true)) &&
            (!onlyPending || snapshot.taskNeedsAttention(t) || snapshot.taskRecord(t.id)?.state=="needs_verification")}
        if(tasks.isEmpty()) item {AssistantPageNote(if(onlyPending) "没有待处理任务" else "交办后，任务会保留在这里。")}
        items(tasks,key={it.id}) {task->AssistantTaskListCard(task,state,navigate)}
    }
}

/** A task as a list shows it: where it stands, what was asked, what it is doing, and the way to its page. */
@Composable internal fun AssistantTaskListCard(task:AssistantTask,state:AssistantUiState,navigate:(String)->Unit) {
    val snapshot=state.snapshot ?: return
    val view=state.jobView(task)
    AssistantCard(Modifier.testTag("assistant-task:${task.id}")) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            AssistantJobGlyph(view)
            PText(view.label,Modifier.weight(1f),style=Pocket.type.labelSmall,color=assistantToneInk(view.tone),maxLines=1,overflow=TextOverflow.Ellipsis)
            if(view.unread) PBadge("新进展",tone=PTone.Accent)
        }
        PText(task.prompt,style=Pocket.type.titleSmall,maxLines=3,overflow=TextOverflow.Ellipsis)
        PText(snapshot.taskStage(task),style=Pocket.type.bodySmall,color=Pocket.colors.inkMuted,maxLines=2,overflow=TextOverflow.Ellipsis)
        AssistantCardAction("查看任务",{navigate("Task:${task.id}")},Modifier.testTag("assistant-task-open:${task.id}"))
    }
}

@Composable internal fun AssistantResultsPage(state:AssistantUiState,navigate:(String)->Unit,open:(String,String)->Unit) {
    val snapshot=state.snapshot ?: return
    var query by rememberSaveable {mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=AssistantPagePadding,verticalArrangement=Arrangement.spacedBy(AssistantPageSpacing)) {
        item {PTextField(query,{query=it},Modifier.testTag("assistant-artifact-filter"),placeholder="查找成果",singleLine=true,leading={PIcon(PocketIcons.Search,null,Modifier.size(18.dp))})}
        val artifacts=snapshot.artifacts.filter {query.isBlank() || it.name.contains(query,true)}
        if(artifacts.isEmpty()) item {AssistantPageNote("成果会按任务和版本保留，未完成任务的部分交付也在这里。")}
        items(artifacts,key={"${it.id}@${it.version}"}) {AssistantArtifactCard(it,snapshot,navigate,open)}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AssistantWorkDetail(id:String,state:AssistantUiState,canAct:Boolean,act:(()->AssistantAction)->Unit,
    navigate:(String)->Unit,open:(String,String)->Unit) {
    val snapshot=state.snapshot ?: return
    val task=snapshot.tasks.firstOrNull {it.id==id} ?: run {AssistantPageNote("任务已不可用，返回对话查看最新状态。",Modifier.padding(horizontal=16.dp));return}
    val c=Pocket.colors
    val record=snapshot.taskRecord(id)
    val runtime=snapshot.runtimeId.orEmpty()
    val view=state.jobView(task)
    var section by rememberSaveable(id) {mutableStateOf("Overview")}
    var stop by rememberSaveable(id) {mutableStateOf(false)}
    LaunchedEffect(id,record?.activitySequence) {if(canAct && record?.unread==true) act {assistantSeen(runtime,id,record.activitySequence)}}
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-task-detail-page:$id"),contentPadding=AssistantPagePadding,verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            SelectionContainer {PText(task.prompt,style=Pocket.type.titleLarge)}
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                AssistantJobGlyph(view)
                PText(snapshot.taskStateLabel(task),style=Pocket.type.labelSmall,color=assistantToneInk(view.tone))
            }
        }}
        items(snapshot.taskOperations(task).filter {it in snapshot.pendingOperations()},key={"pending:${it.id}"}) {AssistantHumanQuestion(it,runtime,state,canAct,act)}
        if(record?.state=="needs_verification") item {AssistantCard {
            PText("保留原执行，等待核对",style=Pocket.type.titleSmall)
            PText("此尝试不会被重放。确认结果后再继续这项任务。",style=Pocket.type.bodySmall,color=c.inkMuted)
            snapshot.taskOperations(task).mapNotNull {it.threadId}.distinct().forEach {thread->AssistantCardAction("查看原执行",{open(runtime,thread)})}
        }}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Overview" to "概览","Process" to "过程","Artifacts" to "成果").forEach {(key,label)->PPill(label,section==key,{section=key},Modifier.testTag("assistant-task-section:$key"))}
        }}
        if(section=="Overview") {
            task.detail?.takeIf(String::isNotBlank)?.let {item {SelectionContainer {PText(it,style=Pocket.type.body)}}}
            task.assignment?.completionCriteria?.takeIf(String::isNotBlank)?.let {item {Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                PText("完成标准",style=Pocket.type.labelSmall,color=c.inkMuted);PText(it,style=Pocket.type.body)
            }}}
            record?.parentId?.let {parent->item {AssistantCardAction("返回父任务",{navigate("Task:$parent")})}}
            record?.continuesId?.let {parent->item {AssistantCardAction("查看上一轮成果",{navigate("Task:$parent")})}}
            items(snapshot.orderedTasks().filter {snapshot.taskRecord(it.id)?.parentId==id},key={"child:${it.id}"}) {child->
                PRow("子任务 · ${child.prompt}",{navigate("Task:${child.id}")},trailing={PIcon(PocketIcons.ChevronRight,null,Modifier.size(15.dp),c.inkFaint)})
            }
        }
        if(section=="Process") {
            items(snapshot.taskOperations(task).flatMap {it.heldInputs},key={"held:${it.operationId}:${it.callId}"}) {AssistantCard {AssistantEndedQuestion(it)}}
            items(snapshot.events.filter {it.taskId==id || snapshot.taskOperations(task).any {op->op.id==it.operationId}}.sortedBy {it.sequence},key={it.id}) {event->
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    PText(event.atMs?.let {assistantTime(it,snapshot.settings?.timezone ?: "Asia/Shanghai")} ?: "时间未记录",style=Pocket.type.caption,color=c.inkFaint)
                    PText(event.detail,style=Pocket.type.body)
                }
            }
            items(snapshot.taskOperations(task),key={"attempt:${it.id}"}) {op->AssistantCard {
                PText("执行尝试 · ${snapshot.taskOperations(task).indexOf(op)+1}",style=Pocket.type.titleSmall)
                PText(when(op.phase) {AssistantOperationPhase.Unknown->"待核对";AssistantOperationPhase.Failed->"未完成";AssistantOperationPhase.Completed->"已完成";AssistantOperationPhase.Paused->"已停止";AssistantOperationPhase.Waiting->"等待回答";else->"执行中"},
                    style=Pocket.type.bodySmall,color=c.inkMuted)
                op.threadId?.let {thread->AssistantCardAction("完整执行过程",{open(runtime,thread)})}
            }}
        }
        if(section=="Artifacts") {
            val artifacts=snapshot.taskArtifacts(id)
            if(artifacts.isEmpty()) item {AssistantPageNote("尚未交付成果")}
            items(artifacts,key={it.id}) {AssistantArtifactCard(it,snapshot,navigate,open)}
        }
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(task.status in setOf(AssistantTaskStatus.Running,AssistantTaskStatus.Queued,AssistantTaskStatus.WaitingUser) && record?.state!="stop_requested")
                AssistantQuietButton("停止任务",{stop=true},Modifier.testTag("assistant-task-stop:$id"),enabled=canAct)
            if(task.status==AssistantTaskStatus.Paused && !task.reconciliationRequired)
                PButton("继续任务",{act {AssistantActions.task(runtime,id,true)}},enabled=canAct && !state.busy("assistant/task/resume"),haptic=PocketHaptic.Confirm)
            if(task.status==AssistantTaskStatus.Failed && !task.reconciliationRequired)
                PButton("重新尝试",{act {assistantRetry(runtime,id)}},Modifier.testTag("assistant-task-retry:$id"),enabled=canAct && !state.busy("assistant/v2/task/retry"),haptic=PocketHaptic.Confirm)
        }}
        if(record?.state!="needs_verification") item {AssistantTaskFollowup(task,state,canAct,act)}
        item {
            val disabled=id in snapshot.memoryDisabledTasks
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                AssistantCardAction(if(disabled) "后续执行使用记忆" else "后续执行不使用记忆",{act {AssistantActions.taskMemory(runtime,id,disabled)}},enabled=canAct)
                AssistantCardAction("设为计划",{navigate("ScheduleFrom:$id")})
            }
        }
    }
    if(stop) PDialog("停止这项任务？",{stop=false},"确认停止",{act {AssistantActions.task(runtime,id,false)};stop=false},
        text="会请求停止当前尝试，已有成果和记录仍保留。收到执行器确认后显示已停止。",dismissLabel="继续执行",destructive=true)
}
