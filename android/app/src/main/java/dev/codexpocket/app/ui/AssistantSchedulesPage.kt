package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AssistantSchedules(state:AssistantUiState,workspaces:List<AssistantWorkspaceChoice>,canAct:Boolean,now:Long,onProjectSelected:(AssistantWorkspaceChoice?)->Unit,navigate:(String)->Unit,act:(()->AssistantAction)->Unit) {
    val c=Pocket.colors
    val runtime=state.snapshot?.runtimeId.orEmpty()
    var creating by rememberSaveable {mutableStateOf(false)}
    var editingId by rememberSaveable {mutableStateOf<String?>(null)}
    var expandedHistory by rememberSaveable {mutableStateOf<String?>(null)}
    var deleteId by rememberSaveable {mutableStateOf<String?>(null)}
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-schedules-list"),contentPadding=AssistantPagePadding,verticalArrangement=Arrangement.spacedBy(AssistantPageSpacing)) {
        item {Column(Modifier.padding(horizontal=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            PText("把事项放在合适的时间",style=Pocket.type.title)
            PText("按所选时区执行；默认跳过错过的时间，可在每项中设置补执行。",style=Pocket.type.bodySmall,color=c.inkMuted)
        }}
        item {PButton("创建定时事项",{editingId=null;creating=true},Modifier.fillMaxWidth().testTag("assistant-schedule-add"),enabled=canAct && !state.busy("assistant/schedule/create"),leading=PocketIcons.Add)}
        if(state.snapshot?.schedules.isNullOrEmpty()) item {AssistantPageNote(if(state.snapshot==null) "定时事项尚未读取" else "暂无定时事项")}
        items(state.snapshot?.schedules.orEmpty().distinctBy {it.id},key={it.id}) {s -> AssistantCard(Modifier.testTag("assistant-schedule:${s.id}")) {
            PText(s.prompt,style=Pocket.type.titleSmall)
            PText(assistantScheduleLabel(s),style=Pocket.type.body)
            PText(s.nextMs?.let {"下次 · ${assistantTime(it,s.timezone)}"} ?: "暂无下次执行时间",style=Pocket.type.bodySmall,color=c.inkMuted)
            PText("创建时区 · ${s.timezone.ifBlank {"未提供"}}",style=Pocket.type.bodySmall,color=c.inkMuted)
            if(s.paused || s.missed) FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(s.paused) AssistantBadge("已暂停")
                if(s.missed) AssistantBadge("已错过 · 已记录",tone=PTone.Warning)
            }
            PText(if(s.missedPolicy=="run_once") "错过后补执行一次" else "错过后跳过",style=Pocket.type.bodySmall,color=c.inkMuted)
            s.error?.let {PText(it,style=Pocket.type.bodySmall,color=c.danger)}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                AssistantCardAction("编辑",{editingId=s.id;creating=true},Modifier.testTag("assistant-schedule-edit:${s.id}"),enabled=canAct && !state.busy("assistant/schedule/update"))
                val action=if(s.paused) "resume" else "pause"
                AssistantCardAction(if(s.paused) "恢复" else "暂停",{act {AssistantActions.scheduleControl(runtime,s.id,action)}},Modifier.testTag("assistant-schedule-toggle:${s.id}"),
                    enabled=canAct && !state.busy("assistant/schedule/$action"))
                AssistantCardAction("运行一次",{act {AssistantActions.scheduleControl(runtime,s.id,"run")}},Modifier.testTag("assistant-schedule-run:${s.id}"),
                    enabled=canAct && state.snapshot?.paused!=true && !state.busy("assistant/schedule/run"))
                val history=state.snapshot?.scheduleRuns.orEmpty().filter {it.scheduleId==s.id}
                AssistantCardAction("执行历史 · ${history.size}",{expandedHistory=if(expandedHistory==s.id) null else s.id},Modifier.testTag("assistant-schedule-history:${s.id}"))
            }
            if(expandedHistory==s.id) {
                val history=state.snapshot?.scheduleRuns.orEmpty().filter {it.scheduleId==s.id}
                if(history.isEmpty()) PText("尚无执行记录",style=Pocket.type.bodySmall,color=c.inkMuted)
                history.forEach {run ->
                    PText("${run.dueMs?.let {assistantTime(it,s.timezone)} ?: "时间待确认"} · ${run.statusLabel}${if(run.manual) " · 手动" else ""}",style=Pocket.type.bodySmall)
                    run.taskId?.let {task -> AssistantCardAction("查看本次任务与成果",{navigate("Task:$task")},Modifier.testTag("assistant-schedule-task:${run.id}"))}
                }
            }
            PButton("删除事项",{deleteId=s.id},Modifier.fillMaxWidth().testTag("assistant-schedule-delete:${s.id}"),PButtonKind.Secondary,enabled=canAct && !state.busy("assistant/schedule/delete",s.id))
        }}
    }
    if(creating) AssistantScheduleForm(runtime,state,workspaces,now,canAct,{creating=false},onProjectSelected,act,editing=state.snapshot?.schedules?.firstOrNull {it.id==editingId})
    deleteId?.let {id -> AssistantDeleteDialog("删除这个定时事项？",{deleteId=null},canAct && !state.busy("assistant/schedule/delete",id)) {act {AssistantActions.scheduleDelete(runtime,id)};deleteId=null}}
}

private val assistantWeekdays=listOf("周一","周二","周三","周四","周五","周六","周日")
internal fun assistantTime(ms:Long,zone:String):String = runCatching {DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.of(zone)))}.getOrDefault("时间待确认")
private fun assistantScheduleLabel(s:AssistantSchedule):String = when(val type=s.kind.keys.singleOrNull()) {
    "Once" -> "一次 · ${s.kind.obj(type).number("at_ms").let {assistantTime(it,s.timezone)}}"
    "Daily" -> s.kind.obj(type).let {"每天 · %02d:%02d".format(it.number("hour"),it.number("minute"))}
    "Weekly" -> s.kind.obj(type).let {"${assistantWeekdays.getOrNull(it.number("weekday").toInt()) ?: "星期待确认"} · %02d:%02d".format(it.number("hour"),it.number("minute"))}
    "Interval" -> s.kind.obj(type).number("every_ms").let {if(it>=60000) "每 ${it/60000} 分钟" else "每 ${it/1000} 秒"};else -> "定时类型待确认"
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
    AssistantDialog(if(editing==null) "创建定时事项" else "编辑定时事项",dismiss,
        confirm={PButton(if(editing==null) "创建" else "保存",{
            val result=runCatching {AssistantActions.schedule(runtime,project,AssistantScheduleDraft(prompt,kind,date,time,weekday,minutes,zone,writes),now,editing,missedPolicy)}
            result.onSuccess {action -> act {action};dismiss()}.onFailure {error=it.message ?: "请检查日期和时区"}
        },Modifier.testTag("assistant-schedule-save"),enabled=canAct && prompt.isNotBlank() && !state.busy(if(editing==null) "assistant/schedule/create" else "assistant/schedule/update"),haptic=PocketHaptic.Confirm)},
        dismiss={PButton("取消",dismiss,Modifier.testTag("assistant-schedule-cancel"),PButtonKind.Secondary,haptic=null)}) {
        val c=Pocket.colors
        PTextField(prompt,{prompt=it},Modifier.testTag("assistant-schedule-prompt"),label="事项",minLines=2,maxLines=5)
        if(editing==null) AssistantChoice("执行范围",project?.label ?: "通用事项 · 助理工作区","assistant-schedule-workspace",listOf("general" to "通用事项 · 助理工作区")+projects.map {it.selectionKey to it.label}) {projectId=if(it=="general") null else it;writes=false;onProjectSelected(projects.firstOrNull {p->p.selectionKey==it})}
        else PText("保留原事项的执行范围与授权。",style=Pocket.type.bodySmall,color=c.inkMuted)
        project?.let {selected ->
            Column {
                PText(selected.permissionStatus,Modifier.padding(horizontal=4.dp).testTag("assistant-project-permission-status"),style=Pocket.type.bodySmall,color=c.inkMuted)
                PTextAction("重新核验项目权限",{onProjectSelected(selected)},Modifier.testTag("assistant-project-recheck"),enabled=canAct)
            }
        }
        PText("通用事项在助理工作区执行；关联项目需使用电脑核验的权限。",Modifier.padding(horizontal=4.dp),style=Pocket.type.bodySmall,color=c.inkMuted)
        AssistantChoice("重复方式",mapOf("Once" to "一次","Daily" to "每天","Weekly" to "每周","Interval" to "间隔")[kind].orEmpty(),"assistant-schedule-kind",listOf("Once" to "一次","Daily" to "每天","Weekly" to "每周","Interval" to "间隔")) {kind=it}
        if(kind=="Once") PTextField(date,{date=it},Modifier.testTag("assistant-schedule-date"),label="日期 · YYYY-MM-DD",singleLine=true)
        if(kind in setOf("Once","Daily","Weekly")) PTextField(time,{time=it},Modifier.testTag("assistant-schedule-time"),label="时间 · HH:mm",singleLine=true)
        if(kind=="Weekly") AssistantChoice("星期",assistantWeekdays[weekday],"assistant-schedule-weekday",assistantWeekdays.mapIndexed {i,s -> i.toString() to s}) {weekday=it.toInt()}
        if(kind=="Interval") PTextField(minutes,{minutes=it},Modifier.testTag("assistant-schedule-interval"),label="每隔多少分钟",singleLine=true)
        PTextField(zone,{zone=it},Modifier.testTag("assistant-schedule-timezone"),label="创建时区",singleLine=true)
        if(editing==null) {
            AssistantSettingToggle("允许使用已有写入授权",writes && project?.canWrite==true,"assistant-schedule-writes",enabled=canAct && project?.canWrite==true) {writes=it && project?.canWrite==true}
            if(project!=null && !project.canWrite) PText("所选项目将以只读方式处理。",Modifier.padding(horizontal=4.dp),style=Pocket.type.bodySmall,color=c.inkMuted)
        } else PText(if(editing.writes) "保留原有写入授权；新增权限仍需审批。" else "保留原有只读范围。",Modifier.padding(horizontal=4.dp),style=Pocket.type.bodySmall,color=c.inkMuted)
        if(editing!=null) AssistantChoice("错过执行时",if(missedPolicy=="run_once") "补执行一次" else "跳过并记录","assistant-schedule-missed",listOf("skip" to "跳过并记录","run_once" to "补执行一次")) {missedPolicy=it}
        error?.let {PText(it,Modifier.padding(horizontal=4.dp).testTag("assistant-schedule-error"),style=Pocket.type.bodySmall,color=c.danger)}
    }
}
