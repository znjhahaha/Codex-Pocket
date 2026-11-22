package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*

@Composable internal fun AssistantMemoryPage(state:AssistantUiState,canAct:Boolean,act:(()->AssistantAction)->Unit) {
    val c=Pocket.colors
    var history by rememberSaveable {mutableStateOf(false)}
    Column {
        Row(Modifier.padding(start=16.dp,end=16.dp,top=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            PPill("当前记忆",!history,{history=false})
            PPill("修订历史",history,{history=true},Modifier.testTag("assistant-memory-history"))
        }
        if(!history) AssistantMemories(state,canAct,act) else LazyColumn(contentPadding=AssistantPagePadding,verticalArrangement=Arrangement.spacedBy(AssistantPageSpacing)) {
            val revisions=state.snapshot?.domain?.memoryRevisions.orEmpty()
            if(revisions.isEmpty()) item {AssistantPageNote("尚无修订记录")}
            items(revisions.asReversed(),key={"${it.memoryId}:${it.revision}"}) {revision->AssistantCard {
                PText("修订 ${revision.revision} · ${if(revision.state=="deleted") "已删除" else "已保存"}",style=Pocket.type.labelSmall,color=c.inkMuted)
                revision.text?.let {SelectionContainer {PText(it,style=Pocket.type.body)}}
                PText("来源 · ${revision.source ?: "旧记录未提供"}",style=Pocket.type.bodySmall,color=c.inkMuted)
            }}
        }
    }
}

@Composable internal fun AssistantMemories(state:AssistantUiState,canAct:Boolean,act:(()->AssistantAction)->Unit) {
    val c=Pocket.colors
    val runtime=state.snapshot?.runtimeId.orEmpty()
    var editing by rememberSaveable {mutableStateOf(false)}
    var editId by rememberSaveable {mutableStateOf<String?>(null)}
    var text by rememberSaveable {mutableStateOf("")}
    var deleteId by rememberSaveable {mutableStateOf<String?>(null)}
    LazyColumn(Modifier.fillMaxSize().testTag("assistant-memory-list"),contentPadding=AssistantPagePadding,verticalArrangement=Arrangement.spacedBy(AssistantPageSpacing)) {
        item {Column(Modifier.padding(horizontal=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            PText("让助理记住你的习惯与事实",style=Pocket.type.title)
            PText("自动保存来自你本人陈述的长期信息，可随时撤销。记忆不代表执行授权。",style=Pocket.type.bodySmall,color=c.inkMuted)
        }}
        item {PButton("新增记忆",{editId=null;text="";editing=true},Modifier.fillMaxWidth().testTag("assistant-memory-add"),enabled=canAct && !state.busy("assistant/memory/add"),leading=PocketIcons.Add)}
        if(state.snapshot?.memories.isNullOrEmpty()) item {AssistantPageNote(if(state.snapshot==null) "记忆尚未读取" else "暂无已保存的记忆")}
        items(state.snapshot?.memories.orEmpty().distinctBy {it.id},key={it.id}) {m -> AssistantCard {
            PText(when(m.scope) {"facts"->"长期事实";"project"->"项目知识";else->"个人偏好"} + if(m.automatic) " · 自动保存" else " · 手动保存",style=Pocket.type.labelSmall,color=c.inkMuted)
            SelectionContainer {PText(m.text,style=Pocket.type.body)}
            PText("来源 · ${m.source ?: "历史记录（来源未记录）"}",style=Pocket.type.bodySmall,color=c.inkMuted)
            m.updatedAtMs?.let {PText("最近保存 · ${assistantTime(it,state.snapshot?.settings?.timezone ?: "Asia/Shanghai")} · ${m.revisions.size.coerceAtLeast(1)} 次记录",style=Pocket.type.caption,color=c.inkFaint)}
            if(m.automatic) AssistantCardAction("撤销自动保存",{act {AssistantActions.memoryUndo(runtime,m.id)}},Modifier.testTag("assistant-memory-undo:${m.id}"),enabled=canAct && !state.busy("assistant/memory/undo"))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PButton("修改",{editId=m.id;text=m.text;editing=true},Modifier.weight(1f).testTag("assistant-memory-edit:${m.id}"),PButtonKind.Secondary,enabled=canAct && !state.busy("assistant/memory/update",m.id))
                PButton("删除",{deleteId=m.id},Modifier.weight(1f).testTag("assistant-memory-delete:${m.id}"),PButtonKind.Secondary,enabled=canAct && !state.busy("assistant/memory/delete",m.id))
            }
        }}
    }
    if(editing) AssistantDialog(if(editId==null) "新增记忆" else "修改记忆",{editing=false},
        confirm={PButton("保存",{val id=editId;act {if(id==null) AssistantActions.memoryAdd(runtime,text) else AssistantActions.memoryEdit(runtime,id,text)};editing=false},Modifier.testTag("assistant-memory-save"),
            enabled=canAct && text.isNotBlank() && text.toByteArray().size<=16384 && !state.busy(if(editId==null) "assistant/memory/add" else "assistant/memory/update",editId),haptic=PocketHaptic.Confirm)},
        dismiss={PButton("取消",{editing=false},Modifier.testTag("assistant-memory-cancel"),PButtonKind.Secondary,haptic=null)}) {
        PTextField(text,{text=it},Modifier.testTag("assistant-memory-text"),placeholder="写下希望助理记住的内容",minLines=3,maxLines=9)
        PText("最多16KiB；保存后以电脑回执为准。",style=Pocket.type.caption,color=c.inkMuted)
    }
    deleteId?.let {id -> AssistantDeleteDialog("删除这条记忆？",{deleteId=null},canAct && !state.busy("assistant/memory/delete",id)) {act {AssistantActions.memoryDelete(runtime,id)};deleteId=null}}
}
