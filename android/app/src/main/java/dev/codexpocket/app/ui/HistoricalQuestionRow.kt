package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.NativeQuestionHistory

/** Read-only native history, kept separate from the live question dock. */
@Composable
internal fun HistoricalQuestionRow(history: NativeQuestionHistory, key: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).testTag("question-history:$key"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (history.cancelled) "问题已取消" else if (history.answers.isEmpty()) "用户问题 · 尚无原生答案" else "用户问题与回答",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        history.batch.questions.forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (question.header.isNotBlank()) Text(question.header, style = MaterialTheme.typography.labelMedium)
                Text(question.prompt, style = MaterialTheme.typography.bodyLarge)
                history.answers[question.id]?.let { answer ->
                    Text("回答：$answer", Modifier.testTag("question-history-answer:${question.id}"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
