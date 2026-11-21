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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A retained conversation; navigation never submits or discards its draft. */
@Composable
internal fun AssistantChat(state: AssistantUiState, workspaces: List<AssistantWorkspaceChoice>, canAct: Boolean,
    act: (() -> AssistantAction) -> Unit, open: (String, String) -> Unit, navigate: (String) -> Unit, header: @Composable () -> Unit = {},
    onProjectSelected: (AssistantWorkspaceChoice?) -> Unit = {}) {
    val binding=(state.snapshot ?: return).composerBinding(state.computerId)
    val holder=rememberSaveableStateHolder()
    var scopeAliases by rememberSaveable {mutableStateOf(emptyMap<String,String>())}
    var boundScopes by rememberSaveable {mutableStateOf(emptyList<String>())}
    var submittedScopes by rememberSaveable {mutableStateOf(emptyList<String>())}
    var preparationScope by rememberSaveable {mutableStateOf<String?>(null)}
    var preparationComputer by rememberSaveable {mutableStateOf<String?>(null)}
    val preparing=state.snapshot?.prepared!=true || binding.runtimeId.isBlank()
    val newPreparation=preparing && binding.scopeKey !in boundScopes
    val preparationSource=preparationScope?.takeIf {
        preparationComputer==binding.computerId && it !in submittedScopes
    }
    val adopting=!preparing && binding.scopeKey !in boundScopes && preparationSource!=null
    // An unsent draft may follow the same computer through its preparation stages.
    // Once bound or submitted, that scope can never lend its draft to another runtime.
    val unboundKey=if(newPreparation && binding.scopeKey in scopeAliases.values)
        "${binding.scopeKey}:preparing:${scopeAliases.size}" else binding.scopeKey
    val storageKey=scopeAliases[binding.scopeKey] ?: if(newPreparation || adopting)
        preparationSource ?: unboundKey else binding.scopeKey
    SideEffect {
        if(newPreparation) {
            preparationScope=storageKey;preparationComputer=binding.computerId
        } else {
            if(adopting && storageKey!=binding.scopeKey) scopeAliases=scopeAliases+(binding.scopeKey to storageKey)
            if(!preparing && binding.scopeKey !in boundScopes) boundScopes=boundScopes+binding.scopeKey
            preparationScope=null;preparationComputer=null
        }
    }
    holder.SaveableStateProvider(storageKey) {
        AssistantChatComposer(state,workspaces,canAct,act,open,navigate,header,onProjectSelected,binding) {submitted ->
            if(submitted && storageKey !in submittedScopes) submittedScopes=submittedScopes+storageKey
            else if(!submitted && storageKey in submittedScopes) submittedScopes=submittedScopes-storageKey
        }
    }
}

@Composable
private fun AssistantChatComposer(state: AssistantUiState, workspaces: List<AssistantWorkspaceChoice>, canAct: Boolean,
    act: (() -> AssistantAction) -> Unit, open: (String, String) -> Unit, navigate: (String) -> Unit, header: @Composable () -> Unit,
    onProjectSelected: (AssistantWorkspaceChoice?) -> Unit, binding: AssistantComposerBinding,
    onSubmissionState:(Boolean)->Unit) {
    val projectCallback=rememberUpdatedState(onProjectSelected)
    DisposableEffect(Unit) {onDispose {projectCallback.value(null)}}
    val snapshot=state.snapshot ?: return
    val runtime=snapshot.runtimeId.orEmpty()
    val palette=assistantPalette()
    val compactChrome=assistantCompactChrome()
    var message by rememberSaveable { mutableStateOf("") }
    var sentDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var sentKey by rememberSaveable { mutableStateOf<String?>(null) }
    var draftRevision by rememberSaveable { mutableLongStateOf(0) }
    var sentRevision by rememberSaveable { mutableLongStateOf(0) }
    var acceptedSends by rememberSaveable { mutableLongStateOf(0) }
    SideEffect {onSubmissionState(sentDraft!=null)}
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetMember by rememberSaveable {mutableStateOf<String?>(null)}
    var targetTask by rememberSaveable {mutableStateOf<String?>(null)}
    var targetPicker by rememberSaveable {mutableStateOf(false)}
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var projectPicker by rememberSaveable { mutableStateOf(false) }
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(state.sendReceipts,sentKey,binding) {
        val receipt=state.sendReceipts.firstOrNull {it.key==sentKey && it.computerId==binding.computerId && it.runtimeId==binding.runtimeId &&
            it.conversationId==binding.conversationId && it.status in setOf("accepted","rejected")}
        if(receipt!=null && sentDraft!=null) {
            if(receipt.status=="accepted" && message==sentDraft && draftRevision==sentRevision) message=""
            if(receipt.status=="accepted") acceptedSends++
            sentDraft=null;sentKey=null
        }
    }
    val projects=workspaces.filter {!it.isSubagent && it.runtimeId.isNotBlank() && it.threadId.isNotBlank()}.distinctBy {it.selectionKey}
    val project=projects.firstOrNull {it.selectionKey==projectId}
    val missingProject=projectId!=null && project==null
    val waiting=state.busy("assistant/send") || sentDraft!=null
    val missingTarget = (targetMember != null && snapshot.members.none {it.id == targetMember}) || (targetTask != null && snapshot.tasks.none {it.id == targetTask})
    val canSend=canAct && snapshot.prepared && snapshot.paused!=true && !missingTarget && !missingProject && !waiting && message.isNotBlank() && '\u0000' !in message && message.toByteArray(Charsets.UTF_8).size<=65536
    val timeline=remember(state.messages,snapshot) { assistantTimeline(state) }
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    var positioned by rememberSaveable {mutableStateOf(false)}
    var followLatest by rememberSaveable {mutableStateOf(true)}
    var seenKeys by rememberSaveable {mutableStateOf(emptyList<String>())}
    var scrollAck by rememberSaveable {mutableLongStateOf(acceptedSends)}
    var unread by rememberSaveable {mutableStateOf(false)}
    // Restore chrome geometry together with the list. A fresh list must wait for both
    // overlays to be measured and for their padding to reach the list's next layout.
    // -1 means unmeasured; an empty header can legitimately measure zero.
    var headerHeight by rememberSaveable {mutableIntStateOf(-1)}
    var composerHeight by rememberSaveable {mutableIntStateOf(-1)}
    val density=LocalDensity.current
    val topInset=with(density) {headerHeight.coerceAtLeast(0).toDp()}
    val bottomInset=with(density) {composerHeight.coerceAtLeast(0).toDp()}
    val topPadding=topInset+12.dp
    val bottomPadding=bottomInset+20.dp
    val topPaddingPx=with(density) {topPadding.roundToPx()}
    val bottomPaddingPx=with(density) {bottomPadding.roundToPx()}
    // Observe user scrolling separately from changing task status/content height.
    LaunchedEffect(list) {
        snapshotFlow {Triple(list.layoutInfo.totalItemsCount,list.isScrollInProgress,list.canScrollForward)}.collect { (count, scrolling, forward) ->
            // Before the restored list is measured, canScrollForward is still false.
            if(count>0) {
                if(scrolling) followLatest=!forward
                if(!forward) {followLatest=true;unread=false}
                else if(positioned && followLatest && !scrolling && sentDraft==null && acceptedSends==scrollAck) {
                    // Native Markdown can change row heights after the first tail layout.
                    // Keep that landing at the tail until the user chooses to read history.
                    list.scrollToItem(count-1)
                }
            }
        }
    }
    val keys=timeline.map {it.key}
    val working=snapshot.operations.any {it.phase==AssistantOperationPhase.Running && !it.suspended && it.role=="Coordinator"}
    val tailIndex=timeline.lastIndex + (if(working) 1 else 0) + (if(state.nextBefore != null) 1 else 0)
    LaunchedEffect(keys,acceptedSends,headerHeight,composerHeight,topPaddingPx,bottomPaddingPx,tailIndex) {
        if(timeline.isNotEmpty()) {
            val previous=seenKeys.toSet()
            val hasNew=keys.any {it !in previous}
            if(!positioned || acceptedSends>scrollAck) {
                if(headerHeight<0 || composerHeight<0) return@LaunchedEffect
                snapshotFlow { Triple(list.layoutInfo.totalItemsCount, list.layoutInfo.beforeContentPadding, list.layoutInfo.afterContentPadding) }.first { (count, before, after) ->
                    count==tailIndex+1 && before==topPaddingPx && after==bottomPaddingPx
                }
                list.scrollToItem(tailIndex)
                positioned=true
                followLatest=true
            } else if(hasNew) unread=true
            seenKeys=keys
        }
        scrollAck=acceptedSends
    }
    BackHandler(toolsOpen || projectPicker || targetPicker) {if(targetPicker) targetPicker=false else if(projectPicker) projectPicker=false else toolsOpen=false}
    val transcriptBackdrop=rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        val waitingQuestions=timeline.filter {it is AssistantTimeline.Recovery || it is AssistantTimeline.HandoffRecovery ||
            (it is AssistantTimeline.Question && it.operation.phase==AssistantOperationPhase.Waiting && !it.operation.suspended)}
            // Capture the page colour with the transcript. A transparent source blends
            // blurred glyphs over their still-sharp originals beneath the glass.
            LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag("assistant-chat-list").layerBackdrop(transcriptBackdrop)
                .background(MaterialTheme.colorScheme.background),
                contentPadding=PaddingValues(start=16.dp,end=16.dp,top=topPadding,bottom=bottomPadding),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                state.nextBefore?.let {before -> item("older") {
                    PocketTextButton({act {AssistantActions.read(snapshot.after,before=before,limit=50)}}, Modifier.fillMaxWidth().testTag("assistant-older"), enabled=!state.busy("assistant/read") && !state.loading) {Text("读取更早消息")}
                }}
                if(timeline.isEmpty()) item("greeting") {
                    Column(Modifier.fillMaxWidth().padding(top=36.dp,bottom=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        AssistantAvatar(size=56.dp)
                        Text("有什么事，直接跟我说",style=MaterialTheme.typography.titleMedium)
                        Text("任务的进展和结果，都会回到这里。",style=MaterialTheme.typography.bodySmall,color=palette.muted)
                        TextButton({message="帮我整理本周待办，按重要程度安排";draftRevision++},Modifier.testTag("assistant-suggestion")) {Text("试试：整理本周待办",color=palette.accent)}
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
                        is AssistantTimeline.Group -> AssistantGroupSteps(item.group,item.tasks,state,navigate)
                        is AssistantTimeline.Task -> AssistantIncoming {
                            AssistantTaskSteps(item.task,state,navigate)
                        }
                        is AssistantTimeline.Question -> AssistantIncoming {
                            AssistantHumanQuestion(item.operation,runtime,state,canAct,act)
                        }
                        is AssistantTimeline.Recovery -> AssistantIncoming {
                            AssistantRecoveryCard(item.request,runtime,state,canAct,act)
                        }
                        is AssistantTimeline.HandoffRecovery -> AssistantIncoming {
                            AssistantHandoffRecoveryCard(item.operation,runtime,state,canAct,act)
                        }
                        is AssistantTimeline.EndedQuestion -> AssistantIncoming {AssistantEndedQuestion(item.input)}
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
                            Column(Modifier.fillMaxWidth().testTag("assistant-result:${item.operation.id}"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                snapshot.operationMemberId(item.operation.id,item.operation.taskId)?.let {id -> snapshot.members.firstOrNull {it.id == id}}?.let {member -> Text(member.name,style=MaterialTheme.typography.labelMedium,color=palette.muted)}
                                AssistantBadge(if(item.operation.phase==AssistantOperationPhase.Completed) "已完成" else "遇到问题",item.operation.phase==AssistantOperationPhase.Failed)
                                Text(item.operation.result.orEmpty(),style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                TextButton({navigate("Result:${item.operation.id}")},Modifier.heightIn(min=48.dp).testTag("assistant-result-detail:${item.operation.id}")) {Text("查看完整结果")}
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
                            Text("${waitingQuestions.size} 件事待处理",style=MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(12.dp));Text("查看",style=MaterialTheme.typography.labelMedium,color=palette.accent)
                        }
                    }
                }
            }
            // A scroll-dependent control must not change the measured dock height:
            // otherwise reaching the tail hides it, changes padding and moves the tail again.
            if(list.canScrollForward) PocketGlassSurface(onClick={scope.launch {list.scrollToItem((list.layoutInfo.totalItemsCount-1).coerceAtLeast(0));followLatest=true;unread=false}},
                    modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=bottomInset+6.dp).testTag("assistant-latest"),shape=PocketOval,dense=true) {
                    Text(if(unread) "有新消息 · 回到最新" else "回到最新",Modifier.padding(horizontal=18.dp,vertical=12.dp),style=MaterialTheme.typography.labelMedium,color=palette.accent)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {composerHeight=it.height}.imePadding().navigationBarsPadding()
                .padding(horizontal=16.dp,vertical=8.dp).testTag("assistant-bottom-dock")) {
                PocketGlassSurface(Modifier.fillMaxWidth().testTag("assistant-composer-glass"),shape=RoundedCornerShape(28.dp),dense=true) {
                Column(Modifier.fillMaxWidth()) {
            if(!snapshot.prepared && !snapshot.dispatchInFlight) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if(!compactChrome || snapshot.startupStages.isEmpty()) Text(snapshot.preparationIssue ?: "助理会自动准备，草稿会保留", Modifier.weight(1f).padding(12.dp), style=MaterialTheme.typography.bodySmall)
                PocketTextButton({act {AssistantActions.prepare()}}, Modifier.then(if(compactChrome && snapshot.startupStages.isNotEmpty()) Modifier.fillMaxWidth() else Modifier).testTag("assistant-prepare"), enabled=!state.busy("assistant/prepare")) {Text(snapshot.preparationRetryLabel)}
            }
            if(targetMember != null || targetTask != null) PocketTextButton({targetPicker=true}, Modifier.testTag("assistant-selected-target")) {
                Text(if(missingTarget) "所选目标已不可用，请重新选择" else listOfNotNull(snapshot.members.firstOrNull {it.id == targetMember}?.name, snapshot.tasks.firstOrNull {it.id == targetTask}?.prompt).joinToString(" · "))
            }
            if(waiting || missingProject || snapshot.paused==true) Text(
                when {waiting->"正在发送，确认后清除草稿";missingProject->"所选项目已不可用，请重新选择";else->"助理已暂停，请在设置中恢复"},
                Modifier.padding(start=16.dp,end=16.dp,top=8.dp).testTag("assistant-send-status"),style=MaterialTheme.typography.labelSmall,color=if(missingProject) MaterialTheme.colorScheme.error else palette.muted)
            if(projectId!=null && !toolsOpen) Row(Modifier.fillMaxWidth().padding(start=12.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton({projectPicker=true},Modifier.weight(1f).testTag("assistant-workspace")) {
                    Icon(AssistantIcons.Project,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp))
                    Text(project?.label ?: "重新选择项目",maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                IconButton({projectId=null;onProjectSelected(null)},Modifier.size(48.dp).testTag("assistant-clear-workspace")) {Icon(AssistantIcons.Close,"取消关联项目",Modifier.size(18.dp))}
            }
            project?.let {selected -> Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(selected.permissionStatus,Modifier.weight(1f).testTag("assistant-project-permission-status"),style=MaterialTheme.typography.labelSmall,color=palette.muted)
                TextButton({onProjectSelected(selected)},Modifier.testTag("assistant-project-recheck"),enabled=canAct) {Text("核验权限")}
            }}
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                IconButton({focus.clearFocus();keyboard?.hide();toolsOpen=!toolsOpen},Modifier.size(48.dp).testTag("assistant-tools")) {
                    Icon(if(toolsOpen) AssistantIcons.Close else AssistantIcons.Plus,if(toolsOpen) "收起更多操作" else "更多操作")
                }
                BasicTextField(value=message,onValueChange={message=it;draftRevision++},modifier=Modifier.weight(1f).heightIn(min=48.dp)
                    .onFocusChanged {if(it.isFocused) toolsOpen=false}
                    .padding(horizontal=12.dp,vertical=10.dp).testTag("assistant-message-input").semantics {contentDescription="给助理发消息"},
                    maxLines=if(compactChrome) 3 else 4,textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),cursorBrush=SolidColor(palette.accent),
                    decorationBox={input -> Box {if(message.isEmpty()) Text("发消息…",style=MaterialTheme.typography.bodyLarge,color=palette.muted,maxLines=1,overflow=TextOverflow.Ellipsis);input()}})
                val send={val text=message;val id=java.util.UUID.randomUUID().toString();
                    act {val action=AssistantActions.send(runtime,text,project,targetMember,targetTask).copy(commandId=id,composerBinding=binding)
                        sentDraft=text;sentRevision=draftRevision;sentKey=binding.receiptKey(id);action}
                    toolsOpen=false}
                if(compactChrome) FilledIconButton(onClick=send,modifier=Modifier.size(48.dp).testTag("assistant-send"),enabled=canSend) {
                    Icon(PocketIcons.ArrowUpward,"发送")
                } else PocketButton("发送",onClick=send,modifier=Modifier.heightIn(min=48.dp).testTag("assistant-send"),enabled=canSend)
            }
            if(toolsOpen) Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=12.dp).testTag("assistant-tools-panel"),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                AssistantTool("项目",AssistantIcons.Project,Modifier.weight(1f).testTag("assistant-workspace")) {projectPicker=true}
                AssistantTool("发给",PocketIcons.Users,Modifier.weight(1f).testTag("assistant-target")) {targetPicker=true}

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
            item {PocketMenuItem("不关联项目",AssistantIcons.Close,{projectId=null;onProjectSelected(null);projectPicker=false;toolsOpen=false},Modifier.testTag("assistant-workspace:none"),selected=projectId==null)}
            itemsIndexed(projects,key={_,p->p.selectionKey}) {_,p ->
                PocketMenuItem(p.label,AssistantIcons.Project,{projectId=p.selectionKey;onProjectSelected(p);projectPicker=false;toolsOpen=false},Modifier.testTag("assistant-workspace:${p.threadId}"),selected=projectId==p.selectionKey)
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
