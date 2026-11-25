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

@Composable internal fun AssistantSettingsPage(state:AssistantUiState,models:List<NativeModel>,runtimes:List<AssistantRuntimeChoice>,canAct:Boolean,act:(()->AssistantAction)->Unit,connected:Boolean=canAct) {
    val c=Pocket.colors
    val s=state.snapshot?.settings
    val current=(state.snapshot?.selectedRuntimeId ?: state.snapshot?.runtimeId).orEmpty()
    val scope=state.snapshot?.scopeId ?: current
    val choices=runtimes.filter {it.available && it.id.isNotBlank()}.distinctBy {it.id}
    var selected by rememberSaveable {mutableStateOf(current.ifBlank {choices.singleOrNull()?.id.orEmpty()})}
    var personality by rememberSaveable {mutableStateOf(s?.personality.orEmpty())}
    var tone by rememberSaveable {mutableStateOf(s?.tone.orEmpty())}
    var zone by rememberSaveable {mutableStateOf(s?.timezone ?: ZoneId.systemDefault().id)}
    var completed by rememberSaveable {mutableStateOf(s?.notifications?.completed ?: true)}
    var failed by rememberSaveable {mutableStateOf(s?.notifications?.failed ?: true)}
    var waiting by rememberSaveable {mutableStateOf(s?.notifications?.waitingUser ?: true)}
    var dirty by rememberSaveable {mutableStateOf(false)}
    val draft=AssistantSettings(personality,tone,s?.model,zone,AssistantNotifications(completed,failed,waiting),s?.coordinatorModel)
    var initializedSettings by rememberSaveable {mutableStateOf(s!=null)}
    LaunchedEffect(s) {
        if(s!=null && !initializedSettings) {
            // Preparation can supply defaults after a model was explicitly chosen. Preserve that choice.
            if(personality.isEmpty()) personality=s.personality
            if(tone.isEmpty()) tone=s.tone
            initializedSettings=true
        }
        if(s!=null && (!dirty || s==draft)) {personality=s.personality;tone=s.tone;zone=s.timezone
            completed=s.notifications.completed;failed=s.notifications.failed;waiting=s.notifications.waitingUser;dirty=false}
    }
    val savedAction=runCatching {AssistantActions.preferences(scope,draft)}
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-settings-list"),contentPadding=AssistantPagePadding,verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {AssistantCard {
            PText("助理的运行入口",style=Pocket.type.titleSmall)
            AssistantChoice("电脑上的配置",choices.firstOrNull {it.id==selected}?.label ?: "请选择运行入口","assistant-runtime",choices.map {it.id to it.label},enabled=!state.loading) {selected=it}
            if(state.snapshot?.enabled!=true || selected!=current) {
                PText(if(state.snapshot?.enabled==null) "请先读取启用状态。" else "保存运行入口，发送消息时启动。",style=Pocket.type.bodySmall,color=c.inkMuted)
                PButton("使用这个运行入口",{act {AssistantActions.enable(selected)}},Modifier.fillMaxWidth().testTag("assistant-enable"),
                    enabled=connected && state.snapshot?.enabled!=null && choices.any {it.id==selected} && !state.loading && state.error==null && !state.busy("assistant/enable"),haptic=PocketHaptic.Confirm)
            }
        }}
        item {AssistantPageHeading("人格与语气")}
        item {PTextField(personality,{personality=it;dirty=true},Modifier.testTag("assistant-personality"),label="人格",placeholder="希望助理如何协助你",minLines=3,maxLines=8)}
        item {PTextField(tone,{tone=it;dirty=true},Modifier.testTag("assistant-tone"),label="语气",placeholder="例如：直接、耐心、简洁",minLines=2,maxLines=5)}
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
                enabled=connected && (canAct || state.snapshot?.preparationStatus=="idle") && current==selected && savedAction.isSuccess && !state.busy("assistant/settings/update"),haptic=PocketHaptic.Confirm)
        }}
        if(state.snapshot?.enabled==true) item {AssistantCard {
            PText("暂时放下任务",style=Pocket.type.titleSmall)
            PText("暂停会交给电脑处理；已暂停任务需要单独继续。",style=Pocket.type.bodySmall,color=c.inkMuted)
            val paused=state.snapshot.paused==true
            AssistantQuietButton(if(paused) "恢复助理" else "暂停助理",{act {if(paused) AssistantActions.resume(current) else AssistantActions.pause(current)}},Modifier.fillMaxWidth().testTag("assistant-global-pause"),enabled=canAct && !state.busy(if(paused) "assistant/resume" else "assistant/pause"))
        }}
    }
}
