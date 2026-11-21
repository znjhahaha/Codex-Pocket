package dev.codexpocket.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.codexpocket.app.data.ExecutionLocations
import dev.codexpocket.wire.*

internal val LocalExecutionLocationPicker = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun ExecutionLocationsRoot(locations: ExecutionLocations) {
    val repository by locations.active.collectAsStateWithLifecycle()
    val catalog by locations.directory.collectAsStateWithLifecycle()
    val error by locations.error.collectAsStateWithLifecycle()
    val progress by locations.progress.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var addingSsh by rememberSaveable { mutableStateOf(false) }
    val pages = rememberSaveableStateHolder()
    val current = repository
    if (current == null) {
        PocketTheme("system") { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error != null) Text(error!!, Modifier.padding(24.dp)) else PocketProgressStatus("正在准备执行位置")
        } }
        return
    }
    val state by current.state.collectAsStateWithLifecycle()
    PocketTheme(state.theme) { PocketOverlayHost {
        CompositionLocalProvider(LocalExecutionLocationPicker provides { choosing = true }) {
            pages.SaveableStateProvider(catalog.selectedId ?: "unpaired") { PocketRoot(state, current) }
        }
        if (choosing) PocketSheet("执行位置", { choosing = false }, subtitle = "项目、对话与用量跟随所选位置") {
            ExecutionLocationChoices(catalog, state.online, { id -> locations.select(id); choosing = false },
                { locations.addComputer(); choosing = false }, { choosing = false; addingSsh = true })
        }
        if (addingSsh) SshLocationSheet(progress, { if (progress == null) addingSsh = false },
            onConnect = { label, config -> locations.connectSsh(label, config) })
        LaunchedEffect(progress, catalog.selectedId) {
            if (addingSsh && progress == null && catalog.selected?.kind == ExecutionTargetKind.LinuxSsh && error == null) addingSsh = false
        }
        if (error != null) PocketDialog(onDismissRequest = locations::dismissError,
            title = { Text("执行位置") }, text = { Text(error!!) },
            confirmButton = { PocketButton("知道了", locations::dismissError) })
    } }
}

@Composable
internal fun ExecutionLocationChoices(catalog: ExecutionTargetDirectory, currentOnline: Boolean,
    onSelect: (String) -> Unit, onAddComputer: () -> Unit, onAddSsh: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        catalog.targets.forEach { target ->
            val selected = target.id == catalog.selectedId
            Surface(Modifier.fillMaxWidth().testTag("execution-location:${target.id}"),
                shape = PocketOval, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.clickable { onSelect(target.id) }.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (target.kind == ExecutionTargetKind.LinuxSsh) PocketIcons.Terminal else PocketIcons.Computer, null)
                    Column(Modifier.weight(1f)) {
                        Text(target.label, style = MaterialTheme.typography.titleSmall)
                        Text(when (target.kind) {
                            ExecutionTargetKind.Computer -> "电脑"
                            ExecutionTargetKind.LinuxSsh -> "Linux · SSH"
                            ExecutionTargetKind.ThisPhone -> "此手机 · 本地运行"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (selected && currentOnline) "已连接" else if (selected) "当前" else "切换", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        PocketButton("连接另一台电脑", onAddComputer, Modifier.fillMaxWidth().testTag("execution-add-computer"), secondary = true)
        PocketButton("添加 SSH 主机", onAddSsh, Modifier.fillMaxWidth().testTag("execution-add-ssh"), secondary = true)
    }
}
