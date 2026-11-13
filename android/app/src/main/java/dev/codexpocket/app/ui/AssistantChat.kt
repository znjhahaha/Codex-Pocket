package dev.codexpocket.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import kotlinx.coroutines.launch

/** A retained conversation; navigation never submits or discards its draft. */
@Composable
internal fun AssistantChat(state: AssistantUiState, workspaces: List<AssistantWorkspaceChoice>, canAct: Boolean,
    act: (() -> AssistantAction) -> Unit, open: (String, String) -> Unit, navigate: (String) -> Unit, header: @Composable () -> Unit = {}) {
    val snapshot=state.snapshot ?: return
    val runtime=snapshot.runtimeId.orEmpty()
    val palette=assistantPalette()
    var message by rememberSaveable { mutableStateOf("") }
    var sentDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var sentAck by rememberSaveable { mutableLongStateOf(state.sendAcknowledgement) }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetMember by rememberSaveable {mutableStateOf<String?>(null)}
    var targetTask by rememberSaveable {mutableStateOf<String?>(null)}
    var targetPicker by rememberSaveable {mutableStateOf(false)}
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var projectPicker by rememberSaveable { mutableStateOf(false) }
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(state.sendAcknowledgement) {
        if(state.sendAcknowledgement>sentAck && sentDraft!=null) {
            if(message==sentDraft) message=""
            sentDraft=null
        }
        sentAck=state.sendAcknowledgement
    }
    LaunchedEffect(state.error) { if(state.error!=null) sentDraft=null }
    val projects=workspaces.filter {it.runtimeId==runtime && it.threadId.isNotBlank()}.distinctBy {it.threadId}
    val project=projects.firstOrNull {it.threadId==projectId}
    val missingProject=projectId!=null && project==null
    val waiting=state.busy("assistant/send") || sentDraft!=null
    val missingTarget = (targetMember != null && snapshot.members.none {it.id == targetMember}) || (targetTask != null && snapshot.tasks.none {it.id == targetTask})
    val canSend=canAct && snapshot.prepared && !missingTarget && !missingProject && !waiting && message.isNotBlank() && '\u0000' !in message && message.toByteArray(Charsets.UTF_8).size<=65536
    val timeline=remember(state.messages,snapshot) { assistantTimeline(state) }
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    var positioned by rememberSaveable {mutableStateOf(false)}
    var followLatest by rememberSaveable {mutableStateOf(true)}
    var seenKeys by rememberSaveable {mutableStateOf(emptyList<String>())}
    var scrollAck by rememberSaveable {mutableLongStateOf(state.sendAcknowledgement)}
    var unread by rememberSaveable {mutableStateOf(false)}
    // Observe user scrolling separately from changing task status/content height.
    LaunchedEffect(list) {
        snapshotFlow {Triple(list.layoutInfo.totalItemsCount,list.isScrollInProgress,list.canScrollForward)}.collect { (count, scrolling, forward) ->
            // Before the restored list is measured, canScrollForward is still false.
            if(count>0) {
                if(scrolling) followLatest=!forward
                if(!forward) {followLatest=true;unread=false}
            }
        }
    }
    val keys=timeline.map {it.key}
    LaunchedEffect(keys,state.sendAcknowledgement) {
        if(timeline.isNotEmpty()) {
            val previous=seenKeys.toSet()
            val hasNew=keys.any {it !in previous}
            if(!positioned || state.sendAcknowledgement>scrollAck) {
                val working=snapshot.operations.any {it.phase==AssistantOperationPhase.Running && !it.suspended && it.role=="Coordinator"}
                list.scrollToItem(timeline.lastIndex + (if(working) 1 else 0) + (if(state.nextBefore != null) 1 else 0))
                positioned=true
            } else if(hasNew) unread=true
            seenKeys=keys
        }
        scrollAck=state.sendAcknowledgement
    }
    BackHandler(toolsOpen || projectPicker || targetPicker) {if(targetPicker) targetPicker=false else if(projectPicker) projectPicker=false else toolsOpen=false}
    val transcriptBackdrop=rememberLayerBackdrop()
    // Restore chrome geometry together with the list. Zero padding on the first restored
    // measure clamps a near-bottom saved offset before the chrome is measured again.
    var headerHeight by rememberSaveable {mutableIntStateOf(0)}
    var composerHeight by rememberSaveable {mutableIntStateOf(0)}
    val topInset=with(LocalDensity.current) {headerHeight.toDp()}
    val bottomInset=with(LocalDensity.current) {composerHeight.toDp()}
    Box(Modifier.fillMaxSize()) {
        val waitingQuestions=timeline.filterIsInstance<AssistantTimeline.Question>().filter {it.operation.phase==AssistantOperationPhase.Waiting && !it.operation.suspended}
            LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag("assistant-chat-list").layerBackdrop(transcriptBackdrop),
                contentPadding=PaddingValues(start=16.dp,end=16.dp,top=topInset+12.dp,bottom=bottomInset+20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                state.nextBefore?.let {before -> item("older") {
                    PocketTextButton({act {AssistantActions.read(snapshot.after,before=before,limit=50)}}, Modifier.fillMaxWidth().testTag("assistant-older"), enabled=!state.busy("assistant/read") && !state.loading) {Text("读取更早消息")}
                }}
                if(timeline.isEmpty()) item("greeting") {
                    Column(Modifier.fillMaxWidth().padding(top=36.dp,bottom=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        AssistantAvatar(size=56.dp)
                        Text("有什么事，直接跟我说",style=MaterialTheme.typography.titleMedium)
                        Text("任务的进展和结果，都会回到这里。",style=MaterialTheme.typography.bodySmall,color=palette.muted)
                        TextButton({message="帮我整理一下项目的待办"},Modifier.testTag("assistant-suggestion")) {Text("试试：帮我整理项目待办",color=palette.accent)}
                    }
                }
                itemsIndexed(timeline,key={_,item->item.key},contentType={_,item->item.javaClass.simpleName}) {index,item ->
                    val previous=timeline.getOrNull(index-1)?.atMs
                    if(item.atMs!=null && (previous==null || item.atMs!!-previous>=5*60_000L)) {
                        Text(assistantTime(item.atMs!!,snapshot.settings?.timezone ?: "UTC"),Modifier.fillMaxWidth().padding(bottom=12.dp),
                            textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.labelSmall,color=palette.muted)
                    }
                    when(item) {
                        is AssistantTimeline.Message -> AssistantMessageBubble(item.message,runtime)
                        is AssistantTimeline.Group -> AssistantGroupCard(item.group,item.tasks,state,canAct,act,open,workspaces)
                        is AssistantTimeline.Task -> AssistantIncoming {
                            AssistantTaskCard(item.task,state,canAct,act,open,workspaces)
                        }
                        is AssistantTimeline.Question -> AssistantIncoming {
                            AssistantHumanQuestion(item.operation,runtime,state,canAct,act)
                        }
                        is AssistantTimeline.Delivery -> Text("${item.delivery.label} · ${item.delivery.message}",
                            Modifier.fillMaxWidth().testTag("assistant-delivery:${item.delivery.id}"),style=MaterialTheme.typography.labelMedium,maxLines=3,overflow=TextOverflow.Ellipsis)
                        is AssistantTimeline.History -> {
                            val op=snapshot.operations.firstOrNull {it.id==item.history.operationId}
                            val memberId=snapshot.operationMemberId(item.history.operationId,op?.taskId)
                            val source=op?.threadId ?: snapshot.events.lastOrNull {it.operationId==item.history.operationId && it.threadId!=null}?.threadId
                            NativeQuestionHistory.from(item.history.item)?.let {history -> HistoricalQuestionRow(history,"assistant:${item.history.id}",
                                snapshot.members.firstOrNull {it.id==memberId}?.name, if(source!=null && runtime.isNotBlank()) ({open(runtime,source)}) else null)}
                        }
                        is AssistantTimeline.Result -> AssistantIncoming {
                            Column(Modifier.fillMaxWidth().testTag("assistant-result:${item.operation.id}"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                snapshot.operationMemberId(item.operation.id,item.operation.taskId)?.let {id -> snapshot.members.firstOrNull {it.id == id}}?.let {member -> Text(member.name,style=MaterialTheme.typography.labelMedium,color=palette.muted)}
                                AssistantBadge(if(item.operation.phase==AssistantOperationPhase.Completed) "已完成" else "遇到问题",item.operation.phase==AssistantOperationPhase.Failed)
                                MarkdownText(item.operation.result.orEmpty(),"assistant:$runtime:result:${item.operation.id}")
                                item.operation.threadId?.let {thread ->
                                    TextButton({open(runtime,thread)},Modifier.testTag("assistant-thread:${item.operation.id}"),enabled=runtime.isNotBlank()) {
                                        Icon(PocketIcons.Link2,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("查看原对话")
                                    }
                                }
                            }
                        }
                    }
                }
                if(snapshot.operations.any {it.phase==AssistantOperationPhase.Running && !it.suspended && it.role=="Coordinator"}) item("working") {
                    AssistantIncoming {Text("正在处理…",Modifier.padding(12.dp).testTag("assistant-working"),style=MaterialTheme.typography.bodySmall,color=palette.muted)}
                }
            }
        CompositionLocalProvider(LocalPocketBackdrop provides transcriptBackdrop) {
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged {headerHeight=it.height}) {
                header()
                if(waitingQuestions.isNotEmpty()) CompositionLocalProvider(LocalPocketClearGlass provides true) {
                    PocketGlassSurface(onClick={scope.launch {list.scrollToItem(timeline.indexOf(waitingQuestions.first()).coerceAtLeast(0)+if(state.nextBefore!=null) 1 else 0);followLatest=false}},
                        modifier=Modifier.align(Alignment.CenterHorizontally).padding(horizontal=16.dp).testTag("assistant-attention"),shape=PocketOval) {
                        Row(Modifier.heightIn(min=48.dp).padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("${waitingQuestions.size} 件事等你确认",style=MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(12.dp));Text("查看",style=MaterialTheme.typography.labelMedium,color=palette.accent)
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {composerHeight=it.height}.imePadding().navigationBarsPadding()
                .padding(horizontal=16.dp,vertical=8.dp).testTag("assistant-bottom-dock")) {
                if(list.canScrollForward) PocketGlassSurface(onClick={scope.launch {list.scrollToItem((list.layoutInfo.totalItemsCount-1).coerceAtLeast(0));followLatest=true;unread=false}},
                    modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=6.dp).testTag("assistant-latest"),shape=PocketOval,dense=true) {
                    Text(if(unread) "有新消息 · 回到最新" else "回到最新",Modifier.padding(horizontal=18.dp,vertical=12.dp),style=MaterialTheme.typography.labelMedium,color=palette.accent)
                }
                PocketGlassSurface(Modifier.fillMaxWidth().testTag("assistant-composer-glass"),shape=RoundedCornerShape(28.dp),dense=true) {
                Column(Modifier.fillMaxWidth()) {
            if(!snapshot.prepared) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(snapshot.preparationIssue ?: "助理会自动准备，草稿会保留", Modifier.weight(1f).padding(12.dp), style=MaterialTheme.typography.bodySmall)
                PocketTextButton({act {AssistantActions.prepare()}}, Modifier.testTag("assistant-prepare"), enabled=!state.busy("assistant/prepare")) {Text(snapshot.preparationRetryLabel)}
            }
            if(targetMember != null || targetTask != null) PocketTextButton({targetPicker=true}, Modifier.testTag("assistant-selected-target")) {
                Text(if(missingTarget) "所选目标已不可用，请重新选择" else listOfNotNull(snapshot.members.firstOrNull {it.id == targetMember}?.name, snapshot.tasks.firstOrNull {it.id == targetTask}?.prompt).joinToString(" · "))
            }
            if(waiting || missingProject || snapshot.paused==true) Text(
                when {waiting->"正在发送，确认后清除草稿";missingProject->"所选项目已不可用，请重新选择";else->"助理已暂停，可发送消息或在设置中恢复"},
                Modifier.padding(start=16.dp,end=16.dp,top=8.dp).testTag("assistant-send-status"),style=MaterialTheme.typography.labelSmall,color=if(missingProject) MaterialTheme.colorScheme.error else palette.muted)
            if(projectId!=null && !toolsOpen) Row(Modifier.fillMaxWidth().padding(start=12.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton({projectPicker=true},Modifier.weight(1f).testTag("assistant-workspace")) {
                    Icon(AssistantIcons.Project,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp))
                    Text(project?.label ?: "重新选择项目",maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                IconButton({projectId=null},Modifier.size(48.dp).testTag("assistant-clear-workspace")) {Icon(AssistantIcons.Close,"取消关联项目",Modifier.size(18.dp))}
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                IconButton({focus.clearFocus();keyboard?.hide();toolsOpen=!toolsOpen},Modifier.size(48.dp).testTag("assistant-tools")) {
                    Icon(if(toolsOpen) AssistantIcons.Close else AssistantIcons.Plus,if(toolsOpen) "收起更多操作" else "更多操作")
                }
                BasicTextField(value=message,onValueChange={message=it},modifier=Modifier.weight(1f).heightIn(min=48.dp)
                    .onFocusChanged {if(it.isFocused) toolsOpen=false}
                    .padding(horizontal=12.dp,vertical=10.dp).testTag("assistant-message-input").semantics {contentDescription="给助理发消息"},
                    maxLines=4,textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),cursorBrush=SolidColor(palette.accent),
                    decorationBox={input -> Box {if(message.isEmpty()) Text("发消息…",style=MaterialTheme.typography.bodyLarge,color=palette.muted);input()}})
                PocketButton("发送",onClick={val text=message;act {AssistantActions.send(runtime,text,project,targetMember,targetTask)};sentDraft=text;sentAck=state.sendAcknowledgement;toolsOpen=false;followLatest=true},
                    modifier=Modifier.heightIn(min=48.dp).testTag("assistant-send"),enabled=canSend)
            }
            if(toolsOpen) Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=12.dp).testTag("assistant-tools-panel"),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                AssistantTool("项目",AssistantIcons.Project,Modifier.weight(1f).testTag("assistant-workspace")) {projectPicker=true}
                AssistantTool("发给",PocketIcons.Users,Modifier.weight(1f).testTag("assistant-target")) {targetPicker=true}
                AssistantTool("记忆",AssistantIcons.Memory,Modifier.weight(1f)) {toolsOpen=false;navigate("Memory")}
                AssistantTool("定时",AssistantIcons.Schedule,Modifier.weight(1f)) {toolsOpen=false;navigate("Schedules")}
            }
                }
                }
            }
        }
    }
    if(targetPicker) PocketDialog({targetPicker=false},title={Text("聊天目标")},text={
        LazyColumn(Modifier.heightIn(max=320.dp)) {
            item {PocketMenuItem("助理自动安排",AssistantIcons.Tasks,{targetMember=null;targetTask=null;targetPicker=false},Modifier.testTag("assistant-target:auto"),selected=targetMember==null && targetTask==null)}
            itemsIndexed(snapshot.tasks.distinctBy {it.id},key={_,t -> "task:${t.id}"}) {_,task ->
                PocketMenuItem(task.prompt,AssistantIcons.Tasks,{targetTask=task.id;targetMember=null;targetPicker=false},Modifier.testTag("assistant-target:task:${task.id}"),selected=targetTask==task.id)
            }
            itemsIndexed(snapshot.members.filter {it.active}.distinctBy {it.id},key={_,m -> "member:${m.id}"}) {_,member ->
                PocketMenuItem(member.name,PocketIcons.Users,{targetMember=member.id;targetTask=null;targetPicker=false},Modifier.testTag("assistant-target:member:${member.id}"),selected=targetMember==member.id)
            }
        }
    },confirmButton={TextButton({targetPicker=false}) {Text("完成")}})
    if(projectPicker) PocketDialog({projectPicker=false},title={Text("关联项目")},text={
        LazyColumn(Modifier.heightIn(max=320.dp)) {
            item {PocketMenuItem("不关联项目",AssistantIcons.Close,{projectId=null;projectPicker=false;toolsOpen=false},Modifier.testTag("assistant-workspace:none"),selected=projectId==null)}
            itemsIndexed(projects,key={_,p->p.threadId}) {_,p ->
                PocketMenuItem(p.label,AssistantIcons.Project,{projectId=p.threadId;projectPicker=false;toolsOpen=false},Modifier.testTag("assistant-workspace:${p.threadId}"),selected=projectId==p.threadId)
            }
            if(projects.isEmpty()) item {Text("暂时没有可关联的项目",Modifier.padding(12.dp),color=palette.muted)}
        }
    },confirmButton={TextButton({projectPicker=false}) {Text("完成")}})
}

@Composable private fun AssistantTool(label:String,icon:ImageVector,modifier:Modifier,onClick:()->Unit) {
    val palette=assistantPalette()
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Surface(shape=RoundedCornerShape(16.dp),color=palette.surface) {Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {Icon(icon,null,Modifier.size(24.dp),tint=palette.accent)}}
        Text(label,style=MaterialTheme.typography.labelMedium,color=palette.muted)
    }
}

@Composable internal fun AssistantIncoming(content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().widthIn(max=720.dp),content=content)
}

@Composable private fun AssistantMessageBubble(message:AssistantChatMessage,runtime:String) {
    val palette=assistantPalette()
    if(message.role == "Assistant") {
        Column(Modifier.fillMaxWidth().widthIn(max=720.dp).testTag("assistant-message:${message.id}"), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            message.authorName?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.labelMedium,color=palette.muted)}
            MarkdownText(message.text,"assistant:$runtime:message:${message.id}")
        }
    } else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
        Surface(Modifier.widthIn(max=640.dp).padding(start=32.dp).testTag("assistant-message:${message.id}"),
            color=palette.outgoing,shape=RoundedCornerShape(16.dp)) {
            SelectionContainer {ReferenceText(message.text,emptyList(),Modifier.padding(14.dp),color=palette.onOutgoing)}
        }
    }
}
