package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import java.time.ZoneId

@Composable internal fun AssistantSettingsPage(state:AssistantUiState,models:List<NativeModel>,runtimes:List<AssistantRuntimeChoice>,canAct:Boolean,act:(()->AssistantAction)->Unit) {
    val c=Pocket.colors
    val s=state.snapshot?.settings
    val current=state.snapshot?.runtimeId.orEmpty()
    val choices=runtimes.filter {it.available && it.id.isNotBlank()}.distinctBy {it.id}
    var selected by rememberSaveable {mutableStateOf(current.ifBlank {choices.singleOrNull()?.id.orEmpty()})}
    var personality by rememberSaveable {mutableStateOf(s?.personality.orEmpty())}
    var tone by rememberSaveable {mutableStateOf(s?.tone.orEmpty())}
    var model by rememberSaveable {mutableStateOf(s?.model)}
    // The assistant's own model: null follows the model above. Only its own control changes it.
    var coordinator by rememberSaveable {mutableStateOf(s?.coordinatorModel)}
    var zone by rememberSaveable {mutableStateOf(s?.timezone ?: ZoneId.systemDefault().id)}
    var completed by rememberSaveable {mutableStateOf(s?.notifications?.completed ?: true)}
    var failed by rememberSaveable {mutableStateOf(s?.notifications?.failed ?: true)}
    var waiting by rememberSaveable {mutableStateOf(s?.notifications?.waitingUser ?: true)}
    var dirty by rememberSaveable {mutableStateOf(false)}
    val draft=AssistantSettings(personality,tone,model,zone,AssistantNotifications(completed,failed,waiting),coordinator)
    var initializedSettings by rememberSaveable {mutableStateOf(s!=null)}
    LaunchedEffect(s) {
        if(s!=null && !initializedSettings) {
            // Preparation can supply defaults after a model was explicitly chosen. Preserve that choice.
            if(personality.isEmpty()) personality=s.personality
            if(tone.isEmpty()) tone=s.tone
            initializedSettings=true
        }
        if(s!=null && (!dirty || s==draft)) {personality=s.personality;tone=s.tone;model=s.model;coordinator=s.coordinatorModel;zone=s.timezone
            completed=s.notifications.completed;failed=s.notifications.failed;waiting=s.notifications.waitingUser;dirty=false}
    }
    // A coordinator model the computer already holds is sent back as it is, listed or not.
    val savedAction=runCatching {AssistantActions.settings(current,draft,models,s)}
    val catalogue=models.filter {it.model.isNotBlank()}.distinctBy {it.model}
    val modelChoice=selected==current || state.snapshot?.enabled==false
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-settings-list"),contentPadding=AssistantPagePadding,verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {AssistantCard {
            PText("助理的运行入口",style=Pocket.type.titleSmall)
            AssistantChoice("电脑上的配置",choices.firstOrNull {it.id==selected}?.label ?: "请选择运行入口","assistant-runtime",choices.map {it.id to it.label},enabled=!state.loading) {selected=it}
            if(state.snapshot?.enabled!=true || selected!=current) {
                PText(if(state.snapshot?.enabled==null) "请先读取启用状态。" else "启用请求会交给所选运行入口确认。",style=Pocket.type.bodySmall,color=c.inkMuted)
                PButton(if(state.snapshot?.enabled==false) "启用助理" else "使用这个运行入口",{act {AssistantActions.enable(selected)}},Modifier.fillMaxWidth().testTag("assistant-enable"),
                    enabled=state.snapshot?.enabled!=null && choices.any {it.id==selected} && !state.loading && state.error==null && !state.busy("assistant/enable"),haptic=PocketHaptic.Confirm)
            }
        }}
        item {AssistantPageHeading("人格与语气")}
        item {PTextField(personality,{personality=it;dirty=true},Modifier.testTag("assistant-personality"),label="人格",placeholder="希望助理如何协助你",minLines=3,maxLines=8)}
        item {PTextField(tone,{tone=it;dirty=true},Modifier.testTag("assistant-tone"),label="语气",placeholder="例如：直接、耐心、简洁",minLines=2,maxLines=5)}
        item {AssistantPageHeading("模型")}
        item {Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            AssistantChoice("任务使用的模型",if(model==null) "随运行入口默认模型" else models.firstOrNull {it.model==model}?.name?.ifBlank {model!!} ?: "当前模型未在目录确认",
                "assistant-model",listOf("host-default" to "随运行入口默认模型")+catalogue.map {"model:${it.model}" to it.name.ifBlank {it.model}},enabled=modelChoice) {model=if(it=="host-default") null else it.removePrefix("model:");dirty=true}
            if(model!=null && models.none {it.model==model}) PText("保留原模型；请刷新目录或明确选择默认模型。",Modifier.padding(horizontal=4.dp),style=Pocket.type.bodySmall,color=c.inkMuted)
        }}
        item {Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            val listed=catalogue.firstOrNull {it.model==coordinator}
            AssistantChoice("助理自身的模型",when {coordinator==null -> "跟随默认";listed!=null -> listed.name.ifBlank {listed.model};else -> "$coordinator（不在列表中）"},
                "assistant-coordinator-model",listOf("follow-default" to "跟随默认")+catalogue.map {"model:${it.model}" to it.name.ifBlank {it.model}},enabled=modelChoice) {
                coordinator=if(it=="follow-default") null else it.removePrefix("model:");dirty=true
            }
            PText(when {
                coordinator==null -> "助理自己对话和安排任务时，使用上面为任务选择的模型。"
                listed==null -> "电脑上保存的是这个模型，但它不在当前目录里。不改动它也可以保存其他设置；选了别的就不能再选回来。"
                else -> "只用于助理自己的对话和安排；交办出去的任务仍使用上面的模型。"
            },Modifier.padding(horizontal=4.dp).testTag("assistant-coordinator-model-note"),style=Pocket.type.bodySmall,color=c.inkMuted)
        }}
        item {Column {
            PTextField(zone,{zone=it;dirty=true},Modifier.testTag("assistant-timezone"),label="时区",placeholder="Asia/Shanghai",singleLine=true)
            PTextAction("使用手机时区 · ${ZoneId.systemDefault().id}",{zone=ZoneId.systemDefault().id;dirty=true},tint=c.inkMuted)
        }}
        item {AssistantPageHeading("通知意向")}
        item {AssistantCard {
            AssistantSettingToggle("任务完成",completed,"assistant-notify-completed") {completed=it;dirty=true}
            AssistantSettingToggle("执行失败",failed,"assistant-notify-failed") {failed=it;dirty=true}
            AssistantSettingToggle("等待我确认",waiting,"assistant-notify-waiting") {waiting=it;dirty=true}
        }}
        item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            PText("这些设置决定哪些事件需要通知；系统通知权限由主界面管理。",Modifier.padding(horizontal=4.dp),style=Pocket.type.bodySmall,color=c.inkMuted)
            savedAction.exceptionOrNull()?.message?.let {PText(it,Modifier.padding(horizontal=4.dp).testTag("assistant-settings-issue"),style=Pocket.type.bodySmall,color=c.warning)}
            PButton("保存设置",{act {savedAction.getOrThrow()}},Modifier.fillMaxWidth().testTag("assistant-save-settings"),
                enabled=canAct && current==selected && savedAction.isSuccess && !state.busy("assistant/settings/update"),haptic=PocketHaptic.Confirm)
        }}
        if(state.snapshot?.enabled==true) item {AssistantCard {
            PText("暂时放下任务",style=Pocket.type.titleSmall)
            PText("暂停会交给电脑处理；已暂停任务需要单独继续。",style=Pocket.type.bodySmall,color=c.inkMuted)
            val paused=state.snapshot.paused==true
            AssistantQuietButton(if(paused) "恢复助理" else "暂停助理",{act {if(paused) AssistantActions.resume(current) else AssistantActions.pause(current)}},Modifier.fillMaxWidth().testTag("assistant-global-pause"),enabled=canAct && !state.busy(if(paused) "assistant/resume" else "assistant/pause"))
        }}
    }
}
