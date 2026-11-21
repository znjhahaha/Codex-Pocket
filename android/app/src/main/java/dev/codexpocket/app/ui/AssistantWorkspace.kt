package dev.codexpocket.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

/** The assistant has its own navigation and reading surfaces. Native execution
 * and request identities continue through the existing repository and service. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AssistantWorkspace(state:AssistantUiState,models:List<NativeModel>,runtimes:List<AssistantRuntimeChoice>,
    workspaces:List<AssistantWorkspaceChoice>,onAction:(AssistantAction)->Unit,onOpenThread:(String,String)->Unit,onBack:()->Unit,
    modifier:Modifier,nowMs:Long,connected:Boolean,onProjectSelected:(AssistantWorkspaceChoice?)->Unit) {
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val colors=if(dark) darkColorScheme(primary=Color(0xffa3c3b6),onPrimary=Color(0xff173c30),secondary=Color(0xffa3c3b6),
        secondaryContainer=Color(0xff334d42),onSecondaryContainer=Color(0xffd4e7dc),outline=Color(0xff7e8982),outlineVariant=Color(0xff414b44),
        background=Color(0xff171a19),surface=Color(0xff171a19),surfaceContainerLow=Color(0xff202422),surfaceContainerHigh=Color(0xff2d3330),
        onSurface=Color(0xffe4e8e5),onSurfaceVariant=Color(0xffb8c0ba)) else lightColorScheme(primary=Color(0xff476b5e),onPrimary=Color.White,
        secondary=Color(0xff476b5e),secondaryContainer=Color(0xffdce9df),onSecondaryContainer=Color(0xff294a3b),outline=Color(0xff758078),outlineVariant=Color(0xffc6cec8),
        background=Color(0xfff6f7f5),surface=Color(0xfff6f7f5),surfaceContainerLow=Color.White,
        surfaceContainerHigh=Color(0xffe7ede8),onSurface=Color(0xff232925),onSurfaceVariant=Color(0xff5b655e))
    MaterialTheme(colorScheme=colors) { ProvideMarkdownRendering {
        val snapshot=state.snapshot
        val owner=state.computerId
        var tab by rememberSaveable(owner) {mutableStateOf("Main")}
        var stack by rememberSaveable(owner) {mutableStateOf(emptyList<String>())}
        var menu by remember {mutableStateOf(false)}
        var error by remember {mutableStateOf<String?>(null)}
        val holder=rememberSaveableStateHolder()
        val canAct=connected && snapshot?.enabled==true && snapshot.fresh!=false && state.readError==null && !state.loading
        fun act(make:()->AssistantAction) {runCatching(make).onSuccess {error=null;onAction(it)}.onFailure {error=it.message}}
        fun navigate(destination:String) {if(destination!=tab) {stack=stack+tab;tab=destination};menu=false}
        fun back() {if(stack.isNotEmpty()) {tab=stack.last();stack=stack.dropLast(1)} else if(tab!="Main") tab="Main" else onBack()}
        BackHandler {back()}
        val title=when {tab=="Main"->"助理";tab=="Tasks"->"任务";tab=="Artifacts"->"成果";tab=="Plans"->"计划";
            tab=="Memory"->"记忆";tab=="Settings"->"助理设置";tab.startsWith("Task:")->"任务详情";tab.startsWith("Artifact:")->"成果详情";else->"计划"}
        Scaffold(modifier.testTag("assistant-workspace"),containerColor=colors.background,
            topBar={TopAppBar(title={PocketDestinationTitle(title,style=MaterialTheme.typography.titleLarge)},navigationIcon={IconButton({back()},Modifier.testTag("assistant-back")) {Icon(Icons.Outlined.ArrowBack,"返回")}},
                actions={Box {IconButton({menu=true},Modifier.testTag("assistant-title-menu")) {Icon(Icons.Outlined.MoreVert,"更多")}
                    DropdownMenu(menu,{menu=false}) {
                        DropdownMenuItem(text={Text("记忆")},onClick={navigate("Memory")})
                        DropdownMenuItem(text={Text("助理设置")},onClick={navigate("Settings")})
                    }}},colors=TopAppBarDefaults.topAppBarColors(containerColor=colors.background))},
            bottomBar={NavigationBar(containerColor=colors.surfaceContainerLow,tonalElevation=0.dp) {
                listOf(Triple("Main","助理",Icons.Outlined.ChatBubbleOutline),Triple("Tasks","任务",Icons.Outlined.Checklist),
                    Triple("Artifacts","成果",Icons.Outlined.FolderOpen),Triple("Plans","计划",Icons.Outlined.EventRepeat)).forEach {(id,label,icon)->
                    NavigationBarItem(selected=tab==id,onClick={tab=id;stack=emptyList()},icon={Icon(icon,null)},label={PocketTabLabel(label,tab==id)},modifier=Modifier.testTag("assistant-nav:$id"))
                }
            }}) {padding->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                val issue=error ?: state.readError ?: state.error?.takeUnless {
                    (it==state.unconfirmedFeedback && snapshot?.protocolVersion==2) ||
                        (tab=="Main" && snapshot?.prepared!=true && it==snapshot?.preparationIssue)
                }
                issue?.let {Surface(color=colors.errorContainer,modifier=Modifier.fillMaxWidth().testTag("assistant-workspace-error")) {
                    Row(Modifier.padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(it,Modifier.weight(1f),color=colors.onErrorContainer,style=MaterialTheme.typography.bodySmall)
                        TextButton({act {AssistantActions.read(snapshot?.after ?: 0)}}) {Text("重试读取")}
                    }
                }}
                if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.weight(1f)) {holder.SaveableStateProvider("$owner:$tab") {
                    when {
                        tab=="Main"->AssistantMainPage(state,workspaces,canAct,connected,::act,::navigate,onProjectSelected)
                        tab=="Tasks"->AssistantTasksPage(state,::navigate)
                        tab=="Artifacts"->AssistantResultsPage(state,::navigate,onOpenThread)
                        tab=="Plans"->AssistantSchedules(state,workspaces,canAct,nowMs,onProjectSelected,::navigate,::act)
                        tab=="Settings"->AssistantSettingsPage(state,models,runtimes,canAct,::act)
                        tab=="Memory"->AssistantMemoryPage(state,canAct,::act)
                        tab.startsWith("Task:")->AssistantWorkDetail(tab.substringAfter(':'),state,canAct,::act,::navigate,onOpenThread)
                        tab.startsWith("Artifact:")->AssistantDetailPage(tab,state,canAct,::act,onOpenThread,workspaces,::navigate)
                        tab.startsWith("ScheduleFrom:")-> {
                            val task=snapshot?.tasks?.firstOrNull {it.id==tab.substringAfter(':')}
                            AssistantScheduleForm(snapshot?.runtimeId.orEmpty(),state,workspaces,nowMs,canAct,::back,onProjectSelected,::act,initialPrompt=task?.prompt.orEmpty())
                        }
                    }
                }}
            }
        }
    }}
}

@Composable private fun AssistantMainPage(state:AssistantUiState,workspaces:List<AssistantWorkspaceChoice>,canAct:Boolean,connected:Boolean,
    act:(()->AssistantAction)->Unit,navigate:(String)->Unit,onProjectSelected:(AssistantWorkspaceChoice?)->Unit) {
    val snapshot=state.snapshot ?: AssistantSnapshot()
    val binding=snapshot.composerBinding(state.computerId)
    val preferences=LocalContext.current.getSharedPreferences("assistant-drafts-v2",0)
    val preparationKey="preparing:${state.computerId}"
    val key=if(snapshot.assistantId==null && snapshot.enabled!=true) preparationKey else "main:${binding.scopeKey}"
    var draft by rememberSaveable(key) {mutableStateOf(preferences.getString(key,null) ?: preferences.getString(preparationKey,"").orEmpty())}
    val attachments=rememberAssistantAttachments("main:${state.computerId}:${snapshot.assistantId ?: "preparing"}")
    LaunchedEffect(attachments.key) {if(snapshot.assistantId!=null) attachments.adoptPreparingDraft("main:${state.computerId}:preparing")}
    LaunchedEffect(key) {if(key!=preparationKey && snapshot.prepared) {
        preferences.edit().putString(key,draft).remove(preparationKey).apply()
    }}
    var sentId by rememberSaveable(key) {mutableStateOf(attachments.pendingId)}
    var sentText by rememberSaveable(key) {mutableStateOf(attachments.sentText)}
    var selected by rememberSaveable(key) {mutableStateOf<String?>(null)}
    var projectMenu by remember {mutableStateOf(false)}
    val projects=workspaces.filter {!it.isSubagent}.distinctBy {it.selectionKey}
    val project=projects.firstOrNull {it.selectionKey==selected}
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val messages=state.messages.distinctBy {it.id}.sortedWith(compareBy<AssistantChatMessage> {it.atMs ?: 0}.thenBy {it.id})
    val taskMessages=messages.mapNotNull {m->m.taskId?.let {it to m.id}}.toMap()
    val tasks=snapshot.orderedTasks().associateBy {it.id}
    val last=messages.lastOrNull()?.id
    var previousLast by rememberSaveable(key) {mutableStateOf<String?>(null)}
    var moreBelow by rememberSaveable(key) {mutableStateOf(false)}
    LaunchedEffect(last) {
        if(last!=previousLast) {
            if(previousLast==null || !list.canScrollForward) {if(list.layoutInfo.totalItemsCount>0) list.scrollToItem(list.layoutInfo.totalItemsCount-1);moreBelow=false}
            else moreBelow=true
            previousLast=last
        }
    }
    LaunchedEffect(state.sendReceipts,sentId) {
        val receipt=state.sendReceipts.firstOrNull {it.key==sentId?.let(binding::receiptKey)}
        if(receipt!=null) {
            if(receipt.status=="accepted" && draft==sentText) {draft="";preferences.edit().putString(key,"").apply()}
            attachments.complete(receipt.status)
            if(receipt.status in setOf("accepted","rejected")) {sentId=null;sentText=null}
        }
    }
    DisposableEffect(Unit) {onDispose {onProjectSelected(null)}}
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("assistant-chat-list"),state=list,
            contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(snapshot.prepared!=true) item {AssistantCard {
                Text(if(connected) "准备好后，把事情交给助理" else "电脑离线，草稿会保留",style=MaterialTheme.typography.titleMedium)
                snapshot.preparationIssue?.let {Text(it,style=MaterialTheme.typography.bodyMedium)}
                Button({act {AssistantActions.prepare()}},enabled=connected && "assistant/prepare" !in state.pendingActions,modifier=Modifier.testTag("assistant-prepare")) {Text("准备助理")}
            }}
            val pending=snapshot.pendingOperations()
            if(pending.isNotEmpty()) item {Text("待你处理 · ${pending.size}",style=MaterialTheme.typography.titleMedium)}
            items(pending.filter {it.taskId !in tasks},key={"question:${it.id}"}) {op->AssistantHumanQuestion(op,snapshot.runtimeId.orEmpty(),state,canAct,act)}
            items(snapshot.recoveries,key={"recovery:${it.operationId}:${it.requestId}"}) {request->
                AssistantRecoveryCard(request,snapshot.runtimeId.orEmpty(),state,canAct,act)
            }
            items(snapshot.operations.filter {it.handoffRecovery!=null},key={"handoff:${it.id}"}) {op->
                AssistantHandoffRecoveryCard(op,snapshot.runtimeId.orEmpty(),state,canAct,act)
            }
            items(snapshot.operations.flatMap {it.heldInputs}.distinctBy {"${it.operationId}:${it.callId}"},
                key={"ended:${it.operationId}:${it.callId}"}) {AssistantEndedQuestion(it)}
            val unknown=snapshot.domain?.attention.orEmpty().filter {it.state=="open" && it.kind=="verification"}
            if(unknown.isNotEmpty()) item {AssistantCard {
                Text("${unknown.size} 次执行待核对",style=MaterialTheme.typography.titleSmall)
                Text("保留原执行与回执，你可以继续交办其他目标。",style=MaterialTheme.typography.bodyMedium)
                TextButton({navigate("Tasks")}) {Text("查看待核对任务")}
            }}
            if(state.nextBefore!=null) item {TextButton({act {AssistantActions.read(0,state.nextBefore)}},enabled=!state.busy("assistant/read")) {Text("查看更早消息")}}
            if(messages.isEmpty() && snapshot.prepared) item {
                Column(Modifier.padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("想推进哪件事？",style=MaterialTheme.typography.headlineMedium)
                    Text("说清目标，助理会安排任务、汇报进展，把需要你决定的事带回来。",color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(messages,key={"message:${it.id}"}) {message->
                val outgoing=message.role=="User"
                Column(Modifier.fillMaxWidth(),horizontalAlignment=if(outgoing) Alignment.End else Alignment.Start) {
                    Text(if(outgoing) "你" else message.authorName ?: "助理",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(color=if(outgoing) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape=RoundedCornerShape(18.dp),modifier=Modifier.padding(top=6.dp).testTag("assistant-message:${message.id}")) {
                        Box(Modifier.padding(14.dp)) {
                            if(outgoing) SelectionContainer {Text(message.text,style=MaterialTheme.typography.bodyLarge)}
                            else MarkdownText(message.text,"assistant:$key:${message.id}")
                        }
                    }
                    message.taskId?.takeIf {taskMessages[it]==message.id}?.let(tasks::get)?.let {task->
                        Spacer(Modifier.height(12.dp))
                        AssistantMainTaskCard(task,state,canAct,act,navigate)
                    }
                }
            }
            items(tasks.values.filter {it.id !in taskMessages},key={"task:${it.id}"}) {task->
                AssistantMainTaskCard(task,state,canAct,act,navigate)
            }
        }
        if(moreBelow) TextButton({scope.launch {list.animateScrollToItem((list.layoutInfo.totalItemsCount-1).coerceAtLeast(0));moreBelow=false}},Modifier.align(Alignment.CenterHorizontally)) {Text("查看新进展")}
        Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,tonalElevation=0.dp) {
            Column(Modifier.padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Box {
                    TextButton({projectMenu=true},Modifier.testTag("assistant-workspace-project")) {Icon(Icons.Outlined.FolderOpen,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(project?.label ?: "助理工作区",maxLines=1,overflow=TextOverflow.Ellipsis)}
                    DropdownMenu(projectMenu,{projectMenu=false}) {
                        DropdownMenuItem(text={Text("助理工作区")},onClick={selected=null;onProjectSelected(null);projectMenu=false})
                        projects.forEach {p->DropdownMenuItem(text={Text(p.label)},onClick={selected=p.selectionKey;onProjectSelected(p);projectMenu=false})}
                    }
                }
                OutlinedTextField(draft,{draft=it;preferences.edit().putString(key,it).apply()},Modifier.fillMaxWidth().testTag("assistant-workspace-draft"),
                    placeholder={Text("交办新目标，或继续聊")},minLines=1,maxLines=5,shape=RoundedCornerShape(16.dp))
                AssistantAttachmentPicker(attachments,"main",sentId==null)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    if(sentId!=null) TextButton({sentId=null;sentText=null;draft="";attachments.newGoal();preferences.edit().putString(key,"").apply()}) {Text("另写新目标")}
                    else Text(if(snapshot.paused==true) "助理已暂停" else if(!connected) "电脑离线 · 草稿已保存" else "",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({
                        act {val action=AssistantActions.send(snapshot.runtimeId.orEmpty(),draft.ifBlank {"请查看附件。"},project).copy(commandId=UUID.randomUUID().toString(),composerBinding=binding,attachments=attachments.files.toList())
                            sentId=action.commandId;sentText=draft;attachments.sent(action.commandId!!,draft);action}
                    },enabled=canAct && snapshot.prepared && snapshot.paused!=true && (draft.isNotBlank() || attachments.files.isNotEmpty()) && sentId==null && "assistant/send" !in state.pendingActions,
                        modifier=Modifier.testTag("assistant-workspace-send")) {Text(if(sentId==null) "发送" else "待回执")}
                }
            }
        }
    }
}

@Composable private fun AssistantMainTaskCard(task:AssistantTask,state:AssistantUiState,canAct:Boolean,
    act:(()->AssistantAction)->Unit,navigate:(String)->Unit) {
    val snapshot=state.snapshot ?: return
    val children=snapshot.orderedTasks().filter {snapshot.taskRecord(it.id)?.parentId==task.id}
    val artifacts=snapshot.taskArtifacts(task.id)
    val pending=snapshot.pendingOperations().filter {it.taskId==task.id}
    AssistantCard(Modifier.fillMaxWidth().testTag("assistant-chat-task:${task.id}")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(snapshot.taskStateLabel(task),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            if(snapshot.taskRecord(task.id)?.unread==true) Text("新进展",style=MaterialTheme.typography.labelSmall)
        }
        Text(task.prompt,style=MaterialTheme.typography.titleMedium)
        Text(snapshot.taskStage(task),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(children.isNotEmpty()) Text("子任务 ${children.count {it.status==AssistantTaskStatus.Done}}/${children.size} 已完成",style=MaterialTheme.typography.bodyMedium)
        if(artifacts.isEmpty()) Text("成果尚未交付",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Text("成果 · ${artifacts.size}",style=MaterialTheme.typography.labelMedium)
            artifacts.take(2).forEach {artifact->TextButton({navigate("Artifact:${artifact.id}")}) {Text(artifact.name,maxLines=2,overflow=TextOverflow.Ellipsis)}}
        }
        pending.forEach {AssistantHumanQuestion(it,snapshot.runtimeId.orEmpty(),state,canAct,act)}
        TextButton({navigate("Task:${task.id}")},Modifier.testTag("assistant-chat-task-open:${task.id}")) {Text("查看任务")}
    }
}

@Composable private fun AssistantTasksPage(state:AssistantUiState,navigate:(String)->Unit) {
    val snapshot=state.snapshot ?: return
    var query by rememberSaveable {mutableStateOf("")}
    var onlyPending by rememberSaveable {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-tasks-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),placeholder={Text("查找任务")},singleLine=true)}
        item {FilterChip(onlyPending,{onlyPending=!onlyPending},label={Text("待处理")},modifier=Modifier.testTag("assistant-tasks-pending"))}
        val tasks=snapshot.orderedTasks().filter {t->(query.isBlank() || t.prompt.contains(query,true)) &&
            (!onlyPending || snapshot.taskNeedsAttention(t) || snapshot.taskRecord(t.id)?.state=="needs_verification")}
        if(tasks.isEmpty()) item {Text(if(onlyPending) "没有待处理任务" else "交办后，任务会保留在这里。")}
        items(tasks,key={it.id}) {task->AssistantCard(Modifier.testTag("assistant-task:${task.id}")) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(snapshot.taskStateLabel(task),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                if(snapshot.taskRecord(task.id)?.unread==true) Text("新进展",style=MaterialTheme.typography.labelMedium)
            }
            Text(task.prompt,style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=TextOverflow.Ellipsis)
            Text(snapshot.taskStage(task),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            TextButton({navigate("Task:${task.id}")},Modifier.testTag("assistant-task-open:${task.id}")) {Text("查看任务")}
        }}
    }
}

@Composable private fun AssistantResultsPage(state:AssistantUiState,navigate:(String)->Unit,open:(String,String)->Unit) {
    val snapshot=state.snapshot ?: return
    var query by rememberSaveable {mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().testTag("assistant-artifact-filter"),placeholder={Text("查找成果")},singleLine=true)}
        val artifacts=snapshot.artifacts.filter {query.isBlank() || it.name.contains(query,true)}
        if(artifacts.isEmpty()) item {Text("成果会按任务和版本保留，未完成任务的部分交付也在这里。")}
        items(artifacts,key={"${it.id}@${it.version}"}) {AssistantArtifactCard(it,snapshot,navigate,open)}
    }
}

@Composable private fun AssistantWorkDetail(id:String,state:AssistantUiState,canAct:Boolean,act:(()->AssistantAction)->Unit,
    navigate:(String)->Unit,open:(String,String)->Unit) {
    val snapshot=state.snapshot ?: return
    val task=snapshot.tasks.firstOrNull {it.id==id} ?: return
    val record=snapshot.taskRecord(id)
    val runtime=snapshot.runtimeId.orEmpty()
    var section by rememberSaveable(id) {mutableStateOf("Overview")}
    var stop by rememberSaveable(id) {mutableStateOf(false)}
    LaunchedEffect(id,record?.activitySequence) {if(canAct && record?.unread==true) act {assistantSeen(runtime,id,record.activitySequence)}}
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-task-detail-page:$id"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Text(task.prompt,style=MaterialTheme.typography.headlineSmall);Text(snapshot.taskStateLabel(task),Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.primary)}
        items(snapshot.taskOperations(task).filter {it in snapshot.pendingOperations()},key={"pending:${it.id}"}) {AssistantHumanQuestion(it,runtime,state,canAct,act)}
        if(record?.state=="needs_verification") item {AssistantCard {
            Text("保留原执行，等待核对",style=MaterialTheme.typography.titleMedium)
            Text("此尝试不会被重放。确认结果后再继续这项任务。")
            snapshot.taskOperations(task).mapNotNull {it.threadId}.distinct().forEach {thread->TextButton({open(runtime,thread)}) {Text("查看原执行")}}
        }}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Overview" to "概览","Process" to "过程","Artifacts" to "成果").forEach {(key,label)->FilterChip(section==key,{section=key},label={Text(label)},modifier=Modifier.testTag("assistant-task-section:$key"))}
        }}
        if(section=="Overview") {
            task.detail?.takeIf(String::isNotBlank)?.let {item {SelectionContainer {Text(it)}}}
            task.assignment?.completionCriteria?.takeIf(String::isNotBlank)?.let {item {Text("完成标准",style=MaterialTheme.typography.titleSmall);Text(it)}}
            record?.parentId?.let {parent->item {TextButton({navigate("Task:$parent")}) {Text("返回父任务")}}}
            record?.continuesId?.let {parent->item {TextButton({navigate("Task:$parent")}) {Text("查看上一轮成果")}}}
            items(snapshot.orderedTasks().filter {snapshot.taskRecord(it.id)?.parentId==id},key={"child:${it.id}"}) {child->TextButton({navigate("Task:${child.id}")}) {Text("子任务 · ${child.prompt}")}}
        }
        if(section=="Process") {
            items(snapshot.taskOperations(task).flatMap {it.heldInputs},key={"held:${it.operationId}:${it.callId}"}) {AssistantEndedQuestion(it)}
            items(snapshot.events.filter {it.taskId==id || snapshot.taskOperations(task).any {op->op.id==it.operationId}}.sortedBy {it.sequence},key={it.id}) {event->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(event.atMs?.let {assistantTime(it,snapshot.settings?.timezone ?: "Asia/Shanghai")} ?: "时间未记录",style=MaterialTheme.typography.labelSmall);Text(event.detail)}
            }
            items(snapshot.taskOperations(task),key={"attempt:${it.id}"}) {op->AssistantCard {
                Text("执行尝试 · ${snapshot.taskOperations(task).indexOf(op)+1}",style=MaterialTheme.typography.titleSmall)
                Text(when(op.phase) {AssistantOperationPhase.Unknown->"待核对";AssistantOperationPhase.Failed->"未完成";AssistantOperationPhase.Completed->"已完成";AssistantOperationPhase.Paused->"已停止";AssistantOperationPhase.Waiting->"等待回答";else->"执行中"})
                op.threadId?.let {thread->TextButton({open(runtime,thread)}) {Text("完整执行过程")}}
            }}
        }
        if(section=="Artifacts") {
            val artifacts=snapshot.taskArtifacts(id)
            if(artifacts.isEmpty()) item {Text("尚未交付成果")}
            items(artifacts,key={it.id}) {AssistantArtifactCard(it,snapshot,navigate,open)}
        }
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(task.status in setOf(AssistantTaskStatus.Running,AssistantTaskStatus.Queued,AssistantTaskStatus.WaitingUser) && record?.state!="stop_requested")
                OutlinedButton({stop=true},enabled=canAct,modifier=Modifier.testTag("assistant-task-stop:$id")) {Text("停止任务")}
            if(task.status==AssistantTaskStatus.Paused && !task.reconciliationRequired)
                Button({act {AssistantActions.task(runtime,id,true)}},enabled=canAct && !state.busy("assistant/task/resume")) {Text("继续任务")}
            if(task.status==AssistantTaskStatus.Failed && !task.reconciliationRequired)
                Button({act {assistantRetry(runtime,id)}},enabled=canAct && !state.busy("assistant/v2/task/retry"),modifier=Modifier.testTag("assistant-task-retry:$id")) {Text("重新尝试")}
        }}
        if(record?.state!="needs_verification") item {AssistantTaskFollowup(task,state,canAct,act)}
        item {
            val disabled=id in snapshot.memoryDisabledTasks
            TextButton({act {AssistantActions.taskMemory(runtime,id,disabled)}},enabled=canAct) {Text(if(disabled) "后续执行使用记忆" else "后续执行不使用记忆")}
        }
        item {TextButton({navigate("ScheduleFrom:$id")}) {Text("设为计划")}}
    }
    if(stop) AlertDialog({stop=false},title={Text("停止这项任务？")},text={Text("会请求停止当前尝试，已有成果和记录仍保留。收到执行器确认后显示已停止。")},
        confirmButton={TextButton({act {AssistantActions.task(runtime,id,false)};stop=false}) {Text("确认停止")}},dismissButton={TextButton({stop=false}) {Text("继续执行")}})
}

@Composable private fun AssistantMemoryPage(state:AssistantUiState,canAct:Boolean,act:(()->AssistantAction)->Unit) {
    var history by rememberSaveable {mutableStateOf(false)}
    Column {
        Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(!history,{history=false},label={Text("当前记忆")})
            FilterChip(history,{history=true},label={Text("修订历史")},modifier=Modifier.testTag("assistant-memory-history"))
        }
        if(!history) AssistantMemories(state,canAct,act) else LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            val revisions=state.snapshot?.domain?.memoryRevisions.orEmpty()
            if(revisions.isEmpty()) item {Text("尚无修订记录")}
            items(revisions.asReversed(),key={"${it.memoryId}:${it.revision}"}) {revision->AssistantCard {
                Text("修订 ${revision.revision} · ${if(revision.state=="deleted") "已删除" else "已保存"}",style=MaterialTheme.typography.labelMedium)
                revision.text?.let {SelectionContainer {Text(it)}}
                Text("来源 · ${revision.source ?: "旧记录未提供"}",style=MaterialTheme.typography.bodySmall)
            }}
        }
    }
}
