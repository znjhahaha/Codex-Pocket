package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
/** The selected location, for screens that say whose data they show. */
internal val LocalExecutionLocation = staticCompositionLocalOf<ExecutionTarget?> { null }

@Composable
internal fun ExecutionLocationsRoot(locations: ExecutionLocations) {
    val repository by locations.active.collectAsStateWithLifecycle()
    val catalog by locations.directory.collectAsStateWithLifecycle()
    val error by locations.error.collectAsStateWithLifecycle()
    val progress by locations.progress.collectAsStateWithLifecycle()
    val notice by locations.notice.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var addingSsh by rememberSaveable { mutableStateOf(false) }
    var thisPhone by rememberSaveable { mutableStateOf(false) }
    val pages = rememberSaveableStateHolder()
    val current = repository
    if (current == null) {
        PocketTheme("system") { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error != null) PText(error!!, Modifier.padding(24.dp)) else PocketProgressStatus("正在准备执行位置")
        } }
        return
    }
    val state by current.state.collectAsStateWithLifecycle()
    PocketTheme(state.theme) { PocketOverlayHost {
        CompositionLocalProvider(LocalExecutionLocationPicker provides { choosing = true }, LocalExecutionLocation provides catalog.selected) {
            pages.SaveableStateProvider(catalog.selectedId ?: "unpaired") { PocketRoot(state, current) }
        }
        if (choosing) PocketSheet("执行位置", { choosing = false }, subtitle = "项目、对话与用量跟随所选位置") {
            ExecutionLocationChoices(catalog, state.online, { id -> locations.select(id); choosing = false },
                { locations.addComputer(); choosing = false }, { choosing = false; addingSsh = true },
                onThisPhone = if (locations.thisPhone.available) ({ choosing = false; thisPhone = true }) else null)
        }
        if (thisPhone) {
            val account by locations.thisPhone.account.collectAsStateWithLifecycle()
            val added = catalog.targets.any { it.kind == ExecutionTargetKind.ThisPhone }
            val projects by locations.thisPhone.projects.collectAsStateWithLifecycle()
            val access by locations.thisPhone.access.collectAsStateWithLifecycle()
            val applying by locations.thisPhone.applying.collectAsStateWithLifecycle()
            LaunchedEffect(added) { if (added) { locations.thisPhone.refreshAccount(); locations.thisPhone.refreshProjects() } }
            ThisPhoneSheet(added, account, progress, { thisPhone = false }, locations::connectThisPhone,
                locations.thisPhone::signInWithChatGpt, locations.thisPhone::refreshAccount,
                onStop = { locations.stopThisPhone(); thisPhone = false },
                access = access, applying = applying, onUseProvider = locations.thisPhone::useProvider,
                onSaveProvider = locations.thisPhone::saveProvider, onRemoveProvider = locations.thisPhone::removeProvider,
                onSignOut = locations.thisPhone::signOut,
                projects = projects, onImport = locations.thisPhone::importProject, onExport = locations.thisPhone::exportProject)
        }
        // Codex on this phone can do nothing until it has an account or a service to use; say so once instead of failing in a conversation.
        var askedForAccess by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(catalog.selected?.kind, state.online) {
            if (!askedForAccess && state.online && catalog.selected?.kind == ExecutionTargetKind.ThisPhone && !locations.thisPhone.hasAccess) {
                askedForAccess = true; thisPhone = true
            }
        }
        if (addingSsh) SshLocationSheet(progress, { if (progress == null) addingSsh = false },
            onConnect = { label, config -> locations.connectSsh(label, config) })
        LaunchedEffect(progress, catalog.selectedId) {
            if (addingSsh && progress == null && catalog.selected?.kind == ExecutionTargetKind.LinuxSsh && error == null) addingSsh = false
        }
        if (notice != null && error == null) PocketDialog(onDismissRequest = locations::dismissNotice,
            title = { PText("SSH 主机已添加") }, text = { PText(notice!!) },
            confirmButton = { PocketButton("知道了", locations::dismissNotice) })
        if (error != null) PocketDialog(onDismissRequest = locations::dismissError,
            title = { PText("执行位置") }, text = { PText(error!!) },
            confirmButton = { PocketButton("知道了", locations::dismissError) })
    } }
}

@Composable
internal fun ExecutionLocationChoices(catalog: ExecutionTargetDirectory, currentOnline: Boolean,
    onSelect: (String) -> Unit, onAddComputer: () -> Unit, onAddSsh: () -> Unit = {}, onThisPhone: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        catalog.targets.forEach { target ->
            val selected = target.id == catalog.selectedId
            PSurface(Modifier.fillMaxWidth().testTag("execution-location:${target.id}"),
                shape = PocketOval, color = if (selected) Pocket.colors.accentSoft else Pocket.colors.sunken) {
                Row(Modifier.clickable { onSelect(target.id) }.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PIcon(if (target.kind == ExecutionTargetKind.LinuxSsh) PocketIcons.Terminal else PocketIcons.Computer, null)
                    Column(Modifier.weight(1f)) {
                        PText(target.label, style = Pocket.type.titleSmall)
                        PText(when (target.kind) {
                            ExecutionTargetKind.Computer -> "电脑"
                            ExecutionTargetKind.LinuxSsh -> "Linux · SSH"
                            ExecutionTargetKind.ThisPhone -> "此手机 · 本地运行"
                        }, style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
                    }
                    PText(if (selected && currentOnline) "已连接" else if (selected) "当前" else "切换", style = Pocket.type.labelSmall)
                }
            }
        }
        PocketButton("连接另一台电脑", onAddComputer, Modifier.fillMaxWidth().testTag("execution-add-computer"), secondary = true)
        PocketButton("添加 SSH 主机", onAddSsh, Modifier.fillMaxWidth().testTag("execution-add-ssh"), secondary = true)
        // Offered only by a package that carries the on-device runtime.
        if (onThisPhone != null) PocketButton(if (catalog.targets.any { it.kind == ExecutionTargetKind.ThisPhone }) "此手机的账号与模型服务" else "在此手机上运行",
            onThisPhone, Modifier.fillMaxWidth().testTag("execution-this-phone"), secondary = true)
    }
}
