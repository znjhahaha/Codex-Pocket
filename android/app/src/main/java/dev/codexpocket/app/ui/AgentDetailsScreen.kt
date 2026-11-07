package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native child identities only. Viewing a task never starts or resumes an agent. */
@Composable
internal fun AgentDetailsScreen(item: TimelineItem, onBack: () -> Unit, availableIds: Set<String>, onOpen: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val activity by produceState<PresentedActivity?>(null, item) {
        value = withContext(Dispatchers.Default) { NativeActivityPresentation.describe(item) }
    }
    PocketScreen("子任务", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).testTag("agent-details"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (activity == null) PocketProgressStatus("正在读取子任务")
            activity?.let { value ->
                if (value.agents.isEmpty()) Text("原生记录未提供子会话身份", color = c.onSurfaceVariant)
                value.agents.forEach { agent -> key(agent.threadId) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(agent.name.ifBlank { "子任务 ${agent.threadId.take(12)}" }, style = MaterialTheme.typography.titleMedium)
                        Text(activityStatusLabel(agent.status), color = if (agent.status == ActivityStatus.Failed) c.error else c.onSurfaceVariant)
                        SelectionContainer { Text(agent.threadId, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
                        if (agent.message.text.isNotBlank()) MarkdownText(agent.message.text, "agent:${item.key}:${agent.threadId}")
                        if (agent.threadId in availableIds) PocketTextButton({ onOpen(agent.threadId) }, modifier = Modifier.testTag("open-agent:${agent.threadId}")) { Text("查看对话") }
                        else Text("子会话尚未出现在当前电脑的会话目录中", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                        HorizontalDivider(color = c.outlineVariant.copy(alpha = .5f))
                    }
                } }
                if (value.output.text.isNotBlank()) {
                    Text("任务说明", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                    MarkdownText(value.output.text, "agent-prompt:${item.key}")
                }
            }
        }
    }
}
