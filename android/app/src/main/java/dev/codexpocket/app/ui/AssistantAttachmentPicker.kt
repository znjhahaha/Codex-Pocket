package dev.codexpocket.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal class AssistantAttachmentDraft(private val context:android.content.Context,val key:String) {
    private val preferences=context.getSharedPreferences("assistant-drafts-v2",0)
    var files by mutableStateOf(read("files"));private set
    var pendingId by mutableStateOf(preferences.getString("attachment-pending:$key",null));private set
    var sentText by mutableStateOf(preferences.getString("attachment-text:$key",null));private set
    private fun read(kind:String)=runCatching {pocketJson.parseToJsonElement(preferences.getString("attachment-$kind:$key","[]")!!).jsonArray.map {LocalAttachment.from(it.jsonObject)}}.getOrDefault(emptyList())
    private fun save() {preferences.edit().putString("attachment-files:$key",JsonArray(files.map {it.json()}).toString()).commit()}
    fun add(values:List<LocalAttachment>) {require(files.size+values.size<=4);files=files+values;save()}
    fun remove(id:String) {files=files.filterNot {it.id==id};save()}
    fun sent(id:String,text:String) {pendingId=id;sentText=text;preferences.edit().putString("attachment-pending:$key",id).putString("attachment-text:$key",text)
        .putString("attachment-sent:$key",JsonArray(files.map {it.json()}).toString()).commit()}
    fun complete(status:String) {
        if(status=="accepted") {val ids=read("sent").map {it.id}.toSet();files=files.filterNot {it.id in ids};save()}
        if(status in setOf("accepted","rejected")) {pendingId=null;sentText=null;preferences.edit().remove("attachment-pending:$key").remove("attachment-text:$key").remove("attachment-sent:$key").commit()}
    }
    fun newGoal() {files=emptyList();save();complete("rejected")}
    fun adoptPreparingDraft(previous:String) {
        if(files.isNotEmpty() || pendingId!=null) return
        val old=AssistantAttachmentDraft(context,previous)
        if(old.pendingId==null && old.files.isNotEmpty()) {files=old.files;save();old.newGoal()}
    }
}
@Composable internal fun rememberAssistantAttachments(key:String):AssistantAttachmentDraft {
    val context=LocalContext.current
    return remember(context,key) {AssistantAttachmentDraft(context,key)}
}
@Composable internal fun AssistantAttachmentPicker(draft:AssistantAttachmentDraft,tag:String,enabled:Boolean=true) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var loading by remember {mutableStateOf(false)};var issue by remember {mutableStateOf<String?>(null)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {uris->
        if(uris.isNotEmpty()) scope.launch {loading=true;issue=null
            try {draft.add(copyAssistantAttachments(context,uris,4-draft.files.size))}
            catch(e:kotlinx.coroutines.CancellationException) {throw e}
            catch(e:Exception) {issue=e.message ?: "无法添加附件，草稿已保留"}
            finally {loading=false}
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        draft.files.forEach {file->Row(Modifier.fillMaxWidth()) {
            Text(file.name,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            TextButton({draft.remove(file.id)},enabled=enabled && !loading) {Text("移除")}
        }}
        TextButton({launcher.launch(arrayOf("image/png","image/jpeg","image/webp","text/*","application/json","application/octet-stream"))},
            enabled=enabled && !loading && draft.files.size<4,modifier=Modifier.testTag("assistant-attach:$tag")) {
            Icon(Icons.Outlined.AttachFile,null);Text(if(loading) "正在读取附件…" else "添加图片或文本文件")
        }
        issue?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }
}
