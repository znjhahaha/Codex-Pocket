package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*

private enum class CreationPage { Form, Projects }

private fun pathKeyboard(search: Boolean = false) = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
    imeAction = if (search) ImeAction.Search else ImeAction.Done)

/** Presentation state is independent of the advanced section's visibility. */
@Composable
internal fun ProjectCreationForm(
    s: PocketState,
    onDismiss: () -> Unit,
    onCreate: (SessionMode, String, String?, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val locationKind = LocalExecutionLocation.current?.kind
    val focus = LocalFocusManager.current
    val c = Pocket.colors
    val preferences = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var advanced by rememberSaveable(s.computerId) { mutableStateOf(false) }
    var mode by rememberSaveable(s.computerId) { mutableStateOf(initialCreationMode(s)) }
    val available = s.creationRuntimes(mode)
    var runtimeId by rememberSaveable(s.computerId, mode) {
        mutableStateOf(available.firstOrNull { it.id == preferences.getString("newRuntime", null) }?.id
            ?: available.firstOrNull()?.id)
    }
    val creating = s.actionStatus["create"]?.loading == true
    LaunchedEffect(mode, available.map { it.id }, creating) {
        if (!creating && available.none { it.id == runtimeId }) runtimeId = available.firstOrNull()?.id
    }
    var existingPath by rememberSaveable(s.computerId) { mutableStateOf(preferences.getString("newCwd", "").orEmpty()) }
    var newPath by rememberSaveable(s.computerId) { mutableStateOf("") }
    var createDirectory by rememberSaveable(s.computerId) { mutableStateOf(false) }
    var query by rememberSaveable(s.computerId) { mutableStateOf("") }
    var pickingProject by rememberSaveable(s.computerId) { mutableStateOf(false) }
    var editingPath by rememberSaveable(s.computerId) { mutableStateOf(false) }
    val projects = remember(s.chats, s.computerId) {
        s.chats.asSequence().filter { it.key.computerId == s.computerId }
            .map { it.cwd }.filter(String::isNotBlank).distinctBy(::nativeProjectPathKey).toList()
    }
    val path = if (createDirectory) newPath else existingPath
    val canCreate = !creating && s.online && (!createDirectory || path.isNotBlank()) &&
        (available.any { it.id == runtimeId } || s.canPrepareRuntime(mode))

    val motion = rememberPocketMotion()
    // The form is put away while the project list is over it; where it was scrolled to is kept here.
    val formScroll = rememberScrollState()
    val leaveProjects: (() -> Unit)? = if (pickingProject) { { pickingProject = false } } else null
    PocketPageOverlay("新建对话", { if (pickingProject) pickingProject = false else onDismiss() }) {
        // The project list is a page of its own: pushed over the form, and left the way every page is left.
        PStage(if (pickingProject) CreationPage.Projects else CreationPage.Form, depth = { it.ordinal },
            back = CreationPage.Form.takeIf { pickingProject }, onBack = leaveProjects) { page ->
            if (page == CreationPage.Projects) ProjectPicker(projects, existingPath, query, { query = it }, !creating,
                onDismiss = { pickingProject = false }, onSelect = { selected ->
                    existingPath = selected; createDirectory = false; editingPath = false; pickingProject = false; focus.clearFocus()
                })
            else Column(Modifier.fillMaxSize().background(c.bg).testTag("project-creation")) {
                val entrance = rememberPocketEntrance()
                CreationHeader("新建对话", PocketIcons.Close, !creating, onDismiss)
                Column(Modifier.weight(1f).fillMaxWidth().testTag("project-form-scroll")
                    .scrollEdgeFade({ formScroll.canScrollBackward }, { formScroll.canScrollForward }).verticalScroll(formScroll)
                    .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.pocketEntrance(entrance, 0), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CreationComputer(s.computerName, s.online, Modifier.testTag("project-computer"))
                        CreationDirectorySwitch(createDirectory, !creating) { createDirectory = it; focus.clearFocus() }
                    }
                    // What is asked for depends on the kind of directory; one gives way to the other in place.
                    PSwap(createDirectory, Modifier.fillMaxWidth().pocketEntrance(entrance, 1), Alignment.TopCenter, scale = .97f, slide = .04f,
                        label = "creation-kind") { fresh ->
                        if (fresh) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PTextField(newPath, { newPath = it }, Modifier.testTag("project-path"), label = "新项目保存到",
                                placeholder = "D:\\Projects\\新项目", enabled = !creating, singleLine = true, keyboardOptions = pathKeyboard(),
                                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                                leading = { PIcon(PocketIcons.FolderOpen, null, Modifier.size(18.dp)) })
                            PText("输入电脑上的完整路径，创建时会生成目录。", Modifier.padding(horizontal = 4.dp), style = Pocket.type.bodySmall,
                                color = c.inkMuted)
                            PSection(null) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CreationFolderBadge(true, pulse = newPath.isBlank())
                                    Column(Modifier.weight(1f)) {
                                        PText(if (newPath.isBlank()) "你的新项目" else creationProjectName(newPath), style = Pocket.type.body,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        PText("${s.computerName.ifBlank { "当前电脑" }} · 创建后开始新对话", style = Pocket.type.caption,
                                            color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            PSection("工作目录") {
                                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).graphicsLayer { alpha = if (creating) .5f else 1f }.clip(PocketRadii.control)
                                    .pocketPressable(!creating, pressedScale = .985f) { focus.clearFocus(); pickingProject = true }
                                    .testTag("project-picker-open").padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val name = if (existingPath.isBlank()) "默认目录" else creationProjectName(existingPath)
                                    val place = if (existingPath.isBlank()) "也可以从最近项目中选择" else projectPathLabel(existingPath)
                                    // A choice made with a tap is announced; a path being typed is simply shown as it is typed.
                                    CreationFolderBadge(true, pulse = existingPath.takeUnless { editingPath })
                                    Column(Modifier.weight(1f)) {
                                        if (editingPath) {
                                            PText(name, style = Pocket.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            PText(place, style = Pocket.type.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        } else {
                                            PSwapText(name, style = Pocket.type.body)
                                            PSwapText(place, style = Pocket.type.caption, color = c.inkMuted)
                                        }
                                    }
                                    PIcon(PocketIcons.ChevronRight, null, Modifier.size(18.dp), tint = c.inkMuted)
                                }
                                if (projects.isNotEmpty()) {
                                    PDivider(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), c.line.copy(alpha = .55f))
                                    Row(Modifier.fillMaxWidth()) {
                                        CreationInlineAction(PocketIcons.Search, "所有项目", !creating, Modifier.weight(1f)) {
                                            focus.clearFocus(); pickingProject = true
                                        }
                                        CreationInlineAction(PocketIcons.Edit, if (editingPath) "收起路径" else "输入路径", !creating,
                                            Modifier.weight(1f).testTag("project-manual")) { editingPath = !editingPath }
                                    }
                                }
                            }
                            PSwap(editingPath || projects.isEmpty(), Modifier.fillMaxWidth(), Alignment.TopCenter, scale = .97f, slide = .04f,
                                label = "creation-path") { typed ->
                                if (typed) PTextField(existingPath, { existingPath = it }, Modifier.testTag("project-path"), label = "目录路径",
                                    placeholder = "留空使用默认目录", enabled = !creating, singleLine = true, keyboardOptions = pathKeyboard(),
                                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                                    leading = { PIcon(PocketIcons.FolderOpen, null, Modifier.size(18.dp)) })
                                else PSection("最近使用") {
                                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        projects.take(3).forEach { project ->
                                            CreationChoiceRow(creationProjectName(project), projectPathLabel(project),
                                                nativeProjectPathKey(existingPath) == nativeProjectPathKey(project), !creating,
                                                Modifier.testTag("project-recent:${nativeProjectPathKey(project)}")) { existingPath = project; focus.clearFocus() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    PSection(null, Modifier.pocketEntrance(entrance, 2)) {
                        val taps = rememberPTaps()
                        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).graphicsLayer { alpha = if (creating) .5f else 1f }.clip(PocketRadii.control)
                            .pocketPressable(!creating, pressedScale = .985f, onClick = taps.then { advanced = !advanced })
                            .testTag("project-advanced").padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PTapIcon(PocketIcons.Tune, taps.count, Modifier.size(19.dp))
                            PText(if (advanced) "收起高级选项" else "高级选项", Modifier.weight(1f), style = Pocket.type.body)
                            PSwapText(mode.label, style = Pocket.type.caption, color = c.inkMuted, contentAlignment = Alignment.CenterEnd)
                            val rotation by animateFloatAsState(if (advanced) 180f else 0f, PocketMotion.glyph<Float>().orSnap(motion), label = "advanced-chevron")
                            PIcon(PocketIcons.ExpandMore, null, Modifier.size(17.dp).graphicsLayer { rotationZ = rotation }, tint = c.inkMuted)
                        }
                        // The options open out of the row that names them, inside the same group.
                        Column(Modifier.fillMaxWidth().pocketResize()) {
                            if (advanced) {
                                PDivider(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), c.line.copy(alpha = .55f))
                                if (s.sessionModes.size > 1) Row(Modifier.fillMaxWidth().padding(6.dp).selectableGroup(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    s.sessionModes.forEach { value ->
                                        PocketButton(value.label, { mode = value }, Modifier.weight(1f).testTag("session-mode:${value.wire}")
                                            .semantics { this.selected = mode == value }, enabled = !creating, secondary = mode != value)
                                    }
                                }
                                if (available.isEmpty()) PText(when {
                                    !s.canPrepareRuntime(mode) -> context.getString(R.string.no_runtime)
                                    mode == SessionMode.Cli -> "创建时连接 Codex CLI"
                                    else -> "创建时准备桌面运行环境"
                                }, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = Pocket.type.bodySmall, color = c.inkMuted)
                                else if (available.size > 1) available.forEach { runtime ->
                                    PRow("${context.runtimeLabel(runtime, locationKind)} · ${runtimeVersionLabel(runtime.version)}", { runtimeId = runtime.id },
                                        enabled = !creating, selected = runtimeId == runtime.id, accentSelection = true)
                                }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().pocketResize(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    s.actionStatus["create"]?.phase?.let { PText(it, style = Pocket.type.bodySmall,
                        color = c.inkMuted, modifier = Modifier.padding(horizontal = 12.dp)) }
                    s.actionStatus["create"]?.error?.let {
                        PText(it, color = c.danger, style = Pocket.type.bodySmall,
                            modifier = Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("project-create-error"))
                    }
                    CreationSubmit(if (creating) "正在创建…" else if (createDirectory) "创建项目并开始" else "开始对话", creating, canCreate,
                        Modifier.testTag("project-submit")) {
                        focus.clearFocus()
                        preferences.edit().putString("newCwd", path).apply()
                        runtimeId?.let { preferences.edit().putString("newRuntime", it).apply() }
                        onCreate(mode, path, runtimeId, createDirectory)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectPicker(projects: List<String>, selectedPath: String, query: String, onQuery: (String) -> Unit,
    enabled: Boolean, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val c = Pocket.colors
    val focus = LocalFocusManager.current
    val matches = remember(projects, query) {
        projects.filter { projectPathLabel(it).contains(query.trim(), ignoreCase = true) || it.contains(query.trim(), ignoreCase = true) }
    }
    val selectedKey = nativeProjectPathKey(selectedPath)
    val entrance = rememberPocketEntrance()
    Column(Modifier.fillMaxSize().background(c.bg).testTag("project-picker")) {
        CreationHeader("选择项目", PocketIcons.ArrowBack, enabled, onDismiss)
        PTextField(query, onQuery, Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp).testTag("project-search")
            .semantics { contentDescription = "搜索项目" }, placeholder = "搜索名称或路径", enabled = enabled, singleLine = true,
            keyboardOptions = pathKeyboard(search = true), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            leading = { PIcon(PocketIcons.Search, null, Modifier.size(18.dp)) })
        val projectListState = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().selectableGroup().testTag("project-list")
            .scrollEdgeFade({ projectListState.canScrollBackward }, { projectListState.canScrollForward }), state = projectListState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (query.isBlank()) item(key = "default") {
                CreationChoiceRow("使用默认目录", null, selectedPath.isBlank(), enabled, Modifier.pocketEntrance(entrance, 0),
                    icon = PocketIcons.Computer) { onSelect("") }
            }
            item(key = "heading") {
                PText(if (query.isBlank()) "最近项目 · ${projects.size}" else "搜索结果 · ${matches.size}",
                    Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp).semantics { heading() }, style = Pocket.type.labelSmall, color = c.inkMuted)
            }
            if (matches.isEmpty()) item(key = "empty") {
                PText(if (query.isBlank()) "暂无最近项目，可返回输入目录路径。" else "没有匹配的项目",
                    Modifier.padding(10.dp), style = Pocket.type.body, color = c.inkMuted)
            }
            itemsIndexed(matches, key = { _, path -> "path:$path" }) { index, path ->
                CreationChoiceRow(creationProjectName(path), projectPathLabel(path), selectedKey == nativeProjectPathKey(path), enabled,
                    Modifier.pocketEntrance(entrance, index + 1)) { onSelect(path) }
            }
        }
    }
}

internal fun initialCreationMode(s: PocketState): SessionMode = s.sessionPreferences.defaultMode.let { preferred ->
    val ready = s.sessionModes.filter { s.creationRuntimes(it).isNotEmpty() }
    when {
        preferred in ready -> preferred
        ready.isNotEmpty() -> ready.first()
        s.canPrepareRuntime(preferred) -> preferred
        else -> s.sessionModes.firstOrNull { s.canPrepareRuntime(it) } ?: preferred.takeIf { it in s.sessionModes } ?: s.sessionModes.first()
    }
}

/** Display only; equivalence uses nativeProjectPathKey, while submission keeps the original value. */
internal fun projectPathLabel(path: String): String = when {
    path.startsWith("\\\\?\\UNC\\", ignoreCase = true) -> "\\\\" + path.substring(8)
    path.startsWith("\\\\?\\") && path.length >= 7 && path[4].isLetter() && path[5] == ':' && path[6] == '\\' -> path.substring(4)
    else -> path
}

private fun creationProjectName(path: String): String = projectPathLabel(path).let {
    it.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { it }
}
