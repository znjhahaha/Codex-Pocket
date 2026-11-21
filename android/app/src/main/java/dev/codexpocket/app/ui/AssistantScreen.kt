package dev.codexpocket.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun AssistantScreen(state: AssistantUiState, models: List<NativeModel>, runtimes: List<AssistantRuntimeChoice>, workspaces: List<AssistantWorkspaceChoice>,
    onAction: (AssistantAction) -> Unit, onOpenThread: (String, String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, nowMs: Long = System.currentTimeMillis(), connected: Boolean = true,
    onProjectSelected: (AssistantWorkspaceChoice?) -> Unit = {}) {
    AssistantWorkspace(state,models,runtimes,workspaces,onAction,onOpenThread,onBack,modifier,nowMs,connected,onProjectSelected)
}

@Composable internal fun assistantCompactChrome(): Boolean =
    LocalConfiguration.current.screenWidthDp<360 || LocalDensity.current.fontScale>1.3f

private data class AssistantDestination(val id:String,val label:String,val icon:ImageVector)
private val assistantDestinations get() = listOf(
    AssistantDestination("Tasks","全部任务",AssistantIcons.Tasks), AssistantDestination("Memory","记忆",AssistantIcons.Memory),
    AssistantDestination("Schedules","定时事项",AssistantIcons.Schedule), AssistantDestination("Settings","助理设置",AssistantIcons.Settings))

internal fun AssistantUiState.busy(method: String, id: String? = null) =
    (method in unconfirmedActions && !(snapshot?.protocolVersion==2 && method=="assistant/respond")) || method in pendingActions || (id!=null && "$method:$id" in pendingActions)
private fun assistantStatus(state: AssistantUiState): String = when {
    state.loading -> "正在读取状态"; state.error != null -> "暂时无法确认状态"; state.snapshot?.enabled==null -> "状态尚未读取"
    state.snapshot.dispatchInFlight -> "正在处理"
    state.snapshot.enabled==false -> "尚未启用"; state.busy("assistant/prepare") || state.snapshot.ready==false -> "正在准备助理"; state.snapshot.paused==true -> "助理已暂停"
    state.snapshot.operations.any {it.phase==AssistantOperationPhase.Waiting && !it.suspended} -> "等待你确认"
    state.snapshot.operations.any {it.phase==AssistantOperationPhase.Unknown && !it.suspended} -> "正在核对运行状态"
    state.snapshot.operations.any {it.phase==AssistantOperationPhase.Running && !it.suspended} -> "正在处理"
    state.snapshot.tasks.any {it.status==AssistantTaskStatus.Queued} -> "有任务在排队"; else -> "自动安排任务"
}
internal fun taskLabel(status: AssistantTaskStatus) = when(status) {
    AssistantTaskStatus.Queued -> "排队中"; AssistantTaskStatus.Running -> "运行中"; AssistantTaskStatus.WaitingUser -> "等你确认"; AssistantTaskStatus.Paused -> "已暂停"
    AssistantTaskStatus.Done -> "已完成"; AssistantTaskStatus.Failed -> "执行失败"; AssistantTaskStatus.Unknown -> "状态待确认"
}
@Composable internal fun AssistantCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable internal fun AssistantBadge(text: String, failure: Boolean = false) {
    val c=MaterialTheme.colorScheme
    Text(text,Modifier.background(if(failure) c.errorContainer else c.surfaceContainerHigh,RoundedCornerShape(10.dp)).padding(horizontal=10.dp,vertical=5.dp),
        color=if(failure) c.onErrorContainer else c.onSurfaceVariant,style=MaterialTheme.typography.labelMedium)
}
@Composable internal fun AssistantChoice(label: String, selected: String, tag: String, choices: List<Pair<String,String>>, enabled: Boolean = true, onChoose: (String)->Unit) {
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

@Composable internal fun AssistantTaskCard(task: AssistantTask, state: AssistantUiState, canAct: Boolean, act: (() -> AssistantAction)->Unit, open:(String,String)->Unit,
    workspaces: List<AssistantWorkspaceChoice> = emptyList()) {
    var expanded by rememberSaveable(task.id) {mutableStateOf(false)}
    val holder = rememberSaveableStateHolder()
    AssistantCard {
        PocketGlassSurface(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().testTag("assistant-task:${task.id}").semantics {contentDescription=if(expanded) "收起任务详情" else "展开任务详情"},shape=RoundedCornerShape(16.dp),dense=true) {
        Column(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Icon(AssistantIcons.Tasks,null,Modifier.size(18.dp),tint=assistantPalette().accent)
                Text(taskLabel(task.status),Modifier.weight(1f).padding(start=8.dp),style=MaterialTheme.typography.labelMedium,color=if(task.status==AssistantTaskStatus.Failed) MaterialTheme.colorScheme.error else assistantPalette().accent)
                Text(if(expanded) "收起" else "详情",style=MaterialTheme.typography.labelSmall,color=assistantPalette().muted)
            }
            Text(task.prompt,style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        }
        AssistantExpansion(expanded) {holder.SaveableStateProvider(task.id) {
            AssistantTaskDetails(task,state,canAct,act,open,workspaces)
        }}
    }
}
@Composable internal fun AssistantHumanQuestion(op: AssistantOperation, runtime:String, state:AssistantUiState, canAct:Boolean, act:(()->AssistantAction)->Unit) {
    val q=op.question ?: return
    var answer by rememberSaveable(op.id,q.requestId) {mutableStateOf("")}
    if(op.phase in setOf(AssistantOperationPhase.Completed,AssistantOperationPhase.Failed)) {
        val arguments = runCatching {when(val value=pocketJson.parseToJsonElement(q.text)) {
            is JsonObject -> value; is JsonArray -> buildJsonObject {put("questions",value)}; else -> null
        }}.getOrNull()
        if(q.kind == AssistantQuestionKind.Input && arguments != null) {
            val item = TimelineItem.from(op.id, buildJsonObject {
                put("id",q.requestId);put("type","dynamicToolCall");put("tool","request_user_input");put("status","completed");put("arguments",arguments)
                op.result?.let {result -> put("contentItems",buildJsonArray {add(buildJsonObject {put("type","text");put("text",result)})})}
            })
            NativeQuestionHistory.from(item)?.let {HistoricalQuestionRow(it,"assistant:${op.id}")}
        } else Text(if(op.phase == AssistantOperationPhase.Completed) "已确认" else "确认已结束", style=MaterialTheme.typography.labelMedium)
        return
    }
    val allowed=canAct && state.snapshot?.fresh!=false && state.readError==null && op.phase==AssistantOperationPhase.Waiting && !op.suspended && !state.busy("assistant/respond",op.id)
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

@Composable internal fun AssistantMemories(state:AssistantUiState,canAct:Boolean,act:(()->AssistantAction)->Unit) {
    val runtime=state.snapshot?.runtimeId.orEmpty()
    var editing by rememberSaveable {mutableStateOf(false)}
    var editId by rememberSaveable {mutableStateOf<String?>(null)}
    var text by rememberSaveable {mutableStateOf("")}
    var deleteId by rememberSaveable {mutableStateOf<String?>(null)}
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-memory-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("让助理记住你的习惯与事实",style=MaterialTheme.typography.titleMedium);Text("自动保存来自你本人陈述的长期信息，可随时撤销。记忆不代表执行授权。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {PocketButton("新增记忆",{editId=null;text="";editing=true},Modifier.fillMaxWidth().testTag("assistant-memory-add"),enabled=canAct && !state.busy("assistant/memory/add"))}
        if(state.snapshot?.memories.isNullOrEmpty()) item {Text(if(state.snapshot==null) "记忆尚未读取" else "暂无已保存的记忆",style=MaterialTheme.typography.bodyMedium)}
        items(state.snapshot?.memories.orEmpty().distinctBy {it.id},key={it.id}) {m -> AssistantCard {
            Text(when(m.scope) {"facts"->"长期事实";"project"->"项目知识";else->"个人偏好"} + if(m.automatic) " · 自动保存" else " · 手动保存",style=MaterialTheme.typography.labelMedium)
            SelectionContainer {Text(m.text,style=MaterialTheme.typography.bodyLarge)}
            Text("来源 · ${m.source ?: "历史记录（来源未记录）"}",style=MaterialTheme.typography.bodySmall)
            m.updatedAtMs?.let {Text("最近保存 · ${assistantTime(it,state.snapshot?.settings?.timezone ?: "Asia/Shanghai")} · ${m.revisions.size.coerceAtLeast(1)} 次记录",style=MaterialTheme.typography.labelSmall)}
            if(m.automatic) TextButton({act {AssistantActions.memoryUndo(runtime,m.id)}},Modifier.testTag("assistant-memory-undo:${m.id}"),enabled=canAct && !state.busy("assistant/memory/undo")) {Text("撤销自动保存")}

            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PocketButton("修改",{editId=m.id;text=m.text;editing=true},Modifier.weight(1f).testTag("assistant-memory-edit:${m.id}"),enabled=canAct && !state.busy("assistant/memory/update",m.id),secondary=true)
                PocketButton("删除",{deleteId=m.id},Modifier.weight(1f).testTag("assistant-memory-delete:${m.id}"),enabled=canAct && !state.busy("assistant/memory/delete",m.id),secondary=true)
            }
        }}
    }
    if(editing) PocketDialog({editing=false},title={Text(if(editId==null) "新增记忆" else "修改记忆")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {PocketTextField(text,{text=it},Modifier.heightIn(max=300.dp).testTag("assistant-memory-text"),placeholder="写下希望助理记住的内容",minLines=3,maxLines=9);Text("最多16KiB；保存后以电脑回执为准。",style=MaterialTheme.typography.labelSmall)}
    },confirmButton={PocketButton("保存",{val id=editId;act {if(id==null) AssistantActions.memoryAdd(runtime,text) else AssistantActions.memoryEdit(runtime,id,text)};editing=false},Modifier.testTag("assistant-memory-save"),enabled=canAct && text.isNotBlank() && text.toByteArray().size<=16384 && !state.busy(if(editId==null) "assistant/memory/add" else "assistant/memory/update",editId))},dismissButton={PocketTextButton({editing=false},Modifier.testTag("assistant-memory-cancel")) {Text("取消")}})
    deleteId?.let {id -> AssistantDeleteDialog("删除这条记忆？",{deleteId=null},canAct && !state.busy("assistant/memory/delete",id)) {act {AssistantActions.memoryDelete(runtime,id)};deleteId=null}}
}
@Composable private fun AssistantDeleteDialog(title:String,dismiss:()->Unit,enabled:Boolean,confirm:()->Unit) {
    PocketDialog(dismiss,title={Text(title)},text={Text("确认后交给电脑处理，收到回执后列表才会更新。")},confirmButton={PocketButton("确认删除",confirm,Modifier.testTag("assistant-confirm-delete"),enabled=enabled,destructive=true)},dismissButton={PocketTextButton(dismiss) {Text("取消")}})
}

@Composable internal fun AssistantSettingsPage(state:AssistantUiState,models:List<NativeModel>,runtimes:List<AssistantRuntimeChoice>,canAct:Boolean,act:(()->AssistantAction)->Unit) {
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
    var initializedSettings by rememberSaveable {mutableStateOf(s!=null)}
    LaunchedEffect(s) {
        if(s!=null && !initializedSettings) {
            // Preparation can supply defaults after a model was explicitly chosen. Preserve that choice.
            if(personality.isEmpty()) personality=s.personality
            if(tone.isEmpty()) tone=s.tone
            initializedSettings=true
        }
        if(s!=null && (!dirty || s==draft)) {personality=s.personality;tone=s.tone;model=s.model;zone=s.timezone;completed=s.notifications.completed;failed=s.notifications.failed;waiting=s.notifications.waitingUser;dirty=false}
    }
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
@Composable private fun AssistantSettingToggle(text:String,checked:Boolean,tag:String,enabled:Boolean=true,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge);PocketToggle(checked,onChange,Modifier.testTag(tag),enabled=enabled)}
}

@Composable internal fun AssistantSchedules(state:AssistantUiState,workspaces:List<AssistantWorkspaceChoice>,canAct:Boolean,now:Long,onProjectSelected:(AssistantWorkspaceChoice?)->Unit,navigate:(String)->Unit,act:(()->AssistantAction)->Unit) {
    val runtime=state.snapshot?.runtimeId.orEmpty()
    var creating by rememberSaveable {mutableStateOf(false)}
    var editingId by rememberSaveable {mutableStateOf<String?>(null)}
    var expandedHistory by rememberSaveable {mutableStateOf<String?>(null)}
    var deleteId by rememberSaveable {mutableStateOf<String?>(null)}
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-schedules-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("把事项放在合适的时间",style=MaterialTheme.typography.titleMedium);Text("按所选时区执行；默认跳过错过的时间，可在每项中设置补执行。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {PocketButton("创建定时事项",{editingId=null;creating=true},Modifier.fillMaxWidth().testTag("assistant-schedule-add"),enabled=canAct && !state.busy("assistant/schedule/create"))}
        if(state.snapshot?.schedules.isNullOrEmpty()) item {Text(if(state.snapshot==null) "定时事项尚未读取" else "暂无定时事项",style=MaterialTheme.typography.bodyMedium)}
        items(state.snapshot?.schedules.orEmpty().distinctBy {it.id},key={it.id}) {s -> AssistantCard {
            Text(s.prompt,style=MaterialTheme.typography.titleMedium)
            Text(assistantScheduleLabel(s),style=MaterialTheme.typography.bodyMedium)
            Text("创建时区 · ${s.timezone.ifBlank {"未提供"}}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(s.paused) AssistantBadge("已暂停")
            if(s.missed) AssistantBadge("已错过 · 已记录")
            Text(if(s.missedPolicy=="run_once") "错过后补执行一次" else "错过后跳过",style=MaterialTheme.typography.bodySmall)
            s.error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}
            Text(s.nextMs?.let {"下次 · ${assistantTime(it,s.timezone)}"} ?: "暂无下次执行时间",style=MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton({editingId=s.id;creating=true},enabled=canAct && !state.busy("assistant/schedule/update"),modifier=Modifier.testTag("assistant-schedule-edit:${s.id}")) {Text("编辑")}
                val action=if(s.paused) "resume" else "pause"
                TextButton({act {AssistantActions.scheduleControl(runtime,s.id,action)}},enabled=canAct && !state.busy("assistant/schedule/$action"),modifier=Modifier.testTag("assistant-schedule-toggle:${s.id}")) {Text(if(s.paused) "恢复" else "暂停")}
                TextButton({act {AssistantActions.scheduleControl(runtime,s.id,"run")}},enabled=canAct && state.snapshot?.paused!=true && !state.busy("assistant/schedule/run"),modifier=Modifier.testTag("assistant-schedule-run:${s.id}")) {Text("运行一次")}
            }
            val history=state.snapshot?.scheduleRuns.orEmpty().filter {it.scheduleId==s.id}
            TextButton({expandedHistory=if(expandedHistory==s.id) null else s.id},Modifier.testTag("assistant-schedule-history:${s.id}")) {Text("执行历史 · ${history.size}")}
            if(expandedHistory==s.id) {
                if(history.isEmpty()) Text("尚无执行记录",style=MaterialTheme.typography.bodySmall)
                history.forEach {run ->
                    Text("${run.dueMs?.let {assistantTime(it,s.timezone)} ?: "时间待确认"} · ${run.statusLabel}${if(run.manual) " · 手动" else ""}",style=MaterialTheme.typography.bodySmall)
                    run.taskId?.let {task -> TextButton({navigate("Task:$task")},Modifier.testTag("assistant-schedule-task:${run.id}")) {Text("查看本次任务与成果")}}
                }
            }
            PocketButton("删除事项",{deleteId=s.id},Modifier.fillMaxWidth().testTag("assistant-schedule-delete:${s.id}"),enabled=canAct && !state.busy("assistant/schedule/delete",s.id),secondary=true)
        }}
    }
    if(creating) AssistantScheduleForm(runtime,state,workspaces,now,canAct,{creating=false},onProjectSelected,act,editing=state.snapshot?.schedules?.firstOrNull {it.id==editingId})
    deleteId?.let {id -> AssistantDeleteDialog("删除这个定时事项？",{deleteId=null},canAct && !state.busy("assistant/schedule/delete",id)) {act {AssistantActions.scheduleDelete(runtime,id)};deleteId=null}}
}
@Composable internal fun AssistantScheduleForm(runtime:String,state:AssistantUiState,workspaces:List<AssistantWorkspaceChoice>,now:Long,canAct:Boolean,dismiss:()->Unit,onProjectSelected:(AssistantWorkspaceChoice?)->Unit,act:(()->AssistantAction)->Unit,editing:AssistantSchedule? = null,initialPrompt:String = "") {
    val projectCallback=rememberUpdatedState(onProjectSelected)
    DisposableEffect(Unit) {onDispose {projectCallback.value(null)}}
    var prompt by rememberSaveable(editing?.id) {mutableStateOf(editing?.prompt ?: initialPrompt)}
    var kind by rememberSaveable(editing?.id) {mutableStateOf(editing?.kind?.keys?.singleOrNull() ?: "Once")}
    var zone by rememberSaveable(editing?.id) {mutableStateOf(editing?.timezone ?: state.snapshot?.settings?.timezone ?: "Asia/Shanghai")}
    var missedPolicy by rememberSaveable(editing?.id) {mutableStateOf(editing?.missedPolicy ?: "skip")}
    val original=editing?.kind?.get(kind) as? JsonObject

    val onceAt=original?.number("at_ms")?.takeIf {kind=="Once" && it>0}
    var date by rememberSaveable(editing?.id) {mutableStateOf(Instant.ofEpochMilli(onceAt ?: now).atZone(runCatching {ZoneId.of(zone)}.getOrDefault(ZoneId.of("Asia/Shanghai"))).toLocalDate().let {if(onceAt==null) it.plusDays(1) else it}.toString())}
    var time by rememberSaveable(editing?.id) {mutableStateOf(if(kind in setOf("Daily","Weekly") && original!=null) "%02d:%02d".format(original.number("hour"),original.number("minute")) else onceAt?.let {DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(it).atZone(ZoneId.of(zone)))} ?: "09:00")}
    var weekday by rememberSaveable(editing?.id) {mutableIntStateOf(original?.number("weekday")?.toInt() ?: 0)}
    var minutes by rememberSaveable(editing?.id) {mutableStateOf(if(kind=="Interval" && original!=null) (original.number("every_ms")/60000).toString() else "60")}
    var projectId by rememberSaveable {mutableStateOf<String?>(null)}
    var writes by rememberSaveable(editing?.id) {mutableStateOf(editing?.writes ?: false)}
    var error by remember {mutableStateOf<String?>(null)}
    val projects=workspaces.filter {!it.isSubagent && it.runtimeId.isNotBlank() && it.threadId.isNotBlank()}.distinctBy {it.selectionKey}
    val project=projects.firstOrNull {it.selectionKey==projectId}
    LaunchedEffect(project?.canWrite) {if(editing==null && project?.canWrite!=true) writes=false}
    PocketDialog(dismiss,title={Text(if(editing==null) "创建定时事项" else "编辑定时事项")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        PocketTextField(prompt,{prompt=it},Modifier.testTag("assistant-schedule-prompt"),label="事项",minLines=2,maxLines=5)
        if(editing==null) AssistantChoice("执行范围",project?.label ?: "通用事项 · 助理工作区","assistant-schedule-workspace",listOf("general" to "通用事项 · 助理工作区")+projects.map {it.selectionKey to it.label}) {projectId=if(it=="general") null else it;writes=false;onProjectSelected(projects.firstOrNull {p->p.selectionKey==it})}
        else Text("保留原事项的执行范围与授权。",style=MaterialTheme.typography.bodySmall)
        project?.let {selected ->
            Text(selected.permissionStatus,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("assistant-project-permission-status"))
            PocketTextButton({onProjectSelected(selected)},Modifier.testTag("assistant-project-recheck"),enabled=canAct) {Text("重新核验项目权限")}
        }
        Text("通用事项在助理工作区执行；关联项目需使用电脑核验的权限。",style=MaterialTheme.typography.bodySmall)
        AssistantChoice("重复方式",mapOf("Once" to "一次","Daily" to "每天","Weekly" to "每周","Interval" to "间隔")[kind].orEmpty(),"assistant-schedule-kind",listOf("Once" to "一次","Daily" to "每天","Weekly" to "每周","Interval" to "间隔")) {kind=it}
        if(kind=="Once") PocketTextField(date,{date=it},Modifier.testTag("assistant-schedule-date"),label="日期 · YYYY-MM-DD",singleLine=true)
        if(kind in setOf("Once","Daily","Weekly")) PocketTextField(time,{time=it},Modifier.testTag("assistant-schedule-time"),label="时间 · HH:mm",singleLine=true)
        if(kind=="Weekly") AssistantChoice("星期",assistantWeekdays[weekday],"assistant-schedule-weekday",assistantWeekdays.mapIndexed {i,s -> i.toString() to s}) {weekday=it.toInt()}
        if(kind=="Interval") PocketTextField(minutes,{minutes=it},Modifier.testTag("assistant-schedule-interval"),label="每隔多少分钟",singleLine=true)
        PocketTextField(zone,{zone=it},Modifier.testTag("assistant-schedule-timezone"),label="创建时区",singleLine=true)
        if(editing==null) {
            AssistantSettingToggle("允许使用已有写入授权",writes && project?.canWrite==true,"assistant-schedule-writes",enabled=canAct && project?.canWrite==true) {writes=it && project?.canWrite==true}
            if(project!=null && !project.canWrite) Text("所选项目将以只读方式处理。",style=MaterialTheme.typography.bodySmall)
        } else Text(if(editing.writes) "保留原有写入授权；新增权限仍需审批。" else "保留原有只读范围。",style=MaterialTheme.typography.bodySmall)
        if(editing!=null) AssistantChoice("错过执行时",if(missedPolicy=="run_once") "补执行一次" else "跳过并记录","assistant-schedule-missed",listOf("skip" to "跳过并记录","run_once" to "补执行一次")) {missedPolicy=it}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("assistant-schedule-error"))}
    }},confirmButton={PocketButton(if(editing==null) "创建" else "保存",{
        val result=runCatching {AssistantActions.schedule(runtime,project,AssistantScheduleDraft(prompt,kind,date,time,weekday,minutes,zone,writes),now,editing,missedPolicy)}
        result.onSuccess {action -> act {action};dismiss()}.onFailure {error=it.message ?: "请检查日期和时区"}
    },Modifier.testTag("assistant-schedule-save"),enabled=canAct && prompt.isNotBlank() && !state.busy(if(editing==null) "assistant/schedule/create" else "assistant/schedule/update"))},dismissButton={PocketTextButton(dismiss,Modifier.testTag("assistant-schedule-cancel")) {Text("取消")}})
}
private val assistantWeekdays=listOf("周一","周二","周三","周四","周五","周六","周日")
internal fun assistantTime(ms:Long,zone:String):String = runCatching {DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.of(zone)))}.getOrDefault("时间待确认")
private fun assistantScheduleLabel(s:AssistantSchedule):String = when(val type=s.kind.keys.singleOrNull()) {
    "Once" -> "一次 · ${s.kind.obj(type).number("at_ms").let {assistantTime(it,s.timezone)}}"
    "Daily" -> s.kind.obj(type).let {"每天 · %02d:%02d".format(it.number("hour"),it.number("minute"))}
    "Weekly" -> s.kind.obj(type).let {"${assistantWeekdays.getOrNull(it.number("weekday").toInt()) ?: "星期待确认"} · %02d:%02d".format(it.number("hour"),it.number("minute"))}
    "Interval" -> s.kind.obj(type).number("every_ms").let {if(it>=60000) "每 ${it/60000} 分钟" else "每 ${it/1000} 秒"};else -> "定时类型待确认"
}
