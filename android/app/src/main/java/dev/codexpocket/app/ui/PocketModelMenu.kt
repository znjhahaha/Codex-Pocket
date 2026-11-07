@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codexpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*

/** Root coordinates, already reduced by system/IME insets. Never overlaps the input capsule. */
internal fun modelMenuBounds(viewport: Rect, anchor: Rect?, density: Float, widthDp: Float = 300f, alignStart: Boolean = false, below: Boolean = false): Rect {
    val width = minOf(widthDp * density, viewport.width).coerceAtLeast(0f)
    if (below) {
        // Toolbar menus drop down from their button, like a UIMenu from a navigation bar item.
        val top = maxOf(viewport.top, (anchor?.bottom ?: viewport.top) + 6f * density).coerceAtMost(viewport.bottom)
        val right = (anchor?.right ?: viewport.right).coerceIn(viewport.left + width, viewport.right)
        return Rect(right - width, top, right, minOf(viewport.bottom, top + viewport.height * .65f))
    }
    val bottom = minOf(viewport.bottom, (anchor?.top ?: viewport.bottom) - 8f * density).coerceAtLeast(viewport.top)
    val height = minOf(viewport.height * .55f, bottom - viewport.top).coerceAtLeast(0f)
    if (alignStart) {
        val left = (anchor?.left ?: viewport.left).coerceIn(viewport.left, viewport.right - width)
        return Rect(left, bottom - height, left + width, bottom)
    }
    val right = (anchor?.right ?: viewport.right).coerceIn(viewport.left + width, viewport.right)
    return Rect(right - width, bottom - height, right, bottom)
}

internal fun NativeModel.matchesModelQuery(query: String): Boolean = query.trim().let { q ->
    q.isEmpty() || name.contains(q, true) || model.contains(q, true) || id.contains(q, true)
}
internal fun NativeModel.selectionEffort(current: String?): String? = current?.takeIf { it in efforts }
internal fun nativeComposerControlsEnabled(s: PocketState, method: String): Boolean =
    s.paired && s.activeRuntime?.online == true && s.selected != null && canMutate(s, method) && s.timeline.activeTurn == null

/** Kept for source compatibility with existing catalog fixtures; search is now always available. */
internal const val MODEL_SEARCH_THRESHOLD = 6

/** Root-owned anchor binding so ChatScreen callers keep compiling. */
internal data class ComposerMenuBinding(
    val expanded: Boolean = false,
    val onAnchor: (Rect) -> Unit = {},
)
internal val LocalComposerMenuBinding = staticCompositionLocalOf { ComposerMenuBinding() }

/** Catalog-only radio pills. FlowRow wraps on narrow screens and at large font sizes. */
@Composable
internal fun PocketEffortSelector(efforts: List<String>, effort: String?, enabled: Boolean, onEffort: (String?) -> Unit, selectionKnown: Boolean = true) {
    val context = LocalContext.current
    val choices = remember(efforts) { supportedEffortChoices(efforts) }
    if (choices.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().selectableGroup().testTag("composer-effort")
        .semantics { contentDescription = context.getString(R.string.reasoning) },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        choices.forEach { value ->
            EffortPill(if (value == null) context.getString(R.string.default_effort) else context.effortLabel(value),
                selectionKnown && effort == value, enabled, Modifier.testTag("effort-${value ?: "default"}")) { onEffort(value) }
        }
    }
}

@Composable
private fun EffortPill(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) .96f else 1f, pocketPressSpring(), label = "effort-pill")
    Box(modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(PocketCapsule).background(if (selected) effortAccent().copy(alpha = .15f) else c.surfaceContainerHigh)
        .border(1.dp, if (selected) effortAccent() else c.outlineVariant.copy(alpha = .5f), PocketCapsule)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, interactionSource = interaction, indication = null, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = if (!enabled) c.onSurfaceVariant else if (selected) effortAccent() else c.onSurface)
    }
}

@Composable
private fun EffortRow(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp))
        .background(if (pressed) c.surfaceContainerHighest.copy(alpha = .8f) else Color.Transparent)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, interactionSource = interaction, indication = null, onClick = onClick)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled) c.onSurface else c.onSurfaceVariant)
        if (selected) Icon(PocketIcons.Check, null, Modifier.size(17.dp), tint = c.onSurface)
    }
}

/** Codex's effort accent: the one colored element of the model popover. */
@Composable
internal fun effortAccent(): Color = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF6AA2F8) else Color(0xFF4F8EF7)

internal data class ModelFamily(val name: String, val models: List<Pair<NativeModel, String>>)

/**
 * "GPT-6-Astra（anyL）" and "GPT-6-Astra（aggit）" share a family; rows then show only the variant.
 * A family of one keeps its full name and no heading. Catalog order is kept.
 */
internal fun modelFamilies(models: List<NativeModel>): List<ModelFamily> {
    fun split(model: NativeModel): Pair<String, String> {
        val name = model.name.ifBlank { model.model }.trim()
        val open = name.indexOfFirst { it == '(' || it == '（' }
        if (open <= 0 || !(name.endsWith(')') || name.endsWith('）'))) return name to ""
        return name.substring(0, open).trim() to name.substring(open + 1, name.length - 1).trim()
    }
    val parts = models.map { it to split(it) }
    return parts.groupBy { it.second.first }.map { (family, members) ->
        if (members.size == 1) ModelFamily(family, listOf(members[0].first to members[0].first.name.ifBlank { members[0].first.model }))
        else ModelFamily(family, members.map { (model, split) -> model to split.second.ifBlank { "默认" } })
    }
}

@Composable
private fun ModelRow(label: String, model: NativeModel, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) .97f else 1f, pocketPressSpring(), label = "model-row")
    // Codex lists models as plain lines with a trailing check; only a press tints the row.
    val fill by androidx.compose.animation.animateColorAsState(if (pressed) c.surfaceContainerHighest.copy(alpha = .8f) else Color.Transparent,
        androidx.compose.animation.core.tween(pocketMotionMillis(120)), label = "model-row-fill")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("model-row-${model.id}")
        .graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(14.dp)).background(fill)
        .selectable(selected, role = Role.RadioButton, interactionSource = interaction, indication = null, onClick = onClick)
        .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Full native identifiers distinguish providers even when display names are identical.
            val identifier = model.model.takeIf { it != label } ?: model.id.takeIf { it != label }
            if (identifier != null) Text(identifier, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (model.images) Icon(PocketIcons.Image, "支持图片", Modifier.size(15.dp), tint = c.onSurfaceVariant)
        androidx.compose.animation.AnimatedVisibility(selected, enter = androidx.compose.animation.scaleIn(pocketPressSpring()) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
            Icon(PocketIcons.Check, null, Modifier.size(17.dp), tint = c.onSurface)
        }
    }
}

/**
 * Models come first. Effort changes keep this surface mounted; only a model invokes the closing callback.
 */
@Composable
internal fun ModelSheet(s: PocketState, onSelect: (NativeModel, String?) -> Unit, onMode: (String?) -> Unit, onRetry: () -> Unit = {},
    onEffort: ((NativeModel, String?) -> Unit)? = null, onFast: ((Boolean) -> Unit)? = null) {
    val ready = s.online && s.modelCatalogState == ModelCatalogState.Ready && s.models.isNotEmpty()
    val model = remember(s.models, s.model, ready) {
        if (ready && s.model != null) modelMenuSelection(s.models, s.model, NativeModel::model, NativeModel::isDefault) else null
    }
    val scroll = rememberScrollState()
    var browseModels by rememberSaveable(s.selected?.key?.encoded) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(scroll).testTag("model-sheet")) {
        if (model != null && !browseModels) EffortCard(s, model, onEffort) { browseModels = true }
        else {
            if (model != null) PocketMenuItem("返回强度", PocketIcons.ChevronLeft, { browseModels = false })
            ModelsPage(s, onSelect, onRetry, model)
        }
        ModeToggles(s, onMode, onFast)
    }
}

/** Null is Default. An unsupported explicit effort has no selected pill. */
@Composable
private fun EffortCard(s: PocketState, model: NativeModel, onEffort: ((NativeModel, String?) -> Unit)?, onModels: () -> Unit) {
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val explicitEffort = s.effort
    val choices = supportedEffortChoices(model.efforts)
    val effective = explicitEffort ?: model.defaultEffort.takeIf { s.nativeSettings.effort.known && it in choices }
    val selectionKnown = effective != null && effective in choices
    val shown = when {
        explicitEffort != null && explicitEffort !in choices -> "当前：$explicitEffort"
        effective != null -> effective
        else -> "请选择强度"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(PocketCapsule).clickable(onClick = onModels)
            .testTag("model-picker-open").padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if(model.efforts.isEmpty()) "模型" else shown, Modifier.testTag("effort-title"), style = MaterialTheme.typography.labelLarge, color = effortAccent())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(model.name.ifBlank { model.model }, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium,
                    color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(PocketIcons.ChevronRight, null, Modifier.size(14.dp), tint = c.onSurfaceVariant)
            }
        }
        EffortTrack(choices, effective, selectionKnown, onEffort != null && effortEnabled(s), shown) { value ->
            onEffort?.invoke(model, value)
        }
    }
}

@Composable
private fun ModeToggles(s: PocketState, onMode: (String?) -> Unit, onFast: ((Boolean) -> Unit)?) {
    val c = MaterialTheme.colorScheme
    val plan = s.modes.firstOrNull { it.mode == "plan" }.takeIf { s.supports("collaborationMode/list") }
    val hasFast = onFast != null && s.supports("thread/settings/update")
    if (plan == null && !hasFast) return
    HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), color = c.outlineVariant.copy(alpha = .5f))
    if (plan != null) {
        val enabled = nativeComposerControlsEnabled(s, "collaborationMode/list")
        ModeRow("Plan 模式", PocketIcons.Checklist, s.mode == "plan", enabled, Modifier.testTag("composer-plan")) { onMode(if (s.mode == "plan") null else "plan") }
    }
    if (hasFast) {
        val enabled = nativeComposerControlsEnabled(s, "thread/settings/update")
        ModeRow("Fast", PocketIcons.PlayCircle, s.advanced.fastMode, enabled, Modifier.testTag("composer-fast")) { onFast?.invoke(!s.advanced.fastMode) }
    }
    if (s.timeline.activeTurn != null) Text("本轮结束后可切换", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A 40dp toggle line: smaller than a menu item, still a full-width touch target. */
@Composable
private fun ModeRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
        .background(if (pressed) c.surfaceContainerHighest.copy(alpha = .8f) else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Switch, onClick = onClick)
        .padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(17.dp), tint = if (enabled) c.onSurface else c.onSurfaceVariant.copy(alpha = .6f))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (enabled) c.onSurface else c.onSurfaceVariant.copy(alpha = .7f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.graphicsLayer { scaleX = .82f; scaleY = .82f }) { PocketToggle(on, null, enabled = enabled) }
    }
}

private fun effortEnabled(s: PocketState) = s.paired && s.online && s.activeRuntime?.online == true && !s.busy && !s.sendBusy && !s.uncertainSend &&
    s.selected?.conflict == false && s.selected?.archived == false

@Composable
private fun ModelsPage(s: PocketState, onSelect: (NativeModel, String?) -> Unit, onRetry: () -> Unit, selected: NativeModel?) {
    val c = MaterialTheme.colorScheme
    // A single model-first page: no second confirmation or effort-first drill-down.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("选择模型", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
    }
    val ready = s.online && s.modelCatalogState == ModelCatalogState.Ready && s.models.isNotEmpty()
    var query by rememberSaveable(s.selected?.key?.encoded) { mutableStateOf("") }
    if (!ready) {
        val status = if (!s.online) ModelCatalogState.Offline else s.modelCatalogState
        val message = when (status) {
            ModelCatalogState.Idle -> "尚未读取模型目录"
            ModelCatalogState.Loading -> "正在读取模型目录"
            ModelCatalogState.Empty, ModelCatalogState.Ready -> "当前运行时未返回可用模型"
            ModelCatalogState.Unsupported -> "当前运行时不支持模型目录"
            ModelCatalogState.Offline -> "电脑离线，连接后可重新读取模型"
            ModelCatalogState.Failed -> "模型目录读取失败"
        }
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (status == ModelCatalogState.Loading) PocketProgressStatus(message)
            else Text(message, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
        s.modelCatalogError?.takeIf { status == ModelCatalogState.Failed && it.isNotBlank() }?.let {
            Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = c.error)
        }
        if (status != ModelCatalogState.Loading && status != ModelCatalogState.Unsupported)
            PocketMenuItem("重新读取", PocketIcons.Refresh, onRetry, enabled = s.online)
        return
    }
    run {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).heightIn(min = 48.dp).clip(PocketOval)
            .background(c.surfaceContainerHigh.copy(alpha = .8f)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(PocketIcons.Search, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
            BasicTextField(query, { query = it }, Modifier.weight(1f).testTag("model-search").semantics { contentDescription = "搜索模型" },
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface), cursorBrush = SolidColor(c.secondary),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索模型", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant); inner() } })
        }
    }
    val matches = remember(s.models, query) { s.models.filter { it.matchesModelQuery(query) }.distinctBy { it.id to it.model } }
    if (matches.isEmpty()) Text("没有匹配的模型", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }
    // Stable keys and state preserve scroll across effort and mode updates; only a new query resets it.
    if (matches.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = minOf(240, matches.size * 64).dp)
        .selectableGroup().testTag("model-list"), state = listState) {
        items(matches, key = { "${it.id.length}:${it.id}${it.model}" }) { model ->
            ModelRow(model.name.ifBlank { model.model }, model, selected == model) { onSelect(model, model.selectionEffort(s.effort)) }
        }
    }
}

/** Mode UI extension point. Populate only from an explicitly negotiated native catalog. */
@Composable
internal fun NativeModeSelector(s: PocketState, onMode: (String?) -> Unit) {
    val context = LocalContext.current
    if (s.modes.isNotEmpty() && s.supports("collaborationMode/list")) {
        Text(context.getString(R.string.mode), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            EffortRow(context.getString(R.string.native_default), s.mode == null, nativeComposerControlsEnabled(s, "collaborationMode/list"), Modifier) { onMode(null) }
            s.modes.forEach { mode -> EffortRow(context.modeLabel(mode.mode, mode.name), s.mode == mode.mode, nativeComposerControlsEnabled(s, "collaborationMode/list"), Modifier) { onMode(mode.mode) } }
        }
    }
}
