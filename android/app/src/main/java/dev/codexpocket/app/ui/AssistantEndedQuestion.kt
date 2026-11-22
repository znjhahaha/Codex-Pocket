package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*

@Composable internal fun AssistantEndedQuestion(input:AssistantHeldInput) {
    val c=Pocket.colors
    Column(Modifier.fillMaxWidth().testTag("assistant-ended-question:${input.operationId}:${input.callId}"),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        PText(input.title,style=Pocket.type.titleSmall)
        SelectionContainer {PText(input.text,style=Pocket.type.body)}
        PText(input.statusText,style=Pocket.type.bodySmall,color=c.inkMuted)
        if(input.resolution==AssistantInputResolution.Unresolved && input.answer!=AssistantInputAnswer.Unknown) {
            PText("结束原请求并确认结果后，可以再发一条消息继续。",style=Pocket.type.bodySmall,color=c.inkMuted)
        }
    }
}
