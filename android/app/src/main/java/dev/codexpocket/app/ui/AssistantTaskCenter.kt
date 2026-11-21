package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*

@Composable internal fun AssistantTaskCenter(state:AssistantUiState,view:AssistantTaskView,canAct:Boolean,
    act:(()->AssistantAction)->Unit,navigate:(String)->Unit,open:(String,String)->Unit) {
    val snapshot=state.snapshot ?: AssistantSnapshot()
    var query by rememberSaveable {mutableStateOf("")}
    var artifactKind by rememberSaveable {mutableStateOf("all")}
    val tasks=remember(snapshot,query,view) {snapshot.visibleTasks(query,view)}
    Column(Modifier.fillMaxSize().testTag("assistant-task-center")) {
        PocketTextField(query,{query=it},Modifier.padding(horizontal=16.dp).testTag("assistant-task-search"),placeholder="搜索目标、问题或成果",singleLine=true)
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("Tasks" to "全部","Pending" to "待处理","Results" to "成果").forEach {(destination,label) ->
                FilterChip(selected=destination==when(view) {AssistantTaskView.All->"Tasks";AssistantTaskView.Pending->"Pending";AssistantTaskView.Results->"Results"},
                    onClick={navigate(destination)},label={Text(label)},modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("assistant-center:$destination"))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.End) {
            TextButton({navigate("Schedules")},Modifier.heightIn(min=48.dp).testTag("assistant-center-schedules")) {Text("定时安排")}
        }
        LazyColumn(Modifier.fillMaxSize().testTag("assistant-tasks-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(view==AssistantTaskView.Pending) {
                items(snapshot.recoveries.distinctBy {it.operationId to it.requestId},key={"recovery:${it.operationId}:${it.requestId}"}) {request -> AssistantRecoveryCard(request,snapshot.runtimeId.orEmpty(),state,canAct,act)}
                items(snapshot.pendingOperations().filter {it.taskId==null},key={"pending:${it.id}"}) {op -> AssistantHumanQuestion(op,snapshot.runtimeId.orEmpty(),state,canAct,act)}
            }
            if(view==AssistantTaskView.Results) {
                item {
                    AssistantChoice("成果类型",mapOf("all" to "全部类型","text" to "文字结果","document" to "文档","spreadsheet" to "表格","image" to "图片","code" to "代码变更","file" to "其他文件","link" to "链接")[artifactKind].orEmpty(),
                        "assistant-artifact-filter",listOf("all" to "全部类型","text" to "文字结果","document" to "文档","spreadsheet" to "表格","image" to "图片","code" to "代码变更","file" to "其他文件","link" to "链接")) {artifactKind=it}
                }
                val artifacts=snapshot.artifacts.filter {(artifactKind=="all" || it.category==artifactKind) && (query.isBlank() || it.name.contains(query,true) || snapshot.tasks.firstOrNull {t->t.id==it.taskId}?.prompt?.contains(query,true)==true)}
                if(artifacts.isEmpty()) item {Text("还没有可展示的成果。执行完成后会按任务保留在这里。")}
                artifacts.groupBy {it.taskId}.forEach {(taskId,files) ->
                    item("result-heading:${taskId.orEmpty()}") {Text(snapshot.tasks.firstOrNull {it.id==taskId}?.prompt ?: "助理交付",style=MaterialTheme.typography.titleSmall)}
                    items(files,key={it.id}) {artifact -> AssistantArtifactCard(artifact,snapshot,navigate,open)}
                }
            } else {
                val sections=if(view==AssistantTaskView.Pending) listOf("待我处理" to tasks) else listOf(
                    "待我处理" to tasks.filter(snapshot::taskNeedsAttention),
                    "进行中" to tasks.filter {!snapshot.taskNeedsAttention(it) && it.status !in setOf(AssistantTaskStatus.Done,AssistantTaskStatus.Failed)},
                    "最近结束" to tasks.filter {!snapshot.taskNeedsAttention(it) && it.status in setOf(AssistantTaskStatus.Done,AssistantTaskStatus.Failed)})
                if(tasks.isEmpty() && (view!=AssistantTaskView.Pending || snapshot.pendingOperations().isEmpty() && snapshot.recoveries.isEmpty())) item {Text(if(view==AssistantTaskView.Pending) "目前没有需要你处理的事项。" else "交代一个目标，任务和进展会出现在这里。")}
                val renderedGroups=mutableSetOf<String>()
                sections.filter {it.second.isNotEmpty()}.forEach {(label,section) ->
                    val groups=section.mapNotNull {snapshot.assignmentFor(it.id)?.groupId}.distinct().filter {id->snapshot.groups.any {it.id==id}}
                    val newGroups=groups.filter {renderedGroups.add(it)}
                    val standalone=section.filter {snapshot.assignmentFor(it.id)?.groupId !in groups}
                    if(newGroups.isNotEmpty() || standalone.isNotEmpty()) item("section:$label") {Text(label,style=MaterialTheme.typography.titleMedium)}
                    newGroups.forEach {id -> snapshot.groups.firstOrNull {it.id==id}?.let {group ->
                        item("group:$label:$id") {AssistantGroupSteps(group,snapshot.tasks.filter {snapshot.assignmentFor(it.id)?.groupId==id},state,navigate)}
                    }}
                    items(standalone,key={it.id}) {task -> AssistantTaskSteps(task,state,navigate)}
                }
            }
        }
    }
}

/** Same bound-send receipt as the main composer; navigation cannot clear or
 * retarget a draft and an unknown result never enables a second send. */
@Composable internal fun AssistantTaskFollowup(task:AssistantTask,state:AssistantUiState,canAct:Boolean,act:(()->AssistantAction)->Unit,
    initialContext:String = "", artifact:AssistantArtifact? = null) {
    val snapshot=state.snapshot ?: return
    val preferences=androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("assistant-drafts-v2",0)
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
        Text(if(task.status in setOf(AssistantTaskStatus.Done,AssistantTaskStatus.Failed)) "基于成果继续" else "补充要求",style=MaterialTheme.typography.titleSmall)
        Text("消息会绑定此任务。审批请在上方的原请求中处理。",style=MaterialTheme.typography.bodySmall)
        PocketTextField(draft,{draft=it;preferences.edit().putString(draftKey,it).apply()},Modifier.testTag("assistant-task-draft:${task.id}"),placeholder="写下补充要求",minLines=2,maxLines=6)
        AssistantAttachmentPicker(attachments,task.id,submitted==null)
        if(submitted!=null) Text("已提交，等待电脑回执；草稿已保留。",style=MaterialTheme.typography.bodySmall)
        PocketButton(if(submitted!=null) "确认送达中" else "发送到此任务",{
            val id=java.util.UUID.randomUUID().toString()
            act {
                val base=AssistantActions.send(snapshot.runtimeId.orEmpty(),listOf(initialContext,draft).filter(String::isNotBlank).joinToString("\n\n").ifBlank {"请结合附件继续这项任务。"},null,taskId=task.id)
                val action=base.copy(commandId=id,composerBinding=snapshot.composerBinding(state.computerId),attachments=attachments.files.toList(),params=if(artifact==null) base.params else
                    JsonObject(base.params+mapOf("artifactId" to JsonPrimitive(artifact.id),"artifactVersion" to JsonPrimitive(artifact.version))))
                submitted=id;sentText=draft;attachments.sent(id,draft);action
            }
        },Modifier.fillMaxWidth().testTag("assistant-task-send:${task.id}"),enabled=canAct && snapshot.prepared && snapshot.paused!=true && !task.reconciliationRequired && submitted==null && !state.busy("assistant/send") && (draft.isNotBlank() || attachments.files.isNotEmpty()) && '\u0000' !in draft && (initialContext+draft).toByteArray().size<65000)
    }
}
