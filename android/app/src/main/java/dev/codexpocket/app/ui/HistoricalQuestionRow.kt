package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.NativeQuestionHistory
import dev.codexpocket.app.data.QuestionAnswerStore

/** Read-only native history, kept separate from the live question dock. */
@Composable
internal fun HistoricalQuestionRow(history: NativeQuestionHistory, key: String, authorName: String? = null, onOpenSource: (() -> Unit)? = null) {
    val binding = LocalThreadLinkBinding.current
    val context = LocalContext.current
    val store = remember(context) { QuestionAnswerStore(context) }
    val shown = if (binding?.origin != null) {
        val state by binding.repository.state.collectAsState()
        val receipts = (store.all().associateBy { it.commandId } + state.questionDeliveries.associateBy { it.commandId }).values.toList()
        history.withSubmitted(binding.origin, receipts)
    } else history
    var expanded by rememberSaveable(key) {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).testTag("question-history:$key"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        authorName?.let {PText(it,style=Pocket.type.labelSmall,color=Pocket.colors.inkMuted)}
        PocketTextButton({expanded = !expanded}, Modifier.heightIn(min=48.dp).testTag("question-history-toggle:$key")) {
            PText("已询问 ${history.batch.questions.size} 个问题", style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted)
        }
        AssistantExpansion(expanded) {Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        onOpenSource?.let {open -> PocketTextButton(open,Modifier.testTag("question-history-source:$key")) {PText("查看对话")}}
        if(shown.cancelled) PText("问题已取消", style = Pocket.type.bodySmall)
        if(shown.locallySubmitted) PText("本机已提交 · 等待原生确认", style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
        shown.batch.questions.forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (question.header.isNotBlank()) PText(question.header, style = Pocket.type.labelSmall)
                PText(question.prompt, style = Pocket.type.body)
                shown.answers[question.id]?.let { answer ->
                    PText((if (shown.locallySubmitted) "已提交的回答：" else "回答：") + answer,
                        Modifier.testTag("question-history-answer:${question.id}"), style = Pocket.type.body)
                }
            }
        }
        }}
    }
}
