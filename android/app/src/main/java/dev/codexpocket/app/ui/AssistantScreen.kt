package dev.codexpocket.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.serialization.json.*

@Composable
fun AssistantScreen(state: AssistantUiState, models: List<NativeModel>, runtimes: List<AssistantRuntimeChoice>, workspaces: List<AssistantWorkspaceChoice>,
    onAction: (AssistantAction) -> Unit, onOpenThread: (String, String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, nowMs: Long = System.currentTimeMillis(), connected: Boolean = true,
    onProjectSelected: (AssistantWorkspaceChoice?) -> Unit = {}) {
    AssistantWorkspace(state,models,runtimes,workspaces,onAction,onOpenThread,onBack,modifier,nowMs,connected,onProjectSelected)
}

internal fun AssistantUiState.busy(method: String, id: String? = null) =
    (method in unconfirmedActions && !(snapshot?.protocolVersion==2 && method=="assistant/respond")) || method in pendingActions || (id!=null && "$method:$id" in pendingActions)
/** One short phrase for the readiness dot; the same wording wherever the assistant's state is named. */
internal fun assistantStatus(state: AssistantUiState): String = when {
    state.loading -> "正在读取状态"; state.error != null -> "暂时无法确认状态"; state.snapshot?.enabled==null -> "状态尚未读取"
    state.snapshot.preparationStatus == "idle" -> "发送时启动"
    state.snapshot.preparationStatus in setOf("starting","preparing") || state.busy("assistant/prepare") -> "正在启动助理"
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

/** A solid reading card on the assistant's pages. The hairline keeps it legible on the light page. */
@Composable internal fun AssistantCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c=Pocket.colors
    PCard(modifier,color=c.surface,border=BorderStroke(1.dp,c.line.copy(alpha=if(c.dark) .5f else .7f)),content=content)
}
@Composable internal fun AssistantBadge(text: String, failure: Boolean = false, tone: PTone = if(failure) PTone.Danger else PTone.Neutral) {
    PBadge(text,tone=tone)
}

/** The original request, answered in place. Approving, refusing and answering go out under the request's own identity. */
@Composable internal fun AssistantHumanQuestion(op: AssistantOperation, runtime:String, state:AssistantUiState, canAct:Boolean, act:(()->AssistantAction)->Unit) {
    val q=op.question ?: return
    val c=Pocket.colors
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
        } else PText(if(op.phase == AssistantOperationPhase.Completed) "已确认" else "确认已结束", style=Pocket.type.labelSmall, color=c.inkMuted)
        return
    }
    val allowed=canAct && state.snapshot?.fresh!=false && state.readError==null && op.phase==AssistantOperationPhase.Waiting && !op.suspended && !state.busy("assistant/respond",op.id)
    AssistantCard(Modifier.testTag("assistant-question:${op.id}")) {
        AssistantBadge(if(q.kind==AssistantQuestionKind.Approval) "需要你的审批" else "需要你的回答",tone=PTone.Warning)
        if(op.suspended || op.phase!=AssistantOperationPhase.Waiting) PText("当前操作已暂停或状态待确认，请先刷新。",style=Pocket.type.bodySmall,color=c.inkMuted)
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
                    else PText("问题格式尚未识别，请在原对话中回答。",style=Pocket.type.bodySmall,color=c.inkMuted)
                    validationError?.let {PText(it,style=Pocket.type.bodySmall,color=c.danger)}
                } else {
                    MarkdownText(q.text,"assistant:$runtime:question:${op.id}:${q.requestId}")
                    PTextField(answer,{answer=it},Modifier.testTag("assistant-answer:${op.id}"),label="你的回答",enabled=allowed,minLines=2,maxLines=6)
                    PButton("提交回答",{act {AssistantActions.respond(runtime,op.id,q.requestId,answer)}},Modifier.fillMaxWidth().testTag("assistant-respond:${op.id}"),
                        enabled=allowed && answer.isNotBlank() && answer.toByteArray().size<=65536,haptic=PocketHaptic.Confirm)
                }
            }
            AssistantQuestionKind.Approval -> {
                val details=remember(q.text) {runCatching {pocketJson.parseToJsonElement(q.text) as? JsonObject}.getOrNull()}
                val permissions=details?.get("permissions")
                val permissionLines=permissions?.let {assistantPermissionLines(it)}
                if(details==null) MarkdownText(q.text,"assistant:$runtime:question:${op.id}:${q.requestId}")
                else {
                    details.string("command").takeIf(String::isNotBlank)?.let {command -> SelectionContainer {
                        PText(command,Modifier.fillMaxWidth().clip(PocketRadii.chip).background(c.fill).padding(horizontal=10.dp,vertical=8.dp),style=Pocket.type.mono)
                    }}
                    details.string("reason").takeIf(String::isNotBlank)?.let {PText(it,style=Pocket.type.body)}
                    details.array("changes").map {it.asObject().string("path")}.filter(String::isNotBlank).distinct().forEach {PText(it,style=Pocket.type.bodySmall,color=c.inkMuted)}
                    details.string("cwd").takeIf(String::isNotBlank)?.let {PText("工作目录 · $it",style=Pocket.type.bodySmall,color=c.inkMuted)}
                    if(permissions!=null) {
                        if(permissionLines==null) PText("此权限范围尚未识别，请在原对话核对并批准。",style=Pocket.type.bodySmall,color=c.warning)
                        else permissionLines.forEach {PText(it,style=Pocket.type.bodySmall,color=c.inkMuted)}
                    }
                }
                val decisions=details?.get("availableDecisions") as? JsonArray
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    PButton("同意这次",{act {AssistantActions.approve(runtime,op.id,q.requestId,true)}},Modifier.weight(1f).testTag("assistant-approve:${op.id}"),
                        enabled=allowed && (permissions==null || permissionLines!=null) && (decisions==null || JsonPrimitive("accept") in decisions),haptic=PocketHaptic.Confirm)
                    AssistantQuietButton("拒绝",{act {AssistantActions.approve(runtime,op.id,q.requestId,false)}},Modifier.weight(1f).testTag("assistant-deny:${op.id}"),
                        enabled=allowed && (decisions==null || JsonPrimitive("decline") in decisions || JsonPrimitive("cancel") in decisions),haptic=PocketHaptic.Reject)
                }
            }
            AssistantQuestionKind.Unknown -> PText("问题类型尚未识别，请在原对话中确认。",style=Pocket.type.bodySmall,color=c.inkMuted)
        }
    }
}
