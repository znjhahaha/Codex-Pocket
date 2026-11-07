package dev.codexpocket.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
        return Rect(right - width, top, right, viewport.bottom)
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
    s.paired && s.selected != null && canMutate(s, method) && s.timeline.activeTurn == null

/** Search appears only when a list is long enough to need it. */
internal const val MODEL_SEARCH_THRESHOLD = 6

/** Root-owned anchor binding so ChatScreen callers keep compiling. */
internal data class ComposerMenuBinding(
    val expanded: Boolean = false,
    val onAnchor: (Rect) -> Unit = {},
)
internal val LocalComposerMenuBinding = staticCompositionLocalOf { ComposerMenuBinding() }

/** Vertical radio list: eight effort levels never overflow sideways. */
@Composable
internal fun PocketEffortSelector(efforts: List<String>, effort: String?, enabled: Boolean, onEffort: (String?) -> Unit) {
    val context = LocalContext.current
    if (efforts.isEmpty()) return
    Column(Modifier.fillMaxWidth().selectableGroup().testTag("composer-effort")
        .semantics { contentDescription = context.getString(R.string.reasoning) }) {
        EffortRow(context.getString(R.string.default_effort), effort == null, enabled, Modifier.testTag("effort-default")) { onEffort(null) }
        efforts.distinct().forEach { value ->
            EffortRow(context.effortLabel(value), effort == value, enabled, Modifier.testTag("effort-$value")) { onEffort(value) }
        }
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

private enum class ModelPage { Main, Models }

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
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(PocketCapsule).background(fill)
        .selectable(selected, role = Role.RadioButton, interactionSource = interaction, indication = null, onClick = onClick)
        .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (model.images) Icon(PocketIcons.Image, "支持图片", Modifier.size(15.dp), tint = c.onSurfaceVariant)
        androidx.compose.animation.AnimatedVisibility(selected, enter = androidx.compose.animation.scaleIn(pocketPressSpring()) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
            Icon(PocketIcons.Check, null, Modifier.size(17.dp), tint = c.onSurface)
        }
    }
}

/**
 * Codex model popover, adapted for touch. The first card is the reasoning effort: a large accent label,
 * the model name with a chevron that drills into the model list, and a snapping slider. Plan and Fast follow.
 * Without a usable catalog the popover opens on the list page, which explains the catalog state.
 */
@Composable
internal fun ModelSheet(s: PocketState, onSelect: (NativeModel, String?) -> Unit, onMode: (String?) -> Unit, onRetry: () -> Unit = {},
    onEffort: ((NativeModel, String?) -> Unit)? = null, onFast: ((Boolean) -> Unit)? = null) {
    val ready = s.online && s.modelCatalogState == ModelCatalogState.Ready && s.models.isNotEmpty()
    val model = s.currentModel.takeIf { ready }
    var picking by rememberSaveable { mutableStateOf(false) }
    val page = if (model == null || picking) ModelPage.Models else ModelPage.Main
    AnimatedContent(page, transitionSpec = {
        val forward = targetState == ModelPage.Models
        (slideInHorizontally(tween(pocketMotionMillis(260), easing = FastOutSlowInEasing)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(pocketMotionMillis(180)))) togetherWith
            (slideOutHorizontally(tween(pocketMotionMillis(220), easing = FastOutSlowInEasing)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(pocketMotionMillis(120))))
    }, label = "model-page") { current ->
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            when {
                current == ModelPage.Main && model != null -> {
                    EffortCard(s, model, onEffort) { picking = true }
                    ModeToggles(s, onMode, onFast)
                }
                else -> {
                    ModelsPage(s, onSelect = { picked, effort -> picking = false; onSelect(picked, effort) }, onRetry = onRetry,
                        onBack = if (model != null) ({ picking = false }) else null)
                    if (model == null) ModeToggles(s, onMode, onFast)
                }
            }
        }
    }
}

/** Effort first, as in Codex: "高" in accent, "6 Astra ›" under it, then the slider. */
@Composable
private fun EffortCard(s: PocketState, model: NativeModel, onEffort: ((NativeModel, String?) -> Unit)?, openModels: () -> Unit) {
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val efforts = model.efforts.distinct()
    val shown = s.effort?.takeIf { it in efforts } ?: model.defaultEffort.takeIf { it in efforts }
    // Phone-sized: one header line (effort in accent on the left, model ›  on the right), then a slim slider.
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(top = 2.dp, bottom = 6.dp)) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val tint by androidx.compose.animation.animateColorAsState(if (pressed) c.onSurface else c.onSurfaceVariant, tween(pocketMotionMillis(120)), label = "model-name")
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(PocketCapsule)
            .clickable(role = Role.Button, interactionSource = interaction, indication = null, onClick = openModels)
            .testTag("model-picker-entry").padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(PocketIcons.Sparkle, null, Modifier.size(17.dp), tint = tint)
            Text(model.name.ifBlank { model.model }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(PocketIcons.ChevronRight, "选择模型", Modifier.size(16.dp), tint = tint)
        }
        if (efforts.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("思考强度", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (shown != null) androidx.compose.animation.AnimatedContent(shown, transitionSpec = {
                // Rolls like a counter: up for more effort, down for less.
                val up = efforts.indexOf(targetState) > efforts.indexOf(initialState)
                val spring = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = .7f, stiffness = 600f)
                ((androidx.compose.animation.slideInVertically(spring) { if (up) it else -it } + fadeIn(tween(pocketMotionMillis(140)))) togetherWith
                    (androidx.compose.animation.slideOutVertically(spring) { if (up) -it else it } + fadeOut(tween(pocketMotionMillis(90)))))
                    .using(androidx.compose.animation.SizeTransform(clip = true))
            }, label = "effort-title") { value ->
                Text(context.effortLabel(value), Modifier.testTag("effort-title"), style = MaterialTheme.typography.labelLarge, color = effortAccent())
            }
        }
        if (efforts.size > 1 && onEffort != null) {
            Spacer(Modifier.height(2.dp))
            EffortSlider(efforts, shown, effortEnabled(s)) { onEffort(model, it) }
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
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp))
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
private fun ModelsPage(s: PocketState, onSelect: (NativeModel, String?) -> Unit, onRetry: () -> Unit, onBack: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    // "选择模型" heading; with an effort card behind it, the heading is also the way back.
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp))
        .then(if (onBack != null) Modifier.selectable(false, role = Role.Button, onClick = onBack).testTag("model-list-back") else Modifier)
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onBack != null) Icon(PocketIcons.ChevronLeft, stringResource(R.string.back_conversation), Modifier.size(18.dp), tint = c.onSurfaceVariant)
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
    if (s.models.size > MODEL_SEARCH_THRESHOLD) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).heightIn(min = 40.dp).clip(PocketOval)
            .background(c.surfaceContainerHigh.copy(alpha = .8f)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(PocketIcons.Search, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
            BasicTextField(query, { query = it }, Modifier.weight(1f).testTag("model-search").semantics { contentDescription = "搜索模型" },
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface), cursorBrush = SolidColor(c.secondary),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索模型", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant); inner() } })
        }
    }
    val matches = remember(s.models, query) { s.models.filter { it.matchesModelQuery(query) } }
    if (matches.isEmpty()) Text("没有匹配的模型", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
    // A flat list with full names, the way Codex shows it; the check marks the current one.
    matches.forEach { model -> key(model.id) {
        ModelRow(model.name.ifBlank { model.model }, model, s.currentModel?.id == model.id) { onSelect(model, model.selectionEffort(s.effort)) }
    } }
}

/**
 * Discrete effort slider: one dot per catalog level, an accent track up to the thumb, a white thumb that
 * snaps with a haptic tick. Tap a position or drag. Exposed to accessibility as a stepped range.
 */
@Composable
private fun EffortSlider(efforts: List<String>, value: String?, enabled: Boolean, onEffort: (String) -> Unit) {
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val accent = effortAccent()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val last = efforts.lastIndex
    val committed = efforts.indexOf(value).takeIf { it >= 0 } ?: (last / 2)
    val motion = rememberPocketGlassPreferences().animatorsEnabled
    var dragging by remember { mutableStateOf<Float?>(null) }
    var held by remember { mutableStateOf(false) }
    var preview by remember(committed) { mutableIntStateOf(committed) }
    val target = dragging ?: preview.toFloat()
    // The finger drives the thumb 1:1; on release or tap it springs to the nearest step.
    val position by androidx.compose.animation.core.animateFloatAsState(target,
        if (dragging != null || !motion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = .62f, stiffness = 520f), label = "effort-thumb")
    // A slower follower: the gap between the two is the thumb's speed, used for the liquid stretch.
    val lag by androidx.compose.animation.core.animateFloatAsState(position,
        if (motion) androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 160f) else androidx.compose.animation.core.snap(), label = "effort-lag")
    val press by androidx.compose.animation.core.animateFloatAsState(if (held) 1f else 0f,
        if (motion) androidx.compose.animation.core.spring(dampingRatio = .58f, stiffness = 620f) else androidx.compose.animation.core.snap(), label = "effort-press")
    val track = c.surfaceContainerHighest
    val description = context.getString(R.string.reasoning)
    fun commit(index: Int) {
        if (index != preview) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        preview = index
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 4.dp).testTag("composer-effort")
        .semantics {
            contentDescription = description
            stateDescription = context.effortLabel(efforts[preview])
            progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(preview.toFloat(), 0f..last.toFloat(), last - 1)
            if (enabled) setProgress { requested ->
                val index = requested.roundToInt().coerceIn(0, last); preview = index; onEffort(efforts[index]); true
            }
        }
        .pointerInput(efforts, enabled) {
            if (!enabled) return@pointerInput
            val inset = 9.dp.toPx()
            fun at(x: Float) = ((x - inset) / (size.width - inset * 2) * last).coerceIn(0f, last.toFloat())
            awaitEachGesture {
                val down = awaitFirstDown()
                held = true
                val slop = viewConfiguration.touchSlop
                try {
                    // A tap springs the thumb over; only a real drag pins it to the finger.
                    var moved = false
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            if (!moved && kotlin.math.abs(change.position.x - down.position.x) > slop) moved = true
                            if (moved) { val x = at(change.position.x); dragging = x; commit(x.roundToInt()); change.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!moved) commit(at(down.position.x).roundToInt())
                    onEffort(efforts[preview])
                } finally { dragging = null; held = false }
            }
        }) {
        val height = maxHeight
        val thumb = 18.dp
        // Press: the bar swells and the thumb grows with a soft accent halo, then settles back.
        val bar = 6.dp + 3.dp * press
        val inset = thumb / 2
        val span = maxWidth - inset * 2
        val fraction = if (last == 0) 0f else position / last
        val barTop = (height - bar) / 2
        val fillColor = if (enabled) accent else c.outline
        Box(Modifier.offset(y = barTop).fillMaxWidth().height(bar).clip(PocketCapsule).background(track))
        // Accent fill up to the thumb centre; it brightens toward higher effort.
        Box(Modifier.offset(y = barTop).width(inset + span * fraction).height(bar).clip(PocketCapsule)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(fillColor.copy(alpha = .55f), fillColor))))
        efforts.indices.forEach { index ->
            val x = inset + span * (index.toFloat() / last)
            val dot by androidx.compose.animation.animateColorAsState(
                if (index <= position + .01f) Color.White.copy(alpha = .85f) else c.onSurfaceVariant.copy(alpha = .5f), tween(pocketMotionMillis(120)), label = "effort-dot")
            Box(Modifier.offset(x = x - 1.5.dp, y = height / 2 - 1.5.dp).size(3.dp).background(dot, CircleShape))
        }
        val centre = inset + span * fraction
        // Halo under the thumb while held.
        Box(Modifier.offset(x = centre - 15.dp, y = height / 2 - 15.dp).size(30.dp)
            .graphicsLayer { alpha = press.coerceIn(0f, 1f); scaleX = .6f + .4f * press; scaleY = .6f + .4f * press }
            .background(accent.copy(alpha = .18f), CircleShape))
        // Thumb: stretches along the motion and springs round when it lands; hairline ring, no drop shadow.
        Box(Modifier.offset(x = centre - thumb / 2, y = (height - thumb) / 2).size(thumb)
            .graphicsLayer {
                val speed = ((position - lag) * 1.6f).coerceIn(-.35f, .35f)
                val grow = 1f + .14f * press
                scaleX = grow * (1f + kotlin.math.abs(speed))
                scaleY = grow * (1f - kotlin.math.abs(speed) * .45f)
            }
            .background(Color.White, CircleShape)
            .border(androidx.compose.foundation.BorderStroke(.5.dp, c.outlineVariant), CircleShape))
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
