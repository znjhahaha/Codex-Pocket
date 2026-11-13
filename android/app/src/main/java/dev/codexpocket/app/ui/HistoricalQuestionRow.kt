package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.NativeQuestionHistory

/** Read-only native history, kept separate from the live question dock. */
@Composable
internal fun HistoricalQuestionRow(history: NativeQuestionHistory, key: String, authorName: String? = null, onOpenSource: (() -> Unit)? = null) {
    var expanded by rememberSaveable(key) {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).testTag("question-history:$key"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        authorName?.let {Text(it,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        PocketTextButton({expanded = !expanded}, Modifier.heightIn(min=48.dp).testTag("question-history-toggle:$key")) {
            Text("已询问 ${history.batch.questions.size} 个问题", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AssistantExpansion(expanded) {Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        onOpenSource?.let {open -> PocketTextButton(open,Modifier.testTag("question-history-source:$key")) {Text("查看对话")}}
        if(history.cancelled) Text("问题已取消", style = MaterialTheme.typography.bodySmall)
        history.batch.questions.forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (question.header.isNotBlank()) Text(question.header, style = MaterialTheme.typography.labelMedium)
                Text(question.prompt, style = MaterialTheme.typography.bodyLarge)
                history.answers[question.id]?.let { answer ->
                    Text("回答：$answer", Modifier.testTag("question-history-answer:${question.id}"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        }}
    }
}
