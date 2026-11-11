package dev.codexpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun AssistantScreen(state: AssistantUiState, models: List<NativeModel>, runtimes: List<AssistantRuntimeChoice>, workspaces: List<AssistantWorkspaceChoice>,
    onAction: (AssistantAction) -> Unit, onOpenThread: (String, String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, nowMs: Long = System.currentTimeMillis()) {
    val snapshot = state.snapshot
    val runtime = snapshot?.runtimeId.orEmpty()
    val canAct = snapshot?.enabled == true && runtimes.any {it.id==runtime && it.available} && !state.loading && state.error == null
    var tab by rememberSaveable { mutableStateOf("Chat") }
    var localError by rememberSaveable(runtime) { mutableStateOf<String?>(null) }
    val holder = rememberSaveableStateHolder()
    fun act(make: () -> AssistantAction) {
        val result = runCatching(make)
        result.onSuccess {localError=null;onAction(it)}.onFailure {localError=it.message ?: "请检查填写内容"}
    }
    val c = MaterialTheme.colorScheme
    ProvideMarkdownRendering {
        Column(modifier.fillMaxSize().background(c.background).systemBarsPadding().imePadding().testTag("assistant-screen")) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                AssistantPocketButton({tab="Chat"},attention=snapshot?.operations.orEmpty().any {it.phase==AssistantOperationPhase.Waiting && !it.suspended})
                Column(Modifier.weight(1f)) {
                    Text("口袋助理",style=MaterialTheme.typography.titleLarge)
                    Text(assistantStatus(state),style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
                }
                IconButton({act {AssistantActions.read(snapshot?.after ?: 0)}},enabled=!state.loading && !state.busy("assistant/read")) {Icon(Icons.Rounded.Refresh,"刷新助理状态")}
                IconButton(onBack) {Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回原页面")}
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Chat" to "对话","Memory" to "记忆","Schedules" to "定时","Settings" to "设置").forEach { (id,label) ->
                    PocketPill(label,tab==id,{tab=id},modifier=Modifier.weight(1f).testTag("assistant-tab:$id"))
                }
            }
            if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
            (state.error ?: localError)?.let {Text(it,Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp).testTag("assistant-error"),color=c.error,style=MaterialTheme.typography.bodyMedium)}
            Box(Modifier.weight(1f).fillMaxWidth().padding(top=8.dp)) {
                holder.SaveableStateProvider("$runtime:$tab") {
                    when(tab) {
                        "Chat" -> if(snapshot?.enabled != true) {
                            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp)) {
                                item {AssistantCard {
                                    Text(if(snapshot?.enabled==false) "让助理接住接下来的事" else "先读取电脑上的助理状态",style=MaterialTheme.typography.titleMedium)
                                    Text(if(snapshot?.enabled==false) "启用后，你可以交办任务、回答问题，并查看电脑上的执行结果。" else "尚未确认入口是否启用。",style=MaterialTheme.typography.bodyMedium,color=c.onSurfaceVariant)
                                    PocketButton(if(snapshot?.enabled==false) "前往启用设置" else "读取状态",{
                                        if(snapshot?.enabled==false) tab="Settings" else act {AssistantActions.read(0)}
                                    },Modifier.fillMaxWidth().testTag("assistant-configure"),enabled=!state.loading)
                                }}
                            }
                        } else AssistantChat(state,workspaces,canAct,{make -> act(make)},onOpenThread)
                        "Memory" -> AssistantMemories(state,canAct) {make -> act(make)}
                        "Schedules" -> AssistantSchedules(state,workspaces,canAct,nowMs) {make -> act(make)}
                        "Settings" -> AssistantSettingsPage(state,models,runtimes,canAct) {make -> act(make)}
                    }
                }
            }
        }
    }
}

private fun AssistantUiState.busy(method: String, id: String? = null) = method in pendingActions || (id!=null && "$method:$id" in pendingActions)
private fun assistantStatus(state: AssistantUiState): String = when {
    state.loading -> "正在读取状态"; state.error != null -> "暂时无法确认状态"; state.snapshot?.enabled==null -> "状态尚未读取"
    state.snapshot.enabled==false -> "尚未启用"; state.snapshot.runtimeId.isNullOrBlank() -> "运行入口尚未提供"; state.snapshot.paused==true -> "助理已暂停"
    state.snapshot.operations.any {it.phase==AssistantOperationPhase.Waiting && !it.suspended} -> "等待你确认"
    state.snapshot.operations.any {it.phase==AssistantOperationPhase.Unknown && !it.suspended} -> "正在核对运行状态"
    state.snapshot.operations.any {it.phase==AssistantOperationPhase.Running && !it.suspended} -> "正在处理"
    state.snapshot.tasks.any {it.status==AssistantTaskStatus.Queued} -> "有任务在排队"; else -> "已启用"
}
private fun taskLabel(status: AssistantTaskStatus) = when(status) {
    AssistantTaskStatus.Queued -> "排队中"; AssistantTaskStatus.Running -> "运行中"; AssistantTaskStatus.WaitingUser -> "等你确认"; AssistantTaskStatus.Paused -> "已暂停"
    AssistantTaskStatus.Done -> "已完成"; AssistantTaskStatus.Failed -> "执行失败"; AssistantTaskStatus.Unknown -> "状态待确认"
}
@Composable private fun AssistantCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    PocketGlassSurface(modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),dense=true) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable private fun AssistantBadge(text: String, failure: Boolean = false) {
    val c=MaterialTheme.colorScheme
    Text(text,Modifier.background(if(failure) c.errorContainer else c.surfaceContainerHigh,RoundedCornerShape(10.dp)).padding(horizontal=10.dp,vertical=5.dp),
        color=if(failure) c.onErrorContainer else c.onSurfaceVariant,style=MaterialTheme.typography.labelMedium)
}
@Composable private fun AssistantChoice(label: String, selected: String, tag: String, choices: List<Pair<String,String>>, enabled: Boolean = true, onChoose: (String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            PocketButton(selected,{expanded=true},Modifier.fillMaxWidth().testTag(tag),enabled=enabled,secondary=true)
            DropdownMenu(expanded,{expanded=false}) {
                choices.forEach {(id,text) -> DropdownMenuItem(text={Text(text)},onClick={expanded=false;onChoose(id)},modifier=Modifier.testTag("$tag:$id"))}
            }
        }
    }
}

@Composable private fun AssistantChat(state: AssistantUiState, workspaces: List<AssistantWorkspaceChoice>, canAct: Boolean, act: (() -> AssistantAction)->Unit, open: (String,String)->Unit) {
    val snapshot=state.snapshot ?: return
    val runtime=snapshot.runtimeId.orEmpty()
    var message by rememberSaveable {mutableStateOf("")}
    var sentDraft by rememberSaveable {mutableStateOf<String?>(null)}
    var sentAck by rememberSaveable {mutableLongStateOf(state.sendAcknowledgement)}
    var projectId by rememberSaveable {mutableStateOf<String?>(null)}
    LaunchedEffect(state.sendAcknowledgement) {
        if(state.sendAcknowledgement > sentAck && sentDraft != null) {if(message==sentDraft) message="";sentDraft=null;sentAck=state.sendAcknowledgement}
    }
    LaunchedEffect(state.error) {if(state.error!=null) sentDraft=null}
    val projects=workspaces.filter {it.runtimeId==runtime && it.threadId.isNotBlank()}.distinctBy {it.threadId}
    val project=projects.firstOrNull {it.threadId==projectId}
    val missingProject=projectId!=null && project==null
    val messages=remember(state.messages,snapshot) {
        (state.messages.filter {it.role in setOf("User","Assistant")} + snapshot.coordinatorReplies.filter {reply ->
            state.messages.none {it.role=="Assistant" && it.operationId==reply.operationId && it.text==reply.text}
        }).distinctBy {it.id}
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("assistant-chat-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            snapshot.coordinatorThreadId?.let {thread -> item {
                PocketTextButton({open(runtime,thread)},enabled=runtime.isNotBlank()) {Text("打开原协调对话")}
            }}
            if(messages.isEmpty()) item {Text("这里还没有收到真实消息记录。你可以交办新任务，或先查看下面的执行状态。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(messages,key={"message:${it.id}"}) {m ->
                AssistantCard(Modifier.testTag("assistant-message:${m.id}")) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(if(m.role=="User") "你" else "助理",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.secondary)
                        m.atMs?.let {Text(assistantTime(it,snapshot.settings?.timezone ?: "UTC"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                    if(m.role=="User") SelectionContainer {Text(m.text,style=MaterialTheme.typography.bodyLarge)} else MarkdownText(m.text,"assistant:$runtime:message:${m.id}")
                }
            }
            items(snapshot.operations.filter {it.question!=null}.distinctBy {it.id},key={"question:${it.id}"}) {op -> AssistantHumanQuestion(op,runtime,state,canAct,act)}
            if(snapshot.tasks.isNotEmpty()) item {PocketSectionLabel("任务 · ${snapshot.tasks.size}")}
            items(snapshot.tasks.distinctBy {it.id},key={"task:${it.id}"}) {task -> AssistantTaskCard(task,state,canAct,act,open)}
            items(snapshot.operations.filter {it.role=="Execution" && it.phase in setOf(AssistantOperationPhase.Completed,AssistantOperationPhase.Failed) && !it.result.isNullOrBlank()}.distinctBy {it.id},key={"result:${it.id}"}) {op ->
                AssistantCard(Modifier.testTag("assistant-result:${op.id}")) {
                    AssistantBadge(if(op.phase==AssistantOperationPhase.Completed) "执行结果" else "失败原因",op.phase==AssistantOperationPhase.Failed)
                    MarkdownText(op.result!!,"assistant:$runtime:result:${op.id}")
                    op.threadId?.let {thread -> PocketButton("查看原对话",{open(runtime,thread)},Modifier.fillMaxWidth().testTag("assistant-thread:${op.id}"),enabled=runtime.isNotBlank(),secondary=true)}
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            AssistantChoice("项目",project?.label ?: if(missingProject) "项目已不可用，请重新选择" else "不选择项目", "assistant-workspace",
                listOf("none" to "不选择项目")+projects.map {it.threadId to it.label},enabled=canAct) {projectId=it.takeUnless {it=="none"}}
            PocketTextField(message,{message=it},Modifier.testTag("assistant-message-input"),placeholder="把接下来的事交给助理…",enabled=canAct,minLines=1,maxLines=4)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(state.busy("assistant/send") || sentDraft!=null && sentDraft==message) "等待电脑确认，草稿已保留" else if(missingProject) "请重新确认项目" else if(snapshot.paused==true) "助理暂停中；新消息仍需电脑确认" else "使用所选运行入口",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                PocketButton("发送",{val text=message;act {AssistantActions.send(runtime,text,project)};sentDraft=text;sentAck=state.sendAcknowledgement},Modifier.testTag("assistant-send"),
                    enabled=canAct && !missingProject && !state.busy("assistant/send") && message.isNotBlank() && message.toByteArray().size<=65536 && !(sentDraft!=null && message==sentDraft))
            }
        }
    }
}
@Composable private fun AssistantTaskCard(task: AssistantTask, state: AssistantUiState, canAct: Boolean, act: (() -> AssistantAction)->Unit, open:(String,String)->Unit) {
    val runtime=state.snapshot?.runtimeId.orEmpty()
    val operation=state.snapshot?.operations?.firstOrNull {it.id==task.activeRunId}
    var expanded by rememberSaveable(task.id) {mutableStateOf(false)}
    AssistantCard(Modifier.testTag("assistant-task:${task.id}")) {
        AssistantBadge(taskLabel(task.status),task.status==AssistantTaskStatus.Failed)
        Text(task.prompt,style=MaterialTheme.typography.titleSmall,maxLines=if(expanded) Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis)
        PocketTextButton({expanded=!expanded}) {Text(if(expanded) "收起描述" else "展开描述")}
        task.detail?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.bodyMedium)}
        if(task.reconciliationRequired) Text("运行状态待电脑核对",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(task.status in setOf(AssistantTaskStatus.Queued,AssistantTaskStatus.Running,AssistantTaskStatus.WaitingUser)) PocketButton("暂停",{act {AssistantActions.task(runtime,task.id,false)}},Modifier.weight(1f).testTag("assistant-task-pause:${task.id}"),enabled=canAct && !state.busy("assistant/task/pause",task.id),secondary=true)
            if(task.status in setOf(AssistantTaskStatus.Paused,AssistantTaskStatus.WaitingUser) && !task.reconciliationRequired && task.activeRunId==null) PocketButton("继续",{act {AssistantActions.task(runtime,task.id,true)}},Modifier.weight(1f).testTag("assistant-task-resume:${task.id}"),enabled=canAct && !state.busy("assistant/task/resume",task.id),secondary=true)
            operation?.threadId?.let {thread -> PocketButton("原对话",{open(runtime,thread)},Modifier.weight(1f).testTag("assistant-task-thread:${task.id}"),enabled=runtime.isNotBlank(),secondary=true)}
        }
    }
}
@Composable private fun AssistantHumanQuestion(op: AssistantOperation, runtime:String, state:AssistantUiState, canAct:Boolean, act:(()->AssistantAction)->Unit) {
    val q=op.question ?: return
    var answer by rememberSaveable(op.id,q.requestId) {mutableStateOf("")}
    val allowed=canAct && op.phase==AssistantOperationPhase.Waiting && !op.suspended && !state.busy("assistant/respond",op.id)
    AssistantCard(Modifier.testTag("assistant-question:${op.id}")) {
        AssistantBadge(if(q.kind==AssistantQuestionKind.Approval) "需要你的审批" else "需要你的回答")
        if(op.suspended || op.phase!=AssistantOperationPhase.Waiting) Text("当前操作已暂停或状态待确认，请先刷新。",style=MaterialTheme.typography.bodySmall)
        when(q.kind) {
            AssistantQuestionKind.Input -> {
                val questions=remember(q.text) {runCatching {pocketJson.parseToJsonElement(q.text) as? JsonArray}.getOrNull()}
                if(questions!=null) {
                    val batch=QuestionBatches.blocking("assistant:$runtime:${op.id}:${q.requestId}",buildJsonObject {put("questions",questions)})
                    val session=remember(batch.id) {QuestionSession(batch)}
                    var validationError by remember(batch.id) {mutableStateOf<String?>(null)}
                    DisposableEffect(session) {onDispose {session.clear()}}
                    session.update(batch)
                    session.observe(false,state.busy("assistant/respond",op.id),canAct && op.phase==AssistantOperationPhase.Waiting && !op.suspended)
                    if(batch.valid) QuestionCard(batch,session,if(allowed) QuestionPhase.Waiting else QuestionPhase.Stale,onSubmit={answers ->
                        val encoded=if(answers.size==1) answers.single().second else buildJsonObject {answers.forEach {(id,text)->put(id,buildJsonArray {add(text)})}}.toString()
                        runCatching {AssistantActions.respond(runtime,op.id,q.requestId,encoded)}
                            .onSuccess {action -> validationError=null;act {action}}
                            .onFailure {session.rejectBeforeDispatch();validationError="回答合计最多 64KiB，请缩短后重试"}
                    },enabled=allowed)
                    else Text("问题格式尚未识别，请在原对话中回答。",style=MaterialTheme.typography.bodySmall)
                    validationError?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                } else {
                    MarkdownText(q.text,"assistant:$runtime:question:${op.id}:${q.requestId}")
                    PocketTextField(answer,{answer=it},Modifier.testTag("assistant-answer:${op.id}"),label="你的回答",enabled=allowed,minLines=2,maxLines=6)
                    PocketButton("提交回答",{act {AssistantActions.respond(runtime,op.id,q.requestId,answer)}},Modifier.fillMaxWidth().testTag("assistant-respond:${op.id}"),enabled=allowed && answer.isNotBlank() && answer.toByteArray().size<=65536)
                }
            }
            AssistantQuestionKind.Approval -> {
                val details=remember(q.text) {runCatching {pocketJson.parseToJsonElement(q.text) as? JsonObject}.getOrNull()}
                val permissions=details?.get("permissions")
                val permissionLines=permissions?.let {assistantPermissionLines(it)}
                if(details==null) MarkdownText(q.text,"assistant:$runtime:question:${op.id}:${q.requestId}")
                else {
                    details.string("command").takeIf(String::isNotBlank)?.let {SelectionContainer {Text(it,style=MaterialTheme.typography.bodyMedium)}}
                    details.string("reason").takeIf(String::isNotBlank)?.let {Text(it,style=MaterialTheme.typography.bodyMedium)}
                    details.array("changes").map {it.asObject().string("path")}.filter(String::isNotBlank).distinct().forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
                    details.string("cwd").takeIf(String::isNotBlank)?.let {Text("工作目录 · $it",style=MaterialTheme.typography.bodySmall)}
                    if(permissions!=null) {
                        if(permissionLines==null) Text("此权限范围尚未识别，请在原对话核对并批准。",style=MaterialTheme.typography.bodySmall)
                        else permissionLines.forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
                    }
                }
                val decisions=details?.get("availableDecisions") as? JsonArray
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    PocketButton("同意这次",{act {AssistantActions.approve(runtime,op.id,q.requestId,true)}},Modifier.weight(1f).testTag("assistant-approve:${op.id}"),enabled=allowed && (permissions==null || permissionLines!=null) && (decisions==null || JsonPrimitive("accept") in decisions))
                    PocketButton("拒绝",{act {AssistantActions.approve(runtime,op.id,q.requestId,false)}},Modifier.weight(1f).testTag("assistant-deny:${op.id}"),enabled=allowed && (decisions==null || JsonPrimitive("decline") in decisions || JsonPrimitive("cancel") in decisions),secondary=true)
                }
            }
            AssistantQuestionKind.Unknown -> Text("问题类型尚未识别，请在原对话中确认。",style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun AssistantMemories(state:AssistantUiState,canAct:Boolean,act:(()->AssistantAction)->Unit) {
    val runtime=state.snapshot?.runtimeId.orEmpty()
    var editing by rememberSaveable {mutableStateOf(false)}
    var editId by rememberSaveable {mutableStateOf<String?>(null)}
    var text by rememberSaveable {mutableStateOf("")}
    var deleteId by rememberSaveable {mutableStateOf<String?>(null)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("记住你明确告诉助理的事",style=MaterialTheme.typography.titleMedium);Text("这里只显示已保存的记忆。新增、修改和删除都由你决定。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {PocketButton("新增记忆",{editId=null;editing=true},Modifier.fillMaxWidth().testTag("assistant-memory-add"),enabled=canAct && !state.busy("assistant/memory/add"))}
        if(state.snapshot?.memories.isNullOrEmpty()) item {Text(if(state.snapshot==null) "记忆尚未读取" else "暂无已保存的记忆",style=MaterialTheme.typography.bodyMedium)}
        items(state.snapshot?.memories.orEmpty().distinctBy {it.id},key={it.id}) {m -> AssistantCard {
            SelectionContainer {Text(m.text,style=MaterialTheme.typography.bodyLarge)}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PocketButton("修改",{editId=m.id;text=m.text;editing=true},Modifier.weight(1f).testTag("assistant-memory-edit:${m.id}"),enabled=canAct && !state.busy("assistant/memory/update",m.id),secondary=true)
                PocketButton("删除",{deleteId=m.id},Modifier.weight(1f).testTag("assistant-memory-delete:${m.id}"),enabled=canAct && !state.busy("assistant/memory/delete",m.id),secondary=true)
            }
        }}
    }
    if(editing) PocketDialog({editing=false},title={Text(if(editId==null) "新增记忆" else "修改记忆")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {PocketTextField(text,{text=it},Modifier.heightIn(max=300.dp).testTag("assistant-memory-text"),placeholder="写下希望助理记住的内容",minLines=3,maxLines=9);Text("最多16KiB；保存后以电脑回执为准。",style=MaterialTheme.typography.labelSmall)}
    },confirmButton={PocketButton("保存",{val id=editId;act {if(id==null) AssistantActions.memoryAdd(runtime,text) else AssistantActions.memoryEdit(runtime,id,text)};editing=false},Modifier.testTag("assistant-memory-save"),enabled=canAct && text.isNotBlank() && text.toByteArray().size<=16384 && !state.busy(if(editId==null) "assistant/memory/add" else "assistant/memory/update",editId))},dismissButton={PocketTextButton({editing=false}) {Text("取消")}})
    deleteId?.let {id -> AssistantDeleteDialog("删除这条记忆？",{deleteId=null},canAct && !state.busy("assistant/memory/delete",id)) {act {AssistantActions.memoryDelete(runtime,id)};deleteId=null}}
}
@Composable private fun AssistantDeleteDialog(title:String,dismiss:()->Unit,enabled:Boolean,confirm:()->Unit) {
    PocketDialog(dismiss,title={Text(title)},text={Text("确认后交给电脑处理，收到回执后列表才会更新。")},confirmButton={PocketButton("确认删除",confirm,Modifier.testTag("assistant-confirm-delete"),enabled=enabled,destructive=true)},dismissButton={PocketTextButton(dismiss) {Text("取消")}})
}

@Composable private fun AssistantSettingsPage(state:AssistantUiState,models:List<NativeModel>,runtimes:List<AssistantRuntimeChoice>,canAct:Boolean,act:(()->AssistantAction)->Unit) {
    val s=state.snapshot?.settings
    val current=state.snapshot?.runtimeId.orEmpty()
    val choices=runtimes.filter {it.available && it.id.isNotBlank()}.distinctBy {it.id}
    var selected by rememberSaveable {mutableStateOf(current.ifBlank {choices.singleOrNull()?.id.orEmpty()})}
    var personality by rememberSaveable {mutableStateOf(s?.personality.orEmpty())}
    var tone by rememberSaveable {mutableStateOf(s?.tone.orEmpty())}
    var model by rememberSaveable {mutableStateOf(s?.model)}
    var zone by rememberSaveable {mutableStateOf(s?.timezone ?: ZoneId.systemDefault().id)}
    var completed by rememberSaveable {mutableStateOf(s?.notifications?.completed ?: true)}
    var failed by rememberSaveable {mutableStateOf(s?.notifications?.failed ?: true)}
    var waiting by rememberSaveable {mutableStateOf(s?.notifications?.waitingUser ?: true)}
    var dirty by rememberSaveable {mutableStateOf(false)}
    val draft=AssistantSettings(personality,tone,model,zone,AssistantNotifications(completed,failed,waiting))
    LaunchedEffect(s) {if(s!=null && (!dirty || s==draft)) {personality=s.personality;tone=s.tone;model=s.model;zone=s.timezone;completed=s.notifications.completed;failed=s.notifications.failed;waiting=s.notifications.waitingUser;dirty=false}}
    val savedAction=runCatching {AssistantActions.settings(current,draft,models)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {AssistantCard {
            Text("助理的运行入口",style=MaterialTheme.typography.titleMedium)
            AssistantChoice("电脑上的配置",choices.firstOrNull {it.id==selected}?.label ?: "请选择运行入口","assistant-runtime",choices.map {it.id to it.label},enabled=!state.loading) {selected=it}
            if(state.snapshot?.enabled!=true || selected!=current) {
                Text(if(state.snapshot?.enabled==null) "请先读取启用状态。" else "启用请求会交给所选运行入口确认。",style=MaterialTheme.typography.bodyMedium)
                PocketButton(if(state.snapshot?.enabled==false) "启用助理" else "使用这个运行入口",{act {AssistantActions.enable(selected)}},Modifier.fillMaxWidth().testTag("assistant-enable"),enabled=state.snapshot?.enabled!=null && choices.any {it.id==selected} && !state.loading && state.error==null && !state.busy("assistant/enable"))
            }
        }}
        item {PocketSectionLabel("人格与语气")}
        item {PocketTextField(personality,{personality=it;dirty=true},Modifier.testTag("assistant-personality"),label="人格",placeholder="希望助理如何协助你",minLines=3,maxLines=8)}
        item {PocketTextField(tone,{tone=it;dirty=true},Modifier.testTag("assistant-tone"),label="语气",placeholder="例如：直接、耐心、简洁",minLines=2,maxLines=5)}
        item {
            AssistantChoice("模型",if(model==null) "随运行入口默认模型" else models.firstOrNull {it.model==model}?.name?.ifBlank {model!!} ?: "当前模型未在目录确认",
                "assistant-model",listOf("host-default" to "随运行入口默认模型")+models.filter {it.model.isNotBlank()}.distinctBy {it.model}.map {"model:${it.model}" to it.name.ifBlank {it.model}},enabled=selected==current || state.snapshot?.enabled==false) {model=if(it=="host-default") null else it.removePrefix("model:");dirty=true}
            if(model!=null && models.none {it.model==model}) Text("保留原模型；请刷新目录或明确选择默认模型。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {PocketTextField(zone,{zone=it;dirty=true},Modifier.testTag("assistant-timezone"),label="时区",placeholder="Asia/Shanghai",singleLine=true);PocketTextButton({zone=ZoneId.systemDefault().id;dirty=true}) {Text("使用手机时区 · ${ZoneId.systemDefault().id}")}}
        item {PocketSectionLabel("通知意向")}
        item {AssistantSettingToggle("任务完成",completed,"assistant-notify-completed") {completed=it;dirty=true}}
        item {AssistantSettingToggle("执行失败",failed,"assistant-notify-failed") {failed=it;dirty=true}}
        item {AssistantSettingToggle("等待我确认",waiting,"assistant-notify-waiting") {waiting=it;dirty=true}}
        item {
            Text("这些设置决定哪些事件需要通知；系统通知权限由主界面管理。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            savedAction.exceptionOrNull()?.message?.let {Text(it,Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            PocketButton("保存设置",{act {savedAction.getOrThrow()}},Modifier.fillMaxWidth().padding(top=12.dp).testTag("assistant-save-settings"),enabled=canAct && current==selected && savedAction.isSuccess && !state.busy("assistant/settings/update"))
        }
        if(state.snapshot?.enabled==true) item {AssistantCard {
            Text("暂时放下任务",style=MaterialTheme.typography.titleMedium)
            Text("暂停会交给电脑处理；已暂停任务需要单独继续。",style=MaterialTheme.typography.bodyMedium)
            val paused=state.snapshot.paused==true
            PocketButton(if(paused) "恢复助理" else "暂停助理",{act {if(paused) AssistantActions.resume(current) else AssistantActions.pause(current)}},Modifier.fillMaxWidth().testTag("assistant-global-pause"),enabled=canAct && !state.busy(if(paused) "assistant/resume" else "assistant/pause"),secondary=true)
        }}
    }
}
@Composable private fun AssistantSettingToggle(text:String,checked:Boolean,tag:String,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge);PocketToggle(checked,onChange,Modifier.testTag(tag))}
}

@Composable private fun AssistantSchedules(state:AssistantUiState,workspaces:List<AssistantWorkspaceChoice>,canAct:Boolean,now:Long,act:(()->AssistantAction)->Unit) {
    val runtime=state.snapshot?.runtimeId.orEmpty()
    var creating by rememberSaveable {mutableStateOf(false)}
    var deleteId by rememberSaveable {mutableStateOf<String?>(null)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("把事项放在合适的时间",style=MaterialTheme.typography.titleMedium);Text("按创建时区执行；离线或暂停时错过的事项不会补执行。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {PocketButton("创建定时事项",{creating=true},Modifier.fillMaxWidth().testTag("assistant-schedule-add"),enabled=canAct && !state.busy("assistant/schedule/create"))}
        if(state.snapshot?.schedules.isNullOrEmpty()) item {Text(if(state.snapshot==null) "定时事项尚未读取" else "暂无定时事项",style=MaterialTheme.typography.bodyMedium)}
        items(state.snapshot?.schedules.orEmpty().distinctBy {it.id},key={it.id}) {s -> AssistantCard {
            Text(s.prompt,style=MaterialTheme.typography.titleMedium)
            Text(assistantScheduleLabel(s),style=MaterialTheme.typography.bodyMedium)
            Text("创建时区 · ${s.timezone.ifBlank {"未提供"}}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(s.missed) AssistantBadge("已错过 · 不补执行")
            s.error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}
            Text(s.nextMs?.let {"下次 · ${assistantTime(it,s.timezone)}"} ?: "暂无下次执行时间",style=MaterialTheme.typography.bodyMedium)
            PocketButton("删除事项",{deleteId=s.id},Modifier.fillMaxWidth().testTag("assistant-schedule-delete:${s.id}"),enabled=canAct && !state.busy("assistant/schedule/delete",s.id),secondary=true)
        }}
    }
    if(creating) AssistantScheduleForm(runtime,state,workspaces,now,canAct,{creating=false},act)
    deleteId?.let {id -> AssistantDeleteDialog("删除这个定时事项？",{deleteId=null},canAct && !state.busy("assistant/schedule/delete",id)) {act {AssistantActions.scheduleDelete(runtime,id)};deleteId=null}}
}
@Composable private fun AssistantScheduleForm(runtime:String,state:AssistantUiState,workspaces:List<AssistantWorkspaceChoice>,now:Long,canAct:Boolean,dismiss:()->Unit,act:(()->AssistantAction)->Unit) {
    var prompt by rememberSaveable {mutableStateOf("")}
    var kind by rememberSaveable {mutableStateOf("Once")}
    var zone by rememberSaveable {mutableStateOf(state.snapshot?.settings?.timezone ?: ZoneId.systemDefault().id)}
    var date by rememberSaveable {mutableStateOf(Instant.ofEpochMilli(now).atZone(runCatching {ZoneId.of(zone)}.getOrDefault(ZoneId.systemDefault())).toLocalDate().plusDays(1).toString())}
    var time by rememberSaveable {mutableStateOf("09:00")}
    var weekday by rememberSaveable {mutableIntStateOf(0)}
    var minutes by rememberSaveable {mutableStateOf("60")}
    var projectId by rememberSaveable {mutableStateOf<String?>(null)}
    var writes by rememberSaveable {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val projects=workspaces.filter {it.runtimeId==runtime && it.threadId.isNotBlank()}.distinctBy {it.threadId}
    val project=projects.firstOrNull {it.threadId==projectId}
    PocketDialog(dismiss,title={Text("创建定时事项")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        PocketTextField(prompt,{prompt=it},Modifier.testTag("assistant-schedule-prompt"),label="事项",minLines=2,maxLines=5)
        AssistantChoice("已授权项目",project?.label ?: "请选择项目","assistant-schedule-workspace",projects.map {it.threadId to it.label}) {projectId=it;if(projects.none {p -> p.threadId==it && p.canWrite}) writes=false}
        Text("仅限电脑验证过的项目范围；原生审批仍需你逐次确认。",style=MaterialTheme.typography.bodySmall)
        AssistantChoice("重复方式",mapOf("Once" to "一次","Daily" to "每天","Weekly" to "每周","Interval" to "间隔")[kind].orEmpty(),"assistant-schedule-kind",listOf("Once" to "一次","Daily" to "每天","Weekly" to "每周","Interval" to "间隔")) {kind=it}
        if(kind=="Once") PocketTextField(date,{date=it},Modifier.testTag("assistant-schedule-date"),label="日期 · YYYY-MM-DD",singleLine=true)
        if(kind in setOf("Once","Daily","Weekly")) PocketTextField(time,{time=it},Modifier.testTag("assistant-schedule-time"),label="时间 · HH:mm",singleLine=true)
        if(kind=="Weekly") AssistantChoice("星期",assistantWeekdays[weekday],"assistant-schedule-weekday",assistantWeekdays.mapIndexed {i,s -> i.toString() to s}) {weekday=it.toInt()}
        if(kind=="Interval") PocketTextField(minutes,{minutes=it},Modifier.testTag("assistant-schedule-interval"),label="每隔多少分钟",singleLine=true)
        PocketTextField(zone,{zone=it},Modifier.testTag("assistant-schedule-timezone"),label="创建时区",singleLine=true)
        AssistantSettingToggle("允许使用已有写入授权",writes,"assistant-schedule-writes") {writes=it && project?.canWrite==true}
        if(project?.canWrite!=true) Text("所选项目将以只读方式处理。",style=MaterialTheme.typography.bodySmall)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("assistant-schedule-error"))}
    }},confirmButton={PocketButton("创建",{
        val result=runCatching {AssistantActions.schedule(runtime,requireNotNull(project) {"请选择已授权项目"},AssistantScheduleDraft(prompt,kind,date,time,weekday,minutes,zone,writes),now)}
        result.onSuccess {action -> act {action};dismiss()}.onFailure {error=it.message ?: "请检查日期和时区"}
    },Modifier.testTag("assistant-schedule-save"),enabled=canAct && project!=null && prompt.isNotBlank() && !state.busy("assistant/schedule/create"))},dismissButton={PocketTextButton(dismiss) {Text("取消")}})
}
private val assistantWeekdays=listOf("周一","周二","周三","周四","周五","周六","周日")
private fun assistantTime(ms:Long,zone:String):String = runCatching {DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.of(zone)))}.getOrDefault("时间待确认")
private fun assistantScheduleLabel(s:AssistantSchedule):String = when(val type=s.kind.keys.singleOrNull()) {
    "Once" -> "一次 · ${s.kind.obj(type).number("at_ms").let {assistantTime(it,s.timezone)}}"
    "Daily" -> s.kind.obj(type).let {"每天 · %02d:%02d".format(it.number("hour"),it.number("minute"))}
    "Weekly" -> s.kind.obj(type).let {"${assistantWeekdays.getOrNull(it.number("weekday").toInt()) ?: "星期待确认"} · %02d:%02d".format(it.number("hour"),it.number("minute"))}
    "Interval" -> s.kind.obj(type).number("every_ms").let {if(it>=60000) "每 ${it/60000} 分钟" else "每 ${it/1000} 秒"};else -> "定时类型待确认"
}
