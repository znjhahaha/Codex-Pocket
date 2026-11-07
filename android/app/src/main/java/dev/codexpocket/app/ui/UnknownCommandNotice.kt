package dev.codexpocket.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.PocketRepository
import dev.codexpocket.app.data.PocketState

@Composable
internal fun UnknownCommandNotice(s: PocketState, repo: PocketRepository) {
    val context = LocalContext.current
    val commands = s.unresolvedCommands
    val signature = commands.joinToString { it.id }
    var confirming by rememberSaveable(s.selected?.key?.encoded, signature) { mutableStateOf(false) }
    var inspected by rememberSaveable(s.selected?.key?.encoded, signature) { mutableStateOf(false) }
    LaunchedEffect(s.unknownHistoryInspected) { if (!s.unknownHistoryInspected) { confirming = false; inspected = false } }
    if (commands.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(context.getString(R.string.unknown_action), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            PocketActionRow(context.getString(R.string.delivery_inspect_history), PocketIcons.Search, { repo.inspectUnknownHistory() }, enabled = s.online && !s.busy)
            if (s.unknownHistoryInspected) PocketActionRow(
                context.getString(R.string.delivery_finish_inspection), PocketIcons.Checklist, { confirming = true },
                subtitle = context.getString(R.string.delivery_inspection_ready), enabled = s.online && !s.busy,
            )
        }
    }
    if (confirming && s.unknownHistoryInspected) PocketDialog(
        onDismissRequest = { confirming = false; inspected = false },
        title = { Text(context.getString(R.string.delivery_resolve_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(context.getString(R.string.delivery_resolve_explainer))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        commands.forEach { command ->
                            Text("${context.actionLabel(command.method)} · ${context.stateLabel(command.status)} · ${command.id.takeLast(8)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                PocketActionRow(
                    context.getString(R.string.delivery_history_checked), PocketIcons.Checklist, { inspected = !inspected },
                    selected = inspected,
                    trailing = { PocketCheck(inspected, { inspected = it }, Modifier.semantics { contentDescription = context.getString(R.string.delivery_history_checked) }) },
                )
            }
        },
        confirmButton = {
            PocketButton(
                context.getString(R.string.delivery_confirm_resolution),
                { confirming = false; inspected = false; repo.resolveUnknownAfterInspection() },
                enabled = inspected && s.online && !s.busy,
            )
        },
        dismissButton = { PocketButton(context.getString(R.string.cancel), { confirming = false; inspected = false }, secondary = true) },
    )
}
