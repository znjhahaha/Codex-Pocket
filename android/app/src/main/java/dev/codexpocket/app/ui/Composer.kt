@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codexpocket.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch

internal data class ComposerAvailability(val send: Boolean, val steer: Boolean, val attach: Boolean, val stop: Boolean)
internal fun composerAvailability(s: PocketState): ComposerAvailability {
    val chat = s.selected
    val ready = chat != null && s.online && s.activeRuntime?.online == true && !s.busy && !s.sendBusy && !s.uncertainSend && !chat.conflict && !chat.archived
    val content = s.draft?.text?.isNotBlank() == true || s.attachments.isNotEmpty()
    val running = s.timeline.activeTurn != null
    return ComposerAvailability(
        ready && content && s.supports(if (running) "thread/queue/add" else "turn/start"),
        ready && content && running && s.supports("turn/steer"),
        !s.busy && !s.sendBusy,
        chat != null && s.online && running && !chat.conflict && !s.stopping && s.supports("turn/interrupt"),
    )
}

/** Callback-only surface shared by live composition, previews and host interaction tests. */
@Composable
internal fun PocketComposer(text: String, model: String, detail: String, running: Boolean, availability: ComposerAvailability,
    onText: (String) -> Unit, onModels: () -> Unit, onAttach: () -> Unit, onReferences: () -> Unit,
    onSend: () -> Unit, onSteer: () -> Unit, onStop: () -> Unit, referencesEnabled: Boolean,
    attachments: @Composable () -> Unit = {},
    readingHistory: Boolean = false,
    onModelAnchor: ((Rect) -> Unit)? = null,
    modelMenuExpanded: Boolean? = null,
    transcriptScrolling: Boolean = false,
) {
    val menuBinding = LocalComposerMenuBinding.current
    val reportAnchor = onModelAnchor ?: menuBinding.onAnchor
    val menuExpanded = modelMenuExpanded ?: menuBinding.expanded
    val modelChevron by animateFloatAsState(if (menuExpanded) 180f else 0f,
        tween(pocketMotionMillis(140)), label = "model-chevron")
    var expanded by rememberSaveable { mutableStateOf(false) }
    var addMenu by rememberSaveable { mutableStateOf(false) }
    var addAnchor by remember { mutableStateOf<Rect?>(null) }
    var focused by remember { mutableStateOf(false) }
    // The repository owns draft bodies. Saving them again in the Activity Bundle can exceed Binder limits.
    var savedStart by rememberSaveable { mutableIntStateOf(0) }
    var savedEnd by rememberSaveable { mutableIntStateOf(0) }
    val savedSelection = TextRange(savedStart, savedEnd)
    var editor by remember { mutableStateOf(TextFieldValue(text, savedSelection)) }
    var editorMounted by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { editorMounted = false } }
    val inputActive = LocalPocketInputActive.current
    LaunchedEffect(text) {
        if (text != editor.text) editor = editor.copy(text = text,
            selection = TextRange(savedSelection.start.coerceIn(0, text.length), savedSelection.end.coerceIn(0, text.length)), composition = null)
    }
    val updateEditor: (TextFieldValue) -> Unit = { value ->
        val changed = value.text != editor.text
        editor = value
        savedStart = value.selection.start
        savedEnd = value.selection.end
        if (changed) onText(value.text)
    }
    val compact = readingHistory && !focused && text.isEmpty() && !expanded && !running
    // Insets surround the floating surfaces; there is no painted keyboard/navigation mask.
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("composer")
        .then(if (expanded || addMenu) Modifier.clearAndSetSemantics {} else Modifier).imePadding().navigationBarsPadding()
        .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.BottomCenter) {
        val editorLimit = (maxHeight - 172.dp).coerceIn(48.dp, 176.dp)
        val showSteerLabel = maxWidth >= 320.dp
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            val modelDescription = stringResource(R.string.v02_model_selected, model)
            // The model capsule sits outside the box, above its top-right corner. While reading history it slides
            // down behind the box's top edge and folds away; it returns when the input is used or the menu opens.
            val showModel = focused || menuExpanded || (!readingHistory && !transcriptScrolling)
            AnimatedVisibility(showModel, modifier = Modifier.align(Alignment.End),
                enter = fadeIn(tween(pocketMotionMillis(160))) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 520f)) { it / 2 } +
                    expandVertically(tween(pocketMotionMillis(200)), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(pocketMotionMillis(110))) + androidx.compose.animation.slideOutVertically(tween(pocketMotionMillis(180))) { it / 2 } +
                    shrinkVertically(tween(pocketMotionMillis(200)), shrinkTowards = Alignment.Bottom)) {
                Box(Modifier.padding(end = 4.dp, bottom = 6.dp).onGloballyPositioned { reportAnchor(it.boundsInRoot()) }) {
                    ComposerModelChip(model, detail, menuExpanded, modelChevron, modelDescription, onModels)
                }
            }
            PocketGlassSurface(modifier = Modifier.fillMaxWidth().onGloballyPositioned { if (!showModel) reportAnchor(it.boundsInRoot()) }, shape = RoundedCornerShape(28.dp), dense = true) {
                Column(Modifier.animateContentSize(androidx.compose.animation.core.spring(dampingRatio = .9f, stiffness = 520f))) {
                    // Only the drafting area grows/scrolls. The action row stays at the bottom.
                    Column(Modifier.fillMaxWidth().heightIn(max = editorLimit)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 6.dp, bottom = 2.dp)) {
                        attachments()
                        BasicTextField(editor, { value ->
                            if (editorMounted && !expanded && inputActive() && (focused || value.text != editor.text)) updateEditor(value)
                        },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 5.dp)
                                .onFocusChanged { focused = it.isFocused }.testTag("composer-input"),
                            minLines = 1, readOnly = expanded,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                            decorationBox = { inner ->
                                Box {
                                    if (text.isEmpty()) Text(stringResource(R.string.message_codex), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    inner()
                                }
                            })
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 6.dp, end = 7.dp, top = 2.dp, bottom = 7.dp)
                        .testTag("composer-actions"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        // "+" turns into "×" while its menu is open.
                        val addTurn by animateFloatAsState(if (addMenu) 45f else 0f, pocketPressSpring(), label = "composer-add-turn")
                        Box(Modifier.onGloballyPositioned { addAnchor = it.boundsInRoot() }.graphicsLayer { rotationZ = addTurn }) {
                            ActionIcon(PocketIcons.Add, stringResource(R.string.v02_references)) { addMenu = true }
                        }
                        ActionIcon(PocketIcons.OpenInFull, stringResource(R.string.v02_expand_composer)) { expanded = true }
                        Spacer(Modifier.weight(1f))
                        AnimatedVisibility(running, enter = fadeIn(tween(pocketMotionMillis(150))) + androidx.compose.animation.scaleIn(pocketPressSpring(), initialScale = .7f),
                            exit = fadeOut(tween(pocketMotionMillis(100))) + androidx.compose.animation.scaleOut(targetScale = .7f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                val steerLabel = stringResource(R.string.v02_steer)
                                ComposerPill(steerLabel, PocketIcons.SubdirectoryArrowRight, availability.steer, showSteerLabel,
                                    Modifier.testTag("composer-steer").semantics { contentDescription = steerLabel }, onSteer)
                                ComposerStopButton(availability.stop, onStop)
                            }
                        }
                        ComposerSendButton(running, availability.send, onSend)
                    }
                }
            }
            AnimatedVisibility(!compact, modifier = Modifier.align(Alignment.CenterHorizontally),
                enter = fadeIn(tween(pocketMotionMillis(150))) + expandVertically(tween(pocketMotionMillis(200))),
                exit = fadeOut(tween(pocketMotionMillis(100))) + shrinkVertically(tween(pocketMotionMillis(160)))) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(PocketIcons.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.v02_permissions), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (expanded) PocketScreen("编辑消息", { expanded = false }, "草稿会保留，关闭后可继续编辑") {
        val editorFocus = remember { FocusRequester() }
        val expandedInputActive = LocalPocketInputActive.current
        val canFocusEditor = expandedInputActive() && !addMenu
        LaunchedEffect(canFocusEditor) { if (canFocusEditor) editorFocus.requestFocus() }
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                attachments()
                BasicTextField(editor, { value ->
                    if (expanded && !addMenu && expandedInputActive()) updateEditor(value)
                },
                    Modifier.fillMaxWidth().heightIn(min = 80.dp).focusRequester(editorFocus).testTag("composer-expanded-input"),
                    minLines = 6, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary))
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.onGloballyPositioned { addAnchor = it.boundsInRoot() }) {
                    ActionIcon(PocketIcons.Add, stringResource(R.string.v02_references), availability.attach) { addMenu = true }
                }
                ActionIcon(PocketIcons.CloseFullscreen, stringResource(R.string.v02_collapse_composer)) { expanded = false }
                if (running) {
                    PocketTextButton(onSteer, enabled = availability.steer, modifier = Modifier.testTag("composer-steer")) { Text(stringResource(R.string.v02_steer)) }
                    ActionIcon(PocketIcons.StopCircle, stringResource(R.string.stop_turn), availability.stop, onStop)
                }
                ComposerSendButton(running, availability.send, onSend)
            }
        }
    }
    if (addMenu) PocketAnchoredMenu("添加到消息", { addMenu = false }, addAnchor, widthDp = 248f, alignStart = true) {
        PocketMenuItem(stringResource(R.string.v02_attach), PocketIcons.Image, { addMenu = false; onAttach() }, enabled = availability.attach)
        PocketMenuItem(stringResource(R.string.v02_references), PocketIcons.AlternateEmail, { addMenu = false; onReferences() },
            enabled = referencesEnabled, hint = if (referencesEnabled) null else "连接支持引用的 Codex 后可用")
    }
}

/** Model capsule at the input's top-right: model, effort in gray, chevron. Fills a shade darker on press or while open. */
@Composable
private fun ComposerModelChip(model: String, detail: String, open: Boolean, chevron: Float, description: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .95f else 1f, pocketPressSpring(), label = "model-chip-press")
    // Opaque: the capsule now floats over the transcript, outside the glass box.
    val fill by animateColorAsState(if (pressed || open) c.surfaceContainerHighest else c.surfaceContainerHigh,
        tween(pocketMotionMillis(140)), label = "model-chip-fill")
    Row(Modifier.widthIn(max = 240.dp).heightIn(min = 40.dp)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .testTag("composer-model").semantics { contentDescription = description; stateDescription = if (open) "已展开" else "已收起" },
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.height(34.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(PocketCapsule).background(fill)
            .padding(start = 13.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(PocketIcons.Sparkle, null, Modifier.size(13.dp), tint = c.onSurfaceVariant)
            Text(model, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, color = c.onSurface)
            if (detail.isNotBlank()) androidx.compose.animation.AnimatedContent(detail, transitionSpec = {
                fadeIn(tween(pocketMotionMillis(140))) togetherWith fadeOut(tween(pocketMotionMillis(90)))
            }, label = "model-chip-detail") { value -> Text(value, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant) }
            Icon(PocketIcons.KeyboardArrowDown, null, Modifier.size(14.dp).graphicsLayer { rotationZ = chevron }, tint = c.onSurfaceVariant)
        }
    }
}

/** Steer while a turn runs: a small outlined capsule, icon-only on narrow screens. */
@Composable
private fun ComposerPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, showLabel: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .94f else 1f, pocketPressSpring(), label = "composer-pill")
    val fill by animateColorAsState(if (pressed) c.surfaceContainerHighest else c.surfaceContainerHigh.copy(alpha = .75f), tween(pocketMotionMillis(120)), label = "composer-pill-fill")
    val tint = if (enabled) c.onSurface else c.onSurfaceVariant.copy(alpha = .55f)
    Box(modifier.heightIn(min = 44.dp).clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Row(Modifier.height(34.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(PocketCapsule).background(fill)
            .padding(horizontal = if (showLabel) 12.dp else 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            if (showLabel) Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = tint)
        }
    }
}

/** Stop: a dark disc with a white square, the way Codex shows a running turn. */
@Composable
private fun ComposerStopButton(enabled: Boolean, onStop: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .9f else 1f, pocketPressSpring(), label = "composer-stop")
    val label = stringResource(R.string.stop_turn)
    Box(Modifier.size(44.dp).clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onStop)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .background(if (enabled) c.onSurface else c.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(11.dp).background(if (enabled) c.surface else c.onSurfaceVariant, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun ComposerSendButton(running: Boolean, enabled: Boolean, onSend: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, pocketPressSpring(), label = "send-press")
    val color by androidx.compose.animation.animateColorAsState(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(pocketMotionMillis(180)), label = "send-ready")
    // When a draft becomes sendable the button "pops" on an underdamped spring, then settles.
    val ready by animateFloatAsState(if (enabled) 1f else .88f,
        if (pocketMotionMillis(1) == 0) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = .45f, stiffness = 700f), label = "send-pop")
    // Launch: the arrow flies up and out, then a fresh one springs in from below (iMessage-like).
    val launch = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(Modifier.size(48.dp).graphicsLayer { scaleX = ready - press * .06f; scaleY = ready - press * .06f }
        .clip(CircleShape)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = {
            onSend()
            if (pocketMotionMillis(1) != 0) scope.launch {
                launch.snapTo(0f)
                launch.animateTo(1f, tween(160, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                launch.snapTo(-1f)
                launch.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = .6f, stiffness = 600f))
            }
        })
        .testTag("composer-send"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(42.dp).background(color, RoundedCornerShape(21.dp))
            .border(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.secondary else Color.Transparent, RoundedCornerShape(21.dp)), contentAlignment = Alignment.Center) {
            androidx.compose.animation.Crossfade(running, animationSpec = tween(pocketMotionMillis(120)), label = "send-queue-morph") { queue ->
                Icon(if (queue) PocketIcons.PlaylistAdd else PocketIcons.ArrowUpward, stringResource(if (queue) R.string.v02_queue else R.string.send_message),
                    Modifier.size(21.dp).graphicsLayer {
                        val l = launch.value
                        translationY = -press * 3.dp.toPx() - l * 22.dp.toPx()
                        alpha = 1f - kotlin.math.abs(l)
                    }, tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "↳ 引导": text-only, darkens on press, as in Codex's queue row. */
@Composable
private fun QueueSteer(enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint by animateColorAsState(when { !enabled -> c.onSurfaceVariant.copy(alpha = .45f); pressed -> c.onSurface; else -> c.onSurfaceVariant },
        tween(pocketMotionMillis(120)), label = "queue-steer")
    Row(Modifier.heightIn(min = 44.dp).clip(PocketCapsule)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(PocketIcons.SubdirectoryArrowRight, null, Modifier.size(14.dp), tint = tint)
        Text("引导", style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
    }
}

@Composable
private fun QueueIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint by animateColorAsState(when { !enabled -> c.onSurfaceVariant.copy(alpha = .45f); pressed -> c.onSurface; else -> c.onSurfaceVariant },
        tween(pocketMotionMillis(120)), label = "queue-icon")
    Box(Modifier.size(40.dp, 44.dp).clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(17.dp), tint = tint)
    }
}

internal fun queueText(entry: JsonObject) = entry.array("input").mapNotNull { it.asObject().takeIf { part -> part.string("type") == "text" }?.string("text") }.joinToString("\n")
internal fun queueMoveBefore(ids: List<String>, index: Int, direction: Int): String? = when {
    direction < 0 -> ids.getOrNull(index - 1)
    else -> ids.getOrNull(index + 2)
}

/** Rows shown before the queue folds the rest behind "还有 N 条". */
internal const val QUEUE_VISIBLE = 2

/**
 * Queued messages as inline rows above the input, the way Codex desktop lists them, sized for a thumb:
 * one line of text, "引导" (steer it into the running turn now), delete, and "…" for edit and reorder.
 * No separate screen. Long queues fold after [QUEUE_VISIBLE] rows.
 */
@Composable
internal fun NativeQueue(s: PocketState, onEdit: (String, String) -> Unit, onDelete: (String) -> Unit, onMove: (String, String?) -> Unit,
    onSteer: ((String) -> Unit)? = null, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val anchors = remember { mutableStateMapOf<String, Rect>() }
    val entries = s.nativeQueue.filter { it.string("id").isNotBlank() }.distinctBy { it.string("id") }
    if (entries.isEmpty()) return
    val ids = entries.map { it.string("id") }
    val enabled = s.online && !s.busy && !s.sendBusy && !s.uncertainSend && s.selected?.conflict == false && s.selected?.archived == false
    val canSteer = onSteer != null && enabled && s.timeline.activeTurn != null && s.supports("turn/steer")
    val c = MaterialTheme.colorScheme
    val shown = if (expanded || entries.size <= QUEUE_VISIBLE + 1) entries else entries.take(QUEUE_VISIBLE)
    PocketGlassSurface(modifier = modifier.fillMaxWidth().testTag("native-queue"), shape = RoundedCornerShape(20.dp), dense = true) {
        Column(Modifier.animateContentSize(androidx.compose.animation.core.spring(dampingRatio = .9f, stiffness = 520f)).padding(vertical = 2.dp)) {
            shown.forEachIndexed { index, entry -> key(entry.string("id")) {
                val id = entry.string("id")
                if (index > 0) HorizontalDivider(Modifier.padding(start = 40.dp, end = 12.dp), thickness = .5.dp, color = c.outlineVariant.copy(alpha = .5f))
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PocketIcons.PlaylistPlay, null, Modifier.size(15.dp), tint = c.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(queueText(entry).ifBlank { stringResource(R.string.delivery_queue_attachment) }, Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (onSteer != null) QueueSteer(canSteer) { onSteer(id) }
                    QueueIcon(PocketIcons.DeleteOutline, stringResource(R.string.v02_queue_delete), enabled && s.supports("thread/queue/delete")) { onDelete(id) }
                    Box(Modifier.onGloballyPositioned { anchors[id] = it.boundsInRoot() }) {
                        QueueIcon(PocketIcons.MoreHoriz, "更多操作", enabled) { menuFor = id }
                    }
                }
            } }
            if (entries.size > shown.size || (expanded && entries.size > QUEUE_VISIBLE + 1)) {
                HorizontalDivider(Modifier.padding(start = 40.dp, end = 12.dp), thickness = .5.dp, color = c.outlineVariant.copy(alpha = .5f))
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { expanded = !expanded }.padding(start = 40.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (expanded) "收起" else "还有 ${entries.size - shown.size} 条", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }
            }
        }
    }
    val menuIndex = menuFor?.let(ids::indexOf) ?: -1
    entries.getOrNull(menuIndex)?.let { entry ->
        val id = ids[menuIndex]
        val index = menuIndex
        PocketAnchoredMenu("队列消息", { menuFor = null }, anchors[id], widthDp = 200f) {
            PocketMenuItem(stringResource(R.string.v02_queue_edit), PocketIcons.Edit, { menuFor = null; editing = entry }, enabled = enabled && s.supports("thread/queue/update"))
            PocketMenuItem(stringResource(R.string.v02_queue_up), PocketIcons.ArrowUpward, { menuFor = null; onMove(id, queueMoveBefore(ids, index, -1)) },
                enabled = enabled && index > 0 && s.supports("thread/queue/reorder"))
            PocketMenuItem(stringResource(R.string.v02_queue_down), PocketIcons.ArrowDownward, { menuFor = null; onMove(id, queueMoveBefore(ids, index, 1)) },
                enabled = enabled && index < ids.lastIndex && s.supports("thread/queue/reorder"))
        }
    }
    editing?.let { entry ->
        var text by remember(entry) { mutableStateOf(queueText(entry)) }
        PocketDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.v02_queue_edit)) }, text = { PocketTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8) },
            confirmButton = { PocketButton(stringResource(R.string.save), enabled = enabled && text.isNotBlank() && entries.any { it.string("id") == entry.string("id") }, onClick = { onEdit(entry.string("id"), text); editing = null }) },
            dismissButton = { PocketButton(stringResource(R.string.cancel), onClick = { editing = null }, secondary = true) })
    }
}
