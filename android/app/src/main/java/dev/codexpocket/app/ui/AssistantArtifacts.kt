package dev.codexpocket.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import androidx.core.content.FileProvider
import java.io.File

/** Provider must validate paired computer, original artifact/version and SHA-256,
 * and return an immutable cache file under FileProvider's capture directory. */
interface AssistantArtifactSource {
    val ownerKey:String
    suspend fun file(artifact:AssistantArtifact):File
}
val LocalAssistantArtifacts=staticCompositionLocalOf<AssistantArtifactSource?> {null}


@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AssistantArtifactCard(artifact:AssistantArtifact,snapshot:AssistantSnapshot,navigate:(String)->Unit,open:(String,String)->Unit) {
    val c=Pocket.colors
    AssistantCard(Modifier.testTag("assistant-artifact:${artifact.id}")) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            PIcon(when(artifact.category) {"link"->PocketIcons.Link;"image"->PocketIcons.Image;"code"->PocketIcons.Code;else->PocketIcons.Description},null,Modifier.size(18.dp),c.inkMuted)
            PText(artifact.name,Modifier.weight(1f),style=Pocket.type.titleSmall,maxLines=3,overflow=TextOverflow.Ellipsis)
        }
        PText(when(artifact.kind) {"text"->"文字结果";"link"->"外部链接";"file"->"文件引用";else->"成果"}+
            (artifact.createdAtMs?.let {" · ${assistantTime(it,snapshot.settings?.timezone ?: "Asia/Shanghai")}"} ?: ""),style=Pocket.type.caption,color=c.inkMuted)
        if(artifact.kind=="file") PText(when(artifact.availability) {
            "available"->"文件快照已保留${artifact.sizeBytes?.let {" · ${it/1024} KiB"}.orEmpty()}"
            "missing"->"原文件缺失，未能保存快照"
            "unavailable"->"未通过文件范围或内容核验"
            else->"文件内容尚未核验，可在原对话查看引用"
        },style=Pocket.type.bodySmall,color=if(artifact.availability in setOf("missing","unavailable")) c.warning else c.inkMuted)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            AssistantCardAction("查看成果",{navigate("Artifact:${artifact.id}")},Modifier.testTag("assistant-artifact-open:${artifact.id}"))
            artifact.taskId?.let {task -> AssistantCardAction("来源任务",{navigate("Task:$task")})}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AssistantArtifactDetails(artifact:AssistantArtifact,state:AssistantUiState,canAct:Boolean,
    act:(()->AssistantAction)->Unit,navigate:(String)->Unit,open:(String,String)->Unit) {
    val snapshot=state.snapshot ?: return
    val context=LocalContext.current
    val source=LocalAssistantArtifacts.current
    val scope=rememberCoroutineScope()
    var loading by remember(artifact.id,source?.ownerKey) {mutableStateOf(false)}
    var downloaded by remember(artifact.id,source?.ownerKey) {mutableStateOf<File?>(null)}
    var feedback by rememberSaveable(artifact.id,source?.ownerKey) {mutableStateOf<String?>(null)}
    val currentSource=rememberUpdatedState(source)
    val mayDownload=artifact.kind=="file" && artifact.availability=="available" && source!=null
    fun download(done:(File)->Unit) {
        val selected=source ?: return
        scope.launch {
            loading=true;feedback=null
            try {
                val file=downloaded ?: selected.file(artifact)
                if(currentSource.value?.ownerKey!=selected.ownerKey) return@launch
                downloaded=file;done(file)
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {feedback="下载失败，已保留电脑上的成果快照，可重试。"}
            finally {loading=false}
        }
    }
    fun fileUri(file:File)=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
    val saveFile=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(artifact.mimeType)) {uri ->
        val file=downloaded
        if(uri!=null && file!=null) scope.launch {
            feedback=runCatching {withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {output ->file.inputStream().use {it.copyTo(output)}} ?: error("Cannot open destination")
            };"已保存"}.getOrElse {"保存失败，请重新选择位置"}
        }
    }
    val currentArtifact=rememberUpdatedState(artifact)
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {uri ->
        if(uri!=null) scope.launch {
            val selected=currentArtifact.value
            feedback=runCatching {withContext(Dispatchers.IO) {
                val body=selected.content ?: error("成果内容不可用")
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {it.write(body)} ?: error("无法打开保存位置")
            };"已保存"}.getOrElse {"保存失败，请重新选择位置"}
        }
    }
    Column(Modifier.fillMaxWidth().testTag("assistant-artifact-preview:${artifact.id}"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        val c=Pocket.colors
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            SelectionContainer {PText(artifact.name,style=Pocket.type.titleLarge)}
            PText("版本 · ${artifact.version.take(12)}",style=Pocket.type.caption,color=c.inkMuted)
        }
        if(artifact.canExportText) AssistantCard {MarkdownText(artifact.content.orEmpty(),"assistant:artifact:${artifact.id}")}
        else SelectionContainer {PText(artifact.uri ?: "此成果暂不可预览",style=Pocket.type.body,color=if(artifact.uri==null) c.inkMuted else c.ink)}
        if(artifact.kind=="file" && artifact.availability!="available") PText("这是原任务给出的文件引用，尚未获取内容。保存和分享文件需先通过电脑文件权限核验。",style=Pocket.type.bodySmall,color=c.inkMuted)
        if(loading) PLinearProgress(null)
        if(artifact.kind=="file" && artifact.availability=="available" && source==null) PText("电脑已保存文件快照，当前客户端尚未接通下载。",style=Pocket.type.bodySmall,color=c.inkMuted)
        if(mayDownload) FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            AssistantCardAction("预览",{download {file ->
                runCatching {context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {setDataAndType(fileUri(file),artifact.mimeType);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"预览成果"))}.onFailure {feedback="未找到可预览此类型的应用，可保存后打开。"}
            }},Modifier.testTag("assistant-file-preview"),enabled=!loading)
            AssistantCardAction("保存",{download {saveFile.launch(artifact.name.substringAfterLast('/').substringAfterLast('\\').ifBlank {"成果"})}},Modifier.testTag("assistant-file-save"),enabled=!loading)
            AssistantCardAction("分享",{download {file ->
                runCatching {context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type=artifact.mimeType;putExtra(Intent.EXTRA_STREAM,fileUri(file));addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"分享成果"))}.onFailure {feedback="无法打开分享"}
            }},Modifier.testTag("assistant-file-share"),enabled=!loading)
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            if(artifact.canExportText) AssistantCardAction("保存",{save.launch("成果-${artifact.version.take(8)}.md")},Modifier.testTag("assistant-artifact-save"))
            if(artifact.canExportText || artifact.safeWebUri!=null) AssistantCardAction("分享",{
                feedback=runCatching {
                    val intent=Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,artifact.content ?: artifact.safeWebUri);putExtra(Intent.EXTRA_TITLE,artifact.name)}
                    context.startActivity(Intent.createChooser(intent,"分享成果"));null
                }.getOrElse {"暂时无法打开分享"}
            },Modifier.testTag("assistant-artifact-share"))
            artifact.safeWebUri?.let {url -> AssistantCardAction("打开链接",{runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)))}.onFailure {feedback="没有可打开此链接的应用"}})}
            artifact.threadId?.let {thread -> AssistantCardAction("原对话",{open(snapshot.runtimeId.orEmpty(),thread)})}
            artifact.taskId?.let {id -> AssistantCardAction("返回来源任务",{navigate("Task:$id")},Modifier.testTag("assistant-artifact-source-task"))}
        }
        feedback?.let {PText(it,style=Pocket.type.bodySmall,color=c.inkMuted)}
        artifact.taskId?.let {id ->
            snapshot.tasks.firstOrNull {it.id==id}?.let {task -> AssistantTaskFollowup(task,state,canAct && artifact.availability in setOf("available","external"),act,"基于成果「${artifact.name}」（版本 ${artifact.version}）继续。",artifact)}
        }
    }
}
