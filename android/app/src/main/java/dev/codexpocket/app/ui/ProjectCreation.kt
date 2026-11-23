package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*

/** Presentation state is independent of the advanced section's visibility. */
@Composable
internal fun ProjectCreationForm(
    s: PocketState,
    onDismiss: () -> Unit,
    onCreate: (SessionMode, String, String?, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
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

    val pageProgress by animateFloatAsState(if (pickingProject) 1f else 0f, creationTween(520), label = "project-picker-tide")
    val motion = rememberPocketMotion()
    BackHandler(pickingProject) { pickingProject = false }
    val close = { if (pickingProject) pickingProject = false else onDismiss() }
    PocketPageOverlay("新建对话", close) {
        CreationScene(createDirectory) {
            Column(Modifier.fillMaxSize().testTag("project-creation")
                .then(if (pickingProject) Modifier.clearAndSetSemantics {} else Modifier)) {
                CreationHeader("新建对话", !creating, onDismiss)
                val formScroll=rememberScrollState()
                Column(Modifier.weight(1f).fillMaxWidth().testTag("project-form-scroll").verticalScroll(formScroll)
                    .padding(horizontal = 24.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CreationEmblem(createDirectory)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PText(if (createDirectory) "创建新项目" else "选择一个项目", style = Pocket.type.headline,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                            PText(if (createDirectory) "在电脑上创建目录并开始对话" else "从最近项目开始，或选择其他目录", style = Pocket.type.bodySmall,
                                color = Pocket.colors.inkMuted)
                        }
                    }
                    PocketGlassSurface(Modifier.fillMaxWidth().height(48.dp).testTag("project-computer"), shape = RoundedCornerShape(50)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PIcon(PocketIcons.Computer, null, Modifier.size(17.dp), tint = Pocket.colors.inkMuted)
                            PText(s.computerName.ifBlank { "当前电脑" }, Modifier.weight(1f), style = Pocket.type.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val connectivity by animateColorAsState(if (s.online) Color(0xFF62A88A) else Pocket.colors.danger,
                                PocketMotion.color<Color>().orSnap(motion), label = "project-connectivity")
                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(connectivity))
                            PText(if (s.online) "已连接" else "未连接", style = Pocket.type.labelSmall,
                                color = Pocket.colors.inkMuted)
                        }
                    }
                    CreationDirectorySwitch(createDirectory, !creating) { createDirectory = it; focus.clearFocus() }
                    if (!createDirectory) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CreationSectionTitle("工作目录")
                            val pickerPress = remember { MutableInteractionSource() }
                            PocketGlassSurface(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                                .clickable(pickerPress, indication = null, enabled = !creating, role = Role.Button) { focus.clearFocus(); pickingProject = true }
                                .testTag("project-picker-open"), shape = RoundedCornerShape(28.dp), dense = true, interactionSource = pickerPress) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CreationFolderBadge(true)
                                    Column(Modifier.weight(1f)) {
                                        PText(if (existingPath.isBlank()) "默认目录" else creationProjectName(existingPath),
                                            style = Pocket.type.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        PText(if (existingPath.isBlank()) "也可以从最近项目中选择" else projectPathLabel(existingPath),
                                            style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    PIcon(PocketIcons.ChevronRight, null, Modifier.size(18.dp), tint = Pocket.colors.inkMuted)
                                }
                            }
                            if (projects.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CreationCapsule({ focus.clearFocus(); pickingProject = true }, Modifier.weight(1f), !creating) {
                                        PIcon(PocketIcons.Search, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                                        PText("所有项目", style = Pocket.type.labelSmall)
                                    }
                                    CreationCapsule({ editingPath = !editingPath }, Modifier.weight(1f).testTag("project-manual"), !creating) {
                                        PIcon(PocketIcons.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                                        PText(if (editingPath) "收起路径" else "输入路径", style = Pocket.type.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    if (createDirectory || editingPath || projects.isEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CreationSectionTitle(if (createDirectory) "新项目保存到" else "目录路径")
                            CompactProjectField(path, { if (createDirectory) newPath = it else existingPath = it },
                                Modifier.testTag("project-path"), label = if (createDirectory) "新目录完整路径" else "已有目录路径",
                                placeholder = if (createDirectory) "D:\\Projects\\新项目" else "留空使用默认目录", enabled = !creating)
                            if (createDirectory) {
                                PText("输入电脑上的完整路径，创建时会生成目录。", style = Pocket.type.bodySmall,
                                    color = Pocket.colors.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
                                PocketGlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), dense = true) {
                                    Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CreationFolderBadge(true)
                                        Column(Modifier.weight(1f)) {
                                            PText(if (newPath.isBlank()) "你的新项目" else creationProjectName(newPath), style = Pocket.type.titleSmall,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            PText("${s.computerName.ifBlank { "当前电脑" }} · 创建后开始新对话", style = Pocket.type.bodySmall,
                                                color = Pocket.colors.inkMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!createDirectory && !editingPath && projects.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CreationSectionTitle("最近使用")
                            projects.take(3).forEach { project ->
                                ProjectChoice(project, nativeProjectPathKey(existingPath) == nativeProjectPathKey(project), !creating,
                                    Modifier.testTag("project-recent:${nativeProjectPathKey(project)}")) { existingPath = project; focus.clearFocus() }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CreationCapsule({ advanced = !advanced }, Modifier.fillMaxWidth().testTag("project-advanced"), !creating) {
                            PIcon(PocketIcons.Tune, null, Modifier.size(17.dp)); Spacer(Modifier.width(10.dp))
                            PText(if (advanced) "收起高级选项" else "高级选项", Modifier.weight(1f), style = Pocket.type.labelSmall)
                            PText(mode.label, style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted)
                            val rotation by animateFloatAsState(if (advanced) 180f else 0f, PocketMotion.glyph<Float>().orSnap(motion), label = "advanced-chevron")
                            PIcon(PocketIcons.ExpandMore, null, Modifier.padding(start = 8.dp).size(17.dp).graphicsLayer { rotationZ = rotation })
                        }
                        if (advanced) {
                            if (s.sessionModes.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                                s.sessionModes.forEach { value ->
                                    CreationCapsule({ mode = value }, Modifier.weight(1f).testTag("session-mode:${value.wire}")
                                        .semantics { this.selected = mode == value }, !creating, primary = mode == value) {
                                        PText(value.label, style = Pocket.type.labelSmall)
                                    }
                                }
                            }
                            if (available.isEmpty()) PText(when {
                                !s.canPrepareRuntime(mode) -> context.getString(R.string.no_runtime)
                                mode == SessionMode.Cli -> "创建时连接 Codex CLI"
                                else -> "创建时准备桌面运行环境"
                            }, style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
                            else if (available.size > 1) available.forEach { runtime ->
                                CreationCapsule({ runtimeId = runtime.id }, Modifier.fillMaxWidth(), !creating, primary = runtimeId == runtime.id) {
                                    PText("${context.sourceLabel(runtime.source)} · ${runtimeVersionLabel(runtime.version)}", style = Pocket.type.labelSmall)
                                }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    s.actionStatus["create"]?.phase?.let { PText(it, style = Pocket.type.bodySmall,
                        color = Pocket.colors.inkMuted, modifier = Modifier.padding(horizontal = 12.dp)) }
                    s.actionStatus["create"]?.error?.let {
                        PText(it, color = Pocket.colors.danger, style = Pocket.type.bodySmall,
                            modifier = Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("project-create-error"))
                    }
                    CreationCapsule(onClick = {
                        focus.clearFocus()
                        preferences.edit().putString("newCwd", path).apply()
                        runtimeId?.let { preferences.edit().putString("newRuntime", it).apply() }
                        onCreate(mode, path, runtimeId, createDirectory)
                    }, modifier = Modifier.fillMaxWidth().testTag("project-submit"), enabled = canCreate, primary = true) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            PText(if (creating) "正在创建…" else if (createDirectory) "创建项目并开始" else "开始对话", Modifier.weight(1f),
                                style = Pocket.type.label)
                            // Turns only while the request is in flight; the arrow is back as soon as it ends.
                            if (creating) PLoopGlyph(MotionGlyphs.Spin, true, Modifier.size(20.dp), Pocket.ink, millis = 900)
                            else PIcon(PocketIcons.ArrowForward, null, Modifier.size(20.dp))
                        }
                    }
                }
            }
            if (pickingProject || pageProgress > 0f) {
                // The wave clips pixels, not input ownership: the covered form cannot receive a rapid tap.
                Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .clearAndSetSemantics {})
                Box(Modifier.fillMaxSize().graphicsLayer { shape = CreationTideShape(pageProgress); clip = true }
                    .then(if (!pickingProject) Modifier.clearAndSetSemantics {} else Modifier)) {
                    ProjectPicker(projects, existingPath, query, { query = it }, !creating,
                        onDismiss = { pickingProject = false }, onSelect = { selected ->
                            existingPath = selected; createDirectory = false; editingPath = false; pickingProject = false; focus.clearFocus()
                        })
                }
            }
        }
    }
}

@Composable
private fun CreationHeader(title: String, enabled: Boolean, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PText(title, Modifier.weight(1f).semantics { heading() }, style = Pocket.type.title)
        CreationIconButton(PocketIcons.Close, "关闭", enabled, onClose)
    }
}

@Composable
private fun CreationSectionTitle(title: String) {
    PText(title, Modifier.padding(horizontal = 4.dp), style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted)
}

@Composable
private fun ProjectChoice(path: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Pocket.colors
    val press = remember { MutableInteractionSource() }
    PocketGlassSurface(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .selectable(selected, press, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(28.dp), dense = true, interactionSource = press) {
        Row(Modifier.fillMaxWidth().background(if (selected) c.accent.copy(alpha = .055f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CreationFolderBadge(selected)
            Column(Modifier.weight(1f)) {
                PText(creationProjectName(path), style = Pocket.type.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PText(projectPathLabel(path), style = Pocket.type.bodySmall, color = c.inkMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PIcon(if (selected) PocketIcons.Check else PocketIcons.ChevronRight, null, Modifier.size(16.dp),
                tint = if (selected) c.accent else c.inkMuted)
        }
    }
}

@Composable
private fun CompactProjectField(value: String, onValue: (String) -> Unit, modifier: Modifier,
    label: String, placeholder: String, enabled: Boolean, search: Boolean = false) {
    val c = Pocket.colors
    val focus = LocalFocusManager.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    PocketGlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), dense = true) {
        BasicTextField(value, onValue, modifier.fillMaxWidth().semantics { contentDescription = label },
            enabled = enabled, singleLine = true, textStyle = Pocket.type.body.copy(color = c.ink),
            interactionSource = interactions, cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                imeAction = if (search) ImeAction.Search else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }, onSearch = { focus.clearFocus() }),
            decorationBox = { inner ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .border(1.dp, if (focused) c.accent.copy(alpha = .7f) else Color.Transparent, RoundedCornerShape(28.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PIcon(if (search) PocketIcons.Search else PocketIcons.FolderOpen, null, Modifier.size(18.dp), tint = c.inkMuted)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) PText(placeholder, style = Pocket.type.body, color = c.inkMuted)
                        inner()
                    }
                }
            })
    }
}

@Composable
private fun ProjectPicker(projects: List<String>, selectedPath: String, query: String, onQuery: (String) -> Unit,
    enabled: Boolean, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val matches = remember(projects, query) {
        projects.filter { projectPathLabel(it).contains(query.trim(), ignoreCase = true) || it.contains(query.trim(), ignoreCase = true) }
    }
    val selectedKey = nativeProjectPathKey(selectedPath)
    CreationScene(true) {
        Column(Modifier.fillMaxSize().testTag("project-picker")) {
            CreationHeader("选择项目", enabled, onDismiss)
            Column(Modifier.padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PText("选择工作目录", style = Pocket.type.headline)
                CompactProjectField(query, onQuery, Modifier.testTag("project-search"),
                    label = "搜索项目", placeholder = "搜索名称或路径", enabled = enabled, search = true)
            }
            val projectListState=rememberLazyListState()
            LazyColumn(Modifier.weight(1f).fillMaxWidth().selectableGroup().testTag("project-list"),state=projectListState,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (query.isBlank()) item(key = "default") {
                    CreationCapsule({ onSelect("") }, Modifier.fillMaxWidth().semantics { selected = selectedPath.isBlank() }, enabled) {
                        PIcon(PocketIcons.Computer, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                        PText("使用默认目录", Modifier.weight(1f), style = Pocket.type.body)
                        if (selectedPath.isBlank()) PIcon(PocketIcons.Check, null, Modifier.size(16.dp))
                    }
                }
                item(key = "heading") { CreationSectionTitle(if (query.isBlank()) "最近项目 · ${projects.size}" else "搜索结果 · ${matches.size}") }
                if (matches.isEmpty()) item(key = "empty") {
                    PText(if (query.isBlank()) "暂无最近项目，可返回输入目录路径。" else "没有匹配的项目",
                        Modifier.padding(10.dp), style = Pocket.type.body, color = Pocket.colors.inkMuted)
                }
                items(matches, key = { "path:$it" }) { path ->
                    ProjectChoice(path, selectedKey == nativeProjectPathKey(path), enabled) { onSelect(path) }
                }
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
