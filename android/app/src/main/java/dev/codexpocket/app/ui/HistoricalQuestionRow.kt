package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.foundation.layout.*
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
        authorName?.let {PText(it,style=Pocket.type.labelSmall,color=Pocket.colors.inkMuted)}
        PocketTextButton({expanded = !expanded}, Modifier.heightIn(min=48.dp).testTag("question-history-toggle:$key")) {
            PText("已询问 ${history.batch.questions.size} 个问题", style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted)
        }
        AssistantExpansion(expanded) {Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        onOpenSource?.let {open -> PocketTextButton(open,Modifier.testTag("question-history-source:$key")) {PText("查看对话")}}
        if(history.cancelled) PText("问题已取消", style = Pocket.type.bodySmall)
        history.batch.questions.forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (question.header.isNotBlank()) PText(question.header, style = Pocket.type.labelSmall)
                PText(question.prompt, style = Pocket.type.body)
                history.answers[question.id]?.let { answer ->
                    PText("回答：$answer", Modifier.testTag("question-history-answer:${question.id}"), style = Pocket.type.body)
                }
            }
        }
        }}
    }
}
