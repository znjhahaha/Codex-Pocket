package dev.codexpocket.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*

/** One collapsed summary for related work. Live human requests also remain in the main timeline. */
@Composable
internal fun AssistantGroupCard(group: AssistantGroup, tasks: List<AssistantTask>, state: AssistantUiState,
    canAct: Boolean, act: (() -> AssistantAction) -> Unit, open: (String,String) -> Unit,
    workspaces: List<AssistantWorkspaceChoice>) {
    var expanded by rememberSaveable(group.id) {mutableStateOf(false)}
    val holder=rememberSaveableStateHolder()
    val waiting=tasks.count {task -> task.status==AssistantTaskStatus.WaitingUser ||
        state.snapshot?.operations.orEmpty().any {it.taskId==task.id && it.phase==AssistantOperationPhase.Waiting && !it.suspended && it.question!=null}}
    val failed=tasks.count {it.status==AssistantTaskStatus.Failed}
    AssistantCard {
        PocketGlassSurface(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().testTag("assistant-group:${group.id}").semantics {contentDescription=if(expanded) "收起任务组" else "展开任务组"},shape=RoundedCornerShape(16.dp),dense=true) {
        Column(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(AssistantIcons.Tasks,null,Modifier.size(18.dp),tint=assistantPalette().accent)
                Text(group.title.ifBlank {"相关任务"},Modifier.weight(1f).padding(horizontal=8.dp),style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(if(expanded) "收起" else "详情",style=MaterialTheme.typography.labelSmall,color=assistantPalette().muted)
            }
            Text(buildList {
                add("${tasks.size} 项分工")
                if(waiting>0) add("$waiting 项待回答")
                if(failed>0) add("$failed 项执行失败")
                if(waiting==0 && failed==0) add(assistantGroupLabel(group.status))
            }.joinToString(" · "),style=MaterialTheme.typography.labelMedium,
                color=if(failed>0) MaterialTheme.colorScheme.error else assistantPalette().muted)
        }
        }
        AssistantExpansion(expanded) {holder.SaveableStateProvider(group.id) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {tasks.forEach {task -> key(task.id) {
                AssistantTaskCard(task,state,canAct,act,open,workspaces)
            }}}
        }}
    }
}
