package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*

@Composable internal fun AssistantEndedQuestion(input:AssistantHeldInput) {
    Column(Modifier.fillMaxWidth().testTag("assistant-ended-question:${input.operationId}:${input.callId}"),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(input.title,style=MaterialTheme.typography.titleSmall)
        SelectionContainer {Text(input.text,style=MaterialTheme.typography.bodyMedium)}
        Text(input.statusText,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(input.resolution==AssistantInputResolution.Unresolved && input.answer!=AssistantInputAnswer.Unknown) {
            Text("结束原请求并确认结果后，可以再发一条消息继续。",style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
