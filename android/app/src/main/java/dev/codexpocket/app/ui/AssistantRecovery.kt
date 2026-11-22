package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*

@Composable internal fun AssistantHandoffRecoveryCard(operation:AssistantOperation,runtime:String,state:AssistantUiState,
    canAct:Boolean,act:(()->AssistantAction)->Unit) {
    val c=Pocket.colors
    val unconfirmed="assistant/resume" in state.unconfirmedActions
    val pending=state.busy("assistant/resume",operation.id)
    val enabled=canAct && runtime.isNotBlank() && state.snapshot?.fresh==true && state.readError==null &&
        !unconfirmed && !pending
    Column(Modifier.fillMaxWidth().testTag("assistant-handoff-recovery:${operation.id}"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        PText("这条消息尚未开始",style=Pocket.type.titleSmall)
        PText("电脑保留了原消息。恢复后继续处理，并保留原操作记录。",style=Pocket.type.bodySmall,color=c.inkMuted)
        if(unconfirmed) PText("恢复结果尚未确认，请勿再次提交",style=Pocket.type.bodySmall,color=c.danger)
        PButton(if(unconfirmed) "等待恢复结果" else if(pending) "正在恢复原消息" else "恢复这条消息",{act {AssistantActions.resumeHandoff(runtime,operation.id)}},
            Modifier.fillMaxWidth().testTag("assistant-handoff-resume:${operation.id}"),PButtonKind.Secondary,enabled=enabled,haptic=PocketHaptic.Confirm)
    }
}

@Composable internal fun AssistantRecoveryCard(request:AssistantRecoveryRequest,runtime:String,state:AssistantUiState,
    canAct:Boolean,act:(()->AssistantAction)->Unit) {
    val c=Pocket.colors
    Column(Modifier.fillMaxWidth().testTag("assistant-recovery:${request.operationId}:${request.requestId}"),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        PText("有一项操作需要恢复",style=Pocket.type.titleSmall)
        PText("电脑保留了一项尚未结束的请求。结束原请求并核对结果后，可以继续对话；不会重新执行。",style=Pocket.type.bodySmall,color=c.inkMuted)
        if("assistant/respond" in state.unconfirmedActions) PText("结果尚未确认，不能重复发送",
            Modifier.testTag("assistant-recovery-unconfirmed"),style=Pocket.type.bodySmall,color=c.danger)
        PButton(if("assistant/respond" in state.unconfirmedActions) "等待原操作结果" else if(state.busy("assistant/respond",request.operationId)) "正在核对结果" else "结束原操作",
            {act {AssistantActions.approve(runtime,request.operationId,request.requestId,false)}},
            Modifier.fillMaxWidth().testTag("assistant-recovery-end:${request.operationId}:${request.requestId}"),PButtonKind.Secondary,
            enabled=canAct && runtime.isNotBlank() && !state.busy("assistant/respond",request.operationId),haptic=PocketHaptic.Confirm)
    }
}
